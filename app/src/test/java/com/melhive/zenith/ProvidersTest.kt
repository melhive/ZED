package com.melhive.zenith

import com.melhive.zenith.core.intelligence.ChatTurn
import com.melhive.zenith.core.intelligence.Role
import com.melhive.zenith.intelligence.GenerationEvent
import com.melhive.zenith.intelligence.GenerationRequest
import com.melhive.zenith.intelligence.providers.AnthropicProvider
import com.melhive.zenith.intelligence.providers.GeminiProvider
import com.melhive.zenith.intelligence.providers.OpenAiProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProvidersTest {
    private lateinit var server: MockWebServer
    private val request = GenerationRequest("system", listOf(ChatTurn(Role.USER, "hi"), ChatTurn(Role.ASSISTANT, "hello"), ChatTurn(Role.TOOL, "Tool result"), ChatTurn(Role.USER, "again")))

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun sse(vararg events: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events.joinToString("") { "$it\n\n" })
    private fun text(events: List<GenerationEvent>) = events.filterIsInstance<GenerationEvent.Delta>().joinToString("") { it.text }

    @Test fun openAiStreamsDeltasAndUsage() = runBlocking {
        server.enqueue(sse(
            """data: {"choices":[{"delta":{"content":"Hel"}}]}""",
            """data: {"choices":[{"delta":{"content":"lo"},"finish_reason":"stop"}]}""",
            """data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":2}}""",
            "data: [DONE]",
        ))
        val p = OpenAiProvider("OpenAI", server.url("/v1").toString().trimEnd('/'), "gpt-test", { "sk-test" })
        val events = p.generate(request).toList()
        assertEquals("Hello", text(events))
        assertTrue(events.any { it is GenerationEvent.Usage && it.inputTokens == 12 && it.outputTokens == 2 })
        assertTrue(events.last() is GenerationEvent.Done)
        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        // Tool turn merged into the following user turn; roles alternate.
        assertTrue(body.contains("\"role\":\"system\""))
        assertTrue(body.contains("Tool result\\n\\nagain"))
    }

    @Test fun anthropicStreamsTextDeltas() = runBlocking {
        server.enqueue(sse(
            "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":20}}}",
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"\"}}",
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi there\"}}",
            "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":3}}",
            "event: message_stop\ndata: {\"type\":\"message_stop\"}",
        ))
        val p = AnthropicProvider("claude-opus-5-5", { "key" }, server.url("").toString().trimEnd('/'))
        val events = p.generate(request).toList()
        assertEquals("Hi there", text(events))
        assertTrue(events.any { it is GenerationEvent.Usage && it.inputTokens == 20 && it.outputTokens == 3 })
        val r = server.takeRequest()
        assertEquals("key", r.getHeader("x-api-key"))
        assertEquals(AnthropicProvider.API_VERSION, r.getHeader("anthropic-version"))
        assertTrue(r.body.readUtf8().contains("\"fallbacks\":\"default\""))
    }

    @Test fun geminiStreamsParts() = runBlocking {
        server.enqueue(sse(
            """data: {"candidates":[{"content":{"parts":[{"text":"Good "}],"role":"model"}}]}""",
            """data: {"candidates":[{"content":{"parts":[{"text":"morning"}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":2}}""",
        ))
        val p = GeminiProvider("gemini-test", { "g-key" }, server.url("").toString().trimEnd('/'))
        val events = p.generate(request).toList()
        assertEquals("Good morning", text(events))
        val r = server.takeRequest()
        assertEquals("/v1beta/models/gemini-test:streamGenerateContent?alt=sse", r.path)
        assertEquals("g-key", r.getHeader("x-goog-api-key"))
    }

    @Test fun httpErrorsAreReportedWithoutSecrets() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid key sk-abcdefghijklmnopqrstuvwxyz"}}"""))
        val p = OpenAiProvider("OpenAI", server.url("/v1").toString().trimEnd('/'), "m", { "sk-test" })
        val events = p.generate(request).toList()
        val err = events.filterIsInstance<GenerationEvent.Error>().single()
        assertTrue(err.message.startsWith("Authentication failed"))
        assertTrue("abcdefghijklmnop" !in err.message)
    }

    @Test fun missingKeyFailsFast() = runBlocking {
        val events = GeminiProvider("m", { null }, server.url("").toString()).generate(request).toList()
        assertTrue(events.single() is GenerationEvent.Error)
        assertEquals(0, server.requestCount)
    }
}
