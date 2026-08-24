package dev.specflow.slipstream.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.specflow.slipstream.core.Store
import dev.specflow.slipstream.core.Theme
import dev.specflow.slipstream.net.TunnelService
import java.io.File

@Composable
fun SettingsScreen(store: Store) {
    val saved by store.state.collectAsState()
    val settings = saved.settings
    val context = LocalContext.current
    var pickingApps by remember { mutableStateOf(false) }
    var showingLog by remember { mutableStateOf(false) }

    // The picked image is copied into the app's own storage under a name that
    // changes every time. A stable name would be cached by the image loader
    // and a new wallpaper would silently show the old one.
    val pickWallpaper = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val target = File(context.filesDir, "wallpaper-${System.currentTimeMillis()}.img")
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }.onSuccess {
            context.filesDir.listFiles()
                ?.filter { it.name.startsWith("wallpaper-") && it.name != target.name }
                ?.forEach { it.delete() }
            store.edit { it.copy(settings = it.settings.copy(wallpaper = target.path)) }
        }
    }

    LazyColumn(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }

        item {
            Card {
                SectionTitle("Appearance")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (option in Theme.entries) {
                        FilterChip(
                            selected = settings.theme == option,
                            onClick = { store.edit { it.copy(settings = it.settings.copy(theme = option)) } },
                            label = {
                                Text(
                                    when (option) {
                                        Theme.DARK -> "Graphite"
                                        Theme.LIGHT -> "Paper"
                                        Theme.BLUE -> "Navy"
                                    }
                                )
                            },
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { pickWallpaper.launch("image/*") },
                        label = { Text(if (settings.wallpaper.isBlank()) "Choose wallpaper" else "Change wallpaper") },
                    )
                    if (settings.wallpaper.isNotBlank()) {
                        AssistChip(
                            onClick = {
                                store.edit { it.copy(settings = it.settings.copy(wallpaper = "")) }
                            },
                            label = { Text("Remove") },
                        )
                    }
                }

                if (settings.wallpaper.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Labelled("Dim", "${settings.wallpaperDim}%")
                    Slider(
                        value = settings.wallpaperDim.toFloat(),
                        onValueChange = { v ->
                            store.edit { it.copy(settings = it.settings.copy(wallpaperDim = v.toInt())) }
                        },
                        valueRange = 0f..90f,
                    )
                    Labelled("Blur", "${settings.wallpaperBlur} dp")
                    Slider(
                        value = settings.wallpaperBlur.toFloat(),
                        onValueChange = { v ->
                            store.edit { it.copy(settings = it.settings.copy(wallpaperBlur = v.toInt())) }
                        },
                        valueRange = 0f..24f,
                    )
                }

                Toggle("Animations", settings.animations) { on ->
                    store.edit { it.copy(settings = it.settings.copy(animations = on)) }
                }
            }
        }

        item {
            Card {
                SectionTitle("Tunnel")
                Spacer(Modifier.height(6.dp))
                Toggle(
                    "Block traffic while connecting",
                    settings.killSwitch,
                    "Nothing leaves the device outside the tunnel, including while it comes up",
                ) { on -> store.edit { it.copy(settings = it.settings.copy(killSwitch = on)) } }
                Toggle(
                    "Reconnect automatically",
                    settings.autoReconnect,
                    "Starts the tunnel again if it stops on its own",
                ) { on -> store.edit { it.copy(settings = it.settings.copy(autoReconnect = on)) } }
                Toggle(
                    "Carry IPv6",
                    settings.ipv6,
                    "Off routes only IPv4 into the tunnel",
                ) { on -> store.edit { it.copy(settings = it.settings.copy(ipv6 = on)) } }
                Toggle(
                    "Start after a restart",
                    settings.startOnBoot,
                ) { on -> store.edit { it.copy(settings = it.settings.copy(startOnBoot = on)) } }

                Spacer(Modifier.height(10.dp))
                var mtu by remember(settings.mtu) { mutableStateOf(settings.mtu.toString()) }
                OutlinedTextField(
                    mtu,
                    { value ->
                        mtu = value.filter(Char::isDigit)
                        mtu.toIntOrNull()?.takeIf { it in 576..9000 }?.let { size ->
                            store.edit { it.copy(settings = it.settings.copy(mtu = size)) }
                        }
                    },
                    label = { Text("MTU") },
                    supportingText = { Text("1500 suits most networks; lower it if large transfers stall") },
                    singleLine = true,
                )
            }
        }

        item {
            Card {
                SectionTitle("Apps")
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        settings.allowedApps.isNotEmpty() ->
                            "${settings.allowedApps.size} apps use the tunnel; everything else goes direct."
                        settings.blockedApps.isNotEmpty() ->
                            "${settings.blockedApps.size} apps go direct; everything else uses the tunnel."
                        else -> "Every app uses the tunnel."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                AssistChip(onClick = { pickingApps = true }, label = { Text("Choose apps") })
            }
        }

        item {
            Card {
                SectionTitle("Diagnostics")
                Spacer(Modifier.height(6.dp))
                AssistChip(onClick = { showingLog = true }, label = { Text("Show the tunnel's output") })
            }
        }
    }

    if (pickingApps) {
        AppPicker(store, onDismiss = { pickingApps = false })
    }
    if (showingLog) {
        val lines = remember { TunnelService.log() }
        AlertDialog(
            onDismissRequest = { showingLog = false },
            title = { Text("Tunnel output") },
            text = {
                if (lines.isEmpty()) {
                    Text("Nothing yet. Connect once and look again.")
                } else {
                    LazyColumn { items(lines) { Text(it, style = MaterialTheme.typography.labelSmall.merge(Tabular)) } }
                }
            },
            confirmButton = { TextButton(onClick = { showingLog = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun Labelled(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            style = MaterialTheme.typography.labelSmall.merge(Tabular),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Toggle(
    label: String,
    checked: Boolean,
    explanation: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (explanation != null) {
                Text(
                    explanation,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked, onChange)
    }
}

/**
 * Which apps the tunnel carries.
 *
 * Android enforces this at the interface, so it is the real equivalent of the
 * desktop's routing rules for whole programs — and unlike a rule matched on a
 * host name, an app kept out of the tunnel genuinely never enters it.
 */
@Composable
private fun AppPicker(store: Store, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val saved by store.state.collectAsState()
    val apps = remember { installedApps(context) }
    var allowMode by remember { mutableStateOf(saved.settings.allowedApps.isNotEmpty()) }
    var chosen by remember {
        mutableStateOf(
            (if (allowMode) saved.settings.allowedApps else saved.settings.blockedApps).toSet()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apps") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!allowMode, { allowMode = false; chosen = emptySet() }, { Text("Keep out") })
                    FilterChip(allowMode, { allowMode = true; chosen = emptySet() }, { Text("Only these") })
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (allowMode) "Only the apps you tick use the tunnel."
                    else "The apps you tick bypass the tunnel.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(340.dp)) {
                    items(apps, key = { it.second }) { (label, packageName) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = packageName in chosen,
                                onCheckedChange = { on ->
                                    chosen = if (on) chosen + packageName else chosen - packageName
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                Text(
                                    packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                store.edit {
                    it.copy(
                        settings = it.settings.copy(
                            allowedApps = if (allowMode) chosen.toList() else emptyList(),
                            blockedApps = if (allowMode) emptyList() else chosen.toList(),
                        )
                    )
                }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Apps that can reach the network, which are the only ones worth listing. */
private fun installedApps(context: Context): List<Pair<String, String>> {
    val pm = context.packageManager
    return pm.getInstalledApplications(0)
        .asSequence()
        .filter { it.packageName != context.packageName }
        .filter {
            pm.checkPermission(android.Manifest.permission.INTERNET, it.packageName) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        .map { info: ApplicationInfo -> pm.getApplicationLabel(info).toString() to info.packageName }
        .sortedBy { it.first.lowercase() }
        .toList()
}
