package com.melhive.zed.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.melhive.zed.ui.theme.ZedColors
import com.melhive.zed.voice.LiveUiState
import com.melhive.zed.voice.OrbState
import kotlin.math.cos
import kotlin.math.sin

fun hudColor(o: OrbState): Color = when (o) {
    OrbState.LISTENING -> ZedColors.Cyan
    OrbState.THINKING, OrbState.SEARCHING, OrbState.RESEARCHING -> ZedColors.Violet
    OrbState.SPEAKING -> ZedColors.Teal
    OrbState.EXECUTING -> ZedColors.Amber
    OrbState.ERROR -> ZedColors.Red
    else -> ZedColors.Cyan
}

/**
 * Arc-reactor style rings around the orb: rotating tick ring, counter-rotating
 * arcs. While the model is working (or with [animate] off) the rings hold
 * still, so the screen leaves the graphics chip to the model.
 */
@Composable
fun JarvisHud(state: LiveUiState, modifier: Modifier = Modifier, animate: Boolean = true) {
    val working = state.orb in setOf(OrbState.THINKING, OrbState.SEARCHING, OrbState.RESEARCHING)
    if (animate && !working) AnimatedHud(state, modifier) else HudRings(state, modifier, 0f, 0f, 0f, 1f, false)
}

@Composable
private fun AnimatedHud(state: LiveUiState, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "hud")
    val slow by t.animateFloat(0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "slow")
    val fast by t.animateFloat(360f, 0f, infiniteRepeatable(tween(7_000, easing = LinearEasing)), label = "fast")
    val scan by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1_600, easing = LinearEasing)), label = "scan")
    val breathe by t.animateFloat(0.85f, 1f, infiniteRepeatable(tween(1_400), RepeatMode.Reverse), label = "breathe")
    HudRings(state, modifier, slow, fast, scan, breathe, state.orb == OrbState.EXECUTING)
}

@Composable
private fun HudRings(state: LiveUiState, modifier: Modifier, slow: Float, fast: Float, scan: Float, breathe: Float, thinking: Boolean) {
    val color by animateColorAsState(hudColor(state.orb), tween(400), label = "color")
    val energy by animateFloatAsState(
        when (state.orb) { OrbState.LISTENING -> 0.35f + state.micLevel; OrbState.SPEAKING -> 0.8f + (state.speechPulse % 2) * 0.2f; else -> 0.4f },
        tween(180), label = "energy",
    )

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
