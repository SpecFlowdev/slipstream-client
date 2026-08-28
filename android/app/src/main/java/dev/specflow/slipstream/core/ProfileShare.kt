package dev.specflow.slipstream.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Profile transfer by a compact link, meant to be shown or scanned as a QR
 * code.
 *
 * The payload is a small JSON object with single-letter keys, base64url
 * encoded into a `slipstream://p?v=1&d=...` link — kept letter-for-letter
 * identical to the desktop client's own `share.rs`, independently
 * implemented on each side. This app's own [Profile] carries a list of
 * resolvers and of authoritative addresses, since the tunnel accepts either
 * as a repeatable flag; the desktop client's holds one of each, so a link
 * built there always decodes here as a single-element list — never a reason
 * to drop anything, which is why only the desktop side of this pair needs to
 * report a note back.
 *
 * Encoding goes through the Kotlin standard library rather than
 * `android.util.Base64` on purpose: the platform one is a stub outside a real
 * device or Robolectric, so a plain JVM unit test calling it fails on "not
 * mocked" — an environment error with nothing to do with whether the codec
 * itself is right.
 */
@OptIn(ExperimentalEncodingApi::class)
object ProfileShare {

    private const val PREFIX = "slipstream://p?v=1&d="
    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    @Serializable
    private data class Payload(
        val n: String,
        val d: String,
        val r: List<String> = emptyList(),
        val a: List<String> = emptyList(),
        val c: String = "",
        val cc: String? = null,
        val g: Boolean = false,
        val k: Int? = null,
    )

    fun encode(profile: Profile): String {
        val payload = Payload(
            n = profile.name,
            d = profile.domain,
            r = profile.resolvers,
            a = profile.authoritative,
            c = profile.cert,
            cc = when (profile.congestion) {
                Congestion.BBR -> "bbr"
                Congestion.DCUBIC -> "dcubic"
                // Left out, the same way the desktop form's "per path"
                // option leaves the flag off: the tunnel picks BBR for
                // authoritative paths and dCUBIC for recursive ones.
                Congestion.DEFAULT -> null
            },
            g = profile.gso,
            k = profile.keepAliveMs,
        )
        val encoded = base64.encode(json.encodeToString(payload).toByteArray(Charsets.UTF_8))
        return PREFIX + encoded
    }

    /** What a successful [decode] hands back; there is no [Profile.problem] left unresolved. */
    data class Decoded(val profile: Profile)

    fun decode(text: String): Result<Decoded> = runCatching {
        val trimmed = text.trim()
        val encoded = trimmed.removePrefix(PREFIX)
        if (encoded == trimmed) {
            error("That doesn't look like a slipstream profile link")
        }
        val body = try {
            base64.decode(encoded).toString(Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Could not decode the link", e)
        }
        val payload = try {
            json.decodeFromString<Payload>(body)
        } catch (e: Exception) {
            throw IllegalArgumentException("The link's contents were not a profile", e)
        }

        val profile = Profile(
            name = payload.n,
            domain = payload.d,
            resolvers = payload.r,
            authoritative = payload.a,
            cert = payload.c,
            congestion = when (payload.cc) {
                "bbr" -> Congestion.BBR
                "dcubic" -> Congestion.DCUBIC
                else -> Congestion.DEFAULT
            },
            keepAliveMs = payload.k ?: 400,
            gso = payload.g,
        )
        profile.problem()?.let { throw IllegalArgumentException(it) }
        Decoded(profile)
    }

    /** True for anything worth trying to [decode] — cheap enough to call on every clipboard read. */
    fun looksLikeALink(text: String): Boolean = text.trim().startsWith(PREFIX)
}
