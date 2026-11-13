package com.melhive.zed.core.voice

/**
 * Turns what the on-device recognizer hears ("remind me at seven thirty p m", "twenty one degrees",
 * "set a timer for five minutes") into the written form the rest of ZED understands
 * ("remind me at 7:30 pm", "21 degrees", "set a timer for 5 minutes"). The recognizer writes
 * lowercase words with no digits or punctuation.
 */
object SpokenText {
    private val UNITS = mapOf("zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19)
    private val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    /** Words that make a lone "one" (or "oh") a number rather than a pronoun. */
    private val COUNTED = Regex("^(?:am|pm|o'?clock|minutes?|hours?|seconds?|days?|weeks?|months?|years?|percent|degrees?|times?|items?|tasks?|reminders?|kilometers?|km|miles?|meters?|kilos?|pounds?|dollars?|pesos?|euros?|calories?|steps?|pm|am)$")
    private val SPLIT_WORDS = listOf("to day" to "today", "to morrow" to "tomorrow", "to night" to "tonight", "a m" to "am", "p m" to "pm", "o clock" to "o'clock")

    private val TIME_BEFORE = setOf("at", "until", "till", "by", "from", "before", "after", "around", "about", "to", "for")
    private val TIME_AFTER = Regex("^(?:am|pm|o'clock|today|tomorrow|tonight|morning|afternoon|evening|later)$")

    fun normalize(raw: String): String {
        var s = " " + raw.trim().lowercase().replace(Regex("\\s+"), " ") + " "
        for ((a, b) in SPLIT_WORDS) s = s.replace(" $a ", " $b ")
        val words = s.trim().split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var i = 0
        while (i < words.size) {
            val (value, used) = number(words, i)
            if (used == 0) { out += words[i]; i++; continue }
            val next = words.getOrNull(i + used)
            val prev = out.lastOrNull()
            // A lone "one" is usually a word ("one of them"), unless it is clearly counted or a time.
            if (used == 1 && words[i] == "one" && !(next != null && COUNTED.matches(next)) && prev !in TIME_BEFORE && prev != "number") { out += "one"; i++; continue }
            // A time: an hour and its minutes ("seven thirty", "nine oh five") when the sentence says it is one.
            if (value in 1..12) {
                val (mins, usedM) = minutes(words, i + used)
                val after = words.getOrNull(i + used + usedM)
                if (usedM > 0 && (prev in TIME_BEFORE || (after != null && TIME_AFTER.matches(after)))) {
                    out += "%d:%02d".format(value, mins); i += used + usedM
                    continue
                }
            }
            out += value.toString(); i += used
        }
        val text = out.joinToString(" ")
            .replace(Regex("\\b(\\d+) percent\\b"), "$1%")
            .replace(Regex("\\b(\\d+(?::\\d{2})?) o'clock\\b"), "$1")
        return text.replaceFirstChar { it.uppercase() }
    }

    private enum class Kind { UNIT, TENS, HUNDRED, AND }

    /** A cardinal number starting at [i]: (value, words used) — "twenty one", "a hundred", "two hundred and five", "fifteen hundred". */
    private fun number(w: List<String>, i: Int): Pair<Int, Int> {
        if (w[i] == "oh" || w[i] == "and") return 0 to 0
        var j = i; var total = 0; var cur = 0; var last: Kind? = null
        if (w[i] == "a" && w.getOrNull(i + 1) in setOf("hundred", "thousand")) { cur = 1; last = Kind.UNIT; j++ }
        while (j < w.size) {
            val t = w[j]
            val u = UNITS[t]; val tens = TENS[t]
            when {
                u != null && t != "oh" && (last == null || last == Kind.HUNDRED || last == Kind.AND || (last == Kind.TENS && u in 1..9)) -> { cur += u; last = Kind.UNIT }
                tens != null && (last == null || last == Kind.HUNDRED || last == Kind.AND) -> { cur += tens; last = Kind.TENS }
                t == "hundred" && (last == Kind.UNIT || last == Kind.TENS) && cur % 100 in 1..99 -> { cur = (cur / 100) * 100 + (cur % 100) * 100; last = Kind.HUNDRED }
                t == "thousand" && last != null && last != Kind.AND -> { total += (if (cur == 0) 1 else cur) * 1000; cur = 0; last = Kind.HUNDRED }
                t == "and" && last == Kind.HUNDRED && w.getOrNull(j + 1)?.let { UNITS.containsKey(it) || TENS.containsKey(it) } == true -> last = Kind.AND
                else -> break
            }
            j++
        }
        if (last == null) return 0 to 0
        return (total + cur) to (j - i)
    }
    /** Minutes after an hour: "thirty", "forty five", "oh five", or am/pm/o'clock (00). */
    private fun minutes(w: List<String>, i: Int): Pair<Int, Int> {
        val t = w.getOrNull(i) ?: return 0 to 0
        if (t == "oh" || t == "zero") { val u = UNITS[w.getOrNull(i + 1)] ?: return 0 to 0; return if (u in 1..9) u to 2 else 0 to 0 }
        TENS[t]?.let { tens ->
            if (tens > 50) return 0 to 0
            val u = UNITS[w.getOrNull(i + 1)]
            return if (u != null && u in 1..9) (tens + u) to 2 else tens to 1
        }
        UNITS[t]?.let { u -> if (u in 10..19) return u to 1 }
        return 0 to 0
    }
}
