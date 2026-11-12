package com.melhive.zed.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The loudness of what ZED is saying right now, slice by slice, taken from the
 * actual audio being played (on-device voices). The orb's voice ring follows
 * surface from it. The phone's built-in voices don't share their audio, so
 * with them the orb falls back to the word-by-word pulse.
 */
object AudioLevels {
    class Speech(val bins: FloatArray, val at: Long)

    private val _speech = MutableStateFlow(Speech(FloatArray(0), 0L))
    val speech: StateFlow<Speech> = _speech.asStateFlow()

    /** Loudness bins from the voice process (empty = silence). */
    fun set(bins: FloatArray) { _speech.value = Speech(bins, System.currentTimeMillis()) }

    fun silence() { _speech.value = Speech(FloatArray(0), System.currentTimeMillis()) }
}
