package com.nextplease.backend.dto.request;

import jakarta.validation.constraints.NotBlank;

public record DeactivateAccountRequest(
        @NotBlank(message = "Vui lòng nhập mật khẩu để xác nhận.") String password
) {
}
