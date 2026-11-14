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
    /** Said instead of a reminder's text, an event's title or the briefing while locked. */
    const val REMINDER = "You have a reminder."
    const val EVENT = "You have an event coming up."
    const val BRIEFING = "Good morning. Unlock your phone for your briefing."
}
