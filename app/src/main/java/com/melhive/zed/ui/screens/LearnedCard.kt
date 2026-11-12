package com.melhive.zed.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.zed.database.MemoryFactEntity
import com.melhive.zed.ui.components.SectionTitle
import com.melhive.zed.ui.components.ZedCard
import com.melhive.zed.ui.navigation.rememberContainer
import com.melhive.zed.ui.theme.ZedColors
import kotlinx.coroutines.launch

/**
 * "I learned 3 new things": what ZED picked up from conversation, each with
 * a way to confirm, fix or forget it. Shown on Home until reviewed.
 */
@Composable
fun LearnedCard() {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val ids by c.learner.newFacts.collectAsStateWithLifecycle()
    val facts by c.memory.facts.collectAsStateWithLifecycle(initialValue = emptyList())
    val learned = remember(ids, facts) { ids.mapNotNull { id -> facts.firstOrNull { it.id == id } } }
    var editing by remember { mutableStateOf<MemoryFactEntity?>(null) }
    if (learned.isEmpty()) return

    ZedCard(accent = ZedColors.Teal) {
        SectionTitle("I learned ${learned.size} new thing${if (learned.size == 1) "" else "s"}") {
            TextButton(onClick = { c.learner.dismissAll() }) { Text("Done") }
        }
        Text("From our conversations. Tell me if I got something wrong — I treat these as less certain until you confirm.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
        learned.forEach { f ->
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(f.text, style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { scope.launch { c.learner.confirm(f.id) } }) { Text("Right") }
                    TextButton(onClick = { editing = f }) { Text("Fix") }
                    TextButton(onClick = { scope.launch { c.learner.forget(f.id) } }) { Text("Forget", color = ZedColors.Red) }
                }
            }
        }
    }

    editing?.let { f ->
        var text by remember(f.id) { mutableStateOf(f.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Fix what I learned") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(200) }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { scope.launch { c.learner.edit(f.id, text) }; editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
            containerColor = ZedColors.SurfaceHigh,
        )
    }
}
