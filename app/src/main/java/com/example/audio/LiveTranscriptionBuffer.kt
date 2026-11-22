package com.example.audio

import java.util.concurrent.ArrayBlockingQueue

/**
 * Bounded queue to prevent audio-frame backlog from consuming unbounded memory.
 * Frames rejected at capacity must be surfaced as degraded transcription, not as
 * recording failure. Capture code must continue preserving the original audio.
 */
class LiveTranscriptionBuffer(capacity: Int = 32) {
    private val queue = ArrayBlockingQueue<LiveAudioFrame>(capacity.coerceAtLeast(1))

    fun offer(frame: LiveAudioFrame): Boolean = queue.offer(frame)
    fun poll(): LiveAudioFrame? = queue.poll()
    fun size(): Int = queue.size
    fun clear() = queue.clear()
}
