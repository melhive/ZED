package com.melhive.zenith.assist

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.zenith.ZenithApp
import com.melhive.zenith.android.PermissionCatalog
import com.melhive.zenith.intelligence.ImageInput
import com.melhive.zenith.ui.components.ZenithOrb
import com.melhive.zenith.ui.theme.ZenithColors
import com.melhive.zenith.ui.theme.ZenithTheme
import com.melhive.zenith.voice.LiveUiState
import com.melhive.zenith.voice.OrbState
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * The assistant overlay: opened by the assist gesture, "Hey Zenith" or the
 * tile. It floats over whatever app is open — the orb inside a HUD of moving
 * rings, a live transcript and LIVE's controls — and closes back to that app.
 */
class AssistOverlayActivity : ComponentActivity() {
    /** True while the camera app is open for "what am I looking at", so leaving doesn't end the session. */
    @Volatile var keepAlive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        enableEdgeToEdge()
        val live = (application as ZenithApp).container.live
        if (!PermissionCatalog.isGranted(this, PermissionCatalog.MICROPHONE)) {
            // The full app asks for the microphone permission.
            startActivity(ZenithLaunch.intent(this, ZenithLaunch.ROUTE_LIVE)); finish(); return
        }
        live.start()
        setContent { ZenithTheme { AssistOverlay(onClose = ::close, onOpenApp = ::openApp) } }
    }

    private fun close() {
        (application as ZenithApp).container.live.end()
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, android.R.anim.fade_out)
    }

    private fun openApp() {
        (application as ZenithApp).container.live.end()
        startActivity(ZenithLaunch.intent(this, ZenithLaunch.ROUTE_LIVE))
        finish()
    }

    override fun onStop() {
        super.onStop()
        // Leaving the overlay ends the session (the microphone is never kept in the background).
        if (!keepAlive && !isChangingConfigurations && !isFinishing) close()
    }
}

@Composable
private fun AssistOverlay(onClose: () -> Unit, onOpenApp: () -> Unit) {
    val activity = androidx.compose.ui.platform.LocalContext.current as AssistOverlayActivity
    val c = (activity.application as ZenithApp).container
    val live = c.live
    val state by live.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    var captureUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        activity.keepAlive = false
        val uri = captureUri
        if (ok && uri != null) scope.launch {
            runCatching { ImageInput.fromUri(activity, uri) }.onSuccess { live.submitImage(it, "What am I looking at?") }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Dim the app behind, more toward the bottom; tapping the clear area closes.
        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color(0x8805070D), 1f to Color(0xF205070D)))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        )
        AnimatedVisibility(shown, enter = slideInVertically(tween(420)) { it / 2 }, modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(300.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { live.tapOrb() }, contentAlignment = Alignment.Center) {
                    JarvisHud(state, Modifier.fillMaxSize())
                    ZenithOrb(state.orb, micLevel = state.micLevel, speechPulse = state.speechPulse, size = 150.dp, glow = true)
                }
                Text(
                    (state.statusDetail?.takeIf { state.orb == OrbState.ERROR || state.orb == OrbState.EXECUTING } ?: state.orb.label).uppercase(),
                    style = MaterialTheme.typography.labelMedium, color = hudColor(state.orb), letterSpacing = 3.sp,
                )
                val lastUser = state.partial.ifBlank { state.transcript.lastOrNull { it.fromUser }?.text.orEmpty() }
                val lastZenith = state.transcript.lastOrNull { !it.fromUser }?.text.orEmpty()
                if (lastUser.isNotBlank()) Text("“$lastUser”", style = MaterialTheme.typography.bodyMedium, color = ZenithColors.Muted, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                if (lastZenith.isNotBlank() && (state.transcript.lastOrNull()?.fromUser == false)) Text(lastZenith, style = MaterialTheme.typography.bodyLarge, color = ZenithColors.Text,
                    textAlign = TextAlign.Center, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                if (typing) OutlinedTextField(
                    value = typed, onValueChange = { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    placeholder = { Text("Type to ZENITH") },
                    trailingIcon = { IconButton(onClick = { live.submitText(typed); typed = ""; typing = false }) { Icon(Icons.AutoMirrored.Outlined.Send, "Send") } },
                )
                Spacer(Modifier.size(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    HudButton(if (state.micMuted) Icons.Outlined.MicOff else Icons.Outlined.Mic, if (state.micMuted) "Unmute" else "Mute", live::toggleMute)
                    HudButton(if (state.speakerOn) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff, "Voice replies", live::toggleSpeaker)
                    HudButton(Icons.Outlined.PhotoCamera, "What am I looking at?") {
                        runCatching { ImageInput.newCaptureUri(activity) }.onSuccess { activity.keepAlive = true; captureUri = it; camera.launch(it) }
                    }
                    HudButton(Icons.Outlined.Keyboard, "Type") { typing = !typing }
                    HudButton(Icons.Outlined.StopCircle, "Stop", live::cancelRequest)
                    HudButton(Icons.Outlined.OpenInFull, "Open ZENITH", onOpenApp)
                    IconButton(onClick = onClose, modifier = Modifier.size(52.dp),
                        colors = IconButtonDefaults.iconButtonColors(containerColor = ZenithColors.Red.copy(alpha = 0.85f), contentColor = Color.White)) {
                        Icon(Icons.Outlined.Close, "Close")
                    }
                }
            }
        }
    }
}

@Composable
private fun HudButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(46.dp),
        colors = IconButtonDefaults.iconButtonColors(containerColor = ZenithColors.Cyan.copy(alpha = 0.12f), contentColor = ZenithColors.Cyan)) {
        Icon(icon, description)
    }
}

private fun hudColor(o: OrbState): Color = when (o) {
    OrbState.LISTENING -> ZenithColors.Cyan
    OrbState.THINKING, OrbState.SEARCHING, OrbState.RESEARCHING -> ZenithColors.Violet
    OrbState.SPEAKING -> ZenithColors.Teal
    OrbState.EXECUTING -> ZenithColors.Amber
    OrbState.ERROR -> ZenithColors.Red
    else -> ZenithColors.Cyan
}

/** Arc-reactor style rings around the orb: rotating tick ring, counter-rotating arcs, a scanner while thinking. */
@Composable
fun JarvisHud(state: LiveUiState, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "hud")
    val slow by t.animateFloat(0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "slow")
    val fast by t.animateFloat(360f, 0f, infiniteRepeatable(tween(7_000, easing = LinearEasing)), label = "fast")
    val scan by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1_600, easing = LinearEasing)), label = "scan")
    val breathe by t.animateFloat(0.85f, 1f, infiniteRepeatable(tween(1_400), RepeatMode.Reverse), label = "breathe")
    val color by animateColorAsState(hudColor(state.orb), tween(400), label = "color")
    val energy by animateFloatAsState(
        when (state.orb) { OrbState.LISTENING -> 0.35f + state.micLevel; OrbState.SPEAKING -> 0.8f + (state.speechPulse % 2) * 0.2f; else -> 0.4f },
        tween(180), label = "energy",
    )
    val thinking = state.orb in setOf(OrbState.THINKING, OrbState.SEARCHING, OrbState.RESEARCHING, OrbState.EXECUTING)

    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        // Outer dashed ring.
        drawCircle(color.copy(alpha = 0.25f), r * 0.98f, c, style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 10f))))
        // Rotating tick ring.
        rotate(slow, c) {
            for (i in 0 until 72) {
                val a = Math.toRadians(i * 5.0)
                val long = i % 6 == 0
                val inner = r * (if (long) 0.80f else 0.84f)
                val outer = r * 0.88f
                drawLine(color.copy(alpha = if (long) 0.8f else 0.35f), Offset(c.x + inner * cos(a).toFloat(), c.y + inner * sin(a).toFloat()),
                    Offset(c.x + outer * cos(a).toFloat(), c.y + outer * sin(a).toFloat()), strokeWidth = if (long) 2.5f else 1.2f)
            }
        }
        // Counter-rotating arc segments; thicker with voice energy.
        val arcR = r * 0.72f
        rotate(fast, c) {
            for (k in 0 until 3) drawArc(
                color.copy(alpha = 0.55f * breathe), startAngle = k * 120f, sweepAngle = 70f, useCenter = false,
                topLeft = Offset(c.x - arcR, c.y - arcR), size = Size(arcR * 2, arcR * 2), style = Stroke(3f + 9f * energy, cap = StrokeCap.Round),
            )
        }
        // Inner thin ring.
        drawCircle(color.copy(alpha = 0.4f), r * 0.62f, c, style = Stroke(1.2f))
        // Scanner sweep while working.
        if (thinking) rotate(scan, c) {
            drawArc(Brush.sweepGradient(listOf(Color.Transparent, color.copy(alpha = 0.45f)), c), 0f, 60f, true,
                topLeft = Offset(c.x - r * 0.9f, c.y - r * 0.9f), size = Size(r * 1.8f, r * 1.8f))
        }
        // Small markers at the cardinal points.
        for (i in 0 until 4) {
            val a = Math.toRadians(i * 90.0 + 45)
            drawCircle(color.copy(alpha = 0.9f), 3.5f, Offset(c.x + r * 0.94f * cos(a).toFloat(), c.y + r * 0.94f * sin(a).toFloat()))
        }
    }
}
