package com.nextplease.backend.dto.response;

import java.util.Set;
import java.util.UUID;

public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserDto user
) {
    public record UserDto(
            UUID id,
            String email,
            String displayName,
            Set<String> roles
    ) {
    }
}
