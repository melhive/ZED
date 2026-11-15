package com.melhive.zed.core.voice

/**
 * Puts right what speech recognition got wrong, using what ZED knows about the user. The recognizer
 * only writes words it was trained on, in lower case: a ticker comes out as spelled letters
 * ("x a u u s d") and an unusual name as the nearest ordinary word. Three steps, each cautious:
 *
 *  1. spelled letters become one word ("x a u u s d" → "XAUUSD");
 *  2. things the user corrected before are corrected again ("kay lin" → "Kaelyn");
 *  3. a word that sounds like one in the user's own vocabulary (contacts, memories, taught words)
 *     becomes that word — never an everyday word, and only when the sound really matches.
 */
object HearingFix {
    private val SPACE = Regex("\\s+")
    private val LETTER = Regex("^[a-z]$", RegexOption.IGNORE_CASE)
    private val DIGIT = Regex("^[0-9]$")

    /** Everyday words that are never "corrected" into a name, however alike they sound. */
    private val COMMON: Set<String> = ("a about after again all also always am an and any are around as ask at back be because been before being best better between big both but by call came can " +
        "come could day did do does done down each even ever every few find first for from get give go going good got had has have he hear her here him his how i if in into is it its just keep " +
        "know last left let like little long look made make many may me mean might more most much must my need never new next no not now of off okay old on once one only open or other our out over " +
        "own part people play please put read right said same saw say see send set she should show so some soon start still stop such take tell than thank thanks that the their them then there these " +
        "they thing things think this those three time to today told tomorrow too try turn two under up us use very want was way we week well went were what when where which while who why will with " +
        "work would year yes yesterday yet you your zed hey hi hello morning night " +
        "later early late great nice home house phone music light lights water food money price buy sell market trade gold dollar weather rain sun hot cold timer alarm remind reminder " +
        "message email note list task meeting minute minutes hour hours second seconds month months years days weeks number name word place city country world life man woman child friend " +
        "family mother father brother sister school job car bus road street door room kitchen bed sleep eat drink run walk talk speak listen watch movie song game book story news question " +
        "answer problem idea plan help check search wait quick slow fast high low small large short tall full empty easy hard happy sad sorry sure maybe really actually already almost " +
        "together another different important possible enough during without within across behind above below near far front inside outside east west north south red blue green white " +
        "black yellow something anything nothing everything someone anyone everyone where there here until since ago").split(' ').toSet()

    /** Words after which a name or a thing's name is expected: only there may a single heard word become a name that merely sounds alike. */
    private val BEFORE_NAME = setOf("call", "text", "message", "email", "tell", "ask", "named", "called", "meet", "visit", "open", "play", "launch", "with", "from", "to", "remind", "ping", "dial", "phone")

    /** "x a u u s d" → "XAUUSD"; "b t c" → "BTC". Three or more single letters in a row (digits may sit among them). */
    fun joinLetters(text: String): String {
        val tokens = text.trim().split(SPACE)
        val out = ArrayList<String>()
        var i = 0
        while (i < tokens.size) {
            var j = i
            var letters = 0
            while (j < tokens.size && (LETTER.matches(tokens[j].trimEnd('.', ',', '?', '!')) || (j > i && DIGIT.matches(tokens[j].trimEnd('.', ',', '?', '!'))))) {
                if (LETTER.matches(tokens[j].trimEnd('.', ',', '?', '!'))) letters++
                j++
                // Punctuation ends the run: "a b c, d" is two things.
                if (tokens[j - 1].last() in ".,?!") break
            }
            if (j - i >= 3 && letters >= 2) {
                val last = tokens[j - 1]
                val tail = last.takeLastWhile { it in ".,?!" }
                out += tokens.subList(i, j).joinToString("") { it.trimEnd('.', ',', '?', '!').uppercase() } + tail
                i = j
            } else { out += tokens[i]; i++ }
        }
        return out.joinToString(" ")
    }

    /** How a word sounds, roughly: its consonants in order, with alike-sounding letters merged. */
    fun sound(word: String): String {
        val w = word.lowercase().filter { it in 'a'..'z' }
        if (w.isEmpty()) return ""
        val s = StringBuilder()
        var i = 0
        while (i < w.length) {
            val c = w[i]
            val next = w.getOrNull(i + 1)
            val k: String = when {
                c == 'p' && next == 'h' -> { i++; "f" }
                c == 'c' && next == 'k' -> { i++; "k" }
                c == 'c' && next == 'h' -> { i++; "x" }
                c == 's' && next == 'h' -> { i++; "x" }
                c == 't' && next == 'h' -> { i++; "t" }
                c == 'g' && next == 'h' -> { i++; "" }
                c == 'c' -> if (next == 'e' || next == 'i' || next == 'y') "s" else "k"
                c == 'q' -> "k"
                c == 'x' -> "ks"
                c == 'z' -> "s"
                c == 'j' -> "g"
                c == 'v' -> "f"
                c == 'w' || c == 'h' -> if (i == 0) c.toString() else ""
                c in "aeiouy" -> if (i == 0) "a" else ""
                else -> c.toString()
            }
            if (k.isNotEmpty() && (s.isEmpty() || !s.endsWith(k))) s.append(k)
            i++
        }
        return s.toString()
    }

    private fun distance(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /** True when [heard] could be [word] said aloud: the same sound, and not too different on paper. */
    fun soundsLike(heard: String, word: String): Boolean {
        val h = heard.lowercase().filter { it in 'a'..'z' }; val w = word.lowercase().filter { it in 'a'..'z' }
        if (h.length < 3 || w.length < 4) return false
        if (h == w) return true
        val sh = sound(h); val sw = sound(w)
        if (sh.length < 2 || sw.length < 2) return false
        val paper = distance(h, w)
        return (sh == sw && paper <= maxOf(2, w.length / 3)) || (distance(sh, sw) <= 1 && sh.length >= 3 && paper <= 1 && w.length >= 5)
    }

    /**
     * The corrected sentence. [learned]: what the user fixed before (heard → meant). [vocabulary]: the user's own
     * words, written the way they should appear.
     */
    fun apply(text: String, learned: Map<String, String> = emptyMap(), vocabulary: Collection<String> = emptyList()): String {
        var out = joinLetters(text)
        for ((heard, meant) in learned.entries.sortedByDescending { it.key.length }) {
            if (heard.isBlank() || meant.isBlank()) continue
            out = Regex("(?<![\\p{L}\\p{N}])" + heard.trim().split(SPACE).joinToString("\\s+") { Regex.escape(it) } + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).replace(out) { meant }
        }
        if (vocabulary.isEmpty()) return out
        val words = vocabulary.map { it.trim() }.filter { it.length >= 4 && it.lowercase() !in COMMON }.distinct()
        if (words.isEmpty()) return out
        val exact = words.associateBy { it.lowercase().filter { c -> c.isLetterOrDigit() } }
        val tokens = out.split(SPACE).toMutableList()
        var i = 0
        val result = ArrayList<String>()
        while (i < tokens.size) {
            var replaced = false
            // Longest first: a name heard as two or three short words ("kay lin").
            for (n in 3 downTo 1) {
                if (i + n > tokens.size) continue
                val span = tokens.subList(i, i + n)
                val bare = span.map { it.trim('.', ',', '?', '!', '"', '\'') }
                if (bare.any { it.isEmpty() || !it.all { c -> c.isLetter() || c == '\'' } }) continue
                // Punctuation inside the span means these words don't belong together.
                if (span.dropLast(1).any { it.last() in ".,?!" }) continue
                val joined = bare.joinToString("").lowercase().replace("'", "")
                val tail = span.last().takeLastWhile { it in ".,?!" }
                val same = exact[joined]
                if (same != null) { if (n == 1 || bare.joinToString(" ") != same) { result += same + tail; i += n; replaced = true; break } else continue }
                if (n == 1 && (joined in COMMON || joined.length < 4)) continue
                // One heard word is always a real word: it only becomes a similar-sounding name where a name belongs ("call …").
                if (n == 1 && result.lastOrNull()?.lowercase()?.trim('.', ',') !in BEFORE_NAME) continue
                if (n > 1 && bare.all { it.lowercase() in COMMON }) continue
                val match = words.firstOrNull { w -> !w.contains(' ') && (if (n == 1) soundsLike(joined, w) else sound(joined) == sound(w) && distance(joined, w.lowercase()) <= maxOf(2, w.length / 3)) }
                if (match != null) { result += match + tail; i += n; replaced = true; break }
            }
            if (!replaced) { result += tokens[i]; i++ }
        }
        return result.joinToString(" ")
    }

    private val SAID = Regex("^(?:no|nope|not that|wrong)?[,.! ]*(?:i said|i meant|i mean|i was saying|what i said was|it should be|it'?s spelled|spelled) (.+?)[.!]?$", RegexOption.IGNORE_CASE)

    /** "No, I said Kaelyn" → "Kaelyn": the user is correcting what ZED heard. */
    fun correction(said: String): String? = SAID.matchEntire(said.trim())?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && it.split(SPACE).size <= 5 }

    /**
     * Where in [heard] the user's correction [meant] belongs: the stretch that sounds most like it.
     * Returns that stretch and the corrected sentence, or null when nothing in it sounds alike.
     */
    fun place(heard: String, meant: String): Pair<String, String>? {
        val tokens = heard.trim().split(SPACE)
        val target = sound(meant.replace(" ", ""))
        if (target.length < 2) return null
        var best: IntRange? = null
        var bestScore = Float.MAX_VALUE
        for (n in 1..minOf(4, tokens.size)) for (i in 0..tokens.size - n) {
            val span = tokens.subList(i, i + n).joinToString("") { it.trim('.', ',', '?', '!') }
            val s = sound(span)
            if (s.isEmpty()) continue
            val score = distance(s, target).toFloat() / maxOf(s.length, target.length)
            if (score < bestScore || (score == bestScore && best != null && n < best.count())) { bestScore = score; best = i until i + n }
        }
        val r = best ?: return null
        if (bestScore > 0.45f) return null
        val span = tokens.subList(r.first, r.last + 1).joinToString(" ") { it.trim('.', ',', '?', '!') }
        if (span.equals(meant, ignoreCase = true)) return null
        val tail = tokens[r.last].takeLastWhile { it in ".,?!" }
        return span to (tokens.subList(0, r.first) + (meant + tail) + tokens.subList(r.last + 1, tokens.size)).joinToString(" ")
    }

    /**
     * What the user changed when they edited what ZED heard: each replaced stretch as (heard, meant).
     * Words that stayed the same are skipped, so only the mistakes are learned.
     */
    fun learnFrom(heard: String, corrected: String): List<Pair<String, String>> {
        fun bare(t: String) = t.trim('.', ',', '?', '!', '"').lowercase()
        val a = heard.trim().split(SPACE).filter { it.isNotEmpty() }
        val b = corrected.trim().split(SPACE).filter { it.isNotEmpty() }
        if (a.isEmpty() || b.isEmpty()) return emptyList()
        // Longest common run of words, to line the two sentences up.
        val l = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) l[i][j] = if (bare(a[i]) == bare(b[j])) l[i + 1][j + 1] + 1 else maxOf(l[i + 1][j], l[i][j + 1])
        val out = ArrayList<Pair<String, String>>()
        var i = 0; var j = 0
        val from = ArrayList<String>(); val to = ArrayList<String>()
        fun flush() {
            if (from.isNotEmpty() && to.isNotEmpty() && from.size <= 8 && to.size <= 4) out += from.joinToString(" ") { bare(it) } to to.joinToString(" ") { it.trim('.', ',', '?', '!', '"') }
            from.clear(); to.clear()
        }
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && bare(a[i]) == bare(b[j]) -> { flush(); i++; j++ }
                j < b.size && (i == a.size || l[i][j + 1] >= l[i + 1][j]) -> { to += b[j]; j++ }
                else -> { from += a[i]; i++ }
            }
        }
        flush()
        return out.filter { (h, m) -> !h.equals(m, ignoreCase = true) }
    }

    /** Words worth keeping from a text as the user's vocabulary: names and symbols, not everyday words. */
    fun vocabulary(text: String): List<String> = Regex("[\\p{L}][\\p{L}\\p{N}'-]{2,}").findAll(text).map { it.value.trim('\'', '-') }.filter { w ->
        val lower = w.lowercase()
        lower !in COMMON && w.length >= 3 && (w.first().isUpperCase() || w.any { it.isDigit() } || w.drop(1).any { it.isUpperCase() })
    }.distinct().toList()
}

/** What the user says to teach ZED a word, an interest or a subject. */
object LearnAsk {
    private val opt = RegexOption.IGNORE_CASE
    private val WORD = Regex("^(?:please )?(?:learn|remember|add|save)(?: this| the)? (?:word|name|term|symbol|ticker)[: ]+(.+?)(?: (?:to|in) (?:your |my )?(?:vocabulary|dictionary|words))?[.!]?$", opt)
    private val WORD2 = Regex("^(?:please )?add (.+?) to (?:your|my|the) (?:vocabulary|dictionary|words)[.!]?$", opt)
    // "Learn about X", "study X", "read up on X": keep it. Plain "research X" is a question to answer now, unless it says to keep it.
    private val TOPIC_LEARN = Regex("^(?:please )?(?:learn about|learn everything about|learn all about|study|read up on)(?: the topic(?: of)?)? (.+?)(?: for me)?[.!]?$", opt)
    private val TOPIC_KEEP = Regex("^(?:please )?(?:research|find out about|look into|look up|search for)(?: the topic(?: of)?)? (.+?) (?:and|then) (?:learn|remember|keep|save|memori[sz]e)(?: it| that| them| what you find)?[.!]?$", opt)
    private val INTEREST = Regex("^(?:i(?:'m| am) (?:really |very )?(?:interested in|into)|my interests? (?:is|are|include)|i(?:'m| am) (?:learning|studying)|i (?:trade|follow|study)) (.+?)[.!]?$", opt)

    /** "learn the word XAUUSD" → "XAUUSD". */
    fun word(text: String): String? = (WORD.matchEntire(text.trim()) ?: WORD2.matchEntire(text.trim()))?.groupValues?.get(1)?.trim()?.trim('"', '“', '”', '\'')
        ?.let { HearingFix.joinLetters(it) }?.takeIf { it.isNotEmpty() && it.split(' ').size <= 4 }

    /** "research forex trading and learn it" or "learn about forex trading" → "forex trading". */
    fun topic(text: String): String? = (TOPIC_KEEP.matchEntire(text.trim()) ?: TOPIC_LEARN.matchEntire(text.trim()))?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 3 && it.split(' ').size <= 10 && !it.startsWith("my ", true) && !it.startsWith("this", true) }

    /** "I'm interested in forex trading and astronomy" → ["forex trading", "astronomy"]. */
    fun interests(text: String): List<String> = INTEREST.matchEntire(text.trim())?.groupValues?.get(1)
        ?.split(Regex(",| and | & ", opt))?.map { it.trim().trimEnd('.') }?.filter { it.length in 3..40 && it.split(' ').size <= 5 }.orEmpty()
}
