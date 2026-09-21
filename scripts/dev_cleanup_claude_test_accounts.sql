-- ============================================================================
--  DỌN 2 TÀI KHOẢN RÁC DO CLAUDE TẠO NHẦM KHI KIỂM THỬ
-- ============================================================================
--  Lúc kiểm tra ràng buộc MST / số điện thoại, tôi đã gọi thẳng
--  POST /api/v1/auth/b2b/register hai lần. Endpoint đó GHI DỮ LIỆU THẬT, nên
--  đã tạo ra hai tài khoản đối tác cùng hai tổ chức rỗng:
--
--      kt-mst@test.invalid   — CTY KT, MST 079205010931 (sai, 12 số kiểu CCCD)
--      kt-sdt@test.invalid   — CTY KT, SĐT 09434234234 (sai, 11 số đầu số cũ)
--
--  Chính việc chúng được tạo thành công là bằng chứng bản đang chạy chưa có
--  ràng buộc nào. Nhưng chúng không nên nằm lại trong DB.
--
--  Chạy trong Supabase → SQL Editor. Bôi đen toàn bộ file rồi Run.
-- ============================================================================

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
    v_emails  text[] := array['kt-mst@test.invalid', 'kt-sdt@test.invalid'];
    v_email   text;
    v_user_id uuid;
    v_auth_id uuid;
    v_cos     uuid[];
BEGIN
    foreach v_email in array v_emails loop
        select id into v_user_id from app_users  where lower(btrim(email)) = v_email;
        select id into v_auth_id from auth.users where lower(btrim(email)) = v_email;

        if v_user_id is null and v_auth_id is null then
            raise notice '% — không có, bỏ qua.', v_email;
            continue;
        end if;

        if v_user_id is not null then
            select array_agg(id) into v_cos from companies where owner_user_id = v_user_id;
            if v_cos is not null then
                perform pg_temp.purge('companies', v_cos);
                delete from companies where id = any(v_cos);
            end if;
            perform pg_temp.purge('app_users', array[v_user_id]);
            delete from app_users where id = v_user_id;
        end if;

        if v_auth_id is not null then
            delete from auth.identities where user_id = v_auth_id;
            delete from auth.users where id = v_auth_id;
        end if;

        raise notice '% — đã xoá sạch.', v_email;
        v_user_id := null; v_auth_id := null; v_cos := null;
    end loop;
END
$$;


-- Kiểm tra lại: cả hai cột phải bằng 0.
select
    (select count(*) from app_users  where email like 'kt-%@test.invalid') as con_app_users,
    (select count(*) from auth.users where email like 'kt-%@test.invalid') as con_auth_users,
    (select count(*) from companies  where name = 'CTY KT')                as con_to_chuc;
