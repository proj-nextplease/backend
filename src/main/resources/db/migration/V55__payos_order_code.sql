-- PayOS định danh mỗi giao dịch bằng `orderCode` KIỂU SỐ do mình tự sinh, và
-- webhook báo thanh toán chỉ gửi lại đúng con số đó — không gửi kèm id nội bộ
-- của mình. Không lưu lại thì khi webhook về, không có cách nào biết nó đang
-- nói về đơn nạp tiền nào.
--
-- bigint chứ không phải int: PayOS giới hạn orderCode tối đa 9.007.199.254.740.991
-- (giới hạn số nguyên an toàn của JavaScript), lớn hơn nhiều so với int 32-bit.
-- Sinh theo mốc thời gian mili-giây là vượt int ngay lập tức.

ALTER TABLE payment_requests
    ADD COLUMN IF NOT EXISTS order_code BIGINT;

-- unique để chống cộng tiền hai lần: PayOS gửi lại webhook khi không nhận được
-- HTTP 200, và đây là lớp phòng thủ cuối ở tầng CSDL nếu logic ứng dụng sót.
-- Partial index vì các đơn MOCK cũ không có order_code.
CREATE UNIQUE INDEX IF NOT EXISTS ux_payment_requests_order_code
    ON payment_requests (order_code)
    WHERE order_code IS NOT NULL;

COMMENT ON COLUMN payment_requests.order_code IS
    'Mã đơn dạng số gửi sang PayOS; webhook dùng chính nó để đối chiếu.';
