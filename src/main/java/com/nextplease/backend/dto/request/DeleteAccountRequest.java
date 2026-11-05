package com.nextplease.backend.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Xác nhận xoá tài khoản bằng cách gõ lại email đăng nhập.
 *
 * Không dùng mật khẩu như {@link DeactivateAccountRequest}: người đăng nhập
 * bằng Google/GitHub/Facebook không có mật khẩu, mà Google Play bắt buộc
 * MỌI người dùng đều phải tự xoá được tài khoản.
 */
public record DeleteAccountRequest(
        @NotBlank(message = "Vui lòng nhập email để xác nhận.") String confirmEmail
) {
}
