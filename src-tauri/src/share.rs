//! Profile transfer by a compact link, meant to be shown or scanned as a QR
//! code.
//!
//! The payload is a small JSON object with single-letter keys, base64url
//! encoded into a `slipstream://p?v=1&d=...` link. The shape is kept
//! letter-for-letter identical between this client and the Android one,
//! independently implemented in each — nothing but the wire format is shared
//! between the two.
//!
//! This client's profile carries one resolver and one authoritative address;
//! Android's carries a list, since the tunnel binary accepts either as
//! repeatable flags. A link built on Android with more than one of either
//! loses the extras coming into a profile here — there is nowhere for them to
//! go — and the caller is told so rather than have them vanish silently.

use base64::Engine;
use serde::{Deserialize, Serialize};

use crate::config::{Profile, CONGESTION_CONTROLS};

const PREFIX: &str = "slipstream://p?v=1&d=";

#[derive(Debug, Default, Serialize, Deserialize)]
struct SharePayload {
    n: String,
    d: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    r: Vec<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    a: Vec<String>,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    c: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    cc: Option<String>,
    #[serde(default, skip_serializing_if = "is_false")]
    g: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    k: Option<u32>,
}

fn is_false(value: &bool) -> bool {
    !value
}

/// The new-profile default this client already uses when nothing else is
/// known; imported profiles start here too and get edited like any other.
const DEFAULT_LISTEN_PORT: u16 = 1080;

pub fn encode(profile: &Profile) -> String {
    let payload = SharePayload {
        n: profile.name.clone(),
        d: profile.domain.clone(),
        r: one_or_none(&profile.resolver),
        a: one_or_none(&profile.authoritative),
        c: profile.cert.clone(),
        cc: Some(profile.congestion_control.clone()),
        g: profile.gso,
        k: Some(profile.keep_alive_ms),
    };
    // A struct built from a handful of plain strings and numbers cannot fail
    // to serialize; unwrapping keeps every caller from having to handle an
    // error that has no real cause.
    let json = serde_json::to_vec(&payload).expect("share payload always serializes");
    let encoded = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(json);
    format!("{PREFIX}{encoded}")
}

fn one_or_none(value: &str) -> Vec<String> {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        Vec::new()
    } else {
        vec![trimmed.to_string()]
    }
}

pub struct Decoded {
    pub profile: Profile,
    /// Set when the link carried more than this client can hold, so the
    /// caller can say what was left out rather than lose it in silence.
    pub note: Option<String>,
}

pub fn decode(text: &str) -> Result<Decoded, String> {
    let text = text.trim();
    let encoded = text
        .strip_prefix(PREFIX)
        .ok_or("That doesn't look like a slipstream profile link")?;
    let json = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(encoded)
        .map_err(|_| "Could not decode the link")?;
    let payload: SharePayload =
        serde_json::from_slice(&json).map_err(|_| "The link's contents were not a profile")?;

    let mut dropped = Vec::new();
    let mut take_first = |values: &[String], what: &str| -> String {
        if values.len() > 1 {
            dropped.push(format!("{} {what}", values.len()));
        }
        values.first().cloned().unwrap_or_default()
    };
    let resolver = take_first(&payload.r, "resolvers");
    let authoritative = take_first(&payload.a, "authoritative servers");
    let note = if dropped.is_empty() {
        None
    } else {
        Some(format!(
            "This code named {}; only the first of each is kept.",
            dropped.join(" and ")
        ))
    };

    let profile = Profile {
        id: String::new(),
        name: payload.n,
        domain: payload.d,
        resolver,
        cert: payload.c,
        listen_port: DEFAULT_LISTEN_PORT,
        socks_username: String::new(),
        socks_password: String::new(),
        congestion_control: payload
            .cc
            .filter(|value| CONGESTION_CONTROLS.contains(&value.as_str()))
            .unwrap_or_else(|| "bbr".into()),
        gso: payload.g,
        keep_alive_ms: payload.k.unwrap_or(400).clamp(100, 60_000),
        authoritative,
    };
    profile.validate()?;
    Ok(Decoded { profile, note })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn profile() -> Profile {
        Profile {
            id: "abc".into(),
            name: "Home".into(),
            domain: "t.example.com".into(),
            resolver: "1.1.1.1:53".into(),
            cert: String::new(),
            listen_port: 1080,
            socks_username: String::new(),
            socks_password: String::new(),
            congestion_control: "bbr".into(),
            gso: false,
            keep_alive_ms: 400,
            authoritative: String::new(),
        }
    }

    #[test]
    fn a_profile_round_trips_through_the_link() {
        let link = encode(&profile());
        assert!(link.starts_with(PREFIX));
        let decoded = decode(&link).unwrap();
        assert_eq!(decoded.profile.name, "Home");
        assert_eq!(decoded.profile.domain, "t.example.com");
        assert_eq!(decoded.profile.resolver, "1.1.1.1:53");
        assert_eq!(decoded.profile.listen_port, 1080);
        assert!(decoded.profile.id.is_empty());
        assert!(decoded.note.is_none());
    }

    #[test]
    fn identity_never_rides_along() {
        // The desktop-only id, port and proxy credentials are never encoded,
        // so importing a link never carries them across from whichever
        // machine it was shared from.
        let mut p = profile();
        p.socks_username = "carol".into();
        p.socks_password = "hunter2".into();
        let link = encode(&p);
        assert!(!link.contains("carol"));
        assert!(!link.contains("hunter2"));
    }

    #[test]
    fn a_link_with_extra_resolvers_keeps_the_first_and_says_so() {
        let json = serde_json::json!({
            "n": "Many", "d": "t.example.com",
            "r": ["1.1.1.1:53", "8.8.8.8:53"],
        });
        let encoded =
            base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(json.to_string());
        let decoded = decode(&format!("{PREFIX}{encoded}")).unwrap();
        assert_eq!(decoded.profile.resolver, "1.1.1.1:53");
        assert!(decoded.note.unwrap().contains("2 resolvers"));
    }

    #[test]
    fn garbage_is_refused_rather_than_partially_imported() {
        assert!(decode("not a link at all").is_err());
        assert!(decode("slipstream://p?v=1&d=not-base64!!!").is_err());
    }

    #[test]
    fn an_invalid_domain_is_refused_by_the_same_validation_as_the_form() {
        let json = serde_json::json!({ "n": "Bad", "d": "not a domain", "r": ["1.1.1.1:53"] });
        let encoded =
            base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(json.to_string());
        assert!(decode(&format!("{PREFIX}{encoded}")).is_err());
    }
}
