package com.nextplease.backend.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reads/writes platform configuration (system_configs). Integer values are
 * cached in-memory and the cache is cleared on update. BE services should call
 * {@link #getInt(String, int)} instead of using hardcoded constants so admins
 * can tune values at runtime. Changes apply to NEW events only (never retroactive).
 */
@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ConcurrentHashMap<String, Integer> intCache = new ConcurrentHashMap<>();

    public ConfigService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Returns the configured int for {@code key}, or {@code defaultValue} if missing/unreadable. */
    public int getInt(String key, int defaultValue) {
        Integer cached = intCache.get(key);
        if (cached != null) return cached;
        try {
            Integer value = jdbcTemplate.queryForObject(
                    "select value_int from system_configs where config_key = :k and data_type = 'INT'",
                    Map.of("k", key), Integer.class);
            if (value != null) {
                intCache.put(key, value);
                return value;
            }
        } catch (Exception e) {
            log.warn("[ConfigService] Could not read config '{}', using default {}: {}", key, defaultValue, e.getMessage());
        }
        return defaultValue;
    }

    /** Lists all configs for the admin settings UI. */
    public List<Map<String, Object>> getAll() {
        return jdbcTemplate.queryForList("""
                select config_key as "key", value_int as "valueInt", value_text as "valueText",
                       data_type as "dataType", config_group as "group", label, description,
                       updated_at as "updatedAt"
                from system_configs
                order by config_group, config_key
                """, Map.of());
    }

    /** Updates an INT config and clears its cache. Returns the new value. */
    public int updateInt(String key, int value, UUID adminUserId) {
        int rows = jdbcTemplate.update("""
                update system_configs
                set value_int = :v, updated_by = :admin, updated_at = now()
                where config_key = :k and data_type = 'INT'
                """, new MapSqlParameterSource()
                .addValue("v", value)
                .addValue("admin", adminUserId)
                .addValue("k", key));
        if (rows == 0) {
            throw new com.nextplease.backend.exception.AppException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "Không tìm thấy cấu hình INT: " + key);
        }
        intCache.put(key, value);
        log.info("[ConfigService] Admin {} set {} = {}", adminUserId, key, value);
        return value;
    }
}
