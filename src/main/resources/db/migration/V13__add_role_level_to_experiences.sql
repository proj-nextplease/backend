-- V13: Add role_level to experiences for RS award calculation.
-- MEMBER = +5 RS on approval, LEADER = +10 RS on approval.
ALTER TABLE experiences
    ADD COLUMN IF NOT EXISTS role_level VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    ADD CONSTRAINT ck_experiences_role_level CHECK (role_level IN ('MEMBER', 'LEADER'));
