-- Thêm các cột phục vụ xác thực Doanh nghiệp & CLB
ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS tax_code VARCHAR(50), -- Mã số thuế (Doanh nghiệp)
    ADD COLUMN IF NOT EXISTS document_url TEXT, -- Link ảnh/file GPKD hoặc Quyết định thành lập
    ADD COLUMN IF NOT EXISTS rejection_reason TEXT, -- Lý do từ chối nếu không được duyệt
    ADD COLUMN IF NOT EXISTS representative_name VARCHAR(150), -- Tên người đại diện đăng ký
    ADD COLUMN IF NOT EXISTS representative_phone VARCHAR(20), -- SĐT người đại diện
    ADD COLUMN IF NOT EXISTS school_id UUID REFERENCES schools(id), -- Trường liên kết (đối với CLB)
    ADD COLUMN IF NOT EXISTS fanpage_url TEXT, -- Link Fanpage chính thức (đối với CLB)
    ADD COLUMN IF NOT EXISTS advisor_contact JSONB; -- Thông tin cố vấn CLB (Tên, SĐT, Email)

-- Thêm chỉ mục tăng tốc độ truy vấn cho Admin Dashboard khi lọc trạng thái phê duyệt
CREATE INDEX IF NOT EXISTS idx_companies_tax_code ON companies(tax_code);
CREATE INDEX IF NOT EXISTS idx_companies_school_id ON companies(school_id);

-- Chèn dữ liệu mẫu cho bảng schools nếu chưa tồn tại
INSERT INTO schools (id, name, email_domain, country_code, is_testbed, verification_status)
VALUES 
    ('11111111-1111-1111-1111-111111111111', 'Trường Đại học FPT TP.HCM', 'fpt.edu.vn', 'VN', true, 'ACTIVE'),
    ('22222222-2222-2222-2222-222222222222', 'Trường Đại học Kinh tế TP.HCM (UEH)', 'ueh.edu.vn', 'VN', true, 'ACTIVE'),
    ('33333333-3333-3333-3333-333333333333', 'Trường Đại học Bách Khoa TP.HCM (HCMUT)', 'hcmut.edu.vn', 'VN', true, 'ACTIVE'),
    ('44444444-4444-4444-4444-444444444444', 'Trường Đại học Quốc tế - ĐHQG TP.HCM (IU)', 'hcmiu.edu.vn', 'VN', true, 'ACTIVE')
ON CONFLICT (id) DO NOTHING;
