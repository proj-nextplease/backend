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

    public ProfileService(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
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


    @Transactional(readOnly = true)
    public PortfolioResponse getPortfolio() {
        UUID supabaseUserId = getCurrentUserSupabaseId();

        // 1. Get user details from app_users
        Map<String, Object> user;
        try {
            user = jdbcTemplate.queryForMap("""
                    select id, display_name, email from app_users where supabase_user_id = :supabaseUserId
                    """, Map.of("supabaseUserId", supabaseUserId));
        } catch (EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Tài khoản người dùng chưa được khởi tạo.");
        }

        UUID userId = (UUID) user.get("id");
        String displayName = (String) user.get("display_name");

        // 2. Get profile details. If not exist, dynamically create one
        Map<String, Object> profile;
        try {
            profile = jdbcTemplate.queryForMap("""
                    select id, headline, bio, location, school_id, avatar_config, credentials 
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
                    select id, headline, bio, location, school_id, avatar_config, credentials 
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
        Map<String, Object> avatarConfig = parseJsonMap((String) profile.get("avatar_config"));
        List<CredentialDto> credentials = parseCredentialsJson((String) profile.get("credentials"));

        // 5. Get skills
        List<String> skills = jdbcTemplate.query("""
                select s.name from skills s
                join profile_skills ps on ps.skill_id = s.id
                where ps.profile_id = :profileId
                """, Map.of("profileId", profileId), (rs, rowNum) -> rs.getString("name"));

        // 6. Get experiences
        List<ExperienceDto> experiences = jdbcTemplate.query("""
                select id, project_name, position, description, started_at, ended_at from experiences
                where profile_id = :profileId
                order by created_at asc
                """, Map.of("profileId", profileId), (rs, rowNum) -> new ExperienceDto(
                        rs.getString("id"),
                        rs.getString("position"),
                        rs.getString("project_name"),
                        rs.getString("description"),
                        formatMmYy(rs.getDate("started_at")),
                        formatMmYy(rs.getDate("ended_at"))
                ));

        return new PortfolioResponse(
                displayName,
                headline,
                schoolName,
                location,
                bio,
                skills,
                avatarConfig,
                experiences,
                credentials
        );
    }

    @Transactional
    public void updatePortfolio(PortfolioRequest request) {
        UUID supabaseUserId = getCurrentUserSupabaseId();

        // 1. Find user ID
        UUID userId;
        try {
            userId = jdbcTemplate.queryForObject("""
                    select id from app_users where supabase_user_id = :supabaseUserId
                    """, Map.of("supabaseUserId", supabaseUserId), UUID.class);
        } catch (EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Tài khoản người dùng chưa được khởi tạo.");
        }

        // 2. Find profile ID or create if not exists
        UUID profileId;
        try {
            profileId = jdbcTemplate.queryForObject("""
                    select id from profiles where user_id = :userId
                    """, Map.of("userId", userId), UUID.class);
        } catch (EmptyResultDataAccessException e) {
            profileId = UUID.randomUUID();
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
                    updated_at = now()
                where id = :profileId
                """, new MapSqlParameterSource()
                .addValue("headline", request.headline())
                .addValue("bio", request.bio())
                .addValue("location", request.location())
                .addValue("schoolId", schoolId)
                .addValue("avatarConfig", avatarConfigJson)
                .addValue("credentials", credentialsJson)
                .addValue("profileId", profileId));

        // 7. Update skills
        updateProfileSkills(profileId, request.skills());

        // 8. Update experiences
        updateExperiences(profileId, request.experiences());
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
                    .addValue("endDate", endDate != null ? java.sql.Date.valueOf(endDate) : null);

            jdbcTemplate.update("""
                    insert into experiences (id, profile_id, project_name, position, category, description, started_at, ended_at, verification_status, created_at, updated_at)
                    values (gen_random_uuid(), :profileId, :organization, :title, 'COMPANY_PROJECT', :description, :startDate, :endDate, 'PENDING', now(), now())
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
}
