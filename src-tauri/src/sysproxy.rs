//! Points the operating system's own proxy setting at this app.
//!
//! This is not a TUN device and does not pretend to be one. A TUN device
//! captures every packet a machine sends, regardless of what the program
//! sending it wants; the tunnel binary this app drives exposes a SOCKS5 port
//! and nothing else, so there is no packet-level path to capture. What this
//! module does instead is set the system-wide proxy configuration while the
//! tunnel is up and put it back exactly as it was on disconnect, so software
//! that honours that setting — browsers, most desktop applications, anything
//! reading `http_proxy` — goes through the tunnel with no per-app setup.
//!
//! Software that ignores the system proxy still bypasses the tunnel. The
//! interface says so rather than implying whole-machine coverage.

/// What the proxy was set to before we touched it, so disconnecting restores
/// the user's own configuration rather than blanking it.
#[derive(Debug, Clone, Default)]
pub struct Saved {
    #[cfg(target_os = "linux")]
    mode: Option<String>,
    #[cfg(target_os = "linux")]
    host: Option<String>,
    #[cfg(target_os = "linux")]
    port: Option<String>,
    #[cfg(target_os = "windows")]
    enable: Option<String>,
    #[cfg(target_os = "windows")]
    server: Option<String>,
}

#[cfg(target_os = "linux")]
mod imp {
    use super::Saved;
    use std::process::Command;

    const SCHEMA: &str = "org.gnome.system.proxy";

    fn get(schema: &str, key: &str) -> Option<String> {
        let out = Command::new("gsettings")
            .args(["get", schema, key])
            .output()
            .ok()?;
        if !out.status.success() {
            return None;
        }
        Some(String::from_utf8_lossy(&out.stdout).trim().to_string())
    }

    fn set(schema: &str, key: &str, value: &str) -> Result<(), String> {
        let out = Command::new("gsettings")
            .args(["set", schema, key, value])
            .output()
            .map_err(|err| format!("gsettings is not available: {err}"))?;
        if out.status.success() {
            return Ok(());
        }
        Err(String::from_utf8_lossy(&out.stderr).trim().to_string())
    }

    pub fn enable(port: u16) -> Result<Saved, String> {
        // Read the current values first; if this fails there is no desktop
        // proxy setting to drive and enabling would be a silent no-op.
        let mode = get(SCHEMA, "mode").ok_or_else(|| {
            "This desktop does not expose the GNOME proxy settings, so the system proxy \
             cannot be set from here. Point applications at the SOCKS5 port directly."
                .to_string()
        })?;
        let saved = Saved {
            mode: Some(mode),
            host: get(&format!("{SCHEMA}.socks"), "host"),
            port: get(&format!("{SCHEMA}.socks"), "port"),
        };

        set(&format!("{SCHEMA}.socks"), "host", "'127.0.0.1'")?;
        set(&format!("{SCHEMA}.socks"), "port", &port.to_string())?;
        set(SCHEMA, "mode", "'manual'")?;
        Ok(saved)
    }

    pub fn restore(saved: &Saved) {
        // Best effort: a failure here must never block disconnecting.
        if let Some(mode) = &saved.mode {
            let _ = set(SCHEMA, "mode", mode);
        }
        if let Some(host) = &saved.host {
            let _ = set(&format!("{SCHEMA}.socks"), "host", host);
        }
        if let Some(port) = &saved.port {
            let _ = set(&format!("{SCHEMA}.socks"), "port", port);
        }
    }
}

#[cfg(target_os = "windows")]
mod imp {
    use super::Saved;
    use std::process::Command;

    const KEY: &str = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings";

    fn query(name: &str) -> Option<String> {
        let out = Command::new("reg")
            .args(["query", KEY, "/v", name])
            .output()
            .ok()?;
        if !out.status.success() {
            return None;
        }
        let text = String::from_utf8_lossy(&out.stdout).to_string();
        // "    ProxyEnable    REG_DWORD    0x1"
        text.lines()
            .find(|line| line.trim_start().starts_with(name))
            .and_then(|line| line.split_whitespace().last())
            .map(str::to_string)
    }

    fn set(name: &str, kind: &str, value: &str) -> Result<(), String> {
        let out = Command::new("reg")
            .args(["add", KEY, "/v", name, "/t", kind, "/d", value, "/f"])
            .output()
            .map_err(|err| format!("could not run reg: {err}"))?;
        if out.status.success() {
            return Ok(());
        }
        Err(String::from_utf8_lossy(&out.stderr).trim().to_string())
    }

    pub fn enable(port: u16) -> Result<Saved, String> {
        let saved = Saved {
            enable: query("ProxyEnable"),
            server: query("ProxyServer"),
        };
        // WinINET reads SOCKS proxies from this same string with the
        // "socks=" prefix.
        set("ProxyServer", "REG_SZ", &format!("socks=127.0.0.1:{port}"))?;
        set("ProxyEnable", "REG_DWORD", "1")?;
        Ok(saved)
    }

    pub fn restore(saved: &Saved) {
        let _ = set(
            "ProxyEnable",
            "REG_DWORD",
            saved.enable.as_deref().unwrap_or("0x0"),
        );
        if let Some(server) = &saved.server {
            let _ = set("ProxyServer", "REG_SZ", server);
        }
    }
}

#[cfg(not(any(target_os = "linux", target_os = "windows")))]
mod imp {
    use super::Saved;

    pub fn enable(_port: u16) -> Result<Saved, String> {
        Err("Setting the system proxy is not supported on this platform".into())
    }

    pub fn restore(_saved: &Saved) {}
}

/// Switches the system proxy to this app's SOCKS5 port, returning what was
/// there before so it can be put back.
pub fn enable(port: u16) -> Result<Saved, String> {
    imp::enable(port)
}

/// Puts back whatever `enable` found. Never fails: a disconnect must not be
/// blocked by the desktop refusing a settings write.
pub fn restore(saved: &Saved) {
    imp::restore(saved)
}
