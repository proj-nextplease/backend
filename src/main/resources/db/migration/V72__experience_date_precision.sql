-- V72: ngày bắt đầu / kết thúc kinh nghiệm chuyển sang dd/MM/yyyy.
--
-- Trước đây người dùng chỉ khai tháng/năm và started_at/ended_at được lưu là
-- ngày 01 của tháng. Hai cờ dưới đây phân biệt ngày người dùng thật sự khai với
-- con số 01 giả đó: bản ghi cũ mặc định false nên vẫn chỉ hiện MM/yyyy, không
-- tự nhiên thành "01/06/2026" trên hồ sơ công khai.
alter table experiences
    add column if not exists started_at_has_day boolean not null default false,
    add column if not exists ended_at_has_day   boolean not null default false;
