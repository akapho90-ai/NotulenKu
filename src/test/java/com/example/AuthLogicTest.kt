package com.example

import com.example.data.auth.isValidEmailFormat
import com.example.data.auth.mapFirebaseAuthError
import com.example.data.auth.validateLoginInput
import com.example.data.auth.validateRegistrationInput
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthLogicTest {

    @Test
    fun `isValidEmailFormat accepts standard email and rejects invalid strings`() {
        assertTrue(isValidEmailFormat("budi@perusahaan.co.id"))
        assertTrue(isValidEmailFormat("  user.name+1@gmail.com  "))
        assertFalse(isValidEmailFormat(""))
        assertFalse(isValidEmailFormat("budi"))
        assertFalse(isValidEmailFormat("budi@"))
        assertFalse(isValidEmailFormat("@gmail.com"))
        assertFalse(isValidEmailFormat("budi@gmail"))
    }

    @Test
    fun `validateLoginInput enforces email format and non-empty password`() {
        assertEquals("Email dan password wajib diisi.", validateLoginInput("", "rahasia123"))
        assertEquals("Email dan password wajib diisi.", validateLoginInput("user@mail.com", ""))
        assertEquals("Format email tidak valid.", validateLoginInput("bukan-email", "rahasia123"))
        assertNull(validateLoginInput("  user@mail.com ", "rahasia123"))
    }

    @Test
    fun `validateRegistrationInput enforces email, min length 8, and matching confirmation`() {
        assertEquals("Lengkapi semua kolom pendaftaran.", validateRegistrationInput("", "Password1", "Password1"))
        assertEquals("Format email tidak valid.", validateRegistrationInput("user@", "Password1", "Password1"))
        assertEquals("Password minimal 8 karakter.", validateRegistrationInput("user@mail.com", "1234567", "1234567"))
        assertEquals("Konfirmasi password tidak cocok.", validateRegistrationInput("user@mail.com", "Password1", "Password2"))
        assertNull(validateRegistrationInput("user@mail.com", "Password1", "Password1"))
    }

    @Test
    fun `mapFirebaseAuthError returns generic Indonesian credential message and distinct network error`() {
        val wrongCred = mapFirebaseAuthError(
            FirebaseAuthInvalidCredentialsException("ERROR_INVALID_CREDENTIAL", "bad"),
            isRegister = false
        )
        assertEquals("Email atau password salah.", wrongCred.message)
        assertFalse(wrongCred.isNetworkError)

        val missingUser = mapFirebaseAuthError(
            FirebaseAuthInvalidUserException("ERROR_USER_NOT_FOUND", "missing"),
            isRegister = false
        )
        assertEquals("Email atau password salah.", missingUser.message)

        val networkErr = mapFirebaseAuthError(
            FirebaseNetworkException("offline"),
            isRegister = false
        )
        assertTrue(networkErr.isNetworkError)
        assertNotEquals(wrongCred.message, networkErr.message)
        assertTrue(networkErr.message.contains("internet", ignoreCase = true))

        val unknownHost = mapFirebaseAuthError(
            RuntimeException(UnknownHostException("no host")),
            isRegister = false
        )
        assertTrue(unknownHost.isNetworkError)
    }

    @Test
    fun `mapFirebaseAuthError handles email collision, weak password, and rate limit`() {
        val collision = mapFirebaseAuthError(
            FirebaseAuthUserCollisionException("ERROR_EMAIL_ALREADY_IN_USE", "exists"),
            isRegister = true
        )
        assertTrue(collision.isEmailAlreadyInUse)
        assertTrue(collision.message.contains("sudah terdaftar", ignoreCase = true))

        val weakPass = mapFirebaseAuthError(
            FirebaseAuthWeakPasswordException("ERROR_WEAK_PASSWORD", "weak", "6 chars"),
            isRegister = true
        )
        assertTrue(weakPass.message.contains("lemah", ignoreCase = true))

        val rateLimit = mapFirebaseAuthError(
            FirebaseTooManyRequestsException("too many"),
            isRegister = false
        )
        assertTrue(rateLimit.message.contains("Terlalu banyak", ignoreCase = true))
    }
}
