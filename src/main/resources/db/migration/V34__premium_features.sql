-- Migration V34: Premium features monetization for candidate
-- Author: Antigravity

-- 1. Add boosted_until to applications
ALTER TABLE applications ADD COLUMN IF NOT EXISTS boosted_until TIMESTAMP WITH TIME ZONE;

-- 2. Add express_verification to experiences
ALTER TABLE experiences ADD COLUMN IF NOT EXISTS express_verification BOOLEAN NOT NULL DEFAULT FALSE;

-- 3. Add theme_unlocked to profiles
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS theme_unlocked BOOLEAN NOT NULL DEFAULT FALSE;

-- 4. Create application_insights_unlocks table
CREATE TABLE IF NOT EXISTS application_insights_unlocks (
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    job_id UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    unlocked_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, job_id)
);

-- 5. Insert system configs
INSERT INTO system_configs (config_key, value_int, data_type, config_group, label, description) VALUES
    ('premium_boost_price_np', 15000, 'INT', 'pricing', 'Giá mua lượt nổi bật (Profile Boost)', 'Số NP bị trừ khi boost đơn ứng tuyển.'),
    ('premium_boost_duration_hours', 48, 'INT', 'general', 'Thời gian ghim nổi bật (Giờ)', 'Số giờ ghim hồ sơ ở vị trí hàng đầu khi dùng Boost.'),
    ('application_insight_price_np', 10000, 'INT', 'pricing', 'Giá xem Application Insight', 'Số NP bị trừ khi xem thống kê cạnh tranh của một job.'),
    ('express_verification_price_np', 25000, 'INT', 'pricing', 'Giá xác thực nhanh (Express)', 'Số NP bị trừ khi đăng ký xác thực nhanh kinh nghiệm.'),
    ('profile_theme_price_np', 50000, 'INT', 'pricing', 'Giá mở khóa Visual Upgrade', 'Số NP bị trừ khi mở khóa chọn giao diện hồ sơ.'),
    ('job_match_alert_price_np', 19000, 'INT', 'pricing', 'Giá đăng ký Job Match Alert (tháng)', 'Số NP bị trừ khi mua/gia hạn gói thông báo việc làm và gợi ý Quest.'),
    ('job_match_early_access_hours', 12, 'INT', 'general', 'Thời gian xem sớm tin tuyển dụng (Giờ)', 'Khoảng thời gian (giờ) tin tuyển dụng mới chỉ hiển thị cho người dùng Premium/Match Alert.')
ON CONFLICT (config_key) DO NOTHING;
