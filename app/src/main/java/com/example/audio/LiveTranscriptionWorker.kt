package com.example.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Isolated consumer for the capture-to-transcription queue.
 * It never owns or stops the audio recorder and does not attempt provider-specific fallback.
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
            try {
                provider.open(
                    sessionId,
                    onPartial = { LiveTranscriptRepository.upsert(sessionId, it.copy(isFinal = false)) },
                    onFinal = { LiveTranscriptRepository.upsert(sessionId, it.copy(isFinal = true)) }
                )
                LiveTranscriptRepository.setStatus(sessionId, LiveTranscriptionStatus.LIVE)
                while (true) provider.sendFrame(adapter.awaitFrame())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                LiveTranscriptRepository.setStatus(
                    sessionId, LiveTranscriptionStatus.FALLBACK,
                    failure.message ?: failure::class.java.simpleName
                )
            } finally {
                try { provider.close() } catch (_: Throwable) { /* best-effort shutdown */ }
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
