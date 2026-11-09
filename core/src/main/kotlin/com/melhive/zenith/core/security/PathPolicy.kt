package com.melhive.zenith.core.security

/**
 * Path rules for file tools. All tool paths are RELATIVE to a folder the user
 * explicitly granted through the Storage Access Framework. Absolute paths,
 * traversal segments and reserved characters are rejected outright.
 */
object PathPolicy {
    sealed interface Result {
        data class Ok(val path: String) : Result
        data class Rejected(val reason: String) : Result
    }

    private val RESERVED = setOf('<', '>', ':', '"', '|', '?', '*', '\\')
    const val MAX_NAME = 127
    const val MAX_DEPTH = 16

    /** Normalizes "a//b/./c/" to "a/b/c". Empty string means the root itself. */
    fun normalizeRelative(input: String): Result {
        val trimmed = input.trim()
        if (trimmed.startsWith("/") || trimmed.startsWith("~")) {
            return Result.Rejected("absolute paths are not allowed; use a path inside a granted folder")
        }
        if (trimmed.contains("://")) return Result.Rejected("URIs are not allowed")
        val segments = trimmed.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.size > MAX_DEPTH) return Result.Rejected("path is too deep")
        for (s in segments) {
            if (s == "..") return Result.Rejected("'..' segments are not allowed")
            val r = validateFileName(s)
            if (r is Result.Rejected) return r
        }
        return Result.Ok(segments.joinToString("/"))
    }

    fun validateFileName(name: String): Result {
        val n = name.trim()
        if (n.isEmpty()) return Result.Rejected("name is empty")
        if (n == "." || n == "..") return Result.Rejected("invalid name")
        if (n.length > MAX_NAME) return Result.Rejected("name is longer than $MAX_NAME characters")
        if (n.contains('/')) return Result.Rejected("name must not contain '/'")
        if (n.any { it in RESERVED || it.isISOControl() }) return Result.Rejected("name contains reserved characters")
        if (n.endsWith(".") || n.endsWith(" ")) return Result.Rejected("name must not end with '.' or space")
        return Result.Ok(n)
    }

    fun parent(path: String): String = path.substringBeforeLast('/', "")
    fun name(path: String): String = path.substringAfterLast('/')
    fun join(parent: String, child: String): String = if (parent.isEmpty()) child else "$parent/$child"

    /** Text formats ZENITH reads and indexes. Binary formats are refused by file.read. */
    val TEXT_EXTENSIONS = setOf("txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm", "log", "yaml", "yml", "ini", "kt", "java", "py", "js", "ts", "sh", "sql")

    fun isTextFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS
}
