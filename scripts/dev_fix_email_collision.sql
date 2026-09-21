-- ============================================================================
--  ĐỤNG EMAIL SAU KHI XOÁ TÀI KHOẢN SUPABASE AUTH
-- ============================================================================
--  Triệu chứng: đăng nhập được, nhưng mọi lời gọi backend trả 503
--  "Database is temporarily unavailable"; log backend có dòng
--      Concurrent JIT provisioning detected ...
--      EmptyResultDataAccessException: expected 1, actual 0
--
--  Nguyên nhân: app_users có ràng buộc duy nhất trên CẢ supabase_user_id lẫn
--  email. Xoá người dùng bên Supabase Auth rồi đăng nhập lại sẽ sinh
--  supabase_user_id MỚI, nhưng dòng app_users cũ vẫn giữ email đó — nên lần
--  tạo mới bị chặn bởi ràng buộc email.
-- ============================================================================

-- ── 1. Xem va chạm ─────────────────────────────────────────────────────────
select
    a.id                as app_user_id,
    a.email,
    a.supabase_user_id  as supabase_id_trong_app_users,
    a.created_at,
    (select count(*) from auth.users u where u.id = a.supabase_user_id) as con_ben_auth
from app_users a
where lower(btrim(a.email)) = lower(btrim('phat280405@gmail.com'));

--  con_ben_auth = 0 → dòng này mồ côi: tài khoản Auth tương ứng đã bị xoá.
--  Đó chính là dòng đang chặn. Chọn MỘT trong hai cách bên dưới.


-- ── 2a. GIỮ dữ liệu cũ: trỏ dòng cũ sang tài khoản Auth mới ────────────────
--  Dùng khi muốn giữ nguyên portfolio, điểm uy tín, đơn ứng tuyển…
--  Lấy supabase_user_id mới từ log backend (dòng "JIT provisioning ... for
--  Supabase user: <id>"), hoặc từ auth.users theo email.

-- update app_users
-- set supabase_user_id = (
--     select u.id from auth.users u
--     where lower(u.email) = lower('phat280405@gmail.com')
--     order by u.created_at desc
--     limit 1
-- )
-- where lower(btrim(email)) = lower(btrim('phat280405@gmail.com'));


-- ── 2b. XOÁ HẲN để test từ đầu ─────────────────────────────────────────────
--  Chạy scripts/dev_reset_account.sql. Nó dọn mọi tham chiếu rồi xoá app_users.
--  Sau đó đăng nhập lại, JIT sẽ tạo hồ sơ mới tinh.


-- ── 3. Kiểm lại ────────────────────────────────────────────────────────────
-- select id, email, supabase_user_id from app_users
-- where lower(btrim(email)) = lower(btrim('phat280405@gmail.com'));
