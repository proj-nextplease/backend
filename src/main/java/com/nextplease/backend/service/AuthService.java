package com.nextplease.backend.service;

import com.nextplease.backend.dto.request.LoginRequest;
import com.nextplease.backend.dto.response.LoginResponse;
import com.nextplease.backend.entity.AppUser;
import com.nextplease.backend.exception.AppException;
import com.nextplease.backend.repository.AppUserRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final SupabaseAdminService supabaseAdminService;
    private final AppUserRepository appUserRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AuthService(
            SupabaseAdminService supabaseAdminService,
            AppUserRepository appUserRepository,
            NamedParameterJdbcTemplate jdbcTemplate
    ) {
        this.supabaseAdminService = supabaseAdminService;
        this.appUserRepository = appUserRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        log.info("Processing login request for email: {}", request.email());

        // 1. Authenticate with Supabase to get the tokens
        Map<String, Object> authResult = supabaseAdminService.authenticateUser(
                request.email().trim().toLowerCase(),
                request.password()
        );

        String accessToken = (String) authResult.get("access_token");
        String refreshToken = (String) authResult.get("refresh_token");
        String tokenType = (String) authResult.get("token_type");

        long expiresIn = 3600;
        if (authResult.get("expires_in") instanceof Number num) {
            expiresIn = num.longValue();
        }

        // Extract Supabase User UUID
        @SuppressWarnings("unchecked")
        Map<String, Object> userMap = (Map<String, Object>) authResult.get("user");
        if (userMap == null || !userMap.containsKey("id")) {
            throw new AppException(HttpStatus.BAD_GATEWAY, "Supabase authentication response did not contain user information.");
        }

        UUID supabaseUserId = UUID.fromString((String) userMap.get("id"));

        // 2. Fetch User from Local DB with JIT fallback
        AppUser appUser = appUserRepository.findBySupabaseUserId(supabaseUserId)
                .orElseGet(() -> {
                    String email = (String) userMap.get("email");
                    if (email == null) {
                        email = request.email().trim().toLowerCase();
                    }
                    String normalizedEmail = email.trim().toLowerCase();

                    // Check if email already exists in local DB
                    List<UUID> existingIds = jdbcTemplate.queryForList("""
                            select id
                            from app_users
                            where lower(email) = :email
                            """, Map.of("email", normalizedEmail), UUID.class);

                    if (!existingIds.isEmpty()) {
                        UUID userId = existingIds.get(0);
                        log.info("Updating supabase_user_id for existing email: {} to {}", normalizedEmail, supabaseUserId);

                        jdbcTemplate.update("""
                                update app_users
                                set supabase_user_id = :supabaseUserId,
                                    updated_at = now()
                                where id = :id
                                """, Map.of(
                                "supabaseUserId", supabaseUserId,
                                "id", userId
                        ));

                        return appUserRepository.findById(userId)
                                .orElseThrow(() -> new AppException(HttpStatus.INTERNAL_SERVER_ERROR,
                                        "Không thể tải thông tin ứng viên sau khi đồng bộ ID."));
                    }

                    log.info("JIT Provisioning new local profile for Supabase user: {}", supabaseUserId);
                    String displayName = "Ứng viên";
                    if (userMap.get("user_metadata") instanceof Map<?, ?> metadata) {
                        if (metadata.get("display_name") instanceof String name) {
                            displayName = name;
                        } else if (metadata.get("full_name") instanceof String name) {
                            displayName = name;
                        }
                    }

                    return provisionLocalUser(supabaseUserId, normalizedEmail, displayName);
                });

        if (!"ACTIVE".equals(appUser.getStatus().name())) {
            throw new AppException(HttpStatus.FORBIDDEN, "Tài khoản của bạn hiện đang bị khóa hoặc ngưng hoạt động.");
        }

        // 3. Fetch User Roles
        List<String> rolesList = jdbcTemplate.queryForList("""
                select role_code
                from user_roles
                where user_id = :userId
                """, Map.of("userId", appUser.getId()), String.class);
        Set<String> roles = new HashSet<>(rolesList);

        // 4. Construct LoginResponse
        return new LoginResponse(
                accessToken,
                refreshToken,
                tokenType,
                expiresIn,
                new LoginResponse.UserDto(
                        appUser.getId(),
                        appUser.getEmail(),
                        appUser.getDisplayName(),
                        roles
                )
        );
    }

    private AppUser provisionLocalUser(UUID supabaseUserId, String email, String displayName) {
        UUID userId = UUID.randomUUID();

        jdbcTemplate.update("""
                insert into app_users (
                    id,
                    supabase_user_id,
                    email,
                    display_name,
                    status,
                    created_at,
                    updated_at
                )
                values (
                    :userId,
                    :supabaseUserId,
                    :email,
                    :displayName,
                    'ACTIVE',
                    now(),
                    now()
                )
                """, Map.of(
                "userId", userId,
                "supabaseUserId", supabaseUserId,
                "email", email,
                "displayName", displayName
        ));

        jdbcTemplate.update("""
                insert into user_roles (user_id, role_code)
                values (:userId, 'candidate_free')
                """, Map.of("userId", userId));

        jdbcTemplate.update("""
                insert into profiles (user_id, headline, visibility)
                values (:userId, 'Ứng viên nextplease', '{}'::jsonb)
                """, Map.of("userId", userId));

        jdbcTemplate.update("""
                insert into wallets (user_id, np_balance, locked_np_balance)
                values (:userId, 0, 0)
                """, Map.of("userId", userId));

        jdbcTemplate.update("""
                insert into audit_logs (actor_user_id, action, entity_type, entity_id, metadata)
                values (
                    :userId,
                    'candidate.jit_provisioned',
                    'app_user',
                    :userId,
                    jsonb_build_object('provider', 'supabase_login')
                )
                """, Map.of("userId", userId));

        return appUserRepository.findById(userId)
                .orElseThrow(() -> new AppException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Không thể khởi tạo thông tin ứng viên cục bộ. Vui lòng thử lại sau."));
    }
}
