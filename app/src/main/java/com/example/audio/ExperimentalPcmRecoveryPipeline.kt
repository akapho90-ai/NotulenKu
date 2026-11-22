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

/** Explicitly-confirmed, experimental end-to-end recovery workflow; source session files are not modified. */
class ExperimentalPcmRecoveryPipeline(private val sessionRoot: File) {
    fun run(assessment: ExperimentalPcmRecoveryAssessment, confirmed: Boolean): PcmRecoveryPipelineResult {
        val decision=ExperimentalPcmRecoveryDecisionEngine().decide(assessment)
        if(decision.type==PcmRecoveryDecisionType.REJECTED) return result(PcmRecoveryPipelineStatus.REJECTED,decision,null,null,null,null,decision.reasons)
        if(!confirmed) return result(PcmRecoveryPipelineStatus.FAILED,decision,null,null,null,null,listOf("Pipeline membutuhkan konfirmasi pengguna."))
        try {
            val pcm=ExperimentalPcmRecoveryExecutor(sessionRoot).execute(assessment,decision,true)
            val recoveryDir=pcm.outputFile.parentFile ?: throw IOException("Direktori PCM recovery tidak tersedia.")
            val metadata=assessment.metadata ?: throw IOException("Metadata sesi tidak tersedia.")
            val wav=ExperimentalPcmWavConverter(recoveryDir).convert(pcm.outputFile,metadata.sampleRateHz,true)
            val validation=ExperimentalWavPlaybackValidator().validate(wav.wavFile)
            val valid=validation.status==WavValidationStatus.VALID_READABLE
            val report=File(recoveryDir,wav.wavFile.name+".pipeline-report.txt")
            if(report.exists()) throw IOException("Laporan pipeline sudah ada; tidak akan ditimpa.")
            report.writeText(listOf(
                "session_id=${metadata.sessionId}",
                "pipeline_status=${if(valid) PcmRecoveryPipelineStatus.COMPLETED_VALID_READABLE else PcmRecoveryPipelineStatus.COMPLETED_VALIDATION_FAILED}",
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
            return result(if(valid) PcmRecoveryPipelineStatus.COMPLETED_VALID_READABLE else PcmRecoveryPipelineStatus.COMPLETED_VALIDATION_FAILED,decision,pcm,wav,validation,report,validation.issues)
        } catch(e:Exception) {
            return result(PcmRecoveryPipelineStatus.FAILED,decision,null,null,null,null,listOf(e.message ?: e.javaClass.simpleName))
        }
    }
    private fun result(status:PcmRecoveryPipelineStatus,decision:PcmRecoveryDecision,pcm:PcmRecoveryExecutionResult?,wav:PcmWavConversionResult?,validation:WavPlaybackValidationResult?,report:File?,issues:List<String>) =
        PcmRecoveryPipelineResult(status,decision,pcm,wav,validation,report,issues)
}
