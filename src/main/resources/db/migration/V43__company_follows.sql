-- V43: Candidates can "follow" a partner (company/CLB) to bookmark it.
-- Simple many-to-many join; the (user_id, company_id) primary key makes a
-- follow idempotent and a duplicate follow a no-op (INSERT ... ON CONFLICT).
set lock_timeout = '5s';

create table if not exists company_follows (
    user_id uuid not null references app_users(id) on delete cascade,
    company_id uuid not null references companies(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (user_id, company_id)
);

create index if not exists idx_company_follows_company on company_follows(company_id);
create index if not exists idx_company_follows_user on company_follows(user_id);
