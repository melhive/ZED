package com.melhive.zenith.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.zenith.android.PermissionCatalog
import com.melhive.zenith.tasks.statusEnum
import com.melhive.zenith.ui.components.StatusPill
import com.melhive.zenith.ui.components.TaskIndicator
import com.melhive.zenith.ui.components.ZenithOrb
import com.melhive.zenith.ui.navigation.rememberContainer
import com.melhive.zenith.ui.theme.ZenithColors
import com.melhive.zenith.voice.OrbState
import kotlinx.coroutines.launch

@Composable
fun LiveScreen(onClose: () -> Unit, onOpenTasks: () -> Unit) {
    val c = rememberContainer()
    val context = LocalContext.current
    val live = c.live
    val state by live.state.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val tasks by c.taskEngine.tasks.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeTasks = tasks.count { !it.statusEnum().terminal }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) live.start() }
    // Fallback: the phone's own voice-typing popup (works even when background recognition fails).
    val systemVoice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { live.submitText(it) }
    }
    val liveScope = androidx.compose.runtime.rememberCoroutineScope()
    var liveCapture by remember { mutableStateOf<android.net.Uri?>(null) }
    val liveCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = liveCapture
        if (ok && uri != null) liveScope.launch {
            runCatching { com.melhive.zenith.intelligence.ImageInput.fromUri(context, uri) }
                .onSuccess { live.submitImage(it, "What am I looking at?") }
        }
    }
    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    fun launchSystemVoice() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak to ZENITH")
        runCatching { systemVoice.launch(intent) }.onFailure { typing = true }
    }
    LaunchedEffect(Unit) {
        if (PermissionCatalog.isGranted(context, PermissionCatalog.MICROPHONE)) live.start() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    // A request handed over by a notification or shortcut ("brief me") is sent once LIVE is ready.
    val pendingPrompt by c.livePrompt.collectAsStateWithLifecycle()
    LaunchedEffect(pendingPrompt, state.active, state.orb) {
        val p = pendingPrompt
        if (p != null && state.active && state.orb in setOf(OrbState.LISTENING, OrbState.IDLE)) {
            kotlinx.coroutines.delay(400)
            c.livePrompt.value = null
            live.submitText(p)
        }
    }
    // Leaving the screen ends the voice session (the mic is never used in the background). Tasks continue.
    DisposableEffect(Unit) { onDispose { live.end() } }

    val listState = rememberLazyListState()
    LaunchedEffect(state.transcript.size) { if (state.transcript.isNotEmpty()) listState.animateScrollToItem(state.transcript.size - 1) }

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF070B16), ZenithColors.Background, Color(0xFF02030A))))
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("ZENITH LIVE", style = MaterialTheme.typography.titleMedium, color = ZenithColors.Muted, modifier = Modifier.weight(1f))
                TaskIndicator(activeTasks, onOpenTasks)
                if (state.orb == OrbState.LISTENING) {
                    Spacer(Modifier.size(8.dp))
                    StatusPill("Mic on", ZenithColors.Red)
                }
            }

            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.transcript.takeLast(30)) { turn ->
                    Text(
                        (if (turn.fromUser) "You: " else "ZENITH: ") + turn.text,
                        color = if (turn.fromUser) ZenithColors.Text else ZenithColors.Cyan.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            val interaction = remember { MutableInteractionSource() }
            Box(Modifier.clickable(interactionSource = interaction, indication = null) { live.tapOrb() }, contentAlignment = Alignment.Center) {
                ZenithOrb(state.orb, micLevel = state.micLevel, speechPulse = state.speechPulse, size = 280.dp, glow = settings?.glow ?: true)
            }
            Text(state.orb.label, style = MaterialTheme.typography.headlineSmall, color = ZenithColors.Text)
            Text(
                state.partial.ifEmpty { state.statusDetail ?: hintFor(state.orb) },
                style = MaterialTheme.typography.bodyMedium, color = ZenithColors.Muted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 4.dp), maxLines = 4,
            )

            if (state.orb == OrbState.ERROR || typing) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { launchSystemVoice() }) { Text("System voice input") }
                    TextButton(onClick = { typing = !typing }) { Text(if (typing) "Hide keyboard" else "Type instead") }
                }
            }
            if (typing) {
                OutlinedTextField(
                    value = typed, onValueChange = { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth().imePadding(),
                    placeholder = { Text("Type to ZENITH") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { live.submitText(typed); typed = "" }),
                    trailingIcon = { IconButton(onClick = { live.submitText(typed); typed = "" }) { Icon(Icons.AutoMirrored.Outlined.Send, "Send") } },
                )
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = live::toggleMute, modifier = Modifier.size(56.dp)) {
                    Icon(if (state.micMuted) Icons.Outlined.MicOff else Icons.Outlined.Mic, if (state.micMuted) "Unmute microphone" else "Mute microphone")
                }
                FilledTonalIconButton(onClick = live::toggleSpeaker, modifier = Modifier.size(56.dp)) {
                    Icon(if (state.speakerOn) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff, if (state.speakerOn) "Mute voice replies" else "Enable voice replies")
                }
                FilledTonalIconButton(onClick = {
                    runCatching { com.melhive.zenith.intelligence.ImageInput.newCaptureUri(context) }.onSuccess { liveCapture = it; liveCamera.launch(it) }
                }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Outlined.PhotoCamera, "What am I looking at? Take a photo")
                }
                FilledTonalIconButton(onClick = live::cancelRequest, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Outlined.StopCircle, "Cancel current request")
                }
                IconButton(
                    onClick = { live.end(); onClose() },
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.iconButtonColors(containerColor = ZenithColors.Red.copy(alpha = 0.85f), contentColor = Color.White),
                ) { Icon(Icons.Outlined.Close, "End voice session") }
            }
            Text(
                buildString {
                    append(state.sttEngine.ifEmpty { "Speech recognition" })
                    if (state.orb == OrbState.SPEAKING) append(" · tap the orb to interrupt")
                    append(" · ending the session does not cancel tasks")
                },
                style = MaterialTheme.typography.labelSmall, color = ZenithColors.Muted.copy(alpha = 0.7f), textAlign = TextAlign.Center,
            )
            if (state.error != null && !state.active) {
                TextButton(onClick = { permission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Grant microphone access") }
            }
        }
    }
}

private fun hintFor(o: OrbState) = when (o) {
    OrbState.IDLE -> "Tap the orb to talk"
    OrbState.LISTENING -> "Speak now — tap the orb to finish"
    OrbState.SPEAKING -> "Tap the orb to interrupt"
    OrbState.THINKING -> "Tap the orb to cancel"
    else -> ""
}

