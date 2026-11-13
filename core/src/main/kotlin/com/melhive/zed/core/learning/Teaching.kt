package com.melhive.zed.core.learning

/**
 * What the user teaches ZED about how to talk and what they mean, said in plain words:
 * - pronunciation: "pronounce Cebu as seh-boo", "Siargao is pronounced shar-gow"
 * - how to answer: "from now on keep it short", "stop calling me sir", "don't use emojis"
 * - their own shortcuts: "when I say lights out, I mean turn off the lights and do not disturb on"
 * - speaking speed: "talk slower"
 *
 * Exact phrasings only, so ordinary requests ("don't forget to buy milk", "stop the music")
 * are never mistaken for lessons.
 */
object Teaching {
    sealed interface Taught {
        data class Pronounce(val word: String, val say: String) : Taught
        data class Rule(val rule: String) : Taught
        data class Alias(val phrase: String, val meaning: String) : Taught
        data class Speed(val faster: Boolean) : Taught
    }

    private val opt = setOf(RegexOption.IGNORE_CASE)
    private const val Q = "[\"“”'‘’]?"
    private const val WORD = "$Q([\\p{L}\\p{N}][\\p{L}\\p{N}'’ .-]{0,40}?)$Q"

    private val PRONOUNCE = listOf(
        Regex("^(?:you should |you need to |you have to )?(?:pronounce|say) $WORD (?:as|like) $WORD$", opt),
        Regex("^$WORD is (?:pronounced|said) (?:as |like )?$WORD$", opt),
        Regex("^(?:the )?(?:right|correct|proper) (?:way to say|way to pronounce|pronunciation (?:of|for)) $WORD is $WORD$", opt),
        Regex("^you(?:'re| are) (?:pronouncing|saying) $WORD wrong(?:ly)?[,.;:!]? (?:it'?s|it is|say(?: it)?|it should be|it'?s pronounced) $WORD$", opt),
    )

    /** Words that make a "from now on …" sentence about how ZED answers, not a request. */
    private val ABOUT_REPLIES = Regex("\\b(you|your|answer|answers|reply|replies|respond|response|responses|say|saying|talk|speak|call me|tell me|explain|ask|use|keep|be|don'?t|do not|never|stop|sound|voice|short|shorter|long|longer|brief|detail|detailed|emojis?|language|tagalog|filipino|english|formal|casual|sir|ma'?am|boss|greet|mention|repeat|apologi[sz]e|questions?)\\b", opt)
    private val FROM_NOW = Regex("^(?:from now on|next time|in the future|going forward|always|whenever i ask(?: you)?(?: [^,]+)?)[,:]? (.+)$", opt)
    private val STOP = Regex("^(?:please )?(?:stop|quit) (saying|calling me|using|asking(?: me)?|repeating|adding|apologi[sz]ing|explaining|talking about|reading(?: out)?|mentioning|ending|starting|giving me|telling me)\\b(.*)$", opt)
    private val DONT = Regex("^(?:please )?(?:don'?t|do not|never) (say|call me|use|ask(?: me)?|repeat|mention|apologi[sz]e|explain|read out|(?:start|end|begin|finish) (?:your|with|by|every|each|replies|answers)|give me|tell me|talk|speak|(?:answer|reply|respond) (?:with|in|like|using|by))\\b(.*)$", opt)
    private val STYLE = Regex("^(?:please )?(?:be|answer|reply|respond|talk|speak|keep (?:it|your answers?|your replies|replies|answers)) (?:more |a bit |a little |much |way )?(shorter|briefer|brief|short|concise|longer|more detailed|detailed|simpler|simple|casual|formal|friendlier|friendly|direct|straight to the point|in (?:english|tagalog|filipino|taglish))\\b(.*)$", opt)
    private val PREFER = Regex("^i (?:prefer|like it when|want|'d like|would like) (?:you to |your answers? to |your replies to |it when you |when you )(.+)$", opt)
    private val ALIAS = Regex("^(?:when|whenever|if) i say $Q(.+?)$Q,? (?:i mean|it means|that means|you should|i want you to|do) $Q(.+?)$Q$", opt)
    private val SPEED = Regex("^(?:please )?(?:talk|speak) (?:a bit |a little |a lot |much )?(faster|quicker|more quickly|slower|more slowly)(?: please)?$", opt)

    fun detect(input: String): Taught? {
        val text = input.trim().trimEnd('.', '!').replace(Regex("\\s+"), " ")
            .replace(Regex("^(?:hey |ok |okay )?(?:zed[,:]?\\s+)", RegexOption.IGNORE_CASE), "")
        if (text.isEmpty()) return null
        for (r in PRONOUNCE) r.matchEntire(text)?.let { m ->
            val word = m.groupValues[1].trim(); val say = m.groupValues[2].trim()
            if (word.split(' ').size <= 4 && say.split(' ').size <= 6 && !word.equals(say, true)) return Taught.Pronounce(word, say)
        }
        ALIAS.matchEntire(text)?.let { m ->
            val phrase = m.groupValues[1].trim().trimEnd(','); val meaning = m.groupValues[2].trim()
            if (phrase.split(' ').size <= 6 && meaning.length >= 3) return Taught.Alias(phrase, meaning)
        }
        SPEED.matchEntire(text)?.let { m -> return Taught.Speed(m.groupValues[1].lowercase().let { it.startsWith("fast") || it.startsWith("quick") || it == "more quickly" }) }
        STYLE.matchEntire(text)?.let { return Taught.Rule(rule(text)) }
        STOP.matchEntire(text)?.let { return Taught.Rule(rule(text)) }
        DONT.matchEntire(text)?.let { return Taught.Rule(rule(text)) }
        FROM_NOW.matchEntire(text)?.let { m -> if (ABOUT_REPLIES.containsMatchIn(m.groupValues[1])) return Taught.Rule(rule(m.groupValues[1])) }
        PREFER.matchEntire(text)?.let { m -> if (ABOUT_REPLIES.containsMatchIn(text)) return Taught.Rule(rule(text)) }
        return null
    }

    private fun rule(text: String) = text.trim().trimEnd('.', '!').replaceFirstChar { it.uppercase() } + "."
}

/** How ZED says the words the user taught it ("Cebu" → "seh-boo"). */
object Pronunciations {
    /** Replaces each taught word (whole words, any case), longest first so phrases win over their parts. */
    fun apply(text: String, words: Map<String, String>): String {
        if (words.isEmpty() || text.isBlank()) return text
        var out = text
        for ((word, say) in words.entries.sortedByDescending { it.key.length }) {
            if (word.isBlank()) continue
            out = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(word) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).replace(out) { say }
        }
        return out
    }
}

/** The user's own shortcuts ("lights out" → "turn off the lights and turn on do not disturb"). */
object Aliases {
    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
        .removePrefix("hey zed ").removePrefix("zed ").removePrefix("ok zed ").removeSuffix(" please").removePrefix("please ").trim()

    /** The meaning when the whole message is one of the shortcuts, else null. */
    fun expand(text: String, aliases: Map<String, String>): String? {
        if (aliases.isEmpty()) return null
        val n = norm(text)
        return aliases.entries.firstOrNull { norm(it.key) == n }?.value
    }
}
