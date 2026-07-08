-- ============================================================================
-- Seed: 6 tin tuyển dụng "chờ Admin duyệt" cho tài khoản nextplease.noreply@gmail.com
--
-- Cách dùng: mở Supabase SQL Editor, dán toàn bộ file này rồi Run.
--   • Tất cả tin được tạo với status = 'PENDING' → chỉ hiện cho Ứng viên
--     SAU KHI Admin duyệt (Admin duyệt sẽ chuyển sang 'OPEN').
--   • deadline_at của mỗi tin là NGẪU NHIÊN và luôn SAU 10/07/2026
--     (mỗi tin một hạn khác nhau — dùng random() nên chạy lại sẽ ra hạn khác).
--   • Script tự resolve đúng company của tài khoản qua authority_nodes
--     (giống logic khi đăng tin thật trong app), nên không cần điền id thủ công.
--
-- An toàn khi chạy nhiều lần? Mỗi lần chạy sẽ TẠO THÊM 6 tin mới (không chống
-- trùng theo tiêu đề). Nếu chỉ muốn seed 1 lần thì chỉ Run 1 lần.
-- ============================================================================

with partner as (
    -- Company mà tài khoản này được quyền đại diện (ưu tiên vai trò OWNER),
    -- đúng như cách app phân giải khi tạo tin (CompanyAccessService).
    select an.user_id, c.id as company_id
    from app_users u
    join authority_nodes an
         on an.user_id = u.id
        and an.status = 'ACTIVE'
        and an.deleted_at is null
    join companies c on c.id = an.company_id
    where lower(u.email) = lower('nextplease.noreply@gmail.com')
    order by case an.node_role
                 when 'OWNER' then 0
                 when 'MANAGER' then 1
                 else 2 end,
             an.created_at asc
    limit 1
),
seed(title, description, job_type, category, specialty, compensation, min_req_rs, location, is_remote, capacity) as (
    values
        ('Thực tập sinh Lập trình Backend (Java/Spring)',
         'Tham gia phát triển API cho hệ thống tuyển dụng, làm việc cùng team senior, được review code hằng tuần. Ưu tiên bạn đã từng làm project cá nhân với Spring Boot hoặc Node.js.',
         'INTERNSHIP', 'TECH', 'SOFTWARE_ENG', 4000000, 10, 'TP. Hồ Chí Minh', false, 3),

        ('Cộng tác viên Thiết kế UI/UX cho sản phẩm mobile',
         'Thiết kế wireframe và giao diện cho ứng dụng di động, phối hợp cùng team sản phẩm. Thành thạo Figma là một lợi thế lớn. Portfolio là bắt buộc khi ứng tuyển.',
         'PART_TIME', 'DESIGN', 'UI_UX', 3000000, 5, 'Remote', true, 2),

        ('Thực tập Marketing & Truyền thông số',
         'Lên kế hoạch nội dung, chạy chiến dịch trên các nền tảng mạng xã hội, đo lường hiệu quả. Phù hợp sinh viên năm 3-4 ngành Marketing hoặc Truyền thông.',
         'INTERNSHIP', 'BUSINESS', 'MARKETING', 3500000, 0, 'Hà Nội', false, 4),

        ('Freelance Biên tập & Sản xuất Video ngắn',
         'Dựng các video ngắn (TikTok/Reels) phục vụ truyền thông thương hiệu. Trả theo sản phẩm, chủ động thời gian. Cần có kỹ năng dựng Premiere hoặc CapCut.',
         'FREELANCE', 'MEDIA', 'VIDEO_PRODUCTION', 5000000, 8, 'Remote', true, 2),

        ('Nhân sự Sự kiện — Ngày hội hướng nghiệp',
         'Hỗ trợ vận hành, đón tiếp và điều phối khách mời trong sự kiện hướng nghiệp quy mô lớn. Làm việc theo ca, có phụ cấp ăn uống và di chuyển.',
         'EVENT_STAFF', 'MEDIA', 'EVENT_PLANNING', 250000, 0, 'TP. Hồ Chí Minh', false, 15),

        ('Thực tập ngắn hạn Phân tích dữ liệu (Data Analyst)',
         'Dự án 6 tuần: làm sạch dữ liệu, dựng dashboard và trình bày insight cho phòng vận hành. Biết SQL cơ bản và một công cụ BI (Power BI/Looker) là lợi thế.',
         'MICRO_INTERNSHIP', 'TECH', 'DATA_SCIENCE', 4500000, 15, 'Đà Nẵng', false, 2)
)
insert into jobs (
    id, company_id, title, description, job_type, category, specialty,
    compensation, compensation_currency, min_req_rs, location, is_remote, capacity,
    deadline_at, status, created_by, content_flag, created_at, updated_at
)
select
    gen_random_uuid(),
    p.company_id,
    s.title,
    s.description,
    s.job_type,
    s.category,
    s.specialty,
    s.compensation,
    'VND',
    s.min_req_rs,
    s.location,
    s.is_remote,
    s.capacity,
    -- Hạn nộp ngẫu nhiên: từ 11/07/2026 trở đi, mỗi tin lệch 0..45 ngày + 0..23 giờ
    (timestamptz '2026-07-11 09:00:00+07'
        + (floor(random() * 45))::int * interval '1 day'
        + (floor(random() * 24))::int * interval '1 hour'),
    'PENDING',
    p.user_id,
    false,
    now(),
    now()
from partner p
cross join seed s;

-- Kiểm tra nhanh sau khi chạy: xem 6 tin vừa tạo và hạn nộp của chúng.
select title, status, deadline_at
from jobs
where created_by = (select user_id from (
        select an.user_id
        from app_users u
        join authority_nodes an on an.user_id = u.id and an.status = 'ACTIVE' and an.deleted_at is null
        where lower(u.email) = lower('nextplease.noreply@gmail.com')
        order by case an.node_role when 'OWNER' then 0 when 'MANAGER' then 1 else 2 end, an.created_at
        limit 1) t)
  and status = 'PENDING'
order by created_at desc
limit 6;
