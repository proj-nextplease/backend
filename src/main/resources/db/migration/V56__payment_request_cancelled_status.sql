-- Ràng buộc status gốc (V2) chỉ cho phép PENDING/PAID/FAILED/EXPIRED/RESOLVED.
-- Lệnh huỷ đơn mới ghi 'CANCELLED' — giá trị không nằm trong danh sách đó, nên
-- mọi lần người dùng bấm "Huỷ thanh toán" sẽ nổ lỗi ràng buộc ở tầng CSDL.
--
-- Dùng CANCELLED chứ không tái sử dụng FAILED: hai chuyện khác hẳn nhau. FAILED
-- là trả tiền mà không thành; CANCELLED là người dùng đổi ý và chưa trả đồng
-- nào. Gộp lại thì sau này không còn phân biệt được khi đọc số liệu.

ALTER TABLE payment_requests
    DROP CONSTRAINT IF EXISTS ck_payment_requests_status;

ALTER TABLE payment_requests
    ADD CONSTRAINT ck_payment_requests_status
    CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'EXPIRED', 'RESOLVED', 'CANCELLED'));
