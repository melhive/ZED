package com.melhive.zenith.core.voice

/** Which synthesizer produces a voice. */
enum class VoiceEngineKind { SYSTEM, SUPERTONIC, KOKORO }

enum class VoiceGender(val label: String) { FEMALE("Female"), MALE("Male") }

/**
 * One selectable neural voice inside a downloadable [VoicePack].
 * [sid] is the speaker index in the pack's voice table (verified against the
 * sherpa-onnx voice-table generator scripts for each pack).
 */
data class NeuralVoice(
    val packId: String,
    val sid: Int,
    val name: String,
    val gender: VoiceGender,
    val accent: String,
    val description: String,
    val featured: Boolean = false,
) {
    val id: String get() = "$packId:$sid"
}

/**
 * A downloadable offline voice pack (a sherpa-onnx `.tar.bz2` archive).
 * Size and SHA-256 come from the GitHub release metadata and are verified
 * after download. [keepFiles] lists the archive entries extracted on install;
 * everything else (other-language dictionaries, legacy espeak data) is skipped
 * to save storage.
 */
data class VoicePack(
    val id: String,
    val name: String,
    val kind: VoiceEngineKind,
    val tagline: String,
    val description: String,
    val archiveUrl: String,
    val archiveRoot: String,
    val sizeBytes: Long,
    val sha256: String,
    val keepFiles: Set<String>,
    val license: String,
    val voices: List<NeuralVoice>,
)

/**
 * Voice selection stored in settings:
 *  - `system` — the phone's default text-to-speech voice
 *  - `system:<voice name>` — a specific Android TTS voice
 *  - `<packId>:<sid>` — a neural voice from a downloaded pack
 */
sealed interface VoiceSelection {
    data object SystemDefault : VoiceSelection
    data class System(val voiceName: String) : VoiceSelection
    data class Neural(val pack: VoicePack, val voice: NeuralVoice) : VoiceSelection

    companion object {
        const val SYSTEM_DEFAULT = "system"
        fun system(name: String) = "system:$name"

        /** Unknown or stale ids fall back to the system default voice. */
        fun parse(id: String?): VoiceSelection {
            if (id.isNullOrBlank() || id == SYSTEM_DEFAULT) return SystemDefault
            if (id.startsWith("system:")) return id.removePrefix("system:").takeIf { it.isNotBlank() }?.let { System(it) } ?: SystemDefault
            val voice = VoiceCatalog.voice(id) ?: return SystemDefault
            return Neural(VoiceCatalog.pack(voice.packId)!!, voice)
        }
    }
}

object VoiceCatalog {
    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

    /** Supertonic 3: natural, very fast, reads numbers/currency itself, 31 languages. */
    val SUPERTONIC = VoicePack(
        id = "supertonic3",
        name = "Supertonic 3",
        kind = VoiceEngineKind.SUPERTONIC,
        tagline = "Natural and very fast · recommended",
        description = "Realistic voices that start speaking almost instantly, even on mid-range phones. Reads numbers, money and phone numbers naturally. Speaks 31 languages.",
        archiveUrl = "$BASE/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
        archiveRoot = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11",
        sizeBytes = 128_774_318L,
        sha256 = "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427",
        keepFiles = setOf(
            "duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx",
            "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin", "LICENSE",
        ),
        license = "Model: OpenRAIL-M (Supertone) · engine: sherpa-onnx, Apache-2.0",
        // voice.bin is built from the voice-style JSONs in sorted order: F1..F5, then M1..M5.
        voices = listOf(
            NeuralVoice("supertonic3", 0, "Aria", VoiceGender.FEMALE, "Neutral", "Preset voice F1", featured = true),
            NeuralVoice("supertonic3", 1, "Lena", VoiceGender.FEMALE, "Neutral", "Preset voice F2"),
            NeuralVoice("supertonic3", 2, "Maya", VoiceGender.FEMALE, "Neutral", "Preset voice F3"),
            NeuralVoice("supertonic3", 3, "Iris", VoiceGender.FEMALE, "Neutral", "Preset voice F4"),
            NeuralVoice("supertonic3", 4, "Nora", VoiceGender.FEMALE, "Neutral", "Preset voice F5"),
            NeuralVoice("supertonic3", 5, "Atlas", VoiceGender.MALE, "Neutral", "Preset voice M1", featured = true),
            NeuralVoice("supertonic3", 6, "Orion", VoiceGender.MALE, "Neutral", "Preset voice M2"),
            NeuralVoice("supertonic3", 7, "Felix", VoiceGender.MALE, "Neutral", "Preset voice M3"),
            NeuralVoice("supertonic3", 8, "Silas", VoiceGender.MALE, "Neutral", "Preset voice M4"),
            NeuralVoice("supertonic3", 9, "Theo", VoiceGender.MALE, "Neutral", "Preset voice M5"),
        ),
    )

    private fun k(sid: Int, name: String, g: VoiceGender, accent: String, desc: String, featured: Boolean = false) =
        NeuralVoice("kokoro1", sid, name, g, accent, desc, featured)

    /** Kokoro v1.0: the most expressive voices; English voices only (the pack's English lexicons are installed). */
    val KOKORO = VoicePack(
        id = "kokoro1",
        name = "Kokoro",
        kind = VoiceEngineKind.KOKORO,
        tagline = "Most expressive · 28 English voices",
        description = "Very lifelike American and British English voices. Needs a fast phone: on budget phones it renders slower than it speaks, so there are pauses between sentences. ZED measures this and tells you.",
        archiveUrl = "$BASE/kokoro-int8-multi-lang-v1_0.tar.bz2",
        archiveRoot = "kokoro-int8-multi-lang-v1_0",
        sizeBytes = 132_353_410L,
        sha256 = "72c347230107ef8a31098ec4d468a605244beef5f1ed00db97efa13518800653",
        keepFiles = setOf("model.int8.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt", "lexicon-gb-en.txt", "LICENSE"),
        license = "Model: Apache-2.0 (Kokoro-82M) · engine: sherpa-onnx, Apache-2.0",
        // Speaker ids from sherpa-onnx scripts/kokoro/v1.0/generate_voices_bin.py; grades from Kokoro-82M VOICES.md.
        voices = listOf(
            k(3, "Heart", VoiceGender.FEMALE, "American", "Top quality (grade A)", featured = true),
            k(2, "Bella", VoiceGender.FEMALE, "American", "Top quality (grade A-)", featured = true),
            k(6, "Nicole", VoiceGender.FEMALE, "American", "Top quality (grade B-)", featured = true),
            k(0, "Alloy", VoiceGender.FEMALE, "American", "Good quality (grade C)"),
            k(1, "Aoede", VoiceGender.FEMALE, "American", "Good quality (grade C+)"),
            k(4, "Jessica", VoiceGender.FEMALE, "American", "Fair quality (grade D)"),
            k(5, "Kore", VoiceGender.FEMALE, "American", "Good quality (grade C+)"),
            k(7, "Nova", VoiceGender.FEMALE, "American", "Good quality (grade C)"),
            k(8, "River", VoiceGender.FEMALE, "American", "Fair quality (grade D)"),
            k(9, "Sarah", VoiceGender.FEMALE, "American", "Good quality (grade C+)"),
            k(10, "Sky", VoiceGender.FEMALE, "American", "Good quality (grade C-)"),
            k(16, "Michael", VoiceGender.MALE, "American", "Good quality (grade C+)", featured = true),
            k(14, "Fenrir", VoiceGender.MALE, "American", "Good quality (grade C+)"),
            k(18, "Puck", VoiceGender.MALE, "American", "Good quality (grade C+)"),
            k(11, "Adam", VoiceGender.MALE, "American", "Fair quality (grade F+)"),
            k(12, "Echo", VoiceGender.MALE, "American", "Fair quality (grade D)"),
            k(13, "Eric", VoiceGender.MALE, "American", "Fair quality (grade D)"),
            k(15, "Liam", VoiceGender.MALE, "American", "Fair quality (grade D)"),
            k(17, "Onyx", VoiceGender.MALE, "American", "Fair quality (grade D)"),
            k(19, "Santa", VoiceGender.MALE, "American", "Fair quality (grade D-)"),
            k(21, "Emma", VoiceGender.FEMALE, "British", "Top quality (grade B-)", featured = true),
            k(22, "Isabella", VoiceGender.FEMALE, "British", "Good quality (grade C)"),
            k(20, "Alice", VoiceGender.FEMALE, "British", "Fair quality (grade D)"),
            k(23, "Lily", VoiceGender.FEMALE, "British", "Fair quality (grade D)"),
            k(26, "George", VoiceGender.MALE, "British", "Good quality (grade C)", featured = true),
            k(25, "Fable", VoiceGender.MALE, "British", "Good quality (grade C)", featured = true),
            k(27, "Lewis", VoiceGender.MALE, "British", "Fair quality (grade D+)"),
            k(24, "Daniel", VoiceGender.MALE, "British", "Fair quality (grade D)"),
        ),
    )

    val packs: List<VoicePack> = listOf(SUPERTONIC, KOKORO)

    /**
     * "Hey Zed" keyword-spotting model (sherpa-onnx streaming zipformer, GigaSpeech English).
     * The "-mobile" variant crashes in ONNX Runtime (encoder reshape) with sherpa-onnx 2.0.1; this one was verified.
     */
    val WAKE_WORD = VoicePack(
        id = "kws-gigaspeech",
        name = "Wake word model",
        kind = VoiceEngineKind.SYSTEM,
        tagline = "Hey Zed",
        description = "Small on-device model that listens only for “Hey Zed”.",
        archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2",
        archiveRoot = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01",
        sizeBytes = 17_626_723L,
        sha256 = "f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a",
        keepFiles = setOf(
            "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx", "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx", "tokens.txt",
        ),
        license = "Apache-2.0 (k2-fsa)",
        voices = emptyList(),
    )

    fun pack(id: String): VoicePack? = packs.firstOrNull { it.id == id } ?: WAKE_WORD.takeIf { it.id == id }

    fun voice(id: String): NeuralVoice? {
        val packId = id.substringBefore(':', "")
        val sid = id.substringAfter(':', "").toIntOrNull() ?: return null
        return pack(packId)?.voices?.firstOrNull { it.sid == sid }
    }

    /** Supertonic 3 languages (ISO 639-1). Anything else is spoken as English. */
    val SUPERTONIC_LANGUAGES = setOf(
        "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi", "hr", "hu",
        "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi",
    )

    fun supertonicLanguage(languageTag: String): String =
        languageTag.substringBefore('-').lowercase().takeIf { it in SUPERTONIC_LANGUAGES } ?: "en"

    /** Lexicon file for a Kokoro voice: British voices use the British pronunciation dictionary. */
    fun kokoroLexicon(voice: NeuralVoice): String = if (voice.accent == "British") "lexicon-gb-en.txt" else "lexicon-us-en.txt"
}
