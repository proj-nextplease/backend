-- Gamification layer for candidates: daily activity streak + daily/weekly quests.
-- EXP rewards are granted through ExpService (append-only exp_events), never written here directly.

-- Supabase poolers enforce a short statement_timeout; the ALTER below needs an
-- ACCESS EXCLUSIVE lock on profiles, so give it room to acquire the lock.
set local lock_timeout = '90s';
set local statement_timeout = '180s';

-- Adding constant-default columns is metadata-only in PostgreSQL 11+ (no table rewrite).
alter table profiles add column if not exists current_streak   integer not null default 0;
alter table profiles add column if not exists longest_streak    integer not null default 0;
alter table profiles add column if not exists last_active_date  date;
alter table profiles add column if not exists streak_freezes    integer not null default 0;

-- Daily quests: one row per (profile, quest_key, quest_date). Resets each calendar day (Asia/Ho_Chi_Minh).
create table if not exists daily_quest_progress (
    id           uuid primary key default gen_random_uuid(),
    profile_id   uuid        not null references profiles(id) on delete cascade,
    quest_key    varchar(40) not null,
    quest_date   date        not null,
    progress     integer     not null default 0,
    target       integer     not null default 1,
    completed_at timestamptz,
    exp_awarded  boolean     not null default false,
    created_at   timestamptz not null default now(),
    constraint uq_daily_quest unique (profile_id, quest_key, quest_date),
    constraint ck_daily_quest_progress check (progress >= 0)
);
create index if not exists ix_daily_quest_profile_date on daily_quest_progress(profile_id, quest_date);

-- Weekly quests: one row per (profile, quest_key, week_start). week_start = Monday (Asia/Ho_Chi_Minh).
create table if not exists weekly_quest_progress (
    id           uuid primary key default gen_random_uuid(),
    profile_id   uuid        not null references profiles(id) on delete cascade,
    quest_key    varchar(40) not null,
    week_start   date        not null,
    progress     integer     not null default 0,
    target       integer     not null default 1,
    completed_at timestamptz,
    exp_awarded  boolean     not null default false,
    created_at   timestamptz not null default now(),
    constraint uq_weekly_quest unique (profile_id, quest_key, week_start),
    constraint ck_weekly_quest_progress check (progress >= 0)
);
create index if not exists ix_weekly_quest_profile_week on weekly_quest_progress(profile_id, week_start);
