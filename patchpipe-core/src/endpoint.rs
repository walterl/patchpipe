use std::time::Duration;

use iroh::{
    dns::DnsResolver,
    endpoint::{presets, Builder},
    Endpoint, RelayMap, RelayMode, RelayUrl, SecretKey,
};
use tokio::time::timeout;

use crate::alpn::ALPN;
use crate::error::PatchpipeError;

const ONLINE_TIMEOUT: Duration = Duration::from_secs(5);

#[derive(uniffi::Enum, Clone, Debug)]
pub enum RelayConfig {
    Default,
    Disabled,
    Custom { urls: Vec<String> },
}

fn dns_resolver() -> DnsResolver {
    #[cfg(target_os = "android")]
    {
        if crate::android::system_dns_ready() {
            return DnsResolver::new();
        }
        tracing::warn!("Android JNI DNS context missing; using 9.9.9.9");
        return DnsResolver::with_nameserver(std::net::SocketAddr::from((
            std::net::Ipv4Addr::new(9, 9, 9, 9),
            53,
        )));
    }
    #[cfg(not(target_os = "android"))]
    {
        DnsResolver::new()
    }
}

pub async fn bind_endpoint(
    secret_key: SecretKey,
    listen: bool,
    relay: &RelayConfig,
) -> Result<Endpoint, PatchpipeError> {
    let alpns = if listen {
        vec![ALPN.to_vec()]
    } else {
        Vec::new()
    };
    let mut builder = Endpoint::builder(presets::N0)
        .secret_key(secret_key)
        .alpns(alpns)
        .dns_resolver(dns_resolver());
    builder = apply_relay(builder, relay)?;
    builder
        .bind()
        .await
        .map_err(|e| PatchpipeError::message(format!("failed to bind iroh endpoint: {e}")))
}

pub async fn wait_online(endpoint: &Endpoint, relay: &RelayConfig) {
    if matches!(relay, RelayConfig::Disabled) {
        return;
    }
    if timeout(ONLINE_TIMEOUT, endpoint.online()).await.is_err() {
        tracing::warn!("endpoint did not come online within {ONLINE_TIMEOUT:?}");
    }
}

fn apply_relay(builder: Builder, relay: &RelayConfig) -> Result<Builder, PatchpipeError> {
    match relay {
        RelayConfig::Default => Ok(builder),
        RelayConfig::Disabled => Ok(builder.relay_mode(RelayMode::Disabled)),
        RelayConfig::Custom { urls } => {
            if urls.is_empty() {
                return Err(PatchpipeError::message(
                    "custom relay mode requires at least one URL",
                ));
            }
            let mut parsed = Vec::new();
            for url in urls {
                parsed.push(url.parse::<RelayUrl>().map_err(|e| {
                    PatchpipeError::message(format!("invalid relay URL {url}: {e}"))
                })?);
            }
            Ok(builder.relay_mode(RelayMode::Custom(RelayMap::from_iter(parsed))))
        }
    }
}
