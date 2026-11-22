package com.example.audio

import java.io.File
import java.io.IOException

data class PcmRecoveryExecutionResult(val outputFile: File, val reportFile: File, val copiedBytes: Long, val sha256: String, val estimatedDurationMs: Long)

/** Copies only a confirmed trusted PCM prefix to a separate destination. */
class ExperimentalPcmRecoveryExecutor(private val rootDirectory: File) {
    fun execute(assessment: ExperimentalPcmRecoveryAssessment, decision: PcmRecoveryDecision, userConfirmed: Boolean): PcmRecoveryExecutionResult {
        if (!userConfirmed) throw IOException("Pemulihan membutuhkan konfirmasi pengguna.")
        if (decision.type == PcmRecoveryDecisionType.REJECTED) throw IOException("Assessment ditolak.")
        val metadata = assessment.metadata ?: throw IOException("Metadata sesi tidak tersedia.")
        val source = assessment.pcmFile ?: throw IOException("File sumber tidak tersedia.")
        val integrity = assessment.integrity ?: throw IOException("Integrity assessment tidak tersedia.")
        val root = rootDirectory.canonicalFile
        if (!source.isFile || source.canonicalFile.parentFile != root) throw IOException("File sumber tidak aman atau tidak ditemukan.")
        if (!integrity.metadataValid || !integrity.checkpointValid || !integrity.checkpointMatchesJournal ||
            integrity.trustedBytes != decision.trustedBytes || decision.trustedBytes<=0L ||
            decision.trustedBytes%2L!=0L || decision.trustedBytes>source.length() ||
            metadata.format!="pcm16_mono" || metadata.sampleRateHz<=0) throw IOException("Assessment atau batas byte tepercaya tidak valid.")
        val recoveryDir=File(root,"recovered")
        if(!recoveryDir.exists() && !recoveryDir.mkdirs()) throw IOException("Direktori recovery tidak dapat dibuat.")
        if(recoveryDir.canonicalFile.parentFile!=root) throw IOException("Direktori recovery tidak aman.")
        val output=File(recoveryDir, metadata.sessionId.replace(Regex("[^A-Za-z0-9_-]"),"_")+".recovered.pcm")
        val temp=File(recoveryDir,output.name+".tmp")
        val report=File(recoveryDir,output.name+".report.txt")
        val reportTemp=File(recoveryDir,report.name+".tmp")
        if(output.exists() || report.exists()) throw IOException("Output recovery sudah ada; tidak akan ditimpa.")
        try {
            source.inputStream().use { input -> temp.outputStream().use { out ->
                val buffer=ByteArray(8192); var remaining=decision.trustedBytes
                while(remaining>0) { val n=input.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt()); if(n<=0) throw IOException("Sumber berhenti sebelum byte tepercaya tersalin."); out.write(buffer,0,n); remaining-=n }
                out.flush()
            } }
            if(temp.length()!=decision.trustedBytes || temp.length()%2L!=0L) throw IOException("Validasi ukuran hasil recovery gagal.")
            val hash=sha256(temp)
            if(integrity.sha256!=null && integrity.sha256!=hash) throw IOException("Hash hasil recovery tidak cocok dengan prefix sumber.")
            val duration=decision.trustedBytes/2L*1000L/metadata.sampleRateHz
            reportTemp.writeText(listOf("session_id=${metadata.sessionId}","classification=${decision.type}","source=${source.name}","output=${output.name}","copied_bytes=${decision.trustedBytes}","sample_rate_hz=${metadata.sampleRateHz}","estimated_duration_ms=$duration","sha256=$hash","playback_verified=false","source_modified=false").joinToString("\n",postfix="\n"))
            if(!temp.renameTo(output)) throw IOException("File hasil tidak dapat dipindahkan ke nama final.")
            if(!reportTemp.renameTo(report)) { output.delete(); throw IOException("Laporan recovery tidak dapat disimpan.") }
            return PcmRecoveryExecutionResult(output,report,decision.trustedBytes,hash,duration)
        } catch(e:Exception) { temp.delete(); reportTemp.delete(); throw e }
    }
    private fun sha256(file:File):String { val digest=java.security.MessageDigest.getInstance("SHA-256"); file.inputStream().use { input -> val buffer=ByteArray(8192); while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)} }; return digest.digest().joinToString(""){"%02x".format(it)} }
}
