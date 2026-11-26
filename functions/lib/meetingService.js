const { ServiceError } = require("./supabaseClient");
const { resolveOrProvisionSupabaseUser, linkVerifiedSupabaseAccount } = require("./identityLinker");

const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function requireAuthContext(auth) {
  if (!auth || !auth.uid) {
    throw new ServiceError("unauthenticated", "Sesi login Firebase tidak valid. Silakan masuk kembali.");
  }
  const token = auth.token || {};
  return {
    firebaseUid: auth.uid,
    firebaseEmail: token.email || null,
    emailVerified: Boolean(token.email_verified),
  };
}

function requireValidUuid(value, label = "ID rapat") {
  if (typeof value !== "string" || !UUID_REGEX.test(value.trim())) {
    throw new ServiceError("invalid-argument", `${label} tidak valid (harus UUID).`);
  }
  return value.trim().toLowerCase();
}

/**
 * Original recordings remain strictly on the user's device.
 * Never store device-local paths, base64 audio, public audio URLs, or remote audio keys in Supabase DB.
 */
function sanitizeRemoteAudioPath(_rawPath) {
  return null;
}

function mapRpcError(err) {
  if (err instanceof ServiceError) return err;
  const msg = String((err && err.message) || "");
  if ((err && err.pgCode === "42501") || msg.includes("FORBIDDEN_MEETING_OWNERSHIP")) {
    return new ServiceError("permission-denied", "Anda tidak memiliki izin untuk mengakses rapat ini.");
  }
  if (err && (err.code === "deadline-exceeded" || err.code === "failed-precondition")) {
    return err;
  }
  return new ServiceError(
    "unavailable",
    "Gagal berkomunikasi dengan database notulen. Silakan coba lagi."
  );
}

async function savePersonalMeeting({ auth, data, supabase }) {
  const { firebaseUid, firebaseEmail, emailVerified } = requireAuthContext(auth);
  const payload = data || {};
  const meetingId = requireValidUuid(payload.meetingId, "ID rapat");

  // Always resolve owner strictly from verified Firebase UID — ignore any client userId/workspaceId
  const { supabaseUserId } = await resolveOrProvisionSupabaseUser({
    firebaseUid,
    firebaseEmail,
    emailVerified,
    supabase,
  });

  const judul =
    typeof payload.judul === "string" && payload.judul.trim()
      ? payload.judul.trim().slice(0, 200)
      : "Rapat Baru";
  const durasiDetik = Math.max(0, Math.round(Number(payload.durasiDetik) || 0));
  const ukuranBytes = Math.max(0, Math.round(Number(payload.ukuranBytes) || 0));
  const status =
    typeof payload.status === "string" && payload.status.trim()
      ? payload.status.trim().slice(0, 40)
      : "done";
  const audioPath = sanitizeRemoteAudioPath(payload.audioPath);
  const errorMessage =
    typeof payload.errorMessage === "string" && payload.errorMessage.trim()
      ? payload.errorMessage.trim().slice(0, 500)
      : null;
  const isFavorite = Boolean(payload.isFavorite);
  const tags = Array.isArray(payload.tags)
    ? payload.tags.map((t) => String(t).trim()).filter(Boolean).slice(0, 20)
    : [];
  const modelInfo = payload.modelInfo && typeof payload.modelInfo === "object" ? payload.modelInfo : null;

  const transcriptInput = payload.transcript && typeof payload.transcript === "object" ? payload.transcript : null;
  const hasTranscript = Boolean(transcriptInput);
  const bahasa = transcriptInput && typeof transcriptInput.bahasa === "string"
    ? transcriptInput.bahasa.trim().slice(0, 20) || "id"
    : "id";
  const isi = transcriptInput && typeof transcriptInput.isi === "string"
    ? transcriptInput.isi.slice(0, 300000)
    : "";
  const segmen = transcriptInput && Array.isArray(transcriptInput.segmen)
    ? transcriptInput.segmen.slice(0, 1500)
    : [];

  const summaryInput = payload.summary && typeof payload.summary === "object" ? payload.summary : null;
  const hasSummary = Boolean(summaryInput);
  const ringkasan = summaryInput && typeof summaryInput.ringkasan === "string"
    ? summaryInput.ringkasan.slice(0, 20000)
    : "";
  const poinUtama = summaryInput && Array.isArray(summaryInput.poinUtama)
    ? summaryInput.poinUtama.slice(0, 100)
    : [];
  const keputusan = summaryInput && Array.isArray(summaryInput.keputusan)
    ? summaryInput.keputusan.slice(0, 100)
    : [];
  const risiko = summaryInput && Array.isArray(summaryInput.risiko)
    ? summaryInput.risiko.slice(0, 100)
    : [];
  const angkaKunci = summaryInput && Array.isArray(summaryInput.angkaKunci)
    ? summaryInput.angkaKunci.slice(0, 50)
    : [];
  const actionItems = summaryInput && Array.isArray(summaryInput.actionItems)
    ? summaryInput.actionItems.slice(0, 200)
    : [];
  const analysis = summaryInput && summaryInput.analysis && typeof summaryInput.analysis === "object"
    ? summaryInput.analysis
    : null;
  const visualJson = summaryInput && summaryInput.visualJson && typeof summaryInput.visualJson === "object"
    ? summaryInput.visualJson
    : null;

  try {
    const meeting = await supabase.rpc("upsert_personal_meeting_bundle", {
      p_owner_user_id: supabaseUserId,
      p_meeting_id: meetingId,
      p_judul: judul,
      p_durasi_detik: durasiDetik,
      p_status: status,
      p_audio_path: audioPath,
      p_ukuran_bytes: ukuranBytes,
      p_error_message: errorMessage,
      p_is_favorite: isFavorite,
      p_tags: tags,
      p_model_info: modelInfo,
      p_has_transcript: hasTranscript,
      p_bahasa: bahasa,
      p_isi: isi,
      p_segmen: segmen,
      p_has_summary: hasSummary,
      p_ringkasan: ringkasan,
      p_poin_utama: poinUtama,
      p_keputusan: keputusan,
      p_risiko: risiko,
      p_angka_kunci: angkaKunci,
      p_action_items: actionItems,
      p_analysis: analysis,
      p_visual_json: visualJson,
    });
    return { meeting };
  } catch (err) {
    throw mapRpcError(err);
  }
}

async function listPersonalMeetings({ auth, data, supabase }) {
  const { firebaseUid, firebaseEmail, emailVerified } = requireAuthContext(auth);
  const { supabaseUserId } = await resolveOrProvisionSupabaseUser({
    firebaseUid,
    firebaseEmail,
    emailVerified,
    supabase,
  });
  const limit = Math.min(Math.max(Math.round(Number(data && data.limit) || 200), 1), 500);
  try {
    const meetings = await supabase.rpc("list_personal_meetings", {
      p_owner_user_id: supabaseUserId,
      p_limit: limit,
    });
    return { meetings: Array.isArray(meetings) ? meetings : [] };
  } catch (err) {
    throw mapRpcError(err);
  }
}

async function getPersonalMeetingDetail({ auth, data, supabase }) {
  const { firebaseUid, firebaseEmail, emailVerified } = requireAuthContext(auth);
  const meetingId = requireValidUuid(data && data.meetingId, "ID rapat");
  const { supabaseUserId } = await resolveOrProvisionSupabaseUser({
    firebaseUid,
    firebaseEmail,
    emailVerified,
    supabase,
  });
  try {
    const result = await supabase.rpc("get_personal_meeting_detail", {
      p_owner_user_id: supabaseUserId,
      p_meeting_id: meetingId,
    });
    if (!result || result.status === "not_found") {
      throw new ServiceError("not-found", "Rapat tidak ditemukan atau telah dihapus.");
    }
    if (result.status === "forbidden") {
      throw new ServiceError("permission-denied", "Anda tidak memiliki izin untuk membuka rapat ini.");
    }
    return { meeting: result.meeting };
  } catch (err) {
    throw mapRpcError(err);
  }
}

async function updatePersonalMeeting({ auth, data, supabase }) {
  const { firebaseUid, firebaseEmail, emailVerified } = requireAuthContext(auth);
  const payload = data || {};
  const meetingId = requireValidUuid(payload.meetingId, "ID rapat");
  const rawPatch = payload.patch && typeof payload.patch === "object" ? payload.patch : {};

  // Whitelist allowed mutable fields; never accept user_id, id, or tenantId
  const patch = {};
  if (typeof rawPatch.judul === "string") {
    const clean = rawPatch.judul.trim().slice(0, 200);
    if (!clean) throw new ServiceError("invalid-argument", "Judul rapat tidak boleh kosong.");
    patch.judul = clean;
  }
  if (typeof rawPatch.isFavorite === "boolean") patch.is_favorite = rawPatch.isFavorite;
  if (Array.isArray(rawPatch.tags)) {
    patch.tags = rawPatch.tags.map((t) => String(t).trim()).filter(Boolean).slice(0, 20);
  }
  if (rawPatch.modelInfo && typeof rawPatch.modelInfo === "object") {
    patch.model_info = rawPatch.modelInfo;
  }
  if (typeof rawPatch.bahasa === "string") patch.bahasa = rawPatch.bahasa.trim().slice(0, 20);
  if (typeof rawPatch.isi === "string") patch.isi = rawPatch.isi.slice(0, 300000);
  if (Array.isArray(rawPatch.segmen)) patch.segmen = rawPatch.segmen.slice(0, 1500);
  if (typeof rawPatch.ringkasan === "string") patch.ringkasan = rawPatch.ringkasan.slice(0, 20000);
  if (Array.isArray(rawPatch.poinUtama)) patch.poin_utama = rawPatch.poinUtama.slice(0, 100);
  if (Array.isArray(rawPatch.keputusan)) patch.keputusan = rawPatch.keputusan.slice(0, 100);
  if (Array.isArray(rawPatch.risiko)) patch.risiko = rawPatch.risiko.slice(0, 100);
  if (Array.isArray(rawPatch.angkaKunci)) patch.angka_kunci = rawPatch.angkaKunci.slice(0, 50);
  if (Array.isArray(rawPatch.actionItems)) patch.action_items = rawPatch.actionItems.slice(0, 200);
  if (rawPatch.analysis !== undefined) patch.analysis = rawPatch.analysis;
  if (rawPatch.visualJson !== undefined) patch.visual_json = rawPatch.visualJson;

  if (Object.keys(patch).length === 0) {
    throw new ServiceError("invalid-argument", "Tidak ada perubahan yang dikirim.");
  }

  const { supabaseUserId } = await resolveOrProvisionSupabaseUser({
    firebaseUid,
    firebaseEmail,
    emailVerified,
    supabase,
  });

  try {
    const result = await supabase.rpc("update_personal_meeting_fields", {
      p_owner_user_id: supabaseUserId,
      p_meeting_id: meetingId,
      p_patch: patch,
    });
    if (!result || result.status === "not_found") {
      throw new ServiceError("not-found", "Rapat tidak ditemukan atau telah dihapus.");
    }
    if (result.status === "forbidden") {
      throw new ServiceError("permission-denied", "Anda tidak memiliki izin untuk mengubah rapat ini.");
    }
    return { meeting: result.meeting };
  } catch (err) {
    throw mapRpcError(err);
  }
}

async function deletePersonalMeeting({ auth, data, supabase }) {
  const { firebaseUid, firebaseEmail, emailVerified } = requireAuthContext(auth);
  const meetingId = requireValidUuid(data && data.meetingId, "ID rapat");
  const { supabaseUserId } = await resolveOrProvisionSupabaseUser({
    firebaseUid,
    firebaseEmail,
    emailVerified,
    supabase,
  });

  try {
    const result = await supabase.rpc("delete_personal_meeting", {
      p_owner_user_id: supabaseUserId,
      p_meeting_id: meetingId,
    });
    if (!result || result.status === "not_found") {
      throw new ServiceError("not-found", "Rapat tidak ditemukan atau sudah dihapus.");
    }
    if (result.status === "forbidden") {
      throw new ServiceError("permission-denied", "Anda tidak memiliki izin untuk menghapus rapat ini.");
    }
    return { deleted: true };
  } catch (err) {
    throw mapRpcError(err);
  }
}

async function linkSupabaseAccount({ auth, data, supabase }) {
  const { firebaseUid, firebaseEmail } = requireAuthContext(auth);
  const payload = data || {};
  return linkVerifiedSupabaseAccount({
    firebaseUid,
    firebaseEmail,
    supabaseAccessToken: payload.supabaseAccessToken,
    supabaseEmail: payload.supabaseEmail,
    supabasePassword: payload.supabasePassword,
    supabase,
  });
}

module.exports = {
  savePersonalMeeting,
  listPersonalMeetings,
  getPersonalMeetingDetail,
  updatePersonalMeeting,
  deletePersonalMeeting,
  linkSupabaseAccount,
  sanitizeRemoteAudioPath,
};
