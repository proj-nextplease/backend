-- Cho phép đăng bài và bình luận ẩn danh trong mục Thảo luận.
--
-- ─── Ẩn danh với AI ─────────────────────────────────────────────────────
-- Ẩn với NGƯỜI DÙNG KHÁC, không ẩn với hệ thống. author_user_id vẫn được
-- lưu đầy đủ và vẫn NOT NULL.
--
-- Vì sao không xoá hẳn danh tính:
--   1. Nền tảng có kiểm duyệt (hidden_at/hidden_by), cờ gian lận và khoá tài
--      khoản. Ẩn danh tuyệt đối nghĩa là ai đó quấy rối xong thì không xử lý
--      được — và "ẩn danh" sẽ thành lá chắn cho đúng những hành vi mà mục
--      thảo luận sinh viên dễ gặp nhất.
--   2. Chính tác giả vẫn phải sửa/xoá được bài của mình.
--   3. author_user_id đang NOT NULL và có khoá ngoại; bỏ nó đi là phá luôn
--      đường kiểm duyệt.
--
-- Giao diện PHẢI nói rõ điều này ra khi người dùng bật ẩn danh. Hứa "hoàn
-- toàn ẩn danh" rồi vẫn lưu danh tính là nói dối về quyền riêng tư — thứ
-- người dùng không có cách nào tự kiểm chứng.

ALTER TABLE discussion_posts
    ADD COLUMN IF NOT EXISTS is_anonymous boolean NOT NULL DEFAULT false;

ALTER TABLE discussion_comments
    ADD COLUMN IF NOT EXISTS is_anonymous boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN discussion_posts.is_anonymous IS
    'Ẩn tên tác giả với người dùng khác. Admin và chính tác giả vẫn thấy.';
COMMENT ON COLUMN discussion_comments.is_anonymous IS
    'Ẩn tên người bình luận với người dùng khác. Admin và chính họ vẫn thấy.';
