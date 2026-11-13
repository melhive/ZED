package com.melhive.zed.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
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
import com.melhive.zed.ui.components.EmptyState
import com.melhive.zed.ui.components.ZedCard
import com.melhive.zed.ui.navigation.rememberContainer
import com.melhive.zed.ui.theme.ZedColors
import kotlinx.coroutines.launch

@Composable
fun MemoryScreen(onBack: () -> Unit, initialTab: Int = 0, onOpenBrain: (() -> Unit)? = null) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(initialTab) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var scheduleRefresh by remember { mutableIntStateOf(0) }
    val calendarPermission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { scheduleRefresh++ }
    val upcoming by androidx.compose.runtime.produceState<List<Pair<java.time.LocalDate, List<com.melhive.zed.agents.CalendarReader.Item>>>?>(null, tab, scheduleRefresh) {
        value = if (tab != 4) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val cal = com.melhive.zed.agents.CalendarReader(context)
            if (!cal.granted()) emptyList() else (0L until 14L).map { java.time.LocalDate.now().plusDays(it) }.map { d -> d to runCatching { cal.items(d) }.getOrDefault(emptyList()) }.filter { it.second.isNotEmpty() }
        }
    }
    // Deletes one calendar occurrence at once (asks for calendar write access the first time).
    val deleteEvent: (com.melhive.zed.agents.CalendarReader.Item) -> Unit = { item ->
        val writer = com.melhive.zed.agents.CalendarWriter(context)
        if (!writer.granted()) calendarPermission.launch(arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR))
        else scope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { writer.delete(item) }
            if (!ok) android.widget.Toast.makeText(context, "Your calendar didn't allow deleting that event.", android.widget.Toast.LENGTH_SHORT).show()
            scheduleRefresh++
        }
    }
    val allFacts by c.memory.facts.collectAsStateWithLifecycle(initialValue = emptyList())
    // Answering rules the user gave live in Lessons, not among the facts.
    val facts = allFacts.filter { it.source != "preference" }
    val rules = allFacts.filter { it.source == "preference" }
    val taught by c.teaching.state.collectAsStateWithLifecycle()
    val todos by c.todos.todos.collectAsStateWithLifecycle(initialValue = emptyList())
    val reminders by c.db.reminders().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val docs by c.knowledge.documents.collectAsStateWithLifecycle(initialValue = emptyList())
    var adding by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    ListScreen("Memory", onBack = onBack, subtitle = "Stored only on this device", actions = {
        if (tab == 0 || tab == 1) IconButton(onClick = { adding = true }) { Icon(Icons.Outlined.Add, "Add") }
    }) {
        if (onOpenBrain != null) item(key = "brain") {
            ZedCard(onClick = onOpenBrain, accent = ZedColors.Violet) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.melhive.zed.ui.screens.BrainGlyph(Modifier.padding(end = 14.dp).size(64.dp))
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        Text("LIVE BRAIN", style = MaterialTheme.typography.titleMedium, color = ZedColors.Cyan, letterSpacing = androidx.compose.ui.unit.TextUnit(2f, androidx.compose.ui.unit.TextUnitType.Sp))
                        Text("See everything ZED knows as a living network — ${facts.size + todos.size + docs.size} things, organized and connected.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                    }
                }
            }
        }
        item {
            SecondaryScrollableTabRow(selectedTabIndex = tab, containerColor = ZedColors.Background, edgePadding = 0.dp) {
                listOf("Facts", "Task list", "Reminders", "Knowledge", "Schedule", "Lessons").forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                }
            }
        }
        when (tab) {
            5 -> {
                item { Text("What you taught ZED. Say “from now on keep answers short”, “pronounce Cebu as seh-boo”, or “when I say lights out, I mean turn off the lights”. Tell it “that's wrong” and it learns the right answer too.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted) }
                if (rules.isEmpty() && taught.pronunciations.isEmpty() && taught.aliases.isEmpty()) item { EmptyState("Nothing taught yet", "Correct ZED or tell it how you like answers — it remembers.") }
                if (rules.isNotEmpty()) {
                    item { Text("How you like answers", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp)) }
                    items(rules, key = { "rule-" + it.id }) { f ->
                        ZedCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(f.text, modifier = Modifier.weight(1f))
                                IconButton(onClick = { scope.launch { c.memory.delete(listOf(f.id)) } }) { Icon(Icons.Outlined.Delete, "Delete rule", tint = ZedColors.Muted) }
                            }
                        }
                    }
                }
                if (taught.pronunciations.isNotEmpty()) {
                    item { Text("Pronunciations", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp)) }
                    items(taught.pronunciations.entries.toList(), key = { "say-" + it.key }) { (word, say) ->
                        ZedCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("$word → $say", modifier = Modifier.weight(1f))
                                IconButton(onClick = { scope.launch { c.voice.preview("$word.") } }) { Icon(Icons.Outlined.PlayArrow, "Hear it", tint = ZedColors.Cyan) }
                                IconButton(onClick = { c.teaching.forgetPronunciation(word) }) { Icon(Icons.Outlined.Delete, "Delete pronunciation", tint = ZedColors.Muted) }
                            }
                        }
                    }
                }
                if (taught.aliases.isNotEmpty()) {
                    item { Text("Your shortcuts", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp)) }
                    items(taught.aliases.entries.toList(), key = { "alias-" + it.key }) { (phrase, meaning) ->
                        ZedCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                                    Text("“$phrase”")
                                    Text(meaning, style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                                }
                                IconButton(onClick = { c.teaching.forgetAlias(phrase) }) { Icon(Icons.Outlined.Delete, "Delete shortcut", tint = ZedColors.Muted) }
                            }
                        }
                    }
                }
            }
            4 -> {
                item { Text("Your calendar for the next 14 days, plus ZED reminders. Say “schedule lunch with Ana tomorrow at noon” to add one.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted) }
                if (!com.melhive.zed.agents.CalendarReader(context).granted()) item {
                    ZedCard(accent = ZedColors.Amber) {
                        Text("Allow calendar access to see and add events here.", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { calendarPermission.launch(arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR)) }) { Text("Allow calendar") }
                    }
                }
                val list = upcoming
                if (list != null && list.isEmpty() && com.melhive.zed.agents.CalendarReader(context).granted()) item { EmptyState("Nothing scheduled", "No calendar events in the next 14 days.") }
                list?.forEach { (day, events) ->
                    item(key = "day-$day") {
                        ZedCard {
                            Text(when (day) { java.time.LocalDate.now() -> "Today"; java.time.LocalDate.now().plusDays(1) -> "Tomorrow"; else -> day.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d")) },
                                style = MaterialTheme.typography.titleSmall, color = ZedColors.Cyan)
                            events.forEach { e ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("• ${e.label}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { deleteEvent(e) }) { Icon(Icons.Outlined.Delete, if (e.recurring) "Delete this occurrence" else "Delete event", tint = ZedColors.Muted) }
                                }
                            }
                        }
                    }
                }
                val pending = reminders.filter { it.status == "scheduled" && it.triggerAt > System.currentTimeMillis() }
                if (pending.isNotEmpty()) {
                    item { Text("ZED reminders", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                    items(pending, key = { "r-" + it.id }) { r ->
                        ZedCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                                    Text(r.text); Text(formatTime(r.triggerAt), style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted)
                                }
                                IconButton(onClick = { scope.launch { c.reminderScheduler.deleteReminder(r.id) } }) { Icon(Icons.Outlined.Delete, "Delete reminder", tint = ZedColors.Muted) }
                            }
                        }
                    }
                }
            }
            0 -> {
                item { Text("What you ask ZED to remember (“remember that…”), plus what it learns from your conversations — marked “learned” until you confirm it. Turn learning off in Settings → Intelligence.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted) }
                if (facts.isEmpty()) item { EmptyState("No memories", "Say “remember that my sister's name is Ana”.") }
                items(facts, key = { it.id }) { f ->
                    ZedCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(f.text, modifier = Modifier.weight(1f))
                            IconButton(onClick = { scope.launch { c.memory.update(f.copy(pinned = !f.pinned)) } }) {
                                Icon(Icons.Outlined.PushPin, "Pin", tint = if (f.pinned) ZedColors.Cyan else ZedColors.Muted)
                            }
                            IconButton(onClick = { scope.launch { c.memory.delete(listOf(f.id)) } }) { Icon(Icons.Outlined.Delete, "Delete", tint = ZedColors.Muted) }
                        }
                        val learned = f.source == "learned"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${formatTime(f.createdAt)} · ${if (learned) "learned by ZED — not confirmed" else f.source.replace('_', ' ')}", style = MaterialTheme.typography.labelSmall, color = if (learned) ZedColors.Amber else ZedColors.Muted, modifier = Modifier.weight(1f))
                            if (learned) TextButton(onClick = { scope.launch { c.learner.confirm(f.id) } }) { Text("It's right", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
                if (facts.isNotEmpty()) item { TextButton(onClick = { confirmClear = true }) { Text("Forget everything", color = ZedColors.Red) } }
            }
            1 -> {
                if (todos.isEmpty()) item { EmptyState("Task list is empty", "Say “add task buy milk”.") }
                items(todos, key = { it.id }) { t ->
                    ZedCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { scope.launch { c.todos.setDone(t, !t.done) } }) {
                                Icon(if (t.done) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, "Toggle done", tint = if (t.done) ZedColors.Green else ZedColors.Muted)
                            }
                            Text("#${t.id} ${t.title}", modifier = Modifier.weight(1f), textDecoration = if (t.done) TextDecoration.LineThrough else null)
                            IconButton(onClick = { scope.launch { c.todos.delete(t.id) } }) { Icon(Icons.Outlined.Delete, "Delete", tint = ZedColors.Muted) }
                        }
                        t.dueAt?.let { Text("Due ${formatTime(it)}", style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted) }
                    }
                }
            }
            2 -> {
                if (reminders.isEmpty()) item { EmptyState("No reminders", "Say “remind me to stretch in 30 minutes”.") }
                items(reminders.sortedByDescending { it.triggerAt }, key = { it.id }) { r ->
                    ZedCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("#${r.id} ${r.text}", modifier = Modifier.weight(1f))
                            IconButton(onClick = { scope.launch { c.reminderScheduler.deleteReminder(r.id) } }) { Icon(Icons.Outlined.Delete, "Delete reminder", tint = ZedColors.Muted) }
                        }
                        Text("${formatTime(r.triggerAt)} · ${r.status}${if (r.status == "scheduled") if (r.exact) " · exact" else " · approximate" else ""}",
                            style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted)
                    }
                }
            }
            else -> {
                item { Text("Documents indexed for local search. Index more with “index my documents” or from Files.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted) }
                if (docs.isEmpty()) item { EmptyState("Index is empty", "Grant a folder in Files, then index it.") }
                items(docs, key = { it.id }) { d ->
                    ZedCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.path, modifier = Modifier.weight(1f))
                            IconButton(onClick = { scope.launch { c.knowledge.deleteDoc(d.id) } }) { Icon(Icons.Outlined.Delete, "Remove from index", tint = ZedColors.Muted) }
                        }
                        Text("${d.chunkCount} chunks · indexed ${formatTime(d.indexedAt)}", style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted)
                    }
                }
                if (docs.isNotEmpty()) item { TextButton(onClick = { scope.launch { c.knowledge.clear() } }) { Text("Clear index", color = ZedColors.Red) } }
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
            confirmButton = { TextButton(onClick = { val ids = facts.map { it.id }; scope.launch { c.memory.delete(ids) }; confirmClear = false }) { Text("Delete all", color = ZedColors.Red) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
