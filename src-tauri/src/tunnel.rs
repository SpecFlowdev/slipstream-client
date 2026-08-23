//! Supervises the `slipstream-client` process and derives connection state.
//!
//! The tunnel binary is shipped as a Tauri sidecar. It is told to listen on a
//! loopback port of our choosing; the port the user configured is owned by the
//! metering relay in `meter`, which forwards to it.

use std::net::SocketAddr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde::Serialize;
use tauri::async_runtime;
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_shell::process::{CommandChild, CommandEvent};
use tauri_plugin_shell::ShellExt;
use tokio::net::TcpListener;
use tokio_util::sync::CancellationToken;

use crate::config::Profile;
use crate::meter::{self, Counters};

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
}

#[derive(Default)]
pub struct TunnelState {
    session: Mutex<Option<Session>>,
    state: Mutex<(ConnectionState, Option<String>)>,
    logs: Mutex<Vec<LogLine>>,
    seq: AtomicU64,
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
        if state == ConnectionState::Connected {
            let mut guard = self.session.lock().unwrap();
            if let Some(session) = guard.as_mut() {
                session.connected_since.get_or_insert_with(Instant::now);
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

pub async fn connect(app: AppHandle, profile: Profile) -> Result<(), String> {
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
    ];
    if let Some(path) = &cert_path {
        args.push("--cert".to_string());
        args.push(path.to_string_lossy().to_string());
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

    async_runtime::spawn(meter::serve(
        front,
        SocketAddr::from(([127, 0, 0, 1], internal_port)),
        counters.clone(),
        cancel.clone(),
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

pub async fn disconnect(app: AppHandle) {
    let state = app.state::<Arc<TunnelState>>().inner().clone();
    let session = state.session.lock().unwrap().take();
    if let Some(session) = session {
        session.cancel.cancel();
        let _ = session.child.kill();
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
