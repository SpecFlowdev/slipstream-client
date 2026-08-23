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
}

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
}

fn default_language() -> String {
    "system".into()
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            auto_reconnect: true,
            connect_on_launch: false,
            minimise_to_tray: true,
            theme: "system".into(),
            language: default_language(),
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
}
