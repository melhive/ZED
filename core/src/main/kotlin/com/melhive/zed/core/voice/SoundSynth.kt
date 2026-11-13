package com.melhive.zed.core.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * ZED's LIVE sounds, synthesized in code (no audio files): the sounds of a
 * cinematic AI assistant — holographic interfaces, a reactor core, neural
 * links, scanners, armour locking on — in a space with a short reverb.
 *
 * Never toy-like: no bright square bleeps, chirps or major arpeggios. Notes
 * sit low (55–500 Hz) for weight and glassy FM tones replace beeps. Every
 * sound is filtered so its energy lands above ~350 Hz, where phone speakers
 * can play it (the ear still hears the low pitch).
 */
object SoundSynth {
    const val RATE = 44_100

    enum class Style(val id: String, val label: String, val description: String) {
        HOLO("holo", "Hologram", "A holographic display shimmers up and two glass tones lock in"),
        REACTOR("reactor", "Reactor", "An energy core hums to life with electric crackle and a power chord"),
        NEURAL("neural", "Neural", "Synapses fire in a rising cascade that settles into a warm chord"),
        SCAN("scan", "Scanner", "A resonant scan sweeps across and locks on target"),
        ARMOR("armor", "Armor", "Servos drive, plates clamp shut and the suit powers up"),
        SONAR("sonar", "Sonar", "A deep sonar ping echoing through a vast space"),
        CORE("core", "Core", "A reactor engine spins up, a heavy lock engages and a low chord confirms"),
        COMMAND("command", "Command", "A starship bridge console: low, warm synth chords"),
        OFF("off", "Off", "No sound");

        companion object {
            /** Unknown ids (including retired sounds) fall back to Hologram. */
            fun parse(id: String?) = entries.firstOrNull { it.id == id } ?: HOLO
        }
    }

    /** 16-bit mono PCM at [RATE]; empty for [Style.OFF]. */
    fun render(style: Style, start: Boolean): ShortArray {
        val out = when (style) {
            Style.HOLO -> if (start) holoUp() else holoDown()
            Style.REACTOR -> if (start) reactorUp() else reactorDown()
            Style.NEURAL -> if (start) neuralUp() else neuralDown()
            Style.SCAN -> if (start) scanUp() else scanDown()
            Style.ARMOR -> if (start) armorUp() else armorDown()
            Style.SONAR -> if (start) sonarUp() else sonarDown()
            Style.CORE -> reverb(if (start) coreUp() else coreDown(), 0.2)
            Style.COMMAND -> reverb(if (start) commandUp() else commandDown(), 0.3)
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
        // 3 ms fade in; the last 12% fades out so reverb tails never stop abruptly.
        val fade = RATE * 3 / 1000
        val tail = (n * 0.12).toInt().coerceAtLeast(fade)
        return ShortArray(n) { i ->
            val edge = min(1.0, min(i.toDouble() / fade, (n - 1 - i).toDouble() / tail))
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

    private fun holoUp(): DoubleArray {
        val o = buffer(1.0)
        shimmer(o, 0.0, 0.46, 500.0, 3200.0, 0.55, fadeIn = 0.3, fadeOut = 0.05)
        chord(o, 0.05, 0.75, doubleArrayOf(110.0, 165.0), 0.3, attack = 0.35, release = 0.3)
        bell(o, 0.42, 0.5, 330.0, 1.414, 2.0, 0.6, 0.25)
        bell(o, 0.5, 0.45, 495.0, 1.414, 1.5, 0.45, 0.22)
        return reverb(o, 0.35)
    }

    private fun holoDown(): DoubleArray {
        val o = buffer(0.9)
        bell(o, 0.0, 0.4, 495.0, 1.414, 1.5, 0.45, 0.18)
        bell(o, 0.08, 0.45, 330.0, 1.414, 2.0, 0.6, 0.22)
        shimmer(o, 0.12, 0.5, 2200.0, 450.0, 0.38, fadeIn = 0.03, fadeOut = 0.3)
        return reverb(o, 0.35)
    }

    private fun reactorUp(): DoubleArray {
        val o = buffer(1.0)
        engine(o, 0.0, 0.6, 50.0, 100.0, 0.3, 0.9, whine = 0.1, throb = 0.2)
        crackle(o, 0.05, 0.55, 12, 0.5, seed = 11)
        boom(o, 0.56, 0.3, 80.0, 52.0, 0.8)
        chord(o, 0.56, 0.4, doubleArrayOf(110.0, 146.8, 164.8, 220.0), 0.75, attack = 0.03, release = 0.22)
        return reverb(o, 0.25)
    }

    private fun reactorDown(): DoubleArray {
        val o = buffer(0.9)
        chord(o, 0.0, 0.18, doubleArrayOf(110.0, 146.8, 164.8, 220.0), 0.7, attack = 0.01, release = 0.08)
        engine(o, 0.12, 0.55, 100.0, 40.0, 0.9, 0.2, whine = 0.08, throb = 0.2)
        crackle(o, 0.15, 0.4, 6, 0.4, seed = 23)
        clank(o, 0.66, 0.7, 0.5)
        return reverb(o, 0.25)
    }

    private fun neuralUp(): DoubleArray {
        val o = buffer(0.98)
        for (k in 0 until 14) bell(o, k * 0.026, 0.07, 392.0 * (1175.0 / 392.0).pow(k / 13.0), 2.0, 1.2, 0.32, 0.03)
        chord(o, 0.34, 0.58, doubleArrayOf(146.8, 220.0, 261.6, 329.6), 0.7, attack = 0.12, release = 0.22)
        return reverb(o, 0.4)
    }

    private fun neuralDown(): DoubleArray {
        val o = buffer(0.9)
        chord(o, 0.0, 0.3, doubleArrayOf(146.8, 220.0, 261.6, 329.6), 0.65, attack = 0.02, release = 0.15)
        for (k in 0 until 10) bell(o, 0.22 + k * 0.03, 0.07, 1047.0 * (330.0 / 1047.0).pow(k / 9.0), 2.0, 1.2, 0.3, 0.03)
        return reverb(o, 0.4)
    }

    private fun scanUp(): DoubleArray {
        val o = buffer(0.95)
        shimmer(o, 0.0, 0.46, 350.0, 2900.0, 0.8, fadeIn = 0.1, fadeOut = 0.04, resonance = 0.12)
        boom(o, 0.46, 0.25, 90.0, 55.0, 0.6)
        bell(o, 0.46, 0.35, 247.0, 2.76, 2.5, 0.7, 0.12)
        bell(o, 0.6, 0.32, 370.0, 2.76, 2.0, 0.55, 0.11)
        return reverb(o, 0.3)
    }

    private fun scanDown(): DoubleArray {
        val o = buffer(0.85)
        shimmer(o, 0.0, 0.42, 2900.0, 350.0, 0.8, fadeIn = 0.03, fadeOut = 0.1, resonance = 0.12)
        bell(o, 0.42, 0.35, 247.0, 2.76, 2.5, 0.65, 0.14)
        return reverb(o, 0.3)
    }

    private fun armorUp(): DoubleArray {
        val o = buffer(0.88)
        hiss(o, 0.0, 0.1, 2600.0, 1800.0, 0.3, fadeIn = 0.005, fadeOut = 0.05)
        motor(o, 0.03, 0.18, 150.0, 240.0, 38.0, 52.0, 0.7, 0.9, depth = 0.6, whine = 0.12)
        clank(o, 0.22, 0.8, 0.7)
        motor(o, 0.27, 0.16, 190.0, 320.0, 44.0, 66.0, 0.8, 1.0, depth = 0.6, whine = 0.12)
        clank(o, 0.44, 0.6, 1.0)
        boom(o, 0.44, 0.25, 90.0, 50.0, 0.7)
        chord(o, 0.5, 0.3, doubleArrayOf(82.4, 123.5, 164.8), 0.55, attack = 0.01, release = 0.15)
        return reverb(o, 0.12)
    }

    private fun armorDown(): DoubleArray {
        val o = buffer(0.8)
        clank(o, 0.0, 0.8, 0.6)
        motor(o, 0.05, 0.2, 320.0, 190.0, 66.0, 44.0, 1.0, 0.8, depth = 0.6, whine = 0.12)
        motor(o, 0.28, 0.18, 240.0, 140.0, 52.0, 36.0, 0.9, 0.6, depth = 0.6, whine = 0.12)
        hiss(o, 0.46, 0.15, 1800.0, 2800.0, 0.3, fadeIn = 0.01, fadeOut = 0.1)
        clank(o, 0.5, 0.6, 0.9)
        return reverb(o, 0.12)
    }

    private fun sonarUp(): DoubleArray {
        val o = buffer(1.1)
        bell(o, 0.0, 0.7, 392.0, 0.5, 2.2, 1.0, 0.35)
        bell(o, 0.42, 0.6, 392.0, 0.5, 1.8, 0.42, 0.3)
        return reverb(o, 0.45)
    }

    private fun sonarDown(): DoubleArray {
        val o = buffer(1.0)
        bell(o, 0.0, 0.65, 329.6, 0.5, 2.0, 1.0, 0.32)
        bell(o, 0.38, 0.55, 329.6, 0.5, 1.6, 0.35, 0.28)
        return reverb(o, 0.45)
    }

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

    /**
     * A glassy FM tone (a sci-fi interface "ting", not a beep): carrier [f], modulator at
     * [ratio]× with [index] depth that fades faster than the tone, so it starts bright and
     * settles pure. Decays with time constant [decay].
     */
    private fun bell(o: DoubleArray, at: Double, dur: Double, f: Double, ratio: Double, index: Double, gain: Double, decay: Double) {
        val i0 = (at * RATE).toInt()
        val n = (dur * RATE).toInt()
        for (k in 0 until n) {
            val i = i0 + k
            if (i >= o.size) break
            val t = k.toDouble() / RATE
            val env = min(1.0, t / 0.002) * exp(-t / decay) * min(1.0, (dur - t) / 0.02)
            val idx = index * exp(-t / (decay * 0.6))
            o[i] += gain * env * sin(2 * PI * f * t + idx * sin(2 * PI * f * ratio * t))
        }
    }

    /** A holographic shimmer / scan sweep: resonant band-passed noise gliding [c0]→[c1] Hz (lower [resonance] = more whistling). */
    private fun shimmer(o: DoubleArray, at: Double, dur: Double, c0: Double, c1: Double, gain: Double, fadeIn: Double, fadeOut: Double, resonance: Double = 0.25) =
        noise(o, at, dur, c0, c1, resonance, gain, seed = (at * 1e3).toInt() + 97) { t, d -> gate(t, d, fadeIn, fadeOut) }

    /** A low impact: a falling sine pushed into saturation so its harmonics carry the weight on a small speaker. */
    private fun boom(o: DoubleArray, at: Double, dur: Double, f0: Double, f1: Double, gain: Double) {
        val i0 = (at * RATE).toInt()
        val n = (dur * RATE).toInt()
        var ph = 0.0
        for (k in 0 until n) {
            val i = i0 + k
            if (i >= o.size) break
            val t = k.toDouble() / RATE
            ph += 2 * PI * f0 * (f1 / f0).pow(t / dur) / RATE
            val env = min(1.0, t / 0.004) * exp(-t / (dur * 0.35))
            o[i] += gain * env * kotlin.math.tanh(3.5 * sin(ph))
        }
    }

    /** Electric crackle: [count] tiny sparks scattered over the window. */
    private fun crackle(o: DoubleArray, at: Double, dur: Double, count: Int, gain: Double, seed: Int) {
        var state = seed * 2_654_435_761L + 1
        fun next(): Double { state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L; return ((state ushr 11) and 0xFFFFF).toDouble() / 0xFFFFF }
        repeat(count) { click(o, at + next() * dur, 1800.0 + next() * 2600.0, gain * (0.4 + 0.6 * next())) }
    }

    /** A small room: four damped comb filters and two all-passes (Freeverb-style), mixed in by [mix]. */
    private fun reverb(o: DoubleArray, mix: Double, feedback: Double = 0.8): DoubleArray {
        val dry = o.copyOf()
        val wet = DoubleArray(o.size)
        for (len in intArrayOf(1116, 1277, 1422, 1557)) {
            val buf = DoubleArray(len); var idx = 0; var store = 0.0
            for (i in o.indices) {
                val y = buf[idx]
                store = y * 0.75 + store * 0.25
                buf[idx] = dry[i] + store * feedback
                idx = if (idx + 1 == len) 0 else idx + 1
                wet[i] += y
            }
        }
        for (len in intArrayOf(556, 225)) {
            val buf = DoubleArray(len); var idx = 0
            for (i in o.indices) {
                val b = buf[idx]
                val x = wet[i]
                wet[i] = -x + b
                buf[idx] = x + b * 0.5
                idx = if (idx + 1 == len) 0 else idx + 1
            }
        }
        for (i in o.indices) o[i] = dry[i] * (1 - mix * 0.5) + wet[i] * mix * 0.25
        return o
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
