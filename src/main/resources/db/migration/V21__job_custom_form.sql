-- Custom application form: employers add extra questions per job,
-- candidates answer them on apply, organizers see answers.

create table if not exists job_form_fields (
    id          uuid primary key default gen_random_uuid(),
    job_id      uuid not null references jobs(id) on delete cascade,
    label       varchar(200) not null,
    field_type  varchar(20)  not null default 'TEXT',   -- TEXT | TEXTAREA | SELECT
    options     text,                                    -- newline-separated options for SELECT
    required    boolean      not null default false,
    sort_order  integer      not null default 0,
    created_at  timestamptz  not null default now()
);
create index if not exists idx_job_form_fields_job on job_form_fields(job_id);

-- Denormalized answers snapshot: [{ "label": "...", "value": "..." }, ...]
alter table applications
    add column if not exists custom_answers jsonb;
