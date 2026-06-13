package com.nextplease.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextplease.backend.dto.response.MeResponse;
import com.nextplease.backend.entity.AppUser;
import com.nextplease.backend.exception.ResourceNotFoundException;
import com.nextplease.backend.repository.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CurrentUserService {

    private static final Logger log = LoggerFactory.getLogger(CurrentUserService.class);

    private final AppUserRepository appUserRepository;
    private final ObjectMapper objectMapper;

    public CurrentUserService(
            AppUserRepository appUserRepository,
            ObjectMapper objectMapper
    ) {
        this.appUserRepository = appUserRepository;
        this.objectMapper = objectMapper;
    }

    private HttpServletRequest getCurrentRequest() {
        org.springframework.web.context.request.RequestAttributes attrs = 
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes servletAttrs) {
            return servletAttrs.getRequest();
        }
        return null;
    }

    @Transactional(readOnly = true)
    public MeResponse getCurrentUser() {
        UUID supabaseUserId = resolveSupabaseUserId();
        AppUser appUser = appUserRepository.findBySupabaseUserId(supabaseUserId)
                .orElseThrow(() -> new ResourceNotFoundException("App user profile has not been created"));

        return new MeResponse(
                appUser.getId(),
                appUser.getSupabaseUserId(),
                appUser.getEmail(),
                appUser.getStatus().name(),
                extractRolesFromToken()
        );
    }

    private UUID resolveSupabaseUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            return UUID.fromString(jwtAuthenticationToken.getToken().getSubject());
        }

        // Fallback for dev mode where APP_SECURITY_JWT_ENABLED = false
        HttpServletRequest request = getCurrentRequest();
        if (request != null) {
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                try {
                    String[] parts = token.split("\\.");
                    if (parts.length >= 2) {
                        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]));
                        Map<?, ?> claims = objectMapper.readValue(payloadJson, Map.class);
                        Object sub = claims.get("sub");
                        if (sub instanceof String subStr) {
                            return UUID.fromString(subStr);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse JWT token manually from header: {}", e.getMessage());
                }
            }
        }

        throw new ResourceNotFoundException("Authenticated Supabase session is required");
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractRolesFromToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            Jwt jwt = jwtAuthenticationToken.getToken();
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
        }

        // Fallback for dev mode where APP_SECURITY_JWT_ENABLED = false
        HttpServletRequest request = getCurrentRequest();
        if (request != null) {
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                try {
                    String[] parts = token.split("\\.");
                    if (parts.length >= 2) {
                        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]));
                        Map<?, ?> claims = objectMapper.readValue(payloadJson, Map.class);
                        Map<?, ?> appMetadata = (Map<?, ?>) claims.get("app_metadata");
                        if (appMetadata != null) {
                            Object roles = appMetadata.get("roles");
                            if (roles instanceof Iterable<?> values) {
                                Set<String> result = new java.util.HashSet<>();
                                for (Object role : values) {
                                    if (role instanceof String roleText) {
                                        result.add(roleText);
                                    }
                                }
                                return result;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to extract roles from token manually: {}", e.getMessage());
                }
            }
        }

        return Set.of();
    }
}

