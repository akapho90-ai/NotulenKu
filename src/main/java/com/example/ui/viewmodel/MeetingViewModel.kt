package com.example.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioSegmenter
import com.example.audio.PreparedAudioChunk
import com.example.core.AppConstants
import com.example.core.toFriendlyIndonesianError
import com.example.data.ai.AiService
import com.example.data.ai.PeriodicSummaryResult
import com.example.data.ai.TranscriptCorrectionItem
import com.example.data.ai.toUsageLog
import com.example.data.model.Meeting
import com.example.data.model.ModelInfo
import com.example.data.model.ModelPrice
import com.example.data.model.QaMessage
import com.example.data.model.Tenant
import com.example.data.model.TranscriptSegment
import com.example.data.repository.AudioChunkCheckpoint
import com.example.data.repository.ChunkStatus
import com.example.data.repository.LocalProcessingCheckpointStore
import com.example.data.repository.LocalProcessingJob
import com.example.data.repository.MeetingRepository
import com.example.data.repository.PersonalBackendException
import com.example.data.repository.PersonalMeetingRepository
import com.example.data.repository.PersonalMeetingSavePayload
import com.example.data.repository.TenantRepository
import com.google.firebase.Timestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val TAG = "MeetingVM"

enum class SaveStatus {
    NONE,
    SAVING,
    SAVED,
    FAILED
}

data class ProcessingStepState(
    val meetingId: String = "",
    val stepIndex: Int = 0, // 0 idle, 1 prepared, 2 transcribing, 3 summarizing, 4 ai_done
    val error: String? = null,
    val saveStatus: SaveStatus = SaveStatus.NONE,
    val saveError: String? = null,
    val accountLinkRequired: Boolean = false,
    val stageDetail: String? = null,
    val completedChunks: Int = 0,
    val totalChunks: Int = 0,
    val canRetryProcessing: Boolean = false
)

private data class PendingSaveContext(
    val payload: PersonalMeetingSavePayload,
    val localAudioPath: String?
)

class MeetingViewModel(
    private val personalRepo: PersonalMeetingRepository,
    private val meetingRepo: MeetingRepository, // Used for Firestore Q&A history when tenant is active
    private val tenantRepo: TenantRepository,
    private val aiService: AiService,
    private val currentUserId: String,
    autoLoadOnInit: Boolean = true,
    private val ownerDisplayNameProvider: () -> String = { "Pengguna" },
    private val appContext: Context? = null,
    private val checkpointStore: LocalProcessingCheckpointStore? = appContext?.let { LocalProcessingCheckpointStore(it) }
) : ViewModel() {

    private val _activeTenant = MutableStateFlow<Tenant?>(null)
    private val _meetingsState = MutableStateFlow<UiState<List<Meeting>>>(UiState.Loading)
    val meetingsState: StateFlow<UiState<List<Meeting>>> = _meetingsState.asStateFlow()

    private val _detailState = MutableStateFlow<UiState<Meeting>?>(null)
    val detailState: StateFlow<UiState<Meeting>?> = _detailState.asStateFlow()

    private val _processingState = MutableStateFlow<ProcessingStepState?>(null)
    val processingState: StateFlow<ProcessingStepState?> = _processingState.asStateFlow()

    private val _pendingLocalJobs = MutableStateFlow<List<LocalProcessingJob>>(emptyList())
    val pendingLocalJobs: StateFlow<List<LocalProcessingJob>> = _pendingLocalJobs.asStateFlow()

    private val _accountLinkRequired = MutableStateFlow(false)
    val accountLinkRequired: StateFlow<Boolean> = _accountLinkRequired.asStateFlow()

    private val _linkAccountBusy = MutableStateFlow(false)
    val linkAccountBusy: StateFlow<Boolean> = _linkAccountBusy.asStateFlow()

    private val _uiMessage = MutableStateFlow<String?>(null)
    val uiMessage: StateFlow<String?> = _uiMessage.asStateFlow()

    private val _qaLoading = MutableStateFlow(false)
    val qaLoading: StateFlow<Boolean> = _qaLoading.asStateFlow()

    private val _localQaMessages = MutableStateFlow<List<QaMessage>>(emptyList())

    private val _correctionItems = MutableStateFlow<List<TranscriptCorrectionItem>>(emptyList())
    val correctionItems: StateFlow<List<TranscriptCorrectionItem>> = _correctionItems.asStateFlow()

    private val _aiTaskBusy = MutableStateFlow(false)
    val aiTaskBusy: StateFlow<Boolean> = _aiTaskBusy.asStateFlow()

    private val _periodicSummaryState = MutableStateFlow<UiState<PeriodicSummaryResult>?>(null)
    val periodicSummaryState: StateFlow<UiState<PeriodicSummaryResult>?> = _periodicSummaryState.asStateFlow()

    private var pendingSave: PendingSaveContext? = null
    private var activeJob: LocalProcessingJob? = null
    private var activeDetailMeetingId: String? = null

    init {
        refreshPendingLocalJobs()
        if (autoLoadOnInit && currentUserId.isNotBlank()) {
            refreshMeetings()
        }
    }

    fun setActiveTenant(tenant: Tenant?) {
        if (_activeTenant.value?.id != tenant?.id) {
            _activeTenant.value = tenant
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val firestoreQaMessages: StateFlow<List<QaMessage>> = _activeTenant
        .flatMapLatest { tenant ->
            if (tenant == null) flowOf(emptyList())
            else meetingRepo.observeQaMessages(tenant.id, currentUserId)
                .catch { e ->
                    if (e is CancellationException) throw e
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val qaMessages: StateFlow<List<QaMessage>> = combine(
        firestoreQaMessages,
        _localQaMessages
    ) { remote, local ->
        if (remote.isNotEmpty()) remote else local
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    fun refreshPendingLocalJobs() {
        val store = checkpointStore ?: return
        if (currentUserId.isBlank()) {
            _pendingLocalJobs.value = emptyList()
            return
        }
        _pendingLocalJobs.value = store.listJobs(currentUserId)
    }

    fun discardLocalJob(meetingId: String) {
        checkpointStore?.removeJobAndCleanTempChunks(currentUserId, meetingId)
        if (activeJob?.meetingId == meetingId) {
            activeJob = null
        }
        refreshPendingLocalJobs()
    }

    fun refreshMeetings(showLoading: Boolean = true) {
        if (currentUserId.isBlank()) {
            _meetingsState.value = UiState.Success(emptyList())
            return
        }
        viewModelScope.launch {
            if (showLoading || _meetingsState.value !is UiState.Success) {
                _meetingsState.value = UiState.Loading
            }
            personalRepo.listMeetings()
                .onSuccess { list ->
                    _accountLinkRequired.value = false
                    val hydrated = list.map { hydrateLocalAudioPath(it) }
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                    _meetingsState.value = UiState.Success(hydrated)
                }
                .onFailure { err ->
                    Log.w(TAG, "Failed loading Supabase personal meetings", err)
                    val backendErr = err as? PersonalBackendException
                    _accountLinkRequired.value = backendErr?.isAccountLinkRequired == true
                    val msg = backendErr?.message ?: toFriendlyIndonesianError(err)
                    _meetingsState.value = UiState.Error(msg)
                }
        }
    }

    fun loadMeetingDetail(meetingId: String, forceSpinner: Boolean = false) {
        if (meetingId.isBlank()) return
        activeDetailMeetingId = meetingId
        val cached = (_meetingsState.value as? UiState.Success)?.data?.firstOrNull { it.id == meetingId }
        _detailState.value = if (!forceSpinner && cached != null) {
            UiState.Success(hydrateLocalAudioPath(cached))
        } else {
            UiState.Loading
        }

        viewModelScope.launch {
            personalRepo.getMeetingDetail(meetingId)
                .onSuccess { fetched ->
                    _accountLinkRequired.value = false
                    val hydrated = hydrateLocalAudioPath(fetched)
                    if (activeDetailMeetingId == meetingId) {
                        _detailState.value = UiState.Success(hydrated)
                    }
                    upsertMeetingInState(hydrated)
                }
                .onFailure { err ->
                    val backendErr = err as? PersonalBackendException
                    if (backendErr?.isAccountLinkRequired == true) {
                        _accountLinkRequired.value = true
                    }
                    val msg = backendErr?.message ?: toFriendlyIndonesianError(err)
                    if (activeDetailMeetingId == meetingId) {
                        if (backendErr?.statusCode == "NOT_FOUND" || backendErr?.statusCode == "PERMISSION_DENIED") {
                            removeMeetingFromState(meetingId)
                            _detailState.value = UiState.Error(msg)
                        } else if (cached == null || forceSpinner) {
                            _detailState.value = UiState.Error(msg)
                        }
                    }
                }
        }
    }

    fun processAudioMeeting(
        tenant: Tenant?,
        judul: String,
        audioFile: File,
        mimeType: String,
        durasiDetik: Long,
        taskConfigs: List<com.example.data.model.TaskConfig>,
        prices: List<ModelPrice>,
        preferredLanguage: String = "id"
    ) {
        viewModelScope.launch {
            val resolvedTitle = judul.trim().ifBlank {
                buildDefaultMeetingTitle(ownerDisplayNameProvider())
            }
            val sizeBytes = audioFile.length()
            // Dedicated Supabase UUID generated & preserved before saving so retries are idempotent
            val meetingId = UUID.randomUUID().toString()
            _processingState.value = ProcessingStepState(
                meetingId = meetingId,
                stepIndex = 1,
                stageDetail = "Menyiapkan berkas audio lokal..."
            )

            if (tenant != null) {
                tenantRepo.recordAuditLog(
                    tenant = tenant,
                    userId = currentUserId,
                    aksi = "CONSENT_AND_CREATE_MEETING",
                    target = meetingId,
                    detail = "Persetujuan rekaman dicatat; ukuran=${sizeBytes}B"
                )
            }

            val initialJob = LocalProcessingJob(
                meetingId = meetingId,
                ownerUserId = currentUserId,
                judul = resolvedTitle,
                originalAudioPath = audioFile.absolutePath,
                mimeType = mimeType.ifBlank { "audio/mp4" },
                durasiDetik = durasiDetik.coerceAtLeast(1L),
                ukuranBytes = sizeBytes,
                preferredLanguage = preferredLanguage
            )
            if (!persistJob(initialJob)) return@launch
            executeResumableJob(
                job = initialJob,
                tenant = tenant,
                taskConfigs = taskConfigs,
                prices = prices
            )
        }
    }

    fun retryActiveProcessing(
        tenant: Tenant?,
        taskConfigs: List<com.example.data.model.TaskConfig>,
        prices: List<ModelPrice>
    ) {
        val job = activeJob ?: _pendingLocalJobs.value.firstOrNull {
            it.meetingId == _processingState.value?.meetingId
        }
        if (job == null) {
            if (pendingSave != null) {
                retrySaveMeeting()
            } else {
                _uiMessage.value = "Tidak ada proses tertunda untuk diulang."
            }
            return
        }
        viewModelScope.launch {
            executeResumableJob(job, tenant, taskConfigs, prices)
        }
    }

    fun resumeLocalJob(
        job: LocalProcessingJob,
        tenant: Tenant?,
        taskConfigs: List<com.example.data.model.TaskConfig>,
        prices: List<ModelPrice>
    ) {
        viewModelScope.launch {
            executeResumableJob(job, tenant, taskConfigs, prices)
        }
    }

    fun retryMeeting(
        tenant: Tenant?,
        meeting: Meeting,
        taskConfigs: List<com.example.data.model.TaskConfig>,
        prices: List<ModelPrice>
    ) {
        viewModelScope.launch {
            val savedCheckpoint = checkpointStore?.loadJob(currentUserId, meeting.id)
            if (savedCheckpoint != null) {
                executeResumableJob(savedCheckpoint, tenant, taskConfigs, prices)
                return@launch
            }
            val path = meeting.localAudioPath
            val file = path?.let { File(it) }
            if (meeting.transcriptText.isNotBlank()) {
                val job = LocalProcessingJob(
                    meetingId = meeting.id,
                    ownerUserId = currentUserId,
                    judul = meeting.judul,
                    originalAudioPath = path.orEmpty(),
                    mimeType = "audio/mp4",
                    durasiDetik = meeting.durasiDetik,
                    ukuranBytes = meeting.ukuranBytes,
                    transcriptDone = true,
                    bahasa = meeting.bahasa,
                    mergedSegments = meeting.segments,
                    mergedTranscriptText = meeting.transcriptText
                )
                if (!persistJob(job)) return@launch
                executeResumableJob(job, tenant, taskConfigs, prices)
            } else if (file != null && file.exists()) {
                val job = LocalProcessingJob(
                    meetingId = meeting.id,
                    ownerUserId = currentUserId,
                    judul = meeting.judul,
                    originalAudioPath = file.absolutePath,
                    mimeType = "audio/mp4",
                    durasiDetik = meeting.durasiDetik.coerceAtLeast(1L),
                    ukuranBytes = file.length()
                )
                persistJob(job)
                executeResumableJob(job, tenant, taskConfigs, prices)
            } else {
                _uiMessage.value = "Berkas audio lokal tidak ditemukan untuk diulang."
            }
        }
    }

    private suspend fun executeResumableJob(
        job: LocalProcessingJob,
        tenant: Tenant?,
        taskConfigs: List<com.example.data.model.TaskConfig>,
        prices: List<ModelPrice>
    ) {
        var currentJob = job
        activeJob = currentJob
        // Processing is unsafe without a durable checkpoint. Fail closed before AI/backend work.
        if (!persistJob(currentJob)) return

        // Stage 4 shortcut: if summary is already done, ONLY retry saving to Supabase without re-running AI!
        if (currentJob.summaryDone) {
            pendingSave = PendingSaveContext(
                payload = currentJob.toSavePayload(),
                localAudioPath = currentJob.originalAudioPath.takeIf { it.isNotBlank() }
            )
            executePendingSave()
            return
        }

        // Stage 1 & 2: Prepare valid chunks & transcribe ONLY pending/failed chunks
        if (!currentJob.transcriptDone) {
            val originalFile = File(currentJob.originalAudioPath)
            if (!originalFile.exists() || originalFile.length() <= 0L) {
                currentJob = currentJob.copy(lastError = "Berkas audio asli tidak ditemukan di perangkat.")
                if (!persistJob(currentJob)) return
                _processingState.value = ProcessingStepState(
                    meetingId = currentJob.meetingId,
                    stepIndex = 2,
                    error = currentJob.lastError,
                    canRetryProcessing = false
                )
                return
            }

            // Prepare chunks if not yet created OR if temp chunk files were purged by OS cache cleaner
            val needPrepareChunks = currentJob.chunks.isEmpty() || currentJob.chunks.any {
                it.status != ChunkStatus.DONE && !File(it.filePath).exists()
            }
            if (needPrepareChunks) {
                _processingState.value = ProcessingStepState(
                    meetingId = currentJob.meetingId,
                    stepIndex = 1,
                    stageDetail = "Memeriksa dan menyiapkan segmen audio..."
                )
                val preparedChunks: List<PreparedAudioChunk> = if (appContext != null) {
                    val prepRes = withContext(Dispatchers.IO) {
                        AudioSegmenter.prepareAudioChunks(
                            context = appContext,
                            sessionId = currentJob.meetingId,
                            sourceFile = originalFile,
                            mimeType = currentJob.mimeType,
                            knownDurationSec = currentJob.durasiDetik
                        )
                    }
                    prepRes.getOrElse { err ->
                        val msg = toFriendlyIndonesianError(err)
                        currentJob = currentJob.copy(lastError = msg)
                        if (!persistJob(currentJob)) return
                        _processingState.value = ProcessingStepState(
                            meetingId = currentJob.meetingId,
                            stepIndex = 1,
                            error = msg,
                            canRetryProcessing = true
                        )
                        return
                    }
                } else {
                    listOf(
                        PreparedAudioChunk(
                            index = 0,
                            startOffsetSec = 0L,
                            endOffsetSec = currentJob.durasiDetik.coerceAtLeast(1L),
                            file = originalFile,
                            mimeType = currentJob.mimeType,
                            isTempChunk = false
                        )
                    )
                }

                // Preserve already-DONE chunks when re-preparing after OS cache cleanup
                val oldDoneByIndex = currentJob.chunks
                    .filter { it.status == ChunkStatus.DONE }
                    .associateBy { it.index }

                val mergedChunks = preparedChunks.map { p ->
                    val prev = oldDoneByIndex[p.index]
                    if (prev != null) {
                        prev.copy(filePath = p.file.absolutePath, isTempChunk = p.isTempChunk)
                    } else {
                        AudioChunkCheckpoint(
                            index = p.index,
                            startOffsetSec = p.startOffsetSec,
                            endOffsetSec = p.endOffsetSec,
                            filePath = p.file.absolutePath,
                            mimeType = p.mimeType,
                            isTempChunk = p.isTempChunk,
                            status = ChunkStatus.PENDING
                        )
                    }
                }
                currentJob = currentJob.copy(chunks = mergedChunks, lastError = null)
                if (!persistJob(currentJob)) return
            }

            val sttConfig = taskConfigs.firstOrNull { it.task == "audio_transcription" }
            val totalChunks = currentJob.chunks.size
            val updatedChunks = currentJob.chunks.toMutableList()
            var detectedLang = currentJob.preferredLanguage

            for (i in updatedChunks.indices) {
                val chunk = updatedChunks[i]
                if (chunk.status == ChunkStatus.DONE) {
                    if (chunk.bahasa.isNotBlank()) detectedLang = chunk.bahasa
                    continue
                }

                val doneBefore = updatedChunks.count { it.status == ChunkStatus.DONE }
                val progressText = if (totalChunks > 1) {
                    "Mentranskrip bagian ${i + 1} dari $totalChunks..."
                } else {
                    "Mentranskrip rekaman melalui SumoPod AI..."
                }
                _processingState.value = ProcessingStepState(
                    meetingId = currentJob.meetingId,
                    stepIndex = 2,
                    stageDetail = progressText,
                    completedChunks = doneBefore,
                    totalChunks = totalChunks
                )

                val chunkFile = File(chunk.filePath)
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { chunkFile.readBytes() }.getOrNull()
                }
                if (bytes == null || bytes.isEmpty()) {
                    val errMsg = "Bagian audio ${i + 1}/$totalChunks tidak dapat dibaca. Rekaman asli tetap aman."
                    updatedChunks[i] = chunk.copy(status = ChunkStatus.FAILED, errorMessage = errMsg)
                    currentJob = currentJob.copy(chunks = updatedChunks, lastError = errMsg)
                    if (!persistJob(currentJob)) return
                    _processingState.value = ProcessingStepState(
                        meetingId = currentJob.meetingId,
                        stepIndex = 2,
                        error = errMsg,
                        stageDetail = "Gagal pada bagian ${i + 1} dari $totalChunks",
                        completedChunks = doneBefore,
                        totalChunks = totalChunks,
                        canRetryProcessing = true
                    )
                    return
                }

                val sttResult = aiService.transcribeAudio(
                    audioBytes = bytes,
                    mimeType = chunk.mimeType,
                    config = sttConfig,
                    prices = prices,
                    preferredLanguage = currentJob.preferredLanguage
                )

                val trans = sttResult.getOrElse { err ->
                    val errMsg = toFriendlyIndonesianError(err)
                    val fullErr = if (totalChunks > 1) {
                        "Transkripsi gagal pada bagian ${i + 1} dari $totalChunks: $errMsg"
                    } else {
                        "Transkripsi gagal. $errMsg"
                    }
                    updatedChunks[i] = chunk.copy(status = ChunkStatus.FAILED, errorMessage = errMsg)
                    currentJob = currentJob.copy(chunks = updatedChunks, lastError = fullErr)
                    if (!persistJob(currentJob)) return
                    _processingState.value = ProcessingStepState(
                        meetingId = currentJob.meetingId,
                        stepIndex = 2,
                        error = fullErr,
                        stageDetail = "$doneBefore dari $totalChunks bagian sudah selesai dan tersimpan lokal",
                        completedChunks = doneBefore,
                        totalChunks = totalChunks,
                        canRetryProcessing = true
                    )
                    return
                }

                if (tenant != null) {
                    tenantRepo.recordUsageLog(
                        tenant,
                        trans.meta.toUsageLog(
                            tenantId = tenant.id,
                            userId = currentUserId,
                            meetingId = currentJob.meetingId,
                            task = "audio_transcription",
                            kind = "stt",
                            audioSeconds = (chunk.endOffsetSec - chunk.startOffsetSec).coerceAtLeast(1L),
                            adminUids = tenant.adminUids
                        )
                    )
                }

                detectedLang = trans.bahasa.ifBlank { detectedLang }
                updatedChunks[i] = chunk.copy(
                    status = ChunkStatus.DONE,
                    bahasa = detectedLang,
                    segments = trans.segments,
                    errorMessage = null
                )
                currentJob = currentJob.copy(chunks = updatedChunks, lastError = null)
                if (!persistJob(currentJob)) return
            }

            // Strictly verify ALL chunks succeeded before marking transcription done
            if (updatedChunks.any { it.status != ChunkStatus.DONE }) {
                val msg = "Sebagian segmen transkripsi belum selesai. Ketuk Proses Ulang untuk melanjutkan."
                currentJob = currentJob.copy(lastError = msg)
                if (!persistJob(currentJob)) return
                _processingState.value = ProcessingStepState(
                    meetingId = currentJob.meetingId,
                    stepIndex = 2,
                    error = msg,
                    completedChunks = updatedChunks.count { it.status == ChunkStatus.DONE },
                    totalChunks = totalChunks,
                    canRetryProcessing = true
                )
                return
            }

            val mergedSegments: List<TranscriptSegment> = AudioSegmenter.mergeChunkTranscripts(
                updatedChunks.sortedBy { it.index }.map { it.startOffsetSec to it.segments }
            )
            if (mergedSegments.isEmpty()) {
                val msg = "Transkrip kosong — tidak ada ucapan yang terdeteksi atau model tidak mengembalikan teks."
                currentJob = currentJob.copy(lastError = msg)
                if (!persistJob(currentJob)) return
                _processingState.value = ProcessingStepState(
                    meetingId = currentJob.meetingId,
                    stepIndex = 2,
                    error = msg,
                    canRetryProcessing = true
                )
                return
            }

            val inferredDuration = mergedSegments.maxOfOrNull { it.selesaiDetik }
                ?.coerceAtLeast(currentJob.durasiDetik)
                ?: currentJob.durasiDetik
            val fullText = mergedSegments.joinToString("\n") { "[${it.pembicara}] ${it.teks}" }

            currentJob = currentJob.copy(
                transcriptDone = true,
                bahasa = detectedLang,
                durasiDetik = inferredDuration,
                mergedSegments = mergedSegments,
                mergedTranscriptText = fullText,
                lastError = null
            )
            if (!persistJob(currentJob)) return
        }

        // Stage 3: Generate Summary & Action Items (reuses completed transcript on retry!)
        _processingState.value = ProcessingStepState(
            meetingId = currentJob.meetingId,
            stepIndex = 3,
            stageDetail = "Menyusun ringkasan, keputusan, & tindak lanjut...",
            completedChunks = currentJob.completedChunkCount,
            totalChunks = currentJob.totalChunkCount
        )
        val sumConfig = taskConfigs.firstOrNull { it.task == "meeting_summary" }
        val sumResult = aiService.generateSummaryAndActions(currentJob.mergedTranscriptText, sumConfig, prices)
        val summary = sumResult.getOrElse { err ->
            val errMsg = "Transkrip selesai, tetapi penyusunan notulen gagal: ${toFriendlyIndonesianError(err)}"
            currentJob = currentJob.copy(lastError = errMsg)
            if (!persistJob(currentJob)) return
            _processingState.value = ProcessingStepState(
                meetingId = currentJob.meetingId,
                stepIndex = 3,
                error = errMsg,
                stageDetail = "Transkrip sudah aman; Proses Ulang tidak akan mentranskrip ulang",
                canRetryProcessing = true
            )
            return
        }

        if (tenant != null) {
            tenantRepo.recordUsageLog(
                tenant,
                summary.meta.toUsageLog(
                    tenantId = tenant.id,
                    userId = currentUserId,
                    meetingId = currentJob.meetingId,
                    task = "meeting_summary",
                    adminUids = tenant.adminUids
                )
            )
        }

        val nowLabel = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id", "ID")).format(Date())
        val modelInfo = ModelInfo(
            provider = summary.meta.providerLabel,
            model = summary.meta.modelId,
            generatedAtLabel = nowLabel
        )
        currentJob = currentJob.copy(
            summaryDone = true,
            ringkasan = summary.ringkasan,
            poinUtama = summary.poinUtama,
            keputusan = summary.keputusan,
            risiko = summary.risiko,
            angkaKunci = summary.angkaKunci,
            actionItems = summary.actionItems,
            modelInfo = modelInfo,
            lastError = null
        )
        if (!persistJob(currentJob)) return

        pendingSave = PendingSaveContext(
            payload = currentJob.toSavePayload(),
            localAudioPath = currentJob.originalAudioPath.takeIf { it.isNotBlank() }
        )
        executePendingSave()
    }

    fun retrySaveMeeting() {
        if (pendingSave == null) {
            val job = activeJob?.takeIf { it.summaryDone }
            if (job != null) {
                pendingSave = PendingSaveContext(
                    payload = job.toSavePayload(),
                    localAudioPath = job.originalAudioPath.takeIf { it.isNotBlank() }
                )
            } else {
                return
            }
        }
        viewModelScope.launch {
            executePendingSave()
        }
    }

    private suspend fun executePendingSave() {
        val ctx = pendingSave ?: return
        val meetingId = ctx.payload.meetingId
        _processingState.value = ProcessingStepState(
            meetingId = meetingId,
            stepIndex = 4,
            saveStatus = SaveStatus.SAVING,
            stageDetail = "Menyimpan notulen ke akun Anda..."
        )

        personalRepo.saveMeetingBundle(ctx.payload)
            .onSuccess { saved ->
                _accountLinkRequired.value = false
                personalRepo.rememberLocalAudioPath(currentUserId, meetingId, ctx.localAudioPath)
                val hydrated = hydrateLocalAudioPath(saved)
                upsertMeetingInState(hydrated)
                if (activeDetailMeetingId == meetingId) {
                    _detailState.value = UiState.Success(hydrated)
                }
                pendingSave = null
                activeJob = null
                // Clean ONLY temp chunks & checkpoint — NEVER delete originalAudioPath!
                checkpointStore?.removeJobAndCleanTempChunks(currentUserId, meetingId)
                appContext?.let { AudioSegmenter.cleanupTempChunkDir(it, meetingId) }
                refreshPendingLocalJobs()

                _processingState.value = ProcessingStepState(
                    meetingId = meetingId,
                    stepIndex = 4,
                    saveStatus = SaveStatus.SAVED
                )
                _uiMessage.value = "Transkrip dan notulen rapat tersimpan."
            }
            .onFailure { err ->
                Log.w(TAG, "Auto-save to Supabase failed for $meetingId (retryable)", err)
                val backendErr = err as? PersonalBackendException
                val linkReq = backendErr?.isAccountLinkRequired == true
                if (linkReq) _accountLinkRequired.value = true
                val msg = backendErr?.message ?: toFriendlyIndonesianError(err)
                activeJob?.let { j ->
                    persistJob(j.copy(lastError = "Gagal menyimpan ke server: $msg"))
                }
                _processingState.value = ProcessingStepState(
                    meetingId = meetingId,
                    stepIndex = 4,
                    saveStatus = SaveStatus.FAILED,
                    saveError = msg,
                    accountLinkRequired = linkReq
                )
            }
    }

    fun generatePeriodicSummary(
        periodType: String, // "weekly" | "monthly"
        tenant: Tenant?,
        allMeetings: List<Meeting>,
        taskConfigs: List<com.example.data.model.TaskConfig>,
        prices: List<ModelPrice>
    ) {
        viewModelScope.launch {
            val now = Date()
            val (label, rangeLabel, filtered) = filterMeetingsForPeriod(allMeetings, periodType, now)
            if (filtered.isEmpty()) {
                _periodicSummaryState.value = UiState.Error(
                    "Belum ada rapat milik akun Anda pada periode $label ($rangeLabel)."
                )
                return@launch
            }
            _periodicSummaryState.value = UiState.Loading
            val cfg = taskConfigs.firstOrNull { it.task == "periodic_summary" }
            aiService.generatePeriodicSummary(label, rangeLabel, filtered, cfg, prices)
                .onSuccess { res ->
                    _periodicSummaryState.value = UiState.Success(res)
                    if (tenant != null) {
                        tenantRepo.recordUsageLog(
                            tenant,
                            res.meta.toUsageLog(
                                tenantId = tenant.id,
                                userId = currentUserId,
                                meetingId = null,
                                task = "periodic_summary",
                                adminUids = tenant.adminUids
                            )
                        )
                    }
                }
                .onFailure { err ->
                    _periodicSummaryState.value = UiState.Error(toFriendlyIndonesianError(err))
                }
        }
    }

    fun clearPeriodicSummary() {
        _periodicSummaryState.value = null
    }

    fun linkSupabaseAccount(supabaseEmail: String, supabasePassword: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            _linkAccountBusy.value = true
            personalRepo.linkSupabaseAccount(supabaseEmail, supabasePassword)
                .onSuccess {
                    _accountLinkRequired.value = false
                    _uiMessage.value = "Akun Supabase berhasil ditautkan."
                    refreshMeetings(showLoading = false)
                    if (pendingSave != null) {
                        executePendingSave()
                    }
                    onDone()
                }
                .onFailure { err ->
                    val msg = (err as? PersonalBackendException)?.message ?: toFriendlyIndonesianError(err)
                    _uiMessage.value = msg
                }
            _linkAccountBusy.value = false
        }
    }

    fun regenerateSummary(
        tenant: Tenant?,
        meeting: Meeting,
        taskConfigs: List<com.example.data.model.TaskConfig>,
        prices: List<ModelPrice>
    ) {
        viewModelScope.launch {
            _aiTaskBusy.value = true
            val sumConfig = taskConfigs.firstOrNull { it.task == "meeting_summary" }
            aiService.generateSummaryAndActions(meeting.transcriptText, sumConfig, prices)
                .onSuccess { summary ->
                    if (tenant != null) {
                        tenantRepo.recordUsageLog(
                            tenant,
                            summary.meta.toUsageLog(
                                tenantId = tenant.id,
                                userId = currentUserId,
                                meetingId = meeting.id,
                                task = "meeting_summary",
                                adminUids = tenant.adminUids
                            )
                        )
                    }
                    val nowLabel = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id", "ID")).format(Date())
                    val modelInfo = ModelInfo(
                        provider = summary.meta.providerLabel,
                        model = summary.meta.modelId,
                        generatedAtLabel = nowLabel
                    )
                    personalRepo.updateMeetingFields(
                        meeting.id,
                        mapOf(
                            "ringkasan" to summary.ringkasan,
                            "poinUtama" to summary.poinUtama,
                            "keputusan" to summary.keputusan,
                            "risiko" to summary.risiko,
                            "angkaKunci" to summary.angkaKunci.map { it.toMap() },
                            "actionItems" to summary.actionItems.map { it.toMap() },
                            "modelInfo" to modelInfo.toMap()
                        )
                    ).onSuccess { updated ->
                        applyMeetingUpdate(updated)
                        _uiMessage.value = "Notulen berhasil diperbarui."
                    }.onFailure { _uiMessage.value = toFriendlyIndonesianError(it) }
                }
                .onFailure { _uiMessage.value = toFriendlyIndonesianError(it) }
            _aiTaskBusy.value = false
        }
    }

    fun runMeetingAnalysis(
        tenant: Tenant?,
        meeting: Meeting,
        taskConfigs: List<com.example.data.model.TaskConfig>,
        prices: List<ModelPrice>
    ) {
        viewModelScope.launch {
            _aiTaskBusy.value = true
            val cfg = taskConfigs.firstOrNull { it.task == "meeting_analysis" }
            aiService.analyzeMeeting(meeting.transcriptText, cfg, prices)
                .onSuccess { (analysis, meta) ->
                    personalRepo.updateMeetingFields(meeting.id, mapOf("analysis" to analysis.toMap()))
                        .onSuccess { updated ->
                            applyMeetingUpdate(updated)
                            if (tenant != null) {
                                tenantRepo.recordUsageLog(
                                    tenant,
                                    meta.toUsageLog(tenant.id, currentUserId, meeting.id, "meeting_analysis", adminUids = tenant.adminUids)
                                )
                            }
                            _uiMessage.value = "Analisis rapat diperbarui."
                        }
                        .onFailure { _uiMessage.value = toFriendlyIndonesianError(it) }
                }
                .onFailure { _uiMessage.value = toFriendlyIndonesianError(it) }
            _aiTaskBusy.value = false
        }
    }

    fun runTranscriptCorrection(
        tenant: Tenant?,
        meeting: Meeting,
        taskConfigs: List<com.example.data.model.TaskConfig>,
        prices: List<ModelPrice>
    ) {
        viewModelScope.launch {
            _aiTaskBusy.value = true
            val cfg = taskConfigs.firstOrNull { it.task == "transcript_correction" }
            aiService.correctTranscript(meeting.segments, cfg, prices)
                .onSuccess { (items, meta) ->
                    _correctionItems.value = items
                    if (tenant != null) {
                        tenantRepo.recordUsageLog(
                            tenant,
                            meta.toUsageLog(tenant.id, currentUserId, meeting.id, "transcript_correction", adminUids = tenant.adminUids)
                        )
                    }
                    if (items.isEmpty()) {
                        _uiMessage.value = "Transkrip sudah rapi, tidak ada koreksi yang diperlukan."
                    }
                }
                .onFailure { _uiMessage.value = toFriendlyIndonesianError(it) }
            _aiTaskBusy.value = false
        }
    }

    fun acceptCorrection(meeting: Meeting, item: TranscriptCorrectionItem) {
        viewModelScope.launch {
            val updated = meeting.segments.toMutableList()
            if (item.indeks in updated.indices) {
                updated[item.indeks] = updated[item.indeks].copy(teks = item.teksKoreksi)
                val fullText = updated.joinToString("\n") { "[${it.pembicara}] ${it.teks}" }
                personalRepo.updateMeetingFields(
                    meeting.id,
                    mapOf(
                        "segmen" to updated.map { it.toMap() },
                        "isi" to fullText
                    )
                ).onSuccess { applyMeetingUpdate(it) }
                    .onFailure { _uiMessage.value = toFriendlyIndonesianError(it) }
            }
            _correctionItems.value = _correctionItems.value.filterNot { it.indeks == item.indeks }
        }
    }

    fun rejectCorrection(item: TranscriptCorrectionItem) {
        _correctionItems.value = _correctionItems.value.filterNot { it.indeks == item.indeks }
    }

    fun generateVisualData(
        tenant: Tenant?,
        meeting: Meeting,
        taskConfigs: List<com.example.data.model.TaskConfig>,
        prices: List<ModelPrice>
    ) {
        viewModelScope.launch {
            _aiTaskBusy.value = true
            val cfg = taskConfigs.firstOrNull { it.task == "structured_visual_data" }
            aiService.generateVisualData(meeting, cfg, prices)
                .onSuccess { (visual, meta) ->
                    personalRepo.updateMeetingFields(meeting.id, mapOf("visualJson" to visual.toMap()))
                        .onSuccess { updated ->
                            applyMeetingUpdate(updated)
                            if (tenant != null) {
                                tenantRepo.recordUsageLog(
                                    tenant,
                                    meta.toUsageLog(tenant.id, currentUserId, meeting.id, "structured_visual_data", adminUids = tenant.adminUids)
                                )
                            }
                        }
                        .onFailure { _uiMessage.value = toFriendlyIndonesianError(it) }
                }
                .onFailure { _uiMessage.value = toFriendlyIndonesianError(it) }
            _aiTaskBusy.value = false
        }
    }

    fun askQuestion(
        tenant: Tenant?,
        question: String,
        scope: String,
        selectedMeetingId: String?,
        allMeetings: List<Meeting>,
        taskConfigs: List<com.example.data.model.TaskConfig>,
        prices: List<ModelPrice>
    ) {
        val cleanQ = question.trim()
        if (cleanQ.isBlank()) return

        viewModelScope.launch {
            _qaLoading.value = true
            val userMsg = QaMessage(
                id = "local_${UUID.randomUUID().toString().take(8)}",
                tenantId = tenant?.id.orEmpty(),
                userId = currentUserId,
                scope = scope,
                meetingId = selectedMeetingId,
                role = "user",
                isi = cleanQ,
                createdAt = Timestamp.now()
            )
            _localQaMessages.value = _localQaMessages.value + userMsg
            if (tenant != null) {
                meetingRepo.saveQaMessage(tenant.id, scope, selectedMeetingId, "user", cleanQ, emptyList())
            }

            val contextMeetings = if (scope == "meeting" && selectedMeetingId != null) {
                allMeetings.filter { it.id == selectedMeetingId }
            } else {
                allMeetings.filter { it.status == "done" || it.transcriptText.isNotBlank() || it.ringkasan.isNotBlank() }
            }

            if (contextMeetings.isEmpty()) {
                val emptyReply = "Belum ada catatan rapat yang selesai untuk dijadikan sumber jawaban."
                _localQaMessages.value = _localQaMessages.value + QaMessage(
                    id = "local_${UUID.randomUUID().toString().take(8)}",
                    tenantId = tenant?.id.orEmpty(),
                    userId = currentUserId,
                    scope = scope,
                    meetingId = selectedMeetingId,
                    role = "assistant",
                    isi = emptyReply,
                    createdAt = Timestamp.now()
                )
                if (tenant != null) {
                    meetingRepo.saveQaMessage(tenant.id, scope, selectedMeetingId, "assistant", emptyReply, emptyList())
                }
                _qaLoading.value = false
                return@launch
            }

            val cfg = taskConfigs.firstOrNull { it.task == "meeting_qa" }
            aiService.answerQuestion(cleanQ, contextMeetings, qaMessages.value, cfg, prices)
                .onSuccess { ans ->
                    _localQaMessages.value = _localQaMessages.value + QaMessage(
                        id = "local_${UUID.randomUUID().toString().take(8)}",
                        tenantId = tenant?.id.orEmpty(),
                        userId = currentUserId,
                        scope = scope,
                        meetingId = selectedMeetingId,
                        role = "assistant",
                        isi = ans.jawaban,
                        sumber = ans.sumber,
                        createdAt = Timestamp.now()
                    )
                    if (tenant != null) {
                        meetingRepo.saveQaMessage(
                            tenant.id,
                            scope,
                            selectedMeetingId,
                            "assistant",
                            ans.jawaban,
                            ans.sumber
                        )
                        tenantRepo.recordUsageLog(
                            tenant,
                            ans.meta.toUsageLog(tenant.id, currentUserId, selectedMeetingId, "meeting_qa", adminUids = tenant.adminUids)
                        )
                    }
                }
                .onFailure { err ->
                    _uiMessage.value = toFriendlyIndonesianError(err)
                }
            _qaLoading.value = false
        }
    }

    fun clearQaHistory(tenant: Tenant?) {
        _localQaMessages.value = emptyList()
        if (tenant != null) {
            viewModelScope.launch {
                meetingRepo.clearQaHistory(tenant.id, firestoreQaMessages.value)
            }
        }
    }

    fun toggleActionItem(meeting: Meeting, itemId: String) {
        viewModelScope.launch {
            val updatedItems = meeting.actionItems.map {
                if (it.id == itemId) it.copy(selesai = !it.selesai) else it
            }
            personalRepo.updateMeetingFields(
                meeting.id,
                mapOf("actionItems" to updatedItems.map { it.toMap() })
            ).onSuccess { applyMeetingUpdate(it) }
                .onFailure { _uiMessage.value = toFriendlyIndonesianError(it) }
        }
    }

    fun updateSummaryFields(
        meeting: Meeting,
        ringkasan: String,
        poinUtama: List<String>,
        keputusan: List<String>,
        risiko: List<String>
    ) {
        viewModelScope.launch {
            val info = meeting.modelInfo ?: ModelInfo("Manual", AppConstants.DEFAULT_MODEL_ID, "")
            personalRepo.updateMeetingFields(
                meeting.id,
                mapOf(
                    "ringkasan" to ringkasan,
                    "poinUtama" to poinUtama,
                    "keputusan" to keputusan,
                    "risiko" to risiko,
                    "modelInfo" to info.toMap()
                )
            ).onSuccess {
                applyMeetingUpdate(it)
                _uiMessage.value = "Notulen diperbarui."
            }.onFailure { _uiMessage.value = toFriendlyIndonesianError(it) }
        }
    }

    fun renameSpeaker(meeting: Meeting, oldName: String, newName: String) {
        val clean = newName.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            val updatedSegs = meeting.segments.map {
                if (it.pembicara == oldName) it.copy(pembicara = clean) else it
            }
            val fullText = updatedSegs.joinToString("\n") { "[${it.pembicara}] ${it.teks}" }
            personalRepo.updateMeetingFields(
                meeting.id,
                mapOf(
                    "segmen" to updatedSegs.map { it.toMap() },
                    "isi" to fullText
                )
            ).onSuccess {
                applyMeetingUpdate(it)
                _uiMessage.value = "Nama pembicara diubah ke $clean."
            }.onFailure { _uiMessage.value = toFriendlyIndonesianError(it) }
        }
    }

    fun editSegmentText(meeting: Meeting, index: Int, newText: String) {
        viewModelScope.launch {
            val updatedSegs = meeting.segments.toMutableList()
            if (index in updatedSegs.indices) {
                updatedSegs[index] = updatedSegs[index].copy(teks = newText.trim())
                val fullText = updatedSegs.joinToString("\n") { "[${it.pembicara}] ${it.teks}" }
                personalRepo.updateMeetingFields(
                    meeting.id,
                    mapOf(
                        "segmen" to updatedSegs.map { it.toMap() },
                        "isi" to fullText
                    )
                ).onSuccess { applyMeetingUpdate(it) }
                    .onFailure { _uiMessage.value = toFriendlyIndonesianError(it) }
            }
        }
    }

    fun renameMeeting(meetingId: String, newTitle: String) {
        val clean = newTitle.trim()
        if (clean.isBlank()) {
            _uiMessage.value = "Judul rapat tidak boleh kosong."
            return
        }
        viewModelScope.launch {
            personalRepo.updateMeetingFields(meetingId, mapOf("judul" to clean))
                .onSuccess {
                    applyMeetingUpdate(it)
                    _uiMessage.value = "Judul rapat diperbarui."
                }
                .onFailure { _uiMessage.value = toFriendlyIndonesianError(it) }
        }
    }

    fun toggleFavorite(meeting: Meeting) {
        viewModelScope.launch {
            val next = !meeting.isFavorite
            personalRepo.updateMeetingFields(meeting.id, mapOf("isFavorite" to next))
                .onSuccess {
                    applyMeetingUpdate(it)
                    _uiMessage.value = if (next) "Ditambahkan ke Favorit." else "Dihapus dari Favorit."
                }
                .onFailure { _uiMessage.value = toFriendlyIndonesianError(it) }
        }
    }

    fun updateTags(meeting: Meeting, tags: List<String>) {
        val clean = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(20)
        viewModelScope.launch {
            personalRepo.updateMeetingFields(meeting.id, mapOf("tags" to clean))
                .onSuccess {
                    applyMeetingUpdate(it)
                    _uiMessage.value = "Tag rapat diperbarui."
                }
                .onFailure { _uiMessage.value = toFriendlyIndonesianError(it) }
        }
    }

    fun bulkToggleFavorite(selected: List<Meeting>, favorite: Boolean) {
        viewModelScope.launch {
            selected.forEach { m ->
                personalRepo.updateMeetingFields(m.id, mapOf("isFavorite" to favorite))
                    .onSuccess { applyMeetingUpdate(it) }
            }
            _uiMessage.value = if (favorite) "${selected.size} rapat ditandai Favorit." else "${selected.size} rapat dihapus dari Favorit."
        }
    }

    fun bulkAddTag(selected: List<Meeting>, tag: String) {
        val clean = tag.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            selected.forEach { m ->
                val merged = (m.tags + clean).distinct().take(20)
                personalRepo.updateMeetingFields(m.id, mapOf("tags" to merged))
                    .onSuccess { applyMeetingUpdate(it) }
            }
            _uiMessage.value = "Tag \"$clean\" ditambahkan ke ${selected.size} rapat."
        }
    }

    fun bulkDeleteMeetings(tenant: Tenant?, selected: List<Meeting>) {
        viewModelScope.launch {
            var deletedCount = 0
            selected.forEach { meeting ->
                deleteMeetingEverywhere(tenant, meeting).onSuccess {
                    deletedCount++
                }.onFailure {
                    _uiMessage.value = toFriendlyIndonesianError(it)
                }
            }
            if (tenant != null && deletedCount > 0) {
                tenantRepo.recordAuditLog(
                    tenant,
                    currentUserId,
                    "BULK_DELETE_MEETINGS",
                    "$deletedCount rapat",
                    "Penghapusan massal"
                )
            }
            if (deletedCount == selected.size) {
                _uiMessage.value = "$deletedCount rapat dihapus."
            }
        }
    }

    fun deleteMeeting(tenant: Tenant?, meeting: Meeting) {
        viewModelScope.launch {
            deleteMeetingEverywhere(tenant, meeting)
                .onSuccess {
                    if (tenant != null) {
                        tenantRepo.recordAuditLog(tenant, currentUserId, "DELETE_MEETING", meeting.judul, "Rapat dihapus")
                    }
                    _uiMessage.value = "Rapat dihapus."
                }
                .onFailure { _uiMessage.value = toFriendlyIndonesianError(it) }
        }
    }

    private suspend fun deleteMeetingEverywhere(tenant: Tenant?, meeting: Meeting): Result<Unit> {
        tenant?.let {
            meetingRepo.deleteMeeting(it.id, meeting).onFailure { error ->
                return Result.failure(error)
            }
        }

        personalRepo.deleteMeeting(meeting.id).onFailure { error ->
            return Result.failure(error)
        }

        removeMeetingFromState(meeting.id)
        checkpointStore?.removeJobAndCleanTempChunks(currentUserId, meeting.id)
        personalRepo.rememberLocalAudioPath(currentUserId, meeting.id, null)
        meeting.localAudioPath?.let { path -> runCatching { File(path).delete() } }
        return Result.success(Unit)
    }

    fun clearSessionState() {
        pendingSave = null
        activeJob = null
        activeDetailMeetingId = null
        _processingState.value = null
        _detailState.value = null
        _meetingsState.value = UiState.Loading
        _pendingLocalJobs.value = emptyList()
        _localQaMessages.value = emptyList()
        _periodicSummaryState.value = null
        _accountLinkRequired.value = false
        _correctionItems.value = emptyList()
    }

    fun dismissProcessingBanner() {
        val current = _processingState.value
        if (current?.saveStatus == SaveStatus.FAILED) {
            pendingSave = null
        }
        _processingState.value = null
    }

    fun clearUiMessage() {
        _uiMessage.value = null
    }

    private fun persistJob(job: LocalProcessingJob): Boolean {
        activeJob = job
        val store = checkpointStore
        // Jobs without a checkpoint store cannot be considered durable; expose that limitation.
        val saved = store?.saveJob(job) ?: false
        refreshPendingLocalJobs()
        if (!saved) {
            val message = "Checkpoint lokal gagal disimpan; kemajuan proses mungkin tidak tersimpan. Rekaman asli tetap dipertahankan."
            _uiMessage.value = message
            val current = _processingState.value
            if (current?.meetingId == job.meetingId) {
                _processingState.value = current.copy(error = message, stageDetail = "Checkpoint gagal disimpan", canRetryProcessing = true)
            }
        }
        return saved
    }

    private fun hydrateLocalAudioPath(meeting: Meeting): Meeting {
        val cachedPath = personalRepo.getLocalAudioPath(currentUserId, meeting.id)
        return if (!cachedPath.isNullOrBlank() && meeting.localAudioPath.isNullOrBlank()) {
            meeting.copy(localAudioPath = cachedPath)
        } else {
            meeting
        }
    }

    private fun applyMeetingUpdate(updated: Meeting) {
        val hydrated = hydrateLocalAudioPath(updated)
        upsertMeetingInState(hydrated)
        if (activeDetailMeetingId == hydrated.id) {
            _detailState.value = UiState.Success(hydrated)
        }
    }

    private fun upsertMeetingInState(meeting: Meeting) {
        val current = (_meetingsState.value as? UiState.Success)?.data.orEmpty()
        val merged = (listOf(meeting) + current.filterNot { it.id == meeting.id })
            .sortedByDescending { it.createdAt?.seconds ?: Long.MAX_VALUE }
        _meetingsState.value = UiState.Success(merged)
    }

    private fun removeMeetingFromState(meetingId: String) {
        personalRepo.rememberLocalAudioPath(currentUserId, meetingId, null)
        val current = (_meetingsState.value as? UiState.Success)?.data
        if (current != null) {
            _meetingsState.value = UiState.Success(current.filterNot { it.id == meetingId })
        }
        if (activeDetailMeetingId == meetingId) {
            _detailState.value = UiState.Error("Rapat tidak ditemukan atau telah dihapus.")
        }
    }

    companion object {
        fun buildDefaultMeetingTitle(accountName: String, now: Date = Date()): String {
            val cleanName = accountName.trim().ifBlank { "Pengguna" }.take(60)
            val dateTime = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id", "ID")).format(now)
            return "$cleanName - $dateTime"
        }

        fun filterMeetingsForPeriod(
            meetings: List<Meeting>,
            periodType: String,
            now: Date = Date()
        ): Triple<String, String, List<Meeting>> {
            val daysBack = if (periodType == "monthly") 30 else 7
            val label = if (periodType == "monthly") "Ringkasan Bulanan" else "Ringkasan Mingguan"
            val calStart = Calendar.getInstance().apply {
                time = now
                add(Calendar.DAY_OF_YEAR, -(daysBack - 1))
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startMs = calStart.timeInMillis
            val endMs = now.time
            val fmt = SimpleDateFormat("dd MMM yyyy", Locale("id", "ID"))
            val rangeLabel = "${fmt.format(Date(startMs))} – ${fmt.format(Date(endMs))}"

            val matched = meetings.filter { m ->
                val d = m.createdAt?.toDate() ?: return@filter false
                d.time in startMs..endMs
            }.sortedByDescending { it.createdAt?.seconds ?: 0L }

            return Triple(label, rangeLabel, matched)
        }
    }
}
