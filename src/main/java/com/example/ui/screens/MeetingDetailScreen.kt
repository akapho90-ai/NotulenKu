package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import com.example.ui.viewmodel.UiState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notulenrapat.android.R
import com.example.audio.MeetingAudioPlayer
import com.example.data.ai.TranscriptCorrectionItem
import com.example.data.model.Meeting
import com.example.data.model.Tenant
import com.example.export.ExportUtils
import com.example.ui.components.NotulenCard
import com.example.ui.components.SpeakerTalkTimeChart
import com.example.ui.components.StatusBadge
import com.example.ui.theme.SpeakerPalette
import com.example.ui.theme.StatusEmerald
import com.example.ui.theme.StatusRose
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingDetailScreen(
    meeting: Meeting?,
    detailState: UiState<Meeting>? = null,
    activeTenant: Tenant?,
    aiBusy: Boolean,
    corrections: List<TranscriptCorrectionItem>,
    initialTab: Int = 0,
    defaultVisualStyle: String = "CARDS",
    onRetryLoad: () -> Unit = {},
    onBack: () -> Unit,
    onRenameMeeting: (String) -> Unit,
    onShareTenantToggle: (Boolean) -> Unit = {},
    onRenameSpeaker: (oldName: String, newName: String) -> Unit,
    onEditSegment: (index: Int, newText: String) -> Unit,
    onToggleActionItem: (String) -> Unit,
    onSaveMinutesManual: (ringkasan: String, poin: List<String>, keputusan: List<String>, risiko: List<String>) -> Unit,
    onRegenerateSummary: () -> Unit,
    onRunAnalysis: () -> Unit,
    onRunTranscriptCorrection: () -> Unit,
    onAcceptCorrection: (TranscriptCorrectionItem) -> Unit,
    onRejectCorrection: (TranscriptCorrectionItem) -> Unit,
    onGenerateVisual: () -> Unit,
    onNotify: (String) -> Unit
) {
    val resolvedMeeting = (detailState as? UiState.Success)?.data ?: meeting
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember { MeetingAudioPlayer() }
    val isPlaying by player.isPlaying.collectAsStateWithLifecycle()
    val currentPos by player.currentPositionSec.collectAsStateWithLifecycle()
    val playerDur by player.durationSec.collectAsStateWithLifecycle()
    val speed by player.playbackSpeed.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    val audioAvailable = remember(resolvedMeeting?.localAudioPath) {
        val p = resolvedMeeting?.localAudioPath
        !p.isNullOrBlank() && File(p).exists()
    }
    LaunchedEffect(resolvedMeeting?.localAudioPath) {
        val p = resolvedMeeting?.localAudioPath
        if (!p.isNullOrBlank() && File(p).exists()) {
            player.prepare(p, scope)
        }
    }

    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 3)) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameValue by remember(resolvedMeeting?.judul) { mutableStateOf(resolvedMeeting?.judul.orEmpty()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = resolvedMeeting?.judul ?: "Detail Rapat",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    if (resolvedMeeting != null) {
                        IconButton(onClick = { showRenameDialog = true }) {
                            Icon(Icons.Rounded.Edit, contentDescription = "Ubah judul")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (detailState is UiState.Loading && resolvedMeeting == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator()
                    Text("Memuat detail rapat...", style = MaterialTheme.typography.bodyMedium)
                }
            }
            return@Scaffold
        }

        if (detailState is UiState.Error && resolvedMeeting == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                NotulenCard {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = detailState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onRetryLoad,
                                modifier = Modifier.testTag("detail_retry_button")
                            ) {
                                Text("Coba lagi")
                            }
                            OutlinedButton(onClick = onBack) {
                                Text("Kembali")
                            }
                        }
                    }
                }
            }
            return@Scaffold
        }

        val activeMeeting = resolvedMeeting
        if (activeMeeting == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Rapat tidak ditemukan atau telah dihapus.")
            }
            return@Scaffold
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header metadata + private access indicator
                NotulenCard {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = ExportUtils.formatDate(activeMeeting.createdAt?.toDate()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Durasi: ${ExportUtils.formatDuration(activeMeeting.durasiDetik)} • Pemilik: ${activeMeeting.ownerName}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            StatusBadge(activeMeeting.status)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Notulen pribadi — hanya dapat diakses oleh akun Anda",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Built-in Audio Player
                if (audioAvailable) {
                    NotulenCard {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IconButton(
                                    onClick = { player.togglePlay() },
                                    modifier = Modifier.testTag("audio_play_pause_button")
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = if (isPlaying) "Jeda audio" else "Putar audio"
                                    )
                                }
                                Text(
                                    text = "${ExportUtils.formatDuration(currentPos)} / ${ExportUtils.formatDuration(playerDur)}",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                                    val active = kotlin.math.abs(speed - spd) < 0.05f
                                    Surface(
                                        onClick = { player.setSpeed(spd) },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = "${spd}x",
                                            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                            Slider(
                                value = currentPos.toFloat(),
                                onValueChange = { player.seekToSeconds(it.toLong()) },
                                valueRange = 0f..playerDur.coerceAtLeast(1L).toFloat()
                            )
                        }
                    }
                }

                // Tabs
                val tabs = listOf("Transkrip", "Notulen", "Analisis", "Visual")
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { idx, title ->
                        Tab(
                            selected = selectedTab == idx,
                            onClick = { selectedTab = idx },
                            text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }

                when (selectedTab) {
                    0 -> TranscriptTabContent(
                        meeting = activeMeeting,
                        currentPosSec = currentPos,
                        audioAvailable = audioAvailable,
                        onSeekTo = { player.seekToSeconds(it) },
                        onRenameSpeaker = onRenameSpeaker,
                        onEditSegment = onEditSegment,
                        onNotify = onNotify
                    )
                    1 -> MinutesTabContent(
                        meeting = activeMeeting,
                        aiBusy = aiBusy,
                        onToggleActionItem = onToggleActionItem,
                        onSaveManual = onSaveMinutesManual,
                        onRegenerate = onRegenerateSummary,
                        onNotify = onNotify
                    )
                    2 -> AnalysisTabContent(
                        meeting = activeMeeting,
                        aiBusy = aiBusy,
                        corrections = corrections,
                        onRunAnalysis = onRunAnalysis,
                        onRunCorrection = onRunTranscriptCorrection,
                        onAcceptCorrection = onAcceptCorrection,
                        onRejectCorrection = onRejectCorrection
                    )
                    3 -> MeetingVisualTab(
                        meeting = activeMeeting,
                        watermarkEnabled = activeTenant?.watermarkEnabled ?: true,
                        aiBusy = aiBusy,
                        defaultStyle = defaultVisualStyle,
                        onGenerateVisual = onGenerateVisual,
                        onNotify = onNotify
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Ubah Judul Rapat") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    onRenameMeeting(renameValue)
                    showRenameDialog = false
                }) { Text("Simpan") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Batal") }
            }
        )
    }
}

@Composable
private fun TranscriptTabContent(
    meeting: Meeting,
    currentPosSec: Long,
    audioAvailable: Boolean,
    onSeekTo: (Long) -> Unit,
    onRenameSpeaker: (String, String) -> Unit,
    onEditSegment: (Int, String) -> Unit,
    onNotify: (String) -> Unit
) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    var speakerRenameTarget by remember { mutableStateOf<String?>(null) }
    var speakerNewName by remember { mutableStateOf("") }
    var editSegIndex by remember { mutableStateOf<Int?>(null) }
    var editSegText by remember { mutableStateOf("") }

    val speakers = remember(meeting.segments) {
        meeting.segments.map { it.pembicara }.distinct()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = {
            ExportUtils.copyToClipboard(context, "Transkrip", ExportUtils.buildTranscriptTxt(meeting))
            onNotify("Transkrip disalin ke papan klip.")
        }) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Salin")
        }
        OutlinedButton(onClick = {
            ExportUtils.shareTextFile(context, "transkrip-${meeting.id}.txt", ExportUtils.buildTranscriptTxt(meeting))
        }) {
            Text("Ekspor .txt")
        }
        OutlinedButton(onClick = {
            ExportUtils.shareTextFile(context, "transkrip-${meeting.id}.srt", ExportUtils.buildTranscriptSrt(meeting))
        }) {
            Text("Ekspor .srt")
        }
    }

    OutlinedTextField(
        value = search,
        onValueChange = { search = it },
        placeholder = { Text("Cari kata dalam transkrip...") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    )

    if (meeting.segments.isEmpty()) {
        NotulenCard {
            Text(
                text = meeting.transcriptText.ifBlank { "Transkrip belum tersedia." },
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (meeting.transcriptText.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
        }
        return
    }

    meeting.segments.forEachIndexed { idx, seg ->
        if (search.isNotBlank() && !seg.teks.contains(search, ignoreCase = true) && !seg.pembicara.contains(search, ignoreCase = true)) {
            return@forEachIndexed
        }
        val speakerColor = SpeakerPalette[speakers.indexOf(seg.pembicara).coerceAtLeast(0) % SpeakerPalette.size]
        val activeNow = audioAvailable && currentPosSec in seg.mulaiDetik..seg.selesaiDetik.coerceAtLeast(seg.mulaiDetik + 1)

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (activeNow) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                1.dp,
                if (activeNow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = {
                            speakerRenameTarget = seg.pembicara
                            speakerNewName = seg.pembicara
                        },
                        color = speakerColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = seg.pembicara,
                            color = speakerColor,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = ExportUtils.formatDuration(seg.mulaiDetik),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = audioAvailable) { onSeekTo(seg.mulaiDetik) }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            editSegIndex = idx
                            editSegText = seg.teks
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Edit segmen", modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(seg.teks, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (speakerRenameTarget != null) {
        AlertDialog(
            onDismissRequest = { speakerRenameTarget = null },
            title = { Text("Ganti Nama Pembicara") },
            text = {
                OutlinedTextField(
                    value = speakerNewName,
                    onValueChange = { speakerNewName = it },
                    label = { Text("Nama baru (berlaku semua segmen)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    onRenameSpeaker(speakerRenameTarget!!, speakerNewName)
                    speakerRenameTarget = null
                }) { Text("Terapkan") }
            },
            dismissButton = {
                TextButton(onClick = { speakerRenameTarget = null }) { Text("Batal") }
            }
        )
    }

    if (editSegIndex != null) {
        AlertDialog(
            onDismissRequest = { editSegIndex = null },
            title = { Text("Edit Teks Segmen") },
            text = {
                OutlinedTextField(
                    value = editSegText,
                    onValueChange = { editSegText = it },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    onEditSegment(editSegIndex!!, editSegText)
                    editSegIndex = null
                }) { Text("Simpan") }
            },
            dismissButton = {
                TextButton(onClick = { editSegIndex = null }) { Text("Batal") }
            }
        )
    }
}

@Composable
private fun MinutesTabContent(
    meeting: Meeting,
    aiBusy: Boolean,
    onToggleActionItem: (String) -> Unit,
    onSaveManual: (String, List<String>, List<String>, List<String>) -> Unit,
    onRegenerate: () -> Unit,
    onNotify: (String) -> Unit
) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var ringkasanInput by remember(meeting.ringkasan) { mutableStateOf(meeting.ringkasan) }
    var poinInput by remember(meeting.poinUtama) { mutableStateOf(meeting.poinUtama.joinToString("\n")) }
    var keputusanInput by remember(meeting.keputusan) { mutableStateOf(meeting.keputusan.joinToString("\n")) }
    var risikoInput by remember(meeting.risiko) { mutableStateOf(meeting.risiko.joinToString("\n")) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(onClick = { ExportUtils.exportAndSharePdf(context, meeting) }, modifier = Modifier.testTag("export_pdf_button")) {
            Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Ekspor PDF")
        }
        OutlinedButton(onClick = {
            ExportUtils.copyToClipboard(context, "Notulen", ExportUtils.buildMinutesText(meeting))
            onNotify("Notulen disalin ke papan klip.")
        }) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Salin")
        }
        OutlinedButton(onClick = onRegenerate, enabled = !aiBusy) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (aiBusy) "Menyusun..." else "Buat Ulang")
        }
        OutlinedButton(onClick = { editing = !editing }) {
            Text(if (editing) "Batal Edit" else "Edit Manual")
        }
    }

    if (editing) {
        NotulenCard {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = ringkasanInput, onValueChange = { ringkasanInput = it }, label = { Text("Ringkasan") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = poinInput, onValueChange = { poinInput = it }, label = { Text("Poin Utama (per baris)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = keputusanInput, onValueChange = { keputusanInput = it }, label = { Text("Keputusan (per baris)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = risikoInput, onValueChange = { risikoInput = it }, label = { Text("Risiko (per baris)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        onSaveManual(
                            ringkasanInput,
                            poinInput.lines().map { it.trim() }.filter { it.isNotBlank() },
                            keputusanInput.lines().map { it.trim() }.filter { it.isNotBlank() },
                            risikoInput.lines().map { it.trim() }.filter { it.isNotBlank() }
                        )
                        editing = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Simpan Perubahan Notulen") }
            }
        }
    } else {
        NotulenCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ringkasan Eksekutif", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(meeting.ringkasan.ifBlank { "Belum ada ringkasan." }, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (meeting.angkaKunci.isNotEmpty()) {
            NotulenCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Angka Kunci", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    meeting.angkaKunci.forEach { kn ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(kn.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(kn.nilai, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        if (meeting.poinUtama.isNotEmpty()) {
            BulletCard("Poin Utama", meeting.poinUtama)
        }
        if (meeting.keputusan.isNotEmpty()) {
            BulletCard("Keputusan", meeting.keputusan)
        }
        if (meeting.actionItems.isNotEmpty()) {
            NotulenCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Action Items", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    meeting.actionItems.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleActionItem(item.id) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = item.selesai, onCheckedChange = { onToggleActionItem(item.id) })
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.tugas, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(
                                    text = "${item.siapa} • Prioritas ${item.prioritas}${if (item.deadline.isNotBlank()) " • Tenggat ${item.deadline}" else ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
        if (meeting.risiko.isNotEmpty()) {
            BulletCard("Risiko & Hambatan", meeting.risiko)
        }
    }

    Text(
        text = stringResource(id = R.string.ai_disclaimer) +
            (meeting.modelInfo?.let { " (${it.model} • ${it.generatedAtLabel})" } ?: ""),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun BulletCard(title: String, items: List<String>) {
    NotulenCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            items.forEach { line ->
                Text("• $line", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun AnalysisTabContent(
    meeting: Meeting,
    aiBusy: Boolean,
    corrections: List<TranscriptCorrectionItem>,
    onRunAnalysis: () -> Unit,
    onRunCorrection: () -> Unit,
    onAcceptCorrection: (TranscriptCorrectionItem) -> Unit,
    onRejectCorrection: (TranscriptCorrectionItem) -> Unit
) {
    // Speaker talk time computed locally from segments
    val speakerDurations = remember(meeting.segments) {
        meeting.segments.groupBy { it.pembicara }
            .mapValues { (_, list) ->
                list.sumOf { (it.selesaiDetik - it.mulaiDetik).coerceAtLeast(2L) }
            }
            .toList()
            .sortedByDescending { it.second }
    }

    if (speakerDurations.isNotEmpty()) {
        NotulenCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Distribusi Waktu Bicara", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                SpeakerTalkTimeChart(speakerDurations = speakerDurations)
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onRunAnalysis,
            enabled = !aiBusy && meeting.transcriptText.isNotBlank(),
            modifier = Modifier.weight(1f)
        ) {
            Text(if (meeting.analysis == null) "Analisis AI" else "Perbarui Analisis")
        }
        OutlinedButton(
            onClick = onRunCorrection,
            enabled = !aiBusy && meeting.segments.isNotEmpty(),
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Perbaiki Transkrip")
        }
    }

    if (aiBusy) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sedang memproses dengan AI...", style = MaterialTheme.typography.bodySmall)
        }
    }

    meeting.analysis?.let { a ->
        NotulenCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Suasana Rapat: ${a.suasana}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (a.topik.isNotEmpty()) {
                    Text("Topik Utama", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    a.topik.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
                if (a.ringkasPerTopik.isNotEmpty()) {
                    Text("Ringkasan per Topik", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    a.ringkasPerTopik.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
                if (a.pertanyaanTerbuka.isNotEmpty()) {
                    Text("Pertanyaan Belum Terjawab", style = MaterialTheme.typography.titleSmall, color = StatusRose)
                    a.pertanyaanTerbuka.forEach { Text("? $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }

    if (corrections.isNotEmpty()) {
        Text("Usulan Perbaikan Transkrip (${corrections.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        corrections.forEach { item ->
            NotulenCard {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${item.pembicara} • ${item.alasan}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Sebelum: ${item.teksAsli}", style = MaterialTheme.typography.bodySmall, color = StatusRose)
                    Text("Sesudah: ${item.teksKoreksi}", style = MaterialTheme.typography.bodyMedium, color = StatusEmerald, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onAcceptCorrection(item) }) { Text("Terima") }
                        OutlinedButton(onClick = { onRejectCorrection(item) }) { Text("Tolak") }
                    }
                }
            }
        }
    }
}
