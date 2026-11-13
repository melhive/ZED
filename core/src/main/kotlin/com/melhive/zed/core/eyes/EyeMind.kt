package com.melhive.zed.core.eyes

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** What the eyes express. */
enum class EyeMood { NEUTRAL, HAPPY, CURIOUS, CONCERNED, SURPRISED, AMUSED, FOCUSED }

/** What ZED is doing right now. */
enum class EyeActivity { IDLE, LISTENING, THINKING, SPEAKING, WORKING, DONE, ERROR }

/** What the eyes are looking at. */
enum class GazeMode { OBJECT, FACE, SEARCHING, WANDERING }

/**
 * The behaviour of the eyes: where they look, when they blink, how the lids and
 * brows sit for each mood. Call [update] every frame and draw [left] and [right].
 *
 * Looking priority: something being shown → your face → searching for you → wandering.
 */
class EyeMind(seed: Long = System.nanoTime()) {
    private val rnd = Random(seed)

    val left = EyePose()
    val right = EyePose()
    /** Head tilt in radians. */
    var roll = 0f
        private set
    var gazeMode = GazeMode.WANDERING
        private set
    var drowsy = false
        private set

    // ---- What the screen tells the eyes ----
    var activity = EyeActivity.IDLE
    var mood = EyeMood.NEUTRAL
    /** Microphone loudness 0..1 while listening. */
    var micLevel = 0f
    /** A face is in the camera's view right now, at [faceX],[faceY] in gaze terms (-1..1). */
    var faceSeen = false
    var faceX = 0f
    var faceY = 0f
    /** Something has come into view that isn't the face, at [objectX],[objectY]. */
    var objectSeen = false
    var objectX = 0f
    var objectY = 0f
    /** ZED was asked to look at something ("what is this?"): the object wins over the face. */
    var attending = false

    // ---- Inner state ----
    private var time = 0f
    private var faceHeld = false
    private var faceOnFor = 0f
    private var faceOffFor = 99f
    private var everSeen = false
    private var lostAt = -99f
    private var lastFaceX = 0f
    private var lastFaceY = 0f
    private var objectOnFor = 0f
    private var noticed = false

    private var targetX = 0f
    private var targetY = 0f
    private var gx = 0f
    private var gy = 0f
    private var jitterX = 0f
    private var jitterY = 0f
    private var nextJitter = 0f
    private var nextMove = 0f
    private var awayUntil = 0f
    private var nextAway = 5f
    private var awayX = 0f
    private var awayY = 0f
    private var searchStep = -1

    private var nextBlink = 2.5f
    private var blinkAt = -9f
    private var blinkLen = 0.16f
    private var secondBlink = -1f
    private var nextMembrane = 14f
    private var membraneAt = -9f

    private var speech = 0f
    private var greet = 0f
    private var quietFor = 0f
    private var tiltUntil = 0f
    private var nextTilt = 7f
    private var tiltSide = 1f
    private var spin = 0f
    private var lastActivity = EyeActivity.IDLE

    private val v = FloatArray(N)
    private val goal = FloatArray(N)
    private var tintNow = CYAN

    init {
        preset(EyeMood.NEUTRAL, goal)
        goal.copyInto(v)
    }

    /** A word was just spoken: the eyes pulse with the voice. */
    fun speechPulse() { speech = 1f }

    /** Advances the eyes by [dt] seconds. */
    fun update(dt: Float) {
        val d = dt.coerceIn(0f, 0.1f)
        time += d
        senses(d)
        look(d)
        blinks()
        express(d)
        write()
    }

    private fun senses(d: Float) {
        // A face must stay a moment to count, and may vanish a moment without being lost.
        if (faceSeen) { faceOnFor += d; faceOffFor = 0f; lastFaceX = faceX; lastFaceY = faceY } else { faceOffFor += d; faceOnFor = 0f }
        if (!faceHeld && faceOnFor > 0.25f) {
            faceHeld = true
            if (time - lostAt > 2f) { greet = 1f; blinkSoon(0.12f) }
            everSeen = true
        } else if (faceHeld && faceOffFor > 0.9f) {
            faceHeld = false; lostAt = time; searchStep = -1
        }
        if (objectSeen) objectOnFor += d else objectOnFor = 0f
        noticed = objectOnFor > 0.35f

        if (activity != lastActivity) {
            if (lastActivity == EyeActivity.IDLE || drowsy) blinkSoon(0.05f)
            lastActivity = activity
            nextMove = 0f
        }
        val quiet = activity == EyeActivity.IDLE && !faceHeld && !attending && !noticed
        quietFor = if (quiet) quietFor + d else 0f
        drowsy = quietFor > 22f
        speech *= exp(-d * 7f)
        greet *= exp(-d * 2.2f)
    }

    private fun look(d: Float) {
        val mode = when {
            attending || noticed -> GazeMode.OBJECT
            faceHeld -> GazeMode.FACE
            everSeen && time - lostAt < 4.6f -> GazeMode.SEARCHING
            else -> GazeMode.WANDERING
        }
        if (mode != gazeMode) { gazeMode = mode; nextMove = 0f; awayUntil = 0f; nextAway = time + range(3f, 6f) }
        var tx = targetX; var ty = targetY
        when (mode) {
            GazeMode.OBJECT -> {
                // What is being shown, or the middle of the camera's view when it can't be picked out.
                if (objectSeen) { tx = objectX; ty = objectY } else { tx = 0f; ty = 0.05f }
                // Glance back at you while answering.
                if (activity == EyeActivity.SPEAKING && faceHeld && attending) { tx = faceX; ty = faceY }
            }
            GazeMode.FACE -> {
                if (time > nextAway && time > awayUntil) {
                    // Eye contact breaks away now and then; more while thinking.
                    val thinking = activity == EyeActivity.THINKING || activity == EyeActivity.WORKING
                    awayUntil = time + if (thinking) range(0.9f, 2f) else range(0.35f, 1f)
                    nextAway = awayUntil + when (activity) {
                        EyeActivity.THINKING, EyeActivity.WORKING -> range(0.7f, 1.8f)
                        EyeActivity.SPEAKING -> range(5f, 10f)
                        else -> range(4f, 8f)
                    }
                    val sideways = if (rnd.nextBoolean()) 1f else -1f
                    awayX = (faceX + sideways * range(0.35f, 0.7f)).coerceIn(-0.9f, 0.9f)
                    awayY = (faceY + if (thinking) -range(0.25f, 0.55f) else range(-0.25f, 0.3f)).coerceIn(-0.8f, 0.8f)
                }
                if (time < awayUntil) { tx = awayX; ty = awayY }
                else if (abs(faceX - tx) > 0.07f || abs(faceY - ty) > 0.07f || time >= nextMove) { tx = faceX; ty = faceY; nextMove = time + 0.5f }
            }
            GazeMode.SEARCHING -> {
                val since = time - lostAt
                val step = if (since < 1.3f) 0 else 1 + ((since - 1.3f) / 0.8f).toInt()
                if (step != searchStep) {
                    searchStep = step
                    if (step == 0) { tx = lastFaceX; ty = lastFaceY }
                    else { tx = (if (step % 2 == 1) -1f else 1f) * range(0.45f, 0.8f) * (if (lastFaceX < 0f) 1f else -1f); ty = range(-0.25f, 0.2f) }
                }
            }
            GazeMode.WANDERING -> if (time >= nextMove) {
                val thinking = activity == EyeActivity.THINKING || activity == EyeActivity.WORKING
                val ahead = when (activity) { EyeActivity.LISTENING, EyeActivity.SPEAKING -> 0.65f; else -> 0.35f }
                when {
                    drowsy -> { tx = range(-0.25f, 0.25f); ty = range(0.15f, 0.45f); nextMove = time + range(3f, 6f) }
                    thinking -> { tx = (if (rnd.nextBoolean()) 1f else -1f) * range(0.3f, 0.7f); ty = -range(0.3f, 0.6f); nextMove = time + range(0.9f, 2.2f) }
                    rnd.nextFloat() < ahead -> { tx = 0f; ty = 0f; nextMove = time + range(1.4f, 3.6f) }
                    else -> { tx = range(-0.75f, 0.75f); ty = range(-0.4f, 0.4f); nextMove = time + range(1.1f, 3.2f) }
                }
            }
        }
        // A big jump of the eyes often comes with a blink.
        if (abs(tx - targetX) + abs(ty - targetY) > 0.75f && rnd.nextFloat() < 0.5f) blinkSoon(0.02f)
        targetX = tx; targetY = ty

        // Tiny movements while holding a look: living eyes are never perfectly still.
        if (time >= nextJitter) {
            jitterX = range(-0.018f, 0.018f); jitterY = range(-0.014f, 0.014f)
            nextJitter = time + range(0.22f, 0.6f)
        }
        // Eyes jump rather than glide: a fast settle toward the target.
        val k = 1f - exp(-d * if (drowsy) 9f else 34f)
        gx += (targetX + jitterX - gx) * k
        gy += (targetY + jitterY - gy) * k
    }

    private fun blinkSoon(delay: Float) { if (time - blinkAt > 0.5f) nextBlink = min(nextBlink, time + delay) }

    private fun blinks() {
        if (time >= nextBlink && time - blinkAt > blinkLen) {
            blinkAt = time
            blinkLen = if (drowsy) 0.34f else range(0.14f, 0.19f)
            // Sometimes twice in a row.
            secondBlink = if (!drowsy && rnd.nextFloat() < 0.15f) time + blinkLen + 0.09f else -1f
            nextBlink = time + when {
                drowsy -> range(1.6f, 3.6f)
                activity == EyeActivity.LISTENING -> range(3f, 7f)
                else -> range(2f, 6f)
            }
        }
        if (secondBlink > 0f && time >= secondBlink) { blinkAt = time; secondBlink = -1f }
        if (time >= nextMembrane && time - blinkAt > 0.6f) { membraneAt = time; nextMembrane = time + range(11f, 24f) }
    }

    /** 0 open … 1 shut: closes fast, opens a little slower. */
    private fun blinkAmount(): Float {
        val t = (time - blinkAt) / blinkLen
        if (t < 0f || t > 1f) return 0f
        return if (t < 0.38f) smooth(t / 0.38f) else 1f - smooth((t - 0.38f) / 0.62f)
    }

    /** The sideways inner lid: sweeps across, holds for a moment, slides back. */
    private fun membraneAmount(): Float {
        val t = time - membraneAt
        return when {
            t < 0f || t > 0.5f -> 0f
            t < 0.17f -> smooth(t / 0.17f)
            t < 0.24f -> 1f
            else -> 1f - smooth((t - 0.24f) / 0.26f)
        }
    }

    private fun express(d: Float) {
        preset(mood, goal)
        var tint = TINTS[mood.ordinal]
        var tilt = ROLLS[mood.ordinal]
        when (activity) {
            EyeActivity.THINKING -> { goal[UPPER] += 0.07f; goal[BROW_TILT] += 0.18f; goal[PUPIL] -= 0.06f; tint = EyeColor.mix(tint, VIOLET, 0.4f) }
            EyeActivity.WORKING -> { goal[UPPER] += 0.1f; goal[LOWER] += 0.1f; goal[BROW_TILT] += 0.35f; goal[BROW] -= 0.15f; goal[PUPIL] -= 0.15f; tint = EyeColor.mix(tint, AMBER, 0.3f) }
            EyeActivity.LISTENING -> { goal[UPPER] -= 0.05f; goal[PUPIL] += 0.08f; goal[BROW] += 0.08f }
            EyeActivity.SPEAKING -> { goal[BROW] += 0.07f * speech; goal[PUPIL] += 0.04f * speech }
            EyeActivity.DONE -> { goal[LOWER] += 0.2f; goal[BROW] += 0.15f }
            EyeActivity.ERROR -> { goal[BROW_TILT] -= 0.5f; goal[UPPER] += 0.06f; goal[PUPIL] += 0.05f; tint = EyeColor.mix(tint, RED, 0.45f) }
            EyeActivity.IDLE -> Unit
        }
        if (gazeMode == GazeMode.OBJECT) {
            // Focusing on what is shown: pupils tighten, brows come down.
            goal[PUPIL] = min(goal[PUPIL], 0.24f); goal[BROW_TILT] += 0.3f; goal[BROW] -= 0.1f; goal[UPPER] += 0.04f
            if (!attending) { goal[BROW] += 0.35f; goal[BROW_TILT] -= 0.3f; goal[PUPIL] = 0.55f; tilt = 0.07f } // noticed, not asked yet: curious
        }
        if (gazeMode == GazeMode.SEARCHING) { goal[BROW] += 0.25f; goal[UPPER] -= 0.06f }
        // Recognition when you come into view.
        goal[PUPIL] += 0.3f * greet; goal[BROW] += 0.45f * greet; goal[UPPER] -= 0.12f * greet; goal[PUFF] += 0.3f * greet
        if (drowsy) {
            goal[UPPER] = max(goal[UPPER], 0.56f + 0.05f * sin(time * 0.6f)); goal[BROW] -= 0.25f; goal[LOWER] += 0.05f; goal[PUFF] = 0f
            tint = EyeColor.scale(tint, 0.8f)
        }
        // Lids follow the gaze: looking down lowers the upper lid, looking up lifts it.
        goal[UPPER] += 0.12f * gy

        // A curious tilt of the head now and then while listening.
        if (activity == EyeActivity.LISTENING && time > nextTilt) { tiltUntil = time + range(1.6f, 3f); nextTilt = tiltUntil + range(6f, 13f); tiltSide = if (rnd.nextBoolean()) 1f else -1f }
        if (time < tiltUntil && tilt == 0f) tilt = 0.06f
        val rollGoal = if (drowsy) 0f else tilt * tiltSide
        roll += (rollGoal - roll) * (1f - exp(-d * 4.5f))

        val ease = 1f - exp(-d * 9f)
        for (i in 0 until N) {
            // Pupils are slower than lids, and tighten faster than they widen.
            val k = if (i == PUPIL) 1f - exp(-d * if (goal[i] < v[i]) 11f else 5f) else ease
            v[i] += (goal[i] - v[i]) * k
        }
        tintNow = EyeColor.mix(tintNow, tint, 1f - exp(-d * 4f))
        spin += d * (0.22f + (if (activity == EyeActivity.THINKING || activity == EyeActivity.WORKING) 0.55f else 0f) + 0.7f * speech)
    }

    private fun write() {
        val blink = blinkAmount()
        val membrane = membraneAmount()
        val near = gazeMode == GazeMode.FACE || gazeMode == GazeMode.OBJECT
        val glow = when (activity) {
            EyeActivity.SPEAKING -> 0.34f + 0.55f * speech
            EyeActivity.LISTENING -> 0.3f + 0.45f * micLevel.coerceIn(0f, 1f)
            EyeActivity.THINKING, EyeActivity.WORKING -> 0.3f + 0.12f * sin(time * 3.2f)
            else -> if (drowsy) 0.14f else 0.26f
        }
        for (side in 0..1) {
            val p = if (side == 0) left else right
            // Both eyes turn slightly toward each other when looking at something close.
            val converge = if (near) 0.05f else 0.015f
            p.gazeX = (gx + if (side == 0) converge else -converge).coerceIn(-1f, 1f)
            p.gazeY = gy.coerceIn(-1f, 1f)
            p.pupil = v[PUPIL].coerceIn(0f, 1f)
            val lower = v[LOWER].coerceIn(0f, 1f)
            p.upper = (v[UPPER] + (1f - v[UPPER]) * blink).coerceIn(0f, 1f)
            p.lower = lower + (max(lower, 0.7f) - lower) * blink
            p.lidTilt = v[LID_TILT]
            // One brow higher for a doubtful or curious look.
            p.browRaise = (v[BROW] + if (side == 0) v[BROW_ODD] else -v[BROW_ODD] * 0.4f).coerceIn(-1f, 1f)
            p.browTilt = v[BROW_TILT].coerceIn(-1f, 1f)
            p.puff = v[PUFF].coerceIn(-0.5f, 1f)
            p.membrane = membrane
            p.spin = spin
            p.glow = glow.coerceIn(0f, 1f)
            p.energy = (0.45f + 0.5f * speech + (if (activity == EyeActivity.LISTENING) 0.3f * micLevel else 0f) - if (drowsy) 0.25f else 0f).coerceIn(0f, 1f)
            p.tint = tintNow
        }
    }

    private fun range(a: Float, b: Float): Float = a + (b - a) * rnd.nextFloat()
    private fun smooth(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3f - 2f * x) }

    companion object {
        private const val PUPIL = 0
        private const val UPPER = 1
        private const val LOWER = 2
        private const val LID_TILT = 3
        private const val BROW = 4
        private const val BROW_TILT = 5
        private const val PUFF = 6
        private const val BROW_ODD = 7
        private const val N = 8

        const val CYAN = 0x5CE1FF
        private const val VIOLET = 0x7B6CFF
        private const val AMBER = 0xFFB547
        private const val RED = 0xFF5C7A

        /** Iris colour per mood, in [EyeMood] order. */
        private val TINTS = intArrayOf(CYAN, 0x5CFFE0, CYAN, 0x7FA8FF, 0xB4F1FF, 0x62F0E6, 0x4FC4FF)
        /** Head tilt per mood, radians. */
        private val ROLLS = floatArrayOf(0f, 0f, 0.085f, 0f, 0f, 0.045f, 0f)

        /** The resting pose of each mood: pupil, upper lid, lower lid, lid tilt, brow, brow tilt, puff, odd brow. */
        fun preset(mood: EyeMood, out: FloatArray) {
            val p = when (mood) {
                EyeMood.NEUTRAL -> floatArrayOf(0.50f, 0.20f, 0.06f, 0.10f, 0.00f, 0.00f, 0.00f, 0.00f)
                EyeMood.HAPPY -> floatArrayOf(0.64f, 0.15f, 0.56f, 0.02f, 0.35f, -0.30f, 0.25f, 0.00f)
                EyeMood.CURIOUS -> floatArrayOf(0.72f, 0.08f, 0.04f, 0.05f, 0.45f, -0.10f, 0.15f, 0.28f)
                EyeMood.CONCERNED -> floatArrayOf(0.58f, 0.26f, 0.14f, -0.16f, 0.12f, -0.75f, 0.00f, 0.00f)
                EyeMood.SURPRISED -> floatArrayOf(0.86f, 0.00f, 0.00f, 0.00f, 1.00f, -0.15f, 1.00f, 0.00f)
                EyeMood.AMUSED -> floatArrayOf(0.56f, 0.28f, 0.42f, 0.08f, 0.10f, 0.00f, 0.10f, 0.42f)
                EyeMood.FOCUSED -> floatArrayOf(0.22f, 0.33f, 0.22f, 0.26f, -0.35f, 0.80f, -0.20f, 0.00f)
            }
            p.copyInto(out)
        }
    }
}
