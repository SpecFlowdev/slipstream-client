package dev.specflow.slipstream.net

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Just enough of SOCKS5 (RFC 1928) to be both ends of it.
 *
 * The app speaks this twice over: once as a server, to the packet bridge that
 * turns the VPN's captured packets into proxy requests, and once as a client,
 * to the proxy sitting at the far end of the tunnel.
 */
object Socks5 {
    val VERSION = 0x05.toByte()

    val NO_AUTH = 0x00.toByte()
    val NO_ACCEPTABLE = 0xFF.toByte()

    val CMD_CONNECT = 0x01.toByte()
    val CMD_BIND = 0x02.toByte()
    val CMD_UDP_ASSOCIATE = 0x03.toByte()

    val ATYP_IPV4 = 0x01.toByte()
    val ATYP_HOST = 0x03.toByte()
    val ATYP_IPV6 = 0x04.toByte()

    val REP_OK = 0x00.toByte()
    val REP_FAILURE = 0x01.toByte()
    val REP_NOT_ALLOWED = 0x02.toByte()
    val REP_HOST_UNREACHABLE = 0x04.toByte()
    val REP_CMD_UNSUPPORTED = 0x07.toByte()

    data class Address(val host: String, val port: Int) {
        override fun toString() = if (host.contains(':')) "[$host]:$port" else "$host:$port"
    }

    data class Request(val command: Byte, val address: Address)

    /** Reads the client's greeting and answers it. Only "no authentication" is offered. */
    @Throws(IOException::class)
    fun serverGreeting(input: DataInputStream, output: OutputStream): Boolean {
        if (input.readByte() != VERSION) return false
        val count = input.readUnsignedByte()
        val methods = ByteArray(count)
        input.readFully(methods)
        val ok = methods.any { it == NO_AUTH }
        output.write(byteArrayOf(VERSION, if (ok) NO_AUTH else NO_ACCEPTABLE))
        output.flush()
        return ok
    }

    @Throws(IOException::class)
    fun readRequest(input: DataInputStream): Request {
        if (input.readByte() != VERSION) throw IOException("not a SOCKS5 request")
        val command = input.readByte()
        input.readByte() // reserved
        val address = readAddress(input)
        return Request(command, address)
    }

    @Throws(IOException::class)
    fun readAddress(input: DataInputStream): Address {
        val host = when (val atyp = input.readByte()) {
            ATYP_IPV4 -> {
                val raw = ByteArray(4); input.readFully(raw)
                raw.joinToString(".") { (it.toInt() and 0xff).toString() }
            }
            ATYP_HOST -> {
                val len = input.readUnsignedByte()
                val raw = ByteArray(len); input.readFully(raw)
                String(raw, Charsets.US_ASCII)
            }
            ATYP_IPV6 -> {
                val raw = ByteArray(16); input.readFully(raw)
                (0 until 8).joinToString(":") {
                    Integer.toHexString(((raw[it * 2].toInt() and 0xff) shl 8) or (raw[it * 2 + 1].toInt() and 0xff))
                }
            }
            else -> throw IOException("unknown address type $atyp")
        }
        val port = input.readUnsignedShort()
        return Address(host, port)
    }

    fun encodeAddress(address: Address): ByteArray {
        val ipv4 = parseIpv4(address.host)
        val body = when {
            ipv4 != null -> byteArrayOf(ATYP_IPV4) + ipv4
            address.host.contains(':') -> byteArrayOf(ATYP_IPV6) + parseIpv6(address.host)
            else -> {
                val name = address.host.toByteArray(Charsets.US_ASCII)
                byteArrayOf(ATYP_HOST, name.size.toByte()) + name
            }
        }
        return body + byteArrayOf((address.port ushr 8).toByte(), address.port.toByte())
    }

    /** A reply carrying no meaningful bound address, which is all a client needs here. */
    fun reply(code: Byte, address: Address = Address("0.0.0.0", 0)): ByteArray =
        byteArrayOf(VERSION, code, 0x00) + encodeAddress(address)

    /** Performs the client side of a handshake on an already-connected stream. */
    @Throws(IOException::class)
    fun clientHandshake(input: DataInputStream, output: OutputStream, to: Address): Byte {
        output.write(byteArrayOf(VERSION, 1, NO_AUTH))
        output.flush()
        if (input.readByte() != VERSION) throw IOException("proxy is not SOCKS5")
        if (input.readByte() != NO_AUTH) throw IOException("proxy wants authentication")

        output.write(byteArrayOf(VERSION, CMD_CONNECT, 0x00) + encodeAddress(to))
        output.flush()

        if (input.readByte() != VERSION) throw IOException("bad reply from the proxy")
        val code = input.readByte()
        input.readByte() // reserved
        readAddress(input) // bound address, unused
        return code
    }

    private fun parseIpv4(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (i in 0 until 4) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            out[i] = n.toByte()
        }
        return out
    }

    /** Only the plain, fully written form; the bridge never sends anything else. */
    private fun parseIpv6(host: String): ByteArray {
        val out = ByteArray(16)
        val groups = host.split(':')
        if (groups.size != 8) return out
        for (i in 0 until 8) {
            val n = groups[i].toIntOrNull(16) ?: 0
            out[i * 2] = (n ushr 8).toByte()
            out[i * 2 + 1] = n.toByte()
        }
        return out
    }
}
