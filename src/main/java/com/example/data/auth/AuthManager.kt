package com.example.data.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.notulenrapat.android.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.Firebase
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private const val TAG = "Auth"

internal data class AuthErrorFeedback(
    val message: String,
    val isEmailAlreadyInUse: Boolean = false,
    val isNetworkError: Boolean = false
)

internal fun isValidEmailFormat(email: String): Boolean =
    Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email.trim())

internal fun isValidGoogleWebClientId(clientId: String?): Boolean {
    val clean = clientId?.trim().orEmpty()
    if (clean.isBlank()) return false
    if (clean.contains("YOUR_", ignoreCase = true) ||
        clean.contains("REPLACE", ignoreCase = true) ||
        clean.contains("placeholder", ignoreCase = true)
    ) return false
    return clean.endsWith(".apps.googleusercontent.com") && clean.length > 30
}

internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

internal fun resolveGoogleWebClientId(context: Context): String? {
    val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
    if (resId == 0) return null
    val raw = runCatching { context.getString(resId) }.getOrNull()
    return raw?.trim()?.takeIf { isValidGoogleWebClientId(it) }
}

internal fun validateLoginInput(email: String, password: String): String? {
    val cleanEmail = email.trim()
    return when {
        cleanEmail.isBlank() || password.isBlank() -> "Email dan password wajib diisi."
        !isValidEmailFormat(cleanEmail) -> "Format email tidak valid."
        else -> null
    }
}

internal fun validateRegistrationInput(
    email: String,
    password: String,
    confirmPassword: String
): String? {
    val cleanEmail = email.trim()
    return when {
        cleanEmail.isBlank() || password.isBlank() || confirmPassword.isBlank() ->
            "Lengkapi semua kolom pendaftaran."
        !isValidEmailFormat(cleanEmail) -> "Format email tidak valid."
        password.length < 8 -> "Password minimal 8 karakter."
        confirmPassword != password -> "Konfirmasi password tidak cocok."
        else -> null
    }
}

internal fun mapFirebaseAuthError(error: Throwable, isRegister: Boolean): AuthErrorFeedback {
    return when (error) {
        is FirebaseNetworkException -> AuthErrorFeedback(
            message = "Koneksi internet bermasalah. Periksa jaringan Anda lalu coba lagi.",
            isNetworkError = true
        )
        is FirebaseTooManyRequestsException -> AuthErrorFeedback(
            message = "Terlalu banyak percobaan gagal. Tunggu beberapa saat lalu coba lagi."
        )
        is FirebaseAuthUserCollisionException -> AuthErrorFeedback(
            message = "Email ini sudah terdaftar. Silakan masuk atau atur ulang password.",
            isEmailAlreadyInUse = true
        )
        is FirebaseAuthWeakPasswordException -> AuthErrorFeedback(
            message = "Password terlalu lemah. Gunakan minimal 8 karakter dengan kombinasi huruf dan angka."
        )
        is FirebaseAuthInvalidUserException -> {
            if (error.errorCode == "ERROR_USER_DISABLED") {
                AuthErrorFeedback("Akun ini dinonaktifkan. Hubungi admin untuk bantuan.")
            } else {
                AuthErrorFeedback("Email atau password salah.")
            }
        }
        is FirebaseAuthInvalidCredentialsException -> {
            if (isRegister && error.errorCode == "ERROR_INVALID_EMAIL") {
                AuthErrorFeedback("Format email tidak valid.")
            } else {
                AuthErrorFeedback("Email atau password salah.")
            }
        }
        is FirebaseAuthException -> when (error.errorCode) {
            "ERROR_EMAIL_ALREADY_IN_USE" -> AuthErrorFeedback(
                message = "Email ini sudah terdaftar. Silakan masuk atau atur ulang password.",
                isEmailAlreadyInUse = true
            )
            "ERROR_INVALID_EMAIL" -> AuthErrorFeedback(
                if (isRegister) "Format email tidak valid." else "Email atau password salah."
            )
            "ERROR_WEAK_PASSWORD" -> AuthErrorFeedback(
                "Password terlalu lemah. Gunakan minimal 8 karakter dengan kombinasi huruf dan angka."
            )
            "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND", "ERROR_INVALID_CREDENTIAL" ->
                AuthErrorFeedback("Email atau password salah.")
            "ERROR_USER_DISABLED" ->
                AuthErrorFeedback("Akun ini dinonaktifkan. Hubungi admin untuk bantuan.")
            "ERROR_TOO_MANY_REQUESTS" ->
                AuthErrorFeedback("Terlalu banyak percobaan gagal. Tunggu beberapa saat lalu coba lagi.")
            "ERROR_NETWORK_REQUEST_FAILED" -> AuthErrorFeedback(
                message = "Koneksi internet bermasalah. Periksa jaringan Anda lalu coba lagi.",
                isNetworkError = true
            )
            else -> AuthErrorFeedback("Gagal memproses autentikasi. Silakan coba lagi.")
        }
        else -> {
            val isNetworkCause = generateSequence(error) { it.cause }.any {
                it is UnknownHostException || it is ConnectException || it is SocketTimeoutException
            }
            if (isNetworkCause) {
                AuthErrorFeedback(
                    message = "Koneksi internet bermasalah. Periksa jaringan Anda lalu coba lagi.",
                    isNetworkError = true
                )
            } else {
                AuthErrorFeedback("Gagal memproses autentikasi. Silakan coba lagi.")
            }
        }
    }
}

internal fun mapGoogleSignInError(error: Throwable): String {
    if (error is FirebaseAuthException || error is FirebaseNetworkException || error is FirebaseTooManyRequestsException) {
        return mapFirebaseAuthError(error, isRegister = false).message
    }
    val rawMsg = error.message.orEmpty()
    val lower = rawMsg.lowercase()
    return when {
        error is GetCredentialCancellationException ->
            "Proses masuk dengan Google dibatalkan."
        error is NoCredentialException ->
            "Tidak ada akun Google yang tersedia di perangkat ini. Pastikan Anda sudah masuk akun Google di Pengaturan Android atau gunakan login Email."
        error is GetCredentialProviderConfigurationException || error is GetCredentialUnsupportedException ->
            "Layanan Google Play Services tidak tersedia atau belum diperbarui pada perangkat ini."
        error is GetCredentialInterruptedException ->
            "Proses pemilihan akun Google terganggu. Silakan ketuk tombol Masuk dengan Google kembali."
        error is GoogleIdTokenParsingException ->
            "Token akun Google tidak dapat dibaca. Silakan coba masuk lagi."
        lower.contains("developer_error") ||
            lower.contains("28444") ||
            lower.contains("code=10") ||
            lower.contains("reauth failed") ||
            lower.contains("sha-1") ||
            lower.contains("16:") ->
            "Konfigurasi Google Sign-In untuk build ini belum cocok (SHA-1/SHA-256 atau Web Client ID di Firebase Console). Periksa google-services.json dan sertifikat signing APK."
        generateSequence(error) { it.cause }.any {
            it is UnknownHostException || it is ConnectException || it is SocketTimeoutException
        } || lower.contains("network") || lower.contains("timeout") ->
            "Koneksi internet bermasalah saat menghubungi Google. Periksa jaringan Anda lalu coba lagi."
        error is GetCredentialException ->
            "Gagal memuat pilihan akun Google. Pastikan Google Play Services aktif lalu coba lagi."
        else ->
            "Gagal masuk dengan akun Google. Silakan coba lagi atau gunakan login Email."
    }
}

fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

fun attemptAutoSignIn(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onUnauthenticated: () -> Unit,
    scope: CoroutineScope
) {
    if (Firebase.auth.currentUser != null) {
        onAuthSuccess()
        return
    }
    val activity = context.findActivity()
    val clientId = resolveGoogleWebClientId(context)
    if (activity == null || clientId == null) {
        onUnauthenticated()
        return
    }
    val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(true)
        .setServerClientId(clientId)
        .setAutoSelectEnabled(true)
        .build()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleIdOption)
        .build()
    scope.launch {
        try {
            val cred = credentialManager.getCredential(activity, request).credential
            if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                Firebase.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
                onAuthSuccess()
            } else {
                onUnauthenticated()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            onUnauthenticated()
        }
    }
}

fun onGoogleSignInClicked(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    scope: CoroutineScope,
    onAuthCancelled: () -> Unit = {}
) {
    val clientId = resolveGoogleWebClientId(context)
    if (clientId == null) {
        onAuthError(
            "Konfigurasi Google Sign-In belum lengkap (default_web_client_id tidak ditemukan dari google-services.json). " +
                "Tambahkan google-services.json proyek gen-lang-client-0830960267 sebelum build."
        )
        return
    }
    val activity = context.findActivity()
    if (activity == null) {
        onAuthError("Konteks aktivitas tidak valid untuk membuka pilihan akun Google.")
        return
    }

    val primaryRequest = GetCredentialRequest.Builder()
        .addCredentialOption(
            GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        )
        .build()

    val fallbackRequest = GetCredentialRequest.Builder()
        .addCredentialOption(
            GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(clientId)
                .setAutoSelectEnabled(false)
                .build()
        )
        .build()

    scope.launch {
        try {
            val result = try {
                credentialManager.getCredential(activity, primaryRequest)
            } catch (primaryErr: NoCredentialException) {
                Log.w(TAG, "Primary SignInWithGoogle returned NoCredential, retrying with GoogleIdOption")
                credentialManager.getCredential(activity, fallbackRequest)
            }
            val cred = result.credential
            if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                Firebase.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
                onAuthSuccess()
            } else {
                onAuthError("Tipe kredensial Google tidak dikenali.")
            }
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Google Sign-In flow cancelled by user: ${e.message}")
            onAuthCancelled()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Google Sign-In failed: ${e.javaClass.simpleName}", e)
            onAuthError(mapGoogleSignInError(e))
        }
    }
}

fun signOutUser(
    credentialManager: CredentialManager,
    scope: CoroutineScope,
    onSignOutComplete: () -> Unit = {}
) {
    scope.launch {
        withContext(NonCancellable) {
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear credential state: ${e.message}")
            } finally {
                Firebase.auth.signOut()
                onSignOutComplete()
            }
        }
    }
}
