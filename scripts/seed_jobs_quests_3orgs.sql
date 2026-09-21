-- ============================================================================
--  SEED TIN TUYỂN DỤNG + QUEST CHO 3 TỔ CHỨC
-- ============================================================================
--  Tạo:
--    • 3 tin/tổ chức cho  kt-mst@test.invalid       (CTY KT)
--    • 3 tin/tổ chức cho  kt-sdt@test.invalid       (CTY KT)
--    • 4 tin/tổ chức cho  phattai280405@gmail.com
--    • 4 quest            cho  nextplease.noreply@gmail.com
--
--  Cách dùng: Supabase → SQL Editor → dán cả file → Run.
--
--  ĐỌC TRƯỚC KHI CHẠY:
--    • Tất cả đặt status = 'PENDING' để còn chỗ test luồng Admin duyệt.
--      Muốn hiện ngay cho ứng viên thì đổi v_status thành 'OPEN' ở đầu khối DO.
--    • Script tự tìm company qua authority_nodes, ưu tiên vai trò OWNER —
--      đúng cách CompanyAccessService phân giải khi đăng tin thật. Không cần
--      điền id thủ công.
--    • Email nào không tìm thấy tổ chức thì BỎ QUA và báo notice, các email
--      còn lại vẫn chạy. Không có chuyện một email sai làm hỏng cả mẻ.
--    • CHỐNG TRÙNG theo (company_id, title): chạy lại nhiều lần không sinh
--      bản sao. Muốn tạo thêm đợt mới thì đổi tiêu đề.
--
--  LƯU Ý: hai tài khoản kt-*@test.invalid là hai tài khoản tôi lỡ tạo lúc
--  kiểm thử. Nếu sau này bạn chạy dev_cleanup_claude_test_accounts.sql thì
--  toàn bộ tin seed ở đây cho chúng cũng bị xoá theo.
-- ============================================================================

DO $$
DECLARE
    v_status  text := 'PENDING';   -- ← đổi thành 'OPEN' nếu muốn hiện ngay

    v_email   text;
    v_user    uuid;
    v_company uuid;
    v_made    integer;
    r         record;
BEGIN
-- ─────────────────────────────────────────────────────────────────────────
--  PHẦN A — TIN TUYỂN DỤNG
-- ─────────────────────────────────────────────────────────────────────────
FOR v_email IN SELECT unnest(array[
        'kt-mst@test.invalid',
        'kt-sdt@test.invalid',
        'phattai280405@gmail.com'
    ])
LOOP
    /* Phân giải tổ chức giống hệt app: đi qua authority_nodes đang ACTIVE,
       ưu tiên OWNER rồi MANAGER. Không dùng companies.owner_user_id vì một
       người có thể là MANAGER của tổ chức mà không sở hữu nó. */
    select an.user_id, c.id into v_user, v_company
    from app_users u
    join authority_nodes an on an.user_id = u.id
                           and an.status = 'ACTIVE'
                           and an.deleted_at is null
    join companies c on c.id = an.company_id and c.deleted_at is null
    where lower(btrim(u.email)) = lower(btrim(v_email))
    order by case an.node_role when 'OWNER' then 0 when 'MANAGER' then 1 else 2 end,
             an.created_at
    limit 1;

    if v_company is null then
        raise notice 'BỎ QUA % — không tìm thấy tổ chức nào tài khoản này đại diện.', v_email;
        continue;
    end if;

    v_made := 0;

    for r in
        select * from (values
            ('Thực tập sinh Lập trình Backend (Java/Spring Boot)',
             E'Tham gia phát triển và bảo trì API cho hệ thống nội bộ. Bạn sẽ làm việc trực tiếp trên repo thật, được review code hằng tuần và tham gia họp sprint cùng team.\n\nYêu cầu: nắm cơ bản Java hoặc một ngôn ngữ OOP, đã từng làm ít nhất một project cá nhân có kết nối cơ sở dữ liệu. Không yêu cầu kinh nghiệm đi làm.\n\nQuyền lợi: phụ cấp theo tháng, laptop cấp sẵn, xác nhận thực tập và thư giới thiệu nếu hoàn thành tốt.',
             'INTERNSHIP', 'TECH', 'SOFTWARE_ENG', 4500000, 10, 'TP. Hồ Chí Minh', false, 3),

            ('Cộng tác viên Thiết kế UI/UX (bán thời gian)',
             E'Thiết kế giao diện cho các màn hình mới của sản phẩm web và mobile, phối hợp cùng lập trình viên để bàn giao design system.\n\nYêu cầu: sử dụng thành thạo Figma, có portfolio ít nhất 2 sản phẩm. Ưu tiên bạn từng làm việc với component và auto-layout.\n\nThời gian: 20 giờ/tuần, linh hoạt, có thể làm từ xa 2 buổi.',
             'PART_TIME', 'DESIGN', 'UI_UX', 5000000, 15, 'TP. Hồ Chí Minh', true, 2),

            ('Nhân viên Marketing nội dung (Content Marketing)',
             E'Lên kế hoạch và viết nội dung cho website, fanpage và bản tin email. Theo dõi chỉ số tương tác và đề xuất điều chỉnh hằng tháng.\n\nYêu cầu: viết tiếng Việt tốt, có khả năng tự tìm hiểu chủ đề mới. Biết dùng công cụ phân tích cơ bản là một lợi thế.\n\nHình thức: toàn thời gian tại văn phòng, thử việc 2 tháng.',
             'PART_TIME', 'BUSINESS', 'MARKETING', 7000000, 5, 'TP. Hồ Chí Minh', false, 2),

            ('Freelance Quay dựng video ngắn cho kênh tuyển dụng',
             E'Sản xuất 8–12 video ngắn mỗi tháng giới thiệu môi trường làm việc và câu chuyện nhân sự, dùng cho TikTok và Reels.\n\nYêu cầu: tự quay và dựng được, có thiết bị cá nhân. Gửi kèm 2–3 sản phẩm đã làm khi ứng tuyển.\n\nThanh toán: theo gói sản phẩm, nghiệm thu từng đợt.',
             'FREELANCE', 'MEDIA', 'VIDEO_PRODUCTION', 6000000, 0, 'Làm từ xa', true, 1)
        ) as t(title, description, job_type, category, specialty,
               compensation, min_req_rs, location, is_remote, capacity)
        /* Hai công ty KT chỉ lấy 3 tin đầu để dữ liệu ba tổ chức không giống hệt
           nhau — nhìn danh sách admin mới ra dáng thật. */
        limit case when v_email like 'kt-%' then 3 else 4 end
    loop
        -- Chống trùng: cùng tổ chức + cùng tiêu đề thì bỏ qua.
        if exists (select 1 from jobs
                   where company_id = v_company and title = r.title and deleted_at is null) then
            continue;
        end if;

        insert into jobs (company_id, title, description, job_type, category, specialty,
                          compensation, compensation_currency, min_req_rs, location,
                          is_remote, capacity, deadline_at, status, created_by)
        values (v_company, r.title, r.description, r.job_type, r.category, r.specialty,
                r.compensation, 'VND', r.min_req_rs, r.location,
                r.is_remote, r.capacity,
                -- Hạn nộp rải ngẫu nhiên trong 20–70 ngày tới, mỗi tin một hạn.
                now() + ((20 + floor(random() * 50)) || ' days')::interval,
                v_status, v_user);
        v_made := v_made + 1;
    end loop;

    raise notice '% → tổ chức % : thêm % tin (status=%).', v_email, v_company, v_made, v_status;
    v_company := null; v_user := null;
END LOOP;

-- ─────────────────────────────────────────────────────────────────────────
--  PHẦN B — QUEST
-- ─────────────────────────────────────────────────────────────────────────
    v_email := 'nextplease.noreply@gmail.com';

    select an.user_id, c.id into v_user, v_company
    from app_users u
    join authority_nodes an on an.user_id = u.id
                           and an.status = 'ACTIVE'
                           and an.deleted_at is null
    join companies c on c.id = an.company_id and c.deleted_at is null
    where lower(btrim(u.email)) = lower(btrim(v_email))
    order by case an.node_role when 'OWNER' then 0 when 'MANAGER' then 1 else 2 end,
             an.created_at
    limit 1;

    if v_company is null then
        raise notice 'BỎ QUA quest — % không đại diện tổ chức nào.', v_email;
    else
        v_made := 0;

        for r in
            select * from (values
                ('Hỗ trợ tổ chức Ngày hội Việc làm sinh viên 2026',
                 E'Cần 15 bạn hỗ trợ khâu đón khách, hướng dẫn gian hàng và thu thập phản hồi trong 2 ngày diễn ra sự kiện.\n\nCông việc cụ thể: trực quầy check-in, hướng dẫn sinh viên tới đúng khu vực doanh nghiệp, phát và thu phiếu khảo sát.\n\nQuyền lợi: giấy chứng nhận tham gia, suất ăn và nước uống, ghi nhận đóng góp vào hồ sơ năng lực.',
                 'SMALL_EVENT', 0, 300, 150, 15,
                 now() + interval '14 days', now() + interval '16 days', 'TP. Hồ Chí Minh'),

                ('Chiến dịch truyền thông "Hồ sơ dựa trên bằng chứng"',
                 E'Đồng hành cùng đội truyền thông trong 4 tuần: sản xuất nội dung chia sẻ trải nghiệm thật của sinh viên khi xây dựng portfolio.\n\nCông việc: viết bài, thiết kế ảnh đăng, phỏng vấn ngắn 3–5 bạn sinh viên.\n\nPhù hợp với bạn học truyền thông, marketing hoặc thiết kế muốn có sản phẩm thật đưa vào portfolio.',
                 'SCHOOL_CAMPAIGN', 5, 500, 250, 8,
                 now() + interval '7 days', now() + interval '35 days', 'Làm từ xa'),

                ('Dự án phân tích dữ liệu hành vi ứng viên',
                 E'Làm việc với tập dữ liệu ẩn danh về hành vi tìm việc của sinh viên: làm sạch dữ liệu, dựng biểu đồ và viết báo cáo phát hiện chính.\n\nYêu cầu: biết Python hoặc SQL ở mức cơ bản, đã từng dùng pandas hoặc công cụ tương đương.\n\nKết quả bàn giao: một notebook và một báo cáo 5–8 trang. Có mentor hướng dẫn hằng tuần.',
                 'COMPANY_PROJECT', 20, 800, 400, 4,
                 now() + interval '10 days', now() + interval '55 days', 'Làm từ xa'),

                ('Thực tập ngắn hạn: Kiểm thử tính năng portfolio',
                 E'Trong 3 tuần, kiểm thử toàn bộ luồng tạo và chia sẻ portfolio trên cả máy tính lẫn điện thoại, ghi nhận lỗi theo mẫu và đề xuất cải thiện trải nghiệm.\n\nKhông yêu cầu biết lập trình. Cần tỉ mỉ và mô tả được lỗi rõ ràng, kèm ảnh chụp màn hình.\n\nBạn sẽ học được cách viết báo cáo lỗi chuẩn và làm việc trên công cụ quản lý công việc thật.',
                 'SHORT_INTERNSHIP', 0, 400, 200, 6,
                 now() + interval '5 days', now() + interval '26 days', 'TP. Hồ Chí Minh')
            ) as t(title, description, category, min_req_rs, exp_reward, np_reward,
                   capacity, starts_at, ends_at, location)
        loop
            if exists (select 1 from quests
                       where company_id = v_company and title = r.title and deleted_at is null) then
                continue;
            end if;

            insert into quests (company_id, title, description, category, min_req_rs,
                                exp_reward, np_reward, capacity, starts_at, ends_at,
                                location, status, created_by)
            values (v_company, r.title, r.description, r.category, r.min_req_rs,
                    r.exp_reward, r.np_reward, r.capacity, r.starts_at, r.ends_at,
                    r.location, v_status, v_user);
            v_made := v_made + 1;
        end loop;

        raise notice '% → tổ chức % : thêm % quest (status=%).', v_email, v_company, v_made, v_status;
    end if;
END
$$;


-- ============================================================================
--  KIỂM TRA — chạy riêng khối này sau khi Run ở trên
-- ============================================================================
select c.name as to_chuc, u.email as chu_so_huu,
       count(*) filter (where j.id is not null) as so_tin,
       j.status
from companies c
join app_users u on u.id = c.owner_user_id
left join jobs j on j.company_id = c.id and j.deleted_at is null
where lower(u.email) in ('kt-mst@test.invalid', 'kt-sdt@test.invalid', 'phattai280405@gmail.com')
group by c.name, u.email, j.status
order by u.email;

select c.name as to_chuc, q.status, count(*) as so_quest
from quests q
join companies c on c.id = q.company_id
join app_users u on u.id = c.owner_user_id
where lower(u.email) = 'nextplease.noreply@gmail.com' and q.deleted_at is null
group by c.name, q.status;
