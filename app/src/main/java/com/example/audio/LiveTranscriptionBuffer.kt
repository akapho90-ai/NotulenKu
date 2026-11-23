package com.example.audio

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded queue to prevent audio-frame backlog from consuming unbounded memory.
 * Capture enqueue is non-blocking; rejected frames never affect the recorder.
 */
class LiveTranscriptionBuffer(capacity: Int = 32) {
    private val queue = Channel<LiveAudioFrame>(capacity.coerceAtLeast(1))
    private val count = AtomicInteger(0)

    fun offer(frame: LiveAudioFrame): Boolean {
        count.incrementAndGet()
        if (queue.trySend(frame).isSuccess) return true
        count.decrementAndGet()
        return false
    }

    fun poll(): LiveAudioFrame? = queue.tryReceive().getOrNull()?.also { count.decrementAndGet() }
    suspend fun receive(): LiveAudioFrame = queue.receive().also { count.decrementAndGet() }
    fun size(): Int = count.get().coerceAtLeast(0)
    fun clear() { while (queue.tryReceive().isSuccess) count.decrementAndGet() }
}
