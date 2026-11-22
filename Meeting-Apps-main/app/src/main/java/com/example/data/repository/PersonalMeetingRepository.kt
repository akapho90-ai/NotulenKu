package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.BuildConfig
import com.example.core.AppConstants
import com.example.data.model.ActionItem
import com.example.data.model.KeyNumber
import com.example.data.model.Meeting
import com.example.data.model.MeetingAnalysis
import com.example.data.model.ModelInfo
import com.example.data.model.TranscriptSegment
import com.example.data.model.VisualData
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class PersonalMeetingSavePayload(
    val meetingId: String,
    val judul: String,
    val durasiDetik: Long,
    val ukuranBytes: Long,
    val status: String = "done",
    val errorMessage: String? = null,
    val isFavorite: Boolean = false,
    val tags: List<String> = emptyList(),
    val modelInfo: ModelInfo? = null,
    val bahasa: String = "id",
    val transcriptText: String = "",
    val segments: List<TranscriptSegment> = emptyList(),
    val hasTranscript: Boolean = true,
    val ringkasan: String = "",
    val poinUtama: List<String> = emptyList(),
    val keputusan: List<String> = emptyList(),
    val risiko: List<String> = emptyList(),
    val angkaKunci: List<KeyNumber> = emptyList(),
    val actionItems: List<ActionItem> = emptyList(),
    val analysis: MeetingAnalysis? = null,
    val visualData: VisualData? = null,
    val hasSummary: Boolean = true
)

class PersonalBackendException(
    val statusCode: String,
    val detailCode: String? = null,
    override val message: String
) : Exception(message) {
    val isAccountLinkRequired: Boolean
        get() = detailCode == "ACCOUNT_LINK_REQUIRED"
}

interface PersonalMeetingDataSource {
    suspend fun saveMeetingBundle(payload: PersonalMeetingSavePayload): Result<Meeting>
    suspend fun listMeetings(limit: Int = 200): Result<List<Meeting>>
    suspend fun getMeetingDetail(meetingId: String): Result<Meeting>
    suspend fun updateMeetingFields(meetingId: String, patch: Map<String, Any?>): Result<Meeting>
    suspend fun deleteMeeting(meetingId: String): Result<Unit>
    suspend fun linkSupabaseAccount(supabaseEmail: String, supabasePassword: String): Result<String>
    fun rememberLocalAudioPath(userId: String, meetingId: String, localPath: String?)
    fun getLocalAudioPath(userId: String, meetingId: String): String?
    fun clearLocalAudioCache(userId: String)
}

class PersonalMeetingRepository(
    private val dataSource: PersonalMeetingDataSource
) {
    constructor(
        context: Context,
        auth: FirebaseAuth = Firebase.auth,
        baseUrl: String = BuildConfig.MEETING_BACKEND_URL.ifBlank { AppConstants.DEFAULT_MEETING_BACKEND_URL }
    ) : this(
        FirebaseCallableMeetingDataSource(
            context = context.applicationContext,
            authProvider = { auth },
            baseUrl = baseUrl
        )
    )

    suspend fun saveMeetingBundle(payload: PersonalMeetingSavePayload): Result<Meeting> =
        dataSource.saveMeetingBundle(payload)

    suspend fun listMeetings(limit: Int = 200): Result<List<Meeting>> =
        dataSource.listMeetings(limit)

    suspend fun getMeetingDetail(meetingId: String): Result<Meeting> =
        dataSource.getMeetingDetail(meetingId)

    suspend fun updateMeetingFields(meetingId: String, patch: Map<String, Any?>): Result<Meeting> =
        dataSource.updateMeetingFields(meetingId, patch)

    suspend fun deleteMeeting(meetingId: String): Result<Unit> =
        dataSource.deleteMeeting(meetingId)

    suspend fun linkSupabaseAccount(supabaseEmail: String, supabasePassword: String): Result<String> =
        dataSource.linkSupabaseAccount(supabaseEmail, supabasePassword)

    fun rememberLocalAudioPath(userId: String, meetingId: String, localPath: String?) =
        dataSource.rememberLocalAudioPath(userId, meetingId, localPath)

    fun getLocalAudioPath(userId: String, meetingId: String): String? =
        dataSource.getLocalAudioPath(userId, meetingId)

    fun clearLocalAudioCache(userId: String) =
        dataSource.clearLocalAudioCache(userId)
}

class FirebaseCallableMeetingDataSource(
    context: Context? = null,
    private val authProvider: () -> FirebaseAuth? = { runCatching { Firebase.auth }.getOrNull() },
    private val baseUrl: String = AppConstants.DEFAULT_MEETING_BACKEND_URL,
    private val tokenProvider: suspend () -> String = {
        val user = authProvider()?.currentUser
            ?: throw PersonalBackendException("UNAUTHENTICATED", null, "Pengguna belum masuk.")
        user.getIdToken(false).await().token
            ?: throw PersonalBackendException("UNAUTHENTICATED", null, "Token autentikasi Firebase tidak tersedia.")
    },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) : PersonalMeetingDataSource {

    private val prefs: SharedPreferences? = context?.applicationContext?.getSharedPreferences(
        "personal_audio_cache_v1",
        Context.MODE_PRIVATE
    )
    private val memoryAudioPaths = ConcurrentHashMap<String, String>()

    private fun currentOwnerDisplayName(): String {
        val u = authProvider()?.currentUser
        return u?.displayName?.takeIf { it.isNotBlank() }
            ?: u?.email?.substringBefore("@")?.takeIf { it.isNotBlank() }
            ?: "Saya"
    }

    override suspend fun saveMeetingBundle(payload: PersonalMeetingSavePayload): Result<Meeting> {
        val body = JSONObject().apply {
            put("meetingId", payload.meetingId)
            put("judul", payload.judul)
            put("durasiDetik", payload.durasiDetik)
            put("ukuranBytes", payload.ukuranBytes)
            put("status", payload.status)
            // Keep audioPath strictly null unless uploaded to remote Storage; never send local paths.
            put("audioPath", JSONObject.NULL)
            put("errorMessage", payload.errorMessage ?: JSONObject.NULL)
            put("isFavorite", payload.isFavorite)
            put("tags", JSONArray(payload.tags))
            if (payload.modelInfo != null) {
                put("modelInfo", JSONObject(payload.modelInfo.toMap()))
            }
            if (payload.hasTranscript) {
                put(
                    "transcript",
                    JSONObject().apply {
                        put("bahasa", payload.bahasa)
                        put("isi", payload.transcriptText)
                        put("segmen", JSONArray(payload.segments.map { JSONObject(it.toMap()) }))
                    }
                )
            }
            if (payload.hasSummary) {
                put(
                    "summary",
                    JSONObject().apply {
                        put("ringkasan", payload.ringkasan)
                        put("poinUtama", JSONArray(payload.poinUtama))
                        put("keputusan", JSONArray(payload.keputusan))
                        put("risiko", JSONArray(payload.risiko))
                        put("angkaKunci", JSONArray(payload.angkaKunci.map { JSONObject(it.toMap()) }))
                        put("actionItems", JSONArray(payload.actionItems.map { JSONObject(it.toMap()) }))
                        if (payload.analysis != null) {
                            put("analysis", JSONObject(payload.analysis.toMap()))
                        }
                        if (payload.visualData != null) {
                            put("visualJson", JSONObject(payload.visualData.toMap()))
                        }
                    }
                )
            }
        }
        return callFunction("savePersonalMeeting", body).map { result ->
            PersonalMeetingJsonMapper.parseMeeting(
                obj = result.getJSONObject("meeting"),
                fallbackOwnerName = currentOwnerDisplayName()
            )
        }
    }

    override suspend fun listMeetings(limit: Int): Result<List<Meeting>> {
        val body = JSONObject().apply { put("limit", limit) }
        return callFunction("listPersonalMeetings", body).map { result ->
            val arr = result.optJSONArray("meetings") ?: JSONArray()
            val ownerName = currentOwnerDisplayName()
            val list = ArrayList<Meeting>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(PersonalMeetingJsonMapper.parseMeeting(obj, ownerName))
            }
            list.sortedByDescending { it.createdAt?.seconds ?: 0L }
        }
    }

    override suspend fun getMeetingDetail(meetingId: String): Result<Meeting> {
        val body = JSONObject().apply { put("meetingId", meetingId) }
        return callFunction("getPersonalMeetingDetail", body).map { result ->
            PersonalMeetingJsonMapper.parseMeeting(
                obj = result.getJSONObject("meeting"),
                fallbackOwnerName = currentOwnerDisplayName()
            )
        }
    }

    override suspend fun updateMeetingFields(meetingId: String, patch: Map<String, Any?>): Result<Meeting> {
        val patchJson = PersonalMeetingJsonMapper.mapToJsonObject(patch)
        val body = JSONObject().apply {
            put("meetingId", meetingId)
            put("patch", patchJson)
        }
        return callFunction("updatePersonalMeeting", body).map { result ->
            PersonalMeetingJsonMapper.parseMeeting(
                obj = result.getJSONObject("meeting"),
                fallbackOwnerName = currentOwnerDisplayName()
            )
        }
    }

    override suspend fun deleteMeeting(meetingId: String): Result<Unit> {
        val body = JSONObject().apply { put("meetingId", meetingId) }
        return callFunction("deletePersonalMeeting", body).map { }
    }

    override suspend fun linkSupabaseAccount(supabaseEmail: String, supabasePassword: String): Result<String> {
        val body = JSONObject().apply {
            put("supabaseEmail", supabaseEmail.trim())
            put("supabasePassword", supabasePassword)
        }
        return callFunction("linkSupabaseAccount", body).map { result ->
            result.optString("supabaseUserId", "")
        }
    }

    override fun rememberLocalAudioPath(userId: String, meetingId: String, localPath: String?) {
        if (userId.isBlank() || meetingId.isBlank()) return
        val key = "${userId}_$meetingId"
        if (localPath.isNullOrBlank()) {
            memoryAudioPaths.remove(key)
            prefs?.edit()?.remove(key)?.apply()
        } else {
            memoryAudioPaths[key] = localPath
            prefs?.edit()?.putString(key, localPath)?.apply()
        }
    }

    override fun getLocalAudioPath(userId: String, meetingId: String): String? {
        if (userId.isBlank() || meetingId.isBlank()) return null
        val key = "${userId}_$meetingId"
        return memoryAudioPaths[key] ?: prefs?.getString(key, null)
    }

    override fun clearLocalAudioCache(userId: String) {
        val prefix = "${userId}_"
        memoryAudioPaths.keys.filter { it.startsWith(prefix) }.forEach { memoryAudioPaths.remove(it) }
        prefs?.let { sp ->
            val editor = sp.edit()
            sp.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
            editor.apply()
        }
    }

    private suspend fun callFunction(name: String, data: JSONObject): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                val idToken = tokenProvider()
                val normalizedBase = baseUrl.trimEnd('/')
                val payload = JSONObject().apply { put("data", data) }.toString()
                val request = Request.Builder()
                    .url("$normalizedBase/$name")
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("Authorization", "Bearer $idToken")
                    .header("Content-Type", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    val json = runCatching { JSONObject(raw) }.getOrNull()
                    if (!response.isSuccessful || json?.has("error") == true) {
                        val errObj = json?.optJSONObject("error")
                        val status = errObj?.optString("status").orEmpty().ifBlank { "HTTP_${response.code}" }
                        val details = errObj?.optJSONObject("details")
                        val detailCode = details?.optString("code")?.takeIf { it.isNotBlank() }
                        val message = errObj?.optString("message")?.takeIf { it.isNotBlank() }
                            ?: friendlyHttpError(response.code)
                        return@withContext Result.failure(
                            PersonalBackendException(status, detailCode, message)
                        )
                    }
                    val resultObj = json?.optJSONObject("result")
                        ?: return@withContext Result.failure(
                            PersonalBackendException("INVALID_RESPONSE", null, "Respons server tidak valid.")
                        )
                    Result.success(resultObj)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: PersonalBackendException) {
                Result.failure(e)
            } catch (e: IOException) {
                Result.failure(
                    PersonalBackendException(
                        "UNAVAILABLE",
                        null,
                        "Koneksi jaringan bermasalah atau habis waktu. Periksa internet Anda dan coba lagi."
                    )
                )
            } catch (e: Exception) {
                Result.failure(
                    PersonalBackendException(
                        "UNKNOWN",
                        null,
                        "Terjadi kendala saat menyimpan atau memuat notulen. Silakan coba lagi."
                    )
                )
            }
        }

    private fun friendlyHttpError(code: Int): String = when (code) {
        401 -> "Sesi masuk berakhir. Silakan masuk kembali."
        403 -> "Anda tidak memiliki izin untuk mengakses notulen ini."
        404 -> "Layanan backend atau rapat tidak ditemukan."
        408, 504 -> "Koneksi ke server habis waktu. Silakan coba lagi."
        in 500..599 -> "Server sedang mengalami gangguan. Silakan coba lagi."
        else -> "Gagal memproses permintaan ($code). Silakan coba lagi."
    }
}

object PersonalMeetingJsonMapper {

    fun parseMeeting(obj: JSONObject, fallbackOwnerName: String = "Saya"): Meeting {
        val transcript = obj.optJSONObject("transcript")
        val summary = obj.optJSONObject("summary")

        val segments = parseSegments(transcript?.optJSONArray("segmen"))
        val transcriptText = transcript?.optString("isi", "").orEmpty().ifBlank {
            segments.joinToString("\n") { "[${it.pembicara}] ${it.teks}" }
        }

        val audioPath = obj.optString("audio_path", "").takeIf {
            it.isNotBlank() && it != "null"
        }

        return Meeting(
            id = obj.optString("id", ""),
            tenantId = "",
            userId = obj.optString("user_id", ""),
            ownerName = fallbackOwnerName,
            judul = obj.optString("judul", "Rapat").ifBlank { "Rapat" },
            status = obj.optString("status", "done").ifBlank { "done" },
            errorMessage = obj.optString("error_message", "").takeIf { it.isNotBlank() && it != "null" },
            durasiDetik = obj.optLong("durasi_detik", 0L),
            ukuranBytes = obj.optLong("ukuran_bytes", 0L),
            // Never hydrate remote audio_path into localAudioPath as a local file path.
            localAudioPath = if (audioPath != null && (audioPath.startsWith("http://") || audioPath.startsWith("https://"))) {
                null
            } else {
                null
            },
            bahasa = transcript?.optString("bahasa", "id")?.ifBlank { "id" } ?: "id",
            transcriptText = transcriptText,
            segments = segments,
            ringkasan = summary?.optString("ringkasan", "").orEmpty(),
            poinUtama = parseStringList(summary?.optJSONArray("poin_utama")),
            keputusan = parseStringList(summary?.optJSONArray("keputusan")),
            risiko = parseStringList(summary?.optJSONArray("risiko")),
            angkaKunci = parseKeyNumbers(summary?.optJSONArray("angka_kunci")),
            actionItems = parseActionItems(summary?.optJSONArray("action_items")),
            analysis = parseAnalysis(summary?.optJSONObject("analysis")),
            visualData = parseVisualData(summary?.optJSONObject("visual_json")),
            modelInfo = parseModelInfo(obj.optJSONObject("model_info")),
            isFavorite = obj.optBoolean("is_favorite", false),
            tags = parseStringList(obj.optJSONArray("tags")),
            createdAt = parseIsoTimestamp(obj.optString("created_at", "")),
            updatedAt = parseIsoTimestamp(obj.optString("updated_at", ""))
        )
    }

    fun parseSegments(arr: JSONArray?): List<TranscriptSegment> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    TranscriptSegment(
                        pembicara = o.optString("pembicara", "Pembicara 1").ifBlank { "Pembicara 1" },
                        mulaiDetik = o.optLong("mulaiDetik", o.optLong("mulai_detik", 0L)),
                        selesaiDetik = o.optLong("selesaiDetik", o.optLong("selesai_detik", 0L)),
                        teks = o.optString("teks", "")
                    )
                )
            }
        }
    }

    fun parseStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotBlank() && s != "null") add(s)
            }
        }
    }

    fun parseKeyNumbers(arr: JSONArray?): List<KeyNumber> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(KeyNumber(label = o.optString("label", ""), nilai = o.optString("nilai", "")))
            }
        }
    }

    fun parseActionItems(arr: JSONArray?): List<ActionItem> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    ActionItem(
                        id = o.optString("id", "act_$i"),
                        siapa = o.optString("siapa", ""),
                        tugas = o.optString("tugas", ""),
                        deadline = o.optString("deadline", ""),
                        prioritas = o.optString("prioritas", "sedang"),
                        selesai = o.optBoolean("selesai", false)
                    )
                )
            }
        }
    }

    fun parseAnalysis(obj: JSONObject?): MeetingAnalysis? {
        if (obj == null) return null
        return MeetingAnalysis(
            topik = parseStringList(obj.optJSONArray("topik")),
            suasana = obj.optString("suasana", ""),
            pertanyaanTerbuka = parseStringList(obj.optJSONArray("pertanyaanTerbuka")),
            ringkasPerTopik = parseStringList(obj.optJSONArray("ringkasPerTopik"))
        )
    }

    fun parseVisualData(obj: JSONObject?): VisualData? {
        if (obj == null) return null
        return VisualData(
            judul = obj.optString("judul", ""),
            poinUtama = parseStringList(obj.optJSONArray("poinUtama")),
            keputusan = parseStringList(obj.optJSONArray("keputusan")),
            angkaKunci = parseStringList(obj.optJSONArray("angkaKunci")),
            risiko = parseStringList(obj.optJSONArray("risiko")),
            tindakan = parseStringList(obj.optJSONArray("tindakan"))
        )
    }

    fun parseModelInfo(obj: JSONObject?): ModelInfo? {
        if (obj == null) return null
        return ModelInfo(
            provider = obj.optString("provider", ""),
            model = obj.optString("model", ""),
            generatedAtLabel = obj.optString("generatedAtLabel", "")
        )
    }

    fun parseIsoTimestamp(raw: String?): Timestamp? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val cleaned = raw.trim()
        val normalized = normalizeIsoTimestamp(cleaned)
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ssZ"
        )
        for (pattern in patterns) {
            val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val parsed: Date? = runCatching { sdf.parse(normalized) }.getOrNull()
            if (parsed != null) {
                return Timestamp(parsed)
            }
        }
        return null
    }

    private fun normalizeIsoTimestamp(input: String): String {
        var s = input
        if (s.endsWith("Z")) {
            s = s.dropLast(1) + "+0000"
        } else {
            val tzRegex = Regex("([+-]\\d{2}):(\\d{2})$")
            s = tzRegex.replace(s) { m -> "${m.groupValues[1]}${m.groupValues[2]}" }
        }
        val fracRegex = Regex("\\.(\\d+)([+-]\\d{4})$")
        s = fracRegex.replace(s) { m ->
            val ms = m.groupValues[1].padEnd(3, '0').take(3)
            ".$ms${m.groupValues[2]}"
        }
        return s
    }

    fun mapToJsonObject(map: Map<String, Any?>): JSONObject {
        val obj = JSONObject()
        map.forEach { (k, v) ->
            obj.put(k, toJsonValue(v))
        }
        return obj
    }

    @Suppress("UNCHECKED_CAST")
    private fun toJsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray, is Boolean, is Int, is Long, is Double, is String -> value
        is Float -> value.toDouble()
        is Map<*, *> -> mapToJsonObject(value as Map<String, Any?>)
        is Iterable<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
        else -> value.toString()
    }
}
