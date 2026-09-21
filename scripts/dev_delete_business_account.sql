-- ============================================================================
--  XOÁ SẠCH MỘT TÀI KHOẢN DOANH NGHIỆP  (chỉ dùng cho môi trường phát triển)
-- ============================================================================
--  Xoá: tổ chức người này sở hữu, toàn bộ tin tuyển dụng / quest / đơn ứng
--  tuyển / thành viên / lời mời của tổ chức đó, hồ sơ phía ứng dụng, VÀ tài
--  khoản đăng nhập bên Supabase Auth.
--
--  KHÁC dev_reset_account.sql: bản kia CHẶN nếu tài khoản đang sở hữu tổ chức.
--  Bản này cố ý xoá luôn tổ chức, nên chỉ dùng khi thật sự muốn xoá cả doanh
--  nghiệp chứ không riêng người dùng.
--
--  ĐỌC TRƯỚC KHI CHẠY:
--    • Xoá tổ chức sẽ xoá luôn ĐƠN ỨNG TUYỂN CỦA NGƯỜI KHÁC vào tin của tổ
--      chức đó. Không tránh được, và không khôi phục được.
--    • Chỉ chạy trên project DEV.
--    • Chạy PHẦN 1 trước để xem sẽ mất những gì, rồi mới chạy PHẦN 2.
-- ============================================================================


-- ============================================================================
--  PHẦN 1 — XEM TRƯỚC (chỉ đọc, không đổi gì)
-- ============================================================================

with target as (
    select id from app_users where lower(btrim(email)) = lower(btrim('phattai280405@gmail.com'))
)
select
    (select count(*) from target)                                              as tim_thay_tai_khoan,
    (select count(*) from auth.users a
      where lower(a.email) = lower(btrim('phattai280405@gmail.com')))           as co_tai_khoan_dang_nhap,
    (select count(*) from companies  where owner_user_id in (select id from target)) as so_to_chuc_so_huu,
    (select count(*) from jobs       where company_id in
        (select id from companies where owner_user_id in (select id from target)))   as so_tin_tuyen_dung,
    (select count(*) from applications where job_id in
        (select j.id from jobs j join companies c on c.id = j.company_id
          where c.owner_user_id in (select id from target)))                         as so_don_ung_tuyen_se_mat;

-- tim_thay_tai_khoan = 0 → gõ sai email, hoặc đang ở nhầm project Supabase.


-- ============================================================================
--  PHẦN 2 — XOÁ THẬT
--  Bôi đen từ đây xuống hết rồi bấm Run. Cả khối là một giao dịch.
-- ============================================================================

/* Hàm dọn đệ quy, tạo trong pg_temp nên tự biến mất khi đóng phiên.

   Vì sao phải đệ quy: phần lớn khoá ngoại trong schema này khai báo NO ACTION,
   tức là chúng CHẶN việc xoá chứ không cascade. Xoá companies bị jobs chặn,
   xoá jobs bị applications chặn, xoá applications bị ratings chặn. Liệt kê tay
   thứ tự đó sẽ sai lại mỗi khi có migration mới, nên hàm này đọc thẳng catalog
   của Postgres và tự đi từ lá lên gốc.

   Quy tắc ở mỗi khoá ngoại:
       cột cho phép NULL → gán NULL  (giữ bản ghi của người khác, chỉ cắt liên kết)
       cột NOT NULL      → xoá dòng  (dòng đó không tồn tại nếu thiếu bản ghi cha) */
create or replace function pg_temp.purge(p_table text, p_ids uuid[], p_depth int default 0)
returns void language plpgsql as $fn$
declare
    r          record;
    v_child    uuid[];
    v_has_id   boolean;
    v_n        integer;
begin
    if p_ids is null or array_length(p_ids, 1) is null then return; end if;
    if p_depth > 8 then
        raise exception 'Đệ quy quá 8 tầng tại bảng % — nhiều khả năng có vòng lặp khoá ngoại, cần xem tay.', p_table;
    end if;

    for r in
        select src.relname::text as bang, att.attname::text as cot, att.attnotnull as bat_buoc
        from pg_constraint con
        join pg_class src on src.oid = con.conrelid
        join pg_class tgt on tgt.oid = con.confrelid
        join pg_attribute att on att.attrelid = con.conrelid and att.attnum = con.conkey[1]
        where con.contype = 'f'
          and tgt.relname = p_table
          and src.relnamespace = 'public'::regnamespace
          and src.relname <> p_table            -- bỏ khoá ngoại tự trỏ vào chính nó
          and array_length(con.conkey, 1) = 1   -- khoá ghép phải xem tay, không đoán
        order by att.attnotnull desc, src.relname
    loop
        if not r.bat_buoc then
            execute format('update public.%I set %I = null where %I = any($1)', r.bang, r.cot, r.cot)
                using p_ids;
            get diagnostics v_n = row_count;
            if v_n > 0 then raise notice '  [%] %.% ← NULL (% dòng)', p_depth, r.bang, r.cot, v_n; end if;
            continue;
        end if;

        /* Bảng nối (user_roles, company_follows…) không có cột id uuid nên
           không thể có cháu trỏ vào — xoá thẳng. Bảng có id thì phải dọn cháu
           trước, nếu không chính chúng sẽ chặn. */
        select exists (
            select 1 from pg_attribute a
            join pg_class c on c.oid = a.attrelid and c.relname = r.bang
                           and c.relnamespace = 'public'::regnamespace
            where a.attname = 'id' and a.atttypid = 'uuid'::regtype and a.attnum > 0
        ) into v_has_id;

        if v_has_id then
            execute format('select array_agg(id) from public.%I where %I = any($1)', r.bang, r.cot)
                into v_child using p_ids;
            perform pg_temp.purge(r.bang, v_child, p_depth + 1);
        end if;

        execute format('delete from public.%I where %I = any($1)', r.bang, r.cot) using p_ids;
        get diagnostics v_n = row_count;
        if v_n > 0 then raise notice '  [%] % ← xoá % dòng', p_depth, r.bang, v_n; end if;
    end loop;
end
$fn$;


DO $$
DECLARE
    v_email    text := 'phattai280405@gmail.com';   -- ← đổi email ở ĐÂY (chỉ một chỗ)
    v_user_id  uuid;
    v_auth_id  uuid;
    v_companies uuid[];
BEGIN
    -- btrim: email lấy từ form hay từ token đôi khi mang khoảng trắng thừa,
    -- và so khớp tuyệt đối sẽ trượt mà không ai hiểu vì sao.
    select id into v_user_id
    from app_users where lower(btrim(email)) = lower(btrim(v_email));

    select id into v_auth_id
    from auth.users where lower(btrim(email)) = lower(btrim(v_email));

    if v_user_id is null and v_auth_id is null then
        raise exception 'Không tìm thấy % ở cả app_users lẫn auth.users.', v_email;
    end if;

    if v_user_id is not null then
        select array_agg(id) into v_companies from companies where owner_user_id = v_user_id;

        /* Xoá tổ chức TRƯỚC. companies.owner_user_id là NOT NULL nên nếu để
           purge('app_users') tự xử thì nó cũng ra kết quả này, nhưng làm riêng
           thì log rõ ràng hơn và đúng thứ tự mong đợi. */
        if v_companies is not null then
            raise notice 'Xoá % tổ chức thuộc sở hữu...', array_length(v_companies, 1);
            perform pg_temp.purge('companies', v_companies);
            delete from companies where id = any(v_companies);
        end if;

        raise notice 'Xoá dữ liệu phía ứng dụng của app_user %...', v_user_id;
        perform pg_temp.purge('app_users', array[v_user_id]);
        delete from app_users where id = v_user_id;
    else
        raise notice 'Không có dòng app_users (tài khoản mồ côi ngược) — bỏ qua.';
    end if;

    /* Xoá luôn tài khoản đăng nhập. Bỏ bước này chính là thứ sinh ra "tài khoản
       mồ côi": một bên còn, một bên mất, và email thì vẫn chiếm chỗ. */
    if v_auth_id is not null then
        delete from auth.identities where user_id = v_auth_id;
        delete from auth.users where id = v_auth_id;
        raise notice 'Đã xoá tài khoản đăng nhập Supabase %.', v_auth_id;
    else
        raise notice 'Không có tài khoản đăng nhập bên Supabase Auth — bỏ qua.';
    end if;

    raise notice 'Xong. % đã được xoá sạch.', v_email;
END
$$;


-- ============================================================================
--  KIỂM TRA LẠI — chạy riêng khối này sau khi Run ở trên
-- ============================================================================
select
    (select count(*) from app_users  where lower(btrim(email)) = lower('phattai280405@gmail.com')) as con_app_users,
    (select count(*) from auth.users where lower(btrim(email)) = lower('phattai280405@gmail.com')) as con_auth_users;
-- Kỳ vọng: cả hai = 0. Lúc đó email này đăng ký lại được từ đầu.
