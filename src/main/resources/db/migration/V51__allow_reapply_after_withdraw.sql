-- Rút đơn rồi phải nộp lại được.
--
-- V2 đặt ràng buộc unique (job_id, candidate_id) và (quest_id, candidate_id)
-- KHÔNG loại trừ trạng thái nào. Hệ quả: rút đơn xong là mất vĩnh viễn cơ hội
-- ứng tuyển lại vị trí đó — kể cả khi tin còn hạn và người dùng chỉ rút vì bấm
-- nhầm.
--
-- Giao diện thì hứa ngược lại: nút "Ứng tuyển" vẫn sáng và ngay dưới nút rút
-- đơn có dòng "Rút rồi vẫn nộp lại được, chừng nào tin còn hạn". Bấm vào mới
-- nhận được "Bạn đã ứng tuyển vị trí này rồi."
--
-- Cách sửa: unique index CÓ ĐIỀU KIỆN, bỏ qua bản ghi đã rút. Cùng kiểu mà
-- authority_nodes đã dùng cho (company_id, user_id) với status <> 'REJECTED'.
--
-- Vẫn giữ nguyên hành vi chặn nộp trùng khi đơn còn sống, nên
-- DuplicateKeyException ở ApplicationService/QuestService tiếp tục ném đúng
-- mã ALREADY_APPLIED.

-- ── Tin tuyển dụng ──────────────────────────────────────────────────────
ALTER TABLE applications
    DROP CONSTRAINT IF EXISTS ux_applications_job_candidate;

CREATE UNIQUE INDEX IF NOT EXISTS ux_applications_job_candidate_live
    ON applications (job_id, candidate_id)
    WHERE status <> 'WITHDRAWN';

-- ── Quest ───────────────────────────────────────────────────────────────
ALTER TABLE quest_applications
    DROP CONSTRAINT IF EXISTS ux_quest_applications_quest_candidate;

CREATE UNIQUE INDEX IF NOT EXISTS ux_quest_applications_quest_candidate_live
    ON quest_applications (quest_id, candidate_id)
    WHERE status <> 'WITHDRAWN';
