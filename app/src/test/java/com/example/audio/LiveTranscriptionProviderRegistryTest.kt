package com.example.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class LiveTranscriptionProviderRegistryTest {
    private class Provider(override val mode: LiveProviderMode) : LiveTranscriptionProvider {
        override suspend fun open(sessionId: String, onPartial: (LiveTranscriptEntry) -> Unit, onFinal: (LiveTranscriptEntry) -> Unit) = Unit
        override suspend fun sendFrame(frame: LiveAudioFrame) = Unit
        override suspend fun close() = Unit
    }

    @Test
    fun createsEnabledProviderAndFiltersByMode() {
        val registry = LiveTranscriptionProviderRegistry(listOf(
            LiveTranscriptionProviderRegistration("stream", LiveProviderMode.STREAMING) { Provider(LiveProviderMode.STREAMING) },
            LiveTranscriptionProviderRegistration("disabled", LiveProviderMode.STREAMING, enabled = false) { Provider(LiveProviderMode.STREAMING) }
        ))
        assertNotNull(registry.create("stream", LiveProviderMode.STREAMING))
        assertNull(registry.create("stream", LiveProviderMode.BATCH_ONLY))
        assertNull(registry.create("disabled"))
        assertEquals(listOf("stream"), registry.availableProviderIds())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateIds() {
        LiveTranscriptionProviderRegistry(listOf(
            LiveTranscriptionProviderRegistration("x", LiveProviderMode.STREAMING) { Provider(LiveProviderMode.STREAMING) },
            LiveTranscriptionProviderRegistration("x", LiveProviderMode.STREAMING) { Provider(LiveProviderMode.STREAMING) }
        ))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsFactoryModeMismatch() {
        LiveTranscriptionProviderRegistry(listOf(
            LiveTranscriptionProviderRegistration("x", LiveProviderMode.STREAMING) { Provider(LiveProviderMode.BATCH_ONLY) }
        )).create("x")
    }
}
