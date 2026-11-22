const { ServiceError } = require("./supabaseClient");

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
  if (normalizedEmail) {
    const existingSupabaseUser = await supabase.findSupabaseUserByEmail(normalizedEmail);
    if (existingSupabaseUser && existingSupabaseUser.id) {
      // STRICT SECURITY RULE: Never auto-link existing Supabase accounts by email alone.
      throw buildLinkRequiredError();
    }
  }

  const provisioningEmail =
    normalizedEmail || `${firebaseUid.replace(/[^a-zA-Z0-9_-]/g, "_")}@firebase-user.invalid`;

  let createdUser;
  try {
    createdUser = await supabase.createSupabaseUser({
      email: provisioningEmail,
      emailVerified: normalizedEmail ? Boolean(emailVerified) : true,
      firebaseUid,
    });
  } catch (err) {
    // Handle race condition: check if another concurrent request for THIS same firebaseUid just linked it
    const raceLink = await supabase.findIdentityLinkByFirebaseUid(firebaseUid);
    if (raceLink && raceLink.supabase_user_id) {
      return {
        supabaseUserId: raceLink.supabase_user_id,
        linkMethod: raceLink.link_method,
        newlyProvisioned: false,
      };
    }
    const msg = String((err && err.message) || "").toLowerCase();
    if (
      err &&
      (err.status === 422 || err.errorCode === "email_exists" || msg.includes("already registered"))
    ) {
      throw buildLinkRequiredError();
    }
    throw err;
  }

  const newSupabaseUserId = createdUser && (createdUser.id || (createdUser.user && createdUser.user.id));
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

  await supabase.insertIdentityLink({
    firebaseUid,
    supabaseUserId: verifiedSupabaseId,
    firebaseEmail: fbEmailNorm || sbEmailNorm || null,
    linkMethod,
  });

  return { linked: true, supabaseUserId: verifiedSupabaseId, linkMethod };
}

module.exports = {
  resolveOrProvisionSupabaseUser,
  linkVerifiedSupabaseAccount,
};
