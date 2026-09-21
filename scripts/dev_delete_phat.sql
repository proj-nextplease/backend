-- Xoá tài khoản phat280405@gmail.com khỏi database ứng dụng.
-- Dán nguyên file vào Supabase SQL Editor → Run.
DO $$
DECLARE
    v_email   text := 'phat280405@gmail.com';
    v_ids     uuid[];
    v_id      uuid;
    r         record;
    v_pass    integer;
    v_changed integer;
    v_n       integer;
BEGIN
    -- Bắt cả dòng khớp email lẫn dòng trỏ tới tài khoản Auth cùng email
    -- (sau khi xoá bên Auth, hai thứ này có thể lệch nhau).
    select array_agg(distinct id) into v_ids
    from app_users
    where lower(btrim(email)) = lower(btrim(v_email))
       or supabase_user_id in (select u.id from auth.users u where lower(u.email) = lower(v_email));

    if v_ids is null then
        raise exception 'Không có dòng app_users nào khớp %', v_email;
    end if;

    foreach v_id in array v_ids loop
        raise notice 'Xoá app_user %', v_id;

        select count(*) into v_n from companies where owner_user_id = v_id;
        if v_n > 0 then
            raise exception 'app_user % đang sở hữu % tổ chức — dừng lại.', v_id, v_n;
        end if;

        -- Bảng cháu: không trỏ thẳng vào app_users nên vòng lặp không thấy,
        -- nhưng chặn việc xoá bảng cha.
        delete from wallet_transactions where wallet_id in (select id from wallets where user_id = v_id);
        delete from experience_assets where file_asset_id in (select id from file_assets where owner_user_id = v_id);

        -- Dò mọi khoá ngoại trỏ vào app_users: nullable thì gán NULL,
        -- NOT NULL thì xoá dòng. Lặp vì dọn bảng này mở khoá cho bảng kia.
        v_pass := 0;
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
                  and array_length(con.conkey, 1) = 1
                order by att.attnotnull desc, src.relname
            loop
                if r.bat_buoc then
                    execute format('delete from public.%I where %I = $1', r.bang, r.cot) using v_id;
                else
                    execute format('update public.%I set %I = null where %I = $1', r.bang, r.cot, r.cot) using v_id;
                end if;
                get diagnostics v_n = row_count;
                v_changed := v_changed + v_n;
            end loop;
            exit when v_changed = 0;
            if v_pass >= 10 then
                raise exception 'Còn tham chiếu sau 10 vòng — xem tay.';
            end if;
        end loop;

        delete from app_users where id = v_id;
    end loop;

    raise notice 'Xong. Đã xoá % dòng app_users.', array_length(v_ids, 1);
END
$$;
