use std::sync::Arc;

use crate::endpoint::RelayConfig;
use crate::error::PatchpipeError;
use crate::keys::{secret_key_from_optional, secret_key_hex as encode_secret};
use crate::session::{start_connect_tcp, start_listen_tcp, ActivePipe};
use crate::tickets::TicketInfo;

uniffi::setup_scaffolding!("patchpipe");

mod alpn;
mod endpoint;
mod error;
mod forward;
mod keys;
mod runtime;
mod session;
mod tickets;

#[cfg(target_os = "android")]
mod android;

/// Factory for pipes. Each pipe gets its own endpoint key unless one is passed in.
#[derive(uniffi::Object)]
pub struct PatchpipeEndpoint {
    secret_key_hex: String,
}

#[uniffi::export]
impl PatchpipeEndpoint {
    #[uniffi::constructor]
    pub fn new(secret_key_hex: Option<String>) -> Result<Arc<Self>, PatchpipeError> {
        let key = secret_key_from_optional(secret_key_hex.as_deref())?;
        Ok(Arc::new(Self {
            secret_key_hex: encode_secret(&key),
        }))
    }

    pub fn node_id(&self) -> Result<String, PatchpipeError> {
        let key = crate::keys::parse_secret_key(&self.secret_key_hex)?;
        Ok(key.public().to_string())
    }

    pub fn secret_key_hex(&self) -> String {
        self.secret_key_hex.clone()
    }

    pub fn connect_tcp_loopback(
        &self,
        ticket: String,
        listen_port: u32,
        relay: RelayConfig,
        secret_key_override: Option<String>,
    ) -> Result<Arc<ActivePipe>, PatchpipeError> {
        let port = to_port(listen_port)?;
        let key = key_for_pipe(secret_key_override.as_deref())?;
        start_connect_tcp(key, &ticket, port, relay)
    }

    pub fn listen_tcp_loopback(
        &self,
        target_port: u32,
        relay: RelayConfig,
        secret_key_override: Option<String>,
    ) -> Result<Arc<ActivePipe>, PatchpipeError> {
        let port = to_port(target_port)?;
        let key = key_for_pipe(secret_key_override.as_deref())?;
        start_listen_tcp(key, port, relay)
    }

    pub fn shutdown(&self) {
        // Pipes own their endpoints; the factory has no shared iroh endpoint to close.
    }
}

#[uniffi::export]
pub fn parse_ticket(ticket: String) -> Result<TicketInfo, PatchpipeError> {
    crate::tickets::describe_ticket(&ticket)
}

#[uniffi::export]
pub fn generate_secret_key_hex() -> String {
    encode_secret(&crate::keys::generate_secret_key())
}

#[uniffi::export]
pub fn node_id_from_secret_key(secret_key_hex: String) -> Result<String, PatchpipeError> {
    Ok(crate::keys::parse_secret_key(&secret_key_hex)?
        .public()
        .to_string())
}

#[uniffi::export]
pub fn loopback_port_available(port: u32) -> Result<bool, PatchpipeError> {
    Ok(crate::session::loopback_port_available(to_port(port)?))
}

/// Key for one pipe.
///
/// An empty override generates a new key. Pipes must not fall back to the
/// factory key: a relay keeps a single registration per endpoint id, so every
/// other pipe sharing that id times out once the registration is replaced.
fn key_for_pipe(override_hex: Option<&str>) -> Result<iroh::SecretKey, PatchpipeError> {
    secret_key_from_optional(override_hex)
}

fn to_port(port: u32) -> Result<u16, PatchpipeError> {
    u16::try_from(port).map_err(|_| PatchpipeError::message("port must be between 0 and 65535"))
}

#[cfg(test)]
mod tests;
