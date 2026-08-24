//! Per-destination routing rules.
//!
//! The relay learns the host every SOCKS5 connection is for (see `traffic`),
//! which means it can also decide whether that connection should be allowed
//! to proceed at all. That is the whole of this module: a small ordered list
//! of patterns, matched against the destination, first match winning.
//!
//! Blocking happens at the point the destination becomes known — after the
//! greeting has already been forwarded but before any payload moves — and
//! takes the form of closing the connection. Replying with the SOCKS5
//! "connection not allowed" code would be tidier, but the client's write half
//! belongs to the other copy direction by then; a closed connection is
//! reported clearly enough by every SOCKS client worth the name.

use std::sync::atomic::{AtomicU64, Ordering};

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Action {
    Allow,
    Block,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Rule {
    /// One of:
    ///   `example.com`    matches that host exactly
    ///   `*.example.com`  matches any subdomain of it, but not it
    ///   `*`              matches everything, useful as a final default
    pub pattern: String,
    pub action: Action,
    /// Off without deleting, so a rule can be tried and put back.
    #[serde(default = "yes")]
    pub enabled: bool,
    /// Free text, for remembering why a rule is there.
    #[serde(default)]
    pub note: String,
}

fn yes() -> bool {
    true
}

impl Rule {
    pub fn validate(&self) -> Result<(), String> {
        let pattern = self.pattern.trim();
        if pattern.is_empty() {
            return Err("A rule needs a pattern".into());
        }
        if pattern.len() > 255 {
            return Err("Pattern is too long".into());
        }
        // A `*` is only meaningful as the whole pattern or as a leading label.
        // Anywhere else it silently would not do what it looks like it does.
        if pattern != "*" && pattern.contains('*') && !pattern.starts_with("*.") {
            return Err("Use `*` on its own, or a leading `*.` as in `*.example.com`".into());
        }
        if pattern.starts_with("*.") && pattern[2..].contains('*') {
            return Err("Only one `*` is allowed, at the start".into());
        }
        Ok(())
    }

    fn matches(&self, host: &str) -> bool {
        let pattern = self.pattern.trim();
        if pattern == "*" {
            return true;
        }
        if let Some(suffix) = pattern.strip_prefix("*.") {
            // Subdomains only: `*.example.com` covers `a.example.com` but not
            // `example.com` itself, and must not match `notexample.com`.
            return host.len() > suffix.len()
                && host.ends_with(suffix)
                && host.as_bytes()[host.len() - suffix.len() - 1] == b'.';
        }
        host == pattern
    }
}

#[derive(Debug, Default)]
pub struct RuleSet {
    rules: Vec<Rule>,
    /// Connections refused this session, for the interface to show.
    pub blocked: AtomicU64,
}

impl RuleSet {
    pub fn new(rules: Vec<Rule>) -> Self {
        Self {
            rules,
            blocked: AtomicU64::new(0),
        }
    }

    pub fn replace(&mut self, rules: Vec<Rule>) {
        self.rules = rules;
    }

    /// Case is not significant in host names, so matching folds it. Any
    /// trailing dot on a fully qualified name is dropped first, since
    /// `example.com.` and `example.com` are the same destination.
    pub fn decide(&self, host: &str) -> Action {
        let host = host.trim_end_matches('.').to_ascii_lowercase();
        for rule in self.rules.iter().filter(|r| r.enabled) {
            if rule.matches(&host) {
                return rule.action;
            }
        }
        Action::Allow
    }

    /// Records and reports a refusal in one step, so callers cannot count one
    /// without the other.
    pub fn refuse(&self) -> u64 {
        self.blocked.fetch_add(1, Ordering::Relaxed) + 1
    }

    pub fn blocked_count(&self) -> u64 {
        self.blocked.load(Ordering::Relaxed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rule(pattern: &str, action: Action) -> Rule {
        Rule {
            pattern: pattern.into(),
            action,
            enabled: true,
            note: String::new(),
        }
    }

    #[test]
    fn an_exact_pattern_matches_only_that_host() {
        let set = RuleSet::new(vec![rule("example.com", Action::Block)]);
        assert_eq!(set.decide("example.com"), Action::Block);
        assert_eq!(set.decide("a.example.com"), Action::Allow);
        assert_eq!(set.decide("notexample.com"), Action::Allow);
    }

    #[test]
    fn a_wildcard_matches_subdomains_but_not_the_domain_itself() {
        let set = RuleSet::new(vec![rule("*.example.com", Action::Block)]);
        assert_eq!(set.decide("a.example.com"), Action::Block);
        assert_eq!(set.decide("deep.nested.example.com"), Action::Block);
        assert_eq!(set.decide("example.com"), Action::Allow);
        // The classic off-by-one: a suffix match alone would block this.
        assert_eq!(set.decide("notexample.com"), Action::Allow);
        assert_eq!(set.decide("evilexample.com"), Action::Allow);
    }

    #[test]
    fn the_first_matching_rule_wins() {
        // An allow ahead of a broad block is how an exception is written.
        let set = RuleSet::new(vec![
            rule("good.ads.example", Action::Allow),
            rule("*.ads.example", Action::Block),
        ]);
        assert_eq!(set.decide("good.ads.example"), Action::Allow);
        assert_eq!(set.decide("other.ads.example"), Action::Block);
    }

    #[test]
    fn a_bare_star_blocks_everything_it_reaches() {
        let set = RuleSet::new(vec![
            rule("keep.example", Action::Allow),
            rule("*", Action::Block),
        ]);
        assert_eq!(set.decide("keep.example"), Action::Allow);
        assert_eq!(set.decide("anything.at.all"), Action::Block);
    }

    #[test]
    fn matching_ignores_case_and_a_trailing_dot() {
        let set = RuleSet::new(vec![rule("example.com", Action::Block)]);
        assert_eq!(set.decide("EXAMPLE.COM"), Action::Block);
        assert_eq!(set.decide("example.com."), Action::Block);
        assert_eq!(set.decide("ExAmPlE.CoM."), Action::Block);
    }

    #[test]
    fn a_disabled_rule_is_skipped_without_being_deleted() {
        let mut off = rule("example.com", Action::Block);
        off.enabled = false;
        let set = RuleSet::new(vec![off]);
        assert_eq!(set.decide("example.com"), Action::Allow);
    }

    #[test]
    fn nothing_matching_means_allowed() {
        let set = RuleSet::new(vec![rule("blocked.example", Action::Block)]);
        assert_eq!(set.decide("something.else"), Action::Allow);
        assert_eq!(RuleSet::default().decide("anything"), Action::Allow);
    }

    #[test]
    fn ip_literals_match_exactly_like_any_other_host() {
        let set = RuleSet::new(vec![rule("10.0.0.1", Action::Block)]);
        assert_eq!(set.decide("10.0.0.1"), Action::Block);
        assert_eq!(set.decide("10.0.0.10"), Action::Allow);
    }

    #[test]
    fn refusals_are_counted() {
        let set = RuleSet::new(vec![rule("*", Action::Block)]);
        assert_eq!(set.blocked_count(), 0);
        assert_eq!(set.refuse(), 1);
        assert_eq!(set.refuse(), 2);
        assert_eq!(set.blocked_count(), 2);
    }

    #[test]
    fn validation_rejects_patterns_that_would_not_do_what_they_look_like() {
        assert!(rule("example.com", Action::Block).validate().is_ok());
        assert!(rule("*.example.com", Action::Block).validate().is_ok());
        assert!(rule("*", Action::Block).validate().is_ok());

        for bad in ["", "   ", "ex*ample.com", "*.a*.com", "example.*"] {
            assert!(
                rule(bad, Action::Block).validate().is_err(),
                "should reject {bad:?}"
            );
        }
    }
}
