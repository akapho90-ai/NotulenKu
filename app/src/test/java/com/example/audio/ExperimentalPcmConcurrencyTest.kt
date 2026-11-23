package com.example.audio

import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExperimentalPcmConcurrencyTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun secondWriterAndExternalJournalCannotModifyAnOpenSession() {
        val root = folder.root
        val alias = File(root, ".")
        ExperimentalPcmSegmentWriter(root, ID, flushIntervalBytes = 64).use { first ->
            // No operation is currently running, but the writer still owns the session and has buffered PCM.
            first.append(prefix)
            ExperimentalPcmSegmentWriter(alias, ID).use { second ->
                Running {
                    assertThrows(IOException::class.java) { second.open() }
                    assertThrows(IOException::class.java) { ExperimentalPcmSessionJournal(alias).write(record(0)) }
                }.result()
                assertEquals(0L, File(root, PCM).length())

                Running { first.close() }.result()
                Running { second.append(tail) }.result()
            }
        }
        assertCommitted(root, prefix + tail)
    }

    @Test
    fun concurrentAppendsOnTheSameWriterPublishOneOrderedCheckpoint() {
        val root = folder.root
        val io = PausingIo()
        writer(root, io).use { writer ->
            writer.append(prefix)
            val pause = io.pauseAfter(CHECKPOINT, "$CHECKPOINT.bak")
            val first = Running { writer.append(tail) }
            val second = try {
                pause.awaitEntered()
                Running { writer.append(lastFrame) }.also { it.awaitBlocked() }
            } finally {
                pause.resume()
            }
            first.result()
            second.result()
            assertEquals(8L, writer.totalBytes)
            assertCommitted(root, prefix + tail + lastFrame)
        }
    }

    @Test
    fun reopenWaitsForThePreviousWriterToFinishClosing() {
        val root = folder.root
        val io = PausingIo()
        writer(root, io, flushInterval = 64).use { first ->
            first.append(prefix)
            val pause = io.pauseAfter(JOURNAL, "$JOURNAL.bak")
            val closing = Running { first.close() }
            ExperimentalPcmSegmentWriter(File(root, "."), ID).use { reopened ->
                val reopening = try {
                    pause.awaitEntered()
                    Running { reopened.open() }.also { it.awaitBlocked() }
                } finally {
                    pause.resume()
                }
                closing.result()
                assertArrayEquals(prefix, reopening.result().readBytes())
                reopened.append(tail)
            }
        }
        assertCommitted(root, prefix + tail)
    }

    @Test
    fun journalReadersAndRecoveryScanWaitForTheSameCommitWhileAnotherSessionCanWrite() {
        val root = folder.root
        val io = PausingIo()
        writer(root, io).use { writer ->
            writer.append(prefix)
            val alias = File(root, ".")
            val pause = io.pauseAfter(JOURNAL, "$JOURNAL.bak")
            val writing = Running { writer.append(tail) }
            val scans = try {
                pause.awaitEntered()
                assertFalse(File(root, JOURNAL).exists())
                val list = Running { ExperimentalPcmSessionJournal(alias).list() }.also { it.awaitBlocked() }
                val read = Running { ExperimentalPcmSessionJournal(alias).read(File(alias, JOURNAL)) }
                    .also { it.awaitBlocked() }
                val assessments = Running { ExperimentalPcmRecoveryManager(alias).assessAll() }
                    .also { it.awaitBlocked() }
                Running {
                    ExperimentalPcmSegmentWriter(root, "other").use { it.append(prefix) }
                }.result()
                Triple(list, read, assessments)
            } finally {
                pause.resume()
            }

            writing.result()
            assertEquals(6L, scans.first.result().single { it.sessionId == ID }.checkpointBytes)
            assertEquals(6L, scans.second.result()?.checkpointBytes)
            val assessment = scans.third.result().single { it.metadata?.sessionId == ID }
            assertTrue(assessment.recoverable)
            assertEquals(6L, assessment.integrity?.trustedBytes)
            assertCommitted(root, prefix + tail)
        }
    }

    @Test
    fun separateJournalInstancesSerializeReplacementThroughCanonicalDirectory() {
        val root = folder.root
        val io = PausingIo()
        val first = ExperimentalPcmSessionJournal(root, io)
        val second = ExperimentalPcmSessionJournal(File(root, "."))
        first.write(record(2))
        val pause = io.pauseAfter(JOURNAL, "$JOURNAL.bak")
        val firstWrite = Running { first.write(record(4)) }
        val secondWrite = try {
            pause.awaitEntered()
            Running { second.write(record(6)) }.also { it.awaitBlocked() }
        } finally {
            pause.resume()
        }

        firstWrite.result()
        secondWrite.result()
        assertEquals(listOf(record(6)), first.list())
        assertFalse(File(root, "$JOURNAL.bak").exists())
        assertFalse(File(root, "$JOURNAL.tmp").exists())
    }

    @Test
    fun recoveryRejectsActiveWriterAndStaleCheckpointBeforeCreatingOutput() {
        val root = folder.root
        ExperimentalPcmSegmentWriter(root, ID, sampleRateHz = RATE).use { it.append(prefix) }
        val manager = ExperimentalPcmRecoveryManager(root)
        val executor = ExperimentalPcmRecoveryExecutor(File(root, "."))
        val engine = ExperimentalPcmRecoveryDecisionEngine()
        val stale = manager.assessAll().single()
        val previousDecision = engine.decide(stale)
        assertEquals(PcmRecoveryDecisionType.RECOVERABLE, previousDecision.type)

        ExperimentalPcmSegmentWriter(root, ID, sampleRateHz = RATE).use { writer ->
            writer.open()
            Running {
                assertThrows(IOException::class.java) { executor.execute(stale, previousDecision, true) }
            }.result()
            writer.append(tail)
        }
        assertThrows(IOException::class.java) { executor.execute(stale, previousDecision, true) }
        assertFalse(File(root, "recovered").exists())

        val current = manager.assessAll().single()
        val recovered = executor.execute(current, engine.decide(current), true)
        assertArrayEquals(prefix + tail, recovered.outputFile.readBytes())
        assertCommitted(root, prefix + tail, RATE)
    }

    private fun writer(root: File, io: PcmPersistenceIo, flushInterval: Int = 2) =
        ExperimentalPcmSegmentWriter(root, ID, 16_000, flushInterval, io)

    private fun assertCommitted(root: File, expected: ByteArray, sampleRate: Int = 16_000) {
        val pcm = File(root, PCM)
        assertArrayEquals(expected, pcm.readBytes())
        assertEquals(
            expected.size.toLong(),
            readPcmCheckpointBytes(File(root, CHECKPOINT), ID, PCM, sampleRate, pcm.length())
        )
        assertEquals(expected.size.toLong(), ExperimentalPcmSessionJournal(root).read(File(root, JOURNAL))?.checkpointBytes)
    }

    private fun record(bytes: Long) = ExperimentalPcmSessionMetadata(
        ID, PCM, 16_000, status = ExperimentalPcmSessionStatus.RECOVERABLE,
        checkpointBytes = bytes, updatedAtEpochMs = bytes
    )

    private class Running<T>(action: () -> T) {
        private val task = FutureTask(Callable { action() })
        private val thread = Thread(task).apply {
            isDaemon = true
            start()
        }

        fun awaitBlocked() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT)
            while (thread.state != Thread.State.BLOCKED && !task.isDone && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(Thread.State.BLOCKED, thread.state)
        }

        fun result(): T = task.get(TIMEOUT, TimeUnit.SECONDS)
    }

    private class RenamePause(val source: String, val target: String) {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)

        fun awaitEntered() = assertTrue(entered.await(TIMEOUT, TimeUnit.SECONDS))
        fun resume() = released.countDown()
    }

    private class PausingIo : PcmPersistenceIo {
        @Volatile private var next: RenamePause? = null

        fun pauseAfter(source: String, target: String) = RenamePause(source, target).also { next = it }

        override fun rename(source: File, target: File): Boolean {
            val renamed = super.rename(source, target)
            val pause = next
            if (renamed && pause != null && source.name == pause.source && target.name == pause.target) {
                next = null
                pause.entered.countDown()
                check(pause.released.await(TIMEOUT, TimeUnit.SECONDS)) { "Timed out waiting to resume rename." }
            }
            return renamed
        }
    }

    private companion object {
        const val ID = "recover"
        const val PCM = "$ID.pcm"
        const val CHECKPOINT = "$PCM.checkpoint"
        const val JOURNAL = "$PCM.journal"
        const val RATE = 1_000
        const val TIMEOUT = 10L
        val prefix = byteArrayOf(1, 2, 3, 4)
        val tail = byteArrayOf(5, 6)
        val lastFrame = byteArrayOf(7, 8)
    }
}
