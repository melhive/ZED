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
    updated = "November 13, 2026",
    intro = "These terms apply to the ZED app, a MelHive Project. By installing or using ZED you agree to them. If you don't agree, please uninstall the app.",
    sections = listOf(
        "1. What ZED is" to listOf(
            "ZED is a personal assistant app for Android. It can answer questions, control features of your phone, manage reminders, files, routines and smart-home devices, and talk with you by voice.",
            "ZED is provided free of charge, as is, for personal use.",
        ),
        "2. Your responsibilities" to listOf(
            "You are responsible for how you use ZED and for the actions you ask it to take — for example calls, messages, deleting files, changing settings or controlling devices.",
            "By default ZED carries out requests immediately without asking for confirmation. You can change this in Settings → Ask before actions. Check that ZED understood you before relying on an action.",
            "Use ZED lawfully. Don't use it to harass anyone, to access accounts or devices you're not allowed to use, or to break the terms of services you connect to it.",
            "Don't rely on ZED while driving or in situations where a distraction or a wrong answer could cause harm.",
        ),
        "3. AI answers can be wrong" to listOf(
            "ZED's answers come from an AI model running on your phone (Gemma) and from built-in rules. AI can make mistakes, misunderstand speech or give outdated information.",
            "ZED is not a substitute for professional medical, legal, financial or safety advice. Always verify important information.",
            "Emergency calls should be made with your phone's dialer, not through ZED.",
        ),
        "4. Third-party services" to listOf(
            "Some features use services from other companies, under their own terms: web search (DuckDuckGo, Bing, Wikipedia) when you ask ZED to look something up, Google's speech services, Open-Meteo weather, Hugging Face and GitHub for downloads, and your own Home Assistant server.",
        ),
        "5. Models and voices" to listOf(
            "Downloadable AI models and voices are made by their respective authors and are provided under their own licenses (shown in the app next to each download, e.g. Apache-2.0, MIT or the LFM Open License).",
        ),
        "6. No warranty and limitation of liability" to listOf(
            "ZED is provided \"as is\" and \"as available\", without warranties of any kind. To the extent permitted by law, the MelHive Project is not liable for any loss or damage arising from use of the app, including lost data, missed reminders, unintended actions, charges from third-party services or device issues.",
        ),
        "7. Changes" to listOf(
            "ZED is updated regularly. These terms may change with an update; the date above shows the latest version. Continuing to use ZED after an update means you accept the updated terms.",
        ),
        "8. Contact" to listOf("Questions or problems: open an issue at $PROJECT_URL."),
    ),
)

private val PRIVACY = LegalDoc(
    title = "Privacy Policy",
    updated = "November 13, 2026",
    intro = "ZED is built to keep your information on your phone. The MelHive Project does not run servers for ZED, does not create accounts, and does not collect analytics, advertising IDs or usage data. This policy explains what stays on your phone and what is sent elsewhere — only when you use features that need it.",
    sections = listOf(
        "Stored only on your phone" to listOf(
            "Conversations, memories, your task list, reminders, routines, settings, the document index and the lessons you teach ZED (answering rules, pronunciations and shortcuts) are stored in ZED's private storage on your phone. Android app backups are turned off for ZED; ZED's own backup feature saves a file only where you choose.",
            "Your Home Assistant token is encrypted with the Android Keystore.",
            "Problem reports (crash details) are saved on the phone and are only shared if you tap Share report yourself.",
            "Uninstalling ZED deletes all of this. You can also clear memories and conversations in the app.",
        ),
        "When something leaves your phone" to listOf(
            "No online AI: your messages, memories, documents and photos are answered by the model on your phone and are never sent to an AI service. Web research sends only the search question to the search service.",
            "Speech recognition: standard voice input uses your phone's speech service (often Google's), which may process audio on the device or online depending on your phone.",
            "Weather: your city name or approximate location is sent to Open-Meteo to get the forecast.",
            "Smart home: commands and device lists travel between ZED and your own Home Assistant server.",
            "Downloads and updates: ZED contacts GitHub to check for updates and downloads models and voices from Hugging Face and GitHub. These services see your IP address like any website.",
        ),
        "Permissions and what they're used for" to listOf(
            "Microphone: only while ZED LIVE is open, while you press the mic button, or — if you turn it on — to listen for “Hey Zed” on the phone. Wake-word listening happens entirely on the phone and shows a notification while active.",
            "Contacts: to find the number when you ask to call or text someone.",
            "Phone and SMS: to place calls and send texts you ask for.",
            "Calendar: to add events you ask for and read your schedule for the briefing and Schedule page.",
            "Notifications access (optional): to answer “what did I miss?”. Notifications are kept in memory only, read only when you ask, and never saved.",
            "Location (optional): city-level, only for weather.",
            "Bluetooth (optional): to start a routine when a chosen device, such as your car, connects.",
            "Camera: ZED opens your camera app only when you ask “what am I looking at?”; the photo is looked at by the model on your phone.",
            "Recognising people (an experiment you switch on in Eye mode): ZED stores a description of your face and voice, and of the faces and names of people who tell it their name, in a private file on this phone. It is never uploaded and not included in backups, and you can delete anyone, or everyone, from Eye mode's people list.",
            "Eye mode: while Eye mode is on screen, the front camera is used to find where your face is so the eyes can follow you, and to notice something you hold up. This happens on the phone and the frames are thrown away at once. When you ask “what is this?”, one picture is given to the AI model you chose (on the phone, or your online provider if you set one up) for that one answer; ZED does not save the picture. The camera is off whenever Eye mode is closed.",
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
