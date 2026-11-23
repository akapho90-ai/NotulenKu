package com.example.audio

import java.io.File
import java.io.FileOutputStream
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
    private val sessionIdPattern = Regex("[A-Za-z0-9_-]{1,128}")

    @Synchronized
    fun write(metadata: ExperimentalPcmSessionMetadata) {
        if (!isValid(metadata)) throw IOException("Metadata journal PCM tidak valid.")
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Tidak dapat membuat direktori journal PCM.")

        val file = File(directory, "${metadata.fileName}.journal")
        val backup = File(directory, file.name + ".bak")
        val temp = File(directory, file.name + ".tmp")

        recoverPromotion(file)
        val previous = readRecord(file)
        if (previous == null && (readRecord(backup) != null || readRecord(temp) != null)) {
            throw IOException("Tidak dapat memulihkan journal PCM sebelum penulisan.")
        }

        val content = listOf(
            "session=${metadata.sessionId}", "file=${metadata.fileName}",
            "sample_rate=${metadata.sampleRateHz}", "format=${metadata.format}",
            "status=${metadata.status.name}", "checkpoint_bytes=${metadata.checkpointBytes}",
            "updated_at=${metadata.updatedAtEpochMs}"
        ).joinToString("\n", postfix = "\n")
        FileOutputStream(temp).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (readRecord(temp) != metadata) throw IOException("Verifikasi journal sementara gagal.")

        if (previous != null) {
            if (backup.exists() && (!backup.isFile || !backup.delete())) {
                throw IOException("Tidak dapat membersihkan backup journal.")
            }
            if (!file.renameTo(backup)) throw IOException("Tidak dapat menyimpan backup journal.")
        } else if (file.exists() && !archiveInvalidPcmRecord(file)) {
            throw IOException("Tidak dapat mengarsipkan journal PCM yang tidak valid.")
        }

        if (!temp.renameTo(file)) {
            if (previous != null && !file.exists()) backup.renameTo(file)
            throw IOException("Tidak dapat mempromosikan journal PCM.")
        }
        if (readRecord(file) != metadata) {
            if (previous != null) recoverPromotion(file)
            throw IOException("Verifikasi journal hasil penggantian gagal.")
        }
        if (backup.isFile) backup.delete()
    }

    fun read(file: File): ExperimentalPcmSessionMetadata? = runCatching { readRecord(file) }.getOrNull()

    // A malformed record can be replaced. An I/O error must leave the files alone.
    private fun readRecord(file: File): ExperimentalPcmSessionMetadata? {
        if (!file.isFile) return null
        val values = mutableMapOf<String, String>()
        file.useLines(Charsets.UTF_8) { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val separator = line.indexOf('=')
                if (separator <= 0) return null
                val key = line.substring(0, separator)
                if (values.put(key, line.substring(separator + 1)) != null) return null
            }
        }
        val metadata = ExperimentalPcmSessionMetadata(
            sessionId = values["session"] ?: return null,
            fileName = values["file"] ?: return null,
            sampleRateHz = values["sample_rate"]?.toIntOrNull() ?: return null,
            format = values["format"] ?: return null,
            status = ExperimentalPcmSessionStatus.entries.firstOrNull { it.name == values["status"] }
                ?: return null,
            checkpointBytes = values["checkpoint_bytes"]?.toLongOrNull() ?: return null,
            updatedAtEpochMs = values["updated_at"]?.toLongOrNull() ?: return null
        )
        val primaryName = "${metadata.fileName}.journal"
        val matchesJournal = file.name == primaryName ||
            file.name == "$primaryName.bak" || file.name == "$primaryName.tmp"
        return metadata.takeIf { matchesJournal && isValid(it) }
    }

    private fun isValid(metadata: ExperimentalPcmSessionMetadata): Boolean =
        sessionIdPattern.matches(metadata.sessionId) &&
            metadata.fileName == "${metadata.sessionId}.pcm" &&
            metadata.sampleRateHz > 0 && metadata.format == "pcm16_mono" &&
            metadata.checkpointBytes >= 0 && metadata.checkpointBytes % 2L == 0L &&
            metadata.updatedAtEpochMs >= 0

    /**
     * A valid primary wins. Otherwise prefer the last committed version in the
     * backup over the prepared temp file. Both moves stay in the same directory.
     */
    private fun recoverPromotion(target: File) {
        if (readRecord(target) != null) return
        val backup = File(directory, target.name + ".bak")
        val temp = File(directory, target.name + ".tmp")
        val backupMetadata = readRecord(backup)
        val candidate = if (backupMetadata != null) backup else temp
        val metadata = backupMetadata ?: readRecord(temp) ?: return

        if (target.exists() && !archiveInvalidPcmRecord(target)) return
        // If promotion fails or the process stops after archiving, the candidate
        // is still available for the next recovery attempt.
        if (!candidate.renameTo(target) || readRecord(target) != metadata) return

        if (backup.isFile) backup.delete()
        if (temp.isFile) temp.delete()
    }

    internal fun recoverForSession(sessionId: String): ExperimentalPcmSessionMetadata? {
        require(sessionIdPattern.matches(sessionId))
        val target = File(directory, "$sessionId.pcm.journal")
        recoverPromotion(target)
        val record = readRecord(target)
        if (record != null) return record
        val sidecars = listOf(target, File(directory, target.name + ".bak"), File(directory, target.name + ".tmp"))
        if (sidecars.any(File::exists)) {
            throw IOException("Journal PCM tidak dapat digunakan untuk memverifikasi checkpoint.")
        }
        return null
    }

    fun list(): List<ExperimentalPcmSessionMetadata> {
        val targets = directory.listFiles()?.filter { f ->
            f.isFile && (f.name.endsWith(".pcm.journal") ||
                f.name.endsWith(".pcm.journal.bak") || f.name.endsWith(".pcm.journal.tmp"))
        }?.map { f ->
            when {
                f.name.endsWith(".pcm.journal.bak") -> File(directory, f.name.removeSuffix(".bak"))
                f.name.endsWith(".pcm.journal.tmp") -> File(directory, f.name.removeSuffix(".tmp"))
                else -> f
            }
        }?.distinctBy { it.name } ?: emptyList()
        for (target in targets) {
            try {
                recoverPromotion(target)
            } catch (_: IOException) {
                // Leave unreadable records for a later attempt and continue listing other sessions.
            }
        }
        return directory.listFiles { f -> f.isFile && f.name.endsWith(".pcm.journal") }
            ?.mapNotNull(::read)?.sortedByDescending { it.updatedAtEpochMs } ?: emptyList()
    }
}

internal fun archiveInvalidPcmRecord(target: File): Boolean {
    if (!target.isFile) return false
    val archiveName = target.name + ".invalid"
    var archive = File(target.parentFile, archiveName)
    var suffix = 1
    while (archive.exists()) {
        archive = File(target.parentFile, "$archiveName.$suffix")
        suffix++
    }
    return target.renameTo(archive)
}

data class ExperimentalPcmRecoveryAssessment(
    val metadata: ExperimentalPcmSessionMetadata?, val pcmFile: File?,
    val recoverable: Boolean, val reason: String, val integrity: ExperimentalPcmIntegrityResult? = null
)

data class ExperimentalPcmIntegrityResult(
    val metadataValid: Boolean, val checkpointValid: Boolean, val fileSizeAligned: Boolean,
    val checkpointMatchesJournal: Boolean, val trustedBytes: Long?, val sha256: String?,
    val issues: List<String>
)

/** Read-only checks of metadata, checkpoint consistency, alignment and trusted-byte SHA-256. */
class ExperimentalPcmIntegrityVerifier(private val directory: File) {
    fun verify(metadata: ExperimentalPcmSessionMetadata, pcmFile: File): ExperimentalPcmIntegrityResult {
        val issues=mutableListOf<String>()
        val expected=File(directory,metadata.fileName).canonicalFile
        val base=directory.canonicalFile
        val safePath=expected.parentFile==base
        if (!safePath) issues += "Path file berada di luar direktori sesi."
        val metadataValid=metadata.sessionId.isNotBlank() && metadata.sampleRateHz>0 &&
            metadata.format=="pcm16_mono" && metadata.checkpointBytes>=0 &&
            metadata.checkpointBytes%2L==0L && safePath && expected==pcmFile.canonicalFile
        if (!metadataValid) issues += "Metadata sesi tidak valid."
        val fileExists=pcmFile.isFile
        if (!fileExists) issues += "File PCM tidak ditemukan."
        val aligned=fileExists && pcmFile.length()%2L==0L
        if (fileExists && !aligned) issues += "Ukuran file tidak sejajar PCM16."
        val checkpointFile = File(directory, pcmFile.name + ".checkpoint")
        val checkpointBytes = if (fileExists) {
            try {
                readPcmCheckpointBytes(
                    checkpointFile, metadata.sessionId, pcmFile.name, metadata.sampleRateHz, pcmFile.length()
                )
            } catch (_: IOException) {
                null
            }
        } else null
        val checkpointValid = checkpointBytes != null
        if (!checkpointValid) issues += "Checkpoint sidecar hilang atau tidak valid."
        val matches=checkpointValid && checkpointBytes==metadata.checkpointBytes
        if(!matches) issues += "Ukuran checkpoint tidak cocok dengan journal."
        val trusted=if(matches && metadata.checkpointBytes<=pcmFile.length()) metadata.checkpointBytes else null
        val hash=trusted?.let { length ->
            runCatching {
                val digest=java.security.MessageDigest.getInstance("SHA-256")
                pcmFile.inputStream().use { input ->
                    val buffer=ByteArray(8192); var remaining=length
                    while(remaining>0) {
                        val read=input.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt())
                        if(read<=0) throw java.io.IOException("Data PCM lebih pendek dari trusted byte count.")
                        digest.update(buffer,0,read); remaining-=read
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }
        if(trusted!=null && hash==null) issues += "Hash SHA-256 tidak berhasil dihitung."
        return ExperimentalPcmIntegrityResult(metadataValid,checkpointValid,aligned,matches,trusted,hash,issues)
    }
}

/** Read-only scanner. No auto-resume, truncation, conversion, or deletion. */
class ExperimentalPcmRecoveryManager(private val directory: File) {
    private val journal=ExperimentalPcmSessionJournal(directory)
    private val verifier=ExperimentalPcmIntegrityVerifier(directory)

    fun assessAll(): List<ExperimentalPcmRecoveryAssessment> {
        val records=journal.list()
        val assessments=records.map { record ->
            val file=File(directory,record.fileName)
            val integrity=verifier.verify(record,file)
            val valid=integrity.metadataValid && integrity.checkpointValid && integrity.fileSizeAligned &&
                integrity.checkpointMatchesJournal && integrity.trustedBytes!=null &&
                record.status!=ExperimentalPcmSessionStatus.FINALIZED && record.status!=ExperimentalPcmSessionStatus.FAILED
            ExperimentalPcmRecoveryAssessment(record,file,valid,when {
                record.status==ExperimentalPcmSessionStatus.FINALIZED -> "Sesi sudah final."
                record.status==ExperimentalPcmSessionStatus.FAILED -> "Sesi ditandai gagal."
                valid -> "Pemeriksaan integritas dasar lulus; hash SHA-256 tersedia, belum diuji playback."
                else -> integrity.issues.joinToString(" ")
            },integrity)
        }
        val known=records.map { it.fileName }.toSet()
        val orphans=directory.listFiles { f -> f.isFile && f.name.endsWith(".pcm") && f.name !in known }
            ?.map { f -> ExperimentalPcmRecoveryAssessment(null,f,false,"File PCM tanpa journal; metadata dan checkpoint tidak dapat dipercaya.") } ?: emptyList()
        return assessments+orphans
    }
}
