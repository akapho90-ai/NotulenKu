package com.example

import com.example.data.repository.AudioChunkCheckpoint
import com.example.data.repository.ChunkStatus
import com.example.data.repository.LocalProcessingCheckpointStore
import com.example.data.repository.LocalProcessingJob
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProcessingCheckpointRecoveryTest {
    @get:Rule val folder = TemporaryFolder()

    private fun job(id: String = "meeting-1", owner: String = "account_A") = LocalProcessingJob(
        meetingId = id,
        ownerUserId = owner,
        judul = "Rapat",
        originalAudioPath = File(folder.root, "original.m4a").absolutePath,
        mimeType = "audio/mp4",
        durasiDetik = 15,
        ukuranBytes = 10
    )

    @Test fun backupOnlyRecoveryIsListedAndCleanupDoesNotResurrectIt() {
        val store = LocalProcessingCheckpointStore(folder.root)
        val job = job()
        File(job.originalAudioPath).writeText("original")
        assertTrue(store.saveJob(job))
        assertTrue(store.saveJob(job.copy(judul = "Revisi")))
        val file = File(folder.root, "account_A/job_meeting-1.json")
        assertTrue(file.delete())

        val restartedStore = LocalProcessingCheckpointStore(folder.root)
        assertEquals(job, restartedStore.loadJob(job.ownerUserId, job.meetingId))
        assertEquals(listOf(job), restartedStore.listJobs(job.ownerUserId))
        assertTrue(restartedStore.removeJob(job.ownerUserId, job.meetingId))
        assertNull(restartedStore.loadJob(job.ownerUserId, job.meetingId))
        assertEquals("original", File(job.originalAudioPath).readText())
    }

    @Test fun failedWriteKeepsLastReadableCheckpoint() {
        val store = LocalProcessingCheckpointStore(folder.root)
        val job = job()
        assertTrue(store.saveJob(job))
        File(folder.root, "account_A/job_meeting-1.json.tmp").mkdir()

        assertFalse(store.saveJob(job.copy(judul = "Tidak tersimpan")))
        assertEquals(job, store.loadJob(job.ownerUserId, job.meetingId))
    }

    @Test fun corruptPrimaryAndWrongOwnerOrMeetingIdNeverResume() {
        val store = LocalProcessingCheckpointStore(folder.root)
        val job = job()
        assertTrue(store.saveJob(job))
        assertTrue(store.saveJob(job.copy(judul = "Revisi")))
        val ownerDir = File(folder.root, "account_A")
        val file = File(ownerDir, "job_meeting-1.json")
        file.writeText("{")
        val otherOwnerFile = File(folder.root, "account_B/job_meeting-1.json")
        otherOwnerFile.parentFile!!.mkdirs()
        File(ownerDir, "job_meeting-1.json.bak").copyTo(otherOwnerFile)
        otherOwnerFile.copyTo(File(ownerDir, "job_other.json"))

        assertEquals(job, store.loadJob(job.ownerUserId, job.meetingId))
        assertNull(store.loadJob("account_B", job.meetingId))
        assertNull(store.loadJob(job.ownerUserId, "other"))
        assertFalse(store.saveJob(job(id = "../other")))
    }

    @Test fun incompleteChunksCannotBeCheckpointedAsFinished() {
        val job = job()
        val store = LocalProcessingCheckpointStore(folder.root)
        val pending = AudioChunkCheckpoint(0, 0, 15, job.originalAudioPath, job.mimeType, false)
        assertFalse(store.saveJob(job.copy(chunks = listOf(pending), transcriptDone = true, mergedTranscriptText = "Partial")))
        assertFalse(store.saveJob(job.copy(chunks = listOf(pending.copy(status = ChunkStatus.DONE)))))
        assertFalse(store.saveJob(job.copy(summaryDone = true, ringkasan = "Ringkasan")))
        assertNull(store.loadJob(job.ownerUserId, job.meetingId))
    }
}
