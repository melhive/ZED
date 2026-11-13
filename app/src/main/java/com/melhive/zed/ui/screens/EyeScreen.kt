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
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.GraphicEq
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
 * Eye mode: ZED's two eyes on a black landscape screen. It is the LIVE voice
 * session with a different face, so everything said is saved to the same
 * conversation. The camera lets the eyes follow the user and look at what is
 * shown; it runs only while this screen is visible.
 *
 * Tap: show the controls. Double-tap: interrupt ZED or start talking.
 */
@Composable
fun EyeScreen(onClose: () -> Unit, onOpenChat: (Long) -> Unit, onOpenLive: () -> Unit) {
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
    var cameraAllowed by remember { mutableStateOf(PermissionCatalog.isGranted(context, PermissionCatalog.CAMERA)) }
    var cameraOn by remember { mutableStateOf(true) }
    var controls by remember { mutableStateOf(false) }
    var touched by remember { mutableIntStateOf(0) }
    var closing by remember { mutableStateOf(false) }

    // Landscape, nothing but the eyes, and the screen stays on.
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val before = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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

    // Microphone for the conversation, camera for the eyes. Without the camera the eyes still work; they just can't see.
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.CAMERA] == true) cameraAllowed = true
        if (granted[Manifest.permission.RECORD_AUDIO] == true || PermissionCatalog.isGranted(context, PermissionCatalog.MICROPHONE)) live.start()
    }
    LaunchedEffect(Unit) {
        val mic = PermissionCatalog.isGranted(context, PermissionCatalog.MICROPHONE)
        if (mic) live.start()
        val missing = buildList { if (!mic) add(Manifest.permission.RECORD_AUDIO); if (!cameraAllowed) add(Manifest.permission.CAMERA) }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }
    // Opened by voice from the floating orb: that session ends as the orb closes, so start a fresh one here.
    LaunchedEffect(state.active) {
        if (!state.active && !closing) {
            delay(700)
            if (!live.state.value.active && !closing && PermissionCatalog.isGranted(context, PermissionCatalog.MICROPHONE)) live.start()
        }
    }
    // The same session as LIVE: it ends when the last voice screen closes, not when switching between them.
    DisposableEffect(Unit) { c.voiceScreens.incrementAndGet(); onDispose { if (c.voiceScreens.decrementAndGet() <= 0) live.end() } }

    // The camera runs only while Eye mode is on screen.
    LifecycleStartEffect(cameraAllowed, cameraOn) {
        if (cameraAllowed && cameraOn) {
            camera.start()
            live.frameSource = { camera.capture() }
        }
        onStopOrDispose {
            live.frameSource = null
            camera.stop()
        }
    }

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
                mind.update(dt)
                tick++
            }
        }
    }

    // The controls fade away a few seconds after the last touch.
    LaunchedEffect(controls, touched) { if (controls) { delay(4500); controls = false } }

    val path = remember { Path() }
    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTapGestures(
                onTap = { controls = !controls; touched++ },
                onDoubleTap = { live.tapOrb() },
            )
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (tick < 0) return@Canvas // reading the counter redraws this every frame
            art.draw(frame, size.width, size.height, mind.left, mind.right, mind.roll)
            drawEyes(frame, path)
        }

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
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.72f).padding(bottom = 14.dp),
        )

        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopEnd)) {
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(12.dp)) {
                Row(
                    Modifier.background(Color(0xCC0B111E), RoundedCornerShape(28.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
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
                    EyeButton(Icons.AutoMirrored.Outlined.Chat, "Chat mode", true) { closing = true; live.conversation?.let(onOpenChat) ?: onClose() }
                    EyeButton(Icons.Outlined.GraphicEq, "Live mode", true) { onOpenLive() }
                    EyeButton(Icons.Outlined.Close, "Close Eye mode", true) { close() }
                }
                Text(
                    "Double-tap to interrupt or talk", color = ZedColors.Muted.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp, end = 10.dp),
                )
            }
        }
    }
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
