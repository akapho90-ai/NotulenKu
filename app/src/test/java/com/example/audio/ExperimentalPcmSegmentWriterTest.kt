package com.example.audio

import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
    fun verifiedCheckpointBackupIsRestoredOnReopen() {
        val dir=tempDir()
        try {
            val pcm=File(dir,"sessionE.pcm"); pcm.writeBytes(byteArrayOf(1,2,3,4))
            val backup=File(dir,"sessionE.pcm.checkpoint.bak")
            backup.writeText("session=sessionE\nformat=pcm16_mono\nsample_rate=16000\nbytes=4\n")
            ExperimentalPcmSessionJournal(dir).write(
                ExperimentalPcmSessionMetadata("sessionE",pcm.name,16000,status=ExperimentalPcmSessionStatus.RECOVERABLE,checkpointBytes=4,updatedAtEpochMs=5)
            )
            val writer=ExperimentalPcmSegmentWriter(dir,"sessionE")
            writer.open()
            writer.close()
            assertEquals(4L,pcm.length())
            assertTrue(File(dir,"sessionE.pcm.checkpoint").isFile)
            assertFalse(backup.exists())
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

    @get:Rule
    val recoveryFolder = TemporaryFolder()

    private fun checkpointFile(dir: File, suffix: String = "") = File(dir, "recover.pcm.checkpoint$suffix")

    private fun checkpointText(bytes: Long = 4) =
        "session=recover\nfile=recover.pcm\nformat=pcm16_mono\nsample_rate=16000\nbytes=$bytes\n"

    private fun writeJournal(dir: File, bytes: Long = 4, sampleRate: Int = 16_000) {
        ExperimentalPcmSessionJournal(dir).write(
            ExperimentalPcmSessionMetadata(
                "recover", "recover.pcm", sampleRate, status = ExperimentalPcmSessionStatus.RECOVERABLE,
                checkpointBytes = bytes, updatedAtEpochMs = 5
            )
        )
    }

    @Test
    fun corruptPrimaryIsArchivedAndMatchingBackupIsRecoveredAcrossReopens() {
        val dir = recoveryFolder.root
        val pcm = File(dir, "recover.pcm").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6)) }
        writeJournal(dir)
        checkpointFile(dir).writeText(checkpointText(3))
        checkpointFile(dir, ".invalid").writeText("previous archive")
        checkpointFile(dir, ".bak").writeText(checkpointText())
        checkpointFile(dir, ".tmp").writeText(checkpointText(6))

        repeat(2) {
            ExperimentalPcmSegmentWriter(dir, "recover").use { writer ->
                assertEquals(pcm, writer.open())
                assertEquals(4L, writer.totalBytes)
            }
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), pcm.readBytes())
        }
        assertEquals(checkpointText(), checkpointFile(dir).readText())
        assertEquals("previous archive", checkpointFile(dir, ".invalid").readText())
        assertEquals(checkpointText(3), checkpointFile(dir, ".invalid.1").readText())
        assertFalse(checkpointFile(dir, ".bak").exists())
        assertFalse(checkpointFile(dir, ".tmp").exists())
    }

    @Test
    fun matchingTempAndJournalBackupAreRecoveredWhenPrimariesAreMissing() {
        val dir = recoveryFolder.root
        val pcm = File(dir, "recover.pcm").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        writeJournal(dir)
        val journal = File(dir, "recover.pcm.journal")
        val journalBackup = File(dir, journal.name + ".bak")
        assertTrue(journal.renameTo(journalBackup))
        checkpointFile(dir, ".bak").writeText(checkpointText().substringBefore("bytes="))
        checkpointFile(dir, ".tmp").writeText(checkpointText())

        ExperimentalPcmSegmentWriter(dir, "recover").use { it.open() }

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), pcm.readBytes())
        assertTrue(journal.isFile)
        assertEquals(checkpointText(), checkpointFile(dir).readText())
        assertFalse(journalBackup.exists())
        assertFalse(checkpointFile(dir, ".bak").exists())
        assertFalse(checkpointFile(dir, ".tmp").exists())
    }

    @Test
    fun matchingPrimaryTakesPrecedenceOverOlderBackupAndNewerTemp() {
        val dir = recoveryFolder.root
        val pcm = File(dir, "recover.pcm").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6)) }
        writeJournal(dir)
        checkpointFile(dir).writeText(checkpointText())
        checkpointFile(dir, ".bak").writeText(checkpointText(2))
        checkpointFile(dir, ".tmp").writeText(checkpointText(6))

        ExperimentalPcmSegmentWriter(dir, "recover").use { it.open() }

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), pcm.readBytes())
        assertFalse(checkpointFile(dir, ".invalid").exists())
    }

    @Test
    fun mismatchedPrimaryWithoutMatchingSidecarLeavesPcmAndCheckpointUntouched() {
        val dir = recoveryFolder.root
        val original = byteArrayOf(1, 2, 3, 4, 5, 6)
        val pcm = File(dir, "recover.pcm").apply { writeBytes(original) }
        writeJournal(dir)
        checkpointFile(dir).writeText(checkpointText(2))
        checkpointFile(dir, ".bak").writeText(checkpointText(6))

        assertThrows(IOException::class.java) { ExperimentalPcmSegmentWriter(dir, "recover").open() }

        assertArrayEquals(original, pcm.readBytes())
        assertEquals(checkpointText(2), checkpointFile(dir).readText())
        assertEquals(checkpointText(6), checkpointFile(dir, ".bak").readText())
        assertFalse(checkpointFile(dir, ".invalid").exists())
    }

    @Test
    fun sidecarsWithUnsafeMetadataAreNeverPromotedOrUsedToTruncatePcm() {
        val cases = listOf(
            Triple("session", 4L, checkpointText().replace("session=recover", "session=other")),
            Triple("name", 4L, checkpointText().replace("file=recover.pcm", "file=other.pcm")),
            Triple("format", 4L, checkpointText().replace("pcm16_mono", "pcm16_stereo")),
            Triple("rate", 4L, checkpointText().replace("16000", "8000")),
            Triple("odd", 4L, checkpointText(3)),
            Triple("negative", 4L, checkpointText(-2)),
            Triple("longer-than-pcm", 6L, checkpointText(6)),
            Triple("journal-count", 4L, checkpointText(2)),
            Triple("duplicate", 4L, checkpointText() + "bytes=4\n"),
            Triple("partial", 4L, checkpointText().substringBefore("bytes="))
        )
        val original = byteArrayOf(1, 2, 3, 4)
        for ((label, journalBytes, contents) in cases) {
            val dir = recoveryFolder.newFolder(label)
            val pcm = File(dir, "recover.pcm").apply { writeBytes(original) }
            writeJournal(dir, journalBytes)
            val backup = checkpointFile(dir, ".bak").apply { writeText(contents) }
            val temp = checkpointFile(dir, ".tmp").apply { writeText(contents) }

            assertThrows(label, IOException::class.java) { ExperimentalPcmSegmentWriter(dir, "recover").open() }

            assertArrayEquals(label, original, pcm.readBytes())
            assertEquals(label, contents, backup.readText())
            assertEquals(label, contents, temp.readText())
            assertFalse(label, checkpointFile(dir).exists())
        }
    }

    @Test
    fun sidecarRequiresMatchingJournalMetadata() {
        val dir = recoveryFolder.root
        val original = byteArrayOf(1, 2, 3, 4)
        val pcm = File(dir, "recover.pcm").apply { writeBytes(original) }
        val temp = checkpointFile(dir, ".tmp").apply { writeText(checkpointText()) }
        val journal = File(dir, "recover.pcm.journal")

        assertThrows(IOException::class.java) { ExperimentalPcmSegmentWriter(dir, "recover").open() }
        writeJournal(dir, sampleRate = 8_000)
        assertThrows(IOException::class.java) { ExperimentalPcmSegmentWriter(dir, "recover").open() }
        journal.writeText(journal.readText().replace("file=recover.pcm", "file=other.pcm"))
        assertThrows(IOException::class.java) { ExperimentalPcmSegmentWriter(dir, "recover").open() }

        assertArrayEquals(original, pcm.readBytes())
        assertEquals(checkpointText(), temp.readText())
        assertFalse(checkpointFile(dir).exists())
    }

    @Test
    fun validLegacyPrimaryWithoutJournalCanStillBeReopened() {
        val dir = recoveryFolder.root
        val pcm = File(dir, "recover.pcm").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        checkpointFile(dir).writeText(checkpointText().replace("file=recover.pcm\n", ""))

        ExperimentalPcmSegmentWriter(dir, "recover").use { it.open() }

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), pcm.readBytes())
        assertEquals(checkpointText(), checkpointFile(dir).readText())
    }

    @Test
    fun missingPcmDoesNotOverwriteExistingCheckpointMetadata() {
        val dir = recoveryFolder.root
        writeJournal(dir)
        val backup = checkpointFile(dir, ".bak").apply { writeText(checkpointText()) }

        assertThrows(IOException::class.java) { ExperimentalPcmSegmentWriter(dir, "recover").open() }

        assertFalse(File(dir, "recover.pcm").exists())
        assertFalse(checkpointFile(dir).exists())
        assertEquals(checkpointText(), backup.readText())
    }

    @Test
    fun checkpointBackupSurvivesJournalWriteFailureAndRestoresCommittedPrefix() {
        val dir = recoveryFolder.root
        val journalFile = File(dir, "recover.pcm.journal")
        val blockedJournalTemp = File(dir, journalFile.name + ".tmp")
        val writer = ExperimentalPcmSegmentWriter(dir, "recover", flushIntervalBytes = 2)
        writer.use {
            it.open()
            it.append(byteArrayOf(1, 2, 3, 4))
            assertTrue(blockedJournalTemp.mkdir())
            assertThrows(IOException::class.java) { it.append(byteArrayOf(5, 6)) }
        }

        assertEquals(checkpointText(6), checkpointFile(dir).readText())
        assertEquals(checkpointText(), checkpointFile(dir, ".bak").readText())
        assertEquals(4L, ExperimentalPcmSessionJournal(dir).read(journalFile)?.checkpointBytes)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), writer.currentFile.readBytes())

        assertTrue(blockedJournalTemp.delete())
        ExperimentalPcmSegmentWriter(dir, "recover").use { it.open() }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), writer.currentFile.readBytes())
        assertEquals(checkpointText(6), checkpointFile(dir, ".invalid").readText())
    }

    @Test
    fun checkpointWriteFailureDoesNotAdvanceJournalByteCount() {
        val dir = recoveryFolder.root
        val writer = ExperimentalPcmSegmentWriter(dir, "recover", flushIntervalBytes = 2)
        writer.use {
            it.open()
            it.append(byteArrayOf(1, 2, 3, 4))
            assertTrue(checkpointFile(dir, ".bak").mkdir())
            assertThrows(IOException::class.java) { it.append(byteArrayOf(5, 6)) }
        }

        val record = ExperimentalPcmSessionJournal(dir).read(File(dir, "recover.pcm.journal"))
        assertEquals(ExperimentalPcmSessionStatus.FAILED, record?.status)
        assertEquals(4L, record?.checkpointBytes)
        assertEquals(checkpointText(), checkpointFile(dir).readText())
        assertEquals(checkpointText(6), checkpointFile(dir, ".tmp").readText())
    }

    @Test
    fun integrityAssessmentRejectsAmbiguousPrimaryCheckpoint() {
        val dir = recoveryFolder.root
        File(dir, "recover.pcm").writeBytes(byteArrayOf(1, 2, 3, 4))
        writeJournal(dir)
        checkpointFile(dir).writeText(checkpointText() + "bytes=4\n")

        val assessment = ExperimentalPcmRecoveryManager(dir).assessAll().single()
        assertFalse(assessment.recoverable)
        assertEquals(false, assessment.integrity?.checkpointValid)
    }
}
