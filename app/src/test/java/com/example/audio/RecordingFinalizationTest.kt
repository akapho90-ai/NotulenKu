package com.example.audio

import android.content.Context
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowMediaExtractor
import org.robolectric.shadows.util.DataSource

@RunWith(RobolectricTestRunner::class)
class RecordingFinalizationTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun stopFinalizationKeepsJournalAndSourcesWhenMiddlePartIsMissing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "missing-middle-stop"
        val first = part("part_0000.m4a")
        val missing = File(folder.root, "part_0001.m4a")
        val last = part("part_0002.m4a")
        val expected = listOf(first, missing, last)
        val output = File(folder.root, "final.m4a")
        val journal = saveJournal(context, session, expected)
        try {
            val originalJournal = journal.readText()
            val result = RecordingStateRepository.finalizeSessionToAudioFile(context, session, expected, output, 30)

            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains(missing.name))
            assertFalse(output.exists())
            assertEquals(originalJournal, journal.readText())
            assertTrue(first.isFile)
            assertTrue(last.isFile)
        } finally {
            RecordingStateRepository.deleteSessionJournal(context, session)
        }
    }

    @Test
    fun journalDiscoveryKeepsMissingPartAndRecoveryCannotPublishRemainingPart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "missing-next-recovery"
        val first = part("part_0000.m4a")
        val missing = File(folder.root, "part_0001.m4a")
        val expected = listOf(first, missing)
        val journal = saveJournal(context, session, expected)
        try {
            val originalJournal = journal.readText()
            val candidate = RecordingStateRepository.listRecoverableSessions(context, owner(session)).single()
            assertEquals(expected.map { it.absolutePath }, candidate.segmentPaths)
            assertEquals(first.length(), candidate.totalSizeBytes)

            val result = RecordingStateRepository.recoverSessionToAudioFile(context, candidate)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains(missing.name))
            assertTrue(first.isFile)
            assertEquals(originalJournal, journal.readText())
        } finally {
            RecordingStateRepository.deleteSessionJournal(context, session)
        }
    }

    @Test
    fun finalizationDoesNotPublishWhenAFullSizedPartHasNoReadableAudio() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "unreadable-recording"
        val first = part("part_0000.m4a")
        val corrupt = part("part_0001.m4a")
        ShadowMediaExtractor.addTrack(
            DataSource.toDataSource(first.absolutePath),
            MediaFormat.createAudioFormat("audio/mp4a-latm", 16_000, 1),
            byteArrayOf(1, 2)
        )
        val expected = listOf(first, corrupt)
        val output = File(folder.root, "final.m4a")
        val journal = saveJournal(context, session, expected)
        try {
            val originalJournal = journal.readText()
            val result = RecordingStateRepository.finalizeSessionToAudioFile(context, session, expected, output, 30)

            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains(corrupt.name))
            assertFalse(output.exists())
            assertEquals(originalJournal, journal.readText())
            assertTrue(expected.all { it.isFile })
        } finally {
            RecordingStateRepository.deleteSessionJournal(context, session)
        }
    }

    @Test
    fun journalDiscoveryDoesNotDeleteExpectedPathsWhenEveryPartIsMissing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "all-parts-missing"
        val missing = File(folder.root, "part_0000.m4a")
        val journal = saveJournal(context, session, listOf(missing))
        try {
            val candidate = RecordingStateRepository.listRecoverableSessions(context, owner(session)).single()
            assertEquals(listOf(missing.absolutePath), candidate.segmentPaths)
            assertEquals(0L, candidate.totalSizeBytes)
            assertTrue(journal.isFile)
        } finally {
            RecordingStateRepository.deleteSessionJournal(context, session)
        }
    }

    @Test
    fun completeRecordingPublishesOutputAndClearsJournalOnlyAfterSuccessfulMerge() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "complete-recording"
        val source = part("part_0000.m4a")
        val output = File(folder.root, "final.m4a")
        val staged = File(folder.root, "final.m4a.staged")
        for (path in listOf(source, staged, output)) {
            ShadowMediaExtractor.addTrack(
                DataSource.toDataSource(path.absolutePath),
                MediaFormat.createAudioFormat("audio/mp4a-latm", 16_000, 1),
                byteArrayOf(1, 2)
            )
        }
        val journal = saveJournal(context, session, listOf(source))
        try {
            val result = RecordingStateRepository.finalizeSessionToAudioFile(
                context, session, listOf(source), output, 30
            ).getOrThrow()

            assertEquals(output, result.file)
            assertEquals(512L, result.sizeBytes)
            assertFalse(source.exists())
            assertFalse(journal.exists())
        } finally {
            RecordingStateRepository.deleteSessionJournal(context, session)
        }
    }

    private fun part(name: String): File = File(folder.root, name).apply { writeBytes(ByteArray(512)) }

    private fun owner(session: String) = "owner-$session"

    private fun saveJournal(context: Context, session: String, expected: List<File>): File {
        RecordingStateRepository.saveSessionJournal(
            context, session, owner(session), expected.map { it.absolutePath }, 30, 1
        )
        return File(RecordingStateRepository.sessionMetaDir(context), "session_$session.json")
    }
}
