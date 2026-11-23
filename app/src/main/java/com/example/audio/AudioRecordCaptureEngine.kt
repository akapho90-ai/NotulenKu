package com.example.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/** Experimental PCM capture engine, intentionally not wired to the production recorder. */
class AudioRecordCaptureEngine(
    private val context: Context,
    private val sampleRateHz: Int = 16_000,
    private val onPcmRead: (bytes: ByteArray, captureTimestampMs: Long) -> Unit,
    private val onError: (message: String) -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var captureThread: Thread? = null

    @Synchronized
    fun start(): Boolean {
        if (running.get()) return true
        // Reap any previous capture thread before creating a new recorder.
        val oldThread = captureThread
        if (oldThread != null && oldThread !== Thread.currentThread()) {
            runCatching { oldThread.join(500L) }
            if (oldThread.isAlive) {
                report("Capture thread sebelumnya belum berhenti.")
                return false
            }
            captureThread = null
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            report("Izin mikrofon belum diberikan.")
            return false
        }
        val minBuffer = AudioRecord.getMinBufferSize(sampleRateHz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            report("Ukuran buffer AudioRecord tidak valid: $minBuffer")
            return false
        }
        val bufferSize = maxOf(minBuffer * 2, sampleRateHz * 2 / 5)
        val created = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, sampleRateHz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        } catch (e: Exception) {
            report("Gagal membuat AudioRecord: ${e.localizedMessage ?: "unknown error"}")
            return false
        }
        if (created.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { created.release() }
            report("AudioRecord tidak berhasil diinisialisasi.")
            return false
        }
        recorder = created
        running.set(true)
        try {
            created.startRecording()
            if (created.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                releaseRecorder(created)
                running.set(false)
                report("AudioRecord gagal memulai capture.")
                return false
            }
        } catch (e: Exception) {
            running.set(false)
            releaseRecorder(created)
            report("Gagal memulai capture: ${e.localizedMessage ?: "unknown error"}")
            return false
        }
        val thread = Thread({ captureLoop(created, bufferSize) }, "NotulenKu-PcmCapture")
        captureThread = thread
        try {
            thread.start()
        } catch (e: Exception) {
            running.set(false)
            releaseRecorder(created)
            captureThread = null
            report("Thread capture gagal dimulai.")
            return false
        }
        return true
    }

    private fun captureLoop(source: AudioRecord, bufferSize: Int) {
        val readBuffer = ByteArray(bufferSize.coerceAtMost(sampleRateHz * 2))
        try {
            while (running.get()) {
                val count = source.read(readBuffer, 0, readBuffer.size)
                if (count > 0 && running.get()) {
                    try {
                        onPcmRead(readBuffer.copyOf(count), SystemClock.elapsedRealtime())
                    } catch (failure: Throwable) {
                        running.set(false)
                        report("Callback PCM gagal; capture dihentikan: ${failure.localizedMessage ?: "unknown error"}")
                        runCatching { if (source.recordingState == AudioRecord.RECORDSTATE_RECORDING) source.stop() }
                        break
                    }
                } else if (count < 0 && running.get()) {
                    running.set(false)
                    report("AudioRecord read error: $count")
                    break
                }
            }
        } catch (failure: Throwable) {
            if (running.getAndSet(false)) report("Capture berhenti: ${failure.localizedMessage ?: "unknown error"}")
        } finally {
            running.set(false)
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        val source = recorder
        if (source != null) releaseRecorder(source)
        val thread = captureThread
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(1_000L) }
            if (!thread.isAlive) captureThread = null
        } else if (thread === Thread.currentThread()) {
            captureThread = null
        }
    }

    private fun releaseRecorder(source: AudioRecord) {
        if (recorder === source) recorder = null
        runCatching { if (source.recordingState == AudioRecord.RECORDSTATE_RECORDING) source.stop() }
        runCatching { source.release() }
    }

    private fun report(message: String) { runCatching { onError(message) } }

    val isCapturing: Boolean get() = running.get()
}
