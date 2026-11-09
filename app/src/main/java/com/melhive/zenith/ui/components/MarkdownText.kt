package com.melhive.zenith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.melhive.zenith.ui.theme.ZenithColors

/**
 * Lightweight Markdown renderer for chat replies: headings, bullet and
 * numbered lists, fenced code blocks, inline code, bold and italics.
 * Unknown syntax is shown as plain text, never dropped.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, color: Color = ZenithColors.Text) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier) {
        blocks.forEach { b ->
            when (b) {
                is Block.Code -> Text(
                    b.code,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = ZenithColors.Text,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .background(Color(0xFF060A14), RoundedCornerShape(8.dp))
                        .horizontalScroll(rememberScrollState()).padding(10.dp),
                    softWrap = false,
                )
                is Block.Heading -> Text(
                    inline(b.text), color = color, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    style = if (b.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                )
                is Block.ListItem -> Row(Modifier.padding(start = (b.indent * 12).dp, top = 1.dp, bottom = 1.dp)) {
                    Text(b.marker, color = ZenithColors.Cyan, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(22.dp))
                    Text(inline(b.text), color = color, style = MaterialTheme.typography.bodyLarge)
                }
                is Block.Paragraph -> Text(inline(b.text), color = color, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

private sealed interface Block {
    data class Paragraph(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class ListItem(val marker: String, val text: String, val indent: Int) : Block
    data class Code(val code: String) : Block
}

private val BULLET = Regex("^(\\s*)[-*•]\\s+(.*)$")
private val NUMBERED = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
private val HEADING = Regex("^(#{1,6})\\s+(.*)$")

private fun parseBlocks(text: String): List<Block> {
    val out = mutableListOf<Block>()
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += Block.Paragraph(para.toString().trim()); para.setLength(0) }
    val lines = text.replace("\r\n", "\n").split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.trimStart().startsWith("```")) {
            flush()
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code.appendLine(lines[i]); i++ }
            out += Block.Code(code.toString().trimEnd())
            i++; continue
        }
        HEADING.matchEntire(line)?.let { flush(); out += Block.Heading(it.groupValues[1].length, it.groupValues[2]); i++; return@let } ?: run {
            val b = BULLET.matchEntire(line)
            val n = NUMBERED.matchEntire(line)
            when {
                b != null -> { flush(); out += Block.ListItem("•", b.groupValues[2], b.groupValues[1].length / 2) }
                n != null -> { flush(); out += Block.ListItem("${n.groupValues[2]}.", n.groupValues[3], n.groupValues[1].length / 2) }
                line.isBlank() -> flush()
                else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line) }
            }
            i++
        }
    }
    flush()
    return out
}

private val INLINE = Regex("`([^`]+)`|\\*\\*(.+?)\\*\\*|__(.+?)__|(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\*)|(?<![_\\w])_(?!\\s)(.+?)(?<!\\s)_(?![_\\w])")

private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in INLINE.findAll(text)) {
        append(text.substring(last, m.range.first))
        val (code, bold1, bold2, it1, it2) = m.destructured
        when {
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFF16213A), color = ZenithColors.Cyan)) { append(code) }
            bold1.isNotEmpty() || bold2.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold1.ifEmpty { bold2 }) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(it1.ifEmpty { it2 }) }
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}
