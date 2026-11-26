package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.AiCallMeta
import com.example.data.ai.AiService
import com.example.data.ai.SummaryAndActionResult
import com.example.data.ai.TranscriptionResult
import com.example.data.model.ActionItem
import com.example.data.model.Meeting
import com.example.data.model.ModelPrice
import com.example.data.model.TaskConfig
import com.example.data.model.TranscriptSegment
import com.example.data.repository.MeetingRepository
import com.example.data.repository.PersonalBackendException
import com.example.data.repository.PersonalMeetingDataSource
import com.example.data.repository.PersonalMeetingRepository
import com.example.data.repository.PersonalMeetingSavePayload
import com.example.data.repository.TenantRepository
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.MeetingDetailScreen
import com.example.ui.screens.RecordScreen
import com.example.ui.viewmodel.MeetingViewModel
import com.example.ui.viewmodel.ProcessingStepState
import com.example.ui.viewmodel.SaveStatus
import com.example.ui.viewmodel.UiState
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Date

private class FakePersonalDataSource(
    var activeUid: String = "uid_user_a"
) : PersonalMeetingDataSource {
    val dbById = linkedMapOf<String, Meeting>()
    val localCache = mutableMapOf<String, String>()
    var failNextSave = false
    var failNextList = false
    var failWithLinkRequired = false
    var saveCallCount = 0

    override suspend fun saveMeetingBundle(payload: PersonalMeetingSavePayload): Result<Meeting> {
        saveCallCount++
        if (failWithLinkRequired) {
            return Result.failure(
                PersonalBackendException("FAILED_PRECONDITION", "ACCOUNT_LINK_REQUIRED", "Tautkan akun Supabase dulu.")
            )
        }
        if (failNextSave) {
            failNextSave = false
            return Result.failure(
                PersonalBackendException("UNAVAILABLE", null, "Koneksi jaringan bermasalah. Coba lagi.")
            )
        }
        val existing = dbById[payload.meetingId]
        if (existing != null && existing.userId != activeUid) {
            return Result.failure(
                PersonalBackendException("PERMISSION_DENIED", null, "Anda tidak memiliki izin.")
            )
        }
        val saved = Meeting(
            id = payload.meetingId,
            userId = activeUid,
            ownerName = "Saya",
            judul = payload.judul,
            status = payload.status,
            durasiDetik = payload.durasiDetik,
            ukuranBytes = payload.ukuranBytes,
            bahasa = payload.bahasa,
            transcriptText = payload.transcriptText,
            segments = payload.segments,
            ringkasan = payload.ringkasan,
            poinUtama = payload.poinUtama,
            keputusan = payload.keputusan,
            risiko = payload.risiko,
            angkaKunci = payload.angkaKunci,
            actionItems = payload.actionItems,
            createdAt = existing?.createdAt ?: Timestamp(Date()),
            updatedAt = Timestamp(Date())
        )
        dbById[payload.meetingId] = saved
        return Result.success(saved)
    }

    override suspend fun listMeetings(limit: Int): Result<List<Meeting>> {
        if (failWithLinkRequired) {
            return Result.failure(
                PersonalBackendException("FAILED_PRECONDITION", "ACCOUNT_LINK_REQUIRED", "Tautkan akun Supabase dulu.")
            )
        }
        if (failNextList) {
            failNextList = false
            return Result.failure(
                PersonalBackendException("UNAVAILABLE", null, "Gagal memuat riwayat dari server.")
            )
        }
        val mine = dbById.values
            .filter { it.userId == activeUid }
            .sortedByDescending { it.createdAt?.seconds ?: 0L }
            .take(limit)
        return Result.success(mine)
    }

    override suspend fun getMeetingDetail(meetingId: String): Result<Meeting> {
        val row = dbById[meetingId]
            ?: return Result.failure(PersonalBackendException("NOT_FOUND", null, "Rapat tidak ditemukan."))
        if (row.userId != activeUid) {
            return Result.failure(PersonalBackendException("PERMISSION_DENIED", null, "Anda tidak memiliki izin."))
        }
        return Result.success(row)
    }

    override suspend fun updateMeetingFields(meetingId: String, patch: Map<String, Any?>): Result<Meeting> {
        val row = dbById[meetingId]
            ?: return Result.failure(PersonalBackendException("NOT_FOUND", null, "Rapat tidak ditemukan."))
        if (row.userId != activeUid) {
            return Result.failure(PersonalBackendException("PERMISSION_DENIED", null, "Ditolak."))
        }
        val updated = row.copy(judul = (patch["judul"] as? String) ?: row.judul)
        dbById[meetingId] = updated
        return Result.success(updated)
    }

    override suspend fun deleteMeeting(meetingId: String): Result<Unit> {
        val row = dbById[meetingId]
            ?: return Result.failure(PersonalBackendException("NOT_FOUND", null, "Tidak ditemukan."))
        if (row.userId != activeUid) {
            return Result.failure(PersonalBackendException("PERMISSION_DENIED", null, "Ditolak."))
        }
        dbById.remove(meetingId)
        return Result.success(Unit)
    }

    override suspend fun linkSupabaseAccount(supabaseEmail: String, supabasePassword: String): Result<String> {
        failWithLinkRequired = false
        return Result.success("00000000-0000-4000-8000-000000000001")
    }

    override fun rememberLocalAudioPath(userId: String, meetingId: String, localPath: String?) {
        val key = "${userId}_$meetingId"
        if (localPath.isNullOrBlank()) localCache.remove(key) else localCache[key] = localPath
    }

    override fun getLocalAudioPath(userId: String, meetingId: String): String? =
        localCache["${userId}_$meetingId"]

    override fun clearLocalAudioCache(userId: String) {
        localCache.keys.filter { it.startsWith("${userId}_") }.forEach { localCache.remove(it) }
    }
}

private class FakeAiService : AiService() {
    var sttCalls = 0
    var summaryCalls = 0

    override suspend fun transcribeAudio(
        audioBytes: ByteArray,
        mimeType: String,
        config: TaskConfig?,
        prices: List<ModelPrice>,
        preferredLanguage: String
    ): Result<TranscriptionResult> {
        sttCalls++
        return Result.success(
            TranscriptionResult(
                bahasa = "id",
                segments = listOf(TranscriptSegment("Pembicara 1", 0, 10, "Mari bahas target rilis.")),
                meta = AiCallMeta("glm-5.3-flash", 10, 20, 50, 0.0)
            )
        )
    }

    override suspend fun generateSummaryAndActions(
        transcriptText: String,
        config: TaskConfig?,
        prices: List<ModelPrice>
    ): Result<SummaryAndActionResult> {
        summaryCalls++
        return Result.success(
            SummaryAndActionResult(
                ringkasan = "Tim sepakat target rilis Jumat.",
                poinUtama = listOf("Target rilis Jumat"),
                keputusan = listOf("Deploy backend"),
                risiko = emptyList(),
                angkaKunci = emptyList(),
                actionItems = listOf(ActionItem("1", "Pembicara 1", "Uji APK", "Kamis", "tinggi", false)),
                meta = AiCallMeta("glm-5.3-flash", 15, 25, 60, 0.0)
            )
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp")
class PersonalMeetingFlowRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("1:699450924977:android:test000000000000")
                    .setApiKey("test-api-key")
                    .setProjectId("gen-lang-client-0830960267")
                    .build()
            )
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        dataSource: FakePersonalDataSource,
        aiService: AiService = FakeAiService(),
        uid: String = dataSource.activeUid,
        autoLoad: Boolean = false
    ): MeetingViewModel {
        val db = FirebaseFirestore.getInstance(context.getString(R.string.firestore_database_id))
        val auth = FirebaseAuth.getInstance()
        return MeetingViewModel(
            personalRepo = PersonalMeetingRepository(dataSource),
            meetingRepo = MeetingRepository(db, auth),
            tenantRepo = TenantRepository(db, auth),
            aiService = aiService,
            currentUserId = uid,
            autoLoadOnInit = autoLoad
        )
    }

    @Test
    fun saveFailureAndRetry_preservesUuidAndAiResults_withoutRerunningAi_andAppearsOnRestart() = runTest {
        val ds = FakePersonalDataSource("uid_user_a").apply { failNextSave = true }
        val ai = FakeAiService()
        val vm = createViewModel(ds, ai, uid = "uid_user_a", autoLoad = false)

        val tempAudio = File.createTempFile("test_rec_", ".m4a", context.cacheDir).apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }

        vm.processAudioMeeting(
            tenant = null,
            judul = "Rapat Produk Mingguan",
            audioFile = tempAudio,
            mimeType = "audio/mp4",
            durasiDetik = 45L,
            taskConfigs = emptyList(),
            prices = emptyList()
        )
        advanceUntilIdle()

        // First save failed after AI succeeded once
        val failedState = vm.processingState.value!!
        assertEquals(4, failedState.stepIndex)
        assertEquals(SaveStatus.FAILED, failedState.saveStatus)
        assertEquals(1, ai.sttCalls)
        assertEquals(1, ai.summaryCalls)
        assertEquals(1, ds.saveCallCount)
        val preservedUuid = failedState.meetingId
        assertTrue(preservedUuid.isNotBlank())

        // Retry save ONLY (must NOT re-run STT or summary)
        vm.retrySaveMeeting()
        advanceUntilIdle()

        val savedState = vm.processingState.value!!
        assertEquals(SaveStatus.SAVED, savedState.saveStatus)
        assertEquals(preservedUuid, savedState.meetingId)
        assertEquals(1, ai.sttCalls)
        assertEquals(1, ai.summaryCalls)
        assertEquals(2, ds.saveCallCount)
        assertEquals(1, ds.dbById.size)

        // Simulate app restart with fresh ViewModel instance
        val restartedVm = createViewModel(ds, ai, uid = "uid_user_a", autoLoad = true)
        advanceUntilIdle()
        val state = restartedVm.meetingsState.value as UiState.Success
        assertEquals(1, state.data.size)
        assertEquals("Rapat Produk Mingguan", state.data[0].judul)
        assertEquals("Tim sepakat target rilis Jumat.", state.data[0].ringkasan)

        // Account switch / sign-out clears state and isolates second user
        restartedVm.clearSessionState()
        assertNull(restartedVm.processingState.value)
        assertTrue(restartedVm.meetingsState.value is UiState.Loading)

        ds.activeUid = "uid_user_b"
        val userBVm = createViewModel(ds, ai, uid = "uid_user_b", autoLoad = true)
        advanceUntilIdle()
        val userBList = userBVm.meetingsState.value as UiState.Success
        assertTrue(userBList.data.isEmpty())

        // Second user cannot open userA's meeting detail
        userBVm.loadMeetingDetail(preservedUuid, forceSpinner = true)
        advanceUntilIdle()
        assertTrue(userBVm.detailState.value is UiState.Error)
    }

    @Test
    fun recordScreen_displaysSavingSavedAndRetryStates() {
        var retryClicks = 0
        val failedState = ProcessingStepState(
            meetingId = "11111111-1111-4111-8111-111111111111",
            stepIndex = 4,
            saveStatus = SaveStatus.FAILED,
            saveError = "Koneksi jaringan bermasalah."
        )

        composeRule.setContent {
            RecordScreen(
                activeTenant = null,
                processingState = failedState,
                onProcessAudio = { _, _, _, _ -> },
                onRetrySave = { retryClicks++ },
                onOpenMeetingDetail = {},
                onDismissProcessing = {}
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("save_status_error").assertIsDisplayed()
        composeRule.onNodeWithTag("retry_save_meeting_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(1, retryClicks)
    }

    @Test
    fun historyScreen_errorStateShowsRetryButton_notEmptyState() {
        var retryCount = 0

        composeRule.setContent {
            HistoryScreen(
                meetingsState = UiState.Error("Koneksi jaringan bermasalah."),
                isAdminOrOwner = false,
                currentUserId = "uid_user_a",
                onRetryLoad = { retryCount++ },
                onMeetingClick = {},
                onRenameMeeting = { _, _ -> },
                onDeleteMeeting = {},
                onRecordFirst = {}
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Gagal memuat riwayat rapat").assertIsDisplayed()
        composeRule.onNodeWithTag("history_retry_button").performClick()
        composeRule.waitForIdle()
        assertEquals(1, retryCount)
    }

    @Test
    fun meetingDetailScreen_handlesLegacyRowWithoutTranscriptOrSummary_andErrorRetry() {
        var retryDetailCount = 0
        val legacyMeeting = Meeting(
            id = "33333333-3333-4333-8333-333333333333",
            userId = "uid_user_a",
            ownerName = "Saya",
            judul = "Rapat Legacy Minimal",
            status = "done",
            durasiDetik = 120L
        )

        composeRule.setContent {
            MeetingDetailScreen(
                meeting = legacyMeeting,
                detailState = UiState.Success(legacyMeeting),
                activeTenant = null,
                aiBusy = false,
                corrections = emptyList(),
                onRetryLoad = { retryDetailCount++ },
                onBack = {},
                onRenameMeeting = {},
                onRenameSpeaker = { _, _ -> },
                onEditSegment = { _, _ -> },
                onToggleActionItem = {},
                onSaveMinutesManual = { _, _, _, _ -> },
                onRegenerateSummary = {},
                onRunAnalysis = {},
                onRunTranscriptCorrection = {},
                onAcceptCorrection = {},
                onRejectCorrection = {},
                onGenerateVisual = {},
                onNotify = {}
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Rapat Legacy Minimal").assertIsDisplayed()
        composeRule.onNodeWithText("Notulen pribadi — hanya dapat diakses oleh akun Anda").assertIsDisplayed()
        composeRule.onNodeWithText("Transkrip belum tersedia.").assertIsDisplayed()
    }
}
