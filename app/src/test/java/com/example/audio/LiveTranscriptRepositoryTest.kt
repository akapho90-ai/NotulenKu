package com.example.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTranscriptRepositoryTest {
    @Test
    fun partialUpdateReplacesWindowInsteadOfAppending() {
        LiveTranscriptRepository.begin("test-session")
        LiveTranscriptRepository.upsert("test-session", LiveTranscriptEntry("a", "window-1", "Halo", 0, 100, isFinal = false))
        LiveTranscriptRepository.upsert("test-session", LiveTranscriptEntry("a", "window-1", "Halo semuanya", 0, 200, isFinal = false))
        assertEquals(1, LiveTranscriptRepository.state.value.entries.size)
        assertEquals("Halo semuanya", LiveTranscriptRepository.state.value.entries.first().text)
        LiveTranscriptRepository.clear("test-session")
    }

    @Test
    fun entriesAreSortedByStartTime() {
        LiveTranscriptRepository.begin("test-session")
        LiveTranscriptRepository.upsert("test-session", LiveTranscriptEntry("b", "w2", "kedua", 200, 300))
        LiveTranscriptRepository.upsert("test-session", LiveTranscriptEntry("a", "w1", "pertama", 100, 200))
        assertEquals(listOf("w1", "w2"), LiveTranscriptRepository.state.value.entries.map { it.windowId })
        LiveTranscriptRepository.clear("test-session")
    }

    @Test
    fun finalizeWindowMarksEntryFinal() {
        LiveTranscriptRepository.begin("test-session")
        LiveTranscriptRepository.upsert("test-session", LiveTranscriptEntry("a", "w1", "hasil", 0, 100))
        LiveTranscriptRepository.finalizeWindow("test-session", "w1")
        assertTrue(LiveTranscriptRepository.state.value.entries.first().isFinal)
        LiveTranscriptRepository.clear("test-session")
    }
}
