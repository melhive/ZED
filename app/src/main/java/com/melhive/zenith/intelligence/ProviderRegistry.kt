package com.melhive.zenith.intelligence

import com.melhive.zenith.intelligence.providers.AnthropicProvider
import com.melhive.zenith.intelligence.providers.GeminiProvider
import com.melhive.zenith.intelligence.providers.OpenAiProvider
import com.melhive.zenith.models.ModelRepository
import com.melhive.zenith.security.SecretStore
import com.melhive.zenith.settings.AppSettings
import com.melhive.zenith.settings.IntelligenceMode
import com.melhive.zenith.settings.OnlineProviderKind
import com.melhive.zenith.settings.SettingsRepository

/** Resolves which provider answers free-form requests based on current settings. */
class ProviderRegistry(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val models: ModelRepository,
    val localRuntime: LocalModelRuntime,
) {
    fun online(kind: OnlineProviderKind, s: AppSettings): IntelligenceProvider {
        val key = { secrets.get(SecretStore.apiKeyName(kind)) }
        val model = s.modelFor(kind)
        return when (kind) {
            OnlineProviderKind.GEMINI -> GeminiProvider(model, key)
            OnlineProviderKind.OPENAI -> OpenAiProvider("OpenAI", "https://api.openai.com/v1", model, key)
            OnlineProviderKind.ANTHROPIC -> AnthropicProvider(model, key)
            OnlineProviderKind.OPENAI_COMPATIBLE -> OpenAiProvider("Compatible endpoint", s.compatibleBaseUrl, model, key, requireKey = false, isOfficial = false)
        }
    }

    suspend fun local(s: AppSettings): IntelligenceProvider? {
        val id = s.activeLocalModelId ?: return null
        val m = models.get(id) ?: return null
        return LocalLiteRtProvider(localRuntime, m.id, m.displayName, models.fileFor(m), s.localBackend)
    }

    /** The provider for the current mode, or null when only native commands are available. */
    suspend fun active(): IntelligenceProvider? {
        val s = settings.current()
        return when (s.mode) {
            IntelligenceMode.NATIVE_ONLY -> null
            IntelligenceMode.LOCAL -> local(s)?.takeIf { it.status().ready }
            IntelligenceMode.ONLINE -> online(s.onlineProvider, s).takeIf { it.status().ready }
        }
    }

    /** Provider used for web research: the configured online provider if it supports search. */
    suspend fun research(): IntelligenceProvider? {
        val s = settings.current()
        return online(s.onlineProvider, s).takeIf { it.supportsWebSearch && it.status().ready }
    }

    suspend fun describeActive(): String {
        val s = settings.current()
        return when (s.mode) {
            IntelligenceMode.NATIVE_ONLY -> "Native commands only"
            IntelligenceMode.LOCAL -> local(s)?.let { val st = it.status(); if (st.ready) it.label else "On-device model unavailable: ${st.detail}" } ?: "No on-device model selected"
            IntelligenceMode.ONLINE -> online(s.onlineProvider, s).let { val st = it.status(); if (st.ready) st.detail else "${it.label}: ${st.detail}" }
        }
    }
}
