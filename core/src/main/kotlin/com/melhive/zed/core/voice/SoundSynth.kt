package com.melhive.zed.core.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * ZED's LIVE sounds, synthesized in code (no audio files): short, bright,
 * friendly tones like the ones phone assistants make when they wake — soft
 * mallet and glass notes, a pop, a light sparkle.
 *
 * Nothing dark or eerie: no low rumble, no sliding or sinking pitch, no long
 * echo, no detuned chords. Notes are clear pitches that start softly and ring
 * out quickly. Every sound is filtered so its energy lands above ~350 Hz,
 * where phone speakers can play it.
 */
object SoundSynth {
    const val RATE = 44_100

    enum class Style(val id: String, val label: String, val description: String) {
        DUO("duo", "Duo", "Two soft mallet notes, the second higher"),
        GLASS("glass", "Glass", "Two clear glass notes a fifth apart"),
        POP("pop", "Pop", "A soft rounded pop with a faint high ring"),
        BLOOM("bloom", "Bloom", "A bright chord that opens quickly and fades"),
        SPARK("spark", "Spark", "A breath of air and one bright note"),
        TAP("tap", "Tap", "Two quick, light taps"),
        LIFT("lift", "Lift", "Three quick notes stepping upward"),
        HALO("halo", "Halo", "One warm note joined by its octave"),
        NOVA("nova", "Nova", "A low note answered by a high one"),
        ECHO("echo", "Echo", "One clear note with two soft echoes"),
        OFF("off", "Off", "No sound");

        companion object {
            /** Unknown ids (including every retired sound) fall back to Duo. */
            fun parse(id: String?) = entries.firstOrNull { it.id == id } ?: DUO
        }
    }

    /** 16-bit mono PCM at [RATE]; empty for [Style.OFF]. */
    fun render(style: Style, start: Boolean): ShortArray {
        val out = when (style) {
            Style.DUO -> buffer(0.5).also { o -> if (start) { mallet(o, 0.0, 392.0, 0.9); mallet(o, 0.13, 523.3, 1.0) } else { mallet(o, 0.0, 523.3, 0.9); mallet(o, 0.13, 392.0, 1.0) } }
            Style.GLASS -> buffer(0.6).also { o -> if (start) { glass(o, 0.0, 523.3, 0.8); glass(o, 0.14, 784.0, 1.0) } else { glass(o, 0.0, 784.0, 0.8); glass(o, 0.14, 523.3, 1.0) } }
            Style.POP -> buffer(0.36).also { o ->
                if (start) { pop(o, 0.0, 760.0, 560.0, 1.0); mallet(o, 0.05, 1174.7, 0.22, decay = 0.09) }
                else { pop(o, 0.0, 620.0, 440.0, 1.0); mallet(o, 0.05, 880.0, 0.16, decay = 0.08) }
            }
            Style.BLOOM -> buffer(0.62).also { o ->
                val notes = if (start) doubleArrayOf(523.3, 659.3, 784.0) else doubleArrayOf(392.0, 523.3, 659.3)
                for ((i, f) in notes.withIndex()) voice(o, 0.012 * i, 0.5, f, f, 0.6, attack = 0.05, release = 0.38, bright = 0.45)
            }
            Style.SPARK -> buffer(0.52).also { o ->
                if (start) { shimmer(o, 0.0, 0.16, 2400.0, 3600.0, 0.22, fadeIn = 0.05, fadeOut = 0.08, resonance = 0.6); glass(o, 0.1, 1318.5, 0.9, decay = 0.13) }
                else { glass(o, 0.0, 987.8, 0.9, decay = 0.13); shimmer(o, 0.08, 0.16, 3200.0, 2400.0, 0.16, fadeIn = 0.03, fadeOut = 0.1, resonance = 0.6) }
            }
            Style.TAP -> buffer(0.34).also { o ->
                if (start) { mallet(o, 0.0, 880.0, 0.9, decay = 0.05); mallet(o, 0.1, 880.0, 1.0, decay = 0.06) }
                else mallet(o, 0.0, 698.5, 1.0, decay = 0.07)
            }
            Style.LIFT -> buffer(0.56).also { o ->
                val notes = if (start) doubleArrayOf(587.3, 659.3, 784.0) else doubleArrayOf(784.0, 659.3, 587.3)
                for ((i, f) in notes.withIndex()) mallet(o, 0.09 * i, f, 0.8 + 0.1 * i, decay = 0.1)
            }
            Style.HALO -> buffer(0.6).also { o ->
                if (start) { voice(o, 0.0, 0.5, 440.0, 440.0, 0.8, attack = 0.03, release = 0.4, bright = 0.4); voice(o, 0.09, 0.42, 880.0, 880.0, 0.45, attack = 0.03, release = 0.34, bright = 0.2) }
                else { voice(o, 0.0, 0.4, 880.0, 880.0, 0.45, attack = 0.02, release = 0.32, bright = 0.2); voice(o, 0.09, 0.45, 440.0, 440.0, 0.8, attack = 0.03, release = 0.36, bright = 0.4) }
            }
            Style.NOVA -> buffer(0.5).also { o ->
                if (start) { mallet(o, 0.0, 349.2, 0.9, decay = 0.09); glass(o, 0.12, 1046.5, 0.9, decay = 0.14) }
                else { glass(o, 0.0, 1046.5, 0.8, decay = 0.1); mallet(o, 0.12, 349.2, 1.0, decay = 0.12) }
            }
            Style.ECHO -> buffer(0.6).also { o ->
                val f = if (start) 659.3 else 523.3
                glass(o, 0.0, f, 1.0, decay = 0.1); glass(o, 0.13, f, 0.42, decay = 0.1); glass(o, 0.26, f, 0.18, decay = 0.1)
            }
            Style.OFF -> return ShortArray(0)
        }
        return finish(reverb(out, 0.08), 0.6)
    }

    /** The instant "got it" when ZED takes a request in LIVE: one short, soft tick. */
    fun ack(): ShortArray {
        val o = buffer(0.12)
        mallet(o, 0.0, 587.3, 1.0, decay = 0.035)
        return finish(o, 0.36)
    }

    /** Band-limits to where phone speakers play (steep high-pass, dark low-pass), normalizes and fades the ends. */
    private fun finish(out: DoubleArray, level: Double): ShortArray {
        highPass(out, 330.0); highPass(out, 330.0)
        lowPass(out, 7000.0)
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

    /** A soft mallet note (like a marimba bar): a clear pitch with a gentle knock that rings out in a fraction of a second. */
    private fun mallet(o: DoubleArray, at: Double, f: Double, gain: Double, decay: Double = 0.11) {
        val i0 = (at * RATE).toInt()
        val n = (decay * 7 * RATE).toInt()
        for (k in 0 until n) {
            val i = i0 + k
            if (i >= o.size) break
            val t = k.toDouble() / RATE
            val w = 2 * PI * f * t
            val rise = min(1.0, t / 0.003)
            // The overtones die away faster than the note itself, so it starts bright and ends pure.
            o[i] += gain * rise * (exp(-t / decay) * sin(w) + 0.3 * exp(-t / (decay * 0.35)) * sin(4 * w) + 0.12 * exp(-t / (decay * 0.2)) * sin(9.2 * w))
        }
    }

    /** A clear glass note: a pure pitch with a faint shimmer just above it. */
    private fun glass(o: DoubleArray, at: Double, f: Double, gain: Double, decay: Double = 0.16) {
        val i0 = (at * RATE).toInt()
        val n = (decay * 7 * RATE).toInt()
        for (k in 0 until n) {
            val i = i0 + k
            if (i >= o.size) break
            val t = k.toDouble() / RATE
            val w = 2 * PI * f * t
            val rise = min(1.0, t / 0.004)
            o[i] += gain * rise * (exp(-t / decay) * sin(w) + 0.22 * exp(-t / (decay * 0.6)) * sin(2 * w) + 0.1 * exp(-t / (decay * 0.3)) * sin(3.01 * w))
        }
    }

    /** A soft, rounded pop: a very short tone that dips in pitch as it ends, like a bubble. */
    private fun pop(o: DoubleArray, at: Double, f0: Double, f1: Double, gain: Double) {
        val i0 = (at * RATE).toInt()
        val n = (0.11 * RATE).toInt()
        var ph = 0.0
        for (k in 0 until n) {
            val i = i0 + k
            if (i >= o.size) break
            val t = k.toDouble() / RATE
            ph += 2 * PI * (f1 + (f0 - f1) * exp(-t / 0.018)) / RATE
            o[i] += gain * min(1.0, t / 0.002) * exp(-t / 0.03) * (sin(ph) + 0.25 * sin(2 * ph))
        }
    }

    /**
     * A rounded tone gliding [f0]→[f1]: a sine with a few soft overtones ([bright] 0 pure … 1 warm and full),
     * a smooth start and a long, gentle ending. This is what makes the sounds calm instead of beepy.
     */
    private fun voice(o: DoubleArray, at: Double, dur: Double, f0: Double, f1: Double, gain: Double, attack: Double, release: Double, bright: Double) {
        val i0 = (at * RATE).toInt()
        val n = (dur * RATE).toInt()
        var ph = 0.0
        for (k in 0 until n) {
            val i = i0 + k
            if (i >= o.size) break
            val t = k.toDouble() / RATE
            val x = t / dur
            // Ease the glide so the pitch settles instead of sliding at a constant rate.
            val e = x * x * (3 - 2 * x)
            ph += 2 * PI * f0 * (f1 / f0).pow(e) / RATE
            val up = min(1.0, t / attack); val down = min(1.0, (dur - t) / release)
            val env = (up * up * (3 - 2 * up)) * (down * down * (3 - 2 * down))
            val v = sin(ph) + bright * (0.42 * sin(2 * ph) + 0.22 * sin(3 * ph) + 0.1 * sin(4 * ph) + 0.05 * sin(5 * ph))
            o[i] += gain * env * v
        }
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
