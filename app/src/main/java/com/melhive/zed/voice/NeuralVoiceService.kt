package com.melhive.zed.voice

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.melhive.zed.core.voice.NeuralVoice
import com.melhive.zed.core.voice.VoiceCatalog
import com.melhive.zed.core.voice.VoicePack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs neural speech synthesis in its own process (`:voice`). The speech
 * engine is native code; if it ever crashes, only this process dies — the
 * ZED app stays open, notices the disconnect, and switches to the phone
 * voice. Talks to [IsolatedNeuralTts] with [Messenger] messages.
 */
class NeuralVoiceService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger
    private var engine: NeuralTextToSpeech? = null
    private var client: Messenger? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        com.melhive.zed.android.CrashReporter.recordNonFatal("voice process", e)
    })

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("zed-voice-ipc").apply { start() }
        messenger = Messenger(Handler(thread.looper) { msg -> handle(msg); true })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        engine?.release(); engine = null
        stopSelf()
        return false
    }

    override fun onDestroy() {
        engine?.release(); engine = null
        scope.cancel()
        thread.quitSafely()
        super.onDestroy()
    }

    private fun handle(msg: Message) {
        try {
            val data = msg.data
            when (msg.what) {
                MSG_LOAD -> {
                    client = msg.replyTo
                    engine?.release()
                    val pack = VoiceCatalog.pack(data.getString(K_PACK).orEmpty())
                    val voice = pack?.voices?.firstOrNull { it.sid == data.getInt(K_SID) }
                    if (pack == null || voice == null) { reply(MSG_READY, Bundle().apply { putBoolean(K_OK, false); putString(K_MESSAGE, "Unknown voice") }); return }
                    val e = NeuralTextToSpeech(pack, voice, File(data.getString(K_DIR).orEmpty()), data.getString(K_LANG).orEmpty())
                    e.setRate(data.getFloat(K_RATE, 1f)); e.setPitch(data.getFloat(K_PITCH, 1f))
                    e.appContext = applicationContext; e.speakerOnly = data.getBoolean(K_SPEAKER, false)
                    e.onRealTimeFactor = { rtf -> reply(MSG_STATS, Bundle().apply { putFloat(K_RTF, rtf) }) }
                    e.onLevels = { bins -> reply(MSG_LEVELS, Bundle().apply { putFloatArray(K_BINS, bins) }) }
                    engine = e
                    scope.launch { e.events.collect { ev -> forward(ev) } }
                    scope.launch {
                        val ok = e.awaitReady()
                        reply(MSG_READY, Bundle().apply {
                            putBoolean(K_OK, ok); putLong(K_MILLIS, e.loadMillis); putString(K_MESSAGE, e.loadError)
                        })
                    }
                }
                MSG_SPEAK -> engine?.speakWithId(data.getString(K_ID).orEmpty(), data.getString(K_TEXT).orEmpty(), data.getBoolean(K_FLUSH))
                MSG_STOP -> engine?.stop()
                MSG_RATE -> engine?.setRate(data.getFloat(K_RATE, 1f))
                MSG_PITCH -> engine?.setPitch(data.getFloat(K_PITCH, 1f))
                MSG_ROUTE -> engine?.speakerOnly = data.getBoolean(K_SPEAKER, false)
            }
        } catch (e: Throwable) {
            com.melhive.zed.android.CrashReporter.recordNonFatal("voice process message ${msg.what}", e)
        }
    }

    private fun forward(ev: TtsEvent) {
        val (type, id, message) = when (ev) {
            is TtsEvent.Started -> Triple(EV_STARTED, ev.utteranceId, null)
            is TtsEvent.Word -> Triple(EV_WORD, ev.utteranceId, null)
            is TtsEvent.Done -> Triple(EV_DONE, ev.utteranceId, null)
            is TtsEvent.Stopped -> Triple(EV_STOPPED, ev.utteranceId, null)
            is TtsEvent.Error -> Triple(EV_ERROR, ev.utteranceId, ev.message)
        }
        reply(MSG_EVENT, Bundle().apply { putInt(K_TYPE, type); putString(K_ID, id); putString(K_MESSAGE, message) })
    }

    private fun reply(what: Int, data: Bundle) {
        val m = Message.obtain(null, what).apply { this.data = data }
        try { client?.send(m) } catch (_: RemoteException) { /* app side went away */ }
    }

    companion object {
        const val MSG_LOAD = 1; const val MSG_SPEAK = 2; const val MSG_STOP = 3; const val MSG_RATE = 4; const val MSG_PITCH = 5; const val MSG_ROUTE = 6
        const val K_SPEAKER = "speaker"
        const val MSG_READY = 10; const val MSG_EVENT = 11; const val MSG_STATS = 12; const val MSG_LEVELS = 13
        const val EV_STARTED = 1; const val EV_WORD = 2; const val EV_DONE = 3; const val EV_STOPPED = 4; const val EV_ERROR = 5
        const val K_PACK = "pack"; const val K_SID = "sid"; const val K_DIR = "dir"; const val K_LANG = "lang"; const val K_RATE = "rate"
        const val K_PITCH = "pitch"; const val K_ID = "id"; const val K_TEXT = "text"; const val K_FLUSH = "flush"; const val K_OK = "ok"
        const val K_MILLIS = "millis"; const val K_MESSAGE = "message"; const val K_TYPE = "type"; const val K_RTF = "rtf"; const val K_BINS = "bins"
    }
}

/**
 * App-side handle for a neural voice running in [NeuralVoiceService]. Behaves
 * like any [TextToSpeechEngine]. If the voice process dies, every pending
 * utterance ends with an error, [crashed] becomes true and [onCrash] runs so
 * the caller can switch to the phone voice.
 */
class IsolatedNeuralTts(
    private val context: Context,
    private val pack: VoicePack,
    private val voice: NeuralVoice,
    private val dir: File,
    private val languageTag: String,
    private val onCrash: (String) -> Unit,
    /** Smoothed render time ÷ audio length, reported while speaking. */
    private val onSpeed: (Float) -> Unit = {},
) : TextToSpeechEngine {
    @Volatile var realTimeFactor: Float? = null
        private set
    private val _events = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<TtsEvent> = _events.asSharedFlow()
    private val ready = CompletableDeferred<Boolean>()
    private val counter = AtomicInteger()
    private val pending: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private val queued = mutableListOf<Message>()
    @Volatile private var service: Messenger? = null
    @Volatile private var rate = 1f
    @Volatile private var pitch = 1f
    @Volatile private var released = false
    @Volatile var crashed = false
        private set
    @Volatile var loadError: String? = null
        private set
    @Volatile var loadMillis: Long = 0
        private set

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { msg -> onMessage(msg); true })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val m = Messenger(binder)
            service = m
            send(NeuralVoiceService.MSG_LOAD, Bundle().apply {
                putString(NeuralVoiceService.K_PACK, pack.id); putInt(NeuralVoiceService.K_SID, voice.sid)
                putString(NeuralVoiceService.K_DIR, dir.absolutePath); putString(NeuralVoiceService.K_LANG, languageTag)
                putFloat(NeuralVoiceService.K_RATE, rate); putFloat(NeuralVoiceService.K_PITCH, pitch)
                putBoolean(NeuralVoiceService.K_SPEAKER, speakerOnly)
            }, replyTo = true)
            synchronized(queued) { queued.forEach { safeSend(m, it) }; queued.clear() }
        }

        override fun onServiceDisconnected(name: ComponentName?) = died("The voice engine stopped unexpectedly")
        override fun onBindingDied(name: ComponentName?) = died("The voice engine stopped unexpectedly")
        override fun onNullBinding(name: ComponentName?) = died("The voice engine could not start")
    }

    init {
        val bound = runCatching { context.bindService(Intent(context, NeuralVoiceService::class.java), connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) { loadError = "The voice engine could not start"; ready.complete(false) }
    }

    private fun died(reason: String) {
        if (released) return
        service = null
        crashed = true
        loadError = reason
        com.melhive.zed.android.CrashReporter.recordNonFatal("neural voice", IllegalStateException("$reason (${pack.name} · ${voice.name})"))
        val ids = synchronized(pending) { pending.toList().also { pending.clear() } }
        ids.forEach { _events.tryEmit(TtsEvent.Error(it, reason)) }
        if (!ready.isCompleted) ready.complete(false)
        runCatching { context.unbindService(connection) }
        released = true
        onCrash(reason)
    }

    private fun onMessage(msg: Message) {
        val d = msg.data
        when (msg.what) {
            NeuralVoiceService.MSG_READY -> {
                loadMillis = d.getLong(NeuralVoiceService.K_MILLIS)
                loadError = d.getString(NeuralVoiceService.K_MESSAGE)
                ready.complete(d.getBoolean(NeuralVoiceService.K_OK))
            }
            NeuralVoiceService.MSG_LEVELS -> d.getFloatArray(NeuralVoiceService.K_BINS)?.let { AudioLevels.set(it) }
            NeuralVoiceService.MSG_STATS -> {
                val r = d.getFloat(NeuralVoiceService.K_RTF)
                val smoothed = realTimeFactor?.let { it * 0.6f + r * 0.4f } ?: r
                realTimeFactor = smoothed
                onSpeed(smoothed)
            }
            NeuralVoiceService.MSG_EVENT -> {
                val id = d.getString(NeuralVoiceService.K_ID).orEmpty()
                when (d.getInt(NeuralVoiceService.K_TYPE)) {
                    NeuralVoiceService.EV_STARTED -> if (id in pending) _events.tryEmit(TtsEvent.Started(id))
                    NeuralVoiceService.EV_WORD -> if (id in pending) _events.tryEmit(TtsEvent.Word(id))
                    NeuralVoiceService.EV_DONE -> if (pending.remove(id)) _events.tryEmit(TtsEvent.Done(id))
                    NeuralVoiceService.EV_STOPPED -> if (pending.remove(id)) _events.tryEmit(TtsEvent.Stopped(id))
                    NeuralVoiceService.EV_ERROR -> if (pending.remove(id)) _events.tryEmit(TtsEvent.Error(id, d.getString(NeuralVoiceService.K_MESSAGE) ?: "Speech failed"))
                }
            }
        }
    }

    private fun send(what: Int, data: Bundle, replyTo: Boolean = false) {
        val m = Message.obtain(null, what).apply { this.data = data; if (replyTo) this.replyTo = incoming }
        val s = service
        if (s == null) synchronized(queued) { queued += m } else safeSend(s, m)
    }

    private fun safeSend(s: Messenger, m: Message) {
        try { s.send(m) } catch (_: RemoteException) { died("The voice engine stopped unexpectedly") }
    }

    override suspend fun awaitReady(): Boolean = ready.await()

    override fun speak(text: String, flush: Boolean): String? {
        if (released || crashed) return null
        if (SpeechText.clean(text).isBlank()) return null
        if (flush) stop()
        val id = "neural-${counter.incrementAndGet()}"
        pending += id
        send(NeuralVoiceService.MSG_SPEAK, Bundle().apply {
            putString(NeuralVoiceService.K_ID, id); putString(NeuralVoiceService.K_TEXT, text); putBoolean(NeuralVoiceService.K_FLUSH, false)
        })
        return id
    }

    override fun stop() {
        val ids = synchronized(pending) { pending.toList().also { pending.clear() } }
        ids.forEach { _events.tryEmit(TtsEvent.Stopped(it)) }
        if (!released) send(NeuralVoiceService.MSG_STOP, Bundle())
    }

    override fun setRate(rate: Float) { this.rate = rate; if (!released) send(NeuralVoiceService.MSG_RATE, Bundle().apply { putFloat(NeuralVoiceService.K_RATE, rate) }) }
    override fun setPitch(pitch: Float) { this.pitch = pitch; if (!released) send(NeuralVoiceService.MSG_PITCH, Bundle().apply { putFloat(NeuralVoiceService.K_PITCH, pitch) }) }
    @Volatile private var speakerOnly = false
    /** Earphone-mic mode: ZED's voice plays from the phone's loudspeaker. */
    fun setSpeakerOnly(on: Boolean) { speakerOnly = on; if (!released) send(NeuralVoiceService.MSG_ROUTE, Bundle().apply { putBoolean(NeuralVoiceService.K_SPEAKER, on) }) }
    override fun isSpeaking(): Boolean = pending.isNotEmpty()

    override fun info(): VoiceInfo? = if (!ready.isCompleted) null else VoiceInfo(
        engine = "ZED neural · ${pack.name}" + if (loadError != null) " (failed: $loadError)" else " (loaded in $loadMillis ms, separate process)",
        language = if (voice.accent == "British") "en-GB" else if (voice.accent == "American") "en-US" else VoiceCatalog.supertonicLanguage(languageTag),
        offlineVoiceAvailable = true,
        voiceName = "${voice.name} (${voice.gender.label}, ${voice.accent})",
    )

    override fun release() {
        if (released) return
        stop()
        released = true
        if (!ready.isCompleted) ready.complete(false)
        runCatching { context.unbindService(connection) }
    }
}
