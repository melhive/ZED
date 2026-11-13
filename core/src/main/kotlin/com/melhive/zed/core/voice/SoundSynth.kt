package com.melhive.zed.core.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * ZED's LIVE sounds, synthesized in code (no audio files).
 *
 * Deep, mature machine sounds: engines and turbines spinning up, heavy
 * mechanical locks and low synth chords — never chirps, bleeps or toy
 * arpeggios. Fundamentals sit low (55–220 Hz) for weight, but the sound is
 * built from rich harmonics and filtered so its energy lands above ~350 Hz,
 * where phone speakers can play it: the ear still hears the low pitch.
 */
object SoundSynth {
    const val RATE = 44_100

    enum class Style(val id: String, val label: String, val description: String) {
        CORE("core", "Core", "A reactor engine spins up, a heavy lock engages and a low chord confirms"),
        TURBINE("turbine", "Turbine", "A jet turbine winds up with a deep roar, then settles"),
        MECH("mech", "Mech", "Heavy hydraulics: a servo drives, gears grind and a bolt slams home"),
        COMMAND("command", "Command", "A starship console: low, warm synth chords"),
        OFF("off", "Off", "No sound");

        companion object {
            /** Unknown ids (including the retired arc, servo, droid, data, retro and airlock) fall back to Core. */
            fun parse(id: String?) = entries.firstOrNull { it.id == id } ?: CORE
        }
    }

    /** 16-bit mono PCM at [RATE]; empty for [Style.OFF]. */
    fun render(style: Style, start: Boolean): ShortArray {
        val out = when (style) {
            Style.CORE -> if (start) coreUp() else coreDown()
            Style.TURBINE -> if (start) turbineUp() else turbineDown()
            Style.MECH -> if (start) mechUp() else mechDown()
            Style.COMMAND -> if (start) commandUp() else commandDown()
            Style.OFF -> return ShortArray(0)
        }
        return finish(out, 0.6)
    }

    /** The instant "got it" when ZED takes a request in LIVE: one low, solid mechanical thunk. */
    fun ack(): ShortArray {
        val o = buffer(0.16)
        clank(o, 0.0, 0.9, 0.8)
        chord(o, 0.0, 0.09, doubleArrayOf(110.0, 165.0), 0.6, attack = 0.002, release = 0.05)
        return finish(o, 0.36)
    }

    /** Band-limits to where phone speakers play (steep high-pass, dark low-pass), normalizes and fades the ends. */
    private fun finish(out: DoubleArray, level: Double): ShortArray {
        highPass(out, 330.0); highPass(out, 330.0)
        lowPass(out, 4200.0); lowPass(out, 4200.0)
        val n = out.size
        val peak = out.maxOf { abs(it) }.takeIf { it > 0 } ?: 1.0
        val gain = level / peak
        // 3 ms fade at both ends: no clicks from the speaker.
        val fade = RATE * 3 / 1000
        return ShortArray(n) { i ->
            val edge = min(1.0, min(i, n - 1 - i).toDouble() / fade)
            (out[i] * gain * edge * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    /**
     * A synapse tick for the Live Brain: a few hundredths of a second of click
     * and glassy ping. [pitch] 0..4 picks one of five notes so repeated ticks
     * don't sound mechanical-identical.
     */
    fun tick(pitch: Int = 0): ShortArray {
        val o = buffer(0.07)
        val f = doubleArrayOf(2093.0, 2349.0, 2637.0, 3136.0, 3520.0)[pitch.mod(5)]
        click(o, 0.0, 3600.0, 0.5)
        for (k in o.indices) {
            val t = k.toDouble() / RATE
            o[k] += 0.8 * min(1.0, t / 0.001) * exp(-t / 0.014) * (sin(2 * PI * f * t) + 0.4 * sin(2 * PI * f * 2.41 * t))
        }
        highPass(o, 400.0)
        val peak = o.maxOf { abs(it) }.takeIf { it > 0 } ?: 1.0
        val fade = RATE / 1000
        return ShortArray(o.size) { i ->
            val edge = min(1.0, min(i, o.size - 1 - i).toDouble() / fade)
            (o[i] * 0.32 / peak * edge * Short.MAX_VALUE).toInt().toShort()
        }
    }

    // ---------------------------------------------------------------- styles

    private fun coreUp(): DoubleArray {
        val o = buffer(0.82)
        clank(o, 0.0, 0.75, 0.55)
        engine(o, 0.02, 0.52, 55.0, 110.0, 0.35, 1.0, whine = 0.14)
        rumble(o, 0.02, 0.52, 380.0, 760.0, 0.45, fadeIn = 0.08, fadeOut = 0.04)
        clank(o, 0.55, 1.0, 1.0)
        chord(o, 0.6, 0.22, doubleArrayOf(110.0, 165.0, 220.0), 0.7, attack = 0.012, release = 0.09)
        return o
    }

    private fun coreDown(): DoubleArray {
        val o = buffer(0.74)
        chord(o, 0.0, 0.14, doubleArrayOf(110.0, 165.0, 220.0), 0.7, attack = 0.008, release = 0.05)
        engine(o, 0.12, 0.48, 110.0, 46.0, 1.0, 0.25, whine = 0.12)
        rumble(o, 0.12, 0.48, 760.0, 340.0, 0.45, fadeIn = 0.02, fadeOut = 0.12)
        clank(o, 0.6, 0.7, 0.8)
        return o
    }

    private fun turbineUp(): DoubleArray {
        val o = buffer(0.86)
        rumble(o, 0.0, 0.66, 420.0, 1300.0, 0.9, fadeIn = 0.18, fadeOut = 0.06)
        whine(o, 0.0, 0.66, 520.0, 2100.0, 0.18, 0.5)
        engine(o, 0.0, 0.66, 62.0, 124.0, 0.25, 0.8, whine = 0.0)
        chord(o, 0.64, 0.2, doubleArrayOf(98.0, 147.0, 196.0), 0.75, attack = 0.01, release = 0.1)
        return o
    }

    private fun turbineDown(): DoubleArray {
        val o = buffer(0.8)
        chord(o, 0.0, 0.12, doubleArrayOf(98.0, 147.0, 196.0), 0.7, attack = 0.006, release = 0.05)
        rumble(o, 0.08, 0.7, 1300.0, 380.0, 0.9, fadeIn = 0.02, fadeOut = 0.3)
        whine(o, 0.08, 0.7, 2100.0, 450.0, 0.5, 0.1)
        engine(o, 0.08, 0.6, 124.0, 52.0, 0.8, 0.2, whine = 0.0)
        return o
    }

    private fun mechUp(): DoubleArray {
        val o = buffer(0.78)
        hiss(o, 0.0, 0.12, 2600.0, 1800.0, 0.35, fadeIn = 0.005, fadeOut = 0.06)
        motor(o, 0.04, 0.2, 150.0, 230.0, 38.0, 52.0, 0.7, 0.9, depth = 0.6, whine = 0.1)
        clank(o, 0.25, 0.8, 0.7)
        motor(o, 0.3, 0.2, 180.0, 300.0, 44.0, 64.0, 0.8, 1.0, depth = 0.6, whine = 0.1)
        clank(o, 0.52, 0.62, 1.0)
        chord(o, 0.56, 0.2, doubleArrayOf(82.0, 123.0, 165.0), 0.6, attack = 0.006, release = 0.1)
        return o
    }

    private fun mechDown(): DoubleArray {
        val o = buffer(0.72)
        clank(o, 0.0, 0.8, 0.6)
        motor(o, 0.05, 0.22, 300.0, 180.0, 64.0, 44.0, 1.0, 0.8, depth = 0.6, whine = 0.1)
        motor(o, 0.3, 0.2, 230.0, 140.0, 52.0, 36.0, 0.9, 0.6, depth = 0.6, whine = 0.1)
        hiss(o, 0.48, 0.16, 1800.0, 2800.0, 0.35, fadeIn = 0.01, fadeOut = 0.1)
        clank(o, 0.52, 0.6, 0.9)
        return o
    }

    private fun commandUp(): DoubleArray {
        val o = buffer(0.66)
        clank(o, 0.0, 1.1, 0.35)
        chord(o, 0.0, 0.26, doubleArrayOf(110.0, 165.0, 220.0, 277.2), 0.7, attack = 0.03, release = 0.05)
        chord(o, 0.26, 0.38, doubleArrayOf(146.8, 220.0, 293.7, 370.0), 0.75, attack = 0.02, release = 0.16)
        return o
    }

    private fun commandDown(): DoubleArray {
        val o = buffer(0.62)
        chord(o, 0.0, 0.24, doubleArrayOf(146.8, 220.0, 293.7, 370.0), 0.7, attack = 0.015, release = 0.05)
        chord(o, 0.24, 0.36, doubleArrayOf(110.0, 165.0, 220.0, 261.6), 0.75, attack = 0.02, release = 0.18, drop = 0.94)
        clank(o, 0.5, 0.9, 0.3)
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

    /**
     * A combustion engine / reactor: a narrow pulse and a slightly detuned saw on a low
     * fundamental (the beat between them is the engine's throb), pulsing at half the
     * firing rate, with a turbine whine 16 harmonics up that grows as it revs.
     */
    private fun engine(o: DoubleArray, at: Double, dur: Double, f0: Double, f1: Double, g0: Double, g1: Double, whine: Double = 0.1, throb: Double = 0.35) {
        val i0 = (at * RATE).toInt()
        val n = (dur * RATE).toInt()
        var p1 = 0.0; var p2 = 0.0; var pa = 0.0; var pw = 0.0
        for (k in 0 until n) {
            val i = i0 + k
            if (i >= o.size) break
            val t = k.toDouble() / RATE
            val x = t / dur
            val f = f0 * (f1 / f0).pow(x)
            val dt = f / RATE
            p1 += dt; if (p1 >= 1) p1 -= 1
            p2 += dt * 1.007; if (p2 >= 1) p2 -= 1
            pa += f * 0.5 / RATE; if (pa >= 1) pa -= 1
            pw += 16 * dt; if (pw >= 1) pw -= 1
            val body = 0.55 * pulse(p1, dt, 0.16) + 0.45 * saw(p2, dt * 1.007)
            val pulseEnv = 1 - throb * (0.5 + 0.5 * sin(2 * PI * pa))
            o[i] += (g0 + (g1 - g0) * x) * gate(t, dur, 0.02, 0.03) * (body * pulseEnv + whine * x * sin(2 * PI * pw))
        }
    }

    /** A turbine's whine: a clean tone with a quiet fifth, sliding [f0]→[f1] Hz, level [g0]→[g1]. */
    private fun whine(o: DoubleArray, at: Double, dur: Double, f0: Double, f1: Double, g0: Double, g1: Double) {
        val i0 = (at * RATE).toInt()
        val n = (dur * RATE).toInt()
        var ph = 0.0
        for (k in 0 until n) {
            val i = i0 + k
            if (i >= o.size) break
            val t = k.toDouble() / RATE
            val x = t / dur
            ph += 2 * PI * f0 * (f1 / f0).pow(x) / RATE
            o[i] += (g0 + (g1 - g0) * x) * gate(t, dur, 0.04, 0.05) * (sin(ph) + 0.3 * sin(1.5 * ph))
        }
    }

    /** Engine roar: band-passed noise sweeping [c0]→[c1] Hz. */
    private fun rumble(o: DoubleArray, at: Double, dur: Double, c0: Double, c1: Double, gain: Double, fadeIn: Double, fadeOut: Double) =
        noise(o, at, dur, c0, c1, 0.55, gain, seed = (at * 1e3).toInt() + 53) { t, d -> gate(t, d, fadeIn, fadeOut) }

    /**
     * A warm low synth chord: detuned saws per note (slight chorus), soft attack and
     * release, the whole chord optionally sinking to [drop]× its pitch.
     */
    private fun chord(o: DoubleArray, at: Double, dur: Double, notes: DoubleArray, gain: Double, attack: Double, release: Double, drop: Double = 1.0) {
        val i0 = (at * RATE).toInt()
        val n = (dur * RATE).toInt()
        val phases = DoubleArray(notes.size * 2)
        val each = gain / notes.size
        for (k in 0 until n) {
            val i = i0 + k
            if (i >= o.size) break
            val t = k.toDouble() / RATE
            val bend = drop.pow(t / dur)
            var v = 0.0
            for ((j, f) in notes.withIndex()) for (d in 0..1) {
                val dt = f * bend * (if (d == 0) 0.997 else 1.003) / RATE
                val q = j * 2 + d
                phases[q] += dt; if (phases[q] >= 1) phases[q] -= 1
                v += 0.5 * saw(phases[q], dt)
            }
            o[i] += each * gate(t, dur, attack, release) * v
        }
    }

    /** A heavy steel clank: low inharmonic partials ringing a little longer than a latch, plus the bolt's thud. */
    private fun clank(o: DoubleArray, at: Double, pitch: Double, gain: Double) {
        val partials = arrayOf(
            doubleArrayOf(410.0, 1.0, 0.09), doubleArrayOf(780.0, 0.75, 0.07), doubleArrayOf(1190.0, 0.55, 0.05),
            doubleArrayOf(1730.0, 0.35, 0.03), doubleArrayOf(2480.0, 0.2, 0.018),
        )
        val i0 = (at * RATE).toInt()
        val n = (0.22 * RATE).toInt()
        for (k in 0 until n) {
            val i = i0 + k
            if (i >= o.size) break
            val t = k.toDouble() / RATE
            var v = 0.0
            for (p in partials) v += p[1] * sin(2 * PI * p[0] * pitch * t) * exp(-t / p[2])
            o[i] += gain * 0.5 * min(1.0, t / 0.0008) * v
        }
        click(o, at, 520.0 * pitch, gain * 0.7)
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
