#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
cargo build -p patchpipe-core
cargo run -p patchpipe-core --features bindgen --bin uniffi-bindgen -- generate \
  --library --language kotlin --out-dir android/bindings --no-format \
  target/debug/libpatchpipe_core.so
