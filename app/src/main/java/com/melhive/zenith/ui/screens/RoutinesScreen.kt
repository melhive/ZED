package com.melhive.zenith.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.zenith.core.routines.Routine
import com.melhive.zenith.core.routines.RoutineCatalog
import com.melhive.zenith.ui.components.SectionTitle
import com.melhive.zenith.ui.components.WrapRow
import com.melhive.zenith.ui.components.ZenithCard
import com.melhive.zenith.ui.navigation.rememberContainer
import com.melhive.zenith.ui.theme.ZenithColors
import kotlinx.coroutines.launch

@Composable
fun RoutinesScreen(onBack: () -> Unit) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val routines by c.routines.routines.collectAsStateWithLifecycle(initialValue = emptyList())
    val s by c.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    var editing by remember { mutableStateOf<Routine?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    fun safely(block: suspend () -> Unit) = scope.launch {
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
            com.melhive.zenith.android.CrashReporter.recordNonFatal("routines", e); result = "Something went wrong: ${e.message}"
        }
    }
    val settings = s ?: return

    ListScreen("Routines", onBack = onBack, subtitle = "Several actions with one phrase, at a time, or when your car connects") {
        item {
            BriefingCard(settings.briefingEnabled, settings.briefingTime, settings.weatherCity,
                onSave = { on, time, city -> safely { c.settings.setBriefing(on, time); c.settings.setWeatherCity(city); c.routines.scheduleAll(); result = if (on) "Briefing scheduled for $time every day." else "Daily briefing off." } },
            )
        }
        result?.let { r -> item { ZenithCard(accent = ZenithColors.Cyan) { Text(r, style = MaterialTheme.typography.bodySmall) ; TextButton(onClick = { result = null }) { Text("Dismiss") } } } }
        item { SectionTitle("Your routines") { TextButton(onClick = { editing = Routine("r" + System.currentTimeMillis(), "", emptyList()) }) { Text("New routine") } } }
        items(routines, key = { it.id }) { r ->
            ZenithCard(accent = if (r.enabled) ZenithColors.Green else ZenithColors.Muted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = MaterialTheme.typography.titleMedium)
                        Text(r.triggerSummary(), style = MaterialTheme.typography.labelSmall, color = ZenithColors.Cyan)
                    }
                    Switch(checked = r.enabled, onCheckedChange = { on -> safely { c.routines.upsert(r.copy(enabled = on)) } })
                }
                Text(r.steps.joinToString(" → ") { it.label.ifBlank { it.tool } }.ifEmpty { "No steps yet" }, style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
                WrapRow {
                    TextButton(onClick = { safely { result = "Running ${r.name}…"; result = c.routines.run(r).second } }) { Text("Run now") }
                    TextButton(onClick = { editing = r }) { Text("Edit") }
                    TextButton(onClick = { safely { c.routines.delete(r.id) } }) { Text("Delete") }
                }
            }
        }
        item {
            WrapRow {
                TextButton(onClick = { safely { c.routines.save(RoutineCatalog.presets) ; result = "Restored the preset routines." } }) { Text("Restore presets") }
            }
            Text("Routines run through the same safety checks as everything else: deleting files or placing calls still asks you first. Time and Bluetooth routines run in the background and report in a notification.",
                style = MaterialTheme.typography.labelSmall, color = ZenithColors.Muted, modifier = Modifier.padding(4.dp))
        }
    }
    editing?.let { r -> RoutineEditor(r, onDismiss = { editing = null }, onSave = { updated -> safely { c.routines.upsert(updated); editing = null } }) }
}

@Composable
private fun BriefingCard(enabled: Boolean, time: String, city: String, onSave: (Boolean, String, String) -> Unit) {
    val c = rememberContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember(enabled) { mutableStateOf(enabled) }
    var t by remember(time) { mutableStateOf(time) }
    var place by remember(city) { mutableStateOf(city) }
    var preview by remember { mutableStateOf<String?>(null) }
    ZenithCard(accent = ZenithColors.Violet) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Daily briefing", style = MaterialTheme.typography.titleMedium)
                Text("Weather, today's calendar, reminders, tasks and what you missed. Say “brief me” any time.", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
            }
            Switch(checked = on, onCheckedChange = { on = it; onSave(it, t, place) })
        }
        OutlinedTextField(value = t, onValueChange = { t = it.take(5) }, singleLine = true, label = { Text("Time (HH:mm)") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        OutlinedTextField(value = place, onValueChange = { place = it.take(80) }, singleLine = true, label = { Text("City for weather (if location is off)") }, modifier = Modifier.fillMaxWidth())
        WrapRow {
            Button(onClick = { onSave(on, if (Routine.parseTime(t) != null) t else "07:00", place) }) { Text("Save") }
            TextButton(onClick = {
                scope.launch {
                    preview = "Preparing…"
                    preview = runCatching { com.melhive.zenith.agents.BriefingComposer(context.applicationContext, c).compose() }.getOrElse { "Couldn't prepare the briefing: ${it.message}" }
                    preview?.let { c.voice.preview(it) }
                }
            }) { Text("Hear it now") }
        }
        preview?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun RoutineEditor(initial: Routine, onDismiss: () -> Unit, onSave: (Routine) -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initial.name) }
    var phrases by remember { mutableStateOf(initial.phrases.joinToString(", ")) }
    var time by remember { mutableStateOf(initial.time.orEmpty()) }
    var days by remember { mutableStateOf(initial.days) }
    var device by remember { mutableStateOf(initial.bluetoothDevice) }
    var steps by remember { mutableStateOf(initial.steps) }
    var pendingTemplate by remember { mutableStateOf<String?>(null) }
    var paramValue by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val btPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val btGranted = remember(refresh) { Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED }
    val bonded = remember(refresh, btGranted) {
        if (!btGranted) emptyList() else runCatching { context.getSystemService(BluetoothManager::class.java).adapter?.bondedDevices?.mapNotNull { it.name }?.sorted() }.getOrNull().orEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank()) "New routine" else "Edit ${initial.name}") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = phrases, onValueChange = { phrases = it.take(200) }, label = { Text("Voice phrases (comma separated)") }, modifier = Modifier.fillMaxWidth())
                Text("Steps", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                steps.forEachIndexed { i, st ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}. ${st.label.ifBlank { st.tool }}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { steps = steps.filterIndexed { j, _ -> j != i } }) { Text("Remove") }
                    }
                }
                val tpl = pendingTemplate?.let { RoutineCatalog.template(it) }
                if (tpl?.param != null) {
                    OutlinedTextField(value = paramValue, onValueChange = { paramValue = it.take(80) }, singleLine = true, label = { Text("${tpl.title.removeSuffix("…")} — ${tpl.paramHint}") }, modifier = Modifier.fillMaxWidth())
                    WrapRow {
                        Button(onClick = { if (paramValue.isNotBlank()) { steps = steps + RoutineCatalog.step(tpl, paramValue); pendingTemplate = null; paramValue = "" } }) { Text("Add step") }
                        TextButton(onClick = { pendingTemplate = null }) { Text("Cancel") }
                    }
                } else {
                    Text("Add a step:", style = MaterialTheme.typography.labelSmall, color = ZenithColors.Muted)
                    WrapRow {
                        RoutineCatalog.templates.forEach { t ->
                            FilterChip(selected = false, onClick = { if (t.param == null) steps = steps + RoutineCatalog.step(t, null) else { pendingTemplate = t.id; paramValue = "" } }, label = { Text(t.title) })
                        }
                    }
                }
                Text("Run automatically", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(value = time, onValueChange = { time = it.take(5) }, singleLine = true, label = { Text("At time (HH:mm, empty = no)") }, modifier = Modifier.fillMaxWidth())
                if (time.isNotBlank()) WrapRow {
                    listOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 7 to "Sun").forEach { (d, l) ->
                        FilterChip(selected = d in days, onClick = { days = if (d in days) days - d else days + d }, label = { Text(l) })
                    }
                }
                Text("When a Bluetooth device connects:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                if (!btGranted) TextButton(onClick = { btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text("Allow Bluetooth to choose a device") }
                WrapRow {
                    FilterChip(selected = device == null, onClick = { device = null }, label = { Text("None") })
                    (bonded + listOfNotNull(device)).distinct().forEach { d -> FilterChip(selected = device == d, onClick = { device = d }, label = { Text(d) }) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && steps.isNotEmpty(), onClick = {
                onSave(initial.copy(
                    name = name.trim(), steps = steps,
                    phrases = phrases.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    time = time.trim().takeIf { Routine.parseTime(it) != null }, days = days, bluetoothDevice = device,
                ))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
