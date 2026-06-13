-- V5: Store password temporarily in registration attempts so the frontend
-- no longer needs to call supabase.auth.signUp() directly (avoids 429 rate limit).
-- The backend will create the Supabase auth user server-side after OTP verification.

alter table candidate_registration_attempts
    add column if not exists password_hash varchar(255),
    alter column supabase_user_id drop not null;

-- The pending-supabase-user unique index is no longer useful because
-- supabase_user_id is NULL until OTP verification completes.
drop index if exists ux_candidate_registration_attempts_pending_supabase_user;
