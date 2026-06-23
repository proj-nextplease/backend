package com.nextplease.backend.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * In-app notifications (bell icon). This is the single event point for user
 * notifications — email delivery can later be layered on top of {@link #notify}
 * without touching the call sites that emit events.
 *
 * notify() is intentionally fail-soft: a notification must never break the
 * business transaction that triggered it (e.g. accepting an application).
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final EmailDeliveryService emailDeliveryService;

    public NotificationService(NamedParameterJdbcTemplate jdbcTemplate, EmailDeliveryService emailDeliveryService) {
        this.jdbcTemplate = jdbcTemplate;
        this.emailDeliveryService = emailDeliveryService;
    }

    /** Create an in-app notification for a recipient. Never throws to the caller. */
    public void notify(UUID userId, String type, String title, String body, String link) {
        notify(userId, type, title, body, link, false);
    }

    /**
     * Create an in-app notification and, when sendEmail is true, also send a
     * notification email to the recipient. Both paths are fail-soft.
     */
    public void notify(UUID userId, String type, String title, String body, String link, boolean sendEmail) {
        if (userId == null) return;
        try {
            jdbcTemplate.update("""
                    insert into notifications (user_id, type, title, body, link)
                    values (:userId, :type, :title, :body, :link)
                    """, new MapSqlParameterSource()
                    .addValue("userId", userId)
                    .addValue("type", type)
                    .addValue("title", title)
                    .addValue("body", body)
                    .addValue("link", link));
        } catch (Exception e) {
            log.warn("[NotificationService] Failed to create notification for {}: {}", userId, e.getMessage());
        }
        if (sendEmail) {
            try {
                Map<String, Object> u = jdbcTemplate.queryForMap(
                        "select email, display_name from app_users where id = :id", Map.of("id", userId));
                String absoluteLink = toAbsoluteLink(link);
                emailDeliveryService.sendNotificationEmail(
                        (String) u.get("email"), (String) u.get("display_name"), title, body, absoluteLink);
            } catch (Exception e) {
                log.warn("[NotificationService] Failed to send email for {}: {}", userId, e.getMessage());
            }
        }
    }

    /** Notify every admin user. Used for moderation-queue events. */
    public void notifyAdmins(String type, String title, String body, String link) {
        try {
            List<UUID> adminIds = jdbcTemplate.query(
                    "select user_id from user_roles where role_code = 'admin'",
                    (rs, i) -> (UUID) rs.getObject("user_id"));
            for (UUID adminId : adminIds) {
                notify(adminId, type, title, body, link, false);
            }
        } catch (Exception e) {
            log.warn("[NotificationService] Failed to notify admins: {}", e.getMessage());
        }
    }

    private String toAbsoluteLink(String link) {
        if (link == null || link.isBlank() || link.startsWith("http")) return link;
        String base = System.getenv().getOrDefault("APP_PUBLIC_URL", "");
        return base.isBlank() ? null : base.replaceAll("/+$", "") + link;
    }

    /** Recent notifications (max 30) + unread count. */
    public Map<String, Object> list(UUID userId) {
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select id, type, title, body, link, is_read as "isRead", created_at as "createdAt"
                from notifications
                where user_id = :userId
                order by created_at desc
                limit 30
                """, Map.of("userId", userId));
        Integer unread = jdbcTemplate.queryForObject(
                "select count(*) from notifications where user_id = :userId and is_read = false",
                Map.of("userId", userId), Integer.class);
        return Map.of("items", items, "unreadCount", unread == null ? 0 : unread);
    }

    public void markRead(UUID userId, UUID id) {
        jdbcTemplate.update(
                "update notifications set is_read = true where id = :id and user_id = :userId",
                Map.of("id", id, "userId", userId));
    }

    public void markAllRead(UUID userId) {
        jdbcTemplate.update(
                "update notifications set is_read = true where user_id = :userId and is_read = false",
                Map.of("userId", userId));
    }
}
