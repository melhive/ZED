package com.melhive.zed.core.understanding

import com.melhive.zed.core.command.CommandParser
import com.melhive.zed.core.command.ParsedCommand
import com.melhive.zed.core.tools.ToolRequest
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * What the conversation is currently about, so follow-ups make sense:
 * "call her" (who), "remind me about it tomorrow" (what), "move that to 5"
 * (which action), "no, I meant Friday" (which time).
 */
data class DialogueState(
    /** The last person mentioned or acted on ("Ana"). */
    val person: String? = null,
    /** The last thing talked about or acted on ("call the dentist"). */
    val topic: String? = null,
    /** The last action that has a time, so it can be moved or corrected. */
    val lastTimed: ToolRequest? = null,
    /** Id of the reminder that action created, when it was a reminder. */
    val reminderId: Long? = null,
) {
    /** Remembers what an action that succeeded was about. [result] is the tool's message. */
    fun after(request: ToolRequest, result: String): DialogueState {
        val a = request.args
        return when (request.tool) {
            "phone.call" -> copy(person = a["who"]?.let(::nameOnly) ?: person)
            "sms.compose" -> copy(person = a["to"]?.let(::nameOnly) ?: person)
            "contacts.find" -> copy(person = a["name"]?.let(::nameOnly) ?: person)
            "reminder.create", "reminder.reschedule" -> copy(
                topic = a["text"] ?: topic, lastTimed = if (request.tool == "reminder.create") request else lastTimed?.let { it.copy(args = it.args + ("at" to (a["at"] ?: ""))) },
                reminderId = REMINDER_ID.find(result)?.groupValues?.get(1)?.toLongOrNull() ?: reminderId,
                person = personIn(a["text"]) ?: person,
            )
            "alarm.set" -> copy(lastTimed = request, reminderId = null)
            "timer.set" -> copy(lastTimed = request, reminderId = null)
            "calendar.create" -> copy(topic = a["title"] ?: topic, lastTimed = request, reminderId = null, person = personIn(a["title"]) ?: person)
            "calendar.move" -> copy(lastTimed = lastTimed?.takeIf { it.tool == "calendar.create" }?.let { it.copy(args = it.args + ("start" to (a["at"] ?: it.args["start"].orEmpty()))) })
            "todo.add" -> copy(topic = a["title"] ?: topic, person = personIn(a["title"]) ?: person)
            "memory.remember" -> copy(person = personIn(a["fact"]) ?: person)
            else -> this
        }
    }

    /** Picks up who and what a plain message is about ("I need to call the dentist about Ana's tooth"). */
    fun heard(text: String): DialogueState = copy(person = personIn(text) ?: person)

    /** A one-line summary for the model ("“her” = Ana · topic: call the dentist"). */
    fun describe(): String? = listOfNotNull(
        person?.let { "the person just mentioned is $it" },
        topic?.let { "the current topic is “$it”" },
    ).joinToString(" · ").ifBlank { null }

    companion object {
        private val REMINDER_ID = Regex("Reminder #(\\d+)")
        private val NOT_NAMES = setOf(
            "I", "I'm", "I'll", "I've", "Zed", "ZED", "OK", "Okay", "Hey", "Hi", "Hello", "Please", "Thanks", "Thank", "Yes", "No", "The", "A", "An",
            "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday", "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December", "Today", "Tomorrow", "Tonight", "Remind", "Call", "Text", "Set",
            "What", "When", "Where", "Who", "How", "Why", "Can", "Could", "Would", "Will", "Do", "Does", "Is", "Are", "My", "Me", "It", "That",
            "This", "Christmas", "Easter", "God", "AM", "PM", "Spotify", "YouTube", "Google", "Wi-Fi", "Bluetooth", "Android",
        )
        private val NAME = Regex("(?<![.!?]\\s|^)\\b([A-Z][a-z]{1,15}(?:\\s[A-Z][a-z]{1,15})?)\\b")

        /** A capitalised name that isn't at the start of a sentence ("call Ana", "lunch with Mark Cruz"). */
        fun personIn(text: String?): String? {
            if (text.isNullOrBlank()) return null
            return NAME.findAll(text).map { it.groupValues[1] }
                .map { n -> n.split(' ').filter { it !in NOT_NAMES }.joinToString(" ") }
                .firstOrNull { it.isNotBlank() }
        }

        private fun nameOnly(who: String): String? = who.trim().takeIf { it.isNotEmpty() && !it.any { c -> c.isDigit() } }
            ?.replace(Regex("^(?:my |the )", RegexOption.IGNORE_CASE), "")
    }
}

/** What to do with a message after following the conversation. */
sealed interface Resolution {
    /** Nothing to resolve: handle [text] as it is. */
    data class Text(val text: String) : Resolution
    /** Several requests in one sentence ("turn on the flashlight and set a timer for 5 minutes"). */
    data class Several(val parts: List<String>) : Resolution
    /** A follow-up that changes the last action ("move that to 5", "no, I meant Friday"). */
    data class Act(val request: ToolRequest, val note: String) : Resolution
    /** A follow-up ZED can't carry out (and says why). */
    data class Explain(val message: String) : Resolution
}

/**
 * Follows the conversation: fills in pronouns, applies corrections and splits
 * sentences that hold several requests. Purely textual and deterministic; it
 * never invents anything the state or the sentence doesn't contain.
 */
class DialogueTracker(private val clock: () -> LocalDateTime = { LocalDateTime.now() }) {
    private val parser = CommandParser(clock)
    private val opt = setOf(RegexOption.IGNORE_CASE)

    // "move it to 5", "change that to Friday", "make it 6 pm", "push the reminder to tomorrow".
    private val MOVE = Regex("^(?:(?:can you |please |ok,? |okay,? )?(?:move|change|push|shift|reschedule|set|make|put)) (?:it|that|this|the (?:reminder|alarm|timer|meeting|event|appointment))(?: (?:to|for|at|until))? (.+)$", opt)
    // "no, I meant Friday", "I said 5", "actually 6 pm", "make that 7", "not 5, 6".
    private val CORRECT = Regex("^(?:no[,.!]?\\s*|nope[,.!]?\\s*|sorry[,.!]?\\s*|oh[,.!]?\\s*)*(?:i meant|i mean|i said|actually|make (?:it|that)|rather|not .+?,)\\s*(.+?)(?: instead)?$", opt)
    private val INSTEAD = Regex("^(?:at |on |for )?(.+?) instead$", opt)

    private val PRONOUN_PERSON = Regex("\\b(her|him|them)\\b", opt)
    private val PERSON_VERBS = Regex("^(?:call|phone|ring|dial|text|message|sms|send (?:a )?(?:text|message|sms) to|remind me to (?:call|text|message|email|visit|see)|email)\\b", opt)
    private val REMIND_IT = Regex("^remind me (?:about |of )?(?:it|that|this)( .+)$", opt)
    private val ADD_IT = Regex("^(?:add|put) (?:it|that|this) (?:to|on|in) ((?:my |the )?(?:task|todo|to-do) list)$", opt)

    fun resolve(input: String, state: DialogueState, facts: List<String> = emptyList()): Resolution {
        val text = input.trim().trimEnd('.', '!').trim()
        if (text.isEmpty()) return Resolution.Text(input)

        // Corrections and moves apply to the last timed action.
        val change = MOVE.matchEntire(text)?.groupValues?.get(1)
            ?: CORRECT.matchEntire(text)?.groupValues?.get(1)?.takeIf { hintOf(it, clock()) != null }
            ?: INSTEAD.matchEntire(text)?.groupValues?.get(1)?.takeIf { hintOf(it, clock()) != null }
        if (change != null) followUpChange(change, state)?.let { return it }

        var out = text
        // "call her" → "call Ana".
        if (PERSON_VERBS.containsMatchIn(out) && PRONOUN_PERSON.containsMatchIn(out) && state.person != null) {
            out = PRONOUN_PERSON.replaceFirst(out, state.person)
        }
        // "call my sister" → "call Ana" when a memory says who that is.
        if (PERSON_VERBS.containsMatchIn(out)) Relations.whoIs(out, facts)?.let { (phrase, name) -> out = out.replace(phrase, name, ignoreCase = true) }
        // "remind me about it tomorrow" → "remind me about call the dentist tomorrow".
        state.topic?.let { topic ->
            REMIND_IT.matchEntire(out)?.let { m -> out = "remind me to ${topic.removePrefix("to ")}${m.groupValues[1]}" }
            ADD_IT.matchEntire(out)?.let { m -> out = "add $topic to ${m.groupValues[1]}" }
        }
        return split(out) ?: Resolution.Text(if (out == text) input.trim() else out)
    }

    /** "5", "5:30", "Friday", "tomorrow at 9": a bare number counts as a time here. */
    private fun hintOf(change: String, now: LocalDateTime): TimeHints.When? =
        TimeHints.find(change, now) ?: if (Regex("^\\d{1,2}(?::\\d{2})?$").matches(change.trim())) TimeHints.find("at ${change.trim()}", now) else null

    /** Applies "to 5" / "Friday" to the last timed action. */
    private fun followUpChange(change: String, state: DialogueState): Resolution? {
        val last = state.lastTimed ?: return null
        val now = clock()
        return when (last.tool) {
            "reminder.create" -> {
                val hint = hintOf(change, now) ?: return null
                val previous = last.args["at"]?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return null
                val at = TimeHints.adjust(previous, hint, now)
                val id = state.reminderId
                if (id != null) Resolution.Act(ToolRequest("reminder.reschedule", mapOf("id" to id.toString(), "at" to at.toString()), ToolRequest.Origin.NATIVE), "moved")
                else Resolution.Act(ToolRequest("reminder.create", mapOf("text" to (last.args["text"] ?: "Reminder"), "at" to at.toString()), ToolRequest.Origin.NATIVE), "new")
            }
            "alarm.set" -> {
                val hint = hintOf(change, now)?.takeIf { it.time != null } ?: return null
                var time: LocalTime = hint.time!!
                val prevHour = last.args["hour"]?.toIntOrNull() ?: 0
                if (hint.ambiguousHalf && prevHour >= 12 && time.hour in 1..11) time = time.plusHours(12)
                Resolution.Act(ToolRequest("alarm.set", mapOf("hour" to time.hour.toString(), "minute" to time.minute.toString()) + listOfNotNull(last.args["label"]?.let { "label" to it }), ToolRequest.Origin.NATIVE), "new alarm")
            }
            "timer.set" -> {
                val secs = TimeHints.duration(change) ?: return null
                Resolution.Act(ToolRequest("timer.set", mapOf("seconds" to secs.toString()) + listOfNotNull(last.args["label"]?.let { "label" to it }), ToolRequest.Origin.NATIVE), "new timer")
            }
            "calendar.create" -> {
                val previous = last.args["start"]?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return null
                val hint = hintOf(change, now) ?: return null
                val at = TimeHints.adjust(previous, hint, now)
                Resolution.Act(ToolRequest("calendar.move", mapOf("query" to (last.args["title"] ?: "event"), "from" to previous.toString(), "at" to at.toString()), ToolRequest.Origin.NATIVE), "moved")
            }
            else -> null
        }
    }

    /**
     * "turn on the flashlight and set a timer for 5 minutes" → two requests.
     * Only splits when every part is a request ZED understands on its own, so
     * "remind me to buy milk and eggs" stays one request.
     */
    private fun split(text: String): Resolution.Several? {
        val joiners = Regex("\\s*(?:,? and then |,? then |, and |; | and also | also | and )\\s*", opt)
        if (!joiners.containsMatchIn(text)) return null
        val pieces = joiners.split(text).map { it.trim() }.filter { it.isNotEmpty() }
        if (pieces.size < 2 || pieces.size > 4) return null
        // Greedy: join neighbours back together until each chunk is a command on its own.
        val parts = mutableListOf<String>()
        var current = ""
        for (p in pieces) {
            current = if (current.isEmpty()) p else "$current and $p"
            if (isCommand(current)) { parts += current; current = "" }
        }
        if (current.isNotEmpty() || parts.size < 2) return null
        return Resolution.Several(parts)
    }

    private fun isCommand(text: String): Boolean = when (parser.parse(text)) {
        is ParsedCommand.Tool, is ParsedCommand.Answer -> true
        else -> false
    }
}

/** Who "my sister" or "my dentist" is, from what the user told ZED. */
object Relations {
    private val RELATION = Regex("\\bmy (sister|brother|mom|mum|mother|dad|father|wife|husband|partner|girlfriend|boyfriend|son|daughter|boss|friend|best friend|dentist|doctor|grandma|grandmother|grandpa|grandfather|aunt|uncle|cousin|colleague|manager|teacher|landlord|neighbou?r)\\b", RegexOption.IGNORE_CASE)
    private val SAME = mapOf("mum" to "mom", "mother" to "mom", "father" to "dad", "grandmother" to "grandma", "grandfather" to "grandpa", "neighbor" to "neighbour")

    private fun norm(r: String) = r.lowercase().let { SAME[it] ?: it }

    /** ("my sister", "Ana") when [text] mentions "my sister" and a fact names her. */
    fun whoIs(text: String, facts: List<String>): Pair<String, String>? {
        val m = RELATION.find(text) ?: return null
        val rel = norm(m.groupValues[1])
        for (f in facts) {
            val fm = RELATION.find(f) ?: continue
            if (norm(fm.groupValues[1]) != rel) continue
            // "My sister's name is Ana", "My sister is Ana", "Ana is my sister", "my dentist is Dr. Cruz".
            val name = Regex("(?:name is|is called|is) ((?:Dr\\.? )?[A-Z][\\p{L}'-]+(?: [A-Z][\\p{L}'-]+)?)").find(f.substring(fm.range.last + 1))?.groupValues?.get(1)
                ?: Regex("^((?:Dr\\.? )?[A-Z][\\p{L}'-]+(?: [A-Z][\\p{L}'-]+)?) is my\\b").find(f.trim())?.groupValues?.get(1)
            if (name != null) return m.value to name
        }
        return null
    }
}
