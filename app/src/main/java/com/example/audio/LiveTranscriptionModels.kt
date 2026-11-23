package com.example.audio

/**
 * UI-neutral state model for the independent live-transcription pipeline.
 * Audio capture remains authoritative; transcript failures must never stop recording.
 */
enum class LiveTranscriptionStatus {
    IDLE, CONNECTING, LIVE, FALLBACK, DEGRADED, STOPPED
}

data class LiveTranscriptEntry(
    val id: String,
    val windowId: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val speaker: String? = null,
    val isFinal: Boolean = false
)

data class LiveTranscriptionState(
    val sessionId: String = "",
    val status: LiveTranscriptionStatus = LiveTranscriptionStatus.IDLE,
    val entries: List<LiveTranscriptEntry> = emptyList(),
    val lastError: String? = null,
    val lastUpdatedAtMs: Long = 0L
)
