package com.example.audio

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExperimentalPcmSessionJournalTest {

    @get:Rule
    val recoveryFolder = TemporaryFolder()

    private fun journalText(session: String = "recover", bytes: Int = 4) = """
        session=$session
        file=$session.pcm
        sample_rate=16000
        format=pcm16_mono
        status=RECOVERABLE
        checkpoint_bytes=$bytes
        updated_at=10
    """.trimIndent()

    private fun recoveryFile(suffix: String = "") = File(recoveryFolder.root, "recover.pcm.journal$suffix")

    private fun checkpoint(dir: File, file: File, id: String, bytes: Long) {
        File(dir, file.name + ".checkpoint").writeText("session=$id\nformat=pcm16_mono\nsample_rate=16000\nbytes=$bytes\n")
    }
    @Test fun journalRejectsUnsafeFileNameAndSessionId() {
        val dir=kotlin.io.path.createTempDirectory("pcm-journal-test").toFile()
        try {
            val journal=ExperimentalPcmSessionJournal(dir)
            var pathRejected=false
            try { journal.write(ExperimentalPcmSessionMetadata("safe","../escape.pcm",16000,status=ExperimentalPcmSessionStatus.RECOVERABLE,checkpointBytes=0,updatedAtEpochMs=1)) } catch (_: IOException) { pathRejected=true }
            assertTrue(pathRejected)
            var idRejected=false
            try { journal.write(ExperimentalPcmSessionMetadata("../bad","../bad.pcm",16000,status=ExperimentalPcmSessionStatus.RECOVERABLE,checkpointBytes=0,updatedAtEpochMs=1)) } catch (_: IOException) { idRejected=true }
            assertTrue(idRejected)
            assertTrue(dir.listFiles()?.isEmpty() == true)
        } finally { dir.deleteRecursively() }
    }

    @Test fun malformedJournalRecordIsIgnoredDuringListing() {
        val dir=kotlin.io.path.createTempDirectory("pcm-journal-test").toFile()
        try {
            File(dir,"bad.pcm.journal").writeText("session=../outside\nfile=../outside.pcm\nsample_rate=16000\nformat=pcm16_mono\nstatus=RECOVERABLE\ncheckpoint_bytes=4\nupdated_at=1\n")
            File(dir,"odd.pcm.journal").writeText("session=odd\nfile=odd.pcm\nsample_rate=16000\nformat=pcm16_mono\nstatus=RECOVERABLE\ncheckpoint_bytes=3\nupdated_at=1\n")
            assertTrue(ExperimentalPcmSessionJournal(dir).list().isEmpty())
        } finally { dir.deleteRecursively() }
    }


    @Test fun journalBackupIsRecoveredWhenPrimaryIsMissing() {
        val dir=kotlin.io.path.createTempDirectory("pcm-journal-recovery").toFile()
        try {
            val journal=ExperimentalPcmSessionJournal(dir)
            val target=File(dir,"recover.pcm.journal")
            journal.write(ExperimentalPcmSessionMetadata("recover","recover.pcm",16000,status=ExperimentalPcmSessionStatus.RECOVERABLE,checkpointBytes=4,updatedAtEpochMs=10))
            val backup=File(dir,target.name+".bak")
            assertTrue(target.renameTo(backup))
            val restored=journal.list()
            assertTrue(restored.any { it.sessionId=="recover" && it.checkpointBytes==4L })
            assertTrue(target.isFile)
            assertFalse(backup.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun validJournalTempIsRecoveredWhenNoPrimaryOrBackupExists() {
        val dir=kotlin.io.path.createTempDirectory("pcm-journal-recovery").toFile()
        try {
            val journal=ExperimentalPcmSessionJournal(dir)
            val target=File(dir,"tempRecover.pcm.journal")
            val temp=File(dir,target.name+".tmp")
            temp.writeText("""
session=tempRecover
file=tempRecover.pcm
sample_rate=16000
format=pcm16_mono
status=RECOVERABLE
checkpoint_bytes=2
updated_at=11
""".trimIndent())
            val restored=journal.list()
            assertTrue(restored.any { it.sessionId=="tempRecover" && it.checkpointBytes==2L })
            assertTrue(target.isFile)
            assertFalse(temp.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun validPrimaryWinsOverBackupAndTemp() {
        val primary = recoveryFile().apply { writeText(journalText()) }
        val backup = recoveryFile(".bak").apply { writeText(journalText(bytes = 2)) }
        val temp = recoveryFile(".tmp").apply { writeText(journalText(bytes = 6)) }

        val journal = ExperimentalPcmSessionJournal(recoveryFolder.root)
        assertEquals(listOf(4L), journal.list().map { it.checkpointBytes })
        assertEquals(journalText(), primary.readText())
        assertEquals(journalText(bytes = 2), backup.readText())
        assertEquals(journalText(bytes = 6), temp.readText())
    }

    @Test fun invalidPrimaryIsArchivedWithoutReplacingOlderArchiveAndBackupWins() {
        val primary = recoveryFile().apply { writeText("new damaged journal") }
        val backup = recoveryFile(".bak").apply { writeText(journalText()) }
        val temp = recoveryFile(".tmp").apply { writeText(journalText(bytes = 6)) }
        val olderArchive = recoveryFile(".invalid").apply { writeText("older damaged journal") }

        val journal = ExperimentalPcmSessionJournal(recoveryFolder.root)
        val restored = journal.list()
        assertEquals(listOf(4L), restored.map { it.checkpointBytes })
        assertEquals(journalText(), primary.readText())
        assertEquals("older damaged journal", olderArchive.readText())
        assertEquals("new damaged journal", recoveryFile(".invalid.1").readText())
        assertFalse(backup.exists())
        assertFalse(temp.exists())
        assertEquals(restored, ExperimentalPcmSessionJournal(recoveryFolder.root).list())
    }

    @Test fun tempIsRecoveredWhenBackupIsIncompleteAfterPrimaryWasArchived() {
        val backup = recoveryFile(".bak").apply { writeText(journalText().substringBefore("updated_at=")) }
        val temp = recoveryFile(".tmp").apply { writeText(journalText(bytes = 6)) }
        val archive = recoveryFile(".invalid").apply { writeText("previously archived journal") }

        val journal = ExperimentalPcmSessionJournal(recoveryFolder.root)
        assertEquals(listOf(6L), journal.list().map { it.checkpointBytes })
        assertEquals(journalText(bytes = 6), recoveryFile().readText())
        assertEquals("previously archived journal", archive.readText())
        assertFalse(backup.exists())
        assertFalse(temp.exists())
    }

    @Test fun partialAndAmbiguousCandidatesAreLeftInPlace() {
        val primary = recoveryFile().apply { writeText("damaged primary") }
        val partial = "session=recover\nfile=recover.pcm"
        val ambiguous = journalText() + "\ncheckpoint_bytes=6"
        val backup = recoveryFile(".bak").apply { writeText(partial) }
        val temp = recoveryFile(".tmp").apply { writeText(ambiguous) }

        val journal = ExperimentalPcmSessionJournal(recoveryFolder.root)
        assertTrue(journal.list().isEmpty())
        assertEquals("damaged primary", primary.readText())
        assertEquals(partial, backup.readText())
        assertEquals(ambiguous, temp.readText())
        assertFalse(recoveryFile(".invalid").exists())
    }

    @Test fun journalNeverReturnsOrPromotesRecordsForAnotherSession() {
        val otherSession = journalText(session = "other")
        val primary = recoveryFile().apply { writeText(otherSession) }
        val backup = recoveryFile(".bak").apply { writeText(otherSession) }
        val temp = recoveryFile(".tmp").apply { writeText(otherSession) }

        val journal = ExperimentalPcmSessionJournal(recoveryFolder.root)
        assertTrue(journal.list().isEmpty())
        assertEquals(otherSession, primary.readText())
        assertEquals(otherSession, backup.readText())
        assertEquals(otherSession, temp.readText())
    }

    @Test fun blockedPromotionKeepsBothCandidatesForRetry() {
        val primary = recoveryFile()
        assertTrue(primary.mkdir())
        val backup = recoveryFile(".bak").apply { writeText(journalText()) }
        val temp = recoveryFile(".tmp").apply { writeText(journalText(bytes = 6)) }

        val journal = ExperimentalPcmSessionJournal(recoveryFolder.root)
        assertTrue(journal.list().isEmpty())
        assertTrue(primary.isDirectory)
        assertEquals(journalText(), backup.readText())
        assertEquals(journalText(bytes = 6), temp.readText())

        assertTrue(primary.delete())
        assertEquals(listOf(4L), journal.list().map { it.checkpointBytes })
        assertEquals(journalText(), primary.readText())
        assertFalse(backup.exists())
        assertFalse(temp.exists())
    }

    @Test fun writeKeepsRecoveredBackupWhenNewTempCannotBeWritten() {
        val backup = recoveryFile(".bak").apply { writeText(journalText()) }
        val temp = recoveryFile(".tmp")
        assertTrue(temp.mkdir())

        val journal = ExperimentalPcmSessionJournal(recoveryFolder.root)
        val update = ExperimentalPcmSessionMetadata(
            "recover", "recover.pcm", 16000,
            status = ExperimentalPcmSessionStatus.RECOVERABLE, checkpointBytes = 6, updatedAtEpochMs = 11
        )
        assertThrows(IOException::class.java) { journal.write(update) }
        assertEquals(journalText(), recoveryFile().readText())
        assertFalse(backup.exists())
        assertTrue(temp.isDirectory)

        assertTrue(temp.delete())
        journal.write(update)
        assertEquals(listOf(update), journal.list())
        assertFalse(backup.exists())
        assertFalse(temp.exists())
    }

    @Test fun writeDoesNotOverwriteTheOnlyValidTempWhenPromotionIsBlocked() {
        val primary = recoveryFile()
        assertTrue(primary.mkdir())
        val temp = recoveryFile(".tmp").apply { writeText(journalText()) }

        val journal = ExperimentalPcmSessionJournal(recoveryFolder.root)
        val update = ExperimentalPcmSessionMetadata(
            "recover", "recover.pcm", 16000,
            status = ExperimentalPcmSessionStatus.RECOVERABLE, checkpointBytes = 6, updatedAtEpochMs = 11
        )
        assertThrows(IOException::class.java) { journal.write(update) }
        assertEquals(journalText(), temp.readText())

        assertTrue(primary.delete())
        assertEquals(listOf(4L), journal.list().map { it.checkpointBytes })
        assertFalse(temp.exists())
    }

    @Test fun validCheckpointGetsIntegrityHashAndOrphanIsRejected() {
        val dir=kotlin.io.path.createTempDirectory("pcm-journal-test").toFile()
        try {
            val pcm=File(dir,"sessionA.pcm"); pcm.writeBytes(byteArrayOf(1,2,3,4))
            checkpoint(dir,pcm,"sessionA",4)
            ExperimentalPcmSessionJournal(dir).write(ExperimentalPcmSessionMetadata("sessionA",pcm.name,16000,status=ExperimentalPcmSessionStatus.RECOVERABLE,checkpointBytes=4,updatedAtEpochMs=1))
            File(dir,"orphan.pcm").writeBytes(byteArrayOf(1,2))
            val list=ExperimentalPcmRecoveryManager(dir).assessAll()
            assertTrue(list.any { it.pcmFile?.name==pcm.name && it.recoverable && it.integrity?.sha256!=null })
            assertFalse(list.any { it.pcmFile?.name=="orphan.pcm" && it.recoverable })
        } finally { dir.deleteRecursively() }
    }
    @Test fun checkpointBeyondFileIsRejected() {
        val dir=kotlin.io.path.createTempDirectory("pcm-journal-test").toFile()
        try {
            val pcm=File(dir,"sessionB.pcm"); pcm.writeBytes(byteArrayOf(1,2))
            checkpoint(dir,pcm,"sessionB",4)
            ExperimentalPcmSessionJournal(dir).write(ExperimentalPcmSessionMetadata("sessionB",pcm.name,16000,status=ExperimentalPcmSessionStatus.RECOVERABLE,checkpointBytes=4,updatedAtEpochMs=2))
            assertFalse(ExperimentalPcmRecoveryManager(dir).assessAll().first().recoverable)
        } finally { dir.deleteRecursively() }
    }
    @Test fun checkpointJournalMismatchRejected() {
        val dir=kotlin.io.path.createTempDirectory("pcm-journal-test").toFile()
        try {
            val pcm=File(dir,"sessionC.pcm"); pcm.writeBytes(byteArrayOf(1,2,3,4))
            checkpoint(dir,pcm,"sessionC",2)
            ExperimentalPcmSessionJournal(dir).write(ExperimentalPcmSessionMetadata("sessionC",pcm.name,16000,status=ExperimentalPcmSessionStatus.RECOVERABLE,checkpointBytes=4,updatedAtEpochMs=3))
            assertFalse(ExperimentalPcmRecoveryManager(dir).assessAll().first().recoverable)
        } finally { dir.deleteRecursively() }
    }
}
