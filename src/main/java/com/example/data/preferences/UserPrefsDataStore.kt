package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.userPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "notulen_prefs")

class UserPrefsDataStore(private val context: Context) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode") // system | light | dark
        val ACTIVE_TENANT_ID = stringPreferencesKey("active_tenant_id")
        val DEFAULT_TRANSCRIPT_LANG = stringPreferencesKey("default_transcript_lang") // id | en | auto
        val DEFAULT_EXPORT_FORMAT = stringPreferencesKey("default_export_format") // PDF | TXT | PNG | SRT
        val DEFAULT_VISUAL_STYLE = stringPreferencesKey("default_visual_style") // CARDS | MIND_MAP | TIMELINE
        val DEFAULT_HISTORY_VIEW = stringPreferencesKey("default_history_view") // list | card
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val CUSTOM_AVATAR_PATH = stringPreferencesKey("custom_avatar_path")
    }

    val themeMode: Flow<String> = context.userPrefsStore.data.map { it[Keys.THEME_MODE] ?: "system" }
    val savedTenantId: Flow<String?> = context.userPrefsStore.data.map { it[Keys.ACTIVE_TENANT_ID] }
    val defaultTranscriptLang: Flow<String> = context.userPrefsStore.data.map { it[Keys.DEFAULT_TRANSCRIPT_LANG] ?: "id" }
    val defaultExportFormat: Flow<String> = context.userPrefsStore.data.map { it[Keys.DEFAULT_EXPORT_FORMAT] ?: "PDF" }
    val defaultVisualStyle: Flow<String> = context.userPrefsStore.data.map { it[Keys.DEFAULT_VISUAL_STYLE] ?: "CARDS" }
    val defaultHistoryView: Flow<String> = context.userPrefsStore.data.map { it[Keys.DEFAULT_HISTORY_VIEW] ?: "list" }
    val reduceMotion: Flow<Boolean> = context.userPrefsStore.data.map { it[Keys.REDUCE_MOTION] ?: false }
    val customAvatarPath: Flow<String?> = context.userPrefsStore.data.map { it[Keys.CUSTOM_AVATAR_PATH] }

    suspend fun setThemeMode(mode: String) {
        context.userPrefsStore.edit { it[Keys.THEME_MODE] = mode }
    }

    suspend fun setSavedTenantId(tenantId: String?) {
        context.userPrefsStore.edit {
            if (tenantId == null) it.remove(Keys.ACTIVE_TENANT_ID)
            else it[Keys.ACTIVE_TENANT_ID] = tenantId
        }
    }

    suspend fun setDefaultTranscriptLang(lang: String) {
        context.userPrefsStore.edit { it[Keys.DEFAULT_TRANSCRIPT_LANG] = lang }
    }

    suspend fun setDefaultExportFormat(fmt: String) {
        context.userPrefsStore.edit { it[Keys.DEFAULT_EXPORT_FORMAT] = fmt }
    }

    suspend fun setDefaultVisualStyle(style: String) {
        context.userPrefsStore.edit { it[Keys.DEFAULT_VISUAL_STYLE] = style }
    }

    suspend fun setDefaultHistoryView(view: String) {
        context.userPrefsStore.edit { it[Keys.DEFAULT_HISTORY_VIEW] = view }
    }

    suspend fun setReduceMotion(reduce: Boolean) {
        context.userPrefsStore.edit { it[Keys.REDUCE_MOTION] = reduce }
    }

    suspend fun setCustomAvatarPath(path: String?) {
        context.userPrefsStore.edit {
            if (path.isNullOrBlank()) it.remove(Keys.CUSTOM_AVATAR_PATH)
            else it[Keys.CUSTOM_AVATAR_PATH] = path
        }
    }
}
