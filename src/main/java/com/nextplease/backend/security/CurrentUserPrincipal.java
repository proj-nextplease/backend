package com.nextplease.backend.security;

import java.util.Set;
import java.util.UUID;

public record CurrentUserPrincipal(
        UUID supabaseUserId,
        String email,
        Set<String> roles
) {
}
