package com.example.data.ai

import android.util.Base64
import com.example.BuildConfig
import com.example.core.AppConstants
import com.google.firebase.Firebase
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
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class BackendAiException(
    val statusCode: String,
    val detailCode: String? = null,
    override val message: String
) : Exception(message)

data class BackendAiResponse(
    val text: String,
    val modelId: String,
    val providerLabel: String,
    val usedFallback: Boolean,
    val inputTokens: Long,
    val outputTokens: Long,
    val latencyMs: Long
)

data class BackendAiHealthResult(
    val latencyMs: Long,
    val provider: String,
    val availableModels: List<String>
)

class MeetingBackendAiClient(
    private val baseUrl: String = BuildConfig.MEETING_BACKEND_URL.ifBlank { AppConstants.DEFAULT_MEETING_BACKEND_URL },
    private val authProvider: () -> FirebaseAuth? = { runCatching { Firebase.auth }.getOrNull() },
    private val tokenProvider: suspend (forceRefresh: Boolean) -> String = { forceRefresh ->
        val user = authProvider()?.currentUser
            ?: throw BackendAiException("UNAUTHENTICATED", null, "Sesi masuk berakhir. Silakan masuk kembali.")
        user.getIdToken(forceRefresh).await().token
            ?: throw BackendAiException("UNAUTHENTICATED", null, "Token autentikasi Firebase tidak tersedia.")
    },
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    suspend fun processTextTask(
        task: String,
        systemPrompt: String,
        userPrompt: String,
        requestedModel: String? = null,
        temperature: Float = 0.2f,
        maxTokens: Int = 4096,
        jsonMode: Boolean = true,
        timeoutSec: Int = 65
    ): Result<BackendAiResponse> {
        val data = JSONObject().apply {
            put("task", task)
            put("systemPrompt", systemPrompt)
            put("userPrompt", userPrompt)
            if (!requestedModel.isNullOrBlank()) put("requestedModel", requestedModel)
            put("temperature", temperature.toDouble())
            put("maxTokens", maxTokens)
            put("jsonMode", jsonMode)
        }
        return callEndpoint("processAi", data, timeoutSec).map { result ->
            BackendAiResponse(
                text = result.optString("text", ""),
                modelId = result.optString("modelId", requestedModel ?: AppConstants.DEFAULT_MODEL_ID),
                providerLabel = result.optString("providerLabel", "SumoPod AI"),
                usedFallback = result.optBoolean("usedFallback", false),
                inputTokens = result.optLong("inputTokens", 0L),
                outputTokens = result.optLong("outputTokens", 0L),
                latencyMs = result.optLong("latencyMs", 0L)
            )
        }
    }

    suspend fun transcribeAudio(
        audioBytes: ByteArray,
        mimeType: String,
        preferredLanguage: String = "id",
        systemPrompt: String = "",
        timeoutSec: Int = 120
    ): Result<BackendAiResponse> {
        val encoded = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
        val data = JSONObject().apply {
            put("task", "audio_transcription")
            put("audioBase64", encoded)
            put("mimeType", mimeType)
            put("preferredLanguage", preferredLanguage.ifBlank { "id" })
            if (systemPrompt.isNotBlank()) put("systemPrompt", systemPrompt)
            put("jsonMode", true)
        }
        return callEndpoint("processAi", data, timeoutSec).map { result ->
            BackendAiResponse(
                text = result.optString("text", ""),
                modelId = result.optString("modelId", AppConstants.STT_MODEL_ID),
                providerLabel = result.optString("providerLabel", "SumoPod AI"),
                usedFallback = result.optBoolean("usedFallback", false),
                inputTokens = result.optLong("inputTokens", 0L),
                outputTokens = result.optLong("outputTokens", 0L),
                latencyMs = result.optLong("latencyMs", 0L)
            )
        }
    }

    suspend fun checkHealth(timeoutSec: Int = 25): Result<BackendAiHealthResult> {
        return callEndpoint("checkAiHealth", JSONObject(), timeoutSec).map { result ->
            val arr = result.optJSONArray("availableModels")
            val models = buildList {
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i).trim()
                        if (s.isNotBlank()) add(s)
                    }
                }
            }
            BackendAiHealthResult(
                latencyMs = result.optLong("latencyMs", 0L),
                provider = result.optString("provider", "SumoPod AI"),
                availableModels = models
            )
        }
    }

    private suspend fun callEndpoint(
        endpoint: String,
        data: JSONObject,
        timeoutSec: Int
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val firstAttempt = executeRequest(endpoint, data, timeoutSec, forceRefreshToken = false)
            if (firstAttempt.isFailure) {
                val ex = firstAttempt.exceptionOrNull() as? BackendAiException
                if (ex?.statusCode == "UNAUTHENTICATED") {
                    return@withContext executeRequest(endpoint, data, timeoutSec, forceRefreshToken = true)
                }
            }
            firstAttempt
        } catch (c: CancellationException) {
            throw c
        } catch (e: BackendAiException) {
            Result.failure(e)
        } catch (e: SocketTimeoutException) {
            Result.failure(
                BackendAiException(
                    "DEADLINE_EXCEEDED",
                    null,
                    "Pemrosesan AI melewati batas waktu. Silakan coba ulang."
                )
            )
        } catch (e: IOException) {
            Result.failure(
                BackendAiException(
                    "UNAVAILABLE",
                    null,
                    "Koneksi internet ke server AI bermasalah. Periksa jaringan Anda dan coba lagi."
                )
            )
        } catch (e: Exception) {
            Result.failure(
                BackendAiException(
                    "UNKNOWN",
                    null,
                    "Terjadi kendala saat menghubungi layanan AI. Silakan coba lagi."
                )
            )
        }
    }

    private suspend fun executeRequest(
        endpoint: String,
        data: JSONObject,
        timeoutSec: Int,
        forceRefreshToken: Boolean
    ): Result<JSONObject> {
        val idToken = tokenProvider(forceRefreshToken)
        val normalizedBase = baseUrl.trimEnd('/')
        val payload = JSONObject().apply { put("data", data) }.toString()

        val client = baseClient.newBuilder()
            .readTimeout(timeoutSec.coerceIn(15, 180).toLong(), TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url("$normalizedBase/$endpoint")
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
                    ?: if (status == "AUDIO_INPUT_UNSUPPORTED" || status == "AUDIO_TRANSCRIPTION_INVALID_OUTPUT") status else null
                val message = if (response.code == 404 && (status == "NOT_FOUND" || status == "HTTP_404")) {
                    friendlyHttpError(response.code, endpoint)
                } else {
                    errObj?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: friendlyHttpError(response.code, endpoint)
                }
                return Result.failure(BackendAiException(status, detailCode, message))
            }
            val resultObj = json?.optJSONObject("result")
                ?: return Result.failure(
                    BackendAiException("INVALID_RESPONSE", null, "Respons dari server AI tidak valid.")
                )
            return Result.success(resultObj)
        }
    }

    private fun friendlyHttpError(code: Int, endpoint: String): String = when (code) {
        401 -> "Sesi masuk berakhir. Silakan masuk kembali."
        403 -> "Anda tidak memiliki izin untuk menggunakan layanan AI ini."
        404 -> "Endpoint $endpoint belum terpasang di backend meeting-api Supabase."
        413 -> "Ukuran segmen audio atau teks terlalu besar untuk satu permintaan."
        429 -> "Batas penggunaan AI sementara tercapai. Tunggu beberapa menit lalu coba lagi."
        408, 504 -> "Pemrosesan AI melewati batas waktu. Silakan coba lagi."
        in 500..599 -> "Server AI sedang mengalami gangguan sementara. Silakan coba lagi."
        else -> "Permintaan AI gagal (HTTP $code). Silakan coba lagi."
    }
}
