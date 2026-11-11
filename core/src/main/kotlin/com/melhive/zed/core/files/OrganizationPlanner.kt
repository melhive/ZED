package com.melhive.zed.core.files

import com.melhive.zed.core.security.PathPolicy

/**
 * Builds a deterministic plan to organize a flat folder into category
 * sub-folders by file extension. Planning is separated from execution so the
 * user can review the plan before anything moves.
 */
object OrganizationPlanner {
    data class Entry(val name: String, val isDirectory: Boolean, val sizeBytes: Long = 0)
    data class Move(val fileName: String, val targetFolder: String)
    data class Plan(val moves: List<Move>, val skipped: List<String>) {
        val byFolder: Map<String, List<Move>> get() = moves.groupBy { it.targetFolder }
        fun summary(): String = if (moves.isEmpty()) "Nothing to organize." else
            byFolder.entries.sortedBy { it.key }.joinToString("\n") { (folder, items) -> "• ${items.size} → $folder/" }
    }

    val CATEGORIES: Map<String, Set<String>> = linkedMapOf(
        "Documents" to setOf("pdf", "doc", "docx", "odt", "rtf", "txt", "md", "pages", "epub"),
        "Spreadsheets" to setOf("xls", "xlsx", "ods", "csv", "tsv", "numbers"),
        "Presentations" to setOf("ppt", "pptx", "odp", "key"),
        "Images" to setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "tiff"),
        "Videos" to setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v"),
        "Audio" to setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr"),
        "Archives" to setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz"),
        "Apps" to setOf("apk", "apks", "xapk", "aab"),
        "Code" to setOf("kt", "java", "py", "js", "ts", "json", "xml", "html", "css", "sh", "yaml", "yml", "sql"),
    )

    fun categoryOf(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty() || ext == name.lowercase()) return null
        return CATEGORIES.entries.firstOrNull { ext in it.value }?.key
    }

    fun plan(entries: List<Entry>): Plan {
        val moves = mutableListOf<Move>()
        val skipped = mutableListOf<String>()
        for (e in entries.sortedBy { it.name.lowercase() }) {
            if (e.isDirectory) continue
            if (e.name.startsWith(".")) { skipped += e.name; continue }
            if (PathPolicy.validateFileName(e.name) is PathPolicy.Result.Rejected) { skipped += e.name; continue }
            val cat = categoryOf(e.name)
            if (cat == null) skipped += e.name else moves += Move(e.name, cat)
        }
        return Plan(moves, skipped)
    }
}
