package com.example.audio

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LiveTranscriptionConnectionManagerTest {
    private class FakeProvider : LiveTranscriptionProvider {
        override val mode = LiveProviderMode.STREAMING
        var openCalls = 0
        var sendCalls = 0
        var closeCalls = 0
        var failOpens = 0
        var timeoutOpens = 0
        var timeoutSends = 0
        var failSend = false\n        var sendDelayMs = 0L\n        var onSend: (() -> Unit)? = null
        override suspend fun open(sessionId: String, onPartial: (LiveTranscriptEntry) -> Unit, onFinal: (LiveTranscriptEntry) -> Unit) {
            openCalls++
            if (timeoutOpens > 0) { timeoutOpens--; delay(Long.MAX_VALUE) }
            if (failOpens > 0) { failOpens--; error("open failed") }
        }
        override suspend fun sendFrame(frame: LiveAudioFrame) {
            sendCalls++
            if (timeoutSends > 0) { timeoutSends--; delay(Long.MAX_VALUE) }
            if (failSend) error("send failed")
        }
        override suspend fun close() { closeCalls++ }
    }

    @Test fun retriesConnectionThenConnects() = runTest {
        val provider = FakeProvider().apply { failOpens = 2 }
        val manager = LiveTranscriptionConnectionManager("s", provider, maxReconnectAttempts = 2, initialBackoffMs = 0, maxBackoffMs = 0)
        assertTrue(manager.connect())
        assertEquals(3, provider.openCalls)
        manager.close()
    }

    @Test fun failureSendingFrameReconnectsButDoesNotResendFrame() = runTest {
        val provider = FakeProvider()
        val manager = LiveTranscriptionConnectionManager("s", provider, maxReconnectAttempts = 1, initialBackoffMs = 0, maxBackoffMs = 0)
        assertTrue(manager.connect())
        provider.failSend = true
        assertFalse(manager.sendFrame(LiveAudioFrame("s", 1, 0, ByteArray(10))))
        assertEquals(1, provider.sendCalls)
        assertEquals(2, provider.openCalls)
        manager.close()
    }


    @Test fun openTimeoutRetriesAndCanRecover() = runTest {
        val provider = FakeProvider().apply { timeoutOpens = 1 }
        val manager = LiveTranscriptionConnectionManager(
            "s", provider, maxReconnectAttempts = 1, operationTimeoutMs = 10,
            initialBackoffMs = 0, maxBackoffMs = 0
        )
        assertTrue(manager.connect())
        assertEquals(2, provider.openCalls)
        manager.close()
    }

    @Test fun sendTimeoutReconnectsWithoutResendingFrame() = runTest {
        val provider = FakeProvider().apply { timeoutSends = 1 }
        val manager = LiveTranscriptionConnectionManager(
            "s", provider, maxReconnectAttempts = 1, operationTimeoutMs = 10,
            initialBackoffMs = 0, maxBackoffMs = 0
        )
        assertTrue(manager.connect())
        assertFalse(manager.sendFrame(LiveAudioFrame("s", 1, 0, ByteArray(10))))
        assertEquals(1, provider.sendCalls)
        assertEquals(2, provider.openCalls)
        manager.close()
    }


    @Test fun concurrentFrameSendsAreSerialized() = runTest {
        val provider = FakeProvider()
        var activeSends = 0
        var maxActiveSends = 0
        provider.sendDelayMs = 20
        provider.onSend = {
            activeSends++
            maxActiveSends = maxOf(maxActiveSends, activeSends)
            delay(provider.sendDelayMs)
            activeSends--
        }
        val manager = LiveTranscriptionConnectionManager("s", provider, initialBackoffMs = 0, maxBackoffMs = 0)
        assertTrue(manager.connect())
        val first = async { manager.sendFrame(LiveAudioFrame("s", 1, 0, ByteArray(1))) }
        val second = async { manager.sendFrame(LiveAudioFrame("s", 2, 1, ByteArray(1))) }
        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(1, maxActiveSends)
        manager.close()
    }

    @Test fun frameSendAfterCloseIsRejected() = runTest {
        val provider = FakeProvider()
        val manager = LiveTranscriptionConnectionManager("s", provider, initialBackoffMs = 0, maxBackoffMs = 0)
        manager.close()
        assertFalse(manager.sendFrame(LiveAudioFrame("s", 1, 0, ByteArray(1))))
        assertEquals(0, provider.sendCalls)
    }

    @Test fun rejectsFramesForDifferentSessionAndClosedManager() = runTest {
        val provider = FakeProvider()
        val manager = LiveTranscriptionConnectionManager("s", provider, initialBackoffMs = 0, maxBackoffMs = 0)
        assertFalse(manager.sendFrame(LiveAudioFrame("other", 1, 0, ByteArray(1))))
        manager.close()
        assertFalse(manager.sendFrame(LiveAudioFrame("s", 1, 0, ByteArray(1))))
        assertEquals(0, provider.sendCalls)
    }
}
