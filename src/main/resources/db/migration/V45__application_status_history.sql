-- Per-transition status history for job & quest applications, so the candidate
-- timeline can show the exact time each step happened (nộp → đã xem → chấp thuận…).
-- One table with two nullable FKs (exactly one set), discriminated per row.

set local lock_timeout = '30s';
set local statement_timeout = '120s';

create table if not exists application_status_history (
    id                     uuid        primary key default gen_random_uuid(),
    application_id         uuid        references applications(id)        on delete cascade,
    quest_application_id   uuid        references quest_applications(id)  on delete cascade,
    status                 varchar(20) not null,
    created_at             timestamptz not null default now(),
    constraint ck_ash_one_target check (
        (application_id is not null and quest_application_id is null) or
        (application_id is null and quest_application_id is not null)
    )
);

create index if not exists ix_ash_application on application_status_history(application_id, created_at);
create index if not exists ix_ash_quest_application on application_status_history(quest_application_id, created_at);

-- ── Backfill existing rows so current applications also get a timeline ──────────
-- Every application starts with a SUBMITTED event at its applied_at.
insert into application_status_history (application_id, status, created_at)
select a.id, 'SUBMITTED', a.applied_at
from applications a
where not exists (
    select 1 from application_status_history h
    where h.application_id = a.id and h.status = 'SUBMITTED'
);

-- If the application has since moved past SUBMITTED, add its current status at updated_at.
insert into application_status_history (application_id, status, created_at)
select a.id, a.status, a.updated_at
from applications a
where a.status <> 'SUBMITTED'
  and not exists (
    select 1 from application_status_history h
    where h.application_id = a.id and h.status = a.status
);

-- Same for quest applications.
insert into application_status_history (quest_application_id, status, created_at)
select q.id, 'SUBMITTED', q.applied_at
from quest_applications q
where not exists (
    select 1 from application_status_history h
    where h.quest_application_id = q.id and h.status = 'SUBMITTED'
);

insert into application_status_history (quest_application_id, status, created_at)
select q.id, q.status, q.updated_at
from quest_applications q
where q.status <> 'SUBMITTED'
  and not exists (
    select 1 from application_status_history h
    where h.quest_application_id = q.id and h.status = q.status
);
