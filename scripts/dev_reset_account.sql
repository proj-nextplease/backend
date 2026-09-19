-- ============================================================================
--  XOÁ TÀI KHOẢN ĐỂ TEST LẠI TỪ ĐẦU  (chỉ dùng cho môi trường phát triển)
-- ============================================================================
--  Chạy trong Supabase → SQL Editor. Máy dev không có psql nên đây là đường
--  ngắn nhất.
--
--  CÁCH DÙNG: chạy PHẦN 1 trước, đọc kết quả, rồi mới chạy PHẦN 2.
--  Muốn xoá tài khoản khác: đổi email ở dòng @email trong cả hai phần.
--
--  KHÔNG dùng trên dữ liệu thật: script xoá vĩnh viễn, không khôi phục được.
--
--  Thứ tự xoá bên dưới bám theo đồ thị khoá ngoại thật của schema: xoá
--  app_users sẽ kéo theo 33 bảng qua ON DELETE CASCADE, nhưng có 3 bảng trỏ
--  vào nhóm đó bằng RESTRICT và sẽ chặn toàn bộ nếu không dọn trước:
--      wallets            <- wallet_transactions.wallet_id
--      applications       <- ratings.application_id
--      quest_applications <- ratings.quest_application_id
--  Ngoài ra file_assets phải xoá SAU experiences, vì experience_assets giữ
--  file_asset_id bằng RESTRICT.
-- ============================================================================


-- ============================================================================
--  PHẦN 1 — XEM TRƯỚC (chỉ đọc, không đổi gì)
--  Bôi đen từ đây tới hết PHẦN 1 rồi bấm Run.
-- ============================================================================

with target as (
    select id, supabase_user_id, email, display_name, created_at
    from app_users
    where lower(email) = lower('phat280405@gmail.com')
),
counts as (
    select 'profiles'                as bang, count(*) as so_dong from profiles p, target t      where p.user_id = t.id
    union all select 'CẢNH BÁO: sở hữu tổ chức', count(*) from companies c, target t             where c.owner_user_id = t.id
    union all select 'applications',        count(*) from applications a, target t               where a.candidate_id = t.id
    union all select 'quest_applications',  count(*) from quest_applications q, target t         where q.candidate_id = t.id
    union all select 'wallet_transactions', count(*) from wallet_transactions wt
                                                     where wt.wallet_id in (select w.id from wallets w, target t where w.user_id = t.id)
    union all select 'experiences',         count(*) from experiences e
                                                     where e.profile_id in (select p.id from profiles p, target t where p.user_id = t.id)
    union all select 'file_assets',         count(*) from file_assets f, target t                where f.owner_user_id = t.id
    union all select 'discussion_posts',    count(*) from discussion_posts d, target t           where d.author_user_id = t.id
    union all select 'discussion_comments', count(*) from discussion_comments d, target t        where d.author_user_id = t.id
    union all select 'saved_jobs',          count(*) from saved_jobs s, target t                 where s.user_id = t.id
    union all select 'notifications',       count(*) from notifications n, target t              where n.user_id = t.id
    union all select 'authority_nodes',     count(*) from authority_nodes an, target t           where an.user_id = t.id
)
select
    (select email from target)            as email,
    (select id from target)               as app_user_id,
    (select supabase_user_id from target) as supabase_user_id,
    c.bang,
    c.so_dong
from counts c
where c.so_dong > 0 or c.bang = 'profiles'
order by c.so_dong desc;

-- Không ra dòng nào  → email không tồn tại trong DB, kiểm tra lại chính tả.
-- Có "CẢNH BÁO: sở hữu tổ chức" > 0 → DỪNG, đọc mục GHI CHÚ ở cuối file.


-- ============================================================================
--  PHẦN 2 — XOÁ THẬT
--  Bôi đen từ BEGIN tới COMMIT rồi bấm Run. Cả khối chạy trong một giao dịch:
--  nếu có bất kỳ lỗi nào thì toàn bộ tự huỷ, dữ liệu giữ nguyên.
-- ============================================================================

BEGIN;

-- Giữ id vào bảng tạm để khỏi lặp lại câu tìm email ở mọi lệnh bên dưới.
-- Bảng tạm tự biến mất khi phiên kết thúc.
create temporary table _target on commit drop as
select id from app_users where lower(email) = lower('phat280405@gmail.com');

select id as dang_xoa_user_id from _target;


-- ── Bước 1: các bảng cháu chắt chặn nhóm CASCADE ───────────────────────────

-- Giao dịch ví chặn việc xoá ví.
delete from wallet_transactions
where wallet_id in (select w.id from wallets w where w.user_id in (select id from _target));

-- Đánh giá chặn việc xoá đơn ứng tuyển (cả job lẫn quest).
delete from ratings
where candidate_user_id in (select id from _target)
   or rater_user_id     in (select id from _target)
   or application_id       in (select a.id from applications a       where a.candidate_id in (select id from _target))
   or quest_application_id in (select q.id from quest_applications q where q.candidate_id in (select id from _target));

-- experience_assets giữ file_asset_id bằng RESTRICT, nên phải xoá kinh nghiệm
-- TRƯỚC file_assets. Xoá experiences sẽ cascade sang experience_assets.
delete from experiences
where profile_id in (select p.id from profiles p where p.user_id in (select id from _target));


-- ── Bước 2: các bảng trỏ thẳng vào app_users bằng RESTRICT ─────────────────

delete from file_assets        where owner_user_id  in (select id from _target);
delete from discussion_comments where author_user_id in (select id from _target);
delete from discussion_posts    where author_user_id in (select id from _target);
delete from email_logs          where user_id        in (select id from _target);
delete from audit_logs          where actor_user_id  in (select id from _target);

-- Quan hệ với tổ chức (nếu tài khoản từng được mời vào một CLB/doanh nghiệp).
delete from authority_nodes     where user_id     in (select id from _target);
delete from company_invitations where accepted_by in (select id from _target);
update company_invitations set invited_by = null
where invited_by in (select id from _target);

-- Tiền và gói dịch vụ.
delete from payment_requests where user_id in (select id from _target);
delete from subscriptions    where user_id in (select id from _target);

-- Báo cáo và cờ gian lận — thường rỗng với tài khoản test.
delete from reports
where reporter_user_id in (select id from _target)
   or target_user_id   in (select id from _target)
   or reviewed_by      in (select id from _target);

delete from fraud_flags
where user_id     in (select id from _target)
   or created_by  in (select id from _target)
   or resolved_by in (select id from _target);


-- ── Bước 3: xoá tài khoản. 33 bảng CASCADE theo sau tự động ────────────────
--  Nếu còn sót ràng buộc nào, lệnh này báo lỗi và CẢ GIAO DỊCH bị huỷ —
--  không mất gì cả. Gửi nguyên văn lỗi đó để bổ sung.

delete from app_users where id in (select id from _target);

COMMIT;


-- ============================================================================
--  SAU KHI XOÁ
-- ============================================================================
--  Script này chỉ dọn database của ứng dụng. Tài khoản đăng nhập bên
--  Supabase Auth VẪN CÒN. Hai lựa chọn:
--
--  (a) Giữ nguyên Supabase Auth: đăng nhập lại bằng chính Google đó, backend
--      sẽ tự khởi tạo hồ sơ mới tinh (JIT provisioning). Đủ để test lại luồng
--      onboarding và portfolio.
--
--  (b) Test lại từ đúng giây đầu tiên: vào Supabase → Authentication → Users,
--      tìm phat280405@gmail.com và xoá. Lần đăng nhập sau sẽ là một người dùng
--      hoàn toàn mới, kể cả supabase_user_id.
--
--  GHI CHÚ — nếu PHẦN 1 báo "CẢNH BÁO: sở hữu tổ chức" > 0:
--  Tài khoản đang là chủ một tổ chức. Xoá nó sẽ làm mồ côi tổ chức đó cùng
--  toàn bộ tin tuyển dụng, đơn ứng tuyển và thành viên. Đừng chạy PHẦN 2.
--  Hãy chuyển quyền sở hữu sang tài khoản khác trước, hoặc xoá tổ chức đó
--  trước — đó là việc riêng, cần cân nhắc từng trường hợp.
-- ============================================================================
