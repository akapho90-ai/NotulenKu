package com.example.audio

import java.io.File
import java.io.IOException

enum class RecoveryArtifactKind { PCM, PCM_REPORT, WAV, PIPELINE_REPORT, TEMPORARY, UNKNOWN }
data class RecoveryArtifact(val file: File, val kind: RecoveryArtifactKind, val sizeBytes: Long, val cleanupEligible: Boolean)
data class RecoveryCleanupPlan(val files: List<RecoveryArtifact>, val totalBytes: Long, val issues: List<String>)

/** Read-only inventory and explicit cleanup scoped strictly to the recovery directory. */
class ExperimentalPcmRecoveryArtifactLifecycleManager(private val sessionRoot: File) {
    private val recoveryDir = File(sessionRoot, "recovered")

    fun inventory(): List<RecoveryArtifact> {
        if (!recoveryDir.exists()) return emptyList()
        val base = runCatching { sessionRoot.canonicalFile }.getOrNull() ?: return emptyList()
        val root = runCatching { recoveryDir.canonicalFile }.getOrNull() ?: return emptyList()
        if (root.parentFile != base || !root.isDirectory) return emptyList()
        return recoveryDir.listFiles()?.filter { candidate ->
            runCatching { candidate.isFile && candidate.canonicalFile.parentFile == root }.getOrDefault(false)
        }?.map { file ->
            val kind = classify(file.name)
            RecoveryArtifact(file, kind, file.length(), kind != RecoveryArtifactKind.UNKNOWN)
        }?.sortedBy { it.file.name } ?: emptyList()
    }

    fun planCleanup(names: Set<String>): RecoveryCleanupPlan {
        val snapshot = inventory()
        val issues = mutableListOf<String>()
        if (names.isEmpty()) issues += "Cleanup plan kosong."
        if (names.any { it.isBlank() || it.contains('/') || it.contains('\\') || it=="." || it==".." }) issues += "Nama artifact tidak valid."
        if (names.size != names.toSet().size) issues += "Nama artifact duplikat."
        val selected = snapshot.filter { it.file.name in names && it.cleanupEligible }
        val unmatched = names - snapshot.map { it.file.name }.toSet()
        if (unmatched.isNotEmpty()) issues += "Nama tidak ditemukan: ${unmatched.joinToString()}."
        if (selected.size != names.size) issues += "Cleanup plan tidak lengkap atau mengandung artifact non-eligible."
        return RecoveryCleanupPlan(selected, selected.sumOf { it.sizeBytes }, issues)
    }

    @Synchronized fun cleanup(plan: RecoveryCleanupPlan, confirmed: Boolean): List<String> =
        ExperimentalRecoverySessionLock.withLock(sessionRoot, "__artifact_cleanup__") {
            cleanupLocked(plan, confirmed)
        }

    private fun cleanupLocked(plan: RecoveryCleanupPlan, confirmed: Boolean): List<String> {
        if (!confirmed) throw IOException("Cleanup membutuhkan konfirmasi eksplisit.")
        if (plan.issues.isNotEmpty()) throw IOException("Cleanup plan tidak valid: ${plan.issues.joinToString()}")
        val root = recoveryDir.canonicalFile
        if (root.parentFile != sessionRoot.canonicalFile || !root.isDirectory) throw IOException("Direktori recovery tidak aman.")
        if (plan.files.map { it.file.name }.toSet().size != plan.files.size) throw IOException("Plan berisi nama duplikat.")
        if (plan.totalBytes != plan.files.sumOf { it.sizeBytes }) throw IOException("Total ukuran plan tidak konsisten.")

        // Validate the complete plan before deleting any artifact to reduce partial-cleanup risk.
        val validated = plan.files.map { artifact ->
            val file = File(root, artifact.file.name).canonicalFile
            if (file.parentFile != root || !file.isFile || file.name != artifact.file.name) throw IOException("Path artifact tidak aman.")
            val kind = classify(file.name)
            if (kind != artifact.kind || kind == RecoveryArtifactKind.UNKNOWN || file.length() != artifact.sizeBytes) throw IOException("Artifact berubah sejak plan dibuat: ${file.name}")
            file
        }
        val deleted = mutableListOf<String>()
        for (file in validated) {
            if (!file.delete()) throw IOException("Gagal menghapus artifact: ${file.name}; partial cleanup mungkin terjadi.")
            deleted += file.name
        }
        return deleted
    }

    private fun classify(name: String): RecoveryArtifactKind = when {
        name.endsWith(".recovered.pcm.report.txt") -> RecoveryArtifactKind.PCM_REPORT
        name.endsWith(".recovered.wav.pipeline-report.txt") -> RecoveryArtifactKind.PIPELINE_REPORT
        name.endsWith(".recovered.pcm") -> RecoveryArtifactKind.PCM
        name.endsWith(".wav") -> RecoveryArtifactKind.WAV
        name.endsWith(".tmp") -> RecoveryArtifactKind.TEMPORARY
        else -> RecoveryArtifactKind.UNKNOWN
    }
}
