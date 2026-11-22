package com.example.audio

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ExperimentalWavPlaybackValidatorTest {
    @Test fun rejectsMissingOrTooSmallFile() {
        val root=createTempDir()
        try {
            val result=ExperimentalWavPlaybackValidator().validate(File(root,"missing.wav"))
            assertEquals(WavValidationStatus.INVALID,result.status)
            assertFalse(result.firstSampleReadable)
        } finally { root.deleteRecursively() }
    }
    @Test fun reportsWavTrackReadinessFromExtractor() {
        val root=createTempDir()
        try {
            val raw=File(root,"probe.recovered.pcm")
            raw.writeBytes(ByteArray(32000))
            val wav=ExperimentalPcmWavConverter(root).convert(raw,16000,true).wavFile
            val result=ExperimentalWavPlaybackValidator().validate(wav)
            assertEquals(WavValidationStatus.VALID_READABLE,result.status)
            assertEquals("audio/raw",result.mimeType)
            assertEquals(16000,result.sampleRateHz)
            assertEquals(1,result.channelCount)
            assertTrue(result.firstSampleReadable)
            assertTrue((result.durationUs ?: 0L)>0)
        } finally { root.deleteRecursively() }
    }
}
