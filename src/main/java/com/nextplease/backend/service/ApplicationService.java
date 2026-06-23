package com.nextplease.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextplease.backend.exception.AppException;
import com.nextplease.backend.exception.ResourceNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

    private static final Map<String, Integer> JOB_TYPE_EXP = new HashMap<>();
    static {
        JOB_TYPE_EXP.put("INTERNSHIP", 500);
        JOB_TYPE_EXP.put("MICRO_INTERNSHIP", 500);
        JOB_TYPE_EXP.put("PART_TIME", 300);
        JOB_TYPE_EXP.put("FREELANCE", 300);
        JOB_TYPE_EXP.put("EVENT_STAFF", 200);
    }

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ExpService expService;
    private final ReputationService reputationService;
    private final ConfigService configService;

    public ApplicationService(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                              ExpService expService, ReputationService reputationService, ConfigService configService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.expService = expService;
        this.reputationService = reputationService;
        this.configService = configService;
    }

    /**
     * Candidate applies to a job.
     * Gates (in order): job must be OPEN → RS check → premium check → no duplicate.
     *
     * Error codes returned via AppException:
     *   RS_TOO_LOW       — RS < job.min_req_rs
     *   PREMIUM_REQUIRED — job.requires_premium = true AND user has no active premium
     *   ALREADY_APPLIED  — duplicate application
     */
    @Transactional
    public Map<String, Object> apply(UUID userId, UUID jobId, String coverNote, Map<String, String> answers) {
        // 1. Fetch job
        Map<String, Object> job = fetchJobOrThrow(jobId);

        String jobStatus = (String) job.get("status");
        if (!"OPEN".equals(jobStatus)) {
            throw new AppException(HttpStatus.CONFLICT,
                    "Cơ hội này hiện không mở nhận đơn (trạng thái: " + jobStatus + ").");
        }

        // 1b. Deadline check
        Object deadlineRaw = job.get("deadline_at");
        if (deadlineRaw instanceof java.sql.Timestamp deadlineTs) {
            if (deadlineTs.toInstant().isBefore(java.time.Instant.now())) {
                throw new AppException(HttpStatus.CONFLICT,
                        "Cơ hội này đã hết hạn nộp đơn.");
            }
        }

        // 2. Fetch candidate profile + user for RS and premium check
        Map<String, Object> candidate = fetchCandidateOrThrow(userId);

        int myRs = ((Number) candidate.get("reputation_score")).intValue();
        int minRs = ((Number) job.get("min_req_rs")).intValue();

        // 3. RS gate
        if (myRs < minRs) {
            throw new AppException(
                    HttpStatus.FORBIDDEN,
                    String.format("Điểm RS của bạn chưa đủ. Cần %d RS, hiện tại bạn có %d RS.", minRs, myRs),
                    "RS_TOO_LOW"
            );
        }

        // 4. Premium gate
        Boolean requiresPremium = (Boolean) job.get("requires_premium");
        if (Boolean.TRUE.equals(requiresPremium)) {
            boolean hasPremium = checkPremium(userId);
            if (!hasPremium) {
                throw new AppException(
                        HttpStatus.PAYMENT_REQUIRED,
                        "Cơ hội này yêu cầu Premium Pass. Nâng cấp để ứng tuyển không giới hạn.",
                        "PREMIUM_REQUIRED"
                );
            }
        }

        // 5. Snapshot eligibility at time of application
        String snapshot = buildSnapshot(myRs, candidate);

        // 5b. Validate + snapshot custom form answers (denormalized [{label,value}])
        String customAnswersJson = buildCustomAnswers(jobId, answers);

        // 6. Insert application
        UUID applicationId;
        try {
            applicationId = jdbcTemplate.queryForObject("""
                    insert into applications (job_id, candidate_id, status, cover_note, eligibility_snapshot, custom_answers)
                    values (:jobId, :userId, 'SUBMITTED', :coverNote, :snapshot::jsonb, :answers::jsonb)
                    returning id
                    """, new MapSqlParameterSource()
                    .addValue("jobId", jobId)
                    .addValue("userId", userId)
                    .addValue("coverNote", coverNote)
                    .addValue("snapshot", snapshot)
                    .addValue("answers", customAnswersJson),
                    UUID.class);
        } catch (DuplicateKeyException e) {
            throw new AppException(HttpStatus.CONFLICT,
                    "Bạn đã ứng tuyển vị trí này rồi.", "ALREADY_APPLIED");
        }

        log.info("[ApplicationService] User {} applied to job {} → application {}", userId, jobId, applicationId);

        return Map.of(
                "applicationId", applicationId,
                "jobId", jobId,
                "status", "SUBMITTED"
        );
    }

    /**
     * Organizer: list all applications for a specific job they own.
     * Verifies that the job belongs to the organizer's company.
     */
    public List<Map<String, Object>> getJobApplications(UUID jobId, UUID organizerUserId) {
        // Verify job ownership
        try {
            jdbcTemplate.queryForObject("""
                    select j.id from jobs j
                    join companies c on c.id = j.company_id
                    where j.id = :jobId
                      and exists (select 1 from authority_nodes an
                                  where an.company_id = c.id and an.user_id = :ownerId
                                    and an.status = 'ACTIVE' and an.deleted_at is null)
                    """, Map.of("jobId", jobId, "ownerId", organizerUserId), UUID.class);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new AppException(HttpStatus.FORBIDDEN, "Tin tuyển dụng không tồn tại hoặc bạn không có quyền xem.");
        }

        return jdbcTemplate.queryForList("""
                select
                    a.id,
                    a.status,
                    a.cover_note,
                    a.applied_at,
                    a.eligibility_snapshot,
                    a.custom_answers::text as custom_answers,
                    u.id            as candidate_id,
                    u.display_name  as candidate_name,
                    u.email         as candidate_email,
                    p.reputation_score,
                    p.total_exp,
                    p.current_level,
                    p.headline,
                    p.major,
                    p.avatar_url,
                    s.name          as school
                from applications a
                join app_users u on u.id = a.candidate_id
                join profiles p on p.user_id = u.id
                left join schools s on s.id = p.school_id
                where a.job_id = :jobId
                order by a.applied_at asc
                """, Map.of("jobId", jobId));
    }

    /**
     * Organizer: update status of an application (SHORTLISTED, REJECTED, ACCEPTED).
     */
    @Transactional
    public void updateApplicationStatus(UUID applicationId, UUID organizerUserId, String newStatus, String rejectReason) {
        List<String> allowed = List.of("VIEWED", "SHORTLISTED", "ACCEPTED", "REJECTED", "COMPLETED");
        if (!allowed.contains(newStatus)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Trạng thái không hợp lệ: " + newStatus);
        }

        // Verify ownership via job and fetch candidate + job_type
        Map<String, Object> appInfo;
        try {
            appInfo = jdbcTemplate.queryForMap("""
                    select a.id, a.candidate_id, a.status, j.job_type from applications a
                    join jobs j on j.id = a.job_id
                    join companies c on c.id = j.company_id
                    where a.id = :appId
                      and exists (select 1 from authority_nodes an
                                  where an.company_id = c.id and an.user_id = :ownerId
                                    and an.status = 'ACTIVE' and an.deleted_at is null)
                    """, Map.of("appId", applicationId, "ownerId", organizerUserId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new AppException(HttpStatus.FORBIDDEN, "Không có quyền cập nhật đơn ứng tuyển này.");
        }

        // Guard: only an ACCEPTED application can be marked COMPLETED
        String currentStatus = (String) appInfo.get("status");
        if ("COMPLETED".equals(newStatus) && !"ACCEPTED".equals(currentStatus)) {
            throw new AppException(HttpStatus.CONFLICT,
                    "Chỉ có thể đánh dấu hoàn thành khi đơn đang ở trạng thái 'Chấp nhận'.");
        }

        jdbcTemplate.update("""
                update applications
                set status        = :status,
                    reject_reason = :rejectReason,
                    updated_at    = now()
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("id", applicationId)
                .addValue("status", newStatus)
                .addValue("rejectReason", "REJECTED".equals(newStatus) ? rejectReason : null));

        // Award EXP when organizer marks a job application as COMPLETED
        if ("COMPLETED".equals(newStatus)) {
            UUID candidateUserId = (UUID) appInfo.get("candidate_id");
            String jobType = (String) appInfo.get("job_type");
            int expAmount = JOB_TYPE_EXP.getOrDefault(jobType, 300);
            UUID profileId = jdbcTemplate.queryForObject(
                    "select id from profiles where user_id = :userId",
                    Map.of("userId", candidateUserId), UUID.class);
            expService.addExp(profileId, expAmount,
                    "JOB_COMPLETED",
                    "job_application", applicationId);
            // +RS for completing a job (idempotent; mirrors quest completion)
            int rsReward = configService.getInt("rs_job_completed", 5);
            if (rsReward > 0) {
                reputationService.addReputation(profileId, rsReward,
                        "JOB_COMPLETED", "job_application", applicationId);
            }
            log.info("[ApplicationService] Awarded {} EXP + {} RS to candidate {} for completing job (type={})", expAmount, rsReward, candidateUserId, jobType);
        }

        log.info("[ApplicationService] Organizer {} updated application {} → {}", organizerUserId, applicationId, newStatus);
    }

    /** Returns all applications for the given candidate, newest first. */
    public List<Map<String, Object>> getMyApplications(UUID userId) {
        return jdbcTemplate.queryForList("""
                select
                    a.id,
                    a.status,
                    a.cover_note,
                    a.custom_answers::text as custom_answers,
                    a.reject_reason,
                    a.applied_at,
                    a.updated_at,
                    j.id            as job_id,
                    j.title         as job_title,
                    j.job_type,
                    j.compensation,
                    j.min_req_rs,
                    j.requires_premium,
                    j.location,
                    j.is_remote,
                    j.deadline_at,
                    c.id            as company_id,
                    c.name          as company_name,
                    c.logo_url      as company_logo,
                    r.score         as rating_score,
                    r.comment       as rating_comment,
                    r.created_at    as rating_at
                from applications a
                join jobs j on j.id = a.job_id
                join companies c on c.id = j.company_id
                left join ratings r on r.application_id = a.id
                where a.candidate_id = :userId
                order by a.applied_at desc
                """, Map.of("userId", userId));
    }

    /** Candidate withdraws their own application. Only allowed for non-terminal statuses. */
    @Transactional
    public void withdrawApplication(UUID userId, UUID applicationId) {
        Map<String, Object> app;
        try {
            app = jdbcTemplate.queryForMap("""
                    select id, status from applications
                    where id = :id and candidate_id = :userId
                    """, Map.of("id", applicationId, "userId", userId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new AppException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn ứng tuyển.");
        }
        String current = (String) app.get("status");
        if (List.of("WITHDRAWN", "ACCEPTED", "COMPLETED", "REJECTED").contains(current)) {
            throw new AppException(HttpStatus.CONFLICT,
                    "Không thể rút đơn khi trạng thái là " + current + ".");
        }
        jdbcTemplate.update("""
                update applications
                set status = 'WITHDRAWN', updated_at = now()
                where id = :id
                """, Map.of("id", applicationId));
        log.info("[ApplicationService] Candidate {} withdrew application {}", userId, applicationId);
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private Map<String, Object> fetchJobOrThrow(UUID jobId) {
        try {
            return jdbcTemplate.queryForMap("""
                    select id, title, status, min_req_rs, requires_premium, company_id, deadline_at
                    from jobs
                    where id = :id and deleted_at is null
                    """, Map.of("id", jobId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Không tìm thấy cơ hội: " + jobId);
        }
    }

    private Map<String, Object> fetchCandidateOrThrow(UUID userId) {
        try {
            return jdbcTemplate.queryForMap("""
                    select
                        u.id,
                        u.display_name,
                        u.email,
                        u.premium_until,
                        p.id                  as profile_id,
                        p.reputation_score,
                        p.total_exp,
                        p.current_level,
                        p.onboarding_completed
                    from app_users u
                    join profiles p on p.user_id = u.id
                    where u.id = :userId
                    """, Map.of("userId", userId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Hồ sơ ứng viên không tồn tại.");
        }
    }

    private boolean checkPremium(UUID userId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from app_users
                where id = :userId
                  and premium_until > now()
                """, Map.of("userId", userId), Integer.class);
        return count != null && count > 0;
    }

    private String buildSnapshot(int rsAtApply, Map<String, Object> candidate) {
        try {
            Map<String, Object> snap = Map.of(
                    "rsAtApply", rsAtApply,
                    "levelAtApply", candidate.get("current_level"),
                    "expAtApply", candidate.get("total_exp"),
                    "onboardingCompleted", candidate.get("onboarding_completed")
            );
            return objectMapper.writeValueAsString(snap);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Validates required custom fields and returns a denormalized JSON snapshot [{label,value}], or null. */
    private String buildCustomAnswers(UUID jobId, Map<String, String> answers) {
        List<Map<String, Object>> fields = jdbcTemplate.queryForList(
                "select id, label, required from job_form_fields where job_id = :jobId order by sort_order",
                Map.of("jobId", jobId));
        if (fields.isEmpty()) return null;

        List<Map<String, String>> out = new java.util.ArrayList<>();
        for (Map<String, Object> f : fields) {
            String fieldId = f.get("id").toString();
            String label = (String) f.get("label");
            boolean required = Boolean.TRUE.equals(f.get("required"));
            String val = answers != null ? answers.get(fieldId) : null;
            if (required && (val == null || val.isBlank())) {
                throw new AppException(HttpStatus.BAD_REQUEST,
                        "Vui lòng trả lời câu hỏi bắt buộc: " + label);
            }
            if (val != null && !val.isBlank()) {
                out.add(Map.of("label", label, "value", val.trim()));
            }
        }
        if (out.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            return null;
        }
    }
}
