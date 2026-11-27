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
        hasSummary = true,
        expectedFirebaseUid = ownerUserId
    )
}

/**
 * Local per-account checkpoint store for resumable chunked transcription,
 * transcript reuse on summary failure, and save retry without re-running AI.
 * NEVER deletes originalAudioPath.
 */
class LocalProcessingCheckpointStore(private val baseDir: File) {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, "processing_jobs"))

    private companion object {
        // Several ViewModels/store instances can refer to the same files in this process.
        val lock = Any()
        val validId = Regex("[a-zA-Z0-9_-]{1,128}")
        val jobName = Regex("job_([a-zA-Z0-9_-]{1,128})[.]json(?:[.]bak)?")
    }

    private fun userDir(ownerUserId: String): File = File(baseDir, ownerUserId)

    private fun jobFile(ownerUserId: String, meetingId: String): File =
        File(userDir(ownerUserId), "job_$meetingId.json")

    fun saveJob(job: LocalProcessingJob): Boolean = synchronized(lock) {
        if (!validId.matches(job.ownerUserId) || !validId.matches(job.meetingId)) return false
        if (!hasConsistentProgress(job)) return false
        val file = jobFile(job.ownerUserId, job.meetingId)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        val backup = File(file.parentFile, "${file.name}.bak")
        runCatching {
            check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
            val serialized = serializeJob(job).toString()
            FileOutputStream(tmp).use { stream ->
                stream.write(serialized.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            check(readValidated(tmp, job.ownerUserId, job.meetingId) != null)
            if (readValidated(file, job.ownerUserId, job.meetingId) != null) {
                check(!backup.exists() || (backup.isFile && backup.delete()))
                check(file.renameTo(backup))
            } else if (file.exists()) {
                // An invalid primary must never replace an earlier valid backup.
                check(file.isFile && file.delete())
            }
            check(tmp.renameTo(file))
            if (readValidated(file, job.ownerUserId, job.meetingId) == null) {
                file.delete()
                error("Verifikasi checkpoint gagal")
            }
            true
        }.onFailure {
            Log.e(TAG, "Failed saving processing checkpoint ${job.meetingId}", it)
        }.getOrDefault(false)
    }

    private fun hasConsistentProgress(job: LocalProcessingJob): Boolean =
        job.chunks.withIndex().all { (index, chunk) ->
            chunk.index == index && chunk.startOffsetSec >= 0 &&
                chunk.endOffsetSec > chunk.startOffsetSec && chunk.filePath.isNotBlank() &&
                (chunk.status != ChunkStatus.DONE || job.transcriptDone || chunk.segments.any { it.teks.isNotBlank() })
        } && (!job.transcriptDone || (job.mergedTranscriptText.isNotBlank() &&
            job.chunks.all { it.status == ChunkStatus.DONE })) &&
            (!job.summaryDone || (job.transcriptDone && job.ringkasan.isNotBlank()))

    private fun readValidated(file: File, ownerUserId: String, meetingId: String): LocalProcessingJob? =
        runCatching {
            val stored = deserializeJob(JSONObject(file.readText()))
            val job = if (stored.transcriptDone) stored else stored.copy(chunks = stored.chunks.map { chunk ->
                if (chunk.status == ChunkStatus.DONE && chunk.segments.none { it.teks.isNotBlank() }) {
                    chunk.copy(status = ChunkStatus.FAILED, errorMessage = "Transkripsi bagian ini kosong.")
                } else chunk
            })
            val recoverable = if (job.summaryDone && job.ringkasan.isBlank() && job.transcriptDone) {
                job.copy(summaryDone = false)
            } else job
            recoverable.takeIf {
                it.ownerUserId == ownerUserId && it.meetingId == meetingId && hasConsistentProgress(it)
            }
        }.getOrNull()

    fun loadJob(ownerUserId: String, meetingId: String): LocalProcessingJob? = synchronized(lock) {
        if (!validId.matches(ownerUserId) || !validId.matches(meetingId)) return null
        val file = jobFile(ownerUserId, meetingId)
        readValidated(file, ownerUserId, meetingId)
            ?: readValidated(File(file.parentFile, "${file.name}.bak"), ownerUserId, meetingId)
    }

    internal fun chunkSessionsWithoutReadableMaster(): Set<String> = synchronized(lock) {
        baseDir.listFiles().orEmpty().asSequence()
            .filter { it.isDirectory && validId.matches(it.name) }
            .flatMap { listJobs(it.name).asSequence() }
            .filter { job ->
                if (job.transcriptDone || job.chunks.none { it.isTempChunk && it.status != ChunkStatus.DONE }) {
                    return@filter false
                }
                val master = File(job.originalAudioPath)
                !master.isFile || !master.canRead() || master.length() == 0L ||
                    (job.ukuranBytes > 0L && master.length() != job.ukuranBytes)
            }
            .map { it.meetingId }
            .toSet()
    }

    fun listJobs(ownerUserId: String): List<LocalProcessingJob> = synchronized(lock) {
        if (!validId.matches(ownerUserId)) return emptyList()
        userDir(ownerUserId).listFiles().orEmpty().asSequence()
            .filter { it.isFile }
            .mapNotNull { jobName.matchEntire(it.name)?.groupValues?.get(1) }
            .distinct()
            .mapNotNull { loadJob(ownerUserId, it) }
            .sortedByDescending { it.createdAtMs }
            .toList()
    }

    /** Removes temporary checkpoint files first so an old backup cannot resurrect a removed job. */
    fun removeJob(ownerUserId: String, meetingId: String): Boolean = synchronized(lock) {
        if (!validId.matches(ownerUserId) || !validId.matches(meetingId)) return false
        val file = jobFile(ownerUserId, meetingId)
        listOf(File(file.parentFile, "${file.name}.tmp"), File(file.parentFile, "${file.name}.bak"), file)
            .all { !it.exists() || (it.isFile && it.delete()) }
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
                val c = chunksArr.getJSONObject(i)
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
