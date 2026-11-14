package com.melhive.zed.core.security

/**
 * What ZED may do while the phone is locked. Anyone can pick up a locked phone
 * and say "Hey Zed", so only actions that neither read nor change anything
 * personal are allowed; everything else waits until the phone is unlocked.
 * New tools are blocked until they are added here on purpose.
 */
object LockPolicy {
    val allowed: Set<String> = setOf(
        "time.now", "timer.set", "alarm.set", "flashlight.set", "volume.set", "weather.get", "web.research", "device.status",
    )

    fun allows(tool: String): Boolean = tool in allowed

    const val MESSAGE = "Unlock your phone for that."

    /** Said once when ZED hears a voice that isn't the owner's ("only my voice" is on). */
    fun stranger(owner: String?): String = "I don't think you're ${owner ?: "my owner"}, so I'll answer as a guest."
    /** Said when a guest asks for something personal. */
    fun guestOnly(owner: String?): String = "That's only for ${owner ?: "my owner"}."

    /** How sure the voice check is. Between the two it changes nothing: a wrong accusation is worse than a missed one. */
    const val VOICE_IS_OWNER = 0.5f
    const val VOICE_IS_STRANGER = 0.3f
    /** Said instead of a reminder's text, an event's title or the briefing while locked. */
    const val REMINDER = "You have a reminder."
    const val EVENT = "You have an event coming up."
    const val BRIEFING = "Good morning. Unlock your phone for your briefing."
}
