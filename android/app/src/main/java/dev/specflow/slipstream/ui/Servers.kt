package dev.specflow.slipstream.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.specflow.slipstream.core.Congestion
import dev.specflow.slipstream.core.Profile
import dev.specflow.slipstream.core.ProfileShare
import dev.specflow.slipstream.core.Store

/**
 * The servers a tunnel can be built to, and the editor for one.
 *
 * A profile is exactly the set of flags slipstream takes, so anything
 * configurable on the desktop is configurable here and means the same thing.
 */
@Composable
fun ServersScreen(
    store: Store,
    pendingLink: String? = null,
    onPendingLinkConsumed: () -> Unit = {},
) {
    val saved by store.state.collectAsState()
    var editing by remember { mutableStateOf<Profile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Profile?>(null) }
    var sharing by remember { mutableStateOf<Profile?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importSeed by remember { mutableStateOf("") }

    // A link the app was opened or re-opened with — a scanned QR, a link
    // shared from elsewhere — opens the same dialog a manual paste would,
    // pre-filled rather than auto-added: whatever handed us this text was
    // outside the app's own control, so it still goes through one review
    // and a tap before anything is saved.
    LaunchedEffect(pendingLink) {
        if (pendingLink != null) {
            importSeed = pendingLink
            importing = true
            onPendingLinkConsumed()
        }
    }

    LazyColumn(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Servers",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Icon-only, to leave room on a phone-width header next to
                    // the primary Add button — its own dialog explains itself.
                    IconButton(onClick = { importSeed = ""; importing = true }) {
                        Icon(Icons.Filled.Link, contentDescription = "Add from a link")
                    }
                    Button(onClick = { creating = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.fillMaxWidth(0f))
                        Text("  Add")
                    }
                }
            }
        }

        if (saved.profiles.isEmpty()) {
            item { Card { Empty("No servers yet. Add the one your tunnel runs on.") } }
        }

        items(saved.profiles, key = { it.name }) { profile ->
            val active = profile.name == (saved.activeProfile.ifBlank { saved.profiles.first().name })
            Card(Modifier.clickable {
                store.edit { it.copy(activeProfile = profile.name) }
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (active) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = if (active) "In use" else "Not in use",
                        tint = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.fillMaxWidth(0f))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(profile.name, fontWeight = FontWeight.Medium)
                        Text(
                            profile.domain.ifBlank { "no domain" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { sharing = profile }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = { editing = profile }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit")
                    }
                    IconButton(onClick = {
                        val copy = profile.copy(name = uniqueName(saved.profiles.map { it.name }, profile.name))
                        store.edit { it.copy(profiles = it.profiles + copy) }
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate")
                    }
                    IconButton(onClick = { confirmDelete = profile }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(
                        when (profile.congestion) {
                            Congestion.BBR -> "BBR"
                            Congestion.DCUBIC -> "dCUBIC"
                            Congestion.DEFAULT -> "auto"
                        },
                        MaterialTheme.colorScheme.primary,
                    )
                    if (profile.authoritative.isNotEmpty()) {
                        Pill("${profile.authoritative.size} authoritative", MaterialTheme.colorScheme.secondary)
                    }
                    if (profile.resolvers.isNotEmpty()) {
                        Pill("${profile.resolvers.size} recursive", MaterialTheme.colorScheme.secondary)
                    }
                    if (profile.cert.isNotBlank()) {
                        Pill("pinned", MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        ProfileEditor(
            initial = editing ?: Profile(name = uniqueName(saved.profiles.map { it.name }, "Server")),
            existing = saved.profiles.map { it.name },
            onDismiss = { creating = false; editing = null },
            onSave = { profile ->
                val was = editing?.name
                store.edit { state ->
                    val profiles = if (was == null) state.profiles + profile
                    else state.profiles.map { if (it.name == was) profile else it }
                    state.copy(
                        profiles = profiles,
                        activeProfile = if (state.activeProfile == was || state.activeProfile.isBlank())
                            profile.name else state.activeProfile,
                    )
                }
                creating = false
                editing = null
            },
        )
    }

    confirmDelete?.let { doomed ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${doomed.name}?") },
            text = { Text("This removes the server from this device. Nothing on the server itself changes.") },
            confirmButton = {
                TextButton(onClick = {
                    store.edit { state ->
                        val left = state.profiles.filterNot { it.name == doomed.name }
                        state.copy(
                            profiles = left,
                            activeProfile = if (state.activeProfile == doomed.name)
                                left.firstOrNull()?.name.orEmpty() else state.activeProfile,
                        )
                    }
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }

    sharing?.let { profile ->
        ShareDialog(profile, onDismiss = { sharing = null })
    }

    if (importing) {
        ImportDialog(
            existing = saved.profiles.map { it.name },
            initialText = importSeed,
            onDismiss = { importing = false; importSeed = "" },
            onImport = { profile ->
                store.edit { state ->
                    state.copy(
                        profiles = state.profiles + profile,
                        activeProfile = state.activeProfile.ifBlank { profile.name },
                    )
                }
                importing = false
                importSeed = ""
            },
        )
    }
}

/**
 * A server, as a link and as the QR code of that link. Scanning it is
 * whatever the device's own camera app already does with a URL — this app
 * asks nothing of it beyond being registered for `slipstream://` links (see
 * the manifest), so there is no camera permission or preview to get right
 * here.
 */
@Composable
private fun ShareDialog(profile: Profile, onDismiss: () -> Unit) {
    val link = remember(profile) { ProfileShare.encode(profile) }
    val qr = remember(link) { encodeQrCode(link).asImageBitmap() }
    val clipboard = LocalClipboardManager.current
    var copied by remember(profile) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share ${profile.name}") },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // A plain white backing: a QR reader depends on real contrast
                // between the modules, which a dark theme's own background
                // does not reliably give it.
                Column(
                    Modifier
                        .background(Color.White)
                        .padding(12.dp),
                ) {
                    Image(qr, contentDescription = "QR code for $link", modifier = Modifier.size(240.dp))
                }
                Text(
                    "Scan this with the phone's camera, or copy the link below. " +
                        "It carries this server's settings only — never this device's own history or apps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(
                        link,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(link))
                copied = true
            }) { Text(if (copied) "Copied" else "Copy link") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * The other direction: a link typed, pasted, or lifted from a photo by
 * whatever put it on the clipboard — this dialog only ever sees text.
 */
@Composable
private fun ImportDialog(
    existing: List<String>,
    initialText: String = "",
    onDismiss: () -> Unit,
    onImport: (Profile) -> Unit,
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a server from a link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Paste a slipstream:// link — from the desktop client's Share dialog, " +
                        "or another copy of this app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    text,
                    { text = it; error = null },
                    label = { Text("Link") },
                    placeholder = { Text("slipstream://p?v=1&d=…") },
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                )
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    ProfileShare.decode(text).fold(
                        onSuccess = { decoded ->
                            val named = decoded.profile.copy(
                                name = uniqueName(existing, decoded.profile.name.ifBlank { "Server" }),
                            )
                            onImport(named)
                        },
                        onFailure = { error = it.message ?: "That link could not be read" },
                    )
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun uniqueName(taken: List<String>, base: String): String {
    if (base !in taken) return base
    var n = 2
    while ("$base $n" in taken) n++
    return "$base $n"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditor(
    initial: Profile,
    existing: List<String>,
    onDismiss: () -> Unit,
    onSave: (Profile) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var domain by remember { mutableStateOf(initial.domain) }
    var resolvers by remember { mutableStateOf(initial.resolvers.joinToString(", ")) }
    var authoritative by remember { mutableStateOf(initial.authoritative.joinToString(", ")) }
    var cert by remember { mutableStateOf(initial.cert) }
    var keepAlive by remember { mutableStateOf(initial.keepAliveMs.toString()) }
    var congestion by remember { mutableStateOf(initial.congestion) }
    var congestionOpen by remember { mutableStateOf(false) }
    var pinning by remember { mutableStateOf(initial.cert.isNotBlank()) }

    val built = Profile(
        name = name.trim(),
        domain = domain.trim(),
        resolvers = split(resolvers),
        authoritative = split(authoritative),
        cert = if (pinning) cert else "",
        congestion = congestion,
        keepAliveMs = keepAlive.toIntOrNull() ?: 400,
    )
    val clash = built.name != initial.name && built.name in existing
    val problem = built.problem() ?: if (clash) "That name is already used" else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name in existing) "Edit server" else "New server") },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    domain, { domain = it },
                    label = { Text("Domain") },
                    supportingText = { Text("The name the tunnel is served under") },
                    singleLine = true,
                )
                OutlinedTextField(
                    authoritative, { authoritative = it },
                    label = { Text("Authoritative") },
                    supportingText = { Text("Your own server, as ip:port — polled rather than queried") },
                    singleLine = true,
                )
                OutlinedTextField(
                    resolvers, { resolvers = it },
                    label = { Text("Recursive resolvers") },
                    supportingText = { Text("Public resolvers, as ip:port, separated by commas") },
                    singleLine = true,
                )

                ExposedDropdownMenuBox(
                    expanded = congestionOpen,
                    onExpandedChange = { congestionOpen = it },
                ) {
                    OutlinedTextField(
                        value = when (congestion) {
                            Congestion.BBR -> "BBR"
                            Congestion.DCUBIC -> "dCUBIC"
                            Congestion.DEFAULT -> "Per path (recommended)"
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Congestion control") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(congestionOpen) },
                        modifier = Modifier.menuAnchor(),
                    )
                    ExposedDropdownMenu(congestionOpen, { congestionOpen = false }) {
                        for (option in Congestion.entries) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (option) {
                                            Congestion.DEFAULT -> "Per path (recommended)"
                                            Congestion.BBR -> "BBR — fills the link faster"
                                            Congestion.DCUBIC -> "dCUBIC — gentler on shared paths"
                                        }
                                    )
                                },
                                onClick = { congestion = option; congestionOpen = false },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    keepAlive, { keepAlive = it.filter(Char::isDigit) },
                    label = { Text("Keep-alive (ms)") },
                    singleLine = true,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(pinning, { pinning = it })
                    Text("  Pin the server's certificate", style = MaterialTheme.typography.bodyMedium)
                }
                if (pinning) {
                    OutlinedTextField(
                        cert, { cert = it },
                        label = { Text("Certificate (PEM)") },
                        supportingText = { Text("The leaf itself; a CA bundle will not do") },
                        minLines = 3,
                        maxLines = 6,
                    )
                }

                if (problem != null) {
                    Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = problem == null, onClick = { onSave(built) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun split(text: String): List<String> =
    text.split(',', ' ', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
