package com.example.audio

/**
 * Connects raw PCM reads from a capture engine to the frame assembler and bounded
 * transcription queue. It intentionally performs no provider/network work.
 *
 * Queue overflow is counted and surfaced as a degraded signal; it never throws
 * back into the capture engine or alters the persisted recording path.
 */
class LiveCaptureTranscriptionAdapter(
    sessionId: String,
    sampleRateHz: Int = 16_000,
    frameDurationMs: Int = 100,
    queueCapacity: Int = 32
) {
    private val assembler = PcmFrameAssembler(sessionId, sampleRateHz, frameDurationMs)
    private val queue = LiveTranscriptionBuffer(queueCapacity)
    private var droppedFrames = 0L

    @Synchronized
    fun acceptPcm(bytes: ByteArray, captureTimestampMs: Long): Int {
        val frames = assembler.append(bytes, captureTimestampMs)
        var accepted = 0
        frames.forEach { frame ->
            if (queue.offer(frame)) accepted++ else droppedFrames++
        }
        return accepted
    }

    /** Non-blocking dequeue for tests/manual consumers. */
    fun pollFrame(): LiveAudioFrame? = queue.poll()

    /** Suspends until a frame is available for the provider worker. */
    suspend fun awaitFrame(): LiveAudioFrame = queue.receive()

    @Synchronized
    fun clear() {
        queue.clear()
        assembler.reset()
        droppedFrames = 0L
    }

    val queuedFrames: Int get() = queue.size()
    @Synchronized
    val droppedFrameCount: Long get() = droppedFrames
    val pendingAssemblerBytes: Int get() = assembler.pendingByteCount
}
