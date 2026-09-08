use std::io;

use tokio::io::{AsyncRead, AsyncWrite};
use tokio_util::sync::CancellationToken;

fn cancel_token<T>(token: CancellationToken) -> impl Fn(T) -> T {
    move |x| {
        token.cancel();
        x
    }
}

async fn copy_to_noq(
    mut from: impl AsyncRead + Unpin,
    mut send: noq::SendStream,
    token: CancellationToken,
) -> io::Result<u64> {
    tokio::select! {
        res = tokio::io::copy(&mut from, &mut send) => {
            let size = res?;
            send.finish()?;
            Ok(size)
        }
        _ = token.cancelled() => {
            send.reset(0u8.into()).ok();
            Err(io::Error::other("cancelled"))
        }
    }
}

async fn copy_from_noq(
    mut recv: noq::RecvStream,
    mut to: impl AsyncWrite + Unpin,
    token: CancellationToken,
) -> io::Result<u64> {
    tokio::select! {
        res = tokio::io::copy(&mut recv, &mut to) => {
            Ok(res?)
        }
        _ = token.cancelled() => {
            recv.stop(0u8.into()).ok();
            Err(io::Error::other("cancelled"))
        }
    }
}

/// Bidirectionally copy between a TCP split and an iroh bidi stream.
///
/// Either side finishing or the parent token cancelling aborts the other copy.
pub async fn forward_bidi(
    from_tcp: impl AsyncRead + Send + Sync + Unpin + 'static,
    to_tcp: impl AsyncWrite + Send + Sync + Unpin + 'static,
    from_iroh: noq::RecvStream,
    to_iroh: noq::SendStream,
    parent: CancellationToken,
) -> io::Result<()> {
    let token1 = parent.child_token();
    let token2 = token1.clone();
    let forward_to_iroh = tokio::spawn(async move {
        copy_to_noq(from_tcp, to_iroh, token1.clone())
            .await
            .map_err(cancel_token(token1))
    });
    let forward_from_iroh = tokio::spawn(async move {
        copy_from_noq(from_iroh, to_tcp, token2.clone())
            .await
            .map_err(cancel_token(token2))
    });
    let _ = forward_from_iroh.await?;
    let _ = forward_to_iroh.await?;
    Ok(())
}
