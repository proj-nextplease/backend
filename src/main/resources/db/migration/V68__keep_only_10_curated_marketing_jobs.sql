-- ==============================================================================
-- V68__keep_only_10_curated_marketing_jobs.sql
-- Đảm bảo CHỈ DUY NHẤT 10 tin tuyển dụng Marketing (từ file Excel) tồn tại trong bảng external_jobs.
-- Xoá bỏ vĩnh viễn toàn bộ các tin nguồn ngoài khác.
-- ==============================================================================

-- 1. Xoá tất cả các tin không thuộc danh sách 10 tin tuyển dụng Marketing tuyển chọn
DELETE FROM external_jobs
WHERE source_key NOT IN (
    'fe72a74b03e6f3749db0d6d7abbfb1a8a1235bd4164bea4f97bae5ef24f46401',
    '4c93146e091ef37247fd06d837bd6cde2aa6daab96ffcdd888865697cadddf96',
    '2ce1aff3175a1ad48eb759f6124870cd1df005e8beb50ff7da13fe8e3efd3b27',
    '323bf4cbd983e5fd80b85929428a9fa5874da924b90ca88739a8d7ebb0f8bf74',
    '5a44a56939eb719c7efc9063479110dfbebddc620c9f9810c72df809ebc90f0e',
    '259e1bcb3103938d07cd24ab156f92968938d013a2d5cf0009de83c6ef66a1b4',
    'fca1ce4adbbc64c08af33c88e9811db83c0ccfadc0c285a8c6e245b317a2e880',
    'ab8bee80267d61b537b30a1f7f88756e4a144dac600816c70087d75bfa8e35ef',
    '90ae975f6167a7e91d9b68c87d1ab761d18b49c6853930ab174686a40c562eb8',
    '4ee70c05adbe1c2656f8a350ba3fa9fad4bfefcf2d38c22beca0b14aab2e3e16'
);

-- 2. Đảm bảo và cập nhật kích hoạt 10 tin tuyển dụng Marketing
INSERT INTO external_jobs (
    source, source_key, title, company_name, location, excerpt,
    salary_text, salary_min, salary_max, salary_currency, salary_type,
    apply_url, posted_at, first_seen_at, last_seen_at, is_active
) VALUES (
    'CAREERVIET',
    'fe72a74b03e6f3749db0d6d7abbfb1a8a1235bd4164bea4f97bae5ef24f46401',
    'KOC Booking Operator (TikTok Affiliate)',
    'PICKDI Inc',
    'TPHCM - Hà Nội - Đà Nẵng',
    'Hình thức: Remote | Yêu cầu kinh nghiệm: Hơn 1 năm | Hạn nộp: 06/11/2026',
    '14.000.000 – 16.000.000 VND',
    14000000, 16000000, 'VND', 'M',
    'https://careerviet.vn/en/search-job/koc-booking-operator-tiktok-affiliate.35C8BCEB.html',
    now(), now(), now(), true
) ON CONFLICT (source, source_key) DO UPDATE SET is_active = true, last_seen_at = now();

INSERT INTO external_jobs (
    source, source_key, title, company_name, location, excerpt,
    salary_text, salary_min, salary_max, salary_currency, salary_type,
    apply_url, posted_at, first_seen_at, last_seen_at, is_active
) VALUES (
    'CAREERVIET',
    '4c93146e091ef37247fd06d837bd6cde2aa6daab96ffcdd888865697cadddf96',
    'KOC Booking Operator (META, TikTok Shop)',
    'PICKDI Inc',
    'TPHCM - Hà Nội - Đà Nẵng',
    'Hình thức: Remote | Yêu cầu kinh nghiệm: Hơn 1 năm | Hạn nộp: 30/10/2026',
    '15.000.000 – 17.000.000 VND',
    15000000, 17000000, 'VND', 'M',
    'https://careerviet.vn/en/search-job/koc-booking-operator-meta-tiktok-shop.35C8BCEB.html',
    now(), now(), now(), true
) ON CONFLICT (source, source_key) DO UPDATE SET is_active = true, last_seen_at = now();

INSERT INTO external_jobs (
    source, source_key, title, company_name, location, excerpt,
    salary_text, salary_min, salary_max, salary_currency, salary_type,
    apply_url, posted_at, first_seen_at, last_seen_at, is_active
) VALUES (
    'CAREERVIET',
    '2ce1aff3175a1ad48eb759f6124870cd1df005e8beb50ff7da13fe8e3efd3b27',
    'Social Media Researcher (tiếng Đức)',
    'PICKDI Inc',
    'TPHCM - Hà Nội - Đà Nẵng',
    'Hình thức: Remote | Yêu cầu kinh nghiệm: Hơn 1 năm | Hạn nộp: 30/10/2026',
    '15.000.000 – 20.000.000 VND',
    15000000, 20000000, 'VND', 'M',
    'https://careerviet.vn/en/search-job/social-media-researcher-tieng-duc.35C8BD30.html',
    now(), now(), now(), true
) ON CONFLICT (source, source_key) DO UPDATE SET is_active = true, last_seen_at = now();

INSERT INTO external_jobs (
    source, source_key, title, company_name, location, excerpt,
    salary_text, salary_min, salary_max, salary_currency, salary_type,
    apply_url, posted_at, first_seen_at, last_seen_at, is_active
) VALUES (
    'CAREERVIET',
    '323bf4cbd983e5fd80b85929428a9fa5874da924b90ca88739a8d7ebb0f8bf74',
    'Chuyên viên Marketing',
    'CTY TNHH KHO MÁY VIỆT NAM',
    'Hồ Chí Minh',
    'Hình thức: Nhân viên chính thức | Yêu cầu kinh nghiệm: 1 - 2 Năm | Hạn nộp: 10/11/2026',
    '10.000.000 – 15.000.000 VND',
    10000000, 15000000, 'VND', 'M',
    'https://careerviet.vn/en/search-job/chuyen-vien-marketing.35C8BD3B.html',
    now(), now(), now(), true
) ON CONFLICT (source, source_key) DO UPDATE SET is_active = true, last_seen_at = now();

INSERT INTO external_jobs (
    source, source_key, title, company_name, location, excerpt,
    salary_text, salary_min, salary_max, salary_currency, salary_type,
    apply_url, posted_at, first_seen_at, last_seen_at, is_active
) VALUES (
    'CAREERVIET',
    '5a44a56939eb719c7efc9063479110dfbebddc620c9f9810c72df809ebc90f0e',
    'Trưởng phòng Marketing',
    'TẬP ĐOÀN ĐÔNG ĐÔ',
    'Hà Nội',
    'Hình thức: Nhân viên chính thức | Yêu cầu kinh nghiệm: 3 - 5 Năm | Hạn nộp: 26/10/2026',
    '20.000.000 – 25.000.000 VND',
    20000000, 25000000, 'VND', 'M',
    'https://careerviet.vn/en/search-job/truong-phong-marketing.35C88DE6.html',
    now(), now(), now(), true
) ON CONFLICT (source, source_key) DO UPDATE SET is_active = true, last_seen_at = now();

INSERT INTO external_jobs (
    source, source_key, title, company_name, location, excerpt,
    salary_text, salary_min, salary_max, salary_currency, salary_type,
    apply_url, posted_at, first_seen_at, last_seen_at, is_active
) VALUES (
    'CAREERVIET',
    '259e1bcb3103938d07cd24ab156f92968938d013a2d5cf0009de83c6ef66a1b4',
    'Content Creator & Event Specialist',
    'CÔNG TY TNHH WELDCOM',
    'Hà Nội',
    'Hình thức: Nhân viên chính thức | Yêu cầu kinh nghiệm: 1 - 2 Năm | Hạn nộp: 14/11/2026',
    '15.000.000 – 18.000.000 VND',
    15000000, 18000000, 'VND', 'M',
    'https://careerviet.vn/en/search-job/content-creator-event-specialist.35C8BA63.html',
    now(), now(), now(), true
) ON CONFLICT (source, source_key) DO UPDATE SET is_active = true, last_seen_at = now();

INSERT INTO external_jobs (
    source, source_key, title, company_name, location, excerpt,
    salary_text, salary_min, salary_max, salary_currency, salary_type,
    apply_url, posted_at, first_seen_at, last_seen_at, is_active
) VALUES (
    'CAREERVIET',
    'fca1ce4adbbc64c08af33c88e9811db83c0ccfadc0c285a8c6e245b317a2e880',
    'Chuyên Viên Digital Marketing (Lương 10-15TR)',
    'VIỆN THẨM MỸ QUỐC TẾ JOHNSON CLINIC',
    'Hà Nội',
    'Hình thức: Nhân viên chính thức | Yêu cầu kinh nghiệm: 1 - 2 Năm | Hạn nộp: 09/11/2026',
    '10.000.000 – 15.000.000 VND',
    10000000, 15000000, 'VND', 'M',
    'https://careerviet.vn/en/search-job/chuyen-vien-digital-marketing-luong-10-15tr.35C8B49E.html',
    now(), now(), now(), true
) ON CONFLICT (source, source_key) DO UPDATE SET is_active = true, last_seen_at = now();

INSERT INTO external_jobs (
    source, source_key, title, company_name, location, excerpt,
    salary_text, salary_min, salary_max, salary_currency, salary_type,
    apply_url, posted_at, first_seen_at, last_seen_at, is_active
) VALUES (
    'CAREERVIET',
    'ab8bee80267d61b537b30a1f7f88756e4a144dac600816c70087d75bfa8e35ef',
    'Nhân Viên Marketing Online',
    'CÔNG TY TNHH DƯỢC PHẨM VÀ Y TẾ ĐỨC TÂM',
    'Hà Nội',
    'Hình thức: Nhân viên chính thức | Yêu cầu kinh nghiệm: 1 - 2 Năm | Hạn nộp: 02/11/2026',
    '10.000.000 – 15.000.000 VND',
    10000000, 15000000, 'VND', 'M',
    'https://careerviet.vn/en/search-job/nhan-vien-marketing-online.35C8A79B.html',
    now(), now(), now(), true
) ON CONFLICT (source, source_key) DO UPDATE SET is_active = true, last_seen_at = now();

INSERT INTO external_jobs (
    source, source_key, title, company_name, location, excerpt,
    salary_text, salary_min, salary_max, salary_currency, salary_type,
    apply_url, posted_at, first_seen_at, last_seen_at, is_active
) VALUES (
    'CAREERVIET',
    '90ae975f6167a7e91d9b68c87d1ab761d18b49c6853930ab174686a40c562eb8',
    'Nhân Viên Sáng Tạo Nội Dung',
    'CÔNG TY TNHH THƯƠNG MẠI VÀ ĐẦU TƯ GIA MINH',
    'Hà Nội',
    'Hình thức: Nhân viên chính thức | Yêu cầu kinh nghiệm: 1 - 2 Năm | Hạn nộp: 06/11/2026',
    '8.000.000 – 10.000.000 VND',
    8000000, 10000000, 'VND', 'M',
    'https://careerviet.vn/en/search-job/nhan-vien-sang-tao-noi-dung.35C89F38.html',
    now(), now(), now(), true
) ON CONFLICT (source, source_key) DO UPDATE SET is_active = true, last_seen_at = now();

INSERT INTO external_jobs (
    source, source_key, title, company_name, location, excerpt,
    salary_text, salary_min, salary_max, salary_currency, salary_type,
    apply_url, posted_at, first_seen_at, last_seen_at, is_active
) VALUES (
    'CAREERVIET',
    '4ee70c05adbe1c2656f8a350ba3fa9fad4bfefcf2d38c22beca0b14aab2e3e16',
    'Nhân Viên Marketing',
    'CÔNG TY CỔ PHẦN CÔNG NGHỆ THIẾT BỊ MỚI VIỆT NAM (NEOTECH)',
    'Hà Nội',
    'Hình thức: Nhân viên chính thức | Yêu cầu kinh nghiệm: 1 - 2 Năm | Hạn nộp: 23/10/2026',
    '10.000.000 – 12.000.000 VND',
    10000000, 12000000, 'VND', 'M',
    'https://careerviet.vn/en/search-job/nhan-vien-marketing.35C88BF1.html',
    now(), now(), now(), true
) ON CONFLICT (source, source_key) DO UPDATE SET is_active = true, last_seen_at = now();
