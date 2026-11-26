package com.example.core

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"

private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"
internal const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"

internal fun usesDebugAppCheck(debugBuild: Boolean, debugToken: String?): Boolean =
    debugBuild && !debugToken.isNullOrBlank()

fun configureAppCheck(context: Context, intent: Intent?): Boolean {
    val firebaseApp = try {
        FirebaseApp.initializeApp(context)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize the default Firebase app", e)
        return false
    }
    if (firebaseApp == null) {
        Log.e(TAG, "Default Firebase app configuration is missing")
        return false
    }

    try {
        if (usesDebugAppCheck(BuildConfig.DEBUG, intent?.getStringExtra(DEBUG_TOKEN_EXTRA))) {
            // Implementasi ada di src/debug; build rilis memakai stub di src/release
            // sehingga DebugAppCheckProviderFactory tidak ikut ke APK rilis.
            installDebugAppCheckProvider(context, intent)
        } else {
            val resId = context.resources.getIdentifier(
                RECAPTCHA_SITE_KEY_RES,
                "string",
                context.packageName
            )
            if (resId != 0) {
                val siteKey = context.getString(resId)
                if (siteKey.isNotBlank()) {
                    FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                        RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                    )
                    Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
                } else {
                    Log.e(TAG, "recaptcha_site_key resource string is blank.")
                }
            } else {
                Log.e(TAG, "recaptcha_site_key string resource not found in package.")
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
    return true
}
