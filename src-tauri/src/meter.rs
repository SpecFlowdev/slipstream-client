//! A counting TCP relay placed in front of the tunnel.
//!
//! `slipstream-client` reports no traffic figures, so the app owns the port the
//! user configures and forwards to the tunnel's own listener on loopback. Every
//! byte therefore passes through here and the statistics shown in the UI are
//! measured rather than estimated. It also gives the app a place to hang
//! per-connection behaviour later, when routing moves to a TUN device.
//!
//! It is also where the kill switch lives. This app is a local SOCKS5 proxy in
//! front of the tunnel, not a system-wide VPN, so "kill switch" here means:
//! while the tunnel is not in the Connected state, this relay refuses new
//! connections outright and drops connections already in flight, rather than
//! letting traffic sit on a socket that looks open but goes nowhere.

use std::io;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, RwLock};

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::watch;
use tokio_util::sync::CancellationToken;

use crate::rules::{Action, RuleSet};
use crate::traffic::{ConnCounters, Registry, Socks5Sniffer};

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

#[allow(clippy::too_many_arguments)]
pub async fn serve(
    listener: TcpListener,
    upstream: SocketAddr,
    counters: Arc<Counters>,
    cancel: CancellationToken,
    connected: watch::Receiver<bool>,
    kill_switch: Arc<AtomicBool>,
    registry: Arc<Registry>,
    rules: Arc<RwLock<RuleSet>>,
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

        if kill_switch.load(Ordering::Relaxed) && !*connected.borrow() {
            // Refuse outright rather than let the app hang waiting on a
            // connection that will never carry traffic.
            drop(inbound);
            continue;
        }

        let counters = counters.clone();
        let cancel = cancel.clone();
        let connected = connected.clone();
        let kill_switch = kill_switch.clone();
        let registry = registry.clone();
        let rules = rules.clone();
        tokio::spawn(async move {
            counters.active.fetch_add(1, Ordering::Relaxed);
            let own = Arc::new(ConnCounters::default());
            let id = registry.open(own.clone());
            let _ = pump(
                inbound,
                upstream,
                &counters,
                cancel,
                connected,
                kill_switch,
                &registry,
                id,
                &own,
                &rules,
            )
            .await;
            registry.close(id);
            counters.active.fetch_sub(1, Ordering::Relaxed);
        });
    }
}

#[allow(clippy::too_many_arguments)]
async fn pump(
    mut inbound: TcpStream,
    upstream: SocketAddr,
    counters: &Counters,
    cancel: CancellationToken,
    mut connected: watch::Receiver<bool>,
    kill_switch: Arc<AtomicBool>,
    registry: &Registry,
    id: u64,
    own: &ConnCounters,
    rules: &RwLock<RuleSet>,
) -> io::Result<()> {
    let mut outbound = tokio::select! {
        _ = cancel.cancelled() => return Ok(()),
        stream = TcpStream::connect(upstream) => stream?,
    };

    let _ = inbound.set_nodelay(true);
    let _ = outbound.set_nodelay(true);

    let (mut ci, mut co) = inbound.split();
    let (mut ui, mut uo) = outbound.split();

    // Only the application-to-tunnel direction carries the SOCKS5 request, so
    // that is the only side worth reading for a destination.
    let up = copy_up(
        &mut ci,
        &mut uo,
        &counters.bytes_up,
        &own.up,
        registry,
        id,
        rules,
    );
    let down = copy_counting(&mut ui, &mut co, &counters.bytes_down, &own.down);

    // Resolves once the tunnel drops while this connection is enabled for the
    // kill switch, so an in-flight transfer gets cut rather than left hanging.
    let dropped = async {
        loop {
            if kill_switch.load(Ordering::Relaxed) && !*connected.borrow() {
                return;
            }
            if connected.changed().await.is_err() {
                // The sender side is gone (tunnel torn down); nothing left to watch.
                std::future::pending::<()>().await;
            }
        }
    };

    tokio::select! {
        _ = cancel.cancelled() => Ok(()),
        _ = dropped => Ok(()),
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

async fn copy_counting<R, W>(
    reader: &mut R,
    writer: &mut W,
    counter: &AtomicU64,
    own: &AtomicU64,
) -> io::Result<u64>
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
        own.fetch_add(read as u64, Ordering::Relaxed);
    }
}

/// As `copy_counting`, but also reads the passing bytes for a SOCKS5 request
/// so the connection can be attributed to a destination. The bytes are
/// forwarded exactly as they arrived — the sniffer only looks.
async fn copy_up<R, W>(
    reader: &mut R,
    writer: &mut W,
    counter: &AtomicU64,
    own: &AtomicU64,
    registry: &Registry,
    id: u64,
    rules: &RwLock<RuleSet>,
) -> io::Result<u64>
where
    R: AsyncReadExt + Unpin,
    W: AsyncWriteExt + Unpin,
{
    let mut buf = vec![0u8; 16 * 1024];
    let mut total = 0u64;
    let mut sniffer = Socks5Sniffer::default();
    loop {
        let read = reader.read(&mut buf).await?;
        if read == 0 {
            let _ = writer.shutdown().await;
            return Ok(total);
        }
        if let Some(target) = sniffer.feed(&buf[..read]) {
            // The destination is known for the first time here, which is the
            // one moment a rule can still act: the greeting has gone upstream
            // but no payload has. Refusing means ending the connection, which
            // propagates out of pump and closes both halves.
            let blocked = {
                let guard = rules.read().unwrap();
                if guard.decide(&target.host) == Action::Block {
                    guard.refuse();
                    true
                } else {
                    false
                }
            };
            registry.name(id, target);
            if blocked {
                registry.mark_blocked(id);
                return Err(io::Error::new(
                    io::ErrorKind::PermissionDenied,
                    "destination blocked by a routing rule",
                ));
            }
        }
        writer.write_all(&buf[..read]).await?;
        total += read as u64;
        counter.fetch_add(read as u64, Ordering::Relaxed);
        own.fetch_add(read as u64, Ordering::Relaxed);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    async fn echo_server() -> SocketAddr {
        let echo = TcpListener::bind(SocketAddr::from(([127, 0, 0, 1], 0)))
            .await
            .unwrap();
        let addr = echo.local_addr().unwrap();
        tokio::spawn(async move {
            loop {
                let Ok((mut sock, _)) = echo.accept().await else {
                    return;
                };
                tokio::spawn(async move {
                    let (mut r, mut w) = sock.split();
                    let _ = tokio::io::copy(&mut r, &mut w).await;
                });
            }
        });
        addr
    }

    #[tokio::test]
    async fn relays_both_directions_and_counts_every_byte() {
        let echo_addr = echo_server().await;

        let listener = bind(0).await.unwrap();
        let front = listener.local_addr().unwrap();
        let counters = Arc::new(Counters::default());
        let cancel = CancellationToken::new();
        let (_tx, rx) = watch::channel(true);
        let kill_switch = Arc::new(AtomicBool::new(true));
        tokio::spawn(serve(
            listener,
            echo_addr,
            counters.clone(),
            cancel.clone(),
            rx,
            kill_switch,
            Arc::new(Registry::default()),
            Arc::new(RwLock::new(RuleSet::default())),
        ));

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
    async fn attributes_a_connection_to_the_host_named_in_its_socks5_request() {
        let echo_addr = echo_server().await;
        let listener = bind(0).await.unwrap();
        let front = listener.local_addr().unwrap();
        let counters = Arc::new(Counters::default());
        let cancel = CancellationToken::new();
        let (_tx, rx) = watch::channel(true);
        let registry = Arc::new(Registry::default());
        tokio::spawn(serve(
            listener,
            echo_addr,
            counters.clone(),
            cancel.clone(),
            rx,
            Arc::new(AtomicBool::new(true)),
            registry.clone(),
            Arc::new(RwLock::new(RuleSet::default())),
        ));

        // A real SOCKS5 opening: greeting, then a connect request for a name.
        let mut handshake = vec![0x05, 0x01, 0x00, 0x05, 0x01, 0x00, 0x03];
        let host = b"stats.example.com";
        handshake.push(host.len() as u8);
        handshake.extend_from_slice(host);
        handshake.extend_from_slice(&443u16.to_be_bytes());

        let mut client = TcpStream::connect(front).await.unwrap();
        client.write_all(&handshake).await.unwrap();

        // The echo server sends it straight back, which also proves the relay
        // forwarded the handshake untouched rather than swallowing it.
        let mut echoed = vec![0u8; handshake.len()];
        client.read_exact(&mut echoed).await.unwrap();
        assert_eq!(echoed, handshake, "the sniffer must not alter the stream");

        let snapshot = registry.snapshot();
        assert_eq!(snapshot.connections.len(), 1);
        assert_eq!(snapshot.connections[0].host, "stats.example.com");
        assert_eq!(snapshot.connections[0].port, 443);
        assert_eq!(snapshot.top_hosts[0].host, "stats.example.com");
        cancel.cancel();
    }

    #[tokio::test]
    async fn a_routing_rule_refuses_the_connection_before_any_payload_moves() {
        // The echo server stands in for the tunnel. If the rule works, the
        // client's payload never comes back, because the relay closed the
        // connection the moment it read the destination.
        let echo_addr = echo_server().await;
        let listener = bind(0).await.unwrap();
        let front = listener.local_addr().unwrap();
        let counters = Arc::new(Counters::default());
        let cancel = CancellationToken::new();
        let (_tx, rx) = watch::channel(true);
        let registry = Arc::new(Registry::default());
        let rules = Arc::new(RwLock::new(RuleSet::new(vec![crate::rules::Rule {
            pattern: "*.blocked.example".into(),
            action: Action::Block,
            enabled: true,
            note: String::new(),
        }])));
        tokio::spawn(serve(
            listener,
            echo_addr,
            counters.clone(),
            cancel.clone(),
            rx,
            Arc::new(AtomicBool::new(true)),
            registry.clone(),
            rules.clone(),
        ));

        let mut handshake = vec![0x05, 0x01, 0x00, 0x05, 0x01, 0x00, 0x03];
        let host = b"ads.blocked.example";
        handshake.push(host.len() as u8);
        handshake.extend_from_slice(host);
        handshake.extend_from_slice(&443u16.to_be_bytes());

        let mut client = TcpStream::connect(front).await.unwrap();
        client.write_all(&handshake).await.unwrap();

        // Blocked: the socket closes rather than echoing anything back.
        let mut buf = [0u8; 64];
        let read = tokio::time::timeout(Duration::from_secs(2), client.read(&mut buf))
            .await
            .expect("a blocked connection must close promptly")
            .unwrap();
        assert_eq!(read, 0, "expected EOF, not echoed data, for a blocked host");
        assert_eq!(rules.read().unwrap().blocked_count(), 1);
        cancel.cancel();
    }

    #[tokio::test]
    async fn a_host_no_rule_matches_still_passes_through() {
        let echo_addr = echo_server().await;
        let listener = bind(0).await.unwrap();
        let front = listener.local_addr().unwrap();
        let counters = Arc::new(Counters::default());
        let cancel = CancellationToken::new();
        let (_tx, rx) = watch::channel(true);
        let rules = Arc::new(RwLock::new(RuleSet::new(vec![crate::rules::Rule {
            pattern: "*.blocked.example".into(),
            action: Action::Block,
            enabled: true,
            note: String::new(),
        }])));
        tokio::spawn(serve(
            listener,
            echo_addr,
            counters.clone(),
            cancel.clone(),
            rx,
            Arc::new(AtomicBool::new(true)),
            Arc::new(Registry::default()),
            rules.clone(),
        ));

        let mut handshake = vec![0x05, 0x01, 0x00, 0x05, 0x01, 0x00, 0x03];
        let host = b"allowed.example";
        handshake.push(host.len() as u8);
        handshake.extend_from_slice(host);
        handshake.extend_from_slice(&443u16.to_be_bytes());

        let mut client = TcpStream::connect(front).await.unwrap();
        client.write_all(&handshake).await.unwrap();
        let mut echoed = vec![0u8; handshake.len()];
        client.read_exact(&mut echoed).await.unwrap();
        assert_eq!(echoed, handshake, "an allowed host must flow untouched");
        assert_eq!(rules.read().unwrap().blocked_count(), 0);
        cancel.cancel();
    }

    #[tokio::test]
    async fn reports_a_port_already_in_use() {
        let held = bind(0).await.unwrap();
        let port = held.local_addr().unwrap().port();
        assert!(bind(port).await.is_err());
    }

    #[tokio::test]
    async fn kill_switch_refuses_new_connections_while_disconnected() {
        let echo_addr = echo_server().await;
        let listener = bind(0).await.unwrap();
        let front = listener.local_addr().unwrap();
        let counters = Arc::new(Counters::default());
        let cancel = CancellationToken::new();
        let (_tx, rx) = watch::channel(false); // tunnel never reached Connected
        let kill_switch = Arc::new(AtomicBool::new(true));
        tokio::spawn(serve(
            listener,
            echo_addr,
            counters.clone(),
            cancel.clone(),
            rx,
            kill_switch,
            Arc::new(Registry::default()),
            Arc::new(RwLock::new(RuleSet::default())),
        ));

        let mut client = TcpStream::connect(front).await.unwrap();
        // The relay accepted the TCP connection (that part is unavoidable) but
        // must close it immediately without ever reaching the echo server.
        let mut buf = [0u8; 1];
        let read = tokio::time::timeout(Duration::from_secs(2), client.read(&mut buf))
            .await
            .expect("kill switch should close the socket promptly")
            .unwrap();
        assert_eq!(read, 0, "expected EOF, not data, from a refused connection");

        let (_, _, active) = counters.snapshot();
        assert_eq!(active, 0, "a refused connection must not count as active");
        cancel.cancel();
    }

    #[tokio::test]
    async fn kill_switch_disabled_lets_traffic_through_even_when_disconnected() {
        let echo_addr = echo_server().await;
        let listener = bind(0).await.unwrap();
        let front = listener.local_addr().unwrap();
        let counters = Arc::new(Counters::default());
        let cancel = CancellationToken::new();
        let (_tx, rx) = watch::channel(false);
        let kill_switch = Arc::new(AtomicBool::new(false)); // explicitly off
        tokio::spawn(serve(
            listener,
            echo_addr,
            counters.clone(),
            cancel.clone(),
            rx,
            kill_switch,
            Arc::new(Registry::default()),
            Arc::new(RwLock::new(RuleSet::default())),
        ));

        let mut client = TcpStream::connect(front).await.unwrap();
        client.write_all(b"still flows").await.unwrap();
        let mut got = [0u8; 11];
        client.read_exact(&mut got).await.unwrap();
        assert_eq!(&got, b"still flows");
        cancel.cancel();
    }

    #[tokio::test]
    async fn kill_switch_cuts_an_active_transfer_when_the_tunnel_drops() {
        // A slow server: accepts and then never speaks, so the connection stays
        // open long enough to observe the abort.
        let stall = TcpListener::bind(SocketAddr::from(([127, 0, 0, 1], 0)))
            .await
            .unwrap();
        let stall_addr = stall.local_addr().unwrap();
        tokio::spawn(async move {
            let (sock, _) = stall.accept().await.unwrap();
            std::mem::forget(sock); // hold the connection open, reply nothing
        });

        let listener = bind(0).await.unwrap();
        let front = listener.local_addr().unwrap();
        let counters = Arc::new(Counters::default());
        let cancel = CancellationToken::new();
        let (tx, rx) = watch::channel(true); // starts connected
        let kill_switch = Arc::new(AtomicBool::new(true));
        tokio::spawn(serve(
            listener,
            stall_addr,
            counters.clone(),
            cancel.clone(),
            rx,
            kill_switch,
            Arc::new(Registry::default()),
            Arc::new(RwLock::new(RuleSet::default())),
        ));

        let mut client = TcpStream::connect(front).await.unwrap();
        tokio::time::sleep(Duration::from_millis(100)).await;
        {
            let (_, _, active) = counters.snapshot();
            assert_eq!(active, 1, "connection should have been accepted");
        }

        tx.send(false).unwrap(); // tunnel drops

        let mut buf = [0u8; 1];
        let read = tokio::time::timeout(Duration::from_secs(2), client.read(&mut buf))
            .await
            .expect("kill switch should abort the in-flight connection")
            .unwrap();
        assert_eq!(
            read, 0,
            "expected the connection to close, not stall forever"
        );
        cancel.cancel();
    }
}
