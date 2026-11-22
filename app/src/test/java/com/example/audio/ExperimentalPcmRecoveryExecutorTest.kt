package com.example.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ExperimentalPcmRecoveryExecutorTest {
    @Test fun confirmationRequired() {
        val root=createTempDir()
        try {
            val metadata=ExperimentalPcmSessionMetadata("s1","s1.pcm",16000,status=ExperimentalPcmSessionStatus.RECOVERABLE,checkpointBytes=4,updatedAtEpochMs=1)
            val assessment=ExperimentalPcmRecoveryAssessment(metadata,File(root,"s1.pcm"),true,"",ExperimentalPcmIntegrityResult(true,true,true,true,4,null,emptyList()))
            val decision=PcmRecoveryDecision(PcmRecoveryDecisionType.RECOVERABLE,4,0,emptyList())
            try { ExperimentalPcmRecoveryExecutor(root).execute(assessment,decision,false); fail("Expected confirmation failure") } catch(_:Exception) {}
        } finally { root.deleteRecursively() }
    }
    @Test fun copiesTrustedPrefixAndLeavesSourceUntouched() {
        val root=createTempDir()
        try {
            val source=File(root,"s1.pcm"); source.writeBytes(byteArrayOf(1,2,3,4,5,6))
            val metadata=ExperimentalPcmSessionMetadata("s1","s1.pcm",16000,status=ExperimentalPcmSessionStatus.RECOVERABLE,checkpointBytes=4,updatedAtEpochMs=1)
            val assessment=ExperimentalPcmRecoveryAssessment(metadata,source,true,"",ExperimentalPcmIntegrityResult(true,true,true,true,4,null,emptyList()))
            val decision=PcmRecoveryDecision(PcmRecoveryDecisionType.RECOVERABLE,4,1,emptyList())
            val result=ExperimentalPcmRecoveryExecutor(root).execute(assessment,decision,true)
            assertEquals(4L,result.copiedBytes); assertArrayEquals(byteArrayOf(1,2,3,4),result.outputFile.readBytes())
            assertArrayEquals(byteArrayOf(1,2,3,4,5,6),source.readBytes()); assertTrue(result.reportFile.exists())
            assertTrue(result.reportFile.readText().contains("playback_verified=false"))
        } finally { root.deleteRecursively() }
    }
    @Test fun refusesExistingOutput() {
        val root=createTempDir()
        try {
            val source=File(root,"s1.pcm"); source.writeBytes(byteArrayOf(1,2,3,4))
            val recovery=File(root,"recovered"); recovery.mkdirs(); File(recovery,"s1.recovered.pcm").writeBytes(byteArrayOf(9,9))
            val metadata=ExperimentalPcmSessionMetadata("s1","s1.pcm",16000,status=ExperimentalPcmSessionStatus.RECOVERABLE,checkpointBytes=4,updatedAtEpochMs=1)
            val assessment=ExperimentalPcmRecoveryAssessment(metadata,source,true,"",ExperimentalPcmIntegrityResult(true,true,true,true,4,null,emptyList()))
            val decision=PcmRecoveryDecision(PcmRecoveryDecisionType.RECOVERABLE,4,1,emptyList())
            try { ExperimentalPcmRecoveryExecutor(root).execute(assessment,decision,true); fail("Expected collision") } catch(_:Exception) {}
            assertArrayEquals(byteArrayOf(9,9),File(recovery,"s1.recovered.pcm").readBytes())
        } finally { root.deleteRecursively() }
    }
}
