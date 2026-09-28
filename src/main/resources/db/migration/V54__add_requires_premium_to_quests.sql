-- Quest của CLB cũng có thể giới hạn cho ứng viên Premium, giống tin tuyển
-- dụng của doanh nghiệp (jobs.requires_premium, thêm ở V14).
--
-- Trước đây chỉ jobs có cột này, nên CLB không có cách nào lọc ứng viên bằng
-- Premium — một sự bất đối xứng không có lý do sản phẩm nào biện minh, chỉ là
-- do tính năng được làm cho jobs trước rồi dừng ở đó.

ALTER TABLE quests
    ADD COLUMN IF NOT EXISTS requires_premium BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN quests.requires_premium IS
    'If true, only users with an active Premium Pass (premium_until > now()) may apply.';
