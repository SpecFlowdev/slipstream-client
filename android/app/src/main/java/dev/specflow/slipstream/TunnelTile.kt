package dev.specflow.slipstream

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.specflow.slipstream.net.TunnelService

/**
 * The quick settings tile, which is how a tunnel actually gets used day to
 * day: two taps from anywhere rather than finding the app.
 *
 * If the VPN permission has not been granted yet the tile cannot ask for it —
 * only an activity can — so it opens the app instead.
 */
class TunnelTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        val on = TunnelService.state.value.phase == TunnelService.Phase.ON
        if (on) {
            TunnelService.stop(this)
        } else if (VpnService.prepare(this) != null) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            TunnelService.start(this)
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val status = TunnelService.state.value
        tile.state = when (status.phase) {
            TunnelService.Phase.ON -> Tile.STATE_ACTIVE
            TunnelService.Phase.STARTING, TunnelService.Phase.STOPPING -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.app_name)
        tile.updateTile()
    }
}
