package com.melhive.zed.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import com.melhive.zed.BuildConfig
import com.melhive.zed.android.DeviceInfo
import com.melhive.zed.android.Notifications
import com.melhive.zed.android.PermissionCatalog
import com.melhive.zed.android.SystemIntents
import com.melhive.zed.core.skills.SkillRegistry
import com.melhive.zed.ui.components.IconLabel
import com.melhive.zed.ui.components.SectionTitle
import com.melhive.zed.ui.components.StatusPill
import com.melhive.zed.ui.components.WrapRow
import com.melhive.zed.ui.components.ZedCard
import com.melhive.zed.ui.navigation.Routes
import com.melhive.zed.ui.navigation.rememberContainer
import com.melhive.zed.ui.theme.MonoStyle
import com.melhive.zed.ui.theme.ZedColors
import kotlinx.coroutines.launch

@Composable
fun MoreScreen(onNavigate: (String) -> Unit) {
    data class Entry(val route: String, val icon: ImageVector, val title: String, val subtitle: String)
    val groups = listOf(
        "Assistant" to listOf(
            Entry(Routes.VOICE, Icons.Outlined.RecordVoiceOver, "Voice", "Voices, Hey Zed, real-time voice, speed and sounds"),
            Entry(Routes.ROUTINES, Icons.Outlined.AutoAwesome, "Routines & briefing", "Good morning, Good night, Focus, Driving"),
            Entry(Routes.SKILLS, Icons.Outlined.AutoAwesome, "Skills", "What ZED is allowed to do"),
        ),
        "Your data" to listOf(
            Entry(Routes.SCHEDULE, Icons.Outlined.Memory, "Schedule", "Calendar events and reminders"),
            Entry(Routes.MEMORY, Icons.Outlined.Memory, "Memory", "Facts, task list, reminders and documents"),
            Entry(Routes.BRAIN, Icons.Outlined.Hub, "Live Brain", "Everything ZED knows as a living, connected network"),
            Entry(Routes.FILES, Icons.Outlined.Folder, "Files", "Granted folders, browse and organize"),
            Entry(Routes.BACKUP, Icons.Outlined.SettingsBackupRestore, "Backup & restore", "Save everything to a file, or move ZED to another phone"),
        ),
        "Intelligence" to listOf(
            Entry(Routes.MODELS, Icons.Outlined.Psychology, "Models & AI", "Gemma and other on-device models matched to your phone"),
        ),
        "System" to listOf(
            Entry(Routes.SETTINGS, Icons.Outlined.Settings, "Settings", "Personality, actions, smart home, updates"),
            Entry(Routes.DIAGNOSTICS, Icons.Outlined.Security, "Permissions & Diagnostics", "Permissions, engines, problem reports"),
            Entry(Routes.WHATS_NEW, Icons.Outlined.NewReleases, "What's new", "Every version's changes"),
        ),
    )
    ListScreen("More", onBack = null) {
        item { UpdateBanner(onOpen = { onNavigate(Routes.WHATS_NEW) }) }
        groups.forEach { (title, entries) ->
            item(key = "g-$title") { SectionTitle(title) }
            items(entries, key = { it.route }) { e -> ZedCard(onClick = { onNavigate(e.route) }) { IconLabel(e.icon, e.title, e.subtitle) } }
        }
        item { ZedFooter(onNavigate) }
    }
}

/** "ZED · version · MelHive Project" at the bottom of More and Settings. */
@Composable
fun ZedFooter(onNavigate: (String) -> Unit) {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.melhive.zed.R.drawable.zed_logo), contentDescription = "ZED logo", modifier = Modifier.size(64.dp))
        Text("ZED", style = MaterialTheme.typography.titleLarge, color = ZedColors.Cyan, letterSpacing = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp))
        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = ZedColors.Muted)
        Text("MelHive Project", style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted.copy(alpha = 0.8f), letterSpacing = androidx.compose.ui.unit.TextUnit(2f, androidx.compose.ui.unit.TextUnitType.Sp))
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onNavigate(Routes.TERMS) }) { Text("Terms of Use", style = MaterialTheme.typography.labelMedium, color = ZedColors.Cyan) }
            Text("·", color = ZedColors.Muted)
            TextButton(onClick = { onNavigate(Routes.PRIVACY) }) { Text("Privacy Policy", style = MaterialTheme.typography.labelMedium, color = ZedColors.Cyan) }
        }
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
            ZedCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(skill.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(checked = skill.id in enabled, onCheckedChange = { on -> scope.launch { c.settings.setSkillEnabled(skill.id, on) } })
                }
                Text(skill.description, style = MaterialTheme.typography.bodySmall)
                skill.tools(c.catalog).forEach { t ->
                    Text("• ${t.name} — ${t.risk.name.lowercase().replace('_', ' ')}" + if (t.params.isNotEmpty()) " (${t.params.joinToString { p -> p.name + if (p.required) "" else "?" }})" else "",
                        style = MonoStyle, color = ZedColors.Muted)
                }
                val caps = skill.capabilities(c.catalog)
                if (caps.isNotEmpty()) Text("Requires: " + caps.joinToString { it.label }, style = MaterialTheme.typography.labelSmall, color = ZedColors.Amber)
                if (skill.requiresOnline) Text("Needs an online provider", style = MaterialTheme.typography.labelSmall, color = ZedColors.Violet)
            }
        }
        item { Text("${SkillRegistry.DEFAULT_SKILLS.size} skills · ${c.catalog.all.size} tools. Every tool call is validated, permission-checked, confirmed when needed and audited.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted) }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
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
            ZedCard {
                SectionTitle("You")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name?.let { "ZED calls you $it" } ?: "ZED doesn't know your name yet", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { editName = true }) { Text(if (name == null) "Set name" else "Change") }
                }
            }
            if (editName) NameDialog(initial = name ?: "", onDismiss = { editName = false }) { n -> scope.launch { c.memory.setUserName(n); editName = false } }
        }
        item {
            val facts by c.memory.facts.collectAsStateWithLifecycle(initialValue = emptyList())
            val userName = facts.firstNotNullOfOrNull { Regex("^my name is (.+)$", RegexOption.IGNORE_CASE).find(it.text.trim())?.groupValues?.get(1) }
            PersonalityCard(settings.persona, settings.userTitle, userName) { persona, title -> scope.launch { c.settings.setPersona(persona); c.settings.setUserTitle(title) } }
        }
        if (!settings.permissionsCardHidden) item { AllowEverythingCard(onHide = { scope.launch { c.settings.setPermissionsCardHidden(true) } }) }
        item { AssistantShortcutsCard() }
        item { SmartHomeCard(settings.homeAssistantUrl) }
        item {
            ZedCard {
                SectionTitle("Voice")
                SwitchRow("Voice responses", "Speak replies in ZED LIVE and after voice input", settings.voiceResponses) { scope.launch { c.settings.setVoiceResponses(it) } }
                SwitchRow("Interrupt by talking", "With headphones or earbuds, start talking to cut ZED off. On the loudspeaker, tap the orb instead.", settings.bargeIn) { scope.launch { c.settings.setBargeIn(it) } }
                SwitchRow("Say “stop” to interrupt", "On the loudspeaker, say “stop”, “wait” or “enough” to cut ZED off. Needs on-device speech recognition (More → Voice). Turn it off if answers feel slower.", settings.voiceStop) { scope.launch { c.settings.setVoiceStop(it) } }
                SwitchRow("Guest mode while locked", "On a locked phone ZED still answers general questions, the time, the weather and web searches, and sets timers, alarms, the flashlight and volume. Anything personal waits until you unlock.", settings.lockGuard) { scope.launch { c.settings.setLockGuard(it) } }
                SwitchRow("Vibrate with LIVE sounds", "A short pulse when ZED wakes, hears you and ends a call", settings.liveHaptics) { scope.launch { c.settings.setLiveHaptics(it) } }
                SwitchRow("Large captions", "Bigger caption text in LIVE and Eye mode", settings.captionSize == "large") { scope.launch { c.settings.setCaptionSize(if (it) "large" else "normal") } }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        Text("Keep listening", style = MaterialTheme.typography.titleSmall)
                        Text("How long ZED waits for you to speak again after it answers", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                    }
                    listOf("short" to "Short", "normal" to "Normal", "long" to "Long").forEach { (id, label) ->
                        androidx.compose.material3.FilterChip(selected = settings.listenPatience == id, onClick = { scope.launch { c.settings.setListenPatience(id) } }, label = { Text(label) }, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                val voiceLabel = when (val sel = com.melhive.zed.core.voice.VoiceSelection.parse(settings.voiceId)) {
                    com.melhive.zed.core.voice.VoiceSelection.SystemDefault -> "Phone default voice"
                    is com.melhive.zed.core.voice.VoiceSelection.System -> "Phone voice"
                    is com.melhive.zed.core.voice.VoiceSelection.Neural -> "${sel.voice.name} (${sel.pack.name})"
                }
                ZedCard(onClick = { onNavigate(Routes.VOICE) }, accent = ZedColors.Violet, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    IconLabel(Icons.Outlined.RecordVoiceOver, "Voice: $voiceLabel", "Speed ${"%.2f".format(settings.speechRate)}× · pitch ${"%.2f".format(settings.speechPitch)}× · tap to choose natural offline voices")
                }
            }
        }
        item {
            ZedCard {
                SectionTitle("Safety")
                Text("Ask before actions", style = MaterialTheme.typography.titleSmall)
                val mode = com.melhive.zed.core.security.ConfirmMode.parse(settings.confirmMode)
                WrapRow {
                    com.melhive.zed.core.security.ConfirmMode.entries.forEach { m ->
                        androidx.compose.material3.FilterChip(selected = m == mode, onClick = { scope.launch { c.settings.setConfirmMode(m.name) } }, label = { Text(m.label) })
                    }
                }
                Text(mode.description, style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
            }
        }
        item {
            val ctx = LocalContext.current
            ZedCard(accent = ZedColors.Teal) {
                SectionTitle("Intelligence")
                SwitchRow("Learn from conversations", "ZED picks up lasting facts you mention (people, preferences, allergies, work). You review each one on Home; nothing leaves the phone.", settings.autoLearning) {
                    scope.launch { c.settings.setAutoLearning(it); com.melhive.zed.learning.Learner.schedule(ctx.applicationContext, it) }
                }
                SwitchRow("Search the web", "When a question needs fresh facts, ZED searches the web (no account or key) and answers with sources. Only the search question leaves the phone.", settings.webResearch) { scope.launch { c.settings.setWebResearch(it) } }
                SwitchRow("Proactive hints", "Rare, useful nudges: time to leave for an event with a place, or a pattern ZED noticed. At most one every three hours.", settings.proactiveHints) {
                    scope.launch { c.settings.setProactiveHints(it); com.melhive.zed.learning.Dreamer.scheduleHints(ctx.applicationContext, it) }
                }
                SwitchRow("Think twice on hard questions", "Plans, answers, then checks itself on planning, comparing and multi-step questions. Slower, more careful. Everyday questions are never slowed down.", settings.thinkTwice) { scope.launch { c.settings.setThinkTwice(it) } }
                Text("Animation", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                WrapRow {
                    listOf("full" to "Full", "balanced" to "Balanced", "light" to "Light").forEach { (id, label) ->
                        androidx.compose.material3.FilterChip(selected = settings.animationLevel == id, onClick = { scope.launch { c.settings.setAnimationLevel(id) } }, label = { Text(label) })
                    }
                }
                Text(when (settings.animationLevel) {
                    "light" -> "Light: no glow or moving signals, mini brains still. Easiest on the battery and on the model."
                    "balanced" -> "Balanced: glow stays, the brain's travelling signals stop and the mini brains run slower."
                    else -> "Full: every animation, glow and signal."
                }, style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
            }
        }
        item {
            ZedCard {
                SectionTitle("Knowledge")
                SwitchRow("Use local documents as context", "Include matching excerpts from indexed documents in AI prompts", settings.useKnowledge) { scope.launch { c.settings.setUseKnowledge(it) } }
            }
        }
        item {
            ZedCard {
                SectionTitle("Appearance")
                SwitchRow("Glow effects", "Orb and card glow (turn off to save battery)", settings.glow) { scope.launch { c.settings.setGlow(it) } }
                SwitchRow("Live Brain sounds", "Soft synapse ticks and a light vibration when you touch the brain", settings.brainSounds) { scope.launch { c.settings.setBrainSounds(it) } }
            }
        }
        item {
            val update by c.updates.state.collectAsStateWithLifecycle()
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            ZedCard {
                SectionTitle("App updates")
                Text("Installed: ZED ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
                Text(
                    when (val u = update) {
                        com.melhive.zed.android.UpdateState.Unknown -> "Not checked yet"
                        com.melhive.zed.android.UpdateState.Checking -> "Checking GitHub…"
                        com.melhive.zed.android.UpdateState.UpToDate -> "You have the latest version"
                        is com.melhive.zed.android.UpdateState.Available -> "Version ${u.info.versionName} is available"
                        is com.melhive.zed.android.UpdateState.Failed -> "Check failed: ${u.message}"
                    },
                    style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted,
                )
                SwitchRow("Check for updates", "Ask GitHub Releases for a newer version when ZED starts", settings.checkUpdates) { scope.launch { c.settings.setCheckUpdates(it) } }
                WrapRow {
                    TextButton(onClick = { scope.launch { c.updates.check() } }) { Text("Check now") }
                    (update as? com.melhive.zed.android.UpdateState.Available)?.let { u ->
                        TextButton(onClick = { uriHandler.openUri(u.info.apkUrl) }) { Text("Download update") }
                    }
                    val ctx = LocalContext.current
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "Install ZED on Android: ${com.melhive.zed.android.UpdateChecker.LATEST_APK}")
                        ctx.startActivity(Intent.createChooser(send, "Share ZED"))
                    }) { Text("Share app") }
                }
            }
        }
        item {
            ZedCard(onClick = { onNavigate(Routes.MODELS) }) { IconLabel(Icons.Outlined.Psychology, "Models & providers", "Configure intelligence layers in Model Manager") }
        }
        item {
            ZedCard(onClick = { onNavigate(Routes.BACKUP) }) { IconLabel(Icons.Outlined.SettingsBackupRestore, "Backup & restore", "Save your data, models, voices and settings to one file") }
        }
        item { ZedFooter(onNavigate) }
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
            val crash = remember(refresh) { com.melhive.zed.android.CrashReporter.lastCrash() }
            val errors = remember(refresh) { com.melhive.zed.android.CrashReporter.errors() }
            if (crash != null || errors != null) {
                ZedCard(accent = ZedColors.Red) {
                    SectionTitle("Problem reports")
                    Text(if (crash != null) "ZED crashed last time. Share the report so it can be fixed." else "Some background errors were recorded.", style = MaterialTheme.typography.bodySmall)
                    val report = listOfNotNull(crash, errors?.let { "Recent errors:\n$it" }).joinToString("\n\n")
                    Text(report.take(1500), style = MonoStyle, color = ZedColors.Muted)
                    WrapRow {
                        TextButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "ZED problem report").putExtra(Intent.EXTRA_TEXT, report)
                            context.startActivity(Intent.createChooser(send, "Share report"))
                        }) { Text("Share report") }
                        TextButton(onClick = { com.melhive.zed.android.CrashReporter.clear(); refresh++ }) { Text("Clear") }
                    }
                }
            }
        }
        item { SpeedTestCard() }
        item { BackgroundJobsCard() }
        item { SpeedCard() }
        item { SelfTestCard() }
        item { SectionTitle("Permissions") }
        items(PermissionCatalog.ALL.filter { PermissionCatalog.applicable(it) }) { p ->
            val granted = remember(refresh) { PermissionCatalog.isGranted(context, p) }
            ZedCard(accent = if (granted) ZedColors.Green else ZedColors.Amber) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    StatusPill(if (granted) "Granted" else "Not granted", if (granted) ZedColors.Green else ZedColors.Amber)
                }
                Text(p.reason, style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                if (!granted) WrapRow {
                    Button(onClick = { launcher.launch(p.permission) }) { Text("Request") }
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("App settings") }
                }
            }
        }
        item {
            val listener = remember(refresh) { com.melhive.zed.android.ZedNotificationListener.hasAccess(context) }
            ZedCard(accent = if (listener) ZedColors.Green else ZedColors.Muted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("What did I miss (notification access)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    StatusPill(if (listener) "Allowed" else "Optional", if (listener) ZedColors.Green else ZedColors.Muted)
                }
                Text("Lets ZED read your recent notifications when you ask “what did I miss?”. Nothing is read unless you ask, and nothing is saved.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                if (!listener) TextButton(onClick = { com.melhive.zed.android.ZedNotificationListener.openSettings(context) }) { Text("Allow notification access") }
            }
        }
        item {
            val dnd = remember(refresh) { com.melhive.zed.android.PhoneControl(context).hasDndAccess() }
            ZedCard(accent = if (dnd) ZedColors.Green else ZedColors.Muted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Do Not Disturb control", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    StatusPill(if (dnd) "Allowed" else "Optional", if (dnd) ZedColors.Green else ZedColors.Muted)
                }
                Text("Lets ZED turn Do Not Disturb on or off for you and in routines like Good night.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                if (!dnd) TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) } }) { Text("Allow Do Not Disturb access") }
            }
        }
        item {
            val exact = remember(refresh) { c.reminderScheduler.canScheduleExact() }
            ZedCard(accent = if (exact) ZedColors.Green else ZedColors.Muted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Alarms & reminders (exact timing)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    StatusPill(if (exact) "Allowed" else "Optional", if (exact) ZedColors.Green else ZedColors.Muted)
                }
                Text("Optional. Without it, reminders still fire but Android may delay them a few minutes.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                if (!exact && Build.VERSION.SDK_INT >= 31) TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                }) { Text("Allow in system settings") }
            }
        }
        item {
            ZedCard {
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
                    "Voice packs" to (c.voicePacks.installed.value.joinToString().ifEmpty { "none installed" } + " · ${DeviceInfo.formatBytes(c.voicePacks.usedBytes())}"),
                    "Document index" to "$chunkCount chunks",
                    "Network" to if (device.online) (if (device.unmetered) "online (unmetered)" else "online (metered)") else "offline",
                )
                rows.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            ZedCard {
                SectionTitle("Device")
                Text("${device.model} · Android ${device.androidVersion} (API ${device.sdk}) · ${device.abis.joinToString()}", style = MaterialTheme.typography.bodySmall)
                Text("RAM ${DeviceInfo.formatBytes(device.totalRamBytes)} total, ${DeviceInfo.formatBytes(device.availRamBytes)} available${if (device.lowMemory) " (LOW)" else ""}", style = MaterialTheme.typography.bodySmall)
                Text("Free storage ${DeviceInfo.formatBytes(device.freeStorageBytes)} · Models use ${DeviceInfo.formatBytes(c.models.usedBytes())}", style = MaterialTheme.typography.bodySmall)
                Text("App ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
            }
        }
        item {
            SectionTitle("Audit log (${audit.size})") { TextButton(onClick = { showAudit = !showAudit }) { Text(if (showAudit) "Hide" else "Show") } }
        }
        if (showAudit) items(audit, key = { it.id }) { a ->
            Text("${formatTime(a.timestamp)} ${a.outcome.uppercase()} ${a.tool} [${a.origin.lowercase()}] ${a.argsSummary} → ${a.detail}", style = MonoStyle,
                color = when (a.outcome) { "executed" -> ZedColors.Text; "failed", "invalid" -> ZedColors.Red; else -> ZedColors.Amber })
        }
    }
}

/** What ZED has scheduled in the background, and when each job next runs (to check it isn't busy all day). */
@Composable
private fun BackgroundJobsCard() {
    val context = LocalContext.current
    val jobs by produceState<List<Pair<String, String>>>(emptyList()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val wm = androidx.work.WorkManager.getInstance(context)
            val stamp = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault())
            listOf(
                "zed-learning" to "Learning (while charging)", "zed-hints-planner" to "Hint planner (every 3 h)", "zed-hints-at" to "Leave-now check",
                "zed-pending-requests" to "Offline requests", "zed-auto-backup" to "Weekly backup", com.melhive.zed.agents.BriefingWorker.NAME to "Morning briefing",
            ).mapNotNull { (name, label) ->
                val info = runCatching { wm.getWorkInfosForUniqueWork(name).get() }.getOrNull()?.firstOrNull { !it.state.isFinished } ?: return@mapNotNull null
                val next = info.nextScheduleTimeMillis.takeIf { it in 1 until Long.MAX_VALUE }?.let { " · next ${stamp.format(java.util.Date(it))}" }.orEmpty()
                label to (info.state.name.lowercase().replaceFirstChar { it.uppercase() } + next)
            }
        }
    }
    ZedCard {
        SectionTitle("Background jobs")
        if (jobs.isEmpty()) Text("Nothing scheduled right now.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
        jobs.forEach { (label, state) -> Text("$label — $state", style = MonoStyle, color = ZedColors.Muted) }
        Text("Learning and dreaming use the AI model only while charging (or when it's already loaded); hints wake the phone only before an event that has a place.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Diagnostics → Speed test: how long the model takes to start answering, and how fast it writes. */
@Composable
private fun SpeedTestCard() {
    val c = rememberContainer()
    val state by c.speedTest.state.collectAsStateWithLifecycle()
    val running = state as? com.melhive.zed.diagnostics.SpeedTest.State.Running
    val history = remember(state) { c.speedTest.history() }
    val baseline = remember(state) { c.speedTest.baseline() }
    val stamp = remember { java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()) }
    ZedCard(accent = ZedColors.Teal) {
        SectionTitle("Speed test")
        Text("Measures the AI model on this phone: the wait before the first word, and words per second after it. Your first result is kept as the baseline that later versions are compared with.",
            style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
        when (val s = state) {
            is com.melhive.zed.diagnostics.SpeedTest.State.Running -> {
                Text(s.label, style = MaterialTheme.typography.bodyMedium, color = ZedColors.Amber, modifier = Modifier.padding(top = 6.dp))
                com.melhive.zed.ui.components.RealProgress(null, null, Modifier.padding(top = 6.dp), ZedColors.Amber)
            }
            is com.melhive.zed.diagnostics.SpeedTest.State.Done -> {
                Text(s.result.summary(), style = MaterialTheme.typography.titleMedium, color = ZedColors.Teal, modifier = Modifier.padding(top = 6.dp))
                Text("${s.result.model} (${s.result.backend}) · ${s.result.words} words in ${"%.1f".format(s.result.totalMs / 1000f)} s" + (s.result.loadMs?.let { " · model loaded in ${"%.1f".format(it / 1000f)} s" } ?: ""),
                    style = MaterialTheme.typography.bodySmall)
                val base = baseline
                if (base != null && base.at != s.result.at) {
                    val firstDelta = (s.result.firstWordMs - base.firstWordMs) / 1000f
                    val speedDelta = if (base.wordsPerSecond > 0f) (s.result.wordsPerSecond / base.wordsPerSecond - 1f) * 100f else 0f
                    val slower = firstDelta > 0.5f || speedDelta < -10f
                    Text(
                        "Against the baseline (ZED ${base.version}): first word ${if (firstDelta >= 0) "+" else "−"}${"%.1f".format(kotlin.math.abs(firstDelta))} s, writing ${if (speedDelta >= 0) "+" else "−"}${"%.0f".format(kotlin.math.abs(speedDelta))}%",
                        style = MaterialTheme.typography.bodySmall, color = if (slower) ZedColors.Red else ZedColors.Green,
                    )
                } else if (base != null) Text("This is now the baseline.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Green)
                s.savedTo?.let { Text("Saved to $it", style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted) }
            }
            is com.melhive.zed.diagnostics.SpeedTest.State.Failed -> Text(s.message, style = MaterialTheme.typography.bodySmall, color = ZedColors.Red, modifier = Modifier.padding(top = 6.dp))
            com.melhive.zed.diagnostics.SpeedTest.State.Idle -> Unit
        }
        WrapRow {
            // Runs outside this screen's lifetime so leaving the page doesn't cut the measurement short.
            Button(enabled = running == null, onClick = { c.appScope.launch { c.speedTest.run() } }) { Text(if (history.isEmpty()) "Run speed test" else "Run again") }
        }
        if (history.isNotEmpty()) {
            Text("HISTORY", style = MaterialTheme.typography.labelSmall, color = ZedColors.Cyan, modifier = Modifier.padding(top = 4.dp))
            history.takeLast(5).reversed().forEach { r ->
                Text("${stamp.format(java.util.Date(r.at))} · ${r.version} · ${"%.1f".format(r.firstWordMs / 1000f)} s · ${"%.1f".format(r.wordsPerSecond)} w/s" + if (baseline?.at == r.at) " · baseline" else "", style = MonoStyle, color = ZedColors.Muted)
            }
        }
    }
}

@Composable
private fun PersonalityCard(persona: String, title: String, userName: String?, onChange: (String, String) -> Unit) {
    val current = com.melhive.zed.core.intelligence.Persona.parse(persona)
    var customTitle by remember(title) { mutableStateOf(title) }
    ZedCard(accent = ZedColors.Violet) {
        SectionTitle("Personality")
        Text("How ZED talks to you. Applies to AI replies in Chat and LIVE.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
        WrapRow(Modifier.padding(top = 6.dp)) {
            com.melhive.zed.core.intelligence.Persona.entries.forEach { p ->
                androidx.compose.material3.FilterChip(selected = p == current, onClick = { onChange(p.name, title) }, label = { Text(p.label) })
            }
        }
        Text(current.summary, style = MaterialTheme.typography.bodySmall, color = ZedColors.Cyan)
        Text("Call me", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
        WrapRow {
            val options = listOfNotNull("" to (userName ?: "My name"), "sir" to "Sir", "ma'am" to "Ma'am", "boss" to "Boss")
            options.forEach { (value, label) ->
                androidx.compose.material3.FilterChip(selected = title.equals(value, ignoreCase = true), onClick = { customTitle = value; onChange(current.name, value) }, label = { Text(label) })
            }
        }
        androidx.compose.material3.OutlinedTextField(
            value = customTitle, onValueChange = { customTitle = it.take(40) }, singleLine = true,
            label = { Text("Or type a title or nickname") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            trailingIcon = { if (customTitle != title) TextButton(onClick = { onChange(current.name, customTitle) }) { Text("Save") } },
        )
    }
}

@Composable
private fun AssistantShortcutsCard() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    val isDefault = remember(refresh) { com.melhive.zed.assist.ZedLaunch.isDefaultAssistant(context) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    ZedCard(accent = if (isDefault) ZedColors.Green else ZedColors.Cyan) {
        SectionTitle("Open ZED from anywhere")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Default assistant", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            StatusPill(if (isDefault) "ZED" else "Not set", if (isDefault) ZedColors.Green else ZedColors.Amber)
        }
        Text(
            if (isDefault) "Hold the power or home button (or swipe up from a bottom corner) to talk to ZED."
            else "Make ZED your phone's assistant so holding the power or home button opens ZED LIVE. In the screen that opens, choose Digital assistant app → ZED.",
            style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted,
        )
        WrapRow {
            if (!isDefault) Button(onClick = { com.melhive.zed.assist.ZedLaunch.openAssistantSettings(context) }) { Text("Set as assistant") }
            TextButton(onClick = { com.melhive.zed.assist.ZedLaunch.requestAddTile(context) { note = it } }) { Text("Add Quick Settings tile") }
        }
        Text("Widget: long-press your home screen → Widgets → ZED.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ZedColors.Cyan) }
    }
}

@Composable
private fun SmartHomeCard(savedUrl: String) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val configured by c.secrets.configured.collectAsStateWithLifecycle()
    val hasToken = com.melhive.zed.security.SecretStore.HOME_ASSISTANT_TOKEN in configured
    var url by remember(savedUrl) { mutableStateOf(savedUrl) }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    ZedCard(accent = if (savedUrl.isNotBlank() && hasToken) ZedColors.Green else ZedColors.Muted) {
        SectionTitle("Smart home (Home Assistant)")
        Text("Control lights, switches, climate, locks and covers by voice. In Home Assistant open your profile → Security → Long-lived access tokens → Create token.",
            style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
        androidx.compose.material3.OutlinedTextField(value = url, onValueChange = { url = it.trim() }, singleLine = true,
            label = { Text("Address, e.g. http://homeassistant.local:8123") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        androidx.compose.material3.OutlinedTextField(value = token, onValueChange = { token = it.trim() }, singleLine = true,
            label = { Text(if (hasToken) "Token saved — paste to replace" else "Long-lived access token") },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        if (url.startsWith("http://") && !Regex("^http://(localhost|127\\.|10\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\.|[^/]*\\.local\\b)").containsMatchIn(url))
            Text("This address isn't on your home network and doesn't use https:// — your token would travel unencrypted.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Amber)
        WrapRow {
            Button(onClick = {
                scope.launch {
                    c.settings.setHomeAssistantUrl(url)
                    if (token.isNotBlank()) { c.secrets.put(com.melhive.zed.security.SecretStore.HOME_ASSISTANT_TOKEN, token); token = "" }
                    status = "Testing…"
                    status = runCatching { c.homeAssistant()?.ping() ?: "Add the address and token first." }.fold({ "Connected: $it" }, { "Couldn't connect: ${it.message}" })
                }
            }) { Text("Save & test") }
            if (hasToken) TextButton(onClick = { c.secrets.remove(com.melhive.zed.security.SecretStore.HOME_ASSISTANT_TOKEN); status = "Disconnected." }) { Text("Disconnect") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ZedColors.Cyan) }
    }
}

/** One tap to grant every permission ZED can use, so nothing interrupts a command later. */
@Composable
fun AllowEverythingCard(onHide: (() -> Unit)? = null) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val missing = remember(refresh) { PermissionCatalog.missing(context) }
    val notif = remember(refresh) { com.melhive.zed.android.ZedNotificationListener.hasAccess(context) }
    val dnd = remember(refresh) { com.melhive.zed.android.PhoneControl(context).hasDndAccess() }
    val overlay = remember(refresh) { Settings.canDrawOverlays(context) }
    // Nothing left to allow: the card disappears by itself.
    if (missing.isEmpty() && notif && dnd && overlay) return
    ZedCard(accent = if (missing.isEmpty()) ZedColors.Green else ZedColors.Amber) {
        SectionTitle("Allow everything") { if (onHide != null) TextButton(onClick = onHide) { Text("Hide") } }
        Text("Grant ZED's permissions once so commands run instantly — no pop-ups when you ask for something.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
        if (missing.isNotEmpty()) Button(onClick = { launcher.launch(missing) }, modifier = Modifier.padding(top = 6.dp)) { Text("Allow all permissions (${missing.size})") }
        else Text("✓ All permissions allowed", style = MaterialTheme.typography.bodySmall, color = ZedColors.Green)
        WrapRow {
            if (!notif) TextButton(onClick = { com.melhive.zed.android.ZedNotificationListener.openSettings(context) }) { Text("Notification access") }
            if (!dnd) TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) } }) { Text("Do Not Disturb access") }
            if (!overlay) TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))) } }) { Text("Show over other apps") }
        }
    }
}

/** How long each step takes on this phone, measured on real use. */
@Composable
private fun SpeedCard() {
    var tick by remember { mutableIntStateOf(0) }
    val rows = remember(tick) { com.melhive.zed.diagnostics.SpeedLog.summary() }
    fun fmt(ms: Long) = if (ms < 1000) "$ms ms" else "%.1f s".format(ms / 1000.0)
    ZedCard {
        SectionTitle("Speed")
        Text("Measured on this phone as you use ZED: the middle of the last 30 times for each step, with the best and the latest.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
        if (rows.isEmpty()) Text("Nothing measured yet. Talk to ZED a few times and come back.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted, modifier = Modifier.padding(top = 8.dp))
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.stage.label, style = MaterialTheme.typography.titleSmall)
                    Text(r.stage.detail, style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(fmt(r.median), style = MaterialTheme.typography.titleSmall, color = ZedColors.Cyan)
                    Text("best ${fmt(r.best)} · last ${fmt(r.last)} · ${r.count}×", style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted)
                }
            }
        }
        if (rows.isNotEmpty()) TextButton(onClick = { com.melhive.zed.diagnostics.SpeedLog.clear(); tick++ }) { Text("Reset measurements") }
    }
}

/** Checks on this phone that everything ZED does works (safely — test items are removed at once). */
@Composable
private fun SelfTestCard() {
    val c = rememberContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val results = remember { androidx.compose.runtime.mutableStateListOf<com.melhive.zed.diagnostics.SelfTest.Check>() }
    var running by remember { mutableStateOf(false) }
    ZedCard {
        SectionTitle("Self-test")
        Text("Checks that each thing ZED does works on this phone: reminders, calendar, clock, contacts, calls, texts, message replies, the floating orb, speech, “Hey Zed”, the voice, the AI model, files and screen control. It adds and removes a test task, reminder, event and file; nothing is sent or called.",
            style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
        Button(enabled = !running, onClick = {
            results.clear(); running = true
            scope.launch {
                runCatching { com.melhive.zed.diagnostics.SelfTest(context, c).run { check -> results += check } }
                running = false
            }
        }, modifier = Modifier.padding(top = 8.dp)) { Text(if (running) "Testing…" else if (results.isEmpty()) "Run self-test" else "Run again") }
        if (results.isNotEmpty()) {
            val fails = results.count { it.status == com.melhive.zed.diagnostics.SelfTest.Status.FAIL }
            val warns = results.count { it.status == com.melhive.zed.diagnostics.SelfTest.Status.WARN }
            if (!running) Text(if (fails == 0 && warns == 0) "Everything works." else "$fails problem${if (fails == 1) "" else "s"}, $warns to improve.",
                style = MaterialTheme.typography.titleSmall, color = if (fails > 0) ZedColors.Red else if (warns > 0) ZedColors.Amber else ZedColors.Green, modifier = Modifier.padding(top = 8.dp))
        }
        results.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                Text(when (r.status) { com.melhive.zed.diagnostics.SelfTest.Status.PASS -> "✓"; com.melhive.zed.diagnostics.SelfTest.Status.WARN -> "!"; else -> "✕" },
                    color = when (r.status) { com.melhive.zed.diagnostics.SelfTest.Status.PASS -> ZedColors.Green; com.melhive.zed.diagnostics.SelfTest.Status.WARN -> ZedColors.Amber; else -> ZedColors.Red },
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(end = 10.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MaterialTheme.typography.titleSmall)
                    Text(r.detail, style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                }
                Text("${r.ms} ms", style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted)
            }
        }
        if (!com.melhive.zed.assist.ZedAccessibilityService.isEnabled(context))
            TextButton(onClick = { com.melhive.zed.assist.ZedAccessibilityService.openSettings(context) }) { Text("Turn on screen control") }
    }
}

