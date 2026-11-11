package com.melhive.zenith.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melhive.zenith.android.DeviceInfo
import com.melhive.zenith.intelligence.LocalModelRuntime
import com.melhive.zenith.models.Compatibility
import com.melhive.zenith.models.ModelCatalog
import com.melhive.zenith.security.SecretStore
import com.melhive.zenith.settings.IntelligenceMode
import com.melhive.zenith.settings.LocalBackend
import com.melhive.zenith.settings.OnlineProviderKind
import com.melhive.zenith.ui.components.SectionTitle
import com.melhive.zenith.ui.components.StatusPill
import com.melhive.zenith.ui.components.WrapRow
import com.melhive.zenith.ui.components.ZenithCard
import com.melhive.zenith.ui.theme.ZenithColors

@Composable
fun ModelsScreen(onBack: () -> Unit, onOpenTasks: () -> Unit) {
    val vm: ModelsViewModel = viewModel()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val runtime by vm.runtimeState.collectAsStateWithLifecycle()
    val secrets by vm.configuredSecrets.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val available by vm.availableModels.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }
    LaunchedEffect(Unit) { vm.refreshDevice() }
    val s = settings ?: return

    ListScreen("Model Manager", onBack = onBack, subtitle = "Native · On-device · Online") {
        message?.let { m -> item { ZenithCard(accent = ZenithColors.Amber) { Text(m); TextButton(onClick = { vm.message.value = null }) { Text("Dismiss") } } } }

        item {
            ZenithCard {
                SectionTitle("Active intelligence")
                WrapRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(s.mode == IntelligenceMode.AUTO, { vm.setMode(IntelligenceMode.AUTO) }, { Text("Auto") })
                    FilterChip(s.mode == IntelligenceMode.NATIVE_ONLY, { vm.setMode(IntelligenceMode.NATIVE_ONLY) }, { Text("Native") })
                    FilterChip(s.mode == IntelligenceMode.LOCAL, { vm.setMode(IntelligenceMode.LOCAL) }, { Text("On-device") })
                    FilterChip(s.mode == IntelligenceMode.ONLINE, { vm.setMode(IntelligenceMode.ONLINE) }, { Text("Online") })
                }
                Text(
                    when (s.mode) {
                        IntelligenceMode.AUTO -> "Uses the best available: ${s.onlineProvider.label} when its key is set and you're online, otherwise the on-device model, otherwise native commands."
                        IntelligenceMode.NATIVE_ONLY -> "Deterministic commands only. No model, no network."
                        IntelligenceMode.LOCAL -> "Free-form requests use the active on-device model. Native commands still run first."
                        IntelligenceMode.ONLINE -> "Free-form requests go to ${s.onlineProvider.label} (${s.modelFor(s.onlineProvider).ifBlank { "model not set" }}). Native commands still run locally."
                    },
                    style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted,
                )
            }
        }

        item {
            ZenithCard(accent = ZenithColors.Teal) {
                SectionTitle("This device")
                Text("${device.model} · Android ${device.androidVersion} (API ${device.sdk})")
                Text("RAM ${DeviceInfo.formatBytes(device.totalRamBytes)} (available ${DeviceInfo.formatBytes(device.availRamBytes)}) · Free storage ${DeviceInfo.formatBytes(device.freeStorageBytes)}", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
                Text("ABIs: ${device.abis.joinToString()} · Runtime: LiteRT-LM 0.19.1", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
                Spacer(Modifier.height(8.dp))
                Text("Runtime: " + when (val r = runtime) {
                    LocalModelRuntime.State.Unloaded -> "no model loaded"
                    is LocalModelRuntime.State.Loading -> "loading ${r.modelId}…"
                    is LocalModelRuntime.State.Ready -> "${r.modelId} loaded on ${r.backend} in ${r.loadMillis} ms, ${r.contextTokens} token context"
                    is LocalModelRuntime.State.Failed -> "failed to load ${r.modelId}: ${r.message}"
                })
                WrapRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(s.localBackend == LocalBackend.CPU, { vm.setBackend(LocalBackend.CPU) }, { Text("CPU") })
                    FilterChip(s.localBackend == LocalBackend.GPU, { vm.setBackend(LocalBackend.GPU) }, { Text("GPU") })
                    if (runtime is LocalModelRuntime.State.Ready) TextButton(onClick = { vm.unload() }) { Text("Unload (free RAM)") }
                }
                Text("Context size (how much conversation the model can hold)", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                WrapRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Auto", 2048 to "2K", 4096 to "4K", 8192 to "8K").forEach { (v, label) ->
                        FilterChip(s.localContextTokens == v, { vm.setContextTokens(v) }, { Text(label) })
                    }
                }
                Text("Auto picks ${LocalModelRuntime.autoContextTokens(device.totalRamBytes)} tokens for this phone. Larger holds longer chats but uses more memory. GPU is usually faster if your phone supports it.",
                    style = MaterialTheme.typography.labelSmall, color = ZenithColors.Muted)
            }
        }

        item { SectionTitle("Installed models (${installed.size})") }
        if (installed.isEmpty()) item { Text("No on-device models installed. Download one below or import a .litertlm file.", color = ZenithColors.Muted) }
        items(installed, key = { it.id }) { m ->
            val active = s.activeLocalModelId == m.id
            ZenithCard(accent = if (active) ZenithColors.Cyan else ZenithColors.Outline) {
                Row { Text(m.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); if (active) StatusPill("Active", ZenithColors.Cyan) }
                Text("${DeviceInfo.formatBytes(m.sizeBytes)} · ${m.source} · ${if (m.verified) "SHA-256 verified" else "unverified import"}", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
                WrapRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    if (!active) Button(onClick = { vm.activate(m) }) { Text("Use") }
                    OutlinedButton(onClick = { vm.load(m) }) { Text("Load now") }
                    TextButton(onClick = { vm.remove(m) }) { Text("Remove", color = ZenithColors.Red) }
                }
            }
        }
        item {
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }, enabled = importing == null, modifier = Modifier.fillMaxWidth()) {
                Text(importing ?: "Import .litertlm file")
            }
            Text("Models downloaded inside other apps (e.g. AI Edge Gallery) are stored in that app's private storage and cannot be reused; download or import a copy for ZED.",
                style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
        }

        val profile = com.melhive.zenith.models.DeviceProfile(
            device.totalRamBytes, device.freeStorageBytes, DeviceInfo.performanceCores(), DeviceInfo.soc(), device.arm64,
        )
        val recommended = com.melhive.zenith.models.ModelCatalog.recommend(profile)
        item {
            ZenithCard(accent = ZenithColors.Cyan) {
                SectionTitle("Your phone")
                Text("${device.model} · ${profile.soc}", style = MaterialTheme.typography.titleSmall)
                Text("${DeviceInfo.formatBytes(device.totalRamBytes)} memory · ${profile.performanceCores} fast CPU cores · GPU ${if (profile.gpuLikely) "supported" else "not supported"} · ${DeviceInfo.formatBytes(device.freeStorageBytes)} free",
                    style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
                recommended?.let { r ->
                    Text("Best match: ${r.name}", style = MaterialTheme.typography.titleMedium, color = ZenithColors.Green, modifier = Modifier.padding(top = 8.dp))
                    Text("Chosen for your phone's memory and processor: the smartest model that still replies quickly here.", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
                }
            }
        }
        item { SectionTitle("Download a model") }
        val ranked = com.melhive.zenith.models.ModelCatalog.models.map { it to com.melhive.zenith.models.ModelCatalog.assess(it, profile) }
            .sortedWith(compareBy({ it.first.id != recommended?.id }, { it.second.fit.rank }, { -it.first.quality }))
        items(ranked, key = { "cat-" + it.first.id }) { (m, a) ->
            val isRecommended = m.id == recommended?.id
            val fit = if (isRecommended) com.melhive.zenith.models.Fit.RECOMMENDED else a.fit
            val color = when (fit) {
                com.melhive.zenith.models.Fit.RECOMMENDED, com.melhive.zenith.models.Fit.GOOD -> ZenithColors.Green
                com.melhive.zenith.models.Fit.SLOW -> ZenithColors.Amber
                else -> ZenithColors.Red
            }
            val isInstalled = installed.any { it.id == m.id }
            val taskId = downloads[m.id]
            ZenithCard(accent = color) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    StatusPill(fit.label, color)
                }
                Text(m.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                WrapRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    m.bestFor.forEach { StatusPill(it, ZenithColors.Violet) }
                    if (m.reasoning) StatusPill("Thinks first", ZenithColors.Amber)
                    if (m.verified) StatusPill("Tested with ZED", ZenithColors.Teal)
                }
                Row(Modifier.padding(top = 6.dp)) {
                    Text("Quality ${"●".repeat(m.quality)}${"○".repeat(5 - m.quality)}", style = MaterialTheme.typography.labelMedium, color = ZenithColors.Cyan, modifier = Modifier.weight(1f))
                    Text("Speed ${"●".repeat(m.speed)}${"○".repeat(5 - m.speed)}", style = MaterialTheme.typography.labelMedium, color = ZenithColors.Cyan)
                }
                Text(a.reason, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(top = 4.dp))
                Text("${DeviceInfo.formatBytes(m.sizeBytes)} download · runs on ${m.recommendedBackend} · ${m.license}",
                    style = MaterialTheme.typography.labelSmall, color = ZenithColors.Muted)
                WrapRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    when {
                        isInstalled -> StatusPill("Installed", ZenithColors.Green)
                        taskId != null -> OutlinedButton(onClick = onOpenTasks) { Text("Downloading — view task #$taskId") }
                        else -> Button(onClick = { vm.download(m) }, enabled = fit != com.melhive.zenith.models.Fit.INCOMPATIBLE && fit != com.melhive.zenith.models.Fit.TOO_BIG) { Text("Download") }
                    }
                    TextButton(onClick = { uri.openUri(m.pageUrl) }) { Text("Model page") }
                }
            }
        }

        item { SectionTitle("Online providers") }
        item {
            ZenithCard(accent = ZenithColors.Violet) {
                WrapRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OnlineProviderKind.entries.forEach { k ->
                        FilterChip(s.onlineProvider == k, { vm.setProvider(k) }, { Text(when (k) { OnlineProviderKind.GEMINI -> "Gemini"; OnlineProviderKind.OPENAI -> "OpenAI"; OnlineProviderKind.ANTHROPIC -> "Claude"; OnlineProviderKind.OPENAI_COMPATIBLE -> "Custom" }) })
                    }
                }
                val kind = s.onlineProvider
                val hasKey = SecretStore.apiKeyName(kind) in secrets
                var keyInput by remember(kind) { mutableStateOf("") }
                var modelInput by remember(kind, s.onlineModels[kind]) { mutableStateOf(s.modelFor(kind)) }
                var urlInput by remember(s.compatibleBaseUrl) { mutableStateOf(s.compatibleBaseUrl) }
                var modelMenu by remember { mutableStateOf(false) }
                Spacer(Modifier.height(8.dp))
                Text(kind.label, style = MaterialTheme.typography.titleMedium)
                if (kind == OnlineProviderKind.OPENAI_COMPATIBLE) {
                    OutlinedTextField(urlInput, { urlInput = it }, label = { Text("Base URL (e.g. https://host/v1)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { vm.setBaseUrl(urlInput) }) { Text("Save URL") }
                }
                OutlinedTextField(
                    keyInput, { keyInput = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (hasKey) "API key saved — enter to replace" else "API key") },
                    visualTransformation = PasswordVisualTransformation(),
                )
                WrapRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = keyInput.isNotBlank(), onClick = { vm.saveKey(kind, keyInput); keyInput = "" }) { Text("Save key") }
                    if (hasKey) TextButton(onClick = { vm.removeKey(kind) }) { Text("Remove key", color = ZenithColors.Red) }
                }
                Box {
                    OutlinedTextField(modelInput, { modelInput = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        available[kind].orEmpty().take(80).forEach { id ->
                            DropdownMenuItem(text = { Text(id) }, onClick = { modelInput = id; vm.setModel(kind, id); modelMenu = false })
                        }
                    }
                }
                WrapRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.setModel(kind, modelInput) }) { Text("Save model") }
                    TextButton(onClick = { vm.fetchModels(kind); modelMenu = true }) { Text("List models") }
                    TextButton(enabled = !testing, onClick = { vm.testProvider(kind) }) { Text(if (testing) "Testing…" else "Test connection") }
                }
                Text("Keys are encrypted with Android Keystore and only sent to ${kind.label}. Online use sends your messages (and any retrieved document excerpts) to that provider.",
                    style = MaterialTheme.typography.labelSmall, color = ZenithColors.Muted)
            }
        }
        item {
            ZenithCard {
                SectionTitle("Hugging Face token (optional)")
                var hf by remember { mutableStateOf("") }
                Text(if (SecretStore.HF_TOKEN in secrets) "Token saved." else "Not needed for the catalog models (public, Apache-2.0).", style = MaterialTheme.typography.bodySmall, color = ZenithColors.Muted)
                OutlinedTextField(hf, { hf = it }, singleLine = true, label = { Text("hf_…") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                TextButton(enabled = hf.isNotBlank(), onClick = { vm.saveHfToken(hf); hf = "" }) { Text("Save token") }
            }
        }
    }
}
