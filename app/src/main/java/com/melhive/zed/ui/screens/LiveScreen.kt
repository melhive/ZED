package com.melhive.zed.ui.screens

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.outlined.PhotoLibrary
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
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.zed.android.PermissionCatalog
import com.melhive.zed.tasks.statusEnum
import com.melhive.zed.ui.components.StatusPill
import com.melhive.zed.ui.components.TaskIndicator
import com.melhive.zed.ui.components.ZedOrb
import com.melhive.zed.ui.navigation.rememberContainer
import com.melhive.zed.ui.theme.ZedColors
import com.melhive.zed.voice.OrbState
import kotlinx.coroutines.launch

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LiveScreen(onClose: () -> Unit, onOpenTasks: () -> Unit, onOpenEye: () -> Unit = {}, onOpenChat: (Long?) -> Unit = {}) {
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
    // Ask with a photo: take one or pick one, then ZED is asked "What is this?" right away.
    fun askAbout(uri: android.net.Uri) = liveScope.launch {
        runCatching { com.melhive.zed.intelligence.ImageInput.fromUri(context, uri) }
            .onSuccess { live.submitImage(it, "What is this?") }
    }
    val liveCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = liveCapture
        if (ok && uri != null) askAbout(uri)
    }
    val liveGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { askAbout(it) } }
    var photoMenu by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    fun launchSystemVoice() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak to ZED")
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
    // Switching to Eye mode keeps the same session going.
    DisposableEffect(Unit) { c.voiceScreens.incrementAndGet(); onDispose { if (c.voiceScreens.decrementAndGet() <= 0) live.end() } }

    var history by remember { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF070B16), ZedColors.Background, Color(0xFF02030A))))
    ) {
        // The whole panel sits above the keyboard; the orb shrinks while typing so the box stays visible.
        val compact = typing || WindowInsets.isImeVisible
        val wide = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val large = settings?.captionSize == "large"
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("ZED LIVE", style = MaterialTheme.typography.titleMedium, color = ZedColors.Muted, modifier = Modifier.weight(1f))
                TaskIndicator(activeTasks, onOpenTasks)
                // ZED's memory at work: a memory flashes when it is used to answer.
                MiniBrain(Modifier.padding(start = 8.dp).size(52.dp), glow = settings?.glow ?: true)
                if (state.orb == OrbState.LISTENING) {
                    Spacer(Modifier.size(8.dp))
                    StatusPill("Mic on", ZedColors.Red)
                }
            }

            // Orb, eyes or chat: the same conversation in three forms.
            com.melhive.zed.ui.components.VoiceModeSwitch("orb", onSelect = { if (it == "eyes") onOpenEye() else onOpenChat(live.conversation) }, modifier = Modifier.padding(top = 6.dp))

            // Upright: the orb above the conversation. Sideways: the orb on the left, the conversation and controls on the right.
            val orbPart: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
            // The orb sits in the middle of the screen inside its HUD; the conversation lives below.
            Spacer(Modifier.weight(1f))
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val hud = if (wide) minOf(maxWidth * 0.8f, if (compact) 110.dp else 190.dp) else if (compact) minOf(maxWidth * 0.4f, 140.dp) else minOf(maxWidth * 0.86f, 340.dp)
                val interaction = remember { MutableInteractionSource() }
                Box(Modifier.size(hud).clickable(interactionSource = interaction, indication = null) { live.tapOrb() }, contentAlignment = Alignment.Center) {
                    com.melhive.zed.ui.components.JarvisHud(state, Modifier.fillMaxSize(), animate = settings?.animationLevel != "light")
                    ZedOrb(state.orb, micLevel = state.micLevel, speechPulse = state.speechPulse, size = hud * 0.56f, glow = settings?.glow ?: true, light = settings?.animationLevel == "light")
                }
            }
            Text(state.orb.label.uppercase(), style = MaterialTheme.typography.titleMedium, color = com.melhive.zed.ui.components.hudColor(state.orb), letterSpacing = 4.sp)
            Text(
                state.partial.ifEmpty { state.statusDetail ?: hintFor(state.orb) },
                style = MaterialTheme.typography.bodyMedium, color = ZedColors.Muted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(top = 4.dp), maxLines = 3,
            )
            Spacer(Modifier.weight(1f))
            }
            val talkPart: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
            // Captions: the last thing you said and what ZED is saying. Tap or swipe up for the whole conversation.
            if (state.transcript.isNotEmpty()) {
                val lastUser = state.transcript.lastOrNull { it.fromUser }?.text?.removePrefix("📷 ")
                val zedLine = if (state.orb == OrbState.SPEAKING && state.spoken.isNotBlank()) state.spoken.trim() else state.transcript.lastOrNull { !it.fromUser }?.text
                Column(
                    Modifier.fillMaxWidth()
                        .background(ZedColors.Surface.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                        .swipeUp { history = true }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { history = true }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!lastUser.isNullOrBlank()) Text(lastUser, color = ZedColors.Muted, style = if (large) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    if (!zedLine.isNullOrBlank()) Text(zedLine, color = ZedColors.Cyan.copy(alpha = 0.9f), style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium, maxLines = if (compact) 2 else if (large) 4 else 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
                    Text("Tap or swipe up for the conversation", color = ZedColors.Muted.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
            }

            if (state.orb == OrbState.ERROR || typing) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { launchSystemVoice() }) { Text("System voice input") }
                    TextButton(onClick = { typing = !typing }) { Text(if (typing) "Hide keyboard" else "Type instead") }
                }
            }
            if (typing) {
                OutlinedTextField(
                    value = typed, onValueChange = { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Type to ZED") },
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
                androidx.compose.foundation.layout.Box {
                    FilledTonalIconButton(onClick = { photoMenu = true }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Outlined.PhotoCamera, "Ask about a photo")
                    }
                    androidx.compose.material3.DropdownMenu(expanded = photoMenu, onDismissRequest = { photoMenu = false }) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Take a photo") }, leadingIcon = { Icon(Icons.Outlined.PhotoCamera, null) }, onClick = {
                            photoMenu = false
                            runCatching { com.melhive.zed.intelligence.ImageInput.newCaptureUri(context) }.onSuccess { liveCapture = it; liveCamera.launch(it) }
                        })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Choose from gallery") }, leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, null) }, onClick = {
                            photoMenu = false
                            liveGallery.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        })
                    }
                }
                FilledTonalIconButton(onClick = live::cancelRequest, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Outlined.StopCircle, "Cancel current request")
                }
                IconButton(
                    onClick = { live.end(); onClose() },
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.iconButtonColors(containerColor = ZedColors.Red.copy(alpha = 0.85f), contentColor = Color.White),
                ) { Icon(Icons.Outlined.Close, "End voice session") }
            }
            Text(
                buildString {
                    append(state.sttEngine.ifEmpty { "Speech recognition" })
                    if (state.orb == OrbState.SPEAKING) append(" · tap the orb to interrupt")
                    append(" · ending the session does not cancel tasks")
                },
                style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted.copy(alpha = 0.7f), textAlign = TextAlign.Center,
            )
            if (state.error != null && !state.active) {
                TextButton(onClick = { permission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Grant microphone access") }
            }
            }
            if (wide) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(0.42f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) { orbPart() }
                Column(
                    Modifier.weight(0.58f).fillMaxHeight().verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) { talkPart() }
            } else { orbPart(); talkPart() }
        }
        if (history) com.melhive.zed.ui.components.VoiceHistoryPanel(state.conversationId, state.transcript) { history = false }
    }
}

private fun hintFor(o: OrbState) = when (o) {
    OrbState.IDLE -> "Tap the orb to talk"
    OrbState.LISTENING -> "Speak now — tap the orb to finish"
    OrbState.SPEAKING -> "Tap the orb to interrupt"
    OrbState.THINKING -> "Tap the orb to cancel"
    else -> ""
}


/** Calls [onSwipe] when the finger moves up across this element. */
private fun Modifier.swipeUp(onSwipe: () -> Unit): Modifier = pointerInput(Unit) {
    var moved = 0f
    detectVerticalDragGestures(onDragStart = { moved = 0f }, onDragEnd = { if (moved < -40f) onSwipe() }) { _, dy -> moved += dy }
}
