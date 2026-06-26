package com.nextplease.backend.service;

import com.nextplease.backend.exception.AppException;
import com.nextplease.backend.exception.ResourceNotFoundException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
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

/**
 * Manages the "Proof of Work" credential submission and verification flow.
 *
 * Submit → PENDING → Admin approves → APPROVED + EXP/RS awarded
 *                  → Admin rejects  → REJECTED + reason stored
 *
 * EXP per category: CLUB_SMALL=100, SCHOOL_CAMPAIGN=300, others=500
 * RS per role_level: MEMBER=+5, LEADER=+10
 */
@Service
public class CredentialService {

    private static final Logger log = LoggerFactory.getLogger(CredentialService.class);

    private static final Map<String, Integer> EXP_BY_CATEGORY = Map.of(
            "CLUB_SMALL", 100,
            "SCHOOL_CAMPAIGN", 300,
            "COMPANY_PROJECT", 500,
            "SHORT_INTERNSHIP", 500,
            "FREELANCE_GIG", 500
    );

    private static final Map<String, Integer> RS_BY_ROLE = Map.of(
            "MEMBER", 5,
            "LEADER", 10
    );

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ReputationService reputationService;
    private final ExpService expService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final NotificationService notificationService;
    private final ConfigService configService;

    public CredentialService(
            NamedParameterJdbcTemplate jdbcTemplate,
            ReputationService reputationService,
            ExpService expService,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            NotificationService notificationService,
            ConfigService configService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.reputationService = reputationService;
        this.expService = expService;
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
        this.configService = configService;
    }

    /** Resolve the app_users id that owns a profile (notification recipient). */
    private UUID profileOwnerUserId(UUID profileId) {
        try {
            return jdbcTemplate.queryForObject(
                    "select user_id from profiles where id = :id", Map.of("id", profileId), UUID.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** Candidate submits a proof-of-work experience for review. */
    @Transactional
    public Map<String, Object> submit(UUID userId, SubmitCredentialRequest req) {
        validateCategory(req.category());
        validateRoleLevel(req.roleLevel());
        validateSubmittedDates(req.startedAt(), req.endedAt());

        UUID profileId = getProfileIdOrThrow(userId);

        UUID expId = jdbcTemplate.queryForObject("""
                insert into experiences (
                    profile_id, project_name, position, category, role_level,
                    description, proof_link, proof_images, started_at, ended_at,
                    verification_status, source, created_at, updated_at
                ) values (
                    :profileId, :projectName, :position, :category, :roleLevel,
                    :description, :proofLink, :proofImages::jsonb,
                    cast(:startedAt as date), cast(:endedAt as date),
                    'PENDING', 'CREDENTIAL', now(), now()
                )
                returning id
                """, new MapSqlParameterSource()
                .addValue("profileId", profileId)
                .addValue("projectName", req.projectName())
                .addValue("position", req.position())
                .addValue("category", req.category())
                .addValue("roleLevel", req.roleLevel())
                .addValue("description", req.description())
                .addValue("proofLink", req.proofLink())
                .addValue("proofImages", serializeImages(req.proofImages()))
                .addValue("startedAt", toFirstOfMonth(req.startedAt()))
                .addValue("endedAt", toFirstOfMonth(req.endedAt())),
                UUID.class);

        log.info("[CredentialService] User {} submitted credential, experienceId={}, category={}, role={}",
                userId, expId, req.category(), req.roleLevel());

        notificationService.notifyAdmins("EXPERIENCE_PENDING",
                "Minh chứng mới chờ xác thực",
                "Có kinh nghiệm \"" + req.position() + "\" vừa được nộp vào hàng chờ xác thực.",
                "/nextplease-admin-portal/b2b-reviews");

        return Map.of("experienceId", expId, "status", "PENDING");
    }

    public List<Map<String, Object>> getMySubmissions(UUID userId) {
        UUID profileId = getProfileIdOrThrow(userId);
        return jdbcTemplate.queryForList("""
                select id, project_name, position, category, role_level,
                       description, proof_link, proof_images::text as proof_images, verification_status,
                       reject_reason, started_at, ended_at, created_at,
                       express_verification as "expressVerification"
                from experiences
                where profile_id = :profileId
                  and deleted_at is null
                order by created_at desc
                """, Map.of("profileId", profileId));
    }

    /** Admin approves an experience — awards EXP and RS in the same transaction. */
    @Transactional
    public void approve(UUID experienceId, UUID adminUserId, String note) {
        Map<String, Object> exp = lockAndGetExperience(experienceId);
        requireStatus(exp, "PENDING");

        UUID profileId = (UUID) exp.get("profile_id");
        String category = (String) exp.get("category");
        String roleLevel = (String) exp.get("role_level");

        int expPoints = EXP_BY_CATEGORY.getOrDefault(category, 100);
        int rsPoints = RS_BY_ROLE.getOrDefault(roleLevel, 5);
        String reasonCode = "EXPERIENCE_APPROVED_" + category;

        // Mark experience approved
        jdbcTemplate.update("""
                update experiences
                set verification_status = 'APPROVED',
                    verified_by         = :adminUserId,
                    verified_at         = now(),
                    reject_reason       = null,
                    updated_at          = now()
                where id = :id
                """, Map.of("id", experienceId, "adminUserId", adminUserId));

        // Award EXP — idempotent: source = experience
        expService.addExp(profileId, expPoints, reasonCode, "experience", experienceId);

        // Award RS — idempotent: source = experience
        reputationService.addReputation(profileId, rsPoints, reasonCode, "experience", experienceId);

        // Log the review decision
        writeReview(experienceId, adminUserId, "APPROVED", note);

        log.info("[CredentialService] Admin {} approved experience {} → +{} EXP, +{} RS, profile {}",
                adminUserId, experienceId, expPoints, rsPoints, profileId);

        notificationService.notify(profileOwnerUserId(profileId), "EXPERIENCE_APPROVED",
                "Kinh nghiệm đã được xác thực ✅",
                "Kinh nghiệm \"" + exp.get("position") + "\" của bạn đã được duyệt: +" + expPoints + " EXP, +" + rsPoints + " RS.",
                "/portfolio", true);
    }

    /** Admin rejects an experience. */
    @Transactional
    public void reject(UUID experienceId, UUID adminUserId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Lý do từ chối không được để trống.");
        }

        Map<String, Object> exp = lockAndGetExperience(experienceId);
        requireStatus(exp, "PENDING");

        jdbcTemplate.update("""
                update experiences
                set verification_status = 'REJECTED',
                    verified_by         = :adminUserId,
                    verified_at         = now(),
                    reject_reason       = :reason,
                    updated_at          = now()
                where id = :id
                """, Map.of("id", experienceId, "adminUserId", adminUserId, "reason", reason));

        writeReview(experienceId, adminUserId, "REJECTED", reason);

        log.info("[CredentialService] Admin {} rejected experience {} reason={}",
                adminUserId, experienceId, reason);

        // Refund the Express Verification fee if it was paid — the express promise (verify
        // in 24h) cannot be honored for a rejected experience, so the NP is returned.
        boolean refunded = refundExpressFeeIfPaid(experienceId, exp);

        UUID ownerUserId = profileOwnerUserId((UUID) exp.get("profile_id"));
        String refundNote = refunded
                ? " Phí Xác thực nhanh đã được hoàn lại vào ví NP của bạn."
                : "";
        notificationService.notify(ownerUserId, "EXPERIENCE_REJECTED",
                "Kinh nghiệm chưa được duyệt",
                "Kinh nghiệm \"" + exp.get("position") + "\" của bạn bị từ chối. Lý do: " + reason + refundNote,
                "/portfolio", true);
    }

    /**
     * Refunds the Express Verification fee (idempotent) when a paid experience is rejected.
     * Returns true if a refund was actually credited.
     */
    private boolean refundExpressFeeIfPaid(UUID experienceId, Map<String, Object> exp) {
        Object expressRaw = exp.get("express_verification");
        boolean wasExpress = expressRaw instanceof Boolean b && b;
        if (!wasExpress) return false;

        int price = configService.getInt("express_verification_price_np", 25000);
        UUID profileId = (UUID) exp.get("profile_id");
        UUID ownerUserId = profileOwnerUserId(profileId);
        if (ownerUserId == null) return false;

        try {
            Map<String, Object> wallet = jdbcTemplate.queryForMap(
                    "select id, np_balance from wallets where user_id = :userId for update",
                    Map.of("userId", ownerUserId));
            int balance = ((Number) wallet.get("np_balance")).intValue();
            int newBalance = balance + price;

            // Idempotent: unique idempotency_key prevents double refund for the same experience.
            int inserted = jdbcTemplate.update("""
                    insert into wallet_transactions (wallet_id, amount_np, balance_after_np, transaction_type, reason, source_type, source_id, idempotency_key)
                    values (:walletId, :amount, :balanceAfter, 'REFUND', :reason, 'experience', :sourceId, :ikey)
                    on conflict (idempotency_key) where idempotency_key is not null do nothing
                    """, new MapSqlParameterSource()
                    .addValue("walletId", wallet.get("id"))
                    .addValue("amount", price)
                    .addValue("balanceAfter", newBalance)
                    .addValue("reason", String.format("Hoàn phí Xác thực nhanh (kinh nghiệm bị từ chối): %s", exp.get("project_name")))
                    .addValue("sourceId", experienceId)
                    .addValue("ikey", "express_refund_" + experienceId));

            if (inserted == 0) {
                // Already refunded previously — do not credit again.
                return false;
            }

            jdbcTemplate.update("update wallets set np_balance = :balance, updated_at = now() where id = :walletId",
                    Map.of("balance", newBalance, "walletId", wallet.get("id")));

            log.info("[CredentialService] Refunded {} NP express fee for rejected experience {} to user {}",
                    price, experienceId, ownerUserId);
            return true;
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            log.warn("[CredentialService] Cannot refund express fee — wallet not found for user {}", ownerUserId);
            return false;
        }
    }

    /** Admin fetch: all PENDING experiences across all users (newest first). */
    public List<Map<String, Object>> getPendingQueue(UUID currentAdminId) {
        return jdbcTemplate.queryForList("""
                select e.id,
                       e.project_name,
                       e.position,
                       e.category,
                       e.role_level,
                       e.description,
                       e.proof_link,
                       e.proof_images::text as proof_images,
                       e.verification_status,
                       e.started_at,
                       e.ended_at,
                       e.created_at,
                       e.express_verification as "expressVerification",
                       u.email         as candidate_email,
                       u.display_name  as candidate_name,
                       p.reputation_score,
                       p.total_exp,
                       p.current_level,
                       ar.claimed_by_admin_id as "claimedByAdminId",
                       admin_u.display_name as "claimedByAdminName",
                       (ar.claimed_by_admin_id is not null and ar.claimed_by_admin_id = :me) as "claimedByMe",
                       ar.claimed_at as "claimedAt",
                       ar.internal_notes as "internalNotes"
                from experiences e
                join profiles p on p.id = e.profile_id
                join app_users u on u.id = p.user_id
                left join admin_reviews ar on ar.item_id = e.id and ar.item_type = 'EXPERIENCE'
                left join app_users admin_u on ar.claimed_by_admin_id = admin_u.id
                where e.verification_status = 'PENDING'
                  and e.deleted_at is null
                order by e.express_verification desc, e.created_at asc
                """, Map.of("me", currentAdminId));
    }

    /** Admin fetch: ALL submissions across all users, every status (newest first). */
    public List<Map<String, Object>> getAllSubmissions(UUID currentAdminId) {
        return jdbcTemplate.queryForList("""
                select e.id,
                       e.project_name,
                       e.position,
                       e.category,
                       e.role_level,
                       e.description,
                       e.proof_link,
                       e.proof_images::text as proof_images,
                       e.verification_status,
                       e.reject_reason,
                       e.verified_at,
                       e.started_at,
                       e.ended_at,
                       e.created_at,
                       e.express_verification as "expressVerification",
                       u.email         as candidate_email,
                       u.display_name  as candidate_name,
                       p.reputation_score,
                       p.total_exp,
                       p.current_level,
                       ar.claimed_by_admin_id as "claimedByAdminId",
                       admin_u.display_name as "claimedByAdminName",
                       (ar.claimed_by_admin_id is not null and ar.claimed_by_admin_id = :me) as "claimedByMe",
                       ar.claimed_at as "claimedAt",
                       ar.internal_notes as "internalNotes"
                from experiences e
                join profiles p on p.id = e.profile_id
                join app_users u on u.id = p.user_id
                left join admin_reviews ar on ar.item_id = e.id and ar.item_type = 'EXPERIENCE'
                left join app_users admin_u on ar.claimed_by_admin_id = admin_u.id
                where e.deleted_at is null
                order by e.created_at desc
                """, Map.of("me", currentAdminId));
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private UUID getProfileIdOrThrow(UUID userId) {
        try {
            return jdbcTemplate.queryForObject(
                    "select id from profiles where user_id = :userId",
                    Map.of("userId", userId), UUID.class);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Hồ sơ ứng viên không tồn tại.");
        }
    }

    private Map<String, Object> lockAndGetExperience(UUID experienceId) {
        try {
            return jdbcTemplate.queryForMap(
                    "select * from experiences where id = :id for update",
                    Map.of("id", experienceId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Minh chứng không tồn tại: " + experienceId);
        }
    }

    private void requireStatus(Map<String, Object> exp, String expected) {
        String current = (String) exp.get("verification_status");
        if (!expected.equals(current)) {
            throw new AppException(HttpStatus.CONFLICT,
                    "Minh chứng không ở trạng thái " + expected + " (hiện tại: " + current + ").");
        }
    }

    private void validateCategory(String category) {
        if (!EXP_BY_CATEGORY.containsKey(category)) {
            throw new AppException(HttpStatus.BAD_REQUEST,
                    "Loại hình không hợp lệ: " + category +
                    ". Cho phép: " + String.join(", ", EXP_BY_CATEGORY.keySet()));
        }
    }

    private void validateRoleLevel(String roleLevel) {
        if (!RS_BY_ROLE.containsKey(roleLevel)) {
            throw new AppException(HttpStatus.BAD_REQUEST,
                    "Vai trò không hợp lệ: " + roleLevel + ". Cho phép: MEMBER, LEADER");
        }
    }

    private void writeReview(UUID experienceId, UUID reviewerId, String decision, String note) {
        jdbcTemplate.update("""
                insert into verification_reviews (experience_id, reviewer_user_id, decision, note)
                values (:experienceId, :reviewerId, :decision, :note)
                """, Map.of(
                "experienceId", experienceId,
                "reviewerId", reviewerId,
                "decision", decision,
                "note", note != null ? note : ""
        ));
    }

    private static void validateSubmittedDates(String startedAt, String endedAt) {
        LocalDate startDate = parseSubmittedMonth(startedAt, "Thời gian bắt đầu");
        LocalDate endDate = parseSubmittedMonth(endedAt, "Thời gian kết thúc");
        LocalDate currentMonth = YearMonth.now().atDay(1);

        if (startDate != null && startDate.isAfter(currentMonth)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Thời gian bắt đầu không được sau tháng hiện tại.");
        }
        if (endDate != null && endDate.isAfter(currentMonth)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Thời gian kết thúc không được sau tháng hiện tại.");
        }
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Thời gian kết thúc không được trước thời gian bắt đầu.");
        }
    }

    private static LocalDate parseSubmittedMonth(String value, String fieldLabel) {
        if (value == null || value.isBlank()) return null;
        try {
            if (value.length() == 7) {
                return YearMonth.parse(value).atDay(1);
            }
            if (value.length() == 10) {
                return LocalDate.parse(value).withDayOfMonth(1);
            }
        } catch (DateTimeParseException ex) {
            throw new AppException(HttpStatus.BAD_REQUEST, fieldLabel + " không đúng định dạng YYYY-MM.");
        }
        throw new AppException(HttpStatus.BAD_REQUEST, fieldLabel + " không đúng định dạng YYYY-MM.");
    }

    /** Converts "YYYY-MM" (HTML month input) or "YYYY-MM-DD" to "YYYY-MM-01" for PostgreSQL date. */
    private static String toFirstOfMonth(String value) {
        if (value == null || value.isBlank()) return null;
        // Already full date
        if (value.length() == 10) return value;
        // "YYYY-MM" → "YYYY-MM-01"
        if (value.length() == 7) return value + "-01";
        return null;
    }

    /** Immutable record used as request DTO inside this service. */
    public record SubmitCredentialRequest(
            String projectName,
            String position,
            String category,
            String roleLevel,
            String description,
            String proofLink,
            java.util.List<String> proofImages,
            String startedAt,
            String endedAt
    ) {}

    private String serializeImages(java.util.List<String> images) {
        if (images == null || images.isEmpty()) return null;
        java.util.List<String> clean = images.stream()
                .filter(s -> s != null && !s.isBlank())
                .limit(6)
                .toList();
        if (clean.isEmpty()) return null;
        try { return objectMapper.writeValueAsString(clean); } catch (Exception e) { return null; }
    }
}
