-- Migration V63: Portfolio Published Snapshot for Webflow/Notion-style Draft vs Published Model
alter table profiles
    add column if not exists published_snapshot jsonb default null;

comment on column profiles.published_snapshot is
    'Snapshot JSON của toàn bộ thông tin portfolio tại thời điểm xuất bản website công khai (phí 1.000 Điểm). Người xem link ngoài chỉ thấy nội dung từ snapshot này; các chỉnh sửa sau đó nằm ở bản nháp cho đến khi ứng viên cập nhật lại.';
