package com.melhive.zenith.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.zenith.BuildConfig
import com.melhive.zenith.android.DeviceInfo
import com.melhive.zenith.android.Notifications
import com.melhive.zenith.android.PermissionCatalog
import com.melhive.zenith.android.SystemIntents
import com.melhive.zenith.core.skills.SkillRegistry
import com.melhive.zenith.ui.components.IconLabel
import com.melhive.zenith.ui.components.SectionTitle
import com.melhive.zenith.ui.components.StatusPill
import com.melhive.zenith.ui.components.WrapRow
import com.melhive.zenith.ui.components.ZenithCard
import com.melhive.zenith.ui.navigation.Routes
import com.melhive.zenith.ui.navigation.rememberContainer
import com.melhive.zenith.ui.theme.MonoStyle
import com.melhive.zenith.ui.theme.ZenithColors
import kotlinx.coroutines.launch

@Composable
fun MoreScreen(onNavigate: (String) -> Unit) {
    data class Entry(val route: String, val icon: ImageVector, val title: String, val subtitle: String)
    val entries = listOf(
        Entry(Routes.MEMORY, Icons.Outlined.Memory, "Memory", "Facts, task list, reminders and document index"),
        Entry(Routes.MODELS, Icons.Outlined.Psychology, "Model Manager", "On-device models and online providers"),
        Entry(Routes.FILES, Icons.Outlined.Folder, "Files", "Granted folders, browse and organize"),
        Entry(Routes.SKILLS, Icons.Outlined.AutoAwesome, "Skills", "Capabilities ZENITH can use"),
        Entry(Routes.SETTINGS, Icons.Outlined.Settings, "Settings", "Voice, safety and behaviour"),
        Entry(Routes.DIAGNOSTICS, Icons.Outlined.Security, "Permissions & Diagnostics", "Permissions, voice engines, audit log"),
    )
    ListScreen("More", onBack = null) {
        items(entries) { e -> ZenithCard(onClick = { onNavigate(e.route) }) { IconLabel(e.icon, e.title, e.subtitle) } }
        item { Text("ZENITH ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = ZenithColors.Muted, modifier = Modifier.padding(8.dp)) }
    }
}

@Composable
fun SkillsScreen(onBack: () -> Unit) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val enabled = settings?.enabledSkills ?: emptySet()
    ListScreen("Skills", onBack = onBack, subtitle = "Disabled skills are hidden from AI models; native commands still work") {
        items(c.skills.skills, key = { it.id }) { skill ->
            ZenithCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(skill.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(checked = skill.id in enabled, onCheckedChange = { on -> scope.launch { c.settings.setSkillEnabled(skill.id, on) } })
                }
                Text(skill.description, style = MaterialTheme.typography.bodySmall)
                skill.tools(c.catalog).forEach { t ->
                    Text("• ${t.name} — ${t.risk.name.lowercase().replace('_', ' ')}" + if (t.params.isNotEmpty()) " (${t.params.joinToString { p -> p.name + if (p.required) "" else "?" }})" else "",
                        style = MonoStyle, color = ZenithColors.Muted)
                }
                val caps = skill.capabilities(c.catalog)
                if (caps.isNotEmpty()) Text("Requires: " + caps.joinToString { it.label }, style = MaterialTheme.typography.labelSmall, color = ZenithColors.Amber)
                if (skill.requiresOnline) Text("Needs an online provider", style = MaterialTheme.typography.labelSmall, color = ZenithColors.Violet)
            }
        }
        item { Text("${SkillRegistry.DEFAULT_SKILLS.size} skills · ${c.catalog.all.size} tools. Every tool call is validated, permission-checked, confirmed when needed and audited.", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted) }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val s by c.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    var editName by remember { mutableStateOf(false) }
    val settings = s ?: return
    ListScreen("Settings", onBack = onBack) {
        item {
            val facts by c.memory.facts.collectAsStateWithLifecycle(initialValue = emptyList())
            val name = facts.firstNotNullOfOrNull { Regex("^my name is (.+)$", RegexOption.IGNORE_CASE).find(it.text.trim())?.groupValues?.get(1) }
            ZenithCard {
                SectionTitle("You")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name?.let { "ZENITH calls you $it" } ?: "ZENITH doesn't know your name yet", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { editName = true }) { Text(if (name == null) "Set name" else "Change") }
                }
            }
            if (editName) NameDialog(initial = name ?: "", onDismiss = { editName = false }) { n -> scope.launch { c.memory.setUserName(n); editName = false } }
        }
        item {
            ZenithCard {
                SectionTitle("Voice")
                SwitchRow("Voice responses", "Speak replies in ZENITH LIVE and after voice input", settings.voiceResponses) { scope.launch { c.settings.setVoiceResponses(it) } }
                SwitchRow("Interrupt by talking", "With headphones or earbuds, start talking to cut ZENITH off. On the loudspeaker, tap the orb instead.", settings.bargeIn) { scope.launch { c.settings.setBargeIn(it) } }
                Text("Speech rate: ${"%.1f".format(settings.speechRate)}×", style = MaterialTheme.typography.titleSmall)
                Slider(value = settings.speechRate, onValueChange = { v -> scope.launch { c.settings.setSpeechRate(v) } }, valueRange = 0.5f..2.0f, steps = 14)
            }
        }
        item {
            ZenithCard {
                SectionTitle("Safety")
                SwitchRow("Confirm every change", "Ask before any modifying action, even from native commands (deletes always ask)", settings.confirmAllChanges) { scope.launch { c.settings.setConfirmAllChanges(it) } }
            }
        }
        item {
            ZenithCard {
                SectionTitle("Knowledge")
                SwitchRow("Use local documents as context", "Include matching excerpts from indexed documents in AI prompts", settings.useKnowledge) { scope.launch { c.settings.setUseKnowledge(it) } }
            }
        }
        item {
            ZenithCard {
                SectionTitle("Appearance")
                SwitchRow("Glow effects", "Orb and card glow (turn off to save battery)", settings.glow) { scope.launch { c.settings.setGlow(it) } }
            }
        }
        item {
            val update by c.updates.state.collectAsStateWithLifecycle()
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            ZenithCard {
                SectionTitle("App updates")
                Text("Installed: ZENITH ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
                Text(
                    when (val u = update) {
                        com.melhive.zenith.android.UpdateState.Unknown -> "Not checked yet"
                        com.melhive.zenith.android.UpdateState.Checking -> "Checking GitHub…"
                        com.melhive.zenith.android.UpdateState.UpToDate -> "You have the latest version"
                        is com.melhive.zenith.android.UpdateState.Available -> "Version ${u.info.versionName} is available"
                        is com.melhive.zenith.android.UpdateState.Failed -> "Check failed: ${u.message}"
                    },
                    style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted,
                )
                SwitchRow("Check for updates", "Ask GitHub Releases for a newer version when ZENITH starts", settings.checkUpdates) { scope.launch { c.settings.setCheckUpdates(it) } }
                WrapRow {
                    TextButton(onClick = { scope.launch { c.updates.check() } }) { Text("Check now") }
                    (update as? com.melhive.zenith.android.UpdateState.Available)?.let { u ->
                        TextButton(onClick = { uriHandler.openUri(u.info.apkUrl) }) { Text("Download update") }
                    }
                    val ctx = LocalContext.current
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "Install ZENITH on Android: ${com.melhive.zenith.android.UpdateChecker.LATEST_APK}")
                        ctx.startActivity(Intent.createChooser(send, "Share ZENITH"))
                    }) { Text("Share app") }
                }
            }
        }
        item {
            ZenithCard(onClick = { onNavigate(Routes.MODELS) }) { IconLabel(Icons.Outlined.Psychology, "Models & providers", "Configure intelligence layers in Model Manager") }
        }
    }
}

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val c = rememberContainer()
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val audit by c.db.audit().observeRecent(100).collectAsStateWithLifecycle(initialValue = emptyList())
    val device = remember(refresh) { DeviceInfo.snapshot(context) }
    val intents = remember { SystemIntents(context) }
    val ttsInfo by produceState<String?>(null, refresh) { value = if (c.chatTts.awaitReady()) c.chatTts.info()?.let { "${it.engine} · ${it.language} · voice ${it.voiceName ?: "default"} · offline voice ${if (it.offlineVoiceAvailable) "available" else "not installed"}" } else "Text-to-speech unavailable" }
    val grants by produceState(0, refresh) { value = c.files.validGrants().size }
    val chunkCount by produceState(0, refresh) { value = c.knowledge.chunkCount() }
    var showAudit by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { refresh++ }

    ListScreen("Permissions & Diagnostics", onBack = onBack, actions = { TextButton(onClick = { refresh++ }) { Text("Refresh") } }) {
        item {
            val crash = remember(refresh) { com.melhive.zenith.android.CrashReporter.lastCrash() }
            val errors = remember(refresh) { com.melhive.zenith.android.CrashReporter.errors() }
            if (crash != null || errors != null) {
                ZenithCard(accent = ZenithColors.Red) {
                    SectionTitle("Problem reports")
                    Text(if (crash != null) "ZENITH crashed last time. Share the report so it can be fixed." else "Some background errors were recorded.", style = MaterialTheme.typography.bodySmall)
                    val report = listOfNotNull(crash, errors?.let { "Recent errors:\n$it" }).joinToString("\n\n")
                    Text(report.take(1500), style = MonoStyle, color = ZenithColors.Muted)
                    WrapRow {
                        TextButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "ZENITH problem report").putExtra(Intent.EXTRA_TEXT, report)
                            context.startActivity(Intent.createChooser(send, "Share report"))
                        }) { Text("Share report") }
                        TextButton(onClick = { com.melhive.zenith.android.CrashReporter.clear(); refresh++ }) { Text("Clear") }
                    }
                }
            }
        }
        item { SectionTitle("Permissions") }
        items(PermissionCatalog.ALL.filter { PermissionCatalog.applicable(it) }) { p ->
            val granted = remember(refresh) { PermissionCatalog.isGranted(context, p) }
            ZenithCard(accent = if (granted) ZenithColors.Green else ZenithColors.Amber) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    StatusPill(if (granted) "Granted" else "Not granted", if (granted) ZenithColors.Green else ZenithColors.Amber)
                }
                Text(p.reason, style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
                if (!granted) WrapRow {
                    Button(onClick = { launcher.launch(p.permission) }) { Text("Request") }
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("App settings") }
                }
            }
        }
        item {
            val exact = remember(refresh) { c.reminderScheduler.canScheduleExact() }
            ZenithCard(accent = if (exact) ZenithColors.Green else ZenithColors.Muted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Alarms & reminders (exact timing)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    StatusPill(if (exact) "Allowed" else "Optional", if (exact) ZenithColors.Green else ZenithColors.Muted)
                }
                Text("Optional. Without it, reminders still fire but Android may delay them a few minutes.", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
                if (!exact && Build.VERSION.SDK_INT >= 31) TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                }) { Text("Allow in system settings") }
            }
        }
        item {
            ZenithCard {
                SectionTitle("Capabilities")
                val rows = listOf(
                    "Folder access" to "$grants granted folder(s)",
                    "Notifications" to if (Notifications.canPost(context)) "can post" else "blocked",
                    "Clock app (alarms/timers)" to if (intents.canSetAlarms()) "available" else "not found",
                    "Calendar app" to if (intents.canInsertEvents()) "available" else "not found",
                    "Speech recognition" to when {
                        PermissionCatalog.onDeviceRecognitionAvailable(context) -> "on-device recognizer available (offline)"
                        PermissionCatalog.speechRecognitionAvailable(context) -> "recognition service available (offline depends on installed language packs)"
                        else -> "not available"
                    },
                    "Text-to-speech" to (ttsInfo ?: "checking…"),
                    "Document index" to "$chunkCount chunks",
                    "Network" to if (device.online) (if (device.unmetered) "online (unmetered)" else "online (metered)") else "offline",
                )
                rows.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            ZenithCard {
                SectionTitle("Device")
                Text("${device.model} · Android ${device.androidVersion} (API ${device.sdk}) · ${device.abis.joinToString()}", style = MaterialTheme.typography.bodySmall)
                Text("RAM ${DeviceInfo.formatBytes(device.totalRamBytes)} total, ${DeviceInfo.formatBytes(device.availRamBytes)} available${if (device.lowMemory) " (LOW)" else ""}", style = MaterialTheme.typography.bodySmall)
                Text("Free storage ${DeviceInfo.formatBytes(device.freeStorageBytes)} · Models use ${DeviceInfo.formatBytes(c.models.usedBytes())}", style = MaterialTheme.typography.bodySmall)
                Text("App ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
            }
        }
        item {
            SectionTitle("Audit log (${audit.size})") { TextButton(onClick = { showAudit = !showAudit }) { Text(if (showAudit) "Hide" else "Show") } }
        }
        if (showAudit) items(audit, key = { it.id }) { a ->
            Text("${formatTime(a.timestamp)} ${a.outcome.uppercase()} ${a.tool} [${a.origin.lowercase()}] ${a.argsSummary} → ${a.detail}", style = MonoStyle,
                color = when (a.outcome) { "executed" -> ZenithColors.Text; "failed", "invalid" -> ZenithColors.Red; else -> ZenithColors.Amber })
        }
    }
}

