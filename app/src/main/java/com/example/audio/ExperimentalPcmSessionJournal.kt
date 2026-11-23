package com.example.audio

import java.io.File
import java.io.IOException
import java.io.FileOutputStream

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
    private fun safe(id: String): String {
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) throw IOException("ID sesi journal tidak valid.")
        return id
    }
    private fun journalFile(id: String) = File(directory, "${safe(id)}.pcm.journal")

    @Synchronized
    fun write(metadata: ExperimentalPcmSessionMetadata) {
        safe(metadata.sessionId)
        if (metadata.fileName != "${metadata.sessionId}.pcm" || metadata.fileName.contains("/") || metadata.fileName.contains("\\\\") || metadata.sampleRateHz <= 0 || metadata.format != "pcm16_mono" || metadata.checkpointBytes < 0 || metadata.checkpointBytes % 2L != 0L) {
            throw IOException("Metadata journal PCM tidak valid.")
        }
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Tidak dapat membuat direktori journal PCM.")
        val file = journalFile(metadata.sessionId)
        val temp = File(directory, file.name + ".tmp")
        val content = listOf(
            "session=${metadata.sessionId}", "file=${metadata.fileName}",
            "sample_rate=${metadata.sampleRateHz}", "format=${metadata.format}",
            "status=${metadata.status.name}", "checkpoint_bytes=${metadata.checkpointBytes}",
            "updated_at=${metadata.updatedAtEpochMs}"
        ).joinToString("\n", postfix="\n")
        FileOutputStream(temp).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        val backup = File(directory, file.name + ".bak")
        if (backup.exists() && !backup.delete()) { temp.delete(); throw IOException("Tidak dapat membersihkan backup journal.") }
        if (file.exists() && !file.renameTo(backup)) { temp.delete(); throw IOException("Tidak dapat menyimpan backup journal.") }
        if (!temp.renameTo(file)) {
            if (backup.exists()) backup.renameTo(file)
            throw IOException("Tidak dapat mempromosikan journal PCM.")
        }
        if (file.readText(Charsets.UTF_8) != content) {
            file.delete()
            if (backup.exists()) backup.renameTo(file)
            throw IOException("Verifikasi journal hasil penggantian gagal.")
        }
        if (backup.exists() && !backup.delete()) backup.deleteOnExit()
    }

    fun read(file: File): ExperimentalPcmSessionMetadata? = runCatching {
        val values = file.readLines().mapNotNull { line ->
            val i=line.indexOf('=')
            if (i<=0) null else line.substring(0,i) to line.substring(i+1)
        }.toMap()
        val id=values["session"] ?: return null
        val filename=values["file"] ?: return null
        val rate=values["sample_rate"]?.toIntOrNull() ?: return null
        val format=values["format"] ?: return null
        val status=ExperimentalPcmSessionStatus.valueOf(values["status"] ?: return null)
        val bytes=values["checkpoint_bytes"]?.toLongOrNull() ?: return null
        val updated=values["updated_at"]?.toLongOrNull() ?: return null
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,128}")) || filename != "$id.pcm" ||
            rate<=0 || format!="pcm16_mono" || bytes<0 || bytes%2L!=0L || updated<0) return null
        ExperimentalPcmSessionMetadata(id,filename,rate,format,status,bytes,updated)
    }.getOrNull()

    /**
     * Recover interrupted journal promotion conservatively. A valid primary wins.
     * If primary is absent/invalid, only fully parseable sidecars are candidates;
     * backup is preferred over temp because temp may represent an uncommitted write.
     */
    private fun recoverPromotion(target: File) {
        if (read(target) != null) return
        val backup = File(directory, target.name + ".bak")
        val temp = File(directory, target.name + ".tmp")
        val candidate = listOf(backup, temp).firstOrNull { it.isFile && read(it) != null } ?: return
        if (target.exists()) {
            val invalid = File(directory, target.name + ".invalid")
            if (invalid.exists() || !target.renameTo(invalid)) return
        }
        if (!candidate.renameTo(target)) {
            val invalid = File(directory, target.name + ".invalid")
            if (invalid.exists()) runCatching { invalid.delete() }
            if (invalid.exists()) return
            if (target.exists()) runCatching { target.delete() }
            candidate.renameTo(target)
        }
        if (read(target) != null) {
            if (backup.exists() && backup != target) runCatching { backup.delete() }
            if (temp.exists() && temp != target) runCatching { temp.delete() }
        }
    }

    fun list(): List<ExperimentalPcmSessionMetadata> {
        directory.listFiles { f -> f.isFile && f.name.endsWith(".pcm.journal") }
            ?.forEach(::recoverPromotion)
        return directory.listFiles { f -> f.isFile && f.name.endsWith(".pcm.journal") }
            ?.mapNotNull(::read)?.sortedByDescending { it.updatedAtEpochMs } ?: emptyList()
    }
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
        val checkpointFile=File(directory,pcmFile.name+".checkpoint")
        val checkpointValues=if(checkpointFile.isFile) checkpointFile.readLines().mapNotNull { line ->
            val i=line.indexOf('=')
            if(i<=0) null else line.substring(0,i) to line.substring(i+1)
        }.toMap() else emptyMap()
        val checkpointValid=checkpointFile.isFile &&
            checkpointValues["session"]==metadata.sessionId &&
            checkpointValues["format"]==metadata.format &&
            checkpointValues["sample_rate"]==metadata.sampleRateHz.toString() &&
            checkpointValues["bytes"]?.toLongOrNull()?.let { it>=0 && it<=pcmFile.length() && it%2L==0L }==true
        if(!checkpointValid) issues += "Checkpoint sidecar hilang atau tidak valid."
        val checkpointBytes=checkpointValues["bytes"]?.toLongOrNull()
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
