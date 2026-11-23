package com.example.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Experimental append-only PCM segment writer with checkpoint-based reopen recovery.
 * Raw PCM16 mono only; not a finalized recording and not production recovery.
 */
class ExperimentalPcmSegmentWriter(
    private val directory: File,
    private val sessionId: String,
    private val sampleRateHz: Int = 16_000,
    private val flushIntervalBytes: Int = sampleRateHz * 2
) : AutoCloseable {
    private val safeSessionId = validateSessionId(sessionId)
    private val file = File(directory, "$safeSessionId.pcm")
    private val checkpoint = File(directory, "$safeSessionId.pcm.checkpoint")
    private var rawStream: FileOutputStream? = null
    private var stream: BufferedOutputStream? = null
    private var bytesWritten = 0L
    private var bytesSinceFlush = 0
    private var failed = false
    private val journal = ExperimentalPcmSessionJournal(directory)

    init {
        require(sampleRateHz > 0)
        require(flushIntervalBytes > 0)
    }

    @Synchronized
    fun open(): File {
        if (failed) throw IOException("Writer PCM sudah berada pada status FAILED.")
        if (stream != null) return file
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Tidak dapat membuat direktori PCM.")
        reconcileExistingFile()
        rawStream = FileOutputStream(file, true)
        stream = BufferedOutputStream(rawStream!!)
        bytesWritten = file.length()
        try {
            writeCheckpoint(force = true)
            journal.write(metadata(ExperimentalPcmSessionStatus.RECORDING))
        } catch (e: Exception) {
            failWriter(e)
            throw e
        }
        return file
    }

    private fun reconcileExistingFile() {
        if (!file.exists()) { bytesWritten = 0L; return }
        val actual = file.length()
        if (!checkpoint.exists()) {
            throw IOException("File PCM lama ditemukan tanpa checkpoint; append ditolak untuk mencegah reuse data yang tidak terverifikasi.")
        }
        var trustedBytes = actual
        if (checkpoint.exists()) {
            val values = checkpoint.readLines().mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
            }.toMap()
            if (values["session"] != safeSessionId ||
                values["format"] != "pcm16_mono" ||
                values["sample_rate"] != sampleRateHz.toString()
            ) throw IOException("Checkpoint PCM tidak cocok dengan sesi atau format.")
            val recorded = values["bytes"]?.toLongOrNull()
                ?: throw IOException("Checkpoint PCM tidak valid.")
            if (recorded < 0L || recorded > actual) throw IOException("Checkpoint PCM melebihi ukuran file.")
            trustedBytes = recorded
        }
        trustedBytes -= trustedBytes % 2L
        if (trustedBytes != actual) {
            RandomAccessFile(file, "rw").use { it.setLength(trustedBytes); it.fd.sync() }
        }
        bytesWritten = trustedBytes
    }

    @Synchronized
    fun append(pcm: ByteArray) {
        if (failed) throw IOException("Writer PCM gagal; append dihentikan.")
        if (stream == null) open()
        if (pcm.isEmpty()) return
        try {
            if (pcm.size % 2 != 0) throw IOException("Frame PCM16 memiliki jumlah byte ganjil.")
            stream!!.write(pcm)
            bytesWritten += pcm.size
            bytesSinceFlush += pcm.size
            if (bytesSinceFlush >= flushIntervalBytes) writeCheckpoint(force = false)
        } catch (e: Exception) {
            failWriter(e)
            throw e
        }
    }

    private fun writeCheckpoint(force: Boolean) {
        stream?.flush()
        rawStream?.fd?.sync()
        if (force || bytesSinceFlush >= flushIntervalBytes) {
            val text = "session=$safeSessionId\nformat=pcm16_mono\nsample_rate=$sampleRateHz\nbytes=$bytesWritten\n"
            atomicReplace(checkpoint, text)
            bytesSinceFlush = 0
            journal.write(metadata(ExperimentalPcmSessionStatus.RECORDING))
        }
    }

    private fun atomicReplace(target: File, text: String) {
        val temp = File(directory, target.name + ".tmp")
        val backup = File(directory, target.name + ".bak")
        try {
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            // Retain the previous valid file until the new candidate is verified.
            if (backup.exists() && !backup.delete()) throw IOException("Tidak dapat membersihkan backup metadata.")
            if (target.exists() && !target.renameTo(backup)) throw IOException("Tidak dapat menyimpan backup metadata.")
            if (!temp.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                throw IOException("Tidak dapat mempromosikan metadata baru.")
            }
            if (target.readText(Charsets.UTF_8) != text) {
                target.delete()
                if (backup.exists()) backup.renameTo(target)
                throw IOException("Verifikasi metadata hasil penggantian gagal.")
            }
            if (backup.exists() && !backup.delete()) backup.deleteOnExit()
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun failWriter(cause: Throwable) {
        failed = true
        runCatching { stream?.flush() }
        runCatching { rawStream?.fd?.sync() }
        runCatching { stream?.close() }
        stream = null
        rawStream = null
        runCatching { journal.write(metadata(ExperimentalPcmSessionStatus.FAILED)) }
    }

    @Synchronized
    override fun close() {
        val out = stream ?: return
        if (failed) { runCatching { out.close() }; stream = null; rawStream = null; return }
        try {
            out.flush()
            rawStream?.fd?.sync()
            writeCheckpoint(force = true)
            journal.write(metadata(ExperimentalPcmSessionStatus.RECOVERABLE))
        } catch (e: Exception) {
            failWriter(e)
            throw e
        } finally {
            runCatching { out.close() }
            stream = null
            rawStream = null
        }
    }

    private fun metadata(status: ExperimentalPcmSessionStatus) = ExperimentalPcmSessionMetadata(
        sessionId = sessionId, fileName = file.name, sampleRateHz = sampleRateHz,
        status = status, checkpointBytes = bytesWritten, updatedAtEpochMs = System.currentTimeMillis()
    )

    private fun validateSessionId(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "ID sesi PCM tidak valid." }
        return value
    }

    val currentFile: File get() = file
    val totalBytes: Long get() = bytesWritten
}
