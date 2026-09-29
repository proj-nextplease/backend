-- Đổi giá hai dịch vụ NP, theo quyết định của chủ sản phẩm:
--   Đẩy đơn ứng tuyển lên đầu : 15.000 -> 25.000 NP
--   Xác thực minh chứng nhanh : 25.000 -> 15.000 NP
--
-- Phải sửa bằng migration riêng chứ không sửa vào V34: Flyway chỉ chạy mỗi
-- file một lần và ghi lại checksum, nên sửa file cũ vừa không có tác dụng trên
-- môi trường đã chạy, vừa làm hỏng kiểm tra checksum của những môi trường đó.
--
-- Chỉ đổi khi giá vẫn đang là giá cũ. Nếu quản trị viên đã tự chỉnh trong
-- trang cấu hình thì con số của họ được giữ nguyên — migration không nên đè
-- lên quyết định mà người ta vừa đưa ra trên giao diện.

UPDATE system_configs
   SET value_int = 25000
 WHERE config_key = 'premium_boost_price_np'
   AND value_int = 15000;

UPDATE system_configs
   SET value_int = 15000
 WHERE config_key = 'express_verification_price_np'
   AND value_int = 25000;
