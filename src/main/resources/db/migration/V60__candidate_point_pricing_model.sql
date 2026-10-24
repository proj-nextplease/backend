-- V60: Candidate Point Pricing Model & Offer Acceptance Workflow
-- 1. Update application status check constraint to include OFFERED and OFFER_DECLINED
alter table applications
    drop constraint if exists ck_applications_status;

alter table applications
    add constraint ck_applications_status
        check (status in ('SUBMITTED', 'VIEWED', 'SHORTLISTED', 'OFFERED', 'OFFER_DECLINED', 'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'COMPLETED'));

-- 2. Configure new point pricing in system_configs
insert into system_configs (config_key, value_int, data_type, config_group, label, description)
values
    ('paid_job_acceptance_fee_np', 19000, 'INT', 'pricing', 'Phí nhận việc có thù lao (NP)', 'Phí điểm trừ khi ứng viên đồng ý nhận việc dự án có trả lương (19.000 điểm)'),
    ('portfolio_export_fee_np', 1000, 'INT', 'pricing', 'Phí xuất bản Portfolio website (NP)', 'Phí điểm trừ khi ứng viên xuất Portfolio thành website công khai (1.000 điểm)'),
    ('default_topup_package_np', 39000, 'INT', 'pricing', 'Gói nạp chuẩn FONLIO (NP)', 'Gói nạp điểm mặc định của FONLIO (39.000 VNĐ = 39.000 điểm)')
on conflict (config_key) do update
    set value_int = excluded.value_int,
        data_type = excluded.data_type,
        config_group = excluded.config_group,
        label = excluded.label,
        description = excluded.description,
        updated_at = now();

-- 3. Add portfolio export metadata to profiles
alter table profiles
    add column if not exists last_exported_at timestamptz,
    add column if not exists export_count integer not null default 0;
