package com.example.audio

import java.io.File
import java.io.IOException
import java.util.UUID

enum class RecoveryAuditEventType { STARTED, COMPLETED, VALIDATION_FAILED, FAILED, CLEANUP }
data class RecoveryAuditEvent(
    val eventId:String,
    val sessionId:String,
    val type:RecoveryAuditEventType,
    val timestampEpochMs:Long,
    val artifacts:String,
    val detail:String
)

/** Append-only-by-event audit log, stored in recovery/ and updated by atomic temp-file replacement. */
class ExperimentalPcmRecoveryAuditTrail(private val sessionRoot:File, private val clock:()->Long={System.currentTimeMillis()}) {
    private val dir=File(sessionRoot,"recovered")
    private val log=File(dir,"recovery-audit.log")
    private val lock=Any()
    fun record(sessionId:String,type:RecoveryAuditEventType,artifacts:String="",detail:String=""):RecoveryAuditEvent {
        synchronized(lock) {
            if(!dir.exists()&&!dir.mkdirs()) throw IOException("Direktori audit tidak dapat dibuat.")
            if(dir.canonicalFile.parentFile!=sessionRoot.canonicalFile) throw IOException("Path audit tidak aman.")
            val event=RecoveryAuditEvent(UUID.randomUUID().toString(),sessionId,type,clock(),artifacts,detail)
            val line=encode(event)
            val temp=File(dir,log.name+".tmp")
            if(temp.exists()) throw IOException("Temporary audit log sudah ada; manual inspection required.")
            try {
                temp.writeText((if(log.exists()) log.readText() else "")+line+"
")
                java.io.RandomAccessFile(temp,"rw").use { it.fd.sync() }
                if(!temp.renameTo(log)) throw IOException("Audit log gagal difinalisasi.")
            }catch(e:Exception){temp.delete();throw e}
            return event
        }
    }
    fun readAll():List<RecoveryAuditEvent> {
        synchronized(lock) {
            if(!log.isFile) return emptyList()
            if(log.canonicalFile.parentFile!=dir.canonicalFile) throw IOException("Path audit tidak aman.")
            return log.readLines().mapNotNull(::decode)
        }
    }
    fun eventsFor(sessionId:String)=readAll().filter { it.sessionId==sessionId }
    private fun clean(s:String)=s.replace("\\","\\\\").replace("|","\\p").replace("\n","\\n").replace("\r","\\r")
    private fun encode(e:RecoveryAuditEvent)=listOf(e.eventId,e.sessionId,e.type.name,e.timestampEpochMs.toString(),clean(e.artifacts),clean(e.detail)).joinToString("|")
    private fun decode(line:String):RecoveryAuditEvent? {
        // Event detail fields are escaped; parse only unescaped delimiters.
        val fields=mutableListOf<String>();var current=StringBuilder();var escaped=false
        for(c in line) { if(escaped){current.append(when(c){'p'->'|','n'->'\n','r'->'\r','\\'->'\\',else->c});escaped=false}
            else if(c=='\\') escaped=true else if(c=='|'){fields+=current.toString();current=StringBuilder()}else current.append(c) }
        fields+=current.toString()
        if(fields.size!=6) return null
        return runCatching { RecoveryAuditEvent(fields[0],fields[1],RecoveryAuditEventType.valueOf(fields[2]),fields[3].toLong(),fields[4],fields[5]) }.getOrNull()
    }
}
