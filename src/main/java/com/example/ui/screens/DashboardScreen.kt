package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notulenrapat.android.R
import com.example.data.ai.PeriodicSummaryResult
import com.example.data.model.AiConnection
import com.example.data.model.AuditLog
import com.example.data.model.Meeting
import com.example.data.model.ModelPrice
import com.example.data.model.Tenant
import com.example.data.model.UsageLog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import com.example.export.ExportUtils
import com.example.ui.components.HeroGreetingBanner
import com.example.ui.components.NotulenCard
import com.example.ui.components.PrimaryGradientButton
import com.example.ui.components.ProgressRing
import com.example.ui.components.SectionHeader
import com.example.ui.components.SkeletonCard
import com.example.ui.components.SparklineStatCard
import com.example.ui.components.StatusBadge
import com.example.ui.components.TopicChip
import com.example.ui.components.AvatarStack
import com.example.ui.components.WaveformMini
import com.example.ui.components.WeeklyActivityBarChart
import com.example.ui.theme.BrandBlue
import com.example.ui.theme.BrandCyan
import com.example.ui.theme.BrandIndigo
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusEmerald
import com.example.ui.theme.StatusRose
import com.example.ui.viewmodel.UiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    displayName: String,
    photoUrl: String? = null,
    activeTenant: Tenant?,
    isAdminOrOwner: Boolean,
    connectionHealthy: Boolean?,
    connections: List<AiConnection> = emptyList(),
    usageLogs: List<UsageLog> = emptyList(),
    modelPrices: List<ModelPrice> = emptyList(),
    auditLogs: List<AuditLog> = emptyList(),
    reduceMotion: Boolean = false,
    meetingsState: UiState<List<Meeting>>,
    accountLinkRequired: Boolean = false,
    onRetryLoad: () -> Unit = {},
    onOpenAccountLink: () -> Unit = {},
    onRecordClick: () -> Unit,
    onUploadClick: () -> Unit,
    onAskAiClick: () -> Unit = {},
    onVisualClick: (String) -> Unit = {},
    onMeetingClick: (String) -> Unit,
    onSeeAllMeetings: () -> Unit = {},
    onToggleActionItem: (Meeting, String) -> Unit,
    onRetryMeeting: (Meeting) -> Unit,
    onOpenAdminSettings: (Int) -> Unit,
    onCheckAiHealth: () -> Unit,
    periodicSummaryState: UiState<PeriodicSummaryResult>? = null,
    onGeneratePeriodicSummary: (periodType: String, meetings: List<Meeting>) -> Unit = { _, _ -> },
    onClearPeriodicSummary: () -> Unit = {},
    workspaceLabel: String = activeTenant?.name ?: "Belum ada organisasi",
    workspaceError: String? = null,
    onRetryWorkspace: () -> Unit = {}
) {
    var showAllTasksDialog by remember { mutableStateOf(false) }
    var infoPopup by remember { mutableStateOf<Pair<String, String>?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val firstName = displayName.substringBefore(" ").ifBlank { "Rekan" }
    val greeting = remember(firstName) {
        val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val prefix = when (hr) {
            in 4..10 -> "Selamat Pagi"
            in 11..14 -> "Selamat Siang"
            in 15..17 -> "Selamat Sore"
            else -> "Selamat Malam"
        }
        "$prefix, $firstName"
    }
    val dateLabel = remember {
        SimpleDateFormat("EEE, dd MMM yyyy", Locale("id", "ID")).format(Date())
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            scope.launch {
                isRefreshing = true
                onRetryLoad()
                onCheckAiHealth()
                delay(500L)
                isRefreshing = false
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Hero Greeting Banner
                item {
                    HeroGreetingBanner(
                        greeting = greeting,
                        dateText = dateLabel,
                        tenantName = workspaceLabel,
                        displayName = displayName,
                        photoUrl = photoUrl,
                        isAdminOrOwner = isAdminOrOwner,
                        connectionHealthy = connectionHealthy,
                        onAiBadgeClick = {
                            if (connectionHealthy == false) onOpenAdminSettings(0) else onCheckAiHealth()
                        }
                    )
                }

                if (workspaceError != null) {
                    item {
                        NotulenCard {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Workspace tidak dapat dimuat", style = MaterialTheme.typography.titleSmall)
                                Text(workspaceError, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = onRetryWorkspace) { Text("Muat ulang workspace") }
                            }
                        }
                    }
                }

                // 2. 4 Quick Action Gradient Tiles
                item {
                    val readyMeetingId = (meetingsState as? UiState.Success)?.data
                        ?.firstOrNull { it.status == "done" || it.ringkasan.isNotBlank() }?.id
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            QuickActionTile(
                                label = "Rekam",
                                icon = Icons.Rounded.Mic,
                                gradient = listOf(BrandBlue, BrandIndigo),
                                onClick = onRecordClick,
                                modifier = Modifier.weight(1f).testTag("dashboard_record_button")
                            )
                            QuickActionTile(
                                label = "Unggah",
                                icon = Icons.Rounded.UploadFile,
                                gradient = listOf(BrandIndigo, BrandCyan),
                                onClick = onUploadClick,
                                modifier = Modifier.weight(1f).testTag("dashboard_upload_button")
                            )
                            QuickActionTile(
                                label = "Tanya AI",
                                icon = Icons.Rounded.AutoAwesome,
                                gradient = listOf(StatusEmerald, BrandCyan),
                                onClick = onAskAiClick,
                                modifier = Modifier.weight(1f).testTag("dashboard_qa_button")
                            )
                            QuickActionTile(
                                label = "Visual",
                                icon = Icons.Rounded.Image,
                                gradient = listOf(StatusAmber, BrandBlue),
                                onClick = {
                                    if (readyMeetingId != null) onVisualClick(readyMeetingId)
                                    else onSeeAllMeetings()
                                },
                                modifier = Modifier.weight(1f).testTag("dashboard_visual_button")
                            )
                        }
                    }
                }

                when (meetingsState) {
                    is UiState.Loading -> {
                        item { SkeletonCard() }
                        item { SkeletonCard() }
                    }
                    is UiState.Error -> {
                        item {
                            NotulenCard {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Gagal Memuat Rapat", style = MaterialTheme.typography.titleMedium, color = StatusRose)
                                    Text(meetingsState.message, style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(
                                            onClick = onRetryLoad,
                                            modifier = Modifier.testTag("dashboard_retry_button")
                                        ) {
                                            Text("Coba lagi")
                                        }
                                        if (accountLinkRequired) {
                                            TextButton(onClick = onOpenAccountLink) {
                                                Text("Tautkan Akun")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    is UiState.Success -> {
                        val allMeetings = meetingsState.data
                        if (allMeetings.isEmpty()) {
                            // 12. Illustrated Onboarding Checklist Empty State
                            item {
                                OnboardingEmptyChecklistCard(onStartRecord = onRecordClick)
                            }
                        } else {
                            val totalHours = (allMeetings.sumOf { it.durasiDetik } / 3600.0).toFloat()
                            val allActions = allMeetings.flatMap { m -> m.actionItems.map { m to it } }
                            val openActions = allActions.filter { !it.second.selesai }
                            val doneActionsCount = allActions.count { it.second.selesai }
                            val totalDecisions = allMeetings.sumOf { it.keputusan.size }
                            val processingMeetings = allMeetings.filter {
                                it.status in listOf("uploaded", "transcribing", "summarizing", "error")
                            }

                            val nowCal = Calendar.getInstance()
                            val curMonth = nowCal.get(Calendar.MONTH)
                            val curYear = nowCal.get(Calendar.YEAR)
                            val monthMinutes = allMeetings.filter { m ->
                                val d = m.createdAt?.toDate() ?: return@filter false
                                val c = Calendar.getInstance().apply { time = d }
                                c.get(Calendar.MONTH) == curMonth && c.get(Calendar.YEAR) == curYear
                            }.sumOf { it.durasiDetik } / 60f

                            val dailyData = computeLast7Days(allMeetings)
                            val sparkCounts = dailyData.map { it.second.toFloat() }
                            val sparkHours = dailyData.map { it.third }
                            val weekDelta = computeWeekDeltaPercent(allMeetings)

                            // 3. Horizontal SparklineStatCard Strip
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SectionHeader(
                                        title = "Ringkasan Produktivitas",
                                        onInfoClick = {
                                            infoPopup = "Ringkasan Produktivitas" to
                                                "Statistik dihitung langsung dari riwayat rapat organisasi Anda dalam 7 hari terakhir dan bulan berjalan."
                                        }
                                    )
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        contentPadding = PaddingValues(end = 4.dp)
                                    ) {
                                        item {
                                            SparklineStatCard(
                                                title = "Total Rapat",
                                                numericValue = allMeetings.size.toFloat(),
                                                deltaPercent = weekDelta,
                                                sparklinePoints = sparkCounts,
                                                icon = Icons.Rounded.Groups,
                                                tint = BrandBlue,
                                                reduceMotion = reduceMotion
                                            )
                                        }
                                        item {
                                            SparklineStatCard(
                                                title = "Jam Rapat",
                                                numericValue = totalHours,
                                                isDecimal = true,
                                                unitSuffix = " j",
                                                sparklinePoints = sparkHours,
                                                icon = Icons.Rounded.Schedule,
                                                tint = BrandIndigo,
                                                reduceMotion = reduceMotion
                                            )
                                        }
                                        item {
                                            SparklineStatCard(
                                                title = "Tugas Terbuka",
                                                numericValue = openActions.size.toFloat(),
                                                sparklinePoints = sparkCounts,
                                                icon = Icons.Rounded.Checklist,
                                                tint = StatusAmber,
                                                reduceMotion = reduceMotion
                                            )
                                        }
                                        item {
                                            SparklineStatCard(
                                                title = "Menit Bulan Ini",
                                                numericValue = monthMinutes,
                                                unitSuffix = " m",
                                                sparklinePoints = sparkHours,
                                                icon = Icons.Rounded.Timer,
                                                tint = BrandCyan,
                                                reduceMotion = reduceMotion
                                            )
                                        }
                                        item {
                                            SparklineStatCard(
                                                title = "Keputusan",
                                                numericValue = totalDecisions.toFloat(),
                                                sparklinePoints = sparkCounts,
                                                icon = Icons.Rounded.Gavel,
                                                tint = StatusEmerald,
                                                reduceMotion = reduceMotion
                                            )
                                        }
                                    }
                                }
                            }

                            // Active Processing / Error Queue
                            if (processingMeetings.isNotEmpty()) {
                                item {
                                    NotulenCard {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "Antrean Proses",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                            processingMeetings.forEach { m ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable { onMeetingClick(m.id) }
                                                        .padding(vertical = 2.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = m.judul,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    StatusBadge(m.status)
                                                    if (m.status == "error") {
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        TextButton(onClick = { onRetryMeeting(m) }) {
                                                            Text("Ulangi")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // 4. 7-Day Activity Bar Chart
                            item {
                                WeeklyActivityBarChart(dailyData = dailyData)
                            }

                            // 4b. Ringkasan Mingguan & Bulanan AI (Strictly user's own meetings in selected range)
                            item {
                                NotulenCard {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Rounded.AutoAwesome,
                                                    contentDescription = null,
                                                    tint = BrandBlue,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Ringkasan Berkala AI",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            if (periodicSummaryState != null) {
                                                TextButton(onClick = onClearPeriodicSummary) {
                                                    Text("Tutup")
                                                }
                                            }
                                        }
                                        Text(
                                            text = "Rangkum keputusan, tema, dan tindak lanjut dari rapat akun Anda pada rentang waktu pilihan.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedButton(
                                                enabled = periodicSummaryState !is UiState.Loading,
                                                onClick = { onGeneratePeriodicSummary("weekly", allMeetings) },
                                                modifier = Modifier.weight(1f).testTag("periodic_summary_weekly_button")
                                            ) {
                                                Text("Mingguan (7 Hr)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            OutlinedButton(
                                                enabled = periodicSummaryState !is UiState.Loading,
                                                onClick = { onGeneratePeriodicSummary("monthly", allMeetings) },
                                                modifier = Modifier.weight(1f).testTag("periodic_summary_monthly_button")
                                            ) {
                                                Text("Bulanan (30 Hr)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                        when (val ps = periodicSummaryState) {
                                            is UiState.Loading -> {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = "Menyusun ringkasan berkala...",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                            is UiState.Error -> {
                                                Text(
                                                    text = ps.message,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = StatusRose
                                                )
                                            }
                                            is UiState.Success -> {
                                                val res = ps.data
                                                Surface(
                                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                                    shape = RoundedCornerShape(12.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Column(
                                                        modifier = Modifier.padding(12.dp),
                                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Text(
                                                            text = "${res.periodeLabel} • ${res.rentangTanggal} (${res.jumlahRapat} rapat)",
                                                            style = MaterialTheme.typography.labelMedium,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                        Text(
                                                            text = res.ringkasanEksekutif,
                                                            style = MaterialTheme.typography.bodySmall
                                                        )
                                                        if (res.keputusanPenting.isNotEmpty()) {
                                                            Text("Keputusan Penting:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                                            res.keputusanPenting.take(4).forEach { k ->
                                                                Text("• $k", style = MaterialTheme.typography.bodySmall)
                                                            }
                                                        }
                                                        if (res.tindakLanjutPrioritas.isNotEmpty()) {
                                                            Text("Tindak Lanjut Prioritas:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                                            res.tindakLanjutPrioritas.take(4).forEach { t ->
                                                                Text("• $t", style = MaterialTheme.typography.bodySmall)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            null -> Unit
                                        }
                                    }
                                }
                            }

                            // 5. ProgressRing + Urgent Tasks Card
                            if (allActions.isNotEmpty()) {
                                item {
                                    val totalActs = allActions.size.coerceAtLeast(1)
                                    val pct = doneActionsCount.toFloat() / totalActs
                                    val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                                    val urgent = openActions.sortedBy {
                                        it.second.deadline.ifBlank { "9999-12-31" }
                                    }.take(3)

                                    NotulenCard {
                                        Column(
                                            modifier = Modifier.padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                ProgressRing(
                                                    progress = pct,
                                                    centerLabel = "${(pct * 100).toInt()}%",
                                                    sizeDp = 76,
                                                    reduceMotion = reduceMotion
                                                )
                                                Spacer(modifier = Modifier.width(14.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "Progres Tindak Lanjut",
                                                        style = MaterialTheme.typography.titleMedium,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = "$doneActionsCount selesai dari ${allActions.size} tugas (${openActions.size} terbuka)",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                if (openActions.size > 3) {
                                                    TextButton(onClick = { showAllTasksDialog = true }) {
                                                        Text("Semua")
                                                    }
                                                }
                                            }

                                            urgent.forEach { (meeting, item) ->
                                                val overdue = item.deadline.isNotBlank() &&
                                                    Regex("\\d{4}-\\d{2}-\\d{2}").matches(item.deadline) &&
                                                    item.deadline < todayStr
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .clickable { onMeetingClick(meeting.id) },
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Checkbox(
                                                        checked = item.selesai,
                                                        onCheckedChange = { onToggleActionItem(meeting, item.id) }
                                                    )
                                                    Text(
                                                        text = item.tugas,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    if (item.deadline.isNotBlank()) {
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text(
                                                            text = item.deadline,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = if (overdue) StatusRose else MaterialTheme.colorScheme.onSurfaceVariant,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // 6. Conditional Insight AI Card (only if real analysis data exists)
                            val topTopics = allMeetings.flatMap { it.analysis?.topik.orEmpty() }
                                .map { it.trim() }.filter { it.isNotBlank() }
                                .groupingBy { it }.eachCount()
                                .entries.sortedByDescending { it.value }
                                .take(4).map { it.key }
                            val latestMood = allMeetings.firstNotNullOfOrNull {
                                it.analysis?.suasana?.takeIf { s -> s.isNotBlank() }
                            }
                            val openQuestionsCount = allMeetings.sumOf {
                                it.analysis?.pertanyaanTerbuka?.size ?: 0
                            }
                            if (topTopics.isNotEmpty() || latestMood != null) {
                                item {
                                    NotulenCard {
                                        Column(
                                            modifier = Modifier.padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(30.dp)
                                                        .clip(RoundedCornerShape(9.dp))
                                                        .background(BrandIndigo.copy(alpha = 0.14f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Rounded.Lightbulb, contentDescription = null, tint = BrandIndigo, modifier = Modifier.size(18.dp))
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Insight AI Rapat", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                            }
                                            if (latestMood != null) {
                                                Text(
                                                    text = "Dinamika terbaru: $latestMood" +
                                                        if (openQuestionsCount > 0) " • $openQuestionsCount pertanyaan terbuka" else "",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            if (topTopics.isNotEmpty()) {
                                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    items(topTopics) { topic ->
                                                        TopicChip(text = topic)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // 7. Horizontal Swipeable Rapat Terbaru Cards
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SectionHeader(
                                        title = "Rapat Terbaru",
                                        actionLabel = "Lihat semua",
                                        onActionClick = onSeeAllMeetings
                                    )
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        contentPadding = PaddingValues(end = 4.dp)
                                    ) {
                                        items(allMeetings.take(6), key = { it.id }) { m ->
                                            RecentMeetingCarouselCard(
                                                meeting = m,
                                                onClick = { onMeetingClick(m.id) }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 8. Kuota & Pemakaian Card (Admin/Owner only)
                        if (isAdminOrOwner && activeTenant != null) {
                            item {
                                val totalAudioMin = usageLogs.sumOf { it.audioSeconds } / 60.0
                                val totalReq = usageLogs.size
                                val hasConfiguredPrice = modelPrices.any { it.inputPer1M > 0.0 || it.outputPer1M > 0.0 }
                                val totalCost = usageLogs.sumOf { it.estimatedCostUsd }
                                val budget = activeTenant.monthlyBudgetUsd.coerceAtLeast(1.0)
                                val pct = if (hasConfiguredPrice) (totalCost / budget).toFloat().coerceIn(0f, 1f) else 0f

                                NotulenCard(onClick = { onOpenAdminSettings(4) }) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Kuota & Pemakaian", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                            Text("Kelola", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                        }
                                        Text(
                                            text = "$totalReq request AI/STT • ${String.format(Locale.US, "%.1f", totalAudioMin)} menit audio",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (hasConfiguredPrice) {
                                            Text(
                                                text = "Estimasi: ${String.format(Locale.US, "$%.4f", totalCost)} / ${String.format(Locale.US, "$%.2f", budget)}",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (pct >= 0.8f) StatusAmber else StatusEmerald
                                            )
                                            LinearProgressIndicator(
                                                progress = { pct },
                                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp))
                                            )
                                        } else {
                                            Text(
                                                text = "Tarif model belum diatur • Ketuk untuk atur harga token",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 9. Aktivitas Terbaru Compact Timeline
                        if (allMeetings.isNotEmpty() || auditLogs.isNotEmpty()) {
                            item {
                                NotulenCard {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text("Aktivitas Terbaru", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        val timelineItems = remember(allMeetings, auditLogs, isAdminOrOwner) {
                                            if (isAdminOrOwner && auditLogs.isNotEmpty()) {
                                                auditLogs.take(4).map { log ->
                                                    Triple(log.aksi.replace("_", " "), log.target, ExportUtils.formatDate(log.createdAt?.toDate()))
                                                }
                                            } else {
                                                allMeetings.take(4).map { m ->
                                                    val label = if (m.status == "done") "Notulen selesai" else "Rapat ${m.status}"
                                                    Triple(label, m.judul, ExportUtils.formatDate(m.createdAt?.toDate()))
                                                }
                                            }
                                        }
                                        timelineItems.forEach { (title, sub, timeStr) ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(BrandBlue)
                                                )
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(timeStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 10. Status Sistem Card (Admin/Owner)
                        if (isAdminOrOwner) {
                            item {
                                val aiCount = connections.count { it.kind == "ai" && it.enabled }
                                val sttCount = connections.count { it.kind == "stt" && it.enabled }
                                NotulenCard {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text("Status Sistem", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            SystemStatusChip(
                                                label = "AI ($aiCount)",
                                                healthy = connectionHealthy != false && aiCount > 0,
                                                onClick = { onOpenAdminSettings(0) },
                                                modifier = Modifier.weight(1f)
                                            )
                                            SystemStatusChip(
                                                label = "STT ($sttCount)",
                                                healthy = sttCount > 0,
                                                onClick = { onOpenAdminSettings(1) },
                                                modifier = Modifier.weight(1f)
                                            )
                                            SystemStatusChip(
                                                label = "Cloud Sync",
                                                healthy = activeTenant != null,
                                                onClick = { onOpenAdminSettings(5) },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }

    if (showAllTasksDialog && meetingsState is UiState.Success) {
        val openActionItems = meetingsState.data.flatMap { m ->
            m.actionItems.filter { !it.selesai }.map { m to it }
        }
        AlertDialog(
            onDismissRequest = { showAllTasksDialog = false },
            title = { Text("Semua Tugas Terbuka", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    openActionItems.take(12).forEach { (meeting, item) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = item.selesai,
                                onCheckedChange = { onToggleActionItem(meeting, item.id) }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.tugas, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                Text(
                                    text = "${meeting.judul}${if (item.deadline.isNotBlank()) " • ${item.deadline}" else ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAllTasksDialog = false }) { Text("Tutup") }
            }
        )
    }

    if (infoPopup != null) {
        AlertDialog(
            onDismissRequest = { infoPopup = null },
            title = { Text(infoPopup!!.first, fontWeight = FontWeight.Bold) },
            text = { Text(infoPopup!!.second, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { infoPopup = null }) { Text("Mengerti") }
            }
        )
    }
}

@Composable
private fun QuickActionTile(
    label: String,
    icon: ImageVector,
    gradient: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        modifier = modifier.height(82.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(gradient))
                .padding(10.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = label,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun RecentMeetingCarouselCard(
    meeting: Meeting,
    onClick: () -> Unit
) {
    val speakers = remember(meeting.segments) {
        meeting.segments.map { it.pembicara }.distinct()
    }
    val topics = remember(meeting.analysis, meeting.tags) {
        (meeting.tags + meeting.analysis?.topik.orEmpty()).distinct().take(2)
    }
    NotulenCard(
        onClick = onClick,
        modifier = Modifier.width(264.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusBadge(meeting.status)
                WaveformMini(seed = meeting.id.ifBlank { meeting.judul })
            }
            Text(
                text = meeting.judul,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${ExportUtils.formatDate(meeting.createdAt?.toDate())} • ${ExportUtils.formatDuration(meeting.durasiDetik)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            if (topics.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    topics.forEach { TopicChip(it) }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AvatarStack(names = speakers.ifEmpty { listOf(meeting.ownerName) })
                Text(
                    text = "${meeting.actionItems.size} tugas",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun SystemStatusChip(
    label: String,
    healthy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = if (healthy) StatusEmerald else StatusAmber
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(color))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun OnboardingEmptyChecklistCard(onStartRecord: () -> Unit) {
    NotulenCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(
                painter = painterResource(id = R.drawable.img_onboarding_meeting_1795280581520),
                contentDescription = "Ilustrasi ruang rapat AI Notulen Rapat",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .clip(RoundedCornerShape(16.dp))
            )
            Text(
                text = "Mulai Rapat Pertama Anda",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            val steps = listOf(
                "1. Rekam langsung atau unggah audio rapat",
                "2. AI menyusun transkrip & notulen terstruktur",
                "3. Tanya AI lintas rapat atau ekspor visual 4:5"
            )
            steps.forEach { line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = StatusEmerald, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
            PrimaryGradientButton(
                text = "Rekam Rapat Pertama",
                icon = Icons.Rounded.Mic,
                onClick = onStartRecord,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun computeLast7Days(meetings: List<Meeting>): List<Triple<String, Int, Float>> {
    val cal = Calendar.getInstance()
    val dayFmt = SimpleDateFormat("EEE", Locale("id", "ID"))
    val result = mutableListOf<Triple<String, Int, Float>>()
    for (daysAgo in 6 downTo 0) {
        val c = (cal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -daysAgo)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startSec = c.timeInMillis / 1000L
        val endSec = startSec + 86400L - 1L
        val dayMeetings = meetings.filter { m ->
            val ts = m.createdAt?.seconds ?: return@filter false
            ts in startSec..endSec
        }
        val hrs = (dayMeetings.sumOf { it.durasiDetik } / 3600f)
        result.add(Triple(dayFmt.format(c.time).take(3), dayMeetings.size, hrs))
    }
    return result
}

private fun computeWeekDeltaPercent(meetings: List<Meeting>): Int? {
    val nowSec = System.currentTimeMillis() / 1000L
    val weekSec = 7 * 86400L
    val thisWeek = meetings.count { (it.createdAt?.seconds ?: 0L) >= nowSec - weekSec }
    val prevWeek = meetings.count {
        val ts = it.createdAt?.seconds ?: 0L
        ts in (nowSec - 2 * weekSec) until (nowSec - weekSec)
    }
    if (prevWeek == 0 && thisWeek == 0) return null
    if (prevWeek == 0) return 100
    return (((thisWeek - prevWeek).toFloat() / prevWeek) * 100f).toInt()
}
