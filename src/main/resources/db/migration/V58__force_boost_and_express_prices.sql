-- V57 không đổi được giá nào.
--
-- Nó có điều kiện `AND value_int = <giá cũ>` để tránh đè lên con số quản trị
-- viên tự chỉnh. Nhưng giá thật trong CSDL lúc đó là 26.000 chứ không phải
-- 25.000 như trong file cài đặt gốc — tức là đã có người chỉnh tay. Điều kiện
-- không khớp, UPDATE chạy đúng 0 dòng, và migration vẫn báo thành công.
--
-- Bài học: điều kiện kiểu đó chỉ hợp khi đang dọn dữ liệu, KHÔNG hợp khi
-- người ra quyết định vừa nói rõ giá phải là bao nhiêu. Ở đây giá mới là chỉ
-- thị, nên đặt thẳng.
--
-- Phải là V58 chứ không sửa V57: V57 đã chạy trên production và Flyway đã lưu
-- checksum của nó.

UPDATE system_configs SET value_int = 25000 WHERE config_key = 'premium_boost_price_np';
UPDATE system_configs SET value_int = 15000 WHERE config_key = 'express_verification_price_np';

-- Nếu vì lý do nào đó khoá chưa tồn tại thì tạo luôn, để giá không rơi về
-- giá trị mặc định ghi trong mã nguồn.
INSERT INTO system_configs (config_key, value_int, data_type, config_group, label, description)
VALUES
    ('premium_boost_price_np', 25000, 'INT', 'pricing', 'Giá mua lượt nổi bật (Profile Boost)', 'Số NP bị trừ khi boost đơn ứng tuyển.'),
    ('express_verification_price_np', 15000, 'INT', 'pricing', 'Giá xác thực nhanh (Express)', 'Số NP bị trừ khi đăng ký xác thực nhanh kinh nghiệm.')
ON CONFLICT (config_key) DO NOTHING;
