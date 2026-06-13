package com.nextplease.backend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CandidateRegistrationOtpRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 6, max = 25)
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
                message = "Password must contain at least one lowercase, one uppercase, and one digit"
        )
        String password,
        @NotBlank @Size(max = 160) String displayName,
        @NotBlank @Email @Size(max = 320) String studentEmail
) {
}
