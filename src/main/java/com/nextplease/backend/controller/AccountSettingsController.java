package com.nextplease.backend.controller;

import com.nextplease.backend.dto.request.DeactivateAccountRequest;
import com.nextplease.backend.dto.request.UpdateDisplayNameRequest;
import com.nextplease.backend.dto.request.UpdateNotificationPreferencesRequest;
import com.nextplease.backend.dto.response.AccountSettingsResponse;
import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.service.AccountSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/account")
public class AccountSettingsController {

    private final AccountSettingsService accountSettingsService;

    public AccountSettingsController(AccountSettingsService accountSettingsService) {
        this.accountSettingsService = accountSettingsService;
    }

    @GetMapping("/me")
    public ApiResponse<AccountSettingsResponse> getSettings() {
        return ApiResponse.success(accountSettingsService.getSettings());
    }

    @PutMapping("/display-name")
    public ApiResponse<String> updateDisplayName(@Valid @RequestBody UpdateDisplayNameRequest request) {
        accountSettingsService.updateDisplayName(request.displayName());
        return ApiResponse.success("Cập nhật tên hiển thị thành công.");
    }

    @PutMapping("/notification-preferences")
    public ApiResponse<String> updateNotificationPreferences(@Valid @RequestBody UpdateNotificationPreferencesRequest request) {
        accountSettingsService.updateNotificationPreferences(request);
        return ApiResponse.success("Cập nhật tùy chọn thông báo thành công.");
    }

    @PostMapping("/deactivate")
    public ApiResponse<String> deactivateAccount(@Valid @RequestBody DeactivateAccountRequest request, HttpServletRequest httpRequest) {
        String accessToken = null;
        String authHeader = httpRequest.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            accessToken = authHeader.substring(7);
        }
        accountSettingsService.deactivateAccount(request.password(), accessToken);
        return ApiResponse.success("Tài khoản đã được vô hiệu hóa.");
    }
}
