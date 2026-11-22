package com.example.audio

/**
 * Splits arbitrary PCM16 byte reads into fixed-duration frames for streaming consumers.
 * Capture-source agnostic: does not open the microphone or perform network I/O.
 */
class PcmFrameAssembler(
    private val sessionId: String,
    sampleRateHz: Int = 16_000,
    frameDurationMs: Int = 100,
    private val frameBytes: Int = (sampleRateHz * frameDurationMs / 1000) * 2
) {
    init {
        require(sessionId.isNotBlank())
        require(sampleRateHz > 0)
        require(frameDurationMs > 0)
        require(frameBytes > 0 && frameBytes % 2 == 0) { "PCM16 frame size must be even." }
    }

    private val durationMs = frameDurationMs.toLong()
    private var carry = ByteArray(0)
    private var sessionStartTimestampMs: Long? = null
    private var sequence = 0L

    fun append(bytes: ByteArray, captureTimestampMs: Long): List<LiveAudioFrame> {
        if (bytes.isEmpty()) return emptyList()
        if (sessionStartTimestampMs == null) sessionStartTimestampMs = captureTimestampMs
        val combined = ByteArray(carry.size + bytes.size)
        carry.copyInto(combined, 0)
        bytes.copyInto(combined, carry.size)
        val frames = mutableListOf<LiveAudioFrame>()
        var offset = 0
        while (combined.size - offset >= frameBytes) {
            val frameData = combined.copyOfRange(offset, offset + frameBytes)
            frames += LiveAudioFrame(sessionId, sequence, sessionStartTimestampMs!! + sequence * durationMs, frameData)
            sequence++
            offset += frameBytes
        }
        carry = combined.copyOfRange(offset, combined.size)
        return frames
    }

    fun reset() { carry = ByteArray(0); sessionStartTimestampMs = null; sequence = 0L }
    val pendingByteCount: Int get() = carry.size
    val nextSequence: Long get() = sequence
}
