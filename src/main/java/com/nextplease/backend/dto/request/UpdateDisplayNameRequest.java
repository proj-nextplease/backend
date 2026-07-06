package com.nextplease.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateDisplayNameRequest(
        @NotBlank(message = "Tên hiển thị không được để trống.")
        @Size(min = 2, max = 50, message = "Tên hiển thị phải từ 2 đến 50 ký tự.")
        @Pattern(
                regexp = "^(?=.*\\p{L})[\\p{L} .'-]+$",
                message = "Tên hiển thị chỉ được chứa chữ cái, khoảng trắng và các ký tự . ' -, không chứa số hoặc ký tự đặc biệt khác."
        )
        String displayName
) {
}
