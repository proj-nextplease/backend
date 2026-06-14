package com.nextplease.backend.service;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class RegistrationCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(RegistrationCleanupScheduler.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public RegistrationCleanupScheduler(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Clean up stale registration attempts (EXPIRED, REVOKED, LOCKED status) older than 48 hours.
     * Runs every day at 2:00 AM.
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void cleanStaleRegistrationAttempts() {
        log.info("Starting scheduled cleanup of stale candidate registration attempts...");
        try {
            int deletedCount = jdbcTemplate.update("""
                    delete from candidate_registration_attempts
                    where status in ('EXPIRED', 'REVOKED', 'LOCKED')
                      and created_at < now() - interval '2 days'
                    """, Map.of());
            log.info("Stale registration attempts cleanup completed. Deleted {} records.", deletedCount);
        } catch (Exception e) {
            log.error("Failed to delete stale registration attempts: {}", e.getMessage(), e);
        }
    }
}
