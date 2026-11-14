-- Đại sứ thương hiệu: ứng viên được admin cấp quyền dùng fonlio MIỄN PHÍ.
--
-- Một dòng = một nhiệm kỳ (cấp lúc nào, ai cấp, gỡ lúc nào, ai gỡ, vì sao).
-- Gỡ quyền KHÔNG xoá dòng mà điền revoked_at, để còn tra lại sau này ai đã
-- được miễn phí trong khoảng thời gian nào. Cấp lại sau khi gỡ là thêm dòng
-- mới.
--
-- Đang là đại sứ = có dòng với revoked_at is null. Index unique một phần bảo
-- đảm mỗi người chỉ có tối đa một nhiệm kỳ đang hiệu lực, kể cả khi hai admin
-- bấm cấp cùng lúc.
--
-- Tách bảng riêng thay vì thêm một role vào user_roles: role trong app quyết
-- định người dùng thấy giao diện nào (ứng viên / doanh nghiệp / admin), còn
-- đại sứ chỉ là "ứng viên được miễn phí". Gắn nó vào role thì mọi chỗ đang
-- kiểm role ứng viên phải học thêm một mã mới.

create table if not exists brand_ambassadors (
    id            uuid primary key default gen_random_uuid(),
    user_id       uuid not null references app_users(id) on delete cascade,
    note          text,
    granted_by    uuid references app_users(id) on delete set null,
    granted_at    timestamptz not null default now(),
    revoked_at    timestamptz,
    revoked_by    uuid references app_users(id) on delete set null,
    revoke_reason text
);

create unique index if not exists ux_brand_ambassadors_active
    on brand_ambassadors(user_id)
    where revoked_at is null;

create index if not exists idx_brand_ambassadors_user on brand_ambassadors(user_id);
