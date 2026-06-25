package com.nextplease.backend.service;

import com.nextplease.backend.entity.AppUser;
import com.nextplease.backend.exception.ResourceNotFoundException;
import com.nextplease.backend.repository.AppUserRepository;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserJitProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(UserJitProvisioningService.class);

    private final AppUserRepository appUserRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public UserJitProvisioningService(
            AppUserRepository appUserRepository,
            NamedParameterJdbcTemplate jdbcTemplate
    ) {
        this.appUserRepository = appUserRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AppUser provisionLocalUserJit(UUID supabaseUserId, String email, String displayName, String authProvider) {
        UUID userId = UUID.randomUUID();
        log.info("JIT provisioning local database records in new transaction for Supabase user: {} (email: {}, provider: {})", supabaseUserId, email, authProvider);

        jdbcTemplate.update("""
                insert into app_users (
                    id,
                    supabase_user_id,
                    email,
                    display_name,
                    status,
                    auth_provider,
                    created_at,
                    updated_at
                )
                values (
                    :userId,
                    :supabaseUserId,
                    :email,
                    :displayName,
                    'ACTIVE',
                    :authProvider,
                    now(),
                    now()
                )
                """, Map.of(
                "userId", userId,
                "supabaseUserId", supabaseUserId,
                "email", email.toLowerCase().trim(),
                "displayName", displayName,
                "authProvider", authProvider
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
                    jsonb_build_object('provider', :authProvider)
                )
                """, Map.of(
                "userId", userId,
                "authProvider", authProvider
        ));

        return appUserRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không thể tìm thấy thông tin ứng viên vừa được khởi tạo JIT."));
    }
}
