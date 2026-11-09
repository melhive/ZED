package com.melhive.zenith.intelligence

import com.melhive.zenith.core.intelligence.ChatTurn
import com.melhive.zenith.core.intelligence.Role
import kotlinx.coroutines.flow.Flow

data class GenerationRequest(
    val system: String,
    val turns: List<ChatTurn>,
    val maxOutputTokens: Int = 4096,
    /** Ask the provider to use its web search tool (research mode). */
    val webSearch: Boolean = false,
)

sealed interface GenerationEvent {
    data class Delta(val text: String) : GenerationEvent
    data class Usage(val inputTokens: Int?, val outputTokens: Int?) : GenerationEvent
    data class Done(val stopReason: String?) : GenerationEvent
    data class Error(val message: String, val retryable: Boolean = false) : GenerationEvent
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
