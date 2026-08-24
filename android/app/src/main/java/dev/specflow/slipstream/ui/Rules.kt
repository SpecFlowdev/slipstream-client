package dev.specflow.slipstream.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.specflow.slipstream.core.Action
import dev.specflow.slipstream.core.Rule
import dev.specflow.slipstream.core.StarterRules
import dev.specflow.slipstream.core.Store

/**
 * The rule list, in the order it is evaluated.
 *
 * Order is the whole design, so it is what the screen shows and what it lets
 * you change: an allow above a broad block is how an exception gets written,
 * and that is only legible if the list is not sorted behind your back.
 *
 * Changes are applied when the tunnel next starts. Saying so on screen is
 * better than silently doing half of it.
 */
@Composable
fun RulesScreen(store: Store) {
    val saved by store.state.collectAsState()
    var adding by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Rules",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Button(onClick = { adding = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("  Add")
                }
            }
        }

        item {
            Card {
                Text(
                    "The first rule that matches a destination decides it. " +
                        "Anything no rule matches is allowed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { store.edit { it.copy(rules = merge(it.rules, StarterRules.trackers)) } },
                        label = { Text("Add trackers") },
                    )
                    AssistChip(
                        onClick = { store.edit { it.copy(rules = merge(it.rules, StarterRules.telemetry)) } },
                        label = { Text("Add telemetry") },
                    )
                }
            }
        }

        if (saved.rules.isEmpty()) {
            item { Card { Empty("No rules. Everything is allowed.") } }
        }

        itemsIndexed(saved.rules, key = { index, rule -> "$index:${rule.pattern}" }) { index, rule ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            rule.pattern,
                            style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                            color = if (rule.enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (rule.note.isNotBlank()) {
                            Text(
                                rule.note,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Pill(
                        if (rule.action == Action.BLOCK) "block" else "allow",
                        if (rule.action == Action.BLOCK) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { on ->
                            store.edit { it.copy(rules = it.rules.replaceAt(index) { r -> r.copy(enabled = on) }) }
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    IconButton(
                        enabled = index > 0,
                        onClick = { store.edit { it.copy(rules = it.rules.moved(index, index - 1)) } },
                    ) { Icon(Icons.Filled.ArrowUpward, contentDescription = "Earlier") }
                    IconButton(
                        enabled = index < saved.rules.size - 1,
                        onClick = { store.edit { it.copy(rules = it.rules.moved(index, index + 1)) } },
                    ) { Icon(Icons.Filled.ArrowDownward, contentDescription = "Later") }
                    IconButton(
                        onClick = {
                            store.edit { it.copy(rules = it.rules.filterIndexed { i, _ -> i != index }) }
                        },
                    ) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                }
            }
        }
    }

    if (adding) {
        RuleEditor(
            onDismiss = { adding = false },
            onSave = { rule ->
                store.edit { it.copy(rules = it.rules + rule) }
                adding = false
            },
        )
    }
}

private fun merge(existing: List<Rule>, extra: List<Rule>): List<Rule> {
    val known = existing.map { it.pattern }.toSet()
    return existing + extra.filterNot { it.pattern in known }
}

private fun List<Rule>.replaceAt(index: Int, change: (Rule) -> Rule): List<Rule> =
    mapIndexed { i, rule -> if (i == index) change(rule) else rule }

private fun List<Rule>.moved(from: Int, to: Int): List<Rule> {
    if (from == to || to !in indices) return this
    val out = toMutableList()
    out.add(to, out.removeAt(from))
    return out
}

@Composable
private fun RuleEditor(onDismiss: () -> Unit, onSave: (Rule) -> Unit) {
    var pattern by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var block by remember { mutableStateOf(true) }

    val rule = Rule(pattern.trim(), if (block) Action.BLOCK else Action.ALLOW, note = note.trim())
    val problem = rule.validate()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    pattern, { pattern = it },
                    label = { Text("Pattern") },
                    supportingText = {
                        Text("example.com, *.example.com for its subdomains, or * for everything")
                    },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(block, { block = true }, { Text("Block") })
                    FilterChip(!block, { block = false }, { Text("Allow") })
                }
                OutlinedTextField(
                    note, { note = it },
                    label = { Text("Note") },
                    supportingText = { Text("For remembering why, later") },
                    singleLine = true,
                )
                if (pattern.isNotBlank() && problem != null) {
                    Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = problem == null, onClick = { onSave(rule) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
