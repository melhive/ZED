package com.melhive.zenith.core

import com.melhive.zenith.core.security.ConfirmationPolicy
import com.melhive.zenith.core.security.PathPolicy
import com.melhive.zenith.core.security.SecretRedactor
import com.melhive.zenith.core.security.ToolCallExtractor
import com.melhive.zenith.core.tools.ToolCatalog
import com.melhive.zenith.core.tools.ToolRequest
import com.melhive.zenith.core.tools.ToolRequest.Origin
import com.melhive.zenith.core.tools.ToolValidator
import com.melhive.zenith.core.tools.ValidationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SecurityTest {
    private val catalog = ToolCatalog.DEFAULT
    private val validator = ToolValidator(catalog)

    @Test fun pathPolicy() {
        assertEquals(PathPolicy.Result.Ok("a/b/c.txt"), PathPolicy.normalizeRelative("a//b/./c.txt/"))
        assertEquals(PathPolicy.Result.Ok(""), PathPolicy.normalizeRelative(""))
        listOf("/sdcard/a", "~/a", "a/../b", "content://x/y", "a/b:c", "a/b?", "a/\u0000b").forEach {
            assertIs<PathPolicy.Result.Rejected>(PathPolicy.normalizeRelative(it), it)
        }
        assertIs<PathPolicy.Result.Rejected>(PathPolicy.validateFileName("x".repeat(200)))
        assertIs<PathPolicy.Result.Rejected>(PathPolicy.validateFileName("trailing."))
        assertTrue(PathPolicy.isTextFile("Notes.MD"))
        assertFalse(PathPolicy.isTextFile("photo.jpg"))
    }

    @Test fun validatorRejectsUnknownToolsAndArgs() {
        assertIs<ValidationResult.Invalid>(validator.validate(ToolRequest("shell.exec", mapOf("cmd" to "rm -rf /"))))
        val extra = validator.validate(ToolRequest("file.read", mapOf("path" to "a.txt", "mode" to "root")))
        assertIs<ValidationResult.Invalid>(extra)
        assertTrue(extra.errors.single().contains("mode"))
        assertIs<ValidationResult.Invalid>(validator.validate(ToolRequest("file.read")))
        assertIs<ValidationResult.Invalid>(validator.validate(ToolRequest("alarm.set", mapOf("hour" to "24", "minute" to "0"))))
        assertIs<ValidationResult.Invalid>(validator.validate(ToolRequest("alarm.set", mapOf("hour" to "x", "minute" to "0"))))
        assertIs<ValidationResult.Invalid>(validator.validate(ToolRequest("reminder.create", mapOf("text" to "a", "at" to "tomorrow"))))
        assertIs<ValidationResult.Invalid>(validator.validate(ToolRequest("settings.set", mapOf("key" to "api_key", "value" to "x"))))
        assertIs<ValidationResult.Invalid>(validator.validate(ToolRequest("file.rename", mapOf("path" to "a", "new_name" to "../b"))))
        val ok = validator.validate(ToolRequest("alarm.set", mapOf("hour" to "7", "minute" to "05")))
        assertIs<ValidationResult.Valid>(ok)
        assertEquals(5L, ok.args.int("minute"))
    }

    @Test fun confirmationPolicy() {
        val del = catalog.find("file.delete")!!
        val create = catalog.find("file.create")!!
        val read = catalog.find("file.read")!!
        assertTrue(ConfirmationPolicy.requiresConfirmation(del, ToolRequest("file.delete", origin = Origin.NATIVE)))
        assertFalse(ConfirmationPolicy.requiresConfirmation(create, ToolRequest("file.create", origin = Origin.NATIVE)))
        assertTrue(ConfirmationPolicy.requiresConfirmation(create, ToolRequest("file.create", origin = Origin.ONLINE_MODEL)))
        assertTrue(ConfirmationPolicy.requiresConfirmation(create, ToolRequest("file.create", origin = Origin.LOCAL_MODEL)))
        assertTrue(ConfirmationPolicy.requiresConfirmation(create, ToolRequest("file.create", origin = Origin.NATIVE), confirmAllChanges = true))
        assertFalse(ConfirmationPolicy.requiresConfirmation(read, ToolRequest("file.read", origin = Origin.ONLINE_MODEL)))
    }

    @Test fun toolCallExtraction() {
        val output = """
            Sure, I'll create it.
            ```zenith-tool
            {"tool": "file.create", "args": {"path": "notes/a.txt", "content": "hello"}}
            ```
        """.trimIndent()
        val e = ToolCallExtractor.extract(output, Origin.ONLINE_MODEL)
        assertEquals("Sure, I'll create it.", e.visibleText)
        assertEquals(1, e.requests.size)
        assertEquals(ToolRequest("file.create", mapOf("path" to "notes/a.txt", "content" to "hello"), Origin.ONLINE_MODEL), e.requests[0])
    }

    @Test fun toolCallExtractionRejectsMalformedAndNested() {
        val bad = """
            ```zenith-tool
            {"tool": "file.create", "args": {"path": {"nested": true}}}
            ```
            ```zenith-tool
            not json
            ```
            ```zenith-tool
            {"tool": "file.read", "args": {"path": "a"}, "sudo": true}
            ```
            ```json
            {"tool": "file.delete", "args": {"path": "a"}}
            ```
        """.trimIndent()
        val e = ToolCallExtractor.extract(bad, Origin.LOCAL_MODEL)
        assertTrue(e.requests.isEmpty())
        assertEquals(3, e.errors.size)
    }

    @Test fun toolCallLimit() {
        val call = "```zenith-tool\n{\"tool\":\"time.now\"}\n```\n"
        val e = ToolCallExtractor.extract(call.repeat(5), Origin.ONLINE_MODEL)
        assertEquals(ToolCallExtractor.MAX_CALLS, e.requests.size)
    }

    @Test fun secretRedaction() {
        val s = SecretRedactor.redact("key sk-ant-api03-abcdefghijklmnop and AIzaSyA1234567890abcdefghijk and Bearer abcdefghijklmnopqrstuvwxyz")
        assertFalse(s.contains("abcdefghijklmnop"))
        assertFalse(s.contains("AIzaSy"))
        assertTrue(s.contains("Bearer [REDACTED]"))
    }
}
