package com.example.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSegmenterWindowTest {
 @Test fun shortAudioSingleWindow() {
  val windows = AudioSegmenter.planChunkWindows(90)
  assertEquals(1, windows.size); assertEquals(0L, windows.first().startSec); assertEquals(90L, windows.first().endSec)
 }
 @Test fun longAudioOverlappingWindowsCoverEnd() {
  val windows = AudioSegmenter.planChunkWindows(7200,180,2)
  assertTrue(windows.size > 30); assertEquals(0L,windows.first().startSec); assertEquals(7200L,windows.last().endSec)
  windows.zipWithNext().forEach { (a,b) -> assertTrue(b.startSec < a.endSec); assertEquals(2L,a.endSec-b.startSec) }
 }
 @Test fun unicodeOverlapPrefixIsTrimmed() {
  assertEquals("lanjut agenda", AudioSegmenter.trimOverlappingPrefix("Bahas agenda selanjutnya", "agenda selanjutnya lanjut agenda"))
 }
 @Test fun repeatedBoundaryPhraseIsRecognizedAcrossUnicodeWords() {
  val prev = com.example.data.model.TranscriptSegment(pembicara = "Pembicara 1", mulaiDetik = 1L, selesaiDetik = 4L, teks = "bahas jadwal rapat besok")
  val next = prev.copy(mulaiDetik = 3L, selesaiDetik = 5L, teks = "bahas jadwal rapat besok")
  assertTrue(AudioSegmenter.isBoundaryDuplicate(prev, next))
 }
 @Test fun plannerFinalWindowWithinRequestedDuration() {
  val windows=AudioSegmenter.planChunkWindows(181,180,2)
  assertEquals(2,windows.size); assertEquals(181L,windows.last().endSec)
 }
}
