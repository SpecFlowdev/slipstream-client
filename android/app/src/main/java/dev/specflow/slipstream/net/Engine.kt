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
    private var bridge: Process? = null

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

    fun available(): Boolean = binary(TUNNEL).canExecute() && binary(BRIDGE).canExecute()

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
     * It reads the tun descriptor directly, so the descriptor has to survive
     * the fork: [ProcessBuilder] closes nothing it was not told to, and the
     * number is passed as an argument.
     */
    fun startBridge(tunFd: Int, socksPort: Int, mtu: Int) {
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
        bridge = spawn("bridge", listOf(binary(BRIDGE).absolutePath, config.absolutePath, tunFd.toString()))
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
        for (process in listOf(bridge, tunnel)) {
            runCatching { process?.destroy() }
        }
        bridge = null
        tunnel = null
    }

    companion object {
        private const val TAG = "SlipstreamEngine"
        private const val LOG_LINES = 500

        /** The names the build gives the two native programs inside the APK. */
        const val TUNNEL = "libslipstream.so"
        const val BRIDGE = "libbridge.so"
    }
}
