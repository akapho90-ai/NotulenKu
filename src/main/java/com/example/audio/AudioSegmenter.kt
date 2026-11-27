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
        if (!ProcessingChunkStorage.isSessionIdValid(sessionId)) {
            return Result.failure(IllegalArgumentException("ID pemrosesan audio tidak valid."))
        }
        if (!hasReadableAudio(sourceFile)) {
            return Result.failure(IllegalArgumentException("Berkas audio tidak memuat sampel yang dapat dibaca."))
        }
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

        // Adjust chunk duration downward if bitrate is high so each chunk stays safely under 8 MB
        val bytesPerSec = (sourceFile.length().toDouble() / detectedDurationSec.toDouble()).coerceAtLeast(1000.0)
        val sizeSafeChunkSec = ((MAX_DIRECT_UPLOAD_BYTES * 0.75) / bytesPerSec).toLong()
            .coerceIn(DEFAULT_OVERLAP_SEC + 1L, chunkDurationSec.coerceAtLeast(DEFAULT_OVERLAP_SEC + 1L))
        val windows = planChunkWindows(detectedDurationSec, sizeSafeChunkSec, DEFAULT_OVERLAP_SEC)

        val chunks = mutableListOf<PreparedAudioChunk>()
        for (window in windows) {
            val cached = ProcessingChunkStorage.files(context, sessionId, window.index)
                .firstOrNull { ProcessingChunkStorage.isRetained(it) && hasReadableAudio(it) }
            val outFile = cached ?: writeProcessingChunk(context, sessionId, window, sourceFile)
                .getOrElse { return Result.failure(it) }
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

    private fun writeProcessingChunk(
        context: Context,
        sessionId: String,
        window: PlannedAudioWindow,
        sourceFile: File
    ): Result<File> {
        var lastError: Throwable? = null
        for (dest in ProcessingChunkStorage.files(context, sessionId, window.index, includeLegacy = false)) {
            val partial = File(dest.parentFile, dest.name + ".part")
            val result = runCatching {
                check(dest != sourceFile.canonicalFile &&
                    partial.canonicalFile == partial && partial != sourceFile.canonicalFile) {
                    "Lokasi segmen bertabrakan dengan audio asli."
                }
                check(dest.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
                    "Folder segmen audio lokal tidak dapat dibuat."
                }
                extractValidM4aSegment(
                    sourceFile, partial, window.startSec * 1_000_000L, window.endSec * 1_000_000L
                ).getOrThrow()
                check(partial.length() in 1L..MAX_DIRECT_UPLOAD_BYTES && hasReadableAudio(partial)) {
                    "Segmen audio tidak dapat dibaca atau melebihi batas ukuran."
                }
                check(partial.renameTo(dest)) { "Gagal menyimpan segmen audio lokal." }
                dest
            }
            if (result.isSuccess) return result
            lastError = result.exceptionOrNull()
        }
        return Result.failure(lastError ?: IllegalStateException("Folder segmen audio lokal tidak tersedia."))
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
        if (segmentFiles.isEmpty() || segmentFiles.any { !it.isFile || it.length() == 0L }) {
            return Result.failure(IllegalStateException("Ada segmen rekaman yang hilang atau kosong."))
        }
        if (segmentFiles.size == 1) {
            return runCatching {
                val single = segmentFiles.single()
                check(hasReadableAudio(single)) { "Segmen rekaman tidak memuat sampel audio." }
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
            val trackFormat = try {
                firstExtractor.setDataSource(segmentFiles.first().absolutePath)
                val firstTrack = findAudioTrackIndex(firstExtractor)
                check(firstTrack >= 0) { "Segmen pertama tidak memiliki trek audio." }
                firstExtractor.getTrackFormat(firstTrack)
            } finally {
                firstExtractor.release()
            }
            val maxBufSize = runCatching { trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }
                .getOrDefault(256 * 1024)
                .coerceIn(64 * 1024, 1024 * 1024)

            if (tempMerged.exists()) tempMerged.delete()
            muxer = MediaMuxer(tempMerged.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxTrackIdx = muxer.addTrack(trackFormat)
            muxer.start()

            val buffer = ByteBuffer.allocate(maxBufSize)
            val info = MediaCodec.BufferInfo()
            var globalPtsOffsetUs = 0L

            for (segFile in segmentFiles) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(segFile.absolutePath)
                    val trackIdx = findAudioTrackIndex(extractor)
                    check(trackIdx >= 0) { "Segmen rekaman tidak memiliki trek audio." }
                    extractor.selectTrack(trackIdx)

                    var segBasePtsUs = -1L
                    var lastSegPtsUs = 0L
                    var wroteSample = false
                    while (true) {
                        info.offset = 0
                        info.size = extractor.readSampleData(buffer, 0)
                        if (info.size < 0) break
                        if (info.size == 0 || extractor.sampleTime < 0) {
                            extractor.advance()
                            continue
                        }
                        val sampleTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                        if (segBasePtsUs < 0L) segBasePtsUs = sampleTimeUs
                        val relUs = (sampleTimeUs - segBasePtsUs).coerceAtLeast(0L)
                        info.presentationTimeUs = globalPtsOffsetUs + relUs
                        info.flags = extractor.sampleFlags
                        muxer.writeSampleData(muxTrackIdx, buffer, info)
                        wroteSample = true
                        lastSegPtsUs = relUs
                        extractor.advance()
                    }
                    check(wroteSample) { "Segmen rekaman tidak memuat sampel audio." }
                    // Add ~64ms AAC frame spacing between segments to keep timestamps strictly monotonic
                    globalPtsOffsetUs += lastSegPtsUs + 64_000L
                } finally {
                    runCatching { extractor.release() }
                }
            }

            muxer.stop()
            muxer.release()
            muxer = null

            check(hasReadableAudio(tempMerged)) { "Hasil penggabungan audio tidak dapat dibaca." }
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

    internal fun detectDurationMillis(file: File): Long {
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


    private fun findAudioTrackIndex(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    private fun hasReadableAudio(file: File): Boolean = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = findAudioTrackIndex(extractor)
            if (track < 0) false else {
                extractor.selectTrack(track)
                extractor.sampleTrackIndex == track
            }
        } finally {
            extractor.release()
        }
    }.getOrDefault(false)
}
