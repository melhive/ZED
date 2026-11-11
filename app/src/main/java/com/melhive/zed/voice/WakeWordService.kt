package com.melhive.zed.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.melhive.zed.R
import com.melhive.zed.ZedApp
import com.melhive.zed.android.Notifications
import com.melhive.zed.assist.ZedLaunch
import com.melhive.zed.core.voice.VoiceCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * "Hey Zed": listens on-device for the wake word only (sherpa-onnx keyword
 * spotting; no audio leaves the phone), as a foreground service with a visible
 * notification. On detection it opens ZED LIVE — or, when Android doesn't
 * allow opening the app from the background, answers hands-free: it listens
 * for the request, speaks the answer and posts it as a notification.
 * Paused while ZED LIVE has the microphone.
 */
class WakeWordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        com.melhive.zed.android.CrashReporter.recordNonFatal("wake word", e)
    })
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { (application as ZedApp).container.settings.setWakeWordEnabled(false) }
            stopSelf(); return START_NOT_STICKY
        }
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION_ID, notification("Listening for “Hey Zed”"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION_ID, notification("Listening for “Hey Zed”"))
        }
        if (ok.isFailure) { com.melhive.zed.android.CrashReporter.recordNonFatal("wake word start", ok.exceptionOrNull()!!); stopSelf(); return START_NOT_STICKY }
        if (!running) start()
        return START_STICKY
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, Notifications.CHANNEL_TASKS)
            .setSmallIcon(R.drawable.ic_stat_zed)
            .setContentTitle("ZED")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(ZedLaunch.pending(this, ZedLaunch.ROUTE_LIVE, 720))
            .addAction(0, "Stop listening", stop)
            .build()
    }

    @SuppressLint("MissingPermission") // started only after the microphone permission is granted
    private fun start() {
        val dir = File(File(filesDir, "voices"), VoiceCatalog.WAKE_WORD.id)
        if (!File(dir, ".installed").exists() || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { stopSelf(); return }
        running = true
        val sensitivity = runCatching { kotlinx.coroutines.runBlocking { (application as ZedApp).container.settings.current().wakeSensitivity } }.getOrDefault(1)
        thread = Thread({ listenLoop(dir, sensitivity) }, "zed-wake").also { it.start() }
    }

    @SuppressLint("MissingPermission")
    private fun listenLoop(dir: File, sensitivity: Int) {
        val keywordsFile = File(cacheDir, "wake-keywords.txt").apply { writeText(KEYWORDS + "\n") }
        val (threshold, score) = when (sensitivity) { 0 -> THRESHOLD_LOW; 2 -> THRESHOLD_HIGH; else -> THRESHOLD_NORMAL }
        val kws = try {
            KeywordSpotter(config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        File(dir, "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx").path,
                        File(dir, "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx").path,
                        File(dir, "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx").path,
                    ),
                    tokens = File(dir, "tokens.txt").path, numThreads = 1, modelType = "zipformer2",
                ),
                keywordsFile = keywordsFile.path, keywordsThreshold = threshold, keywordsScore = score,
            ))
        } catch (e: Throwable) {
            com.melhive.zed.android.CrashReporter.recordNonFatal("wake word model", e)
            running = false; stopSelf(); return
        }
        val stream = kws.createStream("")
        val buf = ShortArray(RATE / 10)
        val floats = FloatArray(buf.size)
        try {
            while (running) {
                // Hand the microphone to LIVE while it is open.
                if (paused.value) { Thread.sleep(300); continue }
                val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE))
                if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); Thread.sleep(2000); continue }
                var woke = false
                try {
                    rec.startRecording()
                    while (running && !paused.value && !woke) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n <= 0) { Thread.sleep(50); continue }
                        for (i in 0 until n) floats[i] = buf[i] / 32768f
                        stream.acceptWaveform(if (n == buf.size) floats else floats.copyOf(n), RATE)
                        while (kws.isReady(stream)) {
                            kws.decode(stream)
                            if (kws.getResult(stream).keyword.isNotBlank()) { kws.reset(stream); woke = true; break }
                        }
                    }
                } finally { runCatching { rec.stop() }; rec.release() }
                if (woke) runCatching { kotlinx.coroutines.runBlocking { onWake() } }.onFailure { com.melhive.zed.android.CrashReporter.recordNonFatal("wake", it) }
            }
        } finally {
            runCatching { stream.release() }; runCatching { kws.release() }
        }
    }

    /** Opens LIVE when allowed; otherwise handles one request hands-free. */
    private suspend fun onWake() {
        val c = (application as ZedApp).container
        val style = ZedSounds.Style.parse(c.settings.current().liveSound)
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (foreground || Settings.canDrawOverlays(this)) {
            // The floating orb over whatever is on screen (not the full app).
            val opened = runCatching { startActivity(ZedLaunch.overlayIntent(this)) }.isSuccess
            if (opened) {
                // LIVE takes the microphone; wait until it is closed again.
                delay(1500)
                withTimeoutOrNull(30 * 60_000L) { paused.first { !it } }
                return
            }
        }
        ZedSounds.play(this, style, start = true)
        delay(450)
        val heard = withContext(Dispatchers.Main) { listenOnce() }
        if (heard.isNullOrBlank()) { ZedSounds.play(this, style, start = false); return }
        Notifications.post(this, Notifications.CHANNEL_TASKS, NOTIFICATION_ID + 1, "You: $heard", "Thinking…", "live")
        val conv = c.conversations.create("Hey Zed")
        val reply = c.orchestrator.respond(conv, heard, voice = true)
        Notifications.post(this, Notifications.CHANNEL_TASKS, NOTIFICATION_ID + 1, "ZED", reply.text, "chat")
        if (c.settings.current().voiceResponses && c.voice.awaitReady()) {
            c.voice.speak(reply.text, flush = true)
            delay(500)
            withTimeoutOrNull(120_000) { while (c.voice.isSpeaking()) delay(200) }
        }
    }

    /** One utterance through the phone's recognizer (main thread). */
    private suspend fun listenOnce(): String? {
        val stt = AndroidSpeechToText(this)
        val result = CompletableDeferred<String?>()
        val job = scope.launch {
            stt.events.collect { e ->
                when (e) {
                    is SttEvent.Final -> result.complete(e.text)
                    is SttEvent.Error -> result.complete(null)
                    else -> Unit
                }
            }
        }
        stt.start()
        val text = withTimeoutOrNull(12_000) { result.await() }
        job.cancel(); stt.cancel(); stt.release()
        return text
    }

    override fun onDestroy() {
        running = false
        thread?.interrupt(); thread = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val RATE = 16_000
        private const val NOTIFICATION_ID = 65_000
        private const val ACTION_STOP = "com.melhive.zed.wake.STOP"
        /**
         * sherpa-onnx keyword format: BPE tokens of the phrase, then @label. "Hey Zed" was chosen by testing
         * spellings with the real engine on synthesized speech (11 clips, several voices) against 30 near-miss
         * clips ("he said", "Hey Ted", "Hey Ed", "Hey Jed", "hazed", "Hey Zack"…): ▁ Z ED detected 9/11 at
         * threshold 0.08 with zero false alarms; the ▁ Z E D spelling barely triggers.
         */
        const val KEYWORDS = "▁HE Y ▁ Z ED @HEY_ZED"
        /** (threshold, boosting score) per sensitivity; normal had zero false alarms in testing. */
        private val THRESHOLD_LOW = 0.15f to 1.5f
        private val THRESHOLD_NORMAL = 0.08f to 2.5f
        private val THRESHOLD_HIGH = 0.06f to 3.0f

        /** True while ZED LIVE is using the microphone. */
        val paused = MutableStateFlow(false)

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java)) }
                .onFailure { com.melhive.zed.android.CrashReporter.recordNonFatal("wake word start", it) }
        }

        fun stop(context: Context) { context.stopService(Intent(context, WakeWordService::class.java)) }
    }
}
