package com.nextplease.backend.service;

import com.nextplease.backend.exception.AppException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
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

    public SupabaseAdminService(
            @Value("${app.supabase.url:}") String supabaseUrl,
            @Value("${app.supabase.service-role-key:}") String serviceRoleKey
    ) {
        this.enabled = !supabaseUrl.isBlank() && !serviceRoleKey.isBlank();

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
}
