package com.melhive.zenith.core.security

/** Removes things that look like credentials before text reaches logs or audit records. */
object SecretRedactor {
    private val patterns = listOf(
        Regex("sk-ant-[A-Za-z0-9_\\-]{8,}"),
        Regex("sk-[A-Za-z0-9_\\-]{16,}"),
        Regex("AIza[0-9A-Za-z_\\-]{20,}"),
        Regex("hf_[A-Za-z0-9]{20,}"),
        Regex("gh[pousr]_[A-Za-z0-9]{20,}"),
        Regex("(?i)(bearer\\s+)[A-Za-z0-9._\\-]{16,}"),
    )

    fun redact(text: String): String {
        var out = text
        for (p in patterns) {
            out = p.replace(out) { m ->
                val prefix = if (m.groupValues.size > 1) m.groupValues[1] else ""
                prefix + "[REDACTED]"
            }
        }
        return out
    }
}
