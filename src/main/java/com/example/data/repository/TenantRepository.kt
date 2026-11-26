package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.core.AppConstants
import com.example.core.OperationType
import com.example.core.handleFirestoreError
import com.example.data.model.AuditLog
import com.example.data.model.ModelPrice
import com.example.data.model.ProviderPresets
import com.example.data.model.TaskConfig
import com.example.data.model.Tenant
import com.example.data.model.TenantInvite
import com.example.data.model.TenantMember
import com.example.data.model.UsageLog
import com.example.data.model.UserProfile
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
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

class TenantRepository(
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

    fun observeUserProfile(uid: String): Flow<UserProfile?> =
        db.collection("users").document(uid)
            .snapshots()
            .map { it.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
            .catch { error ->
                unwrapAndLog(error, OperationType.GET, "users/$uid", uid)
                throw error
            }

    fun observeUserTenants(uid: String): Flow<List<Tenant>> =
        db.collection("tenants")
            .whereArrayContains("memberUids", uid)
            .snapshots()
            .map { snap ->
                snap.toObjects(Tenant::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedBy { it.name.lowercase() }
            }
            .catch { error ->
                unwrapAndLog(error, OperationType.LIST, "tenants", uid)
                throw error
            }

    fun observeMembers(tenantId: String, uid: String): Flow<List<TenantMember>> =
        db.collection("tenants").document(tenantId).collection("members")
            .whereArrayContains("memberUids", uid)
            .snapshots()
            .map { snap ->
                snap.toObjects(TenantMember::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                unwrapAndLog(error, OperationType.LIST, "tenants/$tenantId/members", uid)
                throw error
            }

    fun observeTaskConfigs(tenantId: String, uid: String): Flow<List<TaskConfig>> =
        db.collection("tenants").document(tenantId).collection("taskConfigs")
            .whereArrayContains("memberUids", uid)
            .snapshots()
            .map { snap ->
                snap.toObjects(TaskConfig::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                unwrapAndLog(error, OperationType.LIST, "tenants/$tenantId/taskConfigs", uid)
                throw error
            }

    fun observeModelPrices(tenantId: String, uid: String): Flow<List<ModelPrice>> =
        db.collection("tenants").document(tenantId).collection("modelPrices")
            .whereArrayContains("memberUids", uid)
            .snapshots()
            .map { snap ->
                snap.toObjects(ModelPrice::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                unwrapAndLog(error, OperationType.LIST, "tenants/$tenantId/modelPrices", uid)
                throw error
            }

    fun observeUsageLogs(tenantId: String, uid: String, isAdmin: Boolean): Flow<List<UsageLog>> {
        val col = db.collection("tenants").document(tenantId).collection("usageLogs")
        val query = if (isAdmin) col.whereArrayContains("adminUids", uid) else col.whereEqualTo("userId", uid)
        return query.snapshots()
            .map { snap ->
                snap.toObjects(UsageLog::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
            }
            .catch { error ->
                unwrapAndLog(error, OperationType.LIST, "tenants/$tenantId/usageLogs", uid)
                throw error
            }
    }

    fun observeAuditLogs(tenantId: String, uid: String): Flow<List<AuditLog>> =
        db.collection("tenants").document(tenantId).collection("auditLogs")
            .whereArrayContains("adminUids", uid)
            .snapshots()
            .map { snap ->
                snap.toObjects(AuditLog::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
            }
            .catch { error ->
                unwrapAndLog(error, OperationType.LIST, "tenants/$tenantId/auditLogs", uid)
                throw error
            }

    fun observeInvites(tenantId: String, uid: String): Flow<List<TenantInvite>> =
        db.collection("tenants").document(tenantId).collection("invites")
            .whereArrayContains("adminUids", uid)
            .snapshots()
            .map { snap ->
                snap.toObjects(TenantInvite::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                unwrapAndLog(error, OperationType.LIST, "tenants/$tenantId/invites", uid)
                throw error
            }

    suspend fun ensureUserAndPersonalTenant(user: FirebaseUser): Result<String> {
        val uid = user.uid
        return try {
            val userRef = db.collection("users").document(uid)
            val userSnap = userRef.get().await()
            val existingTenants = db.collection("tenants")
                .whereArrayContains("memberUids", uid)
                .get()
                .await()

            val ownedOrJoined = existingTenants.documents
            val savedTenantId = userSnap.getString("activeTenantId")
            val existingTenant = ownedOrJoined.firstOrNull { it.id == savedTenantId }
                ?: ownedOrJoined.firstOrNull()
            var activeTenantId = existingTenant?.id

            if (activeTenantId == null) {
                val emailPrefix = user.email?.substringBefore("@")?.take(40)?.ifBlank { "Pribadi" } ?: "Pribadi"
                val orgName = "Workspace $emailPrefix"
                val tenantId = "org_${uid.take(10)}_${UUID.randomUUID().toString().take(6)}"
                createTenantInternal(
                    tenantId = tenantId,
                    name = orgName,
                    ownerUid = uid,
                    ownerName = user.displayName ?: emailPrefix,
                    ownerEmail = user.email.orEmpty()
                )
                activeTenantId = tenantId
            }

            val generatedUsername = buildSafeUsername(user.email, uid)
            if (!userSnap.exists()) {
                val profile = UserProfile(
                    userId = uid,
                    displayName = user.displayName ?: user.email?.substringBefore("@") ?: "Pengguna",
                    username = generatedUsername,
                    email = user.email.orEmpty(),
                    photoUrl = user.photoUrl?.toString(),
                    activeTenantId = activeTenantId
                )
                userRef.set(profile.toCreateMap()).await()
            } else {
                val updates = mutableMapOf<String, Any>()
                if (userSnap.getString("activeTenantId").isNullOrBlank()) {
                    updates["activeTenantId"] = activeTenantId
                }
                if (userSnap.getString("username").isNullOrBlank()) {
                    updates["username"] = generatedUsername
                }
                if (updates.isNotEmpty()) {
                    updates["updatedAt"] = FieldValue.serverTimestamp()
                    userRef.update(updates).await()
                }
            }

            if (existingTenant?.getString("ownerId") == uid) {
                ensureOwnerDefaults(
                    tenantId = activeTenantId,
                    ownerUid = uid,
                    ownerName = user.displayName ?: "Pemilik",
                    ownerEmail = user.email.orEmpty()
                )
            }
            Result.success(activeTenantId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "users/$uid")
            Result.failure(e)
        }
    }

    private fun buildSafeUsername(email: String?, uid: String): String {
        val base = (email?.substringBefore("@") ?: "user")
            .lowercase()
            .replace(Regex("[^a-z0-9._]"), "")
            .take(15)
            .ifBlank { "user" }
        val suffix = uid.lowercase().replace(Regex("[^a-z0-9]"), "").take(4).ifBlank { "01" }
        val combined = "${base}_$suffix".take(20)
        return if (combined.length >= 3) combined else "usr_$suffix".take(20)
    }

    suspend fun createOrganization(name: String): Result<String> {
        return try {
            val uid = requireUserId()
            val user = auth.currentUser
            val cleanName = name.trim().take(100)
            require(cleanName.isNotBlank()) { "Nama organisasi tidak boleh kosong." }
            val tenantId = "org_${UUID.randomUUID().toString().replace("-", "").take(16)}"
            createTenantInternal(
                tenantId = tenantId,
                name = cleanName,
                ownerUid = uid,
                ownerName = user?.displayName ?: "Pemilik",
                ownerEmail = user?.email.orEmpty()
            )
            setActiveTenant(tenantId)
            Result.success(tenantId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "tenants")
            Result.failure(e)
        }
    }

    private suspend fun createTenantInternal(
        tenantId: String,
        name: String,
        ownerUid: String,
        ownerName: String,
        ownerEmail: String
    ) {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "org" } +
            "-${tenantId.takeLast(4)}"
        val tenant = Tenant(
            name = name,
            slug = slug.take(120),
            ownerId = ownerUid,
            memberUids = listOf(ownerUid),
            adminUids = listOf(ownerUid),
            aiMode = "platform",
            watermarkEnabled = true,
            maxFileMb = 200L,
            retentionDays = 90L,
            monthlyBudgetUsd = 50.0,
            status = "active"
        )
        db.collection("tenants").document(tenantId).set(tenant.toCreateMap()).await()

        ensureOwnerDefaults(tenantId, ownerUid, ownerName, ownerEmail)
    }

    private suspend fun ensureOwnerDefaults(
        tenantId: String,
        ownerUid: String,
        ownerName: String,
        ownerEmail: String
    ) {
        val tenantRef = db.collection("tenants").document(tenantId)
        val memberRef = tenantRef.collection("members").document(ownerUid)
        val configs = tenantRef.collection("taskConfigs")
        db.runTransaction { transaction ->
            val tenant = transaction.get(tenantRef).toObject(Tenant::class.java)
            check(tenant != null && tenant.ownerId == ownerUid) { "Workspace pemilik tidak tersedia." }
            val missingOwner = !transaction.get(memberRef).exists()
            val missingTasks = AppConstants.AI_TASKS.filter { task ->
                !transaction.get(configs.document(task.key)).exists()
            }

            if (missingOwner) {
                val member = TenantMember(
                    userId = ownerUid,
                    tenantId = tenantId,
                    displayName = ownerName.ifBlank { "Pemilik" },
                    email = ownerEmail,
                    role = "owner",
                    memberStatus = "active",
                    memberUids = tenant.memberUids
                )
                transaction.set(memberRef, member.toCreateMap())
            }
            missingTasks.forEach { task ->
                val config = TaskConfig(
                    tenantId = tenantId,
                    task = task.key,
                    providerLabel = "SumoPod AI",
                    modelId = task.defaultModelId,
                    connectionId = if (task.key == "audio_transcription") ProviderPresets.BUILT_IN_STT_ID
                    else ProviderPresets.BUILT_IN_AI_ID,
                    memberUids = tenant.memberUids
                )
                transaction.set(configs.document(task.key), config.toCreateMap())
            }
        }.await()
    }

    suspend fun setActiveTenant(tenantId: String): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Belum masuk"))
        return try {
            db.collection("users").document(uid).update(
                mapOf(
                    "activeTenantId" to tenantId,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "users/$uid")
            Result.failure(e)
        }
    }

    suspend fun updateDisplayName(displayName: String): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Belum masuk"))
        val clean = displayName.trim().take(100)
        if (clean.isBlank()) return Result.failure(IllegalArgumentException("Nama tidak boleh kosong"))
        return try {
            db.collection("users").document(uid).update(
                mapOf(
                    "displayName" to clean,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "users/$uid")
            Result.failure(e)
        }
    }

    suspend fun updateUsername(username: String): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Belum masuk"))
        val clean = username.trim().lowercase()
        if (!Regex("^[a-z0-9._]{3,20}$").matches(clean)) {
            return Result.failure(IllegalArgumentException("Username harus 3–20 karakter huruf kecil, angka, titik, atau garis bawah."))
        }
        return try {
            db.collection("users").document(uid).update(
                mapOf(
                    "username" to clean,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "users/$uid")
            Result.failure(e)
        }
    }

    suspend fun deleteTenant(tenant: Tenant): Result<Unit> {
        return try {
            val uid = requireUserId()
            if (tenant.ownerId != uid) {
                return Result.failure(IllegalArgumentException("Hanya pemilik organisasi yang dapat menghapus organisasi."))
            }
            db.collection("tenants").document(tenant.id).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "tenants/${tenant.id}")
            Result.failure(e)
        }
    }

    suspend fun deleteAccountProfile(): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Belum masuk"))
        return try {
            db.collection("users").document(uid).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "users/$uid")
            Result.failure(e)
        }
    }

    suspend fun updateTenantSettings(
        tenant: Tenant,
        name: String,
        aiMode: String,
        watermarkEnabled: Boolean,
        maxFileMb: Long,
        retentionDays: Long,
        monthlyBudgetUsd: Double,
        isOwner: Boolean
    ): Result<Unit> {
        return try {
            val uid = requireUserId()
            val updates = mutableMapOf<String, Any>(
                "name" to name.trim().ifBlank { tenant.name }.take(100),
                "watermarkEnabled" to watermarkEnabled,
                "maxFileMb" to maxFileMb.coerceIn(5L, 500L),
                "retentionDays" to retentionDays.coerceIn(0L, 3650L),
                "monthlyBudgetUsd" to monthlyBudgetUsd.coerceIn(0.0, 100000.0),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (isOwner) {
                updates["aiMode"] = if (aiMode in listOf("platform", "byok")) aiMode else "platform"
            }
            db.collection("tenants").document(tenant.id).update(updates).await()
            recordAuditLog(
                tenant = tenant,
                userId = uid,
                aksi = "UPDATE_TENANT_SETTINGS",
                target = tenant.name,
                detail = "aiMode=${updates["aiMode"] ?: tenant.aiMode}, maxFileMb=$maxFileMb, watermark=$watermarkEnabled"
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "tenants/${tenant.id}")
            Result.failure(e)
        }
    }

    suspend fun saveTaskConfig(tenant: Tenant, config: TaskConfig): Result<Unit> {
        return try {
            val uid = requireUserId()
            val ref = db.collection("tenants").document(tenant.id)
                .collection("taskConfigs").document(config.task)
            val existing = ref.get().await()
            if (existing.exists()) {
                ref.update(
                    mapOf(
                        "providerLabel" to config.providerLabel.take(60),
                        "modelId" to config.modelId.take(80),
                        "connectionId" to config.connectionId.take(80),
                        "routingMode" to if (config.routingMode in listOf("manual", "auto")) config.routingMode else "manual",
                        "fallbackConnectionId" to config.fallbackConnectionId.take(80),
                        "fallbackModelId" to config.fallbackModelId.take(80),
                        "timeoutSec" to config.timeoutSec.coerceIn(5L, 300L),
                        "retryCount" to config.retryCount.coerceIn(0L, 5L),
                        "temperature" to config.temperature.coerceIn(0.0, 2.0),
                        "maxOutputTokens" to config.maxOutputTokens.coerceIn(128L, 65536L),
                        "enabled" to config.enabled,
                        "systemPromptOverride" to config.systemPromptOverride.take(2000),
                        "memberUids" to tenant.memberUids,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                ref.set(
                    config.copy(
                        tenantId = tenant.id,
                        memberUids = tenant.memberUids
                    ).toCreateMap()
                ).await()
            }
            recordAuditLog(
                tenant = tenant,
                userId = uid,
                aksi = "UPDATE_TASK_CONFIG",
                target = config.task,
                detail = "provider=${config.providerLabel}, model=${config.modelId}, mode=${config.routingMode}, active=${config.enabled}"
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "tenants/${tenant.id}/taskConfigs/${config.task}")
            Result.failure(e)
        }
    }

    suspend fun saveModelPrice(tenant: Tenant, price: ModelPrice): Result<Unit> {
        return try {
            val uid = requireUserId()
            val docId = price.id.ifBlank { price.modelId.replace(Regex("[^a-zA-Z0-9_-]"), "_") }
            val ref = db.collection("tenants").document(tenant.id)
                .collection("modelPrices").document(docId)
            val existing = ref.get().await()
            if (existing.exists()) {
                ref.update(
                    mapOf(
                        "provider" to price.provider.take(60),
                        "modelId" to price.modelId.take(80),
                        "inputPer1M" to price.inputPer1M.coerceAtLeast(0.0),
                        "outputPer1M" to price.outputPer1M.coerceAtLeast(0.0),
                        "currency" to price.currency.take(10),
                        "memberUids" to tenant.memberUids,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                ref.set(
                    price.copy(tenantId = tenant.id, memberUids = tenant.memberUids).toCreateMap()
                ).await()
            }
            recordAuditLog(
                tenant = tenant,
                userId = uid,
                aksi = "UPDATE_MODEL_PRICE",
                target = price.modelId,
                detail = "${price.currency} in=${price.inputPer1M}/1M, out=${price.outputPer1M}/1M"
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "tenants/${tenant.id}/modelPrices")
            Result.failure(e)
        }
    }

    suspend fun createInvite(tenant: Tenant, email: String, role: String): Result<Unit> {
        return try {
            val uid = requireUserId()
            val cleanEmail = email.trim().lowercase()
            require(cleanEmail.contains("@") && cleanEmail.length >= 5) { "Format email tidak valid." }
            val inviteId = "inv_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            val token = UUID.randomUUID().toString().replace("-", "").take(24)
            val invite = TenantInvite(
                tenantId = tenant.id,
                email = cleanEmail,
                role = if (role == "admin") "admin" else "member",
                token = token,
                inviteStatus = "pending",
                invitedBy = uid,
                adminUids = tenant.adminUids
            )
            db.collection("tenants").document(tenant.id).collection("invites")
                .document(inviteId).set(invite.toCreateMap()).await()
            recordAuditLog(
                tenant = tenant,
                userId = uid,
                aksi = "CREATE_INVITE",
                target = cleanEmail,
                detail = "role=${invite.role}"
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "tenants/${tenant.id}/invites")
            Result.failure(e)
        }
    }

    suspend fun revokeInvite(tenant: Tenant, inviteId: String): Result<Unit> {
        return try {
            val uid = requireUserId()
            db.collection("tenants").document(tenant.id).collection("invites")
                .document(inviteId).update(
                    mapOf(
                        "inviteStatus" to "revoked",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            recordAuditLog(tenant, uid, "REVOKE_INVITE", inviteId, "Undangan dibatalkan")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "tenants/${tenant.id}/invites/$inviteId")
            Result.failure(e)
        }
    }

    suspend fun updateMemberRole(tenant: Tenant, member: TenantMember, newRole: String): Result<Unit> {
        if (member.role == "owner") return Result.failure(IllegalArgumentException("Pemilik utama tidak dapat diturunkan."))
        return try {
            val uid = requireUserId()
            val cleanRole = if (newRole == "admin") "admin" else "member"
            db.collection("tenants").document(tenant.id).collection("members")
                .document(member.userId).update(
                    mapOf(
                        "role" to cleanRole,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            val updatedAdmins = if (cleanRole == "admin") {
                (tenant.adminUids + member.userId).distinct()
            } else {
                tenant.adminUids.filterNot { it == member.userId || it != tenant.ownerId && it == member.userId }
            }
            db.collection("tenants").document(tenant.id).update(
                mapOf(
                    "adminUids" to updatedAdmins,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            recordAuditLog(tenant, uid, "UPDATE_MEMBER_ROLE", member.email, "role=$cleanRole")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "tenants/${tenant.id}/members/${member.userId}")
            Result.failure(e)
        }
    }

    suspend fun recordUsageLog(tenant: Tenant, log: UsageLog): Result<Unit> {
        return try {
            val uid = requireUserId()
            val logId = "use_${UUID.randomUUID().toString().replace("-", "").take(14)}"
            val payload = log.copy(
                tenantId = tenant.id,
                userId = uid,
                adminUids = tenant.adminUids
            )
            db.collection("tenants").document(tenant.id).collection("usageLogs")
                .document(logId).set(payload.toCreateMap()).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "tenants/${tenant.id}/usageLogs")
            Result.failure(e)
        }
    }

    suspend fun recordAuditLog(
        tenant: Tenant,
        userId: String,
        aksi: String,
        target: String,
        detail: String
    ) {
        try {
            val logId = "aud_${UUID.randomUUID().toString().replace("-", "").take(14)}"
            val audit = AuditLog(
                tenantId = tenant.id,
                userId = userId,
                actorEmail = auth.currentUser?.email.orEmpty(),
                aksi = aksi,
                target = target,
                detail = detail,
                adminUids = tenant.adminUids
            )
            db.collection("tenants").document(tenant.id).collection("auditLogs")
                .document(logId).set(audit.toCreateMap()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "tenants/${tenant.id}/auditLogs")
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
