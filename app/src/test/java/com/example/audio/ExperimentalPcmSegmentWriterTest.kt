package com.example.audio

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalPcmSegmentWriterTest {
    private fun tempDir() = kotlin.io.path.createTempDirectory("pcm-writer-test").toFile()

    @Test
    fun existingPcmWithoutCheckpointIsRejectedWithoutMutation() {
        val dir = tempDir()
        try {
            val pcm = File(dir, "sessionA.pcm")
            pcm.writeBytes(byteArrayOf(1, 2, 3, 4))
            val writer = ExperimentalPcmSegmentWriter(dir, "sessionA")
            var rejected = false
            try { writer.open() } catch (_: IOException) { rejected = true }
            assertTrue(rejected)
            assertEquals(4L, pcm.length())
            assertFalse(File(dir, "sessionA.pcm.checkpoint").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun malformedOddFrameFailsWriterAndDoesNotAppendBytes() {
        val dir = tempDir()
        try {
            val writer = ExperimentalPcmSegmentWriter(dir, "sessionB", flushIntervalBytes = 64)
            var rejected = false
            try { writer.append(byteArrayOf(1, 2, 3)) } catch (_: IOException) { rejected = true }
            assertTrue(rejected)
            assertEquals(0L, writer.currentFile.length())
            var subsequentRejected = false
            try { writer.append(byteArrayOf(4, 5)) } catch (_: IOException) { subsequentRejected = true }
            assertTrue(subsequentRejected)
            assertTrue(ExperimentalPcmSessionJournal(dir).read(File(dir, "sessionB.pcm.journal"))?.status == ExperimentalPcmSessionStatus.FAILED)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun reopenTruncatesUncheckpointedTailToVerifiedCheckpoint() {
        val dir = tempDir()
        try {
            val pcm = File(dir, "sessionC.pcm")
            pcm.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))
            File(dir, "sessionC.pcm.checkpoint").writeText("session=sessionC\nformat=pcm16_mono\nsample_rate=16000\nbytes=4\n")
            ExperimentalPcmSessionJournal(dir).write(
                ExperimentalPcmSessionMetadata("sessionC", pcm.name, 16000, status=ExperimentalPcmSessionStatus.RECOVERABLE, checkpointBytes=4, updatedAtEpochMs=3)
            )
            val writer = ExperimentalPcmSegmentWriter(dir, "sessionC")
            writer.open()
            writer.close()
            assertEquals(4L, pcm.length())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun checkpointSessionOrFormatMismatchIsRejected() {
        val dir = tempDir()
        try {
            val pcm = File(dir, "sessionD.pcm")
            pcm.writeBytes(byteArrayOf(1, 2))
            File(dir, "sessionD.pcm.checkpoint").writeText("session=other\nformat=pcm16_mono\nsample_rate=16000\nbytes=2\n")
            var rejected = false
            try { ExperimentalPcmSegmentWriter(dir, "sessionD").open() } catch (_: IOException) { rejected = true }
            assertTrue(rejected)
            assertEquals(2L, pcm.length())
        } finally { dir.deleteRecursively() }
    }
}
