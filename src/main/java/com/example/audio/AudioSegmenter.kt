package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import com.example.data.model.TranscriptSegment
import java.io.File
import java.nio.ByteBuffer

private const val TAG = "AudioSegmenter"

data class PlannedAudioWindow(
    val index: Int,
    val startSec: Long,
    val endSec: Long
)

data class PreparedAudioChunk(
    val index: Int,
    val startOffsetSec: Long,
    val endOffsetSec: Long,
    val file: File,
    val mimeType: String,
    val isTempChunk: Boolean
)

object AudioSegmenter {
    const val DEFAULT_CHUNK_DURATION_SEC = 180L // 3-minute STT chunks
    const val DEFAULT_OVERLAP_SEC = 2L
    const val MAX_DIRECT_UPLOAD_BYTES = 8L * 1024L * 1024L // 8 MB safe threshold under 10 MB backend cap

    /**
     * Pure-Kotlin deterministic chunk window planner supporting >2-hour recordings
     * with small overlap so speech at boundaries is neither truncated nor lost.
     */
    fun planChunkWindows(
        totalDurationSec: Long,
        chunkDurationSec: Long = DEFAULT_CHUNK_DURATION_SEC,
        overlapSec: Long = DEFAULT_OVERLAP_SEC
    ): List<PlannedAudioWindow> {
        val safeTotal = totalDurationSec.coerceAtLeast(1L)
        val safeChunk = chunkDurationSec.coerceAtLeast(30L)
        val safeOverlap = overlapSec.coerceIn(0L, safeChunk / 4L)

        if (safeTotal <= safeChunk) {
            return listOf(PlannedAudioWindow(index = 0, startSec = 0L, endSec = safeTotal))
        }

        val windows = mutableListOf<PlannedAudioWindow>()
        var start = 0L
        var idx = 0
        while (start < safeTotal) {
            val end = (start + safeChunk).coerceAtMost(safeTotal)
            windows.add(PlannedAudioWindow(index = idx, startSec = start, endSec = end))
            if (end >= safeTotal) break
            start = (end - safeOverlap).coerceAtLeast(start + 1L)
            idx++
        }
        return windows
    }

    /**
     * Adjusts each chunk's relative timestamps by its startOffsetSec, preserves strict chronological order,
     * and removes duplicate boundary speech caused by chunk overlap.
     */
    fun mergeChunkTranscripts(
        orderedChunkResults: List<Pair<Long, List<TranscriptSegment>>>
    ): List<TranscriptSegment> {
        val merged = mutableListOf<TranscriptSegment>()
        for ((offsetSec, segments) in orderedChunkResults) {
            // Earlier rows from this same chunk are never candidates for boundary deduplication.
            val previousCount = merged.size
            for (local in segments.sortedBy { it.mulaiDetik }) {
                if (offsetSec < 0 || local.mulaiDetik < 0 || local.selesaiDetik < local.mulaiDetik ||
                    local.selesaiDetik > Long.MAX_VALUE - offsetSec || local.teks.isBlank()) continue
                val segment = local.copy(
                    mulaiDetik = offsetSec + local.mulaiDetik,
                    selesaiDetik = offsetSec + local.selesaiDetik
                )
                val index = (previousCount - 1 downTo 0).firstOrNull { i ->
                    val previous = merged[i]
                    previous.pembicara == segment.pembicara &&
                        previous.mulaiDetik < segment.selesaiDetik &&
                        segment.mulaiDetik < previous.selesaiDetik
                }
                val previous = index?.let { merged[it] }
                if (previous != null && isBoundaryDuplicate(previous, segment)) {
                    merged[index] = previous.copy(selesaiDetik = maxOf(previous.selesaiDetik, segment.selesaiDetik))
                    continue
                }
                val cleaned = if (previous != null && segment.selesaiDetik > previous.selesaiDetik) {
                    trimOverlappingPrefix(previous.teks, segment.teks)
                } else segment.teks.trim()
                merged.add(segment.copy(teks = cleaned))
            }
        }
        return merged.sortedBy { it.mulaiDetik }
    }

    private val word = Regex("[\\p{L}\\p{M}\\p{N}]+")

    private fun normalizeWord(value: String): String =
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC).lowercase()

    internal fun isBoundaryDuplicate(prev: TranscriptSegment, next: TranscriptSegment): Boolean {
        if (prev.pembicara != next.pembicara || next.mulaiDetik >= prev.selesaiDetik ||
            prev.mulaiDetik >= next.selesaiDetik) return false
        val before = word.findAll(prev.teks).map { normalizeWord(it.value) }.toList()
        val after = word.findAll(next.teks).map { normalizeWord(it.value) }.toList()
        return before.size >= 3 && before == after
    }

    internal fun trimOverlappingPrefix(prevText: String?, nextText: String): String {
        val clean = nextText.trim()
        if (prevText.isNullOrBlank()) return clean
        val before = word.findAll(prevText).map { normalizeWord(it.value) }.toList()
        val after = word.findAll(clean).toList()
        val normalized = after.map { normalizeWord(it.value) }
        for (count in minOf(before.size, after.size - 1, 8) downTo 3) {
            if (before.takeLast(count) == normalized.take(count)) {
                return clean.substring(after[count].range.first)
            }
        }
        return clean
    }

    /**
     * Splits a long audio file into valid standalone M4A segments using MediaExtractor + MediaMuxer
     * (never arbitrary raw byte slicing). Never deletes or alters sourceFile.
     */
    fun prepareAudioChunks(
        context: Context,
        sessionId: String,
        sourceFile: File,
        mimeType: String,
        knownDurationSec: Long,
        chunkDurationSec: Long = DEFAULT_CHUNK_DURATION_SEC
    ): Result<List<PreparedAudioChunk>> {
        if (!sourceFile.isFile || sourceFile.length() <= 0L) {
            return Result.failure(IllegalArgumentException("Berkas audio asli tidak ditemukan atau kosong."))
        }
        if (!processingSessionId.matches(sessionId)) {
            return Result.failure(IllegalArgumentException("ID pemrosesan audio tidak valid."))
        }
        val readable = runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(sourceFile.absolutePath)
                val track = findAudioTrackIndex(extractor)
                if (track < 0) false else {
                    extractor.selectTrack(track)
                    extractor.sampleTrackIndex == track
                }
            } finally {
                extractor.release()
            }
        }.getOrDefault(false)
        if (!readable) return Result.failure(IllegalArgumentException("Berkas audio tidak memuat sampel yang dapat dibaca."))
        val metadataMs = detectDurationMillis(sourceFile)
        val detectedDurationSec = if (metadataMs > 0) (metadataMs - 1L) / 1_000L + 1L
            else knownDurationSec.coerceAtLeast(1L)
        val needsSplitting = sourceFile.length() > MAX_DIRECT_UPLOAD_BYTES || detectedDurationSec > (chunkDurationSec + 60L)

        if (!needsSplitting) {
            return Result.success(
                listOf(
                    PreparedAudioChunk(
                        index = 0,
                        startOffsetSec = 0L,
                        endOffsetSec = detectedDurationSec,
                        file = sourceFile,
                        mimeType = mimeType.ifBlank { "audio/mp4" },
                        isTempChunk = false
                    )
                )
            )
        }

        val chunkDir = processingChunkFile(context, sessionId, 0).parentFile ?: return Result.failure(
            IllegalStateException("Folder segmen audio lokal tidak tersedia.")
        )
        if (!chunkDir.isDirectory && !chunkDir.mkdirs()) {
            return Result.failure(IllegalStateException("Folder segmen audio lokal tidak dapat dibuat."))
        }
        // Adjust chunk duration downward if bitrate is high so each chunk stays safely under 8 MB
        val bytesPerSec = (sourceFile.length().toDouble() / detectedDurationSec.toDouble()).coerceAtLeast(1000.0)
        val sizeSafeChunkSec = ((MAX_DIRECT_UPLOAD_BYTES * 0.75) / bytesPerSec).toLong()
            .coerceIn(DEFAULT_OVERLAP_SEC + 1L, chunkDurationSec.coerceAtLeast(DEFAULT_OVERLAP_SEC + 1L))
        val windows = planChunkWindows(detectedDurationSec, sizeSafeChunkSec, DEFAULT_OVERLAP_SEC)

        val chunks = mutableListOf<PreparedAudioChunk>()
        for (window in windows) {
            val outFile = processingChunkFile(context, sessionId, window.index)
            if (outFile.canonicalFile == sourceFile.canonicalFile) {
                return Result.failure(IllegalArgumentException("Lokasi segmen bertabrakan dengan audio asli."))
            }
            val extractRes = extractValidM4aSegment(
                sourceFile = sourceFile,
                destFile = outFile,
                startUs = window.startSec * 1_000_000L,
                endUs = window.endSec * 1_000_000L
            )
            if (extractRes.isFailure || !outFile.isFile || outFile.length() !in 1L..MAX_DIRECT_UPLOAD_BYTES) {
                // Clean partial temp chunks only (NEVER touch sourceFile)
                chunks.filter { it.isTempChunk }.forEach { runCatching { it.file.delete() } }
                runCatching { outFile.delete() }
                return Result.failure(
                    extractRes.exceptionOrNull()
                        ?: IllegalStateException("Gagal memecah audio panjang menjadi segmen valid.")
                )
            }
            chunks.add(
                PreparedAudioChunk(
                    index = window.index,
                    startOffsetSec = window.startSec,
                    endOffsetSec = window.endSec,
                    file = outFile,
                    mimeType = "audio/mp4",
                    isTempChunk = true
                )
            )
        }

        if (chunks.isEmpty()) {
            return Result.failure(IllegalStateException("Tidak ada segmen audio valid yang dapat diproses."))
        }
        return Result.success(chunks)
    }

    /**
     * Extracts a valid container-framed audio segment [startUs, endUs] into a standalone MPEG-4/M4A file.
     */
    private fun extractValidM4aSegment(
        sourceFile: File,
        destFile: File,
        startUs: Long,
        endUs: Long
    ): Result<File> {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(sourceFile.absolutePath)
            val audioTrackIdx = findAudioTrackIndex(extractor)
            if (audioTrackIdx < 0) {
                return Result.failure(IllegalArgumentException("Berkas tidak memiliki trek audio yang valid."))
            }
            extractor.selectTrack(audioTrackIdx)
            val format = extractor.getTrackFormat(audioTrackIdx)
            val trackMime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (!trackMime.startsWith("audio/mp4a") && !trackMime.startsWith("audio/aac") && !trackMime.startsWith("audio/mpeg")) {
                return Result.failure(
                    IllegalArgumentException(
                        "Format audio ($trackMime) melebihi batas ukuran tunggal dan tidak mendukung pemotongan kontainer M4A langsung. Gunakan format M4A/AAC atau MP3."
                    )
                )
            }

            if (destFile.exists()) destFile.delete()
            muxer = MediaMuxer(destFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxTrackIdx = muxer.addTrack(format)
            muxer.start()

            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val maxBufSize = runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }
                .getOrDefault(256 * 1024)
                .coerceIn(64 * 1024, 1024 * 1024)
            val buffer = ByteBuffer.allocate(maxBufSize)
            val info = MediaCodec.BufferInfo()
            var wroteSample = false

            while (true) {
                info.offset = 0
                info.size = extractor.readSampleData(buffer, 0)
                if (info.size < 0) break
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs >= endUs) break
                if (info.size > 0 && sampleTimeUs >= startUs) {
                    info.presentationTimeUs = sampleTimeUs - startUs
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(muxTrackIdx, buffer, info)
                    wroteSample = true
                }
                extractor.advance()
            }

            check(wroteSample) { "Tidak ada sampel audio pada rentang segmen." }
            muxer.stop()
            muxer.release()
            muxer = null
            Result.success(destFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed extracting valid audio segment", e)
            runCatching { if (destFile.exists()) destFile.delete() }
            Result.failure(
                IllegalStateException("Gagal memecah audio panjang secara aman: ${e.localizedMessage ?: "format tidak didukung"}")
            )
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Merges multiple recorded .m4a segments (from pauses, call interruptions, or 15-min rotations)
     * into one valid .m4a file using MediaExtractor + MediaMuxer.
     */
    fun mergeM4aSegments(segmentFiles: List<File>, outputFile: File): Result<File> {
        val validFiles = segmentFiles.filter { it.exists() && it.length() > 256L }
        if (validFiles.isEmpty()) {
            return Result.failure(IllegalStateException("Tidak ada segmen rekaman yang valid untuk digabungkan."))
        }
        if (validFiles.size == 1) {
            return runCatching {
                val single = validFiles.first()
                if (single.absolutePath != outputFile.absolutePath) {
                    single.copyTo(outputFile, overwrite = true)
                }
                outputFile
            }
        }

        var muxer: MediaMuxer? = null
        val tempMerged = File(outputFile.parentFile, "${outputFile.name}.merging")
        return try {
            val firstExtractor = MediaExtractor()
            firstExtractor.setDataSource(validFiles.first().absolutePath)
            val firstTrack = findAudioTrackIndex(firstExtractor)
            if (firstTrack < 0) {
                firstExtractor.release()
                return Result.failure(IllegalStateException("Segmen rekaman pertama tidak memiliki trek audio valid."))
            }
            val trackFormat = firstExtractor.getTrackFormat(firstTrack)
            val maxBufSize = runCatching { trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }
                .getOrDefault(256 * 1024)
                .coerceIn(64 * 1024, 1024 * 1024)
            firstExtractor.release()

            if (tempMerged.exists()) tempMerged.delete()
            muxer = MediaMuxer(tempMerged.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxTrackIdx = muxer.addTrack(trackFormat)
            muxer.start()

            val buffer = ByteBuffer.allocate(maxBufSize)
            val info = MediaCodec.BufferInfo()
            var globalPtsOffsetUs = 0L

            for (segFile in validFiles) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(segFile.absolutePath)
                    val trackIdx = findAudioTrackIndex(extractor)
                    if (trackIdx < 0) continue
                    extractor.selectTrack(trackIdx)

                    var segBasePtsUs = -1L
                    var lastSegPtsUs = 0L
                    while (true) {
                        info.offset = 0
                        info.size = extractor.readSampleData(buffer, 0)
                        if (info.size < 0) break
                        val sampleTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                        if (segBasePtsUs < 0L) segBasePtsUs = sampleTimeUs
                        val relUs = (sampleTimeUs - segBasePtsUs).coerceAtLeast(0L)
                        info.presentationTimeUs = globalPtsOffsetUs + relUs
                        info.flags = extractor.sampleFlags
                        muxer.writeSampleData(muxTrackIdx, buffer, info)
                        lastSegPtsUs = relUs
                        extractor.advance()
                    }
                    // Add ~64ms AAC frame spacing between segments to keep timestamps strictly monotonic
                    globalPtsOffsetUs += lastSegPtsUs + 64_000L
                } catch (segErr: Exception) {
                    Log.w(TAG, "Skipping unreadable segment ${segFile.name} during merge: ${segErr.message}")
                } finally {
                    runCatching { extractor.release() }
                }
            }

            muxer.stop()
            muxer.release()
            muxer = null

            if (!tempMerged.exists() || tempMerged.length() <= 256L) {
                return Result.failure(IllegalStateException("Hasil penggabungan audio kosong."))
            }
            if (outputFile.exists()) outputFile.delete()
            if (!tempMerged.renameTo(outputFile)) {
                tempMerged.copyTo(outputFile, overwrite = true)
                tempMerged.delete()
            }
            Result.success(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed merging recording segments", e)
            runCatching { muxer?.release() }
            runCatching { if (tempMerged.exists()) tempMerged.delete() }
            Result.failure(e)
        }
    }

    fun detectDurationSeconds(file: File): Long = detectDurationMillis(file) / 1_000L

    private fun detectDurationMillis(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            durMs.coerceAtLeast(0L)
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private val processingSessionId = Regex("[a-zA-Z0-9_-]{1,128}")
    private val processingChunkName = Regex("chunk_[0-9]{4,}[.]m4a")

    internal fun processingChunkFile(context: Context, sessionId: String, index: Int): File {
        require(processingSessionId.matches(sessionId) && index >= 0)
        val root = File(context.cacheDir, "processing_audio_chunks").canonicalFile
        val dir = File(root, sessionId)
        check(dir.canonicalFile == dir) { "Folder segmen audio tidak valid." }
        return File(dir, "chunk_${index.toString().padStart(4, '0')}.m4a").also {
            check(it.canonicalFile == it) { "Lokasi segmen audio tidak valid." }
        }
    }

    fun cleanupTempChunkDir(context: Context, sessionId: String, originalAudioPath: String?) {
        runCatching {
            val dir = processingChunkFile(context, sessionId, 0).parentFile ?: return
            val original = originalAudioPath?.takeIf { it.isNotBlank() }?.let { File(it).canonicalFile }
            dir.listFiles().orEmpty().filter {
                it.isFile && processingChunkName.matches(it.name) && it.canonicalFile == it && it != original
            }.forEach { it.delete() }
            dir.delete() // Succeeds only when empty.
        }
    }

    private fun findAudioTrackIndex(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }
}
