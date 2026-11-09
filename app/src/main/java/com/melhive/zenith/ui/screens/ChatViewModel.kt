package com.melhive.zenith.ui.screens

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.melhive.zenith.ZenithApp
import com.melhive.zenith.agents.AssistantPhase
import com.melhive.zenith.core.security.PathPolicy
import com.melhive.zenith.database.MessageEntity
import com.melhive.zenith.files.readNBytesCompat
import com.melhive.zenith.settings.IntelligenceMode
import com.melhive.zenith.voice.AndroidSpeechToText
import com.melhive.zenith.voice.SttEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Attachment(val name: String, val text: String)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as ZenithApp).container

    private val _conversationId = MutableStateFlow<Long?>(null)
    val conversationId: StateFlow<Long?> = _conversationId.asStateFlow()

    val messages: StateFlow<List<MessageEntity>> = _conversationId
        .flatMapLatest { id -> if (id == null) emptyFlow() else c.conversations.messages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val conversations = c.conversations.conversations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val busy: StateFlow<Boolean> = combine(c.orchestrator.activity, _conversationId) { a, id ->
        a.conversationId == id && a.phase in setOf(AssistantPhase.THINKING, AssistantPhase.EXECUTING, AssistantPhase.RESEARCHING, AssistantPhase.SEARCHING)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val activity = c.orchestrator.activity
    val lastUsage = c.orchestrator.lastUsage

    val attachment = MutableStateFlow<Attachment?>(null)
    val listening = MutableStateFlow(false)
    val voicePartial = MutableStateFlow("")
    val notice = MutableStateFlow<String?>(null)

    private var stt: AndroidSpeechToText? = null

    fun open(initialId: Long?) {
        if (_conversationId.value != null && initialId == null) return
        viewModelScope.launch { _conversationId.value = initialId ?: c.conversations.latestOrCreate() }
    }

    fun select(id: Long) { _conversationId.value = id }

    fun newConversation() { viewModelScope.launch { _conversationId.value = c.conversations.create() } }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            c.conversations.delete(id)
            if (_conversationId.value == id) _conversationId.value = c.conversations.latestOrCreate()
        }
    }

    fun send(text: String, viaVoice: Boolean = false) {
        val id = _conversationId.value ?: return
        val att = attachment.value
        if (text.isBlank() && att == null) return
        attachment.value = null
        c.orchestrator.submit(id, text.ifBlank { "Please review the attached file." }, att?.name, att?.text, voice = viaVoice) { reply ->
            viewModelScope.launch {
                val s = c.settings.current()
                if (viaVoice && s.voiceResponses && reply.text.isNotBlank()) {
                    if (c.chatTts.awaitReady()) { c.chatTts.setRate(s.speechRate); c.chatTts.speak(reply.text, flush = true) }
                }
            }
        }
    }

    fun stop() { c.orchestrator.cancelCurrent(); c.chatTts.stop() }

    fun setMode(mode: IntelligenceMode) { viewModelScope.launch { c.settings.setMode(mode) } }

    fun attach(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    var name = "attachment"
                    app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name }
                    val mime = app.contentResolver.getType(uri).orEmpty()
                    if (!PathPolicy.isTextFile(name) && !mime.startsWith("text/")) error("Only text documents can be attached (got ${mime.ifEmpty { name }})")
                    val bytes = app.contentResolver.openInputStream(uri)?.use { it.readNBytesCompat(100 * 1024) } ?: error("Could not read the file")
                    Attachment(name, String(bytes, Charsets.UTF_8))
                }
            }
            result.onSuccess { attachment.value = it }.onFailure { notice.value = it.message }
        }
    }

    fun clearAttachment() { attachment.value = null }

    fun toggleVoiceInput() {
        if (listening.value) { stt?.stop(); return }
        val engine = stt ?: AndroidSpeechToText(getApplication()).also { s ->
            stt = s
            viewModelScope.launch {
                s.events.collect { e ->
                    when (e) {
                        is SttEvent.Partial -> voicePartial.value = e.text
                        is SttEvent.Final -> { listening.value = false; voicePartial.value = ""; if (e.text.isNotBlank()) send(e.text, viaVoice = true) }
                        is SttEvent.Error -> { listening.value = false; voicePartial.value = ""; notice.value = e.message }
                        else -> Unit
                    }
                }
            }
        }
        c.chatTts.stop()
        listening.value = true
        engine.start()
    }

    override fun onCleared() {
        stt?.release()
        super.onCleared()
    }
}
