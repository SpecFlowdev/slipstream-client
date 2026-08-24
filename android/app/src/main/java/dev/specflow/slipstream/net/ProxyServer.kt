package dev.specflow.slipstream.net

import android.util.Log
import dev.specflow.slipstream.core.Action
import dev.specflow.slipstream.core.RuleSet
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The local SOCKS5 server the packet bridge talks to.
 *
 * Slipstream itself is a plain forwarder: everything written to its local port
 * comes out of one TCP stream at the server, where a SOCKS5 proxy is
 * listening. So this server takes a request from the bridge, opens a fresh
 * connection through the tunnel, performs the proxy handshake on the far side
 * itself, and then splices the two together.
 *
 * Being in the middle is what makes the rest of the app possible: the
 * destination is known before a byte of payload moves, so it can be counted,
 * attributed, and refused.
 *
 * ## UDP
 *
 * The tunnel carries TCP streams and nothing else, so UDP cannot cross it as
 * UDP. Rather than pretend otherwise, [UdpRelay] answers the one kind of UDP
 * that everything depends on — DNS — by asking the same question over TCP
 * (RFC 1035 §4.2.2, a two-byte length in front of the message). Other
 * datagrams are dropped, which is why QUIC falls back to TCP and keeps working.
 */
class ProxyServer(
    private val tunnelPort: Int,
    private val rules: RuleSet,
    val registry: Registry,
) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var udp: UdpRelay? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "socks").apply { isDaemon = true }
    }

    var port: Int = 0
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val socket = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
        server = socket
        port = socket.localPort
        udp = UdpRelay().also { it.start() }
        pool.execute { acceptLoop(socket) }
        Log.i(TAG, "listening on 127.0.0.1:$port, tunnel at 127.0.0.1:$tunnelPort")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { server?.close() }
        udp?.stop()
        pool.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (running.get()) Log.w(TAG, "accept failed", e)
                return
            }
            pool.execute { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        val id = registry.open()
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            val input = DataInputStream(client.getInputStream().buffered())
            val output = client.getOutputStream()

            if (!Socks5.serverGreeting(input, output)) return
            val request = Socks5.readRequest(input)

            when (request.command) {
                Socks5.CMD_UDP_ASSOCIATE -> {
                    // The bridge sends datagrams to the port named here and
                    // keeps this stream open for as long as it wants them
                    // handled, so hold it until it closes.
                    val relay = udp
                    if (relay == null) {
                        output.write(Socks5.reply(Socks5.REP_FAILURE)); output.flush(); return
                    }
                    output.write(
                        Socks5.reply(Socks5.REP_OK, Socks5.Address("127.0.0.1", relay.port))
                    )
                    output.flush()
                    while (running.get() && input.read() >= 0) { /* wait for the close */ }
                    return
                }

                Socks5.CMD_CONNECT -> Unit

                else -> {
                    output.write(Socks5.reply(Socks5.REP_CMD_UNSUPPORTED)); output.flush(); return
                }
            }

            val target = request.address
            registry.name(id, target)

            if (rules.decide(target.host) == Action.BLOCK) {
                rules.refuse()
                registry.markBlocked(id)
                // A refusal has a code of its own in this protocol, so unlike
                // the desktop's pass-through relay it can be reported properly
                // rather than as an unexplained close.
                output.write(Socks5.reply(Socks5.REP_NOT_ALLOWED))
                output.flush()
                Log.i(TAG, "refused ${target.host} by a routing rule")
                return
            }

            val up = openThroughTunnel(target)
            if (up == null) {
                output.write(Socks5.reply(Socks5.REP_HOST_UNREACHABLE)); output.flush(); return
            }
            upstream = up

            output.write(Socks5.reply(Socks5.REP_OK))
            output.flush()

            val down = Thread({
                copy(up.getInputStream(), client.getOutputStream()) { registry.countDown(id, it) }
                closeQuietly(client, up)
            }, "socks-down").apply { isDaemon = true }
            down.start()

            copy(input, up.getOutputStream()) { registry.countUp(id, it) }
        } catch (e: IOException) {
            // Connections end; that is not news above debug level.
            Log.d(TAG, "connection $id ended: ${e.message}")
        } finally {
            closeQuietly(client, upstream)
            registry.close(id)
        }
    }

    /**
     * One tunnelled stream, handshaken with the proxy on the far side.
     * Returns null when the proxy refuses, so the caller can say so properly.
     */
    private fun openThroughTunnel(to: Socks5.Address): Socket? {
        val up = Socket()
        return try {
            up.tcpNoDelay = true
            up.connect(InetSocketAddress("127.0.0.1", tunnelPort), CONNECT_TIMEOUT_MS)
            val code = Socks5.clientHandshake(
                DataInputStream(up.getInputStream()), up.getOutputStream(), to
            )
            if (code != Socks5.REP_OK) {
                Log.d(TAG, "proxy refused $to with code $code")
                closeQuietly(up)
                null
            } else {
                up
            }
        } catch (e: IOException) {
            Log.d(TAG, "could not reach $to: ${e.message}")
            closeQuietly(up)
            null
        }
    }

    private inline fun copy(input: InputStream, output: OutputStream, counted: (Int) -> Unit) {
        val buf = ByteArray(BUFFER)
        try {
            while (true) {
                val read = input.read(buf)
                if (read <= 0) return
                output.write(buf, 0, read)
                output.flush()
                counted(read)
            }
        } catch (_: IOException) {
        }
    }

    private fun closeQuietly(vararg sockets: Socket?) {
        for (s in sockets) runCatching { s?.close() }
    }

    /**
     * The UDP half of SOCKS5, narrowed to what the tunnel can actually carry.
     *
     * Each datagram arrives wrapped in the RFC 1928 §7 header. If it is bound
     * for port 53 the question is re-asked over TCP through the tunnel and the
     * answer sent back the same way it came. Anything else is dropped in
     * silence: there is no honest way to carry it, and pretending would only
     * produce connections that hang instead of failing.
     */
    private inner class UdpRelay {
        private val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        private val alive = AtomicBoolean(true)

        fun start() {
            pool.execute {
                val buf = ByteArray(UDP_BUFFER)
                while (alive.get()) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (e: IOException) {
                        if (alive.get()) Log.d(TAG, "udp receive ended: ${e.message}")
                        return@execute
                    }
                    val copy = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    val from = InetSocketAddress(packet.address, packet.port)
                    pool.execute { handle(copy, from) }
                }
            }
        }

        private fun handle(datagram: ByteArray, from: InetSocketAddress) {
            // RSV RSV FRAG then the address, then the payload.
            if (datagram.size < 7 || datagram[2] != 0x00.toByte()) return
            val input = DataInputStream(datagram.inputStream().also { it.skip(3) })
            val target = try {
                Socks5.readAddress(input)
            } catch (_: IOException) {
                return
            }
            val headerLength = datagram.size - input.available()
            val payload = datagram.copyOfRange(headerLength, datagram.size)

            if (target.port != 53) return
            if (rules.decide(target.host) == Action.BLOCK) {
                rules.refuse()
                return
            }

            val answer = askOverTcp(target, payload) ?: return
            val reply = byteArrayOf(0, 0, 0) + Socks5.encodeAddress(target) + answer
            runCatching {
                socket.send(DatagramPacket(reply, reply.size, from.address, from.port))
            }
        }

        /** RFC 1035 §4.2.2: the same message, with its length in front. */
        private fun askOverTcp(server: Socks5.Address, question: ByteArray): ByteArray? {
            // Whatever nameserver the query was addressed to is the one asked,
            // so a resolver chosen in settings is the resolver actually used.
            val up = openThroughTunnel(server) ?: return null
            return try {
                up.soTimeout = DNS_TIMEOUT_MS
                val out = up.getOutputStream()
                out.write(byteArrayOf((question.size ushr 8).toByte(), question.size.toByte()))
                out.write(question)
                out.flush()
                registry.countUp(0, question.size + 2)

                val input = DataInputStream(up.getInputStream())
                val length = input.readUnsignedShort()
                if (length == 0 || length > UDP_BUFFER) return null
                val answer = ByteArray(length)
                input.readFully(answer)
                registry.countDown(0, length + 2)
                answer
            } catch (e: IOException) {
                Log.d(TAG, "dns over tcp failed: ${e.message}")
                null
            } finally {
                closeQuietly(up)
            }
        }

        fun stop() {
            alive.set(false)
            socket.close()
        }
    }

    private companion object {
        const val TAG = "SlipstreamProxy"
        const val BUFFER = 32 * 1024
        const val UDP_BUFFER = 65535
        const val BACKLOG = 256
        const val CONNECT_TIMEOUT_MS = 10_000
        const val DNS_TIMEOUT_MS = 8_000
    }
}
