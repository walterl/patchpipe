# Patchpipe — Initial Plan

Status: written before any code existed, and kept as a record of the original
design reasoning. The app has since been built; see the README for what
actually shipped.
Source: https://dumbpipe.dev / https://github.com/n0-computer/dumbpipe

## 1. What it does

An Android app that lets the user configure, activate, and manage iroh
dumbpipes directly on the device. Working name: **Patchpipe** — like a patch
panel: plug apps into local ports, route them over iroh. When a pipe is activated, the app spins up
an in-process dumbpipe forwarder:

- **Server mode (local listener, primary use case):** the app listens on a
  configured TCP port on the Android device. Any app on the device connecting
  to `127.0.0.1:<port>` gets its data forwarded over an iroh connection to the
  remote peer that produced the ticket. This is dumbpipe's
  `connect-tcp --addr 127.0.0.1:<port> <ticket>` direction — the phone dials
  out to the remote listener named in the ticket. Example: run an SSH server
  at home, put its ticket in the app, activate, and any Android SSH client
  pointing at `127.0.0.1:2222` reaches the home box.
- **Listener mode (optional, same UX):** the app acts as `listen-tcp
  --host 127.0.0.1:<port>`: it holds an iroh endpoint, produces a ticket,
  and forwards each incoming remote stream to a local TCP service on the
  configured port (e.g. exposing a service that only runs on the phone).

Each saved pipe = { name, mode, local port, ticket (for server mode), secret
key (optional, for a stable endpoint id), notes, enabled flag }. One-tap
activate/deactivate per pipe; several pipes can be active at once.

## 2. Core design decision: embed the library, not the CLI

Dumbpipe is a small CLI wrapper around the `iroh` crate
(`iroh = "1.0"`, `iroh-tickets = "1.0"`, tokio). Its `src/lib.rs` only defines
the ticket type + ALPN; the actual forwarding loops (`listen-tcp`,
`connect-tcp`) are ~100 lines each in `src/main.rs` using plain
`tokio::net::TcpListener`/`TcpStream` and `connection.accept_bi()` /
`connection.open_bi()` copy loops.

So the app embeds a **small Rust "patchpipe-core" crate** that re-implements
those two forwarding loops against iroh 1.0 (fork or copy the ~200 relevant
lines from dumbpipe — MIT/Apache-2.0), and exposes them over UniFFI to
Kotlin. Do NOT shell out to the `dumbpipe` binary: on Android you'd need it
statically linked against bionic, a pty to capture the ticket printed on
stderr, and no clean way to cancel a forwarder mid-stream. In-process gives
structured types, a CancellationToken for clean deactivation, and ticket
values as return values instead of scraped stderr.

## 3. Rust side

### 3.1 Crate: `patchpipe-core` (cdylib + staticlib)

```
patchpipe-core/
├── Cargo.toml
└── src/
    ├── lib.rs          # UniFFI #[uniffi::export] object(s)
    ├── forward.rs      # listen-tcp / connect-tcp forwarding loops (from dumbpipe main.rs)
    └──(tickets.rs)     # parse/print EndpointTicket (iroh-tickets crate)
```

Dependencies (mirroring dumbpipe's Cargo.toml):

```toml
[dependencies]
iroh = { version = "1.0", default-features = false, features = ["tls-ring"] }
iroh-tickets = "1.0"
tokio = { version = "1", features = ["rt-multi-thread", "net", "io-util", "sync", "time", "macros"] }
tokio-util = "0.7"          # CancellationToken
uniffi = { version = "0.29", features = ["clap"] }  # or latest; bindings via build.rs
thiserror / anyhow
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }  # optional, see 3.4
data-encoding = "2"
```

Key `[lib]` + build config:

```toml
[lib]
crate-type = ["cdylib", "staticlib"]   # cdylib → libpatchpipe_core.so for Android
uniffi::generate_scaffolding in build.rs (or the uniffi-cli macro approach)
```

### 3.2 UniFFI surface (keep it tiny and synchronous at the boundary)

```kotlin
// generated Kotlin, conceptually:
class PatchpipeEndpoint {
    constructor(secretKeyHex: String?)   // null → random, persisted by Kotlin layer
    fun nodeId(): String
    fun connectTcpLoopback(ticket: String, listenPort: UInt): ActivePipe   // server mode
    fun listenTcpLoopback(targetPort: UInt): ActivePipe                    // listener mode
    fun shutdown()
}
class ActivePipe {
    fun ticket(): String?      // listener mode only
    fun stop()                 // cancels the tokio task, closes endpoint cleanly
    fun status(): String       // for UI: "connecting" / "direct" / "relay" / closed
}
```

Rust internals:

- One shared tokio `Runtime` created once (multi-thread scheduler), stored in
  a `once_cell::sync::Lazy`. UniFFI async is supported, but simplest robust
  pattern on Android is: exported methods block on `runtime.block_on(...)`
  only for short setup, and `connectTcpLoopback` spawns the accept loop +
  per-connection `tokio::spawn` copy tasks and returns a handle immediately.
- `ActivePipe` holds a `CancellationToken`; `stop()` cancels it, which drops
  the TcpListener and closes the iroh endpoint.
- The local listener binds `127.0.0.1:<port>` (loopback only — never expose
  the port on the LAN; Android's per-app network sandbox plus loopback binding
  is the whole security model. Apps on the device connect via localhost).
- Report connection state (dialing / direct vs relay) back to Kotlin either by
  polling `status()` or via a UniFFI callback interface
  (`onStatusChange(String)`) — callback interfaces are supported by UniFFI
  and are invoked from a Rust thread, so the Kotlin side must hop to the main
  thread itself.

### 3.3 Cross-compilation

- `cargo-ndk` to build `libpatchpipe_core.so` for `arm64-v8a` (primary),
  optionally `armeabi-v7a`, `x86_64` (emulator):
  `cargo ndk -t arm64-v8a -o ./android/app/src/main/jniLibs build --release`
- Targets installed via `rustup target add aarch64-linux-android ...`;
  NDK r26+; `cargo-ndk` handles the linker glue.
- The gradle integration options: the `org.mozilla.rust-android-gradle`
  plugin, a small Gradle task that shells out to `cargo ndk` (simpler,
  recommended), or build once in CI and commit the `.so` files + generated
  Kotlin. `uniffi-bindgen` (from the
  `uniffi` crate, run via `cargo run --bin uniffi-bindgen generate ...`)
  produces the Kotlin bindings + `uniffi.patchpipe_core` native-loading shim.
- Watch out: default `debug = true` etc. — copy dumbpipe's `[profile.release]`
  (`opt-level = "s"`, `lto = true`, `strip = true`) for a small `.so`
  (iroh+quinn+ring links to ~8–12 MB per ABI; strip aggressively).

### 3.4 Android-specific Rust gotchas

- **Tokio runtime vs Android Looper:** iroh's DNS resolver and timers assume a
  working async runtime; a multi-thread tokio runtime spawned from Rust
  threads works fine on Android (this is what working wrappers do). Do NOT
  use `#[tokio::main]` — the app owns the runtime.
- **`Endpoint.bind()` hanging with JNA-based bindings:** the official
  `computer.iroh:iroh` Maven artifact is UniFFI-over-JNA and its async calls
  never complete on Android. Our own JNI-backed UniFFI build (or Gobley) does
  not have this problem — this is the main reason to roll our own thin crate
  instead of consuming the published artifact.
- **DNS:** iroh resolves relay/endpoint-domain names itself via its built-in
  resolver; on
  some devices `/etc/resolv.conf` parsing differs — if DNS fails in tests,
  pass explicit DNS servers via `EndpointOptions`/`dnsServers` (iroh supports
  overriding, e.g. `1.1.1.1`) or use Gobley-generated bindings which wire up
  the Android context for the resolver.
- **Relay servers:** default n0 production relays work out of the box; expose
  a settings toggle for relay mode (Default / Disabled / Custom URLs) for
  self-hosted relay setups (user is self-hosting oriented; `relayModes` are
  part of `EndpointOptions`).
- **Secret keys:** the endpoint's `SecretKey` (32 bytes) determines the
  endpoint id. Generate on first launch, store in Android Keystore-encrypted
  prefs; per-pipe override optional (so a pipe can present a stable id across
  reinstalls, like `IROH_SECRET` does on desktop).
- **Battery/doze:** foreground service needed for long-lived pipes (§4).

## 4. Android side (Kotlin)

- **Min SDK 26 (Android 8)**, target latest; Kotlin + Jetpack Compose UI
  (Material 3). No Compose expertise needed for v1: it's a list + detail form.
- **Architecture:** single-module app is fine for v1.
  - `data/PipeRepository` — Room DB (`pipes` table) or just DataStore JSON
    (a handful of pipes; DataStore + kotlinx-serialization is simpler).
  - `service/PipeForegroundService` — Android **foreground service** with
    `dataSync`/`connectedDevice` type + persistent notification ("N pipes
    active"). Required so the OS doesn't kill the tokio runtime + sockets
    when the app is backgrounded. Handle Android 13+ `POST_NOTIFICATIONS`
    permission, and Android 14's foreground-service-type requirement.
  - `PatchpipeEngine` — singleton owning the `PatchpipeEndpoint` (JNI object) and
    the map of active pipes; survives rotation, destroyed with the service.
- **Permissions:** `INTERNET` (manifest), `FOREGROUND_SERVICE`,
    `FOREGROUND_SERVICE_DATA_SYNC` (or similar), `POST_NOTIFICATIONS` (13+).
  No VPN permissions — this is a TCP loopback forwarder, not a tun device,
  which is exactly what makes it simple.
- **UI (v1):**
  - Pipe list: name, mode icon (←→ vs ←), local port, active status pill,
    tap to toggle.
  - Edit screen: name, mode (Server/Listener), port picker (with
    availability check), ticket paste field (server mode; validate with
    `iroh-tickets` parse and show the remote node id + relay error
    diagnostics), optional static secret key, relay settings.
  - "Share ticket" for listener-mode pipes: copy/share the `dumbpipe://`
    ticket string or the raw `dumbpipe connect-tcp` command for the peer.
  - Diagnostics screen: current connection type (direct/relay), RTT if
    exposed, last error; a "test from local app" hint
    (`adb shell` / ` termux: nc 127.0.0.1 <port>`).

## 5. Build pipeline & repo layout

```
patchpipe/
├── Cargo.toml               # workspace: patchpipe-core
├── patchpipe-core/             # Rust, UniFFI-exported
├── android/
│   ├── app/
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── jniLibs/<abi>/libpatchpipe_core.so   # committed or built by gradle task
│   │       └── ...Kotlin sources
│   └── bindings/            # generated Kotlin from uniffi-bindgen (committed)
├── .github/workflows/ci.yml # build .so per ABI, run uniffi-bindgen, assemble APK
└── README.md
```

- CI: GitHub Actions with `npx`-free rust + Android NDK setup; matrix per ABI
  or `cargo ndk -t arm64-v8a -t x86_64`; commit generated bindings so the
  Kotlin side compiles without Rust locally.
- Alternatives to writing our own forwarding crate:
  - **`iroh-kmp` (`app.azula.iroh:iroh-kmp:0.1.0`)** — a ready-made JNI-backed
    Kotlin-Multiplatform wrapper around iroh 1.0 (built with Gobley). Exposes
    `bind`, `connect`, `accept_bi`, streams etc., so the forwarder loop could
    be written in Kotlin. Trade-off: KMP dependency weight and third-party
    wrapper drift vs ~200 lines of Rust from a canonical source.
  - **Precedent: `futpib/iroh-ssh-android`** — a working, shipped Android app
    bridging a Rust iroh core. It validates the async-call-on-Android pattern
    (2026-09-09, via repo reading):
    - Stack: **Flutter + `flutter_rust_bridge` (FRB)**, not UniFFI. FRB natively
      maps Rust `async fn` to Dart `Future`s, running them on a thread pool
      with its own tokio-style executor — no JNA, no Looper wiring, none of the
      "async never completes" JNA problem. An `#[frb(init)]` fn calls
      `flutter_rust_bridge::setup_default_user_utils_with_log_level(...)` once
      at app start to init logging/executor.
    - Rust side is minimal (`rust/src/api/simple.rs`): thin `pub async fn`
      wrappers (`connect_iroh`, `disconnect_iroh`, `connection_info`,
      returning plain data structs like `IrohConnectionInfo { is_direct,
      is_relay, relay_url, latency_ms }`) over the upstream `iroh-ssh` crate's
      `bridge` module. Clean separation: all iroh complexity stays in Rust;
      the API surface to the UI layer is tiny and synchronous-shaped.
    - Uses `cargo-ndk`-style build via `rust_builder/cargokit` (FRB's gradle
      integration) to produce the `.so` per ABI as part of the Flutter build.
    - Uses a small fork of FRB (`futpib/flutter_rust_bridge`) — only adds a
      log-level parameter, nothing Android-specific; upstream FRB works.
  - **Relevance to this plan:** the async issue is solved either way — UniFFI
    over plain JNI (our recommendation) or FRB (iroh-ssh-android) both work;
    only the JNA-based official artifact is broken. If we were building Flutter
    anyway, FRB would be the lower-friction path. For a native Kotlin app,
    UniFFI + cargo-ndk remains the fit (no Dart runtime to carry). What's
    worth stealing regardless: (1) a `bridge`-style facade module in
    `patchpipe-core` exposing only small async fns returning plain structs, (2)
    exposing connection quality info (direct/relay/latency) from day one for
    the diagnostics UI, (3) the foreground-service pattern for background
    sessions.
  - **Recommendation:** own crate (small, no wrapper drift, we control the
    loop + cancellation semantics); iroh-kmp or an FRB-based hybrid remain
    fallbacks if the UniFFI/JNI toolchain fights us.

## 6. Milestones

1. **Spike (riskiest first):** CLI-less Rust crate that builds for
   `aarch64-linux-android`, loads in a bare Android activity, binds an
   endpoint, prints node id. Proves UniFFI + cargo-ndk + tokio + iroh on
   device. (If the published iroh-kmp artifact works here instead, this
   de-risks the whole plan in an afternoon.)
2. **Forwarding loops:** port `connect-tcp`/`listen-tcp` from dumbpipe
   `main.rs` into `patchpipe-core`, plus ticket parse; unit tests on host (the
   loops are pure tokio, testable without Android).
3. **App skeleton:** Compose list + edit UI, DataStore, foreground service,
   activate one server-mode pipe end-to-end against a desktop `dumbpipe
   listen-tcp`.
4. **Listener mode + ticket sharing**, Keystore-backed secret key storage.
5. **Polish:** diagnostics (direct/relay status callback), multi-pipe
   stability, battery-optimization exemption prompt, release signing +
   F-Droid-friendly build notes (reproducible-builds friendly: committed
   bindings, pinned NDK version).

## 7. Open questions

- Ship on F-Droid? Then the F-Droid packaging of the NDK/ring toolchain and
  reproducible-build story needs attention early.
- Should server-mode pipes auto-reconnect on network change
  (`Endpoint.networkChange()` / re-dial on `EndpointAddr` refresh)? iroh
  handles path migration within a connection, but a dropped connection needs
  app-level re-dial — probably "auto-retry" toggle per pipe.
- Any interest in a `listen-unix`/`connect-unix` equivalent via
  `LocalServerSocket` (Android local sockets)? Probably overkill for v1.
