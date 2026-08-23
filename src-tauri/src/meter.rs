//! A counting TCP relay placed in front of the tunnel.
//!
//! `slipstream-client` reports no traffic figures, so the app owns the port the
//! user configures and forwards to the tunnel's own listener on loopback. Every
//! byte therefore passes through here and the statistics shown in the UI are
//! measured rather than estimated. It also gives the app a place to hang
//! per-connection behaviour later, when routing moves to a TUN device.

use std::io;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio_util::sync::CancellationToken;

#[derive(Debug, Default)]
pub struct Counters {
    pub bytes_up: AtomicU64,
    pub bytes_down: AtomicU64,
    pub active: AtomicU64,
}

impl Counters {
    pub fn snapshot(&self) -> (u64, u64, u64) {
        (
            self.bytes_up.load(Ordering::Relaxed),
            self.bytes_down.load(Ordering::Relaxed),
            self.active.load(Ordering::Relaxed),
        )
    }
}

/// Binds the user-facing port. Returned early so a port clash is reported as a
/// connection failure instead of surfacing later as silence.
pub async fn bind(port: u16) -> io::Result<TcpListener> {
    TcpListener::bind(SocketAddr::from(([127, 0, 0, 1], port))).await
}

pub async fn serve(
    listener: TcpListener,
    upstream: SocketAddr,
    counters: Arc<Counters>,
    cancel: CancellationToken,
) {
    loop {
        let accepted = tokio::select! {
            _ = cancel.cancelled() => return,
            result = listener.accept() => result,
        };

        let Ok((inbound, _)) = accepted else {
            // A failed accept is transient; the listener stays usable.
            continue;
        };

        let counters = counters.clone();
        let cancel = cancel.clone();
        tokio::spawn(async move {
            counters.active.fetch_add(1, Ordering::Relaxed);
            let _ = pump(inbound, upstream, &counters, cancel).await;
            counters.active.fetch_sub(1, Ordering::Relaxed);
        });
    }
}

async fn pump(
    mut inbound: TcpStream,
    upstream: SocketAddr,
    counters: &Counters,
    cancel: CancellationToken,
) -> io::Result<()> {
    let mut outbound = tokio::select! {
        _ = cancel.cancelled() => return Ok(()),
        stream = TcpStream::connect(upstream) => stream?,
    };

    let _ = inbound.set_nodelay(true);
    let _ = outbound.set_nodelay(true);

    let (mut ci, mut co) = inbound.split();
    let (mut ui, mut uo) = outbound.split();

    let up = copy_counting(&mut ci, &mut uo, &counters.bytes_up);
    let down = copy_counting(&mut ui, &mut co, &counters.bytes_down);

    tokio::select! {
        _ = cancel.cancelled() => Ok(()),
        result = async {
            // Either direction closing ends the pair, matching how a proxy hop
            // behaves when one side hangs up.
            tokio::select! {
                r = up => r,
                r = down => r,
            }
        } => result.map(|_| ()),
    }
}

async fn copy_counting<R, W>(reader: &mut R, writer: &mut W, counter: &AtomicU64) -> io::Result<u64>
where
    R: AsyncReadExt + Unpin,
    W: AsyncWriteExt + Unpin,
{
    let mut buf = vec![0u8; 16 * 1024];
    let mut total = 0u64;
    loop {
        let read = reader.read(&mut buf).await?;
        if read == 0 {
            let _ = writer.shutdown().await;
            return Ok(total);
        }
        writer.write_all(&buf[..read]).await?;
        total += read as u64;
        counter.fetch_add(read as u64, Ordering::Relaxed);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn relays_both_directions_and_counts_every_byte() {
        // Echo server standing in for the tunnel's listener.
        let echo = TcpListener::bind(SocketAddr::from(([127, 0, 0, 1], 0)))
            .await
            .unwrap();
        let echo_addr = echo.local_addr().unwrap();
        tokio::spawn(async move {
            let (mut sock, _) = echo.accept().await.unwrap();
            let (mut r, mut w) = sock.split();
            let _ = tokio::io::copy(&mut r, &mut w).await;
        });

        let listener = bind(0).await.unwrap();
        let front = listener.local_addr().unwrap();
        let counters = Arc::new(Counters::default());
        let cancel = CancellationToken::new();
        tokio::spawn(serve(listener, echo_addr, counters.clone(), cancel.clone()));

        let mut client = TcpStream::connect(front).await.unwrap();
        client.write_all(b"hello tunnel").await.unwrap();
        let mut got = [0u8; 12];
        client.read_exact(&mut got).await.unwrap();
        assert_eq!(&got, b"hello tunnel");

        let (up, down, _) = counters.snapshot();
        assert_eq!(up, 12);
        assert_eq!(down, 12);
        cancel.cancel();
    }

    #[tokio::test]
    async fn reports_a_port_already_in_use() {
        let held = bind(0).await.unwrap();
        let port = held.local_addr().unwrap().port();
        assert!(bind(port).await.is_err());
    }
}
