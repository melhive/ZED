package com.melhive.zenith.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.zenith.android.DeviceInfo
import com.melhive.zenith.core.security.PathPolicy
import com.melhive.zenith.core.tools.ToolRequest
import com.melhive.zenith.files.FileItem
import com.melhive.zenith.tools.ToolOutcome
import com.melhive.zenith.ui.components.EmptyState
import com.melhive.zenith.ui.components.IconLabel
import com.melhive.zenith.ui.components.SectionTitle
import com.melhive.zenith.ui.components.ZenithCard
import com.melhive.zenith.ui.navigation.rememberContainer
import com.melhive.zenith.ui.theme.MonoStyle
import com.melhive.zenith.ui.theme.ZenithColors
import kotlinx.coroutines.launch

@Composable
fun FilesScreen(onBack: () -> Unit) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val grants by c.files.grants.collectAsStateWithLifecycle(initialValue = emptyList())
    var path by remember { mutableStateOf<String?>(null) }   // null = grants overview; "Root/sub" when browsing
    var items by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }
    var newFolder by remember { mutableStateOf(false) }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            runCatching { c.files.addGrant(uri) }.onSuccess { notice = "Access granted to ${it.displayName}" }.onFailure { notice = "Could not keep access: ${it.message}" }
        }
    }

    LaunchedEffect(path, refresh) {
        val p = path ?: return@LaunchedEffect
        error = null
        runCatching { c.files.list(p) }.onSuccess { items = it }.onFailure { error = it.message; items = emptyList() }
    }

    fun run(tool: String, args: Map<String, String>, after: () -> Unit = {}) = scope.launch {
        val outcome = c.toolManager.execute(ToolRequest(tool, args, ToolRequest.Origin.USER_UI))
        if (outcome is ToolOutcome.Executed && outcome.result.ok) {
            if (tool == "file.read") preview = args.getValue("path") to outcome.result.message.substringAfter('\n') else notice = outcome.message
            after()
        } else notice = outcome.message
        refresh++
    }

    ListScreen(
        if (path == null) "Files" else PathPolicy.name(path!!).ifEmpty { "Files" },
        onBack = { if (path == null) onBack() else path = PathPolicy.parent(path!!).ifEmpty { null } },
        subtitle = path ?: "Folders you granted through Android's picker",
        actions = { if (path != null) IconButton(onClick = { newFolder = true }) { Icon(Icons.Outlined.CreateNewFolder, "New folder") } },
    ) {
        notice?.let { n -> item { ZenithCard(accent = ZenithColors.Amber) { Text(n); TextButton(onClick = { notice = null }) { Text("Dismiss") } } } }
        if (path == null) {
            item {
                Text("ZENITH can only see folders you pick here. It has no access to the rest of your storage and never needs root.", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
            }
            item { Button(onClick = { treeLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Grant folder access") } }
            if (grants.isEmpty()) item { EmptyState("No folders granted", "Grant Download or Documents to let ZENITH manage files there.") }
            items(grants, key = { it.uri }) { g ->
                ZenithCard(onClick = { path = g.displayName }) {
                    IconLabel(Icons.Outlined.Folder, g.displayName, "Granted ${formatTime(g.grantedAt)}") {
                        IconButton(onClick = { scope.launch { c.files.removeGrant(g.uri) } }) { Icon(Icons.Outlined.LinkOff, "Revoke access", tint = ZenithColors.Muted) }
                    }
                }
            }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { run("files.organize", mapOf("path" to path!!)) }) { Text("Organize") }
                    OutlinedButton(onClick = { run("docs.index", mapOf("path" to path!!)) }) { Text("Index for search") }
                }
            }
            error?.let { e -> item { Text(e, color = ZenithColors.Red) } }
            if (items.isEmpty() && error == null) item { EmptyState("Empty folder", "Nothing here yet.") }
            item { SectionTitle("${items.size} items") }
            items(items, key = { it.path }) { f ->
                ZenithCard(onClick = {
                    val full = PathPolicy.join(path!!, f.name)
                    if (f.isDirectory) path = full else if (PathPolicy.isTextFile(f.name)) run("file.read", mapOf("path" to full)) else notice = "${f.name} is not a text document ZENITH can open."
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconLabel(
                            if (f.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description, f.name,
                            if (f.isDirectory) "Folder" else "${DeviceInfo.formatBytes(f.sizeBytes)} · ${formatTime(f.lastModified)}",
                            tint = if (f.isDirectory) ZenithColors.Cyan else ZenithColors.Muted,
                        ) {
                            IconButton(onClick = { run("file.delete", mapOf("path" to PathPolicy.join(path!!, f.name))) }) { Icon(Icons.Outlined.Delete, "Delete", tint = ZenithColors.Muted) }
                        }
                    }
                }
            }
        }
    }

    preview?.let { (p, text) ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(PathPolicy.name(p)) },
            text = { Text(text, style = MonoStyle, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("Close") } },
        )
    }
    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolder = false },
            title = { Text("New folder") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { run("folder.create", mapOf("path" to PathPolicy.join(path!!, name.trim()))); newFolder = false }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { newFolder = false }) { Text("Cancel") } },
        )
    }
}

