package com.melhive.zed.android

import com.melhive.zed.intelligence.providers.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Minimal Home Assistant REST client (long-lived access token). */
class HomeAssistant(baseUrl: String, private val token: String) {
    private val base = baseUrl.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    data class Entity(val id: String, val name: String, val state: String, val attributes: JsonObject) {
        val domain: String get() = id.substringBefore('.')
        fun attr(k: String) = (attributes[k] as? JsonPrimitive)?.contentOrNull
    }

    private fun request(path: String) = Request.Builder().url("$base$path").header("Authorization", "Bearer $token")

    suspend fun states(): List<Entity> = withContext(Dispatchers.IO) {
        Http.client.newCall(request("/api/states").get().build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (r.code == 401) throw IOException("Home Assistant rejected the token (401). Create a new long-lived token in your HA profile.")
            if (!r.isSuccessful) throw IOException("Home Assistant error HTTP ${r.code}")
            (json.parseToJsonElement(body) as JsonArray).mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = (o["entity_id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val attrs = o["attributes"] as? JsonObject ?: JsonObject(emptyMap())
                Entity(id, (attrs["friendly_name"] as? JsonPrimitive)?.contentOrNull ?: id, (o["state"] as? JsonPrimitive)?.contentOrNull.orEmpty(), attrs)
            }
        }
    }

    suspend fun call(domain: String, service: String, entityIds: List<String>, extra: Map<String, Any> = emptyMap()) = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonArray("entity_id") { entityIds.forEach { add(JsonPrimitive(it)) } }
            extra.forEach { (k, v) -> when (v) { is Number -> put(k, v); is Boolean -> put(k, v); else -> put(k, v.toString()) } }
        }.toString()
        Http.client.newCall(request("/api/services/$domain/$service").post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Home Assistant refused $domain.$service (HTTP ${r.code}): ${r.body?.string().orEmpty().take(200)}")
        }
    }

    suspend fun ping(): String = withContext(Dispatchers.IO) {
        Http.client.newCall(request("/api/").get().build()).execute().use { r ->
            if (r.code == 401) throw IOException("Token rejected (401).")
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            (json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject["message"] as? JsonPrimitive)?.contentOrNull ?: "Connected"
        }
    }

    companion object {
        private val CONTROL_DOMAINS = setOf("light", "switch", "fan", "media_player", "climate", "input_boolean", "scene", "script")
        private val SECURE_DOMAINS = setOf("lock", "cover", "alarm_control_panel")
        private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

        /** Entities matching a spoken target. Plural targets ("lights") return every match. */
        fun match(all: List<Entity>, target: String, secure: Boolean): List<Entity> {
            val domains = if (secure) SECURE_DOMAINS else CONTROL_DOMAINS
            val t = norm(target)
            val plural = t.endsWith("lights") || t.endsWith("lamps") || t.endsWith("switches") || t.endsWith("fans") || t.startsWith("all ")
            val words = t.removePrefix("all ").split(' ').map { it.removeSuffix("s") }.filter { it.isNotEmpty() && it != "the" }
            val wanted = when {
                t.contains("thermostat") || t.contains("temperature") || t.contains("ac") || t.contains("aircon") || t.contains("heating") -> setOf("climate")
                t.contains("light") || t.contains("lamp") -> setOf("light")
                else -> domains
            }
            val candidates = all.filter { it.domain in domains && it.domain in wanted }
            val exact = candidates.filter { norm(it.name) == t || it.id.substringAfter('.') == t.replace(' ', '_') }
            if (exact.isNotEmpty()) return exact
            val scored = candidates.map { e ->
                val n = norm(e.name) + " " + e.id.substringAfter('.').replace('_', ' ')
                e to words.count { w -> n.split(' ').any { it.removeSuffix("s") == w } }
            }.filter { it.second > 0 }
            if (scored.isEmpty()) return if (wanted == setOf("climate")) candidates.take(1) else emptyList()
            val best = scored.maxOf { it.second }
            val top = scored.filter { it.second == best }.map { it.first }
            return if (plural) top else top.take(if (top.size == 1) 1 else top.size)
        }

        fun describe(e: Entity): String = buildString {
            append(e.name).append(": ").append(e.state)
            e.attr("unit_of_measurement")?.let { append(" ").append(it) }
            if (e.domain == "light" && e.state == "on") e.attr("brightness")?.toIntOrNull()?.let { append(" (${it * 100 / 255}%)") }
            if (e.domain == "climate") { e.attr("current_temperature")?.let { append(", now $it°") }; e.attr("temperature")?.let { append(", set to $it°") } }
        }
    }
}
