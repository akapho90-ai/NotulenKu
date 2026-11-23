package com.example.audio

enum class PcmRecoveryDecisionType { RECOVERABLE, PARTIALLY_SALVAGEABLE, REJECTED }

data class PcmRecoveryDecision(
    val type: PcmRecoveryDecisionType,
    val trustedBytes: Long,
    val estimatedDurationMs: Long,
    val reasons: List<String>,
    val requiresUserConfirmation: Boolean = true
)

/**
 * Conservative policy engine. It only classifies; it never alters or resumes audio files.
 */
class ExperimentalPcmRecoveryDecisionEngine {
    fun decide(assessment: ExperimentalPcmRecoveryAssessment): PcmRecoveryDecision {
        val metadata = assessment.metadata
        val integrity = assessment.integrity
        val file = assessment.pcmFile
        val rejected = { reasons: List<String> ->
            PcmRecoveryDecision(PcmRecoveryDecisionType.REJECTED, 0L, 0L, reasons)
        }

        if (metadata == null || file == null) return rejected(listOf("Data sesi tidak tersedia; file orphan tidak dapat dipulihkan secara aman."))
        if (metadata.status == ExperimentalPcmSessionStatus.FINALIZED) return rejected(listOf("Sesi telah difinalisasi."))
        if (metadata.status == ExperimentalPcmSessionStatus.FAILED) return rejected(listOf("Sesi ditandai gagal."))
        if (integrity == null) return rejected(listOf("Hasil pemeriksaan integritas tidak tersedia."))
        if (!integrity.metadataValid) return rejected(integrity.issues.ifEmpty { listOf("Metadata tidak valid.") })
        if (!integrity.checkpointValid || !integrity.checkpointMatchesJournal) return rejected(integrity.issues.ifEmpty { listOf("Checkpoint tidak konsisten.") })
        val trusted = integrity.trustedBytes ?: return rejected(integrity.issues.ifEmpty { listOf("Tidak ada byte PCM yang dapat dipercaya.") })
        if (trusted <= 0L) return rejected(listOf("Checkpoint tidak memiliki data audio yang dapat dipulihkan."))
        val durationMs = trusted / 2L * 1000L / metadata.sampleRateHz.toLong()
        if (durationMs <= 0L) return rejected(listOf("Durasi audio tepercaya terlalu pendek."))
        val issues = integrity.issues
        val clean = assessment.recoverable && issues.isEmpty() && file.length() == trusted
        return if (clean) {
            PcmRecoveryDecision(PcmRecoveryDecisionType.RECOVERABLE, trusted, durationMs,
                listOf("Checkpoint dan journal cocok; byte tepercaya tersedia.", "Playback belum diverifikasi."))
        } else {
            PcmRecoveryDecision(PcmRecoveryDecisionType.PARTIALLY_SALVAGEABLE, trusted, durationMs,
                (issues + listOf("Hanya prefix audio pada checkpoint yang boleh dipertimbangkan.", "File asli harus dipertahankan; jangan overwrite otomatis.")).distinct())
        }
    }
}
