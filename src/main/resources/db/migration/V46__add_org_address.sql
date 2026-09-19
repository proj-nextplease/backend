-- Địa chỉ của Doanh nghiệp / CLB.
-- Dùng làm nguồn mặc định cho trường địa điểm khi tổ chức đăng Job hoặc Quest.
ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS address VARCHAR(300);

-- Quest trước đây không có địa điểm (FE hiển thị cứng "FPTU HCM").
ALTER TABLE quests
    ADD COLUMN IF NOT EXISTS location VARCHAR(200);

COMMENT ON COLUMN companies.address IS 'Địa chỉ trụ sở / sinh hoạt của tổ chức; prefill cho địa điểm tin đăng.';
COMMENT ON COLUMN quests.location IS 'Địa điểm diễn ra quest; mặc định lấy từ companies.address khi tạo.';
