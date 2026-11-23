package com.example.audio

import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExperimentalPcmPersistenceFailureTest {
    @get:Rule
    val folders = TemporaryFolder()

    @Test
    fun pcmWriteFlushAndSyncFailuresPreserveTheLastCommittedPrefix() {
        for (operation in storageOperations) {
            val dir = folders.newFolder(operation.name)
            val io = FaultIo()
            committedWriter(dir, io).use { writer ->
                val fault = io.arm(operation, PCM)
                assertSame(fault.failure, assertThrows(IOException::class.java) { writer.append(tail) })
                assertThrows(IOException::class.java) { writer.append(tail) }
            }

            assertFailedAtCommittedBoundary(dir)
            val partialTail = if (operation == Operation.WRITE) tail.copyOf(1) else tail
            assertArrayEquals(committed + partialTail, File(dir, PCM).readBytes())
            assertReopensWith(dir, committed)
        }
    }

    @Test
    fun checkpointTempWriteFlushAndSyncFailuresDoNotAdvanceTheCheckpoint() {
        for (operation in storageOperations) {
            val dir = folders.newFolder(operation.name)
            val io = FaultIo()
            committedWriter(dir, io).use { writer ->
                val fault = io.arm(operation, "$CHECKPOINT.tmp")
                assertSame(fault.failure, assertThrows(IOException::class.java) { writer.append(tail) })
            }

            val temp = File(dir, "$CHECKPOINT.tmp")
            assertTrue(temp.isFile)
            val expected = if (operation == Operation.WRITE) null else 6L
            assertEquals(expected, checkpointAt(dir, "$CHECKPOINT.tmp"))
            assertFailedAtCommittedBoundary(dir)
            assertArrayEquals(committed + tail, File(dir, PCM).readBytes())
            assertReopensWith(dir, committed)
            assertFalse(temp.exists())
        }
    }

    @Test
    fun journalTempWriteFlushAndSyncFailuresKeepTheCommittedJournalReadable() {
        for (operation in storageOperations) {
            val dir = folders.newFolder(operation.name)
            val io = FaultIo()
            val journal = ExperimentalPcmSessionJournal(dir, io)
            val previous = metadata(4)
            val update = metadata(6)
            journal.write(previous)
            val fault = io.arm(operation, "$JOURNAL.tmp")
            assertSame(fault.failure, assertThrows(IOException::class.java) { journal.write(update) })

            val temp = File(dir, "$JOURNAL.tmp")
            assertTrue(temp.isFile)
            assertEquals(if (operation == Operation.WRITE) null else update, journal.read(temp))
            val restarted = ExperimentalPcmSessionJournal(dir)
            assertEquals(listOf(previous), restarted.list())
            restarted.write(update)
            assertEquals(listOf(update), restarted.list())
            assertFalse(temp.exists())
        }
    }

    @Test
    fun checkpointRenameFailuresKeepOrRestoreTheCommittedPrimary() {
        for ((source, target) in backupAndPromotion(CHECKPOINT)) {
            val dir = folders.newFolder(source)
            val io = FaultIo()
            committedWriter(dir, io).use { writer ->
                val fault = io.arm(Operation.RENAME, source, target)
                assertThrows(IOException::class.java) { writer.append(tail) }
                assertTrue(fault.hit)
            }

            assertFailedAtCommittedBoundary(dir)
            assertEquals(6L, checkpointAt(dir, "$CHECKPOINT.tmp"))
            assertFalse(File(dir, "$CHECKPOINT.bak").exists())
            assertReopensWith(dir, committed)
        }
    }

    @Test
    fun journalRenameFailuresKeepOrRestoreTheCommittedPrimary() {
        for ((source, target) in backupAndPromotion(JOURNAL)) {
            val dir = folders.newFolder(source)
            val io = FaultIo()
            val journal = ExperimentalPcmSessionJournal(dir, io)
            val previous = metadata(4)
            val update = metadata(6)
            journal.write(previous)
            val fault = io.arm(Operation.RENAME, source, target)
            assertThrows(IOException::class.java) { journal.write(update) }
            assertTrue(fault.hit)

            assertEquals(previous, journal.read(File(dir, JOURNAL)))
            assertEquals(update, journal.read(File(dir, "$JOURNAL.tmp")))
            assertFalse(File(dir, "$JOURNAL.bak").exists())
            assertEquals(listOf(previous), ExperimentalPcmSessionJournal(dir).list())
        }
    }

    @Test
    fun interruptedCommitRecoversOnlyThePcmBoundaryPublishedInTheJournal() {
        val interruptions = listOf(
            Fault(Operation.CRASH_AFTER_SYNC, "$CHECKPOINT.tmp"),
            Fault(Operation.CRASH_AFTER_RENAME, CHECKPOINT, "$CHECKPOINT.bak"),
            Fault(Operation.CRASH_AFTER_RENAME, "$CHECKPOINT.tmp", CHECKPOINT),
            Fault(Operation.CRASH_AFTER_SYNC, "$JOURNAL.tmp"),
            Fault(Operation.CRASH_AFTER_RENAME, JOURNAL, "$JOURNAL.bak"),
            Fault(Operation.CRASH_AFTER_RENAME, "$JOURNAL.tmp", JOURNAL)
        )
        for ((index, interruption) in interruptions.withIndex()) {
            val live = folders.newFolder("live-$index")
            val snapshot = folders.newFolder("snapshot-$index")
            val io = FaultIo()
            committedWriter(live, io).use { writer ->
                io.next = interruption
                assertThrows(SimulatedCrash::class.java) { writer.append(tail) }
                assertTrue(interruption.hit)
                // Capture the on-disk state before close can flush or publish anything else.
                live.listFiles().orEmpty().forEach { it.copyTo(File(snapshot, it.name)) }
            }

            assertArrayEquals(committed + tail, File(snapshot, PCM).readBytes())
            val journalPublished = interruption.source == "$JOURNAL.tmp" && interruption.target == JOURNAL
            assertReopensWith(snapshot, if (journalPublished) committed + tail else committed)
        }
    }

    @Test
    fun interruptedBackupPromotionCanBeRetriedForCheckpointAndJournal() {
        for (primary in listOf(CHECKPOINT, JOURNAL)) {
            val dir = folders.newFolder(primary)
            committedWriter(dir, FaultIo()).close()
            File(dir, PCM).appendBytes(tail)
            val backup = "$primary.bak"
            assertTrue(File(dir, primary).renameTo(File(dir, backup)))
            val partialTemp = File(dir, "$primary.tmp").apply { writeText("session=recover\n") }

            val io = FaultIo()
            val failedRename = io.arm(Operation.RENAME, backup, primary)
            newWriter(dir, io).use { writer ->
                assertThrows(IOException::class.java) { writer.open() }
            }
            assertTrue(failedRename.hit)
            assertTrue(File(dir, backup).isFile)
            assertFalse(File(dir, primary).exists())
            assertEquals("session=recover\n", partialTemp.readText())
            assertArrayEquals(committed + tail, File(dir, PCM).readBytes())

            val fault = io.arm(Operation.CRASH_AFTER_RENAME, backup, primary)
            newWriter(dir, io).use { writer ->
                assertThrows(SimulatedCrash::class.java) { writer.open() }
            }

            assertTrue(fault.hit)
            assertTrue(File(dir, primary).isFile)
            assertFalse(File(dir, backup).exists())
            assertEquals("session=recover\n", partialTemp.readText())
            assertArrayEquals(committed + tail, File(dir, PCM).readBytes())
            assertReopensWith(dir, committed)
            assertFalse(partialTemp.exists())
        }
    }

    private fun newWriter(dir: File, io: PcmPersistenceIo) =
        ExperimentalPcmSegmentWriter(dir, "recover", 16_000, 2, io)

    private fun committedWriter(dir: File, io: PcmPersistenceIo) =
        newWriter(dir, io).apply { append(committed) }

    private fun assertFailedAtCommittedBoundary(dir: File) {
        val journal = ExperimentalPcmSessionJournal(dir).read(File(dir, JOURNAL))
        assertEquals(ExperimentalPcmSessionStatus.FAILED, journal?.status)
        assertEquals(4L, journal?.checkpointBytes)
        assertEquals(4L, checkpointAt(dir))
    }

    private fun assertReopensWith(dir: File, expected: ByteArray) {
        ExperimentalPcmSegmentWriter(dir, "recover").use { restarted ->
            assertArrayEquals(expected, restarted.open().readBytes())
            assertEquals(expected.size.toLong(), restarted.totalBytes)
        }
        assertEquals(expected.size.toLong(), checkpointAt(dir))
        val journal = ExperimentalPcmSessionJournal(dir).read(File(dir, JOURNAL))
        assertEquals(expected.size.toLong(), journal?.checkpointBytes)
    }

    private fun checkpointAt(dir: File, name: String = CHECKPOINT) =
        readPcmCheckpointBytes(File(dir, name), "recover", PCM, 16_000, File(dir, PCM).length())

    private fun metadata(bytes: Long) = ExperimentalPcmSessionMetadata(
        "recover", PCM, 16_000, status = ExperimentalPcmSessionStatus.RECOVERABLE,
        checkpointBytes = bytes, updatedAtEpochMs = bytes
    )

    private fun backupAndPromotion(primary: String) =
        listOf(primary to "$primary.bak", "$primary.tmp" to primary)

    private enum class Operation { WRITE, FLUSH, SYNC, RENAME, CRASH_AFTER_SYNC, CRASH_AFTER_RENAME }

    private class Fault(val operation: Operation, val source: String, val target: String? = null) {
        val failure = IOException("Injected $operation for $source")
        var hit = false
    }

    // Bypass best-effort IOException cleanup when taking a process interruption snapshot.
    private class SimulatedCrash : Error()

    private class FaultIo : PcmPersistenceIo {
        var next: Fault? = null

        fun arm(operation: Operation, source: String, target: String? = null) =
            Fault(operation, source, target).also { next = it }

        private fun consume(operation: Operation, source: File, target: File? = null): Fault? {
            val fault = next ?: return null
            if (fault.operation != operation || fault.source != source.name || fault.target != target?.name) return null
            next = null
            fault.hit = true
            return fault
        }

        override fun write(file: File, output: OutputStream, bytes: ByteArray) {
            val fault = consume(Operation.WRITE, file)
            if (fault != null) {
                output.write(bytes, 0, if (file.name == PCM) 1 else bytes.size / 2)
                throw fault.failure
            }
            super.write(file, output, bytes)
        }

        override fun flush(file: File, output: OutputStream) {
            consume(Operation.FLUSH, file)?.let { throw it.failure }
            super.flush(file, output)
        }

        override fun sync(file: File, descriptor: FileDescriptor) {
            consume(Operation.SYNC, file)?.let { throw it.failure }
            super.sync(file, descriptor)
            if (consume(Operation.CRASH_AFTER_SYNC, file) != null) throw SimulatedCrash()
        }

        override fun rename(source: File, target: File): Boolean {
            if (consume(Operation.RENAME, source, target) != null) return false
            val renamed = super.rename(source, target)
            if (renamed && consume(Operation.CRASH_AFTER_RENAME, source, target) != null) throw SimulatedCrash()
            return renamed
        }
    }

    private companion object {
        const val PCM = "recover.pcm"
        const val CHECKPOINT = "$PCM.checkpoint"
        const val JOURNAL = "$PCM.journal"
        val committed = byteArrayOf(1, 2, 3, 4)
        val tail = byteArrayOf(5, 6)
        val storageOperations = listOf(Operation.WRITE, Operation.FLUSH, Operation.SYNC)
    }
}
