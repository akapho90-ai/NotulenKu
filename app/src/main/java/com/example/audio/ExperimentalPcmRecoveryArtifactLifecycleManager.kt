package com.example.audio

import java.io.File
import java.io.IOException

enum class RecoveryArtifactKind { PCM, PCM_REPORT, WAV, PIPELINE_REPORT, TEMPORARY, UNKNOWN }
data class RecoveryArtifact(val file: File,val kind: RecoveryArtifactKind,val sizeBytes: Long,val cleanupEligible: Boolean)
data class RecoveryCleanupPlan(val files: List<RecoveryArtifact>,val totalBytes: Long,val issues: List<String>)

/** Read-only inventory plus explicit-confirmation cleanup for artifacts in the recovery folder only. */
class ExperimentalPcmRecoveryArtifactLifecycleManager(private val sessionRoot: File) {
    private val recoveryDir=File(sessionRoot,"recovered")
    fun inventory(): List<RecoveryArtifact> {
        if(!recoveryDir.exists()) return emptyList()
        val root=runCatching { recoveryDir.canonicalFile }.getOrNull() ?: return emptyList()
        if(root.parentFile!=sessionRoot.canonicalFile) return emptyList()
        return recoveryDir.listFiles()?.filter { it.isFile && it.canonicalFile.parentFile==root }?.map { file ->
            val kind=classify(file.name)
            RecoveryArtifact(file,kind,file.length(),kind!=RecoveryArtifactKind.UNKNOWN)
        }?.sortedBy { it.file.name } ?: emptyList()
    }
    fun planCleanup(names: Set<String>): RecoveryCleanupPlan {
        val inventory=inventory()
        val issues=mutableListOf<String>()
        val chosen=inventory.filter { it.file.name in names && it.cleanupEligible }
        val unmatched=names-inventory.map { it.file.name }.toSet()
        if(unmatched.isNotEmpty()) issues += "Nama tidak ditemukan atau tidak eligible: ${unmatched.joinToString()}."
        if(chosen.size!=names.size) issues += "Cleanup plan tidak lengkap; jangan menjalankan cleanup dengan nama yang tidak tervalidasi."
        return RecoveryCleanupPlan(chosen,chosen.sumOf { it.sizeBytes },issues)
    }
    @Synchronized fun cleanup(plan: RecoveryCleanupPlan,confirmed: Boolean): List<String> {
        if(!confirmed) throw IOException("Cleanup membutuhkan konfirmasi eksplisit.")
        if(plan.issues.isNotEmpty()) throw IOException("Cleanup plan tidak valid: ${plan.issues.joinToString()}")
        val root=recoveryDir.canonicalFile
        val deleted=mutableListOf<String>()
        for(artifact in plan.files) {
            val file=artifact.file.canonicalFile
            if(file.parentFile!=root || !file.isFile) throw IOException("Artifact berubah atau path tidak aman: ${artifact.file.name}")
            // Re-check recognized type, path and current size before deletion.
            if(classify(file.name)!=artifact.kind || file.length()!=artifact.sizeBytes) throw IOException("Artifact berubah sejak plan dibuat: ${file.name}")
            if(!file.delete()) throw IOException("Gagal menghapus artifact: ${file.name}")
            deleted += file.name
        }
        return deleted
    }
    private fun classify(name:String):RecoveryArtifactKind=when {
        name.endsWith(".recovered.pcm.report.txt")->RecoveryArtifactKind.PCM_REPORT
        name.endsWith(".recovered.pcm")->RecoveryArtifactKind.PCM
        name.endsWith(".recovered.wav.pipeline-report.txt")->RecoveryArtifactKind.PIPELINE_REPORT
        name.endsWith(".wav")->RecoveryArtifactKind.WAV
        name.endsWith(".tmp")->RecoveryArtifactKind.TEMPORARY
        else->RecoveryArtifactKind.UNKNOWN
    }
}
