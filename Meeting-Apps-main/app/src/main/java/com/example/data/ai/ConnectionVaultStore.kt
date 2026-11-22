package com.example.data.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.data.model.AiConnection
import com.example.data.model.ProviderPresets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Local device-encrypted store for custom AI and STT provider connections.
 * API keys are encrypted with Android Keystore AES/GCM and never written to Firestore, URLs, or logs.
 */
class ConnectionVaultStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _connections = MutableStateFlow(loadAllConnections())
    val connections: StateFlow<List<AiConnection>> = _connections.asStateFlow()

    fun maskKey(rawKey: String): String {
        val clean = rawKey.trim()
        if (clean.isBlank()) return ""
        val suffix = if (clean.length >= 4) clean.takeLast(4) else clean
        return "••••••••$suffix"
    }

    fun upsertConnection(connection: AiConnection, newApiKey: String? = null): AiConnection {
        val cleanId = connection.id.ifBlank { "conn_${UUID.randomUUID().toString().replace("-", "").take(10)}" }
        val normalizedKey = newApiKey?.trim()?.takeIf { it.isNotBlank() }
        val finalMask = when {
            connection.isBuiltIn -> connection.keyMask
            normalizedKey != null -> {
                saveEncryptedKey(cleanId, normalizedKey)
                maskKey(normalizedKey)
            }
            else -> connection.keyMask
        }
        val cleanModels = connection.models.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val finalDefault = connection.defaultModel.trim().ifBlank { cleanModels.firstOrNull().orEmpty() }
        val stored = connection.copy(
            id = cleanId,
            keyMask = finalMask,
            defaultModel = finalDefault,
            models = (listOf(finalDefault) + cleanModels).filter { it.isNotBlank() }.distinct()
        )
        val current = loadCustomConnections().toMutableList()
        val idx = current.indexOfFirst { it.id == cleanId }
        if (idx >= 0) current[idx] = stored else current.add(stored)
        saveCustomConnections(current)
        _connections.value = loadAllConnections()
        return stored
    }

    fun toggleConnection(connectionId: String) {
        if (connectionId == ProviderPresets.BUILT_IN_AI_ID || connectionId == ProviderPresets.BUILT_IN_STT_ID) return
        val current = loadCustomConnections().map { c ->
            if (c.id == connectionId) {
                val nextEnabled = !c.enabled
                c.copy(
                    enabled = nextEnabled,
                    status = if (!nextEnabled) "disabled" else if (c.lastTestedAtMs > 0L) "ok" else "untested"
                )
            } else c
        }
        saveCustomConnections(current)
        _connections.value = loadAllConnections()
    }

    fun updateTestResult(connectionId: String, ok: Boolean, latencyMs: Long, errorMsg: String = "") {
        if (connectionId == ProviderPresets.BUILT_IN_AI_ID || connectionId == ProviderPresets.BUILT_IN_STT_ID) return
        val current = loadCustomConnections().map { c ->
            if (c.id == connectionId) {
                c.copy(
                    status = if (!c.enabled) "disabled" else if (ok) "ok" else "error",
                    lastTestedAtMs = System.currentTimeMillis(),
                    lastLatencyMs = latencyMs,
                    lastError = if (ok) "" else errorMsg.take(200)
                )
            } else c
        }
        saveCustomConnections(current)
        _connections.value = loadAllConnections()
    }

    fun deleteConnection(connectionId: String) {
        if (connectionId == ProviderPresets.BUILT_IN_AI_ID || connectionId == ProviderPresets.BUILT_IN_STT_ID) return
        val current = loadCustomConnections().filterNot { it.id == connectionId }
        saveCustomConnections(current)
        prefs.edit().remove(KEY_PREFIX + connectionId).apply()
        _connections.value = loadAllConnections()
    }

    fun getDecryptedApiKey(connectionId: String): String {
        val encoded = prefs.getString(KEY_PREFIX + connectionId, null) ?: return ""
        return runCatching {
            val parts = encoded.split(":", limit = 2)
            if (parts.size != 2) return@runCatching ""
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun getConnection(connectionId: String): AiConnection? =
        _connections.value.firstOrNull { it.id == connectionId }

    private fun loadAllConnections(): List<AiConnection> {
        val custom = loadCustomConnections()
        return listOf(
            ProviderPresets.builtInAiConnection(),
            ProviderPresets.builtInSttConnection()
        ) + custom
    }

    private fun loadCustomConnections(): List<AiConnection> {
        val raw = prefs.getString(KEY_META_LIST, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val modelsArr = o.optJSONArray("models")
                    val modelsList = buildList {
                        if (modelsArr != null) {
                            for (j in 0 until modelsArr.length()) {
                                val m = modelsArr.optString(j).trim()
                                if (m.isNotBlank()) add(m)
                            }
                        }
                    }
                    add(
                        AiConnection(
                            id = o.optString("id"),
                            kind = o.optString("kind", "ai"),
                            name = o.optString("name"),
                            providerType = o.optString("providerType", "custom"),
                            baseUrl = o.optString("baseUrl"),
                            keyMask = o.optString("keyMask"),
                            defaultModel = o.optString("defaultModel"),
                            models = modelsList,
                            enabled = o.optBoolean("enabled", true),
                            status = o.optString("status", "untested"),
                            lastTestedAtMs = o.optLong("lastTestedAtMs", 0L),
                            lastLatencyMs = o.optLong("lastLatencyMs", 0L),
                            lastError = o.optString("lastError"),
                            isBuiltIn = false,
                            sttLanguage = o.optString("sttLanguage", "id"),
                            sttDiarization = o.optBoolean("sttDiarization", true),
                            chunkSizeSec = o.optInt("chunkSizeSec", 300),
                            timeoutSec = o.optInt("timeoutSec", 60),
                            retryCount = o.optInt("retryCount", 1)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveCustomConnections(list: List<AiConnection>) {
        val arr = JSONArray()
        list.filterNot { it.isBuiltIn }.forEach { c ->
            val o = JSONObject().apply {
                put("id", c.id)
                put("kind", c.kind)
                put("name", c.name)
                put("providerType", c.providerType)
                put("baseUrl", c.baseUrl)
                put("keyMask", c.keyMask)
                put("defaultModel", c.defaultModel)
                put("models", JSONArray(c.models))
                put("enabled", c.enabled)
                put("status", c.status)
                put("lastTestedAtMs", c.lastTestedAtMs)
                put("lastLatencyMs", c.lastLatencyMs)
                put("lastError", c.lastError)
                put("sttLanguage", c.sttLanguage)
                put("sttDiarization", c.sttDiarization)
                put("chunkSizeSec", c.chunkSizeSec)
                put("timeoutSec", c.timeoutSec)
                put("retryCount", c.retryCount)
            }
            arr.put(o)
        }
        prefs.edit().putString(KEY_META_LIST, arr.toString()).apply()
    }

    private fun saveEncryptedKey(connectionId: String, plainKey: String) {
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val encrypted = cipher.doFinal(plainKey.toByteArray(Charsets.UTF_8))
            val ivB64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            val dataB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            prefs.edit().putString(KEY_PREFIX + connectionId, "$ivB64:$dataB64").apply()
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "notulen_connection_vault"
        const val KEY_META_LIST = "connections_meta_v1"
        const val KEY_PREFIX = "enc_key_"
        const val KEYSTORE_ALIAS = "NotulenAiConnectionVaultKey"
    }
}
