package com.melhive.zed.core.eyes

import kotlin.math.abs

/** Reads the feeling of a sentence so the eyes can show it. */
object EyeEmotion {
    private fun words(vararg w: String) = Regex("\\b(?:" + w.joinToString("|") + ")", RegexOption.IGNORE_CASE)

    private val surprised = words("wow", "whoa", "incredibl\\w*", "amazing", "unbelievabl\\w*", "no way", "astonish\\w*", "surpris\\w*", "remarkabl\\w*", "stunning", "oh my")
    private val happy = words("great", "glad", "happy", "congrat\\w*", "awesome", "wonderful", "love", "lovely", "nice", "perfect", "good news", "you'?re welcome",
        "excellent", "enjoy\\w*", "good (?:morning|afternoon|evening)", "well done", "fantastic", "pleasure", "delighted", "beautiful", "thank you", "thanks", "yay", "brilliant", "proud")
    private val amused = words("haha\\w*", "hehe\\w*", "funny", "joke\\w*", "lol", "kidding", "silly", "pun", "hilarious", "laugh\\w*", "cheeky", "just teasing")
    private val concerned = words("sorry", "unfortunately", "can'?t", "cannot", "couldn'?t", "failed", "failure", "error", "problem\\w*", "sad", "worr\\w*", "careful", "warning",
        "danger\\w*", "sick", "hurt\\w*", "pain\\w*", "lost", "died", "death", "afraid", "wrong", "didn'?t work", "unable", "trouble", "risk\\w*", "emergency", "urgent",
        "bad news", "apolog\\w*", "regret\\w*", "difficult", "tough", "stress\\w*", "tired", "lonely", "upset", "angry", "scared")
    private val curious = words("interesting", "i wonder", "tell me more", "curious", "hmm+", "what about", "how about", "which one", "do you want", "would you like", "shall i", "should i")
    private val focused = words("let me", "checking", "searching", "looking (?:at|into|up)", "calculat\\w*", "working on", "one moment", "step \\d", "first", "next", "then", "analy[sz]\\w*",
        "according to", "exactly", "precisely", "in detail", "specifically")

    /** The mood of what ZED is saying. */
    fun of(text: String): EyeMood {
        val t = text.trim()
        if (t.isEmpty()) return EyeMood.NEUTRAL
        fun n(r: Regex) = r.findAll(t).count()
        val scores = floatArrayOf(
            0f,
            n(happy) * 1.0f + (if (t.endsWith("!")) 0.4f else 0f),
            n(curious) * 1.1f + (if (t.endsWith("?")) 0.9f else 0f),
            n(concerned) * 1.2f,
            n(surprised) * 1.6f + (if (t.contains("!!") || t.contains("?!")) 0.8f else 0f),
            n(amused) * 1.5f,
            n(focused) * 0.7f + (if (Regex("\\d").findAll(t).count() >= 4) 0.5f else 0f),
        )
        var best = 0
        for (i in 1 until scores.size) if (scores[i] > scores[best] + 0.001f) best = i
        return if (scores[best] < 0.65f) EyeMood.NEUTRAL else EyeMood.entries[best]
    }

    /** How the eyes react to what you said while ZED thinks about it. */
    fun hearing(text: String): EyeMood = when (of(text)) {
        EyeMood.CONCERNED -> EyeMood.CONCERNED
        EyeMood.AMUSED -> EyeMood.AMUSED
        EyeMood.SURPRISED -> EyeMood.SURPRISED
        EyeMood.HAPPY -> EyeMood.HAPPY
        else -> EyeMood.NEUTRAL
    }
}

/** Thanks and goodnights: the eyes answer with a slow, contented blink. */
object KindWords {
    private val kind = Regex("\\b(?:thank you|thanks|thank u|good ?night|sweet dreams|love you|well done|good job|good boy|good girl|you'?re the best|appreciate (?:it|you))\\b", RegexOption.IGNORE_CASE)
    fun matches(text: String): Boolean = kind.containsMatchIn(text)
}

/**
 * Cutting ZED off by voice on the loudspeaker. The microphone hears ZED too, so a
 * stop word only counts when ZED itself isn't saying it.
 */
object VoiceInterrupt {
    private val stop = Regex("\\b(stop|wait|enough|quiet|silence|pause|cancel|hold on|shut up|be quiet|never ?mind|that'?s enough)\\b", RegexOption.IGNORE_CASE)

    /** [heard]: what the recognizer picked up while ZED was talking. [speaking]: everything ZED is saying in this answer. */
    fun heard(heard: String, speaking: String): Boolean {
        val own = speaking.lowercase()
        return stop.findAll(heard).any { m ->
            val word = m.value.lowercase().replace(Regex("\\s+"), " ")
            !Regex("\\b" + Regex.escape(word).replace(" ", "\\E\\s+\\Q") + "\\b").containsMatchIn(own)
        }
    }
}

/** Iris colours the user can choose. */
object EyeColors {
    /** "natural" is each eye style's own colour (a cat's green, a lion's amber; cyan for the owl). */
    val all = listOf("natural" to 0, "cyan" to 0x5CE1FF, "amber" to 0xFFB547, "green" to 0x4CFFA0, "violet" to 0x9B8CFF)
    fun of(name: String?, style: EyeStyle = EyeStyle.OWL): Int = all.firstOrNull { it.first == name?.lowercase() }?.second?.takeIf { it != 0 } ?: style.tint
    fun parse(said: String): String? = when (said.lowercase().trim()) {
        "natural", "default", "normal", "real", "own" -> "natural"
        "cyan", "blue", "aqua" -> "cyan"
        "amber", "orange", "yellow", "gold", "golden" -> "amber"
        "green", "emerald" -> "green"
        "violet", "purple", "lilac" -> "violet"
        else -> null
    }
    fun next(name: String?): String { val i = all.indexOfFirst { it.first == name }; return all[(i + 1).mod(all.size)].first }
}

/** Questions that only make sense if ZED looks through the camera. */
object VisualAsk {
    private val look = Regex(
        "\\bwhat(?:'s| is| are|s)? (?:this|that|these|those|it)\\b(?! (?:song|sound|noise|music|word|about|mean))" +
            "|\\bwhat(?:'s| is| are) (?:this|that|these|those) (?:thing|object|item|called|for)" +
            "|\\bwhat am i (?:holding|showing|wearing|doing|looking at|eating|drinking)" +
            "|\\bwhat(?:'s| is) in (?:my|the) hand" +
            "|\\b(?:can|could|do|did) you (?:see|look at|read|recogni[sz]e|identify) (?:me|this|that|these|those|it|my|what|the|how|anything|something|who)\\b" +
            "|\\bwhat (?:do|can) you see\\b" +
            "|\\b(?:look|take a look|have a look) at (?:this|that|these|those|me|my|it|what)\\b" +
            "|\\b(?:see|check|read|identify|describe|recogni[sz]e|translate) (?:this|that|these|those)\\b(?! (?:page|screen|article|message|post|website|app|file|document|chat))" +
            "|\\bdescribe (?:what you see|what'?s in front of you|me|my)\\b" +
            "|\\bwhat(?:'s| is) (?:in front of you|written (?:here|on (?:this|that)))" +
            "|\\bwhat does (?:this|that|it) (?:say|look like)\\b" +
            "|\\bwhat colou?r is (?:this|that|it|my)\\b" +
            "|\\bhow do i look\\b|\\bhow (?:many|much) (?:fingers|\\w+ am i holding)\\b|\\bwho (?:am i|is this|is that)\\b(?! \\w)" +
            "|\\bis (?:this|that) (?:a|an|the|safe|ripe|good|real|broken|clean)\\b" +
            "|\\bwhat (?:kind|type|brand|breed|model) of \\w+ is (?:this|that|it)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val elsewhere = Regex("\\b(?:on (?:my|the|this) screen|this (?:page|screen|article|website|app|chat|message|song)|my screen)\\b", RegexOption.IGNORE_CASE)

    fun matches(text: String): Boolean = look.containsMatchIn(text) && !elsewhere.containsMatchIn(text)

    private val pointing = Regex("\\b(?:it|its|it'?s|this|that|these|those|them|they|one)\\b", RegexOption.IGNORE_CASE)

    private val detail = Regex(
        "\\b(?:tell me more|more (?:details?|info|information)|how much|what(?:'s| is) the price|price|cost|where (?:can i|to|do i) (?:buy|get|find)|who (?:made|makes|invented|created|sells)" +
            "|history|specs?|specifications|reviews?|is it (?:good|worth|safe|healthy|expensive|rare)|search|look it up|google|online|latest|release date|how old|where is it from|what(?:'s| is) it worth|nutrition|calories|side effects)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** A follow-up that the picture alone can't answer: it needs facts from the web about what was identified. */
    fun wantsDetail(text: String): Boolean = detail.containsMatchIn(text) && !elsewhere.containsMatchIn(text)

    private val opener = Regex("^(?:this|that|it|the (?:object|item|thing|picture|image|photo))?\\s*(?:is|'s|looks like|appears to be|seems to be|shows|could be|might be|is probably|is likely)?\\s*(?:a |an |the |some |your )?", RegexOption.IGNORE_CASE)

    /** What ZED said it was looking at, cut down to the thing itself ("That looks like a Casio F-91W watch." → "Casio F-91W watch"). */
    fun subject(answer: String): String {
        val first = answer.trim().split(Regex("(?<=[.!?])\\s+")).firstOrNull().orEmpty().trimEnd('.', '!', '?')
        return opener.replace(first, "").substringBefore(",").substringBefore(" which ").substringBefore(" that ").trim().take(80)
    }

    /** A question that still points at what was just shown ("how do I use it?", "is that expensive?"). */
    fun followUp(text: String): Boolean = pointing.containsMatchIn(text) && !elsewhere.containsMatchIn(text) && text.trim().split(Regex("\\s+")).size <= 16
}

/**
 * Notices when something is held up to the camera. It keeps a slow picture of
 * what the view normally looks like and reports where the view suddenly
 * differs, outside the face. A held thing fades into the normal picture after
 * a while, so the eyes return to you.
 *
 * Works on a small grid of brightness values (0..255), [gw]×[gh], top-left first.
 */
class SceneWatcher(private val gw: Int, private val gh: Int) {
    private val usual = FloatArray(gw * gh)
    private var started = false
    private var heldFor = 0f

    /** Something other than the face is in view. */
    var present = false
        private set
    /** Its middle, 0..1 across and down the picture. */
    var x = 0.5f
        private set
    var y = 0.5f
        private set
    /** Average brightness 0..255: too low and nothing can be seen. */
    var brightness = 0f
        private set

    /** [face]: left, top, right, bottom of the face (0..1), or null when no face is seen. */
    fun update(luma: IntArray, dt: Float, face: FloatArray?) {
        val n = gw * gh
        var sum = 0L
        for (i in 0 until n) sum += luma[i]
        val mean = sum.toFloat() / n
        brightness = mean
        if (!started) { for (i in 0 until n) usual[i] = luma[i].toFloat(); started = true; return }
        var usualSum = 0f
        for (i in 0 until n) usualSum += usual[i]
        // The camera brightens and darkens the whole picture by itself: compare against the average.
        val shift = mean - usualSum / n

        // The face and the body under it move all the time: not an object.
        var fl = -1f; var ft = -1f; var fr = -1f; var fb = -1f
        if (face != null) {
            val w = face[2] - face[0]; val h = face[3] - face[1]
            fl = face[0] - w * 0.35f; fr = face[2] + w * 0.35f; ft = face[1] - h * 0.5f; fb = face[3] + h * 0.45f
        }
        var count = 0; var sx = 0f; var sy = 0f
        val changed = BooleanArray(n)
        for (i in 0 until n) {
            val diff = abs(luma[i] - usual[i] - shift)
            if (diff > THRESHOLD) {
                changed[i] = true
                val cx = (i % gw + 0.5f) / gw; val cy = (i / gw + 0.5f) / gh
                if (face == null || cx < fl || cx > fr || cy < ft || cy > fb) { count++; sx += cx; sy += cy }
            }
        }
        val share = count.toFloat() / n
        val everything = share > 0.5f
        // Learn the normal view: quickly where nothing changed (or when the whole view changed), slowly where something sits.
        val fast = 1f - Math.exp(-dt * 1.6).toFloat()
        val slow = 1f - Math.exp(-dt * 0.09).toFloat()
        for (i in 0 until n) usual[i] += (luma[i] - usual[i]) * if (changed[i] && !everything) slow else fast

        var found = false
        if (share >= MIN_SHARE && !everything && mean > DARK) {
            val mx = sx / count; val my = sy / count
            // One thing, not scattered flicker: most changed cells sit near their middle.
            var spread = 0f
            for (i in 0 until n) if (changed[i]) {
                val cx = (i % gw + 0.5f) / gw; val cy = (i / gw + 0.5f) / gh
                if (face == null || cx < fl || cx > fr || cy < ft || cy > fb) spread += abs(cx - mx) + abs(cy - my)
            }
            if (spread / count < 0.42f) {
                found = true
                val k = if (present) 0.35f else 1f
                x += (mx - x) * k; y += (my - y) * k
            }
        }
        heldFor = if (found) heldFor + dt else 0f
        present = found
    }

    /** True when the view is too dark to trust. */
    val dark: Boolean get() = brightness <= DARK

    companion object {
        private const val THRESHOLD = 26f
        private const val MIN_SHARE = 0.04f
        private const val DARK = 22f
    }
}
