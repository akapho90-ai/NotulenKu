package com.example

import com.notulenrapat.android.R

import android.content.Context
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioSegmenter
import com.example.data.ai.AiCallMeta
import com.example.data.ai.AiService
import com.example.data.ai.MeetingBackendAiClient
import com.example.data.ai.SummaryAndActionResult
import com.example.data.ai.TranscriptionResult
import com.example.data.auth.isValidGoogleWebClientId
import com.example.data.auth.mapGoogleSignInError
import com.example.data.model.ActionItem
import com.example.data.model.Meeting
import com.example.data.model.ModelPrice
import com.example.data.model.TaskConfig
import com.example.data.model.TranscriptSegment
import com.example.data.repository.AudioChunkCheckpoint
import com.example.data.repository.ChunkStatus
import com.example.data.repository.LocalProcessingCheckpointStore
import com.example.data.repository.LocalProcessingJob
import com.example.data.repository.MeetingRepository
import com.example.data.repository.PersonalBackendException
import com.example.data.repository.PersonalMeetingDataSource
import com.example.data.repository.PersonalMeetingRepository
import com.example.data.repository.PersonalMeetingSavePayload
import com.example.data.repository.TenantRepository
import com.example.ui.viewmodel.MeetingViewModel
import com.example.ui.viewmodel.SaveStatus
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.util.Date

private class RevisionFakePersonalDataSource(
    var activeUid: String = "uid_rev_1"
) : PersonalMeetingDataSource {
    val savedMeetings = linkedMapOf<String, Meeting>()
    val localCache = mutableMapOf<String, String>()
    var saveCalls = 0
    var failNextSave = false

    override suspend fun saveMeetingBundle(payload: PersonalMeetingSavePayload): Result<Meeting> {
        saveCalls++
        if (failNextSave) {
            failNextSave = false
            return Result.failure(PersonalBackendException("UNAVAILABLE", null, "Jaringan terputus."))
        }
        val m = Meeting(
            id = payload.meetingId,
            userId = activeUid,
            ownerName = "Budi Santoso",
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
            createdAt = Timestamp(Date()),
            updatedAt = Timestamp(Date())
        )
        savedMeetings[m.id] = m
        return Result.success(m)
    }

    override suspend fun listMeetings(limit: Int): Result<List<Meeting>> =
        Result.success(savedMeetings.values.filter { it.userId == activeUid }.take(limit))

    override suspend fun getMeetingDetail(meetingId: String): Result<Meeting> {
        val m = savedMeetings[meetingId]
            ?: return Result.failure(PersonalBackendException("NOT_FOUND", null, "Tidak ditemukan."))
        return Result.success(m)
    }

    override suspend fun updateMeetingFields(meetingId: String, patch: Map<String, Any?>): Result<Meeting> {
        val m = savedMeetings[meetingId]
            ?: return Result.failure(PersonalBackendException("NOT_FOUND", null, "Tidak ditemukan."))
        return Result.success(m)
    }

    override suspend fun deleteMeeting(meetingId: String): Result<Unit> {
        savedMeetings.remove(meetingId)
        return Result.success(Unit)
    }

    override suspend fun linkSupabaseAccount(supabaseEmail: String, supabasePassword: String): Result<String> =
        Result.success("00000000-0000-4000-8000-000000000002")

    override fun rememberLocalAudioPath(userId: String, meetingId: String, localPath: String?) {
        val key = "${userId}_$meetingId"
        if (localPath.isNullOrBlank()) localCache.remove(key) else localCache[key] = localPath
    }

    override fun getLocalAudioPath(userId: String, meetingId: String): String? = localCache["${userId}_$meetingId"]

    override fun clearLocalAudioCache(userId: String) {
        localCache.keys.filter { it.startsWith("${userId}_") }.forEach { localCache.remove(it) }
    }
}

private class ScriptedAiService : AiService() {
    var sttCalls = 0
    var summaryCalls = 0
    var failSttOnCall: Int? = null
    var failSummaryOnce = false

    override suspend fun transcribeAudio(
        audioBytes: ByteArray,
        mimeType: String,
        config: TaskConfig?,
        prices: List<ModelPrice>,
        preferredLanguage: String
    ): Result<TranscriptionResult> {
        sttCalls++
        if (failSttOnCall == sttCalls) {
            return Result.failure(IllegalStateException("Simulasi timeout chunk $sttCalls"))
        }
        val tag = String(audioBytes)
        return Result.success(
            TranscriptionResult(
                bahasa = "id",
                segments = listOf(TranscriptSegment("Pembicara 1", 0, 12, "Ucapan dari $tag")),
                meta = AiCallMeta("gemini/gemini-3.5-flash-lite", 10, 20, 80, 0.0, providerLabel = "SumoPod AI")
            )
        )
    }

    override suspend fun generateSummaryAndActions(
        transcriptText: String,
        config: TaskConfig?,
        prices: List<ModelPrice>
    ): Result<SummaryAndActionResult> {
        summaryCalls++
        if (failSummaryOnce) {
            failSummaryOnce = false
            return Result.failure(IllegalStateException("Simulasi gagal ringkasan"))
        }
        return Result.success(
            SummaryAndActionResult(
                ringkasan = "Ringkasan berhasil dibuat dari transkrip lengkap.",
                poinUtama = listOf("Poin utama 1"),
                keputusan = listOf("Keputusan 1"),
                risiko = emptyList(),
                angkaKunci = emptyList(),
                actionItems = listOf(ActionItem("1", "Budi", "Tindak lanjut", "Besok", "tinggi", false)),
                meta = AiCallMeta("glm-5.3-flash", 15, 25, 90, 0.0, providerLabel = "SumoPod AI")
            )
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotulenKuRevisionTest {

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

    @Test
    fun audioSegmenter_plansOverlappingWindowsForLongRecordingOverTwoHours() {
        val totalDurationSec = 9000L // 2.5 hours
        val windows = AudioSegmenter.planChunkWindows(
            totalDurationSec = totalDurationSec,
            chunkDurationSec = 600L,
            overlapSec = 10L
        )
        assertTrue("Expected > 12 chunks for 2.5-hour recording", windows.size >= 15)
        assertEquals(0L, windows.first().startSec)
        assertEquals(totalDurationSec, windows.last().endSec)
        for (i in 1 until windows.size) {
            val prev = windows[i - 1]
            val curr = windows[i]
            assertTrue("Chunks must advance monotonically", curr.startSec > prev.startSec)
            assertTrue("Adjacent chunks should overlap by ~10s", curr.startSec <= prev.endSec)
        }
    }

    @Test
    fun audioSegmenter_mergesChunkTranscriptsWithOffsetsAndOverlapDedup() {
        val chunk1 = listOf(
            TranscriptSegment("Pembicara 1", 0, 5, "Selamat pagi semuanya."),
            TranscriptSegment("Pembicara 2", 592, 598, "Kita lanjutkan evaluasi anggaran kuartal tiga.")
        )
        val chunk2 = listOf(
            // Overlap starts at 590s -> relative 2..8s maps to 592..598s (same text, must deduplicate!)
            TranscriptSegment("Pembicara 2", 2, 8, "Kita lanjutkan evaluasi anggaran kuartal tiga."),
            TranscriptSegment("Pembicara 1", 12, 20, "Target pengiriman kita tetapkan hari Jumat.")
        )

        val merged = AudioSegmenter.mergeChunkTranscripts(
            listOf(0L to chunk1, 590L to chunk2)
        )

        assertEquals(3, merged.size)
        assertEquals(0L, merged[0].mulaiDetik)
        assertEquals(592L, merged[1].mulaiDetik)
        assertEquals(602L, merged[2].mulaiDetik)
        assertEquals(610L, merged[2].selesaiDetik)
        assertTrue(merged[2].teks.contains("hari Jumat"))
    }

    @Test
    fun resumableCheckpoint_retriesOnlyFailedChunk_reusesTranscriptOnSummaryFailure_andPreservesOriginalAudio() = runTest {
        val ds = RevisionFakePersonalDataSource("uid_chunk_user")
        val ai = ScriptedAiService().apply {
            failSttOnCall = 2 // Chunk 1 succeeds, chunk 2 fails on first attempt
            failSummaryOnce = true // Summary fails on first attempt after both chunks succeed
        }
        val store = LocalProcessingCheckpointStore(context)
        val db = FirebaseFirestore.getInstance(context.getString(R.string.firestore_database_id))
        val auth = FirebaseAuth.getInstance()
        val vm = MeetingViewModel(
            personalRepo = PersonalMeetingRepository(ds),
            meetingRepo = MeetingRepository(db, auth),
            tenantRepo = TenantRepository(db, auth),
            aiService = ai,
            currentUserId = "uid_chunk_user",
            autoLoadOnInit = false,
            ownerDisplayNameProvider = { "Budi Santoso" },
            appContext = context,
            checkpointStore = store,
            processingIoDispatcher = testDispatcher
        )

        val originalAudio = File.createTempFile("orig_rec_", ".m4a", context.filesDir).apply {
            writeBytes("ORIGINAL_AUDIO".toByteArray())
        }
        val meetingId = "44444444-4444-4444-8444-444444444444"
        val chunk1File = AudioSegmenter.processingChunkFile(context, meetingId, 0).apply {
            parentFile!!.mkdirs()
            writeBytes("CHUNK_1".toByteArray())
        }
        val chunk2File = AudioSegmenter.processingChunkFile(context, meetingId, 1).apply {
            writeBytes("CHUNK_2".toByteArray())
        }

        val preChunkedJob = LocalProcessingJob(
            meetingId = meetingId,
            ownerUserId = "uid_chunk_user",
            judul = "Rapat Panjang 2 Jam",
            originalAudioPath = originalAudio.absolutePath,
            mimeType = "audio/mp4",
            durasiDetik = 1200L,
            ukuranBytes = originalAudio.length(),
            chunks = listOf(
                AudioChunkCheckpoint(0, 0L, 600L, chunk1File.absolutePath, "audio/mp4", isTempChunk = true, status = ChunkStatus.PENDING),
                AudioChunkCheckpoint(1, 590L, 1200L, chunk2File.absolutePath, "audio/mp4", isTempChunk = true, status = ChunkStatus.PENDING)
            )
        )
        store.saveJob(preChunkedJob)

        fun waitUntil(condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 3000L
            while (!condition() && System.currentTimeMillis() < deadline) {
                advanceUntilIdle()
                Thread.sleep(15)
            }
            advanceUntilIdle()
        }

        // Attempt 1: chunk 1 succeeds, chunk 2 fails
        vm.resumeLocalJob(preChunkedJob, null, emptyList(), emptyList())
        waitUntil { vm.processingState.value?.error != null }
        assertEquals(2, ai.sttCalls)
        assertEquals(0, ai.summaryCalls)
        assertEquals(1, vm.processingState.value?.completedChunks)
        assertEquals(2, vm.processingState.value?.totalChunks)
        assertTrue(vm.processingState.value?.canRetryProcessing == true)
        assertTrue("Original audio must NEVER be deleted on failure", originalAudio.exists())

        // Attempt 2: retries ONLY chunk 2 (sttCalls becomes 3, NOT 4!), then fails at summary stage
        vm.retryActiveProcessing(null, emptyList(), emptyList())
        waitUntil { vm.processingState.value?.stepIndex == 3 && vm.processingState.value?.error != null }
        assertEquals(3, ai.sttCalls) // Chunk 1 was NOT re-transcribed!
        assertEquals(1, ai.summaryCalls)
        assertEquals(3, vm.processingState.value?.stepIndex)
        assertTrue(vm.processingState.value?.canRetryProcessing == true)

        // Attempt 3: retries ONLY summary (sttCalls stays 3!), then saves to Supabase
        vm.retryActiveProcessing(null, emptyList(), emptyList())
        waitUntil { vm.processingState.value?.saveStatus == SaveStatus.SAVED }
        assertEquals(3, ai.sttCalls) // Still 3 — transcript reused!
        assertEquals(2, ai.summaryCalls)
        assertEquals(SaveStatus.SAVED, vm.processingState.value?.saveStatus)
        assertEquals(1, ds.saveCalls)
        assertTrue("Original audio must remain on device after save", originalAudio.exists())
        assertFalse("Temp chunk 1 should be cleaned after save", chunk1File.exists())
        assertFalse("Temp chunk 2 should be cleaned after save", chunk2File.exists())

        originalAudio.delete()
    }

    @Test
    fun defaultMeetingTitle_andPeriodicFilter_followAccountAndDateRangeRules() = runTest {
        val defaultTitle = MeetingViewModel.buildDefaultMeetingTitle("Rina Wijaya", Date(1760000000000L))
        assertTrue(defaultTitle.startsWith("Rina Wijaya - "))

        val now = Date(1_760_000_000_000L)
        val threeDaysAgo = Timestamp(Date(now.time - 3L * 24L * 3600L * 1000L))
        val twentyDaysAgo = Timestamp(Date(now.time - 20L * 24L * 3600L * 1000L))
        val fiftyDaysAgo = Timestamp(Date(now.time - 50L * 24L * 3600L * 1000L))

        val meetings = listOf(
            Meeting(id = "m1", judul = "Rapat Minggu Ini", createdAt = threeDaysAgo),
            Meeting(id = "m2", judul = "Rapat Bulan Ini", createdAt = twentyDaysAgo),
            Meeting(id = "m3", judul = "Rapat Lama", createdAt = fiftyDaysAgo)
        )

        val (_, _, weekly) = MeetingViewModel.filterMeetingsForPeriod(meetings, "weekly", now)
        val (_, _, monthly) = MeetingViewModel.filterMeetingsForPeriod(meetings, "monthly", now)

        assertEquals(listOf("m1"), weekly.map { it.id })
        assertEquals(listOf("m1", "m2"), monthly.map { it.id })
    }

    @Test
    fun authManager_validatesGoogleWebClientId_andMapsErrorsClearly() {
        assertTrue(isValidGoogleWebClientId("699450924977-abc123def456.apps.googleusercontent.com"))
        assertFalse(isValidGoogleWebClientId(""))
        assertFalse(isValidGoogleWebClientId("YOUR_WEB_CLIENT_ID"))
        assertFalse(isValidGoogleWebClientId("placeholder.apps.googleusercontent.com"))

        val cancelMsg = mapGoogleSignInError(GetCredentialCancellationException("cancelled"))
        assertTrue(cancelMsg.contains("dibatalkan", ignoreCase = true))

        val noCredMsg = mapGoogleSignInError(NoCredentialException("no account"))
        assertTrue(noCredMsg.contains("akun Google", ignoreCase = true))

        val shaMsg = mapGoogleSignInError(IllegalStateException("ApiException: 10 DEVELOPER_ERROR"))
        assertTrue(shaMsg.contains("SHA-1", ignoreCase = true))
    }

    @Test
    fun transcriptionRejectsPartialOrMalformedSegments() {
        val ai = AiService()
        val valid = """{"pembicara":"A","mulaiDetik":0,"selesaiDetik":2,"teks":"Halo"}"""
        val invalidRows = listOf(
            "null",
            """{"mulaiDetik":-1,"selesaiDetik":3,"teks":"invalid"}""",
            """{"mulaiDetik":3,"selesaiDetik":2,"teks":"invalid"}""",
            """{"mulaiDetik":2,"selesaiDetik":3,"teks":"  "}""",
            """{"mulaiDetik":2,"teks":"waktu akhir hilang"}"""
        )
        invalidRows.forEach { row ->
            assertTrue(runCatching { ai.parseTranscriptionJson("""{"segmen":[$valid,$row]}""", "id") }.isFailure)
        }
        assertTrue(runCatching { ai.parseTranscriptionJson("""{"segmen":[]}""", "id") }.isFailure)
    }

    @Test
    fun backendAiClient_andAiService_routeViaMeetingApi_andGroundQaSources() = runBlocking {
        var lastAuthHeader: String? = null
        val observedTasks = mutableListOf<String>()

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/processAi") { exchange ->
            lastAuthHeader = exchange.requestHeaders.getFirst("Authorization")
            val reqBody = exchange.requestBody.bufferedReader().use { it.readText() }
            val data = JSONObject(reqBody).getJSONObject("data")
            val task = data.getString("task")
            observedTasks.add(task)

            val responseText = when (task) {
                "audio_transcription" -> {
                    assertTrue(data.getString("audioBase64").isNotBlank())
                    assertEquals("audio/mp4", data.getString("mimeType"))
                    """{"bahasa":"id","segmen":[{"pembicara":"Pembicara 1","mulai_detik":0,"selesai_detik":5,"teks":"Halo rapat dimulai."}]}"""
                }
                "meeting_qa" -> {
                    // Includes 1 real meetingId and 1 hallucinated/foreign meetingId that MUST be filtered out
                    """{"jawaban":"Target rilis disepakati hari Jumat.","sumber":[{"meeting_id":"real-1","judul_rapat":"Judul Salah","waktu":"00:05"},{"meeting_id":"foreign-999","judul_rapat":"Rapat Orang Lain","waktu":"01:00"}]}"""
                }
                "periodic_summary" -> {
                    """{"ringkasan_eksekutif":"Ringkasan mingguan selesai.","tema_utama":["Rilis Q2"],"keputusan_utama":["Migrasi SumoPod"],"tindak_lanjut_prioritas":["Uji audio"],"risiko_terbuka":["SHA-1 rilis"],"sorotan_rapat":["Rapat 1"]}"""
                }
                else -> "{}"
            }

            val payload = JSONObject().apply {
                put("result", JSONObject().apply {
                    put("text", responseText)
                    put("modelId", if (task == "audio_transcription") "gemini/gemini-3.5-flash-lite" else "gpt-6-luna")
                    put("providerLabel", "SumoPod AI")
                    put("inputTokens", 42)
                    put("outputTokens", 18)
                    put("usedFallback", false)
                })
            }.toString().toByteArray()

            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.createContext("/checkAiHealth") { exchange ->
            val payload = JSONObject().apply {
                put("result", JSONObject().apply {
                    put("ok", true)
                    put("provider", "SumoPod AI")
                    put("model", "glm-5.3-flash")
                    put("latencyMs", 75)
                })
            }.toString().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val backendClient = MeetingBackendAiClient(
                baseUrl = baseUrl,
                tokenProvider = { "fake-firebase-id-token" }
            )
            val aiService = AiService(backendAiClient = backendClient)

            // 1. Transcription via SumoPod backend
            val stt = aiService.transcribeAudio(
                audioBytes = byteArrayOf(1, 2, 3, 4),
                mimeType = "audio/mp4",
                config = null,
                prices = emptyList()
            ).getOrThrow()
            assertEquals("Bearer fake-firebase-id-token", lastAuthHeader)
            assertEquals("id", stt.bahasa)
            assertEquals(1, stt.segments.size)
            assertEquals("gemini/gemini-3.5-flash-lite", stt.meta.modelId)

            // 2. Grounded Q&A filters out hallucinated/foreign meeting IDs & restores canonical title
            val userMeetings = listOf(
                Meeting(
                    id = "real-1",
                    userId = "uid_1",
                    judul = "Rapat Rilis Resmi",
                    ringkasan = "Target rilis Jumat."
                )
            )
            val qa = aiService.answerQuestion(
                question = "Kapan rilis?",
                meetings = userMeetings,
                history = emptyList(),
                config = null,
                prices = emptyList()
            ).getOrThrow()
            assertEquals(1, qa.sumber.size)
            assertEquals("real-1", qa.sumber[0].meetingId)
            assertEquals("Rapat Rilis Resmi", qa.sumber[0].judulRapat)

            // 3. Periodic summary via backend
            val periodic = aiService.generatePeriodicSummary(
                periodLabel = "Mingguan",
                dateRangeLabel = "10–17 Okt",
                meetings = userMeetings,
                config = null,
                prices = emptyList()
            ).getOrThrow()
            assertNotNull(periodic)
            assertEquals("Ringkasan mingguan selesai.", periodic.ringkasanEksekutif)

            val routingTest = aiService.runSinglePlayground(
                modelId = "gpt-6-luna",
                systemPrompt = "Ringkas contoh berikut.",
                inputText = "Keputusan rapat",
                temperature = 0.2f,
                prices = emptyList(),
                taskName = "periodic_summary"
            ).getOrThrow()
            assertTrue(routingTest.outputText.contains("Ringkasan mingguan selesai."))

            // 4. Health check via backend
            val latency = aiService.testConnectionPing().getOrThrow()
            assertTrue(latency >= 0L)

            assertEquals(
                listOf("audio_transcription", "meeting_qa", "periodic_summary", "periodic_summary"),
                observedTasks
            )
        } finally {
            server.stop(0)
        }
    }
}
