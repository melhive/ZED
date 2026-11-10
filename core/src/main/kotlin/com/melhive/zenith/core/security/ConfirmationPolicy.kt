package com.melhive.zenith.core.security

import com.melhive.zenith.core.tools.RiskLevel
import com.melhive.zenith.core.tools.ToolRequest
import com.melhive.zenith.core.tools.ToolSpec

/**
 * Decides whether the user must confirm a tool call before execution.
 * - DESTRUCTIVE: always.
 * - MODIFY requested by an AI model: always (model output is untrusted).
 * - MODIFY requested by a native command the user typed: no, unless the user
 *   enabled "confirm every change".
 * - READ_ONLY: never.
 * - Tools marked [ToolSpec.alwaysConfirm]: always. Tools marked [ToolSpec.lowRisk]
 *   (harmless, reversible device controls): only with "confirm every change".
 */
object ConfirmationPolicy {
    fun requiresConfirmation(spec: ToolSpec, request: ToolRequest, confirmAllChanges: Boolean = false): Boolean =
        if (spec.alwaysConfirm) true
        else if (spec.lowRisk && spec.risk == RiskLevel.MODIFY) confirmAllChanges
        else when (spec.risk) {
            RiskLevel.DESTRUCTIVE -> true
            RiskLevel.READ_ONLY -> false
            RiskLevel.MODIFY -> confirmAllChanges ||
                request.origin == ToolRequest.Origin.LOCAL_MODEL ||
                request.origin == ToolRequest.Origin.ONLINE_MODEL
        }
}
