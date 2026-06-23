-- Optional banner image for job/quest postings (data URL or hosted URL).
-- Null => frontend uses a system default banner.

alter table jobs   add column if not exists banner_url text;
alter table quests add column if not exists banner_url text;
