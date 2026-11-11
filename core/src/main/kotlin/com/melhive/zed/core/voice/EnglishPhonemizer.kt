package com.melhive.zed.core.voice

/**
 * A word → phoneme dictionary (Kokoro/misaki `lexicon-*.txt` format:
 * `word p h o n e m e s`, one phoneme symbol per space-separated field).
 * Stored as two sorted arrays to stay compact (~180k entries).
 */
class PronunciationLexicon private constructor(private val keys: Array<String>, private val values: Array<String>) {
    val size: Int get() = keys.size

    operator fun get(word: String): String? {
        val i = keys.binarySearch(word)
        return if (i >= 0) values[i] else null
    }

    operator fun contains(word: String) = keys.binarySearch(word) >= 0

    companion object {
        fun parse(lines: Sequence<String>): PronunciationLexicon {
            val map = HashMap<String, String>(200_000)
            val sb = StringBuilder()
            for (line in lines) {
                val sep = line.indexOfFirst { it == ' ' || it == '\t' }
                if (sep <= 0) continue
                sb.setLength(0)
                for (k in sep + 1 until line.length) { val ch = line[k]; if (ch != ' ' && ch != '\t') sb.append(ch) }
                if (sb.isEmpty()) continue
                // First entry wins, as in sherpa-onnx.
                map.putIfAbsent(line.substring(0, sep).lowercase(), sb.toString())
            }
            val sorted = map.keys.sorted().toTypedArray()
            return PronunciationLexicon(sorted, Array(sorted.size) { map.getValue(sorted[it]) })
        }

        fun of(entries: Map<String, String>) = parse(entries.asSequence().map { (k, v) -> "$k ${v.toList().joinToString(" ")}" })
    }
}

/**
 * English grapheme-to-phoneme for Kokoro voices, producing the misaki phoneme
 * symbols Kokoro was trained on. Words come from the pronunciation dictionary
 * first; inflections the dictionary lacks (plurals, -ed, -ing, possessives,
 * contractions) are derived from their base word with the regular English
 * rules; compounds are split into dictionary words; short all-caps words are
 * spelled out; anything left uses simple letter-to-sound rules. Nothing is
 * silently dropped, unlike the dictionary-only frontend.
 *
 * Input should already be normalized by [SpeechNormalizer] (no digits).
 */
class EnglishPhonemizer(private val lexicon: PronunciationLexicon) {

    /** Phoneme strings, one per sentence, ready to pass to the model as code points. */
    fun phonemize(text: String): List<String> {
        val sentences = mutableListOf<String>()
        val current = StringBuilder()
        val token = Regex("[\\p{L}][\\p{L}\\p{M}']*|[,.!?;:]|\\s+|-")
        for (m in token.findAll(text)) {
            val t = m.value
            when {
                t.isBlank() || t == "-" -> if (current.isNotEmpty() && current.last() != ' ') current.append(' ')
                t[0].isLetter() -> current.append(word(t))
                else -> {
                    while (current.isNotEmpty() && current.last() == ' ') current.setLength(current.length - 1)
                    current.append(t)
                    if (t in ".!?") { flush(current, sentences) } else current.append(' ')
                }
            }
        }
        flush(current, sentences)
        return sentences
    }

    private fun flush(sb: StringBuilder, out: MutableList<String>) {
        val s = sb.toString().trim()
        if (s.any { it.isLetter() || it in VOWELS }) out += s
        sb.setLength(0)
    }

    /** Phonemes for one word (no surrounding spaces). */
    fun word(raw: String): String {
        val w = raw.trim('\'')
        if (w.isEmpty()) return ""
        val lower = w.lowercase()
        OVERRIDES[lower]?.let { return it }
        lexicon[lower]?.let { return it }
        if (w.length in 2..5 && w.all { it.isUpperCase() }) return spell(lower)
        // Plural acronyms: "PDFs", "APIs"
        if (w.length in 3..6 && w.last() == 's' && w.dropLast(1).all { it.isUpperCase() }) return spell(lower.dropLast(1)) + "z"
        derived(lower)?.let { return it }
        compound(lower)?.let { return it }
        // Letter prefix + word: "gmail" → G mail, "ebook" → E book
        if (lower.length >= 5) known(lower.substring(1))?.let { return spell(lower.take(1)) + " " + it }
        if (lower.none { it in "aeiouy" }) return spell(lower)
        return LetterToSound.convert(lower)
    }

    private fun known(w: String): String? = lexicon[w]

    /** Regular inflections, possessives and contractions built from a dictionary base word. */
    private fun derived(w: String): String? {
        // Possessives and contractions
        for ((suffix, ph) in CONTRACTIONS) if (w.endsWith(suffix) && w.length > suffix.length) {
            val base = w.dropLast(suffix.length)
            val b = known(base) ?: derived(base) ?: continue
            return if (suffix == "'s") b + sSuffix(b) else b + ph
        }
        if (w.endsWith("s'") && w.length > 3) known(w.dropLast(1))?.let { return it }
        // Plurals and third person -s
        if (w.length > 3 && w.endsWith("ies")) known(w.dropLast(3) + "y")?.let { return it + "z" }
        if (w.length > 3 && w.endsWith("es")) {
            val stem = w.dropLast(2)
            if (stem.endsWith("s") || stem.endsWith("x") || stem.endsWith("z") || stem.endsWith("ch") || stem.endsWith("sh"))
                known(stem)?.let { return it + "ɪz" }
        }
        if (w.length > 2 && w.endsWith("s") && !w.endsWith("ss")) known(w.dropLast(1))?.let { return it + sSuffix(it) }
        // Past tense -ed
        if (w.length > 3 && w.endsWith("ied")) known(w.dropLast(3) + "y")?.let { return it + "d" }
        if (w.length > 3 && w.endsWith("ed")) {
            val stem = w.dropLast(2)
            val base = known(stem) ?: known(w.dropLast(1)) ?: undouble(stem)?.let { known(it) }
            if (base != null) return base + edSuffix(base)
        }
        // -ing
        if (w.length > 4 && w.endsWith("ing")) {
            val stem = w.dropLast(3)
            val base = known(stem) ?: known(stem + "e") ?: undouble(stem)?.let { known(it) }
            if (base != null) return base + "ɪŋ"
        }
        // Common derivational suffixes
        for ((suffix, ph) in SUFFIXES) if (w.length > suffix.length + 2 && w.endsWith(suffix)) {
            val stem = w.dropLast(suffix.length)
            val base = known(stem) ?: known(stem + "e") ?: undouble(stem)?.let { known(it) } ?: (if (stem.endsWith("i")) known(stem.dropLast(1) + "y") else null)
            if (base != null) return base + ph
        }
        return null
    }

    private fun undouble(stem: String): String? =
        if (stem.length >= 3 && stem[stem.length - 1] == stem[stem.length - 2] && stem.last() !in "aeiou") stem.dropLast(1) else null

    /** Splits a compound into the fewest dictionary words of three or more letters ("snapdragon" → snap + dragon). */
    private fun compound(w: String): String? {
        if (w.length < 6 || w.length > 30) return null
        val n = w.length
        val best = arrayOfNulls<List<String>>(n + 1)
        best[0] = emptyList()
        for (i in 0 until n) {
            val sofar = best[i] ?: continue
            if (sofar.size >= 3) continue
            for (j in i + 3..n) {
                val piece = w.substring(i, j)
                val ph = known(piece) ?: continue
                val cand = sofar + ph
                if (best[j] == null || best[j]!!.size > cand.size) best[j] = cand
            }
        }
        return best[n]?.takeIf { it.size >= 2 }?.joinToString("")
    }

    private fun spell(letters: String): String =
        letters.filter { it.isLetter() }.map { c -> known(c.toString()) ?: LetterToSound.convert(c.toString()) }.joinToString(" ")

    companion object {
        /** Common words the dictionary lacks or gets wrong for an assistant. */
        private val OVERRIDES = mapOf(
            "wifi" to "wˈIfˌI", "gmail" to "ʤˈi mˌAl", "ai" to "ˌA ˈI", "app" to "ˈæp", "apps" to "ˈæps",
            "smartphone" to "smˈɑɹtfˌOn", "ok" to "ˌOkˈA", "zed" to "zˈɛd",
        )
        private const val VOWELS = "AIOWYaeiouæɑɐɒɔəɚɛɜɪʊʌᵊᵻ"
        private val CONTRACTIONS = listOf("n't" to "nt", "'s" to "", "'re" to "ɹ", "'ll" to "l", "'ve" to "v", "'d" to "d", "'m" to "m")
        private val SUFFIXES = listOf(
            "ness" to "nəs", "less" to "ləs", "ful" to "fəl", "ment" to "mənt", "ers" to "əɹz", "er" to "əɹ",
            "ly" to "li", "able" to "əbᵊl", "est" to "ɪst",
        )

        private fun lastSound(ph: String) = ph.lastOrNull { it !in "ˈˌː" }

        /** -s after sibilants is /ɪz/, after voiceless sounds /s/, otherwise /z/. */
        fun sSuffix(base: String): String = when (lastSound(base)) {
            's', 'z', 'ʃ', 'ʒ', 'ʧ', 'ʤ' -> "ɪz"
            'p', 't', 'k', 'f', 'θ' -> "s"
            else -> "z"
        }

        /** -ed after t/d is /ɪd/, after voiceless sounds /t/, otherwise /d/. */
        fun edSuffix(base: String): String = when (lastSound(base)) {
            't', 'd' -> "ɪd"
            'p', 'k', 'f', 's', 'ʃ', 'ʧ', 'θ' -> "t"
            else -> "d"
        }
    }
}

/** Simple English letter-to-sound rules for names and words missing from the dictionary. */
object LetterToSound {
    private val START = listOf("kn" to "n", "wr" to "ɹ", "ps" to "s")
    private val GROUPS = listOf(
        "tion" to "ʃən", "sion" to "ʒən", "ture" to "ʧəɹ", "tch" to "ʧ", "sch" to "sk", "igh" to "I",
        "ph" to "f", "th" to "θ", "sh" to "ʃ", "ch" to "ʧ", "ck" to "k", "qu" to "kw", "ng" to "ŋ", "wh" to "w", "gh" to "",
        "ee" to "i", "ea" to "i", "oo" to "u", "ou" to "W", "ow" to "O", "oi" to "Y", "oy" to "Y",
        "ai" to "A", "ay" to "A", "ey" to "A", "ei" to "A", "au" to "ɔ", "aw" to "ɔ", "ie" to "i", "ue" to "u", "ew" to "ju",
        "ar" to "ɑɹ", "er" to "ɜɹ", "ir" to "ɜɹ", "ur" to "ɜɹ", "or" to "ɔɹ",
    )
    private val SINGLE = mapOf(
        'a' to "æ", 'b' to "b", 'd' to "d", 'e' to "ɛ", 'f' to "f", 'h' to "h", 'i' to "ɪ", 'j' to "ʤ", 'k' to "k",
        'l' to "l", 'm' to "m", 'n' to "n", 'o' to "ɑ", 'p' to "p", 'q' to "k", 'r' to "ɹ", 's' to "s", 't' to "t",
        'u' to "ʌ", 'v' to "v", 'w' to "w", 'x' to "ks", 'z' to "z",
    )
    private val LONG = mapOf('a' to "A", 'e' to "i", 'i' to "I", 'o' to "O", 'u' to "u")
    private const val VOWEL_PHONEMES = "AIOWYæɛɪɑʌiuɔɜəᵊ"

    fun convert(word: String): String {
        var w = word.lowercase().filter { it in 'a'..'z' }
        if (w.isEmpty()) return ""
        val out = StringBuilder()
        // Silent final e lengthens the vowel before a single consonant: "jade", "kobe".
        var magic = -1
        if (w.length >= 4 && w.last() == 'e' && w[w.length - 2] !in "aeiouy" && w[w.length - 3] in "aeiou" && (w.length < 4 || w[w.length - 4] !in "aeiou")) {
            magic = w.length - 3
            w = w.dropLast(1)
        }
        val endsInLe = w.length >= 3 && w.endsWith("le") && w[w.length - 3] !in "aeiouy"
        if (endsInLe) {
            w = w.dropLast(2)
            // Open syllable before -ble/-ple: "table", "noble", "bible"
            if (w.length >= 2 && w[w.length - 2] in "aeiou" && (w.length < 3 || w[w.length - 3] !in "aeiou")) magic = w.length - 2
        }
        var i = 0
        START.firstOrNull { w.startsWith(it.first) }?.let { out.append(it.second); i = it.first.length }
        while (i < w.length) {
            val c = w[i]
            // Doubled consonants sound once.
            if (i > 0 && c == w[i - 1] && c !in "aeiou") { i++; continue }
            if (i == magic) { out.append(LONG[c] ?: SINGLE[c].orEmpty()); i++; continue }
            val group = GROUPS.firstOrNull { w.startsWith(it.first, i) }
            if (group != null) { out.append(group.second); i += group.first.length; continue }
            val next = w.getOrNull(i + 1)
            out.append(
                when (c) {
                    'c' -> if (next != null && next in "eiy") "s" else "k"
                    'g' -> if (next == 'e' && i + 2 == w.length) "ʤ" else "ɡ"
                    'y' -> when { i == 0 -> "j"; i == w.length - 1 -> "i"; else -> "ɪ" }
                    'i' -> if (i == w.length - 1 && i > 0) "i" else "ɪ"
                    'o' -> if (i == w.length - 1 && i > 0) "O" else "ɑ"
                    else -> SINGLE[c].orEmpty()
                }
            )
            i++
        }
        if (endsInLe) out.append("ᵊl")
        val firstVowel = out.indexOfFirst { it in VOWEL_PHONEMES }
        if (firstVowel >= 0) out.insert(firstVowel, 'ˈ')
        return out.toString()
    }
}
