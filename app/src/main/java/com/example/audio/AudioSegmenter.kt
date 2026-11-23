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
    const val DEFAULT_CHUNK_DURATION_SEC = 600L // 10 minutes (~2.4 MB at 32 kbps AAC)
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
        for ((offsetSec, rawSegments) in orderedChunkResults) {
            val adjusted = rawSegments
                .filter { it.teks.isNotBlank() }
                .map { seg ->
                    val start = (seg.mulaiDetik + offsetSec).coerceAtLeast(0L)
                    val end = (seg.selesaiDetik + offsetSec).coerceAtLeast(start)
                    seg.copy(mulaiDetik = start, selesaiDetik = end)
                }
                .sortedBy { it.mulaiDetik }

            for (seg in adjusted) {
                val prev = merged.lastOrNull()
                if (prev != null && isBoundaryDuplicate(prev, seg)) {
                    // Keep the longer/more complete utterance at the overlap boundary
                    if (seg.teks.length > prev.teks.length) {
                        merged[merged.lastIndex] = prev.copy(
                            selesaiDetik = maxOf(prev.selesaiDetik, seg.selesaiDetik),
                            teks = seg.teks
                        )
                    }
                    continue
                }
                val cleanedText = trimOverlappingPrefix(prev?.teks, seg.teks)
                if (cleanedText.isNotBlank()) {
                    merged.add(seg.copy(teks = cleanedText))
                }
            }
        }
        return merged
    }

    internal fun isBoundaryDuplicate(prev: TranscriptSegment, next: TranscriptSegment): Boolean {
        val timeClose = next.mulaiDetik <= prev.selesaiDetik + 4L
        if (!timeClose) return false
        val a = normalizeWords(prev.teks)
        val b = normalizeWords(next.teks)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        // Near-duplicate when one contains the other across a chunk overlap window
        val aStr = a.joinToString(" ")
        val bStr = b.joinToString(" ")
        return aStr.contains(bStr) || bStr.contains(aStr)
    }

    internal fun trimOverlappingPrefix(prevText: String?, nextText: String): String {
        val cleanNext = nextText.trim()
        if (prevText.isNullOrBlank() || cleanNext.isBlank()) return cleanNext
        val prevWords = normalizeWords(prevText)
        val nextWordsRaw = cleanNext.split(Regex("\\s+"))
        val nextWordsNorm = nextWordsRaw.map { it.lowercase().replace(Regex("[^a-z0-9]"), "") }
        val maxCheck = minOf(prevWords.size, nextWordsNorm.size, 8)
        for (len in maxCheck downTo 3) {
            val prevSuffix = prevWords.takeLast(len)
            val nextPrefix = nextWordsNorm.take(len)
            if (prevSuffix == nextPrefix) {
                val remainder = nextWordsRaw.drop(len).joinToString(" ").trim()
                return remainder
            }
        }
        return cleanNext
    }

    private fun normalizeWords(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

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
        if (!isSafeSessionId(sessionId)) return Result.failure(IllegalArgumentException("ID sesi tidak aman untuk direktori chunk."))
        if (!sourceFile.exists() || sourceFile.length() <= 0L) {
            return Result.failure(IllegalArgumentException("Berkas audio asli tidak ditemukan atau kosong."))
        }
        val detectedDurationSec = detectDurationSeconds(sourceFile).coerceAtLeast(knownDurationSec).coerceAtLeast(1L)
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

        val chunkBase = File(context.cacheDir, "transcription_chunks")
        val chunkDir = File(chunkBase, sessionId)
        if (!chunkBase.mkdirs() && !chunkBase.isDirectory) return Result.failure(IllegalStateException("Direktori chunk tidak dapat dibuat."))
        if (!chunkDir.mkdirs() && !chunkDir.isDirectory) return Result.failure(IllegalStateException("Direktori sesi chunk tidak dapat dibuat."))
        val canonicalBase = runCatching { chunkBase.canonicalFile }.getOrElse { return Result.failure(it) }
        val canonicalChunkDir = runCatching { chunkDir.canonicalFile }.getOrElse { return Result.failure(it) }
        if (canonicalChunkDir.parentFile != canonicalBase) return Result.failure(IllegalStateException("Path chunk sesi tidak aman."))
        // Adjust chunk duration downward if bitrate is high so each chunk stays safely under 8 MB
        val bytesPerSec = (sourceFile.length().toDouble() / detectedDurationSec.toDouble()).coerceAtLeast(1000.0)
        val sizeSafeChunkSec = ((MAX_DIRECT_UPLOAD_BYTES * 0.75) / bytesPerSec).toLong().coerceIn(60L, chunkDurationSec)
        val windows = planChunkWindows(detectedDurationSec, sizeSafeChunkSec, DEFAULT_OVERLAP_SEC)

        val chunks = mutableListOf<PreparedAudioChunk>()
        for (window in windows) {
            val outFile = File(chunkDir, "chunk_${"%04d".format(window.index)}.m4a")
            val extractRes = extractValidM4aSegment(
                sourceFile = sourceFile,
                destFile = outFile,
                startUs = window.startSec * 1_000_000L,
                endUs = window.endSec * 1_000_000L
            )
            if (extractRes.isFailure) {
                // Clean partial temp chunks only (NEVER touch sourceFile)
                chunks.filter { it.isTempChunk }.forEach { runCatching { it.file.delete() } }
                return Result.failure(
                    extractRes.exceptionOrNull()
                        ?: IllegalStateException("Gagal memecah audio panjang menjadi segmen valid.")
                )
            }
            if (outFile.exists() && outFile.length() > 0L) {
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

            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val maxBufSize = runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }
                .getOrDefault(256 * 1024)
                .coerceIn(64 * 1024, 1024 * 1024)
            val buffer = ByteBuffer.allocate(maxBufSize)
            val info = MediaCodec.BufferInfo()
            var basePtsUs = -1L

            while (true) {
                info.offset = 0
                info.size = extractor.readSampleData(buffer, 0)
                if (info.size < 0) break
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > endUs) break
                if (sampleTimeUs >= startUs - 50_000L) {
                    if (basePtsUs < 0L) basePtsUs = sampleTimeUs
                    info.presentationTimeUs = (sampleTimeUs - basePtsUs).coerceAtLeast(0L)
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(muxTrackIdx, buffer, info)
                }
                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            muxer = null
            extractor.release()
            Result.success(destFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed extracting valid audio segment", e)
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            runCatching { if (destFile.exists()) destFile.delete() }
            Result.failure(
                IllegalStateException("Gagal memecah audio panjang secara aman: ${e.localizedMessage ?: "format tidak didukung"}")
            )
        }
    }

    /**
     * Merges multiple recorded .m4a segments (from pauses, call interruptions, or 15-min rotations)
     * into one valid .m4a file using MediaExtractor + MediaMuxer.
     */
    fun mergeM4aSegments(segmentFiles: List<File>, outputFile: File): Result<File> {
        if (segmentFiles.isEmpty()) return Result.failure(IllegalStateException("Tidak ada segmen rekaman untuk digabungkan."))
        // Fail closed: never silently omit requested recording parts.
        val candidates = segmentFiles
        for (file in candidates) {
            if (!file.exists() || file.length() <= 256L || !isReadableAudioFile(file)) {
                return Result.failure(IllegalStateException("Segmen audio tidak valid atau hilang: ${file.name}"))
            }
        }

        var chosenFormat: MediaFormat? = null
        var maxBufSize = 256 * 1024
        for (candidate in candidates) {
            val probe = MediaExtractor()
            try {
                probe.setDataSource(candidate.absolutePath)
                val trackIndex = findAudioTrackIndex(probe)
                if (trackIndex < 0) throw IllegalStateException("Segmen tidak memiliki trek audio: ${candidate.name}")
                val format = probe.getTrackFormat(trackIndex)
                if (chosenFormat == null) {
                    chosenFormat = format
                    maxBufSize = runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }
                        .getOrDefault(256 * 1024).coerceIn(64 * 1024, 1024 * 1024)
                } else if (format.getString(MediaFormat.KEY_MIME) != chosenFormat!!.getString(MediaFormat.KEY_MIME) ||
                    runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) != chosenFormat!!.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(true) ||
                    runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != chosenFormat!!.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(true)
                ) {
                    throw IllegalStateException("Format segmen tidak kompatibel: ${candidate.name}")
                }
            } catch (e: Exception) {
                return Result.failure(e)
            } finally {
                runCatching { probe.release() }
            }
        }

        if (candidates.size == 1) {
            val single = candidates.first()
            if (single.absolutePath == outputFile.absolutePath) return Result.success(single)
            val staged = File(outputFile.parentFile, "${outputFile.name}.staged")
            return try {
                staged.delete()
                single.copyTo(staged, overwrite = true)
                if (!isReadableAudioFile(staged)) throw IllegalStateException("Salinan audio tidak lolos validasi.")
                promoteValidatedAudio(staged, outputFile)
                Result.success(outputFile)
            } catch (e: Exception) {
                runCatching { staged.delete() }
                Result.failure(e)
            }
        }

        val format = chosenFormat ?: return Result.failure(IllegalStateException("Format audio tidak tersedia."))
        val tempMerged = File(outputFile.parentFile, "${outputFile.name}.merging")
        var muxer: MediaMuxer? = null
        return try {
            tempMerged.delete()
            muxer = MediaMuxer(tempMerged.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxTrackIdx = muxer.addTrack(format)
            muxer.start()
            val buffer = ByteBuffer.allocate(maxBufSize)
            val info = MediaCodec.BufferInfo()
            var globalPtsOffsetUs = 0L
            var totalSamplesWritten = 0L

            for (segFile in candidates) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(segFile.absolutePath)
                    val trackIdx = findAudioTrackIndex(extractor)
                    if (trackIdx < 0) throw IllegalStateException("Trek audio hilang pada ${segFile.name}")
                    extractor.selectTrack(trackIdx)
                    var segBasePtsUs = -1L
                    var lastSegPtsUs = 0L
                    var segmentSamples = 0L
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
                        segmentSamples++
                        extractor.advance()
                    }
                    if (segmentSamples == 0L) throw IllegalStateException("Segmen kosong saat dibaca: ${segFile.name}")
                    totalSamplesWritten += segmentSamples
                    globalPtsOffsetUs += lastSegPtsUs + 64_000L
                } finally {
                    extractor.release()
                }
            }

            muxer.stop()
            muxer.release()
            muxer = null
            if (totalSamplesWritten == 0L || !isReadableAudioFile(tempMerged)) throw IllegalStateException("Hasil merge tidak lolos validasi audio.")
            promoteValidatedAudio(tempMerged, outputFile)
            if (!isReadableAudioFile(outputFile)) throw IllegalStateException("File final gagal validasi setelah promosi.")
            Result.success(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Merge failed; source segments preserved", e)
            runCatching { muxer?.release() }
            runCatching { tempMerged.delete() }
            Result.failure(e)
        }
    }

    /** Staged promotion with backup/restore attempt and validation after final placement. */
    private fun promoteValidatedAudio(staged: File, destination: File) {
        if (!isReadableAudioFile(staged)) throw IllegalStateException("File staged tidak valid.")
        val backup = File(destination.parentFile, "${destination.name}.backup")
        if (backup.exists() && !backup.delete()) throw IllegalStateException("Tidak dapat membersihkan backup audio.")
        val hadDestination = destination.exists()
        if (hadDestination && !destination.renameTo(backup)) {
            destination.copyTo(backup, overwrite = true)
            if (!isReadableAudioFile(backup)) throw IllegalStateException("Backup file lama gagal validasi.")
        }
        try {
            if (destination.exists() && !destination.delete()) throw IllegalStateException("Tidak dapat mengosongkan tujuan.")
            if (!staged.renameTo(destination)) {
                staged.copyTo(destination, overwrite = true)
                staged.delete()
            }
            if (!isReadableAudioFile(destination)) throw IllegalStateException("File promoted gagal validasi.")
            runCatching { backup.delete() }
        } catch (e: Exception) {
            runCatching { destination.delete() }
            if (hadDestination && backup.exists()) {
                if (!backup.renameTo(destination)) runCatching { backup.copyTo(destination, overwrite = true) }
            }
            throw e
        }
    }

    /**
     * Validates a file by opening its container and confirming a readable audio track
     * with at least one sample. This is intentionally stricter than checking file size.
     */
    fun isReadableAudioFile(file: File): Boolean {
        if (!file.exists() || file.length() <= 256L) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = findAudioTrackIndex(extractor)
            if (trackIndex < 0) return false
            extractor.selectTrack(trackIndex)
            val buffer = ByteBuffer.allocate(64 * 1024)
            extractor.readSampleData(buffer, 0) > 0
        } catch (e: Exception) {
            Log.w(TAG, "Audio validation failed for ${file.name}: ${e.message}")
            false
        } finally {
            runCatching { extractor.release() }
        }
    }

    fun detectDurationSeconds(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            (durMs / 1000L).coerceAtLeast(0L)
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun cleanupTempChunkDir(context: Context, sessionId: String) {
        if (!isSafeSessionId(sessionId)) return
        val base = File(context.cacheDir, "transcription_chunks")
        val dir = File(base, sessionId)
        val baseCanonical = runCatching { base.canonicalFile }.getOrNull() ?: return
        val dirCanonical = runCatching { dir.canonicalFile }.getOrNull() ?: return
        if (dirCanonical.parentFile == baseCanonical && dir.isDirectory) runCatching { dir.deleteRecursively() }
    }

    private fun isSafeSessionId(sessionId: String): Boolean =
        sessionId.matches(Regex("[A-Za-z0-9_-]{1,128}"))

    private fun findAudioTrackIndex(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }
}
