-- V41: Portfolio "Verified Bento" redesign support.
-- Adds an "open to work" flag and a free-form social/contact links map to the
-- candidate profile so the public portfolio can surface availability and contact
-- entry points. Both are optional and default to safe empty values.
--
-- Fail fast (5s) instead of hanging on the statement timeout if another session
-- holds a conflicting lock on `profiles`. Adding a column with a constant default
-- is metadata-only on PostgreSQL 11+, so the lock is held only momentarily.
set lock_timeout = '5s';

alter table profiles
    add column if not exists open_to_work boolean not null default false,
    add column if not exists social_links jsonb not null default '{}'::jsonb;
