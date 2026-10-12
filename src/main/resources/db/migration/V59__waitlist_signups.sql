-- Danh sách chờ (waitlist) cho bản Early Access.
--
-- Vì sao để dữ liệu ở ĐÂY chứ không ở Netlify Forms như bản landing rời:
-- Netlify Forms là hộp đen với nhóm — không truy vấn được, không nối được với
-- phần còn lại của hệ thống, giới hạn 100 lượt/tháng ở gói free, và khi nó im
-- lặng không nhận thì không có cách nào biết ngoài việc mở dashboard ra xem.
-- Bảng này nằm cùng chỗ với dữ liệu còn lại nên đếm, lọc, xuất đều làm được
-- bằng SQL thường.
--
-- KHÔNG tham chiếu app_users: người đăng ký waitlist chưa có tài khoản, và
-- phần lớn sẽ không bao giờ tạo. Đây là danh sách người quan tâm, không phải
-- danh sách người dùng.

create table if not exists waitlist_signups (
    id              uuid primary key default gen_random_uuid(),
    full_name       varchar(150) not null,
    email           varchar(255) not null,

    -- Năm câu hỏi khảo sát đều KHÔNG bắt buộc: chúng để hiểu người đăng ký,
    -- không phải để chặn họ. Lưu dạng text tự do thay vì enum — phương án trả
    -- lời còn đổi theo từng đợt khảo sát, mà đổi enum thì phải chạy migration.
    career_stage    varchar(60),
    specialization  varchar(60),
    project_count   varchar(30),
    main_challenge  varchar(60),
    beta_interest   varchar(40),

    -- Đồng ý nhận email. Lưu tách khỏi bản ghi đăng ký vì đây là cơ sở pháp lý
    -- để gửi thư: cần trả lời được "người này có đồng ý không, từ lúc nào".
    email_consent   boolean not null default false,

    -- Nguồn truy cập, để biết đợt truyền thông nào hiệu quả.
    source          varchar(80),
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

-- Một email một chỗ trong danh sách. So theo lower() vì người dùng gõ hoa
-- thường tuỳ hứng, mà "An@gmail.com" với "an@gmail.com" là cùng một người.
-- Nhờ khoá này mà việc gửi lại form là SỬA câu trả lời cũ chứ không tạo thêm
-- một dòng trùng — người đổi ý về lĩnh vực hay mức độ tham gia vẫn điền lại
-- được.
create unique index if not exists waitlist_signups_email_key
    on waitlist_signups (lower(email));

create index if not exists waitlist_signups_created_at_idx
    on waitlist_signups (created_at desc);

comment on table waitlist_signups is
    'Đăng ký danh sách chờ Early Access. Không phải tài khoản người dùng.';
