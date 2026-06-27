package com.nextplease.backend.controller;

import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.dto.response.MeResponse;
import com.nextplease.backend.exception.AppException;
import com.nextplease.backend.service.CurrentUserService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/reviews")
public class AdminReviewController {

    private static final Logger log = LoggerFactory.getLogger(AdminReviewController.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;

    public AdminReviewController(NamedParameterJdbcTemplate jdbcTemplate, CurrentUserService currentUserService) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
    }

    private MeResponse requireAdmin() {
        return currentUserService.requireAdmin();
    }

    @PostMapping("/claim")
    public ApiResponse<String> claimReview(@RequestBody Map<String, String> body) {
        MeResponse admin = requireAdmin();
        String itemType = body.get("itemType");
        String itemIdStr = body.get("itemId");
        if (itemType == null || itemIdStr == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Thiếu thông tin itemType hoặc itemId.");
        }
        UUID itemId = UUID.fromString(itemIdStr);
        UUID adminId = admin.appUserId();

        // Check if already claimed by someone else
        List<Map<String, Object>> existing = jdbcTemplate.queryForList("""
                select ar.claimed_by_admin_id, u.display_name
                from admin_reviews ar
                left join app_users u on u.id = ar.claimed_by_admin_id
                where ar.item_type = :itemType and ar.item_id = :itemId
                """, Map.of("itemType", itemType, "itemId", itemId));

        if (!existing.isEmpty()) {
            Map<String, Object> review = existing.get(0);
            UUID claimedBy = (UUID) review.get("claimed_by_admin_id");
            if (claimedBy != null && !claimedBy.equals(adminId)) {
                String adminName = (String) review.get("display_name");
                if (adminName == null || adminName.isBlank()) {
                    adminName = "một Admin khác";
                }
                throw new AppException(HttpStatus.CONFLICT, "Mục này đang được nhận duyệt bởi " + adminName);
            }
        }

        jdbcTemplate.update("""
                insert into admin_reviews (item_type, item_id, claimed_by_admin_id, claimed_at, updated_at)
                values (:itemType, :itemId, :adminId, now(), now())
                on conflict (item_type, item_id)
                do update set
                    claimed_by_admin_id = :adminId,
                    claimed_at = now(),
                    updated_at = now()
                """, Map.of("itemType", itemType, "itemId", itemId, "adminId", adminId));

        log.info("Admin {} claimed review for itemType={}, itemId={}", admin.email(), itemType, itemId);
        return ApiResponse.success("Đã nhận duyệt mục này thành công!");
    }

    @PostMapping("/unclaim")
    public ApiResponse<String> unclaimReview(@RequestBody Map<String, String> body) {
        requireAdmin();
        String itemType = body.get("itemType");
        String itemIdStr = body.get("itemId");
        if (itemType == null || itemIdStr == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Thiếu thông tin itemType hoặc itemId.");
        }
        UUID itemId = UUID.fromString(itemIdStr);

        jdbcTemplate.update("""
                update admin_reviews
                set claimed_by_admin_id = null,
                    claimed_at = null,
                    updated_at = now()
                where item_type = :itemType and item_id = :itemId
                """, Map.of("itemType", itemType, "itemId", itemId));

        log.info("Released review claim for itemType={}, itemId={}", itemType, itemId);
        return ApiResponse.success("Đã giải phóng mục này về hàng chờ chung.");
    }

    @PostMapping("/notes")
    public ApiResponse<String> updateNotes(@RequestBody Map<String, String> body) {
        MeResponse admin = requireAdmin();
        String itemType = body.get("itemType");
        String itemIdStr = body.get("itemId");
        String notes = body.get("notes");
        if (itemType == null || itemIdStr == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Thiếu thông tin itemType hoặc itemId.");
        }
        UUID itemId = UUID.fromString(itemIdStr);

        jdbcTemplate.update("""
                insert into admin_reviews (item_type, item_id, internal_notes, updated_at)
                values (:itemType, :itemId, :notes, now())
                on conflict (item_type, item_id)
                do update set
                    internal_notes = :notes,
                    updated_at = now()
                """, Map.of("itemType", itemType, "itemId", itemId, "notes", notes != null ? notes : ""));

        log.info("Admin {} updated review notes for itemType={}, itemId={}", admin.email(), itemType, itemId);
        return ApiResponse.success("Đã cập nhật ghi chú nội bộ thành công!");
    }
}
