package com.example.audio

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** JVM-wide keyed lock for recovery operations targeting the same canonical session root and id. */
internal object ExperimentalRecoverySessionLock {
    private val locks = ConcurrentHashMap<String, Any>()

    private fun lockFor(key: String) = locks.computeIfAbsent(key) { Any() }

    /** Serializes recovery-directory mutations across sessions within this JVM. */
    fun <T> withRootLock(root: File, action: () -> T): T {
        val key = root.canonicalPath + "|__recovery_root__"
        synchronized(lockFor(key)) { return action() }
    }

    /** Root lock first, then a session-specific lock, with a consistent lock order. */
    fun <T> withLock(root: File, sessionId: String, action: () -> T): T =
        withRootLock(root) {
            val key = root.canonicalPath + "|" + sessionId
            synchronized(lockFor(key)) { action() }
        }
}
