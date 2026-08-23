//! Where traffic is actually going, learned from the SOCKS5 handshake.
//!
//! The relay in `meter` sits between the applications on this machine and the
//! tunnel, so every SOCKS5 conversation passes through it. A SOCKS5 client
//! states its destination in plain text in the request it sends just after the
//! greeting, which means the relay can learn the host and port each connection
//! is for by *reading* the bytes it is already forwarding — without altering
//! them, terminating the session, or asking the tunnel for anything.
//!
//! That is the whole source of the per-destination figures in the interface.
//! Nothing here inspects payload, only the handshake, and none of it leaves
//! the machine: it is held in memory for the life of the session and dropped
//! on disconnect.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use serde::Serialize;

/// Enough to cover a greeting, a username/password exchange and the request
/// that follows. A client that has not stated its destination within this
/// many bytes is not speaking SOCKS5 at us, so we stop buffering and give up
/// on naming that connection rather than growing without bound.
const SNIFF_LIMIT: usize = 1024;

/// How many destinations the interface is given. The full table stays in
/// memory; this only bounds what is serialised on every status tick.
const TOP_HOSTS: usize = 12;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Target {
    pub host: String,
    pub port: u16,
}

/// The outcome of looking at the bytes buffered so far.
#[derive(Debug, PartialEq, Eq)]
enum Step {
    NeedMore,
    Done(Target),
    NotSocks5,
}

/// Reads the client half of a SOCKS5 handshake as it streams past.
///
/// Only the client's own messages are visible here (this is the
/// application-to-tunnel direction), so which message follows the greeting is
/// inferred from its version byte: `0x01` is the username/password
/// sub-negotiation, `0x05` is the connect request itself. That is exactly the
/// ambiguity the server's method selection would otherwise resolve, and the
/// two are never confusable, so nothing is lost by not seeing it.
#[derive(Debug, Default)]
pub struct Socks5Sniffer {
    buf: Vec<u8>,
    settled: bool,
}

impl Socks5Sniffer {
    /// Feeds freshly forwarded bytes in. Returns the destination once the
    /// request has arrived complete, and `None` on every other call —
    /// including for connections that turn out not to be SOCKS5 at all.
    pub fn feed(&mut self, bytes: &[u8]) -> Option<Target> {
        if self.settled {
            return None;
        }
        let room = SNIFF_LIMIT - self.buf.len();
        self.buf.extend_from_slice(&bytes[..bytes.len().min(room)]);
        match parse(&self.buf) {
            Step::Done(target) => {
                self.settled = true;
                self.buf = Vec::new();
                Some(target)
            }
            Step::NotSocks5 => {
                self.settled = true;
                self.buf = Vec::new();
                None
            }
            Step::NeedMore => {
                if self.buf.len() >= SNIFF_LIMIT {
                    self.settled = true;
                    self.buf = Vec::new();
                }
                None
            }
        }
    }
}

fn take<'a>(buf: &'a [u8], at: &mut usize, n: usize) -> Option<&'a [u8]> {
    let end = at.checked_add(n)?;
    let slice = buf.get(*at..end)?;
    *at = end;
    Some(slice)
}

fn parse(buf: &[u8]) -> Step {
    let mut at = 0usize;

    // Greeting: VER NMETHODS METHOD...
    let Some(head) = take(buf, &mut at, 2) else {
        return Step::NeedMore;
    };
    if head[0] != 0x05 {
        return Step::NotSocks5;
    }
    if take(buf, &mut at, head[1] as usize).is_none() {
        return Step::NeedMore;
    }

    loop {
        let Some(&version) = buf.get(at) else {
            return Step::NeedMore;
        };
        match version {
            // Username/password sub-negotiation: skip past it, the request
            // is the next message.
            0x01 => {
                at += 1;
                let Some(&ulen) = buf.get(at) else {
                    return Step::NeedMore;
                };
                at += 1;
                if take(buf, &mut at, ulen as usize).is_none() {
                    return Step::NeedMore;
                }
                let Some(&plen) = buf.get(at) else {
                    return Step::NeedMore;
                };
                at += 1;
                if take(buf, &mut at, plen as usize).is_none() {
                    return Step::NeedMore;
                }
            }
            // The request: VER CMD RSV ATYP DST.ADDR DST.PORT
            0x05 => {
                at += 1;
                let Some(fixed) = take(buf, &mut at, 3) else {
                    return Step::NeedMore;
                };
                let host = match fixed[2] {
                    0x01 => {
                        let Some(o) = take(buf, &mut at, 4) else {
                            return Step::NeedMore;
                        };
                        format!("{}.{}.{}.{}", o[0], o[1], o[2], o[3])
                    }
                    0x03 => {
                        let Some(&len) = buf.get(at) else {
                            return Step::NeedMore;
                        };
                        at += 1;
                        let Some(o) = take(buf, &mut at, len as usize) else {
                            return Step::NeedMore;
                        };
                        String::from_utf8_lossy(o).into_owned()
                    }
                    0x04 => {
                        let Some(o) = take(buf, &mut at, 16) else {
                            return Step::NeedMore;
                        };
                        let groups: Vec<String> = o
                            .chunks(2)
                            .map(|p| format!("{:x}", u16::from_be_bytes([p[0], p[1]])))
                            .collect();
                        groups.join(":")
                    }
                    _ => return Step::NotSocks5,
                };
                let Some(p) = take(buf, &mut at, 2) else {
                    return Step::NeedMore;
                };
                return Step::Done(Target {
                    host,
                    port: u16::from_be_bytes([p[0], p[1]]),
                });
            }
            _ => return Step::NotSocks5,
        }
    }
}

/// Byte counters owned by one connection, so its own share can be shown
/// separately from the session total.
#[derive(Debug, Default)]
pub struct ConnCounters {
    pub up: AtomicU64,
    pub down: AtomicU64,
}

struct Live {
    target: Option<Target>,
    started: Instant,
    started_ms: u64,
    counters: Arc<ConnCounters>,
}

#[derive(Default, Clone, Copy)]
struct HostTotals {
    up: u64,
    down: u64,
    connections: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConnectionRow {
    pub id: u64,
    /// Empty until the SOCKS5 request has been seen, or for a client that
    /// never speaks SOCKS5.
    pub host: String,
    pub port: u16,
    pub bytes_up: u64,
    pub bytes_down: u64,
    pub age_secs: u64,
    pub started_ms: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HostRow {
    pub host: String,
    pub bytes_up: u64,
    pub bytes_down: u64,
    pub bytes_total: u64,
    pub connections: u64,
}

#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TrafficSnapshot {
    /// Live connections, busiest first.
    pub connections: Vec<ConnectionRow>,
    /// Destinations by total bytes, busiest first, capped for the UI.
    pub top_hosts: Vec<HostRow>,
    /// Distinct destinations seen this session, including ones already closed.
    pub distinct_hosts: u64,
    /// Connections opened this session, including ones already closed.
    pub total_connections: u64,
}

#[derive(Default)]
struct Inner {
    next_id: u64,
    live: HashMap<u64, Live>,
    /// Totals from connections that have already closed. Live connections are
    /// added on top at snapshot time, so the figures stay correct throughout a
    /// connection's life rather than jumping when it ends.
    closed: HashMap<String, HostTotals>,
    total_connections: u64,
}

/// Per-session record of what went where. Dropped and rebuilt on each
/// connect, so nothing outlives the session it describes.
#[derive(Default)]
pub struct Registry {
    inner: Mutex<Inner>,
}

impl Registry {
    pub fn open(&self, counters: Arc<ConnCounters>) -> u64 {
        let mut guard = self.inner.lock().unwrap();
        guard.next_id += 1;
        guard.total_connections += 1;
        let id = guard.next_id;
        guard.live.insert(
            id,
            Live {
                target: None,
                started: Instant::now(),
                started_ms: SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .map(|d| d.as_millis() as u64)
                    .unwrap_or(0),
                counters,
            },
        );
        id
    }

    pub fn name(&self, id: u64, target: Target) {
        let mut guard = self.inner.lock().unwrap();
        if let Some(live) = guard.live.get_mut(&id) {
            live.target = Some(target);
        }
    }

    pub fn close(&self, id: u64) {
        let mut guard = self.inner.lock().unwrap();
        let Some(live) = guard.live.remove(&id) else {
            return;
        };
        let Some(target) = live.target else {
            // Never identified itself; its bytes still count in the session
            // total, they just have no destination to file them under.
            return;
        };
        let entry = guard.closed.entry(target.host).or_default();
        entry.up += live.counters.up.load(Ordering::Relaxed);
        entry.down += live.counters.down.load(Ordering::Relaxed);
        entry.connections += 1;
    }

    pub fn snapshot(&self) -> TrafficSnapshot {
        let guard = self.inner.lock().unwrap();

        let mut totals = guard.closed.clone();
        let mut connections = Vec::with_capacity(guard.live.len());
        for (id, live) in guard.live.iter() {
            let up = live.counters.up.load(Ordering::Relaxed);
            let down = live.counters.down.load(Ordering::Relaxed);
            if let Some(target) = &live.target {
                let entry = totals.entry(target.host.clone()).or_default();
                entry.up += up;
                entry.down += down;
                entry.connections += 1;
            }
            connections.push(ConnectionRow {
                id: *id,
                host: live
                    .target
                    .as_ref()
                    .map(|t| t.host.clone())
                    .unwrap_or_default(),
                port: live.target.as_ref().map(|t| t.port).unwrap_or(0),
                bytes_up: up,
                bytes_down: down,
                age_secs: live.started.elapsed().as_secs(),
                started_ms: live.started_ms,
            });
        }
        connections.sort_by(|a, b| {
            (b.bytes_up + b.bytes_down)
                .cmp(&(a.bytes_up + a.bytes_down))
                .then(a.started_ms.cmp(&b.started_ms))
        });

        let mut top_hosts: Vec<HostRow> = totals
            .into_iter()
            .map(|(host, t)| HostRow {
                host,
                bytes_up: t.up,
                bytes_down: t.down,
                bytes_total: t.up + t.down,
                connections: t.connections,
            })
            .collect();
        let distinct_hosts = top_hosts.len() as u64;
        top_hosts.sort_by(|a, b| {
            b.bytes_total
                .cmp(&a.bytes_total)
                .then_with(|| a.host.cmp(&b.host))
        });
        top_hosts.truncate(TOP_HOSTS);

        TrafficSnapshot {
            connections,
            top_hosts,
            distinct_hosts,
            total_connections: guard.total_connections,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Greeting with a single "no auth" method.
    fn greeting() -> Vec<u8> {
        vec![0x05, 0x01, 0x00]
    }

    fn request_domain(host: &str, port: u16) -> Vec<u8> {
        let mut out = vec![0x05, 0x01, 0x00, 0x03, host.len() as u8];
        out.extend_from_slice(host.as_bytes());
        out.extend_from_slice(&port.to_be_bytes());
        out
    }

    #[test]
    fn reads_a_domain_destination() {
        let mut sniffer = Socks5Sniffer::default();
        let mut stream = greeting();
        stream.extend(request_domain("example.com", 443));
        assert_eq!(
            sniffer.feed(&stream),
            Some(Target {
                host: "example.com".into(),
                port: 443
            })
        );
    }

    #[test]
    fn reads_a_destination_split_across_many_reads() {
        let mut sniffer = Socks5Sniffer::default();
        let mut stream = greeting();
        stream.extend(request_domain("split.example", 8080));
        // One byte at a time is the worst case a real socket can produce.
        let mut found = None;
        for byte in &stream {
            if let Some(target) = sniffer.feed(&[*byte]) {
                found = Some(target);
            }
        }
        assert_eq!(
            found,
            Some(Target {
                host: "split.example".into(),
                port: 8080
            })
        );
    }

    #[test]
    fn skips_past_username_password_auth() {
        let mut sniffer = Socks5Sniffer::default();
        let mut stream = vec![0x05, 0x01, 0x02]; // greeting, user/pass offered
        stream.extend_from_slice(&[0x01, 0x04]);
        stream.extend_from_slice(b"user");
        stream.push(0x04);
        stream.extend_from_slice(b"pass");
        stream.extend(request_domain("behind.auth", 443));
        assert_eq!(
            sniffer.feed(&stream),
            Some(Target {
                host: "behind.auth".into(),
                port: 443
            })
        );
    }

    #[test]
    fn reads_an_ipv4_destination() {
        let mut sniffer = Socks5Sniffer::default();
        let mut stream = greeting();
        stream.extend_from_slice(&[0x05, 0x01, 0x00, 0x01, 93, 184, 216, 34, 0x01, 0xBB]);
        assert_eq!(
            sniffer.feed(&stream),
            Some(Target {
                host: "93.184.216.34".into(),
                port: 443
            })
        );
    }

    #[test]
    fn gives_up_on_a_client_that_is_not_speaking_socks5() {
        let mut sniffer = Socks5Sniffer::default();
        assert_eq!(sniffer.feed(b"GET / HTTP/1.1\r\n"), None);
        // Settled: later bytes are ignored rather than buffered forever.
        assert_eq!(sniffer.feed(b"anything at all"), None);
        assert!(sniffer.buf.is_empty());
    }

    #[test]
    fn stops_buffering_a_client_that_never_finishes_its_handshake() {
        let mut sniffer = Socks5Sniffer::default();
        // A valid greeting, then a request that never arrives.
        assert_eq!(sniffer.feed(&greeting()), None);
        for _ in 0..100 {
            sniffer.feed(&[0x05; 64]);
        }
        assert!(
            sniffer.buf.len() <= SNIFF_LIMIT,
            "buffer must stay bounded, was {}",
            sniffer.buf.len()
        );
    }

    #[test]
    fn totals_stay_correct_while_a_connection_is_still_open() {
        let registry = Registry::default();
        let counters = Arc::new(ConnCounters::default());
        let id = registry.open(counters.clone());
        registry.name(
            id,
            Target {
                host: "example.com".into(),
                port: 443,
            },
        );
        counters.up.store(100, Ordering::Relaxed);
        counters.down.store(900, Ordering::Relaxed);

        let live = registry.snapshot();
        assert_eq!(live.top_hosts.len(), 1);
        assert_eq!(live.top_hosts[0].bytes_total, 1000, "live bytes must count");
        assert_eq!(live.connections.len(), 1);

        // Closing must not double count, nor lose the bytes.
        registry.close(id);
        let after = registry.snapshot();
        assert!(after.connections.is_empty());
        assert_eq!(after.top_hosts.len(), 1);
        assert_eq!(after.top_hosts[0].bytes_total, 1000);
        assert_eq!(after.total_connections, 1);
    }

    #[test]
    fn ranks_destinations_by_traffic() {
        let registry = Registry::default();
        for (host, bytes) in [("quiet.example", 10u64), ("busy.example", 5000)] {
            let counters = Arc::new(ConnCounters::default());
            let id = registry.open(counters.clone());
            registry.name(
                id,
                Target {
                    host: host.into(),
                    port: 443,
                },
            );
            counters.down.store(bytes, Ordering::Relaxed);
            registry.close(id);
        }
        let snapshot = registry.snapshot();
        assert_eq!(snapshot.top_hosts[0].host, "busy.example");
        assert_eq!(snapshot.distinct_hosts, 2);
    }
}
