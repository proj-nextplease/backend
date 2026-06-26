package com.nextplease.backend.controller;

import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.service.CurrentUserService;
import com.nextplease.backend.service.PremiumService;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/premium")
public class PremiumController {

    private final PremiumService premiumService;
    private final CurrentUserService currentUserService;

    public PremiumController(PremiumService premiumService, CurrentUserService currentUserService) {
        this.premiumService = premiumService;
        this.currentUserService = currentUserService;
    }

    /** GET /api/v1/premium/config — pricing configurations for UI */
    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> getPremiumConfig() {
        return ApiResponse.success(premiumService.getPremiumConfig());
    }

    /** POST /api/v1/premium/boost?applicationId={id}&applicationType={JOB|QUEST} — boost application */
    @PostMapping("/boost")
    public ApiResponse<Map<String, Object>> boostApplication(
            @RequestParam UUID applicationId,
            @RequestParam(defaultValue = "JOB") String applicationType
    ) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        return ApiResponse.success(premiumService.boostApplication(userId, applicationId, applicationType));
    }

    /** POST /api/v1/premium/insight/unlock?targetId={id}&applicationType={JOB|QUEST} — unlock insight */
    @PostMapping("/insight/unlock")
    public ApiResponse<Map<String, Object>> unlockInsight(
            @RequestParam UUID targetId,
            @RequestParam(defaultValue = "JOB") String applicationType
    ) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        return ApiResponse.success(premiumService.unlockInsight(userId, targetId, applicationType));
    }

    /** GET /api/v1/premium/insight/{targetId}?applicationType={JOB|QUEST} — view insight metrics */
    @GetMapping("/insight/{targetId}")
    public ApiResponse<Map<String, Object>> getInsight(
            @PathVariable UUID targetId,
            @RequestParam(defaultValue = "JOB") String applicationType
    ) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        return ApiResponse.success(premiumService.getInsight(userId, targetId, applicationType));
    }

    /** POST /api/v1/premium/express?experienceId={id} — request express verification */
    @PostMapping("/express")
    public ApiResponse<Map<String, Object>> requestExpressVerification(@RequestParam UUID experienceId) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        return ApiResponse.success(premiumService.requestExpressVerification(userId, experienceId));
    }

    /** POST /api/v1/premium/theme/unlock — unlock theme visual upgrade */
    @PostMapping("/theme/unlock")
    public ApiResponse<Map<String, Object>> unlockTheme() {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        return ApiResponse.success(premiumService.unlockTheme(userId));
    }

    /** POST /api/v1/premium/theme/select — select premium theme */
    @PostMapping("/theme/select")
    public ApiResponse<Map<String, Object>> selectTheme(@RequestBody SelectThemeRequest body) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        return ApiResponse.success(premiumService.selectTheme(userId, body.theme()));
    }

    /** POST /api/v1/premium/match-alert/subscribe — buy Job Match Alert subscription */
    @PostMapping("/match-alert/subscribe")
    public ApiResponse<Map<String, Object>> subscribeJobMatchAlert() {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        return ApiResponse.success(premiumService.subscribeJobMatchAlert(userId));
    }

    /** GET /api/v1/premium/recommendations — get personalized recommendations */
    @GetMapping("/recommendations")
    public ApiResponse<Map<String, Object>> getPersonalizedRecommendations() {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        return ApiResponse.success(premiumService.getPersonalizedRecommendations(userId));
    }

    public record SelectThemeRequest(String theme) {}
}
