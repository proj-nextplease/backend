package com.nextplease.backend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CandidateRegistrationOtpRequest(
        @NotNull UUID supabaseUserId,
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(max = 160) String displayName,
        @NotBlank @Email @Size(max = 320) String studentEmail
) {
}
