package com.example.audio

import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.util.UUID

enum class RecoveryAuditEventType { STARTED, COMPLETED, VALIDATION_FAILED, FAILED, CLEANUP }
data class RecoveryAuditEvent(val eventId: String, val sessionId: String, val type: RecoveryAuditEventType, val timestampEpochMs: Long, val artifacts: String, val detail: String)

/** Append-only event log; atomic replacement uses a synced candidate file. */
class ExperimentalPcmRecoveryAuditTrail(private val sessionRoot: File, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val dir = File(sessionRoot, "recovered")
    private val log = File(dir, "recovery-audit.log")

    fun record(sessionId: String, type: RecoveryAuditEventType, artifacts: String = "", detail: String = ""): RecoveryAuditEvent =
        ExperimentalRecoverySessionLock.withRootLock(sessionRoot) {
            if (!dir.exists() && !dir.mkdirs()) throw IOException("Direktori audit tidak dapat dibuat.")
            val base = sessionRoot.canonicalFile
            val root = dir.canonicalFile
            if (root.parentFile != base || !root.isDirectory) throw IOException("Path audit tidak aman.")
            if (log.exists() && (!log.isFile || log.canonicalFile.parentFile != root)) throw IOException("File audit tidak aman.")
            val temp = File(root, log.name + ".tmp")
            if (temp.exists()) throw IOException("Temporary audit log sudah ada; manual inspection required.")
            val event = RecoveryAuditEvent(UUID.randomUUID().toString(), sessionId, type, clock(), artifacts, detail)
            try {
                FileOutputStream(temp).use { out ->
                    if (log.exists()) log.inputStream().use { input -> input.copyTo(out) }
                    out.write((encode(event) + "\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                    out.fd.sync()
                }
                if (!temp.renameTo(log)) throw IOException("Audit log gagal difinalisasi.")
                event
            } catch (e: Exception) {
                temp.delete()
                throw e
            }
        }

    fun readAll(): List<RecoveryAuditEvent> = synchronized(this) {
        if (!log.exists()) return@synchronized emptyList()
        val root = dir.canonicalFile
        if (root.parentFile != sessionRoot.canonicalFile || !log.isFile || log.canonicalFile.parentFile != root) throw IOException("Path audit tidak aman.")
        log.readLines(Charsets.UTF_8).mapNotNull(::decode)
    }

    fun eventsFor(sessionId: String) = readAll().filter { it.sessionId == sessionId }

    private fun clean(s: String) = s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n").replace("\r", "\\r")
    private fun encode(e: RecoveryAuditEvent) = listOf(e.eventId, e.sessionId, e.type.name, e.timestampEpochMs.toString(), clean(e.artifacts), clean(e.detail)).joinToString("|")

    private fun decode(line: String): RecoveryAuditEvent? {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        for (c in line) {
            if (escaped) { current.append(when(c) { 'p' -> '|'; 'n' -> '\n'; 'r' -> '\r'; '\\' -> '\\'; else -> c }); escaped = false }
            else if (c == '\\') escaped = true
            else if (c == '|') { fields += current.toString(); current.setLength(0) }
            else current.append(c)
        }
        if (escaped) current.append('\\')
        fields += current.toString()
        if (fields.size != 6) return null
        return runCatching { RecoveryAuditEvent(fields[0], fields[1], RecoveryAuditEventType.valueOf(fields[2]), fields[3].toLong(), fields[4], fields[5]) }.getOrNull()
    }
}
