package com.nextplease.backend.service;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns all EXP mutations. EXP is permanent and represents seniority.
 * Level-up threshold: EXP needed to advance from level N = 100 × N^1.2
 * Every change creates an append-only row in exp_events.
 */
@Service
public class ExpService {

    private static final Logger log = LoggerFactory.getLogger(ExpService.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ExpService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Calculates the current level for a given total EXP amount.
     * Level 1 starts at 0 EXP. To reach level N+1 requires an additional 100 × N^1.2 EXP.
     */
    public int calculateLevel(long totalExp) {
        int level = 1;
        long accumulated = 0;
        while (true) {
            long threshold = (long) (100.0 * Math.pow(level, 1.2));
            if (accumulated + threshold > totalExp) break;
            accumulated += threshold;
            level++;
        }
        return level;
    }

    /**
     * Add EXP to a profile, update total_exp and current_level.
     *
     * @param profileId  target profile
     * @param points     EXP to add (must be positive)
     * @param reasonCode machine-readable reason, e.g. "SMALL_EVENT_COMPLETED"
     * @param sourceType nullable entity type, e.g. "quest_application"
     * @param sourceId   nullable UUID of that entity — idempotency key together with reasonCode
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void addExp(UUID profileId, int points, String reasonCode,
                       String sourceType, UUID sourceId) {
        if (points <= 0) {
            log.warn("[ExpService] addExp called with non-positive points={} for profile {}", points, profileId);
            return;
        }

        // Lock profile row to avoid race condition
        Map<String, Object> profile = jdbcTemplate.queryForMap(
                "select total_exp, current_level from profiles where id = :profileId for update",
                Map.of("profileId", profileId)
        );

        long previousExp = ((Number) profile.get("total_exp")).longValue();
        int previousLevel = ((Number) profile.get("current_level")).intValue();

        long newTotalExp = previousExp + points;
        int newLevel = calculateLevel(newTotalExp);

        jdbcTemplate.update("""
                update profiles
                set total_exp     = :newTotalExp,
                    current_level = :newLevel,
                    updated_at    = now()
                where id = :profileId
                """, Map.of("newTotalExp", newTotalExp, "newLevel", newLevel, "profileId", profileId));

        if (sourceType != null && sourceId != null) {
            jdbcTemplate.update("""
                    insert into exp_events
                        (profile_id, points, reason_code, source_type, source_id, metadata)
                    values
                        (:profileId, :points, :reasonCode, :sourceType, :sourceId,
                         jsonb_build_object('previous_exp', :previousExp, 'new_exp', :newTotalExp,
                                            'previous_level', :previousLevel, 'new_level', :newLevel))
                    on conflict (profile_id, reason_code, source_type, source_id)
                        where source_type is not null and source_id is not null
                    do nothing
                    """, new MapSqlParameterSource()
                    .addValue("profileId", profileId)
                    .addValue("points", points)
                    .addValue("reasonCode", reasonCode)
                    .addValue("sourceType", sourceType)
                    .addValue("sourceId", sourceId)
                    .addValue("previousExp", previousExp)
                    .addValue("newTotalExp", newTotalExp)
                    .addValue("previousLevel", previousLevel)
                    .addValue("newLevel", newLevel));
        } else {
            jdbcTemplate.update("""
                    insert into exp_events
                        (profile_id, points, reason_code, metadata)
                    values
                        (:profileId, :points, :reasonCode,
                         jsonb_build_object('previous_exp', :previousExp, 'new_exp', :newTotalExp,
                                            'previous_level', :previousLevel, 'new_level', :newLevel))
                    """, new MapSqlParameterSource()
                    .addValue("profileId", profileId)
                    .addValue("points", points)
                    .addValue("reasonCode", reasonCode)
                    .addValue("previousExp", previousExp)
                    .addValue("newTotalExp", newTotalExp)
                    .addValue("previousLevel", previousLevel)
                    .addValue("newLevel", newLevel));
        }

        if (newLevel > previousLevel) {
            log.info("[ExpService] Profile {} leveled up {} → {} (totalExp: {} → {})",
                    profileId, previousLevel, newLevel, previousExp, newTotalExp);
        } else {
            log.info("[ExpService] Profile {} +{} EXP (reason={}, totalExp: {} → {}, level={})",
                    profileId, points, reasonCode, previousExp, newTotalExp, newLevel);
        }
    }
}
