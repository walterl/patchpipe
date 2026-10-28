# Security policy

Patchpipe routes arbitrary TCP traffic and holds secret keys on the device, so
sensitive reports are welcome and will be handled seriously.

## Reporting a vulnerability

Please do not open a public issue with exploit details. Instead:

- Message Walter on [White Noise](https://whitenoise.chat):
  `npub12ezsf6jx7z0yavsv264qr5sqps9vrne23fsxy0yqnfrqyea0nyqstdnh5z`, or
- open an issue or discussion on GitHub that says you have something sensitive
  but leaves the details out, and a private contact channel will be arranged,
  or
- use **Report a vulnerability** under the **Security** tab.

## What matters most

- Anything that breaks the end-to-end encryption or authenticity of a stream:
  the forwarding loops, ticket and ALPN handling, or key handling in
  `patchpipe-core`.
- Any way for a remote peer or another app on the device to reach further than
  the configured pipe. Local TCP is bound to loopback only, and that promise is
  the app's main security boundary.
- Secret-key or ticket leakage beyond what the README documents. Pipe secret
  keys and exported config files are plain text in app-private storage by
  design; physical access to an unlocked device implies access to them.

## Out of scope

- Bugs in upstream [iroh](https://github.com/n0-computer/iroh),
  [noq](https://github.com/n0-computer/noq), or
  [dumbpipe](https://github.com/n0-computer/dumbpipe) — please report those to
  the relevant project.
- The n0 production relay infrastructure.
- A compromised or rooted device.

## Supported versions

Only the latest published build of Patchpipe is supported.
