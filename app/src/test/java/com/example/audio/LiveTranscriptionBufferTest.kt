package com.example.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    }
}
