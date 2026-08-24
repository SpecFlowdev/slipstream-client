package dev.specflow.slipstream.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.specflow.slipstream.core.Store
import dev.specflow.slipstream.net.TunnelService

/**
 * The screen that answers the only question most sessions ask: is it on, and
 * how fast is it going.
 */
@Composable
fun HomeScreen(
    store: Store,
    refusedConsent: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val status by TunnelService.state.collectAsState()
    val samples by TunnelService.samples.collectAsState()
    val saved by store.state.collectAsState()
    val profile = saved.profiles.firstOrNull { it.name == saved.activeProfile }
        ?: saved.profiles.firstOrNull()

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PowerButton(
            phase = status.phase,
            animated = saved.settings.animations,
            onClick = {
                if (status.phase == TunnelService.Phase.ON) onDisconnect() else onConnect()
            }
        )

        Text(
            phaseLabel(status.phase),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            when {
                refusedConsent -> "Android needs permission to create a VPN before the tunnel can carry anything."
                status.phase == TunnelService.Phase.FAILED && status.message.isNotBlank() -> status.message
                profile == null -> "Add a server on the Servers tab to begin."
                status.phase == TunnelService.Phase.ON ->
                    "${profile.name} · ${formatDuration(status.seconds)}"
                else -> profile.name
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (refusedConsent || status.phase == TunnelService.Phase.FAILED)
                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card {
            SectionTitle("Right now")
            Spacer(Modifier.height(6.dp))
            AreaChart(
                samples = samples,
                modifier = Modifier.fillMaxWidth().height(150.dp),
            )
            Spacer(Modifier.height(10.dp))
            StatRow(
                "Download" to formatRate(status.rateDown),
                "Upload" to formatRate(status.rateUp),
                "Live" to status.connections.toString(),
            )
        }

        Card {
            SectionTitle("This session")
            Spacer(Modifier.height(4.dp))
            StatRow(
                "Received" to formatBytes(status.bytesDown),
                "Sent" to formatBytes(status.bytesUp),
            )
            StatRow(
                "Peak down" to formatRate(status.peakRateDown),
                "Connections" to status.opened.toString(),
            )
            if (status.blocked > 0) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill("${status.blocked} refused", MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "by routing rules",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (profile != null) {
            Card {
                SectionTitle("Path")
                Spacer(Modifier.height(6.dp))
                Detail("Domain", profile.domain)
                if (profile.authoritative.isNotEmpty()) {
                    Detail("Authoritative", profile.authoritative.joinToString(", "))
                }
                if (profile.resolvers.isNotEmpty()) {
                    Detail("Resolvers", profile.resolvers.joinToString(", "))
                }
                Detail(
                    "Congestion control",
                    when (profile.congestion) {
                        dev.specflow.slipstream.core.Congestion.BBR -> "BBR"
                        dev.specflow.slipstream.core.Congestion.DCUBIC -> "dCUBIC"
                        dev.specflow.slipstream.core.Congestion.DEFAULT ->
                            "per path (BBR authoritative, dCUBIC recursive)"
                    }
                )
                Detail("Keep-alive", "${profile.keepAliveMs} ms")
                if (profile.cert.isNotBlank()) Detail("Certificate", "pinned")
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.58f),
        )
    }
}

/**
 * The connect control.
 *
 * While connecting it breathes, which is the only honest thing an interface
 * can show for a state whose duration it does not know.
 */
@Composable
private fun PowerButton(
    phase: TunnelService.Phase,
    animated: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = when (phase) {
        TunnelService.Phase.ON -> scheme.primary
        TunnelService.Phase.FAILED -> scheme.error
        else -> scheme.onSurfaceVariant
    }

    val pulse = if (animated && phase == TunnelService.Phase.STARTING) {
        val transition = rememberInfiniteTransition(label = "pulse")
        transition.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "scale",
        ).value
    } else 1f

    Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(176.dp)
                .scale(pulse)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.28f), Color.Transparent)
                    )
                ),
        )
        Box(
            Modifier
                .size(124.dp)
                .clip(CircleShape)
                .background(scheme.surface.copy(alpha = 0.9f))
                .border(2.dp, accent.copy(alpha = 0.75f), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.PowerSettingsNew,
                contentDescription = if (phase == TunnelService.Phase.ON) "Disconnect" else "Connect",
                tint = accent,
                modifier = Modifier.size(46.dp),
            )
        }
    }
}
