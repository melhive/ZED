package com.melhive.zenith.intelligence.providers

import com.melhive.zenith.core.security.SecretRedactor
import com.melhive.zenith.intelligence.GenerationEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

internal val ProviderJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
internal val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
internal fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun describeHttpError(code: Int, body: String): GenerationEvent.Error {
        val detail = runCatching {
            val root = ProviderJson.parseToJsonElement(body).jsonObject
            val err = root["error"]
            when (err) {
                is JsonObject -> err["message"].str()
                is JsonPrimitive -> err.contentOrNull
                else -> root["message"].str()
            }
        }.getOrNull() ?: body.take(300)
        val hint = when (code) {
            400 -> "Bad request"
            401, 403 -> "Authentication failed — check the API key"
            404 -> "Model or endpoint not found — check the model name"
            408, 504 -> "Request timed out"
            429 -> "Rate limited or quota exceeded"
            in 500..599 -> "Provider server error"
            else -> "HTTP $code"
        }
        return GenerationEvent.Error(SecretRedactor.redact("$hint: $detail"), retryable = code == 429 || code >= 500 || code == 408)
    }

    /**
     * POSTs [body] and parses the response as Server-Sent Events, calling
     * [onEvent] with (eventName, data) for each event. Cancelling the flow
     * cancels the HTTP call immediately.
     */
    fun sse(
        url: String,
        headers: Map<String, String>,
        body: String,
        onEvent: suspend ProducerScope<GenerationEvent>.(event: String?, data: String) -> Boolean,
    ): Flow<GenerationEvent> = callbackFlow {
        val builder = Request.Builder().url(url).post(body.toRequestBody(JSON_MEDIA)).header("Accept", "text/event-stream")
        headers.forEach { (k, v) -> builder.header(k, v) }
        val call = client.newCall(builder.build())
        val job = launch(Dispatchers.IO) {
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        send(describeHttpError(resp.code, resp.body?.string().orEmpty()))
                        return@use
                    }
                    val source = resp.body?.source() ?: run { send(GenerationEvent.Error("Empty response")); return@use }
                    var eventName: String? = null
                    val data = StringBuilder()
                    var keepGoing = true
                    while (keepGoing) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.isEmpty() -> {
                                if (data.isNotEmpty()) keepGoing = onEvent(eventName, data.toString())
                                eventName = null; data.setLength(0)
                            }
                            line.startsWith(":") -> Unit
                            line.startsWith("event:") -> eventName = line.substringAfter("event:").trim()
                            line.startsWith("data:") -> {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.substringAfter("data:").removePrefix(" "))
                            }
                        }
                    }
                    if (keepGoing && data.isNotEmpty()) onEvent(eventName, data.toString())
                }
            } catch (e: IOException) {
                if (!call.isCanceled()) send(GenerationEvent.Error("Network error: ${e.message ?: e.javaClass.simpleName}", retryable = true))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                send(GenerationEvent.Error(SecretRedactor.redact("Unexpected error: ${e.message}")))
            } finally {
                close()
            }
        }
        awaitClose {
            call.cancel()
            job.cancel()
        }
    }.flowOn(Dispatchers.IO)

    /** Simple GET returning parsed JSON, used for model listing and key verification. */
    fun getJson(url: String, headers: Map<String, String>): Result<JsonObject> = runCatching {
        val b = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException(describeHttpError(resp.code, text).message)
            ProviderJson.parseToJsonElement(text).jsonObject
        }
    }
}

internal fun jsonString(s: String): String = JsonPrimitive(s).toString()
internal fun JsonObject.path(vararg keys: String): JsonElement? {
    var cur: JsonElement? = this
    for (k in keys) cur = (cur as? JsonObject)?.get(k) ?: return null
    return cur
}
internal val JsonElement.primitiveOrNull: String? get() = runCatching { jsonPrimitive.contentOrNull }.getOrNull()
