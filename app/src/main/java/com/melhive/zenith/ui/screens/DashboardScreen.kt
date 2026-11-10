package com.melhive.zenith.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.zenith.android.DeviceInfo
import com.melhive.zenith.android.PermissionCatalog
import com.melhive.zenith.android.UpdateState
import com.melhive.zenith.core.tasks.TaskStatus
import com.melhive.zenith.models.Compatibility
import com.melhive.zenith.models.ModelCatalog
import com.melhive.zenith.settings.IntelligenceMode
import com.melhive.zenith.tasks.TaskTypes
import com.melhive.zenith.tasks.statusEnum
import com.melhive.zenith.ui.components.RealProgress
import com.melhive.zenith.ui.components.SectionTitle
import com.melhive.zenith.ui.components.StatusPill
import com.melhive.zenith.ui.components.TaskIndicator
import com.melhive.zenith.ui.components.WrapRow
import com.melhive.zenith.ui.components.ZenithCard
import com.melhive.zenith.ui.components.ZenithOrb
import com.melhive.zenith.ui.navigation.Routes
import com.melhive.zenith.ui.navigation.rememberContainer
import com.melhive.zenith.ui.theme.ZenithColors
import com.melhive.zenith.voice.OrbState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * Home: greeting, an orb that opens ZENITH LIVE (it animates only while
 * ZENITH is working), a useful "next up" line, a direct "Ask ZENITH" box,
 * today's summary, quick actions and background work.
 */
@Composable
fun DashboardScreen(onNavigate: (String) -> Unit, onTab: (String) -> Unit) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val tasks by c.taskEngine.tasks.collectAsStateWithLifecycle(initialValue = emptyList())
    val todos by c.todos.todos.collectAsStateWithLifecycle(initialValue = emptyList())
    val reminders by c.db.reminders().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val memories by c.memory.facts.collectAsStateWithLifecycle(initialValue = emptyList())
    val activity by c.orchestrator.activity.collectAsStateWithLifecycle()
    val installedModels by c.models.installed.collectAsStateWithLifecycle(initialValue = emptyList())
    val update by c.updates.state.collectAsStateWithLifecycle()
    val intelligence by produceState("Checking…", settings, installedModels) { value = c.providers.describeActive() }
    val configured by produceState<Boolean?>(null, settings, installedModels) { value = c.providers.anyConfigured() }
    val userName by produceState<String?>(null, memories) { value = c.memory.userName() }

    // Clock tick (once a minute) for the greeting and date line.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }

    // Completed/Error flash briefly, then the orb returns to rest.
    var showOutcome by remember { mutableStateOf(false) }
    LaunchedEffect(activity.at, activity.phase) {
        showOutcome = activity.phase == com.melhive.zenith.agents.AssistantPhase.COMPLETED || activity.phase == com.melhive.zenith.agents.AssistantPhase.ERROR
        if (showOutcome) { delay(2500); showOutcome = false }
    }
    val orbState = when {
        activity.busy -> activity.phase.toOrb()
        showOutcome -> activity.phase.toOrb()
        else -> OrbState.IDLE
    }

    var permissionCheck by remember { mutableIntStateOf(0) }
    val notifInfo = PermissionCatalog.NOTIFICATIONS
    val needsNotifications = remember(permissionCheck) { PermissionCatalog.applicable(notifInfo) && !PermissionCatalog.isGranted(context, notifInfo) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionCheck++ }

    val active = tasks.filter { !it.statusEnum().terminal }
    val modelDownload = tasks.firstOrNull { it.type == TaskTypes.MODEL_DOWNLOAD && !it.statusEnum().terminal }
    val upcoming = reminders.filter { it.status == "scheduled" && it.triggerAt > now }.sortedBy { it.triggerAt }
    val openTodos = todos.filter { !it.done }
    val nextLine = when {
        activity.busy -> activity.phase.toOrb().label + (activity.detail?.let { " · $it" } ?: "")
        upcoming.isNotEmpty() -> "Next: ${upcoming.first().text} at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(upcoming.first().triggerAt))}" +
            if (openTodos.isNotEmpty()) " · ${openTodos.size} open task${if (openTodos.size == 1) "" else "s"}" else ""
        openTodos.isNotEmpty() -> "${openTodos.size} open task${if (openTodos.size == 1) "" else "s"} · first: ${openTodos.first().title}"
        else -> "All clear. Tap the orb to talk, or ask below."
    }

    var ask by rememberSaveable { mutableStateOf("") }
    var askName by remember { mutableStateOf(false) }
    fun submitAsk() {
        val text = ask.trim()
        if (text.isEmpty()) return
        ask = ""
        scope.launch {
            val id = c.conversations.latestOrCreate()
            c.chatFocus.value = id
            c.orchestrator.submit(id, text)
            onTab(Routes.CHAT)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Greeting + date
        val zdt = ZonedDateTime.now()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(greeting(LocalTime.now().hour) + (userName?.let { ", $it" } ?: ""), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (userName == null) Text("What should I call you?", style = MaterialTheme.typography.labelMedium, color = ZenithColors.Cyan,
                    modifier = Modifier.clickable { askName = true }.padding(vertical = 2.dp))
                Text(zdt.format(DateTimeFormatter.ofPattern("EEE, d MMM")) + " · " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)),
                    style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
            }
            TaskIndicator(active.size) { onTab(Routes.TASKS) }
        }

        // Orb: tap to talk.
        val interaction = remember { MutableInteractionSource() }
        Box(Modifier.fillMaxWidth().clickable(interactionSource = interaction, indication = null) { onTab(Routes.LIVE) }, contentAlignment = Alignment.Center) {
            ZenithOrb(orbState, size = 190.dp, glow = settings?.glow ?: true)
        }
        Text(nextLine, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = if (activity.busy) ZenithColors.Cyan else ZenithColors.Muted,
            style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)

        // Ask box
        OutlinedTextField(
            value = ask, onValueChange = { ask = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Ask ZENITH anything…") },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submitAsk() }),
            trailingIcon = {
                if (ask.isBlank()) IconButton(onClick = { onTab(Routes.LIVE) }) { Icon(Icons.Outlined.Mic, "Talk", tint = ZenithColors.Cyan) }
                else IconButton(onClick = { submitAsk() }) { Icon(Icons.AutoMirrored.Outlined.Send, "Send", tint = ZenithColors.Cyan) }
            },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ZenithColors.Cyan, unfocusedBorderColor = ZenithColors.Outline),
        )

        AllowEverythingCard()

        (update as? UpdateState.Available)?.let { u ->
            ZenithCard(accent = ZenithColors.Green, onClick = { uriHandler.openUri(u.info.apkUrl) }) {
                SectionTitle("Update available")
                Text("ZENITH ${u.info.versionName} is ready. Download it and tap Install — it replaces this version and keeps your data.", style = MaterialTheme.typography.bodySmall)
                WrapRow {
                    TextButton(onClick = { uriHandler.openUri(u.info.apkUrl) }) { Text("Download update") }
                    TextButton(onClick = { uriHandler.openUri(u.info.pageUrl) }) { Text("What's new") }
                }
            }
        }

        if (configured == false || modelDownload != null) {
            val recommended = remember(installedModels) {
                val e2b = ModelCatalog.find("gemma-4-e2b-it")!!
                if (c.models.assess(e2b).level != Compatibility.INCOMPATIBLE) e2b else ModelCatalog.find("qwen3-0.6b")!!
            }
            ZenithCard(accent = ZenithColors.Violet) {
                SectionTitle("Make ZENITH conversational")
                if (modelDownload != null) {
                    Text("Downloading the on-device model… you can keep using ZENITH.", style = MaterialTheme.typography.bodyMedium)
                    RealProgress(modelDownload.progressCurrent, modelDownload.progressTotal, Modifier.padding(vertical = 6.dp), ZenithColors.Violet)
                    Text(listOfNotNull(modelDownload.progressLabel, modelDownload.progressCurrent?.let { cur -> modelDownload.progressTotal?.let { "${DeviceInfo.formatBytes(cur)} / ${DeviceInfo.formatBytes(it)}" } }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
                } else {
                    Text("I already handle commands, calculations and small talk. For real conversations add an AI brain:", style = MaterialTheme.typography.bodyMedium)
                    Text("• On-device: ${recommended.name}, ${DeviceInfo.formatBytes(recommended.sizeBytes)} download, private and offline.\n• Online: Gemini, OpenAI or Claude with your API key — fastest.",
                        style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
                    WrapRow {
                        TextButton(onClick = { scope.launch { c.taskEngine.create(TaskTypes.MODEL_DOWNLOAD, "Download ${recommended.name}", mapOf("model" to recommended.id)) } }) { Text("Download on-device model") }
                        TextButton(onClick = { onNavigate(Routes.MODELS) }) { Text("Use online AI") }
                    }
                }
            }
        }

        if (needsNotifications) {
            ZenithCard(accent = ZenithColors.Amber) {
                SectionTitle("Notifications are off")
                Text(notifInfo.reason, style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
                TextButton(onClick = { notifLauncher.launch(notifInfo.permission) }) { Text("Allow notifications") }
            }
        }

        // Today
        ZenithCard(onClick = { onNavigate(Routes.MEMORY) }, accent = ZenithColors.Teal) {
            SectionTitle("Today")
            if (upcoming.isEmpty() && openTodos.isEmpty()) Text("Nothing scheduled. Try “remind me to stretch in 30 minutes”.", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
            upcoming.take(3).forEach { r -> Text("⏰ ${formatTime(r.triggerAt)} — ${r.text}", style = MaterialTheme.typography.bodyMedium) }
            openTodos.take(3).forEach { t -> Text("○ ${t.title}", style = MaterialTheme.typography.bodyMedium) }
            if (openTodos.size > 3) Text("+${openTodos.size - 3} more tasks", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(Icons.Outlined.GraphicEq, "Live", ZenithColors.Cyan, Modifier.weight(1f)) { onTab(Routes.LIVE) }
            QuickAction(Icons.Outlined.Chat, "Chat", ZenithColors.Violet, Modifier.weight(1f)) { onTab(Routes.CHAT) }
            QuickAction(Icons.Outlined.Folder, "Files", ZenithColors.Teal, Modifier.weight(1f)) { onNavigate(Routes.FILES) }
            QuickAction(Icons.Outlined.Memory, "Memory", ZenithColors.Amber, Modifier.weight(1f)) { onNavigate(Routes.MEMORY) }
        }

        if (active.isNotEmpty()) {
            ZenithCard(onClick = { onTab(Routes.TASKS) }, accent = ZenithColors.Amber) {
                SectionTitle("Background tasks")
                active.take(3).forEach { t ->
                    Text(t.title, style = MaterialTheme.typography.titleSmall)
                    Text(if (t.statusEnum() == TaskStatus.AWAITING_CONFIRMATION) "Waiting for your approval" else t.progressLabel ?: t.status.lowercase(),
                        style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
                    if (t.statusEnum() == TaskStatus.RUNNING) RealProgress(t.progressCurrent, t.progressTotal, Modifier.padding(vertical = 6.dp), ZenithColors.Amber)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        // AI status chip
        Row(Modifier.fillMaxWidth().clickable { onNavigate(Routes.MODELS) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val color = when (settings?.mode) { IntelligenceMode.LOCAL -> ZenithColors.Teal; IntelligenceMode.ONLINE -> ZenithColors.Violet; IntelligenceMode.NATIVE_ONLY -> ZenithColors.Muted; else -> ZenithColors.Cyan }
            StatusPill(intelligence, color, Modifier.weight(1f, fill = false))
        }
        Spacer(Modifier.height(8.dp))
    }

    if (askName) NameDialog(initial = "", onDismiss = { askName = false }) { name -> scope.launch { c.memory.setUserName(name) }; askName = false }
}

@Composable
fun NameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What should I call you?") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Your name") }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

private fun greeting(hour: Int) = when (hour) { in 5..11 -> "Good Morning"; in 12..17 -> "Good Afternoon"; in 18..22 -> "Good Evening"; else -> "Hello" }

@Composable
private fun QuickAction(icon: ImageVector, label: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    ZenithCard(modifier, onClick = onClick, accent = tint) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = tint)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
