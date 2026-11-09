package com.melhive.zenith.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.zenith.R
import com.melhive.zenith.core.tasks.TaskStatus
import com.melhive.zenith.settings.IntelligenceMode
import com.melhive.zenith.tasks.statusEnum
import com.melhive.zenith.ui.components.RealProgress
import com.melhive.zenith.ui.components.SectionTitle
import com.melhive.zenith.ui.components.StatusPill
import com.melhive.zenith.ui.components.TaskIndicator
import com.melhive.zenith.ui.components.ZenithCard
import com.melhive.zenith.ui.components.ZenithOrb
import com.melhive.zenith.ui.navigation.Routes
import com.melhive.zenith.ui.navigation.rememberContainer
import com.melhive.zenith.ui.theme.ZenithColors
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.melhive.zenith.android.PermissionCatalog
import com.melhive.zenith.android.UpdateState
import androidx.compose.ui.platform.LocalUriHandler

@Composable
fun DashboardScreen(onNavigate: (String) -> Unit, onTab: (String) -> Unit) {
    val c = rememberContainer()
    val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val tasks by c.taskEngine.tasks.collectAsStateWithLifecycle(initialValue = emptyList())
    val todos by c.todos.todos.collectAsStateWithLifecycle(initialValue = emptyList())
    val reminders by c.db.reminders().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val memories by c.memory.facts.collectAsStateWithLifecycle(initialValue = emptyList())
    val activity by c.orchestrator.activity.collectAsStateWithLifecycle()
    val installedModels by c.models.installed.collectAsStateWithLifecycle(initialValue = emptyList())
    val intelligence by produceState("Checking…", settings, installedModels) { value = c.providers.describeActive() }

    val context = LocalContext.current
    var permissionCheck by remember { mutableIntStateOf(0) }
    val notifInfo = PermissionCatalog.NOTIFICATIONS
    val needsNotifications = remember(permissionCheck) { PermissionCatalog.applicable(notifInfo) && !PermissionCatalog.isGranted(context, notifInfo) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionCheck++ }

    val update by c.updates.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    val configured by produceState<Boolean?>(null, settings, installedModels) { value = c.providers.anyConfigured() }
    val modelDownload = tasks.firstOrNull { it.type == "model_download" && !it.statusEnum().terminal }
    val scope = rememberCoroutineScope()

    val active = tasks.filter { !it.statusEnum().terminal }
    val upcoming = reminders.filter { it.status == "scheduled" && it.triggerAt > System.currentTimeMillis() }.take(3)
    val openTodos = todos.filter { !it.done }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ZENITH", style = MaterialTheme.typography.displaySmall, color = ZenithColors.Text)
                Text(stringResource(R.string.tagline), style = MaterialTheme.typography.labelSmall, color = ZenithColors.Muted)
            }
            TaskIndicator(active.size) { onTab(Routes.TASKS) }
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ZenithOrb(activity.phase.toOrb(), size = 200.dp, glow = settings?.glow ?: true)
        }
        Text(
            activity.phase.toOrb().label + (activity.detail?.let { " · $it" } ?: ""),
            Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = ZenithColors.Muted, style = MaterialTheme.typography.bodyMedium,
        )

        (update as? UpdateState.Available)?.let { u ->
            ZenithCard(accent = ZenithColors.Green, onClick = { uriHandler.openUri(u.info.apkUrl) }) {
                SectionTitle("Update available")
                Text("ZENITH ${u.info.versionName} is ready. Download it and tap Install — it replaces this version and keeps your data.", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { uriHandler.openUri(u.info.apkUrl) }) { Text("Download update") }
                    TextButton(onClick = { uriHandler.openUri(u.info.pageUrl) }) { Text("What's new") }
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

        if (configured == false || modelDownload != null) {
            val recommended = remember(installedModels) {
                val e2b = com.melhive.zenith.models.ModelCatalog.find("gemma-4-e2b-it")!!
                val small = com.melhive.zenith.models.ModelCatalog.find("qwen3-0.6b")!!
                if (c.models.assess(e2b).level == com.melhive.zenith.models.Compatibility.COMPATIBLE) e2b else small
            }
            ZenithCard(accent = ZenithColors.Violet) {
                SectionTitle("Make ZENITH conversational")
                if (modelDownload != null) {
                    Text("Downloading ${recommended.name.takeIf { modelDownload.title.contains(it) } ?: "model"}… you can keep using ZENITH.", style = MaterialTheme.typography.bodyMedium)
                    RealProgress(modelDownload.progressCurrent, modelDownload.progressTotal, Modifier.padding(vertical = 6.dp), ZenithColors.Violet)
                    Text(modelDownload.progressLabel ?: "Starting", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
                } else {
                    Text("Right now I answer commands, calculations and small talk. For real conversations, add an AI brain:", style = MaterialTheme.typography.bodyMedium)
                    Text("• On-device: ${recommended.name}, ${com.melhive.zenith.android.DeviceInfo.formatBytes(recommended.sizeBytes)} download, works offline and private.\n• Online: Gemini, OpenAI or Claude with your API key — fastest and smartest.",
                        style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
                    Row {
                        TextButton(onClick = {
                            scope.launch { c.taskEngine.create(com.melhive.zenith.tasks.TaskTypes.MODEL_DOWNLOAD, "Download ${recommended.name}", mapOf("model" to recommended.id)) }
                        }) { Text("Download on-device model") }
                        TextButton(onClick = { onNavigate(Routes.MODELS) }) { Text("Use online AI") }
                    }
                }
            }
        }

        ZenithCard(onClick = { onNavigate(Routes.MODELS) }) {
            SectionTitle("Intelligence")
            val modeColor = when (settings?.mode) { IntelligenceMode.LOCAL -> ZenithColors.Teal; IntelligenceMode.ONLINE -> ZenithColors.Violet; else -> ZenithColors.Cyan }
            StatusPill(
                when (settings?.mode) { IntelligenceMode.AUTO -> "Auto"; IntelligenceMode.LOCAL -> "On-device AI"; IntelligenceMode.ONLINE -> "Online AI"; else -> "Native mode" }, modeColor,
            )
            Spacer(Modifier.height(8.dp))
            Text(intelligence, style = MaterialTheme.typography.bodyMedium)
            Text("Native commands always work offline. Tap to manage models.", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(Icons.Outlined.GraphicEq, "Live", ZenithColors.Cyan, Modifier.weight(1f)) { onTab(Routes.LIVE) }
            QuickAction(Icons.Outlined.Chat, "Chat", ZenithColors.Violet, Modifier.weight(1f)) { onTab(Routes.CHAT) }
            QuickAction(Icons.Outlined.Folder, "Files", ZenithColors.Teal, Modifier.weight(1f)) { onNavigate(Routes.FILES) }
            QuickAction(Icons.Outlined.Memory, "Memory", ZenithColors.Amber, Modifier.weight(1f)) { onNavigate(Routes.MEMORY) }
        }

        if (active.isNotEmpty()) {
            ZenithCard(onClick = { onTab(Routes.TASKS) }, accent = ZenithColors.Amber) {
                SectionTitle("Active tasks")
                active.take(3).forEach { t ->
                    Text(t.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        when (t.statusEnum()) { TaskStatus.AWAITING_CONFIRMATION -> "Awaiting your confirmation"; else -> t.progressLabel ?: t.status.lowercase() },
                        style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted,
                    )
                    if (t.statusEnum() == TaskStatus.RUNNING) RealProgress(t.progressCurrent, t.progressTotal, Modifier.padding(vertical = 6.dp), ZenithColors.Amber)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        ZenithCard(onClick = { onNavigate(Routes.MEMORY) }, accent = ZenithColors.Teal) {
            SectionTitle("Today")
            Text("${openTodos.size} open task-list items · ${upcoming.size} upcoming reminders · ${memories.size} memories", style = MaterialTheme.typography.bodyMedium)
            upcoming.forEach { r -> Text("⏰ ${formatTime(r.triggerAt)} — ${r.text}", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted) }
            openTodos.take(3).forEach { t -> Text("○ ${t.title}", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

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

