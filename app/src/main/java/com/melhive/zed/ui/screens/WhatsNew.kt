package com.melhive.zed.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.zed.BuildConfig
import com.melhive.zed.android.UpdateState
import com.melhive.zed.ui.components.StatusPill
import com.melhive.zed.ui.components.ZedCard
import com.melhive.zed.ui.navigation.rememberContainer
import com.melhive.zed.ui.theme.ZedColors

/** One released version and what it brought. */
data class Release(val version: String, val date: String, val title: String, val items: List<String>)

/** Release history shown in More → What's new and after each update. Newest first. */
object Changelog {
    val releases = listOf(
        Release("0.1.29", "2026-11-12", "Gemma only, steadier, and a keener “Hey Zed”", listOf(
            "“Hey Zed” is much more sensitive: it wakes on “Zed” alone or in “Hey Zed”, even said quietly or from across the room. The new Maximum setting is the default (More → Voice → Sensitivity); words that sound alike, like “zero” or “Jed”, can wake it now and then — pick High for fewer false alarms.",
            "Gemma only: online AI (Gemini, OpenAI, Claude) and real-time Gemini voice are gone. Everything you say is answered by the model on your phone, and API keys saved by older versions are deleted. Web research still works — only the search question leaves the phone.",
            "Move calendar events: “move my meeting with Ana to 4”, “reschedule the dentist to Friday at 10”, or right after adding one, “move it to 5”. Its length stays the same; repeating events are left for your calendar app.",
            "Easier on the battery: housekeeping (“dreaming”) runs at most every few hours, leave-now hints no longer wake the phone every 15 minutes, and Home's animations draw fewer frames. More → Permissions & Diagnostics → Background jobs shows what is scheduled and when.",
            "Gemma isn't preloaded when the battery is below 15% and the phone isn't charging.",
        )),
        Release("0.1.28", "2026-11-12", "Deeper intelligence", listOf(
            "Relationships in the Live Brain: ZED reads who and what connects — “Ana → sister”, “Dr. Cruz → dentist → Cebu” — and draws them as named connections. Tap a person to see how they relate to you.",
            "Dreaming: while the phone charges, ZED tidies its memory — duplicates are merged and relationships are read out (Gemma helps when it's allowed to run).",
            "Insights, kept rare: “You've mentioned the dentist 3 times lately — want a reminder?” or “You turn on Do Not Disturb around 10 PM most days — make it a routine?” Shown once on Home; wave them away with Not now.",
            "Leave-now hints: before a calendar event that has a place, ZED tells you when it's time to get going. At most one hint every three hours (Settings → Intelligence → Proactive hints).",
            "Learning from “that's wrong”: say it (with the right answer, or ZED will ask), or tap Wrong under a reply. The correction is remembered and used the next time the topic comes up.",
            "Voice control of the brain: “show me everything about Ana” opens the Live Brain and flies straight there.",
        )),
        Release("0.1.27", "2026-11-12", "The classic orb is back, improved", listOf(
            "The arc-reactor orb is back in place of the plasma orb, with improvements.",
            "While you speak, rings travel inward with your voice. While ZED speaks, a ring of fine ticks follows its actual voice (on-device voices; phone voices pulse word by word).",
            "While thinking, faint synapse sparks cross the core, and the orb draws fewer frames so Gemma keeps the chip. When something is done, a check mark appears.",
            "The glow is a little softer, and Light mode (Settings → Intelligence → Animation) turns it off and slows the orb further.",
        )),
        Release("0.1.26", "2026-11-12", "The plasma orb and a new Home", listOf(
            "A new plasma orb: a soft, living surface. Your voice energises it and sends ripples inward; when ZED speaks with an on-device voice, the surface takes its shape from the actual sound. Older phones keep the classic orb.",
            "Clearer states: listening, thinking (small synapse flashes cross the orb), acting (it says what — “Setting alarm…”), speaking, done and error.",
            "Lighter while Gemma works: the orb draws fewer frames and the rings hold still while ZED is thinking, leaving the chip to the model.",
            "Floating assistant: the screen edges glow while ZED listens, live captions show what it hears and says, chips confirm what it did (“Alarm 6:00 ✓”), and weather, schedule, tasks and web answers appear as cards. Drag it around; fling it away to close.",
            "Home console: rings for battery, memory, network and Gemma, and a live countdown to your next event, reminder or timer.",
            "Motion: panels scan in with a light sweep, numbers count up, points of light drift behind Home and lean as you tilt the phone, and taps give a light vibration. Settings → Intelligence → Animation turns it down.",
        )),
        Release("0.1.25", "2026-11-12", "Learning and the internet", listOf(
            "Web research without any key: ZED searches the web (DuckDuckGo, with Bing and Wikipedia as backups), reads the best pages and Gemma answers with numbered sources listed underneath. Only the search question leaves the phone — never your memories or conversations.",
            "ZED researches on its own when a question needs fresh facts (news, prices, results, opening hours). Say “research …” to ask directly.",
            "Offline? Requests that need the internet (research, weather) are kept. As soon as you're back online ZED looks them up, adds the answer to the conversation and notifies you. Home shows what's waiting.",
            "Automatic learning: after conversations ZED picks up lasting facts you mentioned — people, preferences, allergies, work, home city. It runs only when ZED is idle and the phone is charging (or Gemma is already loaded), and stops the moment you speak.",
            "Honest learning: Home shows “I learned 3 new things” — mark each Right, Fix it or Forget it. Until you confirm, ZED treats learned facts as less certain than what you told it. Memory marks them “learned”.",
            "Think twice on hard questions (planning, comparing, deciding, multi-step maths): ZED plans, answers, then checks itself. Everyday questions are never slowed down.",
            "Settings → Intelligence: switches for learning, web research and think twice, and an Animation level (Full, Balanced, Light) to save battery and leave more of the chip to Gemma.",
        )),
        Release("0.1.24", "2026-11-12", "ZED understands you better", listOf(
            "Follows the conversation: “call her”, “text him that I'm late”, “remind me about it tomorrow”. “Call my sister” works when ZED knows who your sister is.",
            "Corrections and changes: after setting a reminder, say “move it to 6”, “no, I meant Friday” or “make it tomorrow at 9” — it moves the same reminder. Works for alarms and timers too (“make it 15 minutes”).",
            "Several requests in one sentence: “turn on the flashlight and set a timer for 5 minutes” does both.",
            "Before every reply ZED now tells Gemma the moment it's in: time, place, battery, your next event, running timers and reminders, who you were just talking about, and a short profile of you.",
            "Relevant memories: ZED picks the memories connected to your question (through the names and ideas they share, like the Live Brain), not just the newest ones.",
            "More reliable actions: Gemma sees only the few tools that fit your request, and its tool calls are checked and repaired — a wrong name, a wrong date format or a mistaken time is fixed, and the time you actually said always wins.",
            "Sums, percentages and dates in your message are computed exactly and handed to Gemma, instead of Gemma doing the maths.",
            "Faster: Gemma starts loading when ZED opens, when you say “Hey Zed” and when the floating assistant appears. “Thanks”, “ok”, “bye” and “what's my name” are answered instantly. Spoken answers are kept short, and LIVE gives a soft tick the moment it has your request.",
        )),
        Release("0.1.23", "2026-11-12", "Live Brain, balanced", listOf(
            "Neurons are bigger again — between the two earlier looks — so the brain is easy to see and tap without turning into a wash of light.",
            "Connections are clearly visible, and more signals travel along them at once, with a longer glowing trail and a spark where they land.",
            "Areas, concepts and well-used memories have a soft glow; a memory ZED just used still flashes brightest.",
        )),
        Release("0.1.22", "2026-11-12", "A calmer Live Brain", listOf(
            "Live Brain redesigned as points of light: neurons are much smaller and softer, with a clear size order — the core, then the areas, then concepts, then memories by how strong they are.",
            "Glow now means something: only the areas, strong memories and whatever you select glow. A memory ZED just used still flashes, so it stands out.",
            "Zooming in spreads the brain apart instead of inflating the dots. Connections and travelling signals are thinner and quieter.",
            "The mini brain on Home, in LIVE and in the assistant got the same calmer look.",
        )),
        Release("0.1.21", "2026-11-12", "Live Brain 2.0, speed test, report a reply", listOf(
            "Live Brain is now a 3D hologram: it turns slowly, near neurons are big and bright while far ones are small and dim, connections curve and taper, and signals leave a trail and spark where they land. Drag to turn it, pinch to fly in.",
            "It boots up when you open it — the core ignites, the areas unfold, the memories link — with a readout of neurons, synapses and activity.",
            "It's really live: when ZED uses a memory to answer you, a signal travels from the core and that memory flashes. When ZED saves something new, a neuron grows and connects.",
            "Memory strength: memories ZED uses often and recently glow brighter and sit near the core; ones it never touches fade outward (nothing is deleted). Tap a memory to see how often it has been used.",
            "Time-lapse (the clock button in the brain): watch the brain grow from the first memory to today.",
            "A mini brain on Home, in LIVE and in the floating assistant, flashing when ZED remembers. Soft synapse ticks and a light vibration when you touch the brain (Settings → Appearance to turn off).",
            "Speed test (More → Permissions & Diagnostics): measures the wait before the first word and the words per second on your phone. The first result becomes the baseline every later version is compared with.",
            "Report on any reply in Chat: saves what you asked, what ZED did, what it answered and how long each step took to Downloads/ZED reports. Nothing is sent anywhere — share the file when something goes wrong.",
            "Replies now show how long they took.",
            "Safety copies: before an update changes how your data is stored, ZED keeps a copy of it as it was (Backup & restore → Safety copies).",
            "Weekly automatic backup to a folder you choose (Backup & restore). Keeps the newest four.",
        )),
        Release("0.1.20", "2026-11-11", "Live Brain, backup & restore, machine sounds", listOf(
            "Live Brain (Memory → Live Brain, or More): everything ZED knows as a glowing, moving network. Areas like People, Work and Health orbit the core, every memory, task, reminder, document and conversation hangs off its area, and shared ideas (a name, a place) link them together. Signals run along the connections and new memories pop in as you save them.",
            "In the brain: pinch to zoom, drag to move, tap anything to read it and see what it's connected to, search, and filter by type. Pin or forget a memory right there.",
            "Backup & restore (More → Backup & restore): save your memories, tasks, reminders, conversations, routines and every setting to one file — and optionally your AI models, offline voices and wake word, so a new phone needs no re-downloading.",
            "API keys travel in the backup only if you set a password; they're encrypted with it.",
            "Restore checks the whole file before changing anything, then restarts ZED with everything in place. A damaged file or a wrong password leaves ZED untouched.",
            "LIVE sounds rebuilt to sound like a machine, not a ghost: Arc is now a motor spin-up, a lock click and two crisp beeps. Pulse and Chime are replaced by Servo, Droid, Data, Retro and Airlock — all tuned to be loud and clear on a phone speaker (More → Voice → LIVE sounds, tap to hear).",
        )),
        Release("0.1.19", "2026-11-11", "Ask with a photo, fixed", listOf(
            "Photos from the camera and the gallery now load. Every photo used to fail with “Couldn't open the photo” before ZED could look at it.",
            "Phone photos come out upright, large photos are shrunk before they're sent, and HEIC photos are supported.",
            "Clear messages when a file isn't a photo or ZED wasn't allowed to read it.",
        )),
        Release("0.1.18", "2026-11-11", "Photos work on-device", listOf(
            "Ask with a photo now works with Gemma 4: the Gemma 4 E2B · GPU file ZED downloaded before was a text-only build. Model Manager shows “Update — adds photo support” on it; one tap downloads the new GPU build (2.3 GB), which can see photos and replies just as fast.",
            "ZED checks the model file itself for photo support, so it never pretends a model can see.",
            "Models that can look at photos are marked “Sees photos” in Model Manager.",
        )),
        Release("0.1.17", "2026-11-11", "Clear voice, sharper wake word, ask with a photo", listOf(
            "Supertonic voices (like Atlas) now read times, dates, temperatures and units clearly: ZED spells them out first. Tested with a speech recognizer, “1:05 PM” and “8 AM” are heard correctly every time (they used to come out as “1.5pm” or “am”).",
            "The briefing and routines talk like a person: “Good afternoon, sir. It's Wednesday, November 11th, 1:05 PM. Right now it's 29 degrees with light drizzle, but it feels like 35… You have one event today: Clients meeting at 8 AM.” No more symbols, brackets or lists read aloud.",
            "Wake word: say “Zed” anywhere — “Zed”, “Hey Zed”, “Wake up Zed”, “Okay Zed”. A built-in volume booster helps it hear you when you speak softly, from farther away or in wind. Sensitivity now defaults to Most sensitive (tested: about three times as many wake-ups, with no false alarms on sound-alike phrases).",
            "Ask with a photo: tap the camera in chat, LIVE or the floating assistant, then Take a photo or Choose from gallery — ZED tells you what it is.",
            "Gemma 4 E2B and E4B look at photos right on your phone; no internet or API key needed.",
        )),
        Release("0.1.16", "2026-11-11", "Smooth orb, new logo, exact countdowns", listOf(
            "ZED everywhere: new app ID (com.melhive.zed), so this version installs as a new app — uninstall the old one first, then set up and re-download your model and voices.",
            "New ZED logo everywhere: launcher, round and themed icons, the widget, notifications, the About footer and what security scanners show.",
            "ZED says its name clearly as “Zed” (rhymes with “bed”) in every voice and in Gemini Live — never spelled out as Z-E-D.",
            "The orb animates continuously — no more little jump every few seconds.",
            "Cleaner panels: borderless dark glass with a soft accent glow, and no more decorative lines on panels, headings, titles, the background or the tab bar.",
            "“How much time until 9 AM?” is now calculated exactly by ZED (e.g. “9:00 AM is 4 minutes away”) instead of being guessed by the AI; time zones are no longer read out.",
            "The green “done” orb no longer shows a check mark.",
            "The project lives at github.com/melhive/ZED; downloads are zed.apk.",
            "Gemma 4 E2B · GPU now starts on phones like yours: ZED declares the phone's OpenCL driver (Android 12+ requires it). If a GPU still can't start, ZED runs the model on the CPU instead of failing.",
            "Terms of Use and Privacy Policy, linked at the bottom of More and Settings.",
        )),
        Release("0.1.15", "2026-11-11", "Meet ZED", listOf(
            "Meet ZED — a short name speech recognition hears reliably. Say “Hey Zed” (wake word tested against “he said”, “Hey Ted”, “Hey Ed”… with no false alarms).",
            "A new futuristic look: HUD panels with chamfered corners, a faint grid, glowing headings and a cleaner, grouped More menu.",
            "The orb is now centered in LIVE and bigger in the floating assistant, with an arc-reactor core, orbiting sparks and HUD rings.",
            "Exact time with seconds, and “how much time is left?” for timers, alarms, reminders and your next event — down to the second.",
            "Times like 3:45 PM are now spoken as “P M”, never as the word “am”.",
            "Faster on-device answers: ZED keeps the model's memory of the conversation between messages, skips Gemma's hidden thinking pass, uses only your fast CPU cores and turns on speculative decoding when the model supports it.",
            "13 on-device models with clear descriptions, quality and speed ratings — and a recommendation based on your phone's chip, memory and storage.",
            "The “Allow everything” box left Home; in Settings it hides itself once everything is allowed, or with Hide.",
        )),
        Release("0.1.14", "2026-11-11", "Just do it — and a floating assistant", listOf(
            "ZED no longer asks “OK?” before acting: everything you say runs immediately (Settings → Ask before actions to change it).",
            "Calendar events are added directly to your calendar with a 10-minute reminder — and ZED tells you exactly what it saved.",
            "New Schedule page (More → Schedule) with your next 14 days of events and reminders.",
            "Texts send right away once “Send texts” is allowed; “organize my downloads” no longer waits for approval.",
            "One-tap “Allow everything” for permissions, so nothing pops up mid-command.",
            "The assistant gesture, “Hey Zed” and the Quick Settings tile now open a floating orb with a Jarvis-style HUD over the app you're in — not the whole app.",
        )),
        Release("0.1.13", "2026-11-10", "A real assistant: control your phone, routines and real-time voice", listOf(
            "Real-time voice with Gemini Live: talk naturally, get instant spoken replies and interrupt any time (Voice settings, needs a Gemini key).",
            "Phone control by voice or chat: call (always confirmed) and text contacts, open apps, play and pause music, volume, flashlight, Do Not Disturb, settings panels, navigation, camera and phone status.",
            "“What did I miss?” reads your recent notifications when you ask (opt-in notification access).",
            "Daily briefing: weather, today's calendar, reminders, tasks and missed notifications — say “brief me” or get it every morning.",
            "Routines: Good morning, Good night, Focus and Driving, plus your own — run by voice, at a time, or when your car's Bluetooth connects.",
            "Weather (“what's the weather in Cebu”) and agenda (“what's on tomorrow”) commands.",
            "“Hey Zed” wake word (opt-in), “What am I looking at?” with the camera, and Home Assistant smart-home control.",
            "New LIVE sounds (Arc, Pulse, Chime) and the recognizer's Google beep is hidden.",
            "Smooth sliding screen transitions instead of fades.",
            "This What's new page, shown after every update.",
        )),
        Release("0.1.12", "2026-11-10", "Open ZED from anywhere, with personality", listOf(
            "Set ZED as the phone's default assistant: hold power or home to open ZED LIVE.",
            "Quick Settings tile and a home-screen widget (talk, type, next reminder).",
            "Personalities: Companion, Butler (Jarvis-style), Professional and Playful — and choose how ZED addresses you.",
            "Online AI (Gemini, OpenAI, Claude) now uses native tool calling and can chain several actions in one request.",
        )),
        Release("0.1.11", "2026-11-10", "Neural voice fixes", listOf(
            "Fixed: previewing a neural voice closed the app.",
            "Neural voices run in their own process, so a voice problem can never close ZED.",
            "ZED tells you when your phone renders a voice slower than it speaks.",
        )),
        Release("0.1.10", "2026-11-10", "Natural offline voices", listOf(
            "Download realistic voices that run on the phone: Supertonic 3 (10 voices) and Kokoro (28 voices).",
            "Choose any voice, speaking speed and pitch in More → Voice.",
        )),
        Release("0.1.9", "2026-11-09", "Louder, clearer LIVE", listOf("ZED speaks at normal media volume; no call mode during LIVE.")),
        Release("0.1.8", "2026-11-09", "LIVE reliability", listOf("LIVE no longer pauses itself when it opens.")),
        Release("0.1.7", "2026-11-09", "More human conversation", listOf(
            "LIVE fallbacks (system voice input, typing) and problem reports.",
            "Fixed overlapping buttons; friendlier greetings; more natural replies.",
        )),
        Release("0.1.6", "2026-11-09", "On-device AI fixes", listOf(
            "Fixed “context window is full” errors with on-device models.",
            "Keyboard gap fixed; redesigned Home.",
        )),
        Release("0.1.5", "2026-11-09", "Conversation", listOf(
            "Auto mode picks the best available AI: online, on-device, then native.",
            "Natural conversation, small talk without a model, crash reports.",
        )),
        Release("0.1.4", "2026-11-09", "First release", listOf(
            "Chat and ZED LIVE voice mode, reminders, alarms, timers, calendar, files, tasks and memory.",
            "On-device AI (Gemma, Qwen) and online providers; background tasks; signed releases with update check.",
        )),
    )

    fun parse(v: String?): List<Int> = v.orEmpty().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    fun newer(a: String, b: String?): Boolean {
        val x = parse(a); val y = parse(b)
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }

    /** Releases the user hasn't seen yet (between [lastSeen] and the installed version). */
    fun unseen(lastSeen: String?, installed: String = BuildConfig.VERSION_NAME): List<Release> {
        val list = releases.filter { newer(it.version, lastSeen) && !newer(it.version, installed) }
        return list.ifEmpty { releases.take(1) }.take(3)
    }
}

@Composable
private fun ReleaseCard(r: Release, installed: Boolean) {
    ZedCard(accent = if (installed) ZedColors.Cyan else ZedColors.Outline) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Version ${r.version}", style = MaterialTheme.typography.titleMedium)
                Text("${r.date} · ${r.title}", style = MaterialTheme.typography.labelMedium, color = ZedColors.Cyan)
            }
            if (installed) StatusPill("Installed", ZedColors.Green)
        }
        r.items.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
    }
}

/** More → What's new: the update notice plus every version's changes. */
@Composable
fun WhatsNewScreen(onBack: () -> Unit) {
    val c = rememberContainer()
    val update by c.updates.state.collectAsStateWithLifecycle()
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    ListScreen("What's new", onBack = onBack, subtitle = "Installed: ZED ${BuildConfig.VERSION_NAME}") {
        (update as? UpdateState.Available)?.let { u ->
            item {
                ZedCard(accent = ZedColors.Green) {
                    Text("ZED ${u.info.versionName} is available", style = MaterialTheme.typography.titleMedium)
                    Text("Install it over this version — your data is kept.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                    Button(onClick = { uri.openUri(u.info.apkUrl) }, modifier = Modifier.padding(top = 6.dp)) { Text("Download update") }
                }
            }
        }
        items(Changelog.releases, key = { it.version }) { r -> ReleaseCard(r, r.version == BuildConfig.VERSION_NAME) }
    }
}

/** Shown once after an update: what changed since the version the user last saw. */
@Composable
fun WhatsNewDialog(lastSeen: String?, onSeeAll: () -> Unit, onDismiss: () -> Unit) {
    val unseen = Changelog.unseen(lastSeen)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What's new in ZED ${BuildConfig.VERSION_NAME}") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                unseen.forEach { r ->
                    Text(if (unseen.size > 1) "${r.version} — ${r.title}" else r.title, style = MaterialTheme.typography.titleSmall, color = ZedColors.Cyan, modifier = Modifier.padding(top = 8.dp))
                    r.items.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        dismissButton = { TextButton(onClick = onSeeAll) { Text("All versions") } },
    )
}

/** Persistent notice while a newer version is available (More tab). */
@Composable
fun UpdateBanner(onOpen: () -> Unit) {
    val c = rememberContainer()
    val update by c.updates.state.collectAsStateWithLifecycle()
    val u = update as? UpdateState.Available ?: return
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    ZedCard(onClick = onOpen, accent = ZedColors.Green) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Update available: ZED ${u.info.versionName}", style = MaterialTheme.typography.titleMedium)
                Text("Tap to see what's new", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
            }
            TextButton(onClick = { uri.openUri(u.info.apkUrl) }) { Text("Download") }
        }
    }
}
