package com.melhive.zed.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.zed.ui.navigation.rememberContainer
import com.melhive.zed.ui.theme.ZedColors
import com.melhive.zed.voice.LiveTurn
import kotlinx.coroutines.flow.flowOf

/** One control for the three ways to talk to ZED: the orb (LIVE), the eyes, or the chat. */
@Composable
fun VoiceModeSwitch(current: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(50)).background(Color(0xCC0B111E)).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("orb" to "ORB", "eyes" to "EYES", "chat" to "CHAT").forEach { (id, label) ->
            val on = id == current
            Text(
                label, fontSize = 11.sp, letterSpacing = 1.5.sp,
                color = if (on) Color(0xFF00141B) else ZedColors.Muted,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) ZedColors.Cyan else Color.Transparent)
                    .clickable(enabled = !on) { onSelect(id) }.padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/**
 * The whole conversation of a call as chat bubbles, over the orb or the eyes.
 * Reads the saved conversation when there is one (so an earlier chat that the
 * call continues is there too); otherwise what was said in this call so far.
 * Swipe down on the top bar, press back or tap the cross to close.
 */
@Composable
fun VoiceHistoryPanel(conversationId: Long?, transcript: List<LiveTurn>, onClose: () -> Unit) {
    val c = rememberContainer()
    val saved by remember(conversationId) { if (conversationId != null) c.conversations.messages(conversationId) else flowOf(emptyList()) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val turns = remember(saved, transcript) {
        val fromDb = saved.filter { (it.role == "user" || it.role == "assistant") && it.content.isNotBlank() }.map { LiveTurn(it.role == "user", it.content) }
        fromDb.ifEmpty { transcript }
    }
    val list = rememberLazyListState()
    LaunchedEffect(turns.size) { if (turns.isNotEmpty()) list.scrollToItem(turns.size - 1) }
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(Color(0xF205070D)).statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().pointerInput(Unit) {
                var pulled = 0f
                detectVerticalDragGestures(onDragStart = { pulled = 0f }, onDragEnd = { if (pulled > 60f) onClose() }) { _, dy -> pulled += dy }
            }.padding(start = 20.dp, end = 8.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("CONVERSATION", style = MaterialTheme.typography.titleSmall, color = ZedColors.Muted, letterSpacing = 3.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close conversation", tint = ZedColors.Muted) }
        }
        if (turns.isEmpty()) Text("Nothing has been said yet.", color = ZedColors.Muted, modifier = Modifier.padding(20.dp))
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(turns) { turn ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (turn.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
                    Text(
                        turn.text.removePrefix("📷 "),
                        color = if (turn.fromUser) ZedColors.Text else ZedColors.Cyan,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (turn.fromUser) ZedColors.SurfaceHigh else ZedColors.Surface).padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
            item { Box(Modifier.padding(bottom = 12.dp)) }
        }
    }
}
