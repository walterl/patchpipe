use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};

use crate::endpoint::RelayConfig;
use crate::keys::{generate_secret_key, secret_key_hex};
use crate::runtime::RUNTIME;
use crate::session::{start_connect_tcp_async, start_listen_tcp_async};
use crate::{loopback_port_available, parse_ticket, PatchpipeEndpoint};

#[test]
fn parse_ticket_rejects_garbage() {
    let err = parse_ticket("not-a-ticket".into()).unwrap_err();
    assert!(err.to_string().contains("invalid ticket"));
}

#[test]
fn parse_short_endpoint_ticket() {
    let ticket = "endpointadh42hgv7ucmfsyxd2sd4mtejk4yqpyrsdut6evvcj2dchfszpga6aa";
    let info = parse_ticket(ticket.into()).expect("short endpoint ticket should parse");
    assert!(!info.endpoint_id.is_empty());
    assert!(info.relay_urls.is_empty());
}

#[test]
fn generate_and_parse_secret_roundtrip() {
    let hex = crate::generate_secret_key_hex();
    let id = crate::node_id_from_secret_key(hex.clone()).unwrap();
    assert!(!id.is_empty());
    let endpoint = PatchpipeEndpoint::new(Some(hex)).unwrap();
    assert_eq!(endpoint.node_id().unwrap(), id);
}

#[test]
fn loopback_port_zero_is_available() {
    assert!(loopback_port_available(0).unwrap());
}

#[test]
fn forwards_bytes_loopback_without_relay() {
    RUNTIME.block_on(async {
        let echo = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let echo_port = echo.local_addr().unwrap().port();
        tokio::spawn(async move {
            loop {
                let (mut stream, _) = echo.accept().await.unwrap();
                tokio::spawn(async move {
                    let mut buf = [0u8; 64];
                    let n = stream.read(&mut buf).await.unwrap();
                    stream.write_all(&buf[..n]).await.unwrap();
                });
            }
        });

        let listener_key = generate_secret_key();
        let listener = start_listen_tcp_async(listener_key, echo_port, RelayConfig::Disabled)
            .await
            .unwrap();
        let ticket = listener.ticket().expect("listener ticket");
        let info = parse_ticket(ticket.clone()).unwrap();
        assert_eq!(info.endpoint_id, listener.node_id());

        let connect_port = {
            let probe = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            probe.local_addr().unwrap().port()
        };
        let parsed = crate::tickets::parse_endpoint_ticket(&ticket).unwrap();
        let connector = start_connect_tcp_async(
            generate_secret_key(),
            parsed.endpoint_addr().clone(),
            connect_port,
            RelayConfig::Disabled,
        )
        .await
        .unwrap();

        let mut client = None;
        for _ in 0..50 {
            if let Ok(stream) = tokio::net::TcpStream::connect(("127.0.0.1", connect_port)).await {
                client = Some(stream);
                break;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        let mut client = client.expect("connect-tcp loopback should accept");
        client.write_all(b"ping").await.unwrap();
        let mut buf = [0u8; 4];
        tokio::time::timeout(Duration::from_secs(10), client.read_exact(&mut buf))
            .await
            .expect("echo timed out")
            .unwrap();
        assert_eq!(&buf, b"ping");

        connector.stop();
        listener.stop();
        let _ = secret_key_hex(&generate_secret_key());
    });
}

#[test]
fn pipes_without_a_static_key_get_distinct_endpoint_ids() {
    let factory = PatchpipeEndpoint::new(Some(crate::generate_secret_key_hex())).unwrap();
    let pipes: Vec<_> = (0..3)
        .map(|_| {
            factory
                .listen_tcp_loopback(1, RelayConfig::Disabled, None)
                .unwrap()
        })
        .collect();
    let mut ids: Vec<_> = pipes.iter().map(|pipe| pipe.node_id()).collect();
    ids.sort();
    ids.dedup();
    assert_eq!(ids.len(), 3);
    for pipe in &pipes {
        pipe.stop();
    }
}

#[test]
fn static_secret_key_is_used_when_set() {
    let hex = crate::generate_secret_key_hex();
    let expected = crate::node_id_from_secret_key(hex.clone()).unwrap();
    let factory = PatchpipeEndpoint::new(None).unwrap();
    let pipe = factory
        .listen_tcp_loopback(1, RelayConfig::Disabled, Some(hex))
        .unwrap();
    assert_eq!(pipe.node_id(), expected);
    pipe.stop();
}

#[test]
fn three_listening_pipes_all_forward() {
    RUNTIME.block_on(async {
        let mut echoes = Vec::new();
        for _ in 0..3 {
            let echo = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let echo_port = echo.local_addr().unwrap().port();
            tokio::spawn(async move {
                loop {
                    let Ok((mut stream, _)) = echo.accept().await else {
                        break;
                    };
                    tokio::spawn(async move {
                        let mut buf = [0u8; 64];
                        let Ok(n) = stream.read(&mut buf).await else {
                            return;
                        };
                        let _ = stream.write_all(&buf[..n]).await;
                    });
                }
            });
            echoes.push(echo_port);
        }

        let mut listeners = Vec::new();
        for port in echoes {
            let listener =
                start_listen_tcp_async(generate_secret_key(), port, RelayConfig::Disabled)
                    .await
                    .unwrap();
            listeners.push(listener);
        }

        let mut connectors = Vec::new();
        for listener in &listeners {
            let ticket = listener.ticket().expect("listener ticket");
            let connect_port = {
                let probe = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
                probe.local_addr().unwrap().port()
            };
            let parsed = crate::tickets::parse_endpoint_ticket(&ticket).unwrap();
            let connector = start_connect_tcp_async(
                generate_secret_key(),
                parsed.endpoint_addr().clone(),
                connect_port,
                RelayConfig::Disabled,
            )
            .await
            .unwrap();
            connectors.push((connector, connect_port));
        }

        let mut clients = Vec::new();
        for (i, (_, connect_port)) in connectors.iter().enumerate() {
            let mut client = None;
            for _ in 0..50 {
                if let Ok(stream) =
                    tokio::net::TcpStream::connect(("127.0.0.1", *connect_port)).await
                {
                    client = Some(stream);
                    break;
                }
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
            let mut client = client.unwrap_or_else(|| panic!("pipe {i} did not accept"));
            let payload = format!("ping{i}");
            client.write_all(payload.as_bytes()).await.unwrap();
            let mut buf = vec![0u8; payload.len()];
            tokio::time::timeout(Duration::from_secs(10), client.read_exact(&mut buf))
                .await
                .unwrap_or_else(|_| panic!("pipe {i} timed out"))
                .unwrap();
            assert_eq!(buf, payload.as_bytes());
            clients.push(client);
        }
        drop(clients);
        for (connector, _) in &connectors {
            connector.stop();
        }
        for listener in &listeners {
            listener.stop();
        }
    });
}
