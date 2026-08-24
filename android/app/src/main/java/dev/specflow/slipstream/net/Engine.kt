package dev.specflow.slipstream.net

import android.content.Context
import android.util.Log
import dev.specflow.slipstream.core.Congestion
import dev.specflow.slipstream.core.Profile
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import kotlin.concurrent.thread

/**
 * The native processes, started and watched.
 *
 * Android will not execute a file out of an app's data directory, so both
 * native programs ship as `lib*.so` in the APK's library directory, which is
 * the one place an app's own binaries are allowed to run from. That is also
 * why the manifest asks for legacy packaging: a library that is only mapped
 * out of the APK has no path to execute.
 */
class Engine(private val context: Context) {

    /** Every line either program wrote, newest last, capped. */
    private val lines = ArrayDeque<String>()

    private var tunnel: Process? = null
    private val bridge = Bridge()

    @Volatile
    var onDied: ((String) -> Unit)? = null

    fun log(): List<String> = synchronized(lines) { lines.toList() }

    fun clearLog() = synchronized(lines) { lines.clear() }

    private fun record(line: String) {
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > LOG_LINES) lines.removeFirst()
        }
    }

    private fun binary(name: String): File =
        File(context.applicationInfo.nativeLibraryDir, name)

    fun available(): Boolean = binary(TUNNEL).canExecute() && Bridge.available

    /**
     * Starts slipstream on a loopback port and returns that port.
     *
     * The port is picked here rather than left to the tunnel so the proxy in
     * front of it knows where to forward before anything is running.
     */
    fun startTunnel(profile: Profile, port: Int) {
        val args = mutableListOf(
            binary(TUNNEL).absolutePath,
            "--tcp-listen-host", "127.0.0.1",
            "--tcp-listen-port", port.toString(),
            "--domain", profile.domain,
            "--keep-alive-interval", profile.keepAliveMs.toString(),
        )
        for (resolver in profile.resolvers) { args += "--resolver"; args += resolver }
        for (address in profile.authoritative) { args += "--authoritative"; args += address }
        when (profile.congestion) {
            Congestion.BBR -> { args += "--congestion-control"; args += "bbr" }
            Congestion.DCUBIC -> { args += "--congestion-control"; args += "dcubic" }
            // Left off, so authoritative paths keep their bbr default and
            // recursive ones keep dcubic.
            Congestion.DEFAULT -> Unit
        }
        if (profile.cert.isNotBlank()) {
            val pem = File(context.filesDir, "pinned.pem")
            pem.writeText(profile.cert)
            args += "--cert"; args += pem.absolutePath
        }
        tunnel = spawn("tunnel", args)
    }

    /**
     * Starts the packet bridge on the interface Android handed us.
     *
     * Only `socks5.address` and `socks5.port` are required; the tunnel section
     * that would describe an interface is left out because we are handing it
     * one that already exists.
     */
    fun startBridge(tunFd: Int, socksPort: Int, mtu: Int): Boolean {
        val config = File(context.filesDir, "bridge.yaml")
        config.writeText(
            """
            tunnel:
              mtu: $mtu
            socks5:
              address: 127.0.0.1
              port: $socksPort
              udp: 'udp'
            misc:
              task-stack-size: 20480
              log-level: warn
            """.trimIndent()
        )
        record("$ bridge ${config.name} fd=$tunFd socks=$socksPort mtu=$mtu")
        val started = runCatching { bridge.TProxyStartService(config.path, tunFd) }
            .onFailure { record("[bridge] could not start: ${it.message}") }
            .getOrDefault(false)
        if (!started) record("[bridge] refused to start")
        return started
    }

    private fun spawn(label: String, args: List<String>): Process {
        Log.i(TAG, "$label: ${args.joinToString(" ")}")
        record("$ ${args.joinToString(" ")}")
        val process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .directory(context.filesDir)
            .start()
        thread(isDaemon = true, name = "engine-$label") {
            try {
                process.inputStream.bufferedReader().forEachLineSafely { record("[$label] $it") }
            } catch (_: IOException) {
            }
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            record("[$label] exited with $code")
            // A tunnel that stops is the whole session stopping, so say so
            // rather than leaving an interface that claims to be connected.
            onDied?.invoke("$label exited with $code")
        }
        return process
    }

    private inline fun BufferedReader.forEachLineSafely(action: (String) -> Unit) {
        use { reader ->
            while (true) {
                val line = reader.readLine() ?: return
                action(line)
            }
        }
    }

    fun stop() {
        onDied = null
        runCatching { bridge.TProxyStopService() }
        runCatching { tunnel?.destroy() }
        tunnel = null
    }

    companion object {
        private const val TAG = "SlipstreamEngine"
        private const val LOG_LINES = 500

        /** The name the build gives the tunnel inside the APK. */
        const val TUNNEL = "libslipstream.so"
    }
}
