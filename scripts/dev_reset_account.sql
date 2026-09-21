-- ============================================================================
--  XOÁ TÀI KHOẢN ĐỂ TEST LẠI TỪ ĐẦU  (chỉ dùng cho môi trường phát triển)
-- ============================================================================
--  Chạy trong Supabase → SQL Editor.
--
--  VÌ SAO BẢN NÀY KHÁC BẢN TRƯỚC:
--  Bản trước liệt kê tay các bảng cần dọn, dựa trên giả định "xoá app_users sẽ
--  kéo theo mọi thứ qua ON DELETE CASCADE". Giả định đó SAI: phần lớn khoá
--  ngoại trỏ vào app_users không khai báo `on delete` nào, nên mặc định là
--  NO ACTION — chúng CHẶN việc xoá chứ không cascade. Danh sách tay bỏ sót
--  hàng chục cột (hidden_by, approved_by, verified_by, checked_by,
--  reviewer_user_id, issued_by, updated_by, created_by…) và sẽ lại thiếu mỗi
--  lần thêm migration mới.
--
--  Bản này KHÔNG liệt kê tay. Nó đọc chính catalog của Postgres để tìm mọi
--  khoá ngoại đang trỏ vào app_users, rồi xử theo quy tắc:
--      cột cho phép NULL   → gán NULL   (giữ lại bản ghi của người khác)
--      cột NOT NULL        → xoá dòng   (dòng đó không tồn tại thiếu người này)
--  Chạy lặp nhiều vòng vì xoá ở bảng này có thể mở khoá cho bảng kia.
--
--  KHÔNG dùng trên dữ liệu thật: xoá vĩnh viễn, không khôi phục được.
-- ============================================================================


-- ============================================================================
--  PHẦN 1 — XEM TRƯỚC (chỉ đọc)
--  Liệt kê mọi bảng đang giữ tham chiếu tới tài khoản này, và sẽ bị đụng tới.
-- ============================================================================

with target as (
    select id from app_users where lower(email) = lower('phat280405@gmail.com')
),
fks as (
    select
        src.relname::text  as bang,
        att.attname::text  as cot,
        att.attnotnull     as bat_buoc,
        con.confdeltype    as kieu_xoa
    from pg_constraint con
    join pg_class  src on src.oid = con.conrelid
    join pg_class  tgt on tgt.oid = con.confrelid
    join pg_attribute att on att.attrelid = con.conrelid and att.attnum = con.conkey[1]
    where con.contype = 'f'
      and tgt.relname = 'app_users'
      and src.relnamespace = 'public'::regnamespace
      and array_length(con.conkey, 1) = 1
)
select
    f.bang,
    f.cot,
    case when f.bat_buoc then 'NOT NULL → xoá dòng' else 'nullable → gán NULL' end as cach_xu_ly,
    case f.kieu_xoa when 'c' then 'CASCADE' when 'n' then 'SET NULL' when 'a' then 'NO ACTION (chặn)'
                    when 'r' then 'RESTRICT (chặn)' else f.kieu_xoa::text end      as on_delete,
    (select count(*) from target) as tim_thay_tai_khoan
from fks f
order by f.bat_buoc desc, f.bang;

--  tim_thay_tai_khoan = 0 → email không có trong DB, kiểm tra lại chính tả.


-- ============================================================================
--  PHẦN 2 — XOÁ THẬT
--  Bôi đen cả khối DO bên dưới rồi bấm Run. Cả khối là một giao dịch: lỗi bất kỳ
--  thì tự huỷ toàn bộ, dữ liệu giữ nguyên.
-- ============================================================================

DO $$
DECLARE
    v_email   text := 'phat280405@gmail.com';   -- ← đổi email ở ĐÂY (chỉ một chỗ)
    v_user_id uuid;
    v_owned   integer;
    r         record;
    v_pass    integer := 0;
    v_changed integer;
    v_total   integer;
BEGIN
    -- btrim: email nhập từ form hoặc từ token đôi khi mang khoảng trắng thừa,
    -- và so khớp tuyệt đối sẽ trượt mà không ai hiểu vì sao.
    select id into v_user_id
    from app_users
    where lower(btrim(email)) = lower(btrim(v_email));

    if v_user_id is null then
        raise exception
            'Không tìm thấy % trong app_users. Chạy scripts/dev_find_account.sql để biết là do gõ sai email, do đang ở nhầm project Supabase, hay do hồ sơ đã bị xoá từ trước.',
            v_email;
    end if;

    /* Chặn cứng: tài khoản đang làm chủ một tổ chức.
       Xoá nó sẽ làm mồ côi tổ chức cùng toàn bộ tin tuyển dụng, đơn ứng tuyển
       và thành viên. Đó là việc cần cân nhắc từng trường hợp, không phải việc
       một script dọn tài khoản test được phép tự quyết. */
    select count(*) into v_owned from companies where owner_user_id = v_user_id;
    if v_owned > 0 then
        raise exception 'Tài khoản đang sở hữu % tổ chức. Chuyển quyền sở hữu hoặc xoá tổ chức trước.', v_owned;
    end if;

    raise notice 'Đang xoá app_user %', v_user_id;

    /* ── Bảng CHÁU: không trỏ thẳng vào app_users nên vòng lặp bên dưới không
       nhìn thấy, nhưng lại chặn việc xoá bảng cha.
           wallet_transactions.wallet_id   → wallets       (wallets.user_id NOT NULL → sẽ bị xoá)
           experience_assets.file_asset_id → file_assets   (file_assets.owner_user_id NOT NULL → sẽ bị xoá)
       Nếu sau này xuất hiện chuỗi tương tự, Postgres sẽ báo lỗi nêu đích danh
       ràng buộc chặn — thêm một dòng vào đây là xong. */
    delete from wallet_transactions
    where wallet_id in (select id from wallets where user_id = v_user_id);

    delete from experience_assets
    where file_asset_id in (select id from file_assets where owner_user_id = v_user_id);

    /* Nhiều vòng: gán NULL / xoá ở bảng này có thể mở khoá cho bảng kia
       (vd phải xoá ratings mới xoá được applications). Dừng khi một vòng
       không đụng tới dòng nào nữa. */
    loop
        v_pass := v_pass + 1;
        v_changed := 0;

        for r in
            select src.relname::text as bang, att.attname::text as cot, att.attnotnull as bat_buoc
            from pg_constraint con
            join pg_class src on src.oid = con.conrelid
            join pg_class tgt on tgt.oid = con.confrelid
            join pg_attribute att on att.attrelid = con.conrelid and att.attnum = con.conkey[1]
            where con.contype = 'f'
              and tgt.relname = 'app_users'
              and src.relnamespace = 'public'::regnamespace
              and src.relname <> 'app_users'
              -- Chỉ xử khoá ngoại một cột. Khoá ghép trỏ vào app_users hiện
              -- không có; nếu sau này có thì phải xem tay chứ không gán NULL bừa.
              and array_length(con.conkey, 1) = 1
            order by att.attnotnull desc, src.relname
        loop
            if r.bat_buoc then
                execute format('delete from public.%I where %I = $1', r.bang, r.cot) using v_user_id;
            else
                execute format('update public.%I set %I = null where %I = $1', r.bang, r.cot, r.cot) using v_user_id;
            end if;
            get diagnostics v_total = row_count;
            if v_total > 0 then
                v_changed := v_changed + v_total;
                raise notice '  vòng % — %.% : % dòng', v_pass, r.bang, r.cot, v_total;
            end if;
        end loop;

        exit when v_changed = 0;
        if v_pass >= 10 then
            raise exception 'Vẫn còn tham chiếu sau 10 vòng — có vòng lặp khoá ngoại, cần xem tay.';
        end if;
    end loop;

    delete from app_users where id = v_user_id;
    raise notice 'Xong. Đã xoá tài khoản % sau % vòng.', v_email, v_pass;
END
$$;


-- ============================================================================
--  SAU KHI XOÁ
-- ============================================================================
--  Script này chỉ dọn database của ứng dụng. Tài khoản đăng nhập bên Supabase
--  Auth VẪN CÒN. Hai lựa chọn:
--
--  (a) Giữ nguyên: đăng nhập lại bằng chính Google đó, backend tự khởi tạo hồ
--      sơ mới (JIT provisioning). Đủ để test lại onboarding và portfolio.
--
--  (b) Test từ đúng giây đầu: Supabase → Authentication → Users, tìm email và
--      xoá. Lần sau sẽ là người dùng hoàn toàn mới, kể cả supabase_user_id.
--
--  LƯU Ý về cổng điều khoản: bảng user_consents nằm trong nhóm bị xoá theo,
--  nên sau khi reset, lần đăng nhập kế tiếp sẽ hiện lại cổng xin đồng ý —
--  đúng như với một người dùng mới.
-- ============================================================================
