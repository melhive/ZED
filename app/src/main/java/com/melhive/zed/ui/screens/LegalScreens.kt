package com.melhive.zed.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.melhive.zed.ui.components.SectionTitle
import com.melhive.zed.ui.components.ZedCard
import com.melhive.zed.ui.theme.ZedColors

/** A legal document: title, last update, and sections of plain-language text. */
private data class LegalDoc(val title: String, val updated: String, val intro: String, val sections: List<Pair<String, List<String>>>)

private const val PROJECT_URL = "https://github.com/melhive/ZED"

private val TERMS = LegalDoc(
    title = "Terms of Use",
    updated = "November 15, 2026",
    intro = "These terms apply to the ZED app, a MelHive Project. By installing or using ZED you agree to them. If you don't agree, please uninstall the app.",
    sections = listOf(
        "1. What ZED is" to listOf(
            "ZED is a personal assistant app for Android. It can answer questions, control features of your phone, manage reminders, files, routines and smart-home devices, talk with you by voice, and, in Eye mode and LIVE, look through the camera when you ask it to.",
            "ZED is provided free of charge, as is, for personal use.",
        ),
        "2. Your responsibilities" to listOf(
            "You are responsible for how you use ZED and for the actions you ask it to take, for example calls, messages, deleting files, changing settings or controlling devices.",
            "By default ZED carries out requests immediately without asking for confirmation. You can change this in Settings → Privacy and lock screen → Ask before actions. Check that ZED understood you before relying on an action.",
            "Use ZED lawfully. Don't use it to harass anyone, to access accounts or devices you're not allowed to use, or to break the terms of services you connect to it.",
            "Don't rely on ZED while driving or in situations where a distraction or a wrong answer could cause harm.",
        ),
        "3. AI answers can be wrong" to listOf(
            "ZED's answers come from an AI model running on your phone (Gemma) and from built-in rules. AI can make mistakes, misunderstand speech or give outdated information.",
            "What ZED says it sees through the camera can be wrong too: it may misidentify an object, misread text or miss details.",
            "ZED is not a substitute for professional medical, legal, financial or safety advice. Always verify important information.",
            "Emergency calls should be made with your phone's dialer, not through ZED.",
        ),
        "4. Privacy guards are not locks" to listOf(
            "Guest mode on a locked phone, Only my voice, recognising people and Private Connections are there to keep casual access away from your information. They are not security guarantees.",
            "Voice and face recognition can be fooled, for example by a recording or a photo, and can also mistake you for someone else. Private Connections and guest mode rely on your phone's own screen lock. Protect your phone with a PIN, pattern or fingerprint.",
        ),
        "5. Other people" to listOf(
            "If you turn on recognising people, ZED stores a description of the faces of people who tell it their name. Use it only with people who know about it and agree, and remove anyone who asks (More → Settings → Who ZED knows).",
            "Don't use the camera or microphone features to record or identify people without their knowledge where that is not allowed.",
        ),
        "6. Your data and backups" to listOf(
            "Everything ZED knows is stored on your phone. Uninstalling ZED deletes it. Keeping a backup is up to you (Settings → Backup).",
            "Private Connections notes and the faces and voices ZED has learned are not included in backups. They are lost if you uninstall ZED or move to another phone.",
        ),
        "7. Third-party services" to listOf(
            "Some features use services from other companies, under their own terms: web search (DuckDuckGo, Bing, Wikipedia) when a question needs it, your phone's speech service (often Google's) when you use it instead of on-device speech recognition, Open-Meteo weather, Hugging Face and GitHub for downloads, and your own Home Assistant server.",
        ),
        "8. Models and voices" to listOf(
            "Downloadable AI models, voices, speech recognition and voice recognition models are made by their respective authors and are provided under their own licenses (shown in the app next to each download, for example Apache-2.0, MIT, CC-BY-4.0 or the LFM Open License).",
        ),
        "9. No warranty and limitation of liability" to listOf(
            "ZED is provided \"as is\" and \"as available\", without warranties of any kind. To the extent permitted by law, the MelHive Project is not liable for any loss or damage arising from use of the app, including lost data, missed reminders, unintended actions, wrong answers, access by other people, charges from third-party services or device issues.",
        ),
        "10. Changes" to listOf(
            "ZED is updated regularly. These terms may change with an update; the date above shows the latest version. Continuing to use ZED after an update means you accept the updated terms.",
        ),
        "11. Contact" to listOf("Questions or problems: open an issue at $PROJECT_URL."),
    ),
)

private val PRIVACY = LegalDoc(
    title = "Privacy Policy",
    updated = "November 15, 2026",
    intro = "ZED is built to keep your information on your phone. The MelHive Project does not run servers for ZED, does not create accounts, and does not collect analytics, advertising IDs or usage data. This policy explains what stays on your phone and what is sent elsewhere, which happens only when you use a feature that needs it.",
    sections = listOf(
        "Stored only on your phone" to listOf(
            "Conversations (typed chats and the text of voice calls in LIVE and Eye mode, with a short summary of longer calls), memories, your task list, reminders, routines, settings, the document index and the lessons you teach ZED (answering rules, pronunciations and shortcuts) are stored in ZED's private storage on your phone.",
            "Android app backups are turned off for ZED. ZED's own backup feature saves a file only where you choose.",
            "Your Home Assistant token and your Private Connections notes are encrypted with a key held in the Android Keystore.",
            "Uninstalling ZED deletes all of this. You can also clear memories and conversations in the app.",
        ),
        "Camera, faces and voices" to listOf(
            "Eye mode: while Eye mode is on screen, the front camera is used to find where your face is, so the eyes can follow you, and to notice something you hold up. This happens on the phone and each frame is thrown away at once. The camera is off whenever Eye mode is closed.",
            "What is this: when you ask ZED what something is, in Eye mode or LIVE, it takes one picture through the camera you chose (front or back) and gives it to the AI model on your phone for that answer. The picture is not saved. In LIVE the camera opens only for that moment, unless recognising people is on.",
            "Follow-up questions about what ZED saw (for example how much it costs) send only the name of the thing it identified to the web search service, never the picture.",
            "Recognising people (off unless you turn it on): ZED stores a numeric description of your face and of the faces of people who tell it their name, together with those names, in a private file on this phone. With it on, the front camera stays on while Eye mode or LIVE is open. Nothing is uploaded, it is not included in backups, and you can rename or delete anyone, or everyone, in More → Settings → Who ZED knows.",
            "Your voice: when you record your voice in Who ZED knows, ZED keeps numeric voiceprints of it on this phone. The recordings themselves are not kept. With Only my voice on, each thing ZED hears is compared with those voiceprints on the phone to decide between full access and guest answers.",
            "Photos you send in a chat, or take with your camera app from ZED, are looked at by the model on your phone and are not uploaded.",
        ),
        "Locked phone and private notes" to listOf(
            "Guest mode: while your phone is locked, ZED answers general questions and does a few harmless things (timers, alarms, flashlight, volume). It does not read or change your memories, messages, contacts, calendar, reminders, files, notifications, history or settings, and none of that is given to the AI model, until you unlock. Conversations held on a locked phone are not used for learning.",
            "Private Connections: notes you add to the private part of the Live Brain are stored encrypted on this phone and shown only after you unlock them with your fingerprint or the phone's PIN. Android checks the fingerprint or PIN; ZED never sees it. Private notes are never sent anywhere, never given to the AI model and not included in backups.",
        ),
        "Problem reports" to listOf(
            "When ZED stops unexpectedly it writes a report on the phone: the error, your phone model and Android version, a short trail of what ZED was doing (which screen was open, the state of the voice session, the names of actions that ran) and how Android says its processes stopped. The trail does not contain what you said or what ZED answered.",
            "These reports, and the ones you make with Report a problem, are saved in ZED's private storage and as text files in your Download/ZED folder, where other apps you have given file access could read them. They are shared only if you send them yourself.",
        ),
        "When something leaves your phone" to listOf(
            "No online AI: your messages, memories, documents and pictures are answered by the model on your phone and are never sent to an AI service.",
            "Web search: when a question needs current information, only the search question is sent to the search service (DuckDuckGo, Bing or Wikipedia).",
            "Speech recognition: with on-device speech recognition installed, your speech is turned into text on the phone. Otherwise voice input uses your phone's speech service (often Google's), which may process audio on the device or online depending on your phone.",
            "Weather: your city name or approximate location is sent to Open-Meteo to get the forecast.",
            "Smart home: commands and device lists travel between ZED and your own Home Assistant server.",
            "Downloads and updates: ZED contacts GitHub to check for updates and downloads models, voices, speech recognition and voice recognition from Hugging Face and GitHub. These services see your IP address like any website.",
        ),
        "Permissions and what they're used for" to listOf(
            "Microphone: while ZED LIVE or Eye mode is open, while you press the mic button, while you record your voice in Who ZED knows, and, if you turn it on, to listen for “Hey Zed”. Wake-word listening happens entirely on the phone and shows a notification while active.",
            "Camera: only as described under Camera, faces and voices above.",
            "Contacts: to find the number when you ask to call or text someone.",
            "Phone and SMS: to place calls and send texts you ask for.",
            "Calendar: to add events you ask for and read your schedule for the briefing and Schedule page.",
            "Notifications access (optional): to answer “what did I miss?” and to reply in chat apps for you. Notifications are kept in memory only, read only when you ask, and never saved.",
            "Accessibility (optional): to read or act on the screen when you ask (“what's on my screen”, “tap Send”). Nothing from the screen is stored.",
            "Location (optional): city-level, for the weather and “where am I”.",
            "Bluetooth (optional): to start a routine when a chosen device, such as your car, connects.",
            "Fingerprint or PIN: asked for by Android when you open Private Connections or confirm that a voice is yours. ZED only learns whether it succeeded.",
            "Vibration: a short pulse with the LIVE sounds.",
        ),
        "Children" to listOf("ZED is not directed at children under 13."),
        "Changes" to listOf("This policy may change with app updates; the date above shows the latest version."),
        "Contact" to listOf("Questions: open an issue at $PROJECT_URL."),
    ),
)

@Composable
private fun LegalScreen(doc: LegalDoc, onBack: () -> Unit) {
    ListScreen(doc.title, onBack = onBack, subtitle = "ZED · MelHive Project · Updated ${doc.updated}") {
        item { ZedCard { Text(doc.intro, style = MaterialTheme.typography.bodyMedium) } }
        doc.sections.forEach { (heading, paragraphs) ->
            item(key = heading) {
                SectionTitle(heading)
                ZedCard {
                    paragraphs.forEachIndexed { i, p ->
                        Text(p, style = MaterialTheme.typography.bodySmall, color = ZedColors.Text, modifier = Modifier.padding(top = if (i == 0) 0.dp else 8.dp))
                    }
                }
            }
        }
    }
}

@Composable fun TermsScreen(onBack: () -> Unit) = LegalScreen(TERMS, onBack)
@Composable fun PrivacyScreen(onBack: () -> Unit) = LegalScreen(PRIVACY, onBack)
