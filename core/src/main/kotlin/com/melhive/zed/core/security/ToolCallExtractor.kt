package com.melhive.zed.core.security

import com.melhive.zed.core.tools.ToolRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Extracts structured tool requests from model output.
 *
 * Models are instructed to emit tool calls as fenced blocks:
 * ```zed-tool
 * {"tool": "file.create", "args": {"path": "notes/a.txt", "content": "hi"}}
 * ```
 * Anything that does not match this exact shape is ignored. Nested objects or
 * arrays in args are rejected. The output is still untrusted: callers must run
 * every request through ToolValidator and the ToolManager.
 */
object ToolCallExtractor {
    const val FENCE = "zed-tool"
    private val BLOCK = Regex("```$FENCE\\s*\\n(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }
    const val MAX_CALLS = 3

    data class Extraction(val visibleText: String, val requests: List<ToolRequest>, val errors: List<String>)

    fun extract(output: String, origin: ToolRequest.Origin): Extraction {
        val requests = mutableListOf<ToolRequest>()
        val errors = mutableListOf<String>()
        for (m in BLOCK.findAll(output)) {
            if (requests.size >= MAX_CALLS) { errors += "Too many tool calls; extra calls ignored"; break }
            when (val r = parse(m.groupValues[1].trim(), origin)) {
                is Parsed.Ok -> requests += r.request
                is Parsed.Error -> errors += r.message
            }
        }
        val visible = BLOCK.replace(output, "").trim()
        return Extraction(visible, requests, errors)
    }

    private sealed interface Parsed {
        data class Ok(val request: ToolRequest) : Parsed
        data class Error(val message: String) : Parsed
    }

    private fun parse(body: String, origin: ToolRequest.Origin): Parsed {
        if (body.length > 120_000) return Parsed.Error("Tool call too large")
        val element: JsonElement = try { json.parseToJsonElement(body) } catch (e: Exception) {
            return Parsed.Error("Malformed tool call JSON")
        }
        val obj = element as? JsonObject ?: return Parsed.Error("Tool call must be a JSON object")
        if (obj.keys - setOf("tool", "args") != emptySet<String>()) return Parsed.Error("Unexpected keys in tool call")
        val tool = (obj["tool"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return Parsed.Error("Tool call missing 'tool'")
        val argsEl = obj["args"]
        val args = LinkedHashMap<String, String>()
        if (argsEl != null && argsEl != JsonNull) {
            val argsObj = argsEl as? JsonObject ?: return Parsed.Error("'args' must be an object")
            for ((k, v) in argsObj) {
                if (v == JsonNull) continue
                val prim = v as? JsonPrimitive ?: return Parsed.Error("Argument '$k' must be a scalar")
                args[k] = prim.content
            }
        }
        return Parsed.Ok(ToolRequest(tool, args, origin))
    }
}
