package com.melhive.zenith.core.time

/** Spoken-friendly durations: "1 hour, 12 minutes and 30 seconds". */
object Durations {
    fun describe(totalSeconds: Long, withSeconds: Boolean = true): String {
        if (totalSeconds <= 0) return "now"
        val d = totalSeconds / 86_400
        val h = (totalSeconds % 86_400) / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        val parts = buildList {
            if (d > 0) add(unit(d, "day"))
            if (h > 0) add(unit(h, "hour"))
            if (m > 0) add(unit(m, "minute"))
            // Seconds matter for short countdowns; past a day they're noise.
            if (s > 0 && withSeconds && d == 0L) add(unit(s, "second"))
        }.ifEmpty { listOf(unit(maxOf(totalSeconds, 1), "second")) }
        return if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }

    private fun unit(n: Long, name: String) = "$n $name" + if (n == 1L) "" else "s"
}
