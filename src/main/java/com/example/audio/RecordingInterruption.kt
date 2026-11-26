package com.example.audio

import android.media.AudioManager

internal enum class RecordingInterruption(val pauseMessage: String) {
    CALL("Panggilan terdeteksi. Rekaman dijeda. Setelah panggilan selesai, ketuk Lanjut."),
    MICROPHONE("Android membisukan mikrofon. Rekaman dijeda. Saat mikrofon tersedia, ketuk Lanjut.");

    companion object {
        fun detect(audioMode: Int, microphoneMuted: Boolean, recorderSilenced: Boolean): RecordingInterruption? = when {
            audioMode == AudioManager.MODE_IN_CALL || audioMode == AudioManager.MODE_IN_COMMUNICATION ||
                audioMode == AudioManager.MODE_RINGTONE -> CALL
            microphoneMuted || recorderSilenced -> MICROPHONE
            else -> null
        }
    }
}
