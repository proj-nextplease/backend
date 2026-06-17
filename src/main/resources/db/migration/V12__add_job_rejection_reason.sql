-- Bổ sung cột lý do từ chối cho cả tin tuyển dụng (jobs) và quest sự kiện (quests)
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS rejection_reason TEXT;
ALTER TABLE quests ADD COLUMN IF NOT EXISTS rejection_reason TEXT;
