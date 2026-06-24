-- V32: Track where an experience row came from so the Portfolio editor can
-- never delete proof-of-work submissions made via the dashboard "Nộp minh
-- chứng" form.
--
--   PORTFOLIO  → created/edited through the Portfolio experience editor.
--   CREDENTIAL → submitted through the credential proof-of-work flow.
--
-- The Portfolio save only deletes orphaned PENDING rows whose source is
-- PORTFOLIO; CREDENTIAL rows are managed solely by the verification flow.
ALTER TABLE experiences
    ADD COLUMN IF NOT EXISTS source VARCHAR(20) NOT NULL DEFAULT 'PORTFOLIO';

-- Backfill existing rows conservatively: anything that looks like a credential
-- submission (has a proof link or a non-default activity category) is marked
-- CREDENTIAL so it is protected from deletion. Everything else stays PORTFOLIO.
UPDATE experiences
SET source = 'CREDENTIAL'
WHERE proof_link IS NOT NULL
   OR category <> 'COMPANY_PROJECT';
