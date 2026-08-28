package dev.specflow.slipstream.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.specflow.slipstream.core.Store
import dev.specflow.slipstream.net.TunnelService
import java.io.File

enum class Tab(val label: String) {
    HOME("Tunnel"), SERVERS("Servers"), TRAFFIC("Traffic"), RULES("Rules"), SETTINGS("Settings")
}

/**
 * The frame every screen sits in: the wallpaper behind, the bar below.
 *
 * The wallpaper is drawn here rather than per screen so switching tabs does
 * not reload the image, and so the dim and blur settings apply once.
 */
@Composable
fun Shell(
    store: Store,
    refusedConsent: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    /** A `slipstream://p?...` link the activity was opened or re-opened with
     * (a scanned QR, a shared link) — surfaced once, on the Servers tab. */
    pendingLink: String? = null,
    onPendingLinkConsumed: () -> Unit = {},
) {
    val saved by store.state.collectAsState()
    var tab by remember { mutableStateOf(Tab.HOME) }

    LaunchedEffect(pendingLink) {
        if (pendingLink != null) tab = Tab.SERVERS
    }

    SlipstreamTheme(saved.settings.theme) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Wallpaper(
                path = saved.settings.wallpaper,
                dim = saved.settings.wallpaperDim,
                blur = saved.settings.wallpaperBlur,
            )

            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)) {
                        for (entry in Tab.entries) {
                            NavigationBarItem(
                                selected = tab == entry,
                                onClick = { tab = entry },
                                icon = {
                                    Icon(
                                        when (entry) {
                                            Tab.HOME -> Icons.Filled.Bolt
                                            Tab.SERVERS -> Icons.Filled.Dns
                                            Tab.TRAFFIC -> Icons.Filled.Insights
                                            Tab.RULES -> Icons.Filled.Shield
                                            Tab.SETTINGS -> Icons.Filled.Tune
                                        },
                                        contentDescription = entry.label
                                    )
                                },
                                label = { Text(entry.label) },
                            )
                        }
                    }
                }
            ) { padding ->
                val transition = if (saved.settings.animations) {
                    fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                } else {
                    fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                }
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { transition },
                    label = "tab",
                    modifier = Modifier.padding(padding)
                ) { current ->
                    when (current) {
                        Tab.HOME -> HomeScreen(store, refusedConsent, onConnect, onDisconnect)
                        Tab.SERVERS -> ServersScreen(store, pendingLink, onPendingLinkConsumed)
                        Tab.TRAFFIC -> TrafficScreen()
                        Tab.RULES -> RulesScreen(store)
                        Tab.SETTINGS -> SettingsScreen(store)
                    }
                }
            }
        }
    }
}

@Composable
private fun Wallpaper(path: String, dim: Int, blur: Int) {
    if (path.isBlank()) return
    val context = LocalContext.current
    // Decoding is keyed on the path, so a new image replaces the old one and
    // the same image is not decoded again on every recomposition.
    val bitmap: ImageBitmap? = remember(path) {
        runCatching {
            val file = File(path)
            if (!file.exists()) null
            else BitmapFactory.decodeFile(file.path)?.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap == null) return

    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .then(if (blur > 0) Modifier.blur(blur.dp) else Modifier)
    )
    // The veil is what keeps text readable over an arbitrary photograph; it
    // takes the palette's own background colour so each theme dims towards
    // its own dark rather than towards black.
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = dim / 100f))
    )
}

/** Shared by several screens, so the phase-to-words mapping stays in one place. */
fun phaseLabel(status: TunnelService.Status): String = when (status.phase) {
    // A subject-less `when` on booleans is never exhaustive to the compiler,
    // so the phase stays the subject and the two ON sub-states nest inside it
    // — which also means adding a Phase value here is a compile error until
    // this is updated, rather than a label silently falling through.
    TunnelService.Phase.OFF -> "Not connected"
    TunnelService.Phase.STARTING -> "Connecting"
    TunnelService.Phase.ON -> when {
        status.waitingForNetwork -> "Waiting for a network"
        status.reconnecting -> "Reconnecting"
        else -> "Connected"
    }
    TunnelService.Phase.STOPPING -> "Disconnecting"
    TunnelService.Phase.FAILED -> "Failed"
}
