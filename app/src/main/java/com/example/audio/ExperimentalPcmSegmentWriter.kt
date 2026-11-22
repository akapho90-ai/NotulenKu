package com.example.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Experimental append-only PCM segment writer.
 * This stores raw PCM16 mono data and a lightweight checkpoint sidecar; it is not
 * a finalized/exportable recording and does not replace the current M4A recorder.
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

    @Synchronized
    fun open(): File {
        if (stream != null) return file
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Tidak dapat membuat direktori PCM.")
        }
        stream = BufferedOutputStream(FileOutputStream(file, true))
        bytesWritten = file.length()
        writeCheckpoint(force = true)
        return file
    }

    @Synchronized
    fun append(pcm: ByteArray) {
        if (stream == null) open()
        if (pcm.isEmpty()) return
        stream!!.write(pcm)
        bytesWritten += pcm.size
        bytesSinceFlush += pcm.size
        if (bytesSinceFlush >= flushIntervalBytes) writeCheckpoint(force = false)
    }

    private fun writeCheckpoint(force: Boolean) {
        val out = stream
        if (out != null) out.flush()
        if (force || bytesSinceFlush >= flushIntervalBytes) {
            val temp = File(directory, checkpoint.name + ".tmp")
            temp.writeText(
                "session=$safeSessionId\nformat=pcm16_mono\nsample_rate=$sampleRateHz\nbytes=$bytesWritten\n"
            )
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
