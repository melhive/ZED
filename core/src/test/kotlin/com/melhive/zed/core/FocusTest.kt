package com.melhive.zed.core

import com.melhive.zed.core.understanding.MemoryFact
import com.melhive.zed.core.understanding.MemoryRanker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ZED stays on the current conversation: old topics come up only when the user brings them up. */
class FocusTest {
    private val facts = listOf(
        MemoryFact(1, "Ana is my sister"),
        MemoryFact(2, "Ana is helping me with my thesis defense"),
        MemoryFact(3, "My thesis defense is on Friday"),
        MemoryFact(4, "I want to buy a new laptop", pinned = true),
    )

    @Test fun answersOnlyGetMemoriesTheMessageIsAbout() {
        val r = MemoryRanker.rank("what should I get Ana for her birthday", facts, bridges = false).map { it.fact.id }
        assertTrue(3L !in r, "the thesis is not what this message is about: $r")
        assertTrue(4L !in r, "a pinned memory isn't dragged in either: $r")
        assertTrue(1L in r)
    }

    @Test fun nothingIsRecalledForSmallTalk() {
        assertEquals(emptyList(), MemoryRanker.rank("how are you", facts, bridges = false))
    }

    @Test fun theUserCanStillBringAnOldTopicBack() {
        val r = MemoryRanker.rank("how's my thesis prep going", facts, bridges = false).map { it.fact.id }
        assertTrue(3L in r && 2L in r, "$r")
    }
}
