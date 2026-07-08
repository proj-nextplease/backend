-- ============================================================================
-- Seed: 6 Quest "chờ Admin duyệt" cho tài khoản nextplease.noreply@gmail.com
--
-- VÌ SAO LÀ QUEST (không phải Job)?
--   Tài khoản này là CLB. Trong app, đối tác loại CLB chỉ đăng QUEST — màn
--   "Đăng tin" của CLB render QuestPostForm, và trang hồ sơ đối tác của CLB
--   chỉ hiển thị tab "Quest & Hoạt động". Nếu tạo Job cho một CLB thì tin đó
--   sẽ KHÔNG hiện trên trang hồ sơ CLB (chỉ lọt ra Bảng cơ hội chung) → lệch
--   mô hình. Vì vậy đúng nghiệp vụ là seed Quest cho CLB.
--
-- Cách dùng: Supabase SQL Editor → dán → Run.
--   • status = 'PENDING' → chỉ hiện cho Ứng viên SAU KHI Admin duyệt (→ 'OPEN').
--   • "Hạn" của Quest là cột ends_at → NGẪU NHIÊN, luôn SAU 10/07/2026,
--     mỗi Quest một hạn khác nhau (dùng random()).
--   • starts_at đặt là now() cho đơn giản.
--   • Script tự resolve company qua authority_nodes (giống logic app).
-- ============================================================================

with partner as (
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
seed(title, description, category, exp_reward, np_reward, min_req_rs, capacity) as (
    values
        ('Hỗ trợ tổ chức Workshop khởi nghiệp',
         'Tham gia team hậu cần & điều phối cho buổi workshop chia sẻ về khởi nghiệp: chuẩn bị vật tư, check-in khách mời, hỗ trợ diễn giả. Phù hợp bạn năng động, thích môi trường sự kiện.',
         'SMALL_EVENT', 100, 0, 0, 10),

        ('Content Creator cho chiến dịch truyền thông CLB',
         'Sản xuất nội dung (bài viết, hình ảnh, video ngắn) cho chiến dịch truyền thông của câu lạc bộ trên các nền tảng mạng xã hội. Được mentor về xây dựng thương hiệu cá nhân.',
         'SCHOOL_CAMPAIGN', 300, 0, 5, 4),

        ('Thành viên Ban Tổ chức Ngày hội Sinh viên',
         'Đồng hành cùng ban tổ chức trong chuỗi hoạt động Ngày hội Sinh viên cấp trường: lên kế hoạch, phân công gian hàng, điều phối tình nguyện viên.',
         'SCHOOL_CAMPAIGN', 300, 0, 8, 6),

        ('Dự án nhỏ: Thiết kế bộ nhận diện cho sự kiện',
         'Thiết kế poster, standee và bộ ấn phẩm số cho một sự kiện của CLB. Làm việc theo brief, có phản hồi từ ban chuyên môn. Ưu tiên bạn dùng tốt Canva/Figma.',
         'SMALL_EVENT', 100, 0, 0, 3),

        ('Freelance quay dựng aftermovie sự kiện',
         'Quay và dựng aftermovie tổng kết cho một sự kiện lớn của câu lạc bộ. Chủ động thời gian, output là 1 video 2-3 phút. Cần kỹ năng dựng cơ bản.',
         'FREELANCE_GIG', 300, 0, 10, 2),

        ('Trợ lý dự án Chiến dịch Xanh cấp trường',
         'Hỗ trợ triển khai chiến dịch môi trường cấp trường: điều phối tình nguyện viên, theo dõi tiến độ, tổng hợp báo cáo hoạt động cuối chiến dịch.',
         'SCHOOL_CAMPAIGN', 300, 0, 5, 8)
)
insert into quests (
    company_id, created_by, title, description, category,
    exp_reward, np_reward, min_req_rs, capacity,
    starts_at, ends_at, status, created_at, updated_at
)
select
    p.company_id,
    p.user_id,
    s.title,
    s.description,
    s.category,
    s.exp_reward,
    s.np_reward,
    s.min_req_rs,
    s.capacity,
    now(),
    -- Hạn (ends_at) ngẫu nhiên: từ 11/07/2026 trở đi, mỗi Quest lệch 0..45 ngày + 0..23 giờ
    (timestamptz '2026-07-11 09:00:00+07'
        + (floor(random() * 45))::int * interval '1 day'
        + (floor(random() * 24))::int * interval '1 hour'),
    'PENDING',
    now(),
    now()
from partner p
cross join seed s;

-- Kiểm tra nhanh sau khi chạy:
select title, status, ends_at
from quests
where created_by = (
        select an.user_id
        from app_users u
        join authority_nodes an on an.user_id = u.id and an.status = 'ACTIVE' and an.deleted_at is null
        where lower(u.email) = lower('nextplease.noreply@gmail.com')
        order by case an.node_role when 'OWNER' then 0 when 'MANAGER' then 1 else 2 end, an.created_at
        limit 1)
  and status = 'PENDING'
order by created_at desc
limit 6;
