class ServiceError extends Error {
  constructor(code, message, details = undefined) {
    super(message);
    this.name = "ServiceError";
    this.code = code;
    this.details = details;
  }
}

function createSupabaseClient({ url, serviceRoleKey, fetchImpl = fetch, timeoutMs = 15000 }) {
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
        apikey: serviceRoleKey,
        Authorization: `Bearer ${options.bearerToken || serviceRoleKey}`,
        "Content-Type": "application/json",
        ...(options.headers || {}),
      };
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
    async findIdentityLinkByFirebaseUid(firebaseUid) {
      const rows = await request(
        `/rest/v1/firebase_identity_links?firebase_uid=eq.${encodeURIComponent(firebaseUid)}&select=firebase_uid,supabase_user_id,firebase_email,link_method&limit=1`
      );
      return Array.isArray(rows) && rows.length > 0 ? rows[0] : null;
    },

    async findIdentityLinkBySupabaseUserId(supabaseUserId) {
      const rows = await request(
        `/rest/v1/firebase_identity_links?supabase_user_id=eq.${encodeURIComponent(supabaseUserId)}&select=firebase_uid,supabase_user_id,firebase_email,link_method&limit=1`
      );
      return Array.isArray(rows) && rows.length > 0 ? rows[0] : null;
    },

    async insertIdentityLink({ firebaseUid, supabaseUserId, firebaseEmail, linkMethod }) {
      const rows = await request("/rest/v1/firebase_identity_links", {
        method: "POST",
        headers: { Prefer: "return=representation" },
        body: {
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
          user_metadata: {
            firebase_uid: firebaseUid,
            auth_source: "firebase",
          },
        },
      });
    },

    async verifySupabaseAccessToken(accessToken) {
      return request("/auth/v1/user", {
        method: "GET",
        bearerToken: accessToken,
      });
    },

    async verifySupabasePassword({ email, password }) {
      return request("/auth/v1/token?grant_type=password", {
        method: "POST",
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

module.exports = {
  ServiceError,
  createSupabaseClient,
};
