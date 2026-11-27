package com.example.data.ai

import com.example.core.readReply
import com.example.data.model.AiConnection
import com.example.data.model.ProviderPresets
import com.example.data.model.TranscriptSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ProviderTextResponse(
    val text: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val latencyMs: Long
)

data class ProviderSttResponse(
    val bahasa: String,
    val segments: List<TranscriptSegment>,
    val latencyMs: Long
)

internal class ProviderHttpException(val status: Int, message: String) : IOException(message)

class CustomProviderClient {
    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun clientWithTimeout(timeoutSec: Int): OkHttpClient =
        baseClient.newBuilder()
            .callTimeout(timeoutSec.coerceIn(10, 300).toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSec.coerceIn(10, 300).toLong(), TimeUnit.SECONDS)
            .build()

    private fun normalizeBaseUrl(connection: AiConnection): String {
        val fallback = ProviderPresets.presetFor(connection.providerType)?.defaultBaseUrl.orEmpty()
        return connection.baseUrl.ifBlank { fallback }.trim().trimEnd('/')
    }

    suspend fun testConnection(connection: AiConnection, apiKey: String): Result<Long> = withContext(Dispatchers.IO) {
        val base = normalizeBaseUrl(connection)
        if (base.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Base URL wajib diisi untuk provider ini."))
        }
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("API Key belum diisi."))
        }
        val start = System.currentTimeMillis()
        try {
            val client = clientWithTimeout(connection.timeoutSec)
            val model = connection.defaultModel.ifBlank { connection.allAvailableModels.firstOrNull().orEmpty() }
            val request = when (connection.providerType) {
                "gemini", "google_stt" -> Request.Builder()
                    .url("$base/models")
                    .header("x-goog-api-key", apiKey)
                    .get()
                    .build()
                "anthropic" -> {
                    val body = JSONObject().apply {
                        put("model", model.ifBlank { "claude-3-5-haiku-latest" })
                        put("max_tokens", 8)
                        put("messages", JSONArray().put(JSONObject().apply {
                            put("role", "user")
                            put("content", "Balas OK")
                        }))
                    }
                    Request.Builder()
                        .url("$base/messages")
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", "2023-06-01")
                        .post(body.toString().toRequestBody(JSON_MEDIA))
                        .build()
                }
                "deepgram" -> Request.Builder()
                    .url("$base/projects")
                    .header("Authorization", "Token $apiKey")
                    .get()
                    .build()
                "assemblyai" -> Request.Builder()
                    .url("$base/transcript?limit=1")
                    .header("Authorization", apiKey)
                    .get()
                    .build()
                else -> Request.Builder()
                    .url("$base/models")
                    .header("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
            }

            client.newCall(request).readReply().let { resp ->
                val latency = System.currentTimeMillis() - start
                if (resp.isSuccessful) {
                    return@withContext Result.success(latency)
                }
                // Some OpenAI-compatible gateways do not expose GET /models -> fallback to minimal chat ping
                if (resp.code == 404 && connection.kind == "ai" && connection.providerType !in listOf("gemini", "anthropic")) {
                    val pingBody = JSONObject().apply {
                        put("model", model.ifBlank { "gpt-4o-mini" })
                        put("max_tokens", 4)
                        put("messages", JSONArray().put(JSONObject().apply {
                            put("role", "user")
                            put("content", "OK")
                        }))
                    }
                    val pingReq = Request.Builder()
                        .url("$base/chat/completions")
                        .header("Authorization", "Bearer $apiKey")
                        .post(pingBody.toString().toRequestBody(JSON_MEDIA))
                        .build()
                    client.newCall(pingReq).readReply().let { pingResp ->
                        val pingLat = System.currentTimeMillis() - start
                        if (pingResp.isSuccessful) return@withContext Result.success(pingLat)
                        return@withContext Result.failure(IOException(friendlyHttpError(pingResp.code, pingResp.body)))
                    }
                }
                Result.failure(IOException(friendlyHttpError(resp.code, resp.body)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IOException("Gagal tersambung ke $base: ${e.localizedMessage ?: "Kesalahan jaringan"}"))
        }
    }

    suspend fun fetchModels(connection: AiConnection, apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        val base = normalizeBaseUrl(connection)
        if (base.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Base URL wajib diisi."))
        }
        val preset = ProviderPresets.presetFor(connection.providerType)
        if (preset != null && !preset.supportsFetchModels) {
            return@withContext Result.success(preset.defaultModels)
        }
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Masukkan API Key untuk mengambil daftar model."))
        }
        try {
            val client = clientWithTimeout(connection.timeoutSec)
            val req = when (connection.providerType) {
                "gemini", "google_stt" -> Request.Builder()
                    .url("$base/models")
                    .header("x-goog-api-key", apiKey)
                    .get()
                    .build()
                else -> Request.Builder()
                    .url("$base/models")
                    .header("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
            }
            client.newCall(req).readReply().let { resp ->
                val raw = resp.body.orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(ProviderHttpException(resp.code, friendlyHttpError(resp.code, raw)))
                }
                val json = JSONObject(raw)
                val ids = mutableListOf<String>()
                json.optJSONArray("data")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val id = arr.optJSONObject(i)?.optString("id")?.trim().orEmpty()
                        if (id.isNotBlank()) ids.add(id)
                    }
                }
                if (ids.isEmpty()) {
                    json.optJSONArray("models")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val name = arr.optJSONObject(i)?.optString("name")?.removePrefix("models/")?.trim().orEmpty()
                            if (name.isNotBlank()) ids.add(name)
                        }
                    }
                }
                if (ids.isEmpty()) {
                    return@withContext Result.failure(IOException("Provider tidak mengembalikan daftar model. Tambahkan model manual."))
                }
                Result.success(ids.distinct().sorted())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IOException("Gagal mengambil daftar model: ${e.localizedMessage ?: "Kesalahan jaringan"}"))
        }
    }

    suspend fun generateText(
        connection: AiConnection,
        apiKey: String,
        modelId: String,
        systemPrompt: String,
        userPrompt: String,
        temperature: Float,
        maxTokens: Int,
        timeoutSec: Int,
        jsonMode: Boolean
    ): Result<ProviderTextResponse> = withContext(Dispatchers.IO) {
        val base = normalizeBaseUrl(connection)
        if (base.isBlank() || apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Koneksi ${connection.name} belum lengkap."))
        }
        val model = modelId.ifBlank { connection.defaultModel }.ifBlank { "gpt-4o-mini" }
        val start = System.currentTimeMillis()
        try {
            val client = clientWithTimeout(timeoutSec)
            when (connection.providerType) {
                "gemini" -> {
                    val body = JSONObject().apply {
                        put("systemInstruction", JSONObject().apply {
                            put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                        })
                        put("contents", JSONArray().put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                        }))
                        put("generationConfig", JSONObject().apply {
                            put("temperature", temperature.toDouble())
                            put("maxOutputTokens", maxTokens)
                            if (jsonMode) put("responseMimeType", "application/json")
                        })
                    }
                    val req = Request.Builder()
                        .url("$base/models/$model:generateContent")
                        .header("x-goog-api-key", apiKey)
                        .post(body.toString().toRequestBody(JSON_MEDIA))
                        .build()
                    client.newCall(req).readReply().let { resp ->
                        val raw = resp.body.orEmpty()
                        if (!resp.isSuccessful) return@withContext Result.failure(ProviderHttpException(resp.code, friendlyHttpError(resp.code, raw)))
                        val json = JSONObject(raw)
                        val text = json.optJSONArray("candidates")
                            ?.optJSONObject(0)
                            ?.optJSONObject("content")
                            ?.optJSONArray("parts")
                            ?.optJSONObject(0)
                            ?.optString("text").orEmpty()
                        val usage = json.optJSONObject("usageMetadata")
                        Result.success(
                            ProviderTextResponse(
                                text = text,
                                inputTokens = usage?.optLong("promptTokenCount", 0L) ?: 0L,
                                outputTokens = usage?.optLong("candidatesTokenCount", 0L) ?: 0L,
                                latencyMs = System.currentTimeMillis() - start
                            )
                        )
                    }
                }
                "anthropic" -> {
                    val body = JSONObject().apply {
                        put("model", model)
                        put("max_tokens", maxTokens)
                        put("temperature", temperature.toDouble())
                        put("system", systemPrompt)
                        put("messages", JSONArray().put(JSONObject().apply {
                            put("role", "user")
                            put("content", userPrompt)
                        }))
                    }
                    val req = Request.Builder()
                        .url("$base/messages")
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", "2023-06-01")
                        .post(body.toString().toRequestBody(JSON_MEDIA))
                        .build()
                    client.newCall(req).readReply().let { resp ->
                        val raw = resp.body.orEmpty()
                        if (!resp.isSuccessful) return@withContext Result.failure(ProviderHttpException(resp.code, friendlyHttpError(resp.code, raw)))
                        val json = JSONObject(raw)
                        val text = json.optJSONArray("content")
                            ?.optJSONObject(0)
                            ?.optString("text").orEmpty()
                        val usage = json.optJSONObject("usage")
                        Result.success(
                            ProviderTextResponse(
                                text = text,
                                inputTokens = usage?.optLong("input_tokens", 0L) ?: 0L,
                                outputTokens = usage?.optLong("output_tokens", 0L) ?: 0L,
                                latencyMs = System.currentTimeMillis() - start
                            )
                        )
                    }
                }
                else -> {
                    // OpenAI, DeepSeek, OpenRouter, Sumopod, Custom OpenAI-compatible
                    val body = JSONObject().apply {
                        put("model", model)
                        put("temperature", temperature.toDouble())
                        put("max_tokens", maxTokens)
                        if (jsonMode && connection.providerType in listOf("openai", "deepseek", "openrouter")) {
                            put("response_format", JSONObject().put("type", "json_object"))
                        }
                        put("messages", JSONArray().apply {
                            if (systemPrompt.isNotBlank()) {
                                put(JSONObject().put("role", "system").put("content", systemPrompt))
                            }
                            put(JSONObject().put("role", "user").put("content", userPrompt))
                        })
                    }
                    val reqBuilder = Request.Builder()
                        .url("$base/chat/completions")
                        .header("Authorization", "Bearer $apiKey")
                    if (connection.providerType == "openrouter") {
                        reqBuilder.header("X-Title", "AI Notulen Rapat")
                    }
                    client.newCall(reqBuilder.post(body.toString().toRequestBody(JSON_MEDIA)).build()).readReply().let { resp ->
                        val raw = resp.body.orEmpty()
                        if (!resp.isSuccessful) return@withContext Result.failure(ProviderHttpException(resp.code, friendlyHttpError(resp.code, raw)))
                        val json = JSONObject(raw)
                        val text = json.optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("message")
                            ?.optString("content").orEmpty()
                        val usage = json.optJSONObject("usage")
                        Result.success(
                            ProviderTextResponse(
                                text = text,
                                inputTokens = usage?.optLong("prompt_tokens", 0L) ?: 0L,
                                outputTokens = usage?.optLong("completion_tokens", 0L) ?: 0L,
                                latencyMs = System.currentTimeMillis() - start
                            )
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IOException("Gagal memanggil ${connection.name}: ${e.localizedMessage ?: "Timeout atau jaringan error"}"))
        }
    }

    suspend fun transcribeAudio(
        connection: AiConnection,
        apiKey: String,
        modelId: String,
        audioBytes: ByteArray,
        mimeType: String,
        language: String,
        diarize: Boolean,
        timeoutSec: Int
    ): Result<ProviderSttResponse> = withContext(Dispatchers.IO) {
        val base = normalizeBaseUrl(connection)
        if (base.isBlank() || apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Koneksi STT ${connection.name} belum lengkap."))
        }
        val model = modelId.ifBlank { connection.defaultModel }.ifBlank { "whisper-1" }
        val lang = language.ifBlank { connection.sttLanguage }.ifBlank { "id" }
        val start = System.currentTimeMillis()
        try {
            val client = clientWithTimeout(timeoutSec)
            when (connection.providerType) {
                "deepgram" -> {
                    val url = "$base/listen?model=$model&language=$lang&smart_format=true&diarize=$diarize&utterances=true"
                    val req = Request.Builder()
                        .url(url)
                        .header("Authorization", "Token $apiKey")
                        .post(audioBytes.toRequestBody(mimeType.toMediaType()))
                        .build()
                    client.newCall(req).readReply().let { resp ->
                        val raw = resp.body.orEmpty()
                        if (!resp.isSuccessful) return@withContext Result.failure(ProviderHttpException(resp.code, friendlyHttpError(resp.code, raw)))
                        val json = JSONObject(raw)
                        val results = json.optJSONObject("results")
                        val utterances = results?.optJSONArray("utterances")
                        val segments = mutableListOf<TranscriptSegment>()
                        if (utterances != null && utterances.length() > 0) {
                            for (i in 0 until utterances.length()) {
                                val u = utterances.getJSONObject(i)
                                val spkIdx = u.optInt("speaker", 0) + 1
                                val txt = u.optString("transcript").trim()
                                if (txt.isNotBlank()) {
                                    segments.add(
                                        TranscriptSegment(
                                            pembicara = "Pembicara $spkIdx",
                                            mulaiDetik = u.requiredTimestamp("start"),
                                            selesaiDetik = u.requiredTimestamp("end"),
                                            teks = txt
                                        )
                                    )
                                }
                            }
                        } else {
                            val altText = results?.optJSONArray("channels")
                                ?.optJSONObject(0)
                                ?.optJSONArray("alternatives")
                                ?.optJSONObject(0)
                                ?.optString("transcript")?.trim().orEmpty()
                            if (altText.isNotBlank()) {
                                segments.add(TranscriptSegment("Pembicara 1", 0L, 0L, altText))
                            }
                        }
                        Result.success(ProviderSttResponse(lang, segments, System.currentTimeMillis() - start))
                    }
                }
                "google_stt" -> {
                    val b64 = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP)
                    val promptText = "Transkripsikan rekaman audio dalam bahasa $lang. Ukur waktu dalam detik dari awal berkas audio terlampir (detik 0) dan jangan melebihi durasinya. Kembalikan JSON {\"bahasa\":\"$lang\",\"segmen\":[{\"pembicara\":\"Pembicara 1\",\"mulaiDetik\":0,\"selesaiDetik\":10,\"teks\":\"...\"}]}."
                    val body = JSONObject().apply {
                        put("contents", JSONArray().put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().put("text", promptText))
                                put(JSONObject().put("inlineData", JSONObject().apply {
                                    put("mimeType", mimeType)
                                    put("data", b64)
                                }))
                            })
                        }))
                        put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
                    }
                    val req = Request.Builder()
                        .url("$base/models/$model:generateContent")
                        .header("x-goog-api-key", apiKey)
                        .post(body.toString().toRequestBody(JSON_MEDIA))
                        .build()
                    client.newCall(req).readReply().let { resp ->
                        val raw = resp.body.orEmpty()
                        if (!resp.isSuccessful) return@withContext Result.failure(ProviderHttpException(resp.code, friendlyHttpError(resp.code, raw)))
                        val root = JSONObject(raw)
                        val textJson = root.optJSONArray("candidates")
                            ?.optJSONObject(0)?.optJSONObject("content")
                            ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty()
                        val parsed = JSONObject(extractJsonObject(textJson))
                        val arr = parsed.optJSONArray("segmen")
                        val segments = mutableListOf<TranscriptSegment>()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val o = arr.getJSONObject(i)
                                val t = o.optString("teks").trim()
                                if (t.isNotBlank()) {
                                    segments.add(
                                        TranscriptSegment(
                                            pembicara = o.optString("pembicara", "Pembicara 1"),
                                            mulaiDetik = o.requiredTimestamp("mulaiDetik"),
                                            selesaiDetik = o.requiredTimestamp("selesaiDetik"),
                                            teks = t
                                        )
                                    )
                                }
                            }
                        }
                        Result.success(ProviderSttResponse(parsed.optString("bahasa", lang), segments, System.currentTimeMillis() - start))
                    }
                }
                else -> {
                    // OpenAI Whisper & Custom Whisper-compatible
                    val ext = when {
                        mimeType.contains("wav") -> "wav"
                        mimeType.contains("ogg") -> "ogg"
                        mimeType.contains("mpeg") || mimeType.contains("mp3") -> "mp3"
                        else -> "m4a"
                    }
                    val multipart = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", "meeting.$ext", audioBytes.toRequestBody(mimeType.toMediaType()))
                        .addFormDataPart("model", model)
                        .addFormDataPart("response_format", "verbose_json")
                        .apply {
                            if (lang != "auto") addFormDataPart("language", lang)
                        }
                        .build()
                    val req = Request.Builder()
                        .url("$base/audio/transcriptions")
                        .header("Authorization", "Bearer $apiKey")
                        .post(multipart)
                        .build()
                    client.newCall(req).readReply().let { resp ->
                        val raw = resp.body.orEmpty()
                        if (!resp.isSuccessful) return@withContext Result.failure(ProviderHttpException(resp.code, friendlyHttpError(resp.code, raw)))
                        val json = JSONObject(raw)
                        val segArr = json.optJSONArray("segments")
                        val segments = mutableListOf<TranscriptSegment>()
                        if (segArr != null && segArr.length() > 0) {
                            for (i in 0 until segArr.length()) {
                                val s = segArr.getJSONObject(i)
                                val t = s.optString("text").trim()
                                if (t.isNotBlank()) {
                                    segments.add(
                                        TranscriptSegment(
                                            pembicara = s.optString("speaker", "Pembicara 1").ifBlank { "Pembicara 1" },
                                            mulaiDetik = s.requiredTimestamp("start"),
                                            selesaiDetik = s.requiredTimestamp("end"),
                                            teks = t
                                        )
                                    )
                                }
                            }
                        } else {
                            val txt = json.optString("text").trim()
                            if (txt.isNotBlank()) {
                                segments.add(TranscriptSegment("Pembicara 1", 0L, 0L, txt))
                            }
                        }
                        Result.success(
                            ProviderSttResponse(
                                bahasa = json.optString("language", lang).ifBlank { lang },
                                segments = segments,
                                latencyMs = System.currentTimeMillis() - start
                            )
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IOException("Gagal transkripsi STT (${connection.name}): ${e.localizedMessage ?: "Kesalahan jaringan"}"))
        }
    }

    private fun friendlyHttpError(code: Int, body: String?): String {
        val snippet = runCatching {
            val obj = JSONObject(body.orEmpty())
            obj.optJSONObject("error")?.optString("message")
                ?.ifBlank { obj.optString("message") }
        }.getOrNull().orEmpty().take(120)
        return when (code) {
            401, 403 -> "Autentikasi ditolak ($code). Periksa kembali API Key."
            404 -> "Endpoint atau model tidak ditemukan ($code). Periksa Base URL dan nama model."
            429 -> "Batas laju atau kuota provider terlampaui ($code)."
            else -> "Provider mengembalikan status $code${if (snippet.isNotBlank()) ": $snippet" else ""}"
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun extractJsonObject(raw: String): String {
            val trimmed = raw.trim()
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else "{}"
        }
    }
}

internal fun JSONObject.requiredTimestamp(key: String): Long {
    val value = optDouble(key, Double.NaN)
    require(value.isFinite() && value >= 0 && value < Long.MAX_VALUE.toDouble()) {
        "Stempel waktu transkripsi tidak valid."
    }
    return value.toLong()
}
