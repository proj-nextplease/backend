-- V67: Chống trùng lặp minh chứng hoạt động (Anti-duplicate Credential Verification)
ALTER TABLE experiences
    ADD COLUMN IF NOT EXISTS proof_hash VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_experiences_proof_hash ON experiences(proof_hash);
