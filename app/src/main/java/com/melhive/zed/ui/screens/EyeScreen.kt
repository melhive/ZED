package com.melhive.zed.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.zed.android.PermissionCatalog
import com.melhive.zed.core.eyes.EyeActivity
import com.melhive.zed.core.eyes.EyeArt
import com.melhive.zed.core.eyes.EyeEmotion
import com.melhive.zed.core.eyes.EyeFrame
import com.melhive.zed.core.eyes.EyeMind
import com.melhive.zed.core.eyes.EyeMood
import com.melhive.zed.eyes.EyeCamera
import com.melhive.zed.ui.navigation.rememberContainer
import com.melhive.zed.ui.theme.ZedColors
import com.melhive.zed.voice.OrbState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Eye mode: ZED's two eyes on a black screen, upright or sideways. It is the
 * LIVE voice session with a different face, so everything said is saved to the
 * same conversation. The camera lets the eyes follow the user and look at what
 * is shown; it runs only while this screen is visible.
 *
 * Tap: show the controls. Double-tap: interrupt ZED or start talking.
 */
@Composable
fun EyeScreen(onClose: () -> Unit, onOpenChat: (Long?) -> Unit, onOpenLive: () -> Unit) {
    val c = rememberContainer()
    val context = LocalContext.current
    val view = LocalView.current
    val live = c.live
    val state by live.state.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()

    val mind = remember { EyeMind() }
    val art = remember { EyeArt() }
    val frame = remember { EyeFrame() }
    val camera = remember { EyeCamera(context.applicationContext) }
    val voiceId = c.voiceId
    val people = remember { com.melhive.zed.eyes.EyePeople(c.people, voiceId) }
    var cameraAllowed by remember { mutableStateOf(PermissionCatalog.isGranted(context, PermissionCatalog.CAMERA)) }
    var cameraOn by remember { mutableStateOf(true) }
    var controls by remember { mutableStateOf(false) }
    var touched by remember { mutableIntStateOf(0) }
    var closing by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var asleep by remember { mutableStateOf(false) }
    var peopleDialog by remember { mutableStateOf(false) }
    var testLine by remember { mutableStateOf("") }
    val recognising = settings?.eyeRecognition == true
    val large = settings?.captionSize == "large"
    val sideways = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // Nothing but the eyes, and the screen stays on. The screen turns with the phone (Android's own rotate button works);
    // the rotate button in the controls turns it by hand.
    var turned by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val before = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val bars = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            bars?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = before
        }
    }
    LaunchedEffect(turned) { turned?.let { (context as? Activity)?.requestedOrientation = it } }

    // Microphone for the conversation, camera for the eyes. Without the camera the eyes still work; they just can't see.
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.CAMERA] == true) cameraAllowed = true
        if (granted[Manifest.permission.RECORD_AUDIO] == true || PermissionCatalog.isGranted(context, PermissionCatalog.MICROPHONE)) live.start(greet = true)
    }
    LaunchedEffect(Unit) {
        val mic = PermissionCatalog.isGranted(context, PermissionCatalog.MICROPHONE)
        if (mic) live.start(greet = true)
        val missing = buildList { if (!mic) add(Manifest.permission.RECORD_AUDIO); if (!cameraAllowed) add(Manifest.permission.CAMERA) }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }
    // Opened by voice from the floating orb: that session ends as the orb closes, so start a fresh one here.
    LaunchedEffect(state.active) {
        if (!state.active && !closing) {
            delay(700)
            if (!live.state.value.active && !closing && PermissionCatalog.isGranted(context, PermissionCatalog.MICROPHONE)) live.start(greet = true)
        }
    }
    // The same session as LIVE: it ends when the last voice screen closes, not when switching between them.
    DisposableEffect(Unit) { c.voiceScreens.incrementAndGet(); onDispose { if (c.voiceScreens.decrementAndGet() <= 0) live.end() } }

    // The camera runs only while Eye mode is on screen.
    val lookBack = settings?.eyeLookBack == true
    LifecycleStartEffect(cameraAllowed, cameraOn, lookBack) {
        // The front camera follows you; "what is this?" looks through whichever camera you chose.
        val look: suspend () -> com.melhive.zed.intelligence.ImageInput? = { camera.snap(front = !lookBack, resume = true) }
        if (cameraAllowed && cameraOn) {
            camera.start(preferFront = true)
            live.frameSource = look
        }
        onStopOrDispose {
            if (live.frameSource === look) live.frameSource = null
            camera.stop()
        }
    }

    // The recognition experiment: who is in view, and whether the voice is yours.
    com.melhive.zed.ui.components.PeopleWatch(recognising, camera, people, onMood = { mind.mood = it }) { testLine = it }

    fun close() { closing = true; onClose() }
    BackHandler { close() }

    // What ZED is doing and feeling.
    LaunchedEffect(state.orb) {
        mind.activity = when (state.orb) {
            OrbState.LISTENING -> EyeActivity.LISTENING
            OrbState.THINKING -> EyeActivity.THINKING
            OrbState.SPEAKING -> EyeActivity.SPEAKING
            OrbState.EXECUTING, OrbState.RESEARCHING, OrbState.SEARCHING -> EyeActivity.WORKING
            OrbState.COMPLETED -> EyeActivity.DONE
            OrbState.ERROR -> EyeActivity.ERROR
            OrbState.IDLE -> EyeActivity.IDLE
        }
    }
    LaunchedEffect(state.orb, state.spoken, state.transcript.size) {
        mind.mood = when (state.orb) {
            // The feeling of the sentence being said right now.
            OrbState.SPEAKING -> EyeEmotion.of(state.spoken.ifEmpty { state.transcript.lastOrNull { !it.fromUser }?.text.orEmpty() })
            // While thinking: a reaction to what was just heard.
            OrbState.THINKING, OrbState.EXECUTING, OrbState.RESEARCHING, OrbState.SEARCHING ->
                EyeEmotion.hearing(state.transcript.lastOrNull { it.fromUser }?.text.orEmpty())
            OrbState.COMPLETED -> mind.mood
            OrbState.ERROR -> EyeMood.CONCERNED
            else -> EyeMood.NEUTRAL
        }
    }
    LaunchedEffect(state.speechPulse) { if (state.speechPulse > 0) mind.speechPulse() }
    LaunchedEffect(state.looking) { mind.attending = state.looking }
    // The iris colour you chose.
    val eyeStyle = com.melhive.zed.core.eyes.EyeStyle.parse(settings?.eyeStyle)
    LaunchedEffect(settings?.eyeColor, eyeStyle) { art.style = eyeStyle; mind.baseTint = com.melhive.zed.core.eyes.EyeColors.of(settings?.eyeColor, eyeStyle) }
    // "Thank you", "good night": a slow, contented closing of the eyes.
    LaunchedEffect(state.transcript.size) {
        val last = state.transcript.lastOrNull()
        if (last != null && last.fromUser && com.melhive.zed.core.eyes.KindWords.matches(last.text)) { mind.mood = EyeMood.HAPPY; mind.contentBlink() }
    }
    // Asleep: the screen itself dims until you come back, touch it or say "Zed".
    DisposableEffect(asleep) {
        val window = (context as? Activity)?.window
        window?.attributes = window?.attributes?.apply { screenBrightness = if (asleep) 0.03f else android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        onDispose { window?.attributes = window?.attributes?.apply { screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
    }
    DisposableEffect(asleep, state.active) {
        val wake = com.melhive.zed.voice.WakeWordService
        if (asleep && state.active) {
            // While the eyes sleep the microphone is free: the "Hey Zed" listener (when it is on) wakes them.
            wake.onWakeOverride = {
                view.post {
                    wake.paused.value = true
                    mind.wake()
                    if (live.state.value.orb == OrbState.IDLE) live.tapOrb()
                }
            }
            wake.paused.value = false
        }
        onDispose {
            wake.onWakeOverride = null
            if (live.state.value.active) wake.paused.value = true
        }
    }
    // While asleep: the time, and when the next reminder is due.
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(asleep) {
        while (asleep) {
            val now = System.currentTimeMillis()
            val time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("h:mm"))
            val next = runCatching { c.db.reminders().scheduled().filter { it.triggerAt > now }.minOfOrNull { it.triggerAt } }.getOrNull()
            clock = time + (next?.let { "\nReminder at " + java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("h:mm a")) } ?: "")
            delay(20_000)
        }
        clock = ""
    }

    // The picture ZED just looked at, shown for a moment so you know what the answer is about.
    var picture by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(state.shownTick) {
        val shown = state.shown
        if (state.shownTick == 0 || shown == null) return@LaunchedEffect
        picture = runCatching {
            val bytes = android.util.Base64.decode(shown.base64, android.util.Base64.NO_WRAP)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap()
        }.getOrNull()
        delay(5000)
        picture = null
    }

    // When the conversation has gone quiet and you are in view, ZED listens again by itself.
    LaunchedEffect(state.orb, state.micMuted) {
        if (state.orb != OrbState.IDLE || state.micMuted) return@LaunchedEffect
        delay(2500)
        while (true) {
            val now = live.state.value
            if (!now.active || now.orb != OrbState.IDLE || now.micMuted) break
            if (camera.faceSeen) { live.tapOrb(); break }
            delay(400)
        }
    }

    // One step of the eyes for every frame the screen draws.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1_000_000_000f)
                last = now
                mind.micLevel = live.state.value.micLevel
                mind.faceSeen = camera.faceSeen
                mind.faceX = camera.faceX; mind.faceY = camera.faceY
                mind.objectSeen = camera.objectSeen
                mind.objectX = camera.objectX; mind.objectY = camera.objectY
                // A dark room: turn the glow down.
                mind.dim += ((if (camera.running && camera.brightness < 45f) 1f else 0f) - mind.dim) * 0.03f
                mind.update(dt)
                if (mind.asleep != asleep) asleep = mind.asleep
                tick++
            }
        }
    }

    // The controls fade away a few seconds after the last touch.
    LaunchedEffect(controls, touched) { if (controls) { delay(5500); controls = false } }

    val path = remember { Path() }
    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTapGestures(
                onTap = { if (mind.asleep || mind.drowsy) mind.wake() else { controls = !controls; touched++ } },
                onDoubleTap = { live.tapOrb() },
            )
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (tick < 0) return@Canvas // reading the counter redraws this every frame
            art.draw(frame, size.width, size.height, mind.left, mind.right, mind.roll)
            drawEyes(frame, path)
        }

        if (asleep && clock.isNotEmpty()) Text(
            clock, color = Color.White.copy(alpha = 0.55f), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.displaySmall, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (sideways) 18.dp else 120.dp),
        )

        picture?.let { shown ->
            androidx.compose.foundation.Image(
                shown, "What ZED is looking at", contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.align(Alignment.BottomStart).padding(14.dp).size(if (sideways) 120.dp else 104.dp)
                    .clip(RoundedCornerShape(14.dp)).border(1.dp, ZedColors.Cyan.copy(alpha = 0.5f), RoundedCornerShape(14.dp)),
            )
        }

        if (state.voiceGuest) com.melhive.zed.ui.components.GuestPill(Modifier.align(Alignment.TopCenter).padding(top = 12.dp))

        // The experiment's test line: who ZED thinks it sees and hears, and how sure it is.
        if (recognising && testLine.isNotEmpty() && !asleep) Text(
            testLine, color = ZedColors.Muted.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall, maxLines = 2,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 12.dp).fillMaxWidth(0.6f),
        )

        // Captions: what is being heard or said, one quiet line under the eyes.
        val captions = settings?.eyeCaptions == true
        val line = when {
            state.orb == OrbState.ERROR -> state.error ?: state.statusDetail.orEmpty()
            !state.active && state.error != null -> state.error.orEmpty()
            !captions -> ""
            state.partial.isNotEmpty() -> state.partial
            state.orb == OrbState.SPEAKING -> state.spoken.trim()
            state.orb == OrbState.THINKING || state.orb == OrbState.EXECUTING || state.orb == OrbState.RESEARCHING || state.orb == OrbState.SEARCHING ->
                state.transcript.lastOrNull { it.fromUser }?.text.orEmpty()
            else -> ""
        }
        if (line.isNotEmpty()) Text(
            line, color = (if (state.orb == OrbState.ERROR) ZedColors.Red else ZedColors.Cyan).copy(alpha = 0.78f),
            style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center, maxLines = if (sideways) 2 else 4, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(if (sideways) 0.72f else 0.9f).padding(bottom = if (sideways) 14.dp else 48.dp)
                .pointerInput(Unit) { detectTapGestures(onTap = { history = true }) },
        )

        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopEnd)) {
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(12.dp)) {
                Column(Modifier.background(Color(0xCC0B111E), RoundedCornerShape(24.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        EyeButton(Icons.Outlined.ClosedCaption, if (captions) "Hide captions" else "Show captions", captions) {
                            touched++; scope.launch { c.settings.setEyeCaptions(!captions) }
                        }
                        EyeButton(if (state.micMuted) Icons.Outlined.MicOff else Icons.Outlined.Mic, if (state.micMuted) "Unmute microphone" else "Mute microphone", !state.micMuted) {
                            touched++; live.toggleMute()
                        }
                        EyeButton(if (cameraOn && cameraAllowed) Icons.Outlined.Videocam else Icons.Outlined.VideocamOff, if (cameraOn) "Turn the camera off" else "Turn the camera on", cameraOn && cameraAllowed) {
                            touched++
                            if (!cameraAllowed) permissions.launch(arrayOf(Manifest.permission.CAMERA)) else cameraOn = !cameraOn
                        }
                        EyeButton(Icons.Outlined.Cameraswitch, if (lookBack) "“What is this?” looks through the back camera" else "“What is this?” looks through the front camera", lookBack) {
                            touched++; scope.launch { c.settings.setEyeLookBack(!lookBack) }
                        }
                        EyeButton(Icons.Outlined.ScreenRotation, "Turn the screen", true) {
                            touched++
                            turned = if (sideways) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        EyeButton(Icons.Outlined.History, "Conversation so far", true) { controls = false; history = true }
                        EyeButton(Icons.Outlined.Pets, "Whose eyes: ${eyeStyle.label}", true) {
                            touched++; scope.launch { c.settings.setEyeStyle(com.melhive.zed.core.eyes.EyeStyle.next(settings?.eyeStyle).id) }
                        }
                        EyeButton(Icons.Outlined.Palette, "Eye colour", true) {
                            touched++; scope.launch { c.settings.setEyeColor(com.melhive.zed.core.eyes.EyeColors.next(settings?.eyeColor ?: "natural")) }
                        }
                        EyeButton(Icons.Outlined.Group, "People ZED recognises", recognising) { controls = false; peopleDialog = true }
                        EyeButton(Icons.Outlined.Close, "Close Eye mode", true) { close() }
                    }
                }
                com.melhive.zed.ui.components.VoiceModeSwitch("eyes", onSelect = {
                    if (it == "orb") onOpenLive() else { closing = true; onOpenChat(live.conversation) }
                }, modifier = Modifier.padding(top = 8.dp))
                Text(
                    "Double-tap to interrupt or talk", color = ZedColors.Muted.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp, end = 10.dp),
                )
            }
        }
        if (history) com.melhive.zed.ui.components.VoiceHistoryPanel(state.conversationId, state.transcript) { history = false }
    }

    if (peopleDialog) PeopleDialog(
        recognising = recognising, people = people, voiceId = voiceId, faceInView = { camera.faceSeen && camera.running },
        onDismiss = { peopleDialog = false },
    )
}

/** The recognition experiment's settings: switch it on, teach your face, get the voice model, and manage who ZED knows. */
@Composable
private fun PeopleDialog(recognising: Boolean, people: com.melhive.zed.eyes.EyePeople, voiceId: com.melhive.zed.eyes.VoiceId, faceInView: () -> Boolean, onDismiss: () -> Unit) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val version by c.people.version.collectAsStateWithLifecycle()
    val known = remember(version) { c.people.names() }
    var note by remember { mutableStateOf("") }
    var voiceReady by remember { mutableStateOf(voiceId.installed()) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var teaching by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) { while (true) { teaching = people.teaching; delay(300) } }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZedColors.SurfaceHigh,
        title = { Text("People ZED recognises") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Done") } },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Recognise people", style = MaterialTheme.typography.titleSmall)
                        Text("An experiment. ZED learns your face and voice, asks people it doesn't know for their name and greets them next time. It changes nothing about what ZED will do or say. Faces and voices stay on this phone.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                    }
                    androidx.compose.material3.Switch(checked = recognising, onCheckedChange = { on -> scope.launch { c.settings.setEyeRecognition(on) } })
                }
                if (recognising) {
                    androidx.compose.material3.TextButton(enabled = teaching < 0, onClick = {
                        if (!faceInView()) note = "Look at the screen with the camera on, then try again."
                        else scope.launch { note = "Keep looking at the eyes and turn your head a little…"; people.teach(runCatching { c.memory.userName() }.getOrNull() ?: "You") }
                    }) { Text(if (teaching >= 0) "Learning your face $teaching/${com.melhive.zed.eyes.EyePeople.TEACH}…" else if (c.people.hasOwner()) "Teach my face again" else "Teach my face") }
                    if (voiceReady) Text("Your voice: ${c.people.ownerVoices()} of ${com.melhive.zed.core.eyes.PeopleBook.MAX_VOICES} samples. ZED learns it from what you say while it sees your face (needs on-device speech recognition).", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                    else androidx.compose.material3.TextButton(enabled = downloading == null, onClick = {
                        scope.launch {
                            downloading = "Starting…"
                            runCatching { voiceId.download { done, total -> downloading = "Downloading ${done * 100 / total.coerceAtLeast(1)}%" } }
                                .onSuccess { voiceReady = true; note = "" }
                                .onFailure { note = "The download didn't finish: ${it.message ?: "no connection"}" }
                            downloading = null
                        }
                    }) { Text(downloading ?: "Download voice recognition (30 MB)") }
                    if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = ZedColors.Amber)
                }
                if (known.isNotEmpty()) Text("KNOWN", style = MaterialTheme.typography.labelSmall, color = ZedColors.Cyan, modifier = Modifier.padding(top = 10.dp))
                known.forEach { (id, name, owner) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        var edited by remember(id) { mutableStateOf(name) }
                        androidx.compose.material3.OutlinedTextField(
                            value = edited, onValueChange = { edited = it; if (it.isNotBlank()) c.people.rename(id, it.trim()) }, singleLine = true,
                            label = { Text(if (owner) "You" else "Name") }, modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { c.people.remove(id) }) { Icon(Icons.Outlined.Close, "Forget $name", tint = ZedColors.Red) }
                    }
                }
                if (known.size > 1) androidx.compose.material3.TextButton(onClick = { c.people.clear() }) { Text("Forget everyone", color = ZedColors.Red) }
            }
        },
    )
}

@Composable
private fun EyeButton(icon: ImageVector, label: String, lit: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(icon, label, tint = if (lit) ZedColors.Cyan else ZedColors.Muted)
    }
}

/** Draws a frame of eye shapes: fills, lines and clip regions, in order. */
private fun DrawScope.drawEyes(frame: EyeFrame, path: Path) {
    val canvas = drawContext.canvas
    val pts = frame.pts
    var clips = 0
    for (op in frame.ops) {
        if (op.kind == EyeFrame.UNCLIP) { if (clips > 0) { canvas.restore(); clips-- }; continue }
        path.rewind()
        path.moveTo(pts[op.start], pts[op.start + 1])
        for (i in 1 until op.count) path.lineTo(pts[op.start + i * 2], pts[op.start + i * 2 + 1])
        if (op.closed) path.close()
        when (op.kind) {
            EyeFrame.CLIP -> { canvas.save(); canvas.clipPath(path); clips++ }
            EyeFrame.FILL -> {
                val colors = op.colors
                val stops = op.stops
                if (colors != null && stops != null) {
                    val pairs = Array(colors.size) { stops[it] to Color(colors[it]) }
                    drawPath(path, Brush.radialGradient(*pairs, center = Offset(op.gx, op.gy), radius = op.gr.coerceAtLeast(1f)))
                } else drawPath(path, Color(op.color))
            }
            EyeFrame.STROKE -> drawPath(path, Color(op.color), style = Stroke(width = op.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
    repeat(clips) { canvas.restore() }
}
