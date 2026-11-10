package com.melhive.zenith.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.melhive.zenith.core.skills.SkillRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Which intelligence layer answers free-form requests. Native commands always work.
 * AUTO picks the best available: online (key set and connected) → on-device model → native.
 */
enum class IntelligenceMode { AUTO, NATIVE_ONLY, LOCAL, ONLINE }

enum class OnlineProviderKind(val label: String, val defaultModel: String) {
    GEMINI("Google Gemini", "gemini-2.5-flash"),
    OPENAI("OpenAI", "gpt-4.1-mini"),
    ANTHROPIC("Anthropic Claude", "claude-opus-5-5"),
    OPENAI_COMPATIBLE("OpenAI-compatible endpoint", ""),
}

enum class LocalBackend { CPU, GPU }

data class AppSettings(
    val mode: IntelligenceMode = IntelligenceMode.AUTO,
    val activeLocalModelId: String? = null,
    val localBackend: LocalBackend = LocalBackend.CPU,
    val onlineProvider: OnlineProviderKind = OnlineProviderKind.GEMINI,
    val onlineModels: Map<OnlineProviderKind, String> = emptyMap(),
    val compatibleBaseUrl: String = "",
    val voiceResponses: Boolean = true,
    val speechRate: Float = 1.0f,
    /** Voice pitch, 1.0 = natural. */
    val speechPitch: Float = 1.0f,
    /** Selected voice: see [com.melhive.zenith.core.voice.VoiceSelection]. */
    val voiceId: String = "system",
    val confirmAllChanges: Boolean = false,
    val useKnowledge: Boolean = true,
    val autoIndex: Boolean = false,
    val enabledSkills: Set<String> = SkillRegistry.DEFAULT.skills.map { it.id }.toSet(),
    val bargeIn: Boolean = true,
    val glow: Boolean = true,
    val onboardingDone: Boolean = false,
    val checkUpdates: Boolean = true,
    /** On-device context window in tokens; 0 = choose automatically from device RAM. */
    val localContextTokens: Int = 0,
) {
    fun modelFor(kind: OnlineProviderKind): String = onlineModels[kind]?.takeIf { it.isNotBlank() } ?: kind.defaultModel
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zenith_settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val mode = stringPreferencesKey("mode")
        val localModel = stringPreferencesKey("local_model")
        val localBackend = stringPreferencesKey("local_backend")
        val onlineProvider = stringPreferencesKey("online_provider")
        val compatibleUrl = stringPreferencesKey("compatible_url")
        val voice = booleanPreferencesKey("voice_responses")
        val rate = floatPreferencesKey("speech_rate")
        val pitch = floatPreferencesKey("speech_pitch")
        val voiceId = stringPreferencesKey("voice_id")
        val confirmAll = booleanPreferencesKey("confirm_all")
        val useKnowledge = booleanPreferencesKey("use_knowledge")
        val autoIndex = booleanPreferencesKey("auto_index")
        val skills = stringSetPreferencesKey("enabled_skills")
        val bargeIn = booleanPreferencesKey("barge_in")
        val glow = booleanPreferencesKey("glow")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val checkUpdates = booleanPreferencesKey("check_updates")
        val contextTokens = androidx.datastore.preferences.core.intPreferencesKey("local_context_tokens")
        fun model(kind: OnlineProviderKind) = stringPreferencesKey("online_model_${kind.name.lowercase()}")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val defaults = AppSettings()
        AppSettings(
            mode = p[K.mode]?.let { runCatching { IntelligenceMode.valueOf(it) }.getOrNull() } ?: defaults.mode,
            activeLocalModelId = p[K.localModel],
            localBackend = p[K.localBackend]?.let { runCatching { LocalBackend.valueOf(it) }.getOrNull() } ?: defaults.localBackend,
            onlineProvider = p[K.onlineProvider]?.let { runCatching { OnlineProviderKind.valueOf(it) }.getOrNull() } ?: defaults.onlineProvider,
            onlineModels = OnlineProviderKind.entries.mapNotNull { k -> p[K.model(k)]?.let { k to it } }.toMap(),
            compatibleBaseUrl = p[K.compatibleUrl] ?: "",
            voiceResponses = p[K.voice] ?: defaults.voiceResponses,
            speechRate = p[K.rate] ?: defaults.speechRate,
            speechPitch = p[K.pitch] ?: defaults.speechPitch,
            voiceId = p[K.voiceId] ?: defaults.voiceId,
            confirmAllChanges = p[K.confirmAll] ?: defaults.confirmAllChanges,
            useKnowledge = p[K.useKnowledge] ?: defaults.useKnowledge,
            autoIndex = p[K.autoIndex] ?: defaults.autoIndex,
            enabledSkills = p[K.skills] ?: defaults.enabledSkills,
            bargeIn = p[K.bargeIn] ?: defaults.bargeIn,
            glow = p[K.glow] ?: defaults.glow,
            onboardingDone = p[K.onboarding] ?: false,
            checkUpdates = p[K.checkUpdates] ?: defaults.checkUpdates,
            localContextTokens = p[K.contextTokens] ?: 0,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setMode(mode: IntelligenceMode) = context.dataStore.edit { it[K.mode] = mode.name }
    suspend fun setActiveLocalModel(id: String?) = context.dataStore.edit { if (id == null) it.remove(K.localModel) else it[K.localModel] = id }
    suspend fun setLocalBackend(b: LocalBackend) = context.dataStore.edit { it[K.localBackend] = b.name }
    suspend fun setOnlineProvider(kind: OnlineProviderKind) = context.dataStore.edit { it[K.onlineProvider] = kind.name }
    suspend fun setOnlineModel(kind: OnlineProviderKind, model: String) = context.dataStore.edit { it[K.model(kind)] = model.trim() }
    suspend fun setCompatibleBaseUrl(url: String) = context.dataStore.edit { it[K.compatibleUrl] = url.trim() }
    suspend fun setVoiceResponses(v: Boolean) = context.dataStore.edit { it[K.voice] = v }
    suspend fun setSpeechRate(r: Float) = context.dataStore.edit { it[K.rate] = r.coerceIn(0.5f, 2.0f) }
    suspend fun setSpeechPitch(p: Float) = context.dataStore.edit { it[K.pitch] = p.coerceIn(0.5f, 2.0f) }
    suspend fun setVoiceId(id: String) = context.dataStore.edit { it[K.voiceId] = id }
    suspend fun setConfirmAllChanges(v: Boolean) = context.dataStore.edit { it[K.confirmAll] = v }
    suspend fun setUseKnowledge(v: Boolean) = context.dataStore.edit { it[K.useKnowledge] = v }
    suspend fun setAutoIndex(v: Boolean) = context.dataStore.edit { it[K.autoIndex] = v }
    suspend fun setBargeIn(v: Boolean) = context.dataStore.edit { it[K.bargeIn] = v }
    suspend fun setGlow(v: Boolean) = context.dataStore.edit { it[K.glow] = v }
    suspend fun setLocalContextTokens(v: Int) = context.dataStore.edit { it[K.contextTokens] = v }
    suspend fun setCheckUpdates(v: Boolean) = context.dataStore.edit { it[K.checkUpdates] = v }
    suspend fun setOnboardingDone() = context.dataStore.edit { it[K.onboarding] = true }
    suspend fun setSkillEnabled(id: String, enabled: Boolean) = context.dataStore.edit { p ->
        val current = p[K.skills] ?: AppSettings().enabledSkills
        p[K.skills] = if (enabled) current + id else current - id
    }
}
