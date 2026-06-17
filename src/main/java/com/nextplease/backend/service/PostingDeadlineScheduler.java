package com.nextplease.backend.service;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class PostingDeadlineScheduler {

    private static final Logger log = LoggerFactory.getLogger(PostingDeadlineScheduler.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PostingDeadlineScheduler(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Auto-close job postings and quests whose deadline has passed.
     * Runs every hour.
     */
    @Scheduled(cron = "0 0 * * * *")
    public void closeExpiredPostings() {
        log.info("[PostingDeadlineScheduler] Checking for expired postings...");

        try {
            int closedJobs = jdbcTemplate.update("""
                    update jobs
                    set status = 'CLOSED', updated_at = now()
                    where status = 'OPEN'
                      and deadline_at is not null
                      and deadline_at < now()
                    """, Map.of());

            if (closedJobs > 0) {
                log.info("[PostingDeadlineScheduler] Auto-closed {} expired job posting(s).", closedJobs);
            }
        } catch (Exception e) {
            log.error("[PostingDeadlineScheduler] Failed to close expired jobs: {}", e.getMessage(), e);
        }

        try {
            int closedQuests = jdbcTemplate.update("""
                    update quests
                    set status = 'CLOSED', updated_at = now()
                    where status = 'OPEN'
                      and ends_at is not null
                      and ends_at < now()
                    """, Map.of());

            if (closedQuests > 0) {
                log.info("[PostingDeadlineScheduler] Auto-closed {} expired quest(s).", closedQuests);
            }
        } catch (Exception e) {
            log.error("[PostingDeadlineScheduler] Failed to close expired quests: {}", e.getMessage(), e);
        }
    }
}
