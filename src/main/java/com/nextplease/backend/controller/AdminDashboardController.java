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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final com.nextplease.backend.service.CredentialService credentialService;
    private final com.nextplease.backend.service.SupabaseAdminService supabaseAdminService;
    private final com.nextplease.backend.service.NotificationService notificationService;

    public AdminDashboardController(
            CurrentUserService currentUserService,
            NamedParameterJdbcTemplate jdbcTemplate,
            com.nextplease.backend.service.CredentialService credentialService,
            com.nextplease.backend.service.SupabaseAdminService supabaseAdminService,
            com.nextplease.backend.service.NotificationService notificationService
    ) {
        this.currentUserService = currentUserService;
        this.jdbcTemplate = jdbcTemplate;
        this.credentialService = credentialService;
        this.supabaseAdminService = supabaseAdminService;
        this.notificationService = notificationService;
    }

    /** Notify the post owner about an admin moderation decision (fail-soft). */
    private void notifyPostOwner(String table, java.util.UUID postId, boolean approved, String reason) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "select created_by, title from " + table + " where id = :id", Map.of("id", postId));
            Object owner = row.get("created_by");
            if (!(owner instanceof java.util.UUID ownerId)) return;
            String title = (String) row.get("title");
            boolean isQuest = "quests".equals(table);
            String label = isQuest ? "Quest" : "Tin tuyển dụng";
            notificationService.notify(ownerId, "POST_MODERATION",
                    approved ? label + " đã được duyệt ✅" : label + " bị từ chối",
                    "\"" + title + "\" " + (approved ? "đã được duyệt và hiển thị công khai."
                            : "đã bị từ chối." + (reason != null && !reason.isBlank() ? " Lý do: " + reason : "")),
                    "/businesses/dashboard", true);
        } catch (Exception e) {
            log.warn("Failed to notify post owner for {} {}: {}", table, postId, e.getMessage());
        }
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

            notifyPostOwner("quests", id, true, null);
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

        notifyPostOwner("jobs", id, true, null);
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
                    set status = 'REJECTED',
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

            notifyPostOwner("quests", id, false, reason);
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

        notifyPostOwner("jobs", id, false, reason);
        return ApiResponse.success("Đã từ chối tin tuyển dụng!");
    }

    // ── Verification Queue ───────────────────────────────────────────────────

    /** GET /api/v1/admin/dashboard/verification-queue — all PENDING proof submissions */
    @GetMapping("/verification-queue")
    public ApiResponse<List<Map<String, Object>>> getVerificationQueue() {
        requireAdmin();
        return ApiResponse.success(credentialService.getPendingQueue());
    }

    /** POST /api/v1/admin/dashboard/verification-queue/{id}/approve */
    @PostMapping("/verification-queue/{id}/approve")
    public ApiResponse<String> approveCredential(
            @PathVariable UUID id,
            @RequestParam(required = false) String note
    ) {
        MeResponse admin = requireAdmin();
        credentialService.approve(id, admin.appUserId(), note);
        return ApiResponse.success("Đã phê duyệt minh chứng và cộng EXP/RS cho ứng viên.");
    }

    /** POST /api/v1/admin/dashboard/verification-queue/{id}/reject */
    @PostMapping("/verification-queue/{id}/reject")
    public ApiResponse<String> rejectCredential(
            @PathVariable UUID id,
            @RequestParam String reason
    ) {
        MeResponse admin = requireAdmin();
        credentialService.reject(id, admin.appUserId(), reason);
        return ApiResponse.success("Đã từ chối minh chứng.");
    }

    // ── User Moderation ──────────────────────────────────────────────────────

    /** PATCH /api/v1/admin/dashboard/users/{id}/status — freeze, ban, or unfreeze a user */
    @PatchMapping("/users/{id}/status")
    public ApiResponse<String> updateUserStatus(
            @PathVariable UUID id,
            @RequestBody UserStatusRequest body
    ) {
        MeResponse admin = requireAdmin();
        List<String> allowed = List.of("FROZEN", "BANNED", "ACTIVE");
        if (!allowed.contains(body.status())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Trạng thái không hợp lệ: " + body.status());
        }

        // Prevent admin from modifying themselves
        if (id.equals(admin.appUserId())) {
            throw new AppException(HttpStatus.FORBIDDEN, "Không thể thay đổi trạng thái tài khoản của chính mình.");
        }

        int rows = jdbcTemplate.update("""
                update app_users
                set status = :status, updated_at = now()
                where id = :userId
                """, Map.of("status", body.status(), "userId", id));

        if (rows == 0) {
            throw new AppException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng.");
        }

        String actionVerb = switch (body.status()) {
            case "FROZEN" -> "đóng băng";
            case "BANNED"  -> "cấm";
            case "ACTIVE"  -> "kích hoạt lại";
            default -> "cập nhật";
        };

        try {
            jdbcTemplate.update("""
                    insert into audit_logs (actor_user_id, action, entity_type, entity_id, metadata)
                    values (:adminId, :action, 'app_user', :targetId,
                            jsonb_build_object('new_status', :status, 'reason', :reason))
                    """, new MapSqlParameterSource()
                    .addValue("adminId", admin.appUserId())
                    .addValue("action", "admin.user." + body.status().toLowerCase())
                    .addValue("targetId", id)
                    .addValue("status", body.status())
                    .addValue("reason", body.reason() != null ? body.reason() : ""));
        } catch (Exception e) {
            log.warn("Failed to write user status audit log: {}", e.getMessage());
        }

        return ApiResponse.success("Đã " + actionVerb + " tài khoản người dùng.");
    }

    /**
     * DELETE /api/v1/admin/dashboard/users/{id} — Admin xóa tài khoản người dùng.
     * Cách an toàn: thu hồi mọi quyền tổ chức + đánh dấu status=DELETED + xóa tài khoản Supabase Auth
     * (không đăng nhập được nữa). KHÔNG purge vật lý toàn bộ dữ liệu để tránh phá vỡ ràng buộc/lịch sử.
     * Chặn: tự xóa mình, xóa admin khác, xóa người đang là OWNER của một tổ chức (phải chuyển quyền trước).
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/users/{id}")
    @org.springframework.transaction.annotation.Transactional
    public ApiResponse<String> deleteUser(@PathVariable UUID id) {
        MeResponse admin = requireAdmin();
        if (id.equals(admin.appUserId())) {
            throw new AppException(HttpStatus.FORBIDDEN, "Không thể xóa tài khoản của chính mình.");
        }

        try {
            java.util.UUID supabaseUserId;
            try {
                supabaseUserId = jdbcTemplate.queryForObject(
                        "select supabase_user_id from app_users where id = :id", Map.of("id", id), java.util.UUID.class);
            } catch (org.springframework.dao.EmptyResultDataAccessException e) {
                throw new AppException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng.");
            }

            List<String> targetRoles = jdbcTemplate.queryForList(
                    "select role_code from user_roles where user_id = :id", Map.of("id", id), String.class);
            if (targetRoles.contains("admin")) {
                throw new AppException(HttpStatus.FORBIDDEN, "Không thể xóa tài khoản Quản trị viên khác.");
            }

            Integer ownsCompanies = jdbcTemplate.queryForObject(
                    "select count(*) from companies where owner_user_id = :id", Map.of("id", id), Integer.class);
            if (ownsCompanies != null && ownsCompanies > 0) {
                throw new AppException(HttpStatus.CONFLICT,
                        "Người dùng đang là Chủ sở hữu của một tổ chức. Hãy chuyển quyền sở hữu (hoặc xử lý tổ chức đó) trước khi xóa.");
            }

            // Thu hồi mọi quyền truy cập tổ chức (đã cấp / đang chờ)
            jdbcTemplate.update("""
                    update authority_nodes set status = 'REJECTED', deleted_at = now(), updated_at = now()
                    where user_id = :id and status <> 'REJECTED' and deleted_at is null
                    """, Map.of("id", id));

            // Đánh dấu tài khoản đã xóa (login bị chặn ở BE vì status != ACTIVE)
            jdbcTemplate.update("""
                    update app_users set status = 'DELETED', deleted_at = now(), updated_at = now()
                    where id = :id
                    """, Map.of("id", id));

            // Xóa tài khoản trên Supabase Auth để không thể đăng nhập lại
            try {
                if (supabaseUserId != null) {
                    supabaseAdminService.deleteUser(supabaseUserId);
                }
            } catch (Exception e) {
                log.warn("Failed to delete Supabase Auth user {}: {}", supabaseUserId, e.getMessage());
            }

            try {
                jdbcTemplate.update("""
                        insert into audit_logs (actor_user_id, action, entity_type, entity_id, metadata)
                        values (:adminId, 'admin.user.deleted', 'app_user', :targetId, '{}'::jsonb)
                        """, Map.of("adminId", admin.appUserId(), "targetId", id));
            } catch (Exception e) {
                log.warn("Failed to write user delete audit log: {}", e.getMessage());
            }

            return ApiResponse.success("Đã xóa tài khoản người dùng và thu hồi mọi quyền truy cập.");
        } catch (AppException ae) {
            throw ae;
        } catch (Exception e) {
            log.error("deleteUser failed for id={}", id, e);
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Không thể xóa tài khoản: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    /** POST /api/v1/admin/dashboard/users/{id}/fraud-flag — flag a user for fraud */
    @PostMapping("/users/{id}/fraud-flag")
    public ApiResponse<String> createFraudFlag(
            @PathVariable UUID id,
            @RequestBody FraudFlagRequest body
    ) {
        MeResponse admin = requireAdmin();

        jdbcTemplate.update("""
                insert into fraud_flags
                    (user_id, created_by, reason_code, severity, evidence, status)
                values
                    (:userId, :createdBy, :reasonCode, :severity, :evidence::jsonb, 'ACTIVE')
                """, new MapSqlParameterSource()
                .addValue("userId", id)
                .addValue("createdBy", admin.appUserId())
                .addValue("reasonCode", body.reasonCode())
                .addValue("severity", body.severity() != null ? body.severity() : "LOW")
                .addValue("evidence", body.evidence() != null ? body.evidence() : "{}"));

        return ApiResponse.success("Đã gắn cờ gian lận cho người dùng.");
    }

    /** GET /api/v1/admin/dashboard/fraud-flags — list active fraud flags */
    @GetMapping("/fraud-flags")
    public ApiResponse<List<Map<String, Object>>> getActiveFraudFlags() {
        requireAdmin();

        List<Map<String, Object>> flags = jdbcTemplate.queryForList("""
                select
                    ff.id,
                    ff.reason_code   as "reasonCode",
                    ff.severity,
                    ff.status,
                    ff.evidence,
                    ff.created_at    as "createdAt",
                    u.id             as "userId",
                    u.email          as "userEmail",
                    u.display_name   as "userName",
                    u.status         as "userStatus",
                    r.email          as "reportedByEmail"
                from fraud_flags ff
                join app_users u on u.id = ff.user_id
                left join app_users r on r.id = ff.created_by
                where ff.status = 'ACTIVE'
                order by ff.created_at desc
                limit 100
                """, Map.of());

        return ApiResponse.success(flags);
    }

    /** PATCH /api/v1/admin/dashboard/fraud-flags/{id}/resolve */
    @PatchMapping("/fraud-flags/{id}/resolve")
    public ApiResponse<String> resolveFraudFlag(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "RESOLVED") String resolution
    ) {
        requireAdmin();
        List<String> allowed = List.of("RESOLVED", "FALSE_POSITIVE");
        if (!allowed.contains(resolution)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Kết quả không hợp lệ: " + resolution);
        }

        int rows = jdbcTemplate.update("""
                update fraud_flags set status = :status, updated_at = now()
                where id = :id
                """, Map.of("id", id, "status", resolution));

        if (rows == 0) {
            throw new AppException(HttpStatus.NOT_FOUND, "Không tìm thấy fraud flag.");
        }

        return ApiResponse.success("Đã cập nhật fraud flag → " + resolution);
    }

    // ── Request records ───────────────────────────────────────────────────────

    record UserStatusRequest(String status, String reason) {}
    record FraudFlagRequest(String reasonCode, String severity, String evidence) {}
}

