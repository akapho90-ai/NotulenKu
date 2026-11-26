package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.core.OperationType
import com.example.core.handleFirestoreError
import com.example.data.model.ActionItem
import com.example.data.model.KeyNumber
import com.example.data.model.Meeting
import com.example.data.model.MeetingAnalysis
import com.example.data.model.ModelInfo
import com.example.data.model.QaMessage
import com.example.data.model.QaSource
import com.example.data.model.Tenant
import com.example.data.model.TranscriptSegment
import com.example.data.model.VisualData
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class MeetingRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("Pengguna belum masuk.")

    fun observeMeetings(tenantId: String, uid: String): Flow<List<Meeting>> =
        db.collection("tenants").document(tenantId).collection("meetings")
            .whereArrayContains("visibleToUids", uid)
            .snapshots()
            .map { snap ->
                snap.toObjects(Meeting::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
            }
            .catch { error ->
                unwrapAndLog(error, OperationType.LIST, "tenants/$tenantId/meetings", uid)
                throw error
            }

    fun observeQaMessages(tenantId: String, uid: String): Flow<List<QaMessage>> =
        db.collection("tenants").document(tenantId).collection("qaMessages")
            .whereEqualTo("userId", uid)
            .snapshots()
            .map { snap ->
                snap.toObjects(QaMessage::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedBy { it.createdAt?.seconds ?: Long.MAX_VALUE }
            }
            .catch { error ->
                unwrapAndLog(error, OperationType.LIST, "tenants/$tenantId/qaMessages", uid)
                throw error
            }

    suspend fun createInitialMeeting(
        tenant: Tenant,
        judul: String,
        durasiDetik: Long,
        ukuranBytes: Long,
        localAudioPath: String?
    ): Result<String> {
        return try {
            val uid = requireUserId()
            val user = auth.currentUser
            val meetingId = "mtg_${UUID.randomUUID().toString().replace("-", "").take(14)}"
            val visibleUids = (listOf(uid) + tenant.adminUids).distinct()
            val meeting = Meeting(
                tenantId = tenant.id,
                userId = uid,
                ownerName = (user?.displayName ?: user?.email?.substringBefore("@") ?: "Anggota").take(100),
                judul = judul.trim().ifBlank { "Rapat Baru" }.take(200),
                status = "transcribing",
                durasiDetik = durasiDetik.coerceAtLeast(0L),
                ukuranBytes = ukuranBytes.coerceAtLeast(0L),
                localAudioPath = localAudioPath,
                visibleToUids = visibleUids,
                sharedWithAll = false,
                bahasa = "id"
            )
            db.collection("tenants").document(tenant.id).collection("meetings")
                .document(meetingId).set(meeting.toCreateMap()).await()
            Result.success(meetingId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "tenants/${tenant.id}/meetings")
            Result.failure(e)
        }
    }

    suspend fun updateMeetingStatus(
        tenantId: String,
        meetingId: String,
        status: String,
        errorMessage: String? = null
    ): Result<Unit> {
        return try {
            val updates = mutableMapOf<String, Any>(
                "status" to status,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (errorMessage != null) {
                updates["errorMessage"] = errorMessage.take(500)
            }
            db.collection("tenants").document(tenantId).collection("meetings")
                .document(meetingId).update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "tenants/$tenantId/meetings/$meetingId")
            Result.failure(e)
        }
    }

    suspend fun saveTranscript(
        tenantId: String,
        meetingId: String,
        bahasa: String,
        segments: List<TranscriptSegment>,
        durasiDetik: Long? = null
    ): Result<Unit> {
        return try {
            val fullText = segments.joinToString("\n") { "[${it.pembicara}] ${it.teks}" }.take(300000)
            val updates = mutableMapOf<String, Any>(
                "bahasa" to bahasa.take(20),
                "segments" to segments.take(1500).map { it.toMap() },
                "transcriptText" to fullText,
                "status" to "summarizing",
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (durasiDetik != null && durasiDetik > 0L) {
                updates["durasiDetik"] = durasiDetik
            }
            db.collection("tenants").document(tenantId).collection("meetings")
                .document(meetingId).update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "tenants/$tenantId/meetings/$meetingId")
            Result.failure(e)
        }
    }

    suspend fun saveSummaryAndActions(
        tenantId: String,
        meetingId: String,
        ringkasan: String,
        poinUtama: List<String>,
        keputusan: List<String>,
        risiko: List<String>,
        angkaKunci: List<KeyNumber>,
        actionItems: List<ActionItem>,
        modelInfo: ModelInfo
    ): Result<Unit> {
        return try {
            db.collection("tenants").document(tenantId).collection("meetings")
                .document(meetingId).update(
                    mapOf(
                        "ringkasan" to ringkasan.take(20000),
                        "poinUtama" to poinUtama.take(100),
                        "keputusan" to keputusan.take(100),
                        "risiko" to risiko.take(100),
                        "angkaKunci" to angkaKunci.take(50).map { it.toMap() },
                        "actionItems" to actionItems.take(200).map { it.toMap() },
                        "modelInfo" to modelInfo.toMap(),
                        "status" to "done",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "tenants/$tenantId/meetings/$meetingId")
            Result.failure(e)
        }
    }

    suspend fun saveAnalysis(
        tenantId: String,
        meetingId: String,
        analysis: MeetingAnalysis
    ): Result<Unit> {
        return try {
            db.collection("tenants").document(tenantId).collection("meetings")
                .document(meetingId).update(
                    mapOf(
                        "analysis" to analysis.toMap(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "tenants/$tenantId/meetings/$meetingId")
            Result.failure(e)
        }
    }

    suspend fun saveVisualData(
        tenantId: String,
        meetingId: String,
        visualData: VisualData
    ): Result<Unit> {
        return try {
            db.collection("tenants").document(tenantId).collection("meetings")
                .document(meetingId).update(
                    mapOf(
                        "visualData" to visualData.toMap(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "tenants/$tenantId/meetings/$meetingId")
            Result.failure(e)
        }
    }

    suspend fun renameMeeting(tenantId: String, meetingId: String, newTitle: String): Result<Unit> {
        val clean = newTitle.trim().take(200)
        if (clean.isBlank()) return Result.failure(IllegalArgumentException("Judul tidak boleh kosong"))
        return try {
            db.collection("tenants").document(tenantId).collection("meetings")
                .document(meetingId).update(
                    mapOf(
                        "judul" to clean,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "tenants/$tenantId/meetings/$meetingId")
            Result.failure(e)
        }
    }

    suspend fun updateActionItems(
        tenantId: String,
        meetingId: String,
        items: List<ActionItem>
    ): Result<Unit> {
        return try {
            db.collection("tenants").document(tenantId).collection("meetings")
                .document(meetingId).update(
                    mapOf(
                        "actionItems" to items.take(200).map { it.toMap() },
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "tenants/$tenantId/meetings/$meetingId")
            Result.failure(e)
        }
    }

    suspend fun updateSegments(
        tenantId: String,
        meetingId: String,
        segments: List<TranscriptSegment>
    ): Result<Unit> {
        return try {
            val fullText = segments.joinToString("\n") { "[${it.pembicara}] ${it.teks}" }.take(300000)
            db.collection("tenants").document(tenantId).collection("meetings")
                .document(meetingId).update(
                    mapOf(
                        "segments" to segments.take(1500).map { it.toMap() },
                        "transcriptText" to fullText,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "tenants/$tenantId/meetings/$meetingId")
            Result.failure(e)
        }
    }

    suspend fun shareMeetingWithTenant(
        tenant: Tenant,
        meeting: Meeting,
        shareAll: Boolean
    ): Result<Unit> {
        return try {
            val newVisible = if (shareAll) {
                tenant.memberUids
            } else {
                (listOf(meeting.userId) + tenant.adminUids).distinct()
            }
            db.collection("tenants").document(tenant.id).collection("meetings")
                .document(meeting.id).update(
                    mapOf(
                        "sharedWithAll" to shareAll,
                        "visibleToUids" to newVisible,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "tenants/${tenant.id}/meetings/${meeting.id}")
            Result.failure(e)
        }
    }

    suspend fun toggleFavorite(tenantId: String, meetingId: String, isFavorite: Boolean): Result<Unit> {
        return try {
            db.collection("tenants").document(tenantId).collection("meetings")
                .document(meetingId).update(
                    mapOf(
                        "isFavorite" to isFavorite,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "tenants/$tenantId/meetings/$meetingId")
            Result.failure(e)
        }
    }

    suspend fun updateTags(tenantId: String, meetingId: String, tags: List<String>): Result<Unit> {
        return try {
            val clean = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(20)
            db.collection("tenants").document(tenantId).collection("meetings")
                .document(meetingId).update(
                    mapOf(
                        "tags" to clean,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "tenants/$tenantId/meetings/$meetingId")
            Result.failure(e)
        }
    }

    suspend fun deleteMeeting(tenantId: String, meeting: Meeting): Result<Unit> {
        return try {
            db.collection("tenants").document(tenantId).collection("meetings")
                .document(meeting.id).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "tenants/$tenantId/meetings/${meeting.id}")
            Result.failure(e)
        }
    }

    suspend fun saveQaMessage(
        tenantId: String,
        scope: String,
        meetingId: String?,
        role: String,
        isi: String,
        sumber: List<QaSource>
    ): Result<Unit> {
        return try {
            val uid = requireUserId()
            val msgId = "qa_${UUID.randomUUID().toString().replace("-", "").take(14)}"
            val msg = QaMessage(
                tenantId = tenantId,
                userId = uid,
                scope = scope,
                meetingId = meetingId,
                role = role,
                isi = isi,
                sumber = sumber
            )
            db.collection("tenants").document(tenantId).collection("qaMessages")
                .document(msgId).set(msg.toCreateMap()).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "tenants/$tenantId/qaMessages")
            Result.failure(e)
        }
    }

    suspend fun clearQaHistory(tenantId: String, messages: List<QaMessage>): Result<Unit> {
        return try {
            val col = db.collection("tenants").document(tenantId).collection("qaMessages")
            messages.forEach { col.document(it.id).delete().await() }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "tenants/$tenantId/qaMessages")
            Result.failure(e)
        }
    }

    private fun unwrapAndLog(error: Throwable, op: OperationType, path: String, uid: String) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) return
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, op, path)
        }
    }
}
