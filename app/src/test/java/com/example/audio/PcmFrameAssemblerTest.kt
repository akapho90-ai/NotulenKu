package com.example.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmFrameAssemblerTest {
    @Test fun combinesPartialReadsIntoFrames() {
        val assembler = PcmFrameAssembler("s", sampleRateHz = 1000, frameDurationMs = 100)
        assertEquals(0, assembler.append(ByteArray(90), 1000).size)
        assertEquals(90, assembler.pendingByteCount)
        val frames = assembler.append(ByteArray(110), 1100)
        assertEquals(1, frames.size)
        assertEquals(0L, frames[0].sequence)
        assertEquals(1000L, frames[0].timestampMs)
        assertEquals(200, frames[0].bytes.size)
        assertEquals(0, assembler.pendingByteCount)
    }

    @Test fun emitsOrderedFramesAndRetainsTail() {
        val assembler = PcmFrameAssembler("session", sampleRateHz = 1000, frameDurationMs = 100)
        val frames = assembler.append(ByteArray(450), 500)
        assertEquals(2, frames.size)
        assertEquals(0L, frames[0].sequence)
        assertEquals(1L, frames[1].sequence)
        assertEquals(500L, frames[0].timestampMs)
        assertEquals(600L, frames[1].timestampMs)
        assertEquals(50, assembler.pendingByteCount)
    }

    @Test fun resetClearsCarryAndSequence() {
        val assembler = PcmFrameAssembler("s", sampleRateHz = 1000, frameDurationMs = 100)
        assembler.append(ByteArray(220), 0)
        assertEquals(1L, assembler.nextSequence)
        assembler.reset()
        assertEquals(0L, assembler.nextSequence)
        assertEquals(0, assembler.pendingByteCount)
    }
}
