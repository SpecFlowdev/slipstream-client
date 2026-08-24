package dev.specflow.slipstream.net

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import dev.specflow.slipstream.core.Profile
import dev.specflow.slipstream.core.RuleSet
import dev.specflow.slipstream.core.SessionRecord
import dev.specflow.slipstream.core.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The tunnel, as Android understands one.
 *
 * A VPN service is the only way an app can carry other apps' traffic on
 * Android — there is no system-wide proxy setting to point at a local port, so
 * the desktop client's approach has no equivalent here. What that buys in
 * exchange is that the kill switch is real: while this interface is up and
 * holds the default route, traffic has nowhere else to go.
 *
 * Three things run inside it:
 *
 *   the tunnel   slipstream itself, forwarding one loopback port to the proxy
 *                sitting at the far end
 *   the proxy    a SOCKS5 server of our own, which is where counting,
 *                attribution and routing rules happen
 *   the bridge   turns the interface's raw packets into proxy requests
 *
 * The app's own package is excluded from the interface. Without that, the
 * tunnel's packets to the resolver would be captured by the interface they are
 * carrying, which is a loop that stops nothing from working slowly and
 * everything from working at all.
 */
class TunnelService : VpnService() {

    enum class Phase { OFF, STARTING, ON, STOPPING, FAILED }

    data class Status(
        val phase: Phase = Phase.OFF,
        val profileName: String = "",
        val since: Long = 0,
        val bytesUp: Long = 0,
        val bytesDown: Long = 0,
        val rateUp: Long = 0,
        val rateDown: Long = 0,
        val peakRateUp: Long = 0,
        val peakRateDown: Long = 0,
        val connections: Int = 0,
        val opened: Long = 0,
        val blocked: Long = 0,
        val message: String = "",
    ) {
        val seconds: Long get() = if (since == 0L) 0 else (System.currentTimeMillis() - since) / 1000
    }

    private lateinit var store: Store
    private val engine by lazy { Engine(this) }
    private val rules = RuleSet()
    private var proxy: ProxyServer? = null
    private var tun: ParcelFileDescriptor? = null

    private var scope: CoroutineScope? = null
    private var ticker: Job? = null

    override fun onCreate() {
        super.onCreate()
        store = Store.of(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopTunnel("stopped"); return START_NOT_STICKY }
            else -> start()
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // The user turned the VPN off from system settings, or another app
        // took the slot. Either way this session is over.
        stopTunnel("the VPN permission was revoked")
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel("service destroyed")
        instance = null
        super.onDestroy()
    }

    private fun start() {
        if (state.value.phase == Phase.ON || state.value.phase == Phase.STARTING) return
        val profile = store.activeProfile()
        if (profile == null) {
            fail("No profile to connect with")
            return
        }
        profile.problem()?.let { fail(it) ; return }
        if (!engine.available()) {
            fail("This build has no tunnel for your device's processor")
            return
        }

        state.value = Status(phase = Phase.STARTING, profileName = profile.name)
        Notifications.foreground(this, state.value)

        val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = work
        work.launch {
            try {
                bringUp(profile)
            } catch (e: Exception) {
                Log.e(TAG, "could not start", e)
                fail(e.message ?: "Could not start the tunnel")
            }
        }
    }

    private suspend fun bringUp(profile: Profile) {
        val settings = store.current.settings
        rules.replace(store.current.rules)
        rules.reset()

        val tunnelPort = freePort()
        engine.startTunnel(profile, tunnelPort)

        val server = ProxyServer(tunnelPort, rules, Registry())
        server.start()
        proxy = server

        // Give slipstream a moment to bind before the interface starts sending
        // it traffic; failing that, the first few connections would be refused
        // for no reason a user could act on.
        delay(SETTLE_MS)

        val descriptor = buildInterface(settings) ?: throw IllegalStateException(
            "Android would not grant the VPN interface"
        )
        tun = descriptor
        if (!engine.startBridge(descriptor.fd, server.port, settings.mtu)) {
            throw IllegalStateException("The packet bridge would not start")
        }

        engine.onDied = { reason ->
            if (settings.autoReconnect && state.value.phase == Phase.ON) {
                Log.w(TAG, "restarting: $reason")
                restart()
            } else {
                fail(reason)
            }
        }

        state.value = state.value.copy(phase = Phase.ON, since = System.currentTimeMillis())
        Notifications.foreground(this, state.value)
        startTicking(server)
    }

    private fun buildInterface(settings: dev.specflow.slipstream.core.Settings): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(dev.specflow.slipstream.R.string.app_name))
            .setMtu(settings.mtu)
            .addAddress(TUN_ADDRESS, 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(DNS)

        if (settings.ipv6) {
            builder.addAddress(TUN_ADDRESS6, 126).addRoute("::", 0)
        }

        // Our own traffic must not be carried by the interface it is carrying.
        runCatching { builder.addDisallowedApplication(packageName) }

        val installed = packageManager.getInstalledApplications(0).map { it.packageName }.toSet()
        if (settings.allowedApps.isNotEmpty()) {
            for (app in settings.allowedApps) {
                if (app == packageName || app !in installed) continue
                runCatching { builder.addAllowedApplication(app) }
            }
        } else {
            for (app in settings.blockedApps) {
                if (app == packageName || app !in installed) continue
                runCatching { builder.addDisallowedApplication(app) }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Nothing leaves while the tunnel is coming up or going down.
            builder.setBlocking(settings.killSwitch)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.setUnderlyingNetworks(null)
        }

        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this, 0,
                packageManager.getLaunchIntentForPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        return builder.establish()
    }

    /**
     * One second of arithmetic, which is where every number the interface
     * shows comes from. Rates are differences between ticks rather than an
     * average since the start, so a graph shows what is happening now.
     */
    private fun startTicking(server: ProxyServer) {
        ticker?.cancel()
        ticker = scope?.launch {
            var lastUp = 0L
            var lastDown = 0L
            while (true) {
                delay(1000)
                val registry = server.registry
                val up = registry.bytesUp()
                val down = registry.bytesDown()
                val rateUp = (up - lastUp).coerceAtLeast(0)
                val rateDown = (down - lastDown).coerceAtLeast(0)
                lastUp = up
                lastDown = down

                val now = state.value
                if (now.phase != Phase.ON) continue
                state.value = now.copy(
                    bytesUp = up,
                    bytesDown = down,
                    rateUp = rateUp,
                    rateDown = rateDown,
                    peakRateUp = maxOf(now.peakRateUp, rateUp),
                    peakRateDown = maxOf(now.peakRateDown, rateDown),
                    connections = registry.liveCount(),
                    opened = registry.openedCount(),
                    blocked = rules.blockedCount(),
                )
                samples.value = (samples.value + Sample(rateUp, rateDown)).takeLast(SAMPLES)
                connections.value = registry.connections()
                topHosts.value = registry.topHosts()
                Notifications.foreground(this@TunnelService, state.value)
            }
        }
    }

    private fun restart() {
        scope?.launch {
            teardown()
            delay(RECONNECT_MS)
            start()
        }
    }

    private fun fail(message: String) {
        Log.w(TAG, "failed: $message")
        state.value = state.value.copy(phase = Phase.FAILED, message = message)
        Notifications.foreground(this, state.value)
        teardownBlocking()
    }

    private fun stopTunnel(reason: String) {
        if (state.value.phase == Phase.OFF) return
        state.value = state.value.copy(phase = Phase.STOPPING, message = reason)
        teardownBlocking()
        state.value = Status(phase = Phase.OFF, message = reason)
        Notifications.clear(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardownBlocking() {
        val ended = state.value
        val registry = proxy?.registry
        if (ended.since != 0L && registry != null) {
            store.recordSession(
                SessionRecord(
                    endedMs = System.currentTimeMillis(),
                    profileName = ended.profileName,
                    seconds = ended.seconds,
                    bytesUp = ended.bytesUp,
                    bytesDown = ended.bytesDown,
                    peakRateDown = ended.peakRateDown,
                    connections = ended.opened,
                )
            )
        }
        teardown()
        scope?.cancel()
        scope = null
    }

    private fun teardown() {
        ticker?.cancel()
        ticker = null
        engine.stop()
        proxy?.stop()
        proxy = null
        runCatching { tun?.close() }
        tun = null
        connections.value = emptyList()
    }

    private fun freePort(): Int =
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }

    fun engineLog(): List<String> = engine.log()

    data class Sample(val up: Long, val down: Long)

    companion object {
        private const val TAG = "SlipstreamVpn"

        const val ACTION_STOP = "dev.specflow.slipstream.STOP"

        /** A /30 nobody routes, so it cannot collide with a real network. */
        private const val TUN_ADDRESS = "10.233.233.1"
        private const val TUN_ADDRESS6 = "fd00:5195::1"
        private const val DNS = "1.1.1.1"

        private const val SETTLE_MS = 400L
        private const val RECONNECT_MS = 1500L
        private const val SAMPLES = 60

        val state = MutableStateFlow(Status())
        val samples = MutableStateFlow<List<Sample>>(emptyList())
        val connections = MutableStateFlow<List<Registry.Row>>(emptyList())
        val topHosts = MutableStateFlow<List<Pair<String, Long>>>(emptyList())

        @Volatile
        private var instance: TunnelService? = null

        fun log(): List<String> = instance?.engineLog() ?: emptyList()

        fun start(context: Context) {
            val intent = Intent(context, TunnelService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TunnelService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
