package com.melhive.zenith.core.security

import com.melhive.zenith.core.tools.RiskLevel
import com.melhive.zenith.core.tools.ToolRequest
import com.melhive.zenith.core.tools.ToolSpec

/** How often ZED asks before acting (Settings → Ask before actions). */
enum class ConfirmMode(val label: String, val description: String) {
    NEVER("Never — just do it", "Everything you ask runs immediately, including deletes and calls."),
    RISKY("Only risky things", "Asks only before deleting, calling, or unlocking and opening doors."),
    ALWAYS("Every change", "Asks before anything that changes something.");

    companion object { fun parse(name: String?) = entries.firstOrNull { it.name == name } ?: NEVER }
}

/**
 * Decides whether the user must confirm a tool call before execution.
 * - READ_ONLY: never.
 * - [ConfirmMode.NEVER]: never — the user asked for actions to run immediately.
 * - [ConfirmMode.RISKY]: DESTRUCTIVE tools and tools marked [ToolSpec.alwaysConfirm] (calls, locks).
 * - [ConfirmMode.ALWAYS]: every tool that changes something.
 */
object ConfirmationPolicy {
    fun requiresConfirmation(spec: ToolSpec, request: ToolRequest, mode: ConfirmMode): Boolean = when {
        spec.risk == RiskLevel.READ_ONLY -> false
        mode == ConfirmMode.NEVER -> false
        mode == ConfirmMode.ALWAYS -> true
        else -> spec.risk == RiskLevel.DESTRUCTIVE || spec.alwaysConfirm
    }

    /** Legacy form: "confirm every change" on = ALWAYS, off = RISKY. */
    fun requiresConfirmation(spec: ToolSpec, request: ToolRequest, confirmAllChanges: Boolean = false): Boolean =
        requiresConfirmation(spec, request, if (confirmAllChanges) ConfirmMode.ALWAYS else ConfirmMode.RISKY)
}
