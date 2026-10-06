use std::net::{Ipv4Addr, SocketAddr};
use std::sync::{Arc, Mutex};

use iroh::{Endpoint, EndpointAddr, SecretKey};
use iroh_tickets::endpoint::EndpointTicket;
use tokio::net::TcpListener;
use tokio_util::sync::CancellationToken;

use crate::alpn::{ALPN, HANDSHAKE};
use crate::endpoint::{bind_endpoint, wait_online, RelayConfig};
use crate::error::PatchpipeError;
use crate::forward::forward_bidi;
use crate::runtime::RUNTIME;

#[derive(uniffi::Record, Clone, Debug)]
pub struct PipeStatus {
    pub state: String,
    pub last_error: Option<String>,
    pub rtt_ms: Option<u32>,
    pub remote_id: Option<String>,
}

impl Default for PipeStatus {
    fn default() -> Self {
        Self {
            state: "starting".to_string(),
            last_error: None,
            rtt_ms: None,
            remote_id: None,
        }
    }
}

fn status_from_connection(connection: &iroh::endpoint::Connection) -> PipeStatus {
    let paths = connection.paths();
    let selected = paths.iter().find(|p| p.is_selected());
    let state = match selected.as_ref() {
        Some(p) if p.is_ip() => "direct",
        Some(p) if p.is_relay() => "relay",
        Some(_) => "connected",
        None => "connecting",
    };
    let rtt_ms = selected.map(|p| p.rtt().as_millis() as u32);
    PipeStatus {
        state: state.to_string(),
        last_error: None,
        rtt_ms,
        remote_id: Some(connection.remote_id().to_string()),
    }
}

#[derive(uniffi::Object)]
pub struct ActivePipe {
    token: CancellationToken,
    endpoint: Endpoint,
    ticket: Option<String>,
    node_id: String,
    status: Mutex<PipeStatus>,
}

#[uniffi::export]
impl ActivePipe {
    pub fn ticket(&self) -> Option<String> {
        self.ticket.clone()
    }

    pub fn node_id(&self) -> String {
        self.node_id.clone()
    }

    pub fn status(&self) -> PipeStatus {
        self.status.lock().expect("status mutex").clone()
    }

    /// Tells iroh the device network changed. iroh cannot watch routes on Android itself.
    pub fn network_change(&self) {
        let endpoint = self.endpoint.clone();
        RUNTIME.spawn(async move {
            endpoint.network_change().await;
        });
    }

    pub fn stop(&self) {
        self.token.cancel();
        let endpoint = self.endpoint.clone();
        RUNTIME.spawn(async move {
            endpoint.close().await;
        });
        if let Ok(mut status) = self.status.lock() {
            status.state = "closed".to_string();
        }
    }
}

impl ActivePipe {
    fn new(
        token: CancellationToken,
        endpoint: Endpoint,
        ticket: Option<String>,
        status: PipeStatus,
    ) -> Arc<Self> {
        Arc::new(Self {
            token,
            node_id: endpoint.id().to_string(),
            endpoint,
            ticket,
            status: Mutex::new(status),
        })
    }

    fn set_status(self: &Arc<Self>, next: PipeStatus) {
        if let Ok(mut status) = self.status.lock() {
            *status = next;
        }
    }

    fn set_error(self: &Arc<Self>, msg: impl Into<String>) {
        if let Ok(mut status) = self.status.lock() {
            status.state = "error".to_string();
            status.last_error = Some(msg.into());
        }
    }
}

pub fn start_connect_tcp(
    secret_key: SecretKey,
    ticket: &str,
    listen_port: u16,
    relay: RelayConfig,
) -> Result<Arc<ActivePipe>, PatchpipeError> {
    let parsed = crate::tickets::parse_endpoint_ticket(ticket)?;
    let addr = parsed.endpoint_addr().clone();
    RUNTIME.block_on(start_connect_tcp_async(
        secret_key,
        addr,
        listen_port,
        relay,
    ))
}

pub async fn start_connect_tcp_async(
    secret_key: SecretKey,
    addr: EndpointAddr,
    listen_port: u16,
    relay: RelayConfig,
) -> Result<Arc<ActivePipe>, PatchpipeError> {
    let bind_addr = SocketAddr::from((Ipv4Addr::LOCALHOST, listen_port));
    let listener = TcpListener::bind(bind_addr).await.map_err(|e| {
        PatchpipeError::message(format!("failed to bind 127.0.0.1:{listen_port}: {e}"))
    })?;
    let endpoint = bind_endpoint(secret_key, false, &relay).await?;
    wait_online(&endpoint, &relay).await;
    let token = CancellationToken::new();
    let pipe = ActivePipe::new(
        token.clone(),
        endpoint.clone(),
        None,
        PipeStatus {
            state: "listening".to_string(),
            last_error: None,
            rtt_ms: None,
            remote_id: Some(addr.id.to_string()),
        },
    );
    let loop_pipe = pipe.clone();
    tokio::spawn(async move {
        connect_tcp_loop(listener, endpoint, addr, token, loop_pipe).await;
    });
    Ok(pipe)
}

pub fn start_listen_tcp(
    secret_key: SecretKey,
    target_port: u16,
    relay: RelayConfig,
) -> Result<Arc<ActivePipe>, PatchpipeError> {
    RUNTIME.block_on(start_listen_tcp_async(secret_key, target_port, relay))
}

pub async fn start_listen_tcp_async(
    secret_key: SecretKey,
    target_port: u16,
    relay: RelayConfig,
) -> Result<Arc<ActivePipe>, PatchpipeError> {
    let target = SocketAddr::from((Ipv4Addr::LOCALHOST, target_port));
    let endpoint = bind_endpoint(secret_key, true, &relay).await?;
    wait_online(&endpoint, &relay).await;
    let ticket = EndpointTicket::new(endpoint.addr()).to_string();
    let token = CancellationToken::new();
    let pipe = ActivePipe::new(
        token.clone(),
        endpoint.clone(),
        Some(ticket),
        PipeStatus {
            state: "listening".to_string(),
            last_error: None,
            rtt_ms: None,
            remote_id: None,
        },
    );
    let loop_pipe = pipe.clone();
    tokio::spawn(async move {
        listen_tcp_loop(endpoint, target, token, loop_pipe).await;
    });
    Ok(pipe)
}

async fn connect_tcp_loop(
    listener: TcpListener,
    endpoint: Endpoint,
    addr: EndpointAddr,
    token: CancellationToken,
    pipe: Arc<ActivePipe>,
) {
    loop {
        let accepted = tokio::select! {
            _ = token.cancelled() => break,
            next = listener.accept() => next,
        };
        let (tcp_stream, tcp_addr) = match accepted {
            Ok(v) => v,
            Err(e) => {
                tracing::warn!("tcp accept error: {e}");
                pipe.set_error(format!("tcp accept error: {e}"));
                continue;
            }
        };
        tracing::info!("got tcp connection from {tcp_addr}");
        pipe.set_status(PipeStatus {
            state: "connecting".to_string(),
            last_error: None,
            rtt_ms: None,
            remote_id: Some(addr.id.to_string()),
        });
        let endpoint = endpoint.clone();
        let addr = addr.clone();
        let token = token.clone();
        let pipe = pipe.clone();
        tokio::spawn(async move {
            if let Err(e) = handle_tcp_client(tcp_stream, endpoint, addr, token, pipe.clone()).await
            {
                tracing::warn!("error handling tcp client: {e}");
                pipe.set_error(e.to_string());
            }
        });
    }
}

async fn handle_tcp_client(
    tcp_stream: tokio::net::TcpStream,
    endpoint: Endpoint,
    addr: EndpointAddr,
    token: CancellationToken,
    pipe: Arc<ActivePipe>,
) -> Result<(), PatchpipeError> {
    let (tcp_recv, tcp_send) = tcp_stream.into_split();
    let connection = endpoint
        .connect(addr, ALPN)
        .await
        .map_err(|e| PatchpipeError::message(format!("iroh connect failed: {e}")))?;
    pipe.set_status(status_from_connection(&connection));
    let (mut send, recv) = connection
        .open_bi()
        .await
        .map_err(|e| PatchpipeError::message(format!("open_bi failed: {e}")))?;
    send.write_all(&HANDSHAKE)
        .await
        .map_err(|e| PatchpipeError::message(format!("handshake write failed: {e}")))?;
    forward_bidi(tcp_recv, tcp_send, recv, send, token)
        .await
        .map_err(|e| PatchpipeError::message(format!("forward failed: {e}")))?;
    pipe.set_status(PipeStatus {
        state: "listening".to_string(),
        last_error: None,
        rtt_ms: None,
        remote_id: Some(connection.remote_id().to_string()),
    });
    Ok(())
}

async fn listen_tcp_loop(
    endpoint: Endpoint,
    target: SocketAddr,
    token: CancellationToken,
    pipe: Arc<ActivePipe>,
) {
    loop {
        let incoming = tokio::select! {
            _ = token.cancelled() => break,
            incoming = endpoint.accept() => incoming,
        };
        let Some(incoming) = incoming else {
            break;
        };
        let Ok(connecting) = incoming.accept() else {
            tracing::warn!("incoming.accept failed");
            continue;
        };
        let token = token.clone();
        let pipe = pipe.clone();
        tokio::spawn(async move {
            if let Err(e) = handle_endpoint_accept(connecting, target, token, pipe.clone()).await {
                tracing::warn!("error handling incoming stream: {e}");
                pipe.set_error(e.to_string());
            }
        });
    }
}

async fn handle_endpoint_accept(
    accepting: iroh::endpoint::Accepting,
    target: SocketAddr,
    token: CancellationToken,
    pipe: Arc<ActivePipe>,
) -> Result<(), PatchpipeError> {
    let connection = accepting
        .await
        .map_err(|e| PatchpipeError::message(format!("accept failed: {e}")))?;
    pipe.set_status(status_from_connection(&connection));
    let (send, mut recv) = connection
        .accept_bi()
        .await
        .map_err(|e| PatchpipeError::message(format!("accept_bi failed: {e}")))?;
    let mut buf = [0u8; HANDSHAKE.len()];
    recv.read_exact(&mut buf)
        .await
        .map_err(|e| PatchpipeError::message(format!("handshake read failed: {e}")))?;
    if buf != HANDSHAKE {
        return Err(PatchpipeError::message("invalid dumbpipe handshake"));
    }
    let tcp = tokio::net::TcpStream::connect(target)
        .await
        .map_err(|e| PatchpipeError::message(format!("connect to {target} failed: {e}")))?;
    let (read, write) = tcp.into_split();
    forward_bidi(read, write, recv, send, token)
        .await
        .map_err(|e| PatchpipeError::message(format!("forward failed: {e}")))?;
    Ok(())
}

pub fn loopback_port_available(port: u16) -> bool {
    std::net::TcpListener::bind(SocketAddr::from((Ipv4Addr::LOCALHOST, port))).is_ok()
}
