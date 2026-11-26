package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.model.ActionItem
import com.example.data.model.KeyNumber
import com.example.data.model.ModelInfo
import com.example.data.model.TranscriptSegment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

private const val TAG = "LocalCheckpointStore"

enum class ChunkStatus {
    PENDING,
    DONE,
    FAILED
}

data class AudioChunkCheckpoint(
    val index: Int,
    val startOffsetSec: Long,
    val endOffsetSec: Long,
    val filePath: String,
    val mimeType: String,
    val isTempChunk: Boolean,
    val status: ChunkStatus = ChunkStatus.PENDING,
    val bahasa: String = "id",
    val segments: List<TranscriptSegment> = emptyList(),
    val errorMessage: String? = null
)

data class LocalProcessingJob(
    val meetingId: String,
    val ownerUserId: String,
    val judul: String,
    val originalAudioPath: String,
    val mimeType: String,
    val durasiDetik: Long,
    val ukuranBytes: Long,
    val preferredLanguage: String = "id",
    val createdAtMs: Long = System.currentTimeMillis(),
    val chunks: List<AudioChunkCheckpoint> = emptyList(),
    val transcriptDone: Boolean = false,
    val bahasa: String = "id",
    val mergedSegments: List<TranscriptSegment> = emptyList(),
    val mergedTranscriptText: String = "",
    val summaryDone: Boolean = false,
    val ringkasan: String = "",
    val poinUtama: List<String> = emptyList(),
    val keputusan: List<String> = emptyList(),
    val risiko: List<String> = emptyList(),
    val angkaKunci: List<KeyNumber> = emptyList(),
    val actionItems: List<ActionItem> = emptyList(),
    val modelInfo: ModelInfo? = null,
    val lastError: String? = null
) {
    val completedChunkCount: Int get() = chunks.count { it.status == ChunkStatus.DONE }
    val failedChunkCount: Int get() = chunks.count { it.status == ChunkStatus.FAILED }
    val totalChunkCount: Int get() = chunks.size

    fun toSavePayload(): PersonalMeetingSavePayload = PersonalMeetingSavePayload(
        meetingId = meetingId,
        judul = judul,
        durasiDetik = durasiDetik,
        ukuranBytes = ukuranBytes,
        status = "done",
        modelInfo = modelInfo,
        bahasa = bahasa,
        transcriptText = mergedTranscriptText,
        segments = mergedSegments,
        hasTranscript = true,
        ringkasan = ringkasan,
        poinUtama = poinUtama,
        keputusan = keputusan,
        risiko = risiko,
        angkaKunci = angkaKunci,
        actionItems = actionItems,
        hasSummary = true
    )
}

/**
 * Local per-account checkpoint store for resumable chunked transcription,
 * transcript reuse on summary failure, and save retry without re-running AI.
 * NEVER deletes originalAudioPath.
 */
class LocalProcessingCheckpointStore(private val baseDir: File) {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, "processing_jobs"))

    private fun userDir(ownerUserId: String): File {
        val safeOwner = ownerUserId.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64).ifBlank { "default" }
        return File(baseDir, safeOwner).apply { mkdirs() }
    }

    private fun jobFile(ownerUserId: String, meetingId: String): File {
        val safeId = meetingId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(userDir(ownerUserId), "job_$safeId.json")
    }

    /** Durable checkpoint write; returns false if installation or verification fails. */
    fun saveJob(job: LocalProcessingJob): Boolean {
        if (job.ownerUserId.isBlank() || job.meetingId.isBlank()) return false
        return runCatching {
            val dir = userDir(job.ownerUserId)
            val file = jobFile(job.ownerUserId, job.meetingId)
            val tmp = File(dir, "${file.name}.tmp")
            val backup = File(dir, "${file.name}.bak")
            val serialized = serializeJob(job).toString()
            FileOutputStream(tmp).use { stream ->
                stream.write(serialized.toByteArray(Charsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
            if (file.exists()) {
                if (backup.exists()) backup.delete()
                if (!file.renameTo(backup)) {
                    tmp.delete()
                    throw IllegalStateException("Tidak dapat membuat backup checkpoint")
                }
            }
            if (!tmp.renameTo(file)) {
                if (backup.exists()) backup.renameTo(file)
                throw IllegalStateException("Tidak dapat memasang checkpoint baru")
            }
            val verified = runCatching { JSONObject(file.readText()).getString("meetingId") == job.meetingId }.getOrDefault(false)
            if (!verified) {
                file.delete()
                if (backup.exists()) backup.renameTo(file)
                throw IllegalStateException("Verifikasi checkpoint gagal")
            }
            backup.delete()
            true
        }.onFailure {
            Log.e(TAG, "Failed saving processing checkpoint ${job.meetingId}", it)
        }.getOrDefault(false)
    }

    private fun readValidated(file: File, ownerUserId: String, meetingId: String): LocalProcessingJob? =
        runCatching {
            deserializeJob(JSONObject(file.readText())).takeIf {
                it.ownerUserId == ownerUserId && it.meetingId == meetingId
            }
        }.getOrNull()

    fun loadJob(ownerUserId: String, meetingId: String): LocalProcessingJob? {
        if (ownerUserId.isBlank() || meetingId.isBlank()) return null
        val file = jobFile(ownerUserId, meetingId)
        val backup = File(file.parentFile, "${file.name}.bak")
        val primary = if (file.exists()) readValidated(file, ownerUserId, meetingId) else null
        if (primary != null) return primary

        // Recover the previous durable checkpoint only if the primary is absent or invalid.
        val recovered = if (backup.exists()) readValidated(backup, ownerUserId, meetingId) else null
        if (recovered != null) {
            runCatching {
                if (file.exists()) file.delete()
                backup.copyTo(file, overwrite = true)
                backup.delete()
            }.onFailure { Log.e(TAG, "Failed restoring checkpoint backup", it) }
            return recovered
        }
        return null
    }

    fun listJobs(ownerUserId: String): List<LocalProcessingJob> {
        if (ownerUserId.isBlank()) return emptyList()
        val dir = userDir(ownerUserId)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching { deserializeJob(JSONObject(f.readText())) }
                .getOrNull()
                ?.takeIf { it.ownerUserId == ownerUserId && it.meetingId.isNotBlank() }
        }.sortedByDescending { it.createdAtMs }
    }

    /**
     * Removes checkpoint & temporary chunks safely after full server save (or explicit user discard).
     * NEVER deletes the original recording file (job.originalAudioPath).
     */
    fun removeJobAndCleanTempChunks(ownerUserId: String, meetingId: String) {
        val existing = loadJob(ownerUserId, meetingId)
        if (existing != null) {
            existing.chunks
                .filter { it.isTempChunk && it.filePath != existing.originalAudioPath }
                .forEach { chunk -> runCatching { File(chunk.filePath).delete() } }
        }
        runCatching { jobFile(ownerUserId, meetingId).delete() }
    }

    private fun serializeJob(job: LocalProcessingJob): JSONObject = JSONObject().apply {
        put("meetingId", job.meetingId)
        put("ownerUserId", job.ownerUserId)
        put("judul", job.judul)
        put("originalAudioPath", job.originalAudioPath)
        put("mimeType", job.mimeType)
        put("durasiDetik", job.durasiDetik)
        put("ukuranBytes", job.ukuranBytes)
        put("preferredLanguage", job.preferredLanguage)
        put("createdAtMs", job.createdAtMs)
        put("chunks", JSONArray().apply {
            job.chunks.forEach { c ->
                put(JSONObject().apply {
                    put("index", c.index)
                    put("startOffsetSec", c.startOffsetSec)
                    put("endOffsetSec", c.endOffsetSec)
                    put("filePath", c.filePath)
                    put("mimeType", c.mimeType)
                    put("isTempChunk", c.isTempChunk)
                    put("status", c.status.name)
                    put("bahasa", c.bahasa)
                    put("segments", JSONArray(c.segments.map { JSONObject(it.toMap()) }))
                    put("errorMessage", c.errorMessage ?: JSONObject.NULL)
                })
            }
        })
        put("transcriptDone", job.transcriptDone)
        put("bahasa", job.bahasa)
        put("mergedSegments", JSONArray(job.mergedSegments.map { JSONObject(it.toMap()) }))
        put("mergedTranscriptText", job.mergedTranscriptText)
        put("summaryDone", job.summaryDone)
        put("ringkasan", job.ringkasan)
        put("poinUtama", JSONArray(job.poinUtama))
        put("keputusan", JSONArray(job.keputusan))
        put("risiko", JSONArray(job.risiko))
        put("angkaKunci", JSONArray(job.angkaKunci.map { JSONObject(it.toMap()) }))
        put("actionItems", JSONArray(job.actionItems.map { JSONObject(it.toMap()) }))
        if (job.modelInfo != null) put("modelInfo", JSONObject(job.modelInfo.toMap()))
        put("lastError", job.lastError ?: JSONObject.NULL)
    }

    private fun deserializeJob(obj: JSONObject): LocalProcessingJob {
        val chunksArr = obj.optJSONArray("chunks") ?: JSONArray()
        val chunks = buildList {
            for (i in 0 until chunksArr.length()) {
                val c = chunksArr.optJSONObject(i) ?: continue
                add(
                    AudioChunkCheckpoint(
                        index = c.optInt("index", i),
                        startOffsetSec = c.optLong("startOffsetSec", 0L),
                        endOffsetSec = c.optLong("endOffsetSec", 0L),
                        filePath = c.optString("filePath", ""),
                        mimeType = c.optString("mimeType", "audio/mp4"),
                        isTempChunk = c.optBoolean("isTempChunk", false),
                        status = runCatching { ChunkStatus.valueOf(c.optString("status", "PENDING")) }
                            .getOrDefault(ChunkStatus.PENDING),
                        bahasa = c.optString("bahasa", "id"),
                        segments = PersonalMeetingJsonMapper.parseSegments(c.optJSONArray("segments")),
                        errorMessage = c.optString("errorMessage", "").takeIf { it.isNotBlank() && it != "null" }
                    )
                )
            }
        }
        return LocalProcessingJob(
            meetingId = obj.optString("meetingId", ""),
            ownerUserId = obj.optString("ownerUserId", ""),
            judul = obj.optString("judul", "Rapat"),
            originalAudioPath = obj.optString("originalAudioPath", ""),
            mimeType = obj.optString("mimeType", "audio/mp4"),
            durasiDetik = obj.optLong("durasiDetik", 0L),
            ukuranBytes = obj.optLong("ukuranBytes", 0L),
            preferredLanguage = obj.optString("preferredLanguage", "id"),
            createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis()),
            chunks = chunks,
            transcriptDone = obj.optBoolean("transcriptDone", false),
            bahasa = obj.optString("bahasa", "id"),
            mergedSegments = PersonalMeetingJsonMapper.parseSegments(obj.optJSONArray("mergedSegments")),
            mergedTranscriptText = obj.optString("mergedTranscriptText", ""),
            summaryDone = obj.optBoolean("summaryDone", false),
            ringkasan = obj.optString("ringkasan", ""),
            poinUtama = PersonalMeetingJsonMapper.parseStringList(obj.optJSONArray("poinUtama")),
            keputusan = PersonalMeetingJsonMapper.parseStringList(obj.optJSONArray("keputusan")),
            risiko = PersonalMeetingJsonMapper.parseStringList(obj.optJSONArray("risiko")),
            angkaKunci = PersonalMeetingJsonMapper.parseKeyNumbers(obj.optJSONArray("angkaKunci")),
            actionItems = PersonalMeetingJsonMapper.parseActionItems(obj.optJSONArray("actionItems")),
            modelInfo = PersonalMeetingJsonMapper.parseModelInfo(obj.optJSONObject("modelInfo")),
            lastError = obj.optString("lastError", "").takeIf { it.isNotBlank() && it != "null" }
        )
    }
}
