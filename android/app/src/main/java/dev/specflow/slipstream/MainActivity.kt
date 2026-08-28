package dev.specflow.slipstream

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import dev.specflow.slipstream.core.Store
import dev.specflow.slipstream.net.TunnelService
import dev.specflow.slipstream.ui.Shell

class MainActivity : ComponentActivity() {

    /**
     * Android will not let a VPN start without a consent dialog, and only an
     * activity can show one. The tile and the boot receiver both route back
     * here for exactly that reason.
     */
    private val consent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            TunnelService.start(this)
        } else {
            refused = true
        }
    }

    private val notifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Declining costs the live notification and nothing else. */ }

    private var refused by mutableStateOf(false)

    /** A `slipstream://p?...` link this activity was opened or re-opened with. */
    private var pendingLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        pendingLink = linkFrom(intent)

        val store = Store.of(this)
        setContent {
            Shell(
                store = store,
                refusedConsent = refused,
                onConnect = { connect() },
                onDisconnect = { TunnelService.stop(this) },
                pendingLink = pendingLink,
                onPendingLinkConsumed = { pendingLink = null },
            )
        }
    }

    /**
     * Reached instead of a fresh [onCreate] because the manifest declares
     * `launchMode="singleTask"` — needed so the tunnel's own notification and
     * the quick settings tile keep reopening the same activity instance
     * rather than piling up new ones, which also means a second QR scan (or
     * a second tap on a shared link) arrives here rather than starting over.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        linkFrom(intent)?.let { pendingLink = it }
    }

    private fun linkFrom(intent: Intent?): String? {
        val uri = intent?.data?.takeIf { intent.action == Intent.ACTION_VIEW } ?: return null
        return uri.toString().takeIf { it.startsWith("slipstream://p") }
    }

    private fun connect() {
        refused = false
        val intent: Intent? = VpnService.prepare(this)
        if (intent != null) consent.launch(intent) else TunnelService.start(this)
    }
}
