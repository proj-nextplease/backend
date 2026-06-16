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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
                       u.status,
                       u.created_at as "createdAt",
                       coalesce(
                           string_agg(ur.role_code, ', '),
                           'none'
                       ) as "roles"
                from app_users u
                left join user_roles ur on u.id = ur.user_id
                group by u.id, u.email, u.display_name, u.status, u.created_at
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
                       u.email as "actorEmail"
                from audit_logs al
                left join app_users u on al.actor_user_id = u.id
                order by al.created_at desc
                limit 200
                """, Map.of());

        return ApiResponse.success(logs);
    }
}
