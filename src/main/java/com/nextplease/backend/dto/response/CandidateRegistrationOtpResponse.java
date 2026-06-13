package com.nextplease.backend.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CandidateRegistrationOtpResponse(
        UUID registrationId,
        String email,
        OffsetDateTime expiresAt,
        String devOtp
) {
}
