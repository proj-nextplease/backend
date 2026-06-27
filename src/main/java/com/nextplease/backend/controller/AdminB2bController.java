package com.nextplease.backend.controller;

import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.dto.response.MeResponse;
import com.nextplease.backend.exception.AppException;
import com.nextplease.backend.service.AdminB2bService;
import com.nextplease.backend.service.CurrentUserService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only B2B review controller.
 *
 * Authorization strategy: We perform a DB-level role check rather than relying solely on
 * @PreAuthorize("hasRole('admin')") because the Supabase JWT may be issued before app_metadata
 * is synced (on first login). The DB is the source of truth for roles.
 */
@RestController
@RequestMapping("/api/v1/admin/b2b")
public class AdminB2bController {

    private static final Logger log = LoggerFactory.getLogger(AdminB2bController.class);

    private final AdminB2bService adminB2bService;
    private final CurrentUserService currentUserService;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final com.nextplease.backend.service.CompanyMembershipService membershipService;

    public AdminB2bController(
            AdminB2bService adminB2bService,
            CurrentUserService currentUserService,
            NamedParameterJdbcTemplate jdbcTemplate,
            com.nextplease.backend.service.CompanyMembershipService membershipService
    ) {
        this.adminB2bService = adminB2bService;
        this.currentUserService = currentUserService;
        this.jdbcTemplate = jdbcTemplate;
        this.membershipService = membershipService;
    }

    /**
     * Verifies that the currently authenticated user has the 'admin' role in the DB.
     * Throws 403 if not authorized.
     */
    private MeResponse requireAdmin() {
        return currentUserService.requireAdmin();
    }

    @GetMapping("/pending")
    public ApiResponse<List<Map<String, Object>>> getPendingRegistrations() {
        MeResponse currentAdmin = requireAdmin();
        List<Map<String, Object>> list = adminB2bService.getPendingRegistrations();

        try {
            jdbcTemplate.update("""
                    insert into audit_logs (actor_user_id, action, entity_type, metadata)
                    values (
                        :adminUserId,
                        'admin.b2b_pending.viewed',
                        'company',
                        jsonb_build_object('records_returned', :count)
                    )
                    """, Map.of(
                    "adminUserId", currentAdmin.appUserId(),
                    "count", list.size()
            ));
        } catch (Exception e) {
            log.warn("Failed to write B2B pending view audit log: {}", e.getMessage());
        }

        return ApiResponse.success(list);
    }

    @PostMapping("/approve/{companyId}")
    public ApiResponse<String> approveB2b(@PathVariable UUID companyId) {
        MeResponse currentAdmin = requireAdmin();
        adminB2bService.approveB2b(companyId, currentAdmin.appUserId());
        return ApiResponse.success("Đã phê duyệt hoạt động cho tổ chức đối tác thành công!");
    }

    @PostMapping("/reject/{companyId}")
    public ApiResponse<String> rejectB2b(@PathVariable UUID companyId, @RequestBody Map<String, String> body) {
        MeResponse currentAdmin = requireAdmin();
        String reason = body.get("reason");
        adminB2bService.rejectB2b(companyId, reason, currentAdmin.appUserId());
        return ApiResponse.success("Đã từ chối phê duyệt tổ chức đối tác.");
    }

    /**
     * Provisions an APPROVED company shell and emails an OWNER invitation to the representative.
     * This replaces self-service B2B registration: the platform grants access to one person,
     * who can then delegate/transfer to teammates.
     */
    @PostMapping("/provision")
    public ApiResponse<Map<String, Object>> provisionCompany(@RequestBody Map<String, String> body) {
        MeResponse currentAdmin = requireAdmin();
        Map<String, Object> result = membershipService.provisionCompany(
                currentAdmin.appUserId(),
                body.get("name"),
                body.get("companyType"),
                body.get("representativeEmail"));
        return ApiResponse.success(result);
    }
}
