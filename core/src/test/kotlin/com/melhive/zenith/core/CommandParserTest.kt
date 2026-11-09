package com.melhive.zenith.core

import com.melhive.zenith.core.command.CommandParser
import com.melhive.zenith.core.command.ParsedCommand
import com.melhive.zenith.core.tools.ToolCatalog
import com.melhive.zenith.core.tools.ToolValidator
import com.melhive.zenith.core.tools.ValidationResult
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
        listOf("what time is it?", "What's the date", "Zenith, what day is it", "time").forEach {
            assertEquals("time.now", tool(it).first, it)
        }
    }

    @Test fun fileCommands() {
        assertEquals("file.create" to mapOf("path" to "notes/ideas.txt", "content" to "Buy a telescope"),
            tool("create file notes/ideas.txt with content Buy a telescope"))
        assertEquals("file.create" to mapOf("path" to "todo.md"), tool("Please create a new file called \"todo.md\""))
        assertEquals("folder.create" to mapOf("path" to "Projects/Zenith"), tool("make a folder named Projects/Zenith"))
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
        assertEquals("workflow.run" to mapOf("name" to "morning briefing"), tool("good morning"))
        assertEquals("workflow.run" to mapOf("name" to "organize downloads"), tool("run workflow organize downloads"))
        assertEquals("web.research" to mapOf("query" to "best telescopes 2026"), tool("research best telescopes 2026"))
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
}
