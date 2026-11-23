package com.example.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Isolated consumer for the bounded capture-to-transcription queue. */
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
                var lastReportedDropCount = 0L
                while (true) {
                    val frame = adapter.awaitFrame()
                    manager.sendFrame(frame)
                    val dropped = adapter.droppedFrameCount
                    if (dropped > lastReportedDropCount) {
                        lastReportedDropCount = dropped
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
                LiveTranscriptRepository.setStatus(sessionId, LiveTranscriptionStatus.FALLBACK, failure.message ?: failure::class.java.simpleName)
            } finally {
                try { manager.close() } catch (_: Throwable) { /* best effort */ }
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        val current = job
        job = null
        current?.cancel()
        LiveTranscriptRepository.setStatus(sessionId, LiveTranscriptionStatus.STOPPED)
    }

    val isRunning: Boolean get() = job?.isActive == true
}
