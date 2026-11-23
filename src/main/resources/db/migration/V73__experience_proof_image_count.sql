-- V73: Lưu sẵn số lượng ảnh minh chứng.
--
-- proof_images chứa data URL base64 (tổng ~140 MB). Hàng chờ xác thực của admin
-- chỉ cần biết "có mấy ảnh", nhưng jsonb_array_length(proof_images) buộc
-- Postgres giải nén toàn bộ cột cho từng dòng → truy vấn danh sách mất 10–13s.
-- Cột generated STORED được Postgres tự tính lại mỗi khi proof_images đổi,
-- nên không cần sửa bất kỳ câu INSERT/UPDATE nào.
alter table experiences
    add column if not exists proof_image_count integer
        generated always as (
            coalesce(jsonb_array_length(case when jsonb_typeof(proof_images) = 'array'
                                             then proof_images end), 0)
        ) stored;
