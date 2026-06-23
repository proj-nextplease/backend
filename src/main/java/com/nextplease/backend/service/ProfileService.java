package com.nextplease.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextplease.backend.dto.CredentialDto;
import com.nextplease.backend.dto.ExperienceDto;
import com.nextplease.backend.dto.request.PortfolioRequest;
import com.nextplease.backend.dto.response.PortfolioResponse;
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

    public ProfileService(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ReputationService reputationService,
            ConfigService configService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.reputationService = reputationService;
        this.configService = configService;
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
                user = provisionLocalUserJit(supabaseUserId, email, displayName, provider);
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
                    select id, headline, bio, location, school_id, avatar_config, credentials, onboarding_completed, reputation_score, total_exp, current_level 
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
                    select id, headline, bio, location, school_id, avatar_config, credentials, onboarding_completed, reputation_score, total_exp, current_level 
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
                select id, project_name, position, description, started_at, ended_at, proof_images::text as proof_images from experiences
                where profile_id = :profileId
                order by created_at asc
                """, Map.of("profileId", profileId), (rs, rowNum) -> new ExperienceDto(
                        rs.getString("id"),
                        rs.getString("position"),
                        rs.getString("project_name"),
                        rs.getString("description"),
                        formatMmYy(rs.getDate("started_at")),
                        formatMmYy(rs.getDate("ended_at")),
                        deserializeImages(rs.getString("proof_images"))
                ));

        boolean onboardingCompleted = profile.get("onboarding_completed") != null && (Boolean) profile.get("onboarding_completed");
        int reputationScore = profile.get("reputation_score") != null ? ((Number) profile.get("reputation_score")).intValue() : 0;
        long totalExp = profile.get("total_exp") != null ? ((Number) profile.get("total_exp")).longValue() : 0L;
        int currentLevel = profile.get("current_level") != null ? ((Number) profile.get("current_level")).intValue() : 1;
        long npBalance = user.get("np_balance") != null ? ((Number) user.get("np_balance")).longValue() : 0L;

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
                npBalance
        );
    }

    public PortfolioResponse getPortfolioByUserId(UUID userId) {
        Map<String, Object> userRow;
        try {
            userRow = jdbcTemplate.queryForMap("""
                    select u.display_name, coalesce(w.np_balance, 0) as np_balance
                    from app_users u
                    left join wallets w on w.user_id = u.id
                    where u.id = :userId
                    """, Map.of("userId", userId));
        } catch (EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Không tìm thấy người dùng.");
        }

        String displayName = (String) userRow.get("display_name");
        long npBalance = userRow.get("np_balance") != null ? ((Number) userRow.get("np_balance")).longValue() : 0L;

        Map<String, Object> profile;
        try {
            profile = jdbcTemplate.queryForMap("""
                    select id, headline, bio, location, school_id, avatar_config, credentials::text as credentials,
                           onboarding_completed, reputation_score, total_exp, current_level
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
        List<CredentialDto> credentials = parseCredentialsJson(getJsonString(profile.get("credentials")));

        List<String> skills = jdbcTemplate.query("""
                select s.name from skills s
                join profile_skills ps on ps.skill_id = s.id
                where ps.profile_id = :profileId
                """, Map.of("profileId", profileId), (rs, rowNum) -> rs.getString("name"));

        List<ExperienceDto> experiences = jdbcTemplate.query("""
                select id, project_name, position, description, started_at, ended_at, proof_images::text as proof_images from experiences
                where profile_id = :profileId
                order by created_at asc
                """, Map.of("profileId", profileId), (rs, rowNum) -> new ExperienceDto(
                        rs.getString("id"),
                        rs.getString("position"),
                        rs.getString("project_name"),
                        rs.getString("description"),
                        formatMmYy(rs.getDate("started_at")),
                        formatMmYy(rs.getDate("ended_at")),
                        deserializeImages(rs.getString("proof_images"))
                ));

        boolean onboardingCompleted = Boolean.TRUE.equals(profile.get("onboarding_completed"));
        int reputationScore = profile.get("reputation_score") != null ? ((Number) profile.get("reputation_score")).intValue() : 0;
        long totalExp = profile.get("total_exp") != null ? ((Number) profile.get("total_exp")).longValue() : 0L;
        int currentLevel = profile.get("current_level") != null ? ((Number) profile.get("current_level")).intValue() : 1;

        return new PortfolioResponse(
                displayName, headline, schoolName, location, bio,
                skills, avatarConfig, experiences, credentials,
                onboardingCompleted, reputationScore, totalExp, currentLevel, npBalance
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
                newUser = provisionLocalUserJit(supabaseUserId, email, displayName, provider);
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

        // 6. Update profile columns
        jdbcTemplate.update("""
                update profiles set
                    headline = :headline,
                    bio = :bio,
                    location = :location,
                    school_id = :schoolId,
                    avatar_config = cast(:avatarConfig as jsonb),
                    credentials = cast(:credentials as jsonb),
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

    private void updateExperiences(UUID profileId, List<ExperienceDto> experiences) {
        // Delete all old experiences first to avoid mismatch
        jdbcTemplate.update("delete from experiences where profile_id = :profileId", Map.of("profileId", profileId));

        if (experiences == null || experiences.isEmpty()) {
            return;
        }

        for (ExperienceDto exp : experiences) {
            if ((exp.title() == null || exp.title().trim().isEmpty()) &&
                (exp.organization() == null || exp.organization().trim().isEmpty())) {
                continue; // Skip empty records
            }
            LocalDate startDate = parseMmYy(exp.startDate());
            LocalDate endDate = parseMmYy(exp.endDate());

            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("profileId", profileId)
                    .addValue("organization", exp.organization() == null ? "" : exp.organization().trim())
                    .addValue("title", exp.title() == null ? "" : exp.title().trim())
                    .addValue("description", exp.detail() == null ? "" : exp.detail().trim())
                    .addValue("startDate", startDate != null ? java.sql.Date.valueOf(startDate) : null)
                    .addValue("endDate", endDate != null ? java.sql.Date.valueOf(endDate) : null)
                    .addValue("proofImages", serializeImages(exp.proofImages()));

            jdbcTemplate.update("""
                    insert into experiences (id, profile_id, project_name, position, category, description, started_at, ended_at, proof_images, verification_status, created_at, updated_at)
                    values (gen_random_uuid(), :profileId, :organization, :title, 'COMPANY_PROJECT', :description, :startDate, :endDate, cast(:proofImages as jsonb), 'PENDING', now(), now())
                    """, params);
        }
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

    private Map<String, Object> provisionLocalUserJit(UUID supabaseUserId, String email, String displayName, String authProvider) {
        UUID userId = UUID.randomUUID();
        log.info("JIT provisioning local database records for Supabase user: {} (email: {}, provider: {})", supabaseUserId, email, authProvider);

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

        return Map.of(
                "id", userId,
                "display_name", displayName,
                "email", email,
                "np_balance", 0L
        );
    }
}

