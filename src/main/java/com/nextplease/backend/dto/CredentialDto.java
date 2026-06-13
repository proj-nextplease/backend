package com.nextplease.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CredentialDto(
        String id,
        
        @NotBlank(message = "Tên bằng cấp / chứng chỉ không được để trống")
        String name,
        
        @NotBlank(message = "Đơn vị cấp không được để trống")
        String issuer,
        
        @NotBlank(message = "Thời gian cấp không được để trống")
        @Pattern(regexp = "^(0[1-9]|1[0-2])/[0-9]{2}$", message = "Thời gian cấp phải đúng định dạng MM/YY")
        String issuedAt,
        
        String fileName
) {
}
