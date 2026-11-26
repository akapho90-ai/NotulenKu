package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.Meeting
import com.example.export.ExportUtils
import com.example.ui.components.AvatarStack
import com.example.ui.components.ConfirmDialog
import com.example.ui.components.NotulenCard
import com.example.ui.components.PrimaryGradientButton
import com.example.ui.components.SkeletonCard
import com.example.ui.components.StatusBadge
import com.example.ui.components.SwipeableMeetingRow
import com.example.ui.components.TopicChip
import com.example.ui.components.WaveformMini
import com.example.ui.theme.BrandBlue
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusEmerald
import com.example.ui.theme.StatusRose
import com.example.ui.viewmodel.UiState
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    meetingsState: UiState<List<Meeting>>,
    isAdminOrOwner: Boolean,
    currentUserId: String,
    defaultViewMode: String = "list", // "list" | "cards"
    accountLinkRequired: Boolean = false,
    onRetryLoad: () -> Unit = {},
    onOpenAccountLink: () -> Unit = {},
    onMeetingClick: (String) -> Unit,
    onRenameMeeting: (Meeting, String) -> Unit,
    onDeleteMeeting: (Meeting) -> Unit,
    onToggleFavorite: (Meeting) -> Unit = {},
    onUpdateTags: (Meeting, List<String>) -> Unit = { _, _ -> },
    onBulkToggleFavorite: (List<Meeting>, Boolean) -> Unit = { _, _ -> },
    onBulkAddTag: (List<Meeting>, String) -> Unit = { _, _ -> },
    onBulkDelete: (List<Meeting>) -> Unit = {},
    onNotify: (String) -> Unit = {},
    onRecordFirst: () -> Unit
) {
    val context = LocalContext.current
    var searchExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("all") } // all | done | processing | error | favorite
    var ownerFilter by remember { mutableStateOf("all") } // all | mine
    var dateFilter by remember { mutableStateOf("all") } // all | 7d | 30d | 90d
    var speakerFilter by remember { mutableStateOf("") }
    var topicFilter by remember { mutableStateOf("") }
    var onlyWithActions by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf("newest") } // newest | oldest | duration | title
    var viewMode by remember(defaultViewMode) { mutableStateOf(defaultViewMode) }
    var pageLimit by remember { mutableIntStateOf(20) }

    var showFilterSheet by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val multiSelectActive = selectedIds.isNotEmpty()

    var renameTarget by remember { mutableStateOf<Meeting?>(null) }
    var renameText by remember { mutableStateOf("") }
    var tagTarget by remember { mutableStateOf<Meeting?>(null) }
    var tagInput by remember { mutableStateOf("") }
    var showBulkTagDialog by remember { mutableStateOf(false) }
    var bulkTagText by remember { mutableStateOf("") }
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Meeting?>(null) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            // 1. Header Bar (Title / Bulk bar + Search + Filter + Sort + View toggle)
            if (multiSelectActive) {
                val allList = (meetingsState as? UiState.Success)?.data.orEmpty()
                val selectedMeetings = allList.filter { it.id in selectedIds }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Batal pilih")
                        }
                        Text(
                            text = "${selectedMeetings.size} dipilih",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            onBulkToggleFavorite(selectedMeetings, true)
                            selectedIds = emptySet()
                        }) {
                            Icon(Icons.Rounded.Star, contentDescription = "Favoritkan", tint = StatusAmber)
                        }
                        IconButton(onClick = { showBulkTagDialog = true }) {
                            Icon(Icons.Rounded.Label, contentDescription = "Beri tag")
                        }
                        IconButton(onClick = {
                            ExportUtils.exportBulkMeetingsTxt(context, selectedMeetings)
                            selectedIds = emptySet()
                        }) {
                            Icon(Icons.Rounded.Download, contentDescription = "Ekspor TXT")
                        }
                        IconButton(onClick = { showBulkDeleteConfirm = true }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Hapus pilihan", tint = StatusRose)
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Riwayat Rapat",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            searchExpanded = !searchExpanded
                            if (!searchExpanded) searchQuery = ""
                        },
                        modifier = Modifier.testTag("history_search_toggle")
                    ) {
                        Icon(
                            imageVector = if (searchExpanded) Icons.Rounded.Close else Icons.Rounded.Search,
                            contentDescription = "Cari riwayat"
                        )
                    }
                    IconButton(onClick = { showFilterSheet = true }) {
                        Icon(Icons.Rounded.FilterList, contentDescription = "Filter lanjutan")
                    }
                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "Urutkan")
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false }
                        ) {
                            listOf(
                                "newest" to "Terbaru",
                                "oldest" to "Terlama",
                                "duration" to "Durasi Terpanjang",
                                "title" to "Judul A–Z"
                            ).forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = { Text(label, fontWeight = if (sortBy == key) FontWeight.Bold else FontWeight.Normal) },
                                    onClick = {
                                        sortBy = key
                                        sortMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { viewMode = if (viewMode == "list") "cards" else "list" }) {
                        Icon(
                            imageVector = if (viewMode == "list") Icons.Rounded.GridView else Icons.AutoMirrored.Rounded.ViewList,
                            contentDescription = "Ganti tampilan"
                        )
                    }
                }
            }

            AnimatedVisibility(visible = searchExpanded || searchQuery.isNotBlank()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Cari judul, pembicara, atau isi transkrip...") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Hapus pencarian")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .testTag("history_search_input")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            when (meetingsState) {
                is UiState.Loading -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SkeletonCard()
                        SkeletonCard()
                        SkeletonCard()
                    }
                }
                is UiState.Error -> {
                    NotulenCard {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Gagal memuat riwayat rapat",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = meetingsState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = onRetryLoad,
                                    modifier = Modifier.testTag("history_retry_button")
                                ) {
                                    Text("Coba lagi")
                                }
                                if (accountLinkRequired) {
                                    OutlinedButton(onClick = onOpenAccountLink) {
                                        Text("Tautkan Akun")
                                    }
                                }
                            }
                        }
                    }
                }
                is UiState.Success -> {
                    val allData = meetingsState.data
                    val totalHours = allData.sumOf { it.durasiDetik } / 3600.0
                    val failedCount = allData.count { it.status == "error" }

                    // 2. Clickable Summary Strip
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SummaryStripPill(
                            label = "Total",
                            value = allData.size.toString(),
                            color = BrandBlue,
                            onClick = {
                                statusFilter = "all"
                                dateFilter = "all"
                                speakerFilter = ""
                                topicFilter = ""
                                ownerFilter = "all"
                                onlyWithActions = false
                            },
                            modifier = Modifier.weight(1f)
                        )
                        SummaryStripPill(
                            label = "Jam",
                            value = String.format(Locale.US, "%.1f", totalHours),
                            color = StatusEmerald,
                            onClick = { sortBy = "duration" },
                            modifier = Modifier.weight(1f)
                        )
                        SummaryStripPill(
                            label = "Gagal",
                            value = failedCount.toString(),
                            color = StatusRose,
                            onClick = { statusFilter = "error" },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 3. Quick Filter Chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "all" to "Semua",
                            "done" to "Selesai",
                            "processing" to "Diproses",
                            "error" to "Gagal",
                            "favorite" to "Favorit"
                        ).forEach { (key, label) ->
                            FilterChip(
                                selected = statusFilter == key,
                                onClick = { statusFilter = key },
                                label = { Text(label) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    val nowSec = System.currentTimeMillis() / 1000L
                    val filtered = remember(
                        allData, searchQuery, statusFilter, ownerFilter, dateFilter,
                        speakerFilter, topicFilter, onlyWithActions, sortBy
                    ) {
                        val q = searchQuery.trim().lowercase()
                        val list = allData.filter { m ->
                            val matchesQuery = q.isBlank() ||
                                m.judul.lowercase().contains(q) ||
                                m.transcriptText.lowercase().contains(q) ||
                                m.ringkasan.lowercase().contains(q) ||
                                m.ownerName.lowercase().contains(q)
                            val matchesStatus = when (statusFilter) {
                                "done" -> m.status == "done"
                                "processing" -> m.status in listOf("uploaded", "transcribing", "summarizing")
                                "error" -> m.status == "error"
                                "favorite" -> m.isFavorite
                                else -> true
                            }
                            val matchesOwner = ownerFilter == "all" || m.userId == currentUserId
                            val ts = m.createdAt?.seconds ?: nowSec
                            val matchesDate = when (dateFilter) {
                                "7d" -> ts >= nowSec - 7 * 86400L
                                "30d" -> ts >= nowSec - 30 * 86400L
                                "90d" -> ts >= nowSec - 90 * 86400L
                                else -> true
                            }
                            val matchesSpeaker = speakerFilter.isBlank() ||
                                m.segments.any { it.pembicara.equals(speakerFilter, ignoreCase = true) }
                            val matchesTopic = topicFilter.isBlank() ||
                                (m.tags + m.analysis?.topik.orEmpty()).any { it.equals(topicFilter, ignoreCase = true) }
                            val matchesActions = !onlyWithActions || m.actionItems.isNotEmpty()

                            matchesQuery && matchesStatus && matchesOwner && matchesDate &&
                                matchesSpeaker && matchesTopic && matchesActions
                        }
                        when (sortBy) {
                            "oldest" -> list.sortedBy { it.createdAt?.seconds ?: 0L }
                            "duration" -> list.sortedByDescending { it.durasiDetik }
                            "title" -> list.sortedBy { it.judul.lowercase() }
                            else -> list.sortedByDescending { it.createdAt?.seconds ?: Long.MAX_VALUE }
                        }
                    }

                    val paged = filtered.take(pageLimit)
                    val grouped = remember(paged) { groupMeetingsByDate(paged) }

                    if (filtered.isEmpty()) {
                        if (allData.isEmpty()) {
                            NotulenCard(modifier = Modifier.padding(top = 8.dp)) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Image(
                                        painter = painterResource(id = R.drawable.img_onboarding_meeting_1795280581520),
                                        contentDescription = "Belum ada riwayat rapat",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(150.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                    )
                                    Text("Belum ada riwayat rapat", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(
                                        "Rekam rapat pertama Anda untuk mulai membuat transkrip dan notulen AI.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    PrimaryGradientButton(
                                        text = "Rekam Rapat Pertama",
                                        icon = Icons.Rounded.Mic,
                                        onClick = onRecordFirst,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        } else {
                            NotulenCard(modifier = Modifier.padding(top = 8.dp)) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Tidak ada rapat yang cocok", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(
                                        "Coba ubah kata kunci atau atur ulang filter aktif.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    OutlinedButton(onClick = {
                                        searchQuery = ""
                                        statusFilter = "all"
                                        dateFilter = "all"
                                        speakerFilter = ""
                                        topicFilter = ""
                                        ownerFilter = "all"
                                        onlyWithActions = false
                                    }) {
                                        Text("Reset Semua Filter")
                                    }
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            grouped.forEach { (groupLabel, groupItems) ->
                                stickyHeader(key = "hdr_$groupLabel") {
                                    Surface(
                                        color = MaterialTheme.colorScheme.background,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "$groupLabel (${groupItems.size})",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(vertical = 6.dp)
                                        )
                                    }
                                }
                                items(groupItems, key = { it.id }) { meeting ->
                                    val isSelected = meeting.id in selectedIds
                                    SwipeableMeetingRow(
                                        isFavorite = meeting.isFavorite,
                                        onSwipeFavorite = { onToggleFavorite(meeting) },
                                        onSwipeDelete = { deleteTarget = meeting }
                                    ) {
                                        RichMeetingHistoryItem(
                                            meeting = meeting,
                                            isCardMode = viewMode == "cards",
                                            searchQuery = searchQuery,
                                            multiSelectActive = multiSelectActive,
                                            isSelected = isSelected,
                                            onClick = {
                                                if (multiSelectActive) {
                                                    selectedIds = if (isSelected) selectedIds - meeting.id else selectedIds + meeting.id
                                                } else {
                                                    onMeetingClick(meeting.id)
                                                }
                                            },
                                            onLongClick = {
                                                selectedIds = if (isSelected) selectedIds - meeting.id else selectedIds + meeting.id
                                            },
                                            onToggleFavorite = { onToggleFavorite(meeting) },
                                            onRename = {
                                                renameTarget = meeting
                                                renameText = meeting.judul
                                            },
                                            onEditTags = {
                                                tagTarget = meeting
                                                tagInput = meeting.tags.joinToString(", ")
                                            },
                                            onShareMinutes = {
                                                ExportUtils.shareTextFile(
                                                    context,
                                                    "notulen-${meeting.id}.txt",
                                                    ExportUtils.buildMinutesText(meeting)
                                                )
                                            },
                                            onExportPdf = { ExportUtils.exportAndSharePdf(context, meeting) },
                                            onExportTxt = {
                                                ExportUtils.shareTextFile(
                                                    context,
                                                    "transkrip-${meeting.id}.txt",
                                                    ExportUtils.buildTranscriptTxt(meeting)
                                                )
                                            },
                                            onExportSrt = {
                                                ExportUtils.shareTextFile(
                                                    context,
                                                    "transkrip-${meeting.id}.srt",
                                                    ExportUtils.buildTranscriptSrt(meeting)
                                                )
                                            },
                                            onCopySummary = {
                                                ExportUtils.copyToClipboard(
                                                    context,
                                                    "Ringkasan ${meeting.judul}",
                                                    meeting.ringkasan.ifBlank { ExportUtils.buildMinutesText(meeting) }
                                                )
                                                onNotify("Ringkasan disalin ke papan klip.")
                                            },
                                            onDelete = { deleteTarget = meeting }
                                        )
                                    }
                                }
                            }
                            if (filtered.size > pageLimit) {
                                item {
                                    OutlinedButton(
                                        onClick = { pageLimit += 20 },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Muat lebih banyak (${filtered.size - pageLimit} tersisa)")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // FAB "Rekam"
        ExtendedFloatingActionButton(
            onClick = onRecordFirst,
            icon = { Icon(Icons.Rounded.Mic, contentDescription = null) },
            text = { Text("Rekam", fontWeight = FontWeight.Bold) },
            containerColor = BrandBlue,
            contentColor = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(18.dp)
                .testTag("history_record_fab")
        )
    }

    // Advanced Filter BottomSheet
    if (showFilterSheet && meetingsState is UiState.Success) {
        val allMeetings = meetingsState.data
        val allSpeakers = remember(allMeetings) {
            allMeetings.flatMap { m -> m.segments.map { it.pembicara } }.distinct().take(12)
        }
        val allTopics = remember(allMeetings) {
            allMeetings.flatMap { m -> m.tags + m.analysis?.topik.orEmpty() }.distinct().take(12)
        }
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Filter Rapat", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                Text("Rentang Waktu", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "Semua", "7d" to "7 Hari", "30d" to "30 Hari", "90d" to "90 Hari").forEach { (k, l) ->
                        FilterChip(selected = dateFilter == k, onClick = { dateFilter = k }, label = { Text(l) })
                    }
                }

                // Meetings are strictly personal per logged-in user in Supabase

                if (allSpeakers.isNotEmpty()) {
                    Text("Pembicara", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        allSpeakers.forEach { sp ->
                            FilterChip(
                                selected = speakerFilter == sp,
                                onClick = { speakerFilter = if (speakerFilter == sp) "" else sp },
                                label = { Text(sp) }
                            )
                        }
                    }
                }

                if (allTopics.isNotEmpty()) {
                    Text("Topik & Tag", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        allTopics.forEach { tp ->
                            FilterChip(
                                selected = topicFilter == tp,
                                onClick = { topicFilter = if (topicFilter == tp) "" else tp },
                                label = { Text(tp) }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Hanya rapat dengan Action Items", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = onlyWithActions, onCheckedChange = { onlyWithActions = it })
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            dateFilter = "all"
                            ownerFilter = "all"
                            speakerFilter = ""
                            topicFilter = ""
                            onlyWithActions = false
                            showFilterSheet = false
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Reset") }
                    Button(
                        onClick = { showFilterSheet = false },
                        modifier = Modifier.weight(1f)
                    ) { Text("Terapkan") }
                }
            }
        }
    }

    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Ganti Nama Rapat") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Judul rapat") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val t = renameTarget ?: return@Button
                    onRenameMeeting(t, renameText)
                    renameTarget = null
                }) { Text("Simpan") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Batal") }
            }
        )
    }

    if (tagTarget != null) {
        AlertDialog(
            onDismissRequest = { tagTarget = null },
            title = { Text("Atur Tag Rapat") },
            text = {
                OutlinedTextField(
                    value = tagInput,
                    onValueChange = { tagInput = it },
                    label = { Text("Pisahkan dengan koma (misal: Sprint, Klien)") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val t = tagTarget ?: return@Button
                    val list = tagInput.split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct()
                    onUpdateTags(t, list)
                    tagTarget = null
                }) { Text("Simpan") }
            },
            dismissButton = {
                TextButton(onClick = { tagTarget = null }) { Text("Batal") }
            }
        )
    }

    if (showBulkTagDialog) {
        val allList = (meetingsState as? UiState.Success)?.data.orEmpty()
        val selectedMeetings = allList.filter { it.id in selectedIds }
        AlertDialog(
            onDismissRequest = { showBulkTagDialog = false },
            title = { Text("Beri Tag (${selectedMeetings.size} Rapat)") },
            text = {
                OutlinedTextField(
                    value = bulkTagText,
                    onValueChange = { bulkTagText = it },
                    label = { Text("Nama tag") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    onBulkAddTag(selectedMeetings, bulkTagText)
                    bulkTagText = ""
                    showBulkTagDialog = false
                    selectedIds = emptySet()
                }) { Text("Terapkan") }
            },
            dismissButton = {
                TextButton(onClick = { showBulkTagDialog = false }) { Text("Batal") }
            }
        )
    }

    if (showBulkDeleteConfirm) {
        val allList = (meetingsState as? UiState.Success)?.data.orEmpty()
        val selectedMeetings = allList.filter { it.id in selectedIds }
        ConfirmDialog(
            title = "Hapus ${selectedMeetings.size} Rapat?",
            message = "Semua audio, transkrip, dan notulen rapat terpilih akan dihapus permanen.",
            confirmText = "Hapus Semua",
            isDestructive = true,
            onConfirm = {
                onBulkDelete(selectedMeetings)
                selectedIds = emptySet()
                showBulkDeleteConfirm = false
            },
            onDismiss = { showBulkDeleteConfirm = false }
        )
    }

    if (deleteTarget != null) {
        ConfirmDialog(
            title = "Hapus Rapat?",
            message = "Rapat \"${deleteTarget!!.judul}\" beserta audio, transkrip, dan notulennya akan dihapus permanen.",
            confirmText = "Hapus",
            isDestructive = true,
            onConfirm = {
                onDeleteMeeting(deleteTarget!!)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }
}

@Composable
private fun SummaryStripPill(
    label: String,
    value: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = color.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.25f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RichMeetingHistoryItem(
    meeting: Meeting,
    isCardMode: Boolean,
    searchQuery: String,
    multiSelectActive: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onEditTags: () -> Unit,
    onShareMinutes: () -> Unit,
    onExportPdf: () -> Unit,
    onExportTxt: () -> Unit,
    onExportSrt: () -> Unit,
    onCopySummary: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val speakers = remember(meeting.segments) { meeting.segments.map { it.pembicara }.distinct() }
    val chips = remember(meeting.tags, meeting.analysis) {
        (meeting.tags + meeting.analysis?.topik.orEmpty()).distinct().take(2)
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
        ),
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (multiSelectActive) {
                    Checkbox(checked = isSelected, onCheckedChange = { onClick() })
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = highlightKeyword(meeting.judul, searchQuery),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (isCardMode) 2 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${ExportUtils.formatDate(meeting.createdAt?.toDate())} • ${ExportUtils.formatDuration(meeting.durasiDetik)} • ${meeting.ownerName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = if (meeting.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = "Favorit",
                        tint = if (meeting.isFavorite) StatusAmber else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusBadge(meeting.status)
                Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Opsi rapat")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("Buka") }, onClick = { menuExpanded = false; onClick() })
                        DropdownMenuItem(text = { Text("Ganti nama") }, onClick = { menuExpanded = false; onRename() })
                        DropdownMenuItem(text = { Text("Atur tag") }, onClick = { menuExpanded = false; onEditTags() })
                        DropdownMenuItem(text = { Text("Bagikan notulen") }, onClick = { menuExpanded = false; onShareMinutes() })
                        DropdownMenuItem(text = { Text("Ekspor PDF") }, onClick = { menuExpanded = false; onExportPdf() })
                        DropdownMenuItem(text = { Text("Ekspor TXT") }, onClick = { menuExpanded = false; onExportTxt() })
                        DropdownMenuItem(text = { Text("Ekspor SRT") }, onClick = { menuExpanded = false; onExportSrt() })
                        DropdownMenuItem(text = { Text("Salin ringkasan") }, onClick = { menuExpanded = false; onCopySummary() })
                        DropdownMenuItem(
                            text = { Text("Hapus", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuExpanded = false; onDelete() }
                        )
                    }
                }
            }

            if (isCardMode && meeting.ringkasan.isNotBlank()) {
                Text(
                    text = meeting.ringkasan,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (searchQuery.isNotBlank() && meeting.transcriptText.contains(searchQuery, ignoreCase = true)) {
                val idx = meeting.transcriptText.lowercase().indexOf(searchQuery.lowercase())
                val snippet = meeting.transcriptText.substring(
                    (idx - 20).coerceAtLeast(0),
                    (idx + 80).coerceAtMost(meeting.transcriptText.length)
                )
                Text(
                    text = highlightKeyword("...$snippet...", searchQuery),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2
                )
            }

            if (isCardMode || chips.isNotEmpty() || speakers.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        AvatarStack(names = speakers.ifEmpty { listOf(meeting.ownerName) })
                        chips.forEach { TopicChip(it) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (meeting.actionItems.isNotEmpty()) {
                            Text(
                                text = "${meeting.actionItems.size} tugas",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (isCardMode) {
                            WaveformMini(seed = meeting.id.ifBlank { meeting.judul }, barCount = 10)
                        }
                    }
                }
            }
        }
    }
}

private fun highlightKeyword(text: String, query: String): AnnotatedString {
    val q = query.trim()
    if (q.isBlank()) return AnnotatedString(text)
    val lowerText = text.lowercase()
    val lowerQ = q.lowercase()
    return buildAnnotatedString {
        append(text)
        var start = 0
        while (true) {
            val idx = lowerText.indexOf(lowerQ, start)
            if (idx < 0) break
            addStyle(
                style = SpanStyle(
                    background = StatusAmber.copy(alpha = 0.35f),
                    fontWeight = FontWeight.Bold
                ),
                start = idx,
                end = idx + q.length
            )
            start = idx + q.length
        }
    }
}

private fun groupMeetingsByDate(meetings: List<Meeting>): List<Pair<String, List<Meeting>>> {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val todayStart = cal.timeInMillis / 1000L
    val yesterdayStart = todayStart - 86400L
    val weekStart = todayStart - 7 * 86400L
    val monthStart = todayStart - 30 * 86400L

    val buckets = linkedMapOf<String, MutableList<Meeting>>()
    meetings.forEach { m ->
        val ts = m.createdAt?.seconds ?: (System.currentTimeMillis() / 1000L)
        val group = when {
            ts >= todayStart -> "Hari ini"
            ts >= yesterdayStart -> "Kemarin"
            ts >= weekStart -> "Minggu ini"
            ts >= monthStart -> "Bulan ini"
            else -> "Lebih lama"
        }
        buckets.getOrPut(group) { mutableListOf() }.add(m)
    }
    return buckets.entries.map { it.key to it.value }
}
