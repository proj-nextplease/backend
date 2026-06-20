-- Add reject_reason column to applications (job) and quest_applications tables.
-- Also update the status check constraint for applications to allow COMPLETED.

alter table applications
    add column if not exists reject_reason text;

alter table quest_applications
    add column if not exists reject_reason text;
