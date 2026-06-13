create table candidate_registration_attempts (
    id uuid primary key default gen_random_uuid(),
    supabase_user_id uuid not null,
    email varchar(320) not null,
    display_name varchar(160) not null,
    student_email varchar(320) not null,
    otp_hash_sha256 varchar(64) not null,
    status varchar(30) not null default 'PENDING',
    attempts integer not null default 0,
    max_attempts integer not null default 5,
    expires_at timestamptz not null,
    verified_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_candidate_registration_attempts_status
        check (status in ('PENDING', 'VERIFIED', 'EXPIRED', 'LOCKED', 'REVOKED')),
    constraint ck_candidate_registration_attempts_attempts check (attempts >= 0 and max_attempts > 0)
);

create index idx_candidate_registration_attempts_supabase_user_id
    on candidate_registration_attempts(supabase_user_id);
create index idx_candidate_registration_attempts_email
    on candidate_registration_attempts(email);
create index idx_candidate_registration_attempts_status
    on candidate_registration_attempts(status);
create index idx_candidate_registration_attempts_expires_at
    on candidate_registration_attempts(expires_at);

create unique index ux_candidate_registration_attempts_pending_supabase_user
    on candidate_registration_attempts(supabase_user_id)
    where status = 'PENDING';

create unique index ux_candidate_registration_attempts_pending_email
    on candidate_registration_attempts(lower(email))
    where status = 'PENDING';

create trigger trg_candidate_registration_attempts_updated_at
before update on candidate_registration_attempts
for each row execute function set_updated_at();
