package com.nextplease.backend.controller;

import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.dto.response.MeResponse;
import com.nextplease.backend.service.AmbassadorService;
import com.nextplease.backend.service.CurrentUserService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin cấp / gỡ quyền Đại sứ thương hiệu (dùng fonlio miễn phí). */
@RestController
@RequestMapping("/api/v1/admin/ambassadors")
public class AdminAmbassadorController {

    private final AmbassadorService ambassadorService;
    private final CurrentUserService currentUserService;

    public AdminAmbassadorController(AmbassadorService ambassadorService, CurrentUserService currentUserService) {
        this.ambassadorService = ambassadorService;
        this.currentUserService = currentUserService;
    }

    /** Đại sứ đang hiệu lực, kèm lịch sử các lần gỡ gần nhất. */
    @GetMapping
    public ApiResponse<Map<String, List<Map<String, Object>>>> list(
            @RequestParam(defaultValue = "50") int historyLimit) {
        currentUserService.requireAdmin();
        return ApiResponse.success(Map.of(
                "active", ambassadorService.listActive(),
                "history", ambassadorService.listRevoked(historyLimit)));
    }

    /** Body: { "email": "...", "note": "..." } */
    @PostMapping
    public ApiResponse<Map<String, Object>> grant(@RequestBody Map<String, String> body) {
        MeResponse admin = currentUserService.requireAdmin();
        return ApiResponse.success(ambassadorService.grant(
                admin.appUserId(), body.get("email"), body.get("note")));
    }

    /** Body (tuỳ chọn): { "reason": "..." } */
    @PostMapping("/{userId}/revoke")
    public ApiResponse<Map<String, Object>> revoke(@PathVariable UUID userId,
                                                    @RequestBody(required = false) Map<String, String> body) {
        MeResponse admin = currentUserService.requireAdmin();
        return ApiResponse.success(ambassadorService.revoke(
                admin.appUserId(), userId, body == null ? null : body.get("reason")));
    }
}
