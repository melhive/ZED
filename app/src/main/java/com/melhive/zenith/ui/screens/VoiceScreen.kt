package com.melhive.zenith.ui.screens

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.zenith.android.DeviceInfo
import com.melhive.zenith.core.voice.NeuralVoice
import com.melhive.zenith.core.voice.VoiceCatalog
import com.melhive.zenith.core.voice.VoiceGender
import com.melhive.zenith.core.voice.VoicePack
import com.melhive.zenith.core.voice.VoiceSelection
import com.melhive.zenith.tasks.TaskTypes
import com.melhive.zenith.tasks.input
import com.melhive.zenith.tasks.statusEnum
import com.melhive.zenith.ui.components.RealProgress
import com.melhive.zenith.ui.components.SectionTitle
import com.melhive.zenith.ui.components.StatusPill
import com.melhive.zenith.ui.components.WrapRow
import com.melhive.zenith.ui.components.ZenithCard
import com.melhive.zenith.ui.navigation.rememberContainer
import com.melhive.zenith.ui.theme.ZenithColors
import com.melhive.zenith.voice.SystemVoice
import com.melhive.zenith.voice.VoiceManager
import kotlinx.coroutines.launch

/** Choose how ZENITH sounds: offline neural voices, phone voices, speed and pitch. */
@Composable
fun VoiceScreen(onBack: () -> Unit, onOpenTasks: () -> Unit) {
    val c = rememberContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val status by c.voice.status.collectAsStateWithLifecycle()
    val installed by c.voicePacks.installed.collectAsStateWithLifecycle()
    val tasks by c.taskEngine.tasks.collectAsStateWithLifecycle(initialValue = emptyList())
    val name by produceState<String?>(null) { value = c.memory.userName() }
    var systemVoices by remember { mutableStateOf<List<SystemVoice>?>(null) }
    var showAllSystem by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { systemVoices = runCatching { c.voice.systemVoices() }.getOrDefault(emptyList()) }
    val settings = s ?: return
    val selection = VoiceSelection.parse(settings.voiceId)

    // Every action is guarded: a failure shows a message and is recorded, never closes the app.
    fun safely(block: suspend () -> Unit) = scope.launch(start = kotlinx.coroutines.CoroutineStart.DEFAULT) {
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
            com.melhive.zenith.android.CrashReporter.recordNonFatal("voice screen", e)
            message = "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
        }
    }
    val sample = "Hello${name?.let { ", $it" } ?: ""}. I'm ZENITH. It's 7:30, and you have 3 things on your list today."
    fun choose(id: String) = safely {
        c.settings.setVoiceId(id)
        message = "Preparing the voice… the first time takes a few seconds."
        message = if (c.voice.preview(sample)) null else "That voice could not be loaded. The phone voice is used instead."
    }
    fun previewNow() = safely {
        message = "Preparing the voice…"
        message = if (c.voice.preview(sample)) null else "Speech is unavailable on this phone."
    }

    ListScreen("Voice", onBack = onBack, subtitle = "Choose how ZENITH sounds. Every voice runs on your phone, offline.") {
        item {
            ZenithCard {
                SectionTitle("Now speaking with")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.RecordVoiceOver, null, tint = ZenithColors.Cyan)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(selectionLabel(selection), style = MaterialTheme.typography.titleMedium)
                        (status.detail ?: "Tap Preview to hear it").let { Text(it, style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted) }
                    }
                    when (status.phase) {
                        VoiceManager.Phase.LOADING -> StatusPill("Loading", ZenithColors.Amber)
                        VoiceManager.Phase.READY -> StatusPill("Ready", ZenithColors.Green)
                        VoiceManager.Phase.FALLBACK -> StatusPill("Fallback", ZenithColors.Amber)
                        VoiceManager.Phase.IDLE -> Unit
                    }
                }
                WrapRow(Modifier.padding(top = 8.dp)) {
                    Button(onClick = { previewNow() }) { Text("Preview") }
                    TextButton(onClick = { c.voice.stop() }) { Text("Stop") }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ZenithColors.Amber) }
            }
        }

        item {
            val configured by c.secrets.configured.collectAsStateWithLifecycle()
            RealtimeVoiceCard(
                enabled = settings.liveEngine == com.melhive.zenith.voice.LiveSessionController.LIVE_ENGINE_REALTIME,
                hasKey = com.melhive.zenith.security.SecretStore.apiKeyName(com.melhive.zenith.settings.OnlineProviderKind.GEMINI) in configured,
                voice = settings.liveVoice, model = settings.liveModel,
                onEnabled = { on -> safely { c.settings.setLiveEngine(if (on) com.melhive.zenith.voice.LiveSessionController.LIVE_ENGINE_REALTIME else com.melhive.zenith.voice.LiveSessionController.LIVE_ENGINE_STANDARD) } },
                onVoice = { v -> safely { c.settings.setLiveVoice(v) } },
                onModel = { m -> safely { c.settings.setLiveModel(m) } },
                listModels = {
                    val key = c.secrets.get(com.melhive.zenith.security.SecretStore.apiKeyName(com.melhive.zenith.settings.OnlineProviderKind.GEMINI))
                    com.melhive.zenith.intelligence.providers.GeminiProvider("", { key }).listLiveModels()
                },
            )
        }
        item {
            val wakeInstalled = com.melhive.zenith.core.voice.VoiceCatalog.WAKE_WORD.id in installed
            LaunchedEffect(wakeInstalled, settings.wakeWordEnabled) {
                if (wakeInstalled && settings.wakeWordEnabled) com.melhive.zenith.voice.WakeWordService.start(context)
            }
            val wakeTask = tasks.firstOrNull { it.type == TaskTypes.VOICE_DOWNLOAD && it.input()["pack"] == com.melhive.zenith.core.voice.VoiceCatalog.WAKE_WORD.id && !it.statusEnum().terminal }
            ZenithCard(accent = if (settings.wakeWordEnabled && wakeInstalled) ZenithColors.Green else ZenithColors.Violet) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("“Hey Zenith”", style = MaterialTheme.typography.titleMedium)
                        Text("Say “Hey Zenith” any time to start talking. Listening happens entirely on the phone — only for the wake word — while a notification is shown. Uses some battery.",
                            style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
                    }
                    androidx.compose.material3.Switch(checked = settings.wakeWordEnabled, onCheckedChange = { on ->
                        safely {
                            c.settings.setWakeWordEnabled(on)
                            if (!on) { com.melhive.zenith.voice.WakeWordService.stop(context); return@safely }
                            if (!wakeInstalled && wakeTask == null) {
                                c.taskEngine.create(TaskTypes.VOICE_DOWNLOAD, "Download wake word model", mapOf("pack" to com.melhive.zenith.core.voice.VoiceCatalog.WAKE_WORD.id))
                                message = "Downloading the wake word model (18 MB)… “Hey Zenith” starts when it's ready."
                            } else com.melhive.zenith.voice.WakeWordService.start(context)
                        }
                    })
                }
                if (wakeTask != null) RealProgress(wakeTask.progressCurrent, wakeTask.progressTotal, Modifier.padding(top = 6.dp))
                if (settings.wakeWordEnabled) {
                    Text("Sensitivity", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                    WrapRow {
                        listOf(0 to "Fewer false alarms", 1 to "Normal", 2 to "Hears it easily").forEach { (v, l) ->
                            FilterChip(selected = settings.wakeSensitivity == v, onClick = { safely {
                                c.settings.setWakeSensitivity(v)
                                com.melhive.zenith.voice.WakeWordService.stop(context); com.melhive.zenith.voice.WakeWordService.start(context)
                            } }, label = { Text(l) })
                        }
                    }
                    if (!android.provider.Settings.canDrawOverlays(context)) {
                        Text("To open LIVE when another app is on screen, allow ZENITH to display over other apps. Without it, ZENITH answers hands-free and shows the reply as a notification.",
                            style = MaterialTheme.typography.bodySmall, color = ZenithColors.Amber, modifier = Modifier.padding(top = 4.dp))
                        TextButton(onClick = {
                            runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + context.packageName))) }
                        }) { Text("Allow display over other apps") }
                    }
                }
            }
        }
        item {
            ZenithCard {
                SectionTitle("LIVE sounds")
                Text("The sound when ZENITH LIVE opens and closes. Tap one to hear it.", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
                WrapRow(Modifier.padding(top = 6.dp)) {
                    com.melhive.zenith.voice.ZenithSounds.Style.entries.forEach { st ->
                        FilterChip(selected = settings.liveSound == st.id, onClick = {
                            safely { c.settings.setLiveSound(st.id) }
                            val ms = com.melhive.zenith.voice.ZenithSounds.play(context, st, start = true)
                            if (ms > 0) scope.launch { kotlinx.coroutines.delay(ms + 450); com.melhive.zenith.voice.ZenithSounds.play(context, st, start = false) }
                        }, label = { Text(st.label) })
                    }
                }
                Text(com.melhive.zenith.voice.ZenithSounds.Style.parse(settings.liveSound).description, style = MaterialTheme.typography.labelSmall, color = ZenithColors.Cyan)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Hide the recognizer beep", style = MaterialTheme.typography.titleSmall)
                        Text("Silences the speech service's own “listening” sound (the Google beep) each time LIVE starts listening.", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
                    }
                    androidx.compose.material3.Switch(checked = settings.hideRecognizerBeep, onCheckedChange = { on -> safely { c.settings.setHideRecognizerBeep(on) } })
                }
            }
        }
        item { SpeedAndPitch(settings.speechRate, settings.speechPitch,
            onRate = { v -> safely { c.settings.setSpeechRate(v); c.voice.preview("This is how fast I'll talk.") } },
            onPitch = { v -> safely { c.settings.setSpeechPitch(v); c.voice.preview("This is how my voice will sound.") } },
        ) }

        item { SectionTitle("Natural offline voices") }
        items(VoiceCatalog.packs, key = { it.id }) { pack ->
            val task = tasks.firstOrNull { it.type == TaskTypes.VOICE_DOWNLOAD && it.input()["pack"] == pack.id && !it.statusEnum().terminal }
            VoicePackCard(
                pack = pack,
                installed = pack.id in installed,
                downloading = task != null,
                progress = task?.let { Triple(it.progressCurrent, it.progressTotal, it.progressLabel) },
                selectedId = (selection as? VoiceSelection.Neural)?.voice?.id,
                onDownload = {
                    safely {
                        val free = DeviceInfo.snapshot(context).freeStorageBytes
                        val need = c.voicePacks.requiredBytes(pack)
                        if (free < need) { message = "Not enough storage: ${DeviceInfo.formatBytes(need)} needed, ${DeviceInfo.formatBytes(free)} free."; return@safely }
                        val t = c.taskEngine.create(TaskTypes.VOICE_DOWNLOAD, "Download ${pack.name} voices", mapOf("pack" to pack.id))
                        message = "Downloading ${pack.name} as task #${t.id}. You can keep using ZENITH."
                    }
                },
                onCancel = { task?.let { t -> safely { c.taskEngine.cancel(t.id) } } },
                onOpenTasks = onOpenTasks,
                onRemove = {
                    safely {
                        if ((selection as? VoiceSelection.Neural)?.pack?.id == pack.id) { c.voice.unload(); c.settings.setVoiceId(VoiceSelection.SYSTEM_DEFAULT) }
                        c.voicePacks.remove(pack)
                        message = "Removed ${pack.name} voices"
                    }
                },
                onSelect = { v -> choose(v.id) },
            )
        }

        item { SectionTitle("Phone voices") }
        item {
            ZenithCard {
                Text("Voices from your phone's text-to-speech engine. Quality depends on the phone; Google's high-quality offline voices sound best.",
                    style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
                VoiceRow("Phone default", "Your phone's default voice", selection is VoiceSelection.SystemDefault, featured = false) { choose(VoiceSelection.SYSTEM_DEFAULT) }
                val list = systemVoices
                when {
                    list == null -> Text("Loading phone voices…", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
                    list.isEmpty() -> Text("No phone voices found.", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
                    else -> {
                        val shown = if (showAllSystem) list else list.filter { !it.needsNetwork }.take(6).ifEmpty { list.take(6) }
                        shown.forEach { v ->
                            VoiceRow(
                                title = v.displayLanguage,
                                subtitle = listOfNotNull(v.name, if (v.highQuality) "high quality" else null, if (v.needsNetwork) "needs internet" else "offline").joinToString(" · "),
                                selected = (selection as? VoiceSelection.System)?.voiceName == v.name,
                                featured = false,
                            ) { choose(VoiceSelection.system(v.name)) }
                        }
                        if (list.size > shown.size || showAllSystem) TextButton(onClick = { showAllSystem = !showAllSystem }) { Text(if (showAllSystem) "Show fewer" else "Show all ${list.size} voices") }
                    }
                }
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                }) { Text("Get more phone voices") }
            }
        }
        item {
            Text("Neural voices are synthesized on this phone with sherpa-onnx; no text is sent online. They need an arm64 phone and 100–300 MB of free memory while speaking.",
                style = MaterialTheme.typography.labelSmall, color = ZenithColors.Muted, modifier = Modifier.padding(4.dp))
        }
    }
}

private fun selectionLabel(s: VoiceSelection): String = when (s) {
    VoiceSelection.SystemDefault -> "Phone default voice"
    is VoiceSelection.System -> "Phone voice · ${s.voiceName}"
    is VoiceSelection.Neural -> "${s.voice.name} · ${s.pack.name} (${s.voice.gender.label}, ${s.voice.accent})"
}

private data class Preset(val label: String, val value: Float)

@Composable
private fun SpeedAndPitch(rate: Float, pitch: Float, onRate: (Float) -> Unit, onPitch: (Float) -> Unit) {
    var r by remember(rate) { mutableFloatStateOf(rate) }
    var p by remember(pitch) { mutableFloatStateOf(pitch) }
    ZenithCard {
        SectionTitle("Speaking speed")
        Text("${"%.2f".format(r)}× ${describeRate(r)}", style = MaterialTheme.typography.titleSmall)
        Slider(value = r, onValueChange = { r = (it * 20).toInt() / 20f }, onValueChangeFinished = { onRate(r) }, valueRange = 0.5f..2.0f)
        WrapRow {
            listOf(Preset("Slow", 0.8f), Preset("Normal", 1.0f), Preset("Brisk", 1.2f), Preset("Fast", 1.5f)).forEach { pr ->
                FilterChip(selected = kotlin.math.abs(r - pr.value) < 0.01f, onClick = { r = pr.value; onRate(pr.value) }, label = { Text(pr.label) })
            }
        }
        SectionTitle("Voice pitch")
        Text("${"%.2f".format(p)}× ${if (p < 0.97f) "lower" else if (p > 1.03f) "higher" else "natural"}", style = MaterialTheme.typography.titleSmall)
        Slider(value = p, onValueChange = { p = (it * 20).toInt() / 20f }, onValueChangeFinished = { onPitch(p) }, valueRange = 0.7f..1.3f)
        WrapRow {
            listOf(Preset("Deeper", 0.85f), Preset("Natural", 1.0f), Preset("Brighter", 1.15f)).forEach { pr ->
                FilterChip(selected = kotlin.math.abs(p - pr.value) < 0.01f, onClick = { p = pr.value; onPitch(pr.value) }, label = { Text(pr.label) })
            }
        }
    }
}

private fun describeRate(r: Float) = when {
    r < 0.85f -> "slow"
    r < 0.97f -> "a little slower"
    r <= 1.03f -> "normal"
    r < 1.3f -> "a little faster"
    else -> "fast"
}

@Composable
private fun VoicePackCard(
    pack: VoicePack,
    installed: Boolean,
    downloading: Boolean,
    progress: Triple<Long?, Long?, String?>?,
    selectedId: String?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onOpenTasks: () -> Unit,
    onRemove: () -> Unit,
    onSelect: (NeuralVoice) -> Unit,
) {
    var gender by remember { mutableStateOf<VoiceGender?>(null) }
    var accent by remember { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(false) }
    ZenithCard(accent = if (installed) ZenithColors.Green else ZenithColors.Violet) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pack.name, style = MaterialTheme.typography.titleMedium)
                Text(pack.tagline, style = MaterialTheme.typography.labelMedium, color = ZenithColors.Cyan)
            }
            if (installed) StatusPill("Installed", ZenithColors.Green)
        }
        Text(pack.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        Text("${pack.voices.size} voices · ${DeviceInfo.formatBytes(pack.sizeBytes)} download · ${pack.license}", style = MaterialTheme.typography.labelSmall, color = ZenithColors.Muted)
        when {
            downloading -> {
                RealProgress(progress?.first, progress?.second, Modifier.padding(top = 8.dp))
                val (done, total, label) = progress ?: Triple(null, null, null)
                Text(listOfNotNull(label, if (done != null && total != null) "${DeviceInfo.formatBytes(done)} / ${DeviceInfo.formatBytes(total)}" else null).joinToString(" · ").ifEmpty { "Queued" },
                    style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
                WrapRow { TextButton(onClick = onCancel) { Text("Cancel") }; TextButton(onClick = onOpenTasks) { Text("Open Tasks") } }
            }
            !installed -> WrapRow(Modifier.padding(top = 8.dp)) { Button(onClick = onDownload) { Text("Download ${DeviceInfo.formatBytes(pack.sizeBytes)}") } }
            else -> {
                val accents = pack.voices.map { it.accent }.distinct()
                WrapRow(Modifier.padding(top = 8.dp)) {
                    FilterChip(selected = gender == null && accent == null, onClick = { gender = null; accent = null }, label = { Text("All") })
                    VoiceGender.entries.forEach { g -> FilterChip(selected = gender == g, onClick = { gender = if (gender == g) null else g }, label = { Text(g.label) }) }
                    if (accents.size > 1) accents.forEach { a -> FilterChip(selected = accent == a, onClick = { accent = if (accent == a) null else a }, label = { Text(a) }) }
                }
                val filtered = pack.voices.filter { (gender == null || it.gender == gender) && (accent == null || it.accent == accent) }
                val shown = if (showAll || gender != null || accent != null) filtered else filtered.filter { it.featured || it.id == selectedId }
                shown.forEach { v ->
                    VoiceRow(v.name, "${v.gender.label} · ${v.accent} · ${v.description}", v.id == selectedId, v.featured) { onSelect(v) }
                }
                if (!showAll && gender == null && accent == null && shown.size < filtered.size)
                    TextButton(onClick = { showAll = true }) { Text("Show all ${pack.voices.size} voices") }
                WrapRow { OutlinedButton(onClick = onRemove) { Text("Remove") } }
            }
        }
    }
}

@Composable
private fun VoiceRow(title: String, subtitle: String, selected: Boolean, featured: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = if (selected) ZenithColors.Cyan else ZenithColors.Text)
                if (featured) Icon(Icons.Outlined.Star, "Recommended", tint = ZenithColors.Amber, modifier = Modifier.padding(start = 4.dp).size(16.dp))
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
        }
        if (selected) Icon(Icons.Outlined.CheckCircle, "Selected", tint = ZenithColors.Cyan)
        else Text("Tap to hear", style = MaterialTheme.typography.labelSmall, color = ZenithColors.Muted)
    }
}

@Composable
private fun RealtimeVoiceCard(
    enabled: Boolean, hasKey: Boolean, voice: String, model: String,
    onEnabled: (Boolean) -> Unit, onVoice: (String) -> Unit, onModel: (String) -> Unit,
    listModels: suspend () -> Result<List<String>>,
) {
    val scope = rememberCoroutineScope()
    var found by remember { mutableStateOf<List<String>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    ZenithCard(accent = if (enabled && hasKey) ZenithColors.Green else ZenithColors.Violet) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Real-time voice (Gemini Live)", style = MaterialTheme.typography.titleMedium)
                Text("ZENITH LIVE talks with you like a phone call: instant replies, natural voice, interrupt any time. Uses the internet and your Gemini API key; your voice is sent to Google.",
                    style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
            }
            androidx.compose.material3.Switch(checked = enabled, onCheckedChange = onEnabled, enabled = hasKey || enabled)
        }
        if (!hasKey) Text("Add a Google Gemini API key in Model Manager to use this.", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Amber)
        if (enabled) {
            Text("Voice", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            WrapRow { com.melhive.zenith.voice.GeminiLiveSession.VOICES.forEach { v -> FilterChip(selected = v == voice, onClick = { onVoice(v) }, label = { Text(v) }) } }
            Text("Model: ${model.ifBlank { com.melhive.zenith.voice.GeminiLiveSession.DEFAULT_MODEL + " (default)" }}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            WrapRow {
                TextButton(onClick = {
                    scope.launch {
                        note = "Asking Google for live models…"
                        listModels().onSuccess { found = it; note = if (it.isEmpty()) "No live models available for this key." else null }
                            .onFailure { note = "Couldn't list models: ${it.message}" }
                    }
                }) { Text("Find live models") }
                if (model.isNotBlank()) TextButton(onClick = { onModel("") }) { Text("Use default") }
            }
            found?.let { list -> WrapRow { list.forEach { m -> FilterChip(selected = m == model, onClick = { onModel(m) }, label = { Text(m) }) } } }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted) }
            Text("On the loudspeaker ZENITH mutes the mic while it talks (tap the orb to interrupt). With headphones you can just talk over it.",
                style = MaterialTheme.typography.labelSmall, color = ZenithColors.Muted)
        }
    }
}
