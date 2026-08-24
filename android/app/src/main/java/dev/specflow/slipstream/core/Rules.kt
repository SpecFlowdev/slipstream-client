package dev.specflow.slipstream.core

import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-destination routing rules.
 *
 * The meter learns the host every SOCKS5 connection is for, which means it can
 * also decide whether that connection should proceed at all. That is the whole
 * of this file: a small ordered list of patterns matched against the
 * destination, first match winning.
 *
 * This mirrors the desktop client's `rules.rs` deliberately, down to the tests,
 * so a rule list written on one behaves the same on the other.
 */
enum class Action { ALLOW, BLOCK }

@Serializable
data class Rule(
    /**
     * One of:
     *   `example.com`    matches that host exactly
     *   `*.example.com`  matches any subdomain of it, but not it
     *   `*`              matches everything, useful as a final default
     */
    val pattern: String,
    val action: Action,
    /** Off without deleting, so a rule can be tried and put back. */
    val enabled: Boolean = true,
    /** Free text, for remembering why a rule is there. */
    val note: String = "",
) {
    fun validate(): String? {
        val p = pattern.trim()
        if (p.isEmpty()) return "A rule needs a pattern"
        if (p.length > 255) return "Pattern is too long"
        // A `*` is only meaningful as the whole pattern or as a leading label.
        // Anywhere else it silently would not do what it looks like it does.
        if (p != "*" && p.contains('*') && !p.startsWith("*.")) {
            return "Use * on its own, or a leading *. as in *.example.com"
        }
        if (p.startsWith("*.") && p.substring(2).contains('*')) {
            return "Only one * is allowed, at the start"
        }
        return null
    }

    internal fun matches(host: String): Boolean {
        val p = pattern.trim()
        if (p == "*") return true
        if (p.startsWith("*.")) {
            val suffix = p.substring(2)
            // Subdomains only: `*.example.com` covers `a.example.com` but not
            // `example.com` itself, and must not match `notexample.com`.
            return host.length > suffix.length &&
                host.endsWith(suffix) &&
                host[host.length - suffix.length - 1] == '.'
        }
        return host == p
    }
}

class RuleSet(rules: List<Rule> = emptyList()) {
    @Volatile
    private var rules: List<Rule> = rules

    private val blocked = AtomicLong(0)

    fun replace(next: List<Rule>) {
        rules = next
    }

    /**
     * Case is not significant in host names, so matching folds it. Any trailing
     * dot on a fully qualified name is dropped first, since `example.com.` and
     * `example.com` are the same destination.
     */
    fun decide(host: String): Action {
        val needle = host.trimEnd('.').lowercase()
        for (rule in rules) {
            if (rule.enabled && rule.matches(needle)) return rule.action
        }
        return Action.ALLOW
    }

    /**
     * Records and reports a refusal in one step, so callers cannot count one
     * without the other.
     */
    fun refuse(): Long = blocked.incrementAndGet()

    fun blockedCount(): Long = blocked.get()

    fun reset() = blocked.set(0)
}

/** Lists offered in the interface, so a useful rule set is one tap away. */
object StarterRules {
    val trackers = listOf(
        "*.doubleclick.net",
        "*.googlesyndication.com",
        "*.google-analytics.com",
        "*.scorecardresearch.com",
        "*.adservice.google.com",
    ).map { Rule(it, Action.BLOCK, note = "Trackers") }

    val telemetry = listOf(
        "*.crashlytics.com",
        "*.app-measurement.com",
        "*.branch.io",
        "*.appsflyer.com",
    ).map { Rule(it, Action.BLOCK, note = "Telemetry") }
}
