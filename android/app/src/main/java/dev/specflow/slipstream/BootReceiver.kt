package dev.specflow.slipstream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import dev.specflow.slipstream.core.Store
import dev.specflow.slipstream.net.TunnelService

/**
 * Reconnects after a restart, when asked to.
 *
 * The VPN permission survives a reboot once granted, so this can start the
 * service directly — but it checks, because a permission that was revoked
 * while the device was off would otherwise produce a service that fails
 * silently on every boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = Store.of(context)
        if (!store.current.settings.startOnBoot) return
        if (store.activeProfile() == null) return
        if (VpnService.prepare(context) != null) return
        TunnelService.start(context)
    }
}
