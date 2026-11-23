package com.example.audio

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental orchestration for a single AudioRecord source feeding live STT.
 * Isolated from MeetingRecordingService and the durable production recording path.
 */
class ExperimentalLiveCaptureSession(
    context: Context,
    private val sessionId: String,
    provider: LiveTranscriptionProvider,
    scope: CoroutineScope,
    sampleRateHz: Int = 16_000,
    queueCapacity: Int = 32,
    private val onCaptureError: (String) -> Unit = {}
) {
    private val adapter = LiveCaptureTranscriptionAdapter(sessionId, sampleRateHz, queueCapacity = queueCapacity)
    private val worker = LiveTranscriptionWorker(sessionId, adapter, provider, scope)
    private val pcmWriter = ExperimentalPcmSegmentWriter(File(context.filesDir, "experimental_live_pcm"), sessionId, sampleRateHz)
    private val failureReported = AtomicBoolean(false)

    private val capture = AudioRecordCaptureEngine(
        context = context,
        sampleRateHz = sampleRateHz,
        onPcmRead = { bytes, timestamp ->
            // Propagate writer/adapter failure to capture engine so capture stops, rather
            // than repeatedly invoking a broken writer while microphone capture continues.
            pcmWriter.append(bytes)
            adapter.acceptPcm(bytes, timestamp)
        },
        onError = { message ->
            LiveTranscriptRepository.setStatus(sessionId, LiveTranscriptionStatus.DEGRADED, message)
            if (failureReported.compareAndSet(false, true)) runCatching { onCaptureError(message) }
        }
    )

    @Synchronized
    fun start(): Boolean {
        if (capture.isCapturing) return true
        failureReported.set(false)
        try {
            pcmWriter.open()
        } catch (failure: Throwable) {
            val message = "Tidak dapat membuka file PCM eksperimental."
            LiveTranscriptRepository.setStatus(sessionId, LiveTranscriptionStatus.FALLBACK, message)
            runCatching { onCaptureError(message) }
            return false
        }
        if (!worker.start()) {
            runCatching { pcmWriter.close() }
            LiveTranscriptRepository.setStatus(sessionId, LiveTranscriptionStatus.FALLBACK, "Worker transkripsi tidak dapat dimulai.")
            return false
        }
        if (!capture.start()) {
            worker.stop()
            runCatching { pcmWriter.close() }
            return false
        }
        return true
    }

    /** Stops this experimental pipeline; production recording is never touched. */
    @Synchronized
    fun stop() {
        capture.stop()
        worker.stop()
        adapter.clear()
        runCatching { pcmWriter.close() }
    }

    val isCapturing: Boolean get() = capture.isCapturing
}
