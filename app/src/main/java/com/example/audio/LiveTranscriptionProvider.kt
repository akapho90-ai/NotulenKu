package com.example.audio

/**
 * Provider-neutral live STT boundary. Implementations must be explicitly verified
 * against the provider's documented protocol before being wired to real traffic.
 */
enum class LiveProviderMode { STREAMING, WINDOWED_BATCH, BATCH_ONLY }

data class LiveAudioFrame(val sessionId: String, val sequence: Long, val timestampMs: Long, val bytes: ByteArray)

interface LiveTranscriptionProvider {
    val mode: LiveProviderMode
    suspend fun open(sessionId: String, onPartial: (LiveTranscriptEntry) -> Unit, onFinal: (LiveTranscriptEntry) -> Unit)
    suspend fun sendFrame(frame: LiveAudioFrame)
    suspend fun close()
}
