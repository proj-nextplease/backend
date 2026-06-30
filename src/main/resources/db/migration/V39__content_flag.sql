-- Cờ kiểm duyệt nội dung tự động: đánh dấu tin/quest nghi ngờ chứa từ ngữ không phù hợp.
-- Mặc định false. Hệ thống set true khi ContentModerationService phát hiện nghi ngờ;
-- Admin thấy cảnh báo trong hàng chờ duyệt và quyết định cuối cùng (không chặn cứng).
alter table jobs   add column if not exists content_flag boolean not null default false;
alter table quests add column if not exists content_flag boolean not null default false;
