package com.example.data.model

import com.example.core.AppConstants

data class ProviderPreset(
    val type: String,
    val kind: String, // "ai" | "stt"
    val label: String,
    val defaultBaseUrl: String,
    val supportsFetchModels: Boolean,
    val supportsDiarization: Boolean,
    val requiresBaseUrl: Boolean,
    val defaultModels: List<String>
)

data class AiConnection(
    val id: String,
    val kind: String = "ai", // "ai" | "stt"
    val name: String,
    val providerType: String, // sumopod_backend | sumopod_backend_stt | openai | gemini | anthropic | deepseek | openrouter | sumopod | custom | whisper | google_stt | deepgram | assemblyai | custom_stt
    val baseUrl: String = "",
    val keyMask: String = "", // e.g. "••••••••abcd"
    val defaultModel: String = "",
    val models: List<String> = emptyList(),
    val enabled: Boolean = true,
    val status: String = "untested", // untested | ok | error | disabled
    val lastTestedAtMs: Long = 0L,
    val lastLatencyMs: Long = 0L,
    val lastError: String = "",
    val isBuiltIn: Boolean = false,
    // STT specific settings
    val sttLanguage: String = "id",
    val sttDiarization: Boolean = true,
    val chunkSizeSec: Int = 600,
    val timeoutSec: Int = 90,
    val retryCount: Int = 1
) {
    val allAvailableModels: List<String>
        get() = (listOf(defaultModel) + models).map { it.trim() }.filter { it.isNotBlank() }.distinct()
}

object ProviderPresets {
    // ID konektor bawaan yang diarahkan ke backend Supabase meeting-api (SumoPod AI)
    const val BUILT_IN_AI_ID = "sumopod_backend_ai"
    const val BUILT_IN_STT_ID = "sumopod_backend_stt_builtin"

    val AI_PRESETS = listOf(
        ProviderPreset(
            type = "sumopod",
            kind = "ai",
            label = "SumoPod (OpenAI-Compatible)",
            defaultBaseUrl = "https://ai.sumopod.com/v1",
            supportsFetchModels = true,
            supportsDiarization = false,
            requiresBaseUrl = true,
            defaultModels = listOf(
                AppConstants.DEFAULT_MODEL_ID,
                AppConstants.FAST_MODEL_ID,
                AppConstants.PRO_MODEL_ID
            )
        ),
        ProviderPreset(
            type = "openai",
            kind = "ai",
            label = "OpenAI",
            defaultBaseUrl = "https://api.openai.com/v1",
            supportsFetchModels = true,
            supportsDiarization = false,
            requiresBaseUrl = false,
            defaultModels = listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini")
        ),
        ProviderPreset(
            type = "gemini",
            kind = "ai",
            label = "Google Gemini (API)",
            defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
            supportsFetchModels = true,
            supportsDiarization = false,
            requiresBaseUrl = false,
            defaultModels = listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash")
        ),
        ProviderPreset(
            type = "anthropic",
            kind = "ai",
            label = "Anthropic Claude",
            defaultBaseUrl = "https://api.anthropic.com/v1",
            supportsFetchModels = false,
            supportsDiarization = false,
            requiresBaseUrl = false,
            defaultModels = listOf("claude-3-5-sonnet-latest", "claude-3-5-haiku-latest")
        ),
        ProviderPreset(
            type = "deepseek",
            kind = "ai",
            label = "DeepSeek",
            defaultBaseUrl = "https://api.deepseek.com/v1",
            supportsFetchModels = true,
            supportsDiarization = false,
            requiresBaseUrl = false,
            defaultModels = listOf("deepseek-chat", "deepseek-reasoner")
        ),
        ProviderPreset(
            type = "openrouter",
            kind = "ai",
            label = "OpenRouter",
            defaultBaseUrl = "https://openrouter.ai/api/v1",
            supportsFetchModels = true,
            supportsDiarization = false,
            requiresBaseUrl = false,
            defaultModels = listOf("google/gemini-2.5-flash", "openai/gpt-4o-mini", "anthropic/claude-3.5-sonnet")
        ),
        ProviderPreset(
            type = "custom",
            kind = "ai",
            label = "Custom OpenAI-Compatible",
            defaultBaseUrl = "",
            supportsFetchModels = true,
            supportsDiarization = false,
            requiresBaseUrl = true,
            defaultModels = emptyList()
        )
    )

    val STT_PRESETS = listOf(
        ProviderPreset(
            type = "whisper",
            kind = "stt",
            label = "OpenAI Whisper",
            defaultBaseUrl = "https://api.openai.com/v1",
            supportsFetchModels = true,
            supportsDiarization = false,
            requiresBaseUrl = false,
            defaultModels = listOf("whisper-1", "gpt-4o-mini-transcribe")
        ),
        ProviderPreset(
            type = "google_stt",
            kind = "stt",
            label = "Google Gemini Audio / STT",
            defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
            supportsFetchModels = true,
            supportsDiarization = true,
            requiresBaseUrl = false,
            defaultModels = listOf("gemini-2.5-flash", "gemini-2.5-pro")
        ),
        ProviderPreset(
            type = "deepgram",
            kind = "stt",
            label = "Deepgram",
            defaultBaseUrl = "https://api.deepgram.com/v1",
            supportsFetchModels = false,
            supportsDiarization = true,
            requiresBaseUrl = false,
            defaultModels = listOf("nova-2", "nova-3")
        ),
        ProviderPreset(
            type = "assemblyai",
            kind = "stt",
            label = "AssemblyAI",
            defaultBaseUrl = "https://api.assemblyai.com/v2",
            supportsFetchModels = false,
            supportsDiarization = true,
            requiresBaseUrl = false,
            defaultModels = listOf("best", "nano")
        ),
        ProviderPreset(
            type = "custom_stt",
            kind = "stt",
            label = "Custom Whisper-Compatible",
            defaultBaseUrl = "",
            supportsFetchModels = true,
            supportsDiarization = true,
            requiresBaseUrl = true,
            defaultModels = listOf("whisper-1")
        )
    )

    fun presetFor(type: String): ProviderPreset? =
        (AI_PRESETS + STT_PRESETS).firstOrNull { it.type == type }

    fun builtInAiConnection(): AiConnection = AiConnection(
        id = BUILT_IN_AI_ID,
        kind = "ai",
        name = "SumoPod AI (Backend NotulenKu)",
        providerType = "sumopod_backend",
        baseUrl = AppConstants.DEFAULT_MEETING_BACKEND_URL,
        keyMask = "Secret Backend (SUMOPOD_KEY)",
        defaultModel = AppConstants.DEFAULT_MODEL_ID,
        models = listOf(
            AppConstants.DEFAULT_MODEL_ID,
            AppConstants.FAST_MODEL_ID,
            AppConstants.PRO_MODEL_ID
        ),
        enabled = true,
        status = "ok",
        isBuiltIn = true
    )

    fun builtInSttConnection(): AiConnection = AiConnection(
        id = BUILT_IN_STT_ID,
        kind = "stt",
        name = "SumoPod Audio (Backend NotulenKu)",
        providerType = "sumopod_backend_stt",
        baseUrl = AppConstants.DEFAULT_MEETING_BACKEND_URL,
        keyMask = "Secret Backend (SUMOPOD_KEY)",
        defaultModel = AppConstants.STT_MODEL_ID,
        models = listOf(AppConstants.STT_MODEL_ID),
        enabled = true,
        status = "ok",
        isBuiltIn = true,
        sttLanguage = "id",
        sttDiarization = true,
        chunkSizeSec = 600,
        timeoutSec = 110
    )
}
