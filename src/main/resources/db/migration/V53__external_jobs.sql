-- Tin tuyển dụng lấy từ nguồn ngoài (Careerjet), phục vụ mô hình "cầu nối":
-- hiển thị trên nextplease, bấm ứng tuyển thì chuyển sang trang gốc.
--
-- VÌ SAO KHÔNG DÙNG BẢNG jobs:
-- jobs.company_id và jobs.created_by đều NOT NULL và đều là khoá ngoại. Tin
-- ngoài không có cả hai. Tạo công ty giả + user giả để lách sẽ làm bẩn hàng
-- chờ duyệt B2B của admin, sinh authority_nodes vô nghĩa, và làm sai mọi số
-- đếm ứng viên. Tách bảng thì hai thế giới không đụng nhau.

create table if not exists external_jobs (
    id uuid primary key default gen_random_uuid(),

    source varchar(30) not null default 'CAREERJET',
    -- API Careerjet v4 KHÔNG trả về id cho mỗi tin. Không có khoá ổn định thì
    -- mỗi lần đồng bộ sẽ nhân bản toàn bộ dữ liệu. Dùng sha256 của apply_url
    -- làm khoá thay thế: cùng một tin thì luôn cùng url.
    source_key varchar(64) not null,

    title varchar(300) not null,
    company_name varchar(200),
    location varchar(200),
    -- CHỈ trích đoạn ~120 ký tự do API trả về, không phải mô tả đầy đủ.
    -- Cố ý không lưu JD đầy đủ: đó là văn bản có bản quyền của bên đăng tin.
    excerpt text,

    salary_text varchar(160),
    salary_min numeric(14, 2),
    salary_max numeric(14, 2),
    salary_currency char(3),
    -- Y=năm, M=tháng, W=tuần, D=ngày, H=giờ (theo tài liệu Careerjet)
    salary_type char(1),

    apply_url text not null,
    posted_at timestamptz,

    first_seen_at timestamptz not null default now(),
    -- Cập nhật mỗi lần đồng bộ còn thấy tin này. Tin biến mất khỏi nguồn sẽ
    -- có last_seen_at cũ dần — đây cũng chính là cơ chế dọn link chết, không
    -- cần script riêng đi kiểm tra 404.
    last_seen_at timestamptz not null default now(),
    is_active boolean not null default true,

    constraint ux_external_jobs_source_key unique (source, source_key),
    constraint ck_external_jobs_salary_type
        check (salary_type is null or salary_type in ('Y', 'M', 'W', 'D', 'H'))
);

create index if not exists idx_external_jobs_active_posted
    on external_jobs (is_active, posted_at desc);
create index if not exists idx_external_jobs_company
    on external_jobs (company_name);
create index if not exists idx_external_jobs_last_seen
    on external_jobs (last_seen_at);
