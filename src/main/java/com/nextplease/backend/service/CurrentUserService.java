package com.nextplease.backend.service;

import com.nextplease.backend.dto.response.MeResponse;
import com.nextplease.backend.entity.AppUser;
import com.nextplease.backend.exception.ResourceNotFoundException;
import com.nextplease.backend.repository.AppUserRepository;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CurrentUserService {

    private final AppUserRepository appUserRepository;

    public CurrentUserService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Transactional(readOnly = true)
    public MeResponse getCurrentUser() {
        Jwt jwt = currentJwt();
        UUID supabaseUserId = UUID.fromString(jwt.getSubject());
        AppUser appUser = appUserRepository.findBySupabaseUserId(supabaseUserId)
                .orElseThrow(() -> new ResourceNotFoundException("App user profile has not been created"));

        return new MeResponse(
                appUser.getId(),
                appUser.getSupabaseUserId(),
                appUser.getEmail(),
                appUser.getStatus().name(),
                extractRoles(jwt)
        );
    }

    private Jwt currentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            return jwtAuthenticationToken.getToken();
        }

        throw new ResourceNotFoundException("Authenticated Supabase session is required");
    }

    private Set<String> extractRoles(Jwt jwt) {
        Object roles = jwt.getClaimAsMap("app_metadata") == null
                ? null
                : jwt.getClaimAsMap("app_metadata").get("roles");

        if (roles instanceof Iterable<?> values) {
            Set<String> result = new java.util.HashSet<>();
            for (Object role : values) {
                if (role instanceof String roleText) {
                    result.add(roleText);
                }
            }
            return result;
        }

        return Set.of();
    }
}
