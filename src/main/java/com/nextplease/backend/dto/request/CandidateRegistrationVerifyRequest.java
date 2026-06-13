package com.nextplease.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public record CandidateRegistrationVerifyRequest(
        @NotNull UUID registrationId,
        @NotBlank @Pattern(regexp = "\\d{6}", message = "must be a 6-digit OTP") String otp
) {
}
