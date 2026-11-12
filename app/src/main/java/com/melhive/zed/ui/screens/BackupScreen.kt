package com.melhive.zed.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.zed.backup.BackupManager
import com.melhive.zed.backup.BackupOptions
import com.melhive.zed.backup.BackupState
import com.melhive.zed.core.backup.BackupManifest
import com.melhive.zed.ui.components.RealProgress
import com.melhive.zed.ui.components.SectionTitle
import com.melhive.zed.ui.components.ZedCard
import com.melhive.zed.ui.navigation.rememberContainer
import com.melhive.zed.ui.theme.ZedColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** More → Backup & restore: save everything to one file, or bring it back (on this phone or a new one). */
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val c = rememberContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by c.backup.state.collectAsStateWithLifecycle()
    val working = state as? BackupState.Working

    var includeModels by rememberSaveable { mutableStateOf(true) }
    var includeVoices by rememberSaveable { mutableStateOf(true) }
    var backupPassword by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Pair<android.net.Uri, BackupManifest>?>(null) }
    var pickError by remember { mutableStateOf<String?>(null) }

    val sizes by produceState<com.melhive.zed.backup.BackupSizes?>(null, state) { value = withContext(Dispatchers.IO) { c.backup.sizes() } }
    val keyCount = remember(state) { c.backup.secretNames().size }

    // A long backup or restore shouldn't be cut off by the screen turning off.
    val view = LocalView.current
    DisposableEffect(working != null) {
        view.keepScreenOn = working != null
        onDispose { view.keepScreenOn = false }
    }

    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupManager.MIME)) { uri ->
        if (uri != null) c.backup.startBackup(uri, BackupOptions(includeModels, includeVoices, backupPassword))
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            picked = null; pickError = null; restorePassword = ""
            runCatching { c.backup.inspect(uri) }
                .onSuccess { picked = uri to it }
                .onFailure { pickError = it.message ?: "That file can't be read." }
        }
    }

    // Weekly automatic backup into a folder the user picks once.
    val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val autoFolder = settings?.autoBackupFolder.orEmpty()
    val autoFolderName = remember(autoFolder) {
        if (autoFolder.isBlank()) "" else runCatching { androidx.documentfile.provider.DocumentFile.fromTreeUri(context, android.net.Uri.parse(autoFolder))?.name }.getOrNull() ?: "the folder you chose"
    }
    var autoRunning by remember { mutableStateOf(false) }
    fun autoBackupNow(folder: String) {
        if (autoRunning) return
        autoRunning = true
        scope.launch {
            runCatching { com.melhive.zed.backup.AutoBackupWorker.runNow(c, folder) }
            autoRunning = false
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Keep access across restarts, or next week's backup would have nowhere to go.
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            scope.launch {
                c.settings.setAutoBackupFolder(uri.toString())
                com.melhive.zed.backup.AutoBackupWorker.schedule(context.applicationContext, true)
                autoBackupNow(uri.toString())
            }
        }
    }
    val safetyCopies = remember(state) { c.backup.safetyCopies() }

    ListScreen("Backup & restore", onBack = onBack, subtitle = "Save everything to one file — and move ZED to another phone") {
        if (state !is BackupState.Idle) item(key = "status") {
            when (val s = state) {
                is BackupState.Working -> ZedCard(accent = ZedColors.Amber) {
                    SectionTitle(if (s.restoring) "Restoring" else "Backing up")
                    Text(s.label, style = MaterialTheme.typography.bodyMedium)
                    RealProgress(if (s.total > 0) s.done else null, if (s.total > 0) s.total else null, Modifier.padding(top = 8.dp), ZedColors.Amber)
                    if (s.total > 0) Text("${BackupManager.formatBytes(s.done)} of ${BackupManager.formatBytes(s.total)}", style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted, modifier = Modifier.padding(top = 4.dp))
                    Text("Keep ZED open until this finishes.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted, modifier = Modifier.padding(top = 4.dp))
                }
                is BackupState.Finished -> ZedCard(accent = ZedColors.Green) {
                    SectionTitle(if (s.restoring) "Restore ready" else "Backup complete")
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    if (!s.restoring) {
                        Text("Copy the file to your other phone (or keep it somewhere safe), install ZED there and choose Restore.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted, modifier = Modifier.padding(top = 4.dp))
                        TextButton(onClick = { c.backup.dismiss() }) { Text("OK") }
                    }
                }
                is BackupState.Failed -> ZedCard(accent = ZedColors.Red) {
                    SectionTitle(if (s.restoring) "Restore didn't finish" else "Backup didn't finish")
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { c.backup.dismiss() }) { Text("OK") }
                }
                BackupState.Idle -> Unit
            }
        }

        item(key = "backup") {
            ZedCard {
                SectionTitle("Back up")
                Text("Always included: memories, task list, reminders, conversations, the document index, routines and every setting" +
                    (sizes?.let { " (${BackupManager.formatBytes(it.data)})." } ?: "."), style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                val s = sizes
                OptionRow(
                    "AI models",
                    when { s == null -> "Checking…"; s.modelCount == 0 -> "None downloaded"; else -> "${s.modelCount} downloaded · ${BackupManager.formatBytes(s.models)} — skip to keep the file small and download again later" },
                    includeModels && (s?.modelCount ?: 0) > 0, enabled = (s?.modelCount ?: 0) > 0 && working == null,
                ) { includeModels = it }
                OptionRow(
                    "Offline voices and wake word",
                    when { s == null -> "Checking…"; s.voicePackCount == 0 -> "None downloaded"; else -> "${s.voicePackCount} pack${if (s.voicePackCount == 1) "" else "s"} · ${BackupManager.formatBytes(s.voices)}" },
                    includeVoices && (s?.voicePackCount ?: 0) > 0, enabled = (s?.voicePackCount ?: 0) > 0 && working == null,
                ) { includeVoices = it }
                if (keyCount > 0) {
                    Text("Saved tokens", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    Text("Your $keyCount saved key${if (keyCount == 1) "" else "s"} (smart home) ${if (keyCount == 1) "is" else "are"} included only if you set a password. They're encrypted with it, and you'll type it again when restoring.",
                        style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                    OutlinedTextField(
                        value = backupPassword, onValueChange = { backupPassword = it.take(64) }, singleLine = true, enabled = working == null,
                        label = { Text("Password (optional)") }, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
                val total = s?.let { it.data + (if (includeModels) it.models else 0) + (if (includeVoices) it.voices else 0) }
                Button(enabled = working == null, onClick = { c.backup.dismiss(); createFile.launch(BackupManager.suggestedName()) }, modifier = Modifier.padding(top = 10.dp)) {
                    Text(if (total != null) "Create backup · ${BackupManager.formatBytes(total)}" else "Create backup")
                }
                Text("You choose where the file is saved. Photos and files in your own folders aren't part of it.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted, modifier = Modifier.padding(top = 4.dp))
            }
        }

        item(key = "restore") {
            ZedCard(accent = ZedColors.Violet) {
                SectionTitle("Restore")
                Text("Brings back a backup made on this phone or another one. It replaces what ZED holds on this phone now.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                Button(enabled = working == null, onClick = { c.backup.dismiss(); openFile.launch(arrayOf("*/*")) }, modifier = Modifier.padding(top = 8.dp)) { Text("Choose backup file") }
                pickError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ZedColors.Red, modifier = Modifier.padding(top = 6.dp)) }
                picked?.let { (uri, m) ->
                    Column(Modifier.padding(top = 10.dp)) {
                        Text("Backup from ${formatTime(m.createdAt)}", style = MaterialTheme.typography.titleSmall, color = ZedColors.Cyan)
                        Text(listOfNotNull(m.device.takeIf { it.isNotBlank() }, "ZED ${m.appVersion}", BackupManager.formatBytes(m.totalBytes)).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted)
                        val counts = m.counts.filterValues { it > 0 }.map { "${it.value} ${it.key}" }
                        Text("• " + (if (counts.isEmpty()) "Settings and routines" else counts.joinToString() + ", settings and routines"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                        Text("• AI models: " + if (m.models.isEmpty()) "not included" else m.models.joinToString(), style = MaterialTheme.typography.bodySmall)
                        Text("• Offline voices: " + if (m.voices.isEmpty()) "not included" else "${m.voices.size} pack${if (m.voices.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall)
                        Text("• Saved tokens: " + if (m.keyNames.isEmpty()) "not included" else "${m.keyNames.size}, password-protected", style = MaterialTheme.typography.bodySmall)
                        if (m.keyNames.isNotEmpty()) OutlinedTextField(
                            value = restorePassword, onValueChange = { restorePassword = it.take(64) }, singleLine = true, enabled = working == null,
                            label = { Text("Backup password (empty = skip the keys)") }, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                        Button(enabled = working == null && (state as? BackupState.Finished)?.restoring != true, modifier = Modifier.padding(top = 8.dp), onClick = {
                            val app = context.applicationContext
                            c.backup.startRestore(uri, restorePassword) {
                                // Let go of the model and voice files before they are replaced.
                                runCatching { com.melhive.zed.voice.WakeWordService.stop(app) }
                                runCatching { c.voice.unload() }
                                BackupManager.restartApp(app)
                            }
                        }) { Text("Restore now") }
                        Text("ZED checks the whole file first, then restarts by itself. If it doesn't reopen, open it again.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }

        item(key = "auto") {
            ZedCard(accent = ZedColors.Teal) {
                SectionTitle("Weekly automatic backup")
                Text("Once a week ZED saves your data and settings into a folder you choose and keeps the newest four files. Models, voices and saved tokens are left out, so each file stays small.",
                    style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                if (autoFolder.isBlank()) {
                    Button(onClick = { pickFolder.launch(null) }, modifier = Modifier.padding(top = 8.dp)) { Text("Choose a folder") }
                } else {
                    Text("On · saving to $autoFolderName", style = MaterialTheme.typography.titleSmall, color = ZedColors.Teal, modifier = Modifier.padding(top = 6.dp))
                    Text(
                        if (autoRunning) "Backing up now…" else settings?.autoBackupStatus.orEmpty().ifBlank { "The first backup runs within the hour." },
                        style = MaterialTheme.typography.bodySmall, color = if (settings?.autoBackupStatus.orEmpty().startsWith("Last attempt failed")) ZedColors.Red else ZedColors.Muted,
                    )
                    com.melhive.zed.ui.components.WrapRow {
                        TextButton(enabled = !autoRunning, onClick = { autoBackupNow(autoFolder) }) { Text("Back up now") }
                        TextButton(onClick = { pickFolder.launch(null) }) { Text("Change folder") }
                        TextButton(onClick = {
                            scope.launch {
                                c.settings.setAutoBackupFolder("")
                                com.melhive.zed.backup.AutoBackupWorker.schedule(context.applicationContext, false)
                            }
                        }) { Text("Turn off", color = ZedColors.Red) }
                    }
                }
            }
        }

        if (safetyCopies.isNotEmpty()) item(key = "safety") {
            ZedCard(accent = ZedColors.Amber) {
                SectionTitle("Safety copies")
                Text("Before an update changes how ZED stores your data, it keeps a copy of the data as it was. If something looks wrong after an update, put a copy back. Settings, models and voices stay as they are.",
                    style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                safetyCopies.forEach { copy ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(formatTime(copy.lastModified()), style = MaterialTheme.typography.titleSmall)
                            Text(BackupManager.formatBytes(copy.length()), style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted)
                        }
                        TextButton(enabled = working == null && (state as? BackupState.Finished)?.restoring != true, onClick = {
                            val app = context.applicationContext
                            c.backup.restoreSafetyCopy(copy) {
                                runCatching { com.melhive.zed.voice.WakeWordService.stop(app) }
                                runCatching { c.voice.unload() }
                                BackupManager.restartApp(app)
                            }
                        }) { Text("Put back") }
                    }
                }
            }
        }

        item(key = "howto") {
            ZedCard(accent = ZedColors.Teal) {
                SectionTitle("Moving to a new phone")
                listOf(
                    "Here: Create backup (include models and voices to skip re-downloading) and save the file.",
                    "Send the file to the new phone — Quick Share, USB cable, a memory card or cloud storage.",
                    "There: install ZED, open More → Backup & restore, choose the file and tap Restore now.",
                    "Allow ZED's permissions on the new phone and re-grant your folders in Files — Android doesn't let those travel.",
                ).forEachIndexed { i, t -> Text("${i + 1}. $t", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
