package com.example.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Isolated consumer for the capture-to-transcription queue.
 * It never owns or stops the audio recorder.
 */
class LiveTranscriptionWorker(
    private val sessionId: String,
    private val adapter: LiveCaptureTranscriptionAdapter,
    private val provider: LiveTranscriptionProvider,
    private val scope: CoroutineScope
) {
    private var job: Job? = null

    @Synchronized
    fun start(): Boolean {
        if (job?.isActive == true || sessionId.isBlank()) return false
        LiveTranscriptRepository.begin(sessionId, LiveTranscriptionStatus.CONNECTING)
        job = scope.launch {
            val manager = LiveTranscriptionConnectionManager(
                sessionId = sessionId,
                provider = provider,
                onStatus = { status, error -> LiveTranscriptRepository.setStatus(sessionId, status, error) },
                onPartial = { LiveTranscriptRepository.upsert(sessionId, it.copy(isFinal = false)) },
                onFinal = { LiveTranscriptRepository.upsert(sessionId, it.copy(isFinal = true)) }
            )
            try {
                if (!manager.connect()) return@launch
                while (true) {
                    val frame = adapter.awaitFrame()
                    manager.sendFrame(frame)
                    if (adapter.droppedFrameCount > 0) {
                        LiveTranscriptRepository.setStatus(
                            sessionId,
                            LiveTranscriptionStatus.DEGRADED,
                            "Sebagian frame audio transkripsi terlewat. Rekaman utama tetap aman."
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                LiveTranscriptRepository.setStatus(
                    sessionId, LiveTranscriptionStatus.FALLBACK,
                    failure.message ?: failure::class.java.simpleName
                )
            } finally {
                try { manager.close() } catch (_: Throwable) { /* best-effort shutdown */ }
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        LiveTranscriptRepository.setStatus(sessionId, LiveTranscriptionStatus.STOPPED)
    }

    val isRunning: Boolean get() = job?.isActive == true
}
