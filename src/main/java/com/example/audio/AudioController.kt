package com.example.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

private const val TAG = "AudioController"

data class RecordedAudioFile(
    val file: File,
    val durationSeconds: Long,
    val sizeBytes: Long,
    val mimeType: String
)

data class ImportedAudioInfo(
    val uri: Uri,
    val fileName: String,
    val sizeBytes: Long,
    val durationSeconds: Long,
    val mimeType: String
)

class MeetingAudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var timerJob: Job? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private val _amplitudeHistory = MutableStateFlow<List<Float>>(List(32) { 0.08f })
    val amplitudeHistory: StateFlow<List<Float>> = _amplitudeHistory.asStateFlow()

    fun startRecording(scope: CoroutineScope): Result<Unit> {
        return try {
            stopAndCleanup()
            val dir = File(context.filesDir, "recordings").apply { mkdirs() }
            val file = File(dir, "rec_${UUID.randomUUID().toString().take(10)}.m4a")
            outputFile = file

            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(16000)
            mr.setAudioEncodingBitRate(32000)
            mr.setAudioChannels(1)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()

            recorder = mr
            _isRecording.value = true
            _isPaused.value = false
            _elapsedSeconds.value = 0L
            _amplitudeHistory.value = List(32) { 0.08f }

            timerJob = scope.launch(Dispatchers.Default) {
                var ticks = 0
                while (isActive && _isRecording.value) {
                    delay(125L)
                    if (!_isPaused.value) {
                        ticks++
                        if (ticks % 8 == 0) {
                            _elapsedSeconds.value += 1L
                        }
                        val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                        val normalized = (amp / 18000f).coerceIn(0.08f, 1.0f)
                        _amplitudeHistory.value = (_amplitudeHistory.value.drop(1) + normalized)
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            stopAndCleanup()
            Result.failure(e)
        }
    }

    fun togglePause(): Boolean {
        val mr = recorder ?: return false
        return try {
            if (_isPaused.value) {
                mr.resume()
                _isPaused.value = false
            } else {
                mr.pause()
                _isPaused.value = true
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Pause/resume failed", e)
            false
        }
    }

    fun stopRecording(): RecordedAudioFile? {
        val file = outputFile
        val duration = _elapsedSeconds.value.coerceAtLeast(1L)
        timerJob?.cancel()
        timerJob = null
        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "MediaRecorder stop warning: ${e.message}")
        }
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        _isRecording.value = false
        _isPaused.value = false

        return if (file != null && file.exists() && file.length() > 0L) {
            RecordedAudioFile(
                file = file,
                durationSeconds = duration,
                sizeBytes = file.length(),
                mimeType = "audio/mp4"
            )
        } else {
            null
        }
    }

    fun stopAndCleanup() {
        timerJob?.cancel()
        timerJob = null
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        _isRecording.value = false
        _isPaused.value = false
    }
}

class MeetingAudioPlayer {
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionSec = MutableStateFlow(0L)
    val currentPositionSec: StateFlow<Long> = _currentPositionSec.asStateFlow()

    private val _durationSec = MutableStateFlow(0L)
    val durationSec: StateFlow<Long> = _durationSec.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private var loadedPath: String? = null

    fun prepare(path: String, scope: CoroutineScope): Boolean {
        if (loadedPath == path && mediaPlayer != null) return true
        release()
        val file = File(path)
        if (!file.exists()) return false
        return try {
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    _isPlaying.value = false
                    _currentPositionSec.value = 0L
                }
            }
            mediaPlayer = mp
            loadedPath = path
            _durationSec.value = (mp.duration / 1000L).coerceAtLeast(1L)
            _currentPositionSec.value = 0L
            progressJob = scope.launch {
                while (isActive) {
                    delay(350L)
                    mediaPlayer?.let { player ->
                        if (player.isPlaying) {
                            _currentPositionSec.value = (player.currentPosition / 1000L).coerceAtLeast(0L)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Player prepare failed", e)
            release()
            false
        }
    }

    fun togglePlay() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            _isPlaying.value = false
        } else {
            mp.start()
            _isPlaying.value = true
        }
    }

    fun seekToSeconds(seconds: Long) {
        val mp = mediaPlayer ?: return
        val ms = (seconds * 1000L).toInt().coerceIn(0, mp.duration)
        mp.seekTo(ms)
        _currentPositionSec.value = seconds.coerceAtLeast(0L)
    }

    fun setSpeed(speed: Float) {
        _playbackSpeed.value = speed
        val mp = mediaPlayer ?: return
        runCatching {
            val wasPlaying = mp.isPlaying
            mp.playbackParams = PlaybackParams().setSpeed(speed)
            if (!wasPlaying) mp.pause()
        }
    }

    fun release() {
        progressJob?.cancel()
        progressJob = null
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        loadedPath = null
        _isPlaying.value = false
        _currentPositionSec.value = 0L
    }
}

object AudioFileHelper {
    fun inspectUri(context: Context, uri: Uri): ImportedAudioInfo? {
        return try {
            var name = "audio_rapat.m4a"
            var size = 0L
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
            val retriever = MediaMetadataRetriever()
            var durationSec = 0L
            try {
                retriever.setDataSource(context, uri)
                val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                durationSec = (durMs / 1000L).coerceAtLeast(1L)
            } finally {
                runCatching { retriever.release() }
            }
            val mime = context.contentResolver.getType(uri) ?: guessMimeFromName(name)
            ImportedAudioInfo(uri, name, size, durationSec, mime)
        } catch (e: Exception) {
            Log.e(TAG, "Inspect audio URI failed", e)
            null
        }
    }

    fun copyUriToInternalFile(context: Context, uri: Uri, fileName: String): File? {
        return try {
            val dir = File(context.filesDir, "recordings").apply { mkdirs() }
            val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val dest = File(dir, "imp_${UUID.randomUUID().toString().take(8)}_$safeName")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            dest
        } catch (e: Exception) {
            Log.e(TAG, "Copy URI failed", e)
            null
        }
    }

    private fun guessMimeFromName(name: String): String = when {
        name.endsWith(".mp3", true) -> "audio/mpeg"
        name.endsWith(".wav", true) -> "audio/wav"
        name.endsWith(".ogg", true) -> "audio/ogg"
        name.endsWith(".webm", true) -> "audio/webm"
        name.endsWith(".aac", true) -> "audio/aac"
        else -> "audio/mp4"
    }
}
