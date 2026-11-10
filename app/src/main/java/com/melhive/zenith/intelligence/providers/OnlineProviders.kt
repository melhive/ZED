package com.melhive.zenith.intelligence.providers

import com.melhive.zenith.core.intelligence.Role
import com.melhive.zenith.intelligence.GenerationEvent
import com.melhive.zenith.intelligence.GenerationRequest
import com.melhive.zenith.intelligence.IntelligenceProvider
import com.melhive.zenith.intelligence.ProviderStatus
import com.melhive.zenith.intelligence.normalizeTurns
import com.melhive.zenith.intelligence.ToolCall
import com.melhive.zenith.core.tools.NativeToolSchema
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private fun missingKey(label: String) = flowOf(GenerationEvent.Error("No API key configured for $label. Add one in Model Manager."))

/** OpenAI Chat Completions (and OpenAI-compatible endpoints such as local servers or routers). */
class OpenAiProvider(
    override val label: String,
    private val baseUrl: String,
    private val model: String,
    private val apiKey: () -> String?,
    private val requireKey: Boolean = true,
    private val isOfficial: Boolean = true,
) : IntelligenceProvider {
    override val id: String = if (isOfficial) "openai" else "openai_compatible"
    override val supportsWebSearch: Boolean get() = isOfficial
    override val supportsNativeTools: Boolean get() = isOfficial

    private fun headers(): Map<String, String> = buildMap {
        apiKey()?.let { put("Authorization", "Bearer $it") }
    }

    override suspend fun status(): ProviderStatus = when {
        baseUrl.isBlank() -> ProviderStatus(false, "Endpoint URL not set")
        !baseUrl.startsWith("https://") && !baseUrl.startsWith("http://127.0.0.1") && !baseUrl.startsWith("http://localhost") ->
            ProviderStatus(false, "Endpoint must use HTTPS (plain HTTP is allowed only for localhost)")
        model.isBlank() -> ProviderStatus(false, "Model not set")
        requireKey && apiKey() == null -> ProviderStatus(false, "API key not set")
        else -> ProviderStatus(true, "$label · $model")
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> {
        if (requireKey && apiKey() == null) return missingKey(label)
        if (request.webSearch && isOfficial) return research(request)
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            putJsonObject("stream_options") { put("include_usage", true) }
            put(if (isOfficial) "max_completion_tokens" else "max_tokens", request.maxOutputTokens)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", request.system) }
                for (t in normalizeTurns(request.turns)) addJsonObject {
                    put("role", if (t.role == Role.ASSISTANT) "assistant" else "user")
                    put("content", t.content)
                }
                for (ex in request.exchanges) {
                    addJsonObject {
                        put("role", "assistant")
                        if (ex.assistantText.isNotBlank()) put("content", ex.assistantText) else put("content", JsonNull)
                        putJsonArray("tool_calls") {
                            for (c in ex.calls) addJsonObject {
                                put("id", c.id); put("type", "function")
                                putJsonObject("function") { put("name", NativeToolSchema.wireName(c.name)); put("arguments", c.rawArgs.toString()) }
                            }
                        }
                    }
                    for (r in ex.results) addJsonObject { put("role", "tool"); put("tool_call_id", r.callId); put("content", r.content) }
                }
            }
            if (request.tools.isNotEmpty() && supportsNativeTools) putJsonArray("tools") {
                for (t in request.tools) addJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", NativeToolSchema.wireName(t.name))
                        put("description", NativeToolSchema.description(t))
                        put("parameters", NativeToolSchema.jsonSchema(t))
                    }
                }
            }
        }.toString()
        val pending = sortedMapOf<Int, PendingCall>()
        suspend fun kotlinx.coroutines.channels.ProducerScope<GenerationEvent>.flushCalls() {
            if (pending.isEmpty()) return
            send(GenerationEvent.ToolCalls(pending.map { (i, pc) -> pc.toCall("call_$i") }))
            pending.clear()
        }
        return Http.sse("${baseUrl.trimEnd('/')}/chat/completions", headers(), body) { _, data ->
            if (data.trim() == "[DONE]") { flushCalls(); send(GenerationEvent.Done("stop")); return@sse false }
            val root = runCatching { ProviderJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@sse true
            root["error"]?.let { send(GenerationEvent.Error(it.obj()?.get("message").str() ?: it.toString())); return@sse false }
            val choice = (root["choices"] as? JsonArray)?.firstOrNull()?.obj()
            choice?.path("delta", "content").str()?.let { if (it.isNotEmpty()) send(GenerationEvent.Delta(it)) }
            (choice?.path("delta", "tool_calls") as? JsonArray)?.forEach { el ->
                val tc = el.obj() ?: return@forEach
                val pc = pending.getOrPut(tc["index"].int() ?: 0) { PendingCall() }
                tc["id"].str()?.let { if (it.isNotEmpty()) pc.id = it }
                tc.path("function", "name").str()?.let { if (it.isNotEmpty()) pc.name = it }
                tc.path("function", "arguments").str()?.let { pc.args.append(it) }
            }
            if (choice?.get("finish_reason").str() == "tool_calls") flushCalls()
            root["usage"].obj()?.let { u -> send(GenerationEvent.Usage(u["prompt_tokens"].int(), u["completion_tokens"].int())) }
            choice?.get("finish_reason").str()?.let { if (it == "length") send(GenerationEvent.Delta("\n\n[Response cut off: output token limit reached]")) }
            true
        }
    }

    /** Responses API with the hosted web_search tool. */
    private fun research(request: GenerationRequest): Flow<GenerationEvent> {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("instructions", request.system)
            putJsonArray("tools") { addJsonObject { put("type", "web_search") } }
            putJsonArray("input") {
                for (t in normalizeTurns(request.turns)) addJsonObject {
                    put("role", if (t.role == Role.ASSISTANT) "assistant" else "user")
                    put("content", t.content)
                }
            }
        }.toString()
        return Http.sse("${baseUrl.trimEnd('/')}/responses", headers(), body) { event, data ->
            val root = runCatching { ProviderJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@sse true
            when (event ?: root["type"].str()) {
                "response.output_text.delta" -> root["delta"].str()?.let { send(GenerationEvent.Delta(it)) }
                "response.completed" -> {
                    val u = root.path("response", "usage").obj()
                    if (u != null) send(GenerationEvent.Usage(u["input_tokens"].int(), u["output_tokens"].int()))
                    send(GenerationEvent.Done("stop")); return@sse false
                }
                "response.failed", "error" -> {
                    val msg = root.path("response", "error", "message").str() ?: root["message"].str() ?: "Research request failed"
                    send(GenerationEvent.Error(msg)); return@sse false
                }
            }
            true
        }
    }

    suspend fun listModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        Http.getJson("${baseUrl.trimEnd('/')}/models", headers()).map { root ->
            (root["data"] as? JsonArray).orEmpty().mapNotNull { it.obj()?.get("id").str() }.sorted()
        }
    }
}

/** Anthropic Messages API over raw HTTP with SSE streaming. */
class AnthropicProvider(
    private val model: String,
    private val apiKey: () -> String?,
    private val baseUrl: String = BASE,
) : IntelligenceProvider {
    override val id = "anthropic"
    override val label = "Anthropic Claude"
    override val supportsWebSearch = true
    override val supportsNativeTools = true

    private fun headers(key: String) = buildMap {
        put("x-api-key", key)
        put("anthropic-version", API_VERSION)
        // Server-side refusal fallback for models that support the "default" form.
        if (model in FALLBACK_MODELS) put("anthropic-beta", "server-side-fallback-2026-07-01")
    }

    override suspend fun status(): ProviderStatus = when {
        apiKey() == null -> ProviderStatus(false, "API key not set")
        model.isBlank() -> ProviderStatus(false, "Model not set")
        else -> ProviderStatus(true, "Claude · $model")
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> {
        val key = apiKey() ?: return missingKey(label)
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxOf(request.maxOutputTokens, 16000))
            put("stream", true)
            put("system", request.system)
            if (model in FALLBACK_MODELS) put("fallbacks", "default")
            if (request.webSearch) putJsonArray("tools") {
                addJsonObject { put("type", "web_search_20260209"); put("name", "web_search"); put("max_uses", 5) }
            } else if (request.tools.isNotEmpty()) putJsonArray("tools") {
                for (t in request.tools) addJsonObject {
                    put("name", NativeToolSchema.wireName(t.name))
                    put("description", NativeToolSchema.description(t))
                    put("input_schema", NativeToolSchema.jsonSchema(t))
                }
            }
            putJsonArray("messages") {
                for (t in normalizeTurns(request.turns)) addJsonObject {
                    put("role", if (t.role == Role.ASSISTANT) "assistant" else "user")
                    put("content", t.content)
                }
                for (ex in request.exchanges) {
                    addJsonObject {
                        put("role", "assistant")
                        putJsonArray("content") {
                            if (ex.assistantText.isNotBlank()) addJsonObject { put("type", "text"); put("text", ex.assistantText) }
                            for (c in ex.calls) addJsonObject {
                                put("type", "tool_use"); put("id", c.id); put("name", NativeToolSchema.wireName(c.name)); put("input", c.rawArgs)
                            }
                        }
                    }
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            for (r in ex.results) addJsonObject {
                                put("type", "tool_result"); put("tool_use_id", r.callId); put("content", r.content)
                                if (!r.ok) put("is_error", true)
                            }
                        }
                    }
                }
            }
        }.toString()
        var inputTokens: Int? = null
        val blocks = mutableMapOf<Int, PendingCall>()
        return Http.sse("$baseUrl/v1/messages", headers(key), body) { event, data ->
            val root = runCatching { ProviderJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@sse true
            when (event ?: root["type"].str()) {
                "message_start" -> inputTokens = root.path("message", "usage", "input_tokens").int()
                "content_block_start" -> {
                    val block = root["content_block"].obj()
                    if (block?.get("type").str() == "tool_use") blocks[root["index"].int() ?: 0] = PendingCall().apply {
                        id = block?.get("id").str().orEmpty(); name = block?.get("name").str().orEmpty()
                    }
                }
                "content_block_delta" -> {
                    val delta = root["delta"].obj()
                    when (delta?.get("type").str()) {
                        "text_delta" -> delta?.get("text").str()?.let { send(GenerationEvent.Delta(it)) }
                        "input_json_delta" -> blocks[root["index"].int() ?: 0]?.args?.append(delta?.get("partial_json").str().orEmpty())
                    }
                }
                "content_block_stop" -> blocks.remove(root["index"].int() ?: 0)?.let { pc ->
                    send(GenerationEvent.ToolCalls(listOf(pc.toCall("toolu_${root["index"].int() ?: 0}"))))
                }
                "message_delta" -> {
                    val out = root.path("usage", "output_tokens").int()
                    send(GenerationEvent.Usage(inputTokens, out))
                    when (root.path("delta", "stop_reason").str()) {
                        "refusal" -> send(GenerationEvent.Delta("\n\n[The provider declined this request.]"))
                        "max_tokens" -> send(GenerationEvent.Delta("\n\n[Response cut off: output token limit reached]"))
                    }
                }
                "message_stop" -> { send(GenerationEvent.Done("end_turn")); return@sse false }
                "error" -> { send(GenerationEvent.Error(root.path("error", "message").str() ?: "Provider error", retryable = true)); return@sse false }
            }
            true
        }
    }

    suspend fun listModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        val key = apiKey() ?: return@withContext Result.failure(IllegalStateException("API key not set"))
        Http.getJson("$baseUrl/v1/models?limit=100", mapOf("x-api-key" to key, "anthropic-version" to API_VERSION)).map { root ->
            (root["data"] as? JsonArray).orEmpty().mapNotNull { it.obj()?.get("id").str() }
        }
    }

    companion object {
        const val BASE = "https://api.anthropic.com"
        const val API_VERSION = "2026-10-21"
        val FALLBACK_MODELS = setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")
    }
}

/** Google Gemini API (generativelanguage.googleapis.com) with SSE streaming. */
class GeminiProvider(
    private val model: String,
    private val apiKey: () -> String?,
    private val baseUrl: String = BASE,
) : IntelligenceProvider {
    override val id = "gemini"
    override val label = "Google Gemini"
    override val supportsWebSearch = true
    override val supportsNativeTools = true

    override suspend fun status(): ProviderStatus = when {
        apiKey() == null -> ProviderStatus(false, "API key not set")
        model.isBlank() -> ProviderStatus(false, "Model not set")
        else -> ProviderStatus(true, "Gemini · $model")
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> {
        val key = apiKey() ?: return missingKey(label)
        val body = buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", request.system) } } }
            putJsonArray("contents") {
                for (t in normalizeTurns(request.turns)) addJsonObject {
                    put("role", if (t.role == Role.ASSISTANT) "model" else "user")
                    putJsonArray("parts") { addJsonObject { put("text", t.content) } }
                }
                for (ex in request.exchanges) {
                    addJsonObject {
                        put("role", "model")
                        putJsonArray("parts") {
                            if (ex.assistantText.isNotBlank()) addJsonObject { put("text", ex.assistantText) }
                            // The original part is sent back as-is: newer Gemini models require their thought signatures.
                            for (c in ex.calls) add(c.raw ?: buildJsonObject {
                                putJsonObject("functionCall") { put("name", NativeToolSchema.wireName(c.name)); put("args", c.rawArgs) }
                            })
                        }
                    }
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            for (r in ex.results) addJsonObject {
                                putJsonObject("functionResponse") {
                                    put("name", NativeToolSchema.wireName(r.name))
                                    if (!r.callId.startsWith(SYNTHETIC_ID)) put("id", r.callId)
                                    putJsonObject("response") { put(if (r.ok) "result" else "error", r.content) }
                                }
                            }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") { put("maxOutputTokens", request.maxOutputTokens) }
            if (request.webSearch) putJsonArray("tools") { addJsonObject { putJsonObject("google_search") {} } }
            else if (request.tools.isNotEmpty()) putJsonArray("tools") {
                addJsonObject {
                    putJsonArray("functionDeclarations") {
                        for (t in request.tools) addJsonObject {
                            put("name", NativeToolSchema.wireName(t.name))
                            put("description", NativeToolSchema.description(t))
                            put("parameters", NativeToolSchema.geminiSchema(t))
                        }
                    }
                }
            }
        }.toString()
        var callCounter = 0
        val url = "$baseUrl/v1beta/models/${model.removePrefix("models/")}:streamGenerateContent?alt=sse"
        return Http.sse(url, mapOf("x-goog-api-key" to key), body) { _, data ->
            val root = runCatching { ProviderJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@sse true
            root["error"]?.let { send(GenerationEvent.Error(it.obj()?.get("message").str() ?: "Gemini error")); return@sse false }
            val cand = (root["candidates"] as? JsonArray)?.firstOrNull()?.obj()
            (cand?.path("content", "parts") as? JsonArray)?.forEach { part ->
                val p = part.obj() ?: return@forEach
                if (p["thought"].str() == "true") return@forEach
                p["text"].str()?.let { if (it.isNotEmpty()) send(GenerationEvent.Delta(it)) }
                p["functionCall"].obj()?.let { fc ->
                    val args = fc["args"].obj() ?: JsonObject(emptyMap())
                    val wire = fc["name"].str().orEmpty()
                    send(GenerationEvent.ToolCalls(listOf(ToolCall(
                        id = fc["id"].str() ?: "$SYNTHETIC_ID${callCounter++}",
                        name = NativeToolSchema.toolName(wire), args = NativeToolSchema.argsToStrings(args), rawArgs = args, raw = p,
                    ))))
                }
            }
            root["usageMetadata"].obj()?.let { u -> send(GenerationEvent.Usage(u["promptTokenCount"].int(), u["candidatesTokenCount"].int())) }
            when (cand?.get("finishReason").str()) {
                null, "" -> Unit
                "STOP" -> Unit
                "MAX_TOKENS" -> send(GenerationEvent.Delta("\n\n[Response cut off: output token limit reached]"))
                "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST" -> send(GenerationEvent.Delta("\n\n[The provider blocked this response.]"))
            }
            true
        }
    }

    suspend fun listModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        val key = apiKey() ?: return@withContext Result.failure(IllegalStateException("API key not set"))
        Http.getJson("$baseUrl/v1beta/models?pageSize=200", mapOf("x-goog-api-key" to key)).map { root ->
            (root["models"] as? JsonArray).orEmpty().mapNotNull { m ->
                val o = m.obj() ?: return@mapNotNull null
                val methods = (o["supportedGenerationMethods"] as? JsonArray).orEmpty().mapNotNull { it.str() }
                if ("generateContent" in methods) o["name"].str()?.removePrefix("models/") else null
            }.sorted()
        }
    }

    companion object {
        const val BASE = "https://generativelanguage.googleapis.com"
        private const val SYNTHETIC_ID = "zenith-call-"
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

/** A tool call being assembled from streamed fragments. */
internal class PendingCall {
    var id: String = ""
    var name: String = ""
    val args = StringBuilder()

    fun toCall(fallbackId: String): ToolCall {
        val parsed = runCatching { ProviderJson.parseToJsonElement(args.toString().ifBlank { "{}" }).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        return ToolCall(
            id = id.ifEmpty { fallbackId },
            name = NativeToolSchema.toolName(name),
            args = NativeToolSchema.argsToStrings(parsed),
            rawArgs = parsed,
        )
    }
}
