package com.nextplease.backend.service;

import com.nextplease.backend.dto.request.JobCreateRequest;
import com.nextplease.backend.exception.AppException;
import com.nextplease.backend.exception.ResourceNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final CompanyAccessService companyAccessService;
    private final ConfigService configService;
    private final NotificationService notificationService;
    private final ContentModerationService moderationService;

    public JobService(NamedParameterJdbcTemplate jdbcTemplate, CompanyAccessService companyAccessService,
                      ConfigService configService, NotificationService notificationService,
                      ContentModerationService moderationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.companyAccessService = companyAccessService;
        this.configService = configService;
        this.notificationService = notificationService;
        this.moderationService = moderationService;
    }

    /**
     * Verifies that the user holds an active authority node on an approved company/club.
     * Returns a map containing the company's info (id, verification_status, company_type).
     */
    private Map<String, Object> verifyAndGetApprovedCompany(UUID userId) {
        return companyAccessService.resolveApprovedCompanyForUser(userId);
    }

    @Transactional
    public UUID createJob(UUID userId, JobCreateRequest request) {
        log.info("Creating new job posting for user: {}", userId);

        // 1. Enforce B2B approval
        Map<String, Object> company = verifyAndGetApprovedCompany(userId);
        UUID companyId = (UUID) company.get("id");
        String companyType = (String) company.get("company_type");

        // 1b. Only OWNER/MANAGER may create postings (MEMBER is view + review only)
        companyAccessService.assertCanManagePostings(userId, companyId);

        // 2. Enforce monthly quota check for free employers
        // Clubs or Premium accounts can bypass this check. For free corporate accounts:
        if (!"CLUB".equals(companyType)) {
            // Check if user has free role
            List<String> roles = jdbcTemplate.queryForList("""
                    select role_code from user_roles where user_id = :userId
                    """, Map.of("userId", userId), String.class);

            if (roles.contains("employer_free") && !roles.contains("employer_premium") && !roles.contains("admin")) {
                Integer currentMonthPostings = jdbcTemplate.queryForObject("""
                        select count(*)
                        from jobs
                        where company_id = :companyId
                          and created_at >= date_trunc('month', now())
                          and status != 'REJECTED'
                        """, Map.of("companyId", companyId), Integer.class);

                int quota = configService.getInt("free_jd_quota_monthly", 3);
                if (currentMonthPostings != null && currentMonthPostings >= quota) {
                    throw new AppException(HttpStatus.BAD_REQUEST,
                            String.format("Tài khoản miễn phí tối đa đăng %d tin tuyển dụng trong tháng. Vui lòng nâng cấp Premium để không bị giới hạn.", quota));
                }
            }
        }

        // 3. Insert into jobs table
        UUID jobId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into jobs (
                    id, company_id, title, description, job_type, category, specialty,
                    compensation, min_req_rs, location, is_remote, capacity,
                    deadline_at, banner_url, banner_pos, status, created_by, content_flag, created_at, updated_at
                )
                values (
                    :id, :companyId, :title, :description, :jobType, :category, :specialty,
                    :compensation, :minReqRs, :location, :isRemote, :capacity,
                    :deadlineAt, :bannerUrl, :bannerPos, 'PENDING', :userId, :contentFlag, now(), now()
                )

                """, new MapSqlParameterSource()
                .addValue("contentFlag", moderationService.containsProfanity(request.title() + " " + request.description()))
                .addValue("bannerUrl", request.bannerUrl())
                .addValue("bannerPos", request.bannerPos())
                .addValue("id", jobId)
                .addValue("companyId", companyId)
                .addValue("title", request.title().trim())
                .addValue("description", request.description().trim())
                .addValue("jobType", request.jobType().toUpperCase().trim())
                .addValue("category", request.category().toUpperCase().trim())
                .addValue("specialty", request.specialty().toUpperCase().trim())
                .addValue("compensation", request.compensation())
                .addValue("minReqRs", request.minReqRs())
                .addValue("location", request.location())
                .addValue("isRemote", request.isRemote() != null ? request.isRemote() : false)
                .addValue("capacity", request.capacity())
                .addValue("deadlineAt", request.deadlineAt())
                .addValue("userId", userId)
        );

        // 4. Map and insert job skills
        if (request.skills() != null && !request.skills().isEmpty()) {
            for (JobCreateRequest.SkillRequirement skillReq : request.skills()) {
                jdbcTemplate.update("""
                        insert into job_skills (job_id, skill_id, required_level, created_at)
                        values (:jobId, :skillId, :requiredLevel, now())
                        """, Map.of(
                        "jobId", jobId,
                        "skillId", skillReq.skillId(),
                        "requiredLevel", skillReq.requiredLevel() != null ? skillReq.requiredLevel().toUpperCase().trim() : "BEGINNER"
                ));
            }
        }

        // 4b. Custom application form fields
        insertFormFields(jobId, request.formFields());

        // 5. Audit Log
        jdbcTemplate.update("""
                insert into audit_logs (actor_user_id, action, entity_type, entity_id, metadata)
                values (:userId, 'job.created', 'job', :jobId, jsonb_build_object('title', :title))
                """, Map.of("userId", userId, "jobId", jobId, "title", request.title()));

        log.info("Job successfully created with ID: {}", jobId);

        notificationService.notifyAdmins("POST_PENDING",
                "Tin tuyển dụng mới chờ duyệt",
                "Tin \"" + request.title() + "\" vừa được đăng và đang chờ duyệt.",
                "/nextplease-admin-portal/b2b-reviews");

        return jobId;
    }

    @Transactional
    public void updateJob(UUID jobId, UUID userId, JobCreateRequest request) {
        log.info("Updating job profile: {} by user: {}", jobId, userId);

        // Verify edit rights: platform admin, or OWNER/MANAGER of the job's company (MEMBER cannot edit).
        Map<String, Object> job = getJobDetailsRaw(jobId);
        UUID companyId = (UUID) job.get("companyId");

        List<String> roles = jdbcTemplate.queryForList("""
                select role_code from user_roles where user_id = :userId
                """, Map.of("userId", userId), String.class);

        if (!roles.contains("admin")) {
            companyAccessService.assertCanManagePostings(userId, companyId);
        }

        // If OPEN → reset to PENDING so Admin re-reviews the changes
        String currentStatus = (String) job.get("status");
        String newStatus = "OPEN".equalsIgnoreCase(currentStatus) ? "PENDING" : currentStatus;

        // Update jobs table
        jdbcTemplate.update("""
                update jobs
                set title = :title,
                    description = :description,
                    job_type = :jobType,
                    category = :category,
                    specialty = :specialty,
                    compensation = :compensation,
                    min_req_rs = :minReqRs,
                    location = :location,
                    is_remote = :isRemote,
                    capacity = :capacity,
                    deadline_at = :deadlineAt,
                    banner_url = :bannerUrl,
                    banner_pos = :bannerPos,
                    status = :status,
                    updated_at = now()
                where id = :jobId
                """, new MapSqlParameterSource()
                .addValue("bannerUrl", request.bannerUrl())
                .addValue("bannerPos", request.bannerPos())
                .addValue("status", newStatus)
                .addValue("jobId", jobId)
                .addValue("title", request.title().trim())
                .addValue("description", request.description().trim())
                .addValue("jobType", request.jobType().toUpperCase().trim())
                .addValue("category", request.category().toUpperCase().trim())
                .addValue("specialty", request.specialty().toUpperCase().trim())
                .addValue("compensation", request.compensation())
                .addValue("minReqRs", request.minReqRs())
                .addValue("location", request.location())
                .addValue("isRemote", request.isRemote() != null ? request.isRemote() : false)
                .addValue("capacity", request.capacity())
                .addValue("deadlineAt", request.deadlineAt())
        );

        // Refresh job skills
        jdbcTemplate.update("delete from job_skills where job_id = :jobId", Map.of("jobId", jobId));
        if (request.skills() != null && !request.skills().isEmpty()) {
            for (JobCreateRequest.SkillRequirement skillReq : request.skills()) {
                jdbcTemplate.update("""
                        insert into job_skills (job_id, skill_id, required_level, created_at)
                        values (:jobId, :skillId, :requiredLevel, now())
                        """, Map.of(
                        "jobId", jobId,
                        "skillId", skillReq.skillId(),
                        "requiredLevel", skillReq.requiredLevel() != null ? skillReq.requiredLevel().toUpperCase().trim() : "BEGINNER"
                ));
            }
        }

        // Refresh custom form fields
        jdbcTemplate.update("delete from job_form_fields where job_id = :jobId", Map.of("jobId", jobId));
        insertFormFields(jobId, request.formFields());

        // Audit Log
        jdbcTemplate.update("""
                insert into audit_logs (actor_user_id, action, entity_type, entity_id, metadata)
                values (:userId, 'job.updated', 'job', :jobId, jsonb_build_object('title', :title))
                """, Map.of("userId", userId, "jobId", jobId, "title", request.title()));
    }

    public List<Map<String, Object>> searchJobs(
            String query,
            String category,
            String specialty,
            Integer minRs,
            Boolean isRemote,
            String jobType,
            UUID companyId,
            int limit,
            int offset
    ) {
        StringBuilder sql = new StringBuilder("""
                select j.id,
                       j.company_id as "companyId",
                       j.title,
                       j.description,
                       j.job_type as "jobType",
                       j.category,
                       j.specialty,
                       j.compensation,
                       j.min_req_rs as "minReqRs",
                       j.requires_premium as "requiresPremium",
                       j.location,
                       j.is_remote as "isRemote",
                       j.deadline_at as "deadlineAt",
                       j.status,
                       c.name as "companyName",
                       c.logo_url as "companyLogo"
                from jobs j
                join companies c on j.company_id = c.id
                where j.status = 'OPEN'
                  and (j.deadline_at is null or j.deadline_at > now())
""");

        MapSqlParameterSource params = new MapSqlParameterSource();

        if (companyId != null) {
            sql.append(" and j.company_id = :companyId ");
            params.addValue("companyId", companyId);
        }

        if (query != null && !query.isBlank()) {
            sql.append(" and (lower(j.title) like :query or lower(j.description) like :query) ");
            params.addValue("query", "%" + query.trim().toLowerCase() + "%");
        }
        if (category != null && !category.isBlank()) {
            sql.append(" and j.category = :category ");
            params.addValue("category", category.trim().toUpperCase());
        }
        if (specialty != null && !specialty.isBlank()) {
            // specialty may be stored as comma-separated (multi-select); use LIKE for partial match
            sql.append(" and j.specialty ilike :specialty ");
            params.addValue("specialty", "%" + specialty.trim().toUpperCase() + "%");
        }
        if (minRs != null) {
            sql.append(" and j.min_req_rs <= :minRs ");
            params.addValue("minRs", minRs);
        }
        if (isRemote != null) {
            sql.append(" and j.is_remote = :isRemote ");
            params.addValue("isRemote", isRemote);
        }
        if (jobType != null && !jobType.isBlank()) {
            sql.append(" and j.job_type = :jobType ");
            params.addValue("jobType", jobType.trim().toUpperCase());
        }

        sql.append(" order by j.created_at desc limit :limit offset :offset ");
        params.addValue("limit", limit);
        params.addValue("offset", offset);

        List<Map<String, Object>> jobs = jdbcTemplate.queryForList(sql.toString(), params);
        
        // Fetch skills for each job to show on candidate side
        for (Map<String, Object> jobItem : jobs) {
            UUID id = (UUID) jobItem.get("id");
            List<Map<String, Object>> skills = getJobSkills(id);
            jobItem.put("skills", skills);
        }

        return jobs;
    }

    public List<Map<String, Object>> getOrganizerJobs(UUID userId) {
        // Enforce approved company first
        Map<String, Object> company = verifyAndGetApprovedCompany(userId);
        UUID companyId = (UUID) company.get("id");

        List<Map<String, Object>> jobs = jdbcTemplate.queryForList("""
                select id,
                       title,
                       job_type as "jobType",
                       category,
                       specialty,
                       compensation,
                       min_req_rs as "minReqRs",
                       status,
                       rejection_reason as "rejectionReason",
                       deadline_at as "deadlineAt",
                       created_at as "createdAt",
                       (select count(*) from applications where job_id = jobs.id) as "applicantsCount"
                from jobs
                where company_id = :companyId
                order by created_at desc
                """, Map.of("companyId", companyId));

        return jobs;
    }

    public Map<String, Object> getJobDetails(UUID jobId) {
        Map<String, Object> job = getJobDetailsRaw(jobId);
        boolean isQuest = Boolean.TRUE.equals(job.get("isQuest")) || "QUEST".equals(job.get("postType"));
        if (isQuest) {
            // Quests have no skill requirements; their custom questions live in
            // quest_form_fields (not job_form_fields).
            job.put("skills", java.util.List.of());
            job.put("formFields", getQuestFormFieldsRaw(jobId));
        } else {
            job.put("skills", getJobSkills(jobId));
            job.put("formFields", getJobFormFields(jobId));
        }
        return job;
    }

    private List<Map<String, Object>> getQuestFormFieldsRaw(UUID questId) {
        return jdbcTemplate.queryForList("""
                select id, label, field_type as "fieldType", options, required, sort_order as "sortOrder"
                from quest_form_fields where quest_id = :questId order by sort_order
                """, Map.of("questId", questId));
    }

    private Map<String, Object> getJobDetailsRaw(UUID jobId) {
        try {
            return jdbcTemplate.queryForMap("""
                    select j.id,
                           j.company_id as "companyId",
                           j.title,
                           j.description,
                           j.job_type as "jobType",
                           j.category,
                           j.specialty,
                           j.compensation,
                           j.min_req_rs as "minReqRs",
                           j.location,
                           j.is_remote as "isRemote",
                           j.capacity,
                           j.deadline_at as "deadlineAt",
                           j.banner_url as "bannerUrl",
                           j.banner_pos as "bannerPos",
                           j.status,
                           j.content_flag as "contentFlag",
                           j.rejection_reason as "rejectionReason",
                           j.created_by,
                           c.name as "companyName",
                           c.logo_url as "companyLogo",
                           c.company_type as "companyType",
                           'JOB' as "postType"
                    from jobs j
                    join companies c on j.company_id = c.id
                    where j.id = :jobId
                    """, Map.of("jobId", jobId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            try {
                Map<String, Object> questRaw = jdbcTemplate.queryForMap("""
                        select q.id,
                               q.company_id as "companyId",
                               q.title,
                               q.description,
                               q.category as "jobType",
                               q.category,
                               'OTHER' as "specialty",
                               null as "compensation",
                               q.exp_reward as "expReward",
                               q.np_reward as "npReward",
                               q.min_req_rs as "minReqRs",
                               'FPTU HCM' as "location",
                               false as "isRemote",
                               q.capacity,
                               q.ends_at as "deadlineAt",
                               q.banner_url as "bannerUrl",
                               q.banner_pos as "bannerPos",
                               q.status,
                               q.content_flag as "contentFlag",
                               q.rejection_reason as "rejectionReason",
                               q.created_by,
                               c.name as "companyName",
                               c.logo_url as "companyLogo",
                               c.company_type as "companyType",
                               'QUEST' as "postType"
                        from quests q
                        join companies c on q.company_id = c.id
                        where q.id = :jobId
                        """, Map.of("jobId", jobId));
                Map<String, Object> quest = new java.util.HashMap<>(questRaw);
                quest.put("isQuest", true);
                return quest;
            } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
                throw new ResourceNotFoundException("Không tìm thấy tin tuyển dụng hoặc quest này.");
            }
        }
    }

    private List<Map<String, Object>> getJobSkills(UUID jobId) {
        return jdbcTemplate.queryForList("""
                select s.id as "skillId",
                       s.name as "skillName",
                       js.required_level as "requiredLevel"
                from job_skills js
                join skills s on js.skill_id = s.id
                where js.job_id = :jobId
                """, Map.of("jobId", jobId));
    }

    List<Map<String, Object>> getJobFormFields(UUID jobId) {
        return jdbcTemplate.queryForList("""
                select id, label, field_type as "fieldType", options, required, sort_order as "sortOrder"
                from job_form_fields
                where job_id = :jobId
                order by sort_order
                """, Map.of("jobId", jobId));
    }

    private void insertFormFields(UUID jobId, List<JobCreateRequest.FormField> fields) {
        if (fields == null) return;
        int order = 0;
        for (JobCreateRequest.FormField f : fields) {
            if (f.label() == null || f.label().isBlank()) continue;
            jdbcTemplate.update("""
                    insert into job_form_fields (job_id, label, field_type, options, required, sort_order)
                    values (:jobId, :label, :type, :options, :required, :order)
                    """, new MapSqlParameterSource()
                    .addValue("jobId", jobId)
                    .addValue("label", f.label().trim())
                    .addValue("type", f.fieldType() != null ? f.fieldType().toUpperCase().trim() : "TEXT")
                    .addValue("options", f.options())
                    .addValue("required", f.required() != null && f.required())
                    .addValue("order", order++));
        }
    }

    public Map<String, Object> getOrganizerJobById(UUID userId, UUID jobId) {
        Map<String, Object> company = verifyAndGetApprovedCompany(userId);
        UUID companyId = (UUID) company.get("id");
        try {
            Map<String, Object> job = jdbcTemplate.queryForMap("""
                    select id, title, description, job_type as "jobType", category, specialty,
                           compensation, min_req_rs as "minReqRs", location, is_remote as "isRemote",
                           capacity, deadline_at as "deadlineAt", banner_url as "bannerUrl", banner_pos as "bannerPos",
                           status, rejection_reason as "rejectionReason"
                    from jobs
                    where id = :jobId and company_id = :companyId
                    """, Map.of("jobId", jobId, "companyId", companyId));
            job.put("skills", getJobSkills(jobId));
            job.put("formFields", getJobFormFields(jobId));
            return job;
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Không tìm thấy tin tuyển dụng này.");
        }
    }

    @Transactional
    public void closeJob(UUID userId, UUID jobId) {
        Map<String, Object> company = verifyAndGetApprovedCompany(userId);
        UUID companyId = (UUID) company.get("id");
        companyAccessService.assertCanManagePostings(userId, companyId);

        int rows = jdbcTemplate.update("""
                update jobs set status = 'CLOSED', updated_at = now()
                where id = :jobId and company_id = :companyId and status = 'OPEN'
                """, Map.of("jobId", jobId, "companyId", companyId));
        if (rows == 0) {
            throw new AppException(HttpStatus.CONFLICT,
                    "Tin tuyển dụng không ở trạng thái OPEN hoặc không tìm thấy.");
        }
    }

    @Transactional
    public void deleteJob(UUID userId, UUID jobId) {
        Map<String, Object> company = verifyAndGetApprovedCompany(userId);
        UUID companyId = (UUID) company.get("id");
        companyAccessService.assertCanManagePostings(userId, companyId);

        // Block deleting OPEN jobs — must close first
        String status = jdbcTemplate.queryForObject(
                "select status from jobs where id = :jobId and company_id = :companyId",
                Map.of("jobId", jobId, "companyId", companyId), String.class);
        if ("OPEN".equals(status)) {
            throw new AppException(HttpStatus.CONFLICT,
                    "Không thể xoá tin đang OPEN. Hãy đóng tin trước.");
        }

        int rows = jdbcTemplate.update("""
                delete from jobs where id = :jobId and company_id = :companyId
                """, Map.of("jobId", jobId, "companyId", companyId));
        if (rows == 0) {
            throw new ResourceNotFoundException("Không tìm thấy tin tuyển dụng hoặc bạn không có quyền xoá.");
        }
    }

    public List<Map<String, Object>> getAllSkills() {
        return jdbcTemplate.queryForList("""
                select id, name, category
                from skills
                where category is not null
                order by category, name
                """, Map.of());
    }

    public List<Map<String, Object>> getApprovedCompanies() {
        // Public directory: expose only non-sensitive fields. Tax code, representative
        // phone, and advisor contact are internal/PII and must NOT be returned here.
        return jdbcTemplate.queryForList("""
                select c.id, c.name, c.company_type as "companyType", c.description, c.logo_url as "logoUrl",
                       c.website_url as "websiteUrl", c.fanpage_url as "fanpageUrl",
                       c.representative_name as "representativeName",
                       c.school_id as "schoolId", s.name as "schoolName"
                from companies c
                left join schools s on c.school_id = s.id
                where c.verification_status = 'APPROVED'
                order by c.name asc
                """, Map.of());
    }

    public Map<String, Object> getCompanyDetails(UUID id) {
        try {
            // Public detail: same exclusion of tax code / phone / advisor contact.
            // followerCount = số Ứng viên đang theo dõi đối tác này (từ company_follows).
            return jdbcTemplate.queryForMap("""
                    select c.id, c.name, c.company_type as "companyType", c.description, c.logo_url as "logoUrl",
                           c.website_url as "websiteUrl", c.fanpage_url as "fanpageUrl",
                           c.representative_name as "representativeName",
                           c.school_id as "schoolId", s.name as "schoolName",
                           (select count(*) from company_follows cf where cf.company_id = c.id) as "followerCount"
                    from companies c
                    left join schools s on c.school_id = s.id
                    where c.id = :id and c.verification_status = 'APPROVED'
                    """, Map.of("id", id));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new com.nextplease.backend.exception.ResourceNotFoundException("Không tìm thấy thông tin đối tác.");
        }
    }
}
