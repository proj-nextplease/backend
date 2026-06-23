-- In-app notifications (bell). One row per notification per recipient.
create table if not exists notifications (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid not null references app_users(id) on delete cascade,
    type       varchar(40)  not null,
    title      varchar(200) not null,
    body       text,
    link       varchar(300),
    is_read    boolean not null default false,
    created_at timestamptz not null default now()
);

create index if not exists ix_notifications_user_created
    on notifications(user_id, created_at desc);
