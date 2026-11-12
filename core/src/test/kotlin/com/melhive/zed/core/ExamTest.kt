package com.melhive.zed.core

import com.melhive.zed.core.command.CommandParser
import com.melhive.zed.core.command.ParsedCommand
import com.melhive.zed.core.security.ToolCallExtractor
import com.melhive.zed.core.skills.SkillRegistry
import com.melhive.zed.core.tools.ToolCatalog
import com.melhive.zed.core.tools.ToolRequest
import com.melhive.zed.core.understanding.Computations
import com.melhive.zed.core.understanding.DialogueState
import com.melhive.zed.core.understanding.DialogueTracker
import com.melhive.zed.core.understanding.MemoryFact
import com.melhive.zed.core.understanding.MemoryRanker
import com.melhive.zed.core.understanding.QuickReplies
import com.melhive.zed.core.understanding.Resolution
import com.melhive.zed.core.understanding.TimeHints
import com.melhive.zed.core.understanding.ToolRepair
import com.melhive.zed.core.understanding.ToolSelector
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The exam: real requests and what ZED must do with them, with the clock fixed
 * at Thursday 12 November 2026, 3:00 pm. Every "Report" file that shows a
 * mistake becomes a new line here, so a fixed mistake stays fixed.
 *
 * Each section collects all failures before failing, so one run shows every
 * wrong answer at once.
 */
class ExamTest {
    private val now = LocalDateTime.of(2026, 11, 12, 15, 0)
    private val parser = CommandParser { now }
    private val tracker = DialogueTracker { now }
    private val repair = ToolRepair(ToolCatalog.DEFAULT) { now }

    private fun describe(r: ToolRequest) = (listOf(r.tool) + r.args.toSortedMap().map { (k, v) -> "$k=$v" }).joinToString(" ")

    private fun describe(c: ParsedCommand): String = when (c) {
        is ParsedCommand.Tool -> describe(c.request)
        is ParsedCommand.Answer -> "answer:" + c.text
        is ParsedCommand.Clarify -> "clarify"
        ParsedCommand.Help -> "help"
        ParsedCommand.NotACommand -> "none"
    }

    private fun check(section: String, cases: List<Pair<String, String>>, actual: (String) -> String) {
        val wrong = cases.mapNotNull { (input, expected) ->
            val got = runCatching { actual(input) }.getOrElse { "error: $it" }
            val ok = if (expected.startsWith("answer~")) got.startsWith("answer:") && got.contains(expected.removePrefix("answer~"), ignoreCase = true) else got == expected
            if (ok) null else "  “$input”\n    expected: $expected\n    got:      $got"
        }
        assertTrue(wrong.isEmpty(), "$section — ${wrong.size} of ${cases.size} wrong:\n" + wrong.joinToString("\n"))
    }

    // ------------------------------------------------------------ commands

    @Test fun commands() = check("Commands", listOf(
        "set an alarm for 6:30 am" to "alarm.set hour=6 minute=30",
        "wake me up at 7" to "alarm.set hour=7 label=Wake up minute=0",
        "set an alarm for 6" to "alarm.set hour=6 minute=0",
        "set a timer for 10 minutes" to "timer.set seconds=600",
        "5 minute timer" to "timer.set seconds=300",
        "set a timer for 1 hour 30 minutes" to "timer.set seconds=5400",
        "please set a timer for 90 seconds" to "timer.set seconds=90",
        "start a timer for half an hour" to "timer.set seconds=1800",
        "remind me to call Ana at 5pm" to "reminder.create at=2026-11-12T17:00 text=call Ana",
        "remind me in 20 minutes to stretch" to "reminder.create at=2026-11-12T15:20 text=stretch",
        "remind me tomorrow at 9 to pay rent" to "reminder.create at=2026-11-13T09:00 text=pay rent",
        "remind me to take out the trash tonight at 8" to "reminder.create at=2026-11-12T20:00 text=take out the trash",
        "remind me to buy milk" to "clarify",
        "remind me on friday at 10am to call the bank" to "reminder.create at=2026-11-13T10:00 text=call the bank",
        "remind me about the dentist on december 3 at 2pm" to "reminder.create at=2026-12-03T14:00 text=the dentist",
        "remind me at noon to eat" to "reminder.create at=2026-11-13T12:00 text=eat",
        "remind me in an hour to check the oven" to "reminder.create at=2026-11-12T16:00 text=check the oven",
        "remind me next monday at 8 to submit the report" to "reminder.create at=2026-11-16T08:00 text=submit the report",
        "remind me friday to call the bank" to "reminder.create at=2026-11-13T09:00 text=call the bank",
        "list my reminders" to "reminder.list",
        "cancel reminder 3" to "reminder.cancel id=3",
        "delete reminder 4" to "reminder.cancel id=4",
        "what time is it" to "time.now",
        "hey zed what time is it" to "time.now",
        "what's the date today" to "time.now",
        "how long until my meeting" to "time.left target=meeting",
        "add buy milk to my task list" to "todo.add title=buy milk",
        "add task call plumber" to "todo.add title=call plumber",
        "list my tasks" to "todo.list",
        "complete buy milk" to "todo.complete item=buy milk",
        "remember that my sister's name is Ana" to "memory.remember fact=my sister's name is Ana",
        "my name is rommel" to "memory.remember fact=My name is Rommel",
        "what do you remember" to "memory.list",
        "forget my sister" to "memory.forget query=my sister",
        "turn on the flashlight" to "flashlight.set on=true",
        "torch off" to "flashlight.set on=false",
        "turn off do not disturb" to "dnd.set on=false",
        "turn on the living room lights" to "home.control action=turn_on target=living room lights",
        "set the bedroom lights to 40%" to "home.control action=brightness target=bedroom lights value=40",
        "lock the front door" to "home.secure action=lock target=front door",
        "pause the music" to "media.control action=pause",
        "next song" to "media.control action=next",
        "play some jazz" to "music.play query=some jazz",
        "turn the volume up" to "volume.set action=up",
        "set volume to 50" to "volume.set action=set level=50",
        "mute" to "volume.set action=mute",
        "what did i miss" to "notifications.recent",
        "battery level" to "device.status",
        "open wifi settings" to "settings.panel panel=wifi",
        "take a selfie" to "camera.open mode=photo",
        "navigate to SM Seaside" to "maps.navigate destination=SM Seaside",
        "call mom" to "phone.call who=mom",
        "text Ana saying I'm on my way" to "sms.compose message=I'm on my way to=Ana",
        "open spotify" to "app.open name=spotify",
        "what's the weather in cebu" to "weather.get place=cebu",
        "will it rain today" to "weather.get",
        "what's on my calendar tomorrow" to "calendar.today date=tomorrow",
        "brief me" to "workflow.run name=morning briefing",
        "run my focus routine" to "routine.run name=focus",
        "schedule meeting with Ana tomorrow at 3pm for 1 hour" to "calendar.create end=2026-11-13T16:00 start=2026-11-13T15:00 title=meeting with Ana",
        "search history for budget" to "history.search query=budget",
        "research best budget phones" to "web.research query=best budget phones",
        "create folder Projects" to "folder.create path=Projects",
        "what's 15% of 80" to "answer~12",
        "what's 12 times 7" to "answer~84",
        "how many days until christmas" to "answer~43 days",
        "what day is december 25" to "answer~Friday",
        "tell me a joke" to "none",
        "how are you" to "none",
        "what should I cook tonight" to "none",
        // Moving events — and the near-misses that must stay what they are.
        "move my meeting with Ana to 4pm" to "calendar.move at=2026-11-12T16:00 keep_day=true query=meeting with Ana",
        "reschedule the dentist to friday at 10am" to "calendar.move at=2026-11-13T10:00 query=dentist",
        "push my lunch to tomorrow" to "calendar.move at=2026-11-13T09:00 keep_time=true query=lunch",
        "move notes.txt to Archive" to "file.move destination=Archive source=notes.txt",
        "move Projects to Archive" to "file.move destination=Archive source=Projects",
        "change the thermostat to 22" to "home.control action=temperature target=thermostat value=22",
        "change the volume to 50" to "volume.set action=set level=50",
    )) { describe(parser.parse(it)) }

    // ------------------------------------------------------- tricky dates

    @Test fun trickyDates() = check("Dates and times", listOf(
        "tomorrow at 5" to "2026-11-13T17:00",
        "at 7" to "2026-11-12T19:00",
        "at 10" to "2026-11-12T22:00",
        "at 2" to "2026-11-13T14:00",
        "in 20 minutes" to "2026-11-12T15:20",
        "in 2 hours" to "2026-11-12T17:00",
        "tonight at 8" to "2026-11-12T20:00",
        "friday at 9am" to "2026-11-13T09:00",
        "next tuesday" to "2026-11-17T09:00",
        "on monday at 6:30 pm" to "2026-11-16T18:30",
        "december 3 at 2pm" to "2026-12-03T14:00",
        "the 3rd of december" to "2026-12-03T09:00",
        "tomorrow morning" to "2026-11-13T09:00",
        "tomorrow evening at 7" to "2026-11-13T19:00",
        "at noon tomorrow" to "2026-11-13T12:00",
        "5pm tomorrow" to "2026-11-13T17:00",
        "at 17:30" to "2026-11-12T17:30",
        "at 9:15" to "2026-11-12T21:15",
        "day after tomorrow at 10am" to "2026-11-14T10:00",
        "today at 4" to "2026-11-12T16:00",
        "on 2026-12-01 at 10:00" to "2026-12-01T10:00",
        "tomorrow" to "2026-11-13T09:00",
        "at 5 in the morning" to "2026-11-13T05:00",
        "midnight" to "2026-11-13T00:00",
        "call my mom" to "none",
        "I have 5 apples" to "none",
        "set volume to 5" to "none",
    )) { TimeHints.moment(it, now)?.toString() ?: "none" }

    @Test fun durations() = check("Durations", listOf(
        "set a timer for 10 minutes" to "600",
        "a 90 second timer" to "90",
        "pasta timer 1 hour 15 minutes" to "4500",
        "timer for ten minutes" to "600",
        "how are you" to "none",
    )) { TimeHints.duration(it)?.toString() ?: "none" }

    // ------------------------------------------------- follow-ups and corrections

    private val reminderAt5 = DialogueState(
        person = "Ana", topic = "call Ana",
        lastTimed = ToolRequest("reminder.create", mapOf("text" to "call Ana", "at" to "2026-11-12T17:00")), reminderId = 7,
    )

    private fun resolution(r: Resolution): String = when (r) {
        is Resolution.Text -> "text:" + r.text
        is Resolution.Several -> "several:" + r.parts.joinToString(" | ")
        is Resolution.Act -> "act:" + describe(r.request)
        is Resolution.Explain -> "explain"
    }

    @Test fun followUps() {
        val ana = DialogueState(person = "Ana")
        val dentist = DialogueState(topic = "call the dentist")
        val milk = DialogueState(topic = "buy milk")
        val noId = reminderAt5.copy(reminderId = null)
        val alarm = DialogueState(lastTimed = ToolRequest("alarm.set", mapOf("hour" to "6", "minute" to "30")))
        val timer = DialogueState(lastTimed = ToolRequest("timer.set", mapOf("seconds" to "600")))
        val meeting = DialogueState(lastTimed = ToolRequest("calendar.create", mapOf("title" to "Lunch", "start" to "2026-11-13T12:00")))
        val facts = listOf("My sister's name is Ana", "Dr. Cruz is my dentist", "My boss is Mark Reyes")
        val cases: List<Triple<String, DialogueState, String>> = listOf(
            Triple("call her", ana, "text:call Ana"),
            Triple("text her saying hi", ana, "text:text Ana saying hi"),
            Triple("remind me to call her at 5", ana, "text:remind me to call Ana at 5"),
            Triple("what is she doing", ana, "text:what is she doing"),
            Triple("call my sister", DialogueState(), "text:call Ana"),
            Triple("call my dentist", DialogueState(), "text:call Dr. Cruz"),
            Triple("text my boss that I'm late", DialogueState(), "text:text Mark Reyes that I'm late"),
            Triple("call my cousin", DialogueState(), "text:call my cousin"),
            Triple("remind me about it tomorrow", dentist, "text:remind me to call the dentist tomorrow"),
            Triple("add it to my task list", milk, "text:add buy milk to my task list"),
            Triple("move it to 6", reminderAt5, "act:reminder.reschedule at=2026-11-12T18:00 id=7"),
            Triple("no, I meant Friday", reminderAt5, "act:reminder.reschedule at=2026-11-13T17:00 id=7"),
            Triple("make it tomorrow at 9am", reminderAt5, "act:reminder.reschedule at=2026-11-13T09:00 id=7"),
            Triple("actually 7:30", reminderAt5, "act:reminder.reschedule at=2026-11-12T19:30 id=7"),
            Triple("push the reminder to tomorrow", reminderAt5, "act:reminder.reschedule at=2026-11-13T17:00 id=7"),
            Triple("6 pm instead", reminderAt5, "act:reminder.reschedule at=2026-11-12T18:00 id=7"),
            Triple("move it to 8", noId, "act:reminder.create at=2026-11-12T20:00 text=call Ana"),
            Triple("make it 7", alarm, "act:alarm.set hour=7 minute=0"),
            Triple("change it to 6:45", alarm, "act:alarm.set hour=6 minute=45"),
            Triple("make it 15 minutes", timer, "act:timer.set seconds=900"),
            Triple("move it to 4", meeting, "act:calendar.move at=2026-11-13T16:00 from=2026-11-13T12:00 query=Lunch"),
            Triple("I meant 6", DialogueState(), "text:I meant 6"),
        )
        val wrong = cases.mapNotNull { (input, state, expected) ->
            val got = resolution(tracker.resolve(input, state, facts))
            if (got == expected) null else "  “$input”\n    expected: $expected\n    got:      $got"
        }
        assertTrue(wrong.isEmpty(), "Follow-ups — ${wrong.size} of ${cases.size} wrong:\n" + wrong.joinToString("\n"))
    }

    @Test fun choosingTheEventToMove() {
        val m = com.melhive.zed.core.understanding.CalendarMove
        val lunch = m.Event(1, "Lunch with Ana", now.plusDays(1).withHour(12), now.plusDays(1).withHour(13))
        val lunch2 = m.Event(2, "Lunch with Ana", now.plusDays(8).withHour(12), now.plusDays(8).withHour(13))
        val standup = m.Event(3, "Team standup", now.plusHours(2), now.plusHours(2).plusMinutes(15), recurring = true)
        val events = listOf(lunch2, standup, lunch)
        assertEquals(1L, m.pick(events, "lunch with Ana", null, now)?.id, "the next one")
        assertEquals(2L, m.pick(events, "lunch", lunch2.start, now)?.id, "the one the conversation was about")
        assertEquals(3L, m.pick(events, "my meeting", null, now)?.id, "“my meeting” means the next event")
        assertEquals(null, m.pick(events, "dentist", null, now))
        assertEquals(lunch.start.toLocalDate().atTime(16, 0), m.newStart(lunch, now.withHour(16), keepDay = true, keepTime = false))
        assertEquals(now.plusDays(3).toLocalDate().atTime(12, 0), m.newStart(lunch, now.plusDays(3).withHour(9), keepDay = false, keepTime = true))
    }

    @Test fun conversationState() {
        assertEquals("Mark", DialogueState().heard("I had lunch with Mark today").person)
        assertEquals("Ana", DialogueState().heard("Remind me to call Ana").person)
        assertEquals(null, DialogueState().heard("What time is it on Friday").person)
        assertEquals("Ana", DialogueState().after(ToolRequest("phone.call", mapOf("who" to "Ana")), "Calling Ana").person)
        val s = DialogueState().after(ToolRequest("reminder.create", mapOf("text" to "call Ana", "at" to "2026-11-12T17:00")), "Reminder #12 set for Thu 5:00 PM: “call Ana”.")
        assertEquals(12L, s.reminderId); assertEquals("call Ana", s.topic); assertEquals("Ana", s.person)
        // The tracker's own follow-up then moves that same reminder.
        assertEquals("act:reminder.reschedule at=2026-11-12T18:00 id=12", resolution(tracker.resolve("move it to 6", s)))
    }

    @Test fun severalRequestsInOneSentence() = check("Several requests", listOf(
        "turn on the flashlight and set a timer for 5 minutes" to "several:turn on the flashlight | set a timer for 5 minutes",
        "set an alarm for 6 am and remind me to call mom at 5pm" to "several:set an alarm for 6 am | remind me to call mom at 5pm",
        "pause the music, and turn off the lights" to "several:pause the music | turn off the lights",
        "what time is it and what's the weather" to "several:what time is it | what's the weather",
        "turn on the lights then set an alarm for 7am" to "several:turn on the lights | set an alarm for 7am",
        "remind me to buy milk and eggs tomorrow" to "text:remind me to buy milk and eggs tomorrow",
        "call mom and dad" to "text:call mom and dad",
        "rock and roll" to "text:rock and roll",
        "add bread and butter to my task list" to "text:add bread and butter to my task list",
    )) { resolution(tracker.resolve(it, DialogueState())) }

    // ------------------------------------------------------- tool repair

    private fun call(json: String) = ToolCallExtractor.extract("```zed-tool\n$json\n```", ToolRequest.Origin.LOCAL_MODEL).requests.single()

    @Test fun toolRepair() {
        val cases = listOf(
            Triple("""{"tool":"set_alarm","args":{"time":"6:30"}}""", "wake me at 6:30", "alarm.set hour=6 minute=30"),
            Triple("""{"tool":"alarm.set","args":{"hour":"7am"}}""", "set an alarm", "alarm.set hour=7 minute=0"),
            Triple("""{"tool":"reminder.create","args":{"message":"call Ana","time":"2026-11-12 17:00"}}""", "remind me to call Ana at 5", "reminder.create at=2026-11-12T17:00 text=call Ana"),
            Triple("""{"tool":"reminder.create","args":{"text":"call Ana","at":"2026-11-12T05:00"}}""", "remind me to call ana at 5pm", "reminder.create at=2026-11-12T17:00 text=call Ana"),
            Triple("""{"tool":"reminder.create","args":{"text":"run","at":"tomorrow at 9"}}""", "remind me tomorrow morning to run", "reminder.create at=2026-11-13T09:00 text=run"),
            Triple("""{"tool":"reminder.create","args":{"text":"leave","at":"2026-11-12T16:30"}}""", "remind me 30 minutes before my 5pm meeting", "reminder.create at=2026-11-12T16:30 text=leave"),
            Triple("""{"tool":"create_reminder","args":{"text":"stretch","at":"2026-11-12T15:20:00"}}""", "remind me in 20 minutes to stretch", "reminder.create at=2026-11-12T15:20 text=stretch"),
            Triple("""{"tool":"Reminder.Create","args":{"text":"x","when":"2026-11-13T10:00"}}""", "ok", "reminder.create at=2026-11-13T10:00 text=x"),
            Triple("""{"tool":"timer","args":{"duration":"10 minutes"}}""", "set a timer for 10 minutes", "timer.set seconds=600"),
            Triple("""{"tool":"timer.set","args":{"seconds":"300"}}""", "timer for ten minutes", "timer.set seconds=600"),
            Triple("""{"tool":"timer.set","args":{"minutes":"3"}}""", "start a timer", "timer.set seconds=180"),
            Triple("""{"tool":"send_sms","args":{"recipient":"Ana","body":"hi"}}""", "text ana hi", "sms.compose message=hi to=Ana"),
            Triple("""{"tool":"flashlight","args":{"state":"on"}}""", "light please", "flashlight.set on=true"),
            Triple("""{"tool":"calendar.create","args":{"title":"Lunch","start":"2026-11-13 12:00"}}""", "lunch with mark tomorrow at noon", "calendar.create start=2026-11-13T12:00 title=Lunch"),
            Triple("""{"tool":"calendar.create","args":{"title":"Lunch","start":"2026-11-13T13:00","end":"2026-11-13T14:00"}}""", "lunch tomorrow at noon", "calendar.create end=2026-11-13T13:00 start=2026-11-13T12:00 title=Lunch"),
            Triple("""{"tool":"memory.remember","args":{"fact":"likes tea","importance":"high"}}""", "I like tea", "memory.remember fact=likes tea"),
            Triple("""{"tool":"add_task","args":{"task":"buy milk"}}""", "add buy milk", "todo.add title=buy milk"),
            Triple("""{"tool":"fly.drone","args":{}}""", "fly", "fly.drone"),
            Triple("""{"tool":"call","args":{"name":"Mom"}}""", "call mom", "phone.call who=Mom"),
            Triple("""{"tool":"weather","args":{"city":"Cebu"}}""", "weather in cebu", "weather.get place=Cebu"),
            Triple("""{"tool":"open_app","args":{"app":"Spotify"}}""", "open spotify", "app.open name=Spotify"),
            Triple("""{"tool":"navigate","args":{"to":"the airport"}}""", "take me to the airport", "maps.navigate destination=the airport"),
        )
        val wrong = cases.mapNotNull { (json, said, expected) ->
            val got = describe(repair.repair(call(json), said).request)
            if (got == expected) null else "  $json (“$said”)\n    expected: $expected\n    got:      $got"
        }
        assertTrue(wrong.isEmpty(), "Tool repair — ${wrong.size} of ${cases.size} wrong:\n" + wrong.joinToString("\n"))
        assertEquals("2026-11-12T17:00", repair.isoDateTime("2026-11-12T17:00:00", now))
    }

    @Test fun nearMissToolCallsAreUnderstood() {
        fun ex(s: String) = ToolCallExtractor.extract(s, ToolRequest.Origin.LOCAL_MODEL)
        assertEquals("time.now", ex("Sure.\n```json\n{\"tool\":\"time.now\"}\n```").requests.single().tool)
        assertEquals(ToolRequest("flashlight.set", mapOf("on" to "true"), ToolRequest.Origin.LOCAL_MODEL), ex("{\"tool\": \"flashlight.set\", \"args\": {\"on\": true}}").requests.single())
        val tc = ex("```tool_code\n{\"name\":\"alarm.set\",\"arguments\":{\"hour\":6,\"minute\":0}}\n```")
        assertEquals("alarm.set hour=6 minute=0", describe(tc.requests.single()))
        assertEquals("", tc.visibleText)
        assertTrue(ex("```json\n{\"city\":\"Cebu\"}\n```").requests.isEmpty(), "plain JSON that isn't a tool call stays text")
    }

    // ------------------------------------------------- tool selection

    @Test fun toolSelection() {
        val all = SkillRegistry.DEFAULT.enabledTools(SkillRegistry.DEFAULT.skills.map { it.id }.toSet())
        fun names(t: String, ctx: String? = null) = ToolSelector.select(t, all, context = ctx).map { it.name }
        assertEquals("reminder.create", names("remind me to call Ana at 5").first())
        assertTrue(names("remind me to call Ana at 5").size <= 5)
        assertTrue(names("tell me a joke").isEmpty())
        assertTrue(names("what is the meaning of life").isEmpty())
        assertEquals("weather.get", names("what's the weather like").first())
        assertEquals("weather.get", names("and tomorrow?", "what's the weather today").first())
        assertTrue("home.control" in names("turn on the bedroom lights"))
        assertTrue("sms.compose" in names("text Ana that I'm late"))
        assertTrue("calendar.today" in names("what do I have tomorrow"))
        assertTrue("timer.set" in names("start a countdown for the pasta"))
        assertTrue("reminder.list" in names("cancel my dentist reminder"), "cancelling needs the list to find the id")
    }

    // ----------------------------------------------- recall, brief, quick replies

    @Test fun relevantRecall() {
        val facts = listOf(
            MemoryFact(1, "My sister's name is Ana"), MemoryFact(2, "Ana is allergic to peanuts"), MemoryFact(3, "I work at Accenture"),
            MemoryFact(4, "My favourite colour is blue"), MemoryFact(5, "Dr. Cruz is my dentist in Cebu"), MemoryFact(6, "Ana's birthday is March 3"),
        )
        val sister = MemoryRanker.rank("what should I cook for my sister", facts).map { it.fact.id }
        assertEquals(1L, sister.first())
        assertTrue(2L in sister && 6L in sister, "linked through Ana: $sister")
        assertTrue(3L !in sister && 4L !in sister, "unrelated memories stay out: $sister")
        assertEquals(5L, MemoryRanker.rank("when is my dentist appointment", facts).first().fact.id)
        assertEquals(2L, MemoryRanker.rank("can Ana eat peanut butter", facts).first().fact.id)
        assertTrue(MemoryRanker.rank("hello there", facts).isEmpty())
        // A short follow-up borrows the previous message's subject.
        assertEquals(5L, MemoryRanker.rank("and where is he", facts, previous = "who is my dentist").first().fact.id)
    }

    @Test fun computedNotGuessed() {
        val today = LocalDate.of(2026, 11, 12)
        assertTrue(Computations.find("if I spend 15% of 2400 on food", today).any { it.contains("= 360") })
        assertTrue(Computations.find("split 1250 / 4 between us", today).any { it == "1250 / 4 = 312.5" })
        assertTrue(Computations.find("the meeting is on 2026-12-01", today).isEmpty())
        assertTrue(Computations.find("how are you", today).isEmpty())
    }

    @Test fun quickReplies() {
        assertEquals("You're welcome!", QuickReplies.reply("thanks!", null))
        assertEquals("Okay.", QuickReplies.reply("ok", null))
        assertEquals("You're Rommel.", QuickReplies.reply("what's my name?", "Rommel"))
        assertEquals(null, QuickReplies.reply("okay set an alarm for 6", null))
        assertEquals(null, QuickReplies.reply("thanks, now call mom", null))
    }

    @Test fun briefIsShortAndComplete() {
        val brief = com.melhive.zed.core.understanding.Awareness(
            now = now.atZone(java.time.ZoneId.of("Asia/Manila")), place = "Manila", batteryPercent = 54, charging = true, online = true,
            nextEvent = "Dentist" to now.plusMinutes(80).atZone(java.time.ZoneId.of("Asia/Manila")).toInstant().toEpochMilli(),
            dialogue = "the person just mentioned is Ana", profile = listOf("name Rommel"), memories = listOf("My sister's name is Ana"),
            computed = listOf("15% of 2400 = 360"), tools = ToolSelector.select("remind me", ToolCatalog.DEFAULT.all),
        ).render()
        for (part in listOf("Thursday 12 November 2026, 3:00 PM", "in Manila", "battery 54% charging", "“Dentist” at 4:20 PM (in 1 h 20 min)", "Ana", "name Rommel", "= 360", "reminder.create(text, at)")) {
            assertTrue(brief.contains(part), "missing “$part” in:\n$brief")
        }
        assertTrue(brief.length < 900, "brief too long (${brief.length})")
    }
}
