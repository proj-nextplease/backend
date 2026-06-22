package com.nextplease.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextplease.backend.exception.AppException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-company recruitment pipeline labels/colors/visibility over the fixed
 * canonical application statuses. Presentation-only — never affects scoring.
 */
@Service
public class PipelineService {

    /** Canonical statuses + sensible defaults (label/color) shown if a company hasn't customized. */
    public static final List<Map<String, Object>> DEFAULTS = List.of(
            Map.of("status", "SUBMITTED",   "label", "Đã nộp",        "color", "#6366f1", "hidden", false),
            Map.of("status", "VIEWED",      "label", "Đã xem",        "color", "#0284c7", "hidden", false),
            Map.of("status", "SHORTLISTED", "label", "Vào vòng tiếp", "color", "#d97706", "hidden", false),
            Map.of("status", "ACCEPTED",    "label", "Chấp nhận",     "color", "#16a34a", "hidden", false),
            Map.of("status", "COMPLETED",   "label", "Hoàn thành",    "color", "#7c3aed", "hidden", false),
            Map.of("status", "REJECTED",    "label", "Từ chối",       "color", "#dc2626", "hidden", false)
    );
    private static final java.util.Set<String> VALID_STATUSES = Map.of(
            "SUBMITTED", 1, "VIEWED", 1, "SHORTLISTED", 1, "ACCEPTED", 1, "COMPLETED", 1, "REJECTED", 1
    ).keySet();

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final CompanyAccessService companyAccessService;
    private final ObjectMapper objectMapper;

    public PipelineService(NamedParameterJdbcTemplate jdbcTemplate, CompanyAccessService companyAccessService,
                           ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.companyAccessService = companyAccessService;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> getPipeline(UUID userId) {
        UUID companyId = (UUID) companyAccessService.resolveApprovedCompanyForUser(userId).get("id");
        String raw;
        try {
            raw = jdbcTemplate.queryForObject(
                    "select pipeline_config::text from companies where id = :id",
                    Map.of("id", companyId), String.class);
        } catch (Exception e) {
            raw = null;
        }
        if (raw == null || raw.isBlank()) return DEFAULTS;
        try {
            List<Map<String, Object>> parsed = objectMapper.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            return (parsed == null || parsed.isEmpty()) ? DEFAULTS : parsed;
        } catch (Exception e) {
            return DEFAULTS;
        }
    }

    @Transactional
    public List<Map<String, Object>> savePipeline(UUID userId, List<Map<String, Object>> stages) {
        UUID companyId = (UUID) companyAccessService.resolveApprovedCompanyForUser(userId).get("id");
        companyAccessService.assertCanManagePostings(userId, companyId);

        if (stages == null || stages.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Cấu hình quy trình không hợp lệ.");
        }
        // Keep only valid canonical statuses; normalize fields.
        List<Map<String, Object>> clean = stages.stream()
                .filter(s -> s.get("status") != null && VALID_STATUSES.contains(s.get("status").toString()))
                .map(s -> Map.<String, Object>of(
                        "status", s.get("status").toString(),
                        "label", s.getOrDefault("label", s.get("status")).toString(),
                        "color", s.getOrDefault("color", "#6366f1").toString(),
                        "hidden", Boolean.TRUE.equals(s.get("hidden"))
                ))
                .toList();
        if (clean.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Không có bước hợp lệ trong quy trình.");
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(clean);
        } catch (Exception e) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Không thể lưu cấu hình quy trình.");
        }
        jdbcTemplate.update("update companies set pipeline_config = :cfg::jsonb, updated_at = now() where id = :id",
                new MapSqlParameterSource().addValue("cfg", json).addValue("id", companyId));
        return clean;
    }
}
