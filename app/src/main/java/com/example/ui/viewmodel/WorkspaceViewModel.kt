package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.AppConstants
import com.example.core.toFriendlyIndonesianError
import com.example.data.ai.AiService
import com.example.data.ai.ConnectionVaultStore
import com.example.data.ai.PlaygroundRunResult
import com.example.data.ai.toUsageLog
import com.example.data.model.AiConnection
import com.example.data.model.AuditLog
import com.example.data.model.ModelPrice
import com.example.data.model.ProviderPresets
import com.example.data.model.TaskConfig
import com.example.data.model.Tenant
import com.example.data.model.TenantInvite
import com.example.data.model.TenantMember
import com.example.data.model.UsageLog
import com.example.data.model.UserProfile
import com.example.data.preferences.UserPrefsDataStore
import com.example.data.repository.TenantRepository
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "WorkspaceVM"

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

data class WorkspaceSnapshot(
    val profile: UserProfile? = null,
    val tenants: List<Tenant> = emptyList(),
    val activeTenant: Tenant? = null,
    val roleInActiveTenant: String = "member",
    val isPlatformAdmin: Boolean = false
) {
    val isAdminOrOwner: Boolean
        get() = roleInActiveTenant == "owner" || roleInActiveTenant == "admin" || isPlatformAdmin
    val isOwner: Boolean
        get() = roleInActiveTenant == "owner" || isPlatformAdmin
}

class WorkspaceViewModel(
    private val tenantRepo: TenantRepository,
    private val aiService: AiService,
    private val prefs: UserPrefsDataStore,
    private val currentUserId: String,
    private val vaultStore: ConnectionVaultStore? = null
) : ViewModel() {

    private val _selectedTenantId = MutableStateFlow<String?>(null)
    private val workspaceReload = MutableStateFlow(0)
    private var provisioningJob: Job? = null
    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    private val _connectionHealthy = MutableStateFlow<Boolean?>(null)
    val connectionHealthy: StateFlow<Boolean?> = _connectionHealthy.asStateFlow()

    private val _playgroundResults = MutableStateFlow<List<PlaygroundRunResult>>(emptyList())
    val playgroundResults: StateFlow<List<PlaygroundRunResult>> = _playgroundResults.asStateFlow()

    private val _playgroundLoading = MutableStateFlow(false)
    val playgroundLoading: StateFlow<Boolean> = _playgroundLoading.asStateFlow()

    val connections: StateFlow<List<AiConnection>> = vaultStore?.connections
        ?: MutableStateFlow(
            listOf(ProviderPresets.builtInAiConnection(), ProviderPresets.builtInSttConnection())
        ).asStateFlow()

    val themeMode: StateFlow<String> = prefs.themeMode.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000L), "system"
    )
    val defaultTranscriptLang: StateFlow<String> = prefs.defaultTranscriptLang.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000L), "id"
    )
    val defaultExportFormat: StateFlow<String> = prefs.defaultExportFormat.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000L), "PDF"
    )
    val defaultVisualStyle: StateFlow<String> = prefs.defaultVisualStyle.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000L), "CARDS"
    )
    val defaultHistoryView: StateFlow<String> = prefs.defaultHistoryView.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000L), "list"
    )
    val reduceMotion: StateFlow<Boolean> = prefs.reduceMotion.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000L), false
    )
    val customAvatarPath: StateFlow<String?> = prefs.customAvatarPath.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000L), null
    )

    init {
        viewModelScope.launch {
            val savedId = prefs.savedTenantId.firstOrNull()
            if (!savedId.isNullOrBlank()) {
                _selectedTenantId.value = savedId
            }
            retryWorkspace()
        }
    }

    fun retryWorkspace() {
        if (provisioningJob?.isActive == true) return
        workspaceReload.value += 1
        provisioningJob = viewModelScope.launch {
            val user = Firebase.auth.currentUser ?: return@launch
            if (user.uid != currentUserId) return@launch
            tenantRepo.ensureUserAndPersonalTenant(user)
                .onSuccess { provisionedId ->
                    if (_selectedTenantId.value == null) {
                        _selectedTenantId.value = provisionedId
                    }
                }
                .onFailure { error ->
                    _actionMessage.value = "Workspace: ${toFriendlyIndonesianError(error)}"
                }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val workspaceState: StateFlow<UiState<WorkspaceSnapshot>> = workspaceReload.flatMapLatest {
        combine(
            tenantRepo.observeUserProfile(currentUserId),
            tenantRepo.observeUserTenants(currentUserId),
            _selectedTenantId
        ) { profile, tenants, selectedId ->
            val candidateId = selectedId ?: profile?.activeTenantId
            val active = tenants.firstOrNull { it.id == candidateId } ?: tenants.firstOrNull()
            val role = when {
                active == null -> "member"
                active.ownerId == currentUserId -> "owner"
                currentUserId in active.adminUids -> "admin"
                else -> "member"
            }
            val user = Firebase.auth.currentUser
            val platformAdmin = user != null && user.uid == currentUserId &&
                user.isEmailVerified &&
                user.email.equals(AppConstants.PLATFORM_ADMIN_EMAIL, ignoreCase = true)
            UiState.Success(
                WorkspaceSnapshot(
                    profile = profile,
                    tenants = tenants,
                    activeTenant = active,
                    roleInActiveTenant = role,
                    isPlatformAdmin = platformAdmin
                )
            ) as UiState<WorkspaceSnapshot>
        }
            .onStart { emit(UiState.Loading) }
            .catch { error ->
                val fsErr = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fsErr == null && error is CancellationException) throw error
                Log.w(TAG, "Workspace observation error", fsErr ?: error)
                emit(UiState.Error(toFriendlyIndonesianError(fsErr ?: error)))
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    @OptIn(ExperimentalCoroutinesApi::class)
    val members: StateFlow<List<TenantMember>> = workspaceState
        .flatMapLatest { state ->
            val t = (state as? UiState.Success)?.data?.activeTenant ?: return@flatMapLatest flowOf(emptyList())
            tenantRepo.observeMembers(t.id, currentUserId)
                .catch { e ->
                    if (e is CancellationException) throw e
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val taskConfigs: StateFlow<List<TaskConfig>> = workspaceState
        .flatMapLatest { state ->
            val t = (state as? UiState.Success)?.data?.activeTenant ?: return@flatMapLatest flowOf(emptyList())
            tenantRepo.observeTaskConfigs(t.id, currentUserId)
                .catch { e ->
                    if (e is CancellationException) throw e
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val modelPrices: StateFlow<List<ModelPrice>> = workspaceState
        .flatMapLatest { state ->
            val t = (state as? UiState.Success)?.data?.activeTenant ?: return@flatMapLatest flowOf(emptyList())
            tenantRepo.observeModelPrices(t.id, currentUserId)
                .catch { e ->
                    if (e is CancellationException) throw e
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val usageLogs: StateFlow<List<UsageLog>> = workspaceState
        .flatMapLatest { state ->
            val snap = (state as? UiState.Success)?.data ?: return@flatMapLatest flowOf(emptyList())
            val t = snap.activeTenant ?: return@flatMapLatest flowOf(emptyList())
            tenantRepo.observeUsageLogs(t.id, currentUserId, snap.isAdminOrOwner)
                .catch { e ->
                    if (e is CancellationException) throw e
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val auditLogs: StateFlow<List<AuditLog>> = workspaceState
        .flatMapLatest { state ->
            val snap = (state as? UiState.Success)?.data ?: return@flatMapLatest flowOf(emptyList())
            val t = snap.activeTenant
            if (t == null || !snap.isAdminOrOwner) return@flatMapLatest flowOf(emptyList())
            tenantRepo.observeAuditLogs(t.id, currentUserId)
                .catch { e ->
                    if (e is CancellationException) throw e
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val invites: StateFlow<List<TenantInvite>> = workspaceState
        .flatMapLatest { state ->
            val snap = (state as? UiState.Success)?.data ?: return@flatMapLatest flowOf(emptyList())
            val t = snap.activeTenant
            if (t == null || !snap.isAdminOrOwner) return@flatMapLatest flowOf(emptyList())
            tenantRepo.observeInvites(t.id, currentUserId)
                .catch { e ->
                    if (e is CancellationException) throw e
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    fun selectTenant(tenantId: String) {
        _selectedTenantId.value = tenantId
        viewModelScope.launch {
            prefs.setSavedTenantId(tenantId)
            tenantRepo.setActiveTenant(tenantId)
        }
    }

    fun createOrganization(name: String) {
        viewModelScope.launch {
            tenantRepo.createOrganization(name)
                .onSuccess { newId ->
                    _selectedTenantId.value = newId
                    prefs.setSavedTenantId(newId)
                    _actionMessage.value = "Organisasi \"$name\" berhasil dibuat."
                }
                .onFailure { _actionMessage.value = toFriendlyIndonesianError(it) }
        }
    }

    fun updateDisplayName(name: String) {
        viewModelScope.launch {
            tenantRepo.updateDisplayName(name)
                .onSuccess { _actionMessage.value = "Nama tampilan diperbarui." }
                .onFailure { _actionMessage.value = toFriendlyIndonesianError(it) }
        }
    }

    fun updateUsername(username: String) {
        viewModelScope.launch {
            tenantRepo.updateUsername(username)
                .onSuccess { _actionMessage.value = "Username diperbarui." }
                .onFailure { _actionMessage.value = toFriendlyIndonesianError(it) }
        }
    }

    fun deleteTenant(tenant: Tenant, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            tenantRepo.deleteTenant(tenant)
                .onSuccess {
                    _selectedTenantId.value = null
                    prefs.setSavedTenantId(null)
                    _actionMessage.value = "Organisasi \"${tenant.name}\" dihapus."
                    onDeleted()
                }
                .onFailure { _actionMessage.value = toFriendlyIndonesianError(it) }
        }
    }

    fun deleteAccountProfile(onDone: () -> Unit) {
        viewModelScope.launch {
            tenantRepo.deleteAccountProfile()
                .onSuccess {
                    prefs.setSavedTenantId(null)
                    onDone()
                }
                .onFailure { _actionMessage.value = toFriendlyIndonesianError(it) }
        }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    fun setDefaultTranscriptLang(lang: String) {
        viewModelScope.launch { prefs.setDefaultTranscriptLang(lang) }
    }

    fun setDefaultExportFormat(fmt: String) {
        viewModelScope.launch { prefs.setDefaultExportFormat(fmt) }
    }

    fun setDefaultVisualStyle(style: String) {
        viewModelScope.launch { prefs.setDefaultVisualStyle(style) }
    }

    fun setDefaultHistoryView(view: String) {
        viewModelScope.launch { prefs.setDefaultHistoryView(view) }
    }

    fun setReduceMotion(reduce: Boolean) {
        viewModelScope.launch { prefs.setReduceMotion(reduce) }
    }

    fun setCustomAvatarPath(path: String?) {
        viewModelScope.launch {
            prefs.setCustomAvatarPath(path)
            _actionMessage.value = "Foto profil diperbarui."
        }
    }

    fun upsertConnection(connection: AiConnection, newApiKey: String?) {
        val store = vaultStore ?: return
        val saved = store.upsertConnection(connection, newApiKey)
        _actionMessage.value = "Koneksi \"${saved.name}\" disimpan di brankas lokal perangkat."
    }

    fun toggleConnection(connectionId: String) {
        vaultStore?.toggleConnection(connectionId)
    }

    fun deleteConnection(connectionId: String) {
        vaultStore?.deleteConnection(connectionId)
        _actionMessage.value = "Koneksi dihapus."
    }

    fun testCustomConnection(
        connection: AiConnection,
        apiKeyOverride: String? = null,
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            aiService.testCustomConnection(connection, apiKeyOverride)
                .onSuccess { ms ->
                    if (connection.id.isNotBlank()) {
                        vaultStore?.updateTestResult(connection.id, ok = true, latencyMs = ms)
                    }
                    if (connection.kind == "ai") _connectionHealthy.value = true
                    val msg = "Tes ${connection.name} OK (${ms} ms)"
                    _actionMessage.value = msg
                    onComplete(true, msg)
                }
                .onFailure { err ->
                    val errText = toFriendlyIndonesianError(err)
                    if (connection.id.isNotBlank()) {
                        vaultStore?.updateTestResult(connection.id, ok = false, latencyMs = 0L, errorMsg = errText)
                    }
                    _actionMessage.value = errText
                    onComplete(false, errText)
                }
        }
    }

    fun fetchModelsForConnection(
        connection: AiConnection,
        apiKeyOverride: String? = null,
        onResult: (Result<List<String>>) -> Unit
    ) {
        viewModelScope.launch {
            val res = aiService.fetchModelsForConnection(connection, apiKeyOverride)
            res.onFailure { _actionMessage.value = toFriendlyIndonesianError(it) }
            onResult(res)
        }
    }

    fun updateTenantSettings(
        tenant: Tenant,
        name: String,
        aiMode: String,
        watermarkEnabled: Boolean,
        maxFileMb: Long,
        retentionDays: Long,
        monthlyBudgetUsd: Double,
        isOwner: Boolean
    ) {
        viewModelScope.launch {
            tenantRepo.updateTenantSettings(
                tenant, name, aiMode, watermarkEnabled, maxFileMb, retentionDays, monthlyBudgetUsd, isOwner
            )
                .onSuccess { _actionMessage.value = "Pengaturan organisasi disimpan." }
                .onFailure { _actionMessage.value = toFriendlyIndonesianError(it) }
        }
    }

    fun saveTaskConfig(tenant: Tenant, config: TaskConfig) {
        viewModelScope.launch {
            tenantRepo.saveTaskConfig(tenant, config)
                .onSuccess { _actionMessage.value = "Routing tugas ${config.task} diperbarui." }
                .onFailure { _actionMessage.value = toFriendlyIndonesianError(it) }
        }
    }

    fun applyConfigToAllTasks(tenant: Tenant, template: TaskConfig) {
        viewModelScope.launch {
            AppConstants.AI_TASKS.filter { it.key != "audio_transcription" }.forEach { def ->
                tenantRepo.saveTaskConfig(
                    tenant,
                    template.copy(tenantId = tenant.id, task = def.key)
                )
            }
            _actionMessage.value = "Pengaturan ${template.providerLabel} (${template.modelId}) diterapkan ke semua tugas teks AI."
        }
    }

    fun testTaskConfig(tenant: Tenant, config: TaskConfig) {
        viewModelScope.launch {
            _actionMessage.value = "Menguji ${config.providerLabel} • ${config.modelId}..."
            aiService.runSinglePlayground(
                modelId = config.modelId,
                systemPrompt = config.systemPromptOverride.ifBlank {
                    "Ringkas transkrip contoh dalam 1 kalimat Bahasa Indonesia."
                },
                inputText = "Pembicara 1: Kita sepakat target rilis aplikasi adalah tanggal 15 bulan depan.",
                temperature = config.temperature.toFloat(),
                prices = modelPrices.value,
                connectionId = config.connectionId.ifBlank { ProviderPresets.BUILT_IN_AI_ID }
            ).onSuccess { res ->
                _connectionHealthy.value = true
                tenantRepo.recordUsageLog(
                    tenant,
                    res.meta.toUsageLog(
                        tenantId = tenant.id,
                        userId = currentUserId,
                        meetingId = null,
                        task = config.task,
                        playgroundMode = true,
                        adminUids = tenant.adminUids
                    )
                )
                _actionMessage.value = "Tes berhasil (${res.meta.latencyMs} ms): ${res.outputText.take(100)}"
            }.onFailure { err ->
                _connectionHealthy.value = false
                _actionMessage.value = toFriendlyIndonesianError(err)
            }
        }
    }

    fun checkAiHealth() {
        viewModelScope.launch {
            aiService.testConnectionPing()
                .onSuccess { ms ->
                    _connectionHealthy.value = true
                    _actionMessage.value = "Koneksi AI sehat (${ms} ms)."
                }
                .onFailure { e ->
                    _connectionHealthy.value = false
                    _actionMessage.value = toFriendlyIndonesianError(e)
                }
        }
    }

    fun runPlaygroundComparison(
        tenant: Tenant,
        prompt: String,
        sampleInput: String,
        connAId: String,
        modelA: String,
        connBId: String,
        modelB: String,
        temperature: Float
    ) {
        viewModelScope.launch {
            _playgroundLoading.value = true
            _playgroundResults.value = emptyList()
            val prices = modelPrices.value
            val results = mutableListOf<PlaygroundRunResult>()
            listOf(connAId to modelA, connBId to modelB).distinct().forEach { (cId, mId) ->
                aiService.runSinglePlayground(
                    modelId = mId,
                    systemPrompt = prompt,
                    inputText = sampleInput,
                    temperature = temperature,
                    prices = prices,
                    connectionId = cId.ifBlank { ProviderPresets.BUILT_IN_AI_ID }
                )
                    .onSuccess { res ->
                        results.add(res)
                        tenantRepo.recordUsageLog(
                            tenant,
                            res.meta.toUsageLog(
                                tenantId = tenant.id,
                                userId = currentUserId,
                                meetingId = null,
                                task = "playground",
                                playgroundMode = true,
                                adminUids = tenant.adminUids
                            )
                        )
                    }
                    .onFailure { err ->
                        _actionMessage.value = "Model $mId gagal: ${toFriendlyIndonesianError(err)}"
                    }
            }
            _playgroundResults.value = results
            _playgroundLoading.value = false
        }
    }

    fun saveModelPrice(tenant: Tenant, price: ModelPrice) {
        viewModelScope.launch {
            tenantRepo.saveModelPrice(tenant, price)
                .onSuccess { _actionMessage.value = "Harga model ${price.modelId} disimpan." }
                .onFailure { _actionMessage.value = toFriendlyIndonesianError(it) }
        }
    }

    fun createInvite(tenant: Tenant, email: String, role: String) {
        viewModelScope.launch {
            tenantRepo.createInvite(tenant, email, role)
                .onSuccess { _actionMessage.value = "Undangan dikirim ke $email." }
                .onFailure { _actionMessage.value = toFriendlyIndonesianError(it) }
        }
    }

    fun revokeInvite(tenant: Tenant, inviteId: String) {
        viewModelScope.launch {
            tenantRepo.revokeInvite(tenant, inviteId)
                .onSuccess { _actionMessage.value = "Undangan dibatalkan." }
                .onFailure { _actionMessage.value = toFriendlyIndonesianError(it) }
        }
    }

    fun updateMemberRole(tenant: Tenant, member: TenantMember, newRole: String) {
        viewModelScope.launch {
            tenantRepo.updateMemberRole(tenant, member, newRole)
                .onSuccess { _actionMessage.value = "Peran ${member.displayName} diubah ke $newRole." }
                .onFailure { _actionMessage.value = toFriendlyIndonesianError(it) }
        }
    }

    fun clearActionMessage() {
        _actionMessage.value = null
    }
}
