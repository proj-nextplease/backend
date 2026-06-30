-- ============================================================================
-- SEED DEMO (BỔ SUNG): thêm tin từ 2 tài khoản thật.
--   - phattai280405@gmail.com  -> Doanh nghiệp (job)  -> company "NextPlease Tech"
--   - nextplease.noreply@gmail.com -> CLB (quest)     -> company "CLB Truyền thông NextPlease"
-- Gắn vào company seed do file seed_demo_posts.sql tạo (nếu chưa có thì tự tạo,
-- verification_status = APPROVED, tag website_url='seed://demo').
-- Tất cả tin/quest ở status PENDING -> bạn tự duyệt trong Admin.
-- Đầy đủ trường + ảnh banner + 3 câu hỏi + skills. Hạn = 2026-07-04 23:59:59 +07.
-- Idempotent: chỉ xóa & chèn lại đúng các tiêu đề trong batch này (không đụng
-- tin của file seed gốc, không đụng data thật khác).
-- Cách dùng: dán vào SQL editor (Supabase/psql) rồi Run. Nên chạy SAU file gốc.
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
  -- tiêu đề batch này (để dọn idempotent)
  job_titles text[] := ARRAY[
    'Thực tập sinh Lập trình Mobile (Flutter)',
    'Nhân viên Chăm sóc Khách hàng (Part-time)',
    'Freelance Biên tập Video',
    'Thực tập sinh Nhân sự (HR Intern)',
    'Cộng tác viên SEO Website',
    'Nhân viên Hỗ trợ kỹ thuật (IT Support)'
  ];
  quest_titles text[] := ARRAY[
    'Quay phim & dựng clip sự kiện',
    'Ban Nội dung Fanpage CLB',
    'MC / Dẫn chương trình Gala',
    'Trưởng nhóm Hậu cần chiến dịch',
    'Photographer sự kiện sinh viên',
    'Ban Đối ngoại tìm tài trợ'
  ];
BEGIN
  SELECT id INTO biz_user  FROM app_users WHERE email = 'phattai280405@gmail.com';
  SELECT id INTO club_user FROM app_users WHERE email = 'nextplease.noreply@gmail.com';
  IF biz_user  IS NULL THEN RAISE EXCEPTION 'Không tìm thấy tài khoản phattai280405@gmail.com'; END IF;
  IF club_user IS NULL THEN RAISE EXCEPTION 'Không tìm thấy tài khoản nextplease.noreply@gmail.com'; END IF;

  -- Lấy company seed (do file gốc tạo); nếu chưa có thì tạo mới (APPROVED, tag seed)
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

  -- Dọn batch cũ (chỉ đúng tiêu đề trong batch này)
  DELETE FROM applications       WHERE job_id   IN (SELECT id FROM jobs   WHERE company_id = c_biz  AND title = ANY(job_titles));
  DELETE FROM quest_applications WHERE quest_id IN (SELECT id FROM quests WHERE company_id = c_club AND title = ANY(quest_titles));
  DELETE FROM jobs   WHERE company_id = c_biz  AND title = ANY(job_titles);
  DELETE FROM quests WHERE company_id = c_club AND title = ANY(quest_titles);

  -- ----- JOBS (Doanh nghiệp - phattai280405) -----
  PERFORM pg_temp.seed_job(c_biz, biz_user,
    'Thực tập sinh Lập trình Mobile (Flutter)',
    'Tham gia phát triển ứng dụng di động đa nền tảng bằng Flutter. Được mentor hướng dẫn quy trình phát triển app thực tế, làm việc cùng đội ngũ sản phẩm.',
    'INTERNSHIP', 'TECH', 'SOFTWARE_ENG', 4200000, 'TP. Hồ Chí Minh', false, 0, 3, 'job2-flutter', ARRAY['JavaScript','Git']);
  PERFORM pg_temp.seed_job(c_biz, biz_user,
    'Nhân viên Chăm sóc Khách hàng (Part-time)',
    'Hỗ trợ giải đáp thắc mắc và chăm sóc người dùng qua các kênh chat/email. Ca linh hoạt, phù hợp sinh viên giao tiếp tốt, kiên nhẫn.',
    'PART_TIME', 'BUSINESS', 'OPERATIONS', 4000000, 'TP. Hồ Chí Minh', false, 0, 4, 'job2-cs', ARRAY['English']);
  PERFORM pg_temp.seed_job(c_biz, biz_user,
    'Freelance Biên tập Video',
    'Dựng và biên tập video ngắn cho mạng xã hội và sản phẩm. Làm remote, thanh toán theo dự án. Ưu tiên có sản phẩm mẫu.',
    'FREELANCE', 'MEDIA', 'CONTENT_CREATIVE', 6000000, 'Remote', true, 0, 3, 'job2-video', ARRAY['Content Writing','Social Media']);
  PERFORM pg_temp.seed_job(c_biz, biz_user,
    'Thực tập sinh Nhân sự (HR Intern)',
    'Hỗ trợ tuyển dụng, sàng lọc hồ sơ, tổ chức phỏng vấn và các hoạt động nội bộ. Cơ hội học nghề HR bài bản từ đội ngũ giàu kinh nghiệm.',
    'INTERNSHIP', 'BUSINESS', 'HR', 3500000, 'TP. Hồ Chí Minh', false, 0, 2, 'job2-hr', ARRAY['Excel','English']);
  PERFORM pg_temp.seed_job(c_biz, biz_user,
    'Cộng tác viên SEO Website',
    'Nghiên cứu từ khóa, tối ưu nội dung và theo dõi thứ hạng website. Làm việc từ xa, phù hợp bạn yêu thích marketing số.',
    'FREELANCE', 'BUSINESS', 'MARKETING', 3500000, 'Remote', true, 0, 4, 'job2-seo', ARRAY['Marketing','Content Writing']);
  PERFORM pg_temp.seed_job(c_biz, biz_user,
    'Nhân viên Hỗ trợ kỹ thuật (IT Support, Part-time)',
    'Hỗ trợ vận hành hệ thống, xử lý sự cố kỹ thuật cơ bản cho nội bộ. Phù hợp sinh viên CNTT muốn làm part-time tích lũy kinh nghiệm.',
    'PART_TIME', 'TECH', 'INFO_SYSTEMS', 4500000, 'TP. Hồ Chí Minh', false, 0, 2, 'job2-itsupport', ARRAY['SQL','Git']);

  -- ----- QUESTS (CLB - nextplease.noreply) -----
  PERFORM pg_temp.seed_quest(c_club, club_user,
    'Quay phim & dựng clip sự kiện',
    'Ghi hình và dựng clip recap cho các sự kiện của CLB. Phù hợp bạn yêu thích sản xuất video, có thiết bị cơ bản là lợi thế.',
    'SMALL_EVENT', 0, 100, 50, 4, 'quest2-film');
  PERFORM pg_temp.seed_quest(c_club, club_user,
    'Ban Nội dung Fanpage CLB',
    'Lên kế hoạch và sản xuất nội dung định kỳ cho fanpage CLB: viết bài, thiết kế post, tương tác cộng đồng. Hoạt động xuyên suốt học kỳ.',
    'SCHOOL_CAMPAIGN', 10, 300, 100, 6, 'quest2-content');
  PERFORM pg_temp.seed_quest(c_club, club_user,
    'MC / Dẫn chương trình Gala',
    'Dẫn dắt chương trình Gala cuối kỳ của CLB. Cơ hội rèn luyện kỹ năng sân khấu, làm việc cùng ban tổ chức.',
    'SMALL_EVENT', 0, 100, 60, 2, 'quest2-mc');
  PERFORM pg_temp.seed_quest(c_club, club_user,
    'Trưởng nhóm Hậu cần chiến dịch',
    'Điều phối toàn bộ công tác hậu cần cho chiến dịch lớn của CLB: vật tư, địa điểm, nhân sự. Vai trò leader, cộng nhiều RS khi hoàn thành.',
    'SCHOOL_CAMPAIGN', 20, 300, 150, 3, 'quest2-logistics');
  PERFORM pg_temp.seed_quest(c_club, club_user,
    'Photographer sự kiện sinh viên',
    'Chụp ảnh tư liệu và truyền thông cho các sự kiện sinh viên. Yêu thích nhiếp ảnh, có máy ảnh là lợi thế.',
    'SMALL_EVENT', 0, 100, 50, 5, 'quest2-photo');
  PERFORM pg_temp.seed_quest(c_club, club_user,
    'Ban Đối ngoại tìm tài trợ',
    'Liên hệ và thuyết phục nhà tài trợ cho các hoạt động của CLB. Rèn kỹ năng đàm phán, viết proposal và xây dựng quan hệ đối tác.',
    'SCHOOL_CAMPAIGN', 10, 300, 120, 4, 'quest2-sponsor');

  RAISE NOTICE 'Đã thêm 6 tin (DN) + 6 quest (CLB) cho 2 tài khoản thật - đều PENDING, vào Admin để duyệt.';
END $$;

COMMIT;
