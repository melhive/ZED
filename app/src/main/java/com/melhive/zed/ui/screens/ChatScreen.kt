package com.melhive.zed.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.AssistChip
import androidx.compose.material3.SuggestionChip
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.combinedClickable
import com.melhive.zed.ui.components.MarkdownText
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melhive.zed.android.PermissionCatalog
import com.melhive.zed.database.MessageEntity
import com.melhive.zed.settings.IntelligenceMode
import com.melhive.zed.ui.components.EmptyState
import com.melhive.zed.ui.components.StatusPill
import com.melhive.zed.ui.components.ZedOrb
import com.melhive.zed.ui.theme.ZedColors

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(initialConversationId: Long?, onOpenLive: () -> Unit, onOpenModels: () -> Unit, onOpenEye: () -> Unit = {}) {
    val vm: ChatViewModel = viewModel()
    LaunchedEffect(initialConversationId) { vm.open(initialConversationId) }
    val context = LocalContext.current
    val messages by vm.messages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val attachment by vm.attachment.collectAsStateWithLifecycle()
    val listening by vm.listening.collectAsStateWithLifecycle()
    val partial by vm.voicePartial.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val currentId by vm.conversationId.collectAsStateWithLifecycle()
    val usage by vm.lastUsage.collectAsStateWithLifecycle()

    var input by rememberSaveable { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    var wrongFor by remember { mutableStateOf<MessageEntity?>(null) }
    var modeMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val attachLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::attach) }
    val photo by vm.photo.collectAsStateWithLifecycle()
    val chatContext = androidx.compose.ui.platform.LocalContext.current
    var captureUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) captureUri?.let(vm::attachPhoto) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::attachPhoto) }
    var photoMenu by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleVoiceInput() else vm.notice.value = "Microphone permission denied. Voice input is unavailable."
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader("Chat", subtitle = null) {
            Box {
                AssistChip(onClick = { modeMenu = true }, label = {
                    Text(when (settings?.mode) { IntelligenceMode.AUTO -> "Auto"; IntelligenceMode.LOCAL -> "On-device"; else -> "Native" })
                })
                DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                    DropdownMenuItem(text = { Text("Auto (Gemma when installed)") }, onClick = { vm.setMode(IntelligenceMode.AUTO); modeMenu = false })
                    DropdownMenuItem(text = { Text("Native commands only") }, onClick = { vm.setMode(IntelligenceMode.NATIVE_ONLY); modeMenu = false })
                    DropdownMenuItem(text = { Text("On-device model") }, onClick = { vm.setMode(IntelligenceMode.LOCAL); modeMenu = false })
                    DropdownMenuItem(text = { Text("Manage models…") }, onClick = { modeMenu = false; onOpenModels() })
                }
            }
            IconButton(onClick = { showHistory = true }) { Icon(Icons.Outlined.History, "Conversations") }
            IconButton(onClick = vm::newConversation) { Icon(Icons.Outlined.Add, "New conversation") }
            // A call opened from here carries on this conversation.
            val voice = com.melhive.zed.ui.navigation.rememberContainer().live
            IconButton(onClick = { voice.continueWith = currentId; onOpenLive() }) { Icon(Icons.Outlined.GraphicEq, "ZED LIVE") }
            IconButton(onClick = { voice.continueWith = currentId; onOpenEye() }) { Icon(Icons.Outlined.Visibility, "Eye mode") }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    ZedOrb(activity.phase.toOrb(), size = 140.dp)
                    EmptyState("How can I help?", "Ask anything, or try one of these:")
                    val suggestions = listOf("What can you do?", "Remind me to stretch in 30 minutes", "What is 18% of 250?", "How many days until Christmas?", "Organize my downloads", "Tell me a joke")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { s -> SuggestionChip(onClick = { vm.send(s) }, label = { Text(s) }) }
                    }
                }
            } else {
                LazyColumn(state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                    val lastAssistantId = messages.lastOrNull { it.role == "assistant" }?.id
                    items(messages, key = { it.id }) { m ->
                        MessageBubble(m, showRegenerate = !busy && m.id == lastAssistantId && m.source != "native", onRegenerate = vm::regenerate, onReport = { vm.report(m) }, onWrong = { wrongFor = m })
                    }
                }
            }
        }

        if (busy) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(activity.phase.toOrb().label + (activity.detail?.let { " · $it" } ?: ""), ZedColors.Violet, Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::stop) { Icon(Icons.Outlined.Stop, null); Text("Stop") }
            }
        }
        if (!busy) usage?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted, modifier = Modifier.padding(horizontal = 16.dp)) }
        notice?.let {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = ZedColors.Amber, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.notice.value = null }) { Icon(Icons.Outlined.Close, "Dismiss") }
            }
        }
        if (photo != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text("📷 Photo attached — ask about it", style = MaterialTheme.typography.labelMedium, color = ZedColors.Teal, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.photo.value = null }) { Icon(Icons.Outlined.Close, "Remove photo") }
        }
        attachment?.let { a ->
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill("📎 ${a.name} (${a.text.length} chars)", ZedColors.Teal)
                IconButton(onClick = vm::clearAttachment) { Icon(Icons.Outlined.Close, "Remove attachment") }
            }
        }

        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = { attachLauncher.launch(arrayOf("text/*", "application/json", "application/xml")) }) { Icon(Icons.Outlined.AttachFile, "Attach document") }
            androidx.compose.foundation.layout.Box {
                IconButton(onClick = { photoMenu = true }) { Icon(Icons.Outlined.PhotoCamera, "Add a photo") }
                androidx.compose.material3.DropdownMenu(expanded = photoMenu, onDismissRequest = { photoMenu = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Take a photo") }, onClick = {
                        photoMenu = false
                        runCatching { com.melhive.zed.intelligence.ImageInput.newCaptureUri(chatContext) }.onSuccess { captureUri = it; takePhoto.launch(it) }
                    })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Choose from gallery") }, onClick = {
                        photoMenu = false
                        pickPhoto.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                }
            }
            OutlinedTextField(
                value = if (listening) partial.ifEmpty { "Listening…" } else input,
                onValueChange = { if (!listening) input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message ZED") },
                maxLines = 6,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ZedColors.Cyan, unfocusedBorderColor = ZedColors.Outline),
            )
            IconButton(onClick = {
                if (PermissionCatalog.isGranted(context, PermissionCatalog.MICROPHONE)) vm.toggleVoiceInput()
                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }) { Icon(Icons.Outlined.Mic, "Voice input", tint = if (listening) ZedColors.Red else ZedColors.Muted) }
            IconButton(enabled = !busy && (input.isNotBlank() || attachment != null || photo != null), onClick = { vm.send(input); input = "" }) {
                Icon(Icons.AutoMirrored.Outlined.Send, "Send", tint = ZedColors.Cyan)
            }
        }
    }

    wrongFor?.let { m ->
        var fix by remember(m.id) { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { wrongFor = null },
            title = { Text("What's right?") },
            text = {
                Column {
                    Text("Tell me the correct answer. I'll remember it, so I don't make the same mistake again.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                    OutlinedTextField(value = fix, onValueChange = { fix = it.take(300) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(enabled = fix.isNotBlank(), onClick = { vm.correct(m, fix); wrongFor = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { wrongFor = null }) { Text("Cancel") } },
            containerColor = ZedColors.SurfaceHigh,
        )
    }

    if (showHistory) {
        ModalBottomSheet(onDismissRequest = { showHistory = false }, containerColor = ZedColors.Surface) {
            Text("Conversations", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                items(conversations, key = { it.id }) { conv ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.select(conv.id); showHistory = false }
                            .background(if (conv.id == currentId) ZedColors.Cyan.copy(alpha = 0.08f) else ZedColors.Surface)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(conv.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(formatTime(conv.updatedAt), style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                        }
                        IconButton(onClick = { vm.deleteConversation(conv.id) }) { Icon(Icons.Outlined.Delete, "Delete conversation", tint = ZedColors.Muted) }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(m: MessageEntity, showRegenerate: Boolean = false, onRegenerate: () -> Unit = {}, onReport: () -> Unit = {}, onWrong: () -> Unit = {}) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val isUser = m.role == "user"
    if (m.role == "tool") {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Column(
                Modifier.fillMaxWidth().background(ZedColors.Surface, RoundedCornerShape(12.dp)).padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Build, null, tint = ZedColors.Amber, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(m.toolName ?: "tool", style = MaterialTheme.typography.labelSmall, color = ZedColors.Amber)
                }
                SelectionContainer { Text(m.content.take(1500), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = ZedColors.Muted) }
            }
        }
        return
    }
    val color = when {
        isUser -> ZedColors.Violet.copy(alpha = 0.22f)
        m.status == "error" -> ZedColors.Red.copy(alpha = 0.12f)
        else -> ZedColors.SurfaceHigh
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 330.dp).background(color, RoundedCornerShape(16.dp))
                .combinedClickable(onClick = {}, onLongClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(m.content)) })
                .padding(12.dp)
        ) {
            if (m.attachmentName != null) Text("📎 ${m.attachmentName}", style = MaterialTheme.typography.labelSmall, color = ZedColors.Teal)
            val text = if (isUser && m.attachmentName != null) m.content.substringBefore("\n\n[Attached file:") else m.content
            val shown = text.ifEmpty { if (m.status == "streaming") "…" else "" } + if (m.status == "streaming") " ▍" else ""
            if (isUser) Text(shown, style = MaterialTheme.typography.bodyLarge) else SelectionContainer { MarkdownText(shown) }
            val meta = buildList {
                m.source?.let { add(when { it == "native" -> "native"; it.startsWith("local") -> "on-device"; it.startsWith("online:") -> it.removePrefix("online:"); else -> it }) }
                if (m.status == "cancelled") add("stopped")
                if (m.status == "error") add("error")
                // How long the reply took: the wait for the first word, then the whole answer.
                val total = m.totalMs
                if (total != null && m.status == "complete") {
                    val first = m.firstTokenMs
                    add(if (first != null && total >= 1000) "%.1f s to first word · %.1f s".format(first / 1000f, total / 1000f) else if (total >= 1000) "%.1f s".format(total / 1000f) else "instant")
                }
            }
            if (!isUser && meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted)
            if (!isUser && m.status != "streaming") Row {
                TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(m.content)) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("Copy", style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted)
                }
                if (showRegenerate) TextButton(onClick = onRegenerate, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("Regenerate", style = MaterialTheme.typography.labelSmall, color = ZedColors.Cyan)
                }
                TextButton(onClick = onWrong, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("Wrong", style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted)
                }
                TextButton(onClick = onReport, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("Report", style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted)
                }
            }
        }
    }
}
