package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.configureAppCheck
import com.example.data.preferences.UserPrefsDataStore
import com.example.ui.NotulenAppRoot
import com.example.ui.theme.NotulenTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        enableEdgeToEdge()

        setContent {
            val prefs = remember { UserPrefsDataStore(applicationContext) }
            val themeMode by prefs.themeMode.collectAsStateWithLifecycle(initialValue = "system")
            val darkTheme = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            NotulenTheme(darkTheme = darkTheme) {
                NotulenAppRoot(prefs = prefs)
            }
        }
    }
}
