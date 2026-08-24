package dev.specflow.slipstream.core

import kotlinx.serialization.Serializable

/**
 * The stored shape of the app. It is deliberately the same shape the desktop
 * client stores, so a profile written on one can be typed into the other
 * without translation.
 *
 * Every field added after the first release carries a default, so a store
 * written by an older build still loads.
 */

@Serializable
enum class Congestion { BBR, DCUBIC, DEFAULT }

@Serializable
data class Profile(
    val name: String,
    /** The domain the tunnel is served under. */
    val domain: String = "",
    /** Recursive resolvers, `ip:port`, tried in order. */
    val resolvers: List<String> = emptyList(),
    /** Resolvers that are the tunnel's own server, polled rather than queried. */
    val authoritative: List<String> = emptyList(),
    /** PEM leaf certificate for strict pinning; empty means no pinning. */
    val cert: String = "",
    val congestion: Congestion = Congestion.DEFAULT,
    val keepAliveMs: Int = 400,
    val gso: Boolean = false,
) {
    /** Nothing is worth starting without at least one path and a domain. */
    fun problem(): String? = when {
        name.isBlank() -> "A profile needs a name"
        domain.isBlank() -> "A profile needs a domain"
        resolvers.isEmpty() && authoritative.isEmpty() ->
            "Add at least one resolver or authoritative address"
        else -> null
    }
}

@Serializable
enum class Theme { DARK, LIGHT, BLUE }

@Serializable
data class Settings(
    val theme: Theme = Theme.DARK,
    /** A picked image, copied into app storage; empty means none. */
    val wallpaper: String = "",
    /** How far the wallpaper is dimmed, 0-100. */
    val wallpaperDim: Int = 45,
    /** Blur radius in dp, 0 for none. */
    val wallpaperBlur: Int = 0,
    val animations: Boolean = true,
    /** Reconnect when the tunnel dies rather than giving up. */
    val autoReconnect: Boolean = true,
    /**
     * Refuse to carry traffic outside the tunnel. Android enforces this for
     * real, unlike a desktop proxy setting: see the service.
     */
    val killSwitch: Boolean = true,
    /** Route IPv6 into the tunnel as well as IPv4. */
    val ipv6: Boolean = true,
    val mtu: Int = 1500,
    /** Empty means every app. Otherwise only these packages are tunneled. */
    val allowedApps: List<String> = emptyList(),
    /** Packages kept out of the tunnel. Ignored when [allowedApps] is set. */
    val blockedApps: List<String> = emptyList(),
    val startOnBoot: Boolean = false,
)

@Serializable
data class SessionRecord(
    val endedMs: Long,
    val profileName: String,
    val seconds: Long,
    val bytesUp: Long,
    val bytesDown: Long,
    val peakRateDown: Long,
    val connections: Long,
)

@Serializable
data class StoreFile(
    val profiles: List<Profile> = emptyList(),
    val activeProfile: String = "",
    val settings: Settings = Settings(),
    val rules: List<Rule> = emptyList(),
    val history: List<SessionRecord> = emptyList(),
)

/** Old sessions are worth keeping, but not without limit. */
const val HISTORY_CAPACITY = 100
