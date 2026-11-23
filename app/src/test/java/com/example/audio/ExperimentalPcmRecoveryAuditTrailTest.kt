package com.example.audio

import org.junit.Assert.*
import org.junit.Test

class ExperimentalPcmRecoveryAuditTrailTest {
    @Test fun roundTripEventsAndFilterSession() {
        val root=kotlin.io.path.createTempDirectory().toFile()
        try {
            var time=10L
            val trail=ExperimentalPcmRecoveryAuditTrail(root){time++}
            trail.record("session-A",RecoveryAuditEventType.STARTED,detail="accepted|yes")
            trail.record("session-A",RecoveryAuditEventType.COMPLETED,artifacts="a.wav",detail="done")
            trail.record("session-B",RecoveryAuditEventType.FAILED,detail="failure")
            val all=trail.readAll()
            assertEquals(3,all.size)
            assertEquals("accepted|yes",all[0].detail)
            assertEquals("session-A",trail.eventsFor("session-A").first().sessionId)
            assertEquals(11L,all[1].timestampEpochMs)
        } finally { root.deleteRecursively() }
    }
    @Test fun missingLogReturnsEmpty() {
        val root=kotlin.io.path.createTempDirectory().toFile()
        try { assertTrue(ExperimentalPcmRecoveryAuditTrail(root).readAll().isEmpty()) }
        finally { root.deleteRecursively() }
    }
}
