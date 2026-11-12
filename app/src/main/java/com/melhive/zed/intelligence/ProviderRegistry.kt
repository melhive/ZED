package com.melhive.zed.intelligence

import android.content.Context
import com.melhive.zed.android.DeviceInfo
import com.melhive.zed.models.ModelRepository
import com.melhive.zed.settings.AppSettings
import com.melhive.zed.settings.IntelligenceMode
import com.melhive.zed.settings.SettingsRepository

/** What the assistant needs to know about available intelligence (fakeable in tests). */
interface ProviderSource {
    val localRuntime: LocalModelRuntime
    suspend fun active(): IntelligenceProvider?
    suspend fun anyConfigured(): Boolean
}

/**
 * The intelligence behind free-form requests: Gemma (or another installed
 * model) running on the phone. ZED has no online AI: nothing a user says is
 * sent to an AI service.
 */
class ProviderRegistry(
    private val context: Context,
    private val settings: SettingsRepository,
    private val models: ModelRepository,
    override val localRuntime: LocalModelRuntime,
) : ProviderSource {

    suspend fun local(s: AppSettings): IntelligenceProvider? {
        val m = s.activeLocalModelId?.let { models.get(it) } ?: models.firstInstalled() ?: return null
        val opts = localOptions(s)
        // Never exceed the model's own maximum (e.g. Qwen3 0.6B is built for 1280 tokens).
        val max = com.melhive.zed.models.ModelCatalog.find(m.id)?.contextTokens ?: opts.contextTokens
        return LocalLiteRtProvider(localRuntime, m.id, m.displayName, models.fileFor(m), opts.copy(contextTokens = minOf(opts.contextTokens, max)))
    }

    fun localOptions(s: AppSettings): LocalModelOptions {
        val tokens = s.localContextTokens.takeIf { it > 0 }
            ?: LocalModelRuntime.autoContextTokens(runCatching { DeviceInfo.snapshot(context).totalRamBytes }.getOrDefault(0L))
        return LocalModelOptions(s.localBackend, tokens)
    }

    /** True when an on-device model is installed, even if it can't be used right now. */
    override suspend fun anyConfigured(): Boolean = local(settings.current()) != null

    /** The on-device model, or null when only native commands are available. */
    override suspend fun active(): IntelligenceProvider? {
        val s = settings.current()
        if (s.mode == IntelligenceMode.NATIVE_ONLY) return null
        return local(s)?.takeIf { it.status().ready }
    }

    suspend fun describeActive(): String {
        val s = settings.current()
        if (s.mode == IntelligenceMode.NATIVE_ONLY) return "Native commands only"
        return local(s)?.let { val st = it.status(); if (st.ready) it.label else "On-device model unavailable: ${st.detail}" }
            ?: "No AI model yet — native commands only"
    }
}
