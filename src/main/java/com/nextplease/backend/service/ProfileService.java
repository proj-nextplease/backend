package com.nextplease.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextplease.backend.dto.CredentialDto;
import com.nextplease.backend.dto.ExperienceDto;
import com.nextplease.backend.dto.request.PortfolioRequest;
import com.nextplease.backend.dto.response.PortfolioResponse;
import com.nextplease.backend.dto.response.PublicPortfolioResponse;
import com.nextplease.backend.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ReputationService reputationService;
    private final ConfigService configService;
    private final UserJitProvisioningService userJitProvisioningService;
    private final boolean jwtEnabled;

    public ProfileService(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ReputationService reputationService,
            ConfigService configService,
            UserJitProvisioningService userJitProvisioningService,
            @Value("${app.security.jwt.enabled:false}") boolean jwtEnabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.reputationService = reputationService;
        this.configService = configService;
        this.userJitProvisioningService = userJitProvisioningService;
        this.jwtEnabled = jwtEnabled;
    }

    private HttpServletRequest getCurrentRequest() {
        org.springframework.web.context.request.RequestAttributes attrs = 
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes servletAttrs) {
            return servletAttrs.getRequest();
        }
        return null;
    }

    private UUID getCurrentUserSupabaseId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            return UUID.fromString(jwtAuthenticationToken.getToken().getSubject());
        }

        if (jwtEnabled) {
            throw new ResourceNotFoundException("Yêu cầu phiên đăng nhập Supabase đã xác thực.");
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

        throw new ResourceNotFoundException("Yêu cầu phiên đăng nhập Supabase đã xác thực.");
    }


    public Map<String, Object> getPublicProfile(UUID userId) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                    select u.display_name as name, u.email,
                           p.headline, p.major, p.avatar_url,
                           p.reputation_score, p.current_level, p.total_exp,
                           p.credentials::text as credentials, p.bio,
                           s.name as school
                    from app_users u
                    join profiles p on p.user_id = u.id
                    left join schools s on s.id = p.school_id
                    where u.id = :userId
                    """, Map.of("userId", userId));
            return row;
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new com.nextplease.backend.exception.ResourceNotFoundException("Không tìm thấy hồ sơ ứng viên.");
        }
    }

    @Transactional
    public PortfolioResponse getPortfolio() {
        UUID supabaseUserId = getCurrentUserSupabaseId();

        // 1. Get user details from app_users and wallet
        Map<String, Object> user;
        try {
            user = jdbcTemplate.queryForMap("""
                    select u.id, u.display_name, u.email, coalesce(w.np_balance, 0) as np_balance
                    from app_users u
                    left join wallets w on w.user_id = u.id
                    where u.supabase_user_id = :supabaseUserId
                    """, Map.of("supabaseUserId", supabaseUserId));
        } catch (EmptyResultDataAccessException e) {
            // User does not exist locally -> JIT Provisioning (OAuth flow fallback)
            Map<String, Object> claims = getJwtClaims();
            Object emailObj = claims.get("email");
            String email = emailObj instanceof String ? (String) emailObj : "";
            if (email.isBlank()) {
                throw new ResourceNotFoundException("Tài khoản người dùng chưa được khởi tạo.");
            }

            String displayName = "";
            Object userMetadata = claims.get("user_metadata");
            if (userMetadata instanceof Map<?, ?> metadataMap) {
                Object fullName = metadataMap.get("full_name");
                if (fullName instanceof String fullNameStr) {
                    displayName = fullNameStr;
                }
            }
            if (displayName.isBlank()) {
                Object nameObj = claims.get("name");
                if (nameObj instanceof String nameStr) {
                    displayName = nameStr;
                }
            }
            if (displayName.isBlank()) {
                displayName = email.split("@")[0];
            }
            
            String provider = "supabase";
            Object appMetadata = claims.get("app_metadata");
            if (appMetadata instanceof Map<?, ?> appMetadataMap) {
                Object providerObj = appMetadataMap.get("provider");
                if (providerObj instanceof String providerStr && !providerStr.isBlank()) {
                    provider = providerStr;
                }
            }

            try {
                com.nextplease.backend.entity.AppUser provisioned = userJitProvisioningService.provisionLocalUserJit(supabaseUserId, email, displayName, provider);
                user = Map.of(
                        "id", provisioned.getId(),
                        "display_name", provisioned.getDisplayName(),
                        "email", provisioned.getEmail(),
                        "np_balance", 0L
                );
            } catch (org.springframework.dao.DataIntegrityViolationException dive) {
                log.info("Concurrent JIT provisioning detected in ProfileService.getPortfolio for user {}. Querying existing user details.", supabaseUserId);
                user = jdbcTemplate.queryForMap("""
                        select u.id, u.display_name, u.email, coalesce(w.np_balance, 0) as np_balance
                        from app_users u
                        left join wallets w on w.user_id = u.id
                        where u.supabase_user_id = :supabaseUserId
                        """, Map.of("supabaseUserId", supabaseUserId));
            }
        }

        UUID userId = (UUID) user.get("id");
        String displayName = (String) user.get("display_name");

        // 2. Get profile details. If not exist, dynamically create one
        Map<String, Object> profile;
        try {
            profile = jdbcTemplate.queryForMap("""
                    select id, headline, bio, location, school_id, avatar_config, credentials, onboarding_completed, reputation_score, total_exp, current_level, selected_theme, theme_unlocked, open_to_work, social_links
                    from profiles where user_id = :userId
                    """, Map.of("userId", userId));
        } catch (EmptyResultDataAccessException e) {
            log.info("Profile not found for user {}, creating default profile", userId);
            UUID profileId = UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into profiles (id, user_id, headline, visibility)
                    values (:id, :userId, 'Ứng viên nextplease', '{}'::jsonb)
                    """, Map.of("id", profileId, "userId", userId));

            profile = jdbcTemplate.queryForMap("""
                    select id, headline, bio, location, school_id, avatar_config, credentials, onboarding_completed, reputation_score, total_exp, current_level, selected_theme, theme_unlocked, open_to_work, social_links
                    from profiles where user_id = :userId
                    """, Map.of("userId", userId));
        }

        UUID profileId = (UUID) profile.get("id");
        String headline = (String) profile.get("headline");
        String bio = (String) profile.get("bio");
        String location = (String) profile.get("location");
        UUID schoolId = (UUID) profile.get("school_id");

        // 3. Get school name
        String schoolName = "";
        if (schoolId != null) {
            try {
                schoolName = jdbcTemplate.queryForObject("""
                        select name from schools where id = :schoolId
                        """, Map.of("schoolId", schoolId), String.class);
            } catch (EmptyResultDataAccessException ignored) {
            }
        }

        // 4. Parse JSON columns
        Map<String, Object> avatarConfig = parseJsonMap(getJsonString(profile.get("avatar_config")));
        List<CredentialDto> credentials = parseCredentialsJson(getJsonString(profile.get("credentials")));

        // 5. Get skills
        List<String> skills = jdbcTemplate.query("""
                select s.name from skills s
                join profile_skills ps on ps.skill_id = s.id
                where ps.profile_id = :profileId
                """, Map.of("profileId", profileId), (rs, rowNum) -> rs.getString("name"));

        // 6. Get experiences
        List<ExperienceDto> experiences = jdbcTemplate.query("""
                select id, project_name, position, description, started_at, ended_at, proof_images::text as proof_images,
                       category, role_level, proof_link, verification_status from experiences
                where profile_id = :profileId
                order by created_at asc
                """, Map.of("profileId", profileId), (rs, rowNum) -> new ExperienceDto(
                        rs.getString("id"),
                        rs.getString("position"),
                        rs.getString("project_name"),
                        rs.getString("description"),
                        formatMmYy(rs.getDate("started_at")),
                        formatMmYy(rs.getDate("ended_at")),
                        deserializeImages(rs.getString("proof_images")),
                        rs.getString("category"),
                        rs.getString("role_level"),
                        rs.getString("proof_link"),
                        "APPROVED".equals(rs.getString("verification_status")),
                        ExperienceRewards.expFor(rs.getString("category")),
                        ExperienceRewards.rsFor(rs.getString("role_level"))
                ));

        boolean onboardingCompleted = profile.get("onboarding_completed") != null && (Boolean) profile.get("onboarding_completed");
        int reputationScore = profile.get("reputation_score") != null ? ((Number) profile.get("reputation_score")).intValue() : 0;
        long totalExp = profile.get("total_exp") != null ? ((Number) profile.get("total_exp")).longValue() : 0L;
        int currentLevel = profile.get("current_level") != null ? ((Number) profile.get("current_level")).intValue() : 1;
        long npBalance = user.get("np_balance") != null ? ((Number) user.get("np_balance")).longValue() : 0L;
        String selectedTheme = profile.get("selected_theme") != null ? (String) profile.get("selected_theme") : "DEFAULT";
        boolean themeUnlocked = profile.get("theme_unlocked") != null && (Boolean) profile.get("theme_unlocked");
        boolean openToWork = profile.get("open_to_work") != null && (Boolean) profile.get("open_to_work");
        Map<String, Object> socialLinks = parseJsonMap(getJsonString(profile.get("social_links")));

        return new PortfolioResponse(
                displayName,
                headline,
                schoolName,
                location,
                bio,
                skills,
                avatarConfig,
                experiences,
                credentials,
                onboardingCompleted,
                reputationScore,
                totalExp,
                currentLevel,
                npBalance,
                selectedTheme,
                themeUnlocked,
                openToWork,
                socialLinks
        );
    }

    public PublicPortfolioResponse getPortfolioByUserId(UUID userId) {
        Map<String, Object> userRow;
        try {
            // Public view: never read the wallet — npBalance is private.
            userRow = jdbcTemplate.queryForMap("""
                    select u.display_name
                    from app_users u
                    where u.id = :userId
                    """, Map.of("userId", userId));
        } catch (EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Không tìm thấy người dùng.");
        }

        String displayName = (String) userRow.get("display_name");

        Map<String, Object> profile;
        try {
            profile = jdbcTemplate.queryForMap("""
                    select id, headline, bio, location, school_id, avatar_config, credentials::text as credentials,
                           onboarding_completed, reputation_score, total_exp, current_level, selected_theme, theme_unlocked,
                           open_to_work, social_links::text as social_links
                    from profiles where user_id = :userId
                    """, Map.of("userId", userId));
        } catch (EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Không tìm thấy hồ sơ ứng viên.");
        }

        UUID profileId = (UUID) profile.get("id");
        String headline = (String) profile.get("headline");
        String bio = (String) profile.get("bio");
        String location = (String) profile.get("location");
        UUID schoolId = (UUID) profile.get("school_id");

        String schoolName = "";
        if (schoolId != null) {
            try {
                schoolName = jdbcTemplate.queryForObject("""
                        select name from schools where id = :schoolId
                        """, Map.of("schoolId", schoolId), String.class);
            } catch (EmptyResultDataAccessException ignored) {}
        }

        Map<String, Object> avatarConfig = parseJsonMap(getJsonString(profile.get("avatar_config")));
        // Public view: strip the uploaded certificate file (fileData) and its name —
        // these are private documents (e.g. CV/credential PDFs) that must not be exposed
        // to strangers. Keep only the displayable metadata (name, issuer, issuedAt).
        List<CredentialDto> credentials = parseCredentialsJson(getJsonString(profile.get("credentials"))).stream()
                .map(c -> new CredentialDto(c.id(), c.name(), c.issuer(), c.issuedAt(), null, null, null))
                .toList();

        List<String> skills = jdbcTemplate.query("""
                select s.name from skills s
                join profile_skills ps on ps.skill_id = s.id
                where ps.profile_id = :profileId
                """, Map.of("profileId", profileId), (rs, rowNum) -> rs.getString("name"));

        // Public view shows only verified (APPROVED) experiences — never PENDING/REJECTED proof.
        List<ExperienceDto> experiences = jdbcTemplate.query("""
                select id, project_name, position, description, started_at, ended_at, proof_images::text as proof_images,
                       category, role_level, proof_link from experiences
                where profile_id = :profileId
                  and verification_status = 'APPROVED'
                order by created_at asc
                """, Map.of("profileId", profileId), (rs, rowNum) -> new ExperienceDto(
                        rs.getString("id"),
                        rs.getString("position"),
                        rs.getString("project_name"),
                        rs.getString("description"),
                        formatMmYy(rs.getDate("started_at")),
                        formatMmYy(rs.getDate("ended_at")),
                        deserializeImages(rs.getString("proof_images")),
                        rs.getString("category"),
                        rs.getString("role_level"),
                        rs.getString("proof_link"),
                        true,
                        ExperienceRewards.expFor(rs.getString("category")),
                        ExperienceRewards.rsFor(rs.getString("role_level"))
                ));

        int reputationScore = profile.get("reputation_score") != null ? ((Number) profile.get("reputation_score")).intValue() : 0;
        long totalExp = profile.get("total_exp") != null ? ((Number) profile.get("total_exp")).longValue() : 0L;
        int currentLevel = profile.get("current_level") != null ? ((Number) profile.get("current_level")).intValue() : 1;
        String selectedTheme = profile.get("selected_theme") != null ? (String) profile.get("selected_theme") : "DEFAULT";
        boolean themeUnlocked = profile.get("theme_unlocked") != null && (Boolean) profile.get("theme_unlocked");
        boolean openToWork = profile.get("open_to_work") != null && (Boolean) profile.get("open_to_work");
        Map<String, Object> socialLinks = parseJsonMap(getJsonString(profile.get("social_links")));

        return new PublicPortfolioResponse(
                displayName, headline, schoolName, location, bio,
                skills, avatarConfig, experiences, credentials,
                reputationScore, totalExp, currentLevel,
                selectedTheme, themeUnlocked,
                openToWork, socialLinks
        );
    }

    @Transactional
    public void updatePortfolio(PortfolioRequest request, boolean isDraft) {
        UUID supabaseUserId = getCurrentUserSupabaseId();

        // 1. Find user ID
        UUID userId;
        try {
            userId = jdbcTemplate.queryForObject("""
                    select id from app_users where supabase_user_id = :supabaseUserId
                    """, Map.of("supabaseUserId", supabaseUserId), UUID.class);
        } catch (EmptyResultDataAccessException e) {
            // User does not exist locally -> JIT Provisioning (OAuth flow fallback)
            Map<String, Object> claims = getJwtClaims();
            Object emailObj = claims.get("email");
            String email = emailObj instanceof String ? (String) emailObj : "";
            if (email.isBlank()) {
                throw new ResourceNotFoundException("Tài khoản người dùng chưa được khởi tạo.");
            }

            String displayName = "";
            Object userMetadata = claims.get("user_metadata");
            if (userMetadata instanceof Map<?, ?> metadataMap) {
                Object fullName = metadataMap.get("full_name");
                if (fullName instanceof String fullNameStr) {
                    displayName = fullNameStr;
                }
            }
            if (displayName.isBlank()) {
                Object nameObj = claims.get("name");
                if (nameObj instanceof String nameStr) {
                    displayName = nameStr;
                }
            }
            if (displayName.isBlank()) {
                displayName = email.split("@")[0];
            }
            
            String provider = "supabase";
            Object appMetadata = claims.get("app_metadata");
            if (appMetadata instanceof Map<?, ?> appMetadataMap) {
                Object providerObj = appMetadataMap.get("provider");
                if (providerObj instanceof String providerStr && !providerStr.isBlank()) {
                    provider = providerStr;
                }
            }

            Map<String, Object> newUser;
            try {
                com.nextplease.backend.entity.AppUser provisioned = userJitProvisioningService.provisionLocalUserJit(supabaseUserId, email, displayName, provider);
                newUser = Map.of(
                        "id", provisioned.getId(),
                        "display_name", provisioned.getDisplayName(),
                        "email", provisioned.getEmail(),
                        "np_balance", 0L
                );
            } catch (org.springframework.dao.DataIntegrityViolationException dive) {
                log.info("Concurrent JIT provisioning detected in ProfileService.updatePortfolio for user {}. Fetching existing user details.", supabaseUserId);
                newUser = jdbcTemplate.queryForMap("""
                        select u.id, u.display_name, u.email, coalesce(w.np_balance, 0) as np_balance
                        from app_users u
                        left join wallets w on w.user_id = u.id
                        where u.supabase_user_id = :supabaseUserId
                        """, Map.of("supabaseUserId", supabaseUserId));
            }
            userId = (UUID) newUser.get("id");
        }

        // 2. Find profile ID or create if not exists; capture current onboarding state
        UUID profileId;
        boolean wasOnboardingCompleted;
        try {
            Map<String, Object> profileRow = jdbcTemplate.queryForMap("""
                    select id, onboarding_completed from profiles where user_id = :userId
                    """, Map.of("userId", userId));
            profileId = (UUID) profileRow.get("id");
            wasOnboardingCompleted = Boolean.TRUE.equals(profileRow.get("onboarding_completed"));
        } catch (EmptyResultDataAccessException e) {
            profileId = UUID.randomUUID();
            wasOnboardingCompleted = false;
            jdbcTemplate.update("""
                    insert into profiles (id, user_id, headline, visibility)
                    values (:id, :userId, 'Ứng viên nextplease', '{}'::jsonb)
                    """, Map.of("id", profileId, "userId", userId));
        }

        // 3. Update display name in app_users
        jdbcTemplate.update("""
                update app_users set display_name = :displayName, updated_at = now() where id = :userId
                """, Map.of("displayName", request.name().trim(), "userId", userId));

        // 4. Resolve School ID
        UUID schoolId = null;
        if (request.school() != null && !request.school().trim().isEmpty()) {
            String cleanSchool = request.school().trim();
            try {
                schoolId = jdbcTemplate.queryForObject("""
                        select id from schools where lower(name) = lower(:schoolName)
                        """, Map.of("schoolName", cleanSchool), UUID.class);
            } catch (EmptyResultDataAccessException e) {
                // Create new school
                schoolId = UUID.randomUUID();
                jdbcTemplate.update("""
                        insert into schools (id, name, verification_status, created_at, updated_at)
                        values (:id, :name, 'ACTIVE', now(), now())
                        """, Map.of("id", schoolId, "name", cleanSchool));
            }
        }

        // 5. Serialize JSON columns
        String avatarConfigJson = serializeJson(request.avatar() != null ? request.avatar() : Map.of());
        String credentialsJson = serializeJson(request.credentials() != null ? request.credentials() : List.of());
        String socialLinksJson = serializeJson(request.socialLinks() != null ? request.socialLinks() : Map.of());

        // 6. Update profile columns
        jdbcTemplate.update("""
                update profiles set
                    headline = :headline,
                    bio = :bio,
                    location = :location,
                    school_id = :schoolId,
                    avatar_config = cast(:avatarConfig as jsonb),
                    credentials = cast(:credentials as jsonb),
                    open_to_work = :openToWork,
                    social_links = cast(:socialLinks as jsonb),
                    onboarding_completed = case when :isDraft = true then onboarding_completed else true end,
                    updated_at = now()
                where id = :profileId
                """, new MapSqlParameterSource()
                .addValue("headline", request.headline())
                .addValue("bio", request.bio())
                .addValue("location", request.location())
                .addValue("schoolId", schoolId)
                .addValue("avatarConfig", avatarConfigJson)
                .addValue("credentials", credentialsJson)
                .addValue("openToWork", request.openToWork() != null && request.openToWork())
                .addValue("socialLinks", socialLinksJson)
                .addValue("profileId", profileId)
                .addValue("isDraft", isDraft));

        // 7. Update skills
        updateProfileSkills(profileId, request.skills());

        // 8. Update experiences
        updateExperiences(profileId, request.experiences());

        // 9. +5 RS once when onboarding flips from false → true
        if (!isDraft && !wasOnboardingCompleted) {
            reputationService.addReputation(profileId, configService.getInt("rs_onboarding", 5), "ONBOARDING_COMPLETED", "profile", profileId);
        }
    }

    private void updateProfileSkills(UUID profileId, List<String> skillNames) {
        if (skillNames == null || skillNames.isEmpty()) {
            jdbcTemplate.update("delete from profile_skills where profile_id = :profileId", Map.of("profileId", profileId));
            return;
        }

        List<UUID> skillIds = new ArrayList<>();
        for (String skillName : skillNames) {
            if (skillName.trim().isEmpty()) continue;
            String cleanSkill = skillName.trim();
            String normalized = cleanSkill.toLowerCase();
            UUID skillId;
            try {
                skillId = jdbcTemplate.queryForObject("""
                        select id from skills where lower(name) = :normalized
                        """, Map.of("normalized", normalized), UUID.class);
            } catch (EmptyResultDataAccessException e) {
                skillId = UUID.randomUUID();
                jdbcTemplate.update("""
                        insert into skills (id, name, normalized_name)
                        values (:id, :name, :normalized)
                        """, Map.of("id", skillId, "name", cleanSkill, "normalized", normalized));
            }
            skillIds.add(skillId);
        }

        if (skillIds.isEmpty()) {
            jdbcTemplate.update("delete from profile_skills where profile_id = :profileId", Map.of("profileId", profileId));
            return;
        }

        // Delete skills not in the update request
        jdbcTemplate.update("""
                delete from profile_skills where profile_id = :profileId and skill_id not in (:skillIds)
                """, Map.of("profileId", profileId, "skillIds", skillIds));

        // Insert / update skills
        for (UUID skillId : skillIds) {
            jdbcTemplate.update("""
                    insert into profile_skills (profile_id, skill_id, proficiency_level)
                    values (:profileId, :skillId, 'BEGINNER')
                    on conflict (profile_id, skill_id) do nothing
                    """, Map.of("profileId", profileId, "skillId", skillId));
        }
    }

    /**
     * Upsert experiences for a profile WITHOUT disturbing already-reviewed rows.
     *
     * Why not delete-all + re-insert: experiences are the source of awarded EXP/RS
     * (idempotency is keyed on the row id). Re-creating rows with new ids on every
     * portfolio save (incl. draft autosave) would (a) reset APPROVED rows to PENDING,
     * (b) let the same achievement be re-approved → double-count EXP/RS, and
     * (c) wipe experiences submitted via the separate Credential form. So:
     *   - existing row (id is a known UUID) → UPDATE content only; never touch
     *     verification_status / verified_by / points.
     *   - new row → INSERT as PENDING.
     *   - removed row → DELETE only if still PENDING; reviewed rows are kept so
     *     awarded points/history stay consistent.
     */
    private void updateExperiences(UUID profileId, List<ExperienceDto> experiences) {
        java.util.List<Map<String, Object>> existingRows = jdbcTemplate.queryForList(
                "select id, verification_status, source from experiences where profile_id = :profileId",
                Map.of("profileId", profileId));
        Map<UUID, String> existingStatus = new HashMap<>();
        // Rows submitted via the dashboard "Nộp minh chứng" form are tagged
        // source='CREDENTIAL'. Track them so a Portfolio save can never delete
        // them — even if the client sends a stale list that omits them.
        java.util.Set<UUID> credentialOwned = new java.util.HashSet<>();
        for (Map<String, Object> row : existingRows) {
            UUID id = (UUID) row.get("id");
            existingStatus.put(id, (String) row.get("verification_status"));
            if ("CREDENTIAL".equals(row.get("source"))) {
                credentialOwned.add(id);
            }
        }

        java.util.Set<UUID> keptIds = new java.util.HashSet<>();
        java.util.List<ExperienceDto> list = experiences == null ? java.util.List.of() : experiences;

        for (ExperienceDto exp : list) {
            if ((exp.title() == null || exp.title().trim().isEmpty()) &&
                (exp.organization() == null || exp.organization().trim().isEmpty())) {
                continue; // Skip empty records
            }
            LocalDate startDate = parseMmYy(exp.startDate());
            LocalDate endDate = parseMmYy(exp.endDate());
            UUID existingId = tryParseUuid(exp.id());

            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("profileId", profileId)
                    .addValue("organization", exp.organization() == null ? "" : exp.organization().trim())
                    .addValue("title", exp.title() == null ? "" : exp.title().trim())
                    .addValue("description", exp.detail() == null ? "" : exp.detail().trim())
                    .addValue("startDate", startDate != null ? java.sql.Date.valueOf(startDate) : null)
                    .addValue("endDate", endDate != null ? java.sql.Date.valueOf(endDate) : null)
                    .addValue("proofImages", serializeImages(exp.proofImages()))
                    .addValue("category", sanitizeCategory(exp.category()))
                    .addValue("roleLevel", sanitizeRoleLevel(exp.roleLevel()))
                    .addValue("proofLink", blankToNull(exp.proofLink()));

            if (existingId != null && existingStatus.containsKey(existingId)) {
                keptIds.add(existingId);
                boolean pending = "PENDING".equals(existingStatus.get(existingId));
                if (pending) {
                    // Still pending review → safe to update everything, including the
                    // category/role_level that decide the EXP/RS to be awarded.
                    jdbcTemplate.update("""
                            update experiences set
                                project_name = :organization,
                                position     = :title,
                                description  = :description,
                                category     = :category,
                                role_level   = :roleLevel,
                                proof_link   = :proofLink,
                                started_at   = :startDate,
                                ended_at     = :endDate,
                                proof_images = cast(:proofImages as jsonb),
                                updated_at   = now()
                            where id = :id
                            """, params.addValue("id", existingId));
                } else {
                    // Already reviewed → never change category/role_level (would
                    // misrepresent the already-awarded points). Only edit content.
                    jdbcTemplate.update("""
                            update experiences set
                                project_name = :organization,
                                position     = :title,
                                description  = :description,
                                proof_link   = :proofLink,
                                started_at   = :startDate,
                                ended_at     = :endDate,
                                proof_images = cast(:proofImages as jsonb),
                                updated_at   = now()
                            where id = :id
                            """, params.addValue("id", existingId));
                }
            } else {
                jdbcTemplate.update("""
                        insert into experiences (id, profile_id, project_name, position, category, role_level, proof_link, description, started_at, ended_at, proof_images, verification_status, source, created_at, updated_at)
                        values (gen_random_uuid(), :profileId, :organization, :title, :category, :roleLevel, :proofLink, :description, :startDate, :endDate, cast(:proofImages as jsonb), 'PENDING', 'PORTFOLIO', now(), now())
                        """, params);
            }
        }

        // Delete only PENDING rows the user actually removed. Reviewed (APPROVED/
        // REJECTED) rows are preserved to keep awarded points & history consistent.
        // Credential-form submissions are never deleted here — they are managed by
        // the verification flow, not the Portfolio editor.
        for (Map.Entry<UUID, String> e : existingStatus.entrySet()) {
            if (!keptIds.contains(e.getKey())
                    && "PENDING".equals(e.getValue())
                    && !credentialOwned.contains(e.getKey())) {
                jdbcTemplate.update("delete from experiences where id = :id", Map.of("id", e.getKey()));
            }
        }
    }

    private UUID tryParseUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null; // FE-generated numeric ids for brand-new rows → treat as insert
        }
    }

    private static final java.util.Set<String> VALID_CATEGORIES = java.util.Set.of(
            "CLUB_SMALL", "SCHOOL_CAMPAIGN", "COMPANY_PROJECT", "SHORT_INTERNSHIP", "FREELANCE_GIG");

    /** Default unknown/blank categories to the smallest reward tier (defensive). */
    private static String sanitizeCategory(String category) {
        return category != null && VALID_CATEGORIES.contains(category) ? category : "CLUB_SMALL";
    }

    private static String sanitizeRoleLevel(String roleLevel) {
        return "LEADER".equals(roleLevel) ? "LEADER" : "MEMBER";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private LocalDate parseMmYy(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            String[] parts = value.split("/");
            if (parts.length != 2) {
                return null;
            }
            int month = Integer.parseInt(parts[0].trim());
            int year = Integer.parseInt(parts[1].trim());
            if (year < 100) {
                year += 2000;
            }
            return LocalDate.of(year, month, 1);
        } catch (Exception e) {
            log.warn("Failed to parse date string {}: {}", value, e.getMessage());
            return null;
        }
    }

    private String formatMmYy(java.sql.Date date) {
        if (date == null) {
            return "";
        }
        LocalDate localDate = date.toLocalDate();
        return String.format("%02d/%02d", localDate.getMonthValue(), localDate.getYear() % 100);
    }

    // Proof images: clamp to 6 non-blank entries, store as a jsonb array string (null if none).
    private String serializeImages(java.util.List<String> images) {
        if (images == null || images.isEmpty()) return null;
        java.util.List<String> clean = images.stream().filter(s -> s != null && !s.isBlank()).limit(6).toList();
        if (clean.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(clean);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private java.util.List<String> deserializeImages(String json) {
        if (json == null || json.isBlank()) return java.util.List.of();
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {});
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    private String serializeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize object to JSON: {}", value, e);
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.trim().isEmpty() || json.equals("{}")) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse JSON map: {}", json, e);
            return new HashMap<>();
        }
    }

    private List<CredentialDto> parseCredentialsJson(String json) {
        if (json == null || json.trim().isEmpty() || json.equals("[]")) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, CredentialDto.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to parse credentials JSON: {}", json, e);
            return new ArrayList<>();
        }
    }

    private String getJsonString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        if (value.getClass().getName().equals("org.postgresql.util.PGobject")) {
            try {
                return (String) value.getClass().getMethod("getValue").invoke(value);
            } catch (Exception e) {
                log.error("Failed to extract JSON string via reflection", e);
            }
        }
        return value.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getJwtClaims() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            return jwtAuthenticationToken.getToken().getClaims();
        }

        if (jwtEnabled) {
            return Map.of();
        }

        HttpServletRequest request = getCurrentRequest();
        if (request != null) {
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                try {
                    String[] parts = token.split("\\.");
                    if (parts.length >= 2) {
                        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]));
                        return objectMapper.readValue(payloadJson, Map.class);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse JWT token manually from header: {}", e.getMessage());
                }
            }
        }
        return Map.of();
    }


}

