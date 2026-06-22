-- Platform configuration store (low-code admin settings).
-- Lets admins tune pricing/scoring/feature values without code changes.
-- BE reads these via ConfigService (cached); changes apply to NEW events only.

-- Drop any pre-existing/incompatible leftover table so the schema below is authoritative.
drop table if exists system_configs cascade;

create table system_configs (
    config_key   varchar(80) primary key,
    value_int    integer,
    value_text   text,
    data_type    varchar(20)  not null default 'INT',   -- INT | TEXT
    config_group varchar(40)  not null default 'general',
    label        varchar(200),
    description  text,
    updated_by   uuid,
    updated_at   timestamptz  not null default now()
);

-- Seed with current hardcoded values (no behavior change on deploy).
insert into system_configs (config_key, value_int, data_type, config_group, label, description) values
    ('premium_price_np',    40000, 'INT', 'pricing', 'Giá Premium Pass (NP / tháng)', 'Số NP bị trừ khi mua/gia hạn Premium Pass.'),
    ('min_topup_vnd',       10000, 'INT', 'pricing', 'Nạp tối thiểu (VND)',           'Số tiền nạp NP tối thiểu mỗi lần.'),
    ('five_star_reward_np',  2000, 'INT', 'pricing', 'Thưởng 5 sao (NP)',             'NP thưởng cho ứng viên khi được đánh giá 5 sao.')
on conflict (config_key) do nothing;
