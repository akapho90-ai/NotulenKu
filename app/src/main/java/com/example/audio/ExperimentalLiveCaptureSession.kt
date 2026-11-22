package com.example.audio

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * Experimental orchestration for a single AudioRecord source feeding live STT.
 *
 * This is deliberately NOT wired to MeetingRecordingService or the durable recording
 * path. Until a PCM durable writer/encoder and recovery path exist, this session
 * must only be used in isolated experiments; it must not replace production recording.
 */
class ExperimentalLiveCaptureSession(
    context: Context,
    sessionId: String,
    provider: LiveTranscriptionProvider,
    scope: CoroutineScope,
    sampleRateHz: Int = 16_000,
    queueCapacity: Int = 32,
    onCaptureError: (String) -> Unit = {}
) {
    private val adapter = LiveCaptureTranscriptionAdapter(
        sessionId = sessionId,
        sampleRateHz = sampleRateHz,
        queueCapacity = queueCapacity
    )
    private val worker = LiveTranscriptionWorker(sessionId, adapter, provider, scope)
    private val pcmWriter = ExperimentalPcmSegmentWriter(
        directory = java.io.File(context.filesDir, "experimental_live_pcm"),
        sessionId = sessionId,
        sampleRateHz = sampleRateHz
    )
    private val capture = AudioRecordCaptureEngine(
        context = context,
        sampleRateHz = sampleRateHz,
        onPcmRead = { bytes, timestamp ->
            try {
                pcmWriter.append(bytes)
                adapter.acceptPcm(bytes, timestamp)
            } catch (failure: Throwable) {
                LiveTranscriptRepository.setStatus(sessionId, LiveTranscriptionStatus.DEGRADED, "Penyimpanan PCM eksperimental gagal: ${failure.localizedMessage ?: "error"}")
                onCaptureError("Penyimpanan PCM eksperimental gagal.")
            }
        },
        onError = { message ->
            LiveTranscriptRepository.setStatus(sessionId, LiveTranscriptionStatus.DEGRADED, message)
            onCaptureError(message)
        }
    )

    /** Starts STT worker and then capture; on capture startup failure, worker is stopped. */
    fun start(): Boolean {
        if (capture.isCapturing) return true
        try { pcmWriter.open() } catch (failure: Throwable) {
            onCaptureError("Tidak dapat membuka file PCM eksperimental.")
            return false
        }
        worker.start()
        if (!capture.start()) {
            worker.stop()
            runCatching { pcmWriter.close() }
            return false
        }
        return true
    }

    /** Stops only this experimental capture and transcription pipeline. */
    fun stop() {
        capture.stop()
        worker.stop()
        adapter.clear()
        runCatching { pcmWriter.close() }
    }

    val isCapturing: Boolean get() = capture.isCapturing
}
