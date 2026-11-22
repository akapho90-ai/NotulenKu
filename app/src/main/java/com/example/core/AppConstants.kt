package com.example.core

object AppConstants {
    const val APP_NAME = "NotulenKu"
    const val APP_SUBTITLE = "Catatan Rapat AI"
    const val PLATFORM_ADMIN_EMAIL = "gamecuan.1901@gmail.com"

    const val SUPABASE_URL = "https://ojmdiiwowiqvcxgexxgr.supabase.co"
    const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_VW5gRzSSXBWyPVPQNZ5cog__axZwCHJ"
    const val DEFAULT_MEETING_BACKEND_URL = "https://ojmdiiwowiqvcxgexxgr.supabase.co/functions/v1/meeting-api"

    const val STT_MODEL_ID = "gemini/gemini-3.5-flash-lite"
    const val DEFAULT_MODEL_ID = "glm-5.3-flash"
    const val FAST_MODEL_ID = "claude-haiku-5-5"
    const val PRO_MODEL_ID = "gpt-6-luna"

    val AVAILABLE_MODELS = listOf(
        ModelOption(
            id = STT_MODEL_ID,
            label = "Gemini 3.5 Flash-Lite (Audio)",
            provider = "SumoPod AI",
            description = "Khusus transkripsi audio rapat melalui backend SumoPod"
        ),
        ModelOption(
            id = DEFAULT_MODEL_ID,
            label = "GLM 5.3 Flash",
            provider = "SumoPod AI",
            description = "Model utama notulen, action items, & analisis rapat"
        ),
        ModelOption(
            id = FAST_MODEL_ID,
            label = "Claude Haiku 5.5",
            provider = "SumoPod AI",
            description = "Ringkasan mingguan/bulanan & cadangan utama notulen"
        ),
        ModelOption(
            id = PRO_MODEL_ID,
            label = "GPT 6 Luna",
            provider = "SumoPod AI",
            description = "Tanya AI lintas rapat, data visual/infografis, & cadangan"
        )
    )

    val AI_TASKS = listOf(
        AiTaskDefinition("audio_transcription", "Transkripsi & Diarization Audio", STT_MODEL_ID),
        AiTaskDefinition("meeting_summary", "Ringkasan & Notulen Rapat", DEFAULT_MODEL_ID),
        AiTaskDefinition("action_item_extraction", "Ekstraksi Action Items", DEFAULT_MODEL_ID),
        AiTaskDefinition("meeting_analysis", "Analisis Topik & Suasana", DEFAULT_MODEL_ID),
        AiTaskDefinition("meeting_qa", "Tanya Jawab Lintas Rapat", PRO_MODEL_ID),
        AiTaskDefinition("periodic_summary", "Ringkasan Mingguan & Bulanan", FAST_MODEL_ID),
        AiTaskDefinition("transcript_correction", "Perbaikan Transkrip AI", DEFAULT_MODEL_ID),
        AiTaskDefinition("structured_visual_data", "Data Infografis Visual", PRO_MODEL_ID)
    )
}

data class ModelOption(
    val id: String,
    val label: String,
    val provider: String,
    val description: String
)

data class AiTaskDefinition(
    val key: String,
    val title: String,
    val defaultModelId: String
)
