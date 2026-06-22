-- Custom application form for quests (mirror of job_form_fields).

create table if not exists quest_form_fields (
    id          uuid primary key default gen_random_uuid(),
    quest_id    uuid not null references quests(id) on delete cascade,
    label       varchar(200) not null,
    field_type  varchar(20)  not null default 'TEXT',   -- TEXT | TEXTAREA | SELECT
    options     text,
    required    boolean      not null default false,
    sort_order  integer      not null default 0,
    created_at  timestamptz  not null default now()
);
create index if not exists idx_quest_form_fields_quest on quest_form_fields(quest_id);

alter table quest_applications
    add column if not exists custom_answers jsonb;
