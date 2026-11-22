package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.Meeting
import com.example.data.model.Tenant
import com.example.data.model.UsageLog
import com.example.data.model.UserProfile
import com.example.export.ExportUtils
import com.example.ui.components.ConfirmDialog
import com.example.ui.components.NotulenCard
import com.example.ui.components.UserAvatar
import com.example.ui.theme.BrandBlue
import com.example.ui.theme.BrandCyan
import com.example.ui.theme.BrandIndigo
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusEmerald
import com.example.ui.theme.StatusRose
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.Locale

private enum class ProfileSheetType {
    SECURITY, USAGE, PREFERENCES, NOTIFICATIONS, PRIVACY, HELP
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    profile: UserProfile?,
    activeTenant: Tenant?,
    roleInTenant: String,
    isAdminOrOwner: Boolean,
    isPlatformAdmin: Boolean,
    customAvatarPath: String? = null,
    meetings: List<Meeting> = emptyList(),
    usageLogs: List<UsageLog> = emptyList(),
    themeMode: String,
    defaultTranscriptLang: String = "id",
    defaultExportFormat: String = "PDF",
    defaultVisualStyle: String = "CARDS",
    defaultHistoryView: String = "list",
    reduceMotion: Boolean = false,
    onUpdateDisplayName: (String) -> Unit,
    onUpdateUsername: (String) -> Unit = {},
    onUpdateAvatarPath: (String?) -> Unit = {},
    onOpenTenantSwitcher: () -> Unit,
    onCreateOrganization: (String) -> Unit,
    onThemeModeChange: (String) -> Unit,
    onDefaultTranscriptLangChange: (String) -> Unit = {},
    onDefaultExportFormatChange: (String) -> Unit = {},
    onDefaultVisualStyleChange: (String) -> Unit = {},
    onDefaultHistoryViewChange: (String) -> Unit = {},
    onReduceMotionChange: (Boolean) -> Unit = {},
    onOpenAdminHub: (Int) -> Unit,
    onDeleteAccount: () -> Unit = {},
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    var activeSheet by remember { mutableStateOf<ProfileSheetType?>(null) }
    var showEditProfile by remember { mutableStateOf(false) }
    var nameInput by remember(profile?.displayName) { mutableStateOf(profile?.displayName.orEmpty()) }
    var usernameInput by remember(profile?.username) { mutableStateOf(profile?.username.orEmpty()) }
    var showCreateOrg by remember { mutableStateOf(false) }
    var newOrgName by remember { mutableStateOf("") }
    var showDeleteAccountTypedConfirm by remember { mutableStateOf(false) }
    var deleteConfirmText by remember { mutableStateOf("") }
    var showSignOutConfirm by remember { mutableStateOf(false) }

    val avatarPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val dest = File(context.filesDir, "avatar_${profile?.userId ?: "user"}.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
                dest.absolutePath
            }.onSuccess { path ->
                onUpdateAvatarPath(path)
            }
        }
    }

    val effectivePhoto = customAvatarPath?.takeIf { File(it).exists() } ?: profile?.photoUrl

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
            // 1. Hero Profile Card with Photo Picker Avatar
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                shadowElevation = 2.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                listOf(BrandBlue.copy(alpha = 0.10f), BrandIndigo.copy(alpha = 0.06f))
                            )
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.clickable {
                                avatarPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        ) {
                            UserAvatar(
                                photoUrl = effectivePhoto,
                                displayName = profile?.displayName ?: "Pengguna",
                                sizeDp = 64
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(BrandBlue),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.CameraAlt,
                                    contentDescription = "Ganti foto profil",
                                    tint = Color.White,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profile?.displayName ?: "Pengguna",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            if (!profile?.username.isNullOrBlank()) {
                                Text(
                                    text = "@${profile!!.username}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = profile?.email.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = roleInTenant.uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                                if (isPlatformAdmin) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = "PLATFORM ADMIN",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                        IconButton(onClick = { showEditProfile = true }) {
                            Icon(Icons.Rounded.Edit, contentDescription = "Ubah profil")
                        }
                    }
                }
            }

            // 2. Organisasi Card
            NotulenCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Business, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Organisasi Aktif", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = activeTenant?.name ?: "Belum ada organisasi",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "${activeTenant?.memberUids?.size ?: 1} anggota • Mode AI: ${if (activeTenant?.aiMode == "byok") "BYOK" else "Platform"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onOpenTenantSwitcher,
                            modifier = Modifier.weight(1f)
                        ) { Text("Ganti") }
                        Button(
                            onClick = { showCreateOrg = true },
                            modifier = Modifier.weight(1f)
                        ) { Text("Buat Baru") }
                    }
                }
            }

            // 3. Admin & Pengaturan Quick Links (Owner/Admin/Platform Admin)
            if (isAdminOrOwner) {
                NotulenCard {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenAdminHub(0) }
                                .testTag("open_admin_hub_button"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AdminPanelSettings,
                                contentDescription = "Admin & Pengaturan",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Admin & Pengaturan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    "Buka langsung ke tab manajemen provider & organisasi",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(onClick = { onOpenAdminHub(0) }, modifier = Modifier.weight(1f)) {
                                Text("Koneksi AI", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(onClick = { onOpenAdminHub(2) }, modifier = Modifier.weight(1f)) {
                                Text("Routing AI", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(onClick = { onOpenAdminHub(4) }, modifier = Modifier.weight(1f)) {
                                Text("Biaya", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            // 4. Grouped Settings Menu
            NotulenCard {
                Column {
                    ProfileMenuRow(
                        icon = Icons.Rounded.Person,
                        tint = BrandBlue,
                        title = "Akun",
                        subtitle = "Nama tampilan, username, dan foto profil",
                        onClick = { showEditProfile = true }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ProfileMenuRow(
                        icon = Icons.Rounded.Security,
                        tint = StatusEmerald,
                        title = "Keamanan",
                        subtitle = "Login terakhir & proteksi Google Auth",
                        onClick = { activeSheet = ProfileSheetType.SECURITY }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ProfileMenuRow(
                        icon = Icons.Rounded.DataUsage,
                        tint = BrandIndigo,
                        title = "Pemakaian Saya",
                        subtitle = "${meetings.size} rapat • ${usageLogs.size} panggilan AI",
                        onClick = { activeSheet = ProfileSheetType.USAGE }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ProfileMenuRow(
                        icon = Icons.Rounded.Tune,
                        tint = BrandCyan,
                        title = "Preferensi",
                        subtitle = "Tema, bahasa transkrip, ekspor, gaya visual, animasi",
                        onClick = { activeSheet = ProfileSheetType.PREFERENCES }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ProfileMenuRow(
                        icon = Icons.Rounded.Notifications,
                        tint = StatusAmber,
                        title = "Notifikasi",
                        subtitle = "Pengingat tindak lanjut & ringkasan",
                        onClick = { activeSheet = ProfileSheetType.NOTIFICATIONS }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ProfileMenuRow(
                        icon = Icons.Rounded.Lock,
                        tint = StatusEmerald,
                        title = "Privasi & Data",
                        subtitle = "Retensi ${activeTenant?.retentionDays ?: 90} hari, ekspor data, hapus akun",
                        onClick = { activeSheet = ProfileSheetType.PRIVACY }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ProfileMenuRow(
                        icon = Icons.AutoMirrored.Rounded.HelpOutline,
                        tint = MaterialTheme.colorScheme.primary,
                        title = "Bantuan & Tentang",
                        subtitle = "${stringResource(id = R.string.app_name)} ${stringResource(id = R.string.app_version)}",
                        onClick = { activeSheet = ProfileSheetType.HELP }
                    )
                }
            }

            // 5. Sign Out
            OutlinedButton(
                onClick = { showSignOutConfirm = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRose),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("sign_out_button")
            ) {
                Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = "Keluar", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Keluar", fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Sub-Sheets
    if (activeSheet != null) {
        ModalBottomSheet(
            onDismissRequest = { activeSheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (activeSheet!!) {
                    ProfileSheetType.SECURITY -> {
                        val lastSignInMs = Firebase.auth.currentUser?.metadata?.lastSignInTimestamp ?: 0L
                        val lastSignInStr = if (lastSignInMs > 0L) ExportUtils.formatDate(Date(lastSignInMs)) else "Sesi aktif"
                        Text("Keamanan Akun", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        InfoStatusCard("Metode Masuk", "Google Sign-In (Credential Manager)", "Aktif", StatusEmerald)
                        InfoStatusCard("Login Terakhir", lastSignInStr, "Terverifikasi", BrandBlue)
                        InfoStatusCard("Autentikasi 2 Faktor (TOTP)", "Dikelola langsung melalui keamanan Akun Google Anda.", "Segera hadir", StatusAmber)
                    }
                    ProfileSheetType.USAGE -> {
                        val totalAudioMin = meetings.sumOf { it.durasiDetik } / 60.0
                        val totalIn = usageLogs.sumOf { it.inputTokens }
                        val totalOut = usageLogs.sumOf { it.outputTokens }
                        Text("Pemakaian Organisasi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        InfoStatusCard("Total Rapat", "${meetings.size} rapat tersimpan", String.format(Locale.US, "%.1f mnt", totalAudioMin), BrandBlue)
                        InfoStatusCard("Panggilan AI & STT", "${usageLogs.size} eksekusi", "${totalIn + totalOut} token", BrandIndigo)
                    }
                    ProfileSheetType.PREFERENCES -> {
                        Text("Preferensi Aplikasi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Tema Tampilan", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        val modes = listOf("system" to "Otomatis", "light" to "Terang", "dark" to "Gelap")
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            modes.forEachIndexed { idx, (key, label) ->
                                SegmentedButton(
                                    selected = themeMode == key,
                                    onClick = { onThemeModeChange(key) },
                                    shape = SegmentedButtonDefaults.itemShape(idx, modes.size)
                                ) { Text(label) }
                            }
                        }

                        Text("Bahasa Transkrip Bawaan", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("id" to "Indonesia", "en" to "Inggris", "auto" to "Otomatis").forEach { (k, l) ->
                                FilterChip(selected = defaultTranscriptLang == k, onClick = { onDefaultTranscriptLangChange(k) }, label = { Text(l) })
                            }
                        }

                        Text("Format Ekspor Bawaan", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("PDF", "TXT", "SRT").forEach { fmt ->
                                FilterChip(selected = defaultExportFormat == fmt, onClick = { onDefaultExportFormatChange(fmt) }, label = { Text(fmt) })
                            }
                        }

                        Text("Gaya Visual Bawaan", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("CARDS" to "Kartu", "MIND_MAP" to "Mind Map", "TIMELINE" to "Alur").forEach { (k, l) ->
                                FilterChip(selected = defaultVisualStyle == k, onClick = { onDefaultVisualStyleChange(k) }, label = { Text(l) })
                            }
                        }

                        Text("Tampilan Riwayat Bawaan", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("list" to "Daftar", "cards" to "Kartu").forEach { (k, l) ->
                                FilterChip(selected = defaultHistoryView == k, onClick = { onDefaultHistoryViewChange(k) }, label = { Text(l) })
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Kurangi Animasi", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text("Matikan animasi angka & cincin progres di Beranda", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = reduceMotion, onCheckedChange = onReduceMotionChange)
                        }
                    }
                    ProfileSheetType.NOTIFICATIONS -> {
                        Text("Notifikasi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        InfoStatusCard("Ringkasan Mingguan (Email)", "Pengiriman rekap rapat mingguan via email.", "Segera hadir", StatusAmber)
                        InfoStatusCard("Pengingat Tenggat Tugas (Push)", "Notifikasi lokal & cloud untuk action item.", "Segera hadir", StatusAmber)
                    }
                    ProfileSheetType.PRIVACY -> {
                        Text("Privasi & Data", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(stringResource(id = R.string.privacy_notice), style = MaterialTheme.typography.bodySmall)
                        InfoStatusCard(
                            "Kebijakan Retensi Organisasi",
                            "Data organisasi dikonfigurasi selama ${activeTenant?.retentionDays ?: 90} hari.",
                            "Aktif",
                            StatusEmerald
                        )
                        Button(
                            onClick = {
                                ExportUtils.exportAllUserData(
                                    context = context,
                                    displayName = profile?.displayName ?: "Pengguna",
                                    email = profile?.email.orEmpty(),
                                    tenantName = activeTenant?.name ?: "Organisasi",
                                    meetings = meetings
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Ekspor Semua Data Saya (.txt)")
                        }
                        OutlinedButton(
                            onClick = {
                                activeSheet = null
                                deleteConfirmText = ""
                                showDeleteAccountTypedConfirm = true
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRose),
                            modifier = Modifier.fillMaxWidth().testTag("delete_account_button")
                        ) {
                            Icon(Icons.Rounded.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Hapus Profil Akun")
                        }
                    }
                    ProfileSheetType.HELP -> {
                        Text("Bantuan & Tentang", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        InfoStatusCard("Tips Perekaman Optimal", stringResource(id = R.string.mic_speaker_tip), "Panduan", BrandBlue)
                        InfoStatusCard("Transparansi AI", stringResource(id = R.string.ai_disclaimer), "Info", BrandIndigo)
                        InfoStatusCard("Bagaimana cara memakai BYOK / Provider Kustom?", "Masuk ke Admin & Pengaturan > Koneksi AI atau Koneksi STT untuk menambah OpenAI, Anthropic, DeepSeek, OpenRouter, atau Sumopod.", "FAQ", StatusEmerald)
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_SENDTO).apply {
                                    data = Uri.parse("mailto:support@notulenrapat.id")
                                    putExtra(Intent.EXTRA_SUBJECT, "Dukungan AI Notulen Rapat")
                                }
                                runCatching { context.startActivity(intent) }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Hubungi Dukungan (support@notulenrapat.id)")
                        }
                    }
                }
            }
        }
    }

    if (showEditProfile) {
        AlertDialog(
            onDismissRequest = { showEditProfile = false },
            title = { Text("Ubah Profil") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Nama tampilan") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = usernameInput,
                        onValueChange = { usernameInput = it.lowercase() },
                        label = { Text("Username (3–20 huruf kecil/angka/._)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (customAvatarPath != null) {
                        TextButton(onClick = { onUpdateAvatarPath(null) }) {
                            Text("Kembalikan ke Foto Akun Google")
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (nameInput.isNotBlank() && nameInput != profile?.displayName) {
                        onUpdateDisplayName(nameInput)
                    }
                    if (usernameInput.isNotBlank() && usernameInput != profile?.username) {
                        onUpdateUsername(usernameInput)
                    }
                    showEditProfile = false
                }) { Text("Simpan") }
            },
            dismissButton = {
                TextButton(onClick = { showEditProfile = false }) { Text("Batal") }
            }
        )
    }

    if (showCreateOrg) {
        AlertDialog(
            onDismissRequest = { showCreateOrg = false },
            title = { Text("Buat Organisasi Baru") },
            text = {
                OutlinedTextField(
                    value = newOrgName,
                    onValueChange = { newOrgName = it },
                    label = { Text("Nama organisasi") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newOrgName.isNotBlank()) {
                        onCreateOrganization(newOrgName)
                        newOrgName = ""
                        showCreateOrg = false
                    }
                }) { Text("Buat") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateOrg = false }) { Text("Batal") }
            }
        )
    }

    if (showSignOutConfirm) {
        ConfirmDialog(
            title = "Keluar dari Akun?",
            message = "Anda perlu masuk kembali dengan Google untuk mengakses catatan rapat.",
            confirmText = "Keluar",
            isDestructive = true,
            onConfirm = {
                showSignOutConfirm = false
                onSignOut()
            },
            onDismiss = { showSignOutConfirm = false }
        )
    }

    if (showDeleteAccountTypedConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountTypedConfirm = false },
            title = { Text("Hapus Profil Akun?", fontWeight = FontWeight.Bold, color = StatusRose) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Profil pengguna Anda akan dihapus dari database organisasi dan Anda akan keluar dari aplikasi. Ketik HAPUS untuk melanjutkan.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = deleteConfirmText,
                        onValueChange = { deleteConfirmText = it.uppercase() },
                        label = { Text("Ketik HAPUS") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAccountTypedConfirm = false
                        onDeleteAccount()
                    },
                    enabled = deleteConfirmText.trim() == "HAPUS",
                    colors = ButtonDefaults.buttonColors(containerColor = StatusRose)
                ) { Text("Hapus Permanen") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAccountTypedConfirm = false }) { Text("Batal") }
            }
        )
    }
}

@Composable
private fun ProfileMenuRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = title, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InfoStatusCard(
    title: String,
    detail: String,
    badge: String,
    badgeColor: Color
) {
    NotulenCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = badgeColor.copy(alpha = 0.14f)
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
