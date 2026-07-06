package com.nextplease.backend.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
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

        if (isPreferenceEnabled(userId, "in_app_enabled")) {
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
        }

        if (sendEmail && isPreferenceEnabled(userId, "email_enabled")) {
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

    /**
     * Reads a single boolean column from notification_preferences for this
     * user. Missing row (user never touched Account Settings) or any lookup
     * failure defaults to true — matches the column defaults declared in the
     * notification_preferences table and AccountSettingsService's own
     * fallback, and fails open so a preference-check bug can't silently
     * swallow a notification.
     */
    private boolean isPreferenceEnabled(UUID userId, String column) {
        try {
            Boolean value = jdbcTemplate.queryForObject(
                    "select " + column + " from notification_preferences where user_id = :userId",
                    Map.of("userId", userId), Boolean.class);
            return value == null || value;
        } catch (EmptyResultDataAccessException e) {
            return true;
        } catch (Exception e) {
            log.warn("[NotificationService] Failed to read {} preference for {}: {}", column, userId, e.getMessage());
            return true;
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

    /**
     * Job Match Alert (premium feature #5): when a new job/quest is approved and goes
     * public, notify every subscriber whose RS meets the requirement and who has at
     * least one matching skill. Fail-soft — never breaks the approval transaction.
     *
     * Eligible recipients = active 'job_match_alert_monthly' subscribers OR Premium users.
     */
    public void notifyMatchAlertSubscribers(UUID targetId, boolean isQuest) {
        try {
            String table = isQuest ? "quests" : "jobs";
            String skillTable = isQuest ? "quest_skills" : "job_skills";
            String skillCol = isQuest ? "quest_id" : "job_id";

            // Claim the alert atomically: a job/quest only fires Match Alert ONCE, even if it
            // is edited and re-approved later (re-approval finds match_alert_sent_at already set).
            int claimed = jdbcTemplate.update(
                    "update " + table + " set match_alert_sent_at = now() "
                            + "where id = :id and match_alert_sent_at is null",
                    Map.of("id", targetId));
            if (claimed == 0) {
                log.info("[NotificationService] Match Alert already dispatched for {} {} — skipping.",
                        isQuest ? "quest" : "job", targetId);
                return;
            }

            Map<String, Object> target = jdbcTemplate.queryForMap(
                    "select title, min_req_rs from " + table + " where id = :id",
                    Map.of("id", targetId));
            String title = (String) target.get("title");
            int minReqRs = target.get("min_req_rs") != null ? ((Number) target.get("min_req_rs")).intValue() : 0;

            List<UUID> recipients = jdbcTemplate.query(
                    "select distinct u.id from app_users u "
                            + "join profiles p on p.user_id = u.id "
                            + "where p.reputation_score >= :minRs "
                            + "and ( u.premium_until > now() "
                            + "      or exists (select 1 from subscriptions s "
                            + "                 where s.user_id = u.id and s.plan_code = 'job_match_alert_monthly' "
                            + "                   and s.status = 'ACTIVE' and s.expires_at > now()) ) "
                            + "and exists (select 1 from " + skillTable + " sk "
                            + "            join profile_skills ps on ps.skill_id = sk.skill_id "
                            + "            where sk." + skillCol + " = :targetId and ps.profile_id = p.id)",
                    new MapSqlParameterSource()
                            .addValue("minRs", minReqRs)
                            .addValue("targetId", targetId),
                    (rs, i) -> (UUID) rs.getObject("id"));

            String label = isQuest ? "Quest" : "việc làm";
            String link = (isQuest ? "/quests/" : "/jobs/") + targetId;
            for (UUID uid : recipients) {
                notify(uid, "JOB_MATCH",
                        "Cơ hội mới phù hợp với bạn ✨",
                        "\"" + title + "\" vừa được đăng và khớp với kỹ năng của bạn. Ứng tuyển sớm để có lợi thế cạnh tranh!",
                        link, true);
            }
            log.info("[NotificationService] JOB_MATCH notified {} subscriber(s) for {} {}",
                    recipients.size(), isQuest ? "quest" : "job", targetId);
        } catch (Exception e) {
            log.warn("[NotificationService] Failed to notify match-alert subscribers for {}: {}",
                    targetId, e.getMessage());
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
