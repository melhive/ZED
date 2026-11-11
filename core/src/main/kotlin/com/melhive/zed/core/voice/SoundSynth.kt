package com.melhive.zed.core.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * ZED's LIVE start and stop sounds, synthesized in code (no audio files).
 *
 * They are built to sound like a machine, not a voice or a ghost: harmonically
 * rich pulse and saw waves instead of pure sine glides, motor-style amplitude
 * buzz, hard gated beeps, relay clicks and metal latches — and never a slow
 * vibrato or a long soft tail. The energy sits between about 400 Hz and 5 kHz,
 * where small phone speakers are loud (deep bass is inaudible on them).
 */
object SoundSynth {
    const val RATE = 44_100

    enum class Style(val id: String, val label: String, val description: String) {
        ARC("arc", "Arc", "Reactor power-up: motor spin, lock click and two crisp beeps"),
        SERVO("servo", "Servo", "A robot joint moving: two motor whirs and a gear lock"),
        DROID("droid", "Droid", "Quick robotic chirps, rising to say hello and falling to say bye"),
        DATA("data", "Data", "Computer telemetry: a burst of data blips and a confirm tone"),
        RETRO("retro", "Retro", "8-bit console arpeggio"),
        AIRLOCK("airlock", "Airlock", "Pneumatic hiss, metal latch and an armed beep"),
        OFF("off", "Off", "No sound");

        companion object {
            /** Unknown ids (including the retired "pulse" and "chime") fall back to Arc. */
            fun parse(id: String?) = entries.firstOrNull { it.id == id } ?: ARC
        }
    }

    /** 16-bit mono PCM at [RATE]; empty for [Style.OFF]. */
    fun render(style: Style, start: Boolean): ShortArray {
        val out = when (style) {
            Style.ARC -> if (start) arcUp() else arcDown()
            Style.SERVO -> if (start) servoUp() else servoDown()
            Style.DROID -> if (start) droidUp() else droidDown()
            Style.DATA -> if (start) dataUp() else dataDown()
            Style.RETRO -> crush(if (start) retroUp() else retroDown())
            Style.AIRLOCK -> if (start) airlockUp() else airlockDown()
            Style.OFF -> return ShortArray(0)
        }
        highPass(out, 180.0)
        lowPass(out, 7000.0)
        val n = out.size
        val peak = out.maxOf { abs(it) }.takeIf { it > 0 } ?: 1.0
        val gain = 0.6 / peak
        // 3 ms fade at both ends: no clicks from the speaker.
        val fade = RATE * 3 / 1000
        return ShortArray(n) { i ->
            val edge = min(1.0, min(i, n - 1 - i).toDouble() / fade)
            (out[i] * gain * edge * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    // ---------------------------------------------------------------- styles

    private fun arcUp(): DoubleArray {
        val o = buffer(0.62)
        click(o, 0.0, 2800.0, 0.9)
        motor(o, 0.015, 0.315, 240.0, 1100.0, 45.0, 140.0, 0.55, 1.0)
        latch(o, 0.335, 1.0, 0.7)
        beep(o, 0.395, 0.055, 1568.0, 0.75)
        beep(o, 0.475, 0.095, 2093.0, 0.8)
        return o
    }

    private fun arcDown(): DoubleArray {
        val o = buffer(0.52)
        beep(o, 0.0, 0.05, 2093.0, 0.8)
        beep(o, 0.075, 0.05, 1568.0, 0.75)
        motor(o, 0.15, 0.29, 1000.0, 210.0, 140.0, 35.0, 1.0, 0.45)
        latch(o, 0.445, 0.75, 0.8)
        return o
    }

    private fun servoUp(): DoubleArray {
        val o = buffer(0.5)
        motor(o, 0.0, 0.11, 420.0, 640.0, 95.0, 105.0, 0.9, 0.9, depth = 0.7, whine = 0.35)
        click(o, 0.112, 3200.0, 0.7)
        motor(o, 0.17, 0.23, 520.0, 980.0, 110.0, 150.0, 0.9, 1.0, depth = 0.7, whine = 0.35)
        click(o, 0.402, 3200.0, 0.8)
        latch(o, 0.43, 1.25, 0.6)
        return o
    }

    private fun servoDown(): DoubleArray {
        val o = buffer(0.48)
        motor(o, 0.0, 0.2, 980.0, 520.0, 150.0, 110.0, 1.0, 0.9, depth = 0.7, whine = 0.35)
        click(o, 0.202, 3200.0, 0.7)
        motor(o, 0.26, 0.12, 640.0, 380.0, 105.0, 80.0, 0.9, 0.8, depth = 0.7, whine = 0.35)
        latch(o, 0.385, 0.8, 0.8)
        return o
    }

    private fun droidUp(): DoubleArray {
        val o = buffer(0.44)
        chirp(o, 0.0, 0.06, 1400.0, 2100.0)
        chirp(o, 0.075, 0.045, 2600.0, 2600.0, trill = 45.0)
        chirp(o, 0.135, 0.07, 1800.0, 1100.0)
        chirp(o, 0.22, 0.05, 2300.0, 3000.0)
        chirp(o, 0.285, 0.13, 1500.0, 2700.0)
        return o
    }

    private fun droidDown(): DoubleArray {
        val o = buffer(0.4)
        chirp(o, 0.0, 0.05, 2700.0, 2000.0)
        chirp(o, 0.065, 0.05, 2200.0, 2200.0, trill = 45.0)
        chirp(o, 0.13, 0.06, 1500.0, 2100.0)
        chirp(o, 0.21, 0.17, 1900.0, 700.0)
        return o
    }

    private fun dataUp(): DoubleArray {
        val o = buffer(0.5)
        val notes = doubleArrayOf(1175.0, 1568.0, 1319.0, 2093.0, 1760.0, 2349.0, 1568.0, 2637.0, 1976.0)
        notes.forEachIndexed { k, f -> beep(o, k * 0.034, 0.026, f, 0.6, release = 0.003) }
        beep(o, 0.33, 0.15, 1760.0, 0.6, width = 0.25)
        beep(o, 0.33, 0.15, 2637.0, 0.45, width = 0.25)
        return o
    }

    private fun dataDown(): DoubleArray {
        val o = buffer(0.42)
        beep(o, 0.0, 0.09, 1319.0, 0.6, width = 0.25)
        beep(o, 0.0, 0.09, 1976.0, 0.45, width = 0.25)
        val notes = doubleArrayOf(2349.0, 1760.0, 2093.0, 1319.0, 1568.0, 1047.0, 880.0)
        notes.forEachIndexed { k, f -> beep(o, 0.12 + k * 0.034, 0.026, f, 0.6, release = 0.003) }
        return o
    }

    private fun retroUp(): DoubleArray {
        val o = buffer(0.46)
        doubleArrayOf(523.25, 659.25, 783.99).forEachIndexed { k, f -> beep(o, k * 0.07, 0.066, f, 0.8, width = 0.25, octave = 0.0) }
        // Classic power-up finish: the top note flips between the octave and its fifth.
        for (k in 0 until 8) beep(o, 0.21 + k * 0.028, 0.028, if (k % 2 == 0) 1046.5 else 1568.0, 0.8, width = 0.25, octave = 0.0, attack = 0.001, release = 0.001)
        return o
    }

    private fun retroDown(): DoubleArray {
        val o = buffer(0.42)
        doubleArrayOf(1046.5, 783.99, 659.25).forEachIndexed { k, f -> beep(o, k * 0.07, 0.066, f, 0.8, width = 0.25, octave = 0.0) }
        glide(o, 0.21, 0.18, 523.25, 330.0, 0.8, width = 0.25)
        return o
    }

    private fun airlockUp(): DoubleArray {
        val o = buffer(0.56)
        hiss(o, 0.0, 0.26, 4200.0, 1500.0, 0.9, fadeIn = 0.012, fadeOut = 0.05)
        latch(o, 0.27, 1.0, 1.0)
        latch(o, 0.37, 1.5, 0.5)
        beep(o, 0.45, 0.07, 1976.0, 0.4)
        return o
    }

    private fun airlockDown(): DoubleArray {
        val o = buffer(0.52)
        latch(o, 0.0, 1.5, 0.6)
        latch(o, 0.09, 1.0, 1.0)
        hiss(o, 0.15, 0.34, 1500.0, 4500.0, 0.9, fadeIn = 0.01, fadeOut = 0.2)
        return o
    }

    // ------------------------------------------------------- building blocks

    private fun buffer(seconds: Double) = DoubleArray((seconds * RATE).toInt())

    /** PolyBLEP correction: removes the aliasing a naive saw or square would add. */
    private fun blep(t: Double, dt: Double): Double = when {
        t < dt -> { val x = t / dt; x + x - x * x - 1 }
        t > 1 - dt -> { val x = (t - 1) / dt; x * x + x + x + 1 }
        else -> 0.0
    }

    private fun saw(p: Double, dt: Double) = 2 * p - 1 - blep(p, dt)

    private fun pulse(p: Double, dt: Double, width: Double): Double {
        val q = (p + 1 - width).let { if (it >= 1) it - 1 else it }
        return (if (p < width) 1.0 else -1.0) + blep(p, dt) - blep(q, dt)
    }

    /** Hard-gated envelope: on and off in a few milliseconds, like a relay. */
    private fun gate(t: Double, dur: Double, attack: Double, release: Double) =
        min(1.0, min(t / attack, (dur - t) / release)).coerceAtLeast(0.0)

    /** A digital beep: a pulse wave with a touch of the octave above, hard gated. */
    private fun beep(
        o: DoubleArray, at: Double, dur: Double, freq: Double, gain: Double,
        width: Double = 0.5, octave: Double = 0.2, attack: Double = 0.002, release: Double = 0.005,
    ) = glide(o, at, dur, freq, freq, gain, width, octave, attack, release)

    /** A gated pulse tone sliding from [f0] to [f1]. */
    private fun glide(
        o: DoubleArray, at: Double, dur: Double, f0: Double, f1: Double, gain: Double,
        width: Double = 0.5, octave: Double = 0.0, attack: Double = 0.002, release: Double = 0.005,
    ) {
        val i0 = (at * RATE).toInt()
        val n = (dur * RATE).toInt()
        var p = 0.0
        var p2 = 0.0
        for (k in 0 until n) {
            val i = i0 + k
            if (i >= o.size) break
            val t = k.toDouble() / RATE
            val f = f0 * (f1 / f0).pow(t / dur)
            val dt = f / RATE
            p += dt; if (p >= 1) p -= 1
            p2 += 2 * dt; if (p2 >= 1) p2 -= 1
            var v = pulse(p, dt, width)
            if (octave > 0) v += octave * pulse(p2, 2 * dt, 0.5)
            o[i] += gain * gate(t, dur, attack, release) * v
        }
    }

    /**
     * An electric motor: a saw and a narrow pulse an octave apart, chopped by a
     * fast amplitude buzz (the commutator), with an optional gear whine on top.
     * Pitch runs [f0]→[f1], the buzz [am0]→[am1] Hz and the level [g0]→[g1].
     */
    private fun motor(
        o: DoubleArray, at: Double, dur: Double, f0: Double, f1: Double, am0: Double, am1: Double,
        g0: Double, g1: Double, depth: Double = 0.55, whine: Double = 0.2,
    ) {
        val i0 = (at * RATE).toInt()
        val n = (dur * RATE).toInt()
        var p = 0.0; var p2 = 0.0; var pa = 0.0; var pw = 0.0
        for (k in 0 until n) {
            val i = i0 + k
            if (i >= o.size) break
            val t = k.toDouble() / RATE
            val x = t / dur
            val f = f0 * (f1 / f0).pow(x)
            val dt = f / RATE
            p += dt; if (p >= 1) p -= 1
            p2 += 2.01 * dt; if (p2 >= 1) p2 -= 1
            pa += (am0 + (am1 - am0) * x) / RATE; if (pa >= 1) pa -= 1
            pw += 3.17 * dt; if (pw >= 1) pw -= 1
            // Rounded square buzz: sharp enough to growl, smooth enough not to click.
            val buzz = 1 - depth * (0.5 + 0.5 * (sin(2 * PI * pa) * 3).coerceIn(-1.0, 1.0))
            val body = 0.6 * saw(p, dt) + 0.4 * pulse(p2, 2.01 * dt, 0.3) + whine * sin(2 * PI * pw)
            o[i] += (g0 + (g1 - g0) * x) * gate(t, dur, 0.012, 0.01) * buzz * body
        }
    }

    /** A droid chirp: an FM tone (bright, slightly nasal) sliding quickly, optionally trilled. */
    private fun chirp(o: DoubleArray, at: Double, dur: Double, f0: Double, f1: Double, trill: Double = 0.0, gain: Double = 0.8) {
        val i0 = (at * RATE).toInt()
        val n = (dur * RATE).toInt()
        var ph = 0.0
        for (k in 0 until n) {
            val i = i0 + k
            if (i >= o.size) break
            val t = k.toDouble() / RATE
            var f = f0 * (f1 / f0).pow(t / dur)
            if (trill > 0) f *= if (sin(2 * PI * trill * t) >= 0) 1.0 else 1.26
            ph += 2 * PI * f / RATE
            o[i] += gain * gate(t, dur, 0.003, 0.006) * sin(ph + 1.2 * sin(2 * ph))
        }
    }

    /** A relay click: a few milliseconds of band-passed noise. */
    private fun click(o: DoubleArray, at: Double, center: Double, gain: Double) =
        noise(o, at, 0.014, center, center, 0.7, gain * 2.2, seed = (at * 1e4).toInt() + 7) { t, _ -> exp(-t / 0.0028) }

    /** Air escaping: band-passed noise whose colour sweeps from [c0] to [c1] Hz. */
    private fun hiss(o: DoubleArray, at: Double, dur: Double, c0: Double, c1: Double, gain: Double, fadeIn: Double, fadeOut: Double) =
        noise(o, at, dur, c0, c1, 0.9, gain, seed = 31) { t, d -> gate(t, d, fadeIn, fadeOut) }

    private inline fun noise(
        o: DoubleArray, at: Double, dur: Double, c0: Double, c1: Double, damping: Double, gain: Double, seed: Int,
        env: (Double, Double) -> Double,
    ) {
        val i0 = (at * RATE).toInt()
        val n = (dur * RATE).toInt()
        var state = seed * 1_103_515_245L + 12_345L
        var low = 0.0; var band = 0.0
        for (k in 0 until n) {
            val i = i0 + k
            if (i >= o.size) break
            val t = k.toDouble() / RATE
            state = (state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L)
            val white = ((state ushr 33).toInt() / 1_073_741_824.0) - 1.0
            // State-variable band-pass filter.
            val fc = c0 * (c1 / c0).pow(t / dur)
            val f = 2 * sin(PI * fc / RATE)
            low += f * band
            val high = white - low - damping * band
            band += f * high
            o[i] += gain * env(t, dur) * band
        }
    }

    /**
     * A metal latch: inharmonic partials that ring for a few hundredths of a
     * second, plus the thud of the bolt. [pitch] scales the partials.
     */
    private fun latch(o: DoubleArray, at: Double, pitch: Double, gain: Double) {
        val partials = arrayOf(
            doubleArrayOf(540.0, 1.0, 0.045), doubleArrayOf(1290.0, 0.8, 0.05), doubleArrayOf(2210.0, 0.55, 0.035),
            doubleArrayOf(3370.0, 0.35, 0.022), doubleArrayOf(4480.0, 0.2, 0.014),
        )
        val i0 = (at * RATE).toInt()
        val n = (0.14 * RATE).toInt()
        for (k in 0 until n) {
            val i = i0 + k
            if (i >= o.size) break
            val t = k.toDouble() / RATE
            var v = 0.0
            for (p in partials) v += p[1] * sin(2 * PI * p[0] * pitch * t) * exp(-t / p[2])
            o[i] += gain * 0.5 * min(1.0, t / 0.0006) * v
        }
        click(o, at, 900.0 * pitch, gain * 0.6)
    }

    /** 8-bit console grit: a low sample rate (sample-and-hold) and 5-bit depth. */
    private fun crush(o: DoubleArray): DoubleArray {
        val hold = 3
        val levels = 16.0
        val peak = o.maxOf { abs(it) }.takeIf { it > 0 } ?: 1.0
        var held = 0.0
        for (i in o.indices) {
            if (i % hold == 0) held = Math.round(o[i] / peak * levels) / levels * peak
            o[i] = held
        }
        return o
    }

    private fun highPass(o: DoubleArray, cutoff: Double) {
        val a = exp(-2 * PI * cutoff / RATE)
        var low = 0.0
        for (i in o.indices) { low = (1 - a) * o[i] + a * low; o[i] -= low }
    }

    private fun lowPass(o: DoubleArray, cutoff: Double) {
        val a = exp(-2 * PI * cutoff / RATE)
        var low = 0.0
        for (i in o.indices) { low = (1 - a) * o[i] + a * low; o[i] = low }
    }
}
