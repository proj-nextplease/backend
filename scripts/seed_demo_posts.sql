-- ============================================================================
-- SEED DEMO: nhiều tin tuyển dụng (DN) + Quest (CLB) để làm giàu data giao diện.
-- - Tin/Quest ở trạng thái PENDING -> bạn tự duyệt trong Admin (PENDING -> OPEN).
-- - Company seed để verification_status = APPROVED sẵn (chỉ cần duyệt tin).
-- - Đầy đủ: thông tin job/quest + câu hỏi (form fields) + ảnh banner + skills.
-- - Idempotent: chạy lại sẽ tự xóa data seed cũ (tag website_url = 'seed://demo'
--   và email ghost '%@seed.nextplease.demo'), KHÔNG đụng tới data thật.
-- - Hạn (deadline/ends_at) = 2026-07-04 23:59:59 +07.
-- Cách dùng: dán toàn bộ file này vào SQL editor (Supabase / psql) rồi Run.
-- ============================================================================

BEGIN;

-- ----- Helper: tạo 1 job + 3 câu hỏi + skills (session-temp, tự hủy) -----------
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
          'https://picsum.photos/seed/' || p_banner_seed || '/1000/360', 'center',
          now(), now())
  RETURNING id INTO v_job;

  INSERT INTO job_form_fields (job_id, label, field_type, options, required, sort_order) VALUES
    (v_job, 'Vì sao bạn phù hợp với vị trí này?', 'TEXTAREA', NULL, true, 1),
    (v_job, 'Link CV / Portfolio của bạn', 'TEXT', NULL, false, 2),
    (v_job, 'Bạn có thể bắt đầu khi nào?', 'SELECT', E'Ngay lập tức\nTrong 2 tuần\nTrong 1 tháng', true, 3);

  INSERT INTO job_skills (job_id, skill_id, required_level)
    SELECT v_job, s.id, 'INTERMEDIATE' FROM skills s WHERE s.name = ANY(p_skills);
END;
$func$ LANGUAGE plpgsql;

-- ----- Helper: tạo 1 quest + 3 câu hỏi -----------------------------------------
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
          p_creator, 'https://picsum.photos/seed/' || p_banner_seed || '/1000/360', 'center',
          now(), now())
  RETURNING id INTO v_q;

  INSERT INTO quest_form_fields (quest_id, label, field_type, options, required, sort_order) VALUES
    (v_q, 'Bạn mong muốn đóng góp gì cho hoạt động này?', 'TEXTAREA', NULL, true, 1),
    (v_q, 'Bạn đã có kinh nghiệm liên quan chưa? Mô tả ngắn.', 'TEXTAREA', NULL, false, 2),
    (v_q, 'Bạn tham gia được theo hình thức nào?', 'SELECT', E'Trực tiếp\nOnline\nCả hai', true, 3);
END;
$func$ LANGUAGE plpgsql;

-- ----- Seed chính --------------------------------------------------------------
DO $$
DECLARE
  biz_user  uuid;
  club_user uuid;
  -- companies
  c_biz1 uuid; c_biz2 uuid; c_biz3 uuid;
  c_club1 uuid; c_club2 uuid; c_club3 uuid;
  -- ghost owners (#2 - tài khoản DN/CLB thêm, chỉ để hiển thị)
  g_biz2 uuid; g_biz3 uuid; g_club2 uuid; g_club3 uuid;
BEGIN
  SELECT id INTO biz_user  FROM app_users WHERE email = 'phattai280405@gmail.com';
  SELECT id INTO club_user FROM app_users WHERE email = 'nextplease.noreply@gmail.com';
  IF biz_user  IS NULL THEN RAISE EXCEPTION 'Không tìm thấy tài khoản phattai280405@gmail.com'; END IF;
  IF club_user IS NULL THEN RAISE EXCEPTION 'Không tìm thấy tài khoản nextplease.noreply@gmail.com'; END IF;

  -- 0) Dọn data seed cũ (idempotent) - chỉ xóa theo tag, KHÔNG đụng data thật
  DELETE FROM applications        WHERE job_id   IN (SELECT id FROM jobs   WHERE company_id IN (SELECT id FROM companies WHERE website_url = 'seed://demo'));
  DELETE FROM quest_applications  WHERE quest_id IN (SELECT id FROM quests WHERE company_id IN (SELECT id FROM companies WHERE website_url = 'seed://demo'));
  DELETE FROM jobs   WHERE company_id IN (SELECT id FROM companies WHERE website_url = 'seed://demo');
  DELETE FROM quests WHERE company_id IN (SELECT id FROM companies WHERE website_url = 'seed://demo');
  DELETE FROM companies WHERE website_url = 'seed://demo';
  DELETE FROM app_users WHERE email LIKE '%@seed.nextplease.demo';

  -- 1) Ghost owners (#2)
  INSERT INTO app_users (supabase_user_id, email, display_name, status)
    VALUES (gen_random_uuid(), 'techviet@seed.nextplease.demo', 'TechViet Solutions', 'ACTIVE') RETURNING id INTO g_biz2;
  INSERT INTO app_users (supabase_user_id, email, display_name, status)
    VALUES (gen_random_uuid(), 'saigondigital@seed.nextplease.demo', 'Saigon Digital Agency', 'ACTIVE') RETURNING id INTO g_biz3;
  INSERT INTO app_users (supabase_user_id, email, display_name, status)
    VALUES (gen_random_uuid(), 'doantruong-fpt@seed.nextplease.demo', 'Đoàn trường ĐH FPT', 'ACTIVE') RETURNING id INTO g_club2;
  INSERT INTO app_users (supabase_user_id, email, display_name, status)
    VALUES (gen_random_uuid(), 'clb-khoinghiep-ueh@seed.nextplease.demo', 'CLB Khởi nghiệp UEH', 'ACTIVE') RETURNING id INTO g_club3;

  -- 2) Companies (verification_status = APPROVED, tag website_url = 'seed://demo')
  -- Doanh nghiệp thuộc tài khoản thật (phattai280405)
  INSERT INTO companies (owner_user_id, name, company_type, description, website_url, logo_url, verification_status, monthly_job_quota)
    VALUES (biz_user, 'NextPlease Tech', 'SME',
            'Công ty công nghệ phát triển nền tảng tuyển dụng dựa trên proof of work cho sinh viên.',
            'seed://demo', 'https://picsum.photos/seed/np-tech-logo/200', 'APPROVED', 50)
    RETURNING id INTO c_biz1;
  -- Doanh nghiệp ghost
  INSERT INTO companies (owner_user_id, name, company_type, description, website_url, logo_url, verification_status, monthly_job_quota)
    VALUES (g_biz2, 'TechViet Solutions', 'STARTUP',
            'Startup phần mềm cung cấp giải pháp chuyển đổi số cho doanh nghiệp vừa và nhỏ.',
            'seed://demo', 'https://picsum.photos/seed/techviet-logo/200', 'APPROVED', 50)
    RETURNING id INTO c_biz2;
  INSERT INTO companies (owner_user_id, name, company_type, description, website_url, logo_url, verification_status, monthly_job_quota)
    VALUES (g_biz3, 'Saigon Digital Agency', 'AGENCY',
            'Agency truyền thông sáng tạo, chuyên về thương hiệu, nội dung và sự kiện.',
            'seed://demo', 'https://picsum.photos/seed/sgdigital-logo/200', 'APPROVED', 50)
    RETURNING id INTO c_biz3;

  -- CLB thuộc tài khoản thật (nextplease.noreply)
  INSERT INTO companies (owner_user_id, name, company_type, description, website_url, logo_url, verification_status, monthly_job_quota)
    VALUES (club_user, 'CLB Truyền thông NextPlease', 'CLUB',
            'Câu lạc bộ sinh viên hoạt động trong lĩnh vực truyền thông, sự kiện và sáng tạo nội dung.',
            'seed://demo', 'https://picsum.photos/seed/np-club-logo/200', 'APPROVED', 50)
    RETURNING id INTO c_club1;
  -- CLB ghost
  INSERT INTO companies (owner_user_id, name, company_type, description, website_url, logo_url, verification_status, monthly_job_quota)
    VALUES (g_club2, 'Đoàn trường Đại học FPT', 'SCHOOL',
            'Tổ chức Đoàn - Hội sinh viên trường, điều phối các chiến dịch và hoạt động cấp trường.',
            'seed://demo', 'https://picsum.photos/seed/fpt-school-logo/200', 'APPROVED', 50)
    RETURNING id INTO c_club2;
  INSERT INTO companies (owner_user_id, name, company_type, description, website_url, logo_url, verification_status, monthly_job_quota)
    VALUES (g_club3, 'CLB Khởi nghiệp UEH', 'CLUB',
            'Câu lạc bộ khởi nghiệp và đổi mới sáng tạo của sinh viên trường Kinh tế.',
            'seed://demo', 'https://picsum.photos/seed/ueh-club-logo/200', 'APPROVED', 50)
    RETURNING id INTO c_club3;

  -- 3) JOBS (Doanh nghiệp) ------------------------------------------------------
  -- NextPlease Tech
  PERFORM pg_temp.seed_job(c_biz1, biz_user,
    'Thực tập sinh Lập trình Frontend (React)',
    'Tham gia phát triển giao diện sản phẩm cùng đội ngũ kỹ thuật. Bạn sẽ làm việc với React, làm quen quy trình review code và triển khai thực tế. Được mentor 1-1 và nhận chứng nhận thực tập.',
    'INTERNSHIP', 'TECH', 'SOFTWARE_ENG', 4000000, 'TP. Hồ Chí Minh', false, 0, 3, 'job-fe-intern', ARRAY['React','JavaScript','Git']);
  PERFORM pg_temp.seed_job(c_biz1, biz_user,
    'Thực tập sinh Phân tích dữ liệu (Data Analyst)',
    'Hỗ trợ phân tích dữ liệu người dùng, xây dựng báo cáo và dashboard. Yêu cầu tư duy số liệu tốt, biết SQL và Excel cơ bản, ưu tiên biết Python.',
    'INTERNSHIP', 'TECH', 'DATA_SCIENCE', 4500000, 'TP. Hồ Chí Minh', false, 0, 2, 'job-data-intern', ARRAY['Python','SQL','Excel']);
  PERFORM pg_temp.seed_job(c_biz1, biz_user,
    'Freelance UI/UX Designer',
    'Thiết kế giao diện cho các tính năng mới của sản phẩm. Làm việc remote, thanh toán theo dự án. Yêu cầu thành thạo Figma và có portfolio thực tế.',
    'FREELANCE', 'DESIGN', 'UI_UX', 8000000, 'Remote', true, 20, 2, 'job-uiux-free', ARRAY['Figma','UI/UX Design']);

  -- TechViet Solutions
  PERFORM pg_temp.seed_job(c_biz2, g_biz2,
    'Lập trình viên Backend Java (Part-time)',
    'Phát triển API và dịch vụ backend bằng Java/Spring Boot. Phù hợp sinh viên năm cuối muốn làm part-time linh hoạt, định hướng lên full-time.',
    'PART_TIME', 'TECH', 'SOFTWARE_ENG', 7000000, 'TP. Hồ Chí Minh', false, 30, 2, 'job-be-java', ARRAY['Java','SQL','Git']);
  PERFORM pg_temp.seed_job(c_biz2, g_biz2,
    'Thực tập sinh Kiểm thử phần mềm (QA)',
    'Tham gia kiểm thử thủ công và viết test case cho sản phẩm. Được đào tạo quy trình QA bài bản, không yêu cầu kinh nghiệm.',
    'INTERNSHIP', 'TECH', 'INFO_SYSTEMS', 3500000, 'Hà Nội', false, 0, 4, 'job-qa-intern', ARRAY['SQL']);
  PERFORM pg_temp.seed_job(c_biz2, g_biz2,
    'Cộng tác viên Sales B2B',
    'Tìm kiếm và chăm sóc khách hàng doanh nghiệp cho giải pháp phần mềm. Hoa hồng hấp dẫn, thời gian linh hoạt.',
    'FREELANCE', 'BUSINESS', 'SALES', 5000000, 'Remote', true, 0, 5, 'job-sales-b2b', ARRAY['Sales','Marketing']);

  -- Saigon Digital Agency
  PERFORM pg_temp.seed_job(c_biz3, g_biz3,
    'Cộng tác viên Content Writer',
    'Viết bài chuẩn SEO, nội dung mạng xã hội cho khách hàng của agency. Làm remote, nhuận bút theo bài. Yêu cầu tiếng Việt tốt, biết tiếng Anh là lợi thế.',
    'FREELANCE', 'MEDIA', 'CONTENT_CREATIVE', 3000000, 'Remote', true, 0, 5, 'job-content', ARRAY['Content Writing','English','Social Media']);
  PERFORM pg_temp.seed_job(c_biz3, g_biz3,
    'Nhân sự sự kiện (Event Staff)',
    'Hỗ trợ vận hành các sự kiện của khách hàng: đón khách, điều phối khu vực, hỗ trợ hậu cần. Trả lương theo ca, phù hợp sinh viên năng động.',
    'EVENT_STAFF', 'MEDIA', 'EVENT_PLANNING', 2500000, 'TP. Hồ Chí Minh', false, 0, 10, 'job-event-staff', ARRAY['Event Planning']);
  PERFORM pg_temp.seed_job(c_biz3, g_biz3,
    'Thực tập sinh Thiết kế đồ họa',
    'Thiết kế ấn phẩm truyền thông: banner, post mạng xã hội, ấn phẩm sự kiện. Yêu cầu biết Photoshop/Illustrator, có gu thẩm mỹ.',
    'INTERNSHIP', 'DESIGN', 'GRAPHIC_DESIGN', 3500000, 'TP. Hồ Chí Minh', false, 0, 3, 'job-graphic', ARRAY['Photoshop','Illustrator']);

  -- 4) QUESTS (CLB) -------------------------------------------------------------
  -- CLB Truyền thông NextPlease
  PERFORM pg_temp.seed_quest(c_club1, club_user,
    'Tình nguyện viên Sự kiện Chào tân sinh viên',
    'Tham gia tổ chức chương trình chào đón tân sinh viên: dẫn chương trình, hỗ trợ khu vực, hậu cần. Cơ hội rèn kỹ năng tổ chức và mở rộng mối quan hệ.',
    'SMALL_EVENT', 0, 100, 50, 15, 'quest-welcome');
  PERFORM pg_temp.seed_quest(c_club1, club_user,
    'Ban Truyền thông Chiến dịch Mùa hè xanh',
    'Phụ trách truyền thông cho chiến dịch tình nguyện cấp trường: sản xuất nội dung, quản lý fanpage, chụp ảnh sự kiện. Được cộng EXP và RS khi hoàn thành.',
    'SCHOOL_CAMPAIGN', 10, 300, 100, 8, 'quest-mhx');
  PERFORM pg_temp.seed_quest(c_club1, club_user,
    'Cộng tác viên thiết kế poster sự kiện',
    'Thiết kế bộ ấn phẩm truyền thông cho chuỗi sự kiện của CLB. Phù hợp bạn yêu thích thiết kế, biết Figma hoặc Photoshop.',
    'SMALL_EVENT', 0, 100, 50, 4, 'quest-poster');

  -- Đoàn trường ĐH FPT
  PERFORM pg_temp.seed_quest(c_club2, g_club2,
    'Điều phối Hội thảo hướng nghiệp cấp trường',
    'Tham gia ban tổ chức hội thảo hướng nghiệp: liên hệ diễn giả, điều phối chương trình, quản lý đăng ký. Hoạt động cấp trường có cộng điểm rèn luyện.',
    'SCHOOL_CAMPAIGN', 20, 300, 150, 6, 'quest-career');
  PERFORM pg_temp.seed_quest(c_club2, g_club2,
    'Hỗ trợ tổ chức Workshop kỹ năng mềm',
    'Hỗ trợ chuẩn bị và vận hành các buổi workshop kỹ năng cho sinh viên: setup phòng, hỗ trợ diễn giả, ghi nhận phản hồi.',
    'SMALL_EVENT', 0, 100, 50, 8, 'quest-workshop');
  PERFORM pg_temp.seed_quest(c_club2, g_club2,
    'Đại sứ truyền thông chiến dịch tuyển sinh',
    'Trở thành đại sứ lan tỏa thông tin tuyển sinh và hoạt động sinh viên trên các kênh mạng xã hội. Phù hợp bạn có sức ảnh hưởng và yêu thích truyền thông.',
    'SCHOOL_CAMPAIGN', 10, 300, 100, 10, 'quest-ambassador');

  -- CLB Khởi nghiệp UEH
  PERFORM pg_temp.seed_quest(c_club3, g_club3,
    'Thành viên Ban tổ chức cuộc thi Khởi nghiệp',
    'Tham gia tổ chức cuộc thi ý tưởng khởi nghiệp cấp trường: chấm sơ loại, hỗ trợ thí sinh, vận hành vòng chung kết.',
    'SCHOOL_CAMPAIGN', 10, 300, 120, 6, 'quest-startup-contest');
  PERFORM pg_temp.seed_quest(c_club3, g_club3,
    'Tình nguyện viên Talkshow Doanh nhân',
    'Hỗ trợ tổ chức buổi talkshow với khách mời doanh nhân: đón tiếp, điều phối Q&A, hậu cần. Cơ hội học hỏi và kết nối.',
    'SMALL_EVENT', 0, 100, 50, 10, 'quest-talkshow');

  RAISE NOTICE 'Seed hoàn tất: 6 tổ chức, 9 tin tuyển dụng, 8 quest (đều PENDING - vào Admin để duyệt).';
END $$;

COMMIT;
