package com.melhive.zenith.intelligence

import com.melhive.zenith.core.intelligence.ChatTurn
import com.melhive.zenith.core.intelligence.Role
import com.melhive.zenith.core.tools.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

data class GenerationRequest(
    val system: String,
    val turns: List<ChatTurn>,
    val maxOutputTokens: Int = 4096,
    /** Ask the provider to use its web search tool (research mode). */
    val webSearch: Boolean = false,
    /** Stable id of the conversation, so on-device runtimes can reuse their session. */
    val sessionKey: String? = null,
    /** Per-turn context prepended to the newest user message (on-device models only). */
    val volatileContext: String? = null,
    /** Tools offered through the provider's native function calling (empty = none). */
    val tools: List<ToolSpec> = emptyList(),
    /** Tool calls and results earlier in this same turn, replayed in the provider's own format. */
    val exchanges: List<ToolExchange> = emptyList(),
)

/** A function call requested by a model. [args] are untrusted until validated. */
data class ToolCall(
    val id: String,
    val name: String,
    val args: Map<String, String>,
    val rawArgs: JsonObject,
    /** The provider's original call part, echoed back verbatim when required (Gemini thought signatures). */
    val raw: JsonObject? = null,
)

data class ToolCallResult(val callId: String, val name: String, val content: String, val ok: Boolean)

/** One model step that asked for tools, and what those tools returned. */
data class ToolExchange(val assistantText: String, val calls: List<ToolCall>, val results: List<ToolCallResult>)

sealed interface GenerationEvent {
    data class Delta(val text: String) : GenerationEvent
    data class Usage(val inputTokens: Int?, val outputTokens: Int?) : GenerationEvent
    data class Done(val stopReason: String?) : GenerationEvent
    data class Error(val message: String, val retryable: Boolean = false) : GenerationEvent
    /** The model asked to run these tools (complete calls). */
    data class ToolCalls(val calls: List<ToolCall>) : GenerationEvent
}

data class ProviderStatus(val ready: Boolean, val detail: String)

/**
 * A source of model output: on-device (LiteRT-LM) or online (Gemini, OpenAI,
 * Claude, compatible endpoints). Collecting [generate] streams output;
 * cancelling the collector cancels the underlying request or inference.
 */
interface IntelligenceProvider {
    val id: String
    val label: String
    val supportsWebSearch: Boolean get() = false
    /** True when [GenerationRequest.tools] are passed as native function declarations. */
    val supportsNativeTools: Boolean get() = false
    suspend fun status(): ProviderStatus
    fun generate(request: GenerationRequest): Flow<GenerationEvent>
}

/** Collapses consecutive same-role turns and maps TOOL results to user-role text. */
internal fun normalizeTurns(turns: List<ChatTurn>): List<ChatTurn> {
    val out = mutableListOf<ChatTurn>()
    for (t in turns) {
        if (t.role == Role.SYSTEM) continue
        val role = if (t.role == Role.TOOL) Role.USER else t.role
        val last = out.lastOrNull()
        if (last != null && last.role == role) out[out.size - 1] = last.copy(content = last.content + "\n\n" + t.content)
        else out += ChatTurn(role, t.content)
    }
    while (out.isNotEmpty() && out.first().role != Role.USER) out.removeAt(0)
    return out
}
