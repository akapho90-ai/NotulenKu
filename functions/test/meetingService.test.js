const test = require("node:test");
const assert = require("node:assert/strict");

const {
  savePersonalMeeting,
  listPersonalMeetings,
  getPersonalMeetingDetail,
  updatePersonalMeeting,
  deletePersonalMeeting,
  linkSupabaseAccount,
  sanitizeRemoteAudioPath,
} = require("../lib/meetingService");

function createInMemorySupabase() {
  const authUsers = new Map(); // uuid -> { id, email, password }
  const linksByFirebaseUid = new Map();
  const linksBySupabaseId = new Map();
  const meetings = new Map();
  const transcripts = new Map();
  const summaries = new Map();
  let seq = 1;

  function serializeMeeting(id) {
    const m = meetings.get(id);
    if (!m) return null;
    const t = transcripts.get(id) || null;
    const s = summaries.get(id) || null;
    return {
      ...m,
      transcript: t,
      summary: s,
    };
  }

  return {
    _state: { authUsers, linksByFirebaseUid, linksBySupabaseId, meetings, transcripts, summaries },

    seedLegacyUser({ id, email, password = "secret" }) {
      authUsers.set(id, { id, email, password });
    },

    async findIdentityLinkByFirebaseUid(firebaseUid) {
      return linksByFirebaseUid.get(firebaseUid) || null;
    },

    async findIdentityLinkBySupabaseUserId(supabaseUserId) {
      return linksBySupabaseId.get(supabaseUserId) || null;
    },

    async insertIdentityLink({ firebaseUid, supabaseUserId, firebaseEmail, linkMethod }) {
      if (linksByFirebaseUid.has(firebaseUid) || linksBySupabaseId.has(supabaseUserId)) {
        const err = new Error("duplicate key value violates unique constraint");
        err.pgCode = "23505";
        throw err;
      }
      const row = {
        firebase_uid: firebaseUid,
        supabase_user_id: supabaseUserId,
        firebase_email: firebaseEmail,
        link_method: linkMethod,
      };
      linksByFirebaseUid.set(firebaseUid, row);
      linksBySupabaseId.set(supabaseUserId, row);
      return row;
    },

    async findSupabaseUserByEmail(email) {
      const target = email.trim().toLowerCase();
      for (const u of authUsers.values()) {
        if (u.email.toLowerCase() === target) {
          return { id: u.id, email: u.email, email_confirmed_at: "2026-01-01T00:00:00Z" };
        }
      }
      return null;
    },

    async createSupabaseUser({ email }) {
      for (const u of authUsers.values()) {
        if (u.email.toLowerCase() === email.toLowerCase()) {
          const err = new Error("User already registered");
          err.status = 422;
          err.errorCode = "email_exists";
          throw err;
        }
      }
      const id = `00000000-0000-4000-8000-00000000000${seq++}`;
      const user = { id, email, password: null };
      authUsers.set(id, user);
      return user;
    },

    async verifySupabaseAccessToken(token) {
      if (token.startsWith("valid_tok_for:")) {
        const uid = token.replace("valid_tok_for:", "");
        const u = authUsers.get(uid);
        if (u) return { id: u.id, email: u.email };
      }
      throw new Error("Invalid JWT");
    },

    async verifySupabasePassword({ email, password }) {
      for (const u of authUsers.values()) {
        if (u.email.toLowerCase() === email.toLowerCase() && u.password === password) {
          return { user: { id: u.id, email: u.email } };
        }
      }
      throw new Error("Invalid credentials");
    },

    async rpc(fnName, p) {
      if (fnName === "upsert_personal_meeting_bundle") {
        const existing = meetings.get(p.p_meeting_id);
        if (existing && existing.user_id !== p.p_owner_user_id) {
          const err = new Error("FORBIDDEN_MEETING_OWNERSHIP");
          err.pgCode = "42501";
          throw err;
        }
        const now = new Date(Date.now() + meetings.size * 1000).toISOString();
        meetings.set(p.p_meeting_id, {
          id: p.p_meeting_id,
          user_id: p.p_owner_user_id,
          judul: p.p_judul,
          durasi_detik: p.p_durasi_detik,
          status: p.p_status,
          audio_path: p.p_audio_path || (existing && existing.audio_path) || null,
          ukuran_bytes: p.p_ukuran_bytes,
          error_message: p.p_error_message,
          is_favorite: p.p_is_favorite,
          tags: p.p_tags,
          model_info: p.p_model_info || (existing && existing.model_info) || null,
          created_at: existing ? existing.created_at : now,
          updated_at: now,
        });
        if (p.p_has_transcript) {
          transcripts.set(p.p_meeting_id, {
            meeting_id: p.p_meeting_id,
            bahasa: p.p_bahasa,
            isi: p.p_isi,
            segmen: p.p_segmen,
          });
        }
        if (p.p_has_summary) {
          summaries.set(p.p_meeting_id, {
            meeting_id: p.p_meeting_id,
            ringkasan: p.p_ringkasan,
            keputusan: p.p_keputusan,
            action_items: p.p_action_items,
            visual_json: p.p_visual_json || null,
            poin_utama: p.p_poin_utama,
            risiko: p.p_risiko,
            angka_kunci: p.p_angka_kunci,
            analysis: p.p_analysis || null,
          });
        }
        return serializeMeeting(p.p_meeting_id);
      }

      if (fnName === "list_personal_meetings") {
        return [...meetings.values()]
          .filter((m) => m.user_id === p.p_owner_user_id)
          .sort((a, b) => b.created_at.localeCompare(a.created_at))
          .slice(0, p.p_limit || 200)
          .map((m) => serializeMeeting(m.id));
      }

      if (fnName === "get_personal_meeting_detail") {
        const m = meetings.get(p.p_meeting_id);
        if (!m) return { status: "not_found" };
        if (m.user_id !== p.p_owner_user_id) return { status: "forbidden" };
        return { status: "ok", meeting: serializeMeeting(m.id) };
      }

      if (fnName === "update_personal_meeting_fields") {
        const m = meetings.get(p.p_meeting_id);
        if (!m) return { status: "not_found" };
        if (m.user_id !== p.p_owner_user_id) return { status: "forbidden" };
        if (p.p_patch.judul) m.judul = p.p_patch.judul;
        if (typeof p.p_patch.is_favorite === "boolean") m.is_favorite = p.p_patch.is_favorite;
        if (p.p_patch.tags) m.tags = p.p_patch.tags;
        return { status: "ok", meeting: serializeMeeting(m.id) };
      }

      if (fnName === "delete_personal_meeting") {
        const m = meetings.get(p.p_meeting_id);
        if (!m) return { status: "not_found" };
        if (m.user_id !== p.p_owner_user_id) return { status: "forbidden" };
        meetings.delete(p.p_meeting_id);
        transcripts.delete(p.p_meeting_id);
        summaries.delete(p.p_meeting_id);
        return { status: "ok", deleted: true };
      }

      throw new Error(`Unknown RPC ${fnName}`);
    },
  };
}

test("unauthenticated request is rejected", async () => {
  const supabase = createInMemorySupabase();
  await assert.rejects(
    () => listPersonalMeetings({ auth: null, data: {}, supabase }),
    (err) => err.code === "unauthenticated"
  );
});

test("legacy Supabase user with matching email is NOT auto-linked without proof", async () => {
  const supabase = createInMemorySupabase();
  const legacyId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  supabase.seedLegacyUser({ id: legacyId, email: "lama@perusahaan.id", password: "pw123" });

  const fbAuth = { uid: "fb_uid_1", token: { email: "lama@perusahaan.id", email_verified: true } };

  await assert.rejects(
    () => listPersonalMeetings({ auth: fbAuth, data: {}, supabase }),
    (err) => err.code === "failed-precondition" && err.details && err.details.code === "ACCOUNT_LINK_REQUIRED"
  );
  assert.equal(supabase._state.linksByFirebaseUid.size, 0);

  // Wrong password fails
  await assert.rejects(
    () =>
      linkSupabaseAccount({
        auth: fbAuth,
        data: { supabaseEmail: "lama@perusahaan.id", supabasePassword: "wrong" },
        supabase,
      }),
    (err) => err.code === "permission-denied"
  );

  // Valid ownership proof links 1:1
  const linked = await linkSupabaseAccount({
    auth: fbAuth,
    data: { supabaseEmail: "lama@perusahaan.id", supabasePassword: "pw123" },
    supabase,
  });
  assert.equal(linked.linked, true);
  assert.equal(linked.supabaseUserId, legacyId);
});

test("savePersonalMeeting is idempotent on retry and ignores spoofed user_id / local audio path", async () => {
  const supabase = createInMemorySupabase();
  const authA = { uid: "fb_user_a", token: { email: "a@contoh.id", email_verified: true } };
  const meetingId = "11111111-1111-4111-8111-111111111111";

  const payload = {
    meetingId,
    user_id: "00000000-0000-4000-8000-999999999999",
    workspaceId: "tenant_abc",
    judul: "Rapat Sprint 12",
    durasiDetik: 185,
    status: "done",
    audioPath: "/data/user/0/com.aistudio.notulenrapat.kxmpzq/files/rec_1.m4a",
    transcript: {
      bahasa: "id",
      isi: "[Budi] Mulai sprint.",
      segmen: [{ pembicara: "Budi", mulaiDetik: 0, selesaiDetik: 5, teks: "Mulai sprint." }],
    },
    summary: {
      ringkasan: "Diskusi target sprint 12.",
      poinUtama: ["Target rilis Jumat"],
      keputusan: ["Gunakan Supabase"],
      risiko: [],
      angkaKunci: [],
      actionItems: [{ id: "1", siapa: "Budi", tugas: "Kirim API", deadline: "Jumat", prioritas: "tinggi", selesai: false }],
    },
  };

  const res1 = await savePersonalMeeting({ auth: authA, data: payload, supabase });
  const res2 = await savePersonalMeeting({ auth: authA, data: payload, supabase });

  assert.equal(supabase._state.meetings.size, 1);
  assert.equal(res1.meeting.id, meetingId);
  assert.equal(res2.meeting.id, meetingId);
  assert.equal(res1.meeting.audio_path, null);
  assert.notEqual(res1.meeting.user_id, "00000000-0000-4000-8000-999999999999");

  const list = await listPersonalMeetings({ auth: authA, data: {}, supabase });
  assert.equal(list.meetings.length, 1);
  assert.equal(list.meetings[0].summary.ringkasan, "Diskusi target sprint 12.");
});

test("second user cannot overwrite, read, update, or delete first user meeting", async () => {
  const supabase = createInMemorySupabase();
  const authA = { uid: "fb_user_a", token: { email: "a@contoh.id", email_verified: true } };
  const authB = { uid: "fb_user_b", token: { email: "b@contoh.id", email_verified: true } };
  const meetingId = "22222222-2222-4222-8222-222222222222";

  await savePersonalMeeting({
    auth: authA,
    data: { meetingId, judul: "Rahasia A", durasiDetik: 60 },
    supabase,
  });

  // User B list must be empty
  const listB = await listPersonalMeetings({ auth: authB, data: {}, supabase });
  assert.equal(listB.meetings.length, 0);

  // User B cannot overwrite User A's meetingId
  await assert.rejects(
    () =>
      savePersonalMeeting({
        auth: authB,
        data: { meetingId, judul: "Bajak A", durasiDetik: 10 },
        supabase,
      }),
    (err) => err.code === "permission-denied"
  );

  // User B cannot get detail, update, or delete User A's meeting
  await assert.rejects(
    () => getPersonalMeetingDetail({ auth: authB, data: { meetingId }, supabase }),
    (err) => err.code === "permission-denied"
  );
  await assert.rejects(
    () => updatePersonalMeeting({ auth: authB, data: { meetingId, patch: { judul: "Hack" } }, supabase }),
    (err) => err.code === "permission-denied"
  );
  await assert.rejects(
    () => deletePersonalMeeting({ auth: authB, data: { meetingId }, supabase }),
    (err) => err.code === "permission-denied"
  );
});

test("sanitizeRemoteAudioPath strictly returns null for local paths, URLs, base64, and remote keys", () => {
  assert.equal(sanitizeRemoteAudioPath("/data/user/0/app/rec.m4a"), null);
  assert.equal(sanitizeRemoteAudioPath("file:///storage/emulated/0/rec.m4a"), null);
  assert.equal(sanitizeRemoteAudioPath("content://media/external/audio/1"), null);
  assert.equal(sanitizeRemoteAudioPath("https://example.com/public/audio.m4a"), null);
  assert.equal(sanitizeRemoteAudioPath("data:audio/mp4;base64,AAAA"), null);
  assert.equal(sanitizeRemoteAudioPath("meetings/user-1/audio.m4a"), null);
});
