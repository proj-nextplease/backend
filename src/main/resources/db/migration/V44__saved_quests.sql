-- V44: Candidates can "save" (bookmark) a quest.
set lock_timeout = '5s';

create table if not exists saved_quests (
    user_id uuid not null references app_users(id) on delete cascade,
    quest_id uuid not null references quests(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (user_id, quest_id)
);

create index if not exists idx_saved_quests_quest on saved_quests(quest_id);
create index if not exists idx_saved_quests_user on saved_quests(user_id);
