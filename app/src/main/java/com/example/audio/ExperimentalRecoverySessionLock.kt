package com.example.audio

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Coordinates source files by canonical directory and session within this process. */
internal object ExperimentalRecoverySessionLock {
    private data class SourceKey(val directory: String, val sessionId: String)
    private val sources = ConcurrentHashMap<SourceKey, SourceSession>()
    private val recoveryRoots = ConcurrentHashMap<String, Any>()

    internal class SourceSession {
        // Users include waiters. Updated only by ConcurrentHashMap.compute for this key.
        internal var users = 0
        private var writer: Any? = null
        internal val hasWriter: Boolean get() = writer != null

        fun requireWriterAccess(owner: Any? = null) {
            if (writer != null && writer !== owner) throw IOException("Sesi PCM masih digunakan oleh writer lain.")
        }

        fun claimWriter(owner: Any) {
            requireWriterAccess(owner)
            writer = owner
        }

        fun releaseWriter(owner: Any) {
            if (writer === owner) writer = null
        }
    }

    fun <T> withSourceLock(root: File, sessionId: String, action: SourceSession.() -> T): T {
        val key = SourceKey(root.canonicalPath, sessionId)
        val session = sources.compute(key) { _, current ->
            (current ?: SourceSession()).also { it.users++ }
        }!!
        try {
            return synchronized(session) { session.action() }
        } finally {
            sources.compute(key) { _, _ ->
                session.users--
                // The writer state is stable when no caller holds or waits for this session.
                session.takeIf { it.users > 0 || it.hasWriter }
            }
        }
    }

    /** Serializes recovery output mutations across sessions within a directory. */
    fun <T> withRootLock(root: File, action: () -> T): T {
        val lock = recoveryRoots.computeIfAbsent(root.canonicalPath) { Any() }
        return synchronized(lock) { action() }
    }

    /** Output operations acquire the root lock before the source lock. Source-only callers never take the root lock. */
    fun <T> withLock(root: File, sessionId: String, action: SourceSession.() -> T): T =
        withRootLock(root) { withSourceLock(root, sessionId, action) }
}
