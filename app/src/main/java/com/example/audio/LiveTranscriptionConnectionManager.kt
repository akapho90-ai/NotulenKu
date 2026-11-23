package com.example.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * Owns the provider connection lifecycle for one transcription session.
 * Failed frames are not resent after a transport error to avoid duplicate audio
 * where delivery may have been ambiguous. Recovery applies to subsequent frames.
 */
class LiveTranscriptionConnectionManager(
    private val sessionId: String,
    private val provider: LiveTranscriptionProvider,
    private val maxReconnectAttempts: Int = 3,
    private val operationTimeoutMs: Long = 10_000L,
    private val initialBackoffMs: Long = 500L,
    private val maxBackoffMs: Long = 4_000L,
    private val onStatus: (LiveTranscriptionStatus, String?) -> Unit = { _, _ -> },
    private val onPartial: (LiveTranscriptEntry) -> Unit = {},
    private val onFinal: (LiveTranscriptEntry) -> Unit = {}
) {
    private var connected = false
    private var closed = false

    init {
        require(sessionId.isNotBlank())
        require(maxReconnectAttempts >= 0)
        require(operationTimeoutMs > 0)
        require(initialBackoffMs >= 0 && maxBackoffMs >= initialBackoffMs)
    }

    suspend fun connect(): Boolean {
        if (closed) return false
        if (connected) return true
        return connectWithRetries(maxReconnectAttempts)
    }

    /** Returns true if the frame was delivered without a transport exception. */
    suspend fun sendFrame(frame: LiveAudioFrame): Boolean {
        if (closed || frame.sessionId != sessionId) return false
        if (!connected && !connect()) return false
        try {
            withTimeout(operationTimeoutMs) { provider.sendFrame(frame) }
            return true
        } catch (timeout: TimeoutCancellationException) {
            disconnectQuietly()
            onStatus(LiveTranscriptionStatus.DEGRADED, "Pengiriman frame timeout; frame ini tidak dikirim ulang.")
            connectWithRetries(maxReconnectAttempts)
            return false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            disconnectQuietly()
            onStatus(LiveTranscriptionStatus.DEGRADED, "Koneksi transkripsi terputus; frame ini tidak dikirim ulang.")
            // Recovery is attempted for subsequent frames. This frame is intentionally not retried.
            connectWithRetries(maxReconnectAttempts)
            return false
        }
    }

    private suspend fun connectWithRetries(attempts: Int): Boolean {
        onStatus(LiveTranscriptionStatus.CONNECTING, null)
        var attempt = 0
        var backoff = initialBackoffMs
        while (!closed) {
            try {
                withTimeout(operationTimeoutMs) {
                    provider.open(sessionId, onPartial, onFinal)
                }
                connected = true
                onStatus(LiveTranscriptionStatus.LIVE, null)
                return true
            } catch (timeout: TimeoutCancellationException) {
                disconnectQuietly()
                if (attempt >= attempts) {
                    onStatus(LiveTranscriptionStatus.FALLBACK, "Koneksi provider timeout.")
                    return false
                }
                attempt++
                onStatus(LiveTranscriptionStatus.DEGRADED, "Koneksi provider timeout.")
                if (backoff > 0) delay(backoff)
                backoff = (backoff * 2).coerceAtMost(maxBackoffMs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                disconnectQuietly()
                if (attempt >= attempts) {
                    onStatus(LiveTranscriptionStatus.FALLBACK, failure.message ?: failure::class.java.simpleName)
                    return false
                }
                attempt++
                onStatus(LiveTranscriptionStatus.DEGRADED, failure.message ?: "Koneksi gagal.")
                if (backoff > 0) delay(backoff)
                backoff = (backoff * 2).coerceAtMost(maxBackoffMs)
            }
        }
        return false
    }

    private suspend fun disconnectQuietly() {
        connected = false
        try {
            withTimeout(operationTimeoutMs) { provider.close() }
        } catch (_: TimeoutCancellationException) {
            // A close timeout is best-effort; do not block lifecycle cleanup indefinitely.
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Provider close failures must not mask the original transport/lifecycle failure.
        }
    }

    suspend fun close() {
        if (closed) return
        closed = true
        disconnectQuietly()
        onStatus(LiveTranscriptionStatus.STOPPED, null)
    }
}
