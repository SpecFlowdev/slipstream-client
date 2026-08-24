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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val store = Store.of(this)
        setContent {
            Shell(
                store = store,
                refusedConsent = refused,
                onConnect = { connect() },
                onDisconnect = { TunnelService.stop(this) },
            )
        }
    }

    private fun connect() {
        refused = false
        val intent: Intent? = VpnService.prepare(this)
        if (intent != null) consent.launch(intent) else TunnelService.start(this)
    }
}
