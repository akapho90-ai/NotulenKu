package com.example.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class LiveCaptureTranscriptionAdapterTest {
    @Test
    fun convertsPcmReadsIntoFramesAndQueuesThem() {
        val adapter = LiveCaptureTranscriptionAdapter(
            sessionId = "test",
            sampleRateHz = 1000,
            frameDurationMs = 100,
            queueCapacity = 4
        )
        assertEquals(1, adapter.acceptPcm(ByteArray(200), 500))
        val frame = adapter.pollFrame()
        assertNotNull(frame)
        assertEquals("test", frame?.sessionId)
        assertEquals(0L, frame?.sequence)
        assertEquals(500L, frame?.timestampMs)
        assertEquals(0, adapter.queuedFrames)
    }

    @Test
    fun countsOverflowWithoutThrowingOrLosingQueuedFrame() {
        val adapter = LiveCaptureTranscriptionAdapter(
            sessionId = "test",
            sampleRateHz = 1000,
            frameDurationMs = 100,
            queueCapacity = 1
        )
        assertEquals(1, adapter.acceptPcm(ByteArray(400), 0))
        assertEquals(1L, adapter.droppedFrameCount)
        assertEquals(1, adapter.queuedFrames)
        assertEquals(0L, adapter.pollFrame()?.sequence)
        assertNull(adapter.pollFrame())
    }

    @Test
    fun retainsPartialBytesAcrossReads() {
        val adapter = LiveCaptureTranscriptionAdapter("test", 1000, 100, 2)
        assertEquals(0, adapter.acceptPcm(ByteArray(100), 0))
        assertEquals(100, adapter.pendingAssemblerBytes)
        assertEquals(1, adapter.acceptPcm(ByteArray(100), 50))
        assertEquals(0, adapter.pendingAssemblerBytes)
        assertNotNull(adapter.pollFrame())
    }
}
