-- Nạp NP qua Google Play Billing (app Android).
--
-- Google Play bắt mọi giao dịch mua hàng số trong app Android đi qua hệ thống
-- thanh toán của họ, nên app Android không dùng PayOS được. Đơn từ Google Play
-- được ghi vào cùng bảng payment_requests với provider = 'GOOGLE_PLAY'.
--
-- Chống cộng trùng: provider_transaction_id lưu SHA-256 của purchaseToken
-- (token gốc dài và Google không cam kết độ dài tối đa). Index unique
-- ux_payment_requests_provider_txn (V2) bảo đảm một token chỉ cộng NP một lần,
-- kể cả khi app gửi lại hoặc hai request chạy song song.

ALTER TABLE payment_requests
    DROP CONSTRAINT IF EXISTS ck_payment_requests_provider;

ALTER TABLE payment_requests
    ADD CONSTRAINT ck_payment_requests_provider
    CHECK (provider IN ('PAYOS', 'VIETQR', 'MOCK', 'GOOGLE_PLAY'));
