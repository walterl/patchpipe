//! ALPN and handshake copied from dumbpipe (MIT OR Apache-2.0).
//!
//! The connecting side that calls `open_bi()` must send the handshake; the
//! accepting side that calls `accept_bi()` must consume and verify it.

pub const ALPN: &[u8] = b"DUMBPIPEV0";
pub const HANDSHAKE: [u8; 5] = *b"hello";
