-- ============================================================================
-- AI Notulen Rapat: Personal Meetings & Verified Firebase Identity Mapping
-- Review carefully before applying in Supabase SQL Editor or `supabase db push`.
-- Do NOT execute automatically without review.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ----------------------------------------------------------------------------
-- 1. Ensure core meeting tables & additive columns exist (non-destructive)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.meetings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    judul text NOT NULL DEFAULT 'Rapat Baru',
    durasi_detik integer NOT NULL DEFAULT 0,
    status text NOT NULL DEFAULT 'done',
    audio_path text
);

ALTER TABLE public.meetings
    ADD COLUMN IF NOT EXISTS ukuran_bytes bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS error_message text,
    ADD COLUMN IF NOT EXISTS is_favorite boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS tags jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS model_info jsonb,
    ADD COLUMN IF NOT EXISTS created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS meetings_user_created_idx
    ON public.meetings (user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS public.transcripts (
    meeting_id uuid NOT NULL REFERENCES public.meetings(id) ON DELETE CASCADE,
    bahasa text NOT NULL DEFAULT 'id',
    isi text NOT NULL DEFAULT '',
    segmen jsonb NOT NULL DEFAULT '[]'::jsonb
);

ALTER TABLE public.transcripts
    ADD COLUMN IF NOT EXISTS created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

CREATE UNIQUE INDEX IF NOT EXISTS transcripts_meeting_id_uidx
    ON public.transcripts (meeting_id);

CREATE TABLE IF NOT EXISTS public.summaries (
    meeting_id uuid NOT NULL REFERENCES public.meetings(id) ON DELETE CASCADE,
    ringkasan text NOT NULL DEFAULT '',
    keputusan jsonb NOT NULL DEFAULT '[]'::jsonb,
    action_items jsonb NOT NULL DEFAULT '[]'::jsonb,
    visual_json jsonb
);

ALTER TABLE public.summaries
    ADD COLUMN IF NOT EXISTS poin_utama jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS risiko jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS angka_kunci jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS analysis jsonb,
    ADD COLUMN IF NOT EXISTS created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

CREATE UNIQUE INDEX IF NOT EXISTS summaries_meeting_id_uidx
    ON public.summaries (meeting_id);

-- ----------------------------------------------------------------------------
-- 2. Strict 1:1 Firebase UID <-> Supabase auth.users.id Mapping Table
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.firebase_identity_links (
    firebase_uid text PRIMARY KEY,
    supabase_user_id uuid NOT NULL UNIQUE REFERENCES auth.users(id) ON DELETE CASCADE,
    firebase_email text,
    link_method text NOT NULL CHECK (
        link_method IN (
            'provisioned',
            'verified_supabase_jwt',
            'verified_supabase_password',
            'manual_admin_sql'
        )
    ),
    verified_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Zero-trust: Lock down mapping table from anon & authenticated clients.
-- Only trusted backend using service_role may read/write this table.
ALTER TABLE public.firebase_identity_links ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.firebase_identity_links FROM PUBLIC, anon, authenticated;
GRANT ALL ON TABLE public.firebase_identity_links TO service_role;

-- ----------------------------------------------------------------------------
-- 3. Row Level Security on meetings, transcripts, summaries (auth.uid() intact)
-- ----------------------------------------------------------------------------
ALTER TABLE public.meetings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.transcripts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.summaries ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "meetings_select_own" ON public.meetings;
CREATE POLICY "meetings_select_own" ON public.meetings
    FOR SELECT USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "meetings_insert_own" ON public.meetings;
CREATE POLICY "meetings_insert_own" ON public.meetings
    FOR INSERT WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "meetings_update_own" ON public.meetings;
CREATE POLICY "meetings_update_own" ON public.meetings
    FOR UPDATE USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "meetings_delete_own" ON public.meetings;
CREATE POLICY "meetings_delete_own" ON public.meetings
    FOR DELETE USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "transcripts_all_own" ON public.transcripts;
CREATE POLICY "transcripts_all_own" ON public.transcripts
    FOR ALL
    USING (
        EXISTS (
            SELECT 1 FROM public.meetings m
            WHERE m.id = transcripts.meeting_id AND m.user_id = auth.uid()
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM public.meetings m
            WHERE m.id = transcripts.meeting_id AND m.user_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "summaries_all_own" ON public.summaries;
CREATE POLICY "summaries_all_own" ON public.summaries
    FOR ALL
    USING (
        EXISTS (
            SELECT 1 FROM public.meetings m
            WHERE m.id = summaries.meeting_id AND m.user_id = auth.uid()
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM public.meetings m
            WHERE m.id = summaries.meeting_id AND m.user_id = auth.uid()
        )
    );

-- ----------------------------------------------------------------------------
-- 4. Service-Role-Only Helper RPCs (Single Transaction & Ownership Enforced)
-- ----------------------------------------------------------------------------

-- Exact lowercase email lookup in auth.users so backend can detect existing
-- unlinked legacy accounts without auto-linking by email.
CREATE OR REPLACE FUNCTION public.get_supabase_user_by_email(p_email text)
RETURNS TABLE (
    id uuid,
    email text,
    email_confirmed_at timestamptz
)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public, auth
AS $$
    SELECT u.id, u.email::text, u.email_confirmed_at
    FROM auth.users u
    WHERE u.email IS NOT NULL
      AND lower(u.email) = lower(trim(p_email))
      AND u.deleted_at IS NULL
    LIMIT 1;
$$;

REVOKE ALL ON FUNCTION public.get_supabase_user_by_email(text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.get_supabase_user_by_email(text) TO service_role;

-- Helper to serialize one personal meeting + optional transcript + optional summary
CREATE OR REPLACE FUNCTION public._serialize_personal_meeting(p_meeting_id uuid)
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT jsonb_build_object(
        'id', m.id,
        'user_id', m.user_id,
        'judul', m.judul,
        'durasi_detik', COALESCE(m.durasi_detik, 0),
        'status', COALESCE(m.status, 'done'),
        'audio_path', m.audio_path,
        'ukuran_bytes', COALESCE(m.ukuran_bytes, 0),
        'error_message', m.error_message,
        'is_favorite', COALESCE(m.is_favorite, false),
        'tags', COALESCE(m.tags, '[]'::jsonb),
        'model_info', m.model_info,
        'created_at', m.created_at,
        'updated_at', m.updated_at,
        'transcript', CASE
            WHEN t.meeting_id IS NULL THEN NULL
            ELSE jsonb_build_object(
                'meeting_id', t.meeting_id,
                'bahasa', COALESCE(t.bahasa, 'id'),
                'isi', COALESCE(t.isi, ''),
                'segmen', COALESCE(t.segmen, '[]'::jsonb)
            )
        END,
        'summary', CASE
            WHEN s.meeting_id IS NULL THEN NULL
            ELSE jsonb_build_object(
                'meeting_id', s.meeting_id,
                'ringkasan', COALESCE(s.ringkasan, ''),
                'keputusan', COALESCE(s.keputusan, '[]'::jsonb),
                'action_items', COALESCE(s.action_items, '[]'::jsonb),
                'visual_json', s.visual_json,
                'poin_utama', COALESCE(s.poin_utama, '[]'::jsonb),
                'risiko', COALESCE(s.risiko, '[]'::jsonb),
                'angka_kunci', COALESCE(s.angka_kunci, '[]'::jsonb),
                'analysis', s.analysis
            )
        END
    )
    FROM public.meetings m
    LEFT JOIN public.transcripts t ON t.meeting_id = m.id
    LEFT JOIN public.summaries s ON s.meeting_id = m.id
    WHERE m.id = p_meeting_id;
$$;

REVOKE ALL ON FUNCTION public._serialize_personal_meeting(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._serialize_personal_meeting(uuid) TO service_role;

-- Atomic single-transaction save for meetings + transcripts + summaries.
-- Enforces ownership if p_meeting_id already exists and is idempotent on retry.
CREATE OR REPLACE FUNCTION public.upsert_personal_meeting_bundle(
    p_owner_user_id uuid,
    p_meeting_id uuid,
    p_judul text,
    p_durasi_detik integer DEFAULT 0,
    p_status text DEFAULT 'done',
    p_audio_path text DEFAULT NULL,
    p_ukuran_bytes bigint DEFAULT 0,
    p_error_message text DEFAULT NULL,
    p_is_favorite boolean DEFAULT false,
    p_tags jsonb DEFAULT '[]'::jsonb,
    p_model_info jsonb DEFAULT NULL,
    p_has_transcript boolean DEFAULT false,
    p_bahasa text DEFAULT 'id',
    p_isi text DEFAULT '',
    p_segmen jsonb DEFAULT '[]'::jsonb,
    p_has_summary boolean DEFAULT false,
    p_ringkasan text DEFAULT '',
    p_poin_utama jsonb DEFAULT '[]'::jsonb,
    p_keputusan jsonb DEFAULT '[]'::jsonb,
    p_risiko jsonb DEFAULT '[]'::jsonb,
    p_angka_kunci jsonb DEFAULT '[]'::jsonb,
    p_action_items jsonb DEFAULT '[]'::jsonb,
    p_analysis jsonb DEFAULT NULL,
    p_visual_json jsonb DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_existing_owner uuid;
    v_now timestamptz := now();
BEGIN
    IF p_owner_user_id IS NULL OR p_meeting_id IS NULL THEN
        RAISE EXCEPTION 'INVALID_MEETING_PARAMS' USING ERRCODE = '22023';
    END IF;

    -- Lock any existing meeting row and verify ownership before mutating.
    SELECT user_id INTO v_existing_owner
    FROM public.meetings
    WHERE id = p_meeting_id
    FOR UPDATE;

    IF FOUND AND v_existing_owner <> p_owner_user_id THEN
        RAISE EXCEPTION 'FORBIDDEN_MEETING_OWNERSHIP' USING ERRCODE = '42501';
    END IF;

    INSERT INTO public.meetings (
        id, user_id, judul, durasi_detik, status, audio_path,
        ukuran_bytes, error_message, is_favorite, tags, model_info,
        created_at, updated_at
    )
    VALUES (
        p_meeting_id,
        p_owner_user_id,
        COALESCE(NULLIF(trim(p_judul), ''), 'Rapat Baru'),
        GREATEST(COALESCE(p_durasi_detik, 0), 0),
        COALESCE(NULLIF(trim(p_status), ''), 'done'),
        NULLIF(trim(COALESCE(p_audio_path, '')), ''),
        GREATEST(COALESCE(p_ukuran_bytes, 0), 0),
        p_error_message,
        COALESCE(p_is_favorite, false),
        COALESCE(p_tags, '[]'::jsonb),
        p_model_info,
        v_now,
        v_now
    )
    ON CONFLICT (id) DO UPDATE SET
        judul = EXCLUDED.judul,
        durasi_detik = EXCLUDED.durasi_detik,
        status = EXCLUDED.status,
        audio_path = COALESCE(EXCLUDED.audio_path, public.meetings.audio_path),
        ukuran_bytes = EXCLUDED.ukuran_bytes,
        error_message = EXCLUDED.error_message,
        is_favorite = EXCLUDED.is_favorite,
        tags = EXCLUDED.tags,
        model_info = COALESCE(EXCLUDED.model_info, public.meetings.model_info),
        updated_at = v_now;

    IF p_has_transcript THEN
        INSERT INTO public.transcripts (
            meeting_id, bahasa, isi, segmen, created_at, updated_at
        )
        VALUES (
            p_meeting_id,
            COALESCE(NULLIF(trim(p_bahasa), ''), 'id'),
            COALESCE(p_isi, ''),
            COALESCE(p_segmen, '[]'::jsonb),
            v_now,
            v_now
        )
        ON CONFLICT (meeting_id) DO UPDATE SET
            bahasa = EXCLUDED.bahasa,
            isi = EXCLUDED.isi,
            segmen = EXCLUDED.segmen,
            updated_at = v_now;
    END IF;

    IF p_has_summary THEN
        INSERT INTO public.summaries (
            meeting_id, ringkasan, keputusan, action_items, visual_json,
            poin_utama, risiko, angka_kunci, analysis, created_at, updated_at
        )
        VALUES (
            p_meeting_id,
            COALESCE(p_ringkasan, ''),
            COALESCE(p_keputusan, '[]'::jsonb),
            COALESCE(p_action_items, '[]'::jsonb),
            p_visual_json,
            COALESCE(p_poin_utama, '[]'::jsonb),
            COALESCE(p_risiko, '[]'::jsonb),
            COALESCE(p_angka_kunci, '[]'::jsonb),
            p_analysis,
            v_now,
            v_now
        )
        ON CONFLICT (meeting_id) DO UPDATE SET
            ringkasan = EXCLUDED.ringkasan,
            keputusan = EXCLUDED.keputusan,
            action_items = EXCLUDED.action_items,
            visual_json = COALESCE(EXCLUDED.visual_json, public.summaries.visual_json),
            poin_utama = EXCLUDED.poin_utama,
            risiko = EXCLUDED.risiko,
            angka_kunci = EXCLUDED.angka_kunci,
            analysis = COALESCE(EXCLUDED.analysis, public.summaries.analysis),
            updated_at = v_now;
    END IF;

    RETURN public._serialize_personal_meeting(p_meeting_id);
END;
$$;

REVOKE ALL ON FUNCTION public.upsert_personal_meeting_bundle(
    uuid, uuid, text, integer, text, text, bigint, text, boolean, jsonb, jsonb,
    boolean, text, text, jsonb,
    boolean, text, jsonb, jsonb, jsonb, jsonb, jsonb, jsonb, jsonb
) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.upsert_personal_meeting_bundle(
    uuid, uuid, text, integer, text, text, bigint, text, boolean, jsonb, jsonb,
    boolean, text, text, jsonb,
    boolean, text, jsonb, jsonb, jsonb, jsonb, jsonb, jsonb, jsonb
) TO service_role;

-- List active account's meetings sorted newest-first
CREATE OR REPLACE FUNCTION public.list_personal_meetings(
    p_owner_user_id uuid,
    p_limit integer DEFAULT 200
)
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT COALESCE(
        jsonb_agg(public._serialize_personal_meeting(sub.id) ORDER BY sub.created_at DESC NULLS LAST),
        '[]'::jsonb
    )
    FROM (
        SELECT m.id, m.created_at
        FROM public.meetings m
        WHERE m.user_id = p_owner_user_id
        ORDER BY m.created_at DESC NULLS LAST
        LIMIT LEAST(GREATEST(COALESCE(p_limit, 200), 1), 500)
    ) sub;
$$;

REVOKE ALL ON FUNCTION public.list_personal_meetings(uuid, integer) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.list_personal_meetings(uuid, integer) TO service_role;

-- Fetch single meeting detail with explicit ownership status
CREATE OR REPLACE FUNCTION public.get_personal_meeting_detail(
    p_owner_user_id uuid,
    p_meeting_id uuid
)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_owner uuid;
BEGIN
    SELECT m.user_id INTO v_owner
    FROM public.meetings m
    WHERE m.id = p_meeting_id;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('status', 'not_found');
    END IF;

    IF v_owner <> p_owner_user_id THEN
        RETURN jsonb_build_object('status', 'forbidden');
    END IF;

    RETURN jsonb_build_object(
        'status', 'ok',
        'meeting', public._serialize_personal_meeting(p_meeting_id)
    );
END;
$$;

REVOKE ALL ON FUNCTION public.get_personal_meeting_detail(uuid, uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.get_personal_meeting_detail(uuid, uuid) TO service_role;

-- Targeted updates for existing meetings (title, favorite, tags, segments, summary, analysis, visual)
CREATE OR REPLACE FUNCTION public.update_personal_meeting_fields(
    p_owner_user_id uuid,
    p_meeting_id uuid,
    p_patch jsonb
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_owner uuid;
    v_now timestamptz := now();
BEGIN
    SELECT m.user_id INTO v_owner
    FROM public.meetings m
    WHERE m.id = p_meeting_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('status', 'not_found');
    END IF;

    IF v_owner <> p_owner_user_id THEN
        RETURN jsonb_build_object('status', 'forbidden');
    END IF;

    UPDATE public.meetings
    SET
        judul = CASE
            WHEN p_patch ? 'judul' AND NULLIF(trim(p_patch->>'judul'), '') IS NOT NULL
                THEN trim(p_patch->>'judul')
            ELSE judul
        END,
        is_favorite = CASE
            WHEN p_patch ? 'is_favorite' THEN (p_patch->>'is_favorite')::boolean
            ELSE is_favorite
        END,
        tags = CASE
            WHEN p_patch ? 'tags' THEN COALESCE(p_patch->'tags', '[]'::jsonb)
            ELSE tags
        END,
        model_info = CASE
            WHEN p_patch ? 'model_info' THEN p_patch->'model_info'
            ELSE model_info
        END,
        updated_at = v_now
    WHERE id = p_meeting_id;

    IF p_patch ? 'segmen' OR p_patch ? 'isi' OR p_patch ? 'bahasa' THEN
        INSERT INTO public.transcripts (meeting_id, bahasa, isi, segmen, created_at, updated_at)
        VALUES (
            p_meeting_id,
            COALESCE(NULLIF(trim(p_patch->>'bahasa'), ''), 'id'),
            COALESCE(p_patch->>'isi', ''),
            COALESCE(p_patch->'segmen', '[]'::jsonb),
            v_now,
            v_now
        )
        ON CONFLICT (meeting_id) DO UPDATE SET
            bahasa = CASE WHEN p_patch ? 'bahasa' THEN COALESCE(NULLIF(trim(p_patch->>'bahasa'), ''), public.transcripts.bahasa) ELSE public.transcripts.bahasa END,
            isi = CASE WHEN p_patch ? 'isi' THEN COALESCE(p_patch->>'isi', '') ELSE public.transcripts.isi END,
            segmen = CASE WHEN p_patch ? 'segmen' THEN COALESCE(p_patch->'segmen', '[]'::jsonb) ELSE public.transcripts.segmen END,
            updated_at = v_now;
    END IF;

    IF p_patch ? 'ringkasan' OR p_patch ? 'keputusan' OR p_patch ? 'action_items'
        OR p_patch ? 'visual_json' OR p_patch ? 'poin_utama' OR p_patch ? 'risiko'
        OR p_patch ? 'angka_kunci' OR p_patch ? 'analysis' THEN
        INSERT INTO public.summaries (
            meeting_id, ringkasan, keputusan, action_items, visual_json,
            poin_utama, risiko, angka_kunci, analysis, created_at, updated_at
        )
        VALUES (
            p_meeting_id,
            COALESCE(p_patch->>'ringkasan', ''),
            COALESCE(p_patch->'keputusan', '[]'::jsonb),
            COALESCE(p_patch->'action_items', '[]'::jsonb),
            p_patch->'visual_json',
            COALESCE(p_patch->'poin_utama', '[]'::jsonb),
            COALESCE(p_patch->'risiko', '[]'::jsonb),
            COALESCE(p_patch->'angka_kunci', '[]'::jsonb),
            p_patch->'analysis',
            v_now,
            v_now
        )
        ON CONFLICT (meeting_id) DO UPDATE SET
            ringkasan = CASE WHEN p_patch ? 'ringkasan' THEN COALESCE(p_patch->>'ringkasan', '') ELSE public.summaries.ringkasan END,
            keputusan = CASE WHEN p_patch ? 'keputusan' THEN COALESCE(p_patch->'keputusan', '[]'::jsonb) ELSE public.summaries.keputusan END,
            action_items = CASE WHEN p_patch ? 'action_items' THEN COALESCE(p_patch->'action_items', '[]'::jsonb) ELSE public.summaries.action_items END,
            visual_json = CASE WHEN p_patch ? 'visual_json' THEN p_patch->'visual_json' ELSE public.summaries.visual_json END,
            poin_utama = CASE WHEN p_patch ? 'poin_utama' THEN COALESCE(p_patch->'poin_utama', '[]'::jsonb) ELSE public.summaries.poin_utama END,
            risiko = CASE WHEN p_patch ? 'risiko' THEN COALESCE(p_patch->'risiko', '[]'::jsonb) ELSE public.summaries.risiko END,
            angka_kunci = CASE WHEN p_patch ? 'angka_kunci' THEN COALESCE(p_patch->'angka_kunci', '[]'::jsonb) ELSE public.summaries.angka_kunci END,
            analysis = CASE WHEN p_patch ? 'analysis' THEN p_patch->'analysis' ELSE public.summaries.analysis END,
            updated_at = v_now;
    END IF;

    RETURN jsonb_build_object(
        'status', 'ok',
        'meeting', public._serialize_personal_meeting(p_meeting_id)
    );
END;
$$;

REVOKE ALL ON FUNCTION public.update_personal_meeting_fields(uuid, uuid, jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.update_personal_meeting_fields(uuid, uuid, jsonb) TO service_role;

-- Ownership-checked delete
CREATE OR REPLACE FUNCTION public.delete_personal_meeting(
    p_owner_user_id uuid,
    p_meeting_id uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_owner uuid;
BEGIN
    SELECT m.user_id INTO v_owner
    FROM public.meetings m
    WHERE m.id = p_meeting_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('status', 'not_found');
    END IF;

    IF v_owner <> p_owner_user_id THEN
        RETURN jsonb_build_object('status', 'forbidden');
    END IF;

    DELETE FROM public.meetings WHERE id = p_meeting_id;
    RETURN jsonb_build_object('status', 'ok', 'deleted', true);
END;
$$;

REVOKE ALL ON FUNCTION public.delete_personal_meeting(uuid, uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.delete_personal_meeting(uuid, uuid) TO service_role;

-- ============================================================================
-- 5. OPTIONAL MANUAL TEMPLATE: Safely linking an existing legacy Supabase user
--    to their verified Firebase UID (replace placeholders before running).
-- ============================================================================
-- Step A: Inspect existing Supabase user & verify ownership with the user first:
--   SELECT id AS supabase_user_id, email, created_at
--   FROM auth.users
--   WHERE lower(email) = lower('USER_EMAIL_HERE');
--
-- Step B: Verify their Firebase UID from Firebase Console -> Authentication -> Users,
--   then insert the verified 1:1 link:
--   INSERT INTO public.firebase_identity_links (
--       firebase_uid, supabase_user_id, firebase_email, link_method, verified_at
--   )
--   VALUES (
--       'FIREBASE_UID_HERE',
--       'SUPABASE_AUTH_USER_UUID_HERE'::uuid,
--       'USER_EMAIL_HERE',
--       'manual_admin_sql',
--       now()
--   )
--   ON CONFLICT DO NOTHING;
