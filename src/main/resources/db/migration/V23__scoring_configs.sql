-- Scoring rules as admin-tunable config (group = scoring). Seeded with current
-- defaults so deploy doesn't change behavior. Applies to NEW events only.

insert into system_configs (config_key, value_int, data_type, config_group, label, description) values
    ('rs_email_verified',     10, 'INT', 'scoring', 'RS khi xác thực email SV',   'Cộng RS một lần khi xác thực email sinh viên.'),
    ('rs_onboarding',          5, 'INT', 'scoring', 'RS khi hoàn tất hồ sơ',      'Cộng RS một lần khi hoàn tất onboarding/portfolio.'),
    ('rs_quest_completed',     5, 'INT', 'scoring', 'RS khi hoàn thành Quest',    'Cộng RS khi một Quest được đánh dấu hoàn thành.'),
    ('rs_job_completed',       5, 'INT', 'scoring', 'RS khi hoàn thành Job',      'Cộng RS khi một đơn ứng tuyển Job được đánh dấu hoàn thành.'),
    ('exp_small_event',      100, 'INT', 'scoring', 'EXP — Sự kiện nhỏ',          'EXP thưởng cho Quest loại SMALL_EVENT.'),
    ('exp_school_campaign',  300, 'INT', 'scoring', 'EXP — Chiến dịch trường',    'EXP thưởng cho Quest loại SCHOOL_CAMPAIGN.'),
    ('exp_company_project',  500, 'INT', 'scoring', 'EXP — Dự án doanh nghiệp',   'EXP thưởng cho Quest loại COMPANY_PROJECT.'),
    ('exp_short_internship', 500, 'INT', 'scoring', 'EXP — Thực tập ngắn',        'EXP thưởng cho Quest loại SHORT_INTERNSHIP.'),
    ('exp_freelance_gig',    300, 'INT', 'scoring', 'EXP — Freelance',            'EXP thưởng cho Quest loại FREELANCE_GIG.')
on conflict (config_key) do nothing;
