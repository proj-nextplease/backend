-- Align quest approval flow with jobs: allow PENDING (chờ Admin duyệt) and REJECTED.
ALTER TABLE quests DROP CONSTRAINT IF EXISTS ck_quests_status;
ALTER TABLE quests
    ADD CONSTRAINT ck_quests_status
        CHECK (status IN ('DRAFT', 'PENDING', 'OPEN', 'REJECTED', 'CLOSED', 'COMPLETED', 'CANCELLED'));
