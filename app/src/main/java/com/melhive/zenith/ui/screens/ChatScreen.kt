package com.melhive.zenith.ui.screens

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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.AssistChip
import androidx.compose.material3.SuggestionChip
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.combinedClickable
import com.melhive.zenith.ui.components.MarkdownText
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
import com.melhive.zenith.android.PermissionCatalog
import com.melhive.zenith.database.MessageEntity
import com.melhive.zenith.settings.IntelligenceMode
import com.melhive.zenith.ui.components.EmptyState
import com.melhive.zenith.ui.components.StatusPill
import com.melhive.zenith.ui.components.ZenithOrb
import com.melhive.zenith.ui.theme.ZenithColors

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(initialConversationId: Long?, onOpenLive: () -> Unit, onOpenModels: () -> Unit) {
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
    var modeMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val attachLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::attach) }
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
                    Text(when (settings?.mode) { IntelligenceMode.AUTO -> "Auto"; IntelligenceMode.LOCAL -> "On-device"; IntelligenceMode.ONLINE -> settings?.onlineProvider?.label ?: "Online"; else -> "Native" })
                })
                DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                    DropdownMenuItem(text = { Text("Auto (best available)") }, onClick = { vm.setMode(IntelligenceMode.AUTO); modeMenu = false })
                    DropdownMenuItem(text = { Text("Native commands only") }, onClick = { vm.setMode(IntelligenceMode.NATIVE_ONLY); modeMenu = false })
                    DropdownMenuItem(text = { Text("On-device model") }, onClick = { vm.setMode(IntelligenceMode.LOCAL); modeMenu = false })
                    DropdownMenuItem(text = { Text("Online provider") }, onClick = { vm.setMode(IntelligenceMode.ONLINE); modeMenu = false })
                    DropdownMenuItem(text = { Text("Manage models…") }, onClick = { modeMenu = false; onOpenModels() })
                }
            }
            IconButton(onClick = { showHistory = true }) { Icon(Icons.Outlined.History, "Conversations") }
            IconButton(onClick = vm::newConversation) { Icon(Icons.Outlined.Add, "New conversation") }
            IconButton(onClick = onOpenLive) { Icon(Icons.Outlined.GraphicEq, "ZENITH LIVE") }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    ZenithOrb(activity.phase.toOrb(), size = 140.dp)
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
                        MessageBubble(m, showRegenerate = !busy && m.id == lastAssistantId && m.source != "native", onRegenerate = vm::regenerate)
                    }
                }
            }
        }

        if (busy) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(activity.phase.toOrb().label + (activity.detail?.let { " · $it" } ?: ""), ZenithColors.Violet, Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::stop) { Icon(Icons.Outlined.Stop, null); Text("Stop") }
            }
        }
        if (!busy) usage?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = ZenithColors.Muted, modifier = Modifier.padding(horizontal = 16.dp)) }
        notice?.let {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = ZenithColors.Amber, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.notice.value = null }) { Icon(Icons.Outlined.Close, "Dismiss") }
            }
        }
        attachment?.let { a ->
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill("📎 ${a.name} (${a.text.length} chars)", ZenithColors.Teal)
                IconButton(onClick = vm::clearAttachment) { Icon(Icons.Outlined.Close, "Remove attachment") }
            }
        }

        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = { attachLauncher.launch(arrayOf("text/*", "application/json", "application/xml")) }) { Icon(Icons.Outlined.AttachFile, "Attach document") }
            OutlinedTextField(
                value = if (listening) partial.ifEmpty { "Listening…" } else input,
                onValueChange = { if (!listening) input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message ZENITH") },
                maxLines = 6,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ZenithColors.Cyan, unfocusedBorderColor = ZenithColors.Outline),
            )
            IconButton(onClick = {
                if (PermissionCatalog.isGranted(context, PermissionCatalog.MICROPHONE)) vm.toggleVoiceInput()
                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }) { Icon(Icons.Outlined.Mic, "Voice input", tint = if (listening) ZenithColors.Red else ZenithColors.Muted) }
            IconButton(enabled = !busy && (input.isNotBlank() || attachment != null), onClick = { vm.send(input); input = "" }) {
                Icon(Icons.AutoMirrored.Outlined.Send, "Send", tint = ZenithColors.Cyan)
            }
        }
    }

    if (showHistory) {
        ModalBottomSheet(onDismissRequest = { showHistory = false }, containerColor = ZenithColors.Surface) {
            Text("Conversations", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                items(conversations, key = { it.id }) { conv ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.select(conv.id); showHistory = false }
                            .background(if (conv.id == currentId) ZenithColors.Cyan.copy(alpha = 0.08f) else ZenithColors.Surface)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(conv.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(formatTime(conv.updatedAt), style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
                        }
                        IconButton(onClick = { vm.deleteConversation(conv.id) }) { Icon(Icons.Outlined.Delete, "Delete conversation", tint = ZenithColors.Muted) }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(m: MessageEntity, showRegenerate: Boolean = false, onRegenerate: () -> Unit = {}) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val isUser = m.role == "user"
    if (m.role == "tool") {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Column(
                Modifier.fillMaxWidth().background(ZenithColors.Surface, RoundedCornerShape(10.dp)).border(1.dp, ZenithColors.Outline, RoundedCornerShape(10.dp)).padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Build, null, tint = ZenithColors.Amber, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(m.toolName ?: "tool", style = MaterialTheme.typography.labelSmall, color = ZenithColors.Amber)
                }
                SelectionContainer { Text(m.content.take(1500), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = ZenithColors.Muted) }
            }
        }
        return
    }
    val color = when {
        isUser -> ZenithColors.Violet.copy(alpha = 0.22f)
        m.status == "error" -> ZenithColors.Red.copy(alpha = 0.12f)
        else -> ZenithColors.SurfaceHigh
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 330.dp).background(color, RoundedCornerShape(16.dp))
                .combinedClickable(onClick = {}, onLongClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(m.content)) })
                .padding(12.dp)
        ) {
            if (m.attachmentName != null) Text("📎 ${m.attachmentName}", style = MaterialTheme.typography.labelSmall, color = ZenithColors.Teal)
            val text = if (isUser && m.attachmentName != null) m.content.substringBefore("\n\n[Attached file:") else m.content
            val shown = text.ifEmpty { if (m.status == "streaming") "…" else "" } + if (m.status == "streaming") " ▍" else ""
            if (isUser) Text(shown, style = MaterialTheme.typography.bodyLarge) else SelectionContainer { MarkdownText(shown) }
            val meta = buildList {
                m.source?.let { add(when { it == "native" -> "native"; it.startsWith("local") -> "on-device"; it.startsWith("online:") -> it.removePrefix("online:"); else -> it }) }
                if (m.status == "cancelled") add("stopped")
                if (m.status == "error") add("error")
            }
            if (!isUser && meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = ZenithColors.Muted)
            if (!isUser && m.status != "streaming") Row {
                TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(m.content)) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("Copy", style = MaterialTheme.typography.labelSmall, color = ZenithColors.Muted)
                }
                if (showRegenerate) TextButton(onClick = onRegenerate, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("Regenerate", style = MaterialTheme.typography.labelSmall, color = ZenithColors.Cyan)
                }
            }
        }
    }
}
