package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.audio.AudioSegmenter
import com.example.data.repository.LocalProcessingCheckpointStore
import com.example.data.repository.LocalProcessingJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class Stage2ProcessingInstrumentedTest {
    @Test fun mediaExtractorAcceptsSyntheticWavAndRejectsCorruptAudio() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = File.createTempFile("stage2_valid_", ".wav", context.cacheDir)
        val corrupt = File.createTempFile("stage2_invalid_", ".wav", context.cacheDir)
        try {
            val sampleRate = 16_000
            val dataSize = sampleRate * 2
            val wav = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
            wav.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
            wav.putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
            wav.putShort(2).putShort(16).put("data".toByteArray()).putInt(dataSize)
            repeat(sampleRate) { sample -> wav.putShort(if (sample % 32 < 16) 1_000 else -1_000) }
            audio.writeBytes(wav.array())
            corrupt.writeText("not a media file")
            val chunks = AudioSegmenter.prepareAudioChunks(context, "stage2-valid", audio, "audio/wav", 1).getOrThrow()
            assertEquals(audio, chunks.single().file)
            assertEquals(1L, chunks.single().endOffsetSec)
            assertTrue(AudioSegmenter.prepareAudioChunks(context, "stage2-invalid", corrupt, "audio/wav", 1).isFailure)
        } finally {
            audio.delete()
            corrupt.delete()
        }
    }

    @Test fun longAacProducesPlayableChunksThroughTheFinalWindow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File.createTempFile("stage2_long_", ".m4a", context.cacheDir)
        val id = "stage2-long-${System.currentTimeMillis()}"
        try {
            instrumentation.context.assets.open("synthetic-aac.m4a").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val originalLength = source.length()
            val chunks = AudioSegmenter.prepareAudioChunks(context, id, source, "audio/mp4", 72, 10).getOrThrow()
            assertTrue(chunks.size > 1)
            assertEquals(0L, chunks.first().startOffsetSec)
            assertEquals(72L, chunks.last().endOffsetSec)
            assertEquals(chunks.indices.toList(), chunks.map { it.index })
            chunks.forEach { chunk ->
                val extractor = android.media.MediaExtractor()
                try {
                    extractor.setDataSource(chunk.file.absolutePath)
                    assertTrue(extractor.getTrackFormat(0).getString("mime")?.startsWith("audio/") == true)
                    extractor.selectTrack(0)
                    assertTrue(extractor.sampleTime >= 0L)
                } finally {
                    extractor.release()
                }
            }
            assertEquals(originalLength, source.length())
        } finally {
            AudioSegmenter.cleanupTempChunkDir(context, id, source.absolutePath)
            source.delete()
        }
    }

    @Test fun freshStoreFindsBackupOnlyCheckpointAfterInterruptedPromotion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "stage2-checkpoints-").toFile()
        val job = LocalProcessingJob("sample-meeting", "sample-owner", "Rapat", "/sample.m4a", "audio/mp4", 7, 15)
        try {
            val store = LocalProcessingCheckpointStore(directory)
            assertTrue(store.saveJob(job))
            assertTrue(store.saveJob(job.copy(judul = "Revisi")))
            assertTrue(File(directory, "sample-owner/job_sample-meeting.json").delete())
            val restarted = LocalProcessingCheckpointStore(directory)
            assertEquals(listOf(job), restarted.listJobs("sample-owner"))
            assertTrue(restarted.listJobs("different-owner").isEmpty())
            assertTrue(restarted.removeJob("sample-owner", "sample-meeting"))
            assertTrue(restarted.listJobs("sample-owner").isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}
