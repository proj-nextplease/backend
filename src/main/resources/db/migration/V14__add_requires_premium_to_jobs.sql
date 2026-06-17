ALTER TABLE jobs
    ADD COLUMN IF NOT EXISTS requires_premium BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN jobs.requires_premium IS
    'If true, only users with an active Premium Pass (premium_until > now()) may apply.';
