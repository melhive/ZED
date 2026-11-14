package com.melhive.zed.ui.screens

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
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Visibility
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
import com.melhive.zed.android.DeviceInfo
import com.melhive.zed.android.PermissionCatalog
import com.melhive.zed.android.UpdateState
import com.melhive.zed.core.tasks.TaskStatus
import com.melhive.zed.models.Compatibility
import com.melhive.zed.models.ModelCatalog
import com.melhive.zed.settings.IntelligenceMode
import com.melhive.zed.tasks.TaskTypes
import com.melhive.zed.tasks.statusEnum
import com.melhive.zed.ui.components.RealProgress
import com.melhive.zed.ui.components.SectionTitle
import com.melhive.zed.ui.components.StatusPill
import com.melhive.zed.ui.components.TaskIndicator
import com.melhive.zed.ui.components.WrapRow
import com.melhive.zed.ui.components.ZedCard
import com.melhive.zed.ui.components.ZedOrb
import com.melhive.zed.ui.navigation.Routes
import com.melhive.zed.ui.navigation.rememberContainer
import com.melhive.zed.ui.theme.ZedColors
import com.melhive.zed.voice.OrbState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * Home: greeting, an orb that opens ZED LIVE (it animates only while
 * ZED is working), a useful "next up" line, a direct "Ask ZED" box,
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
        showOutcome = activity.phase == com.melhive.zed.agents.AssistantPhase.COMPLETED || activity.phase == com.melhive.zed.agents.AssistantPhase.ERROR
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
        // Acting: say what ("Setting alarm…"); thinking: which model.
        activity.busy && activity.phase != com.melhive.zed.agents.AssistantPhase.THINKING -> activity.detail ?: activity.phase.toOrb().label
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

    val motion = settings?.animationLevel ?: "full"
    val view = androidx.compose.ui.platform.LocalView.current
    Box(Modifier.fillMaxSize()) {
    // Points of light behind everything, leaning with the phone.
    if (motion != "light") com.melhive.zed.ui.components.ParticleField(Modifier.matchParentSize(), tilt = motion == "full")
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Greeting + date
        val zdt = ZonedDateTime.now()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(greeting(LocalTime.now().hour) + (userName?.let { ", $it" } ?: ""), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (userName == null) Text("What should I call you?", style = MaterialTheme.typography.labelMedium, color = ZedColors.Cyan,
                    modifier = Modifier.clickable { askName = true }.padding(vertical = 2.dp))
                Text(zdt.format(DateTimeFormatter.ofPattern("EEE, d MMM")) + " · " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)),
                    style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
            }
            TaskIndicator(active.size) { onTab(Routes.TASKS) }
        }
        // Battery, memory, network and Gemma as one quiet line, and a countdown to what's next.
        com.melhive.zed.ui.components.HomeConsole(c, upcoming.firstOrNull()?.let { it.text to it.triggerAt }, slim = true)

        // Orb: tap to talk.
        val interaction = remember { MutableInteractionSource() }
        Box(Modifier.fillMaxWidth().clickable(interactionSource = interaction, indication = null) {
            runCatching { view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK) }
            onTab(Routes.LIVE)
        }, contentAlignment = Alignment.Center) {
            ZedOrb(orbState, size = 190.dp, glow = settings?.glow ?: true, light = settings?.animationLevel == "light")
        }
        Text(nextLine, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = if (activity.busy) ZedColors.Cyan else ZedColors.Muted,
            style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)


        // Ask box
        OutlinedTextField(
            value = ask, onValueChange = { ask = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Ask ZED anything…") },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submitAsk() }),
            trailingIcon = {
                if (ask.isBlank()) IconButton(onClick = { onTab(Routes.LIVE) }) { Icon(Icons.Outlined.Mic, "Talk", tint = ZedColors.Cyan) }
                else IconButton(onClick = { submitAsk() }) { Icon(Icons.AutoMirrored.Outlined.Send, "Send", tint = ZedColors.Cyan) }
            },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ZedColors.Cyan, unfocusedBorderColor = ZedColors.Outline),
        )

        // The four you use most; everything else is under More.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(Icons.Outlined.GraphicEq, "Live", ZedColors.Cyan, Modifier.weight(1f)) { onTab(Routes.LIVE) }
            QuickAction(Icons.Outlined.Visibility, "Eyes", ZedColors.Cyan, Modifier.weight(1f)) { onNavigate(Routes.EYE) }
            QuickAction(Icons.Outlined.Chat, "Chat", ZedColors.Violet, Modifier.weight(1f)) { onTab(Routes.CHAT) }
            QuickAction(Icons.Outlined.Memory, "Memory", ZedColors.Amber, Modifier.weight(1f)) { onNavigate(Routes.MEMORY) }
        }

        // Today
        ZedCard(onClick = { onNavigate(Routes.MEMORY) }, accent = ZedColors.Teal) {
            SectionTitle("Today")
            if (upcoming.isEmpty() && openTodos.isEmpty()) Text("Nothing scheduled. Try “remind me to stretch in 30 minutes”.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
            upcoming.take(3).forEach { r -> Text("⏰ ${formatTime(r.triggerAt)} — ${r.text}", style = MaterialTheme.typography.bodyMedium) }
            openTodos.take(3).forEach { t -> Text("○ ${t.title}", style = MaterialTheme.typography.bodyMedium) }
            if (openTodos.size > 3) Text("+${openTodos.size - 3} more tasks", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
        }

        (update as? UpdateState.Available)?.let { u ->
            ZedCard(accent = ZedColors.Green, onClick = { uriHandler.openUri(u.info.apkUrl) }) {
                SectionTitle("Update available")
                Text("ZED ${u.info.versionName} is ready. Download it and tap Install — it replaces this version and keeps your data.", style = MaterialTheme.typography.bodySmall)
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
            ZedCard(accent = ZedColors.Violet) {
                SectionTitle("Make ZED conversational")
                if (modelDownload != null) {
                    Text("Downloading the on-device model… you can keep using ZED.", style = MaterialTheme.typography.bodyMedium)
                    RealProgress(modelDownload.progressCurrent, modelDownload.progressTotal, Modifier.padding(vertical = 6.dp), ZedColors.Violet)
                    Text(listOfNotNull(modelDownload.progressLabel, modelDownload.progressCurrent?.let { cur -> modelDownload.progressTotal?.let { "${DeviceInfo.formatBytes(cur)} / ${DeviceInfo.formatBytes(it)}" } }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                } else {
                    Text("I already handle commands, calculations and small talk. For real conversations add an AI brain:", style = MaterialTheme.typography.bodyMedium)
                    Text("${recommended.name}: ${DeviceInfo.formatBytes(recommended.sizeBytes)} download. It runs entirely on your phone — private, and it works offline.",
                        style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                    WrapRow {
                        TextButton(onClick = { scope.launch { c.taskEngine.create(TaskTypes.MODEL_DOWNLOAD, "Download ${recommended.name}", mapOf("model" to recommended.id)) } }) { Text("Download on-device model") }
                        TextButton(onClick = { onNavigate(Routes.MODELS) }) { Text("See models") }
                    }
                }
            }
        }

        if (needsNotifications) {
            ZedCard(accent = ZedColors.Amber) {
                SectionTitle("Notifications are off")
                Text(notifInfo.reason, style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                TextButton(onClick = { notifLauncher.launch(notifInfo.permission) }) { Text("Allow notifications") }
            }
        }

        // The Live Brain in miniature: it flashes when ZED uses a memory.
        val brain by c.brain.snapshot.collectAsStateWithLifecycle()
        val recalls by c.brain.recalls.collectAsStateWithLifecycle()
        ZedCard(onClick = { onTab(Routes.BRAIN) }, accent = ZedColors.Violet) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiniBrain(Modifier.padding(end = 14.dp).height(64.dp).fillMaxWidth(0.22f), glow = settings?.glow ?: true)
                Column(Modifier.weight(1f)) {
                    Text("LIVE BRAIN", style = MaterialTheme.typography.titleMedium, color = ZedColors.Cyan, letterSpacing = androidx.compose.ui.unit.TextUnit(2f, androidx.compose.ui.unit.TextUnitType.Sp))
                    val g = brain?.graph
                    Text(
                        if (g == null || g.itemCount == 0) "Waiting for its first memory" else "${g.itemCount} things known · ${g.edges.size} connections",
                        style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted,
                    )
                    Text(if (recalls > 0) "Recalled $recalls ${if (recalls == 1) "memory" else "times"} since ZED opened" else "Tap to step inside",
                        style = MaterialTheme.typography.labelSmall, color = if (recalls > 0) ZedColors.Teal else ZedColors.Muted)
                }
            }
        }

        // What ZED learned from recent conversations, for review.
        LearnedCard()
        InsightsCard(onNavigate, onTab)
        val waitingOnline by c.pending.waiting.collectAsStateWithLifecycle(initialValue = 0)
        if (waitingOnline > 0) ZedCard(accent = ZedColors.Amber) {
            SectionTitle("Waiting for the internet")
            Text("$waitingOnline request${if (waitingOnline == 1) "" else "s"} will run as soon as you're back online — I'll notify you with the answer${if (waitingOnline == 1) "" else "s"}.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
        }



        if (active.isNotEmpty()) {
            ZedCard(onClick = { onTab(Routes.TASKS) }, accent = ZedColors.Amber) {
                SectionTitle("Background tasks")
                active.take(3).forEach { t ->
                    Text(t.title, style = MaterialTheme.typography.titleSmall)
                    Text(if (t.statusEnum() == TaskStatus.AWAITING_CONFIRMATION) "Waiting for your approval" else t.progressLabel ?: t.status.lowercase(),
                        style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                    if (t.statusEnum() == TaskStatus.RUNNING) RealProgress(t.progressCurrent, t.progressTotal, Modifier.padding(vertical = 6.dp), ZedColors.Amber)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        // AI status chip
        Row(Modifier.fillMaxWidth().clickable { onNavigate(Routes.MODELS) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val color = when (settings?.mode) { IntelligenceMode.LOCAL -> ZedColors.Teal; IntelligenceMode.NATIVE_ONLY -> ZedColors.Muted; else -> ZedColors.Cyan }
            StatusPill(intelligence, color, Modifier.weight(1f, fill = false))
        }
        Spacer(Modifier.height(8.dp))
    }

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
    ZedCard(modifier, onClick = onClick, accent = tint) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = tint)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}
