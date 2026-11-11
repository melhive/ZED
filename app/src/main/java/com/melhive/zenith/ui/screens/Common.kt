package com.melhive.zenith.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.melhive.zenith.agents.AssistantPhase
import com.melhive.zenith.voice.OrbState
import java.text.DateFormat
import java.util.Date

@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)? = null, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
        Column(Modifier.weight(1f).padding(start = if (onBack == null) 12.dp else 0.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.titleLarge, letterSpacing = androidx.compose.ui.unit.TextUnit(3f, androidx.compose.ui.unit.TextUnitType.Sp),
                color = com.melhive.zenith.ui.theme.ZenithColors.Text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            androidx.compose.foundation.layout.Box(Modifier.padding(top = 4.dp).size(width = 36.dp, height = 2.dp).background(com.melhive.zenith.ui.theme.ZenithColors.Cyan))
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        actions()
    }
}

@Composable
fun ListScreen(title: String, onBack: (() -> Unit)?, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}, content: LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title, onBack, subtitle, actions)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

fun formatTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

fun AssistantPhase.toOrb(): OrbState = when (this) {
    AssistantPhase.IDLE -> OrbState.IDLE
    AssistantPhase.THINKING -> OrbState.THINKING
    AssistantPhase.EXECUTING -> OrbState.EXECUTING
    AssistantPhase.RESEARCHING -> OrbState.RESEARCHING
    AssistantPhase.SEARCHING -> OrbState.SEARCHING
    AssistantPhase.COMPLETED -> OrbState.COMPLETED
    AssistantPhase.ERROR -> OrbState.ERROR
}
