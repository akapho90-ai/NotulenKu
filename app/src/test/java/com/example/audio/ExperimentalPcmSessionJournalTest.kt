package com.example.audio

import java.io.File
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalPcmSessionJournalTest {
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
