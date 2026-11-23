package com.example.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer

enum class WavValidationStatus { VALID_READABLE, INVALID, UNREADABLE }

data class WavPlaybackValidationResult(
    val status: WavValidationStatus,
    val mimeType: String?,
    val sampleRateHz: Int?,
    val channelCount: Int?,
    val durationUs: Long?,
    val firstSampleReadable: Boolean,
    val fileSizeBytes: Long,
    val issues: List<String>
)

/** Read-only container/track probe. Does not play audio or guarantee audible output. */
class ExperimentalWavPlaybackValidator {
    fun validate(file: File): WavPlaybackValidationResult {
        val issues = mutableListOf<String>()
        if (!file.isFile || file.length() <= 44L) {
            return WavPlaybackValidationResult(WavValidationStatus.INVALID,null,null,null,null,false,file.length(),listOf("File tidak ada atau tidak berisi data WAV."))
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var audioIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true) {
                    audioIndex=i;format=candidate;break
                }
            }
            if (audioIndex < 0 || format == null) {
                issues += "Tidak ditemukan track audio."
                return result(WavValidationStatus.INVALID,null,null,null,null,false,file,issues)
            }
            extractor.selectTrack(audioIndex)
            val mime=format.getString(MediaFormat.KEY_MIME)
            val rate=runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrNull()
            val channels=runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrNull()
            val duration=runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrNull()
            val buffer=ByteBuffer.allocate(64*1024)
            val sampleReadable=runCatching { extractor.readSampleData(buffer,0)>0 }.getOrDefault(false)
            if (!sampleReadable) issues += "Sample audio pertama tidak dapat dibaca."
            if (rate==null || rate<=0) issues += "Sample rate tidak valid atau tidak tersedia."
            if (channels==null || channels<=0) issues += "Channel count tidak valid atau tidak tersedia."
            if (duration==null || duration<=0L) issues += "Durasi track tidak tersedia."
            val status=if(sampleReadable && rate!=null && rate>0 && channels!=null && channels>0 && duration!=null && duration>0) WavValidationStatus.VALID_READABLE else WavValidationStatus.INVALID
            return result(status,mime,rate,channels,duration,sampleReadable,file,issues)
        } catch(e: Exception) {
            issues += "MediaExtractor gagal membaca file: ${e.message ?: e.javaClass.simpleName}"
            return result(WavValidationStatus.UNREADABLE,null,null,null,null,false,file,issues)
        } finally { runCatching { extractor.release() } }
    }
    private fun result(status:WavValidationStatus,mime:String?,rate:Int?,channels:Int?,duration:Long?,sample:Boolean,file:File,issues:List<String>) =
        WavPlaybackValidationResult(status,mime,rate,channels,duration,sample,file.length(),issues)
}
