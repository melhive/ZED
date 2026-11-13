package com.melhive.zed.core

import com.melhive.zed.core.command.CommandParser
import com.melhive.zed.core.command.ParsedCommand
import com.melhive.zed.core.tools.ToolCatalog
import com.melhive.zed.core.tools.ToolValidator
import com.melhive.zed.core.tools.ValidationResult
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CommandParserTest {
    private val parser = CommandParser { LocalDateTime.of(2026, 11, 9, 10, 0) }
    private val validator = ToolValidator(ToolCatalog.DEFAULT)

    private fun tool(input: String): Pair<String, Map<String, String>> {
        val r = parser.parse(input)
        assertIs<ParsedCommand.Tool>(r, "Expected tool for: $input but got $r")
        // Every native command must also pass schema validation.
        val v = validator.validate(r.request)
        assertIs<ValidationResult.Valid>(v, "Validation failed for '$input': $v")
        return r.request.tool to r.request.args
    }

    @Test fun timeQueries() {
        listOf("what time is it?", "What's the date", "Zed, what day is it", "time").forEach {
            assertEquals("time.now", tool(it).first, it)
        }
    }

    @Test fun fileCommands() {
        assertEquals("file.create" to mapOf("path" to "notes/ideas.txt", "content" to "Buy a telescope"),
            tool("create file notes/ideas.txt with content Buy a telescope"))
        assertEquals("file.create" to mapOf("path" to "todo.md"), tool("Please create a new file called \"todo.md\""))
        assertEquals("folder.create" to mapOf("path" to "Projects/Zed"), tool("make a folder named Projects/Zed"))
        assertEquals("file.read" to mapOf("path" to "notes/ideas.txt"), tool("read file notes/ideas.txt"))
        assertEquals("file.read" to mapOf("path" to "report.md"), tool("read report.md"))
        assertEquals("file.list" to mapOf("path" to "Projects"), tool("list files in Projects"))
        assertEquals("file.list", tool("list files").first)
        assertEquals("file.move" to mapOf("source" to "a.txt", "destination" to "Archive"), tool("move a.txt to the folder Archive"))
        assertEquals("file.copy" to mapOf("source" to "a.txt", "destination" to "Backup"), tool("copy a.txt into Backup"))
        assertEquals("file.rename" to mapOf("path" to "a.txt", "new_name" to "b.txt"), tool("rename a.txt to b.txt"))
        assertEquals("file.delete" to mapOf("path" to "old/draft.txt"), tool("delete the file old/draft.txt"))
        assertEquals("files.organize" to mapOf("path" to "Download"), tool("organize my downloaded documents"))
    }

    @Test fun alarmsAndTimers() {
        assertEquals("alarm.set" to mapOf("hour" to "6", "minute" to "45"), tool("set an alarm for 6:45 am"))
        assertEquals("alarm.set" to mapOf("hour" to "19", "minute" to "0", "label" to "Gym"), tool("set alarm 7pm called Gym"))
        assertEquals("alarm.set", tool("wake me up at 7").first)
        assertEquals("timer.set" to mapOf("seconds" to "600"), tool("set a timer for 10 minutes"))
        assertEquals("timer.set" to mapOf("seconds" to "300"), tool("5 minute timer"))
        assertIs<ParsedCommand.Clarify>(parser.parse("set an alarm for whenever"))
    }

    @Test fun remindersAndTasks() {
        assertEquals("reminder.create" to mapOf("text" to "stretch", "at" to "2026-11-09T10:30"), tool("remind me to stretch in 30 minutes"))
        assertEquals("reminder.create" to mapOf("text" to "call mom at the office", "at" to "2026-11-09T17:00"), tool("remind me to call mom at the office at 5pm"))
        assertEquals("reminder.create" to mapOf("text" to "take the pills", "at" to "2026-11-10T09:00"), tool("remind me tomorrow at 9 to take the pills"))
        assertIs<ParsedCommand.Clarify>(parser.parse("remind me to buy bread"))
        assertEquals("reminder.list", tool("show my reminders").first)
        assertEquals("reminder.cancel" to mapOf("id" to "2"), tool("cancel reminder 2"))
        assertEquals("todo.add" to mapOf("title" to "buy milk"), tool("add task buy milk"))
        assertEquals("todo.add" to mapOf("title" to "renew passport"), tool("add renew passport to my todo list"))
        assertEquals("todo.list", tool("what are my tasks").first)
        assertEquals("todo.complete" to mapOf("item" to "buy milk"), tool("mark buy milk as done"))
        assertEquals("todo.complete" to mapOf("item" to "3"), tool("complete task 3"))
    }

    @Test fun calendar() {
        val (name, args) = tool("schedule meeting with Ana tomorrow at 3pm for 90 minutes")
        assertEquals("calendar.create", name)
        assertEquals("meeting with Ana", args["title"])
        assertEquals("2026-11-10T15:00", args["start"])
        assertEquals("2026-11-10T16:30", args["end"])
    }

    @Test fun memoryHistorySettingsWorkflows() {
        assertEquals("memory.remember" to mapOf("fact" to "my sister's name is Ana"), tool("remember that my sister's name is Ana"))
        assertEquals("memory.list", tool("what do you remember").first)
        assertEquals("memory.forget" to mapOf("query" to "sister"), tool("forget about sister"))
        assertEquals("history.search" to mapOf("query" to "budget"), tool("search history for budget"))
        assertEquals("docs.search" to mapOf("query" to "invoice 2025"), tool("search documents for invoice 2025"))
        assertEquals("docs.index", tool("index my documents").first)
        assertEquals("settings.set" to mapOf("key" to "voice_responses", "value" to "off"), tool("turn off voice responses"))
        assertEquals("workflow.run" to mapOf("name" to "morning briefing"), tool("morning briefing"))
        assertEquals(ParsedCommand.NotACommand, parser.parse("good morning")) // greeting, handled conversationally
        assertEquals("workflow.run" to mapOf("name" to "organize downloads"), tool("run workflow organize downloads"))
        assertEquals("web.research" to mapOf("query" to "best telescopes 2026"), tool("research best telescopes 2026"))
    }

    @Test fun rememberPhrasings() {
        listOf(
            "remember that I like green tea" to "I like green tea",
            "Remember: my car is a blue Civic" to "my car is a blue Civic",
            "please remember my locker code is 4412" to "my locker code is 4412",
            "don't forget that Ana is allergic to nuts" to "Ana is allergic to nuts",
            "keep in mind I work night shifts" to "I work night shifts",
            "note that the wifi password is on the fridge" to "the wifi password is on the fridge",
            "save this: passport expires 2029" to "passport expires 2029",
        ).forEach { (input, fact) -> assertEquals("memory.remember" to mapOf("fact" to fact), tool(input), input) }
        assertEquals("reminder.create" to mapOf("text" to "call Ana", "at" to "2026-11-09T17:00"), tool("remember to call Ana at 5pm"))
        assertEquals("todo.add" to mapOf("title" to "buy batteries"), tool("don't forget to buy batteries"))
    }

    @Test fun removalsAreRoutedToTheRightPlace() {
        assertEquals("todo.delete" to mapOf("item" to "buy milk"), tool("remove task buy milk"))
        assertEquals("memory.forget" to mapOf("query" to "sister"), tool("delete memory about sister"))
        assertEquals("file.list", tool("show me my files").first)
        assertEquals("file.delete", tool("delete notes.txt").first)
    }

    @Test fun nonCommandsFallThrough() {
        listOf("Explain quantum entanglement simply", "How are you today", "Write me a poem about the sea", "").forEach {
            assertEquals(ParsedCommand.NotACommand, parser.parse(it), it)
        }
        assertEquals(ParsedCommand.Help, parser.parse("what can you do?"))
    }

    @Test fun maliciousPathsAreRejectedByValidation() {
        val r = parser.parse("delete ../../system/build.prop")
        assertIs<ParsedCommand.Tool>(r)
        val v = validator.validate(r.request)
        assertIs<ValidationResult.Invalid>(v)
        assertTrue(v.errors.any { "'..'" in it })
    }

    @Test fun fullControlOfZedsOwnData() {
        assertEquals("todo.delete" to mapOf("item" to "eggs"), tool("delete eggs from my shopping list"))
        assertEquals("todo.delete" to mapOf("item" to "done"), tool("clear completed tasks"))
        assertEquals("todo.delete" to mapOf("item" to "all"), tool("clear my task list"))
        assertEquals("reminder.delete" to mapOf("item" to "rent"), tool("delete the reminder about rent"))
        assertEquals("reminder.delete" to mapOf("item" to "all"), tool("delete all my reminders"))
        assertEquals("reminder.cancel" to mapOf("id" to "2"), tool("cancel reminder 2"))
        assertEquals("calendar.delete" to mapOf("query" to "dentist appointment"), tool("delete my dentist appointment"))
        assertEquals("calendar.delete" to mapOf("query" to "meeting with Ana", "date" to "tomorrow"), tool("cancel the meeting with Ana tomorrow"))
        assertEquals("calendar.delete" to mapOf("query" to "lunch"), tool("remove lunch from my calendar"))
        assertEquals("docs.remove" to mapOf("query" to "all"), tool("clear my knowledge"))
        assertEquals("docs.remove" to mapOf("query" to "notes.txt"), tool("remove notes.txt from the index"))
        assertEquals("memory.forget" to mapOf("query" to "everything"), tool("forget everything"))
        assertEquals("memory.forget" to mapOf("query" to "everything"), tool("delete all my memories"))
        assertEquals("file.delete", tool("delete notes.txt").first, "files still delete as before")
    }

    @Test fun settingsAndScreensByVoice() {
        assertEquals("settings.set" to mapOf("key" to "voice", "value" to "Atlas"), tool("change your voice to Atlas"))
        assertEquals("settings.set" to mapOf("key" to "voice", "value" to "Atlas"), tool("use the Atlas voice"))
        assertEquals("settings.set" to mapOf("key" to "wake_word", "value" to "off"), tool("turn off the wake word"))
        assertEquals("settings.set" to mapOf("key" to "web_research", "value" to "on"), tool("enable web research"))
        assertEquals("settings.set" to mapOf("key" to "wake_sensitivity", "value" to "high"), tool("set wake word sensitivity to high"))
        assertEquals("settings.set" to mapOf("key" to "briefing_time", "value" to "07:30"), tool("set my briefing to 7:30 am"))
        assertEquals("settings.set" to mapOf("key" to "user_title", "value" to "boss"), tool("call me boss"))
        assertEquals("settings.set" to mapOf("key" to "speech_rate", "value" to "slower"), tool("talk slower"))
        assertEquals("home.control", tool("turn on the bedroom lights").first, "devices are still smart home")
        assertEquals("app.show" to mapOf("screen" to "reminders"), tool("open my reminders"))
        assertEquals("app.show" to mapOf("screen" to "settings"), tool("open your settings"))
        assertEquals("app.open", tool("open settings").first, "plain settings is the phone's Settings app")
        assertEquals("app.open", tool("open spotify").first)
        assertEquals(ParsedCommand.NotACommand, parser.parse("call me back later"))
    }

    @Test fun teachingByVoice() {
        assertEquals("teach.pronounce" to mapOf("word" to "Cebu", "say" to "seh-boo"), tool("pronounce Cebu as seh-boo"))
        assertEquals("teach.pronounce" to mapOf("word" to "Siargao", "say" to "shar-gow"), tool("Siargao is pronounced shar-gow"))
        assertEquals("teach.rule" to mapOf("rule" to "Keep your answers short."), tool("from now on, keep your answers short"))
        assertEquals("teach.rule" to mapOf("rule" to "Stop calling me sir."), tool("stop calling me sir"))
        assertEquals("teach.rule" to mapOf("rule" to "Don't use emojis."), tool("don't use emojis"))
        assertEquals("teach.alias" to mapOf("phrase" to "lights out", "meaning" to "turn off the lights"), tool("when I say lights out, I mean turn off the lights"))
        assertEquals("teach.list", tool("what have you learned from me").first)
        assertEquals("teach.forget" to mapOf("what" to "Cebu"), tool("forget the pronunciation of Cebu"))
        // Ordinary requests are not lessons.
        assertEquals("todo.add", tool("don't forget to buy batteries").first)
        assertEquals("media.control", tool("stop the music").first)
    }

    @Test fun undoRepliesAndScreen() {
        assertEquals("app.undo", tool("undo").first)
        assertEquals("app.undo", tool("undo that").first)
        assertEquals("app.undo", tool("bring it back").first)
        assertEquals("message.reply" to mapOf("to" to "Ana", "message" to "on my way"), tool("reply to Ana: on my way"))
        assertEquals("message.reply" to mapOf("to" to "Ana", "message" to "I'm coming", "app" to "WhatsApp"), tool("tell Ana on WhatsApp I'm coming"))
        assertEquals("message.reply" to mapOf("to" to "Mark", "message" to "see you at 5", "app" to "messenger"), tool("messenger Mark saying see you at 5"))
        assertEquals("messages.read" to mapOf("from" to "Ana"), tool("what did Ana say"))
        assertEquals("messages.read" to mapOf("app" to "WhatsApp"), tool("read my WhatsApp messages"))
        assertEquals("sms.compose", tool("text Mom saying I'm home").first, "plain texts stay SMS")
        assertEquals("screen.read", tool("what's on my screen").first)
        assertEquals("screen.read", tool("summarize this article").first)
        assertEquals("screen.tap" to mapOf("target" to "Send"), tool("tap Send"))
        assertEquals("screen.tap" to mapOf("target" to "like"), tool("click the like button"))
        assertEquals("screen.action" to mapOf("action" to "back"), tool("go back"))
        assertEquals("screen.action" to mapOf("action" to "back"), tool("press the back button"))
        assertEquals("screen.action" to mapOf("action" to "lock"), tool("lock my phone"))
        assertEquals("screen.scroll" to mapOf("direction" to "down"), tool("scroll down"))
        assertEquals("screen.type" to mapOf("text" to "pizza near me", "field" to "search"), tool("type pizza near me in the search box"))
        assertEquals("media.control", tool("pause the music").first)
        assertEquals("notifications.recent", tool("show my notifications").first)
        assertEquals("home.secure", tool("lock the front door").first)
    }

    @Test fun snoozeAndEarphones() {
        assertEquals("reminder.snooze" to mapOf("minutes" to "10"), tool("snooze"))
        assertEquals("reminder.snooze" to mapOf("minutes" to "15"), tool("snooze for 15 minutes"))
        assertEquals("reminder.snooze" to mapOf("minutes" to "5"), tool("remind me again in 5 minutes"))
        assertEquals("settings.set" to mapOf("key" to "headset_audio", "value" to "phone speaker"), tool("talk through the phone speaker"))
        assertEquals("settings.set" to mapOf("key" to "headset_audio", "value" to "auto"), tool("talk through my earphones"))
        assertEquals("reminder.create", tool("remind me to stretch in 10 minutes").first, "new reminders still work")
    }
}
