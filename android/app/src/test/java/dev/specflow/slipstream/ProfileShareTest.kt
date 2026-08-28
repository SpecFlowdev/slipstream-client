package dev.specflow.slipstream

import dev.specflow.slipstream.core.Congestion
import dev.specflow.slipstream.core.Profile
import dev.specflow.slipstream.core.ProfileShare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Mirrors the desktop client's `share.rs` tests case for case: the two are
 * independent implementations of the same wire format, and a wire format is
 * only really tested by two ends that never saw each other's code agreeing
 * on what a link means.
 */
@OptIn(ExperimentalEncodingApi::class)
class ProfileShareTest {

    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    private fun linkFromRawJson(json: String): String =
        "slipstream://p?v=1&d=" + base64.encode(json.toByteArray())

    private fun profile() = Profile(
        name = "Home",
        domain = "t.example.com",
        resolvers = listOf("1.1.1.1:53"),
    )

    @Test
    fun `a profile round trips through the link`() {
        val link = ProfileShare.encode(profile())
        assertTrue(link.startsWith("slipstream://p?v=1&d="))
        val decoded = ProfileShare.decode(link).getOrThrow()
        assertEquals("Home", decoded.profile.name)
        assertEquals("t.example.com", decoded.profile.domain)
        assertEquals(listOf("1.1.1.1:53"), decoded.profile.resolvers)
    }

    @Test
    fun `a link from a single-resolver desktop profile decodes as one`() {
        // What the desktop client's share.rs actually produces: r is always
        // zero or one element, never more, since its own Profile only ever
        // holds a single resolver and a single authoritative address.
        val link = linkFromRawJson("""{"n":"Desk","d":"t.example.com","r":["1.1.1.1:53"]}""")
        val decoded = ProfileShare.decode(link).getOrThrow()
        assertEquals(listOf("1.1.1.1:53"), decoded.profile.resolvers)
    }

    @Test
    fun `congestion control round trips, and DEFAULT is left out of the link`() {
        val bbr = ProfileShare.decode(ProfileShare.encode(profile().copy(congestion = Congestion.BBR)))
            .getOrThrow()
        assertEquals(Congestion.BBR, bbr.profile.congestion)

        val auto = ProfileShare.encode(profile().copy(congestion = Congestion.DEFAULT))
        val autoDecoded = ProfileShare.decode(auto).getOrThrow()
        assertEquals(Congestion.DEFAULT, autoDecoded.profile.congestion)
        assertTrue("\"cc\"" !in String(base64.decode(auto.removePrefix("slipstream://p?v=1&d="))))
    }

    @Test
    fun `identity never rides along`() {
        // Nothing desktop-only or Android-only about a specific device (a
        // local port, proxy credentials — neither of which this app's
        // Profile even has) is part of the payload; this only re-confirms
        // that a round trip returns exactly the fields the link carries.
        val link = ProfileShare.encode(profile().copy(cert = "-----BEGIN CERTIFICATE-----"))
        val decoded = ProfileShare.decode(link).getOrThrow()
        assertEquals("-----BEGIN CERTIFICATE-----", decoded.profile.cert)
    }

    @Test
    fun `an incomplete profile is refused rather than half imported`() {
        val link = linkFromRawJson("""{"n":"No domain","d":""}""")
        assertTrue(ProfileShare.decode(link).isFailure)
    }

    @Test
    fun `garbage is refused rather than throwing past the caller`() {
        assertTrue(ProfileShare.decode("not a link at all").isFailure)
        assertTrue(ProfileShare.decode("slipstream://p?v=1&d=not-base64!!!").isFailure)
    }

    @Test
    fun `looksLikeALink is a cheap pre-filter, not a validator`() {
        assertTrue(ProfileShare.looksLikeALink("slipstream://p?v=1&d=anything"))
        assertTrue(!ProfileShare.looksLikeALink("https://example.com"))
    }
}
