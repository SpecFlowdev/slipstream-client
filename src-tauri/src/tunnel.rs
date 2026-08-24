//! Supervises the `slipstream-client` process and derives connection state.
//!
//! The tunnel binary is shipped as a Tauri sidecar. It is told to listen on a
//! loopback port of our choosing; the port the user configured is owned by the
//! metering relay in `meter`, which forwards to it.

use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde::Serialize;
use tauri::async_runtime;
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_shell::process::{CommandChild, CommandEvent};
use tauri_plugin_shell::ShellExt;
use tokio::net::TcpListener;
use tokio::sync::watch;
use tokio_util::sync::CancellationToken;

use crate::config::Profile;
use crate::meter::{self, Counters};
use crate::rules::RuleSet;
use crate::traffic::{Registry, TrafficSnapshot};

const LOG_CAPACITY: usize = 2000;
const SAMPLE_INTERVAL: Duration = Duration::from_millis(1000);

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum ConnectionState {
    #[default]
    Disconnected,
    Starting,
    Connecting,
    Connected,
    Reconnecting,
    Error,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    pub state: ConnectionState,
    pub profile_id: Option<String>,
    pub message: Option<String>,
    pub uptime_secs: u64,
    pub bytes_up: u64,
    pub bytes_down: u64,
    pub rate_up: u64,
    pub rate_down: u64,
    pub active_connections: u64,
    /// Best rates seen this session, so a burst that has already passed is
    /// still visible rather than only the instant reading.
    pub peak_rate_up: u64,
    pub peak_rate_down: u64,
    /// Where the traffic went, from the SOCKS5 requests the relay forwards.
    pub traffic: TrafficSnapshot,
    /// Connections a routing rule refused this session.
    pub blocked: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LogLine {
    pub seq: u64,
    pub ts: u64,
    pub level: String,
    pub message: String,
}

struct Session {
    child: CommandChild,
    cancel: CancellationToken,
    counters: Arc<Counters>,
    profile_id: String,
    connected_since: Option<Instant>,
    last_sample: Instant,
    last_up: u64,
    last_down: u64,
    rate_up: u64,
    rate_down: u64,
    peak_rate_up: u64,
    peak_rate_down: u64,
    /// Broadcasts whether the tunnel is in the Connected state, to the
    /// kill-switch logic in `meter::serve`/`pump`.
    connected_tx: watch::Sender<bool>,
    /// Per-session destination record, rebuilt on every connect.
    registry: Arc<Registry>,
    /// The system proxy configuration as it was before this session changed
    /// it, or None when the session left it alone.
    saved_proxy: Option<crate::sysproxy::Saved>,
}

#[derive(Default)]
pub struct TunnelState {
    session: Mutex<Option<Session>>,
    state: Mutex<(ConnectionState, Option<String>)>,
    logs: Mutex<Vec<LogLine>>,
    seq: AtomicU64,
    /// Live-toggleable: a Settings change applies to the running session
    /// immediately, without needing to reconnect. Constructed false by
    /// `#[derive(Default)]`; `run()` sets the real value from Settings before
    /// any connection can start.
    pub kill_switch: Arc<AtomicBool>,
    /// Shared with the relay, so editing a rule takes effect on the session
    /// already running rather than only on the next connect.
    pub rules: Arc<std::sync::RwLock<RuleSet>>,
}

impl TunnelState {
    pub fn status(&self) -> Status {
        let (state, message) = self.state.lock().unwrap().clone();
        let guard = self.session.lock().unwrap();
        let Some(session) = guard.as_ref() else {
            return Status {
                state,
                profile_id: None,
                message,
                uptime_secs: 0,
                bytes_up: 0,
                bytes_down: 0,
                rate_up: 0,
                rate_down: 0,
                active_connections: 0,
                peak_rate_up: 0,
                peak_rate_down: 0,
                traffic: TrafficSnapshot::default(),
                blocked: self.rules.read().unwrap().blocked_count(),
            };
        };
        let (bytes_up, bytes_down, active) = session.counters.snapshot();
        Status {
            state,
            profile_id: Some(session.profile_id.clone()),
            message,
            uptime_secs: session
                .connected_since
                .map(|t| t.elapsed().as_secs())
                .unwrap_or(0),
            bytes_up,
            bytes_down,
            rate_up: session.rate_up,
            rate_down: session.rate_down,
            active_connections: active,
            peak_rate_up: session.peak_rate_up,
            peak_rate_down: session.peak_rate_down,
            traffic: session.registry.snapshot(),
            blocked: self.rules.read().unwrap().blocked_count(),
        }
    }

    pub fn logs(&self) -> Vec<LogLine> {
        self.logs.lock().unwrap().clone()
    }

    pub fn clear_logs(&self) {
        self.logs.lock().unwrap().clear();
    }

    fn set_state(&self, app: &AppHandle, state: ConnectionState, message: Option<String>) {
        {
            let mut guard = self.state.lock().unwrap();
            *guard = (state, message);
        }
        {
            let mut guard = self.session.lock().unwrap();
            if let Some(session) = guard.as_mut() {
                if state == ConnectionState::Connected {
                    session.connected_since.get_or_insert_with(Instant::now);
                }
                // Every other state means "not safe to pass traffic" for the
                // kill switch, including Reconnecting: a dropped connection
                // getting a fresh one is still a gap the switch must cover.
                let _ = session
                    .connected_tx
                    .send(state == ConnectionState::Connected);
            }
        }
        let _ = app.emit("status", self.status());
    }

    fn push_log(&self, app: &AppHandle, level: &str, message: String) {
        let seq = self.seq.fetch_add(1, Ordering::Relaxed);
        let line = LogLine {
            seq,
            ts: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0),
            level: level.to_string(),
            message,
        };
        {
            let mut guard = self.logs.lock().unwrap();
            if guard.len() >= LOG_CAPACITY {
                let overflow = guard.len() + 1 - LOG_CAPACITY;
                guard.drain(0..overflow);
            }
            guard.push(line.clone());
        }
        let _ = app.emit("log", line);
    }
}

/// Splits a tracing line such as `  INFO Connection ready` into level and text.
fn parse_log(raw: &str) -> (String, String) {
    let stripped = strip_ansi(raw);
    let trimmed = stripped.trim();
    for level in ["TRACE", "DEBUG", "INFO", "WARN", "ERROR"] {
        if let Some(rest) = trimmed.strip_prefix(level) {
            return (level.to_ascii_lowercase(), rest.trim().to_string());
        }
    }
    ("info".to_string(), trimmed.to_string())
}

fn strip_ansi(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    let mut chars = input.chars();
    while let Some(c) = chars.next() {
        if c != '\u{1b}' {
            out.push(c);
            continue;
        }
        // Drop the escape sequence up to and including its final byte.
        for c in chars.by_ref() {
            if c.is_ascii_alphabetic() {
                break;
            }
        }
    }
    out
}

pub async fn connect(app: AppHandle, profile: Profile, system_proxy: bool) -> Result<(), String> {
    disconnect(app.clone()).await;

    let state = app.state::<Arc<TunnelState>>().inner().clone();
    state.set_state(&app, ConnectionState::Starting, None);

    // Claim the user-facing port first: if it is taken, fail now with a clear
    // message rather than starting a tunnel nothing can reach.
    let front = meter::bind(profile.listen_port).await.map_err(|err| {
        let msg = format!("Cannot listen on port {}: {}", profile.listen_port, err);
        state.set_state(&app, ConnectionState::Error, Some(msg.clone()));
        msg
    })?;

    // Let the OS pick the tunnel's own port so two profiles can never collide.
    let scratch = TcpListener::bind(SocketAddr::from(([127, 0, 0, 1], 0)))
        .await
        .map_err(|err| err.to_string())?;
    let internal_port = scratch.local_addr().map_err(|e| e.to_string())?.port();
    drop(scratch);

    let cert_path = if profile.cert.trim().is_empty() {
        None
    } else {
        let dir = app.path().app_config_dir().map_err(|err| err.to_string())?;
        std::fs::create_dir_all(&dir).map_err(|err| err.to_string())?;
        let path = dir.join(format!("{}.pem", profile.id));
        std::fs::write(&path, profile.cert.as_bytes()).map_err(|err| err.to_string())?;
        Some(path)
    };

    let mut args = vec![
        "--domain".to_string(),
        profile.domain.clone(),
        "--resolver".to_string(),
        profile.resolver.clone(),
        "--tcp-listen-host".to_string(),
        "127.0.0.1".to_string(),
        "--tcp-listen-port".to_string(),
        internal_port.to_string(),
        // Validated by Profile::validate against the values the binary
        // actually accepts, so this can never become an unknown-flag exit.
        "--congestion-control".to_string(),
        profile.congestion_control.clone(),
        "--keep-alive-interval".to_string(),
        profile.keep_alive_ms.to_string(),
        // The flag takes an explicit boolean rather than being a bare switch.
        "--gso".to_string(),
        profile.gso.to_string(),
    ];
    if let Some(path) = &cert_path {
        args.push("--cert".to_string());
        args.push(path.to_string_lossy().to_string());
    }
    if !profile.authoritative.trim().is_empty() {
        args.push("--authoritative".to_string());
        args.push(profile.authoritative.trim().to_string());
    }

    let (mut rx, child) = app
        .shell()
        .sidecar("slipstream-tunnel")
        .map_err(|err| format!("Tunnel binary is missing: {err}"))?
        .args(args)
        .spawn()
        .map_err(|err| format!("Could not start the tunnel: {err}"))?;

    let counters = Arc::new(Counters::default());
    let cancel = CancellationToken::new();
    // Starts false: nothing is Connected yet, so the kill switch (if on)
    // refuses connections from the first moment rather than racing the
    // tunnel's own startup.
    let (connected_tx, connected_rx) = watch::channel(false);

    let registry = Arc::new(Registry::default());

    // Taking over the system proxy is opt-in and must never be the reason a
    // connection fails: if the desktop refuses, say so in the log and carry
    // on with the SOCKS5 port working normally.
    let saved_proxy = if system_proxy {
        match crate::sysproxy::enable(profile.listen_port) {
            Ok(saved) => {
                state.push_log(
                    &app,
                    "info",
                    format!("System proxy pointed at 127.0.0.1:{}", profile.listen_port),
                );
                Some(saved)
            }
            Err(err) => {
                state.push_log(
                    &app,
                    "warn",
                    format!("Could not set the system proxy: {err}"),
                );
                None
            }
        }
    } else {
        None
    };

    async_runtime::spawn(meter::serve(
        front,
        SocketAddr::from(([127, 0, 0, 1], internal_port)),
        counters.clone(),
        cancel.clone(),
        connected_rx,
        state.kill_switch.clone(),
        registry.clone(),
        state.rules.clone(),
    ));

    {
        let mut guard = state.session.lock().unwrap();
        *guard = Some(Session {
            child,
            cancel: cancel.clone(),
            counters: counters.clone(),
            profile_id: profile.id.clone(),
            connected_since: None,
            last_sample: Instant::now(),
            last_up: 0,
            last_down: 0,
            rate_up: 0,
            rate_down: 0,
            peak_rate_up: 0,
            peak_rate_down: 0,
            connected_tx,
            registry,
            saved_proxy,
        });
    }

    state.set_state(&app, ConnectionState::Connecting, None);
    state.push_log(
        &app,
        "info",
        format!(
            "Starting tunnel to {} via {} (SOCKS5 on 127.0.0.1:{})",
            profile.domain, profile.resolver, profile.listen_port
        ),
    );

    // Drain the child's output, turning notable lines into state transitions.
    {
        let app = app.clone();
        let state = state.clone();
        async_runtime::spawn(async move {
            while let Some(event) = rx.recv().await {
                match event {
                    CommandEvent::Stdout(bytes) | CommandEvent::Stderr(bytes) => {
                        let text = String::from_utf8_lossy(&bytes).to_string();
                        for raw in text.lines().filter(|l| !l.trim().is_empty()) {
                            let (level, message) = parse_log(raw);
                            if message.contains("Connection ready") {
                                state.set_state(&app, ConnectionState::Connected, None);
                            } else if message.contains("Connection closed; reconnecting") {
                                state.set_state(&app, ConnectionState::Reconnecting, None);
                            }
                            state.push_log(&app, &level, message);
                        }
                    }
                    CommandEvent::Terminated(payload) => {
                        let running = state.session.lock().unwrap().is_some();
                        if running {
                            let msg = match payload.code {
                                Some(0) | None => "Tunnel process exited".to_string(),
                                Some(code) => format!("Tunnel process exited with code {code}"),
                            };
                            state.push_log(&app, "error", msg.clone());
                            state.set_state(&app, ConnectionState::Error, Some(msg));
                            let mut guard = state.session.lock().unwrap();
                            if let Some(session) = guard.take() {
                                session.cancel.cancel();
                                // The tunnel dying must not leave the desktop
                                // pointed at a proxy port nothing answers on.
                                if let Some(saved) = &session.saved_proxy {
                                    crate::sysproxy::restore(saved);
                                }
                            }
                        }
                        break;
                    }
                    _ => {}
                }
            }
        });
    }

    // Sample throughput on a fixed interval so the UI can show a live rate.
    {
        let app = app.clone();
        let state = state.clone();
        async_runtime::spawn(async move {
            loop {
                tokio::time::sleep(SAMPLE_INTERVAL).await;
                let alive = {
                    let mut guard = state.session.lock().unwrap();
                    match guard.as_mut() {
                        None => false,
                        Some(session) => {
                            let (up, down, _) = session.counters.snapshot();
                            let elapsed = session.last_sample.elapsed().as_secs_f64().max(0.001);
                            session.rate_up =
                                ((up.saturating_sub(session.last_up)) as f64 / elapsed) as u64;
                            session.rate_down =
                                ((down.saturating_sub(session.last_down)) as f64 / elapsed) as u64;
                            session.peak_rate_up = session.peak_rate_up.max(session.rate_up);
                            session.peak_rate_down = session.peak_rate_down.max(session.rate_down);
                            session.last_up = up;
                            session.last_down = down;
                            session.last_sample = Instant::now();
                            true
                        }
                    }
                };
                if !alive {
                    break;
                }
                let _ = app.emit("status", state.status());
            }
        });
    }

    Ok(())
}

/// Files a finished session in the history the interface shows. Reads the
/// counters one last time before the session is dropped, since after that the
/// figures are gone.
fn record_session(app: &AppHandle, session: &Session) {
    let Some(store) = app.try_state::<Arc<crate::config::Store>>() else {
        return;
    };
    let (bytes_up, bytes_down, _) = session.counters.snapshot();
    let name = store
        .profile(&session.profile_id)
        .map(|p| p.name)
        .unwrap_or_else(|| "Unknown".to_string());
    store.record_session(crate::config::SessionRecord {
        ended_ms: SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0),
        profile_name: name,
        seconds: session
            .connected_since
            .map(|t| t.elapsed().as_secs())
            .unwrap_or(0),
        bytes_up,
        bytes_down,
        peak_rate_down: session.peak_rate_down,
        connections: session.registry.snapshot().total_connections,
    });
}

/// Tears the session down from a context that cannot await, used on the way
/// out of the process. Only the parts that must not be skipped run here: the
/// child process is killed and the system proxy is put back.
pub fn shutdown_blocking(app: &AppHandle) {
    let Some(state) = app.try_state::<Arc<TunnelState>>() else {
        return;
    };
    let session = state.session.lock().unwrap().take();
    if let Some(session) = session {
        session.cancel.cancel();
        let _ = session.child.kill();
        if let Some(saved) = &session.saved_proxy {
            crate::sysproxy::restore(saved);
        }
    }
}

pub async fn disconnect(app: AppHandle) {
    let state = app.state::<Arc<TunnelState>>().inner().clone();
    let session = state.session.lock().unwrap().take();
    if let Some(session) = session {
        session.cancel.cancel();
        // Read the counters before `kill` consumes the child out of the
        // session; afterwards the session is only partially there.
        record_session(&app, &session);
        let _ = session.child.kill();
        if let Some(saved) = &session.saved_proxy {
            crate::sysproxy::restore(saved);
            state.push_log(&app, "info", "System proxy restored".to_string());
        }
        state.push_log(&app, "info", "Tunnel stopped".to_string());
    }
    state.set_state(&app, ConnectionState::Disconnected, None);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_tracing_levels() {
        assert_eq!(
            parse_log("  INFO Connection ready"),
            ("info".into(), "Connection ready".into())
        );
        assert_eq!(
            parse_log(" WARN GSO is not implemented"),
            ("warn".into(), "GSO is not implemented".into())
        );
    }

    #[test]
    fn strips_colour_codes_from_output() {
        let raw = "\u{1b}[33m WARN\u{1b}[0m pinning disabled";
        assert_eq!(parse_log(raw), ("warn".into(), "pinning disabled".into()));
    }

    #[test]
    fn falls_back_to_info_for_unprefixed_lines() {
        assert_eq!(
            parse_log("something happened"),
            ("info".into(), "something happened".into())
        );
    }
}
