-- ============================================================================
-- SEED DEMO (BỔ SUNG ĐỢT 3): thêm tin từ 2 tài khoản thật.
--   - phattai280405@gmail.com      -> Doanh nghiệp -> company "NextPlease Tech"
--   - nextplease.noreply@gmail.com -> CLB          -> company "CLB Truyền thông NextPlease"
-- Tất cả PENDING -> tự duyệt trong Admin. Đầy đủ trường + ảnh banner + 3 câu hỏi + skills.
-- Hạn (deadline/ends_at) = 2026-07-04 23:59:59 +07.
-- Idempotent theo tiêu đề batch này (không đụng các script seed trước, không đụng data thật).
-- Dán vào SQL editor (Supabase/psql) rồi Run. Nên chạy SAU seed_demo_posts.sql.
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

DO $$
DECLARE
  biz_user  uuid;
  club_user uuid;
  c_biz  uuid;
  c_club uuid;
  job_titles text[] := ARRAY[
    'Thực tập sinh Kế toán (Accounting Intern)',
    'Nhân viên Kinh doanh (Sales Executive, Part-time)',
    'Freelance Dịch thuật Anh - Việt',
    'Thực tập sinh DevOps',
    'Cộng tác viên Quản lý Fanpage',
    'Nhân viên Thiết kế Motion Graphics (Part-time)'
  ];
  quest_titles text[] := ARRAY[
    'Ban Tổ chức Ngày hội Việc làm',
    'Tình nguyện viên Hiến máu nhân đạo',
    'Cộng tác viên Viết bài blog sinh viên',
    'Điều phối Workshop Khởi nghiệp',
    'Ban Kỹ thuật Âm thanh - Ánh sáng',
    'Trưởng ban Truyền thông CLB'
  ];
BEGIN
  SELECT id INTO biz_user  FROM app_users WHERE email = 'phattai280405@gmail.com';
  SELECT id INTO club_user FROM app_users WHERE email = 'nextplease.noreply@gmail.com';
  IF biz_user  IS NULL THEN RAISE EXCEPTION 'Không tìm thấy tài khoản phattai280405@gmail.com'; END IF;
  IF club_user IS NULL THEN RAISE EXCEPTION 'Không tìm thấy tài khoản nextplease.noreply@gmail.com'; END IF;

  SELECT id INTO c_biz FROM companies
    WHERE owner_user_id = biz_user AND name = 'NextPlease Tech' ORDER BY created_at LIMIT 1;
  IF c_biz IS NULL THEN
    INSERT INTO companies (owner_user_id, name, company_type, description, website_url, logo_url, verification_status, monthly_job_quota)
    VALUES (biz_user, 'NextPlease Tech', 'SME',
            'Công ty công nghệ phát triển nền tảng tuyển dụng dựa trên proof of work cho sinh viên.',
            'seed://demo', 'https://picsum.photos/seed/np-tech-logo/200', 'APPROVED', 50)
    RETURNING id INTO c_biz;
  END IF;

  SELECT id INTO c_club FROM companies
    WHERE owner_user_id = club_user AND name = 'CLB Truyền thông NextPlease' ORDER BY created_at LIMIT 1;
  IF c_club IS NULL THEN
    INSERT INTO companies (owner_user_id, name, company_type, description, website_url, logo_url, verification_status, monthly_job_quota)
    VALUES (club_user, 'CLB Truyền thông NextPlease', 'CLUB',
            'Câu lạc bộ sinh viên hoạt động trong lĩnh vực truyền thông, sự kiện và sáng tạo nội dung.',
            'seed://demo', 'https://picsum.photos/seed/np-club-logo/200', 'APPROVED', 50)
    RETURNING id INTO c_club;
  END IF;

  DELETE FROM applications       WHERE job_id   IN (SELECT id FROM jobs   WHERE company_id = c_biz  AND title = ANY(job_titles));
  DELETE FROM quest_applications WHERE quest_id IN (SELECT id FROM quests WHERE company_id = c_club AND title = ANY(quest_titles));
  DELETE FROM jobs   WHERE company_id = c_biz  AND title = ANY(job_titles);
  DELETE FROM quests WHERE company_id = c_club AND title = ANY(quest_titles);

  -- ----- JOBS (Doanh nghiệp - phattai280405) -----
  PERFORM pg_temp.seed_job(c_biz, biz_user,
    'Thực tập sinh Kế toán (Accounting Intern)',
    'Hỗ trợ ghi nhận chứng từ, đối chiếu sổ sách và lập báo cáo cơ bản. Được hướng dẫn quy trình kế toán doanh nghiệp thực tế. Phù hợp sinh viên ngành Kế toán - Tài chính.',
    'INTERNSHIP', 'BUSINESS', 'FINANCE', 3500000, 'TP. Hồ Chí Minh', false, 0, 2, 'job3-acct', ARRAY['Excel']);
  PERFORM pg_temp.seed_job(c_biz, biz_user,
    'Nhân viên Kinh doanh (Sales Executive, Part-time)',
    'Tư vấn và bán giải pháp cho khách hàng, mở rộng tệp khách hàng mới. Lương cứng + hoa hồng hấp dẫn, thời gian linh hoạt theo ca.',
    'PART_TIME', 'BUSINESS', 'SALES', 5500000, 'TP. Hồ Chí Minh', false, 10, 3, 'job3-sales', ARRAY['Sales','Marketing']);
  PERFORM pg_temp.seed_job(c_biz, biz_user,
    'Freelance Dịch thuật Anh - Việt',
    'Dịch tài liệu kỹ thuật và nội dung marketing giữa tiếng Anh và tiếng Việt. Làm remote, nhận việc theo dự án. Yêu cầu tiếng Anh tốt, dịch tự nhiên.',
    'FREELANCE', 'LANGUAGE', 'TRANSLATION', 4000000, 'Remote', true, 0, 4, 'job3-translate', ARRAY['English','Translation']);
  PERFORM pg_temp.seed_job(c_biz, biz_user,
    'Thực tập sinh DevOps',
    'Làm quen CI/CD, container hóa ứng dụng và vận hành hạ tầng cloud. Được mentor về quy trình triển khai tự động. Ưu tiên biết Git, Linux cơ bản.',
    'INTERNSHIP', 'TECH', 'SOFTWARE_ENG', 5000000, 'TP. Hồ Chí Minh', false, 0, 2, 'job3-devops', ARRAY['Git','SQL']);
  PERFORM pg_temp.seed_job(c_biz, biz_user,
    'Cộng tác viên Quản lý Fanpage',
    'Lên lịch đăng bài, trả lời tin nhắn và theo dõi chỉ số tương tác trên fanpage. Làm việc từ xa, phù hợp bạn yêu thích mạng xã hội.',
    'FREELANCE', 'MEDIA', 'SOCIAL_MEDIA', 3000000, 'Remote', true, 0, 5, 'job3-fanpage', ARRAY['Social Media','Content Writing']);
  PERFORM pg_temp.seed_job(c_biz, biz_user,
    'Nhân viên Thiết kế Motion Graphics (Part-time)',
    'Dựng video motion graphics, hiệu ứng đồ họa cho quảng cáo và mạng xã hội. Yêu cầu có gu thẩm mỹ, biết After Effects/Illustrator.',
    'PART_TIME', 'DESIGN', 'GRAPHIC_DESIGN', 6500000, 'TP. Hồ Chí Minh', false, 20, 2, 'job3-motion', ARRAY['Illustrator','Photoshop']);

  -- ----- QUESTS (CLB - nextplease.noreply) -----
  PERFORM pg_temp.seed_quest(c_club, club_user,
    'Ban Tổ chức Ngày hội Việc làm',
    'Tham gia ban tổ chức Ngày hội Việc làm cấp trường: liên hệ doanh nghiệp, sắp xếp gian hàng, điều phối sinh viên tham dự.',
    'SCHOOL_CAMPAIGN', 10, 300, 150, 6, 'quest3-jobfair');
  PERFORM pg_temp.seed_quest(c_club, club_user,
    'Tình nguyện viên Hiến máu nhân đạo',
    'Hỗ trợ tổ chức ngày hội hiến máu: đón tiếp, hướng dẫn thủ tục, chăm sóc người hiến máu. Hoạt động ý nghĩa, rèn kỹ năng cộng đồng.',
    'SMALL_EVENT', 0, 100, 50, 10, 'quest3-blood');
  PERFORM pg_temp.seed_quest(c_club, club_user,
    'Cộng tác viên Viết bài blog sinh viên',
    'Viết bài chia sẻ kinh nghiệm học tập, hoạt động sinh viên cho blog của CLB. Phù hợp bạn yêu thích viết lách.',
    'SMALL_EVENT', 0, 100, 60, 5, 'quest3-blog');
  PERFORM pg_temp.seed_quest(c_club, club_user,
    'Điều phối Workshop Khởi nghiệp',
    'Điều phối chuỗi workshop khởi nghiệp: liên hệ diễn giả, quản lý đăng ký, vận hành buổi học. Cơ hội học hỏi và mở rộng quan hệ.',
    'SCHOOL_CAMPAIGN', 10, 300, 120, 5, 'quest3-startup-ws');
  PERFORM pg_temp.seed_quest(c_club, club_user,
    'Ban Kỹ thuật Âm thanh - Ánh sáng',
    'Phụ trách setup và vận hành âm thanh, ánh sáng cho các sự kiện của CLB. Phù hợp bạn thích kỹ thuật sân khấu.',
    'SMALL_EVENT', 0, 100, 50, 4, 'quest3-tech');
  PERFORM pg_temp.seed_quest(c_club, club_user,
    'Trưởng ban Truyền thông CLB',
    'Dẫn dắt ban truyền thông: hoạch định chiến lược nội dung, quản lý đội ngũ và điều phối các chiến dịch. Vai trò leader, cộng nhiều RS khi hoàn thành.',
    'SCHOOL_CAMPAIGN', 20, 300, 150, 2, 'quest3-comms-lead');

  RAISE NOTICE 'Đã thêm 6 tin (DN) + 6 quest (CLB) cho 2 tài khoản thật - đều PENDING, vào Admin để duyệt.';
END $$;

COMMIT;
