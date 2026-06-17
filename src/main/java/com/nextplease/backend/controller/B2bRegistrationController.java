package com.nextplease.backend.controller;

import com.nextplease.backend.dto.request.B2bRegistrationRequest;
import com.nextplease.backend.dto.request.B2bUpdateRequest;
import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.service.B2bRegistrationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/b2b")
public class B2bRegistrationController {

    private final B2bRegistrationService b2bRegistrationService;
    private final com.nextplease.backend.service.CurrentUserService currentUserService;

    public B2bRegistrationController(
            B2bRegistrationService b2bRegistrationService,
            com.nextplease.backend.service.CurrentUserService currentUserService
    ) {
        this.b2bRegistrationService = b2bRegistrationService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/register")
    public ApiResponse<String> registerB2b(@Valid @RequestBody B2bRegistrationRequest request) {
        b2bRegistrationService.registerB2b(request);
        return ApiResponse.success("Đăng ký tài khoản đối tác thành công! Vui lòng chờ quản trị viên phê duyệt.");
    }

    @org.springframework.web.bind.annotation.GetMapping("/company")
    public ApiResponse<java.util.Map<String, Object>> getMyCompany() {
        com.nextplease.backend.dto.response.MeResponse currentUser = currentUserService.getCurrentUser();
        return ApiResponse.success(b2bRegistrationService.getMyCompany(currentUser.appUserId()));
    }

    @PostMapping("/resubmit")
    public ApiResponse<String> resubmitDocument(@RequestBody java.util.Map<String, String> body) {
        com.nextplease.backend.dto.response.MeResponse currentUser = currentUserService.getCurrentUser();
        String documentUrl = body.get("documentUrl");
        b2bRegistrationService.updateCompanyDocument(currentUser.appUserId(), documentUrl);
        return ApiResponse.success("Cập nhật tài liệu minh chứng thành công! Hồ sơ của bạn đã được gửi phê duyệt lại.");
    }

    @PostMapping("/company/update")
    public ApiResponse<String> updateCompanyProfile(@Valid @RequestBody B2bUpdateRequest request) {
        com.nextplease.backend.dto.response.MeResponse currentUser = currentUserService.getCurrentUser();
        b2bRegistrationService.updateCompanyProfile(currentUser.appUserId(), request);
        return ApiResponse.success("Cập nhật hồ sơ đối tác thành công! Hồ sơ đang chờ duyệt.");
    }
}

