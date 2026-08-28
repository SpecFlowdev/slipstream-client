package dev.specflow.slipstream.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Watches the device's physical network, separately from the tunnel's own
 * QUIC-level reconnect logic.
 *
 * The two problems this solves are specific to a phone and have no desktop
 * equivalent: a full loss of connectivity (airplane mode, a dead zone, a
 * lift) during which no amount of client-side retrying helps because there
 * is nothing to retry over, and Doze suspending the tunnel's subprocess
 * without killing it, which leaves a session that looks alive but answers
 * nothing.
 *
 * An ordinary Wi-Fi/cellular handover is a different thing entirely — it is
 * routine, usually finishes in well under a second, and the OS is already
 * built to carry a live socket across it. Restarting the tunnel for one
 * would make handovers worse, not better, so a short loss is ignored; only
 * a loss that outlasts [OUTAGE_MS] counts as one worth reacting to.
 */
class NetworkWatcher(
    context: Context,
    /** Called when connectivity returns after an outage long enough to matter. */
    private val onRecovered: () -> Unit,
    /** Called once an outage has lasted long enough to say so in the interface. */
    private val onOutage: () -> Unit,
) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastSeen = System.currentTimeMillis()

    @Volatile
    private var outageAnnounced = false

    private var pendingOutageCheck: Runnable? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                cancelPendingOutageCheck()
                if (outageAnnounced) {
                    outageAnnounced = false
                    Log.i(TAG, "network recovered after an outage")
                    onRecovered()
                }
                lastSeen = System.currentTimeMillis()
            }
        }

        override fun onLost(network: Network) {
            handler.post { scheduleOutageCheck() }
        }
    }

    /**
     * A default-network request, not a bare "any internet" one: the default
     * network is whichever one the system would route ordinary traffic over
     * right now, which is what a handover actually changes.
     */
    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { manager.registerNetworkCallback(request, callback) }
            .onFailure { Log.w(TAG, "could not watch connectivity", it) }
    }

    fun stop() {
        cancelPendingOutageCheck()
        runCatching { manager.unregisterNetworkCallback(callback) }
    }

    private fun scheduleOutageCheck() {
        cancelPendingOutageCheck()
        val check = Runnable {
            // onLost fires the instant one network drops, including the
            // ordinary moment mid-handover when neither radio is fully up
            // yet. Only call it an outage if nothing has replaced it by the
            // time this runs.
            if (System.currentTimeMillis() - lastSeen >= OUTAGE_MS && !outageAnnounced) {
                outageAnnounced = true
                Log.w(TAG, "no network for ${OUTAGE_MS}ms")
                onOutage()
            }
        }
        pendingOutageCheck = check
        handler.postDelayed(check, OUTAGE_MS)
    }

    private fun cancelPendingOutageCheck() {
        pendingOutageCheck?.let { handler.removeCallbacks(it) }
        pendingOutageCheck = null
    }

    private companion object {
        const val TAG = "SlipstreamNetwork"

        /**
         * Longer than any ordinary Wi-Fi/cellular handover, short enough that
         * someone watching the screen still sees a prompt response to a real
         * outage rather than a stuck spinner.
         */
        const val OUTAGE_MS = 8_000L
    }
}
