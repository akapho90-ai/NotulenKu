package com.example

import com.example.core.usesDebugAppCheck
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceAppCheckTest {
    @Test fun normalLaunchOfDebugApkUsesAttestation() {
        assertFalse(usesDebugAppCheck(debugBuild = true, debugToken = null))
        assertFalse(usesDebugAppCheck(debugBuild = true, debugToken = "  "))
    }

    @Test fun explicitDebugTokenIsOnlyAcceptedInDebugBuilds() {
        assertTrue(usesDebugAppCheck(debugBuild = true, debugToken = "test-token"))
        assertFalse(usesDebugAppCheck(debugBuild = false, debugToken = "test-token"))
    }
}
