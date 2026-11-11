package com.melhive.zed.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.zed.core.tasks.TaskStatus
import com.melhive.zed.database.TaskEntity
import com.melhive.zed.tasks.statusEnum
import com.melhive.zed.ui.components.EmptyState
import com.melhive.zed.ui.components.RealProgress
import com.melhive.zed.ui.components.SectionTitle
import com.melhive.zed.ui.components.StatusPill
import com.melhive.zed.ui.components.WrapRow
import com.melhive.zed.ui.components.ZedCard
import com.melhive.zed.ui.navigation.rememberContainer
import com.melhive.zed.ui.theme.MonoStyle
import com.melhive.zed.ui.theme.ZedColors
import kotlinx.coroutines.launch

fun statusColor(s: TaskStatus): Color = when (s) {
    TaskStatus.QUEUED -> ZedColors.Muted
    TaskStatus.PLANNING, TaskStatus.RUNNING -> ZedColors.Amber
    TaskStatus.AWAITING_CONFIRMATION -> ZedColors.Cyan
    TaskStatus.SUCCEEDED -> ZedColors.Green
    TaskStatus.FAILED -> ZedColors.Red
    TaskStatus.CANCELLED -> ZedColors.Muted
}

fun statusLabel(s: TaskStatus): String = when (s) {
    TaskStatus.QUEUED -> "Queued"
    TaskStatus.PLANNING -> "Planning"
    TaskStatus.AWAITING_CONFIRMATION -> "Needs confirmation"
    TaskStatus.RUNNING -> "Running"
    TaskStatus.SUCCEEDED -> "Completed"
    TaskStatus.FAILED -> "Failed"
    TaskStatus.CANCELLED -> "Cancelled"
}

@Composable
fun TasksScreen(onOpen: (Long) -> Unit) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val tasks by c.taskEngine.tasks.collectAsStateWithLifecycle(initialValue = emptyList())
    val active = tasks.filter { it.statusEnum() in setOf(TaskStatus.RUNNING, TaskStatus.PLANNING, TaskStatus.AWAITING_CONFIRMATION) }
    val queued = tasks.filter { it.statusEnum() == TaskStatus.QUEUED }
    val completed = tasks.filter { it.statusEnum() == TaskStatus.SUCCEEDED }
    val failed = tasks.filter { it.statusEnum() == TaskStatus.FAILED || it.statusEnum() == TaskStatus.CANCELLED }

    var menu by remember { mutableStateOf(false) }
    ListScreen("Tasks", onBack = null, subtitle = "Background work keeps running while you use ZED", actions = {
        if (tasks.isNotEmpty()) Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Task options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Cancel all active tasks", color = ZedColors.Red) }, enabled = active.isNotEmpty() || queued.isNotEmpty(),
                    onClick = { menu = false; scope.launch { c.taskEngine.cancelAll() } })
                DropdownMenuItem(text = { Text("Clear finished tasks") }, enabled = completed.isNotEmpty() || failed.isNotEmpty(),
                    onClick = { menu = false; scope.launch { c.taskEngine.clearFinished() } })
            }
        }
    }) {
        if (tasks.isEmpty()) item { EmptyState("No tasks yet", "Try “organize my downloads”, “index my documents”, or download a model in Model Manager.") }
        fun section(title: String, list: List<TaskEntity>) {
            if (list.isEmpty()) return
            item(key = "h-$title") { SectionTitle("$title (${list.size})") }
            items(list, key = { it.id }) { t -> TaskCard(t, onOpen) }
        }
        section("Active", active)
        section("Queued", queued)
        section("Failed & cancelled", failed)
        section("Completed", completed)
    }
}

@Composable
fun TaskCard(t: TaskEntity, onOpen: (Long) -> Unit) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val s = t.statusEnum()
    ZedCard(onClick = { onOpen(t.id) }, accent = statusColor(s)) {
        Row(Modifier.fillMaxWidth()) {
            Text("#${t.id} ${t.title}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            StatusPill(statusLabel(s), statusColor(s))
        }
        if (s == TaskStatus.RUNNING || s == TaskStatus.PLANNING) {
            Spacer(Modifier.height(8.dp))
            RealProgress(t.progressCurrent, t.progressTotal, color = statusColor(s))
            val counts = if (t.progressCurrent != null && t.progressTotal != null) {
                if (t.type == "model_download") "${com.melhive.zed.android.DeviceInfo.formatBytes(t.progressCurrent)} / ${com.melhive.zed.android.DeviceInfo.formatBytes(t.progressTotal)}"
                else "${t.progressCurrent} / ${t.progressTotal}"
            } else null
            Text(listOfNotNull(t.progressLabel, counts).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
        }
        if (s == TaskStatus.AWAITING_CONFIRMATION && t.planSummary != null) {
            Spacer(Modifier.height(8.dp))
            Text("Proposed plan:", style = MaterialTheme.typography.labelLarge)
            Text(t.planSummary, style = MonoStyle, color = ZedColors.Text)
        }
        t.resultSummary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ZedColors.Green, modifier = Modifier.padding(top = 6.dp)) }
        t.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ZedColors.Red, modifier = Modifier.padding(top = 6.dp)) }
        WrapRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (s) {
                TaskStatus.AWAITING_CONFIRMATION -> {
                    Button(onClick = { scope.launch { c.taskEngine.confirm(t.id) } }, colors = ButtonDefaults.buttonColors(containerColor = ZedColors.Cyan)) { Text("Approve plan") }
                    OutlinedButton(onClick = { scope.launch { c.taskEngine.cancel(t.id) } }) { Text("Reject") }
                }
                TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.PLANNING ->
                    OutlinedButton(onClick = { scope.launch { c.taskEngine.cancel(t.id) } }) { Text("Cancel task") }
                TaskStatus.FAILED, TaskStatus.CANCELLED ->
                    OutlinedButton(onClick = { scope.launch { c.taskEngine.retry(t.id) } }) { Text("Retry") }
                TaskStatus.SUCCEEDED -> Unit
            }
        }
        Text("Created ${formatTime(t.createdAt)}" + (t.finishedAt?.let { " · finished ${formatTime(it)}" } ?: "") + if (t.attempt > 1) " · attempt ${t.attempt}" else "",
            style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun TaskDetailScreen(taskId: Long, onBack: () -> Unit) {
    val c = rememberContainer()
    val task by c.taskEngine.task(taskId).collectAsStateWithLifecycle(initialValue = null)
    val logs by c.taskEngine.logs(taskId).collectAsStateWithLifecycle(initialValue = emptyList())
    ListScreen("Task #$taskId", onBack = onBack, subtitle = task?.title) {
        task?.let { t -> item { TaskCard(t) {} } }
        item { SectionTitle("Execution history") }
        if (logs.isEmpty()) item { Text("No log entries yet.", color = ZedColors.Muted) }
        items(logs, key = { it.id }) { l ->
            Text(
                "${formatTime(l.timestamp)}  ${l.message}",
                style = MonoStyle,
                color = when (l.level) { "error" -> ZedColors.Red; "warn" -> ZedColors.Amber; else -> ZedColors.Text },
            )
        }
    }
}
