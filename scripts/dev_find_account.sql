-- ============================================================================
--  TÌM TÀI KHOẢN  (chỉ đọc, không đổi gì)
--  Chạy khi dev_reset_account.sql báo "Không tìm thấy tài khoản với email ...".
--  Trả lời đúng ba câu hỏi, theo thứ tự.
-- ============================================================================

-- 1) Database này có bao nhiêu tài khoản? Có đang nhìn đúng project không?
select
    (select count(*) from app_users)  as tong_app_users,
    (select count(*) from auth.users) as tong_auth_users,
    current_database()                as database_dang_dung;
--  tong_app_users = 0 → đang ở NHẦM project Supabase, hoặc schema chưa migrate.


-- 2) Tìm lỏng theo chuỗi con, bỏ qua hoa/thường và khoảng trắng thừa.
--    Nếu ra dòng có email trông giống nhưng không khớp tuyệt đối thì là lỗi
--    chính tả, hoặc email có khoảng trắng ở đầu/cuối.
select
    id,
    supabase_user_id,
    '[' || email || ']' as email_kem_dau_ngoac,   -- lộ khoảng trắng thừa nếu có
    display_name,
    status,
    created_at
from app_users
where email ilike '%phat%'
order by created_at desc
limit 20;


-- 3) Bên Supabase Auth có, mà app_users không có?
--    Nghĩa là tài khoản đăng nhập vẫn tồn tại nhưng hồ sơ ứng dụng ĐÃ BỊ XOÁ
--    (lần chạy script trước có thể đã xoá thành công rồi).
--    Trường hợp này KHÔNG cần chạy dev_reset_account.sql nữa — chỉ cần đăng
--    nhập lại, backend sẽ tự khởi tạo hồ sơ mới (JIT provisioning).
select
    u.id            as auth_user_id,
    u.email,
    u.created_at    as tao_luc,
    (select count(*) from app_users a where a.supabase_user_id = u.id) as co_trong_app_users
from auth.users u
where u.email ilike '%phat%'
order by u.created_at desc
limit 20;
