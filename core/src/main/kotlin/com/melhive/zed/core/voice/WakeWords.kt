package com.melhive.zed.core.voice

/** Keyword lines for the "Hey Zed" detector, checked against the model before they are loaded. */
object WakeWords {
    /** The phrase every copy of the wake model can spell. */
    const val FALLBACK = "▁ Z ED @ZED"

    /**
     * Keeps the keyword lines whose tokens are all in [known] (the model's
     * tokens.txt). A single unknown token makes the speech engine exit the
     * whole app, so nothing unchecked is ever loaded.
     */
    fun safe(keywords: String, known: Set<String>): String {
        if (known.isEmpty()) return keywords
        val ok = keywords.lines().filter { it.isNotBlank() }.filter { line ->
            line.substringBefore('@').trim().split(' ').filter { it.isNotBlank() && !it.startsWith(':') && !it.startsWith('#') }.all { it in known }
        }
        return ok.joinToString("\n").ifBlank { FALLBACK }
    }
}
