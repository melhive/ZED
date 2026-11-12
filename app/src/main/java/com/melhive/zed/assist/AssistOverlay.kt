package com.melhive.zed.assist

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
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.melhive.zed.ZedApp
import com.melhive.zed.android.PermissionCatalog
import com.melhive.zed.intelligence.ImageInput
import com.melhive.zed.ui.components.ZedOrb
import com.melhive.zed.ui.components.JarvisHud
import com.melhive.zed.ui.components.hudColor
import com.melhive.zed.ui.theme.ZedColors
import com.melhive.zed.ui.theme.ZedTheme
import com.melhive.zed.voice.LiveUiState
import com.melhive.zed.voice.OrbState
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * The assistant overlay: opened by the assist gesture, "Hey Zed" or the
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
        val live = (application as ZedApp).container.live
        if (!PermissionCatalog.isGranted(this, PermissionCatalog.MICROPHONE)) {
            // The full app asks for the microphone permission.
            startActivity(ZedLaunch.intent(this, ZedLaunch.ROUTE_LIVE)); finish(); return
        }
        live.start()
        setContent { ZedTheme { AssistOverlay(onClose = ::close, onOpenApp = ::openApp) } }
    }

    private fun close() {
        (application as ZedApp).container.live.end()
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, android.R.anim.fade_out)
    }

    private fun openApp() {
        (application as ZedApp).container.live.end()
        startActivity(ZedLaunch.intent(this, ZedLaunch.ROUTE_LIVE))
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
    val c = (activity.application as ZedApp).container
    val live = c.live
    val state by live.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    var captureUri by remember { mutableStateOf<android.net.Uri?>(null) }
    fun askAbout(uri: android.net.Uri) = scope.launch {
        runCatching { ImageInput.fromUri(activity, uri) }.onSuccess { live.submitImage(it, "What is this?") }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        activity.keepAlive = false
        val uri = captureUri
        if (ok && uri != null) askAbout(uri)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        activity.keepAlive = false
        uri?.let { askAbout(it) }
    }
    var photoMenu by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        // Dim the app behind; tapping the empty area closes.
        Box(
            Modifier.fillMaxSize()
                .background(Brush.radialGradient(listOf(Color(0xE6070B16), Color(0xF205070D))))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        )
        AnimatedVisibility(shown, enter = androidx.compose.animation.scaleIn(tween(420), initialScale = 0.85f), modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("ZED", style = MaterialTheme.typography.titleMedium, color = ZedColors.Cyan, letterSpacing = 8.sp, modifier = Modifier.padding(top = 8.dp))
                com.melhive.zed.ui.screens.MiniBrain(Modifier.padding(top = 6.dp).size(64.dp))
                Spacer(Modifier.weight(1f))
                // The orb in the middle of the screen, as large as the screen allows.
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val hud = minOf(maxWidth * 0.88f, 380.dp)
                    Box(Modifier.size(hud).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { live.tapOrb() }, contentAlignment = Alignment.Center) {
                        JarvisHud(state, Modifier.fillMaxSize())
                        ZedOrb(state.orb, micLevel = state.micLevel, speechPulse = state.speechPulse, size = hud * 0.58f, glow = true)
                    }
                }
                Text(
                    (state.statusDetail?.takeIf { state.orb == OrbState.ERROR || state.orb == OrbState.EXECUTING } ?: state.orb.label).uppercase(),
                    style = MaterialTheme.typography.labelLarge, color = hudColor(state.orb), letterSpacing = 4.sp, textAlign = TextAlign.Center,
                )
                val lastUser = state.partial.ifBlank { state.transcript.lastOrNull { it.fromUser }?.text.orEmpty() }
                val lastZed = state.transcript.lastOrNull { !it.fromUser }?.text.orEmpty()
                if (lastUser.isNotBlank()) Text("“$lastUser”", style = MaterialTheme.typography.bodyMedium, color = ZedColors.Muted, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                if (lastZed.isNotBlank() && (state.transcript.lastOrNull()?.fromUser == false)) Text(lastZed, style = MaterialTheme.typography.bodyLarge, color = ZedColors.Text,
                    textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.weight(1f))
                if (typing) OutlinedTextField(
                    value = typed, onValueChange = { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    placeholder = { Text("Type to ZED") },
                    trailingIcon = { IconButton(onClick = { live.submitText(typed); typed = ""; typing = false }) { Icon(Icons.AutoMirrored.Outlined.Send, "Send") } },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    HudButton(if (state.micMuted) Icons.Outlined.MicOff else Icons.Outlined.Mic, if (state.micMuted) "Unmute" else "Mute", live::toggleMute)
                    HudButton(if (state.speakerOn) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff, "Voice replies", live::toggleSpeaker)
                    Box {
                        HudButton(Icons.Outlined.PhotoCamera, "Ask about a photo") { photoMenu = true }
                        androidx.compose.material3.DropdownMenu(expanded = photoMenu, onDismissRequest = { photoMenu = false }) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text("Take a photo") }, onClick = {
                                photoMenu = false
                                runCatching { ImageInput.newCaptureUri(activity) }.onSuccess { activity.keepAlive = true; captureUri = it; camera.launch(it) }
                            })
                            androidx.compose.material3.DropdownMenuItem(text = { Text("Choose from gallery") }, onClick = {
                                photoMenu = false
                                activity.keepAlive = true
                                gallery.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            })
                        }
                    }
                    HudButton(Icons.Outlined.Keyboard, "Type") { typing = !typing }
                    HudButton(Icons.Outlined.StopCircle, "Stop", live::cancelRequest)
                    HudButton(Icons.Outlined.OpenInFull, "Open ZED", onOpenApp)
                    IconButton(onClick = onClose, modifier = Modifier.size(52.dp),
                        colors = IconButtonDefaults.iconButtonColors(containerColor = ZedColors.Red.copy(alpha = 0.85f), contentColor = Color.White)) {
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
        colors = IconButtonDefaults.iconButtonColors(containerColor = ZedColors.Cyan.copy(alpha = 0.12f), contentColor = ZedColors.Cyan)) {
        Icon(icon, description)
    }
}
