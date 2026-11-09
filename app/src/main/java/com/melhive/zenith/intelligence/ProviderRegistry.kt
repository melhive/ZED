package com.melhive.zenith.intelligence

import android.content.Context
import com.melhive.zenith.android.DeviceInfo
import com.melhive.zenith.intelligence.providers.AnthropicProvider
import com.melhive.zenith.intelligence.providers.GeminiProvider
import com.melhive.zenith.intelligence.providers.OpenAiProvider
import com.melhive.zenith.models.ModelRepository
import com.melhive.zenith.security.SecretStore
import com.melhive.zenith.settings.AppSettings
import com.melhive.zenith.settings.IntelligenceMode
import com.melhive.zenith.settings.OnlineProviderKind
import com.melhive.zenith.settings.SettingsRepository

/** What the assistant needs to know about available intelligence (fakeable in tests). */
interface ProviderSource {
    val localRuntime: LocalModelRuntime
    suspend fun active(): IntelligenceProvider?
    suspend fun research(): IntelligenceProvider?
    suspend fun anyConfigured(): Boolean
}

/** Resolves which provider answers free-form requests based on current settings. */
class ProviderRegistry(
    private val context: Context,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val models: ModelRepository,
    override val localRuntime: LocalModelRuntime,
) : ProviderSource {
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
        val m = s.activeLocalModelId?.let { models.get(it) } ?: models.firstInstalled() ?: return null
        val opts = localOptions(s)
        // Never exceed the model's own maximum (e.g. Qwen3 0.6B is built for 1280 tokens).
        val max = com.melhive.zenith.models.ModelCatalog.find(m.id)?.contextTokens ?: opts.contextTokens
        return LocalLiteRtProvider(localRuntime, m.id, m.displayName, models.fileFor(m), opts.copy(contextTokens = minOf(opts.contextTokens, max)))
    }

    fun localOptions(s: AppSettings): LocalModelOptions {
        val tokens = s.localContextTokens.takeIf { it > 0 }
            ?: LocalModelRuntime.autoContextTokens(runCatching { DeviceInfo.snapshot(context).totalRamBytes }.getOrDefault(0L))
        return LocalModelOptions(s.localBackend, tokens)
    }

    private fun online(): Boolean = runCatching { DeviceInfo.snapshot(context).online }.getOrDefault(false)

    /** True when an online key or local model exists even if it can't be used right now. */
    override suspend fun anyConfigured(): Boolean {
        val s = settings.current()
        return secrets.has(SecretStore.apiKeyName(s.onlineProvider)) || local(s) != null
    }

    /** The provider for the current mode, or null when only native commands are available. */
    override suspend fun active(): IntelligenceProvider? {
        val s = settings.current()
        return when (s.mode) {
            IntelligenceMode.NATIVE_ONLY -> null
            IntelligenceMode.LOCAL -> local(s)?.takeIf { it.status().ready }
            IntelligenceMode.ONLINE -> online(s.onlineProvider, s).takeIf { it.status().ready }
            IntelligenceMode.AUTO -> online(s.onlineProvider, s).takeIf { it.status().ready && online() }
                ?: local(s)?.takeIf { it.status().ready }
        }
    }

    /** Provider used for web research: the configured online provider if it supports search. */
    override suspend fun research(): IntelligenceProvider? {
        val s = settings.current()
        return online(s.onlineProvider, s).takeIf { it.supportsWebSearch && it.status().ready }
    }

    suspend fun describeActive(): String {
        val s = settings.current()
        return when (s.mode) {
            IntelligenceMode.AUTO -> active()?.let { p -> "Auto · " + p.status().detail }
                ?: if (anyConfigured()) "Auto · AI unavailable right now (offline?) — native mode" else "Auto · no AI model yet — native mode"
            IntelligenceMode.NATIVE_ONLY -> "Native commands only"
            IntelligenceMode.LOCAL -> local(s)?.let { val st = it.status(); if (st.ready) it.label else "On-device model unavailable: ${st.detail}" } ?: "No on-device model selected"
            IntelligenceMode.ONLINE -> online(s.onlineProvider, s).let { val st = it.status(); if (st.ready) st.detail else "${it.label}: ${st.detail}" }
        }
    }
}
