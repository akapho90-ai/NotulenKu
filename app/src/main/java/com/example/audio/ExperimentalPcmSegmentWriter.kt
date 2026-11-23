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
    private val checkpointBackup = File(directory, checkpoint.name + ".bak")
    private val checkpointTemp = File(directory, checkpoint.name + ".tmp")
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
        try {
            writeCheckpoint(force = true)
        } catch (e: Exception) {
            failWriter(fallbackCheckpointBytes = bytesWritten)
            throw e
        }
        return file
    }

    private fun recoverCheckpoint(actualPcmBytes: Long): Long {
        val primaryBytes = checkpointBytes(checkpoint, actualPcmBytes)
        val record = journal.recoverForSession(safeSessionId)
        if (record == null) {
            // Older sessions may only have a primary checkpoint. Sidecars still require a journal.
            return primaryBytes ?: throw IOException("Checkpoint PCM tidak valid dan journal tidak tersedia.")
        }
        if (record.fileName != file.name || record.sampleRateHz != sampleRateHz || record.format != "pcm16_mono") {
            throw IOException("Journal PCM tidak cocok dengan file atau format sesi.")
        }

        val trustedBytes = record.checkpointBytes
        if (primaryBytes == trustedBytes) return trustedBytes
        val candidate = listOf(checkpointBackup, checkpointTemp).firstOrNull {
            checkpointBytes(it, actualPcmBytes) == trustedBytes
        } ?: throw IOException("Tidak ada checkpoint PCM yang cocok dengan journal dan ukuran file.")

        if (checkpoint.exists() && !archiveInvalidPcmRecord(checkpoint)) {
            throw IOException("Tidak dapat mengarsipkan checkpoint PCM lama.")
        }
        if (!candidate.renameTo(checkpoint) || checkpointBytes(checkpoint, actualPcmBytes) != trustedBytes) {
            throw IOException("Tidak dapat memulihkan checkpoint PCM.")
        }
        if (checkpointBackup.isFile) checkpointBackup.delete()
        if (checkpointTemp.isFile) checkpointTemp.delete()
        return trustedBytes
    }

    private fun checkpointBytes(candidate: File, actualPcmBytes: Long): Long? =
        readPcmCheckpointBytes(candidate, safeSessionId, file.name, sampleRateHz, actualPcmBytes)

    private fun reconcileExistingFile() {
        if (!file.exists()) {
            if (checkpoint.exists() || checkpointBackup.exists() || checkpointTemp.exists() ||
                journal.recoverForSession(safeSessionId) != null
            ) {
                throw IOException("File PCM untuk sesi yang sudah tercatat tidak ditemukan.")
            }
            bytesWritten = 0L
            return
        }
        if (!file.isFile) throw IOException("Path PCM bukan file.")
        val actual = file.length()
        val trustedBytes = recoverCheckpoint(actual)
        if (trustedBytes != actual) {
            RandomAccessFile(file, "rw").use {
                it.setLength(trustedBytes)
                it.fd.sync()
            }
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
            failWriter()
            throw e
        }
    }

    private fun writeCheckpoint(force: Boolean) {
        stream?.flush()
        rawStream?.fd?.sync()
        if (force || bytesSinceFlush >= flushIntervalBytes) {
            val text = listOf(
                "session=$safeSessionId", "file=${file.name}", "format=pcm16_mono",
                "sample_rate=$sampleRateHz", "bytes=$bytesWritten"
            ).joinToString("\n", postfix = "\n")
            replaceCheckpoint(text)
            journal.write(metadata(ExperimentalPcmSessionStatus.RECORDING))
            bytesSinceFlush = 0
            // The previous checkpoint is still needed until the new journal is committed.
            if (checkpointBackup.isFile) checkpointBackup.delete()
        }
    }

    private fun replaceCheckpoint(text: String) {
        FileOutputStream(checkpointTemp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (checkpointTemp.readText(Charsets.UTF_8) != text) {
            throw IOException("Verifikasi checkpoint sementara gagal.")
        }
        if (checkpointBackup.exists() && (!checkpointBackup.isFile || !checkpointBackup.delete())) {
            throw IOException("Tidak dapat membersihkan backup checkpoint PCM.")
        }
        if (checkpoint.exists() && !checkpoint.renameTo(checkpointBackup)) {
            throw IOException("Tidak dapat menyimpan backup checkpoint PCM.")
        }
        if (!checkpointTemp.renameTo(checkpoint)) {
            if (checkpointBackup.isFile && !checkpoint.exists()) checkpointBackup.renameTo(checkpoint)
            throw IOException("Tidak dapat mempromosikan checkpoint PCM.")
        }
        if (checkpoint.readText(Charsets.UTF_8) != text) {
            if (checkpointBackup.isFile && archiveInvalidPcmRecord(checkpoint)) {
                checkpointBackup.renameTo(checkpoint)
            }
            throw IOException("Verifikasi checkpoint hasil penggantian gagal.")
        }
    }

    private fun failWriter(fallbackCheckpointBytes: Long = 0L) {
        failed = true
        runCatching { stream?.flush() }
        runCatching { rawStream?.fd?.sync() }
        runCatching { stream?.close() }
        stream = null
        rawStream = null
        runCatching {
            val trustedBytes = journal.recoverForSession(safeSessionId)?.checkpointBytes ?: fallbackCheckpointBytes
            journal.write(metadata(ExperimentalPcmSessionStatus.FAILED).copy(checkpointBytes = trustedBytes))
        }
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
            failWriter()
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

/** Returns null for malformed or mismatched metadata. I/O errors leave recovery blocked. */
internal fun readPcmCheckpointBytes(
    checkpoint: File,
    sessionId: String,
    pcmFileName: String,
    sampleRateHz: Int,
    actualPcmBytes: Long
): Long? {
    if (!checkpoint.isFile || pcmFileName != "$sessionId.pcm") return null
    val values = mutableMapOf<String, String>()
    checkpoint.useLines(Charsets.UTF_8) { lines ->
        for (line in lines) {
            if (line.isBlank()) continue
            val separator = line.indexOf('=')
            if (separator <= 0) return null
            if (values.put(line.substring(0, separator), line.substring(separator + 1)) != null) return null
        }
    }
    val recordedFileName = values["file"]
    if (values["session"] != sessionId || values["format"] != "pcm16_mono" ||
        values["sample_rate"] != sampleRateHz.toString() ||
        (recordedFileName != null && recordedFileName != pcmFileName)
    ) return null
    val bytes = values["bytes"]?.toLongOrNull() ?: return null
    return bytes.takeIf { it >= 0 && it <= actualPcmBytes && it % 2L == 0L }
}
