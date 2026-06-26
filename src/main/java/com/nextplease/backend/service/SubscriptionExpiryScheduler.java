package com.nextplease.backend.service;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps subscription rows truthful: flips ACTIVE subscriptions whose expiry has
 * passed to EXPIRED. Access checks already guard on {@code expires_at > now()},
 * so this is for data accuracy and reporting rather than enforcement.
 */
@Service
public class SubscriptionExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionExpiryScheduler.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public SubscriptionExpiryScheduler(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Runs hourly at minute 5. */
    @Scheduled(cron = "0 5 * * * *")
    public void expireLapsedSubscriptions() {
        try {
            int updated = jdbcTemplate.update("""
                    update subscriptions
                    set status = 'EXPIRED', updated_at = now()
                    where status = 'ACTIVE' and expires_at <= now()
                    """, Map.of());
            if (updated > 0) {
                log.info("[SubscriptionExpiryScheduler] Marked {} lapsed subscription(s) as EXPIRED.", updated);
            }
        } catch (Exception e) {
            log.error("[SubscriptionExpiryScheduler] Failed to expire lapsed subscriptions: {}", e.getMessage(), e);
        }
    }
}
