package com.example.audio

import java.io.File
import java.io.IOException

enum class PcmRecoveryPipelineStatus { COMPLETED_VALID_READABLE, COMPLETED_VALIDATION_FAILED, REJECTED, FAILED }

data class PcmRecoveryPipelineResult(
    val status: PcmRecoveryPipelineStatus,
    val decision: PcmRecoveryDecision,
    val pcmResult: PcmRecoveryExecutionResult?,
    val wavResult: PcmWavConversionResult?,
    val playbackValidation: WavPlaybackValidationResult?,
    val reportFile: File?,
    val issues: List<String>
)

/** Explicitly-confirmed experimental workflow. It preflights deterministic output collisions and writes its report atomically. */
class ExperimentalPcmRecoveryPipeline(private val sessionRoot: File) {
    fun run(assessment: ExperimentalPcmRecoveryAssessment, confirmed: Boolean): PcmRecoveryPipelineResult {
        val id = assessment.metadata?.sessionId
        if (id == null) {
            val decision = ExperimentalPcmRecoveryDecisionEngine().decide(assessment)
            return result(PcmRecoveryPipelineStatus.REJECTED, decision, null, null, null, null, listOf("Metadata tidak tersedia."))
        }
        return ExperimentalRecoverySessionLock.withLock(sessionRoot, id) { runLocked(assessment, confirmed) }
    }

    private fun runLocked(assessment: ExperimentalPcmRecoveryAssessment, confirmed: Boolean): PcmRecoveryPipelineResult {
        val decision=ExperimentalPcmRecoveryDecisionEngine().decide(assessment)
        if(decision.type==PcmRecoveryDecisionType.REJECTED) return result(PcmRecoveryPipelineStatus.REJECTED,decision,null,null,null,null,decision.reasons)
        if(!confirmed) return result(PcmRecoveryPipelineStatus.FAILED,decision,null,null,null,null,listOf("Pipeline membutuhkan konfirmasi pengguna."))
        val metadata=assessment.metadata ?: return result(PcmRecoveryPipelineStatus.REJECTED,decision,null,null,null,null,listOf("Metadata tidak tersedia."))
        val safeId=metadata.sessionId.replace(Regex("[^A-Za-z0-9_-]"),"_")
        val recoveryDir=File(sessionRoot,"recovered")
        val wavTarget=File(recoveryDir,"$safeId.recovered.wav")
        val pipelineReport=File(recoveryDir,wavTarget.name+".pipeline-report.txt")
        val pipelineReportTemp=File(recoveryDir,pipelineReport.name+".tmp")
        // Check these before executor creates the PCM artifact.
        if(wavTarget.exists() || pipelineReport.exists() || pipelineReportTemp.exists())
            return result(PcmRecoveryPipelineStatus.FAILED,decision,null,null,null,null,listOf("WAV atau laporan pipeline sudah ada; tidak akan ditimpa."))
        try {
            val pcm=ExperimentalPcmRecoveryExecutor(sessionRoot).execute(assessment,decision,true)
            val recoveryDirectory=pcm.outputFile.parentFile ?: throw IOException("Direktori recovery tidak tersedia.")
            val wav=ExperimentalPcmWavConverter(recoveryDirectory).convert(pcm.outputFile,metadata.sampleRateHz,true)
            val validation=ExperimentalWavPlaybackValidator().validate(wav.wavFile)
            val valid=validation.status==WavValidationStatus.VALID_READABLE
            val finalStatus=if(valid) PcmRecoveryPipelineStatus.COMPLETED_VALID_READABLE else PcmRecoveryPipelineStatus.COMPLETED_VALIDATION_FAILED
            pipelineReportTemp.writeText(listOf(
                "session_id=${metadata.sessionId}",
                "pipeline_status=$finalStatus",
                "source_pcm=${assessment.pcmFile?.name}",
                "recovered_pcm=${pcm.outputFile.name}",
                "wav_output=${wav.wavFile.name}",
                "pcm_bytes=${pcm.copiedBytes}",
                "wav_bytes=${wav.wavFile.length()}",
                "wav_sha256=${wav.sha256}",
                "validation_status=${validation.status}",
                "mime=${validation.mimeType}",
                "sample_rate_hz=${validation.sampleRateHz}",
                "channel_count=${validation.channelCount}",
                "duration_us=${validation.durationUs}",
                "first_sample_readable=${validation.firstSampleReadable}",
                "source_modified=false",
                "audible_playback_verified=false",
                "issues=${validation.issues.joinToString("; ")}"
            ).joinToString("\n",postfix="\n"))
            if(!pipelineReportTemp.renameTo(pipelineReport)) throw IOException("Laporan pipeline gagal difinalisasi; temp report dipertahankan untuk inspeksi.")
            return result(finalStatus,decision,pcm,wav,validation,pipelineReport,validation.issues)
        } catch(e:Exception) {
            return result(PcmRecoveryPipelineStatus.FAILED,decision,null,null,null,null,listOf(e.message ?: e.javaClass.simpleName))
        }
    }
    private fun result(status:PcmRecoveryPipelineStatus,decision:PcmRecoveryDecision,pcm:PcmRecoveryExecutionResult?,wav:PcmWavConversionResult?,validation:WavPlaybackValidationResult?,report:File?,issues:List<String>) =
        PcmRecoveryPipelineResult(status,decision,pcm,wav,validation,report,issues)
}
