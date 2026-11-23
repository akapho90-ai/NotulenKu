package com.example.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTranscriptionWorkerTest {
    private class FakeProvider : LiveTranscriptionProvider {
        override val mode = LiveProviderMode.STREAMING
        val sent = mutableListOf<LiveAudioFrame>()
        var opened = false
        var closed = false
        var partial: ((LiveTranscriptEntry) -> Unit)? = null
        var final: ((LiveTranscriptEntry) -> Unit)? = null

        override suspend fun open(sessionId: String, onPartial: (LiveTranscriptEntry) -> Unit, onFinal: (LiveTranscriptEntry) -> Unit) {
            opened = true
            partial = onPartial
            final = onFinal
        }
        override suspend fun sendFrame(frame: LiveAudioFrame) { sent.add(frame) }
        override suspend fun close() { closed = true }
    }

    @Test
    fun workerOpensAndForwardsFramesAndUpdatesTranscript() = runTest {
        val adapter = LiveCaptureTranscriptionAdapter("worker-test")
        val provider = FakeProvider()
        val worker = LiveTranscriptionWorker("worker-test", adapter, provider, backgroundScope)
        assertTrue(worker.start())
        runCurrent()
        assertTrue(provider.opened)
        assertEquals(LiveTranscriptionStatus.LIVE, LiveTranscriptRepository.state.value.status)

        provider.partial?.invoke(LiveTranscriptEntry("1", "w1", "Halo", 0, 100))
        assertEquals("Halo", LiveTranscriptRepository.state.value.entries.single().text)

        adapter.acceptPcm(ByteArray(3200), 1000)
        runCurrent()
        assertTrue(provider.sent.isNotEmpty())
        worker.stop()
        runCurrent()
        assertTrue(provider.closed)
        LiveTranscriptRepository.clear("worker-test")
    }

    @Test
    fun providerFailureSetsFallbackStatus() = runTest {
        val sessionId = "failure-test"
        var openAttempts = 0
        val failing = object : LiveTranscriptionProvider {
            override val mode = LiveProviderMode.STREAMING

            override suspend fun open(
                sessionId: String,
                onPartial: (LiveTranscriptEntry) -> Unit,
                onFinal: (LiveTranscriptEntry) -> Unit
            ) {
                openAttempts++
                error("offline")
            }

            override suspend fun sendFrame(frame: LiveAudioFrame) = Unit
            override suspend fun close() = Unit
        }

        // advanceUntilIdle waits for foreground work, including the connection backoff.
        val worker = LiveTranscriptionWorker(sessionId, LiveCaptureTranscriptionAdapter(sessionId), failing, this)
        try {
            assertTrue(worker.start())
            advanceUntilIdle()
            assertEquals(4, openAttempts)
            assertEquals(LiveTranscriptionStatus.FALLBACK, LiveTranscriptRepository.state.value.status)
            assertFalse(worker.isRunning)
        } finally {
            worker.stop()
            LiveTranscriptRepository.clear(sessionId)
        }
    }
}
