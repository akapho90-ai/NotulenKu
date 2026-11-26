package com.example.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "RecordingStateRepo"

enum class RecordingStatus {
    IDLE,
    RECORDING,
    PAUSED_USER,
    PAUSED_INTERRUPTED,
    STOPPING
}

data class ActiveRecordingState(
    val sessionId: String = "",
    val ownerUserId: String = "",
    val status: RecordingStatus = RecordingStatus.IDLE,
    val elapsedSeconds: Long = 0L,
    val amplitudeHistory: List<Float> = List(32) { 0.08f },
    val isMicMutedBySystem: Boolean = false,
    val pauseReason: String? = null,
    val segmentCount: Int = 0,
    val completedRecording: RecordedAudioFile? = null,
    val lastError: String? = null
) {
    val isSessionActive: Boolean
        get() = status == RecordingStatus.RECORDING ||
            status == RecordingStatus.PAUSED_USER ||
            status == RecordingStatus.PAUSED_INTERRUPTED ||
            status == RecordingStatus.STOPPING

    val isActivelyCapturing: Boolean
        get() = status == RecordingStatus.RECORDING && !isMicMutedBySystem

    val isPaused: Boolean
        get() = status == RecordingStatus.PAUSED_USER || status == RecordingStatus.PAUSED_INTERRUPTED
}

data class RecoverableLocalRecording(
    val sessionId: String,
    val ownerUserId: String,
    val segmentPaths: List<String>,
    val elapsedSeconds: Long,
    val createdAtMs: Long,
    val totalSizeBytes: Long
)

object RecordingStateRepository {
    private val _state = MutableStateFlow(ActiveRecordingState())
    val state: StateFlow<ActiveRecordingState> = _state.asStateFlow()

    fun update(transform: (ActiveRecordingState) -> ActiveRecordingState) {
        _state.value = transform(_state.value)
    }

    fun consumeCompletedRecording(): RecordedAudioFile? {
        val current = _state.value.completedRecording ?: return null
        _state.value = _state.value.copy(completedRecording = null)
        return current
    }

    fun clearError() {
        _state.value = _state.value.copy(lastError = null)
    }

    fun resetToIdle() {
        _state.value = ActiveRecordingState()
    }

    fun sessionMetaDir(context: Context): File =
        File(context.applicationContext.filesDir, "recording_sessions").apply { mkdirs() }

    fun saveSessionJournal(
        context: Context,
        sessionId: String,
        ownerUserId: String,
        segmentPaths: List<String>,
        elapsedSeconds: Long,
        createdAtMs: Long
    ) {
        if (sessionId.isBlank() || ownerUserId.isBlank()) return
        runCatching {
            val file = File(sessionMetaDir(context), "session_${sessionId}.json")
            val obj = JSONObject().apply {
                put("sessionId", sessionId)
                put("ownerUserId", ownerUserId)
                put("segmentPaths", JSONArray(segmentPaths))
                put("elapsedSeconds", elapsedSeconds)
                put("createdAtMs", createdAtMs)
                put("updatedAtMs", System.currentTimeMillis())
            }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }.onFailure {
            Log.w(TAG, "Failed persisting recording session journal: ${it.message}")
        }
    }

    fun deleteSessionJournal(context: Context, sessionId: String) {
        if (sessionId.isBlank()) return
        runCatching {
            File(sessionMetaDir(context), "session_${sessionId}.json").delete()
        }
    }

    /**
     * Finds unfinished/interrupted local recording sessions belonging strictly to ownerUserId
     * (e.g., after OS process death, battery loss, or unexpected stop error).
     */
    fun listRecoverableSessions(context: Context, ownerUserId: String): List<RecoverableLocalRecording> {
        if (ownerUserId.isBlank()) return emptyList()
        val activeId = _state.value.takeIf { it.isSessionActive }?.sessionId
        val dir = sessionMetaDir(context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching {
                val obj = JSONObject(f.readText())
                val sessionId = obj.optString("sessionId")
                val owner = obj.optString("ownerUserId")
                if (owner != ownerUserId || sessionId == activeId) return@runCatching null
                val arr = obj.optJSONArray("segmentPaths") ?: JSONArray()
                val expectedSegments = buildList {
                    for (i in 0 until arr.length()) {
                        val p = arr.optString(i)
                        if (p.isNotBlank()) add(p)
                    }
                }
                if (expectedSegments.isEmpty()) return@runCatching null
                val totalBytes = expectedSegments.sumOf { File(it).length() }
                RecoverableLocalRecording(
                    sessionId = sessionId,
                    ownerUserId = owner,
                    segmentPaths = expectedSegments,
                    elapsedSeconds = obj.optLong("elapsedSeconds", 1L).coerceAtLeast(1L),
                    createdAtMs = obj.optLong("createdAtMs", f.lastModified()),
                    totalSizeBytes = totalBytes
                )
            }.getOrNull()
        }.sortedByDescending { it.createdAtMs }
    }

    /**
     * Merges a recovered unfinished recording session into a finalized local .m4a file
     * without deleting source segments until merge succeeds.
     */
    fun recoverSessionToAudioFile(
        context: Context,
        session: RecoverableLocalRecording
    ): Result<RecordedAudioFile> {
        val segFiles = session.segmentPaths.map(::File)
        if (segFiles.isEmpty()) {
            return Result.failure(IllegalStateException("Berkas segmen rekaman tidak ditemukan."))
        }
        val outDir = File(context.applicationContext.filesDir, "recordings").apply { mkdirs() }
        val outFile = File(outDir, "rec_recovered_${session.sessionId.take(10)}.m4a")
        val mergeRes = AudioSegmenter.mergeM4aSegments(segFiles, outFile)
        return mergeRes.map { merged ->
            val detectedDur = AudioSegmenter.detectDurationSeconds(merged).coerceAtLeast(session.elapsedSeconds)
            // Safe cleanup of raw segment parts only after merged output is confirmed readable & non-empty
            if (merged.exists() && merged.length() > 256L) {
                segFiles.filter { it.absolutePath != merged.absolutePath }.forEach { runCatching { it.delete() } }
                deleteSessionJournal(context, session.sessionId)
            }
            RecordedAudioFile(
                file = merged,
                durationSeconds = detectedDur.coerceAtLeast(1L),
                sizeBytes = merged.length(),
                mimeType = "audio/mp4"
            )
        }
    }

    fun discardRecoverableSession(context: Context, session: RecoverableLocalRecording) {
        session.segmentPaths.forEach { runCatching { File(it).delete() } }
        deleteSessionJournal(context, session.sessionId)
    }
}
