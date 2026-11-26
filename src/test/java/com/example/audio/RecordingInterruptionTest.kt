package com.example.audio

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingInterruptionTest {
    @Test fun ringingAndCallModesBlockCaptureUntilNormalReturns() {
        val callModes = listOf(AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)
        callModes.forEach { assertEquals(RecordingInterruption.CALL, RecordingInterruption.detect(it, false, false)) }
        assertNull(RecordingInterruption.detect(AudioManager.MODE_NORMAL, false, false))
    }

    @Test fun normalModeAloneDoesNotAllowCaptureWhenMicrophoneIsSilent() {
        assertEquals(RecordingInterruption.MICROPHONE, RecordingInterruption.detect(AudioManager.MODE_NORMAL, true, false))
        assertEquals(RecordingInterruption.MICROPHONE, RecordingInterruption.detect(AudioManager.MODE_NORMAL, false, true))
        assertNull(RecordingInterruption.detect(AudioManager.MODE_NORMAL, false, false))
    }
}
