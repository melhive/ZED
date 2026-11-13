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
        // Only phrases whose every token the installed model knows: an unknown token makes the engine exit the whole app.
        val known = runCatching { File(dir, "tokens.txt").readLines().map { it.substringBefore(' ') }.toHashSet() }.getOrDefault(hashSetOf())
        val phrases = com.melhive.zed.core.voice.WakeWords.safe(KEYWORDS, known)
        val keywordsFile = File(cacheDir, "wake-keywords.txt").apply { writeText(phrases + "\n") }
        // With the booster model installed, both listen and either one can wake ZED.
        val dir2 = (application as ZedApp).container.voicePacks.let { p -> p.dir(com.melhive.zed.core.voice.VoiceCatalog.WAKE_WORD_2).takeIf { p.isInstalled(com.melhive.zed.core.voice.VoiceCatalog.WAKE_WORD_2) } }
        val table = if (dir2 != null) DUAL_LEVELS else LEVELS
        val level = table.getOrElse(sensitivity) { table.last() }
        val booster = if (dir2 == null || level.second == null) null else runCatching {
            val known2 = File(dir2, "tokens.txt").readLines().map { it.substringBefore(' ') }.toHashSet()
            val file2 = File(cacheDir, "wake-keywords-2.txt").apply { writeText(com.melhive.zed.core.voice.WakeWords.safe(KEYWORDS_PHONES, known2) + "\n") }
            KeywordSpotter(config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        File(dir2, "encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx").path,
                        File(dir2, "decoder-epoch-13-avg-2-chunk-16-left-64.onnx").path,
                        File(dir2, "joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx").path,
                    ),
                    tokens = File(dir2, "tokens.txt").path, numThreads = 1, modelType = "zipformer2",
                ),
                maxActivePaths = level.second.paths, keywordsFile = file2.path, keywordsThreshold = level.second.threshold, keywordsScore = level.second.score,
            ))
        }.onFailure { com.melhive.zed.android.CrashReporter.recordNonFatal("wake booster model", it) }.getOrNull()
        val stream2 = booster?.createStream("")
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
                maxActivePaths = level.paths, keywordsFile = keywordsFile.path, keywordsThreshold = level.threshold, keywordsScore = level.score,
            ))
        } catch (e: Throwable) {
            com.melhive.zed.android.CrashReporter.recordNonFatal("wake word model", e)
            running = false; stopSelf(); return
        }
        val stream = kws.createStream("")
        // Boosts quiet or distant speech (up to +24 dB) and softly limits loud audio before the detector.
        val leveler = com.melhive.zed.core.voice.MicLeveler()
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
                        val block = if (n == buf.size) floats else floats.copyOf(n)
                        leveler.process(block)
                        stream.acceptWaveform(block, RATE)
                        while (kws.isReady(stream)) {
                            kws.decode(stream)
                            if (kws.getResult(stream).keyword.isNotBlank()) { kws.reset(stream); woke = true; break }
                        }
                        if (booster != null && stream2 != null && !woke) {
                            stream2.acceptWaveform(block, RATE)
                            while (booster.isReady(stream2)) {
                                booster.decode(stream2)
                                if (booster.getResult(stream2).keyword.isNotBlank()) { booster.reset(stream2); woke = true; break }
                            }
                        }
                        if (woke) { runCatching { kws.reset(stream) }; if (booster != null && stream2 != null) runCatching { booster.reset(stream2) } }
                    }
                } finally { runCatching { rec.stop() }; rec.release() }
                if (woke) runCatching { kotlinx.coroutines.runBlocking { onWake() } }.onFailure { com.melhive.zed.android.CrashReporter.recordNonFatal("wake", it) }
            }
        } finally {
            runCatching { stream.release() }; runCatching { kws.release() }
            runCatching { stream2?.release() }; runCatching { booster?.release() }
        }
    }

    /** Opens LIVE when allowed; otherwise handles one request hands-free. */
    private suspend fun onWake() {
        val wokeAt = System.currentTimeMillis()
        val c = (application as ZedApp).container
        // Start loading the model while the user is still speaking.
        c.warmUpModel()
        val style = com.melhive.zed.core.voice.SoundSynth.Style.parse(c.settings.current().liveSound)
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (foreground || Settings.canDrawOverlays(this)) {
            // The floating orb over whatever is on screen (not the full app).
            val opened = runCatching { startActivity(ZedLaunch.overlayIntent(this).putExtra(EXTRA_WAKE_AT, wokeAt)) }.isSuccess
            if (opened) {
                // LIVE takes the microphone; wait until it is closed again.
                delay(1500)
                withTimeoutOrNull(30 * 60_000L) { paused.first { !it } }
                return
            }
        }
        val soundMs = ZedSounds.play(this, style, start = true)
        delay(maxOf(450L, soundMs))
        val cfg = c.settings.current()
        if (cfg.greetOnCall && cfg.voiceResponses && c.voice.awaitReady()) {
            val hello = com.melhive.zed.core.voice.Greetings.pick(java.time.LocalTime.now().hour, cfg.userTitle, runCatching { c.memory.userName() }.getOrNull())
            c.voice.speak(hello, flush = true)
            delay(300)
            withTimeoutOrNull(8_000) { while (c.voice.isSpeaking()) delay(100) }
        }
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
        val stt = (application as ZedApp).container.newSpeechToText()
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
        /** When "Hey Zed" was heard, so the overlay can time how quickly it opened. */
        const val EXTRA_WAKE_AT = "wake_at"
        private const val NOTIFICATION_ID = 65_000
        private const val ACTION_STOP = "com.melhive.zed.wake.STOP"
        /**
         * sherpa-onnx keyword format: BPE tokens of the phrase, then @label. ZED wakes on "Zed" anywhere —
         * alone or in "Hey Zed", "Wake up Zed", "Okay Zed", "Hi Zed". Tested with the real engine on 34 spoken
         * wake phrases × 5 conditions (clean, −30 dB quiet, far with echo, wind, quiet + wind) and 50 near-miss
         * sentences ("he said", "the bed", "zebra", "Hey Ed", "fed up"…): "Hey Zed" alone woke 22/170;
         * these keywords with the mic leveler 58/170 (balanced, 0 false alarms) and 66/170 (most sensitive,
         * 1 false alarm: the letter Z said "zed").
         */
        const val KEYWORDS = "▁ Z ED @ZED\n▁HE Y ▁ Z ED @ZED\n▁WA KE ▁UP ▁ Z ED @ZED\n▁OKAY ▁ Z ED @ZED\n▁HI ▁ Z ED @ZED"
        /**
         * Detector settings per sensitivity (0 fewest false alarms … 3 maximum), measured with the real
         * engine on 34 spoken wake phrases × 5 conditions and 50 near-miss sentences (v0.1.29):
         * a wider search ([Level.paths] 8) catches far more quiet and distant “Zed”s; 16 only adds
         * false alarms; middle settings gained little; extra spellings ("Zet", "Z E D") made it worse.
         */
        data class Level(val threshold: Float, val score: Float, val paths: Int, val second: Level? = null)
        /** The booster model's phrases, in its phonemes (CMU dictionary). */
        const val KEYWORDS_PHONES = "Z EH1 D @ZED\nHH EY1 Z EH1 D @ZED\nW EY1 K AH1 P Z EH1 D @ZED\nOW2 K EY1 Z EH1 D @ZED\nHH AY1 Z EH1 D @ZED"
        /**
         * With the booster: either model wakes ZED. Measured (34 phrases × 5 conditions, 100 near-miss
         * sentences): balanced 68/170 with 1 false alarm, high 91 with 4, maximum 112 with 13.
         */
        val DUAL_LEVELS = listOf(
            Level(0.12f, 2.0f, 4),
            Level(0.03f, 3.5f, 4),
            Level(0.03f, 3.5f, 4, second = Level(0.03f, 3.5f, 4)),
            Level(0.01f, 4.5f, 8, second = Level(0.03f, 3.5f, 4)),
        )
        val LEVELS = listOf(
            Level(0.12f, 2.0f, 4),  // fewer false alarms
            Level(0.05f, 3.0f, 4),  // balanced
            Level(0.03f, 3.5f, 4),  // high: 81/170 caught, 1 look-alike in 50 sentences
            Level(0.01f, 4.5f, 8),  // maximum: 106/170 caught (62%), look-alikes ("zero", "Jed") wake it now and then
        )


        /** True while ZED LIVE is using the microphone. */
        val paused = MutableStateFlow(false)

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java)) }
                .onFailure { com.melhive.zed.android.CrashReporter.recordNonFatal("wake word start", it) }
        }

        fun stop(context: Context) { context.stopService(Intent(context, WakeWordService::class.java)) }
    }
}
