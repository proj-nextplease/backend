package com.nextplease.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record ExperienceDto(
        String id,
        
        @NotBlank(message = "Vai trò không được để trống")
        String title,
        
        @NotBlank(message = "Tổ chức không được để trống")
        String organization,
        
        @NotBlank(message = "Mô tả không được để trống")
        String detail
) {
}
