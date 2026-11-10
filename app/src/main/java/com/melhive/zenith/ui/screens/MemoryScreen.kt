package com.melhive.zenith.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.zenith.ui.components.EmptyState
import com.melhive.zenith.ui.components.ZenithCard
import com.melhive.zenith.ui.navigation.rememberContainer
import com.melhive.zenith.ui.theme.ZenithColors
import kotlinx.coroutines.launch

@Composable
fun MemoryScreen(onBack: () -> Unit, initialTab: Int = 0) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(initialTab) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var scheduleRefresh by remember { mutableIntStateOf(0) }
    val calendarPermission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { scheduleRefresh++ }
    val upcoming by androidx.compose.runtime.produceState<List<Pair<java.time.LocalDate, List<String>>>?>(null, tab, scheduleRefresh) {
        value = if (tab != 4) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val cal = com.melhive.zenith.agents.CalendarReader(context)
            if (!cal.granted()) emptyList() else (0L until 14L).map { java.time.LocalDate.now().plusDays(it) }.map { d -> d to runCatching { cal.events(d) }.getOrDefault(emptyList()) }.filter { it.second.isNotEmpty() }
        }
    }
    val facts by c.memory.facts.collectAsStateWithLifecycle(initialValue = emptyList())
    val todos by c.todos.todos.collectAsStateWithLifecycle(initialValue = emptyList())
    val reminders by c.db.reminders().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val docs by c.knowledge.documents.collectAsStateWithLifecycle(initialValue = emptyList())
    var adding by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    ListScreen("Memory", onBack = onBack, subtitle = "Stored only on this device", actions = {
        if (tab == 0 || tab == 1) IconButton(onClick = { adding = true }) { Icon(Icons.Outlined.Add, "Add") }
    }) {
        item {
            SecondaryScrollableTabRow(selectedTabIndex = tab, containerColor = ZenithColors.Background, edgePadding = 0.dp) {
                listOf("Facts", "Task list", "Reminders", "Knowledge", "Schedule").forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                }
            }
        }
        when (tab) {
            4 -> {
                item { Text("Your calendar for the next 14 days, plus ZENITH reminders. Say “schedule lunch with Ana tomorrow at noon” to add one.", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted) }
                if (!com.melhive.zenith.agents.CalendarReader(context).granted()) item {
                    ZenithCard(accent = ZenithColors.Amber) {
                        Text("Allow calendar access to see and add events here.", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { calendarPermission.launch(arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR)) }) { Text("Allow calendar") }
                    }
                }
                val list = upcoming
                if (list != null && list.isEmpty() && com.melhive.zenith.agents.CalendarReader(context).granted()) item { EmptyState("Nothing scheduled", "No calendar events in the next 14 days.") }
                list?.forEach { (day, events) ->
                    item(key = "day-$day") {
                        ZenithCard {
                            Text(when (day) { java.time.LocalDate.now() -> "Today"; java.time.LocalDate.now().plusDays(1) -> "Tomorrow"; else -> day.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d")) },
                                style = MaterialTheme.typography.titleSmall, color = ZenithColors.Cyan)
                            events.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
                        }
                    }
                }
                val pending = reminders.filter { it.status == "scheduled" && it.triggerAt > System.currentTimeMillis() }
                if (pending.isNotEmpty()) {
                    item { Text("ZENITH reminders", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                    items(pending, key = { "r-" + it.id }) { r -> ZenithCard { Text(r.text); Text(formatTime(r.triggerAt), style = MaterialTheme.typography.labelSmall, color = ZenithColors.Muted) } }
                }
            }
            0 -> {
                item { Text("ZENITH only remembers what you explicitly ask it to (“remember that…”), or what you approve when an AI model requests it.", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted) }
                if (facts.isEmpty()) item { EmptyState("No memories", "Say “remember that my sister's name is Ana”.") }
                items(facts, key = { it.id }) { f ->
                    ZenithCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(f.text, modifier = Modifier.weight(1f))
                            IconButton(onClick = { scope.launch { c.memory.update(f.copy(pinned = !f.pinned)) } }) {
                                Icon(Icons.Outlined.PushPin, "Pin", tint = if (f.pinned) ZenithColors.Cyan else ZenithColors.Muted)
                            }
                            IconButton(onClick = { scope.launch { c.memory.delete(listOf(f.id)) } }) { Icon(Icons.Outlined.Delete, "Delete", tint = ZenithColors.Muted) }
                        }
                        Text("${formatTime(f.createdAt)} · ${f.source.replace('_', ' ')}", style = MaterialTheme.typography.labelSmall, color = ZenithColors.Muted)
                    }
                }
                if (facts.isNotEmpty()) item { TextButton(onClick = { confirmClear = true }) { Text("Forget everything", color = ZenithColors.Red) } }
            }
            1 -> {
                if (todos.isEmpty()) item { EmptyState("Task list is empty", "Say “add task buy milk”.") }
                items(todos, key = { it.id }) { t ->
                    ZenithCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { scope.launch { c.todos.setDone(t, !t.done) } }) {
                                Icon(if (t.done) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, "Toggle done", tint = if (t.done) ZenithColors.Green else ZenithColors.Muted)
                            }
                            Text("#${t.id} ${t.title}", modifier = Modifier.weight(1f), textDecoration = if (t.done) TextDecoration.LineThrough else null)
                            IconButton(onClick = { scope.launch { c.todos.delete(t.id) } }) { Icon(Icons.Outlined.Delete, "Delete", tint = ZenithColors.Muted) }
                        }
                        t.dueAt?.let { Text("Due ${formatTime(it)}", style = MaterialTheme.typography.labelSmall, color = ZenithColors.Muted) }
                    }
                }
            }
            2 -> {
                if (reminders.isEmpty()) item { EmptyState("No reminders", "Say “remind me to stretch in 30 minutes”.") }
                items(reminders.sortedByDescending { it.triggerAt }, key = { it.id }) { r ->
                    ZenithCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("#${r.id} ${r.text}", modifier = Modifier.weight(1f))
                            if (r.status == "scheduled") TextButton(onClick = { scope.launch { c.reminderScheduler.cancelReminder(r.id) } }) { Text("Cancel") }
                        }
                        Text("${formatTime(r.triggerAt)} · ${r.status}${if (r.status == "scheduled") if (r.exact) " · exact" else " · approximate" else ""}",
                            style = MaterialTheme.typography.labelSmall, color = ZenithColors.Muted)
                    }
                }
            }
            else -> {
                item { Text("Documents indexed for local search. Index more with “index my documents” or from Files.", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted) }
                if (docs.isEmpty()) item { EmptyState("Index is empty", "Grant a folder in Files, then index it.") }
                items(docs, key = { it.id }) { d ->
                    ZenithCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.path, modifier = Modifier.weight(1f))
                            IconButton(onClick = { scope.launch { c.knowledge.deleteDoc(d.id) } }) { Icon(Icons.Outlined.Delete, "Remove from index", tint = ZenithColors.Muted) }
                        }
                        Text("${d.chunkCount} chunks · indexed ${formatTime(d.indexedAt)}", style = MaterialTheme.typography.labelSmall, color = ZenithColors.Muted)
                    }
                }
                if (docs.isNotEmpty()) item { TextButton(onClick = { scope.launch { c.knowledge.clear() } }) { Text("Clear index", color = ZenithColors.Red) } }
            }
        }
    }

    if (adding) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(if (tab == 0) "Remember a fact" else "Add to task list") },
            text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), singleLine = false) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    scope.launch { if (tab == 0) c.memory.remember(text, "manual") else c.todos.add(text, null) }
                    adding = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Forget everything?") },
            text = { Text("All ${facts.size} remembered facts will be permanently deleted.") },
            confirmButton = { TextButton(onClick = { scope.launch { c.memory.clear() }; confirmClear = false }) { Text("Delete all", color = ZenithColors.Red) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
