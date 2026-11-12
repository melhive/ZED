package com.melhive.zed.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.zed.core.ui.PlasmaShape
import com.melhive.zed.ui.theme.ZedColors
import com.melhive.zed.voice.AudioLevels
import com.melhive.zed.voice.OrbState
import kotlin.math.sin

private const val POINTS = 72

/**
 * The plasma orb: a soft, living surface instead of a machine. The user's
 * voice ([micLevel]) energises it and sends ripples inward; ZED's own speech
 * (the real audio loudness, see [AudioLevels]) shapes the surface; while
 * thinking, small synapse flashes cross it. It draws at a low frame rate
 * while the model is working, so the two don't fight over the chip.
 */
@Composable
internal fun PlasmaOrb(state: OrbState, modifier: Modifier, micLevel: Float, speechPulse: Int, size: Dp, glow: Boolean, light: Boolean) {
    val primary by animateColorAsState(orbPrimary(state), tween(500), label = "p")
    val secondary by animateColorAsState(orbSecondary(state), tween(500), label = "s")
    val level by animateFloatAsState(if (state == OrbState.LISTENING) micLevel else 0f, spring(stiffness = 260f), label = "mic")
    val speech by AudioLevels.speech.collectAsStateWithLifecycle()
    val currentState by rememberUpdatedState(state)

    // Own frame clock, so the frame rate follows the state (light while thinking).
    var time by remember { mutableFloatStateOf(0f) }
    val shown = remember { FloatArray(24) }
    val target = remember { FloatArray(24) }
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(speechPulse) { if (speechPulse > 0) { pulse.snapTo(1f); pulse.animateTo(0f, tween(320, easing = FastOutSlowInEasing)) } }
    val done = remember { Animatable(0f) }
    LaunchedEffect(state) { if (state == OrbState.COMPLETED) { done.snapTo(0f); done.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) } else done.snapTo(0f) }

    LaunchedEffect(light) {
        var last = 0L
        var drawn = 0L
        while (true) withFrameNanos { now ->
            if (last == 0L) { last = now; drawn = now }
            val gap = 1_000_000_000L / PlasmaShape.framesPerSecond(currentState.name, light)
            if (now - drawn >= gap) {
                val dt = ((now - last) / 1e9f).coerceIn(0f, 0.2f)
                last = now; drawn = now
                // Follow the speech waveform; without one (phone voices) the word pulse stands in.
                val s = speech
                val fresh = currentState == OrbState.SPEAKING && System.currentTimeMillis() - s.at < 350 && s.bins.isNotEmpty()
                for (i in target.indices) target[i] = when {
                    fresh -> s.bins.getOrElse(i) { 0f }
                    currentState == OrbState.SPEAKING -> pulse.value * (0.35f + 0.25f * sin(i * 0.9f + time * 4f))
                    else -> 0f
                }
                PlasmaShape.follow(shown, target, dt)
                time += dt
            }
        }
    }

    val radii = remember { Array(3) { FloatArray(POINTS) } }
    val path = remember { Path() }
    Canvas(modifier.size(size)) {
        val t = time
        val c = center
        val base = this.size.minDimension * 0.27f
        val breathe = 1f + 0.025f * sin(t * 1.4f)
        val r = base * when (state) {
            OrbState.LISTENING -> 0.97f + 0.2f * level
            OrbState.ERROR -> 0.94f
            else -> breathe
        }
        val unit = density

        if (glow) {
            val halo = r * 2.3f
            drawCircle(Brush.radialGradient(listOf(primary.copy(alpha = 0.24f), primary.copy(alpha = 0.06f), Color.Transparent), c, halo), halo, c)
        }

        // Listening: rings travel inward, quicker and brighter with the voice.
        if (state == OrbState.LISTENING) for ((rad, alpha) in PlasmaShape.inwardRipples(t, level)) {
            drawCircle(primary.copy(alpha = alpha.coerceIn(0f, 1f)), r * (1.02f + 0.75f * rad), c, style = Stroke(1.4f * unit))
        }

        // The plasma: three soft layers, each with its own drift.
        val wave = if (state == OrbState.SPEAKING) shown else null
        val mic = if (state == OrbState.LISTENING) level else 0f
        val spin = t * 0.12f
        val layers = listOf(Triple(1.08f, secondary, 0.42f), Triple(1.0f, primary, 0.5f), Triple(0.84f, lerpWhite(primary, 0.35f), 0.4f))
        layers.forEachIndexed { k, (scale, color, alpha) ->
            PlasmaShape.radii(POINTS, t, mic, wave, layer = k, out = radii[k])
            blob(path, PlasmaShape.ringPoints(radii[k], c.x, c.y, r * scale, spin * (if (k == 1) -1f else 1f)))
            drawPath(path, Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.45f), Color.Transparent), c, r * scale * 1.25f))
        }
        // The rim of the middle layer, so the surface reads clearly.
        blob(path, PlasmaShape.ringPoints(radii[1], c.x, c.y, r, -spin))
        drawPath(path, Brush.sweepGradient(listOf(primary.copy(alpha = 0.75f), secondary.copy(alpha = 0.55f), primary.copy(alpha = 0.75f)), c), style = Stroke(1.3f * unit))

        // Thinking: brief synapse flashes inside the orb, linked by faint threads.
        if (state == OrbState.THINKING || state == OrbState.RESEARCHING || state == OrbState.SEARCHING) {
            val syn = PlasmaShape.synapses(t)
            syn.zipWithNext().forEach { (a, b) ->
                drawLine(Color.White.copy(alpha = 0.22f * minOf(a.third, b.third)), Offset(c.x + a.first * r, c.y + a.second * r), Offset(c.x + b.first * r, c.y + b.second * r), 0.8f * unit)
            }
            syn.forEach { (x, y, b) ->
                val p = Offset(c.x + x * r, c.y + y * r)
                drawCircle(Color.White.copy(alpha = 0.18f * b), 6f * unit * b, p)
                drawCircle(Color.White.copy(alpha = 0.85f * b), 1.6f * unit, p)
            }
        }

        // The heart: brighter with energy.
        val energy = when (state) { OrbState.LISTENING -> 0.3f + level; OrbState.SPEAKING -> 0.4f + shown.average().toFloat() * 1.5f + 0.3f * pulse.value; else -> 0.3f }.coerceIn(0f, 1.2f)
        val heart = r * (0.24f + 0.08f * energy)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f), primary.copy(alpha = 0.55f), Color.Transparent), c, heart * 1.8f), heart * 1.8f, c)
        drawCircle(Color.White.copy(alpha = 0.9f), heart * 0.32f, c)

        when (state) {
            OrbState.EXECUTING -> {
                val rr = r * 1.32f
                val stroke = Stroke(1.6f * unit, cap = StrokeCap.Round)
                drawArc(primary.copy(alpha = 0.85f), t * 160f, 100f, false, Offset(c.x - rr, c.y - rr), Size(rr * 2, rr * 2), style = stroke)
                drawArc(secondary.copy(alpha = 0.7f), -t * 110f + 180f, 70f, false, Offset(c.x - rr * 1.12f, c.y - rr * 1.12f), Size(rr * 2.24f, rr * 2.24f), style = stroke)
            }
            OrbState.RESEARCHING, OrbState.SEARCHING -> {
                val y = c.y - r + 2 * r * ((t * 0.6f) % 1f)
                val half = kotlin.math.sqrt((r * r - (y - c.y) * (y - c.y)).coerceAtLeast(0f))
                drawLine(primary.copy(alpha = 0.8f), Offset(c.x - half, y), Offset(c.x + half, y), 1.4f * unit)
            }
            OrbState.COMPLETED -> {
                drawCircle(primary.copy(alpha = 1f - done.value), r * (1f + 0.6f * done.value), c, style = Stroke(2f * unit))
                check(c, r * 0.32f, done.value.coerceAtMost(1f), unit)
            }
            OrbState.ERROR -> drawCircle(primary.copy(alpha = 0.6f), r * 1.22f, c, style = Stroke(1.6f * unit, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 9f))))
            else -> Unit
        }
    }
}

/** A smooth closed curve through a ring of points (quadratic segments between midpoints). */
private fun blob(path: Path, xy: FloatArray) {
    path.reset()
    val n = xy.size / 2
    fun mx(i: Int) = (xy[2 * (i % n)] + xy[2 * ((i + 1) % n)]) / 2f
    fun my(i: Int) = (xy[2 * (i % n) + 1] + xy[2 * ((i + 1) % n) + 1]) / 2f
    path.moveTo(mx(n - 1), my(n - 1))
    for (i in 0 until n) path.quadraticTo(xy[2 * i], xy[2 * i + 1], mx(i), my(i))
    path.close()
}

private fun DrawScope.check(c: Offset, s: Float, progress: Float, unit: Float) {
    if (progress <= 0f) return
    val p = Path().apply {
        moveTo(c.x - s * 0.6f, c.y)
        lineTo(c.x - s * 0.15f, c.y + s * 0.45f)
        lineTo(c.x + s * 0.65f, c.y - s * 0.45f)
    }
    drawPath(p, Color.White.copy(alpha = progress), style = Stroke(2.4f * unit, cap = StrokeCap.Round))
}

private fun lerpWhite(c: Color, f: Float) = Color(c.red + (1 - c.red) * f, c.green + (1 - c.green) * f, c.blue + (1 - c.blue) * f, c.alpha)

internal fun orbPrimary(s: OrbState) = when (s) {
    OrbState.IDLE, OrbState.LISTENING -> ZedColors.Cyan
    OrbState.THINKING -> ZedColors.Violet
    OrbState.SPEAKING -> ZedColors.Teal
    OrbState.EXECUTING -> ZedColors.Amber
    OrbState.RESEARCHING, OrbState.SEARCHING -> Color(0xFF4DA8FF)
    OrbState.COMPLETED -> ZedColors.Green
    OrbState.ERROR -> ZedColors.Red
}

internal fun orbSecondary(s: OrbState) = when (s) {
    OrbState.ERROR -> Color(0xFFFF9A3D)
    OrbState.COMPLETED -> ZedColors.Teal
    OrbState.EXECUTING -> Color(0xFFFF6B3D)
    else -> ZedColors.Violet
}
