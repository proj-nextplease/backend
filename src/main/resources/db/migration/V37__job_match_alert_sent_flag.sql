-- Migration V37: Track whether a Job Match Alert has already been dispatched for a
-- job/quest, so re-approval after an edit does not re-notify subscribers (feature #5).

ALTER TABLE jobs ADD COLUMN IF NOT EXISTS match_alert_sent_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE quests ADD COLUMN IF NOT EXISTS match_alert_sent_at TIMESTAMP WITH TIME ZONE;
