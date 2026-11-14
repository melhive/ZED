package com.melhive.zed.core.eyes

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** How one eye looks at one moment. Everything is a plain number so it can be blended and tested. */
class EyePose {
    /** Where the eye looks, in screen terms: -1 left … 1 right, -1 up … 1 down. */
    var gazeX = 0f
    var gazeY = 0f
    /** 0 pin-point … 1 wide open. */
    var pupil = 0.5f
    /** Upper lid: 0 fully open … 1 closed onto the lower lid. */
    var upper = 0.2f
    /** Lower lid: 0 resting … 1 raised (a smile or a squint). */
    var lower = 0.06f
    /** Positive: the upper lid sits lower toward the middle of the face. */
    var lidTilt = 0.1f
    /** -1 lowered … 1 raised. */
    var browRaise = 0f
    /** Positive: inner end down (stern). Negative: inner end up (worried). */
    var browTilt = 0f
    /** Feather plates lifting: 0 flat … 1 puffed up. */
    var puff = 0f
    /** The inner lid that sweeps sideways: 0 away … 1 across the whole eye. */
    var membrane = 0f
    /** Rotation of the iris rings, radians. */
    var spin = 0f
    /** Light behind the eye: 0 dim … 1 bright. */
    var glow = 0.3f
    /** Brightness of the iris itself. */
    var energy = 0.5f
    /** Iris colour, 0xRRGGBB. */
    var tint = 0x5CE1FF
}

/**
 * A frame of drawing instructions: filled shapes, lines and clip regions made of points.
 * The phone draws it with Compose; tests draw the same list into a picture.
 */
class EyeFrame {
    class Op(
        val kind: Int, val start: Int, val count: Int, val color: Int, val width: Float, val closed: Boolean,
        val gx: Float, val gy: Float, val gr: Float, val colors: IntArray?, val stops: FloatArray?,
    )

    /** x,y pairs of every shape, one after another. */
    var pts = FloatArray(32768)
        private set
    private var size = 0
    private var mark = 0
    val ops = ArrayList<Op>(512)

    fun reset() { size = 0; mark = 0; ops.clear() }

    fun add(x: Float, y: Float) {
        if (size + 2 > pts.size) pts = pts.copyOf(pts.size * 2)
        pts[size++] = x; pts[size++] = y
    }

    private fun emit(kind: Int, color: Int, width: Float, closed: Boolean, gx: Float = 0f, gy: Float = 0f, gr: Float = 0f, colors: IntArray? = null, stops: FloatArray? = null) {
        val count = (size - mark) / 2
        if (count >= 2 || kind == UNCLIP) ops += Op(kind, mark, count, color, width, closed, gx, gy, gr, colors, stops)
        mark = size
    }

    fun fill(color: Int) = emit(FILL, color, 0f, true)
    fun fillRadial(gx: Float, gy: Float, gr: Float, colors: IntArray, stops: FloatArray) = emit(FILL, 0, 0f, true, gx, gy, gr, colors, stops)
    fun stroke(color: Int, width: Float, closed: Boolean = false) = emit(STROKE, color, width, closed)
    fun clip() = emit(CLIP, 0, 0f, true)
    fun unclip() = emit(UNCLIP, 0, 0f, false)

    companion object {
        const val FILL = 0
        const val STROKE = 1
        const val CLIP = 2
        const val UNCLIP = 3
    }
}

/** Colour helpers on 0xAARRGGBB integers. */
object EyeColor {
    fun argb(alpha: Float, rgb: Int): Int = ((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or (rgb and 0xFFFFFF)

    fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        val r = ((a shr 16 and 255) + ((b shr 16 and 255) - (a shr 16 and 255)) * u).toInt()
        val g = ((a shr 8 and 255) + ((b shr 8 and 255) - (a shr 8 and 255)) * u).toInt()
        val bl = ((a and 255) + ((b and 255) - (a and 255)) * u).toInt()
        return (r shl 16) or (g shl 8) or bl
    }

    fun scale(rgb: Int, k: Float): Int = mix(0x000000, rgb, k)
}

/**
 * Whose eyes ZED wears. The owl is the robotic original (feather plates, reactor rings); the others are
 * drawn after the real animal: the shape and slant of the eye, how much of it the iris fills, the pupil
 * (a slit for the cat), the iris's own colour and the brow above it.
 */
enum class EyeStyle(
    val id: String, val label: String,
    /** How much wider than tall the eye is. */
    val wide: Float,
    /** Iris radius, in eye radii. */
    val iris: Float,
    /** How far the outer corner is lifted, radians. */
    val slant: Float,
    val lidUpper: Float, val lidLower: Float,
    val slit: Boolean,
    /** The iris colour this eye has by nature (0xRRGGBB). */
    val tint: Int,
    /** The eyeball around the iris, and the skin or fur around the eye (inner and outer). */
    val ball: Int, val skin: Int, val skinEdge: Int,
) {
    OWL("owl", "Owl", 1.1f, 0.8f, 0f, 0f, 0f, false, 0x5CE1FF, 0x071826, 0x12203A, 0x0A1322),
    HUMAN("human", "Human", 1.5f, 0.6f, 0.03f, 0.10f, 0.10f, false, 0x9B7446, 0xE6ECF2, 0x2D2420, 0x120E0C),
    CAT("cat", "Cat", 1.3f, 0.88f, 0.2f, 0.04f, 0.02f, true, 0xB9E24A, 0x1A2208, 0x211C13, 0x0B0906),
    LION("lion", "Lion", 1.3f, 0.72f, 0.1f, 0.12f, 0.06f, false, 0xE9A53A, 0x2A1C08, 0x3B2A15, 0x160F08),
    WOLF("wolf", "Wolf", 1.42f, 0.66f, 0.2f, 0.08f, 0.04f, false, 0xE8D25E, 0x24210F, 0x2D3034, 0x0F1012),
    LEOPARD("leopard", "Leopard", 1.32f, 0.8f, 0.15f, 0.06f, 0.03f, false, 0xC6D86A, 0x1F2410, 0x3C3118, 0x15110A);

    companion object {
        fun parse(id: String?): EyeStyle = entries.firstOrNull { it.id == id?.lowercase()?.trim() } ?: OWL
        fun next(id: String?): EyeStyle = entries[(parse(id).ordinal + 1) % entries.size]
    }
}

/**
 * Draws ZED's two eyes: robotic owl eyes with a heavy feathered brow, dark lids,
 * a reactor iris and a deep pupil. All sizes are in "eye radii" and turned into
 * pixels here, so the picture is the same on every screen.
 */
class EyeArt {
    private var f = EyeFrame()
    private var cx = 0f
    private var cy = 0f
    private var s = 1f
    private var mirror = 1f
    private var cosR = 1f
    private var sinR = 0f

    /** Which eyes to draw. */
    var style = EyeStyle.OWL
    private var wide = WIDE

    private val top = FloatArray(LID_STEPS + 1)
    private val bottom = FloatArray(LID_STEPS + 1)

    /** Fills [frame] with both eyes for a screen of [w]×[h] pixels. [roll] tilts the head, radians. */
    fun draw(frame: EyeFrame, w: Float, h: Float, left: EyePose, right: EyePose, roll: Float) {
        f = frame
        frame.reset()
        // Landscape is limited by the height, portrait by the width.
        wide = style.wide
        val r = min(h * 0.27f, w / (4f * wide + 2.6f))
        val sep = r * (wide + 0.62f)
        val mx = w / 2f
        val my = if (h > w) h * 0.44f else h * 0.55f
        val c = cos(roll); val sn = sin(roll)
        // The head tilts around the point between the eyes.
        eye(left, mx - sep * c, my - sep * sn, r, 1f, roll)
        eye(right, mx + sep * c, my + sep * sn, r, -1f, roll)
    }

    // ---- Points in eye units → pixels ----

    private fun p(x: Float, y: Float) {
        val lx = x * mirror
        f.add(cx + (lx * cosR - y * sinR) * s, cy + (lx * sinR + y * cosR) * s)
    }

    private fun px(x: Float, y: Float): Float = cx + (x * mirror * cosR - y * sinR) * s
    private fun py(x: Float, y: Float): Float = cy + (x * mirror * sinR + y * cosR) * s

    private fun circle(x: Float, y: Float, r: Float, rx: Float = 1f, n: Int = 40) {
        for (i in 0 until n) { val a = (2.0 * PI * i / n).toFloat(); p(x + cos(a) * r * rx, y + sin(a) * r) }
    }

    private fun radial(x: Float, y: Float, r: Float, colors: IntArray, stops: FloatArray) = f.fillRadial(px(x, y), py(x, y), r * s, colors, stops)
    private fun line(color: Int, width: Float, closed: Boolean = false) = f.stroke(color, width * s, closed)

    /** Half-height of the socket at [x]: the lids are arcs that all meet at the two corners. */
    private fun arc(x: Float): Float = sqrt(max(0f, 1f - (x / wide) * (x / wide)))

    private fun lidX(i: Int): Float = -wide + 2f * wide * i / LID_STEPS

    private fun eye(pose: EyePose, ecx: Float, ecy: Float, radius: Float, side: Float, roll: Float) {
        // Cats, wolves and leopards carry the outer corner of the eye higher than the inner one.
        val turn = roll + style.slant * side
        cx = ecx; cy = ecy; s = radius; mirror = side; cosR = cos(turn); sinR = sin(turn)
        val tint = pose.tint
        val glow = pose.glow.coerceIn(0f, 1f)
        val energy = pose.energy.coerceIn(0f, 1f)
        val light = EyeColor.mix(tint, 0xFFFFFF, 0.45f)

        // 1. Light behind the eye.
        circle(0f, 0f, 2.6f)
        radial(0f, 0f, 2.6f, intArrayOf(EyeColor.argb(0.10f + 0.20f * glow, tint), EyeColor.argb(0.035f + 0.07f * glow, tint), EyeColor.argb(0f, tint)), floatArrayOf(0f, 0.5f, 1f))

        // 2. A hint of the feathered face disc: short lines fanning out below and beside the eye.
        val puff = pose.puff.coerceIn(-0.5f, 1f)
        if (style == EyeStyle.OWL) for (i in 0 until DISC_LINES) {
            val t = i / (DISC_LINES - 1f)
            val a = (-18f + 216f * t) * RAD
            val edge = min(t, 1f - t) * 5f
            val alpha = (0.07f + 0.13f * glow) * min(1f, edge) * (0.6f + 0.4f * sin(i * 2.4f))
            if (alpha <= 0.012f) continue
            val r0 = 1.24f + 0.03f * sin(i * 1.7f)
            val len = (0.15f + 0.08f * sin(i * 3.1f + 1f)) * (1f + 0.6f * puff)
            p(cos(a) * r0 * wide, sin(a) * r0); p(cos(a) * (r0 + len) * wide, sin(a) * (r0 + len))
            line(EyeColor.argb(alpha, tint), 0.016f)
            if (i % 2 == 0) {
                val r1 = 1.56f + 0.04f * sin(i * 0.9f)
                p(cos(a) * r1 * wide, sin(a) * r1); p(cos(a) * (r1 + len * 0.7f) * wide, sin(a) * (r1 + len * 0.7f))
                line(EyeColor.argb(alpha * 0.5f, tint), 0.012f)
            }
        }

        // 3. The socket: dark lid plates that the opening is cut into.
        circle(0f, 0f, 1.07f, wide)
        radial(0f, -0.25f, 1.7f * wide, intArrayOf(EyeColor.argb(1f, style.skin), EyeColor.argb(1f, style.skinEdge), EyeColor.argb(1f, EyeColor.scale(style.skinEdge, 0.5f))), floatArrayOf(0f, 0.6f, 1f))
        circle(0f, 0f, 1.07f, wide)
        line(EyeColor.argb(0.16f + 0.14f * glow, tint), 0.014f, closed = true)

        // The opening between the lids.
        val kLower = 1f - 0.6f * (pose.lower + style.lidLower * (1f - pose.lower)).coerceIn(0f, 1f)
        val kUpper = -1f + (pose.upper + style.lidUpper * (1f - pose.upper)).coerceIn(0f, 1f) * (kLower + 1f)
        val tilt = pose.lidTilt
        for (i in 0..LID_STEPS) {
            val x = lidX(i); val a = arc(x)
            bottom[i] = a * (kLower - 0.05f * tilt * x)
            top[i] = min(a * (kUpper + tilt * x), bottom[i])
        }
        val open = kLower - kUpper > 0.02f

        if (open) {
            for (i in 0..LID_STEPS) p(lidX(i), top[i])
            for (i in LID_STEPS downTo 0) p(lidX(i), bottom[i])
            f.clip()

            // The eyeball.
            circle(0f, 0f, 1.12f, wide)
            radial(0f, 0f, 1.2f * wide, intArrayOf(EyeColor.argb(1f, style.ball), EyeColor.argb(1f, EyeColor.scale(style.ball, 0.62f)), EyeColor.argb(1f, EyeColor.scale(style.ball, 0.22f))), floatArrayOf(0f, 0.7f, 1f))

            iris(pose, tint, light, energy)

            // Shadow the upper lid casts on the eye.
            for (j in 1..5) shadowBand(0.07f * j, 0.2f)

            // The inner lid sweeping across from the middle of the face.
            val m = pose.membrane.coerceIn(0f, 1f)
            if (m > 0.01f && style != EyeStyle.HUMAN) {
                val edge = wide + 0.2f - (2f * wide + 0.4f) * m
                for (i in 0..12) { val y = -1.1f + 2.2f * i / 12f; p(edge - 0.16f * (1f - y * y), y) }
                p(wide + 0.5f, 1.1f); p(wide + 0.5f, -1.1f)
                f.fill(EyeColor.argb(0.80f, 0x244E68))
                for (i in 0..12) { val y = -1.1f + 2.2f * i / 12f; p(edge - 0.16f * (1f - y * y), y) }
                line(EyeColor.argb(0.9f, light), 0.03f)
            }
            f.unclip()
        }

        // 4. Lid surfaces: seams that follow each lid's edge, like overlapping plates.
        for (j in 1..3) {
            val k = kUpper - 0.19f * j
            if (k < -0.97f) break
            for (i in 2..LID_STEPS - 2) { val x = lidX(i); p(x, arc(x) * (k + tilt * x * 0.6f)) }
            line(EyeColor.argb(0.55f - 0.12f * j, 0x24405F), 0.012f)
        }
        for (j in 1..2) {
            val k = kLower + 0.2f * j
            if (k > 0.97f) break
            for (i in 2..LID_STEPS - 2) { val x = lidX(i); p(x, arc(x) * k) }
            line(EyeColor.argb(0.5f - 0.15f * j, 0x24405F), 0.012f)
        }

        // The skin right at the edge of the opening: dark, thicker on top, with a thin lit lash line.
        for (i in 0..LID_STEPS) p(lidX(i), top[i])
        line(EyeColor.argb(1f, 0x060B14), 0.085f)
        for (i in 0..LID_STEPS) p(lidX(i), bottom[i])
        line(EyeColor.argb(1f, 0x060B14), 0.05f)
        for (i in 0..LID_STEPS) p(lidX(i), top[i])
        line(EyeColor.argb(0.50f + 0.35f * glow, light), 0.02f)
        for (i in 0..LID_STEPS) p(lidX(i), bottom[i])
        line(EyeColor.argb(0.22f + 0.2f * glow, tint), 0.014f)

        if (style == EyeStyle.OWL) brow(pose, tint, glow, puff) else furBrow(pose, tint, glow)
    }

    /** The brow of a person or an animal: one smooth ridge with short hairs, and the leopard's spots above it. */
    private fun furBrow(pose: EyePose, tint: Int, glow: Float) {
        val raise = pose.browRaise.coerceIn(-1f, 1f) * 0.22f
        val bt = pose.browTilt.coerceIn(-1f, 1f)
        val human = style == EyeStyle.HUMAN
        val lift = if (human) 0.16f else 0f
        val x0 = wide * 0.92f; val y0 = -0.62f + 0.28f * bt - raise - lift       // inner end
        val x1 = -0.1f; val y1 = -1.06f - raise - 0.04f * bt - lift             // bend
        val x2 = -(wide + 0.22f); val y2 = -0.74f - 0.16f * bt - raise - lift    // outer end
        val thick = if (human) 0.11f else 0.17f
        val fill = EyeColor.mix(style.skin, 0xFFFFFF, if (human) 0.10f else 0.16f)
        fun bx(t: Float) = (1 - t) * (1 - t) * x0 + 2 * (1 - t) * t * x1 + t * t * x2
        fun by(t: Float) = (1 - t) * (1 - t) * y0 + 2 * (1 - t) * t * y1 + t * t * y2
        // Thickest a third of the way out, thin at both ends.
        fun half(t: Float) = thick * (0.25f + 0.75f * sin((PI * (0.12 + 0.88 * t)).toFloat()).coerceAtLeast(0f)) * (if (t < 0.3f) 0.75f + 0.8f * t else 1f)
        val steps = 20
        for (i in 0..steps) { val t = i / steps.toFloat(); p(bx(t), by(t) - half(t)) }
        for (i in steps downTo 0) { val t = i / steps.toFloat(); p(bx(t), by(t) + half(t)) }
        f.fill(EyeColor.argb(1f, fill))
        // Short hairs lying outward along it.
        for (i in 1 until 16) {
            val t = i / 16f
            val h = half(t)
            p(bx(t) + 0.03f, by(t) + h * 0.7f); p(bx(t) - 0.07f, by(t) - h * 0.8f)
            line(EyeColor.argb(0.55f, EyeColor.mix(fill, 0xFFFFFF, 0.22f)), 0.011f)
        }
        // Its lower edge catches the eye's light.
        for (i in 0..steps) { val t = i / steps.toFloat(); p(bx(t), by(t) + half(t)) }
        line(EyeColor.argb(0.16f + 0.26f * glow, tint), 0.014f)
        if (style == EyeStyle.LEOPARD) {
            // Rosettes: dark spots ringed in tawny fur.
            for (k in 0 until 6) {
                val t = 0.12f + 0.15f * k
                val sx = bx(t) + 0.05f * sin(k * 2.3f); val sy = by(t) - 0.26f - 0.06f * cos(k * 1.7f)
                val r = 0.075f + 0.02f * sin(k * 3.1f)
                circle(sx, sy, r, rx = 1.25f, n = 14)
                f.fill(EyeColor.argb(1f, EyeColor.mix(style.skin, 0xD8A35A, 0.45f)))
                circle(sx + 0.01f, sy, r * 0.55f, rx = 1.2f, n = 12)
                f.fill(EyeColor.argb(1f, 0x120D07))
            }
        }
    }

    private fun shadowBand(depth: Float, alpha: Float) {
        for (i in 0..LID_STEPS) p(lidX(i), top[i] - 0.02f)
        for (i in LID_STEPS downTo 0) { val x = lidX(i); p(x, top[i] + depth * arc(x)) }
        f.fill(EyeColor.argb(alpha, 0x000000))
    }

    private fun iris(pose: EyePose, tint: Int, light: Int, energy: Float) {
        val ri = style.iris
        val natural = style != EyeStyle.OWL
        val ix = pose.gazeX.coerceIn(-1f, 1f) * mirror * 0.25f
        val iy = pose.gazeY.coerceIn(-1f, 1f) * 0.2f
        val rp = ri * (0.26f + 0.32f * pose.pupil.coerceIn(0f, 1f))
        val bright = 0.72f + 0.28f * energy

        // Base colour: bright near the pupil, deep at the rim.
        circle(ix, iy, ri, n = 56)
        radial(ix, iy, ri, intArrayOf(
            EyeColor.argb(1f, EyeColor.scale(EyeColor.mix(tint, 0xFFFFFF, 0.3f), bright)),
            EyeColor.argb(1f, EyeColor.scale(tint, 0.62f * bright)),
            EyeColor.argb(1f, if (natural) EyeColor.scale(tint, 0.36f) else EyeColor.mix(EyeColor.scale(tint, 0.26f), 0x06243C, 0.5f)),
            EyeColor.argb(1f, 0x020A12),
        ), floatArrayOf(0.28f, 0.58f, 0.88f, 1f))

        if (natural) naturalIris(ix, iy, ri, rp, tint, light, bright, pose.spin)
        else {
        // Fine fibres running outward.
        val drift = pose.spin * 0.15f
        for (i in 0 until FIBRES) {
            val a = (2.0 * PI * i / FIBRES).toFloat() + drift
            val r0 = rp * 1.2f
            val r1 = ri * (0.9f + 0.07f * sin(i * 2.3f))
            p(ix + cos(a) * r0, iy + sin(a) * r0); p(ix + cos(a) * r1, iy + sin(a) * r1)
            line(EyeColor.argb(0.10f + 0.10f * (0.5f + 0.5f * sin(i * 1.9f)), light), 0.010f)
        }

        // Glow around the core.
        circle(ix, iy, rp * 2.1f)
        radial(ix, iy, rp * 2.1f, intArrayOf(EyeColor.argb(0.55f * bright, light), EyeColor.argb(0.55f * bright, light), EyeColor.argb(0f, light)), floatArrayOf(0f, 0.46f, 1f))

        // The reactor ring: segments turning slowly.
        val r1 = max(ri * 0.60f, rp * 1.16f + 0.02f)
        val r2 = ri * 0.83f
        val step = (2.0 * PI / SEGMENTS).toFloat()
        for (i in 0 until SEGMENTS) {
            val a0 = pose.spin + i * step + step * 0.11f
            val a1 = pose.spin + (i + 1) * step - step * 0.11f
            for (j in 0..6) { val a = a0 + (a1 - a0) * j / 6f; p(ix + cos(a) * r2, iy + sin(a) * r2) }
            for (j in 6 downTo 0) { val a = a0 + (a1 - a0) * j / 6f; p(ix + cos(a) * r1, iy + sin(a) * r1) }
            f.fill(EyeColor.argb(0.30f + 0.30f * energy, EyeColor.mix(tint, 0xFFFFFF, 0.2f)))
            for (j in 0..6) { val a = a0 + (a1 - a0) * j / 6f; p(ix + cos(a) * r2, iy + sin(a) * r2) }
            for (j in 6 downTo 0) { val a = a0 + (a1 - a0) * j / 6f; p(ix + cos(a) * r1, iy + sin(a) * r1) }
            line(EyeColor.argb(0.75f, light), 0.011f, closed = true)
        }

        // Outer ring with tick marks turning the other way.
        circle(ix, iy, ri * 0.895f, n = 56)
        line(EyeColor.argb(0.65f, tint), 0.013f, closed = true)
        for (i in 0 until TICKS) {
            val a = (2.0 * PI * i / TICKS).toFloat() - pose.spin * 0.6f
            p(ix + cos(a) * ri * 0.915f, iy + sin(a) * ri * 0.915f); p(ix + cos(a) * ri * 0.97f, iy + sin(a) * ri * 0.97f)
            line(EyeColor.argb(if (i % 4 == 0) 0.7f else 0.35f, light), 0.012f)
        }

        }

        // The dark rim of the iris.
        circle(ix, iy, ri, n = 56)
        line(EyeColor.argb(0.9f, 0x01050A), 0.05f, closed = true)

        // Pupil with its bright core ring.
        if (style.slit) {
            // A cat's pupil: a tall slit in the light that rounds out as it widens.
            val open = pose.pupil.coerceIn(0f, 1f)
            // Upright on the face, whatever the slant of the eye around it.
            val back = -style.slant * mirror
            val cb = cos(back); val sb = sin(back)
            val ry = ri * 0.8f; val rx = ry * (0.09f + 0.8f * open * open)
            for (k in 0 until 48) {
                val a = (2.0 * PI * k / 48).toFloat()
                val dx = cos(a) * rx * mirror; val dy = sin(a) * ry
                p(ix + (dx * cb - dy * sb) * mirror, iy + dx * sb + dy * cb)
            }
            f.fill(EyeColor.argb(1f, 0x000204))
        } else {
            circle(ix, iy, rp, n = 48)
            f.fill(EyeColor.argb(1f, 0x000204))
        }
        if (!natural) {
            circle(ix, iy, rp * 1.07f, n = 48)
            line(EyeColor.argb(0.95f, EyeColor.mix(tint, 0xFFFFFF, 0.65f)), 0.024f, closed = true)
            circle(ix, iy, rp * 0.72f, n = 36)
            line(EyeColor.argb(0.16f + 0.14f * energy, tint), 0.008f, closed = true)
        }

        // Light reflected in the eye: it stays toward the same side of the screen and lags the gaze a little.
        val hx = ix - 0.25f * mirror - pose.gazeX * mirror * 0.05f
        val hy = iy - 0.27f - pose.gazeY * 0.05f
        circle(hx, hy, 0.24f)
        radial(hx, hy, 0.24f, intArrayOf(EyeColor.argb(0.22f, 0xFFFFFF), EyeColor.argb(0f, 0xFFFFFF)), floatArrayOf(0f, 1f))
        circle(hx, hy, 0.105f, rx = 1.15f, n = 24)
        f.fill(EyeColor.argb(0.86f, 0xF4FCFF))
        circle(ix + 0.21f * mirror, iy + 0.29f, 0.045f, n = 16)
        f.fill(EyeColor.argb(0.32f, 0xF4FCFF))
    }

    /** A living iris: lighter around the pupil, darker toward the rim, threaded with fine fibres. */
    private fun naturalIris(ix: Float, iy: Float, ri: Float, rp: Float, tint: Int, light: Int, bright: Float, spin: Float) {
        // Fibres of two kinds: pale ones and dark furrows between them. They barely drift, so the eye feels alive.
        val drift = spin * 0.03f
        for (i in 0 until 72) {
            val a = (2.0 * PI * i / 72).toFloat() + drift + 0.03f * sin(i * 7.1f)
            val r0 = rp * (1.05f + 0.1f * sin(i * 1.3f))
            val r1 = ri * (0.86f + 0.11f * sin(i * 2.9f))
            p(ix + cos(a) * r0, iy + sin(a) * r0); p(ix + cos(a) * r1, iy + sin(a) * r1)
            if (i % 3 == 0) line(EyeColor.argb(0.30f, 0x000000), 0.014f) else line(EyeColor.argb(0.12f + 0.14f * (0.5f + 0.5f * sin(i * 1.9f)), light), 0.011f)
        }
        // The paler ring around the pupil.
        circle(ix, iy, min(ri * 0.92f, rp * 1.9f))
        radial(ix, iy, min(ri * 0.92f, rp * 1.9f), intArrayOf(EyeColor.argb(0.5f * bright, EyeColor.mix(tint, 0xFFF2C0, 0.55f)), EyeColor.argb(0.32f * bright, EyeColor.mix(tint, 0xFFF2C0, 0.4f)), EyeColor.argb(0f, tint)), floatArrayOf(0f, 0.55f, 1f))
        // Shade toward the rim.
        circle(ix, iy, ri, n = 56)
        radial(ix, iy, ri, intArrayOf(EyeColor.argb(0f, 0x000000), EyeColor.argb(0f, 0x000000), EyeColor.argb(0.55f, 0x000000)), floatArrayOf(0f, 0.72f, 1f))
    }

    /** The brow ridge: overlapping feather-shaped plates, lower toward the middle of the face. */
    private fun brow(pose: EyePose, tint: Int, glow: Float, puff: Float) {
        val raise = pose.browRaise.coerceIn(-1f, 1f) * 0.24f
        val bt = pose.browTilt.coerceIn(-1f, 1f)
        val x0 = 1.04f; val y0 = -0.58f + 0.30f * bt - raise          // inner end
        val x1 = -0.15f; val y1 = -0.93f - raise - 0.04f * bt          // bend
        val x2 = -1.34f; val y2 = -0.76f - 0.17f * bt - raise          // outer end
        for (i in PLATES - 1 downTo 0) {
            val t = i / (PLATES - 1f) * 0.95f
            val u = 1f - t
            val bx = u * u * x0 + 2f * u * t * x1 + t * t * x2
            val by = u * u * y0 + 2f * u * t * y1 + t * t * y2
            // Along the brow, outward, lifting more toward the outer end (and when puffed up).
            var ax = 2f * u * (x1 - x0) + 2f * t * (x2 - x1)
            var ay = 2f * u * (y1 - y0) + 2f * t * (y2 - y1)
            var len = sqrt(ax * ax + ay * ay); ax /= len; ay /= len
            ay -= 0.10f + 0.34f * t * t + 0.30f * max(0f, puff) * (0.4f + 0.6f * t)
            len = sqrt(ax * ax + ay * ay); ax /= len; ay /= len
            var nx = -ay; var ny = ax
            if (ny < 0f) { nx = -nx; ny = -ny }
            val length = 0.56f + 0.30f * t
            val wide = 0.34f - 0.07f * t
            fun q(al: Float, ac: Float) = p(bx + ax * al * length + nx * ac * wide, by + ay * al * length + ny * ac * wide)
            fun outline() { q(-0.12f, -0.5f); q(0.62f, -0.5f); q(0.9f, -0.3f); q(1f, -0.04f); q(0.97f, 0.14f); q(0.6f, 0.5f); q(-0.12f, 0.5f) }
            outline()
            f.fill(EyeColor.argb(1f, EyeColor.mix(0x0A1322, 0x16263F, 0.25f + 0.5f * (0.5f + 0.5f * sin(i * 2.1f)))))
            // The quill down the middle and the upper edge, barely lit.
            q(0f, 0f); q(0.9f, 0.02f)
            line(EyeColor.argb(0.7f, 0x1C3350), 0.012f)
            q(-0.12f, -0.5f); q(0.62f, -0.5f); q(0.9f, -0.3f); q(1f, -0.04f)
            line(EyeColor.argb(0.8f, 0x223B5C), 0.012f)
            // The lower edge and tip catch the eye's light.
            q(1f, -0.04f); q(0.97f, 0.14f); q(0.6f, 0.5f); q(-0.12f, 0.5f)
            line(EyeColor.argb(0.30f + 0.35f * glow, tint), 0.018f)
        }
    }

    companion object {
        /** The socket is a little wider than it is tall. */
        const val WIDE = 1.1f
        const val IRIS = 0.8f
        private const val LID_STEPS = 36
        private const val SEGMENTS = 9
        private const val FIBRES = 36
        private const val TICKS = 24
        private const val PLATES = 8
        private const val DISC_LINES = 34
        private const val RAD = (PI / 180.0).toFloat()

        /** Direction of a point as seen from an eye, for tests. */
        fun angle(x: Float, y: Float): Float = atan2(y, x)
    }
}
