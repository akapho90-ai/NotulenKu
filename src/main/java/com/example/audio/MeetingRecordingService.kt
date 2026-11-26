package com.example.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.notulenrapat.android.R
import com.example.export.ExportUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

private const val TAG = "MeetingRecordingService"
private const val CHANNEL_ID = "notulenku_recording_channel"
private const val NOTIFICATION_ID = 4201
private const val SEGMENT_ROTATION_SEC = 900L // 15 minutes per safe segment for >2-hour durability
private const val MIN_FREE_STORAGE_BYTES = 25L * 1024L * 1024L // 25 MB safety floor

class MeetingRecordingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager

    private var recorder: MediaRecorder? = null
    private var currentSegmentFile: File? = null
    private var currentSegmentStartElapsedSec: Long = 0L
    private var currentSegmentClockStartMs: Long = 0L
    private var nextSegmentIndex = 0
    private var sessionId: String = ""
    private var ownerUserId: String = ""
    private var sessionCreatedAtMs: Long = 0L
    private val completedSegmentFiles = mutableListOf<File>()

    private var tickerJob: Job? = null
    private var audioRecordingCallback: AudioManager.AudioRecordingCallback? = null
    private var noisyReceiverRegistered = false

    private val audioDeviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (RecordingStateRepository.state.value.status != RecordingStatus.RECORDING) return
            when (intent?.action) {
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    pauseDueToInterruption(
                        "Perangkat audio eksternal/headset terputus. Rekaman dijeda aman — ketuk Lanjut untuk merekam lewat mikrofon HP.",
                        micSilenced = false
                    )
                }
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                    val prev = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_PREVIOUS_STATE, -1)
                    if (prev == AudioManager.SCO_AUDIO_STATE_CONNECTED && state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                        pauseDueToInterruption(
                            "Koneksi mikrofon Bluetooth terputus. Rekaman dijeda aman — ketuk Lanjut untuk melanjutkan.",
                            micSilenced = false
                        )
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val uid = intent.getStringExtra(EXTRA_OWNER_UID).orEmpty()
                handleStart(uid)
            }
            ACTION_PAUSE -> handleUserPause()
            ACTION_RESUME -> handleResume()
            ACTION_STOP -> handleStop()
            else -> {
                if (!RecordingStateRepository.state.value.isSessionActive) {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(uid: String) {
        if (RecordingStateRepository.state.value.isSessionActive) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            RecordingStateRepository.update {
                it.copy(lastError = "Izin mikrofon belum diberikan.")
            }
            stopSelf()
            return
        }

        if (!hasEnoughStorage()) {
            RecordingStateRepository.update {
                it.copy(lastError = "Ruang penyimpanan perangkat kurang dari 25 MB. Kosongkan penyimpanan sebelum merekam.")
            }
            stopSelf()
            return
        }

        val interruption = currentInterruption()
        if (interruption != null) {
            RecordingStateRepository.update {
                it.copy(lastError = if (interruption == RecordingInterruption.CALL) {
                    "Panggilan terdeteksi. Selesaikan panggilan sebelum mulai merekam."
                } else "Mikrofon sedang dibisukan oleh Android.")
            }
            stopSelf()
            return
        }

        sessionId = UUID.randomUUID().toString().replace("-", "").take(14)
        ownerUserId = uid.ifBlank { "local" }
        sessionCreatedAtMs = System.currentTimeMillis()
        completedSegmentFiles.clear()
        nextSegmentIndex = 0
        currentSegmentStartElapsedSec = 0L

        try {
            startForegroundSafely(buildNotification(RecordingStatus.IDLE, 0L, null))
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not start microphone foreground service", error)
            RecordingStateRepository.update { it.copy(lastError = "Buka NotulenKu untuk mulai merekam.") }
            stopSelf()
            return
        }
        registerSystemAudioMonitors()

        val started = startNewRecorderSegment()
        if (!started) {
            cleanupMonitors()
            RecordingStateRepository.update {
                it.copy(
                    status = RecordingStatus.IDLE,
                    lastError = "Gagal mengakses mikrofon. Pastikan tidak ada panggilan atau aplikasi lain yang memakai mikrofon."
                )
            }
            stopForegroundCompat()
            stopSelf()
            return
        }

        RecordingStateRepository.update {
            ActiveRecordingState(
                sessionId = sessionId,
                ownerUserId = ownerUserId,
                status = RecordingStatus.RECORDING,
                elapsedSeconds = 0L,
                amplitudeHistory = List(32) { 0.08f },
                isMicMutedBySystem = false,
                pauseReason = null,
                segmentCount = 1,
                completedRecording = null,
                lastError = null
            )
        }
        persistJournal()
        updateNotificationNow()
        startTicker()
    }

    private fun handleUserPause() {
        val st = RecordingStateRepository.state.value
        if (st.status != RecordingStatus.RECORDING) return
        finalizeCurrentSegmentSafely()
        tickerJob?.cancel()
        RecordingStateRepository.update {
            it.copy(
                status = RecordingStatus.PAUSED_USER,
                isMicMutedBySystem = false,
                pauseReason = "Rekaman dijeda oleh Anda. Ketuk Lanjut untuk meneruskan."
            )
        }
        persistJournal()
        updateNotificationNow()
    }

    private fun pauseDueToInterruption(reason: String, micSilenced: Boolean) {
        val st = RecordingStateRepository.state.value
        if (st.status != RecordingStatus.RECORDING) return
        tickerJob?.cancel()
        RecordingStateRepository.update {
            it.copy(
                status = RecordingStatus.PAUSED_INTERRUPTED,
                isMicMutedBySystem = micSilenced,
                pauseReason = reason
            )
        }
        finalizeCurrentSegmentSafely()
        persistJournal()
        updateNotificationNow()
    }

    private fun handleResume() {
        val st = RecordingStateRepository.state.value
        if (!st.isPaused) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            RecordingStateRepository.update {
                it.copy(lastError = "Izin mikrofon dicabut. Aktifkan kembali izin mikrofon di Pengaturan.")
            }
            return
        }
        if (!hasEnoughStorage()) {
            RecordingStateRepository.update {
                it.copy(lastError = "Penyimpanan hampir penuh (<25 MB). Kosongkan ruang sebelum melanjutkan.")
            }
            return
        }
        val interruption = currentInterruption()
        if (interruption != null) {
            RecordingStateRepository.update {
                it.copy(
                    status = RecordingStatus.PAUSED_INTERRUPTED,
                    isMicMutedBySystem = true,
                    pauseReason = interruption.pauseMessage
                )
            }
            updateNotificationNow()
            return
        }

        val ok = startNewRecorderSegment()
        if (!ok) {
            RecordingStateRepository.update {
                it.copy(
                    status = RecordingStatus.PAUSED_INTERRUPTED,
                    isMicMutedBySystem = true,
                    pauseReason = "Mikrofon masih sibuk atau dibisukan sistem. Coba ketuk Lanjut sesaat lagi."
                )
            }
            updateNotificationNow()
            return
        }

        currentSegmentStartElapsedSec = st.elapsedSeconds
        RecordingStateRepository.update {
            it.copy(
                status = RecordingStatus.RECORDING,
                isMicMutedBySystem = false,
                pauseReason = null,
                segmentCount = completedSegmentFiles.size + 1,
                lastError = null
            )
        }
        persistJournal()
        updateNotificationNow()
        startTicker()
    }

    private fun handleStop() {
        val st = RecordingStateRepository.state.value
        if (st.status == RecordingStatus.STOPPING) return
        if (!st.isSessionActive) {
            stopSelf()
            return
        }

        tickerJob?.cancel()
        RecordingStateRepository.update { it.copy(status = RecordingStatus.STOPPING) }
        finalizeCurrentSegmentSafely()
        persistJournal()
        cleanupMonitors()

        val finalElapsed = st.elapsedSeconds.coerceAtLeast(1L)
        val capturedSegments = completedSegmentFiles.toList()
        val activeSessionId = sessionId

        serviceScope.launch(Dispatchers.IO) {
            if (capturedSegments.isEmpty()) {
                RecordingStateRepository.deleteSessionJournal(applicationContext, activeSessionId)
                RecordingStateRepository.update {
                    ActiveRecordingState(
                        status = RecordingStatus.IDLE,
                        lastError = "Tidak ada data suara yang berhasil direkam."
                    )
                }
            } else {
                val mergeResult = runCatching {
                    val identity = RecordingFileIdentity.next(applicationContext)
                    RecordingFileIdentity.saveMaster(applicationContext, capturedSegments, identity.fileName).getOrThrow()
                }
                ensureActive()
                mergeResult.onSuccess { merged ->
                    val detectedDur = AudioSegmenter.detectDurationSeconds(merged).takeIf { it > 0L } ?: finalElapsed
                    // Delete raw segment parts ONLY after merged file is confirmed valid
                    if (merged.exists() && merged.length() > 256L) {
                        capturedSegments.filter { it.absolutePath != merged.absolutePath }
                            .forEach { runCatching { it.delete() } }
                        RecordingStateRepository.deleteSessionJournal(applicationContext, activeSessionId)
                    }
                    RecordingStateRepository.update {
                        ActiveRecordingState(
                            status = RecordingStatus.IDLE,
                            elapsedSeconds = detectedDur,
                            completedRecording = RecordedAudioFile(
                                file = merged,
                                durationSeconds = detectedDur,
                                sizeBytes = merged.length(),
                                mimeType = "audio/mp4"
                            )
                        )
                    }
                }.onFailure { err ->
                    // NEVER delete segments if merge fails — keep journal intact so recovery can salvage them!
                    Log.e(TAG, "Merge failed on stop; preserving all raw segments for recovery", err)
                    val fallbackSeg = capturedSegments.maxByOrNull { it.length() }
                    val fallbackDuration = fallbackSeg?.let(AudioSegmenter::detectDurationSeconds) ?: 0L
                    if (fallbackSeg != null && capturedSegments.size == 1 && fallbackDuration > 0L) {
                        val detectedDur = fallbackDuration
                        RecordingStateRepository.deleteSessionJournal(applicationContext, activeSessionId)
                        RecordingStateRepository.update {
                            ActiveRecordingState(
                                status = RecordingStatus.IDLE,
                                elapsedSeconds = detectedDur,
                                completedRecording = RecordedAudioFile(
                                    file = fallbackSeg,
                                    durationSeconds = detectedDur,
                                    sizeBytes = fallbackSeg.length(),
                                    mimeType = "audio/mp4"
                                )
                            )
                        }
                    } else {
                        persistJournal()
                        RecordingStateRepository.update {
                            ActiveRecordingState(
                                status = RecordingStatus.IDLE,
                                lastError = "Penggabungan segmen gagal (${err.localizedMessage ?: "error"}), tetapi semua bagian audio tetap aman di perangkat dan dapat dipulihkan."
                            )
                        }
                    }
                }
            }

            serviceScope.launch(Dispatchers.Main.immediate) {
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private fun startNewRecorderSegment(): Boolean {
        var candidate: MediaRecorder? = null
        var candidateFile: File? = null
        var started = false
        return try {
            val segDir = File(filesDir, "recordings/parts_$sessionId")
            check(segDir.isDirectory || segDir.mkdirs()) { "Folder segmen rekaman tidak tersedia." }
            val segIndex = nextSegmentIndex++
            val file = File(segDir, "part_${segIndex.toString().padStart(4, '0')}.m4a")
            candidateFile = file

            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            candidate = mr
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(16000)
            mr.setAudioEncodingBitRate(32000)
            mr.setAudioChannels(1)
            mr.setOutputFile(file.absolutePath)
            mr.setOnErrorListener { _, what, extra ->
                if (recorder !== mr) return@setOnErrorListener
                Log.e(TAG, "MediaRecorder error what=$what extra=$extra")
                pauseDueToInterruption(
                    "Terjadi gangguan mikrofon perangkat (kode $what). Bagian sebelumnya telah diamankan.",
                    micSilenced = true
                )
            }
            mr.setOnInfoListener { _, what, _ ->
                if (recorder !== mr) return@setOnInfoListener
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                ) {
                    rotateSegmentSafely()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val callback = object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                        if (recorder !== mr) return
                        val interruption = currentInterruption(mr, configs?.any { it.isClientSilenced } == true)
                        interruption?.let { pauseDueToInterruption(it.pauseMessage, micSilenced = true) }
                    }
                }
                runCatching { mr.registerAudioRecordingCallback(mainExecutor, callback) }
                    .onSuccess { audioRecordingCallback = callback }
                    .onFailure { Log.w(TAG, "Recording-specific callback unavailable", it) }
            }
            mr.prepare()
            mr.start()
            started = true
            check(currentInterruption(mr) == null) { "Mikrofon belum tersedia untuk merekam." }
            recorder = mr
            currentSegmentFile = file
            currentSegmentClockStartMs = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            Log.e(TAG, "startNewRecorderSegment failed", e)
            candidate?.let { failed ->
                unregisterRecorderCallback(failed)
                if (started) runCatching { failed.stop() }
                runCatching { failed.release() }
            }
            candidateFile?.let { if (it.length() == 0L) it.delete() }
            recorder = null
            currentSegmentFile = null
            false
        }
    }

    /**
     * Finalizes the active MediaRecorder segment safely.
     * NEVER deletes prior valid segments if MediaRecorder.stop() throws.
     */
    private fun finalizeCurrentSegmentSafely() {
        val mr = recorder
        val file = currentSegmentFile
        recorder = null
        currentSegmentFile = null
        if (mr != null) {
            unregisterRecorderCallback(mr)
            try {
                mr.stop()
            } catch (e: Exception) {
                Log.w(TAG, "MediaRecorder stop warning (checking file recoverability): ${e.message}")
            }
            runCatching { mr.release() }
        }
        if (mr != null && file != null) {
            // Keep the expected part so finalization can detect missing or unreadable audio.
            if (!completedSegmentFiles.any { it.absolutePath == file.absolutePath }) {
                completedSegmentFiles.add(file)
            }
        }
    }

    private fun rotateSegmentSafely() {
        val st = RecordingStateRepository.state.value
        if (st.status != RecordingStatus.RECORDING) return
        finalizeCurrentSegmentSafely()
        persistJournal()
        if (!hasEnoughStorage()) {
            pauseDueToInterruption(
                "Penyimpanan perangkat hampir penuh (<25 MB). Rekaman dijeda aman agar bagian sebelumnya tersimpan.",
                micSilenced = false
            )
            return
        }
        val started = startNewRecorderSegment()
        if (!started) {
            pauseDueToInterruption(
                "Tidak dapat melanjutkan ke segmen berikutnya. Bagian sebelumnya sudah aman — ketuk Lanjut atau Hentikan.",
                micSilenced = true
            )
            return
        }
        currentSegmentStartElapsedSec = st.elapsedSeconds
        RecordingStateRepository.update { it.copy(segmentCount = completedSegmentFiles.size + 1) }
        persistJournal()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch(Dispatchers.Default) {
            var ticks = 0
            while (isActive && RecordingStateRepository.state.value.status == RecordingStatus.RECORDING) {
                delay(125L)
                val interruption = currentInterruption(recorder)
                if (interruption != null) {
                    pauseDueToInterruption(interruption.pauseMessage, micSilenced = true)
                    break
                }
                ticks++
                val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                val normalized = (amp / 18000f).coerceIn(0.08f, 1.0f)

                val newElapsed = currentSegmentStartElapsedSec +
                    (SystemClock.elapsedRealtime() - currentSegmentClockStartMs).coerceAtLeast(0L) / 1_000L

                RecordingStateRepository.update { cur ->
                    cur.copy(
                        elapsedSeconds = newElapsed,
                        amplitudeHistory = cur.amplitudeHistory.drop(1) + normalized
                    )
                }

                if (ticks % 8 == 0) {
                    if (newElapsed % 15L == 0L) {
                        if (!hasEnoughStorage()) {
                            serviceScope.launch(Dispatchers.Main.immediate) {
                                pauseDueToInterruption(
                                    "Penyimpanan perangkat hampir penuh (<25 MB). Rekaman dijeda aman.",
                                    micSilenced = false
                                )
                            }
                            break
                        }
                        persistJournal()
                    }
                    if (newElapsed - currentSegmentStartElapsedSec >= SEGMENT_ROTATION_SEC) {
                        serviceScope.launch(Dispatchers.Main.immediate) {
                            rotateSegmentSafely()
                        }
                    }
                    if (newElapsed % 5L == 0L) {
                        updateNotificationNow()
                    }
                }
            }
        }
    }

    private fun registerSystemAudioMonitors() {
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        }
        runCatching {
            ContextCompat.registerReceiver(this, audioDeviceReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            noisyReceiverRegistered = true
        }
    }

    private fun unregisterRecorderCallback(owner: MediaRecorder) {
        val callback = audioRecordingCallback ?: return
        audioRecordingCallback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { owner.unregisterAudioRecordingCallback(callback) }
        }
    }

    private fun cleanupMonitors() {
        if (noisyReceiverRegistered) {
            runCatching { unregisterReceiver(audioDeviceReceiver) }
            noisyReceiverRegistered = false
        }
    }

    private fun currentInterruption(owner: MediaRecorder? = null, callbackSilenced: Boolean = false): RecordingInterruption? {
        val mode = runCatching { audioManager.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        val muted = runCatching { audioManager.isMicrophoneMute }.getOrDefault(false)
        val recorderSilenced = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            runCatching { owner?.activeRecordingConfiguration?.isClientSilenced == true }.getOrDefault(false)
        return RecordingInterruption.detect(mode, muted, recorderSilenced || callbackSilenced)
    }

    private fun hasEnoughStorage(): Boolean {
        return runCatching {
            val stat = StatFs(filesDir.absolutePath)
            stat.availableBytes >= MIN_FREE_STORAGE_BYTES
        }.getOrDefault(true)
    }

    private fun persistJournal() {
        val allPaths = buildList {
            completedSegmentFiles.forEach { add(it.absolutePath) }
            currentSegmentFile?.let { f ->
                if (!contains(f.absolutePath)) add(f.absolutePath)
            }
        }
        RecordingStateRepository.saveSessionJournal(
            context = applicationContext,
            sessionId = sessionId,
            ownerUserId = ownerUserId,
            segmentPaths = allPaths,
            elapsedSeconds = RecordingStateRepository.state.value.elapsedSeconds,
            createdAtMs = sessionCreatedAtMs
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rekaman Rapat NotulenKu",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menjaga rekaman rapat tetap berjalan stabil di latar belakang"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundSafely(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun updateNotificationNow() {
        val st = RecordingStateRepository.state.value
        if (!st.isSessionActive) return
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(st.status, st.elapsedSeconds, st.pauseReason)
        )
    }

    private fun buildNotification(
        status: RecordingStatus,
        elapsedSec: Long,
        pauseReason: String?
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopPi = PendingIntent.getService(
            this,
            1,
            Intent(this, MeetingRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isPaused = status == RecordingStatus.PAUSED_USER || status == RecordingStatus.PAUSED_INTERRUPTED
        val toggleAction = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        val toggleLabel = if (isPaused) "Lanjut" else "Jeda"
        val togglePi = PendingIntent.getService(
            this,
            2,
            Intent(this, MeetingRecordingService::class.java).setAction(toggleAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (status) {
            RecordingStatus.RECORDING -> "NotulenKu • Sedang Merekam (${ExportUtils.formatDuration(elapsedSec)})"
            RecordingStatus.PAUSED_INTERRUPTED -> "NotulenKu • Rekaman Dijeda (Interupsi)"
            RecordingStatus.PAUSED_USER -> "NotulenKu • Rekaman Dijeda (${ExportUtils.formatDuration(elapsedSec)})"
            else -> "NotulenKu • Menyelesaikan Rekaman..."
        }
        val text = when (status) {
            RecordingStatus.RECORDING -> "Rekaman tersimpan lokal di perangkat Anda."
            RecordingStatus.PAUSED_INTERRUPTED -> pauseReason ?: "Mikrofon tidak aktif sementara."
            RecordingStatus.PAUSED_USER -> "Ketuk Lanjut untuk melanjutkan rekaman rapat."
            else -> "Mengamankan berkas audio lokal..."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPi)
            .addAction(0, toggleLabel, togglePi)
            .addAction(0, "Hentikan", stopPi)
            .build()
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        val state = RecordingStateRepository.state.value
        if (state.isSessionActive && state.sessionId == sessionId) {
            if (state.status != RecordingStatus.STOPPING) {
                finalizeCurrentSegmentSafely()
                persistJournal()
            }
            RecordingStateRepository.update {
                ActiveRecordingState(lastError = "Layanan rekaman berhenti. Buka kembali halaman Rekam untuk memeriksa pemulihan.")
            }
        }
        cleanupMonitors()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "${com.notulenrapat.android.BuildConfig.APPLICATION_ID}.audio.ACTION_START_RECORDING"
        const val ACTION_PAUSE = "${com.notulenrapat.android.BuildConfig.APPLICATION_ID}.audio.ACTION_PAUSE_RECORDING"
        const val ACTION_RESUME = "${com.notulenrapat.android.BuildConfig.APPLICATION_ID}.audio.ACTION_RESUME_RECORDING"
        const val ACTION_STOP = "${com.notulenrapat.android.BuildConfig.APPLICATION_ID}.audio.ACTION_STOP_RECORDING"
        const val EXTRA_OWNER_UID = "extra_owner_uid"

        fun start(context: Context, ownerUserId: String) {
            val intent = Intent(context, MeetingRecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_OWNER_UID, ownerUserId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context) {
            context.startService(Intent(context, MeetingRecordingService::class.java).setAction(ACTION_PAUSE))
        }

        fun resume(context: Context) {
            context.startService(Intent(context, MeetingRecordingService::class.java).setAction(ACTION_RESUME))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MeetingRecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
