package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.ui.screens.AuthScreen
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp")
class AuthScreenRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("notulen_auth_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun loginSuccess_trimsEmail_callsAuthSuccess_andNeverStoresPassword() {
        var signedInEmail: String? = null
        var signedInPassword: String? = null
        var authSuccessCount = 0

        composeRule.setContent {
            AuthScreen(
                onAuthSuccess = { authSuccessCount++ },
                enableAutoSignIn = false,
                onEmailSignIn = { email, password ->
                    signedInEmail = email
                    signedInPassword = password
                }
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Email").assertIsDisplayed()
        composeRule.onNodeWithText("Masukkan email").assertIsDisplayed()

        composeRule.onNodeWithTag("login_identifier_input").performScrollTo().performTextInput("  budi@rapat.id ")
        composeRule.onNodeWithTag("login_password_input").performScrollTo().performTextInput("Rahasia123")
        composeRule.onNodeWithTag("login_submit_button").performScrollTo().performClick()

        composeRule.waitForIdle()
        assertEquals("budi@rapat.id", signedInEmail)
        assertEquals("Rahasia123", signedInPassword)
        assertEquals(1, authSuccessCount)

        val prefs = context.getSharedPreferences("notulen_auth_prefs", Context.MODE_PRIVATE).all
        assertEquals("budi@rapat.id", prefs["remembered_email"])
        assertFalse(prefs.values.any { it.toString().contains("Rahasia123") })
    }

    @Test
    fun loginWrongPassword_showsIndonesianError_andAllowsRetry() {
        var attempt = 0
        var authSuccessCount = 0

        composeRule.setContent {
            AuthScreen(
                onAuthSuccess = { authSuccessCount++ },
                enableAutoSignIn = false,
                onEmailSignIn = { _, _ ->
                    attempt++
                    if (attempt == 1) {
                        throw FirebaseAuthInvalidCredentialsException("ERROR_INVALID_CREDENTIAL", "wrong")
                    }
                }
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("login_identifier_input").performScrollTo().performTextInput("budi@rapat.id")
        composeRule.onNodeWithTag("login_password_input").performScrollTo().performTextInput("Salah1234")
        composeRule.onNodeWithTag("login_submit_button").performScrollTo().performClick()

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Email atau password salah.").performScrollTo().assertIsDisplayed()
        assertEquals(0, authSuccessCount)

        // Retry with corrected password
        composeRule.onNodeWithTag("login_password_input").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("login_password_input").performScrollTo().performTextInput("Benar1234")
        composeRule.onNodeWithTag("login_submit_button").performScrollTo().performClick()

        composeRule.waitForIdle()
        assertEquals(2, attempt)
        assertEquals(1, authSuccessCount)
    }

    @Test
    fun loginNetworkError_showsDistinctNetworkMessage() {
        composeRule.setContent {
            AuthScreen(
                onAuthSuccess = {},
                enableAutoSignIn = false,
                onEmailSignIn = { _, _ ->
                    throw FirebaseNetworkException("offline")
                }
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("login_identifier_input").performScrollTo().performTextInput("budi@rapat.id")
        composeRule.onNodeWithTag("login_password_input").performScrollTo().performTextInput("Rahasia123")
        composeRule.onNodeWithTag("login_submit_button").performScrollTo().performClick()

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Koneksi internet bermasalah. Periksa jaringan Anda lalu coba lagi.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun loginWhileLoading_preventsDuplicateSubmission() {
        val gate = CompletableDeferred<Unit>()
        var calls = 0

        composeRule.setContent {
            AuthScreen(
                onAuthSuccess = {},
                enableAutoSignIn = false,
                onEmailSignIn = { _, _ ->
                    calls++
                    gate.await()
                }
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("login_identifier_input").performScrollTo().performTextInput("budi@rapat.id")
        composeRule.onNodeWithTag("login_password_input").performScrollTo().performTextInput("Rahasia123")
        composeRule.onNodeWithTag("login_submit_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("login_submit_button").performScrollTo().performClick()

        composeRule.waitForIdle()
        assertEquals(1, calls)
        gate.complete(Unit)
        composeRule.waitForIdle()
    }

    @Test
    fun registerTab_hasNoUsernameField_validatesConfirmPassword_andSupportsSignUp() {
        var registeredEmail: String? = null
        var authSuccessCount = 0

        composeRule.setContent {
            AuthScreen(
                onAuthSuccess = { authSuccessCount++ },
                enableAutoSignIn = false,
                onEmailSignUp = { email, _ ->
                    registeredEmail = email
                }
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("auth_tab_register").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertTrue(composeRule.onAllNodesWithText("Username").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithTag("register_email_input").performScrollTo().performTextInput("baru@rapat.id")
        composeRule.onNodeWithTag("register_password_input").performScrollTo().performTextInput("Rahasia123")
        composeRule.onNodeWithTag("register_confirm_input").performScrollTo().performTextInput("TidakCocok")
        composeRule.onNodeWithTag("register_submit_button").performScrollTo().performClick()

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Konfirmasi password tidak cocok.").performScrollTo().assertIsDisplayed()
        assertEquals(null, registeredEmail)

        // Fix confirm password and retry
        composeRule.onNodeWithTag("register_confirm_input").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("register_confirm_input").performScrollTo().performTextInput("Rahasia123")
        composeRule.onNodeWithTag("register_submit_button").performScrollTo().performClick()

        composeRule.waitForIdle()
        assertEquals("baru@rapat.id", registeredEmail)
        assertEquals(1, authSuccessCount)
    }

    @Test
    fun registerEmailCollision_guidesUserToLoginOrForgotPassword() {
        composeRule.setContent {
            AuthScreen(
                onAuthSuccess = {},
                enableAutoSignIn = false,
                onEmailSignUp = { _, _ ->
                    throw FirebaseAuthUserCollisionException("ERROR_EMAIL_ALREADY_IN_USE", "used")
                }
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("auth_tab_register").performScrollTo().performClick()
        composeRule.onNodeWithTag("register_email_input").performScrollTo().performTextInput("terdaftar@rapat.id")
        composeRule.onNodeWithTag("register_password_input").performScrollTo().performTextInput("Rahasia123")
        composeRule.onNodeWithTag("register_confirm_input").performScrollTo().performTextInput("Rahasia123")
        composeRule.onNodeWithTag("register_submit_button").performScrollTo().performClick()

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Email ini sudah terdaftar. Silakan masuk atau atur ulang password.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("collision_to_login_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("collision_to_forgot_button").performScrollTo().assertIsDisplayed()

        // Navigate directly to Masuk tab with email prefilled
        composeRule.onNodeWithTag("collision_to_login_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("login_submit_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("terdaftar@rapat.id").performScrollTo().assertIsDisplayed()
    }
}
