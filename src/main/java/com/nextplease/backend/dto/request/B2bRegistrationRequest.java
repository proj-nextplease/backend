package com.nextplease.backend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record B2bRegistrationRequest(
        // Representative credentials
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 6, max = 25)
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
                message = "Password must contain at least one lowercase, one uppercase, and one digit"
        )
        String password,
        @NotBlank @Size(max = 160) String displayName,
        @NotBlank @Size(max = 150) String representativeName,
        @NotBlank @Size(max = 20) String representativePhone,

        // B2B Organization Details
        @NotBlank @Size(max = 200) String companyName,
        @NotBlank @Size(max = 50) String companyType, // SME, STARTUP, CLUB, etc.
        String description,
        String websiteUrl,
        String logoUrl,
        String documentUrl, // GPKD file or Founding Decision file

        // Conditional Verification fields
        String taxCode, // Required for corporate B2B, optional for Clubs
        String schoolId, // Optional UUID string for Clubs
        String fanpageUrl, // Optional for Clubs
        String advisorContact // Optional JSON/text details
) {
}
