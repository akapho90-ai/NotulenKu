package com.example

import com.example.data.model.ActionItem
import com.example.data.model.ModelInfo
import com.example.data.model.TranscriptSegment
import com.example.data.repository.FirebaseCallableMeetingDataSource
import com.example.data.repository.PersonalBackendException
import com.example.data.repository.PersonalMeetingJsonMapper
import com.example.data.repository.PersonalMeetingSavePayload
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PersonalMeetingJsonMapperTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    // Simulated backend store per verified Firebase token
    private val store = ConcurrentHashMap<String, MutableMap<String, JSONObject>>()
    private val authHeadersSeen = mutableListOf<String>()
    private val rawPayloadsSeen = mutableListOf<JSONObject>()

    @Before
    fun startServer() {
        store.clear()
        authHeadersSeen.clear()
        rawPayloadsSeen.clear()

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val fnName = exchange.requestURI.path.trimStart('/')
            val authHeader = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            authHeadersSeen.add(authHeader)
            val uid = authHeader.removePrefix("Bearer ").removePrefix("tok_").trim()

            val bodyText = exchange.requestBody.bufferedReader().readText()
            val reqData = JSONObject(bodyText).optJSONObject("data") ?: JSONObject()
            rawPayloadsSeen.add(reqData)

            if (uid.isBlank()) {
                val err = """{"error":{"status":"UNAUTHENTICATED","message":"Sesi login tidak valid"}}"""
                exchange.sendResponseHeaders(401, err.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(err.toByteArray()) }
                return@createContext
            }

            if (uid == "legacy_collision") {
                val err = """{"error":{"status":"FAILED_PRECONDITION","message":"Akun Supabase sudah ada","details":{"code":"ACCOUNT_LINK_REQUIRED"}}}"""
                exchange.sendResponseHeaders(400, err.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(err.toByteArray()) }
                return@createContext
            }

            val respJson: String
            var statusCode = 200
            when (fnName) {
                "savePersonalMeeting" -> {
                    val meetingId = reqData.getString("meetingId")
                    // Ownership check across all users
                    val ownedByOther = store.entries.any { (otherUid, map) ->
                        otherUid != uid && map.containsKey(meetingId)
                    }
                    if (ownedByOther) {
                        statusCode = 403
                        respJson = """{"error":{"status":"PERMISSION_DENIED","message":"Anda tidak memiliki izin"}}"""
                    } else {
                        val userMeetings = store.getOrPut(uid) { mutableMapOf() }
                        val record = JSONObject().apply {
                            put("id", meetingId)
                            put("user_id", "supa-$uid")
                            put("judul", reqData.optString("judul", "Rapat"))
                            put("durasi_detik", reqData.optLong("durasiDetik", 0L))
                            put("status", reqData.optString("status", "done"))
                            put("audio_path", JSONObject.NULL)
                            put("is_favorite", reqData.optBoolean("isFavorite", false))
                            put("tags", reqData.optJSONArray("tags") ?: org.json.JSONArray())
                            put("created_at", "2026-11-21T10:15:30.123456+00:00")
                            put("updated_at", "2026-11-21T10:15:30Z")
                            if (reqData.has("transcript")) put("transcript", reqData.getJSONObject("transcript"))
                            if (reqData.has("summary")) put("summary", reqData.getJSONObject("summary"))
                        }
                        userMeetings[meetingId] = record
                        respJson = JSONObject().put("result", JSONObject().put("meeting", record)).toString()
                    }
                }
                "listPersonalMeetings" -> {
                    val list = store[uid]?.values?.toList().orEmpty()
                    val arr = org.json.JSONArray(list)
                    respJson = JSONObject().put("result", JSONObject().put("meetings", arr)).toString()
                }
                "getPersonalMeetingDetail" -> {
                    val meetingId = reqData.getString("meetingId")
                    val mine = store[uid]?.get(meetingId)
                    val other = store.entries.any { (otherUid, map) -> otherUid != uid && map.containsKey(meetingId) }
                    if (mine != null) {
                        respJson = JSONObject().put("result", JSONObject().put("meeting", mine)).toString()
                    } else if (other) {
                        statusCode = 403
                        respJson = """{"error":{"status":"PERMISSION_DENIED","message":"Anda tidak memiliki izin untuk membuka rapat ini."}}"""
                    } else {
                        statusCode = 404
                        respJson = """{"error":{"status":"NOT_FOUND","message":"Rapat tidak ditemukan."}}"""
                    }
                }
                else -> {
                    statusCode = 404
                    respJson = """{"error":{"status":"NOT_FOUND","message":"Unknown"}}"""
                }
            }

            val bytes = respJson.toByteArray()
            exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun parseMeeting_handlesLegacyMissingTranscriptSummaryAndAudioGracefully() {
        val legacyJson = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("user_id", "11111111-1111-4111-8111-111111111111")
            put("judul", "Rapat Lama Tanpa Ringkasan")
            put("durasi_detik", 90)
            put("status", "done")
            put("audio_path", JSONObject.NULL)
            put("created_at", "2026-11-21T08:00:00.654321+00:00")
            put("transcript", JSONObject.NULL)
            put("summary", JSONObject.NULL)
        }

        val parsed = PersonalMeetingJsonMapper.parseMeeting(legacyJson, "Budi")
        assertEquals("Rapat Lama Tanpa Ringkasan", parsed.judul)
        assertEquals(90L, parsed.durasiDetik)
        assertNull(parsed.localAudioPath)
        assertTrue(parsed.segments.isEmpty())
        assertEquals("", parsed.transcriptText)
        assertEquals("", parsed.ringkasan)
        assertTrue(parsed.actionItems.isEmpty())
        assertNotNull(parsed.createdAt)
    }

    @Test
    fun callableDataSource_savesIdempotently_sendsNullAudioPath_andEnforcesOwnership() = runTest {
        var activeToken = "tok_userA"
        val ds = FirebaseCallableMeetingDataSource(
            context = null,
            baseUrl = baseUrl,
            tokenProvider = { activeToken }
        )

        val meetingId = UUID.randomUUID().toString()
        val payload = PersonalMeetingSavePayload(
            meetingId = meetingId,
            judul = "Sinkronisasi Q4",
            durasiDetik = 240L,
            ukuranBytes = 4096L,
            modelInfo = ModelInfo("SumoPod AI", "glm-5.3-flash", "21 Nov 2026"),
            segments = listOf(TranscriptSegment("Budi", 0, 12, "Kita rilis minggu ini.")),
            transcriptText = "[Budi] Kita rilis minggu ini.",
            ringkasan = "Sepakat rilis minggu ini.",
            keputusan = listOf("Rilis Jumat"),
            actionItems = listOf(ActionItem("a1", "Budi", "Siapkan build", "Jumat", "tinggi", false))
        )

        // First save + idempotent retry with same meetingId
        val save1 = ds.saveMeetingBundle(payload)
        val save2 = ds.saveMeetingBundle(payload)
        assertTrue(save1.isSuccess)
        assertTrue(save2.isSuccess)
        assertEquals("Bearer tok_userA", authHeadersSeen.first())
        assertTrue(rawPayloadsSeen.first().isNull("audioPath"))

        val listA = ds.listMeetings().getOrThrow()
        assertEquals(1, listA.size)
        assertEquals(meetingId, listA[0].id)
        assertEquals("Sepakat rilis minggu ini.", listA[0].ringkasan)

        // Second user cannot list, read detail, or overwrite userA's meetingId
        activeToken = "tok_userB"
        assertTrue(ds.listMeetings().getOrThrow().isEmpty())

        val detailB = ds.getMeetingDetail(meetingId)
        assertTrue(detailB.isFailure)
        assertEquals("PERMISSION_DENIED", (detailB.exceptionOrNull() as PersonalBackendException).statusCode)

        val overwriteB = ds.saveMeetingBundle(payload.copy(judul = "Bajak"))
        assertTrue(overwriteB.isFailure)
        assertEquals("PERMISSION_DENIED", (overwriteB.exceptionOrNull() as PersonalBackendException).statusCode)
    }

    @Test
    fun callableDataSource_surfacesAccountLinkRequiredError() = runTest {
        val ds = FirebaseCallableMeetingDataSource(
            context = null,
            baseUrl = baseUrl,
            tokenProvider = { "tok_legacy_collision" }
        )
        val res = ds.listMeetings()
        assertTrue(res.isFailure)
        val ex = res.exceptionOrNull() as PersonalBackendException
        assertTrue(ex.isAccountLinkRequired)
        assertFalse(ex.message.isBlank())
    }
}
