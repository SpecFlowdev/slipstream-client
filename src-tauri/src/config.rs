//! Profile and settings storage.
//!
//! Everything lives in the platform config directory as plain JSON. Nothing is
//! ever sent anywhere: the server list, certificates and proxy credentials stay
//! on this machine.

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Profile {
    #[serde(default)]
    pub id: String,
    pub name: String,
    pub domain: String,
    pub resolver: String,
    #[serde(default)]
    pub cert: String,
    pub listen_port: u16,
    #[serde(default)]
    pub socks_username: String,
    #[serde(default)]
    pub socks_password: String,

    // Tuning, passed straight through to the tunnel binary's own flags. All
    // default to what the tunnel itself uses when the flag is absent, so an
    // existing profile saved before these existed behaves exactly as before.
    /// `--congestion-control`: "bbr" or "dcubic". BBR paces against measured
    /// bandwidth and round-trip time instead of treating loss as congestion,
    /// which is usually the faster choice over a DNS tunnel, where loss is
    /// common and rarely means the path is actually saturated.
    #[serde(default = "default_congestion")]
    pub congestion_control: String,
    /// `--gso`: hands segmentation of large UDP writes to the kernel (or the
    /// NIC), so one syscall covers many datagrams. Big throughput win where
    /// it is supported, and unsupported kernels log a warning and carry on.
    #[serde(default)]
    pub gso: bool,
    /// `--keep-alive-interval`, milliseconds. Lower keeps NAT bindings and
    /// resolver state alive on aggressive networks at the cost of idle
    /// traffic; the tunnel's own default is 400.
    #[serde(default = "default_keep_alive")]
    pub keep_alive_ms: u32,
    /// `--authoritative`: query this address directly as the authoritative
    /// server for the zone, skipping the recursive resolver. Faster when it
    /// works, but far more conspicuous. Empty means unused.
    #[serde(default)]
    pub authoritative: String,
}

fn default_congestion() -> String {
    "bbr".into()
}

fn default_keep_alive() -> u32 {
    400
}

/// The values the tunnel binary accepts for `--congestion-control`.
pub const CONGESTION_CONTROLS: &[&str] = &["bbr", "dcubic"];

impl Profile {
    /// Rejects input that would otherwise surface as a confusing runtime error
    /// from the tunnel process.
    pub fn validate(&self) -> Result<(), String> {
        if self.name.trim().is_empty() {
            return Err("Profile name is required".into());
        }
        if !valid_domain(&self.domain) {
            return Err(format!("Not a valid tunnel domain: {}", self.domain));
        }
        if !valid_host_port(&self.resolver) {
            return Err(format!(
                "Resolver must be HOST:PORT, got: {}",
                self.resolver
            ));
        }
        if self.listen_port == 0 {
            return Err("Local port must be between 1 and 65535".into());
        }
        if !self.cert.trim().is_empty() && !self.cert.contains("BEGIN CERTIFICATE") {
            return Err("Certificate does not look like PEM".into());
        }
        if self.socks_password.is_empty() != self.socks_username.is_empty() {
            return Err("Set both the SOCKS username and password, or neither".into());
        }
        if !CONGESTION_CONTROLS.contains(&self.congestion_control.as_str()) {
            return Err(format!(
                "Congestion control must be one of {}, got: {}",
                CONGESTION_CONTROLS.join(", "),
                self.congestion_control
            ));
        }
        // The tunnel takes this as a plain millisecond count. Anything under a
        // tenth of a second is pure overhead on a DNS tunnel, and anything
        // past a minute defeats the point of a keep-alive.
        if !(100..=60_000).contains(&self.keep_alive_ms) {
            return Err("Keep-alive interval must be between 100 and 60000 ms".into());
        }
        if !self.authoritative.trim().is_empty() && !valid_host_port(&self.authoritative) {
            return Err(format!(
                "Authoritative server must be HOST:PORT, got: {}",
                self.authoritative
            ));
        }
        Ok(())
    }
}

fn valid_domain(domain: &str) -> bool {
    let domain = domain.trim();
    if domain.is_empty() || domain.len() > 200 || !domain.contains('.') {
        return false;
    }
    domain.split('.').all(|label| {
        !label.is_empty()
            && label.len() <= 63
            && !label.starts_with('-')
            && !label.ends_with('-')
            && label.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
    })
}

fn valid_host_port(value: &str) -> bool {
    let Some((host, port)) = value.trim().rsplit_once(':') else {
        return false;
    };
    if host.is_empty() {
        return false;
    }
    matches!(port.parse::<u16>(), Ok(p) if p > 0)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Settings {
    pub auto_reconnect: bool,
    pub connect_on_launch: bool,
    pub minimise_to_tray: bool,
    pub theme: String,
    /// "system", "en" or "ru".
    #[serde(default = "default_language")]
    pub language: String,
    /// Refuses new SOCKS5 connections and drops active ones while the tunnel
    /// is not Connected, scoped to this app's own proxy port - not a
    /// system-wide network block, since routing is not on a TUN device yet.
    #[serde(default = "default_kill_switch")]
    pub kill_switch: bool,
    /// Absolute path to a copy of the chosen wallpaper image inside the app's
    /// config directory, or None. Applies to whichever theme is active.
    #[serde(default)]
    pub wallpaper_path: Option<String>,
    /// How strongly the wallpaper is darkened (dark themes) or lightened
    /// (the light theme) behind the panels, 0-90 percent. Without this a
    /// bright photo makes light text unreadable and vice versa.
    #[serde(default = "default_wallpaper_dim")]
    pub wallpaper_dim: u8,
    /// Gaussian blur applied to the wallpaper, 0-40 px. Blurring a busy photo
    /// keeps it from competing with the interface on top of it.
    #[serde(default = "default_wallpaper_blur")]
    pub wallpaper_blur: u8,
    /// Points the operating system's own proxy configuration at this app's
    /// SOCKS5 port while connected, and puts it back on disconnect, so apps
    /// that honour the system proxy need no per-app setup. Not a TUN device:
    /// software that ignores the system proxy still bypasses the tunnel.
    #[serde(default)]
    pub system_proxy: bool,
    /// Keeps the traffic graphs animating and the connection table live.
    /// Off trades the motion for a little less CPU on a slow machine.
    #[serde(default = "default_true")]
    pub animations: bool,
}

fn default_wallpaper_dim() -> u8 {
    45
}

fn default_wallpaper_blur() -> u8 {
    0
}

fn default_true() -> bool {
    true
}

fn default_language() -> String {
    "system".into()
}

fn default_kill_switch() -> bool {
    true
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            auto_reconnect: true,
            connect_on_launch: false,
            minimise_to_tray: true,
            theme: "system".into(),
            language: default_language(),
            kill_switch: default_kill_switch(),
            wallpaper_path: None,
            wallpaper_dim: default_wallpaper_dim(),
            wallpaper_blur: default_wallpaper_blur(),
            system_proxy: false,
            animations: true,
        }
    }
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct StoreFile {
    #[serde(default)]
    profiles: Vec<Profile>,
    #[serde(default)]
    settings: Settings,
    #[serde(default)]
    last_profile: Option<String>,
}

pub struct Store {
    path: PathBuf,
    inner: Mutex<StoreFile>,
}

impl Store {
    pub fn load(dir: &Path) -> Self {
        let path = dir.join("config.json");
        let inner = fs::read_to_string(&path)
            .ok()
            .and_then(|raw| serde_json::from_str::<StoreFile>(&raw).ok())
            .unwrap_or_default();
        Self {
            path,
            inner: Mutex::new(inner),
        }
    }

    fn persist(&self, file: &StoreFile) -> Result<(), String> {
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent).map_err(|err| err.to_string())?;
        }
        let body = serde_json::to_string_pretty(file).map_err(|err| err.to_string())?;
        // Write through a temporary file so a crash mid-write cannot leave the
        // profile list truncated.
        let tmp = self.path.with_extension("json.tmp");
        fs::write(&tmp, body).map_err(|err| err.to_string())?;
        fs::rename(&tmp, &self.path).map_err(|err| err.to_string())
    }

    pub fn profiles(&self) -> Vec<Profile> {
        self.inner.lock().unwrap().profiles.clone()
    }

    pub fn profile(&self, id: &str) -> Option<Profile> {
        self.inner
            .lock()
            .unwrap()
            .profiles
            .iter()
            .find(|p| p.id == id)
            .cloned()
    }

    pub fn save_profile(&self, mut profile: Profile) -> Result<Profile, String> {
        profile.validate()?;
        let mut guard = self.inner.lock().unwrap();
        if profile.id.is_empty() {
            profile.id = new_id();
            guard.profiles.push(profile.clone());
        } else if let Some(slot) = guard.profiles.iter_mut().find(|p| p.id == profile.id) {
            *slot = profile.clone();
        } else {
            guard.profiles.push(profile.clone());
        }
        self.persist(&guard)?;
        Ok(profile)
    }

    pub fn delete_profile(&self, id: &str) -> Result<(), String> {
        let mut guard = self.inner.lock().unwrap();
        guard.profiles.retain(|p| p.id != id);
        if guard.last_profile.as_deref() == Some(id) {
            guard.last_profile = None;
        }
        self.persist(&guard)
    }

    pub fn settings(&self) -> Settings {
        self.inner.lock().unwrap().settings.clone()
    }

    pub fn save_settings(&self, settings: Settings) -> Result<Settings, String> {
        let mut guard = self.inner.lock().unwrap();
        guard.settings = settings.clone();
        self.persist(&guard)?;
        Ok(settings)
    }

    pub fn last_profile(&self) -> Option<String> {
        self.inner.lock().unwrap().last_profile.clone()
    }

    pub fn set_last_profile(&self, id: Option<String>) {
        let mut guard = self.inner.lock().unwrap();
        guard.last_profile = id;
        let _ = self.persist(&guard);
    }
}

fn new_id() -> String {
    // Enough entropy to keep profile ids distinct; they are local identifiers,
    // not secrets.
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or_default();
    format!("{nanos:x}")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn profile() -> Profile {
        Profile {
            id: String::new(),
            name: "Home".into(),
            domain: "t.example.com".into(),
            resolver: "1.1.1.1:53".into(),
            cert: String::new(),
            listen_port: 1080,
            socks_username: String::new(),
            socks_password: String::new(),
            congestion_control: default_congestion(),
            gso: false,
            keep_alive_ms: default_keep_alive(),
            authoritative: String::new(),
        }
    }

    #[test]
    fn accepts_a_reasonable_profile() {
        assert!(profile().validate().is_ok());
    }

    #[test]
    fn rejects_bad_domains() {
        for bad in ["", "example", "-bad.com", "bad-.com", "a..b"] {
            let mut p = profile();
            p.domain = bad.into();
            assert!(p.validate().is_err(), "should reject {bad:?}");
        }
    }

    #[test]
    fn rejects_bad_resolvers() {
        for bad in ["", "1.1.1.1", "1.1.1.1:", ":53", "1.1.1.1:0"] {
            let mut p = profile();
            p.resolver = bad.into();
            assert!(p.validate().is_err(), "should reject {bad:?}");
        }
    }

    #[test]
    fn requires_both_socks_credentials_or_neither() {
        let mut p = profile();
        p.socks_username = "user".into();
        assert!(p.validate().is_err());
        p.socks_password = "pass".into();
        assert!(p.validate().is_ok());
    }

    #[test]
    fn rejects_a_certificate_that_is_not_pem() {
        let mut p = profile();
        p.cert = "not a certificate".into();
        assert!(p.validate().is_err());
    }

    #[test]
    fn rejects_a_congestion_control_the_tunnel_does_not_accept() {
        let mut p = profile();
        p.congestion_control = "reno".into();
        assert!(p.validate().is_err());
        for good in CONGESTION_CONTROLS {
            p.congestion_control = (*good).into();
            assert!(p.validate().is_ok(), "should accept {good}");
        }
    }

    #[test]
    fn rejects_keep_alive_intervals_outside_the_useful_range() {
        for bad in [0, 50, 99, 60_001, 500_000] {
            let mut p = profile();
            p.keep_alive_ms = bad;
            assert!(p.validate().is_err(), "should reject {bad}");
        }
        for good in [100, 400, 60_000] {
            let mut p = profile();
            p.keep_alive_ms = good;
            assert!(p.validate().is_ok(), "should accept {good}");
        }
    }

    #[test]
    fn authoritative_is_optional_but_must_be_host_port_when_set() {
        let mut p = profile();
        assert!(p.validate().is_ok(), "empty is fine");
        p.authoritative = "not-a-socket".into();
        assert!(p.validate().is_err());
        p.authoritative = "203.0.113.9:53".into();
        assert!(p.validate().is_ok());
    }

    #[test]
    fn a_profile_saved_before_the_tuning_fields_existed_still_loads() {
        // Exactly the shape older versions wrote, with none of the new keys.
        let raw = r#"{
            "id": "abc",
            "name": "Home",
            "domain": "t.example.com",
            "resolver": "1.1.1.1:53",
            "listenPort": 1080
        }"#;
        let parsed: Profile = serde_json::from_str(raw).expect("old profiles must still parse");
        assert_eq!(parsed.congestion_control, "bbr");
        assert_eq!(parsed.keep_alive_ms, 400);
        assert!(!parsed.gso);
        assert!(parsed.validate().is_ok());
    }

    #[test]
    fn settings_saved_before_the_new_keys_existed_still_load() {
        let raw = r#"{
            "autoReconnect": true,
            "connectOnLaunch": false,
            "minimiseToTray": true,
            "theme": "dark"
        }"#;
        let parsed: Settings = serde_json::from_str(raw).expect("old settings must still parse");
        assert!(parsed.kill_switch, "kill switch defaults on");
        assert!(parsed.animations);
        assert!(!parsed.system_proxy);
        assert_eq!(parsed.wallpaper_dim, 45);
    }
}
