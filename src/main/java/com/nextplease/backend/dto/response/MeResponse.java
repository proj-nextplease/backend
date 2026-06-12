package com.nextplease.backend.dto.response;

import java.util.Set;
import java.util.UUID;

public record MeResponse(
        UUID appUserId,
        UUID supabaseUserId,
        String email,
        String status,
        Set<String> roles
) {
}
