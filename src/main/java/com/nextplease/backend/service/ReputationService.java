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
 * Owns all Reputation Score mutations. RS is clamped to [0, 100].
 * Every change creates an append-only row in reputation_events.
 * Use sourceType + sourceId to guarantee idempotency (unique partial index
 * ux_reputation_once_per_source prevents double-awarding the same event).
 */
@Service
public class ReputationService {

    private static final Logger log = LoggerFactory.getLogger(ReputationService.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ReputationService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Add (positive) or subtract (negative) RS for a profile.
     *
     * @param profileId  target profile
     * @param delta      points to add or subtract (can be negative for penalties)
     * @param reasonCode machine-readable reason, e.g. "EMAIL_VERIFIED", "NO_SHOW_PENALTY"
     * @param sourceType nullable entity type that triggered this event, e.g. "registration"
     * @param sourceId   nullable UUID of that entity — together with sourceType and reasonCode
     *                   they form the idempotency key
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void addReputation(UUID profileId, int delta, String reasonCode,
                              String sourceType, UUID sourceId) {
        if (delta == 0) return;

        // Clamp: read current RS then compute clamped result
        Integer currentRs = jdbcTemplate.queryForObject(
                "select reputation_score from profiles where id = :profileId for update",
                Map.of("profileId", profileId),
                Integer.class
        );
        if (currentRs == null) {
            log.warn("[ReputationService] Profile {} not found, skipping RS update", profileId);
            return;
        }

        int newRs = Math.max(0, Math.min(100, currentRs + delta));
        int actualDelta = newRs - currentRs;

        if (actualDelta == 0 && delta != 0) {
            log.info("[ReputationService] Profile {} RS already at boundary ({}), delta {} clamped to 0",
                    profileId, currentRs, delta);
            return;
        }

        // Update profile RS
        jdbcTemplate.update("""
                update profiles
                set reputation_score = :newRs,
                    updated_at       = now()
                where id = :profileId
                """, Map.of("newRs", newRs, "profileId", profileId));

        // Log event — ON CONFLICT DO NOTHING prevents duplicate awards for same source
        if (sourceType != null && sourceId != null) {
            jdbcTemplate.update("""
                    insert into reputation_events
                        (profile_id, points, reason_code, source_type, source_id, metadata)
                    values
                        (:profileId, :points, :reasonCode, :sourceType, :sourceId,
                         jsonb_build_object('previous_rs', :previousRs, 'new_rs', :newRs))
                    on conflict (profile_id, reason_code, source_type, source_id)
                        where source_type is not null and source_id is not null
                    do nothing
                    """, new MapSqlParameterSource()
                    .addValue("profileId", profileId)
                    .addValue("points", actualDelta)
                    .addValue("reasonCode", reasonCode)
                    .addValue("sourceType", sourceType)
                    .addValue("sourceId", sourceId)
                    .addValue("previousRs", currentRs)
                    .addValue("newRs", newRs));
        } else {
            jdbcTemplate.update("""
                    insert into reputation_events
                        (profile_id, points, reason_code, metadata)
                    values
                        (:profileId, :points, :reasonCode,
                         jsonb_build_object('previous_rs', :previousRs, 'new_rs', :newRs))
                    """, new MapSqlParameterSource()
                    .addValue("profileId", profileId)
                    .addValue("points", actualDelta)
                    .addValue("reasonCode", reasonCode)
                    .addValue("previousRs", currentRs)
                    .addValue("newRs", newRs));
        }

        log.info("[ReputationService] Profile {} RS {} → {} (reason={}, source={}:{})",
                profileId, currentRs, newRs, reasonCode, sourceType, sourceId);
    }
}
