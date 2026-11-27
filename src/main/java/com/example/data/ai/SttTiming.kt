package com.example.data.ai

import com.example.data.model.TranscriptSegment
import org.json.JSONObject

/** The encoded file's duration and its first second on the original recording timeline. */
data class SttAudioTiming(val startOffsetSec: Long, val durationMs: Long)

internal data class SttSegment(
    val speaker: String,
    val startMs: Double,
    val endMs: Double,
    val text: String
)

internal enum class SttTimeline { CHUNK, RECORDING }

private val decimalTimestamp = Regex("""[0-9]+(?:[.,][0-9]+)?""")
private val clockTimestamp = Regex("""(?:[0-9]+:)?[0-9]+:[0-9]{2}(?:[.,][0-9]+)?""")

internal fun JSONObject.sttTimestampMs(key: String, milliseconds: Boolean = false): Double {
    val scale = if (milliseconds) 1.0 else 1000.0
    val value = when (val field = opt(key)) {
        is Number -> field.toDouble() * scale
        is String -> {
            val input = field.trim().replace(',', '.')
            when {
                decimalTimestamp.matches(input) -> input.toDouble() * scale
                !milliseconds && clockTimestamp.matches(input) -> {
                    val parts = input.split(':').map(String::toDouble)
                    require(parts.last() < 60 && (parts.size == 2 || parts[1] < 60)) {
                        "Format waktu transkripsi tidak valid."
                    }
                    parts.fold(0.0) { total, part -> total * 60 + part } * 1000
                }
                else -> Double.NaN
            }
        }
        else -> Double.NaN
    }
    require(value.isFinite() && value >= 0 && value < Long.MAX_VALUE.toDouble()) {
        "Stempel waktu transkripsi tidak valid."
    }
    return value
}

internal fun normalizeSttSegments(
    segments: List<SttSegment>,
    timing: SttAudioTiming?,
    timeline: SttTimeline? = null
): List<TranscriptSegment> {
    require(segments.isNotEmpty()) { "Respons transkripsi tidak memuat ucapan." }
    require(segments.all { it.text.isNotBlank() && it.startMs >= 0 && it.endMs >= it.startMs }) {
        "Segmen transkripsi berisi teks atau stempel waktu yang tidak valid."
    }
    val offset = if (timing == null) {
        require(timeline != SttTimeline.RECORDING) { "Offset rekaman untuk transkripsi tidak tersedia." }
        0.0
    } else {
        require(timing.startOffsetSec >= 0 && timing.durationMs > 0)
        val recordingOffset = timing.startOffsetSec.toDouble() * 1000
        // Whole-second model output may round the final endpoint up. Accept at most
        // one second and clamp only the endpoint. Never move a start outside the file.
        fun fits(origin: Double): Boolean = segments.all {
            it.startMs - origin in 0.0..timing.durationMs.toDouble() &&
                it.endMs - origin <= timing.durationMs + 1000.0
        }
        when {
            timeline != SttTimeline.RECORDING && fits(0.0) -> 0.0
            timeline != SttTimeline.CHUNK && fits(recordingOffset) -> recordingOffset
            else -> error("Stempel waktu transkripsi berada di luar rentang bagian audio.")
        }
    }
    return segments.sortedBy { it.startMs }.map {
        val end = timing?.let { t -> (it.endMs - offset).coerceAtMost(t.durationMs.toDouble()) } ?: (it.endMs - offset)
        TranscriptSegment(it.speaker, ((it.startMs - offset) / 1000).toLong(), (end / 1000).toLong(), it.text)
    }
}

internal fun parseSttJson(
    rawJsonText: String,
    preferredLanguage: String,
    timing: SttAudioTiming? = null
): Pair<String, List<TranscriptSegment>> {
    val json = JSONObject(CustomProviderClient.extractJsonObject(rawJsonText))
    val language = json.optString("bahasa", preferredLanguage).ifBlank { preferredLanguage }
    val timeline = when (json.optString("timestampReference")) {
        "" -> null
        "chunk" -> SttTimeline.CHUNK
        "recording" -> SttTimeline.RECORDING
        else -> error("Acuan waktu transkripsi tidak dikenal.")
    }
    val milliseconds = when (json.optString("timestampUnit")) {
        "", "seconds", "s" -> false
        "milliseconds", "ms" -> true
        else -> error("Satuan waktu transkripsi tidak dikenal.")
    }
    val rows = json.optJSONArray("segmen") ?: throw BackendAiException(
        "AUDIO_TRANSCRIPTION_INVALID_OUTPUT",
        "AUDIO_TRANSCRIPTION_INVALID_OUTPUT",
        "Respons transkripsi tidak memuat daftar segmen yang valid."
    )
    val segments = List(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        SttSegment(
            row.optString("pembicara").ifBlank { "Pembicara 1" },
            row.sttTimestampMs(if (row.has("mulaiDetik")) "mulaiDetik" else "mulai_detik", milliseconds),
            row.sttTimestampMs(if (row.has("selesaiDetik")) "selesaiDetik" else "selesai_detik", milliseconds),
            (row.opt("teks") as? String).orEmpty().trim()
        )
    }
    return language to normalizeSttSegments(segments, timing, timeline)
}
