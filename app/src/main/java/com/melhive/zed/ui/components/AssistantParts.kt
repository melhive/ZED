package com.melhive.zed.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melhive.zed.agents.ActionResult
import com.melhive.zed.core.ui.ActionLabels
import com.melhive.zed.ui.theme.ZedColors

/** A soft glow along the screen edges while ZED listens; it swells with the voice. */
@Composable
fun EdgeGlow(active: Boolean, level: Float, color: Color = ZedColors.Cyan, modifier: Modifier = Modifier) {
    val strength by animateFloatAsState(if (active) 0.35f + 0.65f * level.coerceIn(0f, 1f) else 0f, tween(180), label = "edge")
    if (strength <= 0.01f) return
    Canvas(modifier.fillMaxSize()) {
        val w = size.minDimension * (0.05f + 0.05f * strength)
        val a = 0.45f * strength
        drawRect(Brush.verticalGradient(listOf(color.copy(alpha = a), Color.Transparent), 0f, w), size = size.copy(height = w))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, color.copy(alpha = a)), size.height - w, size.height), topLeft = Offset(0f, size.height - w), size = size.copy(height = w))
        drawRect(Brush.horizontalGradient(listOf(color.copy(alpha = a), Color.Transparent), 0f, w), size = size.copy(width = w))
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, color.copy(alpha = a)), size.width - w, size.width), topLeft = Offset(size.width - w, 0f), size = size.copy(width = w))
    }
}

/** Live captions: what ZED is hearing, and what it is saying as it says it. */
@Composable
fun LiveCaptions(heard: String, saying: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (heard.isNotBlank()) Text("“${heard.trim()}”", style = MaterialTheme.typography.bodyMedium, color = ZedColors.Muted, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        if (saying.isNotBlank()) Text(saying.trim().takeLast(260), style = MaterialTheme.typography.bodyLarge, color = ZedColors.Text, textAlign = TextAlign.Center,
            maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Small chips for what ZED just did: "Alarm 6:00 ✓". */
@Composable
fun ResultChips(results: List<ActionResult>, modifier: Modifier = Modifier) {
    val chips = results.filter { it.chip != null }.takeLast(3)
    if (chips.isEmpty()) return
    WrapRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        chips.forEach { r ->
            val color = if (r.ok) ZedColors.Green else ZedColors.Red
            Row(Modifier.clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (r.ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null, tint = color, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(r.chip!!, style = MaterialTheme.typography.labelLarge, color = ZedColors.Text, maxLines = 1)
            }
        }
    }
}

/** A card for results worth reading in full: the weather, the day's schedule, tasks, a web answer. */
@Composable
fun ResultCard(result: ActionResult, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val kind = ActionLabels.cardKind(result.tool) ?: return
    val (icon, accent, title) = when (kind) {
        "weather" -> Triple(Icons.Outlined.WbSunny, ZedColors.Amber, "Weather")
        "schedule" -> Triple(Icons.Outlined.Event, ZedColors.Violet, "Coming up")
        "tasks" -> Triple(Icons.Outlined.TaskAlt, ZedColors.Green, "Tasks")
        else -> Triple(Icons.Outlined.Language, ZedColors.Cyan, "From the web")
    }
    val lines = result.message.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("Answer from these sources") }
    ZedCard(modifier.fillMaxWidth(), accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = accent)
            Spacer(Modifier.width(8.dp))
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = accent, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) { Icon(Icons.Outlined.Close, "Close", tint = ZedColors.Muted) }
        }
        Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
            lines.take(8).forEachIndexed { i, l ->
                Text(l, style = if (i == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                    color = if (i == 0) ZedColors.Text else ZedColors.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
