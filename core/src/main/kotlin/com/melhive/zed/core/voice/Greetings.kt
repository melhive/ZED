package com.melhive.zed.core.voice

import kotlin.random.Random

/**
 * What ZED says the moment it is called ("Hey Zed" or the assistant shortcut), so it
 * answers like an assistant at the ready instead of waiting in silence. Many variations,
 * some for the time of day, never the same one twice in a row. "{who}" becomes ", sir"
 * (the title the user chose), ", Rommel" (their name) or nothing.
 */
object Greetings {
    private val ANYTIME = listOf(
        "Hello{who}. How can I help you today?",
        "At your service{who}.",
        "Yes{who}?",
        "I'm here{who}. What do you need?",
        "Online and ready{who}.",
        "Listening{who}.",
        "What can I do for you{who}?",
        "Ready when you are{who}.",
        "How may I assist you{who}?",
        "Go ahead{who}, I'm listening.",
        "Systems online{who}. What's next?",
        "Standing by{who}.",
        "You called{who}?",
        "All systems ready{who}. How can I help?",
        "I'm all ears{who}.",
        "What's on your mind{who}?",
        "Here and ready{who}. What shall we do?",
        "Always a pleasure{who}. What do you need?",
        "Awaiting your instructions{who}.",
        "Right here{who}. What can I get done for you?",
        "Hello{who}. What are we working on?",
        "Fully operational{who}. How can I help?",
        "Ready to assist{who}.",
        "Tell me what you need{who}.",
    )
    private val MORNING = listOf(
        "Good morning{who}. How can I help you today?",
        "Morning{who}. What's first on the list?",
        "Good morning{who}. Ready when you are.",
        "Good morning{who}. What can I do for you?",
    )
    private val AFTERNOON = listOf(
        "Good afternoon{who}. How can I help?",
        "Good afternoon{who}. What do you need?",
        "Afternoon{who}. What can I do for you?",
    )
    private val EVENING = listOf(
        "Good evening{who}. How can I help you?",
        "Good evening{who}. What can I do for you?",
        "Evening{who}. What do you need?",
    )
    private val NIGHT = listOf(
        "Working late{who}? How can I help?",
        "Still up{who}? What do you need?",
        "I'm here{who}, even at this hour. What can I do?",
    )

    /** Every phrase ZED might say, for tests and previews. */
    val all: List<String> get() = ANYTIME + MORNING + AFTERNOON + EVENING + NIGHT

    /**
     * A greeting for [hour] (0–23). [title] ("sir", "boss") wins over [name]; with neither
     * ZED just greets. [last] is the previous greeting, never repeated.
     */
    fun pick(hour: Int, title: String?, name: String?, last: String? = null, random: Random = Random.Default): String {
        val who = (title?.trim()?.takeIf { it.isNotEmpty() } ?: name?.trim()?.takeIf { it.isNotEmpty() })?.let { ", $it" }.orEmpty()
        val timed = when (hour) { in 5..11 -> MORNING; in 12..17 -> AFTERNOON; in 18..22 -> EVENING; else -> NIGHT }
        // About a third of the time the greeting fits the time of day.
        val pool = (if (random.nextInt(3) == 0) timed else ANYTIME + timed).map { it.replace("{who}", who) }
        val choices = pool.filter { it != last }.ifEmpty { pool }
        return choices[random.nextInt(choices.size)]
    }
}
