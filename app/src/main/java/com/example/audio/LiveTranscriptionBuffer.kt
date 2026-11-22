package com.example.audio

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded queue to prevent audio-frame backlog from consuming unbounded memory.
 * Frames rejected at capacity must be surfaced as degraded transcription, not as
 * recording failure. Capture code must continue preserving the original audio.
 */
class LiveTranscriptionBuffer(capacity: Int = 32) {
    private val queue = Channel<LiveAudioFrame>(capacity.coerceAtLeast(1))
    private val count = AtomicInteger(0)

    fun offer(frame: LiveAudioFrame): Boolean {
        val accepted = queue.trySend(frame).isSuccess
        if (accepted) count.incrementAndGet()
        return accepted
    }

    fun poll(): LiveAudioFrame? = queue.tryReceive().getOrNull()?.also { count.decrementAndGet() }

    suspend fun receive(): LiveAudioFrame = queue.receive().also { count.decrementAndGet() }

    fun size(): Int = count.get()

    fun clear() {
        while (queue.tryReceive().isSuccess) count.decrementAndGet()
    }
}
