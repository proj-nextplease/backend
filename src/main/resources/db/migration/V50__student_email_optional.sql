-- Email sinh viên trở thành TUỲ CHỌN khi đăng ký ứng viên.
--
-- Vì sao bỏ ràng buộc: trường này được thu thập nhưng KHÔNG hề xác minh —
-- CandidateRegistrationService luôn tạo tài khoản với student_email_verified
-- = false bất kể người dùng nhập gì. Nói cách khác nó đang bắt buộc mà không
-- đổi lấy điều gì, chỉ thêm một ô chặn người đăng ký mới.
--
-- Việc xác minh sinh viên (nếu làm) sẽ là một luồng riêng có gửi mã tới hòm
-- thư trường, không phải một ô text lúc đăng ký.
--
-- Dữ liệu cũ giữ nguyên: bỏ NOT NULL không đụng tới dòng nào đã có.

alter table candidate_registration_attempts
    alter column student_email drop not null;

comment on column candidate_registration_attempts.student_email is
    'Email trường học, TUỲ CHỌN. Không được xác minh ở bước đăng ký; '
    'app_users.student_email_verified luôn bắt đầu bằng false.';
