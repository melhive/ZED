package com.melhive.zed.core.conversation

/** A short word of praise when the user gets something done. Varied, so it never sounds like a recording. */
object Praise {
    private val lines = listOf(
        "Nicely done.", "Good work.", "One more off the list.", "Well done.", "That's progress.", "Great, that's handled.",
        "Good job getting that done.", "Solid work.", "You're on a roll.", "Done and dusted, nice.", "That's another one finished.", "Well handled.",
    )

    /** [seed] picks the line (the task's id or the time); [name] is added now and then. */
    fun pick(seed: Long, name: String? = null): String {
        val line = lines[(seed.mod(lines.size.toLong())).toInt()]
        return if (name != null && seed.mod(3L) == 0L) line.trimEnd('.') + ", $name." else line
    }
}
