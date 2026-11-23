package com.example.audio

import android.media.MediaFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowMediaExtractor
import org.robolectric.shadows.util.DataSource
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ExperimentalWavPlaybackValidatorTest {
    @Test fun rejectsMissingOrTooSmallFile() {
        val root=kotlin.io.path.createTempDirectory().toFile()
        try {
            val result=ExperimentalWavPlaybackValidator().validate(File(root,"missing.wav"))
            assertEquals(WavValidationStatus.INVALID,result.status)
            assertFalse(result.firstSampleReadable)
        } finally { root.deleteRecursively() }
    }
    @Test fun reportsWavTrackReadinessFromExtractor() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            val raw = File(root, "probe.recovered.pcm")
            raw.writeBytes(ByteArray(32_000))
            val converted = ExperimentalPcmWavConverter(root).convert(raw, 16_000, true)
            val wav = converted.wavFile

            // Robolectric's extractor exposes registered tracks instead of demuxing files.
            val format = MediaFormat.createAudioFormat("audio/raw", 16_000, 1)
            format.setLong(MediaFormat.KEY_DURATION, converted.durationMs * 1_000L)
            val wavBytes = wav.readBytes()
            val samples = wavBytes.copyOfRange(44, wavBytes.size)
            assertArrayEquals(raw.readBytes(), samples)
            ShadowMediaExtractor.addTrack(DataSource.toDataSource(wav.absolutePath), format, samples)

            val result = ExperimentalWavPlaybackValidator().validate(wav)
            assertEquals(result.issues.joinToString(), WavValidationStatus.VALID_READABLE, result.status)
            assertEquals("audio/raw", result.mimeType)
            assertEquals(16_000, result.sampleRateHz)
            assertEquals(1, result.channelCount)
            assertTrue(result.firstSampleReadable)
            assertEquals(1_000_000L, result.durationUs)
        } finally { root.deleteRecursively() }
    }
}
