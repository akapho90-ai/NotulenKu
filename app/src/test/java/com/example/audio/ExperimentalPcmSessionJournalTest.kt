package com.example.audio

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalPcmSessionJournalTest {
    @Test
    fun assessmentAcceptsCheckpointedRecoverableSessionAndRejectsOrphan() {
        val dir = createTempDir(prefix = "pcm-journal-test")
        try {
            val pcm = File(dir, "sessionA.pcm")
            pcm.writeBytes(byteArrayOf(1, 2, 3, 4))
            ExperimentalPcmSessionJournal(dir).write(
                ExperimentalPcmSessionMetadata(
                    sessionId = "sessionA", fileName = pcm.name, sampleRateHz = 16_000,
                    status = ExperimentalPcmSessionStatus.RECOVERABLE,
                    checkpointBytes = 4, updatedAtEpochMs = 1
                )
            )
            File(dir, "orphan.pcm").writeBytes(byteArrayOf(1, 2))
            val results = ExperimentalPcmRecoveryManager(dir).assessAll()
            assertTrue(results.any { it.pcmFile?.name == pcm.name && it.recoverable })
            assertFalse(results.any { it.pcmFile?.name == "orphan.pcm" && it.recoverable })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun assessmentRejectsCheckpointBeyondFileLength() {
        val dir = createTempDir(prefix = "pcm-journal-test")
        try {
            val pcm = File(dir, "sessionB.pcm")
            pcm.writeBytes(byteArrayOf(1, 2))
            ExperimentalPcmSessionJournal(dir).write(
                ExperimentalPcmSessionMetadata(
                    sessionId = "sessionB", fileName = pcm.name, sampleRateHz = 16_000,
                    status = ExperimentalPcmSessionStatus.RECOVERABLE,
                    checkpointBytes = 4, updatedAtEpochMs = 2
                )
            )
            assertFalse(ExperimentalPcmRecoveryManager(dir).assessAll().first().recoverable)
        } finally {
            dir.deleteRecursively()
        }
    }
}
