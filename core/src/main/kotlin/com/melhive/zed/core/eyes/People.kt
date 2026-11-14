package com.melhive.zed.core.eyes

import kotlin.math.sqrt

/**
 * A simple on-phone face recogniser (local binary patterns): it turns a small
 * grey, eye-aligned picture of a face into numbers that stay similar for the
 * same person under similar light. No download, nothing leaves the phone.
 * It tells a handful of people apart; it is not a security check.
 */
object FacePrint {
    /** Faces are compared as SIZE×SIZE grey pictures. */
    const val SIZE = 64
    private const val GRID = 8
    private const val BINS = 59
    /** Uniform patterns (at most two 0/1 changes around the circle) get their own bin; all others share the last. */
    private val binOf = IntArray(256).also { map ->
        var next = 0
        for (v in 0 until 256) {
            var changes = 0
            for (b in 0 until 8) if (((v shr b) and 1) != ((v shr ((b + 1) % 8)) and 1)) changes++
            map[v] = if (changes <= 2) next++ else BINS - 1
        }
    }

    /** [grey]: SIZE×SIZE brightness values 0..255, rows from the top. */
    fun describe(grey: IntArray): FloatArray {
        require(grey.size == SIZE * SIZE)
        // Even out the light: the same face in a dim and a bright room should look alike.
        val hist = IntArray(256)
        for (v in grey) hist[v.coerceIn(0, 255)]++
        val level = IntArray(256)
        var sum = 0
        for (i in 0 until 256) { sum += hist[i]; level[i] = sum * 255 / grey.size }
        val eq = IntArray(grey.size) { level[grey[it].coerceIn(0, 255)] }
        // Smooth a little so sensor noise doesn't flip the patterns.
        val g = IntArray(grey.size)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            var s = 0; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until SIZE && yy in 0 until SIZE) { s += eq[yy * SIZE + xx]; n++ }
            }
            g[y * SIZE + x] = s / n
        }
        val out = FloatArray(GRID * GRID * BINS)
        val cell = SIZE / GRID
        for (y in 2 until SIZE - 2) for (x in 2 until SIZE - 2) {
            val c = g[y * SIZE + x]
            var code = 0
            // Eight neighbours two pixels away, clockwise.
            if (g[(y - 2) * SIZE + x - 2] >= c) code = code or 1
            if (g[(y - 2) * SIZE + x] >= c) code = code or 2
            if (g[(y - 2) * SIZE + x + 2] >= c) code = code or 4
            if (g[y * SIZE + x + 2] >= c) code = code or 8
            if (g[(y + 2) * SIZE + x + 2] >= c) code = code or 16
            if (g[(y + 2) * SIZE + x] >= c) code = code or 32
            if (g[(y + 2) * SIZE + x - 2] >= c) code = code or 64
            if (g[y * SIZE + x - 2] >= c) code = code or 128
            out[((y / cell) * GRID + x / cell) * BINS + binOf[code]] += 1f
        }
        for (k in 0 until GRID * GRID) {
            var total = 0f
            for (b in 0 until BINS) total += out[k * BINS + b]
            if (total > 0f) for (b in 0 until BINS) out[k * BINS + b] /= total
        }
        return out
    }

    /** 0 identical … about 1 nothing alike. */
    fun distance(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (i in a.indices) { val s = a[i] + b[i]; if (s > 0f) { val x = a[i] - b[i]; d += x * x / s } }
        return d / (GRID * GRID) / 2f
    }
}

/** Compares two voiceprints: 1 the same voice … 0 or below unrelated. */
object VoicePrint {
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na <= 0f || nb <= 0f) 0f else dot / sqrt(na * nb)
    }

    /** At or above this the voice is taken to be the same person's. */
    const val SAME = 0.5f
}

/** Someone ZED knows by sight (and, for the owner, by voice). */
class Person(val id: Long, var name: String, val owner: Boolean) {
    val faces = ArrayList<FloatArray>()
    val voices = ArrayList<FloatArray>()
}

/** The people ZED has met in Eye mode, and who a new face or voice most resembles. */
class PeopleBook {
    val people = ArrayList<Person>()

    class Match(val person: Person?, val distance: Float, val known: Boolean, val stranger: Boolean)

    val owner: Person? get() = people.firstOrNull { it.owner }

    /** Who this face is. Between [KNOWN] and [STRANGER] it is neither claimed nor denied. */
    fun whose(face: FloatArray): Match {
        var best: Person? = null
        var bestD = Float.MAX_VALUE
        for (p in people) {
            // The second-closest sample counts: one lucky match is not enough.
            val ds = p.faces.map { FacePrint.distance(it, face) }.sorted()
            if (ds.isEmpty()) continue
            val d = if (ds.size >= 3) (ds[0] + ds[1]) / 2f else ds[0]
            if (d < bestD) { bestD = d; best = p }
        }
        return Match(best, bestD, best != null && bestD <= KNOWN, best == null || bestD >= STRANGER)
    }

    /** How much this voice sounds like the owner (1 the same … 0 unrelated), or null when the owner's voice isn't known yet. */
    fun ownerVoice(voice: FloatArray): Float? = owner?.voices?.takeIf { it.isNotEmpty() }?.maxOf { VoicePrint.similarity(it, voice) }

    fun add(name: String, owner: Boolean, faces: List<FloatArray>): Person {
        val p = Person((people.maxOfOrNull { it.id } ?: 0L) + 1, name.trim().take(40), owner)
        p.faces += faces.take(MAX_FACES)
        people += p
        return p
    }

    /** Keeps learning a known face from new angles, up to a limit. */
    fun learn(p: Person, face: FloatArray) { if (p.faces.size < MAX_FACES) p.faces += face }

    fun forget(name: String): Boolean = people.removeAll { it.name.equals(name.trim(), ignoreCase = true) }

    companion object {
        /** Calibrated on the public ORL face set (40 people × 10 pictures); see EyesTest. */
        const val KNOWN = 0.42f
        const val STRANGER = 0.50f
        const val MAX_FACES = 12
        const val MAX_VOICES = 5
    }
}

/** Picks the name out of an answer to "What's your name?". */
object NameAnswer {
    private val lead = Regex("^(?:hi|hello|hey|oh|um+|uh+|well|it'?s|it is|this is|i'?m|i am|my name is|my name'?s|name'?s|they call me|you can call me|call me|just)[,.! ]+", RegexOption.IGNORE_CASE)
    private val trail = Regex("[,.! ]+(?:here|by the way|nice to meet you|please)?[.! ]*$", RegexOption.IGNORE_CASE)
    private val notNames = setOf("yes", "no", "what", "why", "who", "nothing", "nobody", "stop", "never", "mind", "okay", "ok", "sure", "not", "none", "zed", "you", "me", "nevermind", "the", "a", "an")

    fun parse(said: String): String? {
        var s = said.trim()
        repeat(3) { s = lead.replace(s, "").trim() }
        s = trail.replace(s, "").trim()
        val words = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 3) return null
        if (words.any { !it.matches(Regex("[\\p{L}][\\p{L}'\\-]*")) || it.lowercase() in notNames }) return null
        return words.joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
    }
}
