package com.example

import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.audio.AudioSegmenter
import com.example.audio.MeetingRecordingService
import com.example.audio.RecordingStateRepository
import com.example.audio.RecordingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingCallInstrumentedTest {
    @Test fun incomingEmulatorCallPausesAndManualResumePreservesAudio() {
        // The host drives `adb emu gsm call` and `adb emu gsm cancel` at the log markers.
        assumeTrue(InstrumentationRegistry.getArguments().getString("exerciseEmulatorCall") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { MeetingRecordingService.start(it, "emulator-call-test") }
                await { RecordingStateRepository.state.value.status == RecordingStatus.RECORDING }
                await { RecordingStateRepository.state.value.elapsedSeconds >= 2 }
                Log.i("Stage2CallTest", "waiting-for-incoming-call")

                await { RecordingStateRepository.state.value.status == RecordingStatus.PAUSED_INTERRUPTED }
                val paused = RecordingStateRepository.state.value
                instrumentation.runOnMainSync { MeetingRecordingService.resume(context) }
                SystemClock.sleep(350)
                assertEquals(RecordingStatus.PAUSED_INTERRUPTED, RecordingStateRepository.state.value.status)
                assertEquals(paused.elapsedSeconds, RecordingStateRepository.state.value.elapsedSeconds)
                Log.i("Stage2CallTest", "waiting-for-call-end")

                await { audio.mode == AudioManager.MODE_NORMAL }
                instrumentation.runOnMainSync { MeetingRecordingService.resume(context) }
                await { RecordingStateRepository.state.value.status == RecordingStatus.RECORDING }
                await { RecordingStateRepository.state.value.elapsedSeconds >= paused.elapsedSeconds + 2 }
                instrumentation.runOnMainSync { MeetingRecordingService.stop(context) }
                await { RecordingStateRepository.state.value.completedRecording != null }

                val completed = requireNotNull(RecordingStateRepository.state.value.completedRecording)
                assertTrue(completed.file.isFile)
                assertTrue(completed.file.length() > 0L)
                assertTrue(AudioSegmenter.detectDurationSeconds(completed.file) >= 4L)
                assertEquals(AudioSegmenter.detectDurationSeconds(completed.file), completed.durationSeconds)
                completed.file.delete()
            } finally {
                if (RecordingStateRepository.state.value.isSessionActive) {
                    instrumentation.runOnMainSync { MeetingRecordingService.stop(context) }
                }
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50L)
        assertTrue("Expected service state before timeout: ${RecordingStateRepository.state.value}", condition())
    }
}
