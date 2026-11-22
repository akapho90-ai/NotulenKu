-- ============================================================================
-- AI Notulen Rapat: Persistent per-user rate limit for meeting-api AI calls
-- Review carefully before applying in Supabase SQL Editor or `supabase db push`.
-- Replaces the in-memory Map in the Edge Function, which does not work across
-- multiple function instances.
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.ai_rate_limit_buckets (
    uid text PRIMARY KEY,
    window_start timestamptz NOT NULL DEFAULT now(),
    request_count integer NOT NULL DEFAULT 0 CHECK (request_count >= 0)
);

-- Only the service role (Edge Function) may access this table.
ALTER TABLE public.ai_rate_limit_buckets ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.ai_rate_limit_buckets FROM PUBLIC, anon, authenticated;
GRANT ALL ON TABLE public.ai_rate_limit_buckets TO service_role;

-- Fixed-window counter. Returns TRUE when the request is allowed, FALSE when the
-- user has already used p_max requests inside the current p_window_seconds window.
-- A single INSERT ... ON CONFLICT statement keeps the check-and-increment atomic.
CREATE OR REPLACE FUNCTION public.consume_ai_rate_limit(
    p_uid text,
    p_max integer,
    p_window_seconds integer
)
RETURNS boolean
LANGUAGE plpgsql
SET search_path = public
AS $$
BEGIN
    INSERT INTO public.ai_rate_limit_buckets AS b (uid, window_start, request_count)
    VALUES (p_uid, now(), 1)
    ON CONFLICT (uid) DO UPDATE SET
        window_start = CASE
            WHEN b.window_start <= now() - make_interval(secs => p_window_seconds) THEN now()
            ELSE b.window_start
        END,
        request_count = CASE
            WHEN b.window_start <= now() - make_interval(secs => p_window_seconds) THEN 1
            ELSE b.request_count + 1
        END
    WHERE b.window_start <= now() - make_interval(secs => p_window_seconds)
       OR b.request_count < p_max;

    RETURN FOUND;
END;
$$;

REVOKE ALL ON FUNCTION public.consume_ai_rate_limit(text, integer, integer) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.consume_ai_rate_limit(text, integer, integer) TO service_role;
