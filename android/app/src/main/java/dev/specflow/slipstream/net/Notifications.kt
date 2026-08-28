package dev.specflow.slipstream.net

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.specflow.slipstream.MainActivity
import dev.specflow.slipstream.R
import dev.specflow.slipstream.ui.formatBytes
import dev.specflow.slipstream.ui.formatRate

/**
 * The one notification the app posts, kept current as the numbers change.
 *
 * A VPN has to run in the foreground, so this is not optional; making it show
 * the live rate at least means it earns the space it takes.
 */
object Notifications {
    private const val CHANNEL = "tunnel"
    private const val ID = 1

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
        )
    }

    fun foreground(service: Service, status: TunnelService.Status) {
        ensureChannel(service)
        val open = PendingIntent.getActivity(
            service, 0,
            Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            service, 1,
            Intent(service, TunnelService::class.java).setAction(TunnelService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when {
            status.phase == TunnelService.Phase.ON && status.waitingForNetwork -> "Waiting for a network…"
            status.phase == TunnelService.Phase.ON && status.reconnecting -> "Reconnecting…"
            status.phase == TunnelService.Phase.ON -> status.profileName.ifBlank { "Connected" }
            status.phase == TunnelService.Phase.STARTING -> "Connecting…"
            status.phase == TunnelService.Phase.STOPPING -> "Disconnecting…"
            status.phase == TunnelService.Phase.FAILED -> "Disconnected"
            else -> "Off"
        }
        val text = when {
            status.phase == TunnelService.Phase.ON && status.waitingForNetwork ->
                "No network right now; the session will pick back up when one returns"
            status.phase == TunnelService.Phase.ON ->
                "↓ ${formatRate(status.rateDown)}   ↑ ${formatRate(status.rateUp)}   " +
                    "${formatBytes(status.bytesUp + status.bytesDown)} total"
            status.phase == TunnelService.Phase.FAILED -> status.message
            else -> status.message.ifBlank { " " }
        }

        // The compat builder, because the platform one only takes a channel
        // from API 26 and this app runs from 24.
        val notification = NotificationCompat.Builder(service, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(status.phase == TunnelService.Phase.ON)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, service.getString(R.string.disconnect), stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            service.startForeground(ID, notification)
        }
    }

    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID)
    }
}
