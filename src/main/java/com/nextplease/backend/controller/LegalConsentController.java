package com.nextplease.backend.controller;

import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.service.LegalConsentService;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class LegalConsentController {

    private final LegalConsentService legalConsentService;

    public LegalConsentController(LegalConsentService legalConsentService) {
        this.legalConsentService = legalConsentService;
    }

    /**
     * Ghi nhận người dùng hiện tại đã đồng ý với một phiên bản văn bản pháp lý.
     * Gọi lại nhiều lần với cùng phiên bản là vô hại — bản ghi đầu tiên được giữ.
     */
    @PostMapping("/me/legal-consent")
    public ApiResponse<String> accept(@RequestBody Map<String, String> body) {
        String version = body == null ? null : body.get("version");
        legalConsentService.accept(version);
        return ApiResponse.success("Đã ghi nhận đồng ý.");
    }
}
