package com.example.audio

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** JVM-wide keyed lock for recovery operations targeting the same canonical session root and id. */
internal object ExperimentalRecoverySessionLock {
    private val locks = ConcurrentHashMap<String, Any>()

    fun <T> withLock(root: File, sessionId: String, action: () -> T): T {
        val key = root.canonicalPath + "|" + sessionId
        val lock = locks.computeIfAbsent(key) { Any() }
        synchronized(lock) { return action() }
    }
}
