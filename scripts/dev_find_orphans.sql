-- ============================================================================
--  TÌM TÀI KHOẢN MỒ CÔI  (chỉ đọc, không sửa gì)
-- ============================================================================
--  Mồ côi = còn dòng trong app_users nhưng user bên Supabase Auth đã bị xoá.
--  Hậu quả: người đó không đăng nhập được nữa, VÀ email của họ vẫn chiếm chỗ
--  (app_users.email có ràng buộc unique) nên đăng ký lại cũng vỡ — đúng lỗi
--  503 khi JIT provisioning cố insert.
--
--  Dán vào Supabase → SQL Editor → Run.
-- ============================================================================

select u.id            as app_user_id,
       u.email,
       u.display_name,
       u.status,
       u.created_at,
       u.supabase_user_id,
       coalesce((select string_agg(distinct role_code, ', ')
                 from user_roles where user_id = u.id), '(không có vai trò)') as vai_tro
from app_users u
where u.supabase_user_id is not null
  and not exists (select 1 from auth.users a where a.id = u.supabase_user_id)
order by u.created_at desc;

-- Không ra dòng nào = sạch.
-- Ra dòng nào thì dọn bằng scripts/dev_reset_account.sql với email tương ứng;
-- script đó gỡ hết dữ liệu phụ thuộc trước rồi mới xoá app_users.

-- Tham khảo thêm: app_users chưa từng gắn Auth (do script seed tạo).
-- Đây KHÔNG phải mồ côi, chỉ là tài khoản demo chưa bao giờ đăng nhập được.
select count(*) as so_tai_khoan_seed_khong_co_auth
from app_users where supabase_user_id is null;
