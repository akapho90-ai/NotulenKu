package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.audio.AudioFileHelper
import com.example.audio.ImportedAudioInfo
import com.example.audio.MeetingRecordingService
import com.example.audio.RecordedAudioFile
import com.example.audio.RecordingStateRepository
import com.example.audio.RecordingStatus
import com.example.audio.RecoverableLocalRecording
import com.example.data.model.Tenant
import com.example.data.repository.LocalProcessingJob
import com.example.export.ExportUtils
import com.example.ui.components.AudioWaveformBars
import com.example.ui.components.NotulenCard
import com.example.ui.components.PrimaryGradientButton
import com.example.ui.theme.BrandBlue
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusEmerald
import com.example.ui.theme.StatusRose
import com.example.ui.viewmodel.ProcessingStepState
import com.example.ui.viewmodel.SaveStatus
import java.io.File

@Composable
fun RecordScreen(
    activeTenant: Tenant?,
    initialMode: Int = 0, // 0 = Rekam Langsung, 1 = Unggah File
    processingState: ProcessingStepState?,
    onProcessAudio: (judul: String, file: File, mimeType: String, durationSec: Long) -> Unit,
    onRetrySave: () -> Unit = {},
    onOpenAccountLink: () -> Unit = {},
    onOpenMeetingDetail: (String) -> Unit,
    onDismissProcessing: () -> Unit,
    currentUserId: String = "",
    pendingLocalJobs: List<LocalProcessingJob> = emptyList(),
    onRetryProcessing: () -> Unit = {},
    onResumeLocalJob: (LocalProcessingJob) -> Unit = {},
    onDiscardLocalJob: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val recordingState by RecordingStateRepository.state.collectAsStateWithLifecycle()

    val isRecordingSession = recordingState.isSessionActive
    val isActivelyCapturing = recordingState.isActivelyCapturing
    val isPaused = recordingState.isPaused
    val elapsedSeconds = recordingState.elapsedSeconds
    val amplitudes = recordingState.amplitudeHistory

    // Keep screen awake ONLY while actively recording; NEVER stop/cleanup foreground service on screen dispose!
    val view = LocalView.current
    DisposableEffect(isActivelyCapturing) {
        view.keepScreenOn = isActivelyCapturing
        onDispose {
            view.keepScreenOn = false
        }
    }

    var selectedTab by remember(initialMode) { mutableIntStateOf(initialMode) }
    var meetingTitle by remember { mutableStateOf("") }
    var consentChecked by remember { mutableStateOf(false) }
    var recordedAudio by remember { mutableStateOf<RecordedAudioFile?>(null) }
    var importedAudio by remember { mutableStateOf<ImportedAudioInfo?>(null) }
    var permissionDeniedMsg by remember { mutableStateOf<String?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var recoverableSessions by remember(currentUserId) {
        mutableStateOf(
            if (currentUserId.isNotBlank()) RecordingStateRepository.listRecoverableSessions(context, currentUserId)
            else emptyList()
        )
    }

    // Consume completed recording produced by MeetingRecordingService
    LaunchedEffect(recordingState.completedRecording) {
        val completed = RecordingStateRepository.consumeCompletedRecording()
        if (completed != null) {
            recordedAudio = completed
            if (currentUserId.isNotBlank()) {
                recoverableSessions = RecordingStateRepository.listRecoverableSessions(context, currentUserId)
            }
        }
    }

    // Surface foreground service error cleanly
    LaunchedEffect(recordingState.lastError) {
        val err = recordingState.lastError
        if (!err.isNullOrBlank()) {
            validationError = err
            RecordingStateRepository.clearError()
            if (currentUserId.isNotBlank()) {
                recoverableSessions = RecordingStateRepository.listRecoverableSessions(context, currentUserId)
            }
        }
    }

    // Allow large recordings (>2 hours); AudioSegmenter splits long files into valid M4A chunks
    val maxFileBytes = ((activeTenant?.maxFileMb ?: 250L).coerceAtLeast(250L)) * 1024L * 1024L

    val notificationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            permissionDeniedMsg = "Notifikasi rekaman dinonaktifkan. Rekaman tetap berjalan, tetapi aktifkan izin notifikasi agar lebih stabil di latar belakang."
        }
        recordedAudio = null
        MeetingRecordingService.start(context, currentUserId.ifBlank { "local" })
    }

    fun startForegroundRecordingFlow() {
        permissionDeniedMsg = null
        validationError = null
        val needsNotifPerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotifPerm) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            recordedAudio = null
            MeetingRecordingService.start(context, currentUserId.ifBlank { "local" })
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startForegroundRecordingFlow()
        } else {
            permissionDeniedMsg = "Izin mikrofon ditolak. Aktifkan izin Mikrofon di Pengaturan aplikasi perangkat Anda untuk merekam rapat langsung."
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val info = AudioFileHelper.inspectUri(context, uri)
            if (info == null) {
                validationError = "Berkas audio tidak dapat dibaca."
            } else if (info.sizeBytes > maxFileBytes) {
                validationError = "Ukuran berkas (${info.sizeBytes / (1024 * 1024)} MB) melebihi batas maksimum ${maxFileBytes / (1024 * 1024)} MB."
            } else {
                validationError = null
                importedAudio = info
            }
        }
    }

    var showTipDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Segmented Control + (i) info button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                    listOf("Rekam Langsung", "Unggah File").forEachIndexed { idx, label ->
                        SegmentedButton(
                            selected = selectedTab == idx,
                            onClick = { if (!isRecordingSession) selectedTab = idx },
                            shape = SegmentedButtonDefaults.itemShape(index = idx, count = 2)
                        ) {
                            Text(label)
                        }
                    }
                }
                IconButton(onClick = { showTipDialog = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = "Tips rekaman",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Active Processing Stepper Card
            if (processingState != null) {
                NotulenCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (processingState.error != null) "Proses Terhenti" else "Memproses Rapat",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (processingState.stepIndex == 4 || processingState.error != null) {
                                TextButton(onClick = onDismissProcessing) { Text("Tutup") }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        val steps = listOf("Disiapkan", "Mentranskrip", "Menyusun Notulen", "Selesai")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            steps.forEachIndexed { i, s ->
                                val active = processingState.stepIndex >= i + 1
                                Text(
                                    text = s,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        val computedProgress = if (processingState.stepIndex == 2 && processingState.totalChunks > 1) {
                            val chunkFraction = processingState.completedChunks.toFloat() / processingState.totalChunks.toFloat()
                            (0.25f + chunkFraction * 0.45f).coerceIn(0.25f, 0.7f)
                        } else {
                            (processingState.stepIndex / 4f).coerceIn(0.1f, 1f)
                        }
                        LinearProgressIndicator(
                            progress = { computedProgress },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp))
                        )
                        if (!processingState.stageDetail.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = processingState.stageDetail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (processingState.error != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = processingState.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusRose
                            )
                            if (processingState.canRetryProcessing) {
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(
                                    onClick = onRetryProcessing,
                                    modifier = Modifier.testTag("retry_processing_button")
                                ) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Proses ulang")
                                }
                            }
                        } else if (processingState.stepIndex == 4) {
                            Spacer(modifier = Modifier.height(10.dp))
                            when (processingState.saveStatus) {
                                SaveStatus.SAVING -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.testTag("save_status_saving")
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Menyimpan…",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                SaveStatus.SAVED -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.testTag("save_status_saved")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            tint = StatusEmerald,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Tersimpan",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = StatusEmerald
                                        )
                                    }
                                    if (processingState.meetingId.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Button(
                                            onClick = { onOpenMeetingDetail(processingState.meetingId) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("open_saved_meeting_button")
                                        ) {
                                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Buka Notulen Rapat")
                                        }
                                    }
                                }
                                SaveStatus.FAILED -> {
                                    Text(
                                        text = processingState.saveError ?: "Gagal menyimpan notulen.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StatusRose,
                                        modifier = Modifier.testTag("save_status_error")
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = onRetrySave,
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("retry_save_meeting_button")
                                        ) {
                                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Coba lagi")
                                        }
                                        if (processingState.accountLinkRequired) {
                                            OutlinedButton(
                                                onClick = onOpenAccountLink,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("Tautkan Akun")
                                            }
                                        }
                                    }
                                }
                                SaveStatus.NONE -> {
                                    if (processingState.meetingId.isNotBlank()) {
                                        Button(
                                            onClick = { onOpenMeetingDetail(processingState.meetingId) },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Buka Notulen Rapat")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Recovery card for interrupted local recordings (e.g. after process death / OS stop)
            if (recoverableSessions.isNotEmpty() && !isRecordingSession) {
                val latest: RecoverableLocalRecording = recoverableSessions.first()
                NotulenCard {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Restore, contentDescription = null, tint = StatusAmber, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Rekaman Lokal Belum Selesai Ditemukan",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "Durasi tercatat ${ExportUtils.formatDuration(latest.elapsedSeconds)} • ${latest.segmentPaths.size} bagian (${latest.totalSizeBytes / 1024} KB). Audio asli aman di perangkat ini.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    RecordingStateRepository.recoverSessionToAudioFile(context, latest)
                                        .onSuccess { recovered ->
                                            recordedAudio = recovered
                                            selectedTab = 0
                                            recoverableSessions = RecordingStateRepository.listRecoverableSessions(context, currentUserId)
                                        }
                                        .onFailure { err ->
                                            validationError = "Gagal memulihkan rekaman: ${err.localizedMessage}"
                                        }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Pulihkan Audio")
                            }
                            OutlinedButton(
                                onClick = {
                                    RecordingStateRepository.discardRecoverableSession(context, latest)
                                    recoverableSessions = RecordingStateRepository.listRecoverableSessions(context, currentUserId)
                                }
                            ) {
                                Text("Hapus")
                            }
                        }
                    }
                }
            }

            // Recovery card for unfinished/failed local processing jobs (resumes only failed chunks/stages)
            val otherPendingJobs = pendingLocalJobs.filter { it.meetingId != processingState?.meetingId }
            if (otherPendingJobs.isNotEmpty()) {
                val job = otherPendingJobs.first()
                NotulenCard {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Proses Tertunda: ${job.judul}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val progressSummary = when {
                            job.summaryDone -> "Notulen sudah selesai — tinggal simpan ulang ke server."
                            job.transcriptDone -> "Transkrip sudah lengkap — lanjut langsung ke penyusunan notulen."
                            job.totalChunkCount > 1 -> "${job.completedChunkCount}/${job.totalChunkCount} bagian transkrip sudah selesai."
                            else -> job.lastError ?: "Siap dilanjutkan dari checkpoint lokal."
                        }
                        Text(
                            text = progressSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onResumeLocalJob(job) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Lanjutkan Proses")
                            }
                            OutlinedButton(onClick = { onDiscardLocalJob(job.meetingId) }) {
                                Text("Tutup")
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = meetingTitle,
                onValueChange = { meetingTitle = it },
                label = { Text("Judul rapat (opsional)") },
                placeholder = { Text("Kosongkan untuk nama akun + tanggal & jam") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("meeting_title_input")
            )

            if (selectedTab == 0) {
                // Live Recording Panel
                NotulenCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = ExportUtils.formatDuration(elapsedSeconds),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isActivelyCapturing) StatusRose else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when {
                                recordingState.status == RecordingStatus.STOPPING -> "Mengamankan berkas audio lokal..."
                                recordingState.status == RecordingStatus.PAUSED_INTERRUPTED || recordingState.isMicMutedBySystem ->
                                    "Rekaman Dijeda (Interupsi Sistem)"
                                recordingState.status == RecordingStatus.PAUSED_USER -> "Rekaman Dijeda"
                                isActivelyCapturing -> "Sedang Merekam... (Latar Depan Aktif)"
                                recordedAudio != null -> "Rekaman Siap Diproses (${recordedAudio!!.sizeBytes / 1024} KB)"
                                else -> "Ketuk tombol mikrofon untuk mulai"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (recordingState.status == RecordingStatus.PAUSED_INTERRUPTED || recordingState.isMicMutedBySystem) {
                                StatusAmber
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )

                        if (!recordingState.pauseReason.isNullOrBlank() && isPaused) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                color = StatusAmber.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Warning,
                                        contentDescription = null,
                                        tint = StatusAmber,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = recordingState.pauseReason!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        AudioWaveformBars(
                            amplitudes = amplitudes,
                            isActive = isActivelyCapturing,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isRecordingSession) {
                                OutlinedButton(
                                    enabled = recordingState.status != RecordingStatus.STOPPING,
                                    onClick = {
                                        if (isPaused) MeetingRecordingService.resume(context)
                                        else MeetingRecordingService.pause(context)
                                    },
                                    modifier = Modifier.height(52.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                        contentDescription = if (isPaused) "Lanjut" else "Jeda"
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (isPaused) "Lanjut" else "Jeda")
                                }
                                Button(
                                    enabled = recordingState.status != RecordingStatus.STOPPING,
                                    onClick = { MeetingRecordingService.stop(context) },
                                    colors = ButtonDefaults.buttonColors(containerColor = StatusRose),
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .testTag("stop_record_button")
                                ) {
                                    Icon(Icons.Rounded.Stop, contentDescription = "Stop rekam", modifier = Modifier.size(32.dp))
                                }
                            } else {
                                Button(
                                    onClick = {
                                        validationError = null
                                        val hasPerm = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (hasPerm) {
                                            startForegroundRecordingFlow()
                                        } else {
                                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    },
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                                    modifier = Modifier
                                        .size(80.dp)
                                        .testTag("start_record_button")
                                ) {
                                    Icon(Icons.Rounded.Mic, contentDescription = "Mulai rekam", modifier = Modifier.size(36.dp))
                                }
                            }
                        }

                        if (permissionDeniedMsg != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = permissionDeniedMsg!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusRose
                            )
                        }
                    }
                }
            } else {
                // Upload File Panel
                NotulenCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.UploadFile,
                                contentDescription = "Unggah Audio",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Pilih Berkas Audio Rapat",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Format: m4a, mp3, wav, webm, ogg, aac • Audio panjang >2 jam dipecah otomatis per segmen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        OutlinedButton(
                            onClick = { filePickerLauncher.launch(arrayOf("audio/*")) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("pick_audio_file_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(if (importedAudio == null) "Pilih File Audio" else "Ganti File Audio")
                        }

                        if (importedAudio != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(importedAudio!!.fileName, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "Ukuran: ${importedAudio!!.sizeBytes / 1024} KB • Durasi: ${ExportUtils.formatDuration(importedAudio!!.durationSeconds)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (validationError != null) {
                Text(
                    text = validationError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusRose
                )
            }

            // Mandatory Consent Checkbox
            NotulenCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { consentChecked = !consentChecked }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = consentChecked,
                        onCheckedChange = { consentChecked = it },
                        modifier = Modifier.testTag("recording_consent_checkbox")
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(id = R.string.consent_recording),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            val hasReadyAudio = (selectedTab == 0 && recordedAudio != null && !isRecordingSession) ||
                (selectedTab == 1 && importedAudio != null)

            PrimaryGradientButton(
                text = "Proses Transkrip & Notulen AI",
                enabled = consentChecked && hasReadyAudio,
                onClick = {
                    if (selectedTab == 0 && recordedAudio != null) {
                        val rec = recordedAudio!!
                        onProcessAudio(meetingTitle, rec.file, rec.mimeType, rec.durationSeconds)
                        recordedAudio = null
                        meetingTitle = ""
                        consentChecked = false
                    } else if (selectedTab == 1 && importedAudio != null) {
                        val imp = importedAudio!!
                        val copied = AudioFileHelper.copyUriToInternalFile(context, imp.uri, imp.fileName)
                        if (copied == null) {
                            validationError = "Gagal menyalin berkas audio."
                        } else {
                            onProcessAudio(meetingTitle, copied, imp.mimeType, imp.durationSeconds)
                            importedAudio = null
                            meetingTitle = ""
                            consentChecked = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("process_audio_button")
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showTipDialog) {
        AlertDialog(
            onDismissRequest = { showTipDialog = false },
            title = { Text("Tips Rekaman Rapat", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = stringResource(id = R.string.mic_speaker_tip),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showTipDialog = false }) {
                    Text("Mengerti")
                }
            }
        )
    }
}
