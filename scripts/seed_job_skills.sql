-- ═══════════════════════════════════════════════════════════════════════
-- Gắn kỹ năng cho các tin tuyển dụng và Quest đang có.
-- ═══════════════════════════════════════════════════════════════════════
--
-- VÌ SAO CẦN:
-- Đo trên dữ liệu thật ngày 24/09/2026: 0/10 tin có kỹ năng nào. Bảng
-- job_skills rỗng làm chết ba thứ cùng lúc:
--
--   1. "Hợp với bạn" trên trang chủ app  — không bao giờ xuất hiện
--   2. Job Match Alert (gói Premium 19.000 NP) — notifyMatchAlertSubscribers
--      đòi "ít nhất một kỹ năng khớp", nên nó KHÔNG BAO GIỜ bắn. Đang bán
--      một tính năng không chạy được.
--   3. Insight — điểm cạnh tranh tính theo số kỹ năng khớp
--
-- Đây là thiếu DỮ LIỆU, không phải lỗi code: form đăng tin có bộ chọn kỹ
-- năng và JobService ghi vào job_skills bình thường. Các tin hiện tại được
-- seed thẳng bằng SQL nên bỏ qua bước đó.
--
-- CÁCH LÀM:
-- Gán theo `category` của tin chứ không ghi cứng UUID. Chạy lại được nhiều
-- lần (on conflict do nothing), và vẫn đúng khi bạn thêm tin mới cùng lĩnh
-- vực. Chỉ đụng tới tin CHƯA có kỹ năng nào — không ghi đè lựa chọn mà
-- người dùng đã tự chọn trên form.
--
-- AN TOÀN: chỉ INSERT, không UPDATE, không DELETE.
-- ═══════════════════════════════════════════════════════════════════════

begin;

-- ── 1. Tin tuyển dụng ──────────────────────────────────────────────────
with mapping(category, skill_name, lvl) as (values
    -- Công nghệ & Kỹ thuật
    ('TECH',     'Java',               'INTERMEDIATE'),
    ('TECH',     'SQL',                'INTERMEDIATE'),
    ('TECH',     'Git',                'BASIC'),
    ('TECH',     'JavaScript',         'BASIC'),
    -- Thiết kế & Nghệ thuật
    ('DESIGN',   'Figma',              'INTERMEDIATE'),
    ('DESIGN',   'UI/UX Design',       'INTERMEDIATE'),
    ('DESIGN',   'Photoshop',          'BASIC'),
    -- Kinh tế & Quản lý
    ('BUSINESS', 'Marketing',          'INTERMEDIATE'),
    ('BUSINESS', 'Excel',              'BASIC'),
    ('BUSINESS', 'Project Management', 'BASIC'),
    -- Truyền thông & Sự kiện
    ('MEDIA',    'Content Writing',    'INTERMEDIATE'),
    ('MEDIA',    'Social Media',       'INTERMEDIATE'),
    ('MEDIA',    'Event Planning',     'BASIC'),
    -- Ngôn ngữ & Nhân văn
    ('LANGUAGE', 'English',            'INTERMEDIATE'),
    ('LANGUAGE', 'Translation',        'BASIC')
)
insert into job_skills (job_id, skill_id, required_level)
select j.id, s.id, m.lvl
from jobs j
join mapping m on m.category = j.category
join skills  s on s.name = m.skill_name
where j.deleted_at is null
  -- Chỉ tin CHƯA có kỹ năng nào: không đụng vào tin mà người dùng đã tự chọn.
  and not exists (select 1 from job_skills js where js.job_id = j.id)
on conflict (job_id, skill_id) do nothing;

-- ── 2. Quest ───────────────────────────────────────────────────────────
-- Quest dùng `category` riêng (Hỗ trợ & Proof, chiến dịch…), không trùng
-- bộ mã của tin tuyển dụng, nên gán theo nhóm kỹ năng chung của hoạt động
-- CLB thay vì cố ánh xạ 1-1.
with quest_skill_names(skill_name) as (values
    ('Event Planning'), ('Social Media'), ('Content Writing')
)
insert into quest_skills (quest_id, skill_id)
select q.id, s.id
from quests q
cross join quest_skill_names n
join skills s on s.name = n.skill_name
where q.deleted_at is null
  and not exists (select 1 from quest_skills qs where qs.quest_id = q.id)
on conflict (quest_id, skill_id) do nothing;

commit;

-- ── Kiểm lại ───────────────────────────────────────────────────────────
select 'jobs'   as loai,
       count(*) filter (where exists (select 1 from job_skills js where js.job_id = j.id)) as co_ky_nang,
       count(*) as tong
from jobs j where j.deleted_at is null
union all
select 'quests',
       count(*) filter (where exists (select 1 from quest_skills qs where qs.quest_id = q.id)),
       count(*)
from quests q where q.deleted_at is null;
