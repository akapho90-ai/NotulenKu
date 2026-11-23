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
class ExperimentalPcmSegmentWriter internal constructor(
    private val directory: File,
    private val sessionId: String,
    private val sampleRateHz: Int,
    private val flushIntervalBytes: Int,
    private val persistence: PcmPersistenceIo
) : AutoCloseable {
    constructor(
        directory: File,
        sessionId: String,
        sampleRateHz: Int = 16_000,
        flushIntervalBytes: Int = sampleRateHz * 2
    ) : this(directory, sessionId, sampleRateHz, flushIntervalBytes, DefaultPcmPersistenceIo)

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
    private val owner = Any()
    private val journal = ExperimentalPcmSessionJournal(directory, persistence, owner)

    init {
        require(sampleRateHz > 0)
        require(flushIntervalBytes > 0)
    }

    fun open(): File = ExperimentalRecoverySessionLock.withSourceLock(directory, safeSessionId) { openLocked() }

    private fun ExperimentalRecoverySessionLock.SourceSession.openLocked(): File {
        if (failed) throw IOException("Writer PCM sudah berada pada status FAILED.")
        if (stream != null) return file
        claimWriter(owner)
        var opened = false
        try {
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
                throw IOException("Tidak dapat membuat direktori PCM.")
            }
            reconcileExistingFile()
            rawStream = FileOutputStream(file, true)
            stream = BufferedOutputStream(rawStream!!)
            try {
                writeCheckpoint(force = true)
            } catch (e: Exception) {
                failWriter(fallbackCheckpointBytes = bytesWritten)
                throw e
            }
            opened = true
            return file
        } finally {
            if (!opened) {
                closeStreams()
                releaseWriter(owner)
            }
        }
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

        if (checkpoint.exists() && !archiveInvalidPcmRecord(checkpoint, persistence)) {
            throw IOException("Tidak dapat mengarsipkan checkpoint PCM lama.")
        }
        if (!persistence.rename(candidate, checkpoint) || checkpointBytes(checkpoint, actualPcmBytes) != trustedBytes) {
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

    fun append(pcm: ByteArray): Unit = ExperimentalRecoverySessionLock.withSourceLock(directory, safeSessionId) {
        if (failed) throw IOException("Writer PCM gagal; append dihentikan.")
        if (stream == null) openLocked()
        if (pcm.isEmpty()) return@withSourceLock
        try {
            if (pcm.size % 2 != 0) throw IOException("Frame PCM16 memiliki jumlah byte ganjil.")
            persistence.write(file, stream!!, pcm)
            bytesWritten += pcm.size
            bytesSinceFlush += pcm.size
            if (bytesSinceFlush >= flushIntervalBytes) writeCheckpoint(force = false)
        } catch (e: Exception) {
            try {
                failWriter()
            } finally {
                releaseWriter(owner)
            }
            throw e
        }
    }

    private fun writeCheckpoint(force: Boolean) {
        stream?.let { persistence.flush(file, it) }
        rawStream?.let { persistence.sync(file, it.fd) }
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
            persistence.write(checkpointTemp, out, text.toByteArray(Charsets.UTF_8))
            persistence.flush(checkpointTemp, out)
            persistence.sync(checkpointTemp, out.fd)
        }
        if (checkpointTemp.readText(Charsets.UTF_8) != text) {
            throw IOException("Verifikasi checkpoint sementara gagal.")
        }
        if (checkpointBackup.exists() && (!checkpointBackup.isFile || !checkpointBackup.delete())) {
            throw IOException("Tidak dapat membersihkan backup checkpoint PCM.")
        }
        if (checkpoint.exists() && !persistence.rename(checkpoint, checkpointBackup)) {
            throw IOException("Tidak dapat menyimpan backup checkpoint PCM.")
        }
        if (!persistence.rename(checkpointTemp, checkpoint)) {
            if (checkpointBackup.isFile && !checkpoint.exists()) persistence.rename(checkpointBackup, checkpoint)
            throw IOException("Tidak dapat mempromosikan checkpoint PCM.")
        }
        if (checkpoint.readText(Charsets.UTF_8) != text) {
            if (checkpointBackup.isFile && archiveInvalidPcmRecord(checkpoint, persistence)) {
                persistence.rename(checkpointBackup, checkpoint)
            }
            throw IOException("Verifikasi checkpoint hasil penggantian gagal.")
        }
    }

    private fun failWriter(fallbackCheckpointBytes: Long = 0L) {
        failed = true
        runCatching { stream?.let { persistence.flush(file, it) } }
        runCatching { rawStream?.let { persistence.sync(file, it.fd) } }
        closeStreams()
        runCatching {
            val trustedBytes = journal.recoverForSession(safeSessionId)?.checkpointBytes ?: fallbackCheckpointBytes
            journal.write(metadata(ExperimentalPcmSessionStatus.FAILED).copy(checkpointBytes = trustedBytes))
        }
    }

    override fun close(): Unit = ExperimentalRecoverySessionLock.withSourceLock(directory, safeSessionId) {
        val out = stream ?: return@withSourceLock
        try {
            persistence.flush(file, out)
            rawStream?.let { persistence.sync(file, it.fd) }
            writeCheckpoint(force = true)
            journal.write(metadata(ExperimentalPcmSessionStatus.RECOVERABLE))
        } catch (e: Exception) {
            failWriter()
            throw e
        } finally {
            closeStreams()
            releaseWriter(owner)
        }
    }

    private fun closeStreams() {
        val buffered = stream
        runCatching { buffered?.close() ?: rawStream?.close() }
        stream = null
        rawStream = null
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
    val totalBytes: Long
        get() = ExperimentalRecoverySessionLock.withSourceLock(directory, safeSessionId) { bytesWritten }
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
