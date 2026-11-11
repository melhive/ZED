package com.melhive.zenith.ui.screens

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
import com.melhive.zenith.BuildConfig
import com.melhive.zenith.android.UpdateState
import com.melhive.zenith.ui.components.StatusPill
import com.melhive.zenith.ui.components.ZenithCard
import com.melhive.zenith.ui.navigation.rememberContainer
import com.melhive.zenith.ui.theme.ZenithColors

/** One released version and what it brought. */
data class Release(val version: String, val date: String, val title: String, val items: List<String>)

/** Release history shown in More → What's new and after each update. Newest first. */
object Changelog {
    val releases = listOf(
        Release("0.1.15", "2026-11-11", "Meet ZED", listOf(
            "ZENITH is now ZED — a short name speech recognition hears reliably. Say “Hey Zed” (wake word tested against “he said”, “Hey Ted”, “Hey Ed”… with no false alarms).",
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
    ZenithCard(accent = if (installed) ZenithColors.Cyan else ZenithColors.Outline) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Version ${r.version}", style = MaterialTheme.typography.titleMedium)
                Text("${r.date} · ${r.title}", style = MaterialTheme.typography.labelMedium, color = ZenithColors.Cyan)
            }
            if (installed) StatusPill("Installed", ZenithColors.Green)
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
                ZenithCard(accent = ZenithColors.Green) {
                    Text("ZED ${u.info.versionName} is available", style = MaterialTheme.typography.titleMedium)
                    Text("Install it over this version — your data is kept.", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
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
                    Text(if (unseen.size > 1) "${r.version} — ${r.title}" else r.title, style = MaterialTheme.typography.titleSmall, color = ZenithColors.Cyan, modifier = Modifier.padding(top = 8.dp))
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
    ZenithCard(onClick = onOpen, accent = ZenithColors.Green) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Update available: ZED ${u.info.versionName}", style = MaterialTheme.typography.titleMedium)
                Text("Tap to see what's new", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
            }
            TextButton(onClick = { uri.openUri(u.info.apkUrl) }) { Text("Download") }
        }
    }
}
