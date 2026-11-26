-- Apply after 001. Existing links were created by the original Firebase project.
BEGIN;

ALTER TABLE public.firebase_identity_links
    ADD COLUMN firebase_project_id text NOT NULL DEFAULT 'gen-lang-client-0830960267';

ALTER TABLE public.firebase_identity_links
    DROP CONSTRAINT firebase_identity_links_pkey,
    DROP CONSTRAINT firebase_identity_links_supabase_user_id_key,
    ADD PRIMARY KEY (firebase_project_id, firebase_uid),
    ADD UNIQUE (firebase_project_id, supabase_user_id);

-- An existing ID is returned only for its owner. A retry never overwrites edits or
-- creates child rows on another account's meeting, including on concurrent inserts.
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
    ON CONFLICT (id) DO NOTHING;

    IF NOT FOUND THEN
        SELECT user_id INTO v_existing_owner
        FROM public.meetings
        WHERE id = p_meeting_id
        FOR UPDATE;

        IF NOT FOUND OR v_existing_owner IS DISTINCT FROM p_owner_user_id THEN
            RAISE EXCEPTION 'FORBIDDEN_MEETING_OWNERSHIP' USING ERRCODE = '42501';
        END IF;

        RETURN public._serialize_personal_meeting(p_meeting_id);
    END IF;

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

COMMIT;
