package com.melhive.zed.tools.executors

import android.content.Context
import com.melhive.zed.AppContainer
import com.melhive.zed.core.brain.BrainRecall
import com.melhive.zed.core.security.ConfirmMode
import com.melhive.zed.core.voice.VoiceCatalog
import com.melhive.zed.core.voice.VoiceSelection
import com.melhive.zed.tools.ToolManager
import com.melhive.zed.tools.ToolResult
import com.melhive.zed.ui.navigation.Routes
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private fun ok(msg: String) = ToolResult(true, msg)
private fun fail(msg: String) = ToolResult(false, msg)

private val ALL = setOf("all", "everything", "every one", "all of them", "the whole list", "all tasks", "all reminders", "all items")
private val DONE = setOf("done", "completed", "finished", "checked", "done ones", "completed ones", "completed tasks", "done tasks")

/** True when every meaningful word of [query] is in [text] ("milk" ↔ "Buy milk"; "rent reminder" ↔ "Pay the rent"). */
private fun matches(query: String, text: String): Boolean {
    if (text.contains(query, ignoreCase = true)) return true
    val q = BrainRecall.keys(query) - setOf("task", "todo", "reminder", "item", "event")
    return q.isNotEmpty() && q.all { it in BrainRecall.keys(text) }
}

/**
 * Full control of ZED's own data and settings by voice or chat: delete anything in Facts,
 * Task list, Reminders, Knowledge and Schedule, change any setting, open any screen, and
 * learn what the user teaches (pronunciations, answering rules, shortcuts).
 */
fun registerAppControl(tm: ToolManager, c: AppContainer, context: Context) {
    // ---- Task list ----
    tm.register("todo.delete") { a, _ ->
        val q = a.string("item").trim().removePrefix("#").trim()
        val all = c.todos.list(true)
        if (all.isEmpty()) return@register ok("Your task list is already empty.")
        val gone = when {
            q.lowercase() in ALL -> all
            q.lowercase() in DONE -> all.filter { it.done }.ifEmpty { return@register ok("There are no completed tasks to delete.") }
            q.toLongOrNull() != null -> listOfNotNull(all.firstOrNull { it.id == q.toLong() }).ifEmpty { return@register fail("There's no task #$q.") }
            else -> {
                val exact = all.filter { it.title.equals(q, true) }
                val found = exact.ifEmpty { all.filter { matches(q, it.title) } }
                when {
                    found.isEmpty() -> return@register fail("No task matches “$q”.")
                    found.size > 1 && found.map { it.title.lowercase() }.distinct().size > 1 ->
                        return@register fail("Several tasks match: " + found.joinToString { "#${it.id} ${it.title}" } + ". Say which number to delete.")
                    else -> found
                }
            }
        }
        c.deleter.todos(gone)
        ok(if (gone.size == 1) "Deleted “${gone.first().title}” from your task list." else "Deleted ${gone.size} tasks.")
    }

    // ---- Reminders ----
    tm.register("reminder.delete") { a, _ ->
        val q = a.string("item").trim().removePrefix("#").trim()
        val all = c.db.reminders().observeAll().first()
        if (all.isEmpty()) return@register ok("You have no reminders.")
        val gone = when {
            q.lowercase() in ALL -> all
            q.toLongOrNull() != null -> listOfNotNull(all.firstOrNull { it.id == q.toLong() }).ifEmpty { return@register fail("There's no reminder #$q.") }
            else -> {
                val found = all.filter { matches(q, it.text) }
                val upcoming = found.filter { it.status == "scheduled" }
                when {
                    found.isEmpty() -> return@register fail("No reminder matches “$q”.")
                    upcoming.map { it.text.lowercase() }.distinct().size > 1 ->
                        return@register fail("Several reminders match: " + upcoming.joinToString { "#${it.id} ${it.text}" } + ". Say which number to delete.")
                    else -> found
                }
            }
        }
        c.deleter.reminders(gone)
        ok(if (gone.size == 1) "Deleted the reminder “${gone.first().text}”." else "Deleted ${gone.size} reminders.")
    }

    // ---- Calendar ----
    tm.register("calendar.delete") { a, _ ->
        val writer = com.melhive.zed.agents.CalendarWriter(context)
        if (!writer.granted()) return@register fail("Allow calendar access (Settings → Allow everything) so I can delete events.")
        val query = a.string("query").trim()
        val day = a.stringOrNull("date")?.let { parseDay(it) }
        val now = LocalDateTime.now()
        val events = writer.upcoming(System.currentTimeMillis()).filter { it.end.isAfter(now) }
            .filter { day == null || it.start.toLocalDate() == day }
            .filter { com.melhive.zed.core.understanding.CalendarMove.matches(query, it.title) }
        val generic = BrainRecall.keys(query).none { it !in setOf("meeting", "event", "appointment", "call", "calendar", "thing") }
        val event = when {
            events.isEmpty() -> return@register fail("I couldn't find an upcoming event matching “$query”" + (day?.let { " on ${it.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.ENGLISH))}" } ?: "") + ".")
            generic && events.size > 1 -> return@register fail("Which one? " + events.take(4).joinToString("; ") { "${it.title} (${it.start.format(DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.ENGLISH))})" } + ".")
            else -> events.first()
        }
        val begin = event.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (!c.deleter.calendar(com.melhive.zed.agents.CalendarReader.Item(event.id, begin, event.title, event.recurring), event.title))
            return@register fail("Your calendar didn't allow deleting “${event.title}”.")
        val whenText = event.start.format(DateTimeFormatter.ofPattern("EEEE, MMMM d 'at' h:mm a", Locale.ENGLISH))
        ok("Deleted “${event.title}” ($whenText)" + if (event.recurring) " — just that one; the rest of the series stays." else ".")
    }

    // ---- Knowledge ----
    tm.register("docs.remove") { a, _ ->
        val q = a.string("query").trim()
        val docs = c.knowledge.documents.first()
        if (docs.isEmpty()) return@register ok("ZED's knowledge is already empty.")
        if (q.lowercase() in ALL || q.lowercase() in setOf("index", "knowledge", "the index")) {
            c.deleter.docs(docs)
            return@register ok("Cleared ZED's knowledge (${docs.size} documents). Your files themselves weren't touched.")
        }
        val found = docs.filter { it.path.contains(q, true) || matches(q, it.path.replace('/', ' ').replace('_', ' ').replace('-', ' ')) }
        if (found.isEmpty()) return@register fail("No indexed document matches “$q”.")
        c.deleter.docs(found)
        ok(if (found.size == 1) "Removed “${found.first().path}” from ZED's knowledge (the file itself stays)." else "Removed ${found.size} documents from ZED's knowledge (the files stay).")
    }

    // ---- The screen (accessibility service, only when asked) ----
    fun screen(): com.melhive.zed.assist.ZedAccessibilityService? = com.melhive.zed.assist.ZedAccessibilityService.instance
    val notOn = "To do that I need screen access: turn on ZED in Accessibility settings (I've opened them). It's used only when you ask."
    fun askForAccess(): ToolResult { com.melhive.zed.assist.ZedAccessibilityService.openSettings(context); return fail(notOn) }
    tm.register("screen.read") { a, _ ->
        val s = screen() ?: return@register askForAccess()
        val shot = s.readScreen() ?: return@register fail("I can't see another app on the screen right now.")
        if (shot.text.isBlank()) return@register ok("${shot.app} is open, but there's no text on the screen I can read.")
        val question = a.stringOrNull("question")?.takeIf { it.isNotBlank() } ?: "What's on my screen?"
        // Gemma reads it and answers like a person; without a model, the main text is read out.
        val provider = c.providers.active()
        if (provider != null) {
            val out = StringBuilder()
            runCatching {
                provider.generate(com.melhive.zed.intelligence.GenerationRequest(
                    system = "You see the text on the user's phone screen, in the app ${shot.app}. Answer their request about it briefly and naturally (2–4 sentences): summarize, explain or pick out what matters. Don't list every element and don't mention that you read text.",
                    turns = listOf(com.melhive.zed.core.intelligence.ChatTurn(com.melhive.zed.core.intelligence.Role.USER, "Screen text:\n${shot.text.take(3500)}\n\nRequest: $question")),
                    maxOutputTokens = 220, sessionKey = "screen",
                )).collect { e -> if (e is com.melhive.zed.intelligence.GenerationEvent.Delta) out.append(e.text) }
            }
            if (out.isNotBlank()) return@register ok(out.toString().trim())
        }
        ok("On ${shot.app}: " + shot.text.lines().take(12).joinToString(" · ").take(600))
    }
    tm.register("screen.tap") { a, _ ->
        val s = screen() ?: return@register askForAccess()
        val target = a.string("target")
        s.tap(target)?.let { ok("Tapped “$it”.") } ?: fail("I can't find “$target” on the screen.")
    }
    tm.register("screen.scroll") { a, _ ->
        val s = screen() ?: return@register askForAccess()
        if (s.scroll(a.string("direction"))) ok("Scrolled ${a.string("direction")}.") else fail("There's nothing to scroll here.")
    }
    tm.register("screen.type") { a, _ ->
        val s = screen() ?: return@register askForAccess()
        if (s.type(a.string("text"), a.stringOrNull("field"))) ok("Typed “${a.string("text")}”.") else fail("I can't find a text box on the screen.")
    }
    tm.register("screen.action") { a, _ ->
        val s = screen() ?: return@register askForAccess()
        val act = a.string("action")
        if (s.global(act)) ok(when (act) { "back" -> "Back."; "home" -> "Home."; "recents" -> "Recent apps."; "notifications" -> "Notifications."; "quick_settings" -> "Quick settings."; "lock" -> "Locked."; else -> "Screenshot taken." })
        else fail("This phone doesn't allow that.")
    }

    // ---- Memory care and chat history ----
    tm.register("memory.pin") { a, _ ->
        val q = a.string("query").trim()
        val pin = a.boolOrNull("pinned") ?: true
        val found = c.memory.list(1000).filter { it.source != "preference" && matches(q, it.text) }
        if (found.isEmpty()) return@register fail("I don't remember anything about “$q”.")
        found.forEach { c.memory.update(it.copy(pinned = pin)) }
        ok((if (pin) "Pinned " else "Unpinned ") + if (found.size == 1) "“${found[0].text}”." else "${found.size} memories.")
    }
    tm.register("memory.confirm") { a, _ ->
        val q = a.string("query").trim()
        val learned = c.memory.list(1000).filter { it.source == "learned" && (q.lowercase() in ALL || matches(q, it.text)) }
        if (learned.isEmpty()) return@register ok("There's nothing I learned by myself to confirm" + if (q.lowercase() !in ALL) " about “$q”." else ".")
        learned.forEach { c.learner.confirm(it.id) }
        ok(if (learned.size == 1) "Thanks — “${learned[0].text}” is confirmed." else "Thanks — ${learned.size} things I learned are confirmed.")
    }
    tm.register("chats.clear") { a, _ ->
        val q = a.string("which").trim()
        val all = c.db.conversations().observeAll().first()
        val current = c.orchestrator.currentConversation
        val gone = if (q.lowercase() in ALL || q.lowercase() in setOf("history", "chat history")) all.filter { it.id != current }
            else all.filter { it.id != current && (matches(q, it.title) || c.conversations.search(q).any { m -> m.conversationId == it.id }) }
        if (gone.isEmpty()) return@register ok(if (q.lowercase() in ALL) "There's no other chat history to clear." else "No conversation matches “$q”.")
        c.deleter.conversations(gone)
        ok("Deleted ${if (gone.size == 1) "1 conversation" else "${gone.size} conversations"} (this one stays). Say “undo” to bring ${if (gone.size == 1) "it" else "them"} back.")
    }

    // ---- People ZED recognises in Eye mode ----
    tm.register("people.forget") { a, _ ->
        val name = a.string("name").trim()
        if (c.people.forget(name)) ok("I've forgotten ${name.replaceFirstChar { it.uppercase() }}'s face.") else ok("I don't know anyone called $name by sight.")
    }

    // ---- Undo ----
    tm.register("app.undo") { _, _ -> c.undo.undo()?.let { ok(it) } ?: ok("There's nothing to undo.") }

    // ---- Screens of the app ----
    tm.register("app.show") { a, _ ->
        val screen = a.string("screen")
        val (route, label) = when (screen) {
            "home" -> Routes.HOME to "Home"
            "chat" -> (c.live.conversation?.let { Routes.chat(it) } ?: Routes.CHAT) to "Chat"
            "live" -> Routes.LIVE to "LIVE"
            "eye" -> Routes.EYE to "Eye mode"
            "tasks" -> Routes.TASKS to "background tasks"
            "more" -> Routes.MORE to "More"
            "facts" -> Routes.memoryTab(0) to "your facts"
            "task_list" -> Routes.memoryTab(1) to "your task list"
            "reminders" -> Routes.memoryTab(2) to "your reminders"
            "knowledge" -> Routes.memoryTab(3) to "ZED's knowledge"
            "schedule" -> Routes.SCHEDULE to "your schedule"
            "taught" -> Routes.memoryTab(5) to "what you've taught me"
            "brain" -> Routes.BRAIN to "the Live Brain"
            "settings" -> Routes.SETTINGS to "Settings"
            "voice" -> Routes.VOICE to "Voice settings"
            "models" -> Routes.MODELS to "Model Manager"
            "files" -> Routes.FILES to "Files"
            "skills" -> Routes.SKILLS to "Skills"
            "routines" -> Routes.ROUTINES to "Routines"
            "diagnostics" -> Routes.DIAGNOSTICS to "Permissions & Diagnostics"
            "backup" -> Routes.BACKUP to "Backup"
            "whats_new" -> Routes.WHATS_NEW to "What's new"
            else -> return@register fail("I can't open “$screen”.")
        }
        context.startActivity(com.melhive.zed.assist.ZedLaunch.intent(context, route))
        ok("Opening $label.")
    }

    // ---- Settings ----
    tm.register("settings.set") { a, _ ->
        val raw = a.string("value").trim()
        val value = raw.lowercase()
        val s = c.settings.current()
        fun bool(): Boolean? = when (value) { "on", "true", "yes", "enable", "enabled", "1" -> true; "off", "false", "no", "disable", "disabled", "0" -> false; else -> null }
        suspend fun toggle(name: String, set: suspend (Boolean) -> Unit, after: (Boolean) -> Unit = {}): ToolResult =
            bool()?.let { set(it); after(it); ok("$name ${if (it) "on" else "off"}.") } ?: fail("Say on or off for $name.")
        fun step(current: Float): Float? = when (value) {
            "faster", "higher", "up", "more" -> current + 0.15f
            "slower", "lower", "down", "less" -> current - 0.15f
            "normal", "default", "reset" -> 1f
            else -> value.toFloatOrNull()
        }?.coerceIn(0.5f, 2f)
        when (a.string("key")) {
            "voice_responses" -> toggle("Voice replies", { c.settings.setVoiceResponses(it) })
            "auto_index" -> toggle("Automatic indexing", { c.settings.setAutoIndex(it) })
            "use_knowledge" -> toggle("Using your documents in answers", { c.settings.setUseKnowledge(it) })
            "theme_glow" -> toggle("Glow effects", { c.settings.setGlow(it) })
            "barge_in" -> toggle("Interrupting ZED while it speaks", { c.settings.setBargeIn(it) })
            "web_research" -> toggle("Web research", { c.settings.setWebResearch(it) })
            "think_twice" -> toggle("Think twice", { c.settings.setThinkTwice(it) })
            "brain_sounds" -> toggle("Brain sounds", { c.settings.setBrainSounds(it) })
            "check_updates" -> toggle("Update checks", { c.settings.setCheckUpdates(it) })
            "live_sound" -> {
                val style = com.melhive.zed.core.voice.SoundSynth.Style.entries.firstOrNull { it.id == value || it.label.equals(raw, true) || it.label.lowercase().startsWith(value) }
                    ?: return@register fail("LIVE sounds: " + com.melhive.zed.core.voice.SoundSynth.Style.entries.joinToString { it.label } + ".")
                c.settings.setLiveSound(style.id)
                if (style != com.melhive.zed.core.voice.SoundSynth.Style.OFF) com.melhive.zed.voice.ZedSounds.play(context, style, start = true)
                ok(if (style == com.melhive.zed.core.voice.SoundSynth.Style.OFF) "LIVE sounds off." else "LIVE sound set to ${style.label}.")
            }
            "eye_color" -> {
                val colour = com.melhive.zed.core.eyes.EyeColors.parse(value) ?: return@register fail("Eye colours: cyan, amber, green or violet.")
                c.settings.setEyeColor(colour)
                ok("My eyes are $colour now.")
            }
            "caption_size" -> { val large = value.startsWith("large") || value.startsWith("big"); c.settings.setCaptionSize(if (large) "large" else "normal"); ok(if (large) "Captions are larger now." else "Captions are back to normal size.") }
            "live_haptics" -> toggle("Vibration with my sounds", { c.settings.setLiveHaptics(it) })
            "lock_guard" -> toggle("Guest mode while the phone is locked", { c.settings.setLockGuard(it) })
            "eye_recognition" -> toggle("Recognising people in Eye mode", { c.settings.setEyeRecognition(it) })
            "stop_word" -> toggle("Saying “stop” to interrupt me", { c.settings.setVoiceStop(it) })
            "listening_time" -> {
                val v = when { value.startsWith("short") || value == "less" -> "short"; value.startsWith("long") || value == "more" -> "long"; else -> "normal" }
                c.settings.setListenPatience(v)
                ok(when (v) { "short" -> "I'll stop listening sooner after I answer."; "long" -> "I'll keep listening longer after I answer."; else -> "Listening time is back to normal." })
            }
            "eye_captions" -> toggle("Eye mode captions", { c.settings.setEyeCaptions(it) })
            "hide_beep" -> toggle("Hiding the recognizer beep", { c.settings.setHideRecognizerBeep(it) })
            "speech_recognition" -> {
                val phone = value.contains("phone") || value.contains("google") || value.contains("android") || value == "off"
                if (!phone && !c.voicePacks.isInstalled(VoiceCatalog.SPEECH)) return@register fail("On-device speech recognition isn't downloaded yet — get it in More → Voice (${VoiceCatalog.SPEECH.sizeBytes / 1_000_000} MB).")
                c.settings.setSpeechEngine(if (phone) "android" else "auto")
                ok(if (phone) "I'll use the phone's speech service to hear you." else "I'll hear you on the phone itself — offline, no beep.")
            }
            "announce_reminders" -> toggle("Announcing reminders", { c.settings.setAnnounceReminders(it) })
            "alarm_briefing" -> toggle("The briefing after your alarm", { c.settings.setAlarmBriefing(it) })
            "event_announce" -> {
                val minutes = if (value in setOf("off", "no", "false", "none", "0")) 0 else Regex("\\d+").find(value)?.value?.toIntOrNull() ?: 10
                c.settings.setEventAnnounceMinutes(minutes)
                com.melhive.zed.agents.Announcer.planEvents(context)
                ok(if (minutes == 0) "I won't announce calendar events." else "I'll announce calendar events $minutes minutes before they start.")
            }
            "ai_model" -> {
                val models = c.models.installed.first().filter { c.models.fileFor(it).exists() }
                if (models.isEmpty()) return@register fail("No AI model is downloaded yet (Model Manager).")
                val q = value.replace("gemma", "gemma ").replace(Regex("\\s+"), " ").trim()
                val pick = models.firstOrNull { it.displayName.equals(raw, true) } ?: models.firstOrNull { m -> q.split(' ').filter { it.length > 1 }.all { w -> m.displayName.lowercase().contains(w) } }
                    ?: return@register fail("Downloaded models: " + models.joinToString { it.displayName } + ".")
                c.settings.setActiveLocalModel(pick.id)
                c.warmUpModel()
                ok("Switched to ${pick.displayName}.")
            }
            "model_backend" -> {
                val gpu = value.contains("gpu") || value.contains("graphics")
                c.settings.setLocalBackend(if (gpu) com.melhive.zed.settings.LocalBackend.GPU else com.melhive.zed.settings.LocalBackend.CPU)
                runCatching { c.localRuntime.unload() }
                ok("Gemma will run on the ${if (gpu) "GPU" else "CPU"} from the next answer.")
            }
            "headset_audio" -> {
                val earMic = value.contains("phone") || value.contains("speaker") || value.contains("earphone_mic") || value == "mic"
                c.settings.setHeadsetAudio(if (earMic) "earphone_mic" else "auto")
                ok(if (earMic) "Okay — with earphones I'll listen through their mic and talk from the phone's speaker." else "Okay — with earphones I'll talk in them, like everything else.")
            }
            "greeting" -> toggle("Greeting when you call me", { c.settings.setGreetOnCall(it) })
            "auto_learning" -> toggle("Learning from conversations", { c.settings.setAutoLearning(it) }) { com.melhive.zed.learning.Learner.schedule(context.applicationContext, it) }
            "proactive_hints" -> toggle("Hints", { c.settings.setProactiveHints(it) }) { com.melhive.zed.learning.Dreamer.scheduleHints(context.applicationContext, it) }
            "briefing" -> toggle("The daily briefing", { c.settings.setBriefing(it, s.briefingTime); c.routines.scheduleAll() })
            "briefing_time" -> {
                val t = runCatching { java.time.LocalTime.parse(raw) }.getOrNull() ?: return@register fail("Give the briefing time as HH:mm, e.g. 07:00.")
                val hhmm = "%02d:%02d".format(t.hour, t.minute)
                c.settings.setBriefing(true, hhmm); c.routines.scheduleAll()
                ok("Your daily briefing is set for ${t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))} every day.")
            }
            "speech_rate" -> step(s.speechRate)?.let { c.settings.setSpeechRate(it); ok("Speaking speed set to ${"%.2f".format(it)}×.") } ?: fail("Speaking speed is from 0.5 to 2.")
            "speech_pitch" -> step(s.speechPitch)?.let { c.settings.setSpeechPitch(it); ok("Voice pitch set to ${"%.2f".format(it)}.") } ?: fail("Pitch is from 0.5 to 2.")
            "voice" -> {
                val name = value.removeSuffix(" voice").removePrefix("the ").trim()
                if (name in setOf("system", "phone", "phone voice", "default", "android", "system voice")) {
                    c.settings.setVoiceId(VoiceSelection.SYSTEM_DEFAULT); return@register ok("Switched to the phone's own voice.")
                }
                val voice = VoiceCatalog.packs.flatMap { it.voices }.firstOrNull { it.name.equals(name, true) }
                    ?: VoiceCatalog.packs.flatMap { it.voices }.firstOrNull { it.name.lowercase().startsWith(name) }
                    ?: return@register fail("I don't have a voice called “$raw”. Voices are in More → Voice.")
                val pack = VoiceCatalog.pack(voice.packId)!!
                if (!c.voicePacks.isInstalled(pack)) return@register fail("${voice.name} needs the ${pack.name} voice pack. Download it in More → Voice, then ask me again.")
                c.settings.setVoiceId(voice.id)
                ok("I'll speak as ${voice.name} from now on.")
            }
            "wake_word" -> {
                val on = bool() ?: return@register fail("Say on or off for “Hey Zed”.")
                if (on && !c.voicePacks.isInstalled(VoiceCatalog.WAKE_WORD)) return@register fail("“Hey Zed” needs its small listening model. Download it in More → Voice.")
                c.settings.setWakeWordEnabled(on)
                if (on) com.melhive.zed.voice.WakeWordService.start(context) else com.melhive.zed.voice.WakeWordService.stop(context)
                ok(if (on) "“Hey Zed” is on." else "“Hey Zed” is off.")
            }
            "wake_sensitivity" -> {
                val level = when {
                    value.startsWith("max") || value.startsWith("most") || value == "highest" || value == "3" -> 3
                    value.startsWith("high") || value == "2" -> 2
                    value in setOf("balanced", "normal", "medium", "1") -> 1
                    value.startsWith("low") || value.startsWith("fewer") || value == "0" -> 0
                    else -> return@register fail("Sensitivity can be low, balanced, high or maximum.")
                }
                c.settings.setWakeSensitivity(level)
                if (s.wakeWordEnabled) { com.melhive.zed.voice.WakeWordService.stop(context); com.melhive.zed.voice.WakeWordService.start(context) }
                ok("“Hey Zed” sensitivity set to ${listOf("fewer false alarms", "balanced", "high", "maximum")[level]}.")
            }
            "animation" -> {
                val level = when (value) { "full", "on", "more" -> "full"; "balanced", "medium" -> "balanced"; "light", "less", "minimal", "off", "low" -> "light"; else -> return@register fail("Animation can be full, balanced or light.") }
                c.settings.setAnimationLevel(level); ok("Animation set to $level.")
            }
            "ask_before" -> {
                val mode = when { value.startsWith("never") || value == "off" || value == "no" -> ConfirmMode.NEVER; value.startsWith("always") || value.startsWith("every") -> ConfirmMode.ALWAYS; else -> ConfirmMode.RISKY }
                c.settings.setConfirmMode(mode.name); ok("Ask before actions: ${mode.label}.")
            }
            "user_title" -> {
                val title = if (value in setOf("none", "nothing", "no title", "my name", "off")) "" else raw.trim().trimEnd('.')
                c.settings.setUserTitle(title); ok(if (title.isEmpty()) "Okay, no title — I'll use your name." else "Okay, I'll call you $title.")
            }
            "weather_city" -> { c.settings.setWeatherCity(raw); ok("Your city is now $raw.") }
            "persona" -> {
                val p = com.melhive.zed.core.intelligence.Persona.entries.firstOrNull { it.name.equals(value, true) || it.label.equals(raw, true) || it.label.lowercase().startsWith(value) }
                    ?: return@register fail("Personalities: " + com.melhive.zed.core.intelligence.Persona.entries.joinToString { it.label } + ".")
                c.settings.setPersona(p.name); ok("Personality set to ${p.label}.")
            }
            else -> fail("Unknown setting.")
        }
    }

    // ---- Learning from the user ----
    tm.register("teach.pronounce") { a, _ ->
        val word = a.string("word").trim().trim('"', '“', '”')
        val say = a.string("say").trim().trim('"', '“', '”')
        c.teaching.pronounce(word, say)
        ok("Got it — from now on I'll pronounce it “$say”.")
    }
    tm.register("teach.rule") { a, _ ->
        val rule = a.string("rule").trim().trimEnd('.').replaceFirstChar { it.uppercase() } + "."
        val lower = rule.lowercase()
        val extra = mutableListOf<String>()
        // Rules that are really settings change the setting too.
        if (Regex("\\b(ask|confirm\\w*)\\b").containsMatchIn(lower) && Regex("\\b(before|confirm\\w*|permission|allow)\\b").containsMatchIn(lower)) {
            val mode = if (Regex("^(always|from now on,? always)\\b").containsMatchIn(lower) && !lower.contains("don't") && !lower.contains("never")) ConfirmMode.ALWAYS else ConfirmMode.NEVER
            c.settings.setConfirmMode(mode.name); extra += if (mode == ConfirmMode.NEVER) "I won't ask before doing things." else "I'll ask before every change."
        }
        Regex("(?:stop calling me|don'?t call me|do not call me|never call me) ([\\p{L}' ]+)").find(lower)?.let { m ->
            if (c.settings.current().userTitle.equals(m.groupValues[1].trim(), true)) { c.settings.setUserTitle(""); extra += "No more “${m.groupValues[1].trim()}”." }
        }
        val f = c.memory.remember(rule, "preference")
        if (!f.pinned) c.memory.update(f.copy(pinned = true))
        ok("Got it — from now on: ${rule.replaceFirstChar { it.lowercase() }}" + if (extra.isNotEmpty()) " " + extra.joinToString(" ") else "")
    }
    tm.register("teach.alias") { a, _ ->
        val phrase = a.string("phrase").trim().trim('"', '“', '”')
        val meaning = a.string("meaning").trim().trim('"', '“', '”')
        c.teaching.alias(phrase, meaning)
        ok("Got it — when you say “$phrase”, I'll take it as “$meaning”.")
    }
    tm.register("teach.forget") { a, _ ->
        val what = a.string("what").trim().trim('"', '“', '”')
        val rules = c.memory.list(1000).filter { it.source == "preference" }
        if (what.lowercase() in ALL || what.lowercase() in setOf("lessons", "everything you learned")) {
            val n = rules.size + c.teaching.pronunciations.size + c.teaching.aliases.size
            c.deleter.lessons(rules, c.teaching.pronunciations, c.teaching.aliases)
            return@register ok("Forgot all $n things you taught me.")
        }
        val gone = mutableListOf<String>()
        val say = c.teaching.pronunciations.filterKeys { it.equals(what, true) || matches(what, it) }
        val alias = c.teaching.aliases.filterKeys { it.equals(what, true) || matches(what, it) }
        val ruleHits = rules.filter { matches(what, it.text) }
        say.keys.forEach { gone += "how to say “$it”" }
        alias.keys.forEach { gone += "the shortcut “$it”" }
        ruleHits.forEach { gone += "the rule “${it.text.trimEnd('.')}”" }
        c.deleter.lessons(ruleHits, say, alias)
        if (gone.isEmpty()) fail("I don't have a lesson about “$what”. Say “what have you learned” to hear them.")
        else ok("Forgot " + gone.joinToString(", ") + ".")
    }
    tm.register("teach.list") { _, _ ->
        val rules = c.memory.list(1000).filter { it.source == "preference" }.map { it.text }
        val say = c.teaching.pronunciations
        val alias = c.teaching.aliases
        if (rules.isEmpty() && say.isEmpty() && alias.isEmpty())
            return@register ok("You haven't taught me anything yet. Try “from now on keep answers short”, “pronounce Cebu as seh-boo” or “when I say lights out, I mean turn off the lights”.")
        ok(buildList {
            if (rules.isNotEmpty()) add("How you like answers:\n" + rules.joinToString("\n") { "• $it" })
            if (say.isNotEmpty()) add("Pronunciations:\n" + say.entries.joinToString("\n") { "• ${it.key} → ${it.value}" })
            if (alias.isNotEmpty()) add("Your shortcuts:\n" + alias.entries.joinToString("\n") { "• “${it.key}” → ${it.value}" })
        }.joinToString("\n\n"))
    }
}

/** today, tomorrow, 2026-11-20, or a weekday name ("friday", "next monday"). */
private fun parseDay(text: String): LocalDate? {
    val t = text.trim().lowercase()
    val today = LocalDate.now()
    return when (t) {
        "", "today", "tonight" -> today
        "tomorrow" -> today.plusDays(1)
        else -> runCatching { LocalDate.parse(t) }.getOrNull()
            ?: DayOfWeek.values().firstOrNull { t.removePrefix("next ").startsWith(it.name.lowercase()) }?.let { today.with(TemporalAdjusters.next(it)) }
    }
}
