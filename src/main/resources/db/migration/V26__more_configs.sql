-- More low-code tunables (previously hardcoded). Seeded with current defaults.

insert into system_configs (config_key, value_int, data_type, config_group, label, description) values
    ('premium_duration_days',  30, 'INT', 'pricing', 'Thời hạn Premium (ngày)', 'Số ngày Premium Pass có hiệu lực mỗi lần mua/gia hạn.'),
    ('free_jd_quota_monthly',   3, 'INT', 'limits',  'Hạn mức tin/tháng (Free)', 'Số tin tuyển dụng tối đa mỗi tháng cho doanh nghiệp tài khoản miễn phí.')
on conflict (config_key) do nothing;
