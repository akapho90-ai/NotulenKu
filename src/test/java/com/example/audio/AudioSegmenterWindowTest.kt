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
 @Test fun plannerFinalWindowWithinRequestedDuration() {
  val windows=AudioSegmenter.planChunkWindows(181,180,2)
  assertEquals(2,windows.size); assertEquals(181L,windows.last().endSec)
 }
}
