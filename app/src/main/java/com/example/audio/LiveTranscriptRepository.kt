package com.example.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Session-scoped transcript state. Partial text is replaced by windowId, not appended,
 * preventing the common partial-result duplication problem.
 *
 * This is a state holder only; network streaming/capture orchestration is implemented
 * separately and must checkpoint finalized entries before advancing its queue.
 */
object LiveTranscriptRepository {
    private val _state = MutableStateFlow(LiveTranscriptionState())
    val state: StateFlow<LiveTranscriptionState> = _state.asStateFlow()

    @Synchronized
    fun begin(sessionId: String, status: LiveTranscriptionStatus = LiveTranscriptionStatus.CONNECTING) {
        if (sessionId.isBlank()) return
        _state.value = LiveTranscriptionState(sessionId = sessionId, status = status)
    }

    @Synchronized
    fun setStatus(sessionId: String, status: LiveTranscriptionStatus, error: String? = null) {
        if (_state.value.sessionId != sessionId) return
        _state.value = _state.value.copy(
            status = status,
            lastError = error,
            lastUpdatedAtMs = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun upsert(sessionId: String, entry: LiveTranscriptEntry) {
        if (_state.value.sessionId != sessionId || entry.text.isBlank()) return
        val current = _state.value.entries
        val existingIndex = current.indexOfFirst { it.windowId == entry.windowId }
        // Ignore late provisional updates once a window has been committed.
        if (existingIndex >= 0 && current[existingIndex].isFinal && !entry.isFinal) return
        val updated = if (existingIndex >= 0) {
            current.toMutableList().apply { set(existingIndex, entry) }
        } else {
            current + entry
        }.sortedWith(compareBy<LiveTranscriptEntry> { it.startMs }.thenBy { it.windowId })
        _state.value = _state.value.copy(
            entries = updated,
            lastUpdatedAtMs = System.currentTimeMillis()
        )
    }

    /** Finalization locks a window's entry against accidental provisional replacement. */
    @Synchronized
    fun finalizeWindow(sessionId: String, windowId: String, finalText: String? = null) {
        if (_state.value.sessionId != sessionId) return
        val updated = _state.value.entries.map { entry ->
            if (entry.windowId != windowId) entry
            else entry.copy(text = finalText?.takeIf { it.isNotBlank() } ?: entry.text, isFinal = true)
        }
        _state.value = _state.value.copy(entries = updated, lastUpdatedAtMs = System.currentTimeMillis())
    }

    @Synchronized
    fun clear(sessionId: String) {
        if (_state.value.sessionId != sessionId) return
        _state.value = LiveTranscriptionState()
    }
}
