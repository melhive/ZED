package com.melhive.zenith.core.compute

/** Converts between common units (length, mass, volume, speed, data, time, temperature). */
object UnitConverter {
    private data class Unit(val dimension: String, val toBase: Double, val names: List<String>, val label: String)

    private val units = listOf(
        Unit("length", 0.001, listOf("mm", "millimeter", "millimeters", "millimetre", "millimetres"), "mm"),
        Unit("length", 0.01, listOf("cm", "centimeter", "centimeters", "centimetre", "centimetres"), "cm"),
        Unit("length", 1.0, listOf("m", "meter", "meters", "metre", "metres"), "m"),
        Unit("length", 1000.0, listOf("km", "kilometer", "kilometers", "kilometre", "kilometres"), "km"),
        Unit("length", 0.0254, listOf("in", "inch", "inches"), "in"),
        Unit("length", 0.3048, listOf("ft", "foot", "feet"), "ft"),
        Unit("length", 0.9144, listOf("yd", "yard", "yards"), "yd"),
        Unit("length", 1609.344, listOf("mi", "mile", "miles"), "mi"),
        Unit("mass", 0.001, listOf("mg", "milligram", "milligrams"), "mg"),
        Unit("mass", 1.0, listOf("g", "gram", "grams"), "g"),
        Unit("mass", 1000.0, listOf("kg", "kilo", "kilos", "kilogram", "kilograms"), "kg"),
        Unit("mass", 28.349523125, listOf("oz", "ounce", "ounces"), "oz"),
        Unit("mass", 453.59237, listOf("lb", "lbs", "pound", "pounds"), "lb"),
        Unit("mass", 1_000_000.0, listOf("t", "tonne", "tonnes", "metric ton", "metric tons"), "t"),
        Unit("volume", 0.001, listOf("ml", "milliliter", "milliliters", "millilitre", "millilitres"), "mL"),
        Unit("volume", 1.0, listOf("l", "liter", "liters", "litre", "litres"), "L"),
        Unit("volume", 3.785411784, listOf("gal", "gallon", "gallons"), "gal"),
        Unit("volume", 0.2365882365, listOf("cup", "cups"), "cups"),
        Unit("volume", 0.0295735295625, listOf("fl oz", "fluid ounce", "fluid ounces"), "fl oz"),
        Unit("speed", 1 / 3.6, listOf("km/h", "kmh", "kph", "kilometers per hour", "kilometres per hour"), "km/h"),
        Unit("speed", 0.44704, listOf("mph", "miles per hour"), "mph"),
        Unit("speed", 1.0, listOf("m/s", "meters per second", "metres per second"), "m/s"),
        Unit("speed", 0.514444, listOf("knot", "knots", "kn"), "kn"),
        Unit("data", 1.0, listOf("b", "byte", "bytes"), "B"),
        Unit("data", 1024.0, listOf("kb", "kilobyte", "kilobytes"), "KB"),
        Unit("data", 1024.0 * 1024, listOf("mb", "megabyte", "megabytes"), "MB"),
        Unit("data", 1024.0 * 1024 * 1024, listOf("gb", "gigabyte", "gigabytes"), "GB"),
        Unit("data", 1024.0 * 1024 * 1024 * 1024, listOf("tb", "terabyte", "terabytes"), "TB"),
        Unit("time", 1.0, listOf("s", "sec", "secs", "second", "seconds"), "s"),
        Unit("time", 60.0, listOf("min", "mins", "minute", "minutes"), "min"),
        Unit("time", 3600.0, listOf("h", "hr", "hrs", "hour", "hours"), "h"),
        Unit("time", 86400.0, listOf("day", "days"), "days"),
        Unit("time", 604800.0, listOf("week", "weeks"), "weeks"),
    )
    private val temps = mapOf(
        "c" to "C", "°c" to "C", "celsius" to "C", "degrees celsius" to "C", "centigrade" to "C",
        "f" to "F", "°f" to "F", "fahrenheit" to "F", "degrees fahrenheit" to "F",
        "k" to "K", "kelvin" to "K",
    )

    private fun find(name: String): Unit? = units.firstOrNull { name.trim().lowercase() in it.names }

    private val PATTERN = Regex(
        "^(?:convert\\s+|what(?:'s| is)\\s+|how much is\\s+)?(-?[0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([a-z°/ ]+?)\\s+(?:to|in|into|as)\\s+([a-z°/ ]+)$",
        RegexOption.IGNORE_CASE,
    )
    private val HOW_MANY = Regex("^how many\\s+([a-z°/ ]+?)\\s+(?:are\\s+)?in\\s+(-?[0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([a-z°/ ]+)$", RegexOption.IGNORE_CASE)

    data class Conversion(val value: Double, val from: String, val result: Double, val to: String) {
        fun describe(): String = "${Calculator.format(value)} $from = ${Calculator.format(result)} $to"
    }

    sealed interface Result {
        data class Ok(val conversion: Conversion) : Result
        data class Error(val message: String) : Result
    }

    /** Returns null when the text is not a unit conversion request. */
    fun tryConvert(text: String): Result? {
        val t = text.trim().trimEnd('?', '.', '!').trim()
        val (num, fromName, toName) = PATTERN.matchEntire(t)?.let { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
            ?: HOW_MANY.matchEntire(t)?.let { Triple(it.groupValues[2], it.groupValues[3], it.groupValues[1]) }
            ?: return null
        val value = num.replace(",", "").toDoubleOrNull() ?: return null
        val fromKey = fromName.trim().lowercase().removePrefix("degrees ").let { if (it in temps) it else fromName.trim().lowercase() }
        val toKey = toName.trim().lowercase().removePrefix("degrees ").let { if (it in temps) it else toName.trim().lowercase() }
        val ft = temps[fromKey]; val tt = temps[toKey]
        if (ft != null || tt != null) {
            if (ft == null || tt == null) return Result.Error("I can only convert temperatures to other temperature units.")
            val celsius = when (ft) { "C" -> value; "F" -> (value - 32) * 5 / 9; else -> value - 273.15 }
            val out = when (tt) { "C" -> celsius; "F" -> celsius * 9 / 5 + 32; else -> celsius + 273.15 }
            return Result.Ok(Conversion(value, "°$ft".replace("°K", "K"), out, "°$tt".replace("°K", "K")))
        }
        val from = find(fromName) ?: return null
        val to = find(toName) ?: return null
        if (from.dimension != to.dimension) return Result.Error("Can't convert ${from.label} (${from.dimension}) to ${to.label} (${to.dimension}).")
        return Result.Ok(Conversion(value, from.label, value * from.toBase / to.toBase, to.label))
    }
}
