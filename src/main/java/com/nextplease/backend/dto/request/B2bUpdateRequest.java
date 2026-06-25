package com.nextplease.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record B2bUpdateRequest(
        @NotBlank @Size(max = 200) String companyName,
        @NotBlank @Size(max = 50) String companyType, // SME, STARTUP, CLUB, etc.
        @NotBlank @Size(min = 30, message = "Mô tả tổ chức phải có tối thiểu 30 ký tự") String description,
        String websiteUrl,
        String logoUrl,
        String documentUrl, // GPKD file or Founding Decision file
        String taxCode, // Required for corporate B2B, optional for Clubs
        @NotBlank @Size(max = 150) String representativeName,
        @NotBlank
        @Pattern(regexp = "^[0-9]{10,11}$", message = "Số điện thoại chỉ được chứa chữ số và có độ dài từ 10 đến 11 ký tự")
        String representativePhone,
        String schoolId, // Optional UUID string for Clubs
        String fanpageUrl, // Optional for Clubs
        String advisorContact // Optional JSON/text details
) {
}
