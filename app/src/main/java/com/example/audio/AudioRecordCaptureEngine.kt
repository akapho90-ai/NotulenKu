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

/**
 * Experimental microphone PCM capture, deliberately not wired into MeetingRecordingService.
 *
 * This engine emits copied PCM16 mono frames from AudioRecord. Callbacks run on its
 * capture thread. The caller must keep a durable recorder path separate until this
 * engine has been validated on supported devices.
 */
class AudioRecordCaptureEngine(
    private val context: Context,
    private val sampleRateHz: Int = 16_000,
    private val onPcmRead: (bytes: ByteArray, captureTimestampMs: Long) -> Unit,
    private val onError: (message: String) -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var captureThread: Thread? = null

    fun start(): Boolean {
        if (running.get()) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("Izin mikrofon belum diberikan.")
            return false
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            onError("Ukuran buffer AudioRecord tidak valid: $minBuffer")
            return false
        }

        val bufferSize = maxOf(minBuffer * 2, sampleRateHz * 2 / 5)
        val created = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            onError("Gagal membuat AudioRecord: ${e.localizedMessage ?: "unknown error"}")
            return false
        }

        if (created.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { created.release() }
            onError("AudioRecord tidak berhasil diinisialisasi.")
            return false
        }

        if (!running.compareAndSet(false, true)) {
            runCatching { created.release() }
            return true
        }
        recorder = created
        try {
            created.startRecording()
            if (created.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                stop()
                onError("AudioRecord gagal memulai capture.")
                return false
            }
        } catch (e: Exception) {
            stop()
            onError("Gagal memulai capture: ${e.localizedMessage ?: "unknown error"}")
            return false
        }

        val thread = Thread({ captureLoop(created, bufferSize) }, "NotulenKu-PcmCapture")
        captureThread = thread
        thread.start()
        return true
    }

    private fun captureLoop(source: AudioRecord, bufferSize: Int) {
        val readBuffer = ByteArray(bufferSize.coerceAtMost(sampleRateHz * 2))
        try {
            while (running.get()) {
                val count = source.read(readBuffer, 0, readBuffer.size)
                if (count > 0 && running.get()) {
                    val copy = readBuffer.copyOf(count)
                    runCatching { onPcmRead(copy, SystemClock.elapsedRealtime()) }
                        .onFailure { onError("Callback PCM gagal: ${it.localizedMessage ?: "unknown error"}") }
                } else if (count < 0 && running.get()) {
                    onError("AudioRecord read error: $count")
                    break
                }
            }
        } catch (e: Exception) {
            if (running.get()) onError("Capture berhenti: ${e.localizedMessage ?: "unknown error"}")
        } finally {
            running.set(false)
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        val source = recorder
        recorder = null
        if (source != null) {
            runCatching { if (source.recordingState == AudioRecord.RECORDSTATE_RECORDING) source.stop() }
            runCatching { source.release() }
        }
        val thread = captureThread
        captureThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(500L) }
        }
    }

    val isCapturing: Boolean get() = running.get()
}
