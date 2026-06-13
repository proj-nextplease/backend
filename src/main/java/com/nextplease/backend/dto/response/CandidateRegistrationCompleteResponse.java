package com.nextplease.backend.dto.response;

import java.util.UUID;

public record CandidateRegistrationCompleteResponse(
        UUID userId,
        UUID supabaseUserId,
        UUID profileId,
        String email,
        String displayName,
        String role,
        String status
) {
}
