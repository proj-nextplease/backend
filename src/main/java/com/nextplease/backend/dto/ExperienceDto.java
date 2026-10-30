package com.nextplease.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record ExperienceDto(
        String id,
        
        @NotBlank(message = "Vai trò không được để trống")
        String title,
        
        @NotBlank(message = "Tổ chức không được để trống")
        String organization,
        
        @NotBlank(message = "Mô tả không được để trống")
        String detail,
        
        String startDate,

        String endDate,

        java.util.List<String> proofImages,

        String category,

        String roleLevel,

        String proofLink,

        // Verified-portfolio surface: whether this item was approved by an admin,
        // and the EXP/RS it is worth. Populated by ProfileService for display only;
        // ignored on inbound requests.
        Boolean verified,

        Integer expReward,

        Integer rsReward,

        String verificationStatus
) {
    public ExperienceDto(
            String id,
            String title,
            String organization,
            String detail,
            String startDate,
            String endDate,
            java.util.List<String> proofImages,
            String category,
            String roleLevel,
            String proofLink,
            Boolean verified,
            Integer expReward,
            Integer rsReward) {
        this(id, title, organization, detail, startDate, endDate, proofImages, category, roleLevel, proofLink, verified, expReward, rsReward,
                verified != null && verified ? "APPROVED" : "PENDING");
    }
}
