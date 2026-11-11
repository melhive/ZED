package com.melhive.zenith.core.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Converts ZED tool specs into provider function-calling declarations and
 * converts model-supplied arguments back into the string map the
 * [ToolValidator] checks. Model output stays untrusted: arguments still go
 * through validation, permission checks and confirmation.
 */
object NativeToolSchema {
    /** OpenAI and Anthropic allow only [a-zA-Z0-9_-] in function names, so dots become "__". */
    fun wireName(tool: String): String = tool.replace(".", "__")
    fun toolName(wire: String): String = wire.replace("__", ".")

    /** Standard JSON Schema (OpenAI, Anthropic). */
    fun jsonSchema(spec: ToolSpec): JsonObject = schema(spec, upperCaseTypes = false)

    /** Gemini's OpenAPI-style schema (upper-case type names). */
    fun geminiSchema(spec: ToolSpec): JsonObject = schema(spec, upperCaseTypes = true)

    fun description(spec: ToolSpec): String = buildString {
        append(spec.description)
        when (spec.risk) {
            RiskLevel.DESTRUCTIVE -> append(" The user is always asked to confirm.")
            RiskLevel.MODIFY -> append(" The app may ask the user to confirm.")
            RiskLevel.READ_ONLY -> Unit
        }
        if (spec.background) append(" Runs as a background task.")
    }

    private fun schema(spec: ToolSpec, upperCaseTypes: Boolean): JsonObject = buildJsonObject {
        fun t(name: String) = if (upperCaseTypes) name.uppercase() else name
        put("type", t("object"))
        putJsonObject("properties") {
            for (p in spec.params) putJsonObject(p.name) {
                when (p.type) {
                    ParamType.INT -> put("type", t("integer"))
                    ParamType.BOOLEAN -> put("type", t("boolean"))
                    ParamType.ENUM -> { put("type", t("string")); putJsonArray("enum") { p.enumValues.forEach { add(JsonPrimitive(it)) } } }
                    else -> put("type", t("string"))
                }
                val hint = when (p.type) {
                    ParamType.DATETIME -> " (ISO-8601 local date-time, e.g. 2026-01-31T09:30)"
                    ParamType.PATH -> " (relative to a folder the user granted)"
                    ParamType.INT -> listOfNotNull(p.min?.let { " min $it" }, p.max?.let { " max $it" }).joinToString(",")
                    else -> ""
                }
                put("description", p.description + hint)
            }
        }
        putJsonArray("required") { spec.params.filter { it.required }.forEach { add(JsonPrimitive(it.name)) } }
    }

    /** Model arguments → string map for validation. Nested values are passed as JSON text. */
    fun argsToStrings(args: JsonObject?): Map<String, String> = args.orEmpty().mapNotNull { (k, v) ->
        when (v) {
            is JsonNull -> null
            is JsonPrimitive -> v.contentOrNull?.let { k to it }
            is JsonArray, is JsonObject -> k to v.toString()
        }
    }.toMap()

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
}
