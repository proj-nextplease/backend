-- ==============================================================================
-- V66__ai_credential_verification.sql
-- Thêm các trường lưu trữ trạng thái thẩm định tự động bằng AI cho minh chứng
-- và cấu hình hệ thống phục vụ mô hình AI Hybrid.
-- ==============================================================================

-- 1. Thêm các cột thẩm định AI vào bảng experiences
ALTER TABLE experiences
    ADD COLUMN IF NOT EXISTS ai_status VARCHAR(30) NOT NULL DEFAULT 'NOT_PROCESSED',
    ADD COLUMN IF NOT EXISTS ai_verdict VARCHAR(30),
    ADD COLUMN IF NOT EXISTS ai_confidence_score INTEGER,
    ADD COLUMN IF NOT EXISTS ai_feedback TEXT,
    ADD COLUMN IF NOT EXISTS ai_analysis_data JSONB,
    ADD COLUMN IF NOT EXISTS verified_by_type VARCHAR(30);

-- 2. Thêm indexes để tối ưu hoá truy vấn hàng chờ và phân loại AI
CREATE INDEX IF NOT EXISTS idx_experiences_ai_status ON experiences(ai_status);
CREATE INDEX IF NOT EXISTS idx_experiences_ai_verdict ON experiences(ai_verdict);

-- 3. Tạo tài khoản ảo hệ thống đại diện cho AI Bot (để ghi nhận verified_by mà không vỡ Foreign Key)
INSERT INTO app_users (id, supabase_user_id, email, display_name, status, created_at, updated_at)
VALUES (
    '00000000-0000-0000-0000-0000000000a1',
    '00000000-0000-0000-0000-0000000000a1',
    'ai-agent@nextplease.internal',
    'NextPlease AI Assistant',
    'ACTIVE',
    now(),
    now()
)
ON CONFLICT (id) DO NOTHING;

-- 4. Thêm các cấu hình cho AI Verification vào system_configs
INSERT INTO system_configs (config_key, value_int, data_type, config_group, label, description) VALUES
    ('ai_verification_enabled', 1, 'INT', 'ai', 'Bật AI duyệt minh chứng (1=Bật, 0=Tắt)', 'Bật/tắt tính năng AI quét và thẩm định minh chứng tự động.'),
    ('ai_auto_approve_min_score', 85, 'INT', 'ai', 'Điểm tối thiểu AI tự động duyệt', 'Điểm tự tin từ 0-100. Đạt từ ngưỡng này trở lên và tên khớp sẽ tự động Approve.'),
    ('ai_auto_reject_max_score', 40, 'INT', 'ai', 'Điểm tối đa AI tự động từ chối', 'Điểm tự tin từ 0-100. Dưới ngưỡng này (ảnh rác, ảnh không liên quan) sẽ tự động Reject.')
ON CONFLICT (config_key) DO NOTHING;

