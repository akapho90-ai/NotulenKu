package com.example.audio

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ExperimentalPcmRecoveryPipelineTest {
    @Test fun refusesWithoutExplicitConfirmation() {
        val root=kotlin.io.path.createTempDirectory().toFile()
        try {
            val source=File(root,"s1.pcm");source.writeBytes(ByteArray(32000))
            val metadata=ExperimentalPcmSessionMetadata("s1","s1.pcm",16000,status=ExperimentalPcmSessionStatus.RECOVERABLE,checkpointBytes=32000,updatedAtEpochMs=1)
            val assessment=ExperimentalPcmRecoveryAssessment(metadata,source,true,"",ExperimentalPcmIntegrityResult(true,true,true,true,32000,null,emptyList()))
            val result=ExperimentalPcmRecoveryPipeline(root).run(assessment,false)
            assertEquals(PcmRecoveryPipelineStatus.FAILED,result.status)
            assertNull(result.pcmResult)
            assertTrue(source.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun rejectedAssessmentDoesNotCreateRecoveryFiles() {
        val root=kotlin.io.path.createTempDirectory().toFile()
        try {
            val assessment=ExperimentalPcmRecoveryAssessment(null,File(root,"orphan.pcm"),false,"orphan")
            val result=ExperimentalPcmRecoveryPipeline(root).run(assessment,true)
            assertEquals(PcmRecoveryPipelineStatus.REJECTED,result.status)
            assertNull(result.wavResult)
            assertFalse(File(root,"recovered").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun preflightReportCollisionDoesNotCopyRecoveryPcm() {
        val root=kotlin.io.path.createTempDirectory().toFile()
        try {
            val source=File(root,"s1.pcm");source.writeBytes(ByteArray(32000))
            val metadata=ExperimentalPcmSessionMetadata("s1","s1.pcm",16000,status=ExperimentalPcmSessionStatus.RECOVERABLE,checkpointBytes=32000,updatedAtEpochMs=1)
            val assessment=ExperimentalPcmRecoveryAssessment(metadata,source,true,"",ExperimentalPcmIntegrityResult(true,true,true,true,32000,null,emptyList()))
            val recovery=File(root,"recovered");assertTrue(recovery.mkdirs())
            val report=File(recovery,"s1.recovered.wav.pipeline-report.txt");report.writeText("preserve")
            val result=ExperimentalPcmRecoveryPipeline(root).run(assessment,true)
            assertEquals(PcmRecoveryPipelineStatus.FAILED,result.status)
            assertTrue(report.exists())
            assertEquals("preserve",report.readText())
            assertFalse(File(recovery,"s1.recovered.pcm").exists())
        } finally { root.deleteRecursively() }
    }
}
