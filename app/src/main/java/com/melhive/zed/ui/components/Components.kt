package com.melhive.zed.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melhive.zed.agents.ConfirmationRequest
import com.melhive.zed.ui.theme.ZedColors

/** full | balanced | light — how much motion the UI shows (Settings → Intelligence → Animation). */
val LocalMotion = androidx.compose.runtime.staticCompositionLocalOf { "full" }

@Composable
fun ZedCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Color = ZedColors.Cyan,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Borderless glass panel: deep translucent fill, a soft accent glow from the corner and a small accent tab.
    val shape = RoundedCornerShape(22.dp)
    // Eases in once as it appears (kept across scrolling); no light band.
    var seen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val motion = LocalMotion.current
    val sweep = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (seen || motion != "full") 1f else 0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (sweep.value < 1f) sweep.animateTo(1f, androidx.compose.animation.core.tween(650, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        seen = true
    }
    Column(
        modifier
            .graphicsLayer { alpha = (0.35f + sweep.value * 1.3f).coerceAtMost(1f); translationY = (1f - sweep.value) * 18f }
            .clip(shape)
            .background(Brush.linearGradient(listOf(ZedColors.SurfaceHigh.copy(alpha = 0.85f), ZedColors.Surface.copy(alpha = 0.7f))))
            .drawWithContent {
                drawCircle(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.16f), Color.Transparent), androidx.compose.ui.geometry.Offset.Zero, size.minDimension * 0.95f),
                    size.minDimension * 0.95f, androidx.compose.ui.geometry.Offset.Zero,
                )
                drawContent()
            }
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = ZedColors.Cyan.copy(alpha = 0.85f), letterSpacing = 2.sp, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun StatusPill(text: String, color: Color = ZedColors.Cyan, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun IconLabel(icon: ImageVector, title: String, subtitle: String? = null, tint: Color = ZedColors.Cyan, trailing: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.28f), tint.copy(alpha = 0.08f)))), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.Info, null, tint = ZedColors.Muted)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = ZedColors.Muted)
    }
}

/** Progress bar that only shows a determinate value when real counts exist. */
@Composable
fun RealProgress(current: Long?, total: Long?, modifier: Modifier = Modifier, color: Color = ZedColors.Cyan) {
    if (current != null && total != null && total > 0) {
        LinearProgressIndicator(progress = { (current.toFloat() / total).coerceIn(0f, 1f) }, modifier = modifier.fillMaxWidth().clip(CircleShape), color = color, trackColor = ZedColors.Outline)
    } else {
        LinearProgressIndicator(modifier = modifier.fillMaxWidth().clip(CircleShape), color = color, trackColor = ZedColors.Outline)
    }
}

/** Small in-app orb shown while background tasks are active. No overlay permission needed. */
@Composable
fun TaskIndicator(activeCount: Int, onClick: () -> Unit) {
    if (activeCount <= 0) return
    val t = rememberInfiniteTransition(label = "ti")
    val a by t.animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Row(
        Modifier.clip(CircleShape).background(ZedColors.Amber.copy(alpha = 0.12f)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).alpha(a).clip(CircleShape).background(ZedColors.Amber))
        Spacer(Modifier.width(6.dp))
        Text("$activeCount active", style = MaterialTheme.typography.labelMedium, color = ZedColors.Amber)
    }
}

@Composable
fun ConfirmationDialog(request: ConfirmationRequest, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(request.title) },
        text = { Text(request.message) },
        confirmButton = {
            TextButton(onClick = { onAnswer(true) }) {
                Text(request.confirmLabel, color = if (request.destructive) ZedColors.Red else ZedColors.Cyan)
            }
        },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Cancel") } },
        containerColor = ZedColors.SurfaceHigh,
    )
}

/** A row of chips or buttons that wraps onto the next line instead of squeezing or overlapping on narrow screens. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun WrapRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp),
    content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit,
) = androidx.compose.foundation.layout.FlowRow(
    modifier = modifier,
    horizontalArrangement = horizontalArrangement,
    verticalArrangement = Arrangement.spacedBy(2.dp),
    content = content,
)

/** Futuristic backdrop: deep background with a soft glow at the top (no lines). */
fun Modifier.hudBackground(): Modifier = this.drawBehind {
    drawRect(ZedColors.Background)
    drawCircle(Brush.radialGradient(listOf(ZedColors.Cyan.copy(alpha = 0.10f), Color.Transparent), androidx.compose.ui.geometry.Offset(size.width / 2, 0f), size.width * 0.9f),
        size.width * 0.9f, androidx.compose.ui.geometry.Offset(size.width / 2, 0f))
    drawCircle(Brush.radialGradient(listOf(ZedColors.Violet.copy(alpha = 0.06f), Color.Transparent), androidx.compose.ui.geometry.Offset(size.width, size.height), size.width),
        size.width, androidx.compose.ui.geometry.Offset(size.width, size.height))
}
