package com.melhive.zed.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.zed.core.learning.Insight
import com.melhive.zed.ui.components.SectionTitle
import com.melhive.zed.ui.components.ZedCard
import com.melhive.zed.ui.navigation.Routes
import com.melhive.zed.ui.navigation.rememberContainer
import com.melhive.zed.ui.theme.ZedColors
import kotlinx.coroutines.launch

/** What ZED noticed while "dreaming": one gentle suggestion at a time, each easy to wave away. */
@Composable
fun InsightsCard(onNavigate: (String) -> Unit, onTab: (String) -> Unit) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val insights by c.dreamer.insights.collectAsStateWithLifecycle()
    if (insights.isEmpty()) return
    ZedCard(accent = ZedColors.Violet) {
        SectionTitle("ZED noticed")
        insights.take(2).forEach { i ->
            Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(i.text, style = MaterialTheme.typography.bodyMedium)
                Row {
                    when (i.action) {
                        Insight.Action.REMIND -> TextButton(onClick = {
                            c.dreamer.dismiss(i.key)
                            scope.launch {
                                val id = c.conversations.latestOrCreate()
                                c.chatFocus.value = id
                                c.orchestrator.submit(id, "remind me about ${i.payload} tomorrow at 9am")
                                onTab(Routes.CHAT)
                            }
                        }) { Text("Remind me tomorrow") }
                        Insight.Action.ROUTINE -> TextButton(onClick = { c.dreamer.dismiss(i.key); onNavigate(Routes.ROUTINES) }) { Text("Set up a routine") }
                    }
                    TextButton(onClick = { c.dreamer.dismiss(i.key) }) { Text("Not now", color = ZedColors.Muted) }
                }
            }
        }
    }
}
