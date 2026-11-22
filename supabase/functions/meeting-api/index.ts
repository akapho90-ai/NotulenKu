import { createLocalJWKSet, jwtVerify } from "npm:jose@6.1.3";
import { ApiError, handleCheckAiHealth, handleProcessAi } from "./ai.ts";

class ServiceError extends Error {
  constructor(code, message, details = undefined) {
    super(message);
    this.name = "ServiceError";
    this.code = code;
    this.details = details;
  }
}

function createSupabaseClient({ url, serviceRoleKey, publishableKey, firebaseProject, fetchImpl = fetch, timeoutMs = 15000 }) {
  if (!url || !serviceRoleKey) {
    throw new ServiceError(
      "failed-precondition",
      "Konfigurasi server Supabase (SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY) belum lengkap."
    );
  }
  const baseUrl = url.replace(/\/+$/, "");

  async function request(path, options = {}) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const headers = {
        apikey: options.apiKey || serviceRoleKey,
        "Content-Type": "application/json",
        ...(options.headers || {}),
      };
      const authorization = options.bearerToken ||
        (!options.apiKey && !serviceRoleKey.startsWith("sb_secret_") ? serviceRoleKey : null);
      if (authorization) headers.Authorization = `Bearer ${authorization}`;
      const res = await fetchImpl(`${baseUrl}${path}`, {
        method: options.method || "GET",
        headers,
        body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
        signal: controller.signal,
      });
      const text = await res.text();
      let payload = null;
      if (text) {
        try {
          payload = JSON.parse(text);
        } catch {
          payload = { message: text };
        }
      }
      if (!res.ok) {
        const err = new Error(
          (payload && (payload.message || payload.error_description || payload.msg || payload.error)) ||
            `Supabase HTTP ${res.status}`
        );
        err.status = res.status;
        err.pgCode = payload && payload.code;
        err.errorCode = payload && payload.error_code;
        err.payload = payload;
        throw err;
      }
      return payload;
    } catch (err) {
      if (err && err.name === "AbortError") {
        throw new ServiceError("deadline-exceeded", "Koneksi ke server database habis waktu. Silakan coba lagi.");
      }
      throw err;
    } finally {
      clearTimeout(timer);
    }
  }

  return {
    firebaseProject,
    async findIdentityLinkByFirebaseUid(firebaseUid) {
      const rows = await request(
        `/rest/v1/firebase_identity_links?firebase_project_id=eq.${encodeURIComponent(firebaseProject)}&firebase_uid=eq.${encodeURIComponent(firebaseUid)}&select=firebase_uid,supabase_user_id,firebase_email,link_method&limit=1`
      );
      return Array.isArray(rows) && rows.length > 0 ? rows[0] : null;
    },

    async findIdentityLinkBySupabaseUserId(supabaseUserId) {
      const rows = await request(
        `/rest/v1/firebase_identity_links?firebase_project_id=eq.${encodeURIComponent(firebaseProject)}&supabase_user_id=eq.${encodeURIComponent(supabaseUserId)}&select=firebase_uid,supabase_user_id,firebase_email,link_method&limit=1`
      );
      return Array.isArray(rows) && rows.length > 0 ? rows[0] : null;
    },

    async insertIdentityLink({ firebaseUid, supabaseUserId, firebaseEmail, linkMethod }) {
      const rows = await request("/rest/v1/firebase_identity_links", {
        method: "POST",
        headers: { Prefer: "return=representation" },
        body: {
          firebase_project_id: firebaseProject,
          firebase_uid: firebaseUid,
          supabase_user_id: supabaseUserId,
          firebase_email: firebaseEmail || null,
          link_method: linkMethod,
        },
      });
      return Array.isArray(rows) && rows.length > 0 ? rows[0] : rows;
    },

    async findSupabaseUserByEmail(email) {
      const rows = await request("/rest/v1/rpc/get_supabase_user_by_email", {
        method: "POST",
        body: { p_email: email },
      });
      return Array.isArray(rows) && rows.length > 0 ? rows[0] : null;
    },

    async createSupabaseUser({ email, emailVerified, firebaseUid }) {
      return request("/auth/v1/admin/users", {
        method: "POST",
        body: {
          email,
          email_confirm: Boolean(emailVerified),
          app_metadata: {
            firebase_uid: firebaseUid,
            firebase_project_id: firebaseProject,
            auth_source: "firebase",
          },
        },
      });
    },

    async wasProvisionedForFirebaseUser(supabaseUserId, firebaseUid) {
      const user = await request(`/auth/v1/admin/users/${encodeURIComponent(supabaseUserId)}`);
      return user?.app_metadata?.firebase_project_id === firebaseProject &&
        user?.app_metadata?.firebase_uid === firebaseUid;
    },

    async verifySupabaseAccessToken(accessToken) {
      return request("/auth/v1/user", {
        method: "GET",
        bearerToken: accessToken,
      });
    },

    async verifySupabasePassword({ email, password }) {
      if (!publishableKey) throw new ServiceError("failed-precondition", "Konfigurasi verifikasi belum lengkap.");
      return request("/auth/v1/token?grant_type=password", {
        method: "POST",
        apiKey: publishableKey,
        body: { email, password },
      });
    },

    async rpc(fnName, params) {
      return request(`/rest/v1/rpc/${fnName}`, {
        method: "POST",
        body: params,
      });
    },
  };
}



function normalizeEmail(email) {
  if (typeof email !== "string") return "";
  return email.trim().toLowerCase();
}

function buildLinkRequiredError() {
  return new ServiceError(
    "failed-precondition",
    "Akun Supabase untuk email ini sudah ada dan belum tertaut ke akun Firebase Anda. Verifikasi kepemilikan akun Supabase terlebih dahulu atau tautkan melalui prosedur admin terverifikasi.",
    { code: "ACCOUNT_LINK_REQUIRED" }
  );
}

/**
 * Resolves the 1:1 Supabase auth.users UUID for an authenticated Firebase user.
 * - Never auto-links an existing Supabase auth.users row solely by matching email.
 * - Idempotently provisions a brand-new Supabase user when no conflicting account exists.
 */
async function resolveOrProvisionSupabaseUser({
  firebaseUid,
  firebaseEmail,
  emailVerified,
  supabase,
}) {
  if (!firebaseUid || typeof firebaseUid !== "string") {
    throw new ServiceError("unauthenticated", "Autentikasi Firebase diperlukan.");
  }

  const existingLink = await supabase.findIdentityLinkByFirebaseUid(firebaseUid);
  if (existingLink && existingLink.supabase_user_id) {
    return {
      supabaseUserId: existingLink.supabase_user_id,
      linkMethod: existingLink.link_method,
      newlyProvisioned: false,
    };
  }

  const normalizedEmail = normalizeEmail(firebaseEmail);
  // Unverified Firebase email must never reserve a real Supabase email.
  let provisioningEmail = normalizedEmail;
  if (!normalizedEmail || !emailVerified) {
    const hash = await crypto.subtle.digest(
      "SHA-256",
      new TextEncoder().encode(`${supabase.firebaseProject}:${firebaseUid}`),
    );
    const suffix = Array.from(new Uint8Array(hash).slice(0, 20), (byte) =>
      byte.toString(16).padStart(2, "0")
    ).join("");
    provisioningEmail = `${suffix}@firebase-user.invalid`;
  }

  let newSupabaseUserId;
  const existingEmailUser = normalizedEmail && await supabase.findSupabaseUserByEmail(normalizedEmail);
  const existingOwner = provisioningEmail === normalizedEmail
    ? existingEmailUser
    : await supabase.findSupabaseUserByEmail(provisioningEmail);
  if (existingEmailUser && provisioningEmail !== normalizedEmail && !existingOwner) {
    throw buildLinkRequiredError();
  }
  if (existingOwner?.id) {
    // Only server-written app_metadata can identify a partially provisioned account.
    if (!await supabase.wasProvisionedForFirebaseUser(existingOwner.id, firebaseUid)) {
      throw buildLinkRequiredError();
    }
    newSupabaseUserId = existingOwner.id;
  } else {
    try {
      const createdUser = await supabase.createSupabaseUser({
        email: provisioningEmail,
        emailVerified: true,
        firebaseUid,
      });
      newSupabaseUserId = createdUser?.id || createdUser?.user?.id;
    } catch (error) {
      if (error.errorCode !== "email_exists" && error.errorCode !== "user_already_exists" &&
          error.status !== 409 && error.status !== 422) throw error;
      const racedOwner = await supabase.findSupabaseUserByEmail(provisioningEmail);
      if (!racedOwner?.id || !await supabase.wasProvisionedForFirebaseUser(racedOwner.id, firebaseUid)) {
        throw buildLinkRequiredError();
      }
      newSupabaseUserId = racedOwner.id;
    }
  }
  if (!newSupabaseUserId) {
    throw new ServiceError("internal", "Gagal membuat identitas Supabase untuk akun baru.");
  }

  try {
    const inserted = await supabase.insertIdentityLink({
      firebaseUid,
      supabaseUserId: newSupabaseUserId,
      firebaseEmail: normalizedEmail || null,
      linkMethod: "provisioned",
    });
    return {
      supabaseUserId: (inserted && inserted.supabase_user_id) || newSupabaseUserId,
      linkMethod: "provisioned",
      newlyProvisioned: true,
    };
  } catch (err) {
    if (err && (err.pgCode === "23505" || err.status === 409)) {
      const winner = await supabase.findIdentityLinkByFirebaseUid(firebaseUid);
      if (winner && winner.supabase_user_id) {
        return {
          supabaseUserId: winner.supabase_user_id,
          linkMethod: winner.link_method,
          newlyProvisioned: false,
        };
      }
    }
    throw err;
  }
}

/**
 * Links an existing legacy Supabase auth.users account ONLY after server-side
 * cryptographic/credential ownership verification against Supabase Auth.
 */
async function linkVerifiedSupabaseAccount({
  firebaseUid,
  firebaseEmail,
  supabaseAccessToken,
  supabaseEmail,
  supabasePassword,
  supabase,
}) {
  if (!firebaseUid || typeof firebaseUid !== "string") {
    throw new ServiceError("unauthenticated", "Autentikasi Firebase diperlukan.");
  }

  const currentLink = await supabase.findIdentityLinkByFirebaseUid(firebaseUid);

  let verifiedUser = null;
  let linkMethod = "";

  if (typeof supabaseAccessToken === "string" && supabaseAccessToken.trim()) {
    try {
      verifiedUser = await supabase.verifySupabaseAccessToken(supabaseAccessToken.trim());
      linkMethod = "verified_supabase_jwt";
    } catch {
      throw new ServiceError("permission-denied", "Token verifikasi akun Supabase tidak valid atau kedaluwarsa.");
    }
  } else if (
    typeof supabaseEmail === "string" &&
    supabaseEmail.trim() &&
    typeof supabasePassword === "string" &&
    supabasePassword
  ) {
    try {
      const session = await supabase.verifySupabasePassword({
        email: supabaseEmail.trim(),
        password: supabasePassword,
      });
      verifiedUser = session && session.user ? session.user : null;
      linkMethod = "verified_supabase_password";
    } catch {
      throw new ServiceError("permission-denied", "Email atau password akun Supabase salah.");
    }
  } else {
    throw new ServiceError(
      "invalid-argument",
      "Bukti kepemilikan akun Supabase diperlukan (token sesi atau kredensial Supabase)."
    );
  }

  const verifiedSupabaseId = verifiedUser && verifiedUser.id;
  if (!verifiedSupabaseId) {
    throw new ServiceError("permission-denied", "Verifikasi kepemilikan akun Supabase gagal.");
  }

  const fbEmailNorm = normalizeEmail(firebaseEmail);
  const sbEmailNorm = normalizeEmail(verifiedUser.email);
  if (fbEmailNorm && sbEmailNorm && fbEmailNorm !== sbEmailNorm) {
    throw new ServiceError(
      "permission-denied",
      "Email akun Supabase yang diverifikasi tidak cocok dengan email akun Firebase Anda."
    );
  }

  if (currentLink) {
    if (currentLink.supabase_user_id === verifiedSupabaseId) {
      return { linked: true, supabaseUserId: verifiedSupabaseId, linkMethod: currentLink.link_method };
    }
    throw new ServiceError(
      "already-exists",
      "Akun Firebase ini sudah tertaut ke identitas Supabase lain."
    );
  }

  const existingSupabaseLink = await supabase.findIdentityLinkBySupabaseUserId(verifiedSupabaseId);
  if (existingSupabaseLink && existingSupabaseLink.firebase_uid !== firebaseUid) {
    throw new ServiceError(
      "already-exists",
      "Akun Supabase ini sudah tertaut ke akun Firebase lain."
    );
  }

  try {
    await supabase.insertIdentityLink({
      firebaseUid,
      supabaseUserId: verifiedSupabaseId,
      firebaseEmail: fbEmailNorm || sbEmailNorm || null,
      linkMethod,
    });
  } catch (error) {
    if (error.pgCode !== "23505" && error.status !== 409) throw error;
    const winner = await supabase.findIdentityLinkByFirebaseUid(firebaseUid);
    if (winner?.supabase_user_id !== verifiedSupabaseId) {
      throw new ServiceError("already-exists", "Salah satu akun sudah tertaut ke identitas lain.");
    }
    linkMethod = winner.link_method;
  }

  return { linked: true, supabaseUserId: verifiedSupabaseId, linkMethod };
}



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
      // The current Android flow stores audio on the device only.
      p_audio_path: null,
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



const handlers = {
  deletePersonalMeeting, getPersonalMeetingDetail, linkSupabaseAccount,
  listPersonalMeetings, savePersonalMeeting, updatePersonalMeeting,
  checkAiHealth: handleCheckAiHealth,
  processAi: handleProcessAi,
};
const statuses = {
  "invalid-argument": 400, unauthenticated: 401, "permission-denied": 403,
  "not-found": 404, "already-exists": 409, "failed-precondition": 412,
  "deadline-exceeded": 504, unavailable: 503,
};

function failure(code, message, details) {
  return Response.json({
    error: { status: code.toUpperCase().replaceAll("-", "_"), message, details },
  }, { status: statuses[code] ?? 500 });
}

function createMeetingHandler({ verifyFirebaseToken, getSupabase }) {
  return async (request) => {
    const action = new URL(request.url).pathname.split("/").pop();
    if (request.method !== "POST" || !Object.hasOwn(handlers, action)) {
      return failure("not-found", "Endpoint rapat tidak ditemukan.");
    }
    const token = request.headers.get("authorization")?.match(/^Bearer ([^\s]+)$/i)?.[1];
    if (!token) return failure("unauthenticated", "Sesi Firebase diperlukan. Silakan masuk kembali.");
    let auth;
    try {
      auth = await verifyFirebaseToken(token);
      if (!auth?.uid) throw new Error("Missing verified Firebase UID");
    } catch {
      return failure("unauthenticated", "Sesi Firebase tidak valid. Silakan masuk kembali.");
    }
    let data;
    try {
      ({ data } = await request.json());
      if (!data || typeof data !== "object" || Array.isArray(data)) throw new Error();
    } catch {
      return failure("invalid-argument", "Data permintaan rapat tidak valid.");
    }
    try {
      const result = await handlers[action]({ auth, data, supabase: getSupabase() });
      return Response.json({ result });
    } catch (error) {
      if (error instanceof ServiceError) return failure(error.code, error.message, error.details);
      if (error instanceof ApiError) {
        return Response.json({
          error: { status: error.status, message: error.message, details: error.details },
        }, { status: error.httpStatus });
      }
      return failure("internal", "Terjadi kesalahan server saat mengakses data notulen.");
    }
  };
}


const firebaseProject = Deno.env.get("FIREBASE_PROJECT_ID") || "gen-lang-client-0830960267";
const keyUrl =
  "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
let keys: ReturnType<typeof createLocalJWKSet> | undefined;
let expiresAt = 0;

async function verifyFirebaseToken(token: string) {
  if (!keys || Date.now() >= expiresAt) {
    const response = await fetch(keyUrl, { signal: AbortSignal.timeout(5000) });
    if (!response.ok) throw new Error("Firebase signing keys unavailable");
    const maxAge = Number(response.headers.get("cache-control")?.match(/max-age=(\d+)/)?.[1] ?? 0);
    keys = createLocalJWKSet(await response.json());
    expiresAt = Date.now() + maxAge * 1000;
  }
  const { payload } = await jwtVerify(token, keys, {
    algorithms: ["RS256"],
    issuer: `https://securetoken.google.com/${firebaseProject}`,
    audience: firebaseProject,
    requiredClaims: ["sub", "exp", "iat", "auth_time"],
  });
  const now = Math.floor(Date.now() / 1000);
  if (
    typeof payload.sub !== "string" || !payload.sub || payload.sub.length > 128 ||
    payload.aud !== firebaseProject ||
    typeof payload.iat !== "number" || payload.iat > now ||
    typeof payload.auth_time !== "number" || payload.auth_time > now
  ) throw new Error("Invalid Firebase claims");
  return { uid: payload.sub, token: payload };
}

function getSupabase() {
  const injectedSecret = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") || "{}").default;
  const injectedPublishable = JSON.parse(Deno.env.get("SUPABASE_PUBLISHABLE_KEYS") || "{}").default;
  return createSupabaseClient({
    url: Deno.env.get("SUPABASE_URL"),
    serviceRoleKey: injectedSecret || Deno.env.get("SUPABASE_SERVICE_ROLE_KEY"),
    publishableKey: injectedPublishable || Deno.env.get("SUPABASE_ANON_KEY"),
    firebaseProject,
  });
}

Deno.serve(createMeetingHandler({ verifyFirebaseToken, getSupabase }));
