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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.StatFs
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.export.ExportUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private var sessionId: String = ""
    private var ownerUserId: String = ""
    private var sessionCreatedAtMs: Long = 0L
    private val completedSegmentFiles = mutableListOf<File>()

    private var tickerJob: Job? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioRecordingCallback: AudioManager.AudioRecordingCallback? = null
    private var noisyReceiverRegistered = false

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (RecordingStateRepository.state.value.status == RecordingStatus.RECORDING) {
                    val reason = if (isPhoneOrVoipCallActive()) {
                        "Rekaman dijeda otomatis karena panggilan masuk/aktif. Ketuk Lanjut setelah panggilan selesai."
                    } else {
                        "Rekaman dijeda otomatis karena fokus audio/mikrofon dipakai aplikasi lain. Ketuk Lanjut untuk meneruskan."
                    }
                    pauseDueToInterruption(reason, micSilenced = true)
                }
            }
        }
    }

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

        if (isPhoneOrVoipCallActive()) {
            RecordingStateRepository.update {
                it.copy(lastError = "Mikrofon sedang digunakan untuk panggilan telepon/VoIP. Selesaikan panggilan lalu mulai merekam.")
            }
            stopSelf()
            return
        }

        sessionId = UUID.randomUUID().toString().replace("-", "").take(14)
        ownerUserId = uid.ifBlank { "local" }
        sessionCreatedAtMs = System.currentTimeMillis()
        completedSegmentFiles.clear()
        currentSegmentStartElapsedSec = 0L

        startForegroundSafely(buildNotification(RecordingStatus.RECORDING, 0L, null))
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
        finalizeCurrentSegmentSafely()
        tickerJob?.cancel()
        RecordingStateRepository.update {
            it.copy(
                status = RecordingStatus.PAUSED_INTERRUPTED,
                isMicMutedBySystem = micSilenced,
                pauseReason = reason
            )
        }
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
        if (isPhoneOrVoipCallActive()) {
            RecordingStateRepository.update {
                it.copy(
                    isMicMutedBySystem = true,
                    pauseReason = "Panggilan masih berlangsung. Tunggu panggilan selesai lalu ketuk Lanjut."
                )
            }
            updateNotificationNow()
            return
        }

        requestAudioFocusSafely()
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
        cleanupMonitors()

        val finalElapsed = st.elapsedSeconds.coerceAtLeast(1L)
        val capturedSegments = completedSegmentFiles.toList()
        val activeSessionId = sessionId
        if (capturedSegments.isNotEmpty()) persistJournal()

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
                val outDir = File(filesDir, "recordings").apply { mkdirs() }
                val finalFile = File(outDir, "rec_${activeSessionId}.m4a")
                RecordingStateRepository.finalizeSessionToAudioFile(
                    applicationContext, activeSessionId, capturedSegments, finalFile, finalElapsed
                ).onSuccess { recording ->
                    RecordingStateRepository.update {
                        ActiveRecordingState(
                            status = RecordingStatus.IDLE,
                            elapsedSeconds = recording.durationSeconds,
                            completedRecording = recording
                        )
                    }
                }.onFailure { error ->
                    Log.e(TAG, "Merge failed on stop; preserving session journal and remaining parts", error)
                    RecordingStateRepository.update {
                        ActiveRecordingState(
                            status = RecordingStatus.IDLE,
                            lastError = "Rekaman tidak dapat difinalisasi (${error.localizedMessage ?: "error"}). Bagian yang masih ada tetap disimpan."
                        )
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
        return try {
            val segDir = File(filesDir, "recordings/parts_$sessionId").apply { mkdirs() }
            val segIndex = completedSegmentFiles.size
            val file = File(segDir, "part_${"%04d".format(segIndex)}.m4a")
            currentSegmentFile = file

            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
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
            mr.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaRecorder error what=$what extra=$extra")
                pauseDueToInterruption(
                    "Terjadi gangguan mikrofon perangkat (kode $what). Bagian sebelumnya telah diamankan.",
                    micSilenced = true
                )
            }
            mr.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                ) {
                    rotateSegmentSafely()
                }
            }
            mr.prepare()
            mr.start()
            recorder = mr
            true
        } catch (e: Exception) {
            Log.e(TAG, "startNewRecorderSegment failed", e)
            runCatching { recorder?.release() }
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
            try {
                mr.stop()
            } catch (e: Exception) {
                Log.w(TAG, "MediaRecorder stop warning (checking file recoverability): ${e.message}")
            }
            runCatching { mr.release() }
        }
        // Keep every started part in the manifest, including a part that failed to finalize.
        if (file != null && completedSegmentFiles.none { it.absolutePath == file.absolutePath }) {
            completedSegmentFiles.add(file)
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
                ticks++
                val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                val normalized = (amp / 18000f).coerceIn(0.08f, 1.0f)

                var newElapsed = RecordingStateRepository.state.value.elapsedSeconds
                if (ticks % 8 == 0) {
                    newElapsed += 1L
                }

                RecordingStateRepository.update { cur ->
                    cur.copy(
                        elapsedSeconds = newElapsed,
                        amplitudeHistory = cur.amplitudeHistory.drop(1) + normalized
                    )
                }

                if (ticks % 8 == 0) {
                    // Check system call mode & notification update once per second
                    if (isPhoneOrVoipCallActive()) {
                        serviceScope.launch(Dispatchers.Main.immediate) {
                            pauseDueToInterruption(
                                "Rekaman dijeda otomatis karena panggilan aktif. Ketuk Lanjut setelah panggilan selesai.",
                                micSilenced = true
                            )
                        }
                        break
                    }
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
        requestAudioFocusSafely()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val cb = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val silenced = configs?.any { it.isClientSilenced } == true
                        if (silenced && RecordingStateRepository.state.value.status == RecordingStatus.RECORDING) {
                            serviceScope.launch(Dispatchers.Main.immediate) {
                                pauseDueToInterruption(
                                    "Mikrofon dibisukan oleh Android karena panggilan atau aplikasi lain mengambil mikrofon. Rekaman dijeda aman — ketuk Lanjut setelah selesai.",
                                    micSilenced = true
                                )
                            }
                        }
                    }
                }
            }
            audioRecordingCallback = cb
            runCatching { audioManager.registerAudioRecordingCallback(cb, null) }
        }
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        }
        runCatching {
            ContextCompat.registerReceiver(this, audioDeviceReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            noisyReceiverRegistered = true
        }
    }

    private fun requestAudioFocusSafely() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(audioFocusListener)
                    .build()
                audioFocusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    audioFocusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
        }
    }

    private fun cleanupMonitors() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            audioRecordingCallback?.let { cb ->
                runCatching { audioManager.unregisterAudioRecordingCallback(cb) }
            }
            audioRecordingCallback = null
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusListener)
            }
        }
        if (noisyReceiverRegistered) {
            runCatching { unregisterReceiver(audioDeviceReceiver) }
            noisyReceiverRegistered = false
        }
    }

    private fun isPhoneOrVoipCallActive(): Boolean {
        val mode = runCatching { audioManager.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        return mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
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
        if (RecordingStateRepository.state.value.isSessionActive) {
            finalizeCurrentSegmentSafely()
            persistJournal()
        }
        cleanupMonitors()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.audio.ACTION_START_RECORDING"
        const val ACTION_PAUSE = "com.example.audio.ACTION_PAUSE_RECORDING"
        const val ACTION_RESUME = "com.example.audio.ACTION_RESUME_RECORDING"
        const val ACTION_STOP = "com.example.audio.ACTION_STOP_RECORDING"
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
