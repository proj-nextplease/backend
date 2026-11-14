package com.nextplease.backend.service;

import com.nextplease.backend.exception.AppException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Đại sứ thương hiệu: ứng viên được dùng fonlio KHÔNG tốn phí nào.
 *
 * Hai việc tách bạch:
 *  - Quản lý quyền (admin cấp / gỡ, có email báo cho người được cấp).
 *  - {@link #waiveFee}: mọi chỗ trừ NP của ứng viên gọi qua đây để biết số
 *    phải trừ thật sự. Đại sứ thì trả 0. Gỡ quyền xong, lần bấm kế tiếp tính
 *    phí lại như thường — không có gì phải dọn.
 *
 * Kiểm quyền ngay trong giao dịch trừ tiền, không cache: cache thì người vừa
 * bị gỡ quyền vẫn dùng miễn phí thêm một lúc, người vừa được cấp vẫn bị trừ.
 */
@Service
public class AmbassadorService {

    private static final Logger log = LoggerFactory.getLogger(AmbassadorService.class);

    /** Ghi vào lý do giao dịch 0 NP để lịch sử ví nói rõ vì sao không bị trừ. */
    public static final String WAIVED_REASON_PREFIX = "[Miễn phí – Đại sứ thương hiệu] ";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final NotificationService notificationService;
    private final EmailDeliveryService emailDeliveryService;
    private final String frontendBaseUrl;

    public AmbassadorService(NamedParameterJdbcTemplate jdbcTemplate,
                             NotificationService notificationService,
                             EmailDeliveryService emailDeliveryService,
                             @Value("${app.frontend.base-url:http://localhost:5173}") String frontendBaseUrl) {
        this.jdbcTemplate = jdbcTemplate;
        this.notificationService = notificationService;
        this.emailDeliveryService = emailDeliveryService;
        this.frontendBaseUrl = frontendBaseUrl == null ? "" : frontendBaseUrl.replaceAll("/+$", "");
    }

    /* ───────────────────────────── Miễn phí ───────────────────────────── */

    public boolean isActive(UUID userId) {
        if (userId == null) return false;
        Boolean active = jdbcTemplate.queryForObject("""
                select exists (
                    select 1 from brand_ambassadors
                    where user_id = :userId and revoked_at is null
                )
                """, Map.of("userId", userId), Boolean.class);
        return Boolean.TRUE.equals(active);
    }

    /**
     * Số NP thật sự phải trừ cho một khoản phí của ứng viên.
     *
     * @return 0 nếu người này đang là đại sứ, ngược lại chính {@code fee}
     */
    public int waiveFee(UUID userId, int fee) {
        if (fee <= 0) return fee;
        return isActive(userId) ? 0 : fee;
    }

    /** Lý do ghi vào giao dịch: thêm tiền tố miễn phí khi phí đã được miễn. */
    public static String reason(String reason, int originalFee, int chargedFee) {
        return (originalFee > 0 && chargedFee == 0) ? WAIVED_REASON_PREFIX + reason : reason;
    }

    /* ─────────────────────────── Quản lý quyền ─────────────────────────── */

    public List<Map<String, Object>> listActive() {
        return jdbcTemplate.queryForList("""
                select b.user_id     as "userId",
                       u.email       as "email",
                       u.display_name as "displayName",
                       b.note        as "note",
                       b.granted_at  as "grantedAt",
                       g.email       as "grantedByEmail"
                from brand_ambassadors b
                join app_users u on u.id = b.user_id
                left join app_users g on g.id = b.granted_by
                where b.revoked_at is null
                order by b.granted_at desc
                """, Map.of());
    }

    /** Lịch sử các nhiệm kỳ đã gỡ, mới nhất trước — để admin tra lại. */
    public List<Map<String, Object>> listRevoked(int limit) {
        return jdbcTemplate.queryForList("""
                select b.user_id       as "userId",
                       u.email         as "email",
                       u.display_name  as "displayName",
                       b.granted_at    as "grantedAt",
                       b.revoked_at    as "revokedAt",
                       b.revoke_reason as "revokeReason",
                       r.email         as "revokedByEmail"
                from brand_ambassadors b
                join app_users u on u.id = b.user_id
                left join app_users r on r.id = b.revoked_by
                where b.revoked_at is not null
                order by b.revoked_at desc
                limit :limit
                """, Map.of("limit", Math.max(1, Math.min(limit, 200))));
    }

    @Transactional
    public Map<String, Object> grant(UUID adminId, String rawEmail, String rawNote) {
        String email = rawEmail == null ? "" : rawEmail.trim();
        if (email.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Nhập email của ứng viên.");
        }
        String note = blankToNull(rawNote);
        if (note != null && note.length() > 500) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Ghi chú tối đa 500 ký tự.");
        }

        Map<String, Object> user = findCandidateByEmail(email);
        UUID userId = (UUID) user.get("id");

        try {
            jdbcTemplate.update("""
                    insert into brand_ambassadors (user_id, note, granted_by)
                    values (:userId, :note, :adminId)
                    """, new MapSqlParameterSource()
                    .addValue("userId", userId)
                    .addValue("note", note)
                    .addValue("adminId", adminId));
        } catch (DuplicateKeyException e) {
            // Index unique một phần: đã có nhiệm kỳ đang hiệu lực.
            throw new AppException(HttpStatus.CONFLICT,
                    user.get("email") + " đang là đại sứ thương hiệu rồi.");
        }

        audit(adminId, "admin.ambassador.granted", userId, note);

        String title = "Bạn đã trở thành Đại sứ thương hiệu fonlio";
        String body = "Chào mừng bạn đến với đội ngũ Đại sứ thương hiệu fonlio!\n\n"
                + "Kể từ bây giờ, bạn được dùng fonlio hoàn toàn miễn phí: xác nhận nhận việc có thù lao, "
                + "cập nhật website portfolio và mọi tính năng khác đều không bị trừ NP.\n\n"
                + "Cảm ơn bạn đã đồng hành cùng fonlio.";
        notificationService.notify(userId, "AMBASSADOR_GRANTED", title, body, "/candidates/dashboard", false);
        emailAfterCommit(userId, user, title, body, "/candidates/dashboard", "AMBASSADOR_GRANTED");

        log.info("[Ambassador] Admin {} cấp quyền đại sứ cho {}", adminId, userId);
        return Map.of("userId", userId, "email", user.get("email"), "active", true);
    }

    @Transactional
    public Map<String, Object> revoke(UUID adminId, UUID userId, String rawReason) {
        String reason = blankToNull(rawReason);
        if (reason != null && reason.length() > 500) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Lý do tối đa 500 ký tự.");
        }

        int updated = jdbcTemplate.update("""
                update brand_ambassadors
                set revoked_at = now(), revoked_by = :adminId, revoke_reason = :reason
                where user_id = :userId and revoked_at is null
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("adminId", adminId)
                .addValue("reason", reason));
        if (updated == 0) {
            throw new AppException(HttpStatus.NOT_FOUND, "Người này hiện không phải đại sứ thương hiệu.");
        }

        audit(adminId, "admin.ambassador.revoked", userId, reason);

        Map<String, Object> user = jdbcTemplate.queryForMap(
                "select id, email, display_name from app_users where id = :id", Map.of("id", userId));

        String title = "Quyền Đại sứ thương hiệu fonlio của bạn đã kết thúc";
        String body = "Cảm ơn bạn đã đồng hành cùng fonlio trong vai trò Đại sứ thương hiệu.\n\n"
                + "Quyền dùng miễn phí của bạn đã kết thúc. Tài khoản, hồ sơ và số dư NP của bạn vẫn giữ nguyên; "
                + "từ nay các tính năng có phí sẽ tính NP như người dùng thông thường."
                + (reason == null ? "" : "\n\nGhi chú từ fonlio: " + reason);
        notificationService.notify(userId, "AMBASSADOR_REVOKED", title, body, "/candidates/dashboard", false);
        emailAfterCommit(userId, user, title, body, "/candidates/dashboard", "AMBASSADOR_REVOKED");

        log.info("[Ambassador] Admin {} gỡ quyền đại sứ của {}", adminId, userId);
        return Map.of("userId", userId, "active", false);
    }

    /* ───────────────────────────── Nội bộ ───────────────────────────── */

    private Map<String, Object> findCandidateByEmail(String email) {
        Map<String, Object> user;
        try {
            user = jdbcTemplate.queryForMap("""
                    select id, email, display_name, status
                    from app_users
                    where lower(email) = lower(:email)
                    """, Map.of("email", email));
        } catch (EmptyResultDataAccessException e) {
            throw new AppException(HttpStatus.NOT_FOUND,
                    "Không tìm thấy tài khoản nào với email " + email + ". Người này cần đăng ký fonlio trước.");
        }
        if (!"ACTIVE".equals(user.get("status"))) {
            throw new AppException(HttpStatus.CONFLICT, "Tài khoản " + email + " không còn hoạt động.");
        }
        Boolean isCandidate = jdbcTemplate.queryForObject("""
                select exists (
                    select 1 from user_roles
                    where user_id = :id and role_code in ('candidate_free', 'candidate_premium')
                )
                """, Map.of("id", user.get("id")), Boolean.class);
        if (!Boolean.TRUE.equals(isCandidate)) {
            throw new AppException(HttpStatus.BAD_REQUEST,
                    "Chỉ cấp quyền đại sứ cho tài khoản ứng viên.");
        }
        return user;
    }

    /**
     * Email gửi SAU khi giao dịch commit: lỗi ở bước sau làm rollback thì người
     * dùng không nhận một email nói sai sự thật. Gửi thẳng qua
     * EmailDeliveryService chứ không qua tuỳ chọn "nhận email thông báo" của
     * người dùng — đây là thay đổi về quyền lợi tài khoản, phải tới tay họ.
     */
    private void emailAfterCommit(UUID userId, Map<String, Object> user, String title, String body,
                                  String link, String template) {
        String recipient = (String) user.get("email");
        String name = (String) user.get("display_name");
        String absolute = link.startsWith("http") ? link : frontendBaseUrl + link;
        Runnable send = () -> {
            try {
                emailDeliveryService.sendNotificationEmail(userId, recipient, name, title, body, absolute, template);
            } catch (Exception e) {
                log.warn("[Ambassador] Gửi email {} cho {} thất bại: {}", template, userId, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    private void audit(UUID adminId, String action, UUID targetUserId, String note) {
        try {
            jdbcTemplate.update("""
                    insert into audit_logs (actor_user_id, action, entity_type, entity_id, metadata)
                    values (:admin, :action, 'app_user', :target,
                            jsonb_build_object('note', cast(:note as text)))
                    """, new MapSqlParameterSource()
                    .addValue("admin", adminId)
                    .addValue("action", action)
                    .addValue("target", targetUserId)
                    .addValue("note", note));
        } catch (Exception e) {
            log.warn("[Ambassador] Không ghi được audit log: {}", e.getMessage());
        }
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
