package com.melhive.zed.core.understanding

import com.melhive.zed.core.tools.ToolSpec

/**
 * Picks the few tools that fit a message, so a small on-device model chooses
 * between three or four relevant tools instead of fifty. A message that
 * doesn't sound like a request gets none, and the model simply talks.
 */
object ToolSelector {
    private fun w(vararg words: String) = Regex("\\b(?:${words.joinToString("|")})\\b", RegexOption.IGNORE_CASE)

    /** Words that point at each tool. Order matters only for ties. */
    private val TRIGGERS: List<Pair<String, Regex>> = listOf(
        "reminder.create" to w("remind", "reminder", "don'?t let me forget", "remember to"),
        "reminder.reschedule" to w("(?:move|change|push|shift) (?:[\\w']+ ){0,3}reminders?", "reschedule"),
        "reminder.list" to w("reminders"),
        "reminder.cancel" to w("(?:cancel|delete|remove|stop) (?:[\\w']+ ){0,3}reminders?"),
        "alarm.set" to w("alarm", "wake me"),
        "timer.set" to w("timer", "countdown", "count down"),
        "time.left" to w("how long until", "how much time", "time left", "how long till", "when(?:'s| is) my next"),
        "time.now" to w("what time", "the time", "what day", "today'?s date", "the date"),
        "calendar.create" to w("schedule", "meeting", "appointment", "event", "calendar", "book"),
        "calendar.today" to w("agenda", "calendar", "schedule (?:today|tomorrow)", "what do i have", "what'?s on"),
        "todo.add" to w("task", "to-?do", "add .+ to (?:my )?list", "need to buy", "shopping"),
        "todo.list" to w("tasks", "to-?dos", "task list", "to-?do list"),
        "todo.complete" to w("done with", "finished", "complete", "check off", "mark"),
        "memory.remember" to w("remember", "note that", "keep in mind", "my .+ is", "i (?:like|love|hate|prefer|am allergic)", "don'?t forget"),
        "memory.list" to w("what do you (?:know|remember)", "memories"),
        "memory.forget" to w("forget"),
        "brain.show" to w("brain", "everything (?:you know )?about", "show me (?:what|all)"),
        "phone.call" to w("call", "phone", "ring", "dial"),
        "sms.compose" to w("text", "message", "sms", "tell .+ that"),
        "contacts.find" to w("number", "contact"),
        "app.open" to w("open", "launch", "start the app"),
        "music.play" to w("play", "music", "song", "playlist", "spotify"),
        "media.control" to w("pause", "resume", "skip", "next song", "previous song", "stop the music"),
        "volume.set" to w("volume", "louder", "quieter", "mute", "unmute"),
        "flashlight.set" to w("flashlight", "flash light", "torch"),
        "dnd.set" to w("do not disturb", "dnd", "silence"),
        "settings.panel" to w("wi-?fi", "bluetooth", "settings", "airplane", "location"),
        "device.status" to w("battery", "storage", "phone status", "charging"),
        "camera.open" to w("camera", "photo", "picture", "selfie", "video"),
        "maps.navigate" to w("navigate", "directions", "take me to", "how do i get", "route"),
        "weather.get" to w("weather", "rain", "temperature outside", "forecast", "sunny", "umbrella", "hot today", "cold today"),
        "notifications.recent" to w("notifications", "what did i miss", "missed"),
        "web.open" to w("google", "website", "browser", "open .+\\.(?:com|org|net)"),
        "web.research" to w("research", "look up", "search the web", "search online", "latest news", "news about", "who won", "price of"),
        "docs.search" to w("documents?", "docs", "my notes", "in my files"),
        "history.search" to w("we talked", "last time", "earlier you", "did i (?:say|tell)", "conversation history"),
        "file.read" to w("read (?:the |my )?file", "open (?:the |my )?file", "what'?s in"),
        "file.list" to w("list files", "show files", "folder", "files in"),
        "file.create" to w("create (?:a )?file", "new file", "write (?:a )?(?:file|note)"),
        "files.organize" to w("organi[sz]e", "tidy", "clean up", "sort my"),
        "routine.run" to w("routine", "good night", "good morning", "bedtime", "focus mode", "driving mode"),
        "home.control" to w("lights?", "lamp", "fan", "thermostat", "aircon", "ac", "heater", "plug", "switch (?:on|off)", "turn (?:on|off)"),
        "home.status" to w("is the .+ (?:on|off|open|locked)", "temperature in", "humidity"),
        "home.secure" to w("lock", "unlock", "garage", "front door", "gate", "blinds", "curtains"),
        "workflow.run" to w("workflow", "briefing", "brief me"),
        "settings.set" to w("speech rate", "voice responses", "talk faster", "talk slower"),
    )

    /** Tools that often follow another one (look up a number before texting). */
    private val COMPANIONS = mapOf(
        "reminder.cancel" to listOf("reminder.list"),
        "reminder.reschedule" to listOf("reminder.list"),
        "todo.complete" to listOf("todo.list"),
        "file.read" to listOf("file.list"),
    )

    private val FACTUAL = Regex("^(?:who|what|when|where|which|how (?:much|many|old|tall|far|long|big|high)|is|are|was|were|did|does)\\b", RegexOption.IGNORE_CASE)
    private val PERSONAL = Regex("\\b(?:you|your|yours|me|my|mine|i|i'm|i've|we|our|us)\\b", RegexOption.IGNORE_CASE)
    private val FRESH = Regex("\\b(?:latest|news|today'?s|current(?:ly)?|right now|this (?:week|month|year)|prices?|costs?|score|who won|results?|released?|update|stocks?|exchange rate|election|schedule of|opening hours|open now)\\b", RegexOption.IGNORE_CASE)

    /**
     * A question about the world that the web may answer better than a small
     * model's memory ("who won the game last night", "price of gold today").
     * Questions about the user or ZED itself never qualify.
     */
    fun looksFactual(text: String): Boolean {
        val t = text.trim()
        if (PERSONAL.containsMatchIn(t)) return false
        val words = t.split(Regex("\\s+")).size
        return words >= 3 && (FRESH.containsMatchIn(t) || FACTUAL.containsMatchIn(t))
    }

    /**
     * Up to [max] tools from [available] that fit [text], best first. [context]
     * is the previous message, used when this one is a short follow-up ("and
     * tomorrow?").
     */
    fun select(text: String, available: List<ToolSpec>, max: Int = 5, context: String? = null): List<ToolSpec> {
        val byName = available.associateBy { it.name }
        fun scores(t: String): Map<String, Int> = TRIGGERS.mapNotNull { (tool, rx) ->
            val hits = rx.findAll(t).count()
            if (hits > 0 && tool in byName) tool to hits else null
        }.toMap()
        var found = scores(text)
        if (found.isEmpty() && context != null && text.split(' ').size <= 6) found = scores(context)
        if (found.isEmpty()) return emptyList()
        val order = TRIGGERS.map { it.first }
        val ranked = found.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { order.indexOf(it.key) }).map { it.key }
        val picked = LinkedHashSet<String>()
        for (name in ranked) {
            picked += name
            COMPANIONS[name]?.filter { it in byName }?.let { picked += it }
            if (picked.size >= max) break
        }
        return picked.take(max).mapNotNull { byName[it] }
    }
}
