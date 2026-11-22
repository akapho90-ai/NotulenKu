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
    private val safeSessionId = sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_")
    private val file = File(directory, "$safeSessionId.pcm")
    private val checkpoint = File(directory, "$safeSessionId.pcm.checkpoint")
    private var stream: BufferedOutputStream? = null
    private var bytesWritten = 0L
    private var bytesSinceFlush = 0

    init {
        require(sessionId.isNotBlank())
        require(sampleRateHz > 0)
        require(flushIntervalBytes > 0)
    }

    /**
     * Reconciles an existing PCM file with its checkpoint before append.
     * Uncheckpointed bytes are discarded; sample alignment (2-byte PCM16) is enforced.
     */
    @Synchronized
    fun open(): File {
        if (stream != null) return file
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Tidak dapat membuat direktori PCM.")
        reconcileExistingFile()
        stream = BufferedOutputStream(FileOutputStream(file, true))
        bytesWritten = file.length()
        writeCheckpoint(force = true)
        return file
    }

    private fun reconcileExistingFile() {
        if (!file.exists()) {
            bytesWritten = 0L
            return
        }
        val actual = file.length()
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
            RandomAccessFile(file, "rw").use { it.setLength(trustedBytes) }
        }
        bytesWritten = trustedBytes
    }

    @Synchronized
    fun append(pcm: ByteArray) {
        if (stream == null) open()
        if (pcm.isEmpty()) return
        // Keep each write sample-aligned. Caller normally delivers aligned frames.
        if (pcm.size % 2 != 0) throw IOException("Frame PCM16 memiliki jumlah byte ganjil.")
        stream!!.write(pcm)
        bytesWritten += pcm.size
        bytesSinceFlush += pcm.size
        if (bytesSinceFlush >= flushIntervalBytes) writeCheckpoint(force = false)
    }

    private fun writeCheckpoint(force: Boolean) {
        stream?.flush()
        if (force || bytesSinceFlush >= flushIntervalBytes) {
            val temp = File(directory, checkpoint.name + ".tmp")
            temp.writeText("session=$safeSessionId\nformat=pcm16_mono\nsample_rate=$sampleRateHz\nbytes=$bytesWritten\n")
            if (checkpoint.exists() && !checkpoint.delete()) {
                temp.delete()
                throw IOException("Tidak dapat mengganti checkpoint PCM.")
            }
            if (!temp.renameTo(checkpoint)) {
                temp.copyTo(checkpoint, overwrite = true)
                temp.delete()
            }
            bytesSinceFlush = 0
        }
    }

    @Synchronized
    override fun close() {
        val out = stream ?: return
        try {
            out.flush()
            writeCheckpoint(force = true)
        } finally {
            runCatching { out.close() }
            stream = null
        }
    }

    val currentFile: File get() = file
    val totalBytes: Long get() = bytesWritten
}
