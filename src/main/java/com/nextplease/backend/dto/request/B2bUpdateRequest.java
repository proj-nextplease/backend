package com.nextplease.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record B2bUpdateRequest(
        @NotBlank @Size(max = 200) String companyName,
        @NotBlank @Size(max = 50) String companyType, // SME, STARTUP, CLUB, etc.
        String description,
        String websiteUrl,
        String logoUrl,
        String documentUrl, // GPKD file or Founding Decision file
        String taxCode, // Required for corporate B2B, optional for Clubs
        @NotBlank @Size(max = 150) String representativeName,
        @NotBlank @Size(max = 20) String representativePhone,
        String schoolId, // Optional UUID string for Clubs
        String fanpageUrl, // Optional for Clubs
        String advisorContact // Optional JSON/text details
) {
}
