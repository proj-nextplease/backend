-- Extend candidate premium application features to Quest / CLB applications.

ALTER TABLE quest_applications
    ADD COLUMN IF NOT EXISTS boosted_until TIMESTAMP WITH TIME ZONE;

CREATE TABLE IF NOT EXISTS quest_application_insights_unlocks (
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    quest_id UUID NOT NULL REFERENCES quests(id) ON DELETE CASCADE,
    unlocked_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, quest_id)
);
