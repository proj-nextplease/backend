-- Vị trí khung hình của ảnh bìa portfolio.
--
-- Cột cover_banner_url đã có từ V2 (nhưng chưa từng được ghi). Ảnh bìa là dải
-- ngang, người dùng cần kéo để chọn phần hiển thị và phóng to — giống banner
-- tin tuyển dụng. Lưu cùng một định dạng chuỗi "x% y% zoom" mà jobs.banner_pos
-- đang dùng, để hai nơi chia sẻ được code parse/serialize ở frontend.
ALTER TABLE profiles
    ADD COLUMN IF NOT EXISTS cover_banner_pos VARCHAR(40);

COMMENT ON COLUMN profiles.cover_banner_pos IS
    'Khung hình ảnh bìa dạng "x% y% zoom", cùng quy ước với jobs.banner_pos.';
