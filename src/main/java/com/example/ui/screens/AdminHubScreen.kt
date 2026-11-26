package com.example.ui.screens

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
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.AppConstants
import com.example.data.ai.PlaygroundRunResult
import com.example.data.model.AiConnection
import com.example.data.model.AuditLog
import com.example.data.model.ModelPrice
import com.example.data.model.ProviderPresets
import com.example.data.model.TaskConfig
import com.example.data.model.Tenant
import com.example.data.model.TenantInvite
import com.example.data.model.TenantMember
import com.example.data.model.UsageLog
import com.example.export.ExportUtils
import com.example.ui.components.ConfirmDialog
import com.example.ui.components.NotulenCard
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusEmerald
import com.example.ui.theme.StatusRose
import java.util.Locale

data class SetupCheckItem(
    val title: String,
    val passed: Boolean,
    val detail: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminHubScreen(
    tenant: Tenant?,
    isOwner: Boolean,
    isPlatformAdmin: Boolean = false,
    initialTab: Int = 0,
    setupChecks: List<SetupCheckItem> = emptyList(),
    connections: List<AiConnection> = emptyList(),
    members: List<TenantMember>,
    invites: List<TenantInvite>,
    taskConfigs: List<TaskConfig>,
    modelPrices: List<ModelPrice>,
    usageLogs: List<UsageLog>,
    auditLogs: List<AuditLog>,
    playgroundResults: List<PlaygroundRunResult>,
    playgroundLoading: Boolean,
    onBack: () -> Unit,
    onUpsertConnection: (AiConnection, String?) -> Unit = { _, _ -> },
    onToggleConnection: (String) -> Unit = {},
    onDeleteConnection: (String) -> Unit = {},
    onTestConnection: (AiConnection, String?, (Boolean, String) -> Unit) -> Unit = { _, _, _ -> },
    onFetchModels: (AiConnection, String?, (Result<List<String>>) -> Unit) -> Unit = { _, _, _ -> },
    onSaveTaskConfig: (TaskConfig) -> Unit,
    onApplyConfigToAllTasks: (TaskConfig) -> Unit = {},
    onTestTaskConfig: (TaskConfig) -> Unit,
    onRunPlayground: (prompt: String, sample: String, connAId: String, modelA: String, connBId: String, modelB: String, temp: Float) -> Unit,
    onSaveModelPrice: (ModelPrice) -> Unit,
    onSaveTenantSettings: (name: String, aiMode: String, watermark: Boolean, maxMb: Long, retDays: Long, budget: Double) -> Unit,
    onCreateInvite: (email: String, role: String) -> Unit,
    onRevokeInvite: (String) -> Unit,
    onUpdateMemberRole: (TenantMember, String) -> Unit,
    onDeleteTenant: () -> Unit = {}
) {
    val tabs = remember(isPlatformAdmin) {
        buildList {
            add("Koneksi AI")
            add("Koneksi STT")
            add("Routing AI")
            add("Playground")
            add("Pemakaian & Biaya")
            add("Tim & Organisasi")
            add("Log Audit")
            if (isPlatformAdmin) add("Status Setup")
        }
    }
    var selectedTab by remember(initialTab, tabs.size) {
        mutableIntStateOf(initialTab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)))
    }
    LaunchedEffect(initialTab) {
        selectedTab = initialTab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Admin: ${tenant?.name ?: "Organisasi"}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        }
    ) { padding ->
        if (tenant == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Organisasi belum dimuat.")
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 12.dp) {
                tabs.forEachIndexed { idx, title ->
                    Tab(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        text = { Text(title, maxLines = 1) }
                    )
                }
            }

            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (selectedTab) {
                    0 -> ConnectionsSection(
                        kind = "ai",
                        connections = connections,
                        onUpsertConnection = onUpsertConnection,
                        onToggleConnection = onToggleConnection,
                        onDeleteConnection = onDeleteConnection,
                        onTestConnection = onTestConnection,
                        onFetchModels = onFetchModels
                    )
                    1 -> ConnectionsSection(
                        kind = "stt",
                        connections = connections,
                        onUpsertConnection = onUpsertConnection,
                        onToggleConnection = onToggleConnection,
                        onDeleteConnection = onDeleteConnection,
                        onTestConnection = onTestConnection,
                        onFetchModels = onFetchModels
                    )
                    2 -> TaskRoutingSection(
                        tenant = tenant,
                        connections = connections,
                        taskConfigs = taskConfigs,
                        onSave = onSaveTaskConfig,
                        onApplyAll = onApplyConfigToAllTasks,
                        onTest = onTestTaskConfig
                    )
                    3 -> PlaygroundSection(
                        connections = connections,
                        results = playgroundResults,
                        isLoading = playgroundLoading,
                        onRun = onRunPlayground
                    )
                    4 -> UsageAndCostSection(
                        tenant = tenant,
                        connections = connections,
                        usageLogs = usageLogs,
                        modelPrices = modelPrices,
                        onSavePrice = onSaveModelPrice
                    )
                    5 -> TeamAndSettingsSection(
                        tenant, isOwner, members, invites,
                        onSaveTenantSettings, onCreateInvite, onRevokeInvite, onUpdateMemberRole, onDeleteTenant
                    )
                    6 -> AuditLogSection(auditLogs)
                    7 -> StatusSetupSection(setupChecks)
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun StatusSetupSection(checks: List<SetupCheckItem>) {
    Text("Status Setup Platform", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(
        "Daftar pemeriksaan otomatis kesiapan autentikasi, database, App Check, koneksi AI/STT, dan routing.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    checks.forEach { item ->
        NotulenCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (item.passed) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                    contentDescription = if (item.passed) "Siap" else "Perlu perhatian",
                    tint = if (item.passed) StatusEmerald else StatusAmber,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSelectorField(
    label: String,
    selectedLabel: String,
    options: List<Pair<String, String>>, // id to displayLabel
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (id, text) ->
                DropdownMenuItem(
                    text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        onSelected(id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun TaskRoutingSection(
    tenant: Tenant,
    connections: List<AiConnection>,
    taskConfigs: List<TaskConfig>,
    onSave: (TaskConfig) -> Unit,
    onApplyAll: (TaskConfig) -> Unit,
    onTest: (TaskConfig) -> Unit
) {
    Text("Routing Model AI & STT per Tugas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(
        "Daftar koneksi dan model diambil dinamis dari tab Koneksi AI & Koneksi STT. Mengganti model berlaku langsung pada semua fitur.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    AppConstants.AI_TASKS.forEach { def ->
        val taskKind = if (def.key == "audio_transcription") "stt" else "ai"
        val availableConnections = remember(connections, taskKind) {
            val active = connections.filter { it.kind == taskKind && it.enabled }
            active.ifEmpty {
                listOf(
                    if (taskKind == "stt") ProviderPresets.builtInSttConnection()
                    else ProviderPresets.builtInAiConnection()
                )
            }
        }

        val defaultConnId = if (taskKind == "stt") ProviderPresets.BUILT_IN_STT_ID else ProviderPresets.BUILT_IN_AI_ID
        val existing = taskConfigs.firstOrNull { it.task == def.key }
            ?: TaskConfig(
                tenantId = tenant.id,
                task = def.key,
                connectionId = defaultConnId,
                providerLabel = availableConnections.firstOrNull()?.name ?: "SumoPod AI",
                modelId = def.defaultModelId
            )

        var selectedConnId by remember(existing.connectionId, availableConnections) {
            val valid = availableConnections.any { it.id == existing.connectionId }
            mutableStateOf(if (valid) existing.connectionId else availableConnections.first().id)
        }
        val selectedConn = availableConnections.firstOrNull { it.id == selectedConnId } ?: availableConnections.first()
        val primaryModels = remember(selectedConn) {
            selectedConn.allAvailableModels.ifEmpty { listOf(def.defaultModelId) }
        }
        var selectedModel by remember(existing.modelId, selectedConnId) {
            mutableStateOf(
                if (existing.modelId in primaryModels) existing.modelId
                else primaryModels.firstOrNull() ?: def.defaultModelId
            )
        }

        var routingMode by remember(existing.routingMode) { mutableStateOf(existing.routingMode.ifBlank { "manual" }) }
        var fallbackConnId by remember(existing.fallbackConnectionId) { mutableStateOf(existing.fallbackConnectionId) }
        val fallbackConn = availableConnections.firstOrNull { it.id == fallbackConnId }
        val fallbackModels = fallbackConn?.allAvailableModels.orEmpty()
        var fallbackModelId by remember(existing.fallbackModelId, fallbackConnId) {
            mutableStateOf(
                if (existing.fallbackModelId in fallbackModels) existing.fallbackModelId
                else fallbackModels.firstOrNull().orEmpty()
            )
        }

        var temp by remember(existing.temperature) { mutableFloatStateOf(existing.temperature.toFloat()) }
        var maxTokens by remember(existing.maxOutputTokens) { mutableStateOf(existing.maxOutputTokens.toString()) }
        var timeoutSec by remember(existing.timeoutSec) { mutableStateOf(existing.timeoutSec.toString()) }
        var retryCount by remember(existing.retryCount) { mutableIntStateOf(existing.retryCount.toInt()) }
        var enabled by remember(existing.enabled) { mutableStateOf(existing.enabled) }

        NotulenCard {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(def.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "${def.key} • ${if (taskKind == "stt") "STT" else "LLM"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = routingMode == "manual",
                        onClick = { routingMode = "manual" },
                        label = { Text("Manual") }
                    )
                    FilterChip(
                        selected = routingMode == "auto",
                        onClick = { routingMode = "auto" },
                        label = { Text("Otomatis") }
                    )
                }

                DropdownSelectorField(
                    label = "Koneksi Utama",
                    selectedLabel = selectedConn.name,
                    options = availableConnections.map { it.id to it.name },
                    onSelected = { newCid ->
                        selectedConnId = newCid
                        val c = availableConnections.firstOrNull { it.id == newCid }
                        val mList = c?.allAvailableModels.orEmpty()
                        if (selectedModel !in mList && mList.isNotEmpty()) {
                            selectedModel = mList.first()
                        }
                    }
                )

                DropdownSelectorField(
                    label = "Model Utama",
                    selectedLabel = selectedModel,
                    options = primaryModels.map { it to it },
                    onSelected = { selectedModel = it }
                )

                // Fallback selection
                val fallbackOptions = listOf("" to "Tidak ada fallback") +
                    availableConnections.filter { it.id != selectedConnId }.map { it.id to it.name }
                DropdownSelectorField(
                    label = "Koneksi Fallback (Opsional)",
                    selectedLabel = fallbackConn?.name ?: "Tidak ada fallback",
                    options = fallbackOptions,
                    onSelected = { fCid ->
                        fallbackConnId = fCid
                        val fc = availableConnections.firstOrNull { it.id == fCid }
                        fallbackModelId = fc?.allAvailableModels?.firstOrNull().orEmpty()
                    }
                )

                if (fallbackConn != null && fallbackModels.isNotEmpty()) {
                    DropdownSelectorField(
                        label = "Model Fallback",
                        selectedLabel = fallbackModelId.ifBlank { fallbackModels.first() },
                        options = fallbackModels.map { it to it },
                        onSelected = { fallbackModelId = it }
                    )
                }

                Text("Temperature: ${String.format(Locale.US, "%.2f", temp)}", style = MaterialTheme.typography.labelMedium)
                Slider(value = temp, onValueChange = { temp = it }, valueRange = 0f..1f)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = maxTokens,
                        onValueChange = { maxTokens = it },
                        label = { Text("Max Tokens") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
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
                        label = { Text("Retry") },
                        singleLine = true,
                        modifier = Modifier.weight(0.8f)
                    )
                }

                val updated = existing.copy(
                    connectionId = selectedConn.id,
                    providerLabel = selectedConn.name,
                    modelId = selectedModel,
                    routingMode = routingMode,
                    fallbackConnectionId = fallbackConnId,
                    fallbackModelId = if (fallbackConnId.isBlank()) "" else fallbackModelId,
                    timeoutSec = (timeoutSec.toLongOrNull() ?: 60L).coerceIn(5L, 300L),
                    retryCount = retryCount.toLong().coerceIn(0L, 3L),
                    temperature = temp.toDouble(),
                    maxOutputTokens = (maxTokens.toLongOrNull() ?: 4096L).coerceIn(256L, 32768L),
                    enabled = enabled
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSave(updated) }) { Text("Simpan") }
                    OutlinedButton(onClick = { onTest(updated) }) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Tes Tugas")
                    }
                }
                if (taskKind == "ai") {
                    TextButton(onClick = { onApplyAll(updated) }) {
                        Icon(Icons.Rounded.AllInclusive, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Gunakan untuk semua tugas teks AI")
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaygroundSection(
    connections: List<AiConnection>,
    results: List<PlaygroundRunResult>,
    isLoading: Boolean,
    onRun: (prompt: String, sample: String, connAId: String, modelA: String, connBId: String, modelB: String, temp: Float) -> Unit
) {
    val aiConnections = remember(connections) {
        connections.filter { it.kind == "ai" && it.enabled }
            .ifEmpty { listOf(ProviderPresets.builtInAiConnection()) }
    }

    var prompt by remember { mutableStateOf("Ringkas transkrip berikut menjadi 3 poin keputusan utama.") }
    var sampleText by remember {
        mutableStateOf(
            "Pembicara 1: Kita perlu mempercepat rilis versi Android minggu depan.\n" +
                "Pembicara 2: Setuju, tim QA selesai hari Kamis dan anggaran promo Rp 25 juta disetujui."
        )
    }

    var connAId by remember(aiConnections) { mutableStateOf(aiConnections.first().id) }
    val connA = aiConnections.firstOrNull { it.id == connAId } ?: aiConnections.first()
    val modelsA = connA.allAvailableModels.ifEmpty { listOf(AppConstants.DEFAULT_MODEL_ID) }
    var modelA by remember(connAId) { mutableStateOf(modelsA.first()) }

    var connBId by remember(aiConnections) { mutableStateOf(aiConnections.last().id) }
    val connB = aiConnections.firstOrNull { it.id == connBId } ?: aiConnections.first()
    val modelsB = connB.allAvailableModels.ifEmpty { listOf(AppConstants.FAST_MODEL_ID) }
    var modelB by remember(connBId) {
        mutableStateOf(modelsB.getOrElse(1) { modelsB.first() })
    }

    var temp by remember { mutableFloatStateOf(0.2f) }

    NotulenCard {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Perbandingan 2 Model Berdampingan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Prompt Sistem") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = sampleText, onValueChange = { sampleText = it }, label = { Text("Teks Transkrip Contoh") }, minLines = 3, modifier = Modifier.fillMaxWidth())

            Text("Kandidat A", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownSelectorField(
                    label = "Koneksi A",
                    selectedLabel = connA.name,
                    options = aiConnections.map { it.id to it.name },
                    onSelected = { cid ->
                        connAId = cid
                        val list = aiConnections.firstOrNull { it.id == cid }?.allAvailableModels.orEmpty()
                        if (list.isNotEmpty()) modelA = list.first()
                    },
                    modifier = Modifier.weight(1f)
                )
                DropdownSelectorField(
                    label = "Model A",
                    selectedLabel = modelA,
                    options = modelsA.map { it to it },
                    onSelected = { modelA = it },
                    modifier = Modifier.weight(1f)
                )
            }

            Text("Kandidat B", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownSelectorField(
                    label = "Koneksi B",
                    selectedLabel = connB.name,
                    options = aiConnections.map { it.id to it.name },
                    onSelected = { cid ->
                        connBId = cid
                        val list = aiConnections.firstOrNull { it.id == cid }?.allAvailableModels.orEmpty()
                        if (list.isNotEmpty()) modelB = list.getOrElse(1) { list.first() }
                    },
                    modifier = Modifier.weight(1f)
                )
                DropdownSelectorField(
                    label = "Model B",
                    selectedLabel = modelB,
                    options = modelsB.map { it to it },
                    onSelected = { modelB = it },
                    modifier = Modifier.weight(1f)
                )
            }

            Text("Temperature: ${String.format(Locale.US, "%.2f", temp)}", style = MaterialTheme.typography.labelMedium)
            Slider(value = temp, onValueChange = { temp = it }, valueRange = 0f..1f)
            Button(
                onClick = { onRun(prompt, sampleText, connA.id, modelA, connB.id, modelB, temp) },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Menjalankan perbandingan...")
                } else {
                    Text("Bandingkan Model Sekarang")
                }
            }
        }
    }

    results.forEach { res ->
        NotulenCard {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(res.modelId, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = res.meta.providerLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Text(
                    text = "Latensi: ${res.meta.latencyMs} ms • Token: ${res.meta.inputTokens} in / ${res.meta.outputTokens} out • " +
                        if (res.priceConfigured) String.format(Locale.US, "Biaya: $%.5f", res.meta.estimatedCostUsd) else "Harga belum diatur",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(res.outputText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun UsageAndCostSection(
    tenant: Tenant,
    connections: List<AiConnection>,
    usageLogs: List<UsageLog>,
    modelPrices: List<ModelPrice>,
    onSavePrice: (ModelPrice) -> Unit
) {
    val context = LocalContext.current
    val totalReq = usageLogs.size
    val totalIn = usageLogs.sumOf { it.inputTokens }
    val totalOut = usageLogs.sumOf { it.outputTokens }
    val totalAudioMin = usageLogs.sumOf { it.audioSeconds } / 60.0
    val totalCost = usageLogs.sumOf { it.estimatedCostUsd }
    val budget = tenant.monthlyBudgetUsd.coerceAtLeast(1.0)
    val budgetPct = (totalCost / budget).toFloat().coerceIn(0f, 1f)

    NotulenCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Ringkasan Pemakaian", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { ExportUtils.exportUsageCsv(context, usageLogs) }, enabled = usageLogs.isNotEmpty()) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("CSV")
                }
            }
            Text("Total Request: $totalReq • Audio: ${String.format(Locale.US, "%.1f", totalAudioMin)} menit")
            Text("Token: $totalIn input / $totalOut output")
            Text(
                text = "Estimasi Biaya: ${String.format(Locale.US, "$%.4f", totalCost)} / ${String.format(Locale.US, "$%.2f", budget)}",
                fontWeight = FontWeight.SemiBold,
                color = when {
                    budgetPct >= 1f -> StatusRose
                    budgetPct >= 0.8f -> StatusAmber
                    else -> StatusEmerald
                }
            )
            LinearProgressIndicator(progress = { budgetPct }, modifier = Modifier.fillMaxWidth().height(6.dp))
            if (budgetPct >= 0.8f) {
                Text(
                    text = if (budgetPct >= 1f) "Peringatan: Anggaran bulanan telah mencapai 100%." else "Peringatan: Pemakaian telah melewati 80% anggaran.",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (budgetPct >= 1f) StatusRose else StatusAmber
                )
            }
            val fallbackCount = usageLogs.count { it.status == "fallback" }
            val errorCount = usageLogs.count { it.status == "error" }
            val fallbackPct = if (totalReq > 0) (fallbackCount * 100.0 / totalReq) else 0.0
            Text(
                text = "Fallback: ${String.format(Locale.US, "%.1f%%", fallbackPct)} • Error tercatat: $errorCount",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    val dynamicModels = remember(connections, modelPrices) {
        val fromConns = connections.flatMap { c -> c.allAvailableModels.map { m -> m to "${m} (${c.name})" } }
        val fromBuiltin = AppConstants.AVAILABLE_MODELS.map { it.id to it.label }
        val fromPrices = modelPrices.map { it.modelId to it.modelId }
        (fromConns + fromBuiltin + fromPrices).distinctBy { it.first }
    }

    Text("Tabel Harga Manual (per 1 Juta Token)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    dynamicModels.forEach { (modelId, label) ->
        val existing = modelPrices.firstOrNull { it.modelId == modelId } ?: ModelPrice(tenantId = tenant.id, modelId = modelId)
        var inStr by remember(existing.inputPer1M) { mutableStateOf(existing.inputPer1M.toString()) }
        var outStr by remember(existing.outputPer1M) { mutableStateOf(existing.outputPer1M.toString()) }

        NotulenCard {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = inStr, onValueChange = { inStr = it }, label = { Text("Input USD/1M") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = outStr, onValueChange = { outStr = it }, label = { Text("Output USD/1M") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Button(onClick = {
                    onSavePrice(
                        existing.copy(
                            inputPer1M = inStr.toDoubleOrNull() ?: 0.0,
                            outputPer1M = outStr.toDoubleOrNull() ?: 0.0
                        )
                    )
                }) { Text("Simpan Tarif") }
            }
        }
    }
}

@Composable
private fun TeamAndSettingsSection(
    tenant: Tenant,
    isOwner: Boolean,
    members: List<TenantMember>,
    invites: List<TenantInvite>,
    onSaveSettings: (String, String, Boolean, Long, Long, Double) -> Unit,
    onCreateInvite: (String, String) -> Unit,
    onRevokeInvite: (String) -> Unit,
    onUpdateRole: (TenantMember, String) -> Unit,
    onDeleteTenant: () -> Unit
) {
    var orgName by remember(tenant.name) { mutableStateOf(tenant.name) }
    var aiMode by remember(tenant.aiMode) { mutableStateOf(tenant.aiMode) }
    var watermark by remember(tenant.watermarkEnabled) { mutableStateOf(tenant.watermarkEnabled) }
    var maxMb by remember(tenant.maxFileMb) { mutableStateOf(tenant.maxFileMb.toString()) }
    var retDays by remember(tenant.retentionDays) { mutableStateOf(tenant.retentionDays.toString()) }
    var budget by remember(tenant.monthlyBudgetUsd) { mutableStateOf(tenant.monthlyBudgetUsd.toString()) }

    var inviteEmail by remember { mutableStateOf("") }
    var inviteRole by remember { mutableStateOf("member") }
    var showDeleteOrgConfirm by remember { mutableStateOf(false) }

    NotulenCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Pengaturan Organisasi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = orgName, onValueChange = { orgName = it }, label = { Text("Nama Organisasi") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (isOwner) {
                Text("Mode AI Organisasi", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = aiMode == "platform", onClick = { aiMode = "platform" }, label = { Text("Platform Managed") })
                    FilterChip(selected = aiMode == "byok", onClick = { aiMode = "byok" }, label = { Text("BYOK") })
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Tampilkan watermark di Visual 4:5", modifier = Modifier.weight(1f))
                Switch(checked = watermark, onCheckedChange = { watermark = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = maxMb, onValueChange = { maxMb = it }, label = { Text("Batas MB") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = retDays, onValueChange = { retDays = it }, label = { Text("Retensi (Hari)") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = budget, onValueChange = { budget = it }, label = { Text("Budget ($)") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Button(
                onClick = {
                    onSaveSettings(
                        orgName,
                        aiMode,
                        watermark,
                        maxMb.toLongOrNull() ?: 200L,
                        retDays.toLongOrNull() ?: 90L,
                        budget.toDoubleOrNull() ?: 50.0
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Simpan Pengaturan") }
        }
    }

    NotulenCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Undang Anggota Tim", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = inviteEmail, onValueChange = { inviteEmail = it }, label = { Text("Email anggota") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = inviteRole == "member", onClick = { inviteRole = "member" }, label = { Text("Member") })
                FilterChip(selected = inviteRole == "admin", onClick = { inviteRole = "admin" }, label = { Text("Admin") })
            }
            Button(
                onClick = {
                    if (inviteEmail.isNotBlank()) {
                        onCreateInvite(inviteEmail, inviteRole)
                        inviteEmail = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Kirim Undangan") }

            if (invites.isNotEmpty()) {
                Text("Daftar Undangan", style = MaterialTheme.typography.titleSmall)
                invites.forEach { inv ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(inv.email, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            Text("${inv.role} • ${inv.inviteStatus}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (inv.inviteStatus == "pending") {
                            OutlinedButton(onClick = { onRevokeInvite(inv.id) }) { Text("Cabut") }
                        }
                    }
                }
            }
        }
    }

    NotulenCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Anggota Saat Ini (${members.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            members.forEach { m ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(m.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("${m.email} • ${m.role}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (m.role != "owner") {
                        val nextRole = if (m.role == "admin") "member" else "admin"
                        OutlinedButton(onClick = { onUpdateRole(m, nextRole) }) {
                            Text("Jadikan $nextRole")
                        }
                    }
                }
            }
        }
    }

    if (isOwner) {
        OutlinedButton(
            onClick = { showDeleteOrgConfirm = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRose),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Hapus Organisasi Ini")
        }
    }

    if (showDeleteOrgConfirm) {
        ConfirmDialog(
            title = "Hapus Organisasi \"${tenant.name}\"?",
            message = "Tindakan ini menghapus workspace organisasi secara permanen.",
            confirmText = "Hapus Organisasi",
            isDestructive = true,
            onConfirm = {
                showDeleteOrgConfirm = false
                onDeleteTenant()
            },
            onDismiss = { showDeleteOrgConfirm = false }
        )
    }
}

@Composable
private fun AuditLogSection(auditLogs: List<AuditLog>) {
    val context = LocalContext.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Log Audit Organisasi (${auditLogs.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = { ExportUtils.exportAuditCsv(context, auditLogs) }, enabled = auditLogs.isNotEmpty()) {
            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("CSV")
        }
    }
    if (auditLogs.isEmpty()) {
        NotulenCard {
            Text("Belum ada aktivitas audit tercatat.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        auditLogs.forEach { log ->
            NotulenCard {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(log.aksi, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("Target: ${log.target} • Oleh: ${log.actorEmail}", style = MaterialTheme.typography.bodySmall)
                    if (log.detail.isNotBlank()) {
                        Text(log.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(ExportUtils.formatDate(log.createdAt?.toDate()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
