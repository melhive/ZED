package com.melhive.zenith

import com.melhive.zenith.intelligence.LocalModelRuntime
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalRuntimeLogicTest {
    @Test fun recognizesContextWindowErrorsFromTheRuntime() {
        val full = RuntimeException("Status Code: 8. Message: Not enough space left in the context window for the input: 1273 tokens in the context + 64 input tokens >= 1280 max number of tokens.")
        val max = RuntimeException("Status Code: 8. Message: Max number of tokens reached, the context window is full.")
        assertTrue(LocalModelRuntime.isContextFull(full))
        assertTrue(LocalModelRuntime.isContextFull(max))
        assertTrue(LocalModelRuntime.isContextFull(RuntimeException("wrapper", max)))
        assertFalse(LocalModelRuntime.isContextFull(RuntimeException("GPU delegate failed")))
        assertTrue(LocalModelRuntime.friendlyError(max).contains("too long"))
    }

    @Test fun contextSizeFollowsDeviceRam() {
        val gb = 1024L * 1024 * 1024
        assertEquals(4096, LocalModelRuntime.autoContextTokens(8 * gb))
        assertEquals(8192, LocalModelRuntime.autoContextTokens(12 * gb))
        assertTrue(LocalModelRuntime.estimateTokens("x".repeat(3000)) >= 1000)
    }
}
