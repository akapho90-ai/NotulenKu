package com.example.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ExperimentalPcmRecoveryArtifactLifecycleManagerTest {
    @Test fun inventoryRecognizesRecoveryArtifactsAndUnknownIsNotEligible() {
        val root=kotlin.io.path.createTempDirectory().toFile()
        try {
            val dir=File(root,"recovered");assertTrue(dir.mkdirs())
            File(dir,"s1.recovered.pcm").writeBytes(byteArrayOf(1,2))
            File(dir,"s1.recovered.pcm.report.txt").writeText("report")
            File(dir,"odd.bin").writeText("unknown")
            val inventory=ExperimentalPcmRecoveryArtifactLifecycleManager(root).inventory()
            assertEquals(3,inventory.size)
            assertTrue(inventory.any { it.kind==RecoveryArtifactKind.PCM && it.cleanupEligible })
            assertTrue(inventory.any { it.kind==RecoveryArtifactKind.UNKNOWN && !it.cleanupEligible })
        } finally { root.deleteRecursively() }
    }
    @Test fun cleanupRequiresConfirmationAndPreservesUnselectedFiles() {
        val root=kotlin.io.path.createTempDirectory().toFile()
        try {
            val dir=File(root,"recovered");dir.mkdirs()
            val pcm=File(dir,"s1.recovered.pcm");pcm.writeBytes(byteArrayOf(1,2,3))
            val wav=File(dir,"s1.recovered.wav");wav.writeBytes(byteArrayOf(4,5))
            val manager=ExperimentalPcmRecoveryArtifactLifecycleManager(root)
            val plan=manager.planCleanup(setOf(pcm.name))
            try { manager.cleanup(plan,false);fail("confirmation required") } catch(_:Exception) {}
            assertTrue(pcm.exists())
            assertTrue(wav.exists())
            assertEquals(listOf(pcm.name),manager.cleanup(plan,true))
            assertFalse(pcm.exists())
            assertTrue(wav.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun refusesCleanupIfArtifactChangedAfterPlan() {
        val root=kotlin.io.path.createTempDirectory().toFile()
        try {
            val dir=File(root,"recovered");dir.mkdirs()
            val file=File(dir,"s1.recovered.pcm");file.writeBytes(byteArrayOf(1,2))
            val manager=ExperimentalPcmRecoveryArtifactLifecycleManager(root)
            val plan=manager.planCleanup(setOf(file.name))
            file.writeBytes(byteArrayOf(1,2,3,4))
            try { manager.cleanup(plan,true);fail("changed artifact must be rejected") } catch(_:Exception) {}
            assertTrue(file.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun unknownAndUnmatchedNamesCannotBePlannedForCleanup() {
        val root=kotlin.io.path.createTempDirectory().toFile()
        try {
            val dir=File(root,"recovered");dir.mkdirs()
            val unknown=File(dir,"unknown.dat");unknown.writeText("x")
            val manager=ExperimentalPcmRecoveryArtifactLifecycleManager(root)
            val plan=manager.planCleanup(setOf(unknown.name,"missing"))
            assertTrue(plan.issues.isNotEmpty())
            try { manager.cleanup(plan,true);fail("invalid plan rejected") } catch(_:Exception) {}
            assertTrue(unknown.exists())
        } finally { root.deleteRecursively() }
    }
}
