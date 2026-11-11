package com.melhive.zed.agents

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

data class ConfirmationRequest(
    val id: Long,
    val title: String,
    val message: String,
    val destructive: Boolean,
    val confirmLabel: String,
)

/**
 * Hands confirmation prompts from the execution pipeline to the UI and
 * suspends until the user decides. If no UI answers, the action never runs.
 */
class ConfirmationBroker {
    private val ids = AtomicLong(1)
    private val pending = mutableMapOf<Long, CompletableDeferred<Boolean>>()
    private val _requests = MutableStateFlow<List<ConfirmationRequest>>(emptyList())
    val requests: StateFlow<List<ConfirmationRequest>> = _requests.asStateFlow()

    suspend fun confirm(title: String, message: String, destructive: Boolean, confirmLabel: String = if (destructive) "Delete" else "Allow"): Boolean {
        val req = ConfirmationRequest(ids.getAndIncrement(), title, message, destructive, confirmLabel)
        val deferred = CompletableDeferred<Boolean>()
        synchronized(pending) { pending[req.id] = deferred }
        _requests.update { it + req }
        return try {
            deferred.await()
        } finally {
            synchronized(pending) { pending.remove(req.id) }
            _requests.update { list -> list.filterNot { it.id == req.id } }
        }
    }

    fun answer(id: Long, approved: Boolean) {
        synchronized(pending) { pending[id] }?.complete(approved)
    }
}
