create extension if not exists pgcrypto;

create table app_users (
    id uuid primary key default gen_random_uuid(),
    supabase_user_id uuid not null unique,
    email varchar(320) not null unique,
    display_name varchar(160),
    status varchar(32) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_app_users_status check (status in ('ACTIVE', 'FROZEN', 'BANNED'))
);

create table roles (
    code varchar(64) primary key,
    description varchar(255) not null
);

insert into roles (code, description) values
    ('candidate_free', 'Free candidate account'),
    ('candidate_premium', 'Premium candidate account'),
    ('employer_free', 'Free employer account'),
    ('employer_premium', 'Premium employer account'),
    ('organizer', 'Organizer account'),
    ('admin', 'Administrator account');

create table user_roles (
    user_id uuid not null references app_users(id) on delete cascade,
    role_code varchar(64) not null references roles(code),
    created_at timestamptz not null default now(),
    primary key (user_id, role_code)
);

create table profiles (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null unique references app_users(id) on delete cascade,
    headline varchar(180),
    bio text,
    reputation_score integer not null default 0,
    total_exp bigint not null default 0,
    current_level integer not null default 1,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_profiles_reputation_score check (reputation_score between 0 and 100),
    constraint ck_profiles_total_exp check (total_exp >= 0),
    constraint ck_profiles_current_level check (current_level >= 1)
);

create table audit_logs (
    id uuid primary key default gen_random_uuid(),
    actor_user_id uuid references app_users(id),
    action varchar(120) not null,
    entity_type varchar(120),
    entity_id uuid,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create index idx_app_users_status on app_users(status);
create index idx_app_users_supabase_user_id on app_users(supabase_user_id);
create index idx_user_roles_role_code on user_roles(role_code);
create index idx_profiles_reputation_score on profiles(reputation_score);
create index idx_profiles_current_level on profiles(current_level);
create index idx_audit_logs_action on audit_logs(action);
create index idx_audit_logs_created_at on audit_logs(created_at);
