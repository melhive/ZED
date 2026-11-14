package com.melhive.zed.ui.screens

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
import com.melhive.zed.android.DeviceInfo
import com.melhive.zed.core.voice.NeuralVoice
import com.melhive.zed.core.voice.VoiceCatalog
import com.melhive.zed.core.voice.VoiceGender
import com.melhive.zed.core.voice.VoicePack
import com.melhive.zed.core.voice.VoiceSelection
import com.melhive.zed.tasks.TaskTypes
import com.melhive.zed.tasks.input
import com.melhive.zed.tasks.statusEnum
import com.melhive.zed.ui.components.RealProgress
import com.melhive.zed.ui.components.SectionTitle
import com.melhive.zed.ui.components.StatusPill
import com.melhive.zed.ui.components.WrapRow
import com.melhive.zed.ui.components.ZedCard
import com.melhive.zed.ui.navigation.rememberContainer
import com.melhive.zed.ui.theme.ZedColors
import com.melhive.zed.voice.SystemVoice
import com.melhive.zed.voice.VoiceManager
import kotlinx.coroutines.launch

/** Choose how ZED sounds: offline neural voices, phone voices, speed and pitch. */
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
            com.melhive.zed.android.CrashReporter.recordNonFatal("voice screen", e)
            message = "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
        }
    }
    val sample = "Hello${name?.let { ", $it" } ?: ""}. I'm ZED. It's 7:30, and you have 3 things on your list today."
    fun choose(id: String) = safely {
        c.settings.setVoiceId(id)
        message = "Preparing the voice… the first time takes a few seconds."
        message = if (c.voice.preview(sample)) null else "That voice could not be loaded. The phone voice is used instead."
    }
    fun previewNow() = safely {
        message = "Preparing the voice…"
        message = if (c.voice.preview(sample)) null else "Speech is unavailable on this phone."
    }

    ListScreen("Voice", onBack = onBack, subtitle = "Choose how ZED sounds. Every voice runs on your phone, offline.") {
        item {
            ZedCard {
                SectionTitle("Now speaking with")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.RecordVoiceOver, null, tint = ZedColors.Cyan)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(selectionLabel(selection), style = MaterialTheme.typography.titleMedium)
                        (status.detail ?: "Tap Preview to hear it").let { Text(it, style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted) }
                    }
                    when (status.phase) {
                        VoiceManager.Phase.LOADING -> StatusPill("Loading", ZedColors.Amber)
                        VoiceManager.Phase.READY -> StatusPill("Ready", ZedColors.Green)
                        VoiceManager.Phase.FALLBACK -> StatusPill("Fallback", ZedColors.Amber)
                        VoiceManager.Phase.IDLE -> Unit
                    }
                }
                WrapRow(Modifier.padding(top = 8.dp)) {
                    Button(onClick = { previewNow() }) { Text("Preview") }
                    TextButton(onClick = { c.voice.stop() }) { Text("Stop") }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ZedColors.Amber) }
            }
        }

        item {
            val wakeInstalled = com.melhive.zed.core.voice.VoiceCatalog.WAKE_WORD.id in installed
            val boosterPack = com.melhive.zed.core.voice.VoiceCatalog.WAKE_WORD_2
            val boosterInstalled = boosterPack.id in installed
            val boosterTask = tasks.firstOrNull { it.type == TaskTypes.VOICE_DOWNLOAD && it.input()["pack"] == boosterPack.id && !it.statusEnum().terminal }
            LaunchedEffect(wakeInstalled, settings.wakeWordEnabled) {
                if (wakeInstalled && settings.wakeWordEnabled) com.melhive.zed.voice.WakeWordService.start(context)
            }
            // The booster joins in as soon as it's downloaded.
            LaunchedEffect(boosterInstalled) {
                if (boosterInstalled && wakeInstalled && settings.wakeWordEnabled) { com.melhive.zed.voice.WakeWordService.stop(context); com.melhive.zed.voice.WakeWordService.start(context) }
            }
            val wakeTask = tasks.firstOrNull { it.type == TaskTypes.VOICE_DOWNLOAD && it.input()["pack"] == com.melhive.zed.core.voice.VoiceCatalog.WAKE_WORD.id && !it.statusEnum().terminal }
            ZedCard(accent = if (settings.wakeWordEnabled && wakeInstalled) ZedColors.Green else ZedColors.Violet) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("“Hey Zed”", style = MaterialTheme.typography.titleMedium)
                        Text("Say “Hey Zed” any time to start talking. Listening happens entirely on the phone — only for the wake word — while a notification is shown. Uses some battery.",
                            style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                    }
                    androidx.compose.material3.Switch(checked = settings.wakeWordEnabled, onCheckedChange = { on ->
                        safely {
                            c.settings.setWakeWordEnabled(on)
                            if (!on) { com.melhive.zed.voice.WakeWordService.stop(context); return@safely }
                            if (!boosterInstalled && boosterTask == null) c.taskEngine.create(TaskTypes.VOICE_DOWNLOAD, "Download wake word booster", mapOf("pack" to boosterPack.id))
                            if (!wakeInstalled && wakeTask == null) {
                                c.taskEngine.create(TaskTypes.VOICE_DOWNLOAD, "Download wake word model", mapOf("pack" to com.melhive.zed.core.voice.VoiceCatalog.WAKE_WORD.id))
                                message = "Downloading the wake word model (18 MB)… “Hey Zed” starts when it's ready."
                            } else com.melhive.zed.voice.WakeWordService.start(context)
                        }
                    })
                }
                if (wakeTask != null) RealProgress(wakeTask.progressCurrent, wakeTask.progressTotal, Modifier.padding(top = 6.dp))
                if (boosterTask != null) RealProgress(boosterTask.progressCurrent, boosterTask.progressTotal, Modifier.padding(top = 6.dp))
                else if (wakeInstalled && !boosterInstalled) TextButton(onClick = { safely {
                    c.taskEngine.create(TaskTypes.VOICE_DOWNLOAD, "Download wake word booster", mapOf("pack" to boosterPack.id))
                    message = "Downloading the wake word booster (${boosterPack.sizeBytes / 1_000_000} MB)…"
                } }) { Text("Catch more “Hey Zed”s: add the booster (${boosterPack.sizeBytes / 1_000_000} MB)") }
                else if (boosterInstalled) Text("Booster on: two listening models, so quieter and distant “Hey Zed”s are caught too.", style = MaterialTheme.typography.labelSmall, color = ZedColors.Green, modifier = Modifier.padding(top = 4.dp))
                if (settings.wakeWordEnabled) {
                    Text("Sensitivity", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                    WrapRow {
                        listOf(0 to "Fewer false alarms", 1 to "Balanced", 2 to "High", 3 to "Maximum").forEach { (v, l) ->
                            FilterChip(selected = settings.wakeSensitivity == v, onClick = { safely {
                                c.settings.setWakeSensitivity(v)
                                com.melhive.zed.voice.WakeWordService.stop(context); com.melhive.zed.voice.WakeWordService.start(context)
                            } }, label = { Text(l) })
                        }
                    }
                    Text(when (settings.wakeSensitivity) {
                        3 -> "Maximum: wakes on “Zed” alone or in “Hey Zed”, even said quietly or from across the room. Words that sound like it (“zero”, “Jed”) can wake it now and then."
                        2 -> "High: wakes on most “Zed”s, with only the odd false alarm."
                        1 -> "Balanced: fewer false alarms; say “Zed” clearly."
                        else -> "Fewer false alarms: say “Hey Zed” clearly and close to the phone."
                    }, style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                    if (!android.provider.Settings.canDrawOverlays(context)) {
                        Text("To open LIVE when another app is on screen, allow ZED to display over other apps. Without it, ZED answers hands-free and shows the reply as a notification.",
                            style = MaterialTheme.typography.bodySmall, color = ZedColors.Amber, modifier = Modifier.padding(top = 4.dp))
                        TextButton(onClick = {
                            runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + context.packageName))) }
                        }) { Text("Allow display over other apps") }
                    }
                }
            }
        }
        item {
            ZedCard {
                SectionTitle("Announcements")
                Text("ZED comes onto your screen (even the lock screen) and says what's due, then listens — say “snooze ten minutes” to a reminder.",
                    style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Reminders", style = MaterialTheme.typography.titleSmall)
                        Text("Say each reminder when it's due", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                    }
                    androidx.compose.material3.Switch(checked = settings.announceReminders, onCheckedChange = { on -> safely { c.settings.setAnnounceReminders(on) } })
                }
                Text("Calendar events", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
                WrapRow(Modifier.padding(top = 4.dp)) {
                    listOf(0 to "Off", 5 to "5 min before", 10 to "10 min", 15 to "15 min", 30 to "30 min").forEach { (m, label) ->
                        FilterChip(selected = settings.eventAnnounceMinutes == m, onClick = { safely {
                            c.settings.setEventAnnounceMinutes(m)
                            com.melhive.zed.agents.Announcer.planEvents(context)
                        } }, label = { Text(label) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("After my alarm", style = MaterialTheme.typography.titleSmall)
                        Text("When you turn off an alarm, ZED says good morning with the weather and your day (skipped when you snooze)", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                    }
                    androidx.compose.material3.Switch(checked = settings.alarmBriefing, onCheckedChange = { on -> safely { c.settings.setAlarmBriefing(on) } })
                }
                if (!android.provider.Settings.canDrawOverlays(context))
                    Text("Allow ZED to display over other apps so it can appear on screen; without it, ZED only speaks.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Amber, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item {
            val speech = com.melhive.zed.core.voice.VoiceCatalog.SPEECH
            val speechInstalled = speech.id in installed
            val speechTask = tasks.firstOrNull { it.type == TaskTypes.VOICE_DOWNLOAD && it.input()["pack"] == speech.id && !it.statusEnum().terminal }
            val onDevice = speechInstalled && settings.speechEngine != "android"
            ZedCard(accent = if (onDevice) ZedColors.Green else ZedColors.Violet) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Speech recognition", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (onDevice) "On the phone: works offline, no beep, and your words appear as you speak (English)."
                            else "Recognize speech on the phone instead of the phone's speech service: offline, private, no “Google beep”, faster. English. ${speech.sizeBytes / 1_000_000} MB download.",
                            style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted,
                        )
                    }
                    if (speechInstalled) androidx.compose.material3.Switch(checked = onDevice, onCheckedChange = { on -> safely { c.settings.setSpeechEngine(if (on) "auto" else "android") } })
                }
                when {
                    speechTask != null -> RealProgress(speechTask.progressCurrent, speechTask.progressTotal, Modifier.padding(top = 6.dp))
                    !speechInstalled -> Button(onClick = { safely {
                        c.taskEngine.create(TaskTypes.VOICE_DOWNLOAD, "Download speech recognition", mapOf("pack" to speech.id))
                        message = "Downloading on-device speech recognition (${speech.sizeBytes / 1_000_000} MB)… ZED switches to it when it's ready."
                    } }, modifier = Modifier.padding(top = 8.dp)) { Text("Download") }
                    else -> TextButton(onClick = { safely { com.melhive.zed.voice.OnDeviceSpeechToText.Engine.unload(); c.voicePacks.remove(speech) } }) { Text("Remove (${speech.sizeBytes / 1_000_000} MB)", color = ZedColors.Muted) }
                }
                Text("Model: NVIDIA NeMo FastConformer · ${speech.license}", style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
        item {
            ZedCard {
                SectionTitle("LIVE sounds")
                Text("The sound when ZED LIVE opens and closes. Tap one to hear it.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                WrapRow(Modifier.padding(top = 6.dp)) {
                    com.melhive.zed.core.voice.SoundSynth.Style.entries.forEach { st ->
                        FilterChip(selected = com.melhive.zed.core.voice.SoundSynth.Style.parse(settings.liveSound) == st, onClick = {
                            safely { c.settings.setLiveSound(st.id) }
                            val ms = com.melhive.zed.voice.ZedSounds.play(context, st, start = true)
                            if (ms > 0 && settings.liveHaptics) com.melhive.zed.voice.ZedHaptics.wake(context)
                            if (ms > 0) scope.launch { kotlinx.coroutines.delay(ms + 450); com.melhive.zed.voice.ZedSounds.play(context, st, start = false) }
                        }, label = { Text(st.label) })
                    }
                }
                Text(com.melhive.zed.core.voice.SoundSynth.Style.parse(settings.liveSound).description, style = MaterialTheme.typography.labelSmall, color = ZedColors.Cyan)
                Text("With earphones", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
                WrapRow(Modifier.padding(top = 4.dp)) {
                    listOf("auto" to "Automatic", "earphone_mic" to "Earphone mic, phone speaker").forEach { (id, label) ->
                        FilterChip(selected = settings.headsetAudio == id, onClick = { safely { c.settings.setHeadsetAudio(id) } }, label = { Text(label) })
                    }
                }
                Text(
                    if (settings.headsetAudio == "earphone_mic") "ZED listens through your earphones' or earbuds' mic and talks from the phone's speaker. (Bluetooth earbuds switch to their call mic while LIVE is open.)"
                    else "Everything works the same with earphones: ZED talks in them, a wired headset's mic is used automatically, and Bluetooth earbuds use the phone's mic. With earphones you can also interrupt ZED by talking.",
                    style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Greet me when I call", style = MaterialTheme.typography.titleSmall)
                        Text("After “Hey Zed” or the assistant shortcut, ZED answers right away — “Hello sir, how can I help you today?”, “At your service.”, “Good evening. What do you need?” — then listens. Say “call me sir” to choose how it addresses you.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                    }
                    androidx.compose.material3.Switch(checked = settings.greetOnCall, onCheckedChange = { on -> safely { c.settings.setGreetOnCall(on) } })
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Hide the recognizer beep", style = MaterialTheme.typography.titleSmall)
                        Text("Silences the speech service's own “listening” sound (the Google beep) each time LIVE starts listening.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
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
                        message = "Downloading ${pack.name} as task #${t.id}. You can keep using ZED."
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
            ZedCard {
                Text("Voices from your phone's text-to-speech engine. Quality depends on the phone; Google's high-quality offline voices sound best.",
                    style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                VoiceRow("Phone default", "Your phone's default voice", selection is VoiceSelection.SystemDefault, featured = false) { choose(VoiceSelection.SYSTEM_DEFAULT) }
                val list = systemVoices
                when {
                    list == null -> Text("Loading phone voices…", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                    list.isEmpty() -> Text("No phone voices found.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
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
                style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted, modifier = Modifier.padding(4.dp))
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
    ZedCard {
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
    ZedCard(accent = if (installed) ZedColors.Green else ZedColors.Violet) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pack.name, style = MaterialTheme.typography.titleMedium)
                Text(pack.tagline, style = MaterialTheme.typography.labelMedium, color = ZedColors.Cyan)
            }
            if (installed) StatusPill("Installed", ZedColors.Green)
        }
        Text(pack.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        Text("${pack.voices.size} voices · ${DeviceInfo.formatBytes(pack.sizeBytes)} download · ${pack.license}", style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted)
        when {
            downloading -> {
                RealProgress(progress?.first, progress?.second, Modifier.padding(top = 8.dp))
                val (done, total, label) = progress ?: Triple(null, null, null)
                Text(listOfNotNull(label, if (done != null && total != null) "${DeviceInfo.formatBytes(done)} / ${DeviceInfo.formatBytes(total)}" else null).joinToString(" · ").ifEmpty { "Queued" },
                    style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
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
                Text(title, style = MaterialTheme.typography.titleSmall, color = if (selected) ZedColors.Cyan else ZedColors.Text)
                if (featured) Icon(Icons.Outlined.Star, "Recommended", tint = ZedColors.Amber, modifier = Modifier.padding(start = 4.dp).size(16.dp))
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
        }
        if (selected) Icon(Icons.Outlined.CheckCircle, "Selected", tint = ZedColors.Cyan)
        else Text("Tap to hear", style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted)
    }
}
