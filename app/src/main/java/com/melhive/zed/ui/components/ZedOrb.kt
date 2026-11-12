package com.melhive.zed.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.zed.ui.theme.ZedColors
import com.melhive.zed.voice.OrbState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private fun primaryFor(s: OrbState) = when (s) {
    OrbState.IDLE -> ZedColors.Cyan
    OrbState.LISTENING -> ZedColors.Cyan
    OrbState.THINKING -> ZedColors.Violet
    OrbState.SPEAKING -> ZedColors.Teal
    OrbState.EXECUTING -> ZedColors.Amber
    OrbState.RESEARCHING, OrbState.SEARCHING -> Color(0xFF4DA8FF)
    OrbState.COMPLETED -> ZedColors.Green
    OrbState.ERROR -> ZedColors.Red
}

private fun secondaryFor(s: OrbState) = when (s) {
    OrbState.ERROR -> Color(0xFFFF9A3D)
    OrbState.COMPLETED -> ZedColors.Teal
    OrbState.EXECUTING -> Color(0xFFFF6B3D)
    else -> ZedColors.Violet
}

/**
 * An angle in degrees that keeps growing at [degreesPerSecond] for as long as the composable is shown.
 * It wraps at 360,000° (1,000 turns), so any element that rotates at a multiple like 0.7× or 1.07×
 * of it is still at a whole number of turns when it wraps — the motion stays seamless.
 */
@Composable
fun rememberContinuousRotation(degreesPerSecond: Float): Float {
    val speed by androidx.compose.runtime.rememberUpdatedState(degreesPerSecond)
    var angle by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        // withInfiniteAnimationFrameNanos (not withFrameNanos): an endless animation that UI tests can pause.
        var last = androidx.compose.animation.core.withInfiniteAnimationFrameNanos { it }
        while (true) {
            androidx.compose.animation.core.withInfiniteAnimationFrameNanos { now ->
                angle = (angle + (now - last) / 1_000_000_000f * speed) % 360_000f
                last = now
            }
        }
    }
    return angle
}

/**
 * The ZED orb: an arc reactor. Everything that moves follows something real:
 * [micLevel] (the user's voice) sends rings inward, ZED's own speech drives
 * the tick ring around it (the actual audio loudness from on-device voices,
 * see [AudioLevels]; the word pulse [speechPulse] for phone voices), and
 * [state] follows the assistant. It draws fewer frames while the model is
 * thinking, and in [light] mode, so it never competes with Gemma for the chip.
 */
@Composable
fun ZedOrb(
    state: OrbState,
    modifier: Modifier = Modifier,
    micLevel: Float = 0f,
    speechPulse: Int = 0,
    size: Dp = 240.dp,
    glow: Boolean = true,
    light: Boolean = false,
) {
    val primary by animateColorAsState(primaryFor(state), tween(500), label = "primary")
    val secondary by animateColorAsState(secondaryFor(state), tween(500), label = "secondary")
    val level by animateFloatAsState(if (state == OrbState.LISTENING) micLevel else 0f, spring(stiffness = 300f), label = "level")
    val speech by com.melhive.zed.voice.AudioLevels.speech.collectAsStateWithLifecycle()
    val currentState by androidx.compose.runtime.rememberUpdatedState(state)
    val showGlow = glow && !light

    // Speaking pulse: each word boundary kicks it, then it decays.
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(speechPulse) { if (speechPulse > 0) { pulse.snapTo(1f); pulse.animateTo(0f, tween(320, easing = FastOutSlowInEasing)) } }
    // Completion: a one-shot expanding ring and a check.
    val done = remember { Animatable(0f) }
    LaunchedEffect(state) { if (state == OrbState.COMPLETED) { done.snapTo(0f); done.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) } else done.snapTo(0f) }

    // One clock for everything that moves. Rotation is accumulated (never jumps when its speed changes),
    // and the frame rate follows the state: lighter while thinking, lightest in Light mode.
    var time by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var spin by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val voice = remember { FloatArray(24) }
    val target = remember { FloatArray(24) }
    LaunchedEffect(light) {
        var last = 0L
        while (true) androidx.compose.animation.core.withInfiniteAnimationFrameNanos { now ->
            if (last == 0L) last = now
            val fps = com.melhive.zed.core.ui.PlasmaShape.framesPerSecond(currentState.name, light)
            if (now - last >= 1_000_000_000L / fps) {
                val dt = ((now - last) / 1e9f).coerceIn(0f, 0.2f)
                last = now
                time += dt
                spin = (spin + dt * when (currentState) { OrbState.EXECUTING -> 200f; OrbState.THINKING -> 90f; else -> 60f }) % 360_000f
                // ZED's voice: the real loudness when the voice shares it, else the word pulse.
                val s = speech
                val fresh = currentState == OrbState.SPEAKING && System.currentTimeMillis() - s.at < 350 && s.bins.isNotEmpty()
                for (i in target.indices) target[i] = when {
                    fresh -> s.bins.getOrElse(i) { 0f }
                    currentState == OrbState.SPEAKING -> pulse.value * (0.45f + 0.3f * sin(i * 1.3f + time * 5f))
                    else -> 0f
                }
                com.melhive.zed.core.ui.PlasmaShape.follow(voice, target, dt)
            }
        }
    }

    Canvas(modifier.size(size)) {
        val t = time
        val breathe = 0.5f + 0.5f * sin(t * 0.87f)
        val c = center
        val baseR = this.size.minDimension * 0.26f
        val floatY = if (state == OrbState.IDLE) (breathe - 0.5f) * this.size.minDimension * 0.03f else 0f
        val center = Offset(c.x, c.y + floatY)
        val voiceLevel = voice.average().toFloat()
        val scale = when (state) {
            OrbState.IDLE -> 0.97f + 0.06f * breathe
            OrbState.LISTENING -> 0.95f + 0.3f * level
            OrbState.SPEAKING -> 1f + 0.06f * voiceLevel + 0.04f * pulse.value
            OrbState.ERROR -> 0.94f
            else -> 1f + 0.02f * breathe
        }
        val r = baseR * scale

        if (showGlow) {
            drawCircle(Brush.radialGradient(listOf(primary.copy(alpha = 0.26f), primary.copy(alpha = 0.06f), Color.Transparent), center, r * 2.5f), r * 2.5f, center)
        }

        when (state) {
            // Listening: rings travel inward, faster and brighter with the voice.
            OrbState.LISTENING -> for ((rad, alpha) in com.melhive.zed.core.ui.PlasmaShape.inwardRipples(t, level)) {
                drawCircle(primary.copy(alpha = alpha.coerceIn(0f, 1f)), r * (1.05f + 0.7f * rad), center, style = Stroke(1.6.dp.toPx()))
            }
            OrbState.THINKING -> drawParticles(center, r * 1.45f, spin, primary, secondary, 14)
            OrbState.EXECUTING -> drawRings(center, r, spin, primary, secondary)
            OrbState.RESEARCHING, OrbState.SEARCHING -> drawScan(center, r, (t / 1.6f) % 1f, spin, primary)
            // Speaking: a ring of fine ticks shaped by ZED's voice (mirrored left and right).
            OrbState.SPEAKING -> drawVoiceRing(center, r * 1.16f, voice, primary)
            OrbState.COMPLETED -> drawCircle(primary.copy(alpha = 1f - done.value), r * (1f + 0.8f * done.value), center, style = Stroke(2.5.dp.toPx()))
            OrbState.ERROR -> drawCircle(primary.copy(alpha = 0.6f), r * 1.25f, center, style = Stroke(2.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
            OrbState.IDLE -> drawCircle(primary.copy(alpha = 0.16f + 0.1f * breathe), r * 1.3f, center, style = Stroke(1.dp.toPx()))
        }

        // Orbiting sparks: always alive, faster while working, brighter with the voice.
        val energy = when (state) { OrbState.LISTENING -> 0.4f + level; OrbState.SPEAKING -> 0.55f + 0.6f * voiceLevel + 0.2f * pulse.value; else -> 0.35f }
        for (i in 0 until 10) {
            val dir = if (i % 2 == 0) 1f else -0.7f
            val a = ((spin * dir * (1f + i * 0.07f)) + i * 36f) * PI.toFloat() / 180f
            val orbit = r * (1.2f + 0.06f * (i % 4))
            drawCircle(if (i % 3 == 0) secondary else primary, (1.3f + (i % 3) * 0.7f) * density,
                Offset(center.x + cos(a) * orbit, center.y + sin(a) * orbit), alpha = (0.3f + 0.5f * energy).coerceAtMost(1f))
        }

        // Arc-reactor core: dark sphere, energy sweep, segmented rotating ring, bright heart.
        drawCircle(Brush.radialGradient(listOf(Color(0xFF0D1426), Color(0xFF03050A)), center, r), r, center)
        rotate(if (state == OrbState.THINKING) spin * 2 else spin, center) {
            drawCircle(
                Brush.sweepGradient(listOf(primary.copy(alpha = 0.0f), primary.copy(alpha = 0.4f), secondary.copy(alpha = 0.55f), primary.copy(alpha = 0.0f)), center),
                r * 0.95f, center,
            )
        }
        val segR = r * 0.66f
        rotate(-spin * 0.6f, center) {
            for (k in 0 until 12) drawArc(
                primary.copy(alpha = 0.5f + 0.35f * energy.coerceAtMost(1f)), k * 30f + 4f, 22f, false,
                Offset(center.x - segR, center.y - segR), Size(segR * 2, segR * 2), style = Stroke(r * 0.11f, cap = StrokeCap.Butt),
            )
        }
        drawCircle(primary.copy(alpha = 0.45f), r * 0.82f, center, style = Stroke(1.dp.toPx()))
        drawCircle(secondary.copy(alpha = 0.55f), r * 0.48f, center, style = Stroke(1.2.dp.toPx()))

        // Thinking: faint synapse sparks inside the core, linked by thin threads.
        if (state == OrbState.THINKING || state == OrbState.RESEARCHING || state == OrbState.SEARCHING) {
            val syn = com.melhive.zed.core.ui.PlasmaShape.synapses(t)
            syn.zipWithNext().forEach { (a, b) ->
                drawLine(Color.White.copy(alpha = 0.18f * minOf(a.third, b.third)), Offset(center.x + a.first * r * 0.8f, center.y + a.second * r * 0.8f),
                    Offset(center.x + b.first * r * 0.8f, center.y + b.second * r * 0.8f), 0.8.dp.toPx())
            }
            syn.forEach { (x, y, b) -> drawCircle(Color.White.copy(alpha = 0.75f * b), 1.4.dp.toPx(), Offset(center.x + x * r * 0.8f, center.y + y * r * 0.8f)) }
        }

        val heart = r * (0.36f + 0.08f * energy.coerceAtMost(1f))
        drawCircle(Brush.radialGradient(listOf(Color.White, primary.copy(alpha = 0.9f), primary.copy(alpha = 0f)), center, heart * 1.6f), heart * 1.6f, center)
        drawCircle(Color.White.copy(alpha = 0.95f), heart * 0.42f, center)
        // Glass highlight and glowing rim.
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.3f), Color.Transparent), Offset(center.x - r * 0.35f, center.y - r * 0.4f), r * 0.55f),
            r * 0.55f, Offset(center.x - r * 0.3f, center.y - r * 0.35f),
        )
        drawCircle(Brush.sweepGradient(listOf(primary, secondary, primary), center), r, center, style = Stroke(1.6.dp.toPx()))

        if (state == OrbState.COMPLETED && done.value > 0f) drawCheck(center, r * 0.34f, done.value)
        if (state == OrbState.ERROR) drawWarning(center, r * 0.42f)
    }
}

/** Fine ticks around the orb whose length follows ZED's voice; the waveform is mirrored so the ring stays balanced. */
private fun DrawScope.drawVoiceRing(center: Offset, radius: Float, voice: FloatArray, color: Color) {
    val ticks = 48
    for (i in 0 until ticks) {
        val a = i.toFloat() / ticks * 2f * PI.toFloat() - PI.toFloat() / 2f
        val half = if (i <= ticks / 2) i.toFloat() / (ticks / 2) else (ticks - i).toFloat() / (ticks / 2)
        val pos = half * (voice.size - 1)
        val lo = pos.toInt().coerceIn(0, voice.size - 1); val hi = (lo + 1).coerceAtMost(voice.size - 1)
        val v = (voice[lo] + (voice[hi] - voice[lo]) * (pos - lo)).coerceIn(0f, 1f)
        val len = radius * (0.04f + 0.22f * v)
        val dx = cos(a); val dy = sin(a)
        drawLine(color.copy(alpha = 0.25f + 0.6f * v), Offset(center.x + dx * radius, center.y + dy * radius),
            Offset(center.x + dx * (radius + len), center.y + dy * (radius + len)), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawParticles(center: Offset, radius: Float, spin: Float, a: Color, b: Color, count: Int) {
    for (i in 0 until count) {
        val angle = (spin * (if (i % 2 == 0) 1f else -0.6f) + i * 360f / count) * PI.toFloat() / 180f
        val wobble = 1f + 0.08f * sin(angle * 3)
        val p = Offset(center.x + cos(angle) * radius * wobble, center.y + sin(angle) * radius * wobble)
        drawCircle(if (i % 2 == 0) a else b, (2.5f + (i % 3)) * density, p, alpha = 0.85f)
    }
}

private fun DrawScope.drawRings(center: Offset, r: Float, spin: Float, a: Color, b: Color) {
    val stroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
    val r1 = r * 1.35f
    val r2 = r * 1.6f
    drawArc(a, spin, 110f, false, Offset(center.x - r1, center.y - r1), Size(r1 * 2, r1 * 2), style = stroke)
    drawArc(a, spin + 180f, 110f, false, Offset(center.x - r1, center.y - r1), Size(r1 * 2, r1 * 2), style = stroke)
    drawArc(b, -spin * 1.4f, 70f, false, Offset(center.x - r2, center.y - r2), Size(r2 * 2, r2 * 2), style = stroke)
    drawArc(b, -spin * 1.4f + 120f, 70f, false, Offset(center.x - r2, center.y - r2), Size(r2 * 2, r2 * 2), style = stroke)
    drawArc(b, -spin * 1.4f + 240f, 70f, false, Offset(center.x - r2, center.y - r2), Size(r2 * 2, r2 * 2), style = stroke)
}

private fun DrawScope.drawScan(center: Offset, r: Float, scan: Float, spin: Float, c: Color) {
    val rr = r * 1.45f
    // Radar sweep.
    drawArc(Brush.sweepGradient(listOf(Color.Transparent, c.copy(alpha = 0.45f)), center), spin * 2, 60f, true, Offset(center.x - rr, center.y - rr), Size(rr * 2, rr * 2))
    // Horizontal scan line moving top→bottom across the sphere.
    val y = center.y - r + 2 * r * scan
    val half = kotlin.math.sqrt((r * r - (y - center.y) * (y - center.y)).coerceAtLeast(0f))
    drawLine(c.copy(alpha = 0.9f), Offset(center.x - half, y), Offset(center.x + half, y), 2.dp.toPx())
    drawCircle(c.copy(alpha = 0.4f), rr, center, style = Stroke(1.dp.toPx()))
}

private fun DrawScope.drawCheck(center: Offset, s: Float, progress: Float) {
    val path = Path().apply {
        moveTo(center.x - s * 0.6f, center.y)
        lineTo(center.x - s * 0.15f, center.y + s * 0.45f)
        lineTo(center.x + s * 0.65f, center.y - s * 0.45f)
    }
    drawPath(path, Color.White.copy(alpha = progress), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
}

private fun DrawScope.drawWarning(center: Offset, s: Float) {
    val path = Path().apply {
        moveTo(center.x, center.y - s)
        lineTo(center.x + s, center.y + s * 0.75f)
        lineTo(center.x - s, center.y + s * 0.75f)
        close()
    }
    drawPath(path, Color.White.copy(alpha = 0.9f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    drawLine(Color.White, Offset(center.x, center.y - s * 0.35f), Offset(center.x, center.y + s * 0.25f), 3.dp.toPx(), StrokeCap.Round)
    drawCircle(Color.White, 2.dp.toPx(), Offset(center.x, center.y + s * 0.5f))
}
