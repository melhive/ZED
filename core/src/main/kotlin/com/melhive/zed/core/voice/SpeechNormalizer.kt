package com.melhive.zed.core.voice

/** English number words for speech. */
object NumberWords {
    private val ones = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val scales = listOf("", "thousand", "million", "billion", "trillion")

    fun cardinal(n: Long): String {
        if (n < 0) return "minus " + cardinal(-n)
        if (n < 20) return ones[n.toInt()]
        if (n < 100) return tens[(n / 10).toInt()] + if (n % 10 != 0L) "-" + ones[(n % 10).toInt()] else ""
        if (n < 1000) return ones[(n / 100).toInt()] + " hundred" + if (n % 100 != 0L) " " + cardinal(n % 100) else ""
        val parts = mutableListOf<String>()
        var rest = n
        var scale = 0
        while (rest > 0 && scale < scales.size) {
            val chunk = rest % 1000
            if (chunk != 0L) parts.add(0, cardinal(chunk) + if (scales[scale].isNotEmpty()) " " + scales[scale] else "")
            rest /= 1000
            scale++
        }
        if (rest > 0) return digits(n.toString())
        return parts.joinToString(" ")
    }

    fun ordinal(n: Long): String {
        val words = cardinal(n)
        val irregular = mapOf("one" to "first", "two" to "second", "three" to "third", "five" to "fifth", "eight" to "eighth", "nine" to "ninth", "twelve" to "twelfth")
        val sep = maxOf(words.lastIndexOf(' '), words.lastIndexOf('-'))
        val head = words.substring(0, sep + 1)
        val last = words.substring(sep + 1)
        val ord = irregular[last] ?: when {
            last.endsWith("y") -> last.dropLast(1) + "ieth"
            else -> last + "th"
        }
        return head + ord
    }

    /** "1999" → "nineteen ninety-nine", "2005" → "two thousand five", "2026" → "twenty twenty-six". */
    fun year(n: Int): String = when {
        n in 2000..2009 -> cardinal(n.toLong())
        n % 100 == 0 -> cardinal((n / 100).toLong()) + " hundred"
        n % 100 < 10 -> cardinal((n / 100).toLong()) + " oh " + cardinal((n % 100).toLong())
        else -> cardinal((n / 100).toLong()) + " " + cardinal((n % 100).toLong())
    }

    fun digits(s: String): String = s.filter { it.isDigit() }.map { ones[it - '0'] }.joinToString(" ")

    /** "3.14" → "three point one four". */
    fun decimal(intPart: String, fraction: String): String =
        cardinal(intPart.replace(",", "").toLongOrNull() ?: 0) + " point " + digits(fraction)
}

/**
 * Rewrites text so a lexicon-based neural voice (Kokoro) can pronounce all of it.
 * Kokoro looks every word up in a pronunciation dictionary and silently skips
 * anything it cannot find — including all digits — so numbers, times, money,
 * percentages, units and symbols are spelled out as words here, and characters
 * the tokenizer does not split on (quotes, brackets, dashes) are replaced.
 */
/**
 * The assistant's name is the word "Zed" (one syllable, like "bed"). Written as "ZED", voices read it
 * as an acronym — letter by letter — so every voice gets it as the ordinary word "Zed".
 */
object SpokenName {
    private val name = Regex("(?<![\\p{L}\\d])(?:Z\\.E\\.D|[Zz][Ee][Dd])(?![\\p{L}\\d])")
    fun fix(text: String): String = name.replace(text, "Zed")
}

object SpeechNormalizer {
    private val I = RegexOption.IGNORE_CASE
    private fun num(s: String): Long? = s.replace(",", "").toLongOrNull()
    private fun n(s: String) = num(s)?.let { NumberWords.cardinal(it) } ?: s

    private val currencies = mapOf(
        '$' to ("dollar" to "cent"), '€' to ("euro" to "cent"), '£' to ("pound" to "penny"),
        '₱' to ("peso" to "centavo"), '¥' to ("yen" to ""), '₹' to ("rupee" to "paisa"),
    )
    private val magnitudes = mapOf("k" to "thousand", "m" to "million", "b" to "billion", "bn" to "billion", "thousand" to "thousand", "million" to "million", "billion" to "billion", "trillion" to "trillion")
    private val units = mapOf(
        "°c" to "degrees Celsius", "°f" to "degrees Fahrenheit", "°" to "degrees",
        "km/h" to "kilometers per hour", "kph" to "kilometers per hour", "mph" to "miles per hour",
        "km" to "kilometers", "cm" to "centimeters", "mm" to "millimeters", "kg" to "kilograms", "g" to "grams", "mg" to "milligrams",
        "lb" to "pounds", "lbs" to "pounds", "ml" to "milliliters", "l" to "liters",
        "tb" to "terabytes", "gb" to "gigabytes", "mb" to "megabytes", "kb" to "kilobytes",
        "ghz" to "gigahertz", "mhz" to "megahertz", "ms" to "milliseconds", "s" to "seconds", "sec" to "seconds", "secs" to "seconds",
        "min" to "minutes", "mins" to "minutes", "h" to "hours", "hr" to "hours", "hrs" to "hours",
    )
    private val singularUnits = mapOf(
        "degrees" to "degree", "kilometers" to "kilometer", "centimeters" to "centimeter", "millimeters" to "millimeter",
        "kilograms" to "kilogram", "grams" to "gram", "milligrams" to "milligram", "pounds" to "pound", "milliliters" to "milliliter",
        "liters" to "liter", "terabytes" to "terabyte", "gigabytes" to "gigabyte", "megabytes" to "megabyte", "kilobytes" to "kilobyte",
        "milliseconds" to "millisecond", "seconds" to "second", "minutes" to "minute", "hours" to "hour", "miles" to "mile",
    )

    private fun unitWords(value: String, unit: String): String {
        val plural = units[unit.lowercase()] ?: return unit
        if (value != "1") return plural
        val first = plural.substringBefore(' ')
        return (singularUnits[first] ?: first) + plural.removePrefix(first)
    }

    private fun spokenNumber(s: String): String {
        val parts = s.split('.')
        return if (parts.size == 2 && parts[1].isNotEmpty()) NumberWords.decimal(parts[0], parts[1]) else n(parts[0])
    }

    private fun hourMinute(h: Int, m: Int, meridiem: String?): String {
        val hour = NumberWords.cardinal(h.toLong())
        val minute = when {
            m == 0 && meridiem == null && h <= 12 -> " o'clock"
            m == 0 && meridiem == null -> " hundred"
            m == 0 -> ""
            m < 10 -> " oh " + NumberWords.cardinal(m.toLong())
            else -> " " + NumberWords.cardinal(m.toLong())
        }
        return hour + minute + (meridiem?.let { " " + it.lowercase().first() + " m" } ?: "")
    }

    fun normalize(input: String): String {
        var t = SpokenName.fix(input)
            .replace('’', '\'').replace('‘', '\'').replace('`', '\'')
            .replace(Regex("[“”«»\"]"), " ")
            .replace(Regex("\\s*[—–]\\s*"), ", ")
            .replace("…", "...")

        // Abbreviations
        t = Regex("\\b(Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sept?|Oct|Nov|Dec)\\.?(?=\\s+\\d)").replace(t) { MONTH_ABBR.getValue(it.groupValues[1].take(3)) }
        for ((abbr, full) in ABBREVIATIONS) t = t.replace(Regex("(?<![\\p{L}.])" + Regex.escape(abbr) + "(?![\\p{L}])"), full)

        // Money: $12.50, ₱1,500, $5.2M, €3 billion
        t = Regex("([$€£₱¥₹])\\s?(\\d[\\d,]*)(?:\\.(\\d+))?(?:\\s?(bn|k|m|b|thousand|million|billion|trillion))?\\b", I).replace(t) { mr ->
            val (major, minor) = currencies[mr.groupValues[1][0]]!!
            val whole = mr.groupValues[2]
            val frac = mr.groupValues[3]
            val mag = mr.groupValues[4].lowercase().takeIf { it.isNotEmpty() }?.let { magnitudes[it] }
            when {
                mag != null -> spokenNumber(if (frac.isNotEmpty()) "$whole.$frac" else whole) + " $mag ${major}s"
                frac.isNotEmpty() && frac.length <= 2 && minor.isNotEmpty() -> {
                    val cents = frac.padEnd(2, '0').toLong()
                    val w = num(whole) ?: 0
                    val majorWords = "${NumberWords.cardinal(w)} ${if (w == 1L) major else major + "s"}"
                    if (cents == 0L) majorWords else "$majorWords and ${NumberWords.cardinal(cents)} ${if (cents == 1L) minor else pluralMinor(minor)}"
                }
                frac.isNotEmpty() -> NumberWords.decimal(whole, frac) + " ${major}s"
                else -> { val w = num(whole) ?: 0; "${NumberWords.cardinal(w)} ${if (w == 1L || major == "yen") major else major + "s"}" }
            }
        }

        // Percentages
        t = Regex("(\\d[\\d,]*(?:\\.\\d+)?)\\s?%").replace(t) { spokenNumber(it.groupValues[1]) + " percent" }

        // Times with seconds: 3:42:17 pm → three forty-two and seventeen seconds p m
        t = Regex("\\b(\\d{1,2}):(\\d{2}):(\\d{2})(?:\\s?([ap])\\.?\\s?m\\b)?", I).replace(t) {
            val sec = it.groupValues[3].toInt()
            hourMinute(it.groupValues[1].toInt(), it.groupValues[2].toInt(), null).removeSuffix(" o'clock").removeSuffix(" hundred") +
                (if (sec > 0) " and ${NumberWords.cardinal(sec.toLong())} second${if (sec == 1) "" else "s"}" else "") +
                (it.groupValues[4].takeIf { m -> m.isNotEmpty() }?.let { m -> " ${m.lowercase()} m" } ?: "")
        }

        // Times: 3:45 pm, 3pm, 15:30
        t = Regex("\\b(\\d{1,2}):(\\d{2})\\s?([ap])\\.?\\s?m\\b", I).replace(t) {
            hourMinute(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3])
        }
        t = Regex("\\b(\\d{1,2})\\s?([ap])\\.?\\s?m\\b", I).replace(t) {
            NumberWords.cardinal(it.groupValues[1].toLong()) + " " + it.groupValues[2].lowercase() + " m"
        }
        t = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b").replace(t) {
            hourMinute(it.groupValues[1].toInt(), it.groupValues[2].toInt(), null)
        }

        // ISO dates: 2026-11-10 → November tenth, twenty twenty-six
        t = Regex("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b").replace(t) { mr ->
            val (y, mo, d) = mr.destructured
            val month = MONTHS.getOrNull(mo.toInt() - 1)
            if (month == null || d.toInt() !in 1..31) mr.value
            else "$month ${NumberWords.ordinal(d.toLong())}, ${NumberWords.year(y.toInt())}"
        }

        // Decades: 1990s, '90s → nineteen nineties, nineties
        t = Regex("(?<![\\d,.])(1[1-9]\\d0|20\\d0)s\\b").replace(t) { pluralTens(NumberWords.year(it.groupValues[1].toInt())) }
        t = Regex("(?<![\\p{L}\\d])'?([2-9]0)s\\b").replace(t) { pluralTens(NumberWords.cardinal(it.groupValues[1].toLong())) }

        // Ordinals: 1st, 22nd, 103rd
        t = Regex("\\b(\\d[\\d,]*)(st|nd|rd|th)\\b", I).replace(t) { mr -> num(mr.groupValues[1])?.let { NumberWords.ordinal(it) } ?: mr.value }

        // Numbers with units: 5km, 20 °C, 3.5 GB
        t = Regex("((?<![\\p{L}\\d])-)?(\\d+(?:\\.\\d+)?)\\s?(°[cf]|°|km/h|kph|mph|km|cm|mm|kg|mg|lbs?|ml|tb|gb|mb|kb|ghz|mhz|ms|secs?|mins?|hrs?)(?![\\p{L}\\d])", I).replace(t) {
            (if (it.groupValues[1].isNotEmpty()) "minus " else "") + spokenNumber(it.groupValues[2]) + " " + unitWords(it.groupValues[2], it.groupValues[3])
        }

        // Ranges: 3-5 → three to five. Other digit groups joined by dashes (phone numbers, codes) are read digit by digit.
        t = Regex("(?<![\\d.])(\\d+)(?:\\s?[-–]\\s?(\\d+))+(?![\\d.])").replace(t) { mr ->
            val groups = mr.value.split(Regex("\\s?[-–]\\s?"))
            val isRange = groups.size == 2 && groups.all { it.length <= 3 && !it.startsWith("0") } && groups[0].toInt() < groups[1].toInt()
            val isYearRange = groups.size == 2 && groups.all { it.length == 4 && it.toInt() in 1100..2099 }
            when {
                isRange -> n(groups[0]) + " to " + n(groups[1])
                isYearRange -> NumberWords.year(groups[0].toInt()) + " to " + NumberWords.year(groups[1].toInt())
                else -> groups.joinToString(", ") { NumberWords.digits(it) }
            }
        }

        // Years: 1999, 2026 (stand-alone four-digit numbers in a plausible range)
        t = Regex("(?<![\\d,.])(1[1-9]\\d\\d|20\\d\\d)(?![\\d,]|\\.\\d)").replace(t) { NumberWords.year(it.value.toInt()) }

        // Long digit strings (phone numbers, codes) → digit by digit
        t = Regex("(?<![\\d,.])\\d{5,}(?![\\d,]|\\.\\d)").replace(t) { NumberWords.digits(it.value) }

        // Decimals, negatives and plain numbers
        t = Regex("(?<![\\d,])(\\d[\\d,]*)\\.(\\d+)").replace(t) { NumberWords.decimal(it.groupValues[1], it.groupValues[2]) }
        t = Regex("(?<![\\p{L}\\d])-(\\d)").replace(t) { "minus " + it.groupValues[1] }
        // Spaces around spelled numbers keep them apart from letters: "E2B" → "E two B"
        t = Regex("\\d{1,3}(?:,\\d{3})+|\\d+").replace(t) { " " + n(it.value) + " " }

        // Symbols
        t = t.replace("&", " and ").replace("+", " plus ").replace("=", " equals ").replace("@", " at ")
            .replace(Regex("[()\\[\\]{}]"), ", ")
            .replace(Regex("[/\\\\|_~^*<>#]"), " ")
            // Anything else the tokenizer would glue to a word (and then skip) becomes a space.
            .replace(Regex("[^\\p{L}\\p{M}\\d'\\-\\s,.!?;:]"), " ")
            .replace(Regex("(\\s*,\\s*)+"), ", ")
            .replace(Regex("^\\s*,\\s*|\\s*,\\s*$"), "")
            .replace(Regex("\\s+"), " ")
            .replace(Regex(" ([,.!?;:])"), "$1")
            .replace(Regex(",+\\s*([.!?;:])"), "$1")
            .trim()
        return t
    }

    private val MONTH_ABBR = mapOf(
        "Jan" to "January", "Feb" to "February", "Mar" to "March", "Apr" to "April", "Jun" to "June", "Jul" to "July",
        "Aug" to "August", "Sep" to "September", "Oct" to "October", "Nov" to "November", "Dec" to "December",
    )
    private val ABBREVIATIONS = listOf(
        "e.g." to "for example", "i.e." to "that is", "etc." to "etcetera", "vs." to "versus", "approx." to "approximately",
        "Mr." to "Mister", "Mrs." to "Missus", "Ms." to "Miz", "Dr." to "Doctor", "Prof." to "Professor",
    )

    private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    private fun pluralTens(words: String) = if (words.endsWith("y")) words.dropLast(1) + "ies" else words + "s"

    private fun pluralMinor(minor: String) = when (minor) { "penny" -> "pence"; "paisa" -> "paise"; else -> minor + "s" }
}
