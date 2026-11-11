package com.melhive.zenith.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import com.melhive.zenith.core.tools.NativeToolSchema
import com.melhive.zenith.core.tools.ToolSpec
import com.melhive.zenith.intelligence.ToolCall
import com.melhive.zenith.intelligence.ToolCallResult
import com.melhive.zenith.intelligence.providers.Http
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/** Everything a real-time session needs, resolved from settings when LIVE starts. */
data class RealtimeConfig(
    val apiKey: String,
    val model: String,
    val voiceName: String,
    val systemPrompt: String,
    val tools: List<ToolSpec>,
    val webSearch: Boolean,
    val executeTool: suspend (ToolCall) -> ToolCallResult,
)

/**
 * Gemini Live API session (BidiGenerateContent over WebSocket): the model hears
 * the microphone stream directly and answers in speech, with server-side voice
 * activity detection and interruption. Tool calls run through ZED's tool
 * pipeline (validation, permissions, confirmation) and results are sent back.
 *
 * Echo: on the loudspeaker the microphone is muted while the model speaks (the
 * model would otherwise hear itself); tap the orb to interrupt. With
 * headphones the session is full duplex and you can talk over it.
 */
class GeminiLiveSession(
    private val context: Context,
    private val config: RealtimeConfig,
    private val events: Listener,
) {
    interface Listener {
        fun onConnected()
        fun onListening()
        fun onUserSpeech(text: String, final: Boolean)
        fun onAssistantSpeech(text: String, final: Boolean)
        fun onSpeaking(pulse: Boolean)
        fun onTool(name: String)
        fun onMicLevel(level: Float)
        /** Fatal problem; [beforeSetup] is true when the session never started (fall back to standard LIVE). */
        fun onFailed(message: String, beforeSetup: Boolean)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
        com.melhive.zenith.android.CrashReporter.recordNonFatal("gemini live", e)
    })
    private val audioManager = context.getSystemService(AudioManager::class.java)

    @Volatile private var ws: WebSocket? = null
    @Volatile private var setupDone = false
    @Volatile private var everConnected = false
    @Volatile private var closed = false
    @Volatile var micMuted = false
        private set
    @Volatile var speakerOn = true
    @Volatile private var resumeHandle: String? = null
    private var resumes = 0
    @Volatile private var discardTurn = false
    @Volatile private var playingUntil = 0L

    private var recordThread: Thread? = null
    private val playback = Channel<ByteArray>(Channel.UNLIMITED)
    private var track: AudioTrack? = null
    private val trackLock = Any()
    private val userText = StringBuilder()
    private val modelText = StringBuilder()

    fun start() {
        connect()
        scope.launch { for (pcm in playback) play(pcm) }
    }

    private fun connect() {
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${config.apiKey}"
        val client = Http.client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(setupMessage()) }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (!closed) onDropped("Gemini Live closed the session" + if (reason.isNotBlank()) ": $reason" else " (code $code)")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closed) onDropped("Connection to Gemini Live failed: ${response?.message?.takeIf { it.isNotBlank() } ?: t.message ?: t.javaClass.simpleName}")
            }
        })
    }

    private fun onDropped(message: String) {
        val wasSetup = setupDone
        setupDone = false
        stopMic()
        // Google rotates connections (~10 min); resume transparently when we have a handle.
        if (wasSetup && resumeHandle != null && !closed && resumes++ < 5) { connect(); return }
        events.onFailed(com.melhive.zenith.core.security.SecretRedactor.redact(message.replace(config.apiKey, "•••")), beforeSetup = !everConnected)
    }

    private fun setupMessage(): String = buildJsonObject {
        putJsonObject("setup") {
            put("model", "models/" + config.model.removePrefix("models/"))
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) }
                putJsonObject("speechConfig") { putJsonObject("voiceConfig") { putJsonObject("prebuiltVoiceConfig") { put("voiceName", config.voiceName) } } }
            }
            putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", config.systemPrompt) } } }
            putJsonArray("tools") {
                if (config.tools.isNotEmpty()) addJsonObject {
                    putJsonArray("functionDeclarations") {
                        for (t in config.tools) addJsonObject {
                            put("name", NativeToolSchema.wireName(t.name))
                            put("description", NativeToolSchema.description(t))
                            put("parameters", NativeToolSchema.geminiSchema(t))
                        }
                    }
                }
                if (config.webSearch) addJsonObject { putJsonObject("googleSearch") {} }
            }
            putJsonObject("inputAudioTranscription") {}
            putJsonObject("outputAudioTranscription") {}
            putJsonObject("sessionResumption") { resumeHandle?.let { put("handle", it) } }
            putJsonObject("contextWindowCompression") { putJsonObject("slidingWindow") {} }
        }
    }.toString()

    private fun handle(text: String) {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        if ("setupComplete" in root) {
            setupDone = true
            startMic()
            if (!everConnected) { everConnected = true; events.onConnected(); events.onListening() }
        }
        (root["sessionResumptionUpdate"] as? JsonObject)?.let { u ->
            val h = u.str("newHandle")
            if (u.str("resumable") != "false" && !h.isNullOrBlank()) resumeHandle = h
        }
        (root["serverContent"] as? JsonObject)?.let { onServerContent(it) }
        (root["toolCall"] as? JsonObject)?.let { onToolCall(it) }
        (root["goAway"] as? JsonObject)?.let { /* the close that follows triggers a resume */ }
    }

    private fun onServerContent(sc: JsonObject) {
        (sc["inputTranscription"] as? JsonObject)?.str("text")?.let { t ->
            userText.append(t)
            events.onUserSpeech(userText.toString().trim(), final = false)
        }
        if (sc.str("interrupted") == "true") {
            flushPlayback()
            finishModelText()
            discardTurn = false
        }
        (sc["modelTurn"] as? JsonObject)?.let { turn ->
            if (userText.isNotBlank()) { events.onUserSpeech(userText.toString().trim(), final = true); userText.setLength(0) }
            (turn["parts"] as? JsonArray)?.forEach { part ->
                val data = ((part as? JsonObject)?.get("inlineData") as? JsonObject)?.str("data") ?: return@forEach
                if (!discardTurn) playback.trySend(Base64.decode(data, Base64.DEFAULT))
            }
        }
        (sc["outputTranscription"] as? JsonObject)?.str("text")?.let { t ->
            if (userText.isNotBlank()) { events.onUserSpeech(userText.toString().trim(), final = true); userText.setLength(0) }
            modelText.append(t)
            if (!discardTurn) events.onAssistantSpeech(modelText.toString().trim(), final = false)
        }
        if (sc.str("turnComplete") == "true") {
            finishModelText()
            discardTurn = false
            scope.launch {
                // Back to listening once the last audio has played.
                while (System.currentTimeMillis() < playingUntil && !closed) kotlinx.coroutines.delay(50)
                if (!closed) events.onListening()
            }
        }
    }

    private fun finishModelText() {
        if (userText.isNotBlank()) { events.onUserSpeech(userText.toString().trim(), final = true); userText.setLength(0) }
        if (modelText.isNotBlank()) { events.onAssistantSpeech(modelText.toString().trim(), final = true); modelText.setLength(0) }
    }

    private fun onToolCall(tc: JsonObject) {
        val calls = (tc["functionCalls"] as? JsonArray).orEmpty().mapNotNull { el ->
            val fc = el as? JsonObject ?: return@mapNotNull null
            val args = fc["args"] as? JsonObject ?: JsonObject(emptyMap())
            ToolCall(fc.str("id") ?: "live-${System.nanoTime()}", NativeToolSchema.toolName(fc.str("name").orEmpty()), NativeToolSchema.argsToStrings(args), args)
        }
        if (calls.isEmpty()) return
        scope.launch {
            val results = calls.map { call ->
                events.onTool(call.name)
                runCatching { config.executeTool(call) }.getOrElse { ToolCallResult(call.id, call.name, "Tool failed: ${it.message}", false) }
            }
            val msg = buildJsonObject {
                putJsonObject("toolResponse") {
                    putJsonArray("functionResponses") {
                        for (r in results) addJsonObject {
                            put("id", r.callId)
                            put("name", NativeToolSchema.wireName(r.name))
                            putJsonObject("response") { put(if (r.ok) "result" else "error", r.content.take(8000)) }
                        }
                    }
                }
            }.toString()
            ws?.send(msg)
        }
    }

    // ---- microphone ----

    @SuppressLint("MissingPermission") // LIVE only starts after the microphone permission is granted.
    private fun startMic() {
        if (recordThread != null) return
        val rate = 16_000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate)) }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { rec?.release(); events.onFailed("The microphone is unavailable", beforeSetup = false); return }
        if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true }
        if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(rec.audioSessionId)?.enabled = true }
        val t = Thread({
            val buf = ShortArray(rate / 10) // 100 ms
            val silence = ByteArray(buf.size * 2)
            try {
                rec.startRecording()
                while (!Thread.currentThread().isInterrupted && !closed) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) { val v = buf[i] / 32768.0; sum += v * v }
                    val level = sqrt(sum / n).toFloat()
                    // Half duplex on the loudspeaker: while ZED talks, send silence so it doesn't hear itself.
                    val gate = micMuted || (!headphones() && System.currentTimeMillis() < playingUntil + 350)
                    events.onMicLevel(if (gate) 0f else (level * 6).coerceIn(0f, 1f))
                    val bytes = if (gate) silence.copyOf(n * 2) else ByteArray(n * 2).also { b ->
                        for (i in 0 until n) { b[2 * i] = (buf[i].toInt() and 0xFF).toByte(); b[2 * i + 1] = (buf[i].toInt() shr 8).toByte() }
                    }
                    if (setupDone) ws?.send(audioMessage(bytes))
                }
            } catch (e: Exception) {
                com.melhive.zenith.android.CrashReporter.recordNonFatal("gemini live mic", e)
            } finally {
                runCatching { rec.stop() }; rec.release()
            }
        }, "zenith-live-mic")
        recordThread = t
        t.start()
    }

    private fun stopMic() { recordThread?.interrupt(); recordThread = null }

    private fun audioMessage(pcm: ByteArray): String =
        "{\"realtimeInput\":{\"audio\":{\"data\":\"" + Base64.encodeToString(pcm, Base64.NO_WRAP) + "\",\"mimeType\":\"audio/pcm;rate=16000\"}}}"

    private fun headphones(): Boolean = runCatching {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type in setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET, 26 /* BLE headset */, 27 /* BLE speaker */,
            )
        }
    }.getOrDefault(false)

    // ---- playback (24 kHz, 16-bit mono PCM) ----

    private fun ensureTrack(): AudioTrack = synchronized(trackLock) {
        track ?: AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(OUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), OUT_RATE / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { track = it; it.play() }
    }

    private fun play(pcm: ByteArray) {
        if (closed || !speakerOn || discardTurn) return
        val t = ensureTrack()
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
        val now = System.currentTimeMillis()
        val ms = pcm.size / 2 * 1000L / OUT_RATE
        playingUntil = maxOf(playingUntil, now) + ms
        var sum = 0.0
        var i = 0
        while (i + 1 < pcm.size) { val v = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort() / 32768.0; sum += v * v; i += 2 }
        events.onSpeaking(pulse = sqrt(sum / maxOf(1, pcm.size / 2)) > 0.03)
        t.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
    }

    private fun flushPlayback() {
        while (playback.tryReceive().isSuccess) Unit
        synchronized(trackLock) { track?.let { runCatching { it.pause(); it.flush() } } }
        playingUntil = 0
    }

    // ---- controls ----

    /** Stops ZED talking; the rest of this answer is skipped. */
    fun interrupt() {
        discardTurn = true
        flushPlayback()
        finishModelText()
        events.onListening()
    }

    fun setMuted(muted: Boolean) { micMuted = muted }

    fun sendText(text: String) {
        if (!setupDone) return
        interrupt()
        discardTurn = false
        events.onUserSpeech(text, final = true)
        ws?.send(buildJsonObject { putJsonObject("realtimeInput") { put("text", text) } }.toString())
    }

    /** Shows the model a photo (as a video frame) in the live session. */
    fun sendImage(image: com.melhive.zenith.intelligence.ImageInput) {
        if (!setupDone) return
        ws?.send(buildJsonObject { putJsonObject("realtimeInput") { putJsonObject("video") { put("data", image.base64); put("mimeType", image.mimeType) } } }.toString())
    }

    fun close() {
        if (closed) return
        closed = true
        stopMic()
        runCatching { ws?.close(1000, "bye") }
        flushPlayback()
        playback.close()
        synchronized(trackLock) { track?.let { runCatching { it.stop() }; it.release() }; track = null }
        scope.cancel()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

    companion object {
        const val OUT_RATE = 24_000
        const val DEFAULT_MODEL = "gemini-3.1-flash-live-preview"
        val VOICES = listOf("Charon", "Puck", "Kore", "Fenrir", "Aoede", "Leda", "Orus", "Zephyr")
    }
}
