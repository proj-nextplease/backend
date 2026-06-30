-- ============================================================================
-- SEED — Bài đăng cho ĐÚNG công ty THẬT của 2 tài khoản (B2B đã đăng ký).
--   - phattai280405@gmail.com      -> Doanh nghiệp  -> đăng JOBS
--   - nextplease.noreply@gmail.com -> CLB           -> đăng QUESTS
--
-- KHÁC các script seed trước: script này KHÔNG tạo công ty seed riêng. Nó tìm
-- đúng công ty mà tài khoản đang nắm quyền (bảng authority_nodes, OWNER ưu tiên)
-- — chính là công ty hiện ra trong workspace B2B khi đăng nhập. Nhờ vậy bài đăng
-- THỰC SỰ thuộc về 2 tài khoản đó.
--
-- - Không đụng tới các công ty seed/ghost cũ (giữ nguyên cho danh bạ đa dạng).
-- - Không đổi verification_status (giả định 2 công ty đã APPROVED).
-- - Tin/Quest đều PENDING -> tự duyệt trong Admin. Đủ trường + ảnh + 3 câu hỏi + skills.
-- - Hạn = 2026-07-04 23:59:59 +07. Idempotent theo tiêu đề (chạy lại an toàn).
-- Dán vào SQL editor (Supabase/psql) rồi Run.
-- ============================================================================

BEGIN;

CREATE OR REPLACE FUNCTION pg_temp.seed_job(
  p_company uuid, p_creator uuid, p_title text, p_desc text,
  p_type text, p_category text, p_specialty text, p_comp numeric,
  p_location text, p_remote boolean, p_minrs int, p_cap int,
  p_banner_seed text, p_skills text[]
) RETURNS void AS $func$
DECLARE v_job uuid;
BEGIN
  INSERT INTO jobs (company_id, title, description, job_type, category, specialty,
                    compensation, compensation_currency, min_req_rs, location, is_remote,
                    capacity, deadline_at, status, created_by, banner_url, banner_pos,
                    created_at, updated_at)
  VALUES (p_company, p_title, p_desc, p_type, p_category, p_specialty,
          p_comp, 'VND', p_minrs, p_location, p_remote,
          p_cap, TIMESTAMPTZ '2026-07-04 23:59:59+07', 'PENDING', p_creator,
          'https://picsum.photos/seed/' || p_banner_seed || '/1000/360', 'center', now(), now())
  RETURNING id INTO v_job;
  INSERT INTO job_form_fields (job_id, label, field_type, options, required, sort_order) VALUES
    (v_job, 'Vì sao bạn phù hợp với vị trí này?', 'TEXTAREA', NULL, true, 1),
    (v_job, 'Link CV / Portfolio của bạn', 'TEXT', NULL, false, 2),
    (v_job, 'Bạn có thể bắt đầu khi nào?', 'SELECT', E'Ngay lập tức\nTrong 2 tuần\nTrong 1 tháng', true, 3);
  INSERT INTO job_skills (job_id, skill_id, required_level)
    SELECT v_job, s.id, 'INTERMEDIATE' FROM skills s WHERE s.name = ANY(p_skills);
END;
$func$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION pg_temp.seed_quest(
  p_company uuid, p_creator uuid, p_title text, p_desc text,
  p_category text, p_minrs int, p_exp int, p_np int, p_cap int, p_banner_seed text
) RETURNS void AS $func$
DECLARE v_q uuid;
BEGIN
  INSERT INTO quests (company_id, title, description, category, min_req_rs,
                      exp_reward, np_reward, capacity, starts_at, ends_at, status,
                      created_by, banner_url, banner_pos, created_at, updated_at)
  VALUES (p_company, p_title, p_desc, p_category, p_minrs,
          p_exp, p_np, p_cap, now(), TIMESTAMPTZ '2026-07-04 23:59:59+07', 'PENDING',
          p_creator, 'https://picsum.photos/seed/' || p_banner_seed || '/1000/360', 'center', now(), now())
  RETURNING id INTO v_q;
  INSERT INTO quest_form_fields (quest_id, label, field_type, options, required, sort_order) VALUES
    (v_q, 'Bạn mong muốn đóng góp gì cho hoạt động này?', 'TEXTAREA', NULL, true, 1),
    (v_q, 'Bạn đã có kinh nghiệm liên quan chưa? Mô tả ngắn.', 'TEXTAREA', NULL, false, 2),
    (v_q, 'Bạn tham gia được theo hình thức nào?', 'SELECT', E'Trực tiếp\nOnline\nCả hai', true, 3);
END;
$func$ LANGUAGE plpgsql;

-- Resolve công ty mà 1 user đang nắm quyền (giống backend resolveCompanyForUser).
CREATE OR REPLACE FUNCTION pg_temp.company_of(p_user uuid) RETURNS uuid AS $func$
  SELECT c.id
  FROM companies c
  JOIN authority_nodes an ON an.company_id = c.id
  WHERE an.user_id = p_user AND an.status = 'ACTIVE' AND an.deleted_at IS NULL
  ORDER BY CASE an.node_role WHEN 'OWNER' THEN 0 WHEN 'MANAGER' THEN 1 ELSE 2 END,
           an.created_at ASC
  LIMIT 1;
$func$ LANGUAGE sql;

DO $$
DECLARE
  biz_user  uuid;
  club_user uuid;
  c_biz  uuid;
  c_club uuid;
  job_titles text[] := ARRAY[
    'Thực tập sinh Lập trình Frontend (React)',
    'Thực tập sinh Lập trình Backend (Java)',
    'Thực tập sinh Phân tích dữ liệu (Data Analyst)',
    'Freelance UI/UX Designer',
    'Cộng tác viên Content Marketing',
    'Nhân viên Kinh doanh (Sales, Part-time)',
    'Thực tập sinh Kiểm thử phần mềm (QA)',
    'Nhân sự sự kiện (Event Staff)'
  ];
  quest_titles text[] := ARRAY[
    'Tình nguyện viên Sự kiện Chào tân sinh viên',
    'Ban Truyền thông Chiến dịch Mùa hè xanh',
    'Cộng tác viên Thiết kế ấn phẩm sự kiện',
    'Điều phối Hội thảo hướng nghiệp',
    'MC / Dẫn chương trình sự kiện',
    'Ban Tổ chức Ngày hội Việc làm',
    'Photographer sự kiện sinh viên',
    'Trưởng ban Hậu cần chiến dịch'
  ];
BEGIN
  SELECT id INTO biz_user  FROM app_users WHERE email = 'phattai280405@gmail.com';
  SELECT id INTO club_user FROM app_users WHERE email = 'nextplease.noreply@gmail.com';
  IF biz_user  IS NULL THEN RAISE EXCEPTION 'Không tìm thấy tài khoản phattai280405@gmail.com'; END IF;
  IF club_user IS NULL THEN RAISE EXCEPTION 'Không tìm thấy tài khoản nextplease.noreply@gmail.com'; END IF;

  c_biz  := pg_temp.company_of(biz_user);
  c_club := pg_temp.company_of(club_user);
  IF c_biz IS NULL THEN
    RAISE EXCEPTION 'Tài khoản phattai280405 chưa nắm quyền (OWNER/MANAGER) ở công ty B2B nào — hãy đăng ký & được duyệt B2B trước.';
  END IF;
  IF c_club IS NULL THEN
    RAISE EXCEPTION 'Tài khoản nextplease.noreply chưa nắm quyền ở tổ chức/CLB nào — hãy đăng ký & được duyệt B2B trước.';
  END IF;

  RAISE NOTICE 'Công ty (DN) = %, Công ty (CLB) = %', c_biz, c_club;

  -- Dọn batch cũ (chỉ đúng tiêu đề trong batch này, trong đúng công ty thật)
  DELETE FROM applications       WHERE job_id   IN (SELECT id FROM jobs   WHERE company_id = c_biz  AND title = ANY(job_titles));
  DELETE FROM quest_applications WHERE quest_id IN (SELECT id FROM quests WHERE company_id = c_club AND title = ANY(quest_titles));
  DELETE FROM jobs   WHERE company_id = c_biz  AND title = ANY(job_titles);
  DELETE FROM quests WHERE company_id = c_club AND title = ANY(quest_titles);

  -- ===== JOBS — Doanh nghiệp (phattai280405) =====
  PERFORM pg_temp.seed_job(c_biz, biz_user,
    'Thực tập sinh Lập trình Frontend (React)',
    'Tham gia phát triển giao diện sản phẩm cùng đội ngũ kỹ thuật bằng React. Được mentor 1-1, làm quen quy trình review code và triển khai thực tế. Nhận chứng nhận thực tập.',
    'INTERNSHIP', 'TECH', 'SOFTWARE_ENG', 4000000, 'TP. Hồ Chí Minh', false, 0, 3, 'real-fe', ARRAY['React','JavaScript','Git']);
  PERFORM pg_temp.seed_job(c_biz, biz_user,
    'Thực tập sinh Lập trình Backend (Java)',
    'Phát triển API và dịch vụ backend bằng Java/Spring Boot. Phù hợp sinh viên năm cuối định hướng lập trình server, được hướng dẫn từ cơ bản đến triển khai.',
    'INTERNSHIP', 'TECH', 'SOFTWARE_ENG', 4500000, 'TP. Hồ Chí Minh', false, 0, 2, 'real-be', ARRAY['Java','SQL','Git']);
  PERFORM pg_temp.seed_job(c_biz, biz_user,
    'Thực tập sinh Phân tích dữ liệu (Data Analyst)',
    'Hỗ trợ phân tích dữ liệu người dùng, xây dựng báo cáo và dashboard. Yêu cầu tư duy số liệu tốt, biết SQL và Excel, ưu tiên biết Python.',
    'INTERNSHIP', 'TECH', 'DATA_SCIENCE', 4500000, 'TP. Hồ Chí Minh', false, 0, 2, 'real-data', ARRAY['Python','SQL','Excel']);
  PERFORM pg_temp.seed_job(c_biz, biz_user,
    'Freelance UI/UX Designer',
    'Thiết kế giao diện cho các tính năng mới của sản phẩm. Làm remote, thanh toán theo dự án. Yêu cầu thành thạo Figma và có portfolio thực tế.',
    'FREELANCE', 'DESIGN', 'UI_UX', 8000000, 'Remote', true, 20, 2, 'real-uiux', ARRAY['Figma','UI/UX Design']);
  PERFORM pg_temp.seed_job(c_biz, biz_user,
    'Cộng tác viên Content Marketing',
    'Viết bài chuẩn SEO, nội dung mạng xã hội cho sản phẩm. Làm remote, nhuận bút theo bài. Tiếng Việt tốt, biết tiếng Anh là lợi thế.',
    'FREELANCE', 'MEDIA', 'CONTENT_CREATIVE', 3500000, 'Remote', true, 0, 5, 'real-content', ARRAY['Content Writing','Social Media','English']);
  PERFORM pg_temp.seed_job(c_biz, biz_user,
    'Nhân viên Kinh doanh (Sales, Part-time)',
    'Tư vấn và bán giải pháp cho khách hàng, mở rộng tệp khách hàng mới. Lương cứng + hoa hồng, thời gian linh hoạt.',
    'PART_TIME', 'BUSINESS', 'SALES', 5000000, 'TP. Hồ Chí Minh', false, 10, 3, 'real-sales', ARRAY['Sales','Marketing']);
  PERFORM pg_temp.seed_job(c_biz, biz_user,
    'Thực tập sinh Kiểm thử phần mềm (QA)',
    'Tham gia kiểm thử thủ công và viết test case cho sản phẩm. Được đào tạo quy trình QA bài bản, không yêu cầu kinh nghiệm.',
    'INTERNSHIP', 'TECH', 'INFO_SYSTEMS', 3500000, 'Hà Nội', false, 0, 4, 'real-qa', ARRAY['SQL']);
  PERFORM pg_temp.seed_job(c_biz, biz_user,
    'Nhân sự sự kiện (Event Staff)',
    'Hỗ trợ vận hành sự kiện: đón khách, điều phối khu vực, hỗ trợ hậu cần. Trả lương theo ca, phù hợp sinh viên năng động.',
    'EVENT_STAFF', 'MEDIA', 'EVENT_PLANNING', 2500000, 'TP. Hồ Chí Minh', false, 0, 10, 'real-event', ARRAY['Event Planning']);

  -- ===== QUESTS — CLB (nextplease.noreply) =====
  PERFORM pg_temp.seed_quest(c_club, club_user,
    'Tình nguyện viên Sự kiện Chào tân sinh viên',
    'Tham gia tổ chức chương trình chào đón tân sinh viên: dẫn chương trình, hỗ trợ khu vực, hậu cần. Rèn kỹ năng tổ chức và mở rộng quan hệ.',
    'SMALL_EVENT', 0, 100, 50, 15, 'real-welcome');
  PERFORM pg_temp.seed_quest(c_club, club_user,
    'Ban Truyền thông Chiến dịch Mùa hè xanh',
    'Phụ trách truyền thông cho chiến dịch tình nguyện cấp trường: sản xuất nội dung, quản lý fanpage, chụp ảnh sự kiện.',
    'SCHOOL_CAMPAIGN', 10, 300, 100, 8, 'real-mhx');
  PERFORM pg_temp.seed_quest(c_club, club_user,
    'Cộng tác viên Thiết kế ấn phẩm sự kiện',
    'Thiết kế bộ ấn phẩm truyền thông cho chuỗi sự kiện của CLB. Phù hợp bạn yêu thích thiết kế, biết Figma hoặc Photoshop.',
    'SMALL_EVENT', 0, 100, 50, 4, 'real-design');
  PERFORM pg_temp.seed_quest(c_club, club_user,
    'Điều phối Hội thảo hướng nghiệp',
    'Tham gia ban tổ chức hội thảo hướng nghiệp: liên hệ diễn giả, điều phối chương trình, quản lý đăng ký.',
    'SCHOOL_CAMPAIGN', 20, 300, 150, 6, 'real-career');
  PERFORM pg_temp.seed_quest(c_club, club_user,
    'MC / Dẫn chương trình sự kiện',
    'Dẫn dắt các chương trình của CLB. Cơ hội rèn luyện kỹ năng sân khấu, làm việc cùng ban tổ chức.',
    'SMALL_EVENT', 0, 100, 60, 2, 'real-mc');
  PERFORM pg_temp.seed_quest(c_club, club_user,
    'Ban Tổ chức Ngày hội Việc làm',
    'Tham gia ban tổ chức Ngày hội Việc làm cấp trường: liên hệ doanh nghiệp, sắp xếp gian hàng, điều phối sinh viên.',
    'SCHOOL_CAMPAIGN', 10, 300, 150, 6, 'real-jobfair');
  PERFORM pg_temp.seed_quest(c_club, club_user,
    'Photographer sự kiện sinh viên',
    'Chụp ảnh tư liệu và truyền thông cho các sự kiện sinh viên. Yêu thích nhiếp ảnh, có máy ảnh là lợi thế.',
    'SMALL_EVENT', 0, 100, 50, 5, 'real-photo');
  PERFORM pg_temp.seed_quest(c_club, club_user,
    'Trưởng ban Hậu cần chiến dịch',
    'Điều phối toàn bộ công tác hậu cần cho chiến dịch lớn của CLB: vật tư, địa điểm, nhân sự. Vai trò leader, cộng nhiều RS khi hoàn thành.',
    'SCHOOL_CAMPAIGN', 20, 300, 150, 3, 'real-logistics');

  RAISE NOTICE 'Đã thêm 8 tin (DN) + 8 quest (CLB) vào ĐÚNG công ty thật của 2 tài khoản — đều PENDING, vào Admin để duyệt.';
END $$;

COMMIT;
