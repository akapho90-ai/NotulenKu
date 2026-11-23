package com.example.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest

class LiveTranscriptionBufferTest {
    @Test
    fun queueIsBoundedAndFifo() {
        val buffer = LiveTranscriptionBuffer(2)
        val a = LiveAudioFrame("s", 1, 0, byteArrayOf(1))
        val b = LiveAudioFrame("s", 2, 10, byteArrayOf(2))
        val c = LiveAudioFrame("s", 3, 20, byteArrayOf(3))
        assertTrue(buffer.offer(a))
        assertTrue(buffer.offer(b))
        assertFalse(buffer.offer(c))
        assertEquals(2, buffer.size())
        assertEquals(1L, buffer.poll()?.sequence)
        assertEquals(2L, buffer.poll()?.sequence)
        assertEquals(0, buffer.size())
    }

    @Test
    fun suspendingReceiveDecrementsQueueSize() = runTest {
        val buffer = LiveTranscriptionBuffer(1)
        val frame = LiveAudioFrame("s", 1, 0, byteArrayOf(1))
        assertTrue(buffer.offer(frame))
        assertEquals(1, buffer.size())
        assertEquals(1L, buffer.receive().sequence)
        assertEquals(0, buffer.size())
    }

    @Test
    fun overflowDoesNotChangeCountAndQueueCanAcceptAfterPoll() {
        val buffer = LiveTranscriptionBuffer(1)
        assertTrue(buffer.offer(LiveAudioFrame("s", 1, 0, byteArrayOf(1))))
        assertFalse(buffer.offer(LiveAudioFrame("s", 2, 1, byteArrayOf(2))))
        assertEquals(1, buffer.size())
        assertEquals(1L, buffer.poll()?.sequence)
        assertEquals(0, buffer.size())
        assertTrue(buffer.offer(LiveAudioFrame("s", 3, 2, byteArrayOf(3))))
        assertEquals(3L, buffer.poll()?.sequence)
        assertEquals(0, buffer.size())
    }

    @Test
    fun clearDrainsQueuedFramesAndResetsSize() {
        val buffer = LiveTranscriptionBuffer(3)
        repeat(3) { index -> assertTrue(buffer.offer(LiveAudioFrame("s", index.toLong(), index.toLong(), byteArrayOf(index.toByte())))) }
        assertEquals(3, buffer.size())
        buffer.clear()
        assertEquals(0, buffer.size())
        assertFalse(buffer.offer(LiveAudioFrame("s", 4, 4, byteArrayOf(4))) && false)
        assertEquals(1, buffer.size())
        buffer.clear()
        assertEquals(0, buffer.size())
    }
}
