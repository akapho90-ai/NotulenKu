package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.AiConnection
import com.example.data.model.ProviderPresets
import com.example.export.ExportUtils
import com.example.ui.components.ConfirmDialog
import com.example.ui.components.NotulenCard
import com.example.ui.theme.BrandBlue
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusAmberBg
import com.example.ui.theme.StatusEmerald
import com.example.ui.theme.StatusRose
import java.util.Date

@Composable
fun ConnectionsSection(
    kind: String, // "ai" | "stt"
    connections: List<AiConnection>,
    onUpsertConnection: (AiConnection, String?) -> Unit,
    onToggleConnection: (String) -> Unit,
    onDeleteConnection: (String) -> Unit,
    onTestConnection: (AiConnection, String?, (Boolean, String) -> Unit) -> Unit,
    onFetchModels: (AiConnection, String?, (Result<List<String>>) -> Unit) -> Unit
) {
    val filtered = remember(connections, kind) { connections.filter { it.kind == kind } }
    var editingConnection by remember { mutableStateOf<AiConnection?>(null) }
    var deleteTarget by remember { mutableStateOf<AiConnection?>(null) }

    // Clear Local Vault notice for custom API keys
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = StatusAmberBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Info, contentDescription = null, tint = Color(0xFF92400E), modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Mode Lokal: API key kustom terenkripsi di perangkat ini saja (Android Keystore) dan tidak pernah dikirim ke Firestore atau log.",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF92400E)
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (kind == "ai") "Koneksi Model AI (${filtered.size})" else "Koneksi Speech-to-Text (${filtered.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Button(
            onClick = {
                val defaultPreset = if (kind == "ai") ProviderPresets.AI_PRESETS.first() else ProviderPresets.STT_PRESETS.first()
                editingConnection = AiConnection(
                    id = "",
                    kind = kind,
                    name = "",
                    providerType = defaultPreset.type,
                    baseUrl = defaultPreset.defaultBaseUrl,
                    defaultModel = defaultPreset.defaultModels.firstOrNull().orEmpty(),
                    models = defaultPreset.defaultModels,
                    enabled = true,
                    status = "untested"
                )
            },
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Tambah")
        }
    }

    filtered.forEach { conn ->
        ConnectionCardItem(
            connection = conn,
            onToggle = { onToggleConnection(conn.id) },
            onTest = { onTestConnection(conn, null) { _, _ -> } },
            onEdit = { editingConnection = conn },
            onDelete = { deleteTarget = conn }
        )
    }

    if (editingConnection != null) {
        ConnectionEditorSheet(
            initial = editingConnection!!,
            onDismiss = { editingConnection = null },
            onSave = { updated, newKey ->
                onUpsertConnection(updated, newKey)
                editingConnection = null
            },
            onTest = onTestConnection,
            onFetchModels = onFetchModels
        )
    }

    if (deleteTarget != null) {
        ConfirmDialog(
            title = "Hapus Koneksi?",
            message = "Koneksi \"${deleteTarget!!.name}\" beserta API key terenkripsinya akan dihapus dari perangkat.",
            confirmText = "Hapus",
            isDestructive = true,
            onConfirm = {
                onDeleteConnection(deleteTarget!!.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }
}

@Composable
private fun ConnectionCardItem(
    connection: AiConnection,
    onToggle: () -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val (statusLabel, statusColor) = when {
        !connection.enabled -> "Nonaktif" to MaterialTheme.colorScheme.onSurfaceVariant
        connection.status == "ok" -> "OK" to StatusEmerald
        connection.status == "error" -> "Error" to StatusRose
        else -> "Belum dites" to StatusAmber
    }

    NotulenCard {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(BrandBlue.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (connection.isBuiltIn) Icons.Rounded.CloudDone else if (connection.kind == "stt") Icons.Rounded.GraphicEq else Icons.Rounded.Hub,
                        contentDescription = null,
                        tint = BrandBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = connection.name.ifBlank { connection.providerType },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${connection.allAvailableModels.size} model • ${connection.keyMask.ifBlank { "Belum ada key" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = statusColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(statusColor))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = statusColor, fontWeight = FontWeight.Bold)
                    }
                }
                if (!connection.isBuiltIn) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Switch(checked = connection.enabled, onCheckedChange = { onToggle() })
                }
            }

            if (connection.lastTestedAtMs > 0L) {
                val timeStr = ExportUtils.formatDate(Date(connection.lastTestedAtMs))
                Text(
                    text = "Tes terakhir: $timeStr (${connection.lastLatencyMs} ms)${if (connection.lastError.isNotBlank()) " • ${connection.lastError}" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (connection.status == "error") StatusRose else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTest) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Tes")
                }
                if (!connection.isBuiltIn) {
                    OutlinedButton(onClick = onEdit) {
                        Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Ubah")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Hapus koneksi", tint = StatusRose)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ConnectionEditorSheet(
    initial: AiConnection,
    onDismiss: () -> Unit,
    onSave: (AiConnection, String?) -> Unit,
    onTest: (AiConnection, String?, (Boolean, String) -> Unit) -> Unit,
    onFetchModels: (AiConnection, String?, (Result<List<String>>) -> Unit) -> Unit
) {
    val presets = remember(initial.kind) {
        if (initial.kind == "stt") ProviderPresets.STT_PRESETS else ProviderPresets.AI_PRESETS
    }
    var providerType by remember { mutableStateOf(initial.providerType) }
    var name by remember { mutableStateOf(initial.name.ifBlank { ProviderPresets.presetFor(initial.providerType)?.label.orEmpty() }) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKeyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var defaultModel by remember { mutableStateOf(initial.defaultModel) }
    var models by remember { mutableStateOf(initial.allAvailableModels) }
    var manualModelInput by remember { mutableStateOf("") }

    var sttLang by remember { mutableStateOf(initial.sttLanguage) }
    var sttDiarization by remember { mutableStateOf(initial.sttDiarization) }
    var chunkSizeSec by remember { mutableStateOf(initial.chunkSizeSec.toString()) }
    var timeoutSec by remember { mutableStateOf(initial.timeoutSec.toString()) }
    var retryCount by remember { mutableIntStateOf(initial.retryCount) }

    var isTesting by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var isFetchingModels by remember { mutableStateOf(false) }

    val selectedPreset = ProviderPresets.presetFor(providerType)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            Text(
                text = if (initial.id.isBlank()) "Tambah Koneksi Baru" else "Edit Koneksi",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Text("Jenis Provider", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { p ->
                    FilterChip(
                        selected = providerType == p.type,
                        onClick = {
                            providerType = p.type
                            if (initial.id.isBlank() || name.isBlank()) name = p.label
                            if (p.defaultBaseUrl.isNotBlank()) baseUrl = p.defaultBaseUrl
                            if (models.isEmpty() || initial.id.isBlank()) {
                                models = p.defaultModels
                                defaultModel = p.defaultModels.firstOrNull().orEmpty()
                            }
                            if (!p.supportsDiarization) sttDiarization = false
                        },
                        label = { Text(p.label) }
                    )
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nama tampilan") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text(if (selectedPreset?.requiresBaseUrl == true) "Base URL (Wajib)" else "Base URL") },
                placeholder = { Text("https://api.openai.com/v1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it },
                label = { Text(if (initial.keyMask.isNotBlank()) "API Key Baru (Saat ini: ${initial.keyMask})" else "API Key") },
                placeholder = { Text("Tempel API key provider") },
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = "Tampilkan kunci")
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = defaultModel,
                onValueChange = { defaultModel = it },
                label = { Text("Model bawaan") },
                placeholder = { Text("Misal: gpt-4o-mini atau model Sumopod") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Daftar Model (${models.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (selectedPreset?.supportsFetchModels == true) {
                    OutlinedButton(
                        enabled = !isFetchingModels,
                        onClick = {
                            isFetchingModels = true
                            val draft = initial.copy(providerType = providerType, baseUrl = baseUrl, timeoutSec = timeoutSec.toIntOrNull() ?: 60)
                            onFetchModels(draft, apiKeyInput.ifBlank { null }) { res ->
                                isFetchingModels = false
                                res.onSuccess { fetched ->
                                    models = (models + fetched).distinct()
                                    if (defaultModel.isBlank()) defaultModel = fetched.firstOrNull().orEmpty()
                                    testMessage = true to "${fetched.size} model berhasil dimuat."
                                }.onFailure { err ->
                                    testMessage = false to (err.localizedMessage ?: "Gagal memuat model.")
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isFetchingModels) "Memuat..." else "Ambil daftar model")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = manualModelInput,
                    onValueChange = { manualModelInput = it },
                    label = { Text("Tambah model manual") },
                    placeholder = { Text("Ketik nama model persis") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        val m = manualModelInput.trim()
                        if (m.isNotBlank()) {
                            models = (models + m).distinct()
                            if (defaultModel.isBlank()) defaultModel = m
                            manualModelInput = ""
                        }
                    }
                ) { Text("Tambah") }
            }

            if (models.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    models.forEach { m ->
                        InputChip(
                            selected = defaultModel == m,
                            onClick = { defaultModel = m },
                            label = { Text(m, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = {
                                IconButton(
                                    onClick = { models = models.filterNot { it == m } },
                                    modifier = Modifier.size(18.dp)
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Hapus $m", modifier = Modifier.size(14.dp))
                                }
                            }
                        )
                    }
                }
            }

            if (initial.kind == "stt") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = sttLang,
                        onValueChange = { sttLang = it },
                        label = { Text("Bahasa (id/en)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = chunkSizeSec,
                        onValueChange = { chunkSizeSec = it },
                        label = { Text("Chunk (detik)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (selectedPreset?.supportsDiarization == true) "Diarization (label pembicara)" else "Diarization tidak didukung provider ini",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Switch(
                        checked = sttDiarization && (selectedPreset?.supportsDiarization == true),
                        onCheckedChange = { sttDiarization = it },
                        enabled = selectedPreset?.supportsDiarization == true
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = timeoutSec,
                    onValueChange = { timeoutSec = it },
                    label = { Text("Timeout (dtk)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = retryCount.toString(),
                    onValueChange = { retryCount = (it.toIntOrNull() ?: 1).coerceIn(0, 3) },
                    label = { Text("Retry (0-3)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            if (testMessage != null) {
                val (ok, msg) = testMessage!!
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = (if (ok) StatusEmerald else StatusRose).copy(alpha = 0.14f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) StatusEmerald else StatusRose,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val draftConn = initial.copy(
                    kind = initial.kind,
                    name = name.trim().ifBlank { selectedPreset?.label ?: "Koneksi Kustom" },
                    providerType = providerType,
                    baseUrl = baseUrl.trim(),
                    defaultModel = defaultModel.trim(),
                    models = (listOf(defaultModel.trim()) + models).filter { it.isNotBlank() }.distinct(),
                    sttLanguage = sttLang.ifBlank { "id" },
                    sttDiarization = sttDiarization && (selectedPreset?.supportsDiarization == true),
                    chunkSizeSec = (chunkSizeSec.toIntOrNull() ?: 300).coerceIn(30, 1800),
                    timeoutSec = (timeoutSec.toIntOrNull() ?: 60).coerceIn(10, 300),
                    retryCount = retryCount
                )
                OutlinedButton(
                    onClick = {
                        isTesting = true
                        onTest(draftConn, apiKeyInput.ifBlank { null }) { ok, msg ->
                            isTesting = false
                            testMessage = ok to msg
                        }
                    },
                    enabled = !isTesting,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Menguji...")
                    } else {
                        Text("Tes Koneksi")
                    }
                }
                Button(
                    onClick = {
                        val finalConn = if (testMessage?.first == true) draftConn.copy(status = "ok", lastTestedAtMs = System.currentTimeMillis()) else draftConn
                        onSave(finalConn, apiKeyInput.ifBlank { null })
                    },
                    enabled = draftConn.name.isNotBlank() && (selectedPreset?.requiresBaseUrl != true || draftConn.baseUrl.isNotBlank()),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue, contentColor = Color.White),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Simpan")
                }
            }
        }
    }
}
