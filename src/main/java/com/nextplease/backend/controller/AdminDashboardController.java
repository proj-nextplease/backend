package com.nextplease.backend.controller;

import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.dto.response.MeResponse;
import com.nextplease.backend.exception.AppException;
import com.nextplease.backend.service.CurrentUserService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;


/**
 * Controller providing comprehensive system stats, user listings, postings,
 * and system logs for users with the 'admin' role.
 */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
public class AdminDashboardController {

    private static final Logger log = LoggerFactory.getLogger(AdminDashboardController.class);

    private final CurrentUserService currentUserService;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AdminDashboardController(
            CurrentUserService currentUserService,
            NamedParameterJdbcTemplate jdbcTemplate
    ) {
        this.currentUserService = currentUserService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Verifies that the currently authenticated user has the 'admin' role in the DB.
     * Throws 403 Forbidden if not authorized.
     */
    private MeResponse requireAdmin() {
        MeResponse currentUser = currentUserService.getCurrentUser();
        List<String> roles = jdbcTemplate.queryForList(
                "select role_code from user_roles where user_id = :userId",
                Map.of("userId", currentUser.appUserId()),
                String.class
        );
        if (!roles.contains("admin")) {
            throw new AppException(HttpStatus.FORBIDDEN,
                    "Bạn không có quyền truy cập trang quản trị.");
        }
        return currentUser;
    }

    /**
     * GET /api/v1/admin/dashboard/stats
     * Returns overall system statistics counters.
     */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getStats() {
        requireAdmin();

        Integer totalCandidates = jdbcTemplate.queryForObject(
                "select count(*) from user_roles where role_code in ('candidate_free', 'candidate_premium')",
                Map.of(),
                Integer.class
        );

        Integer totalCompanies = jdbcTemplate.queryForObject(
                "select count(*) from companies where company_type = 'SME' and verification_status = 'APPROVED'",
                Map.of(),
                Integer.class
        );

        Integer totalClubs = jdbcTemplate.queryForObject(
                "select count(*) from companies where company_type = 'CLUB' and verification_status = 'APPROVED'",
                Map.of(),
                Integer.class
        );

        Integer totalPendingB2b = jdbcTemplate.queryForObject(
                "select count(*) from companies where verification_status = 'PENDING'",
                Map.of(),
                Integer.class
        );

        Integer totalJobs = jdbcTemplate.queryForObject(
                "select count(*) from jobs where deleted_at is null",
                Map.of(),
                Integer.class
        );

        Integer totalQuests = jdbcTemplate.queryForObject(
                "select count(*) from quests where deleted_at is null",
                Map.of(),
                Integer.class
        );

        Integer totalLogs = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs",
                Map.of(),
                Integer.class
        );

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalCandidates", totalCandidates != null ? totalCandidates : 0);
        stats.put("totalCompanies", totalCompanies != null ? totalCompanies : 0);
        stats.put("totalClubs", totalClubs != null ? totalClubs : 0);
        stats.put("totalPendingB2b", totalPendingB2b != null ? totalPendingB2b : 0);
        stats.put("totalJobs", totalJobs != null ? totalJobs : 0);
        stats.put("totalQuests", totalQuests != null ? totalQuests : 0);
        stats.put("totalLogs", totalLogs != null ? totalLogs : 0);

        return ApiResponse.success(stats);
    }

    /**
     * GET /api/v1/admin/dashboard/users
     * Returns a list of all registered users with their roles.
     */
    @GetMapping("/users")
    public ApiResponse<List<Map<String, Object>>> getUsers() {
        MeResponse currentAdmin = requireAdmin();

        List<Map<String, Object>> users = jdbcTemplate.queryForList("""
                select u.id,
                       u.email,
                       u.display_name as "displayName",
                       u.status as "userStatus",
                       c.verification_status as "companyStatus",
                       c.company_type as "companyType",
                       u.created_at as "createdAt",
                       u.auth_provider as "authProvider",
                       u.student_email_verified as "studentEmailVerified",
                       u.premium_until as "premiumUntil",
                       u.last_login_at as "lastLoginAt",
                       coalesce(
                           string_agg(ur.role_code, ', '),
                           'none'
                       ) as "roles"
                from app_users u
                left join user_roles ur on u.id = ur.user_id
                left join companies c on u.id = c.owner_user_id
                group by u.id, u.email, u.display_name, u.status, c.verification_status, c.company_type, u.created_at, u.auth_provider, u.student_email_verified, u.premium_until, u.last_login_at
                order by u.created_at desc
                """, Map.of());

        try {
            jdbcTemplate.update("""
                    insert into audit_logs (actor_user_id, action, entity_type, metadata)
                    values (
                        :adminUserId,
                        'admin.users_list.viewed',
                        'app_user',
                        jsonb_build_object('records_returned', :count)
                    )
                    """, Map.of(
                    "adminUserId", currentAdmin.appUserId(),
                    "count", users.size()
            ));
        } catch (Exception e) {
            log.warn("Failed to write users list view audit log: {}", e.getMessage());
        }

        return ApiResponse.success(users);
    }

    /**
     * GET /api/v1/admin/dashboard/jobs
     * Returns all jobs and quests combined.
     */
    @GetMapping("/jobs")
    public ApiResponse<List<Map<String, Object>>> getJobs() {
        requireAdmin();

        List<Map<String, Object>> jobs = jdbcTemplate.queryForList("""
                select j.id,
                       j.title,
                       j.job_type as "jobType",
                       j.status,
                       j.created_at as "createdAt",
                       c.name as "companyName",
                       c.company_type as "companyType",
                       'JOB' as "postType"
                from jobs j
                join companies c on j.company_id = c.id
                where j.deleted_at is null

                union all

                select q.id,
                       q.title,
                       q.category as "jobType",
                       q.status,
                       q.created_at as "createdAt",
                       c.name as "companyName",
                       c.company_type as "companyType",
                       'QUEST' as "postType"
                from quests q
                join companies c on q.company_id = c.id
                where q.deleted_at is null

                order by "createdAt" desc
                """, Map.of());

        return ApiResponse.success(jobs);
    }

    /**
     * GET /api/v1/admin/dashboard/audit-logs
     * Returns system logs.
     */
    @GetMapping("/audit-logs")
    public ApiResponse<List<Map<String, Object>>> getAuditLogs() {
        requireAdmin();

        List<Map<String, Object>> logs = jdbcTemplate.queryForList("""
                select al.id,
                       al.action,
                       al.entity_type as "entityType",
                       al.entity_id as "entityId",
                       al.metadata,
                       al.created_at as "createdAt",
                       u.email as "actorEmail",
                       u.display_name as "actorName",
                       coalesce(
                           (select string_agg(ur.role_code, ', ') from user_roles ur where ur.user_id = al.actor_user_id),
                           'none'
                       ) as "actorRoles"
                from audit_logs al
                left join app_users u on al.actor_user_id = u.id
                order by al.created_at desc
                limit 400
                """, Map.of());

        return ApiResponse.success(logs);
    }

    @PostMapping("/jobs/{id}/approve")
    public ApiResponse<String> approveJob(@PathVariable UUID id) {
        MeResponse currentAdmin = requireAdmin();

        int rows = jdbcTemplate.update("""
                update jobs
                set status = 'OPEN',
                    updated_at = now()
                where id = :jobId
                """, Map.of("jobId", id));

        if (rows == 0) {
            rows = jdbcTemplate.update("""
                    update quests
                    set status = 'OPEN',
                        updated_at = now()
                    where id = :jobId
                    """, Map.of("jobId", id));

            if (rows == 0) {
                throw new AppException(HttpStatus.NOT_FOUND, "Không tìm thấy tin tuyển dụng hoặc quest này.");
            }

            try {
                jdbcTemplate.update("""
                        insert into audit_logs (actor_user_id, action, entity_type, entity_id)
                        values (:adminUserId, 'quest.approved', 'quest', :jobId)
                        """, Map.of(
                        "adminUserId", currentAdmin.appUserId(),
                        "jobId", id
                ));
            } catch (Exception e) {
                log.warn("Failed to write quest approval audit log: {}", e.getMessage());
            }

            return ApiResponse.success("Đã duyệt quest thành công!");
        }

        try {
            jdbcTemplate.update("""
                    insert into audit_logs (actor_user_id, action, entity_type, entity_id)
                    values (:adminUserId, 'job.approved', 'job', :jobId)
                    """, Map.of(
                    "adminUserId", currentAdmin.appUserId(),
                    "jobId", id
            ));
        } catch (Exception e) {
            log.warn("Failed to write job approval audit log: {}", e.getMessage());
        }

        return ApiResponse.success("Đã duyệt tin tuyển dụng thành công!");
    }

    @PostMapping("/jobs/{id}/reject")
    public ApiResponse<String> rejectJob(
            @PathVariable UUID id,
            @RequestParam(required = false) String reason
    ) {
        MeResponse currentAdmin = requireAdmin();

        int rows = jdbcTemplate.update("""
                update jobs
                set status = 'REJECTED',
                    rejection_reason = :reason,
                    updated_at = now()
                where id = :jobId
                """, Map.of("jobId", id, "reason", reason != null ? reason : ""));

        if (rows == 0) {
            rows = jdbcTemplate.update("""
                    update quests
                    set status = 'CLOSED',
                        rejection_reason = :reason,
                        updated_at = now()
                    where id = :jobId
                    """, Map.of("jobId", id, "reason", reason != null ? reason : ""));

            if (rows == 0) {
                throw new AppException(HttpStatus.NOT_FOUND, "Không tìm thấy tin tuyển dụng hoặc quest này.");
            }

            try {
                jdbcTemplate.update("""
                        insert into audit_logs (actor_user_id, action, entity_type, entity_id, metadata)
                        values (:adminUserId, 'quest.rejected', 'quest', :jobId, jsonb_build_object('reason', :reason))
                        """, Map.of(
                        "adminUserId", currentAdmin.appUserId(),
                        "jobId", id,
                        "reason", reason != null ? reason : ""
                ));
            } catch (Exception e) {
                log.warn("Failed to write quest rejection audit log: {}", e.getMessage());
            }

            return ApiResponse.success("Đã từ chối quest!");
        }

        try {
            jdbcTemplate.update("""
                    insert into audit_logs (actor_user_id, action, entity_type, entity_id, metadata)
                    values (:adminUserId, 'job.rejected', 'job', :jobId, jsonb_build_object('reason', :reason))
                    """, Map.of(
                    "adminUserId", currentAdmin.appUserId(),
                    "jobId", id,
                    "reason", reason != null ? reason : ""
            ));
        } catch (Exception e) {
            log.warn("Failed to write job rejection audit log: {}", e.getMessage());
        }

        return ApiResponse.success("Đã từ chối tin tuyển dụng!");
    }
}

