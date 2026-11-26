package com.example.core

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

fun handleFirestoreError(
    exception: Throwable,
    operationType: OperationType,
    path: String?
): String {
    val causes = generateSequence(exception) { it.cause }
    val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
        ?: causes.firstOrNull { it !is CancellationException }
        ?: exception

    val auth = FirebaseAuth.getInstance()
    val currentUser = auth.currentUser

    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
            put("email", provider.email)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorInfoJson = JSONObject().apply {
        put("error", rootError.message ?: rootError.toString())
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}

fun toFriendlyIndonesianError(error: Throwable?): String {
    if (error == null) return "Terjadi kesalahan yang tidak diketahui."
    if (error is com.example.data.ai.BackendAiException && error.message.isNotBlank()) {
        return error.message
    }
    if (error is com.example.data.repository.PersonalBackendException && error.message.isNotBlank()) {
        return error.message
    }
    val msg = error.message.orEmpty()
    return when {
        error is java.net.UnknownHostException ||
            error is java.net.SocketTimeoutException ||
            error is java.net.ConnectException ||
            msg.contains("UNAVAILABLE", ignoreCase = true) ||
            msg.contains("DEADLINE_EXCEEDED", ignoreCase = true) ||
            msg.contains("network", ignoreCase = true) ||
            msg.contains("timeout", ignoreCase = true) ->
            "Koneksi jaringan bermasalah atau habis waktu. Periksa internet Anda dan coba lagi."
        msg.contains("App Check", ignoreCase = true) ->
            "Verifikasi App Check gagal pada layanan Firebase. Periksa koneksi internet Anda."
        msg.contains("PERMISSION_DENIED", ignoreCase = true) ->
            "Akses ditolak. Periksa izin akun Anda."
        msg.contains("Quota exceeded", ignoreCase = true) ||
            msg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ->
            "Kuota sementara layanan AI tercapai. Tunggu beberapa saat lalu coba lagi."
        error is IllegalArgumentException && msg.isNotBlank() -> msg
        error is IllegalStateException && msg.isNotBlank() -> msg
        else -> "Terjadi kendala saat memproses permintaan. Silakan coba lagi."
    }
}
