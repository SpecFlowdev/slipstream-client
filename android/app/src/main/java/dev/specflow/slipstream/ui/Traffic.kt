package dev.specflow.slipstream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.specflow.slipstream.core.Store
import dev.specflow.slipstream.net.TunnelService

/**
 * Where the traffic is going, and where it went.
 *
 * Everything here comes from the proxy in the middle of the tunnel, so the
 * numbers are counts of bytes that actually crossed it rather than estimates
 * from the system's per-app accounting.
 */
@Composable
fun TrafficScreen() {
    val status by TunnelService.state.collectAsState()
    val samples by TunnelService.samples.collectAsState()
    val live by TunnelService.connections.collectAsState()
    val top by TunnelService.topHosts.collectAsState()
    val store = dev.specflow.slipstream.core.Store.of(androidx.compose.ui.platform.LocalContext.current)
    val saved by store.state.collectAsState()

    LazyColumn(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Traffic",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }

        item {
            Card {
                AreaChart(samples, Modifier.fillMaxWidth().height(160.dp))
                Spacer(Modifier.height(12.dp))
                StatRow(
                    "Down" to formatRate(status.rateDown),
                    "Up" to formatRate(status.rateUp),
                )
                StatRow(
                    "Peak down" to formatRate(status.peakRateDown),
                    "Peak up" to formatRate(status.peakRateUp),
                )
                StatRow(
                    "Received" to formatBytes(status.bytesDown),
                    "Sent" to formatBytes(status.bytesUp),
                )
                StatRow(
                    "Open now" to status.connections.toString(),
                    "Opened" to status.opened.toString(),
                )
                if (status.blocked > 0) {
                    StatRow(
                        "Refused" to status.blocked.toString(),
                        "For" to formatDuration(status.seconds),
                    )
                } else {
                    StatRow("For" to formatDuration(status.seconds))
                }
            }
        }

        item {
            Card {
                SectionTitle("Busiest destinations")
                Spacer(Modifier.height(8.dp))
                if (top.isEmpty()) {
                    Empty("Nothing yet")
                } else {
                    val most = top.first().second.coerceAtLeast(1)
                    for ((host, bytes) in top) {
                        BarRow(host, formatBytes(bytes), bytes.toFloat() / most)
                    }
                }
            }
        }

        item {
            Card {
                SectionTitle("Live connections")
                Spacer(Modifier.height(8.dp))
                if (live.isEmpty()) {
                    Empty("None open")
                } else {
                    for (row in live.take(20)) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(row.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "port ${row.port}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (row.blocked) {
                                Pill("refused", MaterialTheme.colorScheme.error)
                            } else {
                                Text(
                                    "↓${formatBytes(row.down)}  ↑${formatBytes(row.up)}",
                                    style = MaterialTheme.typography.labelSmall.merge(Tabular),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Card {
                SectionTitle("Past sessions")
                Spacer(Modifier.height(8.dp))
                if (saved.history.isEmpty()) {
                    Empty("No finished sessions yet")
                } else {
                    for (record in saved.history.take(12)) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(record.profileName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${formatWhen(record.endedMs)} · ${formatDuration(record.seconds)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                formatBytes(record.bytesUp + record.bytesDown),
                                style = MaterialTheme.typography.bodySmall.merge(Tabular),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BarRow(label: String, value: String, fraction: Float) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text(
                value,
                style = MaterialTheme.typography.labelSmall.merge(Tabular),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .height(5.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
