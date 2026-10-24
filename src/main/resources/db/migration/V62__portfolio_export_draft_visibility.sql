-- V62: Update portfolio export draft visibility
-- Set is_public = false for profiles that have never been exported
update profiles
set is_public = false
where last_exported_at is null and coalesce(export_count, 0) = 0;

-- Default future profiles to is_public = false until exported
alter table profiles
    alter column is_public set default false;
