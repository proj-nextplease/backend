-- ============================================================================
--  TẠO 1 TÀI KHOẢN ỨNG VIÊN ĐỂ TEST  (chỉ dùng cho môi trường phát triển)
-- ============================================================================
--  Dán vào Supabase → SQL Editor → Run.
--
--  CÓ CÁCH AN TOÀN HƠN, CÂN NHẮC TRƯỚC:
--      Supabase Dashboard → Authentication → Users → "Add user"
--      → điền email + mật khẩu, bật "Auto Confirm User".
--  Cách đó không phải đụng tay vào các bảng nội bộ của schema `auth`, nên
--  không có rủi ro lệch với phiên bản Supabase. Script này chỉ nên dùng khi
--  bạn cần tạo hàng loạt hoặc muốn seed sẵn dữ liệu hồ sơ trong cùng một lần.
--
--  ĐỌC TRƯỚC KHI CHẠY:
--    • Script ghi vào schema `auth` (auth.users, auth.identities). Đây là vùng
--      nội bộ của Supabase — chỉ chạy trên project DEV, tuyệt đối không chạy
--      trên project có dữ liệu người dùng thật.
--    • Mật khẩu bên dưới là mật khẩu DÙNG MỘT LẦN để test. Đổi nó trước khi
--      chạy, và đừng commit file này sau khi đã điền mật khẩu thật.
--    • Script idempotent: chạy lại với cùng email thì không tạo trùng, chỉ
--      bổ sung những bản ghi còn thiếu.
--    • Muốn xoá tài khoản test sau khi xong: dùng scripts/dev_reset_account.sql
--      (phần auth.users phải xoá thêm bằng tay hoặc qua Dashboard).
-- ============================================================================

BEGIN;

-- Sửa 3 giá trị trong khối DO ngay bên dưới (v_email / v_password / v_name).

DO $$
DECLARE
    -- Sửa ở đây nếu chạy trong Supabase SQL Editor:
    v_email    text := 'ungvien.test@nextplease.dev';
    v_password text := 'Doi-mat-khau-nay-1';
    v_name     text := 'Ứng viên Test';

    v_auth_id  uuid;
    v_app_id   uuid;
BEGIN
    v_email := lower(trim(v_email));

    -- ── 1. Tài khoản đăng nhập (schema auth của Supabase) ───────────────────
    SELECT id INTO v_auth_id FROM auth.users WHERE email = v_email;

    IF v_auth_id IS NULL THEN
        v_auth_id := gen_random_uuid();

        -- email_confirmed_at đặt sẵn = now() để bỏ qua bước xác thực email;
        -- không có nó thì đăng nhập sẽ bị chặn với lỗi "Email not confirmed".
        INSERT INTO auth.users (
            instance_id, id, aud, role, email, encrypted_password,
            email_confirmed_at, raw_app_meta_data, raw_user_meta_data,
            created_at, updated_at
        ) VALUES (
            '00000000-0000-0000-0000-000000000000',
            v_auth_id,
            'authenticated',
            'authenticated',
            v_email,
            crypt(v_password, gen_salt('bf')),
            now(),
            jsonb_build_object('provider', 'email', 'providers', jsonb_build_array('email')),
            jsonb_build_object('full_name', v_name),
            now(),
            now()
        );

        -- Supabase cần một dòng identity tương ứng thì luồng đăng nhập bằng
        -- email/mật khẩu mới nhận diện được tài khoản.
        INSERT INTO auth.identities (
            id, user_id, provider_id, identity_data, provider,
            last_sign_in_at, created_at, updated_at
        ) VALUES (
            gen_random_uuid(),
            v_auth_id,
            v_auth_id::text,
            jsonb_build_object('sub', v_auth_id::text, 'email', v_email, 'email_verified', true),
            'email',
            now(), now(), now()
        );

        RAISE NOTICE 'Đã tạo tài khoản đăng nhập: % (auth id %)', v_email, v_auth_id;
    ELSE
        RAISE NOTICE 'Tài khoản đăng nhập đã tồn tại, bỏ qua: % (auth id %)', v_email, v_auth_id;
    END IF;

    -- ── 2. Bản ghi phía ứng dụng ────────────────────────────────────────────
    --  Backend có cơ chế JIT provisioning (UserJitProvisioningService): lần đầu
    --  tài khoản gọi API, nó tự tạo app_users + user_roles + profiles + wallets.
    --  Nên phần dưới KHÔNG bắt buộc — nhưng tạo sẵn thì dashboard có dữ liệu
    --  ngay từ lần mở đầu tiên. Các cột bám đúng theo service đó.
    SELECT id INTO v_app_id FROM app_users WHERE supabase_user_id = v_auth_id;

    IF v_app_id IS NULL THEN
        v_app_id := gen_random_uuid();

        INSERT INTO app_users (id, supabase_user_id, email, display_name,
                               status, auth_provider, created_at, updated_at)
        VALUES (v_app_id, v_auth_id, v_email, v_name, 'ACTIVE', 'supabase', now(), now());

        RAISE NOTICE 'Đã tạo app_users: %', v_app_id;
    ELSE
        RAISE NOTICE 'app_users đã tồn tại, bỏ qua: %', v_app_id;
    END IF;

    INSERT INTO user_roles (user_id, role_code)
    VALUES (v_app_id, 'candidate_free')
    ON CONFLICT (user_id, role_code) DO NOTHING;

    INSERT INTO profiles (user_id, headline, avatar_url, visibility)
    VALUES (v_app_id, 'Ứng viên nextplease', NULL, '{}'::jsonb)
    ON CONFLICT (user_id) DO NOTHING;

    INSERT INTO wallets (user_id, np_balance, locked_np_balance)
    VALUES (v_app_id, 0, 0)
    ON CONFLICT (user_id) DO NOTHING;

    INSERT INTO audit_logs (actor_user_id, action, entity_type, entity_id, metadata)
    VALUES (v_app_id, 'candidate.seeded_by_script', 'app_user', v_app_id,
            jsonb_build_object('provider', 'supabase', 'source', 'dev_create_candidate_account.sql'));
END
$$;

COMMIT;


-- ============================================================================
--  KIỂM TRA — chạy riêng khối này sau khi Run ở trên
-- ============================================================================
SELECT
    au.email,
    au.display_name,
    au.status,
    u.email_confirmed_at IS NOT NULL AS da_xac_thuc_email,
    (SELECT string_agg(role_code, ', ') FROM user_roles WHERE user_id = au.id) AS vai_tro,
    (SELECT count(*) FROM profiles WHERE user_id = au.id) AS so_profile,
    (SELECT count(*) FROM wallets  WHERE user_id = au.id) AS so_vi
FROM app_users au
JOIN auth.users u ON u.id = au.supabase_user_id
WHERE au.email = lower('ungvien.test@nextplease.dev');

-- Kỳ vọng: da_xac_thuc_email = true, vai_tro = candidate_free,
--          so_profile = 1, so_vi = 1.
-- Sau đó đăng nhập ở /candidate/login bằng đúng email + mật khẩu đã đặt.
