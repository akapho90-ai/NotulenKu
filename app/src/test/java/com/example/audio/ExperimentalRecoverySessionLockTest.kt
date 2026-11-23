package com.example.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ExperimentalRecoverySessionLockTest {
    @Test fun sessionLockWaitsForRootMutationLock() {
        val root = createTempDir()
        val rootHeld = CountDownLatch(1)
        val releaseRoot = CountDownLatch(1)
        val sessionEntered = CountDownLatch(1)
        try {
            val rootThread = Thread {
                ExperimentalRecoverySessionLock.withRootLock(root) {
                    rootHeld.countDown()
                    releaseRoot.await(2, TimeUnit.SECONDS)
                }
            }
            rootThread.start()
            assertTrue(rootHeld.await(1, TimeUnit.SECONDS))
            val sessionThread = Thread {
                ExperimentalRecoverySessionLock.withLock(root, "session-1") {
                    sessionEntered.countDown()
                }
            }
            sessionThread.start()
            assertFalse(sessionEntered.await(100, TimeUnit.MILLISECONDS))
            releaseRoot.countDown()
            assertTrue(sessionEntered.await(1, TimeUnit.SECONDS))
            rootThread.join(1000)
            sessionThread.join(1000)
        } finally {
            releaseRoot.countDown()
            root.deleteRecursively()
        }
    }
}
