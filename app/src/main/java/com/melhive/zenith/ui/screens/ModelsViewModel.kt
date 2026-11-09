package com.melhive.zenith.ui.screens

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.melhive.zenith.ZenithApp
import com.melhive.zenith.android.DeviceInfo
import com.melhive.zenith.intelligence.GenerationEvent
import com.melhive.zenith.intelligence.GenerationRequest
import com.melhive.zenith.intelligence.LocalModelRuntime
import com.melhive.zenith.intelligence.providers.AnthropicProvider
import com.melhive.zenith.intelligence.providers.GeminiProvider
import com.melhive.zenith.intelligence.providers.OpenAiProvider
import com.melhive.zenith.core.intelligence.ChatTurn
import com.melhive.zenith.core.intelligence.Role
import com.melhive.zenith.database.InstalledModelEntity
import com.melhive.zenith.models.CatalogModel
import com.melhive.zenith.security.SecretStore
import com.melhive.zenith.settings.IntelligenceMode
import com.melhive.zenith.settings.LocalBackend
import com.melhive.zenith.settings.OnlineProviderKind
import com.melhive.zenith.tasks.TaskTypes
import com.melhive.zenith.tasks.input
import com.melhive.zenith.tasks.statusEnum
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ModelsViewModel(app: Application) : AndroidViewModel(app) {
    val c = (app as ZenithApp).container
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val installed = c.models.installed.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val runtimeState: StateFlow<LocalModelRuntime.State> = c.localRuntime.state
    val configuredSecrets = c.secrets.configured
    val device = MutableStateFlow(DeviceInfo.snapshot(app))

    /** Model id → active download task id. */
    val downloads: StateFlow<Map<String, Long>> = c.taskEngine.tasks.map { list ->
        list.filter { it.type == TaskTypes.MODEL_DOWNLOAD && !it.statusEnum().terminal }.associate { (it.input()["model"] ?: "") to it.id }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val message = MutableStateFlow<String?>(null)
    val importing = MutableStateFlow<String?>(null)
    val availableModels = MutableStateFlow<Map<OnlineProviderKind, List<String>>>(emptyMap())
    val testing = MutableStateFlow(false)

    fun refreshDevice() { device.value = DeviceInfo.snapshot(getApplication()) }

    fun download(m: CatalogModel) = viewModelScope.launch {
        val report = c.models.assess(m)
        if (report.level == com.melhive.zenith.models.Compatibility.INCOMPATIBLE) { message.value = "Not downloading: " + report.reasons.joinToString("; "); return@launch }
        val t = c.taskEngine.create(TaskTypes.MODEL_DOWNLOAD, "Download ${m.name}", mapOf("model" to m.id))
        message.value = "Download started as task #${t.id}. You can keep using ZENITH."
    }

    fun import(uri: Uri) = viewModelScope.launch {
        importing.value = "Starting import…"
        runCatching {
            c.models.import(uri) { done, total -> importing.value = "Copied ${DeviceInfo.formatBytes(done)}" + (total?.let { " of ${DeviceInfo.formatBytes(it)}" } ?: "") }
        }.onSuccess { message.value = "Imported ${it.displayName}" + if (!it.verified) " (unverified: not a known catalog file)" else " (matches catalog checksum)" }
            .onFailure { message.value = "Import failed: ${it.message}" }
        importing.value = null
    }

    fun remove(m: InstalledModelEntity) = viewModelScope.launch {
        if (settings.value?.activeLocalModelId == m.id) { c.localRuntime.unload(); c.settings.setActiveLocalModel(null) }
        c.models.remove(m.id)
        message.value = "Removed ${m.displayName}"
    }

    fun activate(m: InstalledModelEntity) = viewModelScope.launch {
        c.localRuntime.unload()
        c.settings.setActiveLocalModel(m.id)
        c.settings.setMode(IntelligenceMode.LOCAL)
        message.value = "${m.displayName} is now the active model. It loads on first use."
    }

    fun load(m: InstalledModelEntity) = viewModelScope.launch {
        val s = c.settings.current()
        runCatching { c.localRuntime.ensureLoaded(m.id, c.models.fileFor(m), s.localBackend) }
            .onSuccess { message.value = "Loaded ${m.displayName}" }
            .onFailure { message.value = "Could not load: ${it.message}" }
    }

    fun unload() = viewModelScope.launch { c.localRuntime.unload() }
    fun setBackend(b: LocalBackend) = viewModelScope.launch { c.localRuntime.unload(); c.settings.setLocalBackend(b) }
    fun setMode(mode: IntelligenceMode) = viewModelScope.launch { c.settings.setMode(mode) }
    fun setProvider(kind: OnlineProviderKind) = viewModelScope.launch { c.settings.setOnlineProvider(kind) }
    fun setModel(kind: OnlineProviderKind, model: String) = viewModelScope.launch { c.settings.setOnlineModel(kind, model) }
    fun setBaseUrl(url: String) = viewModelScope.launch { c.settings.setCompatibleBaseUrl(url) }
    fun saveKey(kind: OnlineProviderKind, key: String) { c.secrets.put(SecretStore.apiKeyName(kind), key); message.value = "API key saved (encrypted with Android Keystore)" }
    fun removeKey(kind: OnlineProviderKind) { c.secrets.remove(SecretStore.apiKeyName(kind)); message.value = "API key removed" }
    fun saveHfToken(token: String) { c.secrets.put(SecretStore.HF_TOKEN, token); message.value = "Hugging Face token saved" }

    fun fetchModels(kind: OnlineProviderKind) = viewModelScope.launch {
        val s = c.settings.current()
        val key = { c.secrets.get(SecretStore.apiKeyName(kind)) }
        val result = when (kind) {
            OnlineProviderKind.GEMINI -> GeminiProvider(s.modelFor(kind), key).listModels()
            OnlineProviderKind.OPENAI -> OpenAiProvider("OpenAI", "https://api.openai.com/v1", s.modelFor(kind), key).listModels()
            OnlineProviderKind.ANTHROPIC -> AnthropicProvider(s.modelFor(kind), key).listModels()
            OnlineProviderKind.OPENAI_COMPATIBLE -> OpenAiProvider("Compatible", s.compatibleBaseUrl, s.modelFor(kind), key, requireKey = false, isOfficial = false).listModels()
        }
        result.onSuccess { availableModels.value = availableModels.value + (kind to it); message.value = "Found ${it.size} models" }
            .onFailure { message.value = "Could not list models: ${it.message}" }
    }

    /** Sends a tiny real request to verify the key, model and connectivity. */
    fun testProvider(kind: OnlineProviderKind) = viewModelScope.launch {
        testing.value = true
        val provider = c.providers.online(kind, c.settings.current())
        val st = provider.status()
        if (!st.ready) { message.value = "Not ready: ${st.detail}"; testing.value = false; return@launch }
        val sb = StringBuilder(); var err: String? = null; var usage: GenerationEvent.Usage? = null
        provider.generate(GenerationRequest("Reply with one short sentence.", listOf(ChatTurn(Role.USER, "Say hello to ZENITH.")), maxOutputTokens = 256)).collect { e ->
            when (e) { is GenerationEvent.Delta -> sb.append(e.text); is GenerationEvent.Error -> err = e.message; is GenerationEvent.Usage -> usage = e; else -> Unit }
        }
        message.value = err?.let { "Test failed: $it" } ?: ("Connected ✓ “${sb.toString().trim().take(80)}”" + (usage?.let { u -> " · tokens in ${u.inputTokens ?: "?"} / out ${u.outputTokens ?: "?"}" } ?: ""))
        testing.value = false
    }
}
