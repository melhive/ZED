package com.melhive.zed.ui.components

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.zed.AppContainer
import com.melhive.zed.intelligence.LocalModelRuntime
import com.melhive.zed.ui.theme.ZedColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.sin

/** A number that counts up to [value] when it first appears (and glides when it changes). */
@Composable
fun CountUp(value: Int, suffix: String = "", color: Color = ZedColors.Text, modifier: Modifier = Modifier) {
    val shown by animateIntAsState(value, tween(900, easing = FastOutSlowInEasing), label = "count")
    Text("$shown$suffix", color = color, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace, modifier = modifier)
}

/** One status ring: an arc that sweeps to [fraction], a number in the middle and a short label under it. */
@Composable
fun StatusRing(fraction: Float, value: String, label: String, color: Color, spinning: Boolean = false, modifier: Modifier = Modifier) {
    val sweep by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(1000, easing = FastOutSlowInEasing), label = "ring")
    val spin = if (spinning) rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "s").value else 0f
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(58.dp)) {
                val stroke = 3.dp.toPx()
                val inset = stroke
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                drawArc(ZedColors.Outline, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                if (spinning) drawArc(color, spin, 90f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                else drawArc(color, -90f, 360f * sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text(value, style = MaterialTheme.typography.labelLarge, color = ZedColors.Text, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
        }
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted, letterSpacing = 1.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

private data class ConsoleStatus(val battery: Int, val charging: Boolean, val ramUsed: Float, val online: Boolean, val unmetered: Boolean, val next: Pair<String, Long>?, val event: Pair<String, Long>?)

private fun readStatus(context: Context, c: AppContainer, withCalendar: Boolean, cachedEvent: Pair<String, Long>?): ConsoleStatus {
    val b = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
    val level = b?.let { val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1); if (l >= 0 && s > 0) l * 100 / s else 0 } ?: 0
    val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val d = runCatching { com.melhive.zed.android.DeviceInfo.snapshot(context) }.getOrNull()
    val ram = d?.let { if (it.totalRamBytes > 0) 1f - it.availRamBytes.toFloat() / it.totalRamBytes else 0f } ?: 0f
    val now = System.currentTimeMillis()
    val event = if (withCalendar) runCatching { com.melhive.zed.agents.CalendarReader(context).nextEvent(now) }.getOrNull() else cachedEvent
    val countdown = runCatching { com.melhive.zed.android.Countdowns(context).active(now).firstOrNull() }.getOrNull()?.let { (it.label.ifBlank { it.kind.replaceFirstChar { c -> c.uppercase() } }) to it.endAt }
    val next = listOfNotNull(event?.takeIf { it.second > now }, countdown).minByOrNull { it.second }
    return ConsoleStatus(level, status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL, ram, d?.online == true, d?.unmetered == true, next, event)
}

/**
 * The Home console: rings for battery, memory, network and the AI model, and
 * a live countdown to whatever comes next (an event or a timer ZED set).
 */
@Composable
fun HomeConsole(c: AppContainer, nextReminder: Pair<String, Long>?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val runtime by c.providers.localRuntime.state.collectAsStateWithLifecycle()
    val status by produceState<ConsoleStatus?>(null) {
        var tick = 0
        var event: Pair<String, Long>? = null
        while (true) {
            val s = withContext(Dispatchers.IO) { readStatus(context, c, withCalendar = tick % 6 == 0, cachedEvent = event) }
            event = s.event
            value = s
            tick++
            delay(10_000)
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            val s = status
            StatusRing((s?.battery ?: 0) / 100f, "${animatedInt(s?.battery ?: 0)}%", if (s?.charging == true) "Charging" else "Battery",
                when { (s?.battery ?: 100) <= 15 -> ZedColors.Red; s?.charging == true -> ZedColors.Green; else -> ZedColors.Cyan })
            StatusRing(s?.ramUsed ?: 0f, "${animatedInt(((s?.ramUsed ?: 0f) * 100).toInt())}%", "Memory", if ((s?.ramUsed ?: 0f) > 0.88f) ZedColors.Amber else ZedColors.Violet)
            StatusRing(if (s?.online == true) (if (s.unmetered) 1f else 0.7f) else 0f, if (s?.online == true) (if (s.unmetered) "Wi-Fi" else "Data") else "Off", "Network",
                if (s?.online == true) ZedColors.Teal else ZedColors.Muted)
            val (frac, text, color, spin) = when (runtime) {
                is LocalModelRuntime.State.Ready -> Quad(1f, "On", ZedColors.Green, false)
                is LocalModelRuntime.State.Loading -> Quad(0.3f, "…", ZedColors.Amber, true)
                is LocalModelRuntime.State.Failed -> Quad(1f, "!", ZedColors.Red, false)
                else -> Quad(0f, "Zz", ZedColors.Muted, false)
            }
            StatusRing(frac, text, "Gemma", color, spinning = spin)
        }
        val next = listOfNotNull(status?.next, nextReminder?.takeIf { it.second > now }).minByOrNull { it.second }
        if (next != null && next.second > now) {
            val secs = (next.second - now) / 1000
            val h = secs / 3600; val m = (secs % 3600) / 60; val sec = secs % 60
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(next.first.take(28), style = MaterialTheme.typography.bodyMedium, color = ZedColors.Muted)
                Text("  " + (if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)), style = MaterialTheme.typography.titleMedium, color = ZedColors.Cyan, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

private data class Quad(val a: Float, val b: String, val c: Color, val d: Boolean)

@Composable
private fun animatedInt(v: Int): Int = animateIntAsState(v, tween(900, easing = FastOutSlowInEasing), label = "n").value

/**
 * Drifting points of light behind Home that lean with the phone (a little
 * parallax from the gravity sensor). Light on the chip: about 24 frames a
 * second and a few dozen dots. [tilt] off keeps them drifting without the sensor.
 */
@Composable
fun ParticleField(modifier: Modifier = Modifier, tilt: Boolean = true) {
    val context = LocalContext.current
    var gx by remember { mutableFloatStateOf(0f) }
    var gy by remember { mutableFloatStateOf(0f) }
    if (tilt) DisposableEffect(Unit) {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { gx += (-e.values[0] / 9.8f - gx) * 0.08f; gy += (e.values[1] / 9.8f - 0.6f - gy) * 0.08f }
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener) }
    }
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { now -> if (now - last >= 41_000_000L) { if (last != 0L) t += (now - last) / 1e9f; last = now } }
    }
    val dots = remember { List(36) { i -> Triple(((i * 7919) % 1000) / 1000f, ((i * 104729) % 1000) / 1000f, 0.3f + ((i * 31) % 70) / 100f) } }
    Canvas(modifier) {
        dots.forEachIndexed { i, (x0, y0, depth) ->
            val x = ((x0 + 0.004f * t * depth + gx * 0.03f * depth) % 1f + 1f) % 1f
            val y = ((y0 + 0.006f * sin(t * 0.3f + i) * depth + gy * 0.03f * depth) % 1f + 1f) % 1f
            val alpha = 0.08f + 0.18f * depth * (0.6f + 0.4f * sin(t * 0.8f + i * 1.7f))
            drawCircle((if (i % 5 == 0) ZedColors.Violet else ZedColors.Cyan).copy(alpha = alpha), (0.8f + 1.4f * depth) * density, Offset(x * size.width, y * size.height))
        }
    }
}
