package com.example.audio

import java.io.File
import java.io.IOException

enum class ExperimentalPcmSessionStatus { RECORDING, RECOVERABLE, FINALIZED, FAILED }

data class ExperimentalPcmSessionMetadata(
    val sessionId: String,
    val fileName: String,
    val sampleRateHz: Int,
    val format: String = "pcm16_mono",
    val status: ExperimentalPcmSessionStatus,
    val checkpointBytes: Long,
    val updatedAtEpochMs: Long
)

class ExperimentalPcmSessionJournal(private val directory: File) {
    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
    private fun journalFile(id: String) = File(directory, "${safe(id)}.pcm.journal")

    @Synchronized
    fun write(metadata: ExperimentalPcmSessionMetadata) {
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Tidak dapat membuat direktori journal PCM.")
        val file = journalFile(metadata.sessionId)
        val temp = File(directory, file.name + ".tmp")
        temp.writeText(listOf(
            "session=${metadata.sessionId}",
            "file=${metadata.fileName}",
            "sample_rate=${metadata.sampleRateHz}",
            "format=${metadata.format}",
            "status=${metadata.status.name}",
            "checkpoint_bytes=${metadata.checkpointBytes}",
            "updated_at=${metadata.updatedAtEpochMs}"
        ).joinToString("\n", postfix="\n"))
        if (file.exists() && !file.delete()) { temp.delete(); throw IOException("Tidak dapat mengganti journal PCM.") }
        if (!temp.renameTo(file)) { temp.copyTo(file, overwrite=true); temp.delete() }
    }

    fun read(file: File): ExperimentalPcmSessionMetadata? = runCatching {
        val values = file.readLines().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0,i) to line.substring(i+1)
        }.toMap()
        ExperimentalPcmSessionMetadata(
            sessionId=values["session"] ?: return null,
            fileName=values["file"] ?: return null,
            sampleRateHz=values["sample_rate"]?.toIntOrNull() ?: return null,
            format=values["format"] ?: return null,
            status=ExperimentalPcmSessionStatus.valueOf(values["status"] ?: return null),
            checkpointBytes=values["checkpoint_bytes"]?.toLongOrNull() ?: return null,
            updatedAtEpochMs=values["updated_at"]?.toLongOrNull() ?: return null
        )
    }.getOrNull()

    fun list(): List<ExperimentalPcmSessionMetadata> =
        directory.listFiles { f -> f.isFile && f.name.endsWith(".pcm.journal") }
            ?.mapNotNull(::read)?.sortedByDescending { it.updatedAtEpochMs } ?: emptyList()
}

data class ExperimentalPcmRecoveryAssessment(
    val metadata: ExperimentalPcmSessionMetadata?,
    val pcmFile: File?,
    val recoverable: Boolean,
    val reason: String
)

/** Scans experimental sessions only; does not automatically resume capture or mutate files. */
class ExperimentalPcmRecoveryManager(private val directory: File) {
    private val journal = ExperimentalPcmSessionJournal(directory)

    fun assessAll(): List<ExperimentalPcmRecoveryAssessment> {
        val records = journal.list()
        val assessments = records.map { record ->
            val file = File(directory, record.fileName)
            val checkpointFile = File(directory, "${file.name}.checkpoint")
            val valid = file.exists() && record.format == "pcm16_mono" &&
                record.sampleRateHz > 0 && record.checkpointBytes >= 0 &&
                record.checkpointBytes <= file.length() && record.checkpointBytes % 2L == 0L &&
                record.status != ExperimentalPcmSessionStatus.FINALIZED &&
                record.status != ExperimentalPcmSessionStatus.FAILED
            ExperimentalPcmRecoveryAssessment(
                record, file, valid,
                when {
                    !file.exists() -> "File PCM tidak ditemukan."
                    record.status == ExperimentalPcmSessionStatus.FINALIZED -> "Sesi sudah final."
                    record.status == ExperimentalPcmSessionStatus.FAILED -> "Sesi ditandai gagal."
                    record.checkpointBytes > file.length() -> "Checkpoint melebihi ukuran file."
                    record.checkpointBytes % 2L != 0L -> "Checkpoint tidak sejajar PCM16."
                    valid -> "Checkpoint dan file memenuhi pemeriksaan dasar; belum diuji untuk pemulihan audio."
                    else -> "Metadata atau format tidak valid."
                }
            )
        }
        // Include orphan PCM files that have no journal: these are not trusted as recoverable.
        val known = records.map { it.fileName }.toSet()
        val orphans = directory.listFiles { f -> f.isFile && f.name.endsWith(".pcm") && f.name !in known }
            ?.map { f -> ExperimentalPcmRecoveryAssessment(null, f, false, "File PCM tanpa journal; checkpoint tidak dapat dipercaya.") } ?: emptyList()
        return assessments + orphans
    }
}
