package dev.specflow.slipstream

import dev.specflow.slipstream.net.Socks5
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream

/**
 * The wire format, both directions.
 *
 * These are the bytes the packet bridge sends and the proxy at the far end
 * expects; getting one field's width wrong produces a tunnel that connects and
 * then reaches the wrong host, which no amount of testing at a higher level
 * would catch clearly.
 */
class Socks5Test {

    private fun reader(vararg bytes: Int) =
        DataInputStream(bytes.map { it.toByte() }.toByteArray().inputStream())

    @Test
    fun `a request for a host name is read back whole`() {
        val request = reader(
            0x05, 0x01, 0x00,
            0x03, 0x0B, // 11 characters
            'e'.code, 'x'.code, 'a'.code, 'm'.code, 'p'.code, 'l'.code, 'e'.code,
            '.'.code, 'c'.code, 'o'.code, 'm'.code,
            0x01, 0xBB, // 443
        )
        val parsed = Socks5.readRequest(request)
        assertEquals(Socks5.CMD_CONNECT, parsed.command)
        assertEquals("example.com", parsed.address.host)
        assertEquals(443, parsed.address.port)
    }

    @Test
    fun `an ipv4 request is read back whole`() {
        val parsed = Socks5.readRequest(
            reader(0x05, 0x01, 0x00, 0x01, 10, 0, 0, 7, 0x00, 0x50)
        )
        assertEquals("10.0.0.7", parsed.address.host)
        assertEquals(80, parsed.address.port)
    }

    @Test
    fun `a port above the signed byte range survives`() {
        // 0xEA60 is 60000, which sign-extends to a negative number if the
        // bytes are read as signed. Ports do not have signs.
        val parsed = Socks5.readRequest(
            reader(0x05, 0x01, 0x00, 0x01, 1, 2, 3, 4, 0xEA, 0x60)
        )
        assertEquals(60000, parsed.address.port)
    }

    @Test
    fun `an ipv6 request is read back whole`() {
        val parsed = Socks5.readRequest(
            reader(
                0x05, 0x01, 0x00, 0x04,
                0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01,
                0x00, 0x35,
            )
        )
        assertEquals("2001:db8:0:0:0:0:0:1", parsed.address.host)
        assertEquals(53, parsed.address.port)
    }

    @Test
    fun `an encoded host name round trips`() {
        val encoded = Socks5.encodeAddress(Socks5.Address("example.com", 443))
        val parsed = Socks5.readAddress(DataInputStream(encoded.inputStream()))
        assertEquals("example.com", parsed.host)
        assertEquals(443, parsed.port)
    }

    @Test
    fun `an address that looks like an ip is encoded as one`() {
        val encoded = Socks5.encodeAddress(Socks5.Address("1.1.1.1", 53))
        assertArrayEquals(byteArrayOf(0x01, 1, 1, 1, 1, 0x00, 0x35), encoded)
    }

    @Test
    fun `a greeting offering no authentication is accepted`() {
        val out = ByteArrayOutputStream()
        val ok = Socks5.serverGreeting(reader(0x05, 0x01, 0x00), out)
        assert(ok)
        assertArrayEquals(byteArrayOf(0x05, 0x00), out.toByteArray())
    }

    @Test
    fun `a greeting offering only authentication is refused`() {
        val out = ByteArrayOutputStream()
        val ok = Socks5.serverGreeting(reader(0x05, 0x01, 0x02), out)
        assert(!ok)
        assertArrayEquals(byteArrayOf(0x05, 0xFF.toByte()), out.toByteArray())
    }

    @Test
    fun `a reply names its code`() {
        val reply = Socks5.reply(Socks5.REP_NOT_ALLOWED)
        assertEquals(Socks5.VERSION, reply[0])
        assertEquals(Socks5.REP_NOT_ALLOWED, reply[1])
    }
}
