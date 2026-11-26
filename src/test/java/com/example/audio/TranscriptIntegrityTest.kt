package com.example.audio

import com.example.data.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptIntegrityTest {
    @Test fun mergeTrimsOnlyOverlappingTailAcrossChunksWithUnicode() {
        val merged = AudioSegmenter.mergeChunkTranscripts(listOf(
            0L to listOf(TranscriptSegment("A", 0, 12, "Kita bahas café à Jakarta.")),
            10L to listOf(TranscriptSegment("A", 0, 5, "cafe\u0301 à Jakarta, besok pagi."))
        ))
        assertEquals(listOf("Kita bahas café à Jakarta.", "besok pagi."), merged.map { it.teks })
        assertEquals(10L, merged.last().mulaiDetik)
    }

    @Test fun mergeKeepsRepetitionWithinChunkAcrossSpeakersAndOutsideOverlap() {
        val words = "Setuju kita lanjut"
        val merged = AudioSegmenter.mergeChunkTranscripts(listOf(
            0L to listOf(TranscriptSegment("A", 0, 3, words), TranscriptSegment("A", 2, 4, words)),
            3L to listOf(TranscriptSegment("B", 0, 2, words), TranscriptSegment("A", 2, 4, words))
        ))
        assertEquals(listOf("A", "A", "B", "A"), merged.map { it.pembicara })
        assertEquals(List(4) { words }, merged.map { it.teks })
    }

    @Test fun overlappingSubstringAndShortRepetitionArePreserved() {
        val merged = AudioSegmenter.mergeChunkTranscripts(listOf(
            0L to listOf(TranscriptSegment("A", 4, 10, "Setuju"), TranscriptSegment("A", 5, 10, "Silakan berangkat")),
            8L to listOf(TranscriptSegment("A", 0, 3, "Setuju"), TranscriptSegment("A", 1, 4, "angkat barang"))
        ))
        assertEquals(listOf("Setuju", "Silakan berangkat", "Setuju", "angkat barang"), merged.map { it.teks })
    }

    @Test fun exactBoundaryDuplicateKeepsOneRowAndMalformedTimestampsAreRejected() {
        val duplicate = "Tolong kirim besok"
        val merged = AudioSegmenter.mergeChunkTranscripts(listOf(
            0L to listOf(TranscriptSegment("A", 5, 10, duplicate)),
            8L to listOf(TranscriptSegment("A", 0, 3, duplicate), TranscriptSegment("A", 5, 1, "invalid")),
            Long.MAX_VALUE to listOf(TranscriptSegment("A", 0, 1, "overflow"))
        ))
        assertEquals(listOf(TranscriptSegment("A", 5, 11, duplicate)), merged)
    }
}
