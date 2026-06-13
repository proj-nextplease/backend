create extension if not exists pgcrypto;

create or replace function set_updated_at()
returns trigger as $$
begin
    new.updated_at = now();
    return new;
end;
$$ language plpgsql;

alter table app_users drop constraint if exists ck_app_users_status;
alter table app_users
    add constraint ck_app_users_status
        check (status in ('ACTIVE', 'FROZEN', 'BANNED', 'PENDING_VERIFY', 'DELETED'));

alter table app_users
    add column if not exists auth_provider varchar(40) not null default 'supabase',
    add column if not exists student_email_verified boolean not null default false,
    add column if not exists premium_until timestamptz,
    add column if not exists last_login_at timestamptz,
    add column if not exists deleted_at timestamptz;

alter table profiles
    add column if not exists public_slug varchar(80),
    add column if not exists major varchar(150),
    add column if not exists avatar_url text,
    add column if not exists cover_banner_url text,
    add column if not exists profile_completion_pct integer not null default 0,
    add column if not exists selected_theme varchar(50),
    add column if not exists badge_glow_enabled boolean not null default false,
    add column if not exists visibility jsonb not null default '{}'::jsonb,
    add column if not exists deleted_at timestamptz;

alter table profiles drop constraint if exists ck_profiles_completion_pct;
alter table profiles
    add constraint ck_profiles_completion_pct check (profile_completion_pct between 0 and 100);

create unique index if not exists ux_profiles_public_slug on profiles (public_slug) where public_slug is not null;
create index if not exists idx_app_users_auth_provider on app_users(auth_provider);
create index if not exists idx_app_users_student_verified on app_users(student_email_verified);
create index if not exists idx_app_users_premium_until on app_users(premium_until);

create table schools (
    id uuid primary key default gen_random_uuid(),
    name varchar(150) not null unique,
    email_domain varchar(150) unique,
    country_code char(2) not null default 'VN',
    is_testbed boolean not null default true,
    verification_status varchar(30) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    constraint ck_schools_verification_status
        check (verification_status in ('ACTIVE', 'INACTIVE', 'PENDING_REVIEW'))
);

alter table profiles
    add column if not exists school_id uuid references schools(id);

create index if not exists idx_profiles_school_id on profiles(school_id);
create index if not exists idx_schools_email_domain on schools(email_domain);
create index if not exists idx_schools_is_testbed on schools(is_testbed);

create table companies (
    id uuid primary key default gen_random_uuid(),
    owner_user_id uuid not null references app_users(id),
    name varchar(200) not null,
    company_type varchar(50) not null,
    description text,
    website_url text,
    logo_url text,
    verification_status varchar(30) not null default 'PENDING',
    monthly_job_quota integer not null default 3,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    constraint ck_companies_type
        check (company_type in ('STARTUP', 'SME', 'AGENCY', 'CLUB', 'EVENT_ORGANIZER', 'SCHOOL', 'ENTERPRISE')),
    constraint ck_companies_verification_status
        check (verification_status in ('PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED')),
    constraint ck_companies_monthly_job_quota check (monthly_job_quota >= 0)
);

create index if not exists idx_companies_owner_user_id on companies(owner_user_id);
create index if not exists idx_companies_name on companies(name);
create index if not exists idx_companies_type on companies(company_type);
create index if not exists idx_companies_verification_status on companies(verification_status);

create table authority_nodes (
    id uuid primary key default gen_random_uuid(),
    company_id uuid references companies(id),
    user_id uuid not null references app_users(id),
    node_type varchar(50) not null,
    status varchar(30) not null default 'PENDING',
    approved_by uuid references app_users(id),
    approved_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    constraint ck_authority_nodes_type
        check (node_type in ('CLUB_LEADER', 'EVENT_HEAD', 'MENTOR', 'COMPANY_MANAGER', 'SCHOOL_ADMIN')),
    constraint ck_authority_nodes_status
        check (status in ('PENDING', 'ACTIVE', 'REJECTED', 'SUSPENDED'))
);

create index if not exists idx_authority_nodes_company_id on authority_nodes(company_id);
create index if not exists idx_authority_nodes_user_id on authority_nodes(user_id);
create index if not exists idx_authority_nodes_node_type on authority_nodes(node_type);
create index if not exists idx_authority_nodes_status on authority_nodes(status);

create table skills (
    id uuid primary key default gen_random_uuid(),
    name varchar(100) not null unique,
    normalized_name varchar(100) not null unique,
    category varchar(80),
    created_at timestamptz not null default now()
);

create table profile_skills (
    profile_id uuid not null references profiles(id) on delete cascade,
    skill_id uuid not null references skills(id),
    proficiency_level varchar(30) not null default 'BEGINNER',
    created_at timestamptz not null default now(),
    primary key (profile_id, skill_id),
    constraint ck_profile_skills_proficiency
        check (proficiency_level in ('BEGINNER', 'INTERMEDIATE', 'ADVANCED', 'EXPERT'))
);

create index if not exists idx_profile_skills_skill_id on profile_skills(skill_id);

create table profile_links (
    id uuid primary key default gen_random_uuid(),
    profile_id uuid not null references profiles(id) on delete cascade,
    link_type varchar(50) not null,
    label varchar(120),
    url text not null,
    display_order integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_profile_links_type
        check (link_type in ('PORTFOLIO', 'LINKEDIN', 'GITHUB', 'FACEBOOK', 'TIKTOK', 'OTHER'))
);

create index if not exists idx_profile_links_profile_id on profile_links(profile_id);

create table file_assets (
    id uuid primary key default gen_random_uuid(),
    owner_user_id uuid not null references app_users(id),
    storage_provider varchar(50) not null default 'supabase_storage',
    storage_bucket varchar(120),
    storage_path text,
    url text not null,
    mime_type varchar(100) not null,
    size_bytes bigint not null,
    checksum_sha256 varchar(64),
    visibility varchar(20) not null default 'PRIVATE',
    created_at timestamptz not null default now(),
    deleted_at timestamptz,
    constraint ck_file_assets_size check (size_bytes >= 0),
    constraint ck_file_assets_visibility check (visibility in ('PUBLIC', 'PRIVATE'))
);

create index if not exists idx_file_assets_owner_user_id on file_assets(owner_user_id);
create index if not exists idx_file_assets_mime_type on file_assets(mime_type);

create table experiences (
    id uuid primary key default gen_random_uuid(),
    profile_id uuid not null references profiles(id) on delete cascade,
    project_name varchar(200) not null,
    position varchar(150) not null,
    category varchar(50) not null,
    description text,
    proof_image_url text,
    proof_link text,
    verification_status varchar(30) not null default 'PENDING',
    verified_by uuid references app_users(id),
    verified_at timestamptz,
    reject_reason text,
    source_type varchar(50),
    source_id uuid,
    started_at date,
    ended_at date,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    constraint ck_experiences_category
        check (category in ('CLUB_SMALL', 'SCHOOL_CAMPAIGN', 'COMPANY_PROJECT', 'SHORT_INTERNSHIP', 'FREELANCE_GIG', 'QUEST')),
    constraint ck_experiences_verification_status
        check (verification_status in ('PENDING', 'APPROVED', 'REJECTED', 'NEEDS_MORE_EVIDENCE'))
);

create index if not exists idx_experiences_profile_id on experiences(profile_id);
create index if not exists idx_experiences_category on experiences(category);
create index if not exists idx_experiences_status on experiences(verification_status);
create index if not exists idx_experiences_verified_by on experiences(verified_by);
create index if not exists idx_experiences_source on experiences(source_type, source_id);

create table experience_assets (
    experience_id uuid not null references experiences(id) on delete cascade,
    file_asset_id uuid not null references file_assets(id),
    asset_role varchar(40) not null default 'PROOF',
    created_at timestamptz not null default now(),
    primary key (experience_id, file_asset_id),
    constraint ck_experience_assets_role check (asset_role in ('PROOF', 'CERTIFICATE', 'PHOTO', 'OTHER'))
);

create table verification_sources (
    id uuid primary key default gen_random_uuid(),
    experience_id uuid references experiences(id) on delete cascade,
    source_type varchar(50) not null,
    source_id uuid,
    status varchar(30) not null default 'PENDING',
    evidence jsonb not null default '{}'::jsonb,
    checked_by uuid references app_users(id),
    checked_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_verification_sources_type
        check (source_type in ('ORGANIZER_APPROVAL', 'QR_CHECKIN', 'PROOF_FILE', 'SCHOOL_EMAIL', 'COMPANY_EMAIL', 'ADMIN_IMPORT')),
    constraint ck_verification_sources_status
        check (status in ('PENDING', 'PASSED', 'FAILED', 'WAIVED'))
);

create index if not exists idx_verification_sources_experience_id on verification_sources(experience_id);
create index if not exists idx_verification_sources_type on verification_sources(source_type);
create index if not exists idx_verification_sources_status on verification_sources(status);

create table verification_reviews (
    id uuid primary key default gen_random_uuid(),
    experience_id uuid not null references experiences(id) on delete cascade,
    reviewer_user_id uuid not null references app_users(id),
    decision varchar(30) not null,
    note text,
    created_at timestamptz not null default now(),
    constraint ck_verification_reviews_decision check (decision in ('APPROVED', 'REJECTED', 'REQUEST_MORE_EVIDENCE'))
);

create index if not exists idx_verification_reviews_experience_id on verification_reviews(experience_id);
create index if not exists idx_verification_reviews_reviewer_user_id on verification_reviews(reviewer_user_id);

create table reputation_events (
    id uuid primary key default gen_random_uuid(),
    profile_id uuid not null references profiles(id) on delete cascade,
    points integer not null,
    reason_code varchar(80) not null,
    source_type varchar(50),
    source_id uuid,
    created_by uuid references app_users(id),
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create index if not exists idx_reputation_events_profile_id on reputation_events(profile_id);
create index if not exists idx_reputation_events_reason_code on reputation_events(reason_code);
create index if not exists idx_reputation_events_created_at on reputation_events(created_at);
create index if not exists idx_reputation_events_source on reputation_events(source_type, source_id);
create unique index if not exists ux_reputation_once_per_source
    on reputation_events(profile_id, reason_code, source_type, source_id)
    where source_type is not null and source_id is not null;

create table exp_events (
    id uuid primary key default gen_random_uuid(),
    profile_id uuid not null references profiles(id) on delete cascade,
    points integer not null,
    reason_code varchar(80) not null,
    source_type varchar(50),
    source_id uuid,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint ck_exp_events_points check (points >= 0)
);

create index if not exists idx_exp_events_profile_id on exp_events(profile_id);
create index if not exists idx_exp_events_reason_code on exp_events(reason_code);
create index if not exists idx_exp_events_created_at on exp_events(created_at);
create index if not exists idx_exp_events_source on exp_events(source_type, source_id);
create unique index if not exists ux_exp_once_per_source
    on exp_events(profile_id, reason_code, source_type, source_id)
    where source_type is not null and source_id is not null;

create table jobs (
    id uuid primary key default gen_random_uuid(),
    company_id uuid not null references companies(id),
    title varchar(200) not null,
    description text not null,
    job_type varchar(50) not null,
    compensation numeric(12, 2),
    compensation_currency char(3) not null default 'VND',
    min_req_rs integer not null default 0,
    location varchar(200),
    is_remote boolean not null default false,
    capacity integer,
    deadline_at timestamptz,
    status varchar(30) not null default 'DRAFT',
    created_by uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    constraint ck_jobs_type check (job_type in ('FREELANCE', 'PART_TIME', 'INTERNSHIP', 'EVENT_STAFF', 'MICRO_INTERNSHIP')),
    constraint ck_jobs_min_req_rs check (min_req_rs between 0 and 100),
    constraint ck_jobs_status check (status in ('DRAFT', 'OPEN', 'CLOSED', 'COMPLETED', 'CANCELLED')),
    constraint ck_jobs_capacity check (capacity is null or capacity > 0)
);

create index if not exists idx_jobs_company_id on jobs(company_id);
create index if not exists idx_jobs_title on jobs(title);
create index if not exists idx_jobs_type on jobs(job_type);
create index if not exists idx_jobs_min_req_rs on jobs(min_req_rs);
create index if not exists idx_jobs_remote on jobs(is_remote);
create index if not exists idx_jobs_deadline_at on jobs(deadline_at);
create index if not exists idx_jobs_status on jobs(status);
create index if not exists idx_jobs_created_by on jobs(created_by);

create table job_skills (
    job_id uuid not null references jobs(id) on delete cascade,
    skill_id uuid not null references skills(id),
    required_level varchar(30),
    created_at timestamptz not null default now(),
    primary key (job_id, skill_id)
);

create table saved_jobs (
    user_id uuid not null references app_users(id) on delete cascade,
    job_id uuid not null references jobs(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (user_id, job_id)
);

create table applications (
    id uuid primary key default gen_random_uuid(),
    job_id uuid not null references jobs(id) on delete cascade,
    candidate_id uuid not null references app_users(id) on delete cascade,
    status varchar(40) not null default 'SUBMITTED',
    cover_note text,
    eligibility_snapshot jsonb not null default '{}'::jsonb,
    applied_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    withdrawn_at timestamptz,
    constraint ck_applications_status
        check (status in ('SUBMITTED', 'VIEWED', 'SHORTLISTED', 'ACCEPTED', 'REJECTED', 'WITHDRAWN')),
    constraint ux_applications_job_candidate unique (job_id, candidate_id)
);

create index if not exists idx_applications_job_id on applications(job_id);
create index if not exists idx_applications_candidate_id on applications(candidate_id);
create index if not exists idx_applications_status on applications(status);
create index if not exists idx_applications_applied_at on applications(applied_at);

create table quests (
    id uuid primary key default gen_random_uuid(),
    company_id uuid not null references companies(id),
    title varchar(200) not null,
    description text not null,
    category varchar(50) not null,
    min_req_rs integer not null default 0,
    exp_reward integer not null default 0,
    np_reward integer not null default 0,
    capacity integer,
    starts_at timestamptz,
    ends_at timestamptz,
    status varchar(30) not null default 'DRAFT',
    created_by uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    constraint ck_quests_category
        check (category in ('SMALL_EVENT', 'SCHOOL_CAMPAIGN', 'COMPANY_PROJECT', 'SHORT_INTERNSHIP', 'FREELANCE_GIG')),
    constraint ck_quests_min_req_rs check (min_req_rs between 0 and 100),
    constraint ck_quests_rewards check (exp_reward >= 0 and np_reward >= 0),
    constraint ck_quests_capacity check (capacity is null or capacity > 0),
    constraint ck_quests_status check (status in ('DRAFT', 'OPEN', 'CLOSED', 'COMPLETED', 'CANCELLED'))
);

create index if not exists idx_quests_company_id on quests(company_id);
create index if not exists idx_quests_category on quests(category);
create index if not exists idx_quests_min_req_rs on quests(min_req_rs);
create index if not exists idx_quests_status on quests(status);
create index if not exists idx_quests_created_by on quests(created_by);

create table quest_skills (
    quest_id uuid not null references quests(id) on delete cascade,
    skill_id uuid not null references skills(id),
    created_at timestamptz not null default now(),
    primary key (quest_id, skill_id)
);

create table quest_applications (
    id uuid primary key default gen_random_uuid(),
    quest_id uuid not null references quests(id) on delete cascade,
    candidate_id uuid not null references app_users(id) on delete cascade,
    status varchar(40) not null default 'SUBMITTED',
    eligibility_snapshot jsonb not null default '{}'::jsonb,
    applied_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_quest_applications_status
        check (status in ('SUBMITTED', 'ACCEPTED', 'REJECTED', 'COMPLETED', 'WITHDRAWN')),
    constraint ux_quest_applications_quest_candidate unique (quest_id, candidate_id)
);

create index if not exists idx_quest_applications_quest_id on quest_applications(quest_id);
create index if not exists idx_quest_applications_candidate_id on quest_applications(candidate_id);
create index if not exists idx_quest_applications_status on quest_applications(status);

create table daily_logs (
    id uuid primary key default gen_random_uuid(),
    profile_id uuid not null references profiles(id) on delete cascade,
    title varchar(200) not null,
    body text,
    log_date date not null default current_date,
    visibility varchar(20) not null default 'PRIVATE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    constraint ck_daily_logs_visibility check (visibility in ('PRIVATE', 'PUBLIC'))
);

create index if not exists idx_daily_logs_profile_id on daily_logs(profile_id);
create index if not exists idx_daily_logs_log_date on daily_logs(log_date);

create table wallets (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null unique references app_users(id) on delete cascade,
    np_balance integer not null default 0,
    locked_np_balance integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_wallets_balance check (np_balance >= 0 and locked_np_balance >= 0)
);

create table wallet_transactions (
    id uuid primary key default gen_random_uuid(),
    wallet_id uuid not null references wallets(id),
    amount_np integer not null,
    balance_after_np integer not null,
    transaction_type varchar(50) not null,
    reason varchar(120) not null,
    source_type varchar(50),
    source_id uuid,
    idempotency_key varchar(160),
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint ck_wallet_transactions_type
        check (transaction_type in ('TOPUP', 'SPEND', 'REWARD', 'REFUND', 'ADJUSTMENT', 'PREMIUM_PURCHASE', 'ITEM_PURCHASE')),
    constraint ck_wallet_transactions_balance_after check (balance_after_np >= 0)
);

create index if not exists idx_wallet_transactions_wallet_id on wallet_transactions(wallet_id);
create index if not exists idx_wallet_transactions_type on wallet_transactions(transaction_type);
create index if not exists idx_wallet_transactions_created_at on wallet_transactions(created_at);
create index if not exists idx_wallet_transactions_source on wallet_transactions(source_type, source_id);
create unique index if not exists ux_wallet_transactions_idempotency_key
    on wallet_transactions(idempotency_key)
    where idempotency_key is not null;

create table payment_requests (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id),
    amount_vnd integer not null,
    amount_np integer not null,
    transfer_content varchar(255) not null,
    qr_url text,
    provider varchar(50) not null,
    status varchar(30) not null default 'PENDING',
    provider_transaction_id varchar(255),
    expires_at timestamptz,
    paid_at timestamptz,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_payment_requests_amount check (amount_vnd >= 10000 and amount_np = amount_vnd),
    constraint ck_payment_requests_provider check (provider in ('PAYOS', 'VIETQR', 'MOCK')),
    constraint ck_payment_requests_status check (status in ('PENDING', 'PAID', 'FAILED', 'EXPIRED', 'RESOLVED'))
);

create index if not exists idx_payment_requests_user_id on payment_requests(user_id);
create index if not exists idx_payment_requests_transfer_content on payment_requests(transfer_content);
create index if not exists idx_payment_requests_status on payment_requests(status);
create unique index if not exists ux_payment_requests_provider_txn
    on payment_requests(provider_transaction_id)
    where provider_transaction_id is not null;

create table payment_webhook_events (
    id uuid primary key default gen_random_uuid(),
    provider varchar(50) not null,
    provider_event_id varchar(255) not null,
    signature_valid boolean not null default false,
    payload jsonb not null,
    processed_at timestamptz,
    processing_error text,
    created_at timestamptz not null default now(),
    constraint ux_payment_webhook_events_provider_event unique (provider, provider_event_id)
);

create table subscription_plans (
    code varchar(50) primary key,
    display_name varchar(120) not null,
    price_np integer not null,
    duration_days integer not null,
    is_active boolean not null default true,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_subscription_plans_price check (price_np >= 0),
    constraint ck_subscription_plans_duration check (duration_days > 0)
);

insert into subscription_plans (code, display_name, price_np, duration_days)
values ('candidate_premium_monthly', 'Candidate Premium Monthly', 40000, 30)
on conflict (code) do nothing;

create table subscriptions (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id),
    plan_code varchar(50) not null references subscription_plans(code),
    price_np integer not null,
    status varchar(30) not null default 'ACTIVE',
    starts_at timestamptz not null,
    expires_at timestamptz not null,
    cancelled_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_subscriptions_price check (price_np >= 0),
    constraint ck_subscriptions_status check (status in ('ACTIVE', 'EXPIRED', 'CANCELLED'))
);

create index if not exists idx_subscriptions_user_id on subscriptions(user_id);
create index if not exists idx_subscriptions_status on subscriptions(status);
create index if not exists idx_subscriptions_expires_at on subscriptions(expires_at);
create unique index if not exists ux_subscriptions_one_active
    on subscriptions(user_id, plan_code)
    where status = 'ACTIVE';

create table digital_items (
    id uuid primary key default gen_random_uuid(),
    name varchar(150) not null,
    category varchar(50) not null,
    price_np integer not null,
    asset_url text,
    is_active boolean not null default true,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_digital_items_category check (category in ('THEME', 'BANNER', 'EFFECT', 'BADGE_FRAME')),
    constraint ck_digital_items_price check (price_np >= 0)
);

create index if not exists idx_digital_items_category on digital_items(category);
create index if not exists idx_digital_items_is_active on digital_items(is_active);

create table user_items (
    user_id uuid not null references app_users(id) on delete cascade,
    item_id uuid not null references digital_items(id),
    purchased_at timestamptz not null default now(),
    equipped_at timestamptz,
    primary key (user_id, item_id)
);

create table ratings (
    id uuid primary key default gen_random_uuid(),
    application_id uuid references applications(id),
    quest_application_id uuid references quest_applications(id),
    rater_user_id uuid not null references app_users(id),
    candidate_user_id uuid not null references app_users(id),
    score integer not null,
    comment text,
    created_at timestamptz not null default now(),
    constraint ck_ratings_score check (score between 1 and 5),
    constraint ck_ratings_one_source
        check ((application_id is not null and quest_application_id is null)
            or (application_id is null and quest_application_id is not null))
);

create unique index if not exists ux_ratings_application_id on ratings(application_id) where application_id is not null;
create unique index if not exists ux_ratings_quest_application_id on ratings(quest_application_id) where quest_application_id is not null;
create index if not exists idx_ratings_candidate_user_id on ratings(candidate_user_id);
create index if not exists idx_ratings_rater_user_id on ratings(rater_user_id);

create table reports (
    id uuid primary key default gen_random_uuid(),
    reporter_user_id uuid not null references app_users(id),
    target_user_id uuid not null references app_users(id),
    report_type varchar(50) not null,
    status varchar(30) not null default 'PENDING',
    reason text,
    proof_url text,
    reviewed_by uuid references app_users(id),
    reviewed_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_reports_type check (report_type in ('NO_SHOW', 'FRAUD', 'ABUSE', 'PAYMENT_ISSUE', 'OTHER')),
    constraint ck_reports_status check (status in ('PENDING', 'APPROVED', 'REJECTED'))
);

create index if not exists idx_reports_reporter_user_id on reports(reporter_user_id);
create index if not exists idx_reports_target_user_id on reports(target_user_id);
create index if not exists idx_reports_type on reports(report_type);
create index if not exists idx_reports_status on reports(status);

create table fraud_flags (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id),
    report_id uuid references reports(id),
    reason_code varchar(80) not null,
    severity varchar(30) not null default 'HIGH',
    status varchar(30) not null default 'ACTIVE',
    evidence jsonb not null default '{}'::jsonb,
    created_by uuid not null references app_users(id),
    resolved_by uuid references app_users(id),
    resolved_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_fraud_flags_severity check (severity in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    constraint ck_fraud_flags_status check (status in ('ACTIVE', 'RESOLVED', 'FALSE_POSITIVE'))
);

create index if not exists idx_fraud_flags_user_id on fraud_flags(user_id);
create index if not exists idx_fraud_flags_status on fraud_flags(status);

create table notifications (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id) on delete cascade,
    notification_type varchar(50) not null,
    title varchar(200) not null,
    body text not null,
    data jsonb not null default '{}'::jsonb,
    is_read boolean not null default false,
    read_at timestamptz,
    created_at timestamptz not null default now()
);

create index if not exists idx_notifications_user_id on notifications(user_id);
create index if not exists idx_notifications_type on notifications(notification_type);
create index if not exists idx_notifications_is_read on notifications(is_read);
create index if not exists idx_notifications_created_at on notifications(created_at);

create table notification_preferences (
    user_id uuid primary key references app_users(id) on delete cascade,
    email_enabled boolean not null default true,
    push_enabled boolean not null default false,
    in_app_enabled boolean not null default true,
    preferences jsonb not null default '{}'::jsonb,
    updated_at timestamptz not null default now()
);

create table checkin_sessions (
    id uuid primary key default gen_random_uuid(),
    quest_id uuid references quests(id),
    job_id uuid references jobs(id),
    created_by uuid not null references app_users(id),
    token_hash varchar(255) not null unique,
    status varchar(30) not null default 'ACTIVE',
    expires_at timestamptz not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_checkin_sessions_status check (status in ('ACTIVE', 'EXPIRED', 'CANCELLED')),
    constraint ck_checkin_sessions_source
        check ((quest_id is not null and job_id is null) or (quest_id is null and job_id is not null))
);

create index if not exists idx_checkin_sessions_quest_id on checkin_sessions(quest_id);
create index if not exists idx_checkin_sessions_job_id on checkin_sessions(job_id);
create index if not exists idx_checkin_sessions_expires_at on checkin_sessions(expires_at);

create table attendances (
    id uuid primary key default gen_random_uuid(),
    checkin_session_id uuid not null references checkin_sessions(id) on delete cascade,
    user_id uuid not null references app_users(id) on delete cascade,
    checked_in_at timestamptz not null default now(),
    source_ip inet,
    device_fingerprint_hash varchar(128),
    metadata jsonb not null default '{}'::jsonb,
    constraint ux_attendances_session_user unique (checkin_session_id, user_id)
);

create index if not exists idx_attendances_checkin_session_id on attendances(checkin_session_id);
create index if not exists idx_attendances_user_id on attendances(user_id);
create index if not exists idx_attendances_checked_in_at on attendances(checked_in_at);

create table badges (
    id uuid primary key default gen_random_uuid(),
    name varchar(150) not null,
    description text,
    issuer_company_id uuid references companies(id),
    badge_type varchar(50) not null default 'ACHIEVEMENT',
    image_url text,
    criteria jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_badges_type check (badge_type in ('ACHIEVEMENT', 'SKILL', 'EVENT', 'TIER', 'VAAS'))
);

create index if not exists idx_badges_issuer_company_id on badges(issuer_company_id);
create index if not exists idx_badges_type on badges(badge_type);

create table user_badges (
    user_id uuid not null references app_users(id) on delete cascade,
    badge_id uuid not null references badges(id),
    source_type varchar(50),
    source_id uuid,
    issued_by uuid references app_users(id),
    issued_at timestamptz not null default now(),
    primary key (user_id, badge_id)
);

create table b2b_orders (
    id uuid primary key default gen_random_uuid(),
    company_id uuid not null references companies(id),
    service_type varchar(50) not null,
    amount_vnd integer not null,
    status varchar(30) not null default 'UNPAID',
    invoice_code varchar(80) unique,
    due_at timestamptz,
    paid_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_b2b_orders_service_type check (service_type in ('COMMISSION', 'JOB_POSTING', 'BRAND_QUEST', 'VAAS')),
    constraint ck_b2b_orders_amount check (amount_vnd >= 0),
    constraint ck_b2b_orders_status check (status in ('UNPAID', 'PAID', 'OVERDUE', 'CANCELLED'))
);

create index if not exists idx_b2b_orders_company_id on b2b_orders(company_id);
create index if not exists idx_b2b_orders_service_type on b2b_orders(service_type);
create index if not exists idx_b2b_orders_status on b2b_orders(status);

create table system_configs (
    config_key varchar(120) primary key,
    config_value jsonb not null,
    description text,
    is_sensitive boolean not null default false,
    updated_by uuid references app_users(id),
    updated_at timestamptz not null default now()
);

insert into system_configs (config_key, config_value, description)
values
    ('reputation.rules', '{"max_rs":100,"student_email_verified":10,"profile_completed":5,"quest_member_completed":5,"leader_completed":10,"cancel_within_24h":-10,"no_show":-20,"fraud_flag":-50}'::jsonb, 'Backend-owned RS scoring rules'),
    ('exp.rules', '{"small_event":100,"school_campaign":300,"company_project":500,"short_internship":500,"next_level_formula":"100 * currentLevel^1.2"}'::jsonb, 'Backend-owned EXP scoring rules'),
    ('wallet.rules', '{"minimum_topup_vnd":10000,"vnd_to_np_rate":1,"premium_monthly_np":40000,"five_star_reward_np":2000}'::jsonb, 'Backend-owned NP wallet rules')
on conflict (config_key) do nothing;

create table email_logs (
    id uuid primary key default gen_random_uuid(),
    user_id uuid references app_users(id),
    recipient_email varchar(320) not null,
    template_code varchar(80) not null,
    provider varchar(50),
    status varchar(30) not null default 'PENDING',
    provider_message_id varchar(255),
    metadata jsonb not null default '{}'::jsonb,
    sent_at timestamptz,
    created_at timestamptz not null default now(),
    constraint ck_email_logs_status check (status in ('PENDING', 'SENT', 'FAILED', 'BOUNCED'))
);

create index if not exists idx_email_logs_user_id on email_logs(user_id);
create index if not exists idx_email_logs_recipient_email on email_logs(recipient_email);
create index if not exists idx_email_logs_status on email_logs(status);

create table auth_verification_providers (
    id uuid primary key default gen_random_uuid(),
    provider_code varchar(80) not null unique,
    provider_type varchar(50) not null,
    issuer varchar(255),
    jwks_uri text,
    public_config jsonb not null default '{}'::jsonb,
    status varchar(30) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_auth_verification_providers_type
        check (provider_type in ('SUPABASE_AUTH', 'SCHOOL_EMAIL', 'COMPANY_EMAIL', 'PAYMENT_PROVIDER', 'OIDC', 'MANUAL_ADMIN')),
    constraint ck_auth_verification_providers_status check (status in ('ACTIVE', 'INACTIVE', 'ROTATING'))
);

comment on table auth_verification_providers is
    'Public/non-secret verification metadata such as Supabase issuer and JWKS URI. Secrets are not stored here.';
comment on column auth_verification_providers.jwks_uri is
    'Plain text is acceptable because JWKS URIs and public keys are public verification metadata.';

insert into auth_verification_providers (provider_code, provider_type, issuer, jwks_uri, public_config)
values (
    'supabase',
    'SUPABASE_AUTH',
    'env:SUPABASE_ISSUER',
    'env:SUPABASE_JWKS_URI',
    '{"token_storage_policy":"do_not_store_raw_access_or_refresh_tokens","decode_policy":"verify_jwt_signature_with_jwks_and_store_only_user_id_claims"}'::jsonb
)
on conflict (provider_code) do nothing;

create table auth_verification_credentials (
    id uuid primary key default gen_random_uuid(),
    provider_id uuid not null references auth_verification_providers(id) on delete cascade,
    credential_name varchar(120) not null,
    credential_kind varchar(50) not null,
    encrypted_secret_ciphertext bytea,
    secret_fingerprint_sha256 varchar(64),
    encryption_scheme varchar(80) not null default 'APP_ENVELOPE_AES_256_GCM',
    key_reference varchar(255) not null,
    status varchar(30) not null default 'ACTIVE',
    valid_from timestamptz not null default now(),
    valid_until timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_auth_verification_credentials_kind
        check (credential_kind in ('API_KEY', 'CLIENT_SECRET', 'WEBHOOK_SECRET', 'PRIVATE_KEY', 'HMAC_SECRET')),
    constraint ck_auth_verification_credentials_status check (status in ('ACTIVE', 'REVOKED', 'EXPIRED', 'ROTATING')),
    constraint ux_auth_verification_credentials_name unique (provider_id, credential_name)
);

comment on table auth_verification_credentials is
    'Stores only encrypted secrets or fingerprints for provider credentials. Never store raw tokens, API keys, private keys, or client secrets in plain text.';
comment on column auth_verification_credentials.encrypted_secret_ciphertext is
    'Ciphertext only. Encrypt/decrypt in backend using an env/KMS key; do not keep the encryption key in PostgreSQL.';
comment on column auth_verification_credentials.secret_fingerprint_sha256 is
    'SHA-256 fingerprint for lookup/rotation without revealing the secret.';

create table user_verification_claims (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id) on delete cascade,
    provider_id uuid not null references auth_verification_providers(id),
    verification_type varchar(50) not null,
    subject_identifier varchar(320) not null,
    issuer varchar(255),
    assurance_level varchar(30) not null default 'LOW',
    status varchar(30) not null default 'PENDING',
    verified_at timestamptz,
    expires_at timestamptz,
    evidence_summary jsonb not null default '{}'::jsonb,
    raw_token_hash_sha256 varchar(64),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_user_verification_claims_type
        check (verification_type in ('SUPABASE_USER', 'STUDENT_EMAIL', 'EMPLOYER_DOMAIN', 'PHONE', 'PAYMENT_ACCOUNT', 'MANUAL_IDENTITY')),
    constraint ck_user_verification_claims_assurance
        check (assurance_level in ('LOW', 'MEDIUM', 'HIGH')),
    constraint ck_user_verification_claims_status
        check (status in ('PENDING', 'VERIFIED', 'REJECTED', 'EXPIRED', 'REVOKED'))
);

comment on table user_verification_claims is
    'Stores decoded, minimal verification claims used by the app: issuer, subject, status, assurance. Raw tokens are not stored; only optional SHA-256 token hash.';
comment on column user_verification_claims.raw_token_hash_sha256 is
    'Optional one-way hash for audit/replay detection. Never store raw JWT, OTP, OAuth code, or refresh token.';

create index if not exists idx_user_verification_claims_user_id on user_verification_claims(user_id);
create index if not exists idx_user_verification_claims_provider_id on user_verification_claims(provider_id);
create index if not exists idx_user_verification_claims_type on user_verification_claims(verification_type);
create index if not exists idx_user_verification_claims_status on user_verification_claims(status);
create unique index if not exists ux_user_verification_claims_active_subject
    on user_verification_claims(provider_id, verification_type, subject_identifier)
    where status in ('PENDING', 'VERIFIED');

create table verification_challenges (
    id uuid primary key default gen_random_uuid(),
    user_id uuid references app_users(id) on delete cascade,
    provider_id uuid not null references auth_verification_providers(id),
    challenge_type varchar(50) not null,
    destination_hash_sha256 varchar(64),
    token_hash_sha256 varchar(64) not null,
    status varchar(30) not null default 'PENDING',
    attempts integer not null default 0,
    max_attempts integer not null default 5,
    expires_at timestamptz not null,
    consumed_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_verification_challenges_type
        check (challenge_type in ('EMAIL_OTP', 'MAGIC_LINK', 'PASSWORD_RESET', 'DOMAIN_VERIFY', 'DEVICE_BIND')),
    constraint ck_verification_challenges_status
        check (status in ('PENDING', 'CONSUMED', 'EXPIRED', 'LOCKED', 'REVOKED')),
    constraint ck_verification_challenges_attempts check (attempts >= 0 and max_attempts > 0)
);

comment on table verification_challenges is
    'Stores only OTP/magic-link/password-reset token hashes, not raw tokens. Raw token is shown/sent once then discarded.';
comment on column verification_challenges.destination_hash_sha256 is
    'Hash of email/phone/domain destination to support auditing without exposing more PII than needed.';

create index if not exists idx_verification_challenges_user_id on verification_challenges(user_id);
create index if not exists idx_verification_challenges_status on verification_challenges(status);
create index if not exists idx_verification_challenges_expires_at on verification_challenges(expires_at);

create table auth_sessions (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id) on delete cascade,
    provider_id uuid references auth_verification_providers(id),
    access_token_jti varchar(160),
    refresh_token_hash_sha256 varchar(64),
    device_fingerprint_hash varchar(128),
    ip_address inet,
    user_agent text,
    status varchar(30) not null default 'ACTIVE',
    issued_at timestamptz not null default now(),
    expires_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_auth_sessions_status check (status in ('ACTIVE', 'EXPIRED', 'REVOKED'))
);

comment on table auth_sessions is
    'Optional app-side session/audit table. It stores JTI and refresh token hashes only; Supabase remains source of truth for auth secrets.';
comment on column auth_sessions.refresh_token_hash_sha256 is
    'One-way hash for revocation/audit. Never store Supabase refresh tokens in plain text.';

create index if not exists idx_auth_sessions_user_id on auth_sessions(user_id);
create index if not exists idx_auth_sessions_access_token_jti on auth_sessions(access_token_jti);
create index if not exists idx_auth_sessions_status on auth_sessions(status);
create index if not exists idx_auth_sessions_expires_at on auth_sessions(expires_at);

create table user_login_events (
    id uuid primary key default gen_random_uuid(),
    user_id uuid references app_users(id) on delete set null,
    provider_id uuid references auth_verification_providers(id),
    auth_session_id uuid references auth_sessions(id) on delete set null,
    event_type varchar(40) not null,
    ip_address inet,
    country_code char(2),
    region varchar(120),
    city varchar(120),
    timezone varchar(80),
    asn varchar(40),
    user_agent text,
    device_fingerprint_hash varchar(128),
    success boolean not null,
    failure_reason varchar(120),
    created_at timestamptz not null default now(),
    constraint ck_user_login_events_type check (event_type in ('LOGIN', 'LOGOUT', 'TOKEN_REFRESH', 'TOKEN_REVOKE', 'AUTH_CALLBACK'))
);

comment on table user_login_events is
    'Stores where authentication events came from: IP/geolocation/device metadata. This is audit metadata, not a proof token store.';

create index if not exists idx_user_login_events_user_id on user_login_events(user_id);
create index if not exists idx_user_login_events_event_type on user_login_events(event_type);
create index if not exists idx_user_login_events_created_at on user_login_events(created_at);
create index if not exists idx_user_login_events_country_code on user_login_events(country_code);

create table api_idempotency_keys (
    id uuid primary key default gen_random_uuid(),
    idempotency_key varchar(160) not null unique,
    user_id uuid references app_users(id) on delete set null,
    request_method varchar(12) not null,
    request_path text not null,
    request_hash_sha256 varchar(64) not null,
    response_status integer,
    response_body jsonb,
    locked_until timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists idx_api_idempotency_keys_user_id on api_idempotency_keys(user_id);
create index if not exists idx_api_idempotency_keys_locked_until on api_idempotency_keys(locked_until);

create trigger trg_app_users_updated_at
before update on app_users
for each row execute function set_updated_at();

create trigger trg_profiles_updated_at
before update on profiles
for each row execute function set_updated_at();

create trigger trg_schools_updated_at before update on schools for each row execute function set_updated_at();
create trigger trg_companies_updated_at before update on companies for each row execute function set_updated_at();
create trigger trg_authority_nodes_updated_at before update on authority_nodes for each row execute function set_updated_at();
create trigger trg_profile_links_updated_at before update on profile_links for each row execute function set_updated_at();
create trigger trg_experiences_updated_at before update on experiences for each row execute function set_updated_at();
create trigger trg_verification_sources_updated_at before update on verification_sources for each row execute function set_updated_at();
create trigger trg_jobs_updated_at before update on jobs for each row execute function set_updated_at();
create trigger trg_applications_updated_at before update on applications for each row execute function set_updated_at();
create trigger trg_quests_updated_at before update on quests for each row execute function set_updated_at();
create trigger trg_quest_applications_updated_at before update on quest_applications for each row execute function set_updated_at();
create trigger trg_daily_logs_updated_at before update on daily_logs for each row execute function set_updated_at();
create trigger trg_wallets_updated_at before update on wallets for each row execute function set_updated_at();
create trigger trg_payment_requests_updated_at before update on payment_requests for each row execute function set_updated_at();
create trigger trg_subscription_plans_updated_at before update on subscription_plans for each row execute function set_updated_at();
create trigger trg_subscriptions_updated_at before update on subscriptions for each row execute function set_updated_at();
create trigger trg_digital_items_updated_at before update on digital_items for each row execute function set_updated_at();
create trigger trg_reports_updated_at before update on reports for each row execute function set_updated_at();
create trigger trg_fraud_flags_updated_at before update on fraud_flags for each row execute function set_updated_at();
create trigger trg_notification_preferences_updated_at before update on notification_preferences for each row execute function set_updated_at();
create trigger trg_checkin_sessions_updated_at before update on checkin_sessions for each row execute function set_updated_at();
create trigger trg_badges_updated_at before update on badges for each row execute function set_updated_at();
create trigger trg_b2b_orders_updated_at before update on b2b_orders for each row execute function set_updated_at();
create trigger trg_system_configs_updated_at before update on system_configs for each row execute function set_updated_at();
create trigger trg_auth_verification_providers_updated_at before update on auth_verification_providers for each row execute function set_updated_at();
create trigger trg_auth_verification_credentials_updated_at before update on auth_verification_credentials for each row execute function set_updated_at();
create trigger trg_user_verification_claims_updated_at before update on user_verification_claims for each row execute function set_updated_at();
create trigger trg_verification_challenges_updated_at before update on verification_challenges for each row execute function set_updated_at();
create trigger trg_auth_sessions_updated_at before update on auth_sessions for each row execute function set_updated_at();
create trigger trg_api_idempotency_keys_updated_at before update on api_idempotency_keys for each row execute function set_updated_at();
