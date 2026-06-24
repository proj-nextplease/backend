package com.nextplease.backend.service;

import com.nextplease.backend.exception.AppException;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Calls the Supabase Auth Admin API to create users server-side.
 * This avoids the client-side rate limit on confirmation emails because
 * the admin endpoint can set email_confirm = true, skipping the email flow entirely.
 *
 * Requires env vars: SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY
 */
@Service
public class SupabaseAdminService {

    private static final Logger log = LoggerFactory.getLogger(SupabaseAdminService.class);

    private final RestClient restClient;
    private final boolean enabled;
    /** Whether the insecure mock-auth fallback is permitted (dev/local/test profiles only). */
    private final boolean mockAuthAllowed;

    public SupabaseAdminService(
            @Value("${app.supabase.url:}") String supabaseUrl,
            @Value("${app.supabase.service-role-key:}") String serviceRoleKey,
            Environment environment
    ) {
        this.enabled = !supabaseUrl.isBlank() && !serviceRoleKey.isBlank();
        this.mockAuthAllowed = environment.acceptsProfiles(Profiles.of("dev", "local", "test"));

        if (this.enabled) {
            this.restClient = RestClient.builder()
                    .baseUrl(supabaseUrl.replaceAll("/+$", ""))
                    .defaultHeader("apikey", serviceRoleKey)
                    .defaultHeader("Authorization", "Bearer " + serviceRoleKey)
                    .build();
        } else {
            this.restClient = null;
            log.warn("SupabaseAdminService is DISABLED – SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY is missing. "
                    + "User creation in Supabase will be skipped (dev/test mode).");
        }
    }

    /**
     * Fail fast: if Supabase is not configured, the service silently falls back to an
     * INSECURE mock that issues unsigned ("mock_signature") JWTs and accepts any login.
     * That must never happen outside dev/local/test. Refuse to start instead.
     */
    @PostConstruct
    void guardMockAuth() {
        if (!enabled && !mockAuthAllowed) {
            throw new IllegalStateException(
                    "SUPABASE_PROJECT_URL / SUPABASE_SERVICE_ROLE_KEY are not configured and the active "
                    + "Spring profile is not dev/local/test. Refusing to start with insecure mock authentication. "
                    + "Set the Supabase variables, or run with SPRING_PROFILES_ACTIVE=dev for local development.");
        }
        if (!enabled) {
            log.warn("⚠️  Mock authentication ENABLED (dev profile, Supabase not configured) – "
                    + "unsigned tokens are accepted. NEVER use this in production.");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Creates a Supabase auth user with pre-confirmed email (no confirmation email sent).
     *
     * @return the Supabase user UUID
     */
    public UUID createUser(String email, String password, Map<String, Object> userMetadata) {
        if (!enabled) {
            log.info("SupabaseAdminService disabled – returning random UUID for dev/test");
            return UUID.randomUUID();
        }

        try {
            Map<String, Object> body = Map.of(
                    "email", email,
                    "password", password,
                    "email_confirm", true,
                    "user_metadata", userMetadata != null ? userMetadata : Map.of()
            );

            Map<String, Object> response = restClient.post()
                    .uri("/auth/v1/admin/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        String responseBody = new String(res.getBody().readAllBytes());
                        log.error("Supabase Admin API 4xx: {} – {}", res.getStatusCode(), responseBody);

                        if (res.getStatusCode().value() == 422
                                && responseBody.contains("already been registered")) {
                            throw new AppException(HttpStatus.CONFLICT,
                                    "Email đã được đăng ký trên Supabase. Nếu bạn đã có tài khoản, hãy đăng nhập.");
                        }

                        throw new AppException(HttpStatus.BAD_GATEWAY,
                                "Supabase Auth không thể tạo tài khoản: " + responseBody);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        String responseBody = new String(res.getBody().readAllBytes());
                        log.error("Supabase Admin API 5xx: {} – {}", res.getStatusCode(), responseBody);
                        throw new AppException(HttpStatus.BAD_GATEWAY,
                                "Supabase Auth đang gặp sự cố. Vui lòng thử lại sau.");
                    })
                    .body(new ParameterizedTypeReference<>() {
                    });

            if (response == null || !response.containsKey("id")) {
                throw new AppException(HttpStatus.BAD_GATEWAY,
                        "Supabase trả về response không hợp lệ – thiếu user id.");
            }

            return UUID.fromString((String) response.get("id"));
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to create Supabase user for email {}", email, e);
            throw new AppException(HttpStatus.BAD_GATEWAY,
                    "Không thể tạo tài khoản trên Supabase Auth. Vui lòng thử lại sau.");
        }
    }

    /**
     * Authenticates a user with email and password via Supabase Auth API.
     *
     * @return response containing token details
     */
    public Map<String, Object> authenticateUser(String email, String password) {
        if (!enabled) {
            log.info("SupabaseAdminService disabled – returning mock authentication response for {}", email);
            UUID mockSub = UUID.randomUUID();
            String mockJwt = generateMockJwt(email, mockSub);
            return Map.of(
                    "access_token", mockJwt,
                    "refresh_token", "mock-refresh-token-" + UUID.randomUUID(),
                    "token_type", "bearer",
                    "expires_in", 3600L,
                    "user", Map.of(
                            "id", mockSub.toString(),
                            "email", email
                    )
            );
        }

        try {
            Map<String, Object> body = Map.of(
                    "email", email,
                    "password", password
            );

            return restClient.post()
                    .uri("/auth/v1/token?grant_type=password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        String responseBody = new String(res.getBody().readAllBytes());
                        log.error("Supabase Auth Token API 4xx: {} – {}", res.getStatusCode(), responseBody);

                        if (res.getStatusCode().value() == 400) {
                            throw new AppException(HttpStatus.BAD_REQUEST,
                                    "Email hoặc mật khẩu không chính xác.");
                        }

                        throw new AppException(HttpStatus.BAD_REQUEST,
                                "Đăng nhập thất bại: " + responseBody);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        String responseBody = new String(res.getBody().readAllBytes());
                        log.error("Supabase Auth Token API 5xx: {} – {}", res.getStatusCode(), responseBody);
                        throw new AppException(HttpStatus.BAD_GATEWAY,
                                "Supabase Auth đang gặp sự cố. Vui lòng thử lại sau.");
                    })
                    .body(new ParameterizedTypeReference<>() {
                    });
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to authenticate Supabase user for email {}", email, e);
            throw new AppException(HttpStatus.BAD_GATEWAY,
                    "Không thể xác thực thông tin đăng nhập trên Supabase. Vui lòng thử lại sau.");
        }
    }

    /**
     * Updates the app_metadata of a Supabase user to include their current roles from DB.
     * This ensures the Supabase JWT carries the correct roles so Spring Security
     * can authorize requests via SupabaseJwtAuthenticationConverter.
     * Called after every login to keep JWT roles in sync with DB.
     */
    public void updateUserAppMetadata(UUID supabaseUserId, java.util.Set<String> roles) {
        if (!enabled) {
            log.info("SupabaseAdminService disabled – skipping app_metadata update for {}", supabaseUserId);
            return;
        }
        try {
            Map<String, Object> body = Map.of(
                    "app_metadata", Map.of("roles", roles)
            );
            restClient.put()
                    .uri("/auth/v1/admin/users/{id}", supabaseUserId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        String responseBody = new String(res.getBody().readAllBytes());
                        log.error("Supabase Admin PUT user 4xx: {} – {}", res.getStatusCode(), responseBody);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        String responseBody = new String(res.getBody().readAllBytes());
                        log.error("Supabase Admin PUT user 5xx: {} – {}", res.getStatusCode(), responseBody);
                    })
                    .toBodilessEntity();
            log.info("Synced app_metadata roles {} for Supabase user {}", roles, supabaseUserId);
        } catch (Exception e) {
            // Non-fatal: login still succeeds; roles will be stale in JWT until next login
            log.warn("Failed to sync app_metadata for Supabase user {}: {}", supabaseUserId, e.getMessage());
        }
    }

    /**
     * Deletes a user from Supabase Auth. Used for rollback in transactional operations.
     */
    public void deleteUser(UUID supabaseUserId) {
        if (!enabled) {
            log.info("SupabaseAdminService disabled – skipping deleteUser for mock UUID: {}", supabaseUserId);
            return;
        }
        try {
            restClient.delete()
                    .uri("/auth/v1/admin/users/{id}", supabaseUserId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        String responseBody = new String(res.getBody().readAllBytes());
                        log.error("Supabase Admin DELETE user 4xx: {} – {}", res.getStatusCode(), responseBody);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        String responseBody = new String(res.getBody().readAllBytes());
                        log.error("Supabase Admin DELETE user 5xx: {} – {}", res.getStatusCode(), responseBody);
                    })
                    .toBodilessEntity();
            log.info("Deleted user from Supabase Auth: {}", supabaseUserId);
        } catch (Exception e) {
            log.error("Failed to delete user from Supabase Auth: {}", supabaseUserId, e);
        }
    }

    private String generateMockJwt(String email, UUID supabaseUserId) {
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payload = String.format(
                "{\"sub\":\"%s\",\"email\":\"%s\",\"app_metadata\":{\"provider\":\"email\",\"roles\":[\"candidate_free\"]},\"exp\":%d}",
                supabaseUserId,
                email,
                (System.currentTimeMillis() / 1000) + 3600
        );

        java.util.Base64.Encoder encoder = java.util.Base64.getUrlEncoder().withoutPadding();
        String encodedHeader = encoder.encodeToString(header.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String encodedPayload = encoder.encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        return encodedHeader + "." + encodedPayload + ".mock_signature";
    }
}
