package com.nextplease.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CandidateRegistrationVerifyRequest(
        @NotNull UUID registrationId,
        @NotBlank @Pattern(regexp = "\\d{6}", message = "must be a 6-digit OTP") String otp,
        @NotBlank @Size(min = 6, max = 25) String password
) {
}
