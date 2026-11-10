package com.melhive.zenith.core

import com.melhive.zenith.core.voice.EnglishPhonemizer
import com.melhive.zenith.core.voice.LetterToSound
import com.melhive.zenith.core.voice.PronunciationLexicon
import com.melhive.zenith.core.voice.NumberWords
import com.melhive.zenith.core.voice.SpeechNormalizer
import com.melhive.zenith.core.voice.VoiceCatalog
import com.melhive.zenith.core.voice.VoiceEngineKind
import com.melhive.zenith.core.voice.VoiceSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VoiceTest {
    private fun norm(s: String) = SpeechNormalizer.normalize(s)

    @Test fun numberWords() {
        assertEquals("zero", NumberWords.cardinal(0))
        assertEquals("forty-two", NumberWords.cardinal(42))
        assertEquals("one hundred five", NumberWords.cardinal(105))
        assertEquals("one thousand two hundred thirty-four", NumberWords.cardinal(1234))
        assertEquals("two million five hundred thousand", NumberWords.cardinal(2_500_000))
        assertEquals("minus seven", NumberWords.cardinal(-7))
        assertEquals("first", NumberWords.ordinal(1))
        assertEquals("twenty-second", NumberWords.ordinal(22))
        assertEquals("one hundred third", NumberWords.ordinal(103))
        assertEquals("twelfth", NumberWords.ordinal(12))
        assertEquals("twentieth", NumberWords.ordinal(20))
        assertEquals("twenty twenty-six", NumberWords.year(2026))
        assertEquals("two thousand five", NumberWords.year(2005))
        assertEquals("nineteen ninety-nine", NumberWords.year(1999))
        assertEquals("nineteen hundred", NumberWords.year(1900))
        assertEquals("nineteen oh five", NumberWords.year(1905))
    }

    @Test fun timesAndDates() {
        assertEquals("Your meeting is at three p m.", norm("Your meeting is at 3pm."))
        assertEquals("Wake up at seven thirty a m", norm("Wake up at 7:30 AM"))
        assertEquals("at nine oh five p m.", norm("at 9:05 p.m."))
        assertEquals("It is fifteen forty-five", norm("It is 15:45"))
        assertEquals("at ten o'clock", norm("at 10:00"))
        assertEquals("on November tenth, twenty twenty-six", norm("on 2026-11-10"))
        assertEquals("the twenty-first of May", norm("the 21st of May"))
        assertEquals("It's twenty twenty-six", norm("It's 2026"))
        assertEquals("music from the nineteen nineties and the eighties", norm("music from the 1990s and the 80s"))
    }

    @Test fun moneyPercentUnits() {
        assertEquals("twelve dollars and fifty cents", norm("$12.50"))
        assertEquals("one dollar", norm("$1"))
        assertEquals("one thousand five hundred pesos", norm("₱1,500"))
        assertEquals("five point two million dollars", norm("$5.2M"))
        assertEquals("forty-five percent", norm("45%"))
        assertEquals("three point five percent", norm("3.5%"))
        assertEquals("twenty-one degrees Celsius", norm("21°C"))
        assertEquals("five kilometers", norm("5 km"))
        assertEquals("one kilogram", norm("1kg"))
        assertEquals("two point five gigabytes", norm("2.5 GB"))
    }

    @Test fun plainNumbersRangesAndCodes() {
        assertEquals("I have three tasks", norm("I have 3 tasks"))
        assertEquals("one thousand two hundred files", norm("1,200 files"))
        assertEquals("pi is about three point one four", norm("pi is about 3.14"))
        assertEquals("three to five days", norm("3-5 days"))
        assertEquals("call five five five, zero one four two", norm("call 555-0142"))
        assertEquals("from twenty twenty to twenty twenty-five", norm("from 2020-2025"))
        assertEquals("code one two three four five six", norm("code 123456"))
        assertEquals("minus four degrees", norm("-4°"))
        assertEquals("Gemma four E two B", norm("Gemma 4 E2B"))
        assertEquals("pay five pesos for it.", norm("pay ₱5 for it."))
        assertEquals("due November fifteenth, for example rent.", norm("due Nov 15th, e.g. rent."))
        assertEquals("from GitHub and YouTube.", norm("from GitHub and YouTube)."))
        assertEquals("Doctor Cruz", norm("Dr. Cruz"))
    }

    @Test fun symbolsAndPunctuation() {
        assertEquals("He said hello, and left", norm("He said “hello” — and left"))
        assertEquals("rock and roll", norm("rock & roll"))
        assertEquals("the plan, draft, is ready", norm("the plan (draft) is ready"))
        assertEquals("don't stop", norm("don’t stop"))
        assertEquals("Done!", norm("Done! ✨"))
    }

    private val lex = PronunciationLexicon.of(mapOf(
        "minute" to "mˈɪnət", "degree" to "dəɡɹˈi", "box" to "bˈɑks", "here" to "hˈɪɹ", "reply" to "ɹəplˈI",
        "stop" to "stˈɑp", "want" to "wˈɑnt", "play" to "plˈA", "use" to "jˈuz", "run" to "ɹˈʌn", "kind" to "kˈInd",
        "snap" to "snˈæp", "dragon" to "dɹˈæɡən", "hello" to "həlˈO", "g" to "ʤˈi", "p" to "pˈi", "u" to "jˈu",
        "it's" to "ɪts", "is" to "ɪz", "ready" to "ɹˈɛdi", "sir" to "sˌɜɹ",
    ))
    private val ph = EnglishPhonemizer(lex)

    @Test fun phonemizerUsesDictionaryAndInflections() {
        assertEquals("həlˈO", ph.word("Hello"))
        assertEquals("mˈɪnəts", ph.word("minutes"))      // voiceless → s
        assertEquals("dəɡɹˈiz", ph.word("degrees"))      // voiced → z
        assertEquals("bˈɑksɪz", ph.word("boxes"))        // sibilant → ɪz
        assertEquals("ɹəplˈIz", ph.word("replies"))
        assertEquals("hˈɪɹz", ph.word("here's"))
        assertEquals("stˈɑpt", ph.word("stopped"))       // doubled consonant, voiceless → t
        assertEquals("wˈɑntɪd", ph.word("wanted"))       // after t → ɪd
        assertEquals("plˈAd", ph.word("played"))
        assertEquals("jˈuzd", ph.word("used"))           // silent-e verb
        assertEquals("ɹˈʌnɪŋ", ph.word("running"))
        assertEquals("kˈIndnəs", ph.word("kindness"))
        assertEquals("snˈæpdɹˈæɡən", ph.word("Snapdragon"))
        assertEquals("ʤˈi pˈi jˈu", ph.word("GPU"))
        assertEquals("pˈi jˈuz", ph.word("PUs"))
        assertEquals("ʤˈi snˈæp", ph.word("gsnap"))
    }

    @Test fun phonemizerFallsBackToLetterRules() {
        assertEquals("ɹˈɑmɛl", ph.word("Rommel"))
        assertEquals("ʤˈɑɹvɪs", ph.word("Jarvis"))
        assertEquals("ʤˈAd", LetterToSound.convert("jade"))
        assertEquals("tˈAbᵊl", LetterToSound.convert("table"))
        assertTrue(LetterToSound.convert("xyz").isNotEmpty())
    }

    @Test fun phonemizerSplitsSentencesAndKeepsPunctuation() {
        val out = ph.phonemize("Hello, sir! It's ready.")
        assertEquals(listOf("həlˈO, sˌɜɹ!", "ɪts ɹˈɛdi."), out)
        assertEquals(listOf("ɹˈʌn ɹˈʌn"), ph.phonemize("run-run"))
        assertTrue(ph.phonemize("  , . ").isEmpty())
    }

    @Test fun catalogIsConsistent() {
        assertEquals(2, VoiceCatalog.packs.size)
        for (p in VoiceCatalog.packs) {
            assertEquals(64, p.sha256.length, p.id)
            assertTrue(p.sizeBytes > 50_000_000, p.id)
            assertTrue(p.archiveUrl.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/"), p.id)
            assertEquals(p.voices.size, p.voices.map { it.sid }.toSet().size, "duplicate speaker ids in ${p.id}")
            assertTrue(p.voices.all { it.packId == p.id })
            assertTrue(p.voices.any { it.featured })
        }
        assertEquals((0..9).toSet(), VoiceCatalog.SUPERTONIC.voices.map { it.sid }.toSet())
        assertEquals(28, VoiceCatalog.KOKORO.voices.size)
        // English voices only: Kokoro ids 0..27 are the American and British speakers.
        assertTrue(VoiceCatalog.KOKORO.voices.all { it.sid in 0..27 })
        assertEquals("George", VoiceCatalog.voice("kokoro1:26")?.name)
        assertEquals("lexicon-gb-en.txt", VoiceCatalog.kokoroLexicon(VoiceCatalog.voice("kokoro1:26")!!))
        assertEquals("lexicon-us-en.txt", VoiceCatalog.kokoroLexicon(VoiceCatalog.voice("kokoro1:3")!!))
    }

    @Test fun selectionParsing() {
        assertIs<VoiceSelection.SystemDefault>(VoiceSelection.parse(null))
        assertIs<VoiceSelection.SystemDefault>(VoiceSelection.parse("system"))
        assertEquals("en-us-x-iol-local", (VoiceSelection.parse("system:en-us-x-iol-local") as VoiceSelection.System).voiceName)
        val neural = VoiceSelection.parse("supertonic3:5")
        assertIs<VoiceSelection.Neural>(neural)
        assertEquals(VoiceEngineKind.SUPERTONIC, neural.pack.kind)
        assertEquals("Atlas", neural.voice.name)
        // Stale or unknown ids fall back safely.
        assertIs<VoiceSelection.SystemDefault>(VoiceSelection.parse("kokoro1:99"))
        assertIs<VoiceSelection.SystemDefault>(VoiceSelection.parse("piper:1"))
        assertEquals("es", VoiceCatalog.supertonicLanguage("es-MX"))
        assertEquals("en", VoiceCatalog.supertonicLanguage("fil-PH"))
        assertFalse("tl" in VoiceCatalog.SUPERTONIC_LANGUAGES)
    }
}
