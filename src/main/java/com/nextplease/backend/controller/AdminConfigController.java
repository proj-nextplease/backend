package com.nextplease.backend.controller;

import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.dto.response.MeResponse;
import com.nextplease.backend.exception.AppException;
import com.nextplease.backend.service.ConfigService;
import com.nextplease.backend.service.CurrentUserService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only platform configuration (low-code settings). DB-level role check. */
@RestController
@RequestMapping("/api/v1/admin/system-config")
public class AdminConfigController {

    private static final Logger log = LoggerFactory.getLogger(AdminConfigController.class);

    private final ConfigService configService;
    private final CurrentUserService currentUserService;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AdminConfigController(ConfigService configService, CurrentUserService currentUserService,
                                 NamedParameterJdbcTemplate jdbcTemplate) {
        this.configService = configService;
        this.currentUserService = currentUserService;
        this.jdbcTemplate = jdbcTemplate;
    }

    private MeResponse requireAdmin() {
        MeResponse currentUser = currentUserService.getCurrentUser();
        List<String> roles = jdbcTemplate.queryForList(
                "select role_code from user_roles where user_id = :userId",
                Map.of("userId", currentUser.appUserId()), String.class);
        if (!roles.contains("admin")) {
            throw new AppException(HttpStatus.FORBIDDEN, "Bạn không có quyền truy cập trang quản trị.");
        }
        return currentUser;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        requireAdmin();
        return ApiResponse.success(configService.getAll());
    }

    @PatchMapping("/{key}")
    public ApiResponse<Map<String, Object>> update(@PathVariable String key, @RequestBody Map<String, Object> body) {
        MeResponse admin = requireAdmin();
        Object raw = body.get("value");
        if (raw == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Thiếu giá trị 'value'.");
        }
        int value;
        try {
            value = (raw instanceof Number n) ? n.intValue() : Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException e) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Giá trị phải là số nguyên.");
        }
        if (value < 0) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Giá trị không được âm.");
        }

        int saved = configService.updateInt(key, value, admin.appUserId());

        try {
            jdbcTemplate.update("""
                    insert into audit_logs (actor_user_id, action, entity_type, metadata)
                    values (:admin, 'admin.system_config.updated', 'system_config',
                            jsonb_build_object('key', :key, 'value', :value))
                    """, Map.of("admin", admin.appUserId(), "key", key, "value", saved));
        } catch (Exception e) {
            log.warn("Failed to write system_config audit log: {}", e.getMessage());
        }

        return ApiResponse.success(Map.of("key", key, "value", saved));
    }
}
