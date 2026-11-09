package com.melhive.zenith.security

import com.melhive.zenith.core.security.SecretRedactor
import com.melhive.zenith.database.AuditDao
import com.melhive.zenith.database.AuditLogEntity

/** Records every tool execution attempt (including denials) for later inspection. */
class AuditLogger(private val dao: AuditDao) {
    suspend fun record(tool: String, origin: String, args: Map<String, String>, outcome: String, detail: String) {
        val summary = args.entries.joinToString(", ") { (k, v) -> "$k=${v.take(80)}" }
        dao.insert(
            AuditLogEntity(
                timestamp = System.currentTimeMillis(),
                tool = tool,
                origin = origin,
                argsSummary = SecretRedactor.redact(summary).take(600),
                outcome = outcome,
                detail = SecretRedactor.redact(detail).take(600),
            )
        )
    }

    suspend fun prune(olderThanDays: Int = 90) =
        dao.prune(System.currentTimeMillis() - olderThanDays * 86_400_000L)
}
