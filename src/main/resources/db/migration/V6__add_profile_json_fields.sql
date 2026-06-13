-- V6: Add location, avatar_config, and credentials to profiles table to easily store candidate data.
alter table profiles
    add column if not exists location varchar(150),
    add column if not exists avatar_config jsonb not null default '{}'::jsonb,
    add column if not exists credentials jsonb not null default '[]'::jsonb;
