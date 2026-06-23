-- V29 created `notifications` only if absent. A pre-existing table with a
-- different shape made V29's CREATE be skipped, leaving columns missing
-- (e.g. "type"). Reconcile by adding every expected column idempotently.
alter table notifications add column if not exists user_id    uuid;
alter table notifications add column if not exists type       varchar(40);
alter table notifications add column if not exists title      varchar(200);
alter table notifications add column if not exists body       text;
alter table notifications add column if not exists link       varchar(300);
alter table notifications add column if not exists is_read    boolean not null default false;
alter table notifications add column if not exists created_at timestamptz not null default now();

create index if not exists ix_notifications_user_created
    on notifications(user_id, created_at desc);
