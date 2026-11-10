package com.melhive.zenith

import com.melhive.zenith.core.intelligence.ChatTurn
import com.melhive.zenith.core.intelligence.Role
import com.melhive.zenith.core.tools.ToolCatalog
import com.melhive.zenith.intelligence.GenerationEvent
import com.melhive.zenith.intelligence.GenerationRequest
import com.melhive.zenith.intelligence.ToolCallResult
import com.melhive.zenith.intelligence.ToolExchange
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

/** Native function calling: request encoding and streamed tool-call parsing per provider. */
class NativeToolsTest {
    private lateinit var server: MockWebServer
    private val tools = listOf(ToolCatalog.DEFAULT.find("reminder.create")!!, ToolCatalog.DEFAULT.find("todo.list")!!)
    private val request = GenerationRequest("system", listOf(ChatTurn(Role.USER, "remind me to stretch at 5pm")), tools = tools)

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun sse(vararg events: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events.joinToString("") { "$it\n\n" })
    private fun calls(events: List<GenerationEvent>) = events.filterIsInstance<GenerationEvent.ToolCalls>().flatMap { it.calls }

    @Test fun openAiAssemblesStreamedToolCalls() = runBlocking<Unit> {
        server.enqueue(sse(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"reminder__create","arguments":""}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"text\":\"stretch\","}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"at\":\"2026-11-10T17:00\"}"}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_2","function":{"name":"todo__list","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
            "data: [DONE]",
        ))
        val p = OpenAiProvider("OpenAI", server.url("/v1").toString().trimEnd('/'), "gpt-test", { "sk" })
        val c = calls(p.generate(request).toList())
        assertEquals(2, c.size)
        assertEquals("reminder.create", c[0].name)
        assertEquals("call_1", c[0].id)
        assertEquals(mapOf("text" to "stretch", "at" to "2026-11-10T17:00"), c[0].args)
        assertEquals("todo.list", c[1].name)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"tools\""))
        assertTrue(body.contains("\"name\":\"reminder__create\""))
        assertTrue(body.contains("\"required\":[\"text\",\"at\"]"))
    }

    @Test fun openAiReplaysExchanges() = runBlocking<Unit> {
        server.enqueue(sse("""data: {"choices":[{"delta":{"content":"Done."},"finish_reason":"stop"}]}""", "data: [DONE]"))
        val call = com.melhive.zenith.intelligence.ToolCall("call_9", "todo.list", emptyMap(), kotlinx.serialization.json.JsonObject(emptyMap()))
        val ex = ToolExchange("", listOf(call), listOf(ToolCallResult("call_9", "todo.list", "No open tasks", true)))
        OpenAiProvider("OpenAI", server.url("/v1").toString().trimEnd('/'), "m", { "k" }).generate(request.copy(exchanges = listOf(ex))).toList()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"tool_calls\":[{\"id\":\"call_9\""), body)
        assertTrue(body.contains("\"role\":\"tool\",\"tool_call_id\":\"call_9\",\"content\":\"No open tasks\""), body)
    }

    @Test fun anthropicParsesToolUseBlocks() = runBlocking<Unit> {
        server.enqueue(sse(
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Setting it.\"}}",
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_A\",\"name\":\"reminder__create\",\"input\":{}}}",
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"text\\\": \\\"stretch\\\", \"}}",
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"at\\\": \\\"2026-11-10T17:00\\\"}\"}}",
            "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":1}",
            "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":30}}",
            "event: message_stop\ndata: {\"type\":\"message_stop\"}",
        ))
        val p = AnthropicProvider("claude-opus-5-5", { "key" }, server.url("").toString().trimEnd('/'))
        val events = p.generate(request).toList()
        val c = calls(events)
        assertEquals(1, c.size)
        assertEquals("toolu_A", c[0].id)
        assertEquals("reminder.create", c[0].name)
        assertEquals("stretch", c[0].args["text"])
        assertEquals("Setting it.", events.filterIsInstance<GenerationEvent.Delta>().joinToString("") { it.text })
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"input_schema\""))
    }

    @Test fun geminiParsesFunctionCallsAndEchoesSignature() = runBlocking<Unit> {
        server.enqueue(sse(
            """data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"reminder__create","args":{"text":"stretch","at":"2026-11-10T17:00"}},"thoughtSignature":"SIG123"}]},"finishReason":"STOP"}]}""",
        ))
        val p = GeminiProvider("gemini-test", { "key" }, server.url("").toString().trimEnd('/'))
        val c = calls(p.generate(request).toList())
        assertEquals(1, c.size)
        assertEquals("reminder.create", c[0].name)
        assertEquals("2026-11-10T17:00", c[0].args["at"])
        val first = server.takeRequest().body.readUtf8()
        assertTrue(first.contains("\"functionDeclarations\""))
        assertTrue(first.contains("\"type\":\"OBJECT\""))

        server.enqueue(sse("""data: {"candidates":[{"content":{"parts":[{"text":"Done"}]},"finishReason":"STOP"}]}"""))
        p.generate(request.copy(exchanges = listOf(ToolExchange("", c, listOf(ToolCallResult(c[0].id, "reminder.create", "Reminder set", true)))))).toList()
        val second = server.takeRequest().body.readUtf8()
        assertTrue(second.contains("\"thoughtSignature\":\"SIG123\""))
        assertTrue(second.contains("\"functionResponse\":{\"name\":\"reminder__create\",\"response\":{\"result\":\"Reminder set\"}}"))
    }
}
