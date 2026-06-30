-- Cờ kiểm duyệt nội dung cho minh chứng (kinh nghiệm) do ứng viên nộp.
-- Đặt true khi ContentModerationService nghi ngờ tên hoạt động/vai trò/mô tả có từ ngữ
-- không phù hợp. Admin thấy cảnh báo trong hàng chờ xác thực (không chặn cứng).
alter table experiences add column if not exists content_flag boolean not null default false;
