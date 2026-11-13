package com.melhive.zed.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.melhive.zed.core.skills.SkillRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Which intelligence layer answers free-form requests. Native commands always work.
 * ZED runs Gemma on the phone only: AUTO and LOCAL both use the on-device
 * model when one is installed (AUTO falls back to native commands without one).
 */
enum class IntelligenceMode { AUTO, NATIVE_ONLY, LOCAL }

enum class LocalBackend { CPU, GPU }

data class AppSettings(
    val mode: IntelligenceMode = IntelligenceMode.AUTO,
    val activeLocalModelId: String? = null,
    val localBackend: LocalBackend = LocalBackend.CPU,
    val voiceResponses: Boolean = true,
    val speechRate: Float = 1.0f,
    /** Voice pitch, 1.0 = natural. */
    val speechPitch: Float = 1.0f,
    /** Selected voice: see [com.melhive.zed.core.voice.VoiceSelection]. */
    val voiceId: String = "system",
    /** Personality preset name, see [com.melhive.zed.core.intelligence.Persona]. */
    val persona: String = "COMPANION",
    /** How ZED addresses the user ("sir", "boss", a nickname); blank = by name. */
    val userTitle: String = "",
    /** City for weather when location isn't allowed. */
    val weatherCity: String = "",
    val briefingEnabled: Boolean = false,
    val briefingTime: String = "07:00",
    /** User routines as JSON (null = presets). */
    val routinesJson: String? = null,
    /** Last version whose "What's new" the user has seen. */
    val lastSeenVersion: String? = null,
    /** LIVE start/stop sound: arc, pulse, chime or off. */
    val liveSound: String = "holo",
    /** Mute the speech recognizer's own start beep while LIVE listens. */
    val hideRecognizerBeep: Boolean = true,
    /** Home Assistant base URL, e.g. http://homeassistant.local:8123 */
    val homeAssistantUrl: String = "",
    /** "Hey Zed" wake word listening (foreground service). */
    val wakeWordEnabled: Boolean = false,
    /** Ask before actions: NEVER (default, just do it), RISKY or ALWAYS — see ConfirmMode. */
    val confirmMode: String = "NEVER",
    /** The user closed the "Allow everything" card in Settings. */
    val permissionsCardHidden: Boolean = false,
    /** 0 fewer false alarms … 3 maximum (default: wakes on “Zed” as readily as possible). */
    val wakeSensitivity: Int = 3,
    val confirmAllChanges: Boolean = false,
    val useKnowledge: Boolean = true,
    val autoIndex: Boolean = false,
    val enabledSkills: Set<String> = SkillRegistry.DEFAULT.skills.map { it.id }.toSet(),
    val bargeIn: Boolean = true,
    /** ZED speaks a greeting the moment it is called ("Hey Zed" or the assistant shortcut). */
    val greetOnCall: Boolean = true,
    /** "auto": on-device recognition when downloaded, else the phone's service; "android": always the phone's service. */
    val speechEngine: String = "auto",
    /** With earphones: "auto" (Android's routing) or "earphone_mic" (earphone mic, ZED talks from the phone speaker). */
    val headsetAudio: String = "auto",
    /** ZED appears and says each reminder when it's due. */
    val announceReminders: Boolean = true,
    /** Minutes before a calendar event that ZED announces it (0 = off). */
    val eventAnnounceMinutes: Int = 10,
    /** After an alarm is turned off, ZED appears with a good-morning briefing. */
    val alarmBriefing: Boolean = true,
    /** Taught pronunciations and shortcuts (JSON), kept here too so backups include them. */
    val taughtPronunciations: String = "{}",
    val taughtAliases: String = "{}",
    val glow: Boolean = true,
    val onboardingDone: Boolean = false,
    val checkUpdates: Boolean = true,
    /** On-device context window in tokens; 0 = choose automatically from device RAM. */
    val localContextTokens: Int = 0,
    /** Folder (tree URI) for the weekly automatic backup; blank = off. */
    val autoBackupFolder: String = "",
    /** What the last automatic backup did. */
    val autoBackupStatus: String = "",
    /** Synapse ticks and vibration when touching the Live Brain. */
    val brainSounds: Boolean = true,
    /** Eye mode shows what is being said as a line of text under the eyes. */
    val eyeCaptions: Boolean = false,
    /** Learn lasting facts from ordinary conversation (shown for review; always editable). */
    val autoLearning: Boolean = true,
    /** Search the web (no key needed) when a question needs fresh facts. Only the search question leaves the phone. */
    val webResearch: Boolean = true,
    /** Plan → answer → check on hard questions (slower, more careful). */
    val thinkTwice: Boolean = true,
    /** Rare, useful nudges: leave-now before events with a place, insights from dreaming. */
    val proactiveHints: Boolean = true,
    /** full | balanced | light — how much animation runs (performance mode). */
    val animationLevel: String = "full",
) {
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zed_settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val mode = stringPreferencesKey("mode")
        val localModel = stringPreferencesKey("local_model")
        val localBackend = stringPreferencesKey("local_backend")
        val voice = booleanPreferencesKey("voice_responses")
        val rate = floatPreferencesKey("speech_rate")
        val pitch = floatPreferencesKey("speech_pitch")
        val voiceId = stringPreferencesKey("voice_id")
        val persona = stringPreferencesKey("persona")
        val userTitle = stringPreferencesKey("user_title")
        val weatherCity = stringPreferencesKey("weather_city")
        val briefingEnabled = booleanPreferencesKey("briefing_enabled")
        val briefingTime = stringPreferencesKey("briefing_time")
        val routines = stringPreferencesKey("routines_json")
        val lastSeenVersion = stringPreferencesKey("last_seen_version")
        val liveSound = stringPreferencesKey("live_sound")
        val hideBeep = booleanPreferencesKey("hide_recognizer_beep")
        val haUrl = stringPreferencesKey("home_assistant_url")
        val wakeWord = booleanPreferencesKey("wake_word")
        val confirmMode = stringPreferencesKey("confirm_mode")
        val permsHidden = booleanPreferencesKey("permissions_card_hidden")
        val wakeSensitivity = androidx.datastore.preferences.core.intPreferencesKey("wake_sensitivity")
        val confirmAll = booleanPreferencesKey("confirm_all")
        val useKnowledge = booleanPreferencesKey("use_knowledge")
        val autoIndex = booleanPreferencesKey("auto_index")
        val skills = stringSetPreferencesKey("enabled_skills")
        val knownSkills = stringSetPreferencesKey("known_skills")
        val bargeIn = booleanPreferencesKey("barge_in")
        val greetOnCall = booleanPreferencesKey("greet_on_call")
        val speechEngine = stringPreferencesKey("speech_engine")
        val headsetAudio = stringPreferencesKey("headset_audio")
        val announceReminders = booleanPreferencesKey("announce_reminders")
        val eventAnnounceMinutes = androidx.datastore.preferences.core.intPreferencesKey("event_announce_minutes")
        val alarmBriefing = booleanPreferencesKey("alarm_briefing")
        val taughtPronunciations = stringPreferencesKey("taught_pronunciations")
        val taughtAliases = stringPreferencesKey("taught_aliases")
        val glow = booleanPreferencesKey("glow")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val checkUpdates = booleanPreferencesKey("check_updates")
        val contextTokens = androidx.datastore.preferences.core.intPreferencesKey("local_context_tokens")
        val autoBackupFolder = stringPreferencesKey("auto_backup_folder")
        val autoBackupStatus = stringPreferencesKey("auto_backup_status")
        val brainSounds = booleanPreferencesKey("brain_sounds")
        val eyeCaptions = booleanPreferencesKey("eye_captions")
        val autoLearning = booleanPreferencesKey("auto_learning")
        val webResearch = booleanPreferencesKey("web_research")
        val thinkTwice = booleanPreferencesKey("think_twice")
        val animationLevel = stringPreferencesKey("animation_level")
        val proactiveHints = booleanPreferencesKey("proactive_hints")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val defaults = AppSettings()
        AppSettings(
            mode = p[K.mode]?.let { runCatching { IntelligenceMode.valueOf(it) }.getOrNull() } ?: defaults.mode,
            activeLocalModelId = p[K.localModel],
            localBackend = p[K.localBackend]?.let { runCatching { LocalBackend.valueOf(it) }.getOrNull() } ?: defaults.localBackend,
            voiceResponses = p[K.voice] ?: defaults.voiceResponses,
            speechRate = p[K.rate] ?: defaults.speechRate,
            speechPitch = p[K.pitch] ?: defaults.speechPitch,
            voiceId = p[K.voiceId] ?: defaults.voiceId,
            persona = p[K.persona] ?: defaults.persona,
            userTitle = p[K.userTitle] ?: defaults.userTitle,
            weatherCity = p[K.weatherCity] ?: "",
            briefingEnabled = p[K.briefingEnabled] ?: false,
            briefingTime = p[K.briefingTime] ?: defaults.briefingTime,
            routinesJson = p[K.routines],
            lastSeenVersion = p[K.lastSeenVersion],
            liveSound = p[K.liveSound] ?: defaults.liveSound,
            hideRecognizerBeep = p[K.hideBeep] ?: true,
            homeAssistantUrl = p[K.haUrl] ?: "",
            wakeWordEnabled = p[K.wakeWord] ?: false,
            // Users who had "confirm every change" on keep being asked.
            permissionsCardHidden = p[K.permsHidden] ?: false,
            confirmMode = p[K.confirmMode] ?: if (p[K.confirmAll] == true) "ALWAYS" else "NEVER",
            wakeSensitivity = p[K.wakeSensitivity] ?: 3,
            confirmAllChanges = p[K.confirmAll] ?: defaults.confirmAllChanges,
            useKnowledge = p[K.useKnowledge] ?: defaults.useKnowledge,
            autoIndex = p[K.autoIndex] ?: defaults.autoIndex,
            // Skills added in later versions start enabled; skills the user turned off stay off.
            enabledSkills = (p[K.skills] ?: defaults.enabledSkills) + (defaults.enabledSkills - (p[K.knownSkills] ?: LEGACY_SKILLS)),
            bargeIn = p[K.bargeIn] ?: defaults.bargeIn,
            greetOnCall = p[K.greetOnCall] ?: defaults.greetOnCall,
            speechEngine = p[K.speechEngine] ?: defaults.speechEngine,
            headsetAudio = p[K.headsetAudio] ?: defaults.headsetAudio,
            announceReminders = p[K.announceReminders] ?: defaults.announceReminders,
            eventAnnounceMinutes = p[K.eventAnnounceMinutes] ?: defaults.eventAnnounceMinutes,
            alarmBriefing = p[K.alarmBriefing] ?: defaults.alarmBriefing,
            taughtPronunciations = p[K.taughtPronunciations] ?: defaults.taughtPronunciations,
            taughtAliases = p[K.taughtAliases] ?: defaults.taughtAliases,
            glow = p[K.glow] ?: defaults.glow,
            onboardingDone = p[K.onboarding] ?: false,
            checkUpdates = p[K.checkUpdates] ?: defaults.checkUpdates,
            localContextTokens = p[K.contextTokens] ?: 0,
            autoBackupFolder = p[K.autoBackupFolder] ?: "",
            autoBackupStatus = p[K.autoBackupStatus] ?: "",
            brainSounds = p[K.brainSounds] ?: true,
            eyeCaptions = p[K.eyeCaptions] ?: false,
            autoLearning = p[K.autoLearning] ?: true,
            webResearch = p[K.webResearch] ?: true,
            thinkTwice = p[K.thinkTwice] ?: true,
            animationLevel = p[K.animationLevel] ?: "full",
            proactiveHints = p[K.proactiveHints] ?: true,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setMode(mode: IntelligenceMode) = context.dataStore.edit { it[K.mode] = mode.name }
    suspend fun setActiveLocalModel(id: String?) = context.dataStore.edit { if (id == null) it.remove(K.localModel) else it[K.localModel] = id }
    suspend fun setLocalBackend(b: LocalBackend) = context.dataStore.edit { it[K.localBackend] = b.name }
    suspend fun setVoiceResponses(v: Boolean) = context.dataStore.edit { it[K.voice] = v }
    suspend fun setSpeechRate(r: Float) = context.dataStore.edit { it[K.rate] = r.coerceIn(0.5f, 2.0f) }
    suspend fun setSpeechPitch(p: Float) = context.dataStore.edit { it[K.pitch] = p.coerceIn(0.5f, 2.0f) }
    suspend fun setVoiceId(id: String) = context.dataStore.edit { it[K.voiceId] = id }
    suspend fun setPersona(name: String) = context.dataStore.edit { it[K.persona] = name }
    suspend fun setUserTitle(title: String) = context.dataStore.edit { it[K.userTitle] = title.trim().take(40) }
    suspend fun setWeatherCity(city: String) = context.dataStore.edit { it[K.weatherCity] = city.trim().take(80) }
    suspend fun setBriefing(enabled: Boolean, time: String) = context.dataStore.edit { it[K.briefingEnabled] = enabled; it[K.briefingTime] = time }
    suspend fun setRoutinesJson(json: String) = context.dataStore.edit { it[K.routines] = json }
    suspend fun setLastSeenVersion(v: String) = context.dataStore.edit { it[K.lastSeenVersion] = v }
    suspend fun setLiveSound(v: String) = context.dataStore.edit { it[K.liveSound] = v }
    suspend fun setHideRecognizerBeep(v: Boolean) = context.dataStore.edit { it[K.hideBeep] = v }
    suspend fun setPermissionsCardHidden(v: Boolean) = context.dataStore.edit { it[K.permsHidden] = v }
    suspend fun setConfirmMode(mode: String) = context.dataStore.edit { it[K.confirmMode] = mode }
    suspend fun setWakeWordEnabled(v: Boolean) = context.dataStore.edit { it[K.wakeWord] = v }
    suspend fun setWakeSensitivity(v: Int) = context.dataStore.edit { it[K.wakeSensitivity] = v.coerceIn(0, 3) }
    suspend fun setHomeAssistantUrl(v: String) = context.dataStore.edit { it[K.haUrl] = v.trim().trimEnd('/') }
    suspend fun setConfirmAllChanges(v: Boolean) = context.dataStore.edit { it[K.confirmAll] = v }
    suspend fun setUseKnowledge(v: Boolean) = context.dataStore.edit { it[K.useKnowledge] = v }
    suspend fun setAutoIndex(v: Boolean) = context.dataStore.edit { it[K.autoIndex] = v }
    suspend fun setBargeIn(v: Boolean) = context.dataStore.edit { it[K.bargeIn] = v }
    suspend fun setGreetOnCall(v: Boolean) = context.dataStore.edit { it[K.greetOnCall] = v }
    suspend fun setSpeechEngine(v: String) = context.dataStore.edit { it[K.speechEngine] = v }
    suspend fun setHeadsetAudio(v: String) = context.dataStore.edit { it[K.headsetAudio] = v }
    suspend fun setAnnounceReminders(v: Boolean) = context.dataStore.edit { it[K.announceReminders] = v }
    suspend fun setEventAnnounceMinutes(v: Int) = context.dataStore.edit { it[K.eventAnnounceMinutes] = v.coerceIn(0, 120) }
    suspend fun setAlarmBriefing(v: Boolean) = context.dataStore.edit { it[K.alarmBriefing] = v }
    suspend fun setTaught(pronunciations: String, aliases: String) = context.dataStore.edit { it[K.taughtPronunciations] = pronunciations; it[K.taughtAliases] = aliases }
    suspend fun setGlow(v: Boolean) = context.dataStore.edit { it[K.glow] = v }
    suspend fun setLocalContextTokens(v: Int) = context.dataStore.edit { it[K.contextTokens] = v }
    suspend fun setCheckUpdates(v: Boolean) = context.dataStore.edit { it[K.checkUpdates] = v }
    suspend fun setAutoBackupFolder(uri: String) = context.dataStore.edit { it[K.autoBackupFolder] = uri; it.remove(K.autoBackupStatus) }
    suspend fun setAutoBackupStatus(v: String) = context.dataStore.edit { it[K.autoBackupStatus] = v }
    suspend fun setEyeCaptions(v: Boolean) = context.dataStore.edit { it[K.eyeCaptions] = v }
    suspend fun setBrainSounds(v: Boolean) = context.dataStore.edit { it[K.brainSounds] = v }
    suspend fun setAutoLearning(v: Boolean) = context.dataStore.edit { it[K.autoLearning] = v }
    suspend fun setWebResearch(v: Boolean) = context.dataStore.edit { it[K.webResearch] = v }
    suspend fun setThinkTwice(v: Boolean) = context.dataStore.edit { it[K.thinkTwice] = v }
    suspend fun setAnimationLevel(v: String) = context.dataStore.edit { it[K.animationLevel] = v }
    suspend fun setProactiveHints(v: Boolean) = context.dataStore.edit { it[K.proactiveHints] = v }
    suspend fun setOnboardingDone() = context.dataStore.edit { it[K.onboarding] = true }
    suspend fun setSkillEnabled(id: String, enabled: Boolean) = context.dataStore.edit { p ->
        val all = AppSettings().enabledSkills
        val current = (p[K.skills] ?: all) + (all - (p[K.knownSkills] ?: LEGACY_SKILLS))
        p[K.skills] = if (enabled) current + id else current - id
        p[K.knownSkills] = all
    }

    companion object {
        /** Skills that existed before skill-set versioning (v0.1.12). */
        private val LEGACY_SKILLS = setOf(
            "core.time", "files.manage", "files.organize", "knowledge.local", "clock.alarms", "calendar.events", "reminders",
            "tasks.list", "memory.personal", "history.search", "app.settings", "automation.workflows", "research.online",
        )
    }
}
