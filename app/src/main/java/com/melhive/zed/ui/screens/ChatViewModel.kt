package com.melhive.zed.ui.screens

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.melhive.zed.ZedApp
import com.melhive.zed.agents.AssistantPhase
import com.melhive.zed.core.security.PathPolicy
import com.melhive.zed.database.MessageEntity
import com.melhive.zed.files.readNBytesCompat
import com.melhive.zed.settings.IntelligenceMode
import com.melhive.zed.voice.AndroidSpeechToText
import com.melhive.zed.voice.SttEvent
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
    private val c = (app as ZedApp).container

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
    /** Photo to send with the next message ("what's in this picture?"). */
    val photo = MutableStateFlow<com.melhive.zed.intelligence.ImageInput?>(null)

    fun attachPhoto(uri: android.net.Uri) {
        viewModelScope.launch {
            runCatching { com.melhive.zed.intelligence.ImageInput.fromUri(getApplication(), uri) }
                .onSuccess { photo.value = it }.onFailure { notice.value = "Couldn't use that photo: ${it.message}" }
        }
    }
    val listening = MutableStateFlow(false)
    val voicePartial = MutableStateFlow("")
    val notice = MutableStateFlow<String?>(null)

    private var stt: AndroidSpeechToText? = null

    init {
        viewModelScope.launch {
            c.chatFocus.collect { id -> if (id != null) { _conversationId.value = id; c.chatFocus.value = null } }
        }
    }

    fun open(initialId: Long?) {
        if (initialId != null) { _conversationId.value = initialId; return }
        if (_conversationId.value != null) return
        viewModelScope.launch {
            val id = c.conversations.latestOrCreate()
            if (_conversationId.value == null) _conversationId.value = id
        }
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
        val img = photo.value
        if (text.isBlank() && att == null && img == null) return
        attachment.value = null
        photo.value = null
        val prompt = text.ifBlank { if (img != null) "What do you see in this photo?" else "Please review the attached file." }
        c.orchestrator.submit(id, prompt, att?.name, att?.text, voice = viaVoice, image = img) { reply ->
            viewModelScope.launch {
                val s = c.settings.current()
                if (viaVoice && s.voiceResponses && reply.text.isNotBlank()) {
                    if (c.chatTts.awaitReady()) { c.chatTts.setRate(s.speechRate); c.chatTts.speak(reply.text, flush = true) }
                }
            }
        }
    }

    fun regenerate() { _conversationId.value?.let { c.chatTts.stop(); c.orchestrator.regenerate(it) } }

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
