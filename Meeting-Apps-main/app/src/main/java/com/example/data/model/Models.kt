package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val username: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val activeTenantId: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "userId" to userId,
        "displayName" to displayName.ifBlank { "Pengguna" }.take(100),
        "username" to username.ifBlank { null },
        "email" to email.take(200),
        "photoUrl" to photoUrl?.take(500),
        "activeTenantId" to activeTenantId?.take(128),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null }.mapValues { it.value!! }
}

@Keep
@IgnoreExtraProperties
data class Tenant(
    @DocumentId val id: String = "",
    val name: String = "",
    val slug: String = "",
    val ownerId: String = "",
    val memberUids: List<String> = emptyList(),
    val adminUids: List<String> = emptyList(),
    val aiMode: String = "platform",
    val watermarkEnabled: Boolean = true,
    val maxFileMb: Long = 200L,
    val retentionDays: Long = 90L,
    val monthlyBudgetUsd: Double = 50.0,
    val status: String = "active",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "name" to name.ifBlank { "Organisasi" }.take(100),
        "slug" to slug.ifBlank { "org" }.take(120),
        "ownerId" to ownerId,
        "memberUids" to memberUids,
        "adminUids" to adminUids,
        "aiMode" to aiMode,
        "watermarkEnabled" to watermarkEnabled,
        "maxFileMb" to maxFileMb,
        "retentionDays" to retentionDays,
        "monthlyBudgetUsd" to monthlyBudgetUsd,
        "status" to status,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class TenantMember(
    @DocumentId val id: String = "",
    val userId: String = "",
    val tenantId: String = "",
    val displayName: String = "",
    val email: String = "",
    val role: String = "member",
    val memberStatus: String = "active",
    val memberUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "userId" to userId,
        "tenantId" to tenantId,
        "displayName" to displayName.ifBlank { "Anggota" }.take(100),
        "email" to email.take(200),
        "role" to role,
        "memberStatus" to memberStatus,
        "memberUids" to memberUids,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class TranscriptSegment(
    val pembicara: String = "Pembicara 1",
    val mulaiDetik: Long = 0L,
    val selesaiDetik: Long = 0L,
    val teks: String = ""
) {
    fun toMap(): Map<String, Any> = mapOf(
        "pembicara" to pembicara,
        "mulaiDetik" to mulaiDetik,
        "selesaiDetik" to selesaiDetik,
        "teks" to teks
    )
}

@Keep
@IgnoreExtraProperties
data class KeyNumber(
    val label: String = "",
    val nilai: String = ""
) {
    fun toMap(): Map<String, Any> = mapOf(
        "label" to label,
        "nilai" to nilai
    )
}

@Keep
@IgnoreExtraProperties
data class ActionItem(
    val id: String = "",
    val siapa: String = "",
    val tugas: String = "",
    val deadline: String = "",
    val prioritas: String = "sedang",
    val selesai: Boolean = false
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "siapa" to siapa,
        "tugas" to tugas,
        "deadline" to deadline,
        "prioritas" to prioritas,
        "selesai" to selesai
    )
}

@Keep
@IgnoreExtraProperties
data class MeetingAnalysis(
    val topik: List<String> = emptyList(),
    val suasana: String = "",
    val pertanyaanTerbuka: List<String> = emptyList(),
    val ringkasPerTopik: List<String> = emptyList()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "topik" to topik,
        "suasana" to suasana,
        "pertanyaanTerbuka" to pertanyaanTerbuka,
        "ringkasPerTopik" to ringkasPerTopik
    )
}

@Keep
@IgnoreExtraProperties
data class VisualData(
    val judul: String = "",
    val poinUtama: List<String> = emptyList(),
    val keputusan: List<String> = emptyList(),
    val angkaKunci: List<String> = emptyList(),
    val risiko: List<String> = emptyList(),
    val tindakan: List<String> = emptyList()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "judul" to judul,
        "poinUtama" to poinUtama,
        "keputusan" to keputusan,
        "angkaKunci" to angkaKunci,
        "risiko" to risiko,
        "tindakan" to tindakan
    )
}

@Keep
@IgnoreExtraProperties
data class ModelInfo(
    val provider: String = "",
    val model: String = "",
    val generatedAtLabel: String = ""
) {
    fun toMap(): Map<String, Any> = mapOf(
        "provider" to provider,
        "model" to model,
        "generatedAtLabel" to generatedAtLabel
    )
}

@Keep
@IgnoreExtraProperties
data class Meeting(
    @DocumentId val id: String = "",
    val tenantId: String = "",
    val userId: String = "",
    val ownerName: String = "",
    val judul: String = "",
    val status: String = "uploaded",
    val errorMessage: String? = null,
    val durasiDetik: Long = 0L,
    val ukuranBytes: Long = 0L,
    val localAudioPath: String? = null,
    val consentRecordedAt: Timestamp? = null,
    val visibleToUids: List<String> = emptyList(),
    val sharedWithAll: Boolean = false,
    val bahasa: String = "id",
    val transcriptText: String = "",
    val segments: List<TranscriptSegment> = emptyList(),
    val ringkasan: String = "",
    val poinUtama: List<String> = emptyList(),
    val keputusan: List<String> = emptyList(),
    val risiko: List<String> = emptyList(),
    val angkaKunci: List<KeyNumber> = emptyList(),
    val actionItems: List<ActionItem> = emptyList(),
    val analysis: MeetingAnalysis? = null,
    val visualData: VisualData? = null,
    val modelInfo: ModelInfo? = null,
    val isFavorite: Boolean = false,
    val tags: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "tenantId" to tenantId,
        "userId" to userId,
        "ownerName" to ownerName.take(100),
        "judul" to judul.ifBlank { "Rapat Baru" }.take(200),
        "status" to status,
        "errorMessage" to errorMessage?.take(500),
        "durasiDetik" to durasiDetik,
        "ukuranBytes" to ukuranBytes,
        "localAudioPath" to localAudioPath?.take(500),
        "consentRecordedAt" to (consentRecordedAt ?: FieldValue.serverTimestamp()),
        "visibleToUids" to visibleToUids,
        "sharedWithAll" to sharedWithAll,
        "bahasa" to bahasa.ifBlank { "id" }.take(20),
        "transcriptText" to transcriptText.take(300000),
        "segments" to segments.map { it.toMap() },
        "ringkasan" to ringkasan.take(20000),
        "poinUtama" to poinUtama,
        "keputusan" to keputusan,
        "risiko" to risiko,
        "angkaKunci" to angkaKunci.map { it.toMap() },
        "actionItems" to actionItems.map { it.toMap() },
        "analysis" to analysis?.toMap(),
        "visualData" to visualData?.toMap(),
        "modelInfo" to modelInfo?.toMap(),
        "isFavorite" to isFavorite,
        "tags" to tags.take(20),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null }.mapValues { it.value!! }
}

@Keep
@IgnoreExtraProperties
data class QaSource(
    val meetingId: String = "",
    val judulRapat: String = "",
    val waktu: String = ""
) {
    fun toMap(): Map<String, Any> = mapOf(
        "meetingId" to meetingId,
        "judulRapat" to judulRapat,
        "waktu" to waktu
    )
}

@Keep
@IgnoreExtraProperties
data class QaMessage(
    @DocumentId val id: String = "",
    val tenantId: String = "",
    val userId: String = "",
    val scope: String = "all",
    val meetingId: String? = null,
    val role: String = "user",
    val isi: String = "",
    val sumber: List<QaSource> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "tenantId" to tenantId,
        "userId" to userId,
        "scope" to scope,
        "meetingId" to meetingId,
        "role" to role,
        "isi" to isi.take(20000),
        "sumber" to sumber.map { it.toMap() },
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null }.mapValues { it.value!! }
}

@Keep
@IgnoreExtraProperties
data class TaskConfig(
    @DocumentId val id: String = "",
    val tenantId: String = "",
    val task: String = "",
    val providerLabel: String = "SumoPod AI",
    val modelId: String = "glm-5.3-flash",
    val connectionId: String = "sumopod_backend_ai",
    val routingMode: String = "manual",
    val fallbackConnectionId: String = "",
    val fallbackModelId: String = "",
    val timeoutSec: Long = 60L,
    val retryCount: Long = 1L,
    val temperature: Double = 0.2,
    val maxOutputTokens: Long = 4096L,
    val enabled: Boolean = true,
    val systemPromptOverride: String = "",
    val memberUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "tenantId" to tenantId,
        "task" to task,
        "providerLabel" to providerLabel,
        "modelId" to modelId,
        "connectionId" to connectionId.take(80),
        "routingMode" to routingMode,
        "fallbackConnectionId" to fallbackConnectionId.take(80),
        "fallbackModelId" to fallbackModelId.take(80),
        "timeoutSec" to timeoutSec.coerceIn(5L, 300L),
        "retryCount" to retryCount.coerceIn(0L, 5L),
        "temperature" to temperature,
        "maxOutputTokens" to maxOutputTokens,
        "enabled" to enabled,
        "systemPromptOverride" to systemPromptOverride.take(2000),
        "memberUids" to memberUids,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class UsageLog(
    @DocumentId val id: String = "",
    val tenantId: String = "",
    val userId: String = "",
    val meetingId: String? = null,
    val task: String = "",
    val kind: String = "ai",
    val provider: String = "SumoPod AI",
    val modelId: String = "glm-5.3-flash",
    val status: String = "ok",
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val audioSeconds: Long = 0L,
    val latencyMs: Long = 0L,
    val estimatedCostUsd: Double = 0.0,
    val playgroundMode: Boolean = false,
    val adminUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "tenantId" to tenantId,
        "userId" to userId,
        "meetingId" to meetingId,
        "task" to task,
        "kind" to kind,
        "provider" to provider,
        "modelId" to modelId,
        "status" to status,
        "inputTokens" to inputTokens,
        "outputTokens" to outputTokens,
        "audioSeconds" to audioSeconds,
        "latencyMs" to latencyMs,
        "estimatedCostUsd" to estimatedCostUsd,
        "playgroundMode" to playgroundMode,
        "adminUids" to adminUids,
        "createdAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null }.mapValues { it.value!! }
}

@Keep
@IgnoreExtraProperties
data class AuditLog(
    @DocumentId val id: String = "",
    val tenantId: String = "",
    val userId: String = "",
    val actorEmail: String = "",
    val aksi: String = "",
    val target: String = "",
    val detail: String = "",
    val adminUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "tenantId" to tenantId,
        "userId" to userId,
        "actorEmail" to actorEmail.take(200),
        "aksi" to aksi.take(100),
        "target" to target.take(200),
        "detail" to detail.take(500),
        "adminUids" to adminUids,
        "createdAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class ModelPrice(
    @DocumentId val id: String = "",
    val tenantId: String = "",
    val provider: String = "SumoPod AI",
    val modelId: String = "",
    val inputPer1M: Double = 0.0,
    val outputPer1M: Double = 0.0,
    val currency: String = "USD",
    val memberUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "tenantId" to tenantId,
        "provider" to provider,
        "modelId" to modelId,
        "inputPer1M" to inputPer1M,
        "outputPer1M" to outputPer1M,
        "currency" to currency,
        "memberUids" to memberUids,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class TenantInvite(
    @DocumentId val id: String = "",
    val tenantId: String = "",
    val email: String = "",
    val role: String = "member",
    val token: String = "",
    val inviteStatus: String = "pending",
    val invitedBy: String = "",
    val adminUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "tenantId" to tenantId,
        "email" to email.take(200),
        "role" to role,
        "token" to token,
        "inviteStatus" to inviteStatus,
        "invitedBy" to invitedBy,
        "adminUids" to adminUids,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}
