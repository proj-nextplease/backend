package com.nextplease.backend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CandidateRegistrationOtpRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 6, max = 25)
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
                message = "Password must contain at least one lowercase, one uppercase, and one digit"
        )
        String password,
        @NotBlank @Size(max = 160) String displayName,
        /// Email trường học — TUỲ CHỌN.
        ///
        /// Bỏ @NotBlank vì trường này không được xác minh ở bước đăng ký:
        /// tài khoản luôn được tạo với student_email_verified = false dù
        /// người dùng nhập gì. Bắt buộc một ô không đổi lấy điều gì chỉ làm
        /// rụng người đăng ký mới.
        ///
        /// @Email vẫn giữ: null và chuỗi rỗng đều qua được, nhưng nhập bậy
        /// thì vẫn bị chặn.
        @Email @Size(max = 320) String studentEmail
) {
}
