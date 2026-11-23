package com.example.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExperimentalPcmRecoveryDecisionEngineTest {
    private val engine = ExperimentalPcmRecoveryDecisionEngine()

    @Test fun orphanIsRejected() {
        val assessment = ExperimentalPcmRecoveryAssessment(null, File("orphan.pcm"), false, "orphan")
        val decision = engine.decide(assessment)
        assertEquals(PcmRecoveryDecisionType.REJECTED, decision.type)
        assertEquals(0L, decision.trustedBytes)
    }

    @Test fun missingIntegrityIsRejected() {
        val metadata = ExperimentalPcmSessionMetadata("s1", "s1.pcm", 16000, status=ExperimentalPcmSessionStatus.RECOVERABLE, checkpointBytes=32000, updatedAtEpochMs=1)
        val assessment = ExperimentalPcmRecoveryAssessment(metadata, File("s1.pcm"), false, "missing integrity")
        assertEquals(PcmRecoveryDecisionType.REJECTED, engine.decide(assessment).type)
    }

    @Test fun inconsistentCheckpointIsRejected() {
        val metadata = ExperimentalPcmSessionMetadata("s1", "s1.pcm", 16000, status=ExperimentalPcmSessionStatus.RECOVERABLE, checkpointBytes=32000, updatedAtEpochMs=1)
        val integrity = ExperimentalPcmIntegrityResult(true,true,true,false,null,null,listOf("Mismatch"))
        val assessment = ExperimentalPcmRecoveryAssessment(metadata, File("s1.pcm"), false, "Mismatch", integrity)
        assertEquals(PcmRecoveryDecisionType.REJECTED, engine.decide(assessment).type)
    }

    @Test fun trustedPrefixCanBePartiallySalvaged() {
        val metadata = ExperimentalPcmSessionMetadata("s1", "s1.pcm", 16000, status=ExperimentalPcmSessionStatus.RECOVERABLE, checkpointBytes=32000, updatedAtEpochMs=1)
        val file = File("s1.pcm")
        val integrity = ExperimentalPcmIntegrityResult(true,true,true,true,32000,"abc",listOf("File has uncheckpointed tail"))
        val assessment = ExperimentalPcmRecoveryAssessment(metadata,file,false,"partial",integrity)
        val decision = engine.decide(assessment)
        assertEquals(PcmRecoveryDecisionType.PARTIALLY_SALVAGEABLE, decision.type)
        assertEquals(1000L, decision.estimatedDurationMs)
        assertTrue(decision.requiresUserConfirmation)
    }

    @Test fun validAssessmentIsRecoverableButPlaybackIsNotClaimed() {
        val dir = kotlin.io.path.createTempDirectory("decision-engine-test").toFile()
        try {
            val pcm = File(dir, "s1.pcm")
            pcm.writeBytes(ByteArray(32000))
            val metadata = ExperimentalPcmSessionMetadata("s1", pcm.name, 16000, status=ExperimentalPcmSessionStatus.RECOVERABLE, checkpointBytes=32000, updatedAtEpochMs=1)
            val integrity = ExperimentalPcmIntegrityResult(true,true,true,true,32000,"abc",emptyList())
            val assessment = ExperimentalPcmRecoveryAssessment(metadata,pcm,true,"ok",integrity)
            val decision = engine.decide(assessment)
            assertEquals(PcmRecoveryDecisionType.RECOVERABLE,decision.type)
            assertTrue(decision.reasons.any { it.contains("Playback belum") })
        } finally { dir.deleteRecursively() }
    }
}
