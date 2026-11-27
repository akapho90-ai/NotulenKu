package com.example.audio

import android.content.Context
import java.io.File

/** Owns STT chunks only. Master recordings are stored separately under Meet. */
internal object ProcessingChunkStorage {
    const val RETENTION_MS = 72L * 60 * 60 * 1000
    private val sessionName = Regex("[a-zA-Z0-9_-]{1,128}")
    private val chunkName = Regex("chunk_[0-9]{4,}[.]m4a(?:[.]part)?")
    private val activeSessions = mutableSetOf<String>()

    fun isSessionIdValid(sessionId: String) = sessionName.matches(sessionId)

    @Synchronized
    fun acquire(sessionId: String): Boolean = isSessionIdValid(sessionId) && activeSessions.add(sessionId)

    @Synchronized
    fun release(sessionId: String) {
        activeSessions.remove(sessionId)
    }

    fun files(context: Context, sessionId: String, index: Int, includeLegacy: Boolean = true): List<File> {
        require(isSessionIdValid(sessionId) && index >= 0)
        val name = "chunk_${index.toString().padStart(4, '0')}.m4a"
        return roots(context, includeLegacy).mapNotNull { child(it, sessionId)?.let { dir -> child(dir, name) } }
    }

    fun isRetained(file: File, now: Long = System.currentTimeMillis()): Boolean =
        file.isFile && file.length() in 1L..AudioSegmenter.MAX_DIRECT_UPLOAD_BYTES &&
            file.lastModified() > now - RETENTION_MS

    @Synchronized
    fun cleanupExpired(context: Context, now: Long = System.currentTimeMillis()) {
        val cutoff = now - RETENTION_MS
        for (root in roots(context, includeLegacy = true)) {
            for (entry in root.listFiles().orEmpty()) {
                if (!isSessionIdValid(entry.name) || entry.name in activeSessions) continue
                val dir = child(root, entry.name)?.takeIf { it.isDirectory } ?: continue
                for (entryFile in dir.listFiles().orEmpty()) {
                    if (!chunkName.matches(entryFile.name)) continue
                    val chunk = child(dir, entryFile.name)?.takeIf { it.isFile } ?: continue
                    val modified = chunk.lastModified()
                    if (modified in 1L..cutoff) chunk.delete()
                }
                dir.delete() // Only removes an empty directory.
            }
        }
    }

    private fun roots(context: Context, includeLegacy: Boolean): List<File> = listOfNotNull(
        RecordingFileIdentity.mediaFolder(context, "Chunks"),
        File(context.filesDir, "processing_audio_chunks"),
        File(context.cacheDir, "processing_audio_chunks").takeIf { includeLegacy }
    ).mapNotNull { root ->
        // Canonicalize the volume's base (which can be an Android mount alias), but never
        // follow a link in our managed roots or inside a meeting directory.
        if (root.name == "Chunks") {
            runCatching { root.parentFile?.parentFile?.canonicalFile }.getOrNull()
                ?.let { child(it, "NotulenKu") }?.let { child(it, "Chunks") }
        } else {
            runCatching { root.parentFile?.canonicalFile }.getOrNull()
                ?.let { child(it, root.name) }
        }
    }

    private fun child(parent: File, name: String): File? = runCatching {
        File(parent, name).takeIf { it.canonicalFile == it }
    }.getOrNull()
}
