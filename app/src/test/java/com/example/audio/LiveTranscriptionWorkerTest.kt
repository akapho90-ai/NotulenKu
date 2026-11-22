package com.example.audio

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val failing = object : LiveTranscriptionProvider {
            override val mode = LiveProviderMode.STREAMING
            override suspend fun open(sessionId: String, onPartial: (LiveTranscriptEntry) -> Unit, onFinal: (LiveTranscriptEntry) -> Unit) { error("offline") }
            override suspend fun sendFrame(frame: LiveAudioFrame) = Unit
            override suspend fun close() = Unit
        }
        val worker = LiveTranscriptionWorker("failure-test", LiveCaptureTranscriptionAdapter("failure-test"), failing, backgroundScope)
        worker.start()
        runCurrent()
        assertEquals(LiveTranscriptionStatus.FALLBACK, LiveTranscriptRepository.state.value.status)
        LiveTranscriptRepository.clear("failure-test")
    }
}
