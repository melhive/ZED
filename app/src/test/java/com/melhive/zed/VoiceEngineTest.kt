package com.melhive.zed

import com.melhive.zed.voice.NeuralTextToSpeech
import com.melhive.zed.voice.SupertonicText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceEngineTest {
    @Test fun splitsIntoSentencesForStreaming() {
        assertEquals(
            listOf("Good morning.", "You have 3 meetings today!", "Ready?"),
            NeuralTextToSpeech.splitSentences("Good morning. You have 3 meetings today! Ready?"),
        )
        // Decimals and abbreviations followed by lower case do not split.
        assertEquals(listOf("It costs 3.50 today, e.g. at the store."), NeuralTextToSpeech.splitSentences("It costs 3.50 today, e.g. at the store."))
        assertEquals(listOf("Line one", "Line two"), NeuralTextToSpeech.splitSentences("Line one\nLine two"))
    }

    @Test fun longSentencesBreakAtCommas() {
        val long = (1..30).joinToString(", ") { "item number $it" } + "."
        val parts = NeuralTextToSpeech.splitSentences(long)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 240 })
        assertEquals(long, parts.joinToString(" "))
    }

    @Test fun supertonicCleanupKeepsNumbersAndDropsEmoji() {
        assertEquals("Done! It's \$12.50 at 3:30 PM.", SupertonicText.clean("Done! ✨ It's \$12.50 at **3:30 PM**."))
    }
}
