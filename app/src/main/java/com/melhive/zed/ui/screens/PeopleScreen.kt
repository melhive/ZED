package com.melhive.zed.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.zed.android.CrashReporter
import com.melhive.zed.android.PermissionCatalog
import com.melhive.zed.core.eyes.PeopleBook
import com.melhive.zed.eyes.EyeCamera
import com.melhive.zed.eyes.EyePeople
import com.melhive.zed.eyes.VoiceId
import com.melhive.zed.ui.components.SectionTitle
import com.melhive.zed.ui.components.ZedCard
import com.melhive.zed.ui.navigation.rememberContainer
import com.melhive.zed.ui.theme.ZedColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the owner reads aloud so ZED can learn their voice: ordinary sentences, each a few seconds long. */
private val VOICE_LINES = listOf(
    "Hey Zed, what's on my schedule for today?",
    "Remind me to call my family tomorrow evening at six.",
    "What's the weather like outside, and should I bring an umbrella?",
    "Set a timer for fifteen minutes and tell me when it's done.",
    "Tell me something interesting about the place where I live.",
)

/**
 * More → Who ZED knows: the owner's face and voice, "only my voice", and the people ZED has met.
 * Everything here stays on the phone.
 */
@Composable
fun PeopleScreen(onBack: () -> Unit) {
    val c = rememberContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val version by c.people.version.collectAsStateWithLifecycle()
    val known = remember(version) { c.people.names() }
    val voices = remember(version) { c.people.ownerVoices() }
    val hasFace = remember(version) { c.people.ownerHasFace() }
    var voiceReady by remember { mutableStateOf(c.voiceId.installed()) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var recording by remember { mutableIntStateOf(-1) } // which sentence is being recorded, or -1
    var level by remember { mutableStateOf(0f) }
    var teaching by remember { mutableIntStateOf(-1) }
    var faceInView by remember { mutableStateOf(false) }
    var cameraAllowed by remember { mutableStateOf(PermissionCatalog.isGranted(context, PermissionCatalog.CAMERA)) }
    var micAllowed by remember { mutableStateOf(PermissionCatalog.isGranted(context, PermissionCatalog.MICROPHONE)) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.CAMERA] == true) cameraAllowed = true
        if (granted[Manifest.permission.RECORD_AUDIO] == true) micAllowed = true
    }
    suspend fun ownerName(): String = runCatching { c.memory.userName() }.getOrNull() ?: "You"

    // The front camera runs here only to teach ZED the owner's face.
    val camera = remember { EyeCamera(context.applicationContext) }
    val people = remember { EyePeople(c.people, c.voiceId) }
    LifecycleStartEffect(cameraAllowed) {
        if (cameraAllowed) { camera.onFaceCrop = { people.onFace(it) }; camera.start(preferFront = true) }
        onStopOrDispose { camera.onFaceCrop = null; camera.stop() }
    }
    LaunchedEffect(Unit) { while (true) { teaching = people.teaching; faceInView = camera.running && camera.faceSeen; delay(300) } }

    ListScreen("Who ZED knows", onBack = onBack, subtitle = "Faces and voices stay on this phone") {
        item {
            ZedCard {
                SectionTitle("Your face")
                Text(
                    if (hasFace) "ZED knows your face. In Eye mode and LIVE it greets you when recognition is on."
                    else "Hold the phone in front of you and tap the button. ZED takes eight pictures over a few seconds; turn your head a little while it does.",
                    style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted,
                )
                if (!cameraAllowed) TextButton(onClick = { permissions.launch(arrayOf(Manifest.permission.CAMERA)) }) { Text("Allow the camera") }
                else {
                    Text(if (faceInView) "Your face is in view." else "No face in view yet: look at the front camera in good light.", style = MaterialTheme.typography.labelSmall, color = if (faceInView) ZedColors.Green else ZedColors.Amber)
                    TextButton(enabled = teaching < 0 && faceInView, onClick = { scope.launch { people.teach(ownerName()) } }) {
                        Text(if (teaching >= 0) "Learning your face $teaching/${EyePeople.TEACH}…" else if (hasFace) "Teach my face again" else "Teach my face")
                    }
                }
            }
        }
        item {
            ZedCard {
                SectionTitle("Your voice")
                Text("Read five sentences aloud, one at a time, in your normal voice. ZED keeps a voiceprint of each ($voices of ${PeopleBook.MAX_VOICES} recorded).", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                if (!voiceReady) TextButton(enabled = downloading == null, onClick = {
                    scope.launch {
                        downloading = "Starting…"
                        runCatching { c.voiceId.download { done, total -> downloading = "Downloading ${done * 100 / total.coerceAtLeast(1)}%" } }
                            .onSuccess { voiceReady = true; note = "" }
                            .onFailure { note = "The download didn't finish: ${it.message ?: "no connection"}" }
                        downloading = null
                    }
                }) { Text(downloading ?: "Download voice recognition (30 MB)") }
                else if (!micAllowed) TextButton(onClick = { permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }) { Text("Allow the microphone") }
                else {
                    val next = voices.coerceAtMost(VOICE_LINES.size - 1)
                    if (voices < PeopleBook.MAX_VOICES) {
                        Text("“${VOICE_LINES[next]}”", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 6.dp))
                        if (recording >= 0) LinearProgressIndicator(progress = { level.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        TextButton(enabled = recording < 0, onClick = {
                            scope.launch {
                                recording = next; note = ""
                                val audio = recordVoice(5000) { level = it }
                                recording = -1; level = 0f
                                val print = audio?.let { withContext(Dispatchers.Default) { c.voiceId.print(it) } }
                                if (print == null) note = "I couldn't hear that clearly. Try again a little closer to the phone."
                                else c.people.addOwnerVoice(ownerName(), print)
                            }
                        }) { Text(if (recording >= 0) "Listening… read the sentence now" else "Record sentence ${next + 1} of ${PeopleBook.MAX_VOICES}") }
                    } else Text("All five are recorded.", style = MaterialTheme.typography.bodyMedium, color = ZedColors.Green)
                    if (voices > 0) TextButton(enabled = recording < 0, onClick = { c.people.clearOwnerVoices(); scope.launch { c.settings.setOwnerVoiceOnly(false) } }) { Text("Start over", color = ZedColors.Red) }
                }
                if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = ZedColors.Amber)
            }
        }
        item {
            ZedCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Only my voice", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "When someone else talks to ZED, it says so once and answers as a guest: general questions only, nothing personal. " +
                                "It checks each sentence you say (very short ones can't be checked), needs on-device speech recognition, and can sometimes mistake you for a guest " +
                                "(a cold, noise, earbuds). Tap “Guest” on the orb and unlock to get full access back. It is a privacy guard, not a lock.",
                            style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted,
                        )
                        if (voices < 3) Text("Record at least three sentences above first.", style = MaterialTheme.typography.labelSmall, color = ZedColors.Amber)
                    }
                    Switch(checked = settings?.ownerVoiceOnly == true, enabled = voices >= 3 && voiceReady, onCheckedChange = { on -> scope.launch { c.settings.setOwnerVoiceOnly(on) } })
                }
            }
        }
        item {
            ZedCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Recognise people", style = MaterialTheme.typography.titleSmall)
                        Text("An experiment. With the camera on in Eye mode and LIVE, ZED greets people it knows and asks someone new for their name once. It changes nothing about what ZED will do or say.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                    }
                    Switch(checked = settings?.eyeRecognition == true, onCheckedChange = { on -> scope.launch { c.settings.setEyeRecognition(on) } })
                }
            }
        }
        item {
            ZedCard {
                SectionTitle("People ZED knows")
                if (known.isEmpty()) Text("Nobody yet.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                known.forEach { (id, name, owner) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        var edited by remember(id) { mutableStateOf(name) }
                        OutlinedTextField(
                            value = edited, onValueChange = { edited = it; if (it.isNotBlank()) c.people.rename(id, it.trim()) }, singleLine = true,
                            label = { Text(if (owner) "You" else "Name") }, modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { c.people.remove(id); if (owner) scope.launch { c.settings.setOwnerVoiceOnly(false) } }) { Icon(Icons.Outlined.Close, "Forget $name", tint = ZedColors.Red) }
                    }
                }
                if (known.size > 1) TextButton(onClick = { c.people.clear(); scope.launch { c.settings.setOwnerVoiceOnly(false) } }) { Text("Forget everyone", color = ZedColors.Red) }
            }
        }
    }
}

/** Records [millis] of speech at 16 kHz. The "Hey Zed" listener is paused meanwhile so both don't fight over the microphone. */
@SuppressLint("MissingPermission")
private suspend fun recordVoice(millis: Int, onLevel: (Float) -> Unit): FloatArray? = withContext(Dispatchers.IO) {
    val wake = com.melhive.zed.voice.WakeWordService
    val wasPaused = wake.paused.value
    wake.paused.value = true
    delay(350)
    val rate = VoiceId.RATE
    var record: AudioRecord? = null
    try {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        if (record.state != AudioRecord.STATE_INITIALIZED) return@withContext null
        val total = rate * millis / 1000
        val out = FloatArray(total)
        val buf = ShortArray(1600)
        var at = 0
        var loud = 0
        record.startRecording()
        while (at < total) {
            val n = record.read(buf, 0, minOf(buf.size, total - at))
            if (n <= 0) break
            var sum = 0.0
            for (i in 0 until n) { val v = buf[i] / 32768f; out[at + i] = v; sum += v * v }
            val rms = kotlin.math.sqrt(sum / n).toFloat()
            if (rms > 0.01f) loud++
            onLevel((rms * 12f).coerceIn(0f, 1f))
            at += n
        }
        // At least about a second of actual speech, or it is not worth keeping.
        if (loud < 10) null else out.copyOf(at)
    } catch (e: Throwable) {
        CrashReporter.recordNonFatal("voice setup", e); null
    } finally {
        runCatching { record?.stop() }; runCatching { record?.release() }
        wake.paused.value = wasPaused
    }
}
