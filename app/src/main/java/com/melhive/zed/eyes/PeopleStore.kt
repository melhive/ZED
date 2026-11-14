package com.melhive.zed.eyes

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.melhive.zed.android.CrashReporter
import com.melhive.zed.core.eyes.FacePrint
import com.melhive.zed.core.eyes.NameAnswer
import com.melhive.zed.core.eyes.PeopleBook
import com.melhive.zed.core.eyes.Person
import com.melhive.zed.core.eyes.VoicePrint
import com.melhive.zed.models.VerifiedDownload
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * The people ZED has learned to recognise in Eye mode: a name, a few face
 * prints and (for the owner) voiceprints. Stored in one private file on the
 * phone; never uploaded and not part of backups.
 */
class PeopleStore(context: Context) {
    private val file = File(context.filesDir, "people.bin")
    private val book = PeopleBook()
    /** Changes whenever someone is added, renamed or forgotten. */
    val version = MutableStateFlow(0)

    init { load() }

    @Synchronized fun names(): List<Triple<Long, String, Boolean>> = book.people.map { Triple(it.id, it.name, it.owner) }
    @Synchronized fun hasOwner(): Boolean = book.owner != null
    @Synchronized fun ownerVoices(): Int = book.owner?.voices?.size ?: 0
    @Synchronized fun whose(face: FloatArray): PeopleBook.Match = book.whose(face)
    @Synchronized fun ownerVoice(voice: FloatArray): Float? = book.ownerVoice(voice)

    @Synchronized fun add(name: String, owner: Boolean, faces: List<FloatArray>): Person {
        // Teaching your own face again replaces the old one.
        if (owner) book.people.removeAll { it.owner }
        return book.add(name, owner, faces).also { save() }
    }

    @Synchronized fun learnFace(p: Person, face: FloatArray) { book.learn(p, face); save() }
    @Synchronized fun learnOwnerVoice(voice: FloatArray) { book.owner?.let { if (it.voices.size < PeopleBook.MAX_VOICES) { it.voices += voice; save() } } }
    @Synchronized fun rename(id: Long, name: String) { book.people.firstOrNull { it.id == id }?.let { it.name = name.take(40); save() } }
    @Synchronized fun remove(id: Long) { if (book.people.removeAll { it.id == id }) save() }
    @Synchronized fun forget(name: String): Boolean = book.forget(name).also { if (it) save() }
    @Synchronized fun clear() { book.people.clear(); save() }

    private fun save() {
        runCatching {
            val tmp = File(file.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(1)
                out.writeInt(book.people.size)
                for (p in book.people) {
                    out.writeLong(p.id); out.writeUTF(p.name); out.writeBoolean(p.owner)
                    for (set in listOf(p.faces, p.voices)) {
                        out.writeInt(set.size)
                        for (v in set) { out.writeInt(v.size); for (x in v) out.writeFloat(x) }
                    }
                }
            }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }.onFailure { CrashReporter.recordNonFatal("people save", it) }
        version.value++
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != 1) return
                repeat(input.readInt()) {
                    val p = Person(input.readLong(), input.readUTF(), input.readBoolean())
                    for (set in listOf(p.faces, p.voices)) repeat(input.readInt()) { set += FloatArray(input.readInt()) { input.readFloat() } }
                    book.people += p
                }
            }
        }.onFailure { CrashReporter.recordNonFatal("people load", it); book.people.clear() }
    }
}

/** Turns a spoken sentence into a voiceprint with a small on-phone model (downloaded once, about 30 MB). */
class VoiceId(context: Context) {
    private val dir = File(context.filesDir, "voices/speaker-id")
    private val model = File(dir, "campplus.onnx")
    private var extractor: SpeakerEmbeddingExtractor? = null

    fun installed(): Boolean = model.exists()

    suspend fun download(onProgress: suspend (Long, Long) -> Unit) {
        dir.mkdirs()
        val part = File(dir, "campplus.onnx.part")
        VerifiedDownload.fetch(URL, part, SIZE, SHA256, null) { done, total, _ -> onProgress(done, total) }
        if (!part.renameTo(model)) throw java.io.IOException("Could not put the voice model in place")
    }

    /** [samples]: 16 kHz sound. Null when the model isn't there or the sound is too short. */
    @Synchronized fun print(samples: FloatArray): FloatArray? {
        if (!installed() || samples.size < RATE) return null
        return runCatching {
            val ex = extractor ?: SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(model = model.path, numThreads = 2, debug = false, provider = "cpu")).also { extractor = it }
            val stream = ex.createStream()
            try {
                stream.acceptWaveform(samples, RATE)
                stream.inputFinished()
                if (ex.isReady(stream)) ex.compute(stream) else null
            } finally { stream.release() }
        }.onFailure { CrashReporter.recordNonFatal("voice print", it) }.getOrNull()
    }

    @Synchronized fun release() { runCatching { extractor?.release() }; extractor = null }

    companion object {
        const val RATE = 16_000
        const val SIZE = 29_596_978L
        private const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"
        private const val SHA256 = "357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b"
    }
}

/**
 * Eye mode's people recognition (an experiment): who the face in view is, when
 * a stranger has stayed long enough to be asked their name, and whether the
 * voice just heard is the owner's. It only changes how ZED greets and reacts.
 */
class EyePeople(private val store: PeopleStore, private val voice: VoiceId) {
    /** What the test line shows. */
    @Volatile var seenLabel = "nobody"
        private set
    @Volatile var heardLabel = ""
        private set
    /** The person several looks in a row agree on, or null. */
    @Volatile var seen: Person? = null
        private set
    /** An unknown face has stayed in view. */
    @Volatile var strangerStable = false
        private set
    /** Teaching the owner's face: how many of [TEACH] pictures are in, or -1 when not teaching. */
    @Volatile var teaching = -1
        private set
    /** The stranger in view was already asked (or is being asked) their name. */
    @Volatile var asked = false

    private var teachName = ""
    private val teachFaces = ArrayList<FloatArray>()
    private val votes = ArrayDeque<Long>()
    private val strangerFaces = ArrayList<FloatArray>()
    private var learnedAt = 0L
    @Volatile private var awaitingNameUntil = 0L
    val greeted: MutableSet<Long> = java.util.Collections.synchronizedSet(HashSet())

    /** Collects pictures of the face in view and saves them as the owner. */
    fun teach(name: String) { synchronized(this) { teachName = name; teachFaces.clear(); teaching = 0 } }

    /** A new picture of the face in view (from the camera's thread). */
    @Synchronized fun onFace(grey: IntArray) {
        val print = FacePrint.describe(grey)
        if (teaching >= 0) {
            teachFaces += print
            teaching = teachFaces.size
            if (teachFaces.size >= TEACH) {
                val me = store.add(teachName, true, teachFaces.toList())
                greeted += me.id
                teaching = -1; votes.clear()
            }
            return
        }
        val m = store.whose(print)
        val vote = when { m.known -> m.person!!.id; m.stranger -> STRANGER; else -> UNSURE }
        votes.addLast(vote); while (votes.size > LOOKS) votes.removeFirst()
        if (vote == STRANGER) { strangerFaces += print; while (strangerFaces.size > 8) strangerFaces.removeAt(0) }
        val known = votes.filter { it > 0 }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.takeIf { it.value >= 3 }?.key
        seen = if (known != null) (if (m.person?.id == known) m.person else seen?.takeIf { it.id == known }) else null
        val strangers = votes.count { it == STRANGER }
        strangerStable = known == null && strangers >= LOOKS - 1
        if (strangers == 0) { strangerFaces.clear(); asked = false }
        val d = "%.2f".format(m.distance.coerceAtMost(9.99f))
        seenLabel = when {
            seen != null -> seen!!.name + (if (seen!!.owner) " (you)" else "") + " · $d"
            strangerStable -> "someone new · $d"
            else -> "not sure · $d"
        }
        // A face it is sure of teaches it more angles of that face, slowly.
        val now = System.currentTimeMillis()
        val p = m.person
        if (m.known && p != null && m.distance < 0.36f && p.faces.size < PeopleBook.MAX_FACES && now - learnedAt > 15_000) { learnedAt = now; store.learnFace(p, print) }
    }

    /** No face in view any more. */
    @Synchronized fun onNoFace() {
        votes.clear(); strangerFaces.clear(); seen = null; strangerStable = false; asked = false
        seenLabel = "nobody"
    }

    /** ZED has just asked the stranger in view for their name. */
    fun askedName() { asked = true; awaitingNameUntil = System.currentTimeMillis() + 25_000 }

    /** The answer to "What's your name?": remembers the face under that name. Null when this wasn't a name. */
    @Synchronized fun nameAnswer(said: String): String? {
        if (System.currentTimeMillis() > awaitingNameUntil) return null
        awaitingNameUntil = 0
        val name = NameAnswer.parse(said) ?: return null
        if (strangerFaces.size < 2) return null
        val p = store.add(name, false, strangerFaces.toList())
        greeted += p.id
        votes.clear(); strangerFaces.clear()
        return "Nice to meet you, $name. I'll remember you."
    }

    /** The sound of what was just said: is it the owner's voice? Learns the owner's voice while the owner is in view. */
    fun onUtterance(samples: FloatArray) {
        if (!voice.installed()) { heardLabel = ""; return }
        val ownerInView = seen?.owner == true
        Thread({
            val print = voice.print(samples) ?: return@Thread
            val score = store.ownerVoice(print)
            // Your voice is learned from sentences said while ZED sees your face.
            if (ownerInView && samples.size >= VoiceId.RATE * 5 / 2 && (score == null || score > 0.35f) && store.ownerVoices() < PeopleBook.MAX_VOICES) store.learnOwnerVoice(print)
            heardLabel = when {
                score == null -> if (ownerInView) "learning your voice (${store.ownerVoices()}/${PeopleBook.MAX_VOICES})" else "your voice isn't known yet"
                score >= VoicePrint.SAME -> "you · %.2f".format(score)
                else -> "not you · %.2f".format(score)
            }
        }, "zed-voice-id").apply { isDaemon = true }.start()
    }

    companion object {
        const val TEACH = 8
        private const val LOOKS = 6
        private const val STRANGER = -1L
        private const val UNSURE = 0L
    }
}
