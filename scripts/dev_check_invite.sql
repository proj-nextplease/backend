-- ============================================================================
--  CHẨN ĐOÁN LỜI MỜI TỔ CHỨC  (chỉ đọc)
-- ============================================================================
--  Sửa email ở dòng dưới rồi Run trong Supabase → SQL Editor.
-- ============================================================================

with target as (select lower(trim('DOI_EMAIL_O_DAY@example.com')) as email)

select
    t.email,
    (select count(*) from auth.users a where lower(a.email) = t.email)      as co_tai_khoan_dang_nhap,
    (select count(*) from app_users u where lower(u.email) = t.email)       as co_dong_app_users,
    (select u.status from app_users u where lower(u.email) = t.email limit 1)     as trang_thai,
    (select u.deleted_at from app_users u where lower(u.email) = t.email limit 1) as da_xoa_luc,
    case
        when (select count(*) from auth.users a where lower(a.email) = t.email) > 0
         and (select count(*) from app_users u where lower(u.email) = t.email) > 0
            then 'BÌNH THƯỜNG — đăng nhập được'
        when (select count(*) from auth.users a where lower(a.email) = t.email) = 0
         and (select count(*) from app_users u where lower(u.email) = t.email) > 0
            then 'CA A — mồ côi: app_users còn, không còn tài khoản đăng nhập'
        when (select count(*) from auth.users a where lower(a.email) = t.email) > 0
         and (select count(*) from app_users u where lower(u.email) = t.email) = 0
            then 'CA B — có tài khoản đăng nhập nhưng thiếu app_users'
        else 'CHƯA CÓ GÌ — luồng đặt mật khẩu sẽ chạy đúng'
    end as chan_doan
from target t;

-- Lời mời của email này
select ci.id, c.name as to_chuc, c.verification_status as trang_thai_duyet,
       ci.node_role, ci.status, ci.expires_at,
       ci.expires_at < now() as da_het_han, ci.accepted_at
from company_invitations ci
join companies c on c.id = ci.company_id
where lower(ci.invited_email) = lower(trim('DOI_EMAIL_O_DAY@example.com'))
order by ci.created_at desc;
