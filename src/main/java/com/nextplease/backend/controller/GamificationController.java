package com.nextplease.backend.controller;

import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.service.CurrentUserService;
import com.nextplease.backend.service.GamificationService;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Candidate gamification: streak + daily/weekly quests for the dashboard overview. */
@RestController
@RequestMapping("/api/v1/me/gamification")
public class GamificationController {

    private final GamificationService gamificationService;
    private final CurrentUserService currentUserService;

    public GamificationController(GamificationService gamificationService,
                                 CurrentUserService currentUserService) {
        this.gamificationService = gamificationService;
        this.currentUserService = currentUserService;
    }

    /** GET — streak, level progress and today's quests. */
    @GetMapping
    public ApiResponse<Map<String, Object>> getState() {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        return ApiResponse.success(gamificationService.getState(userId));
    }

    /** POST /ping — mark active today, roll the streak, complete the daily login quest. */
    @PostMapping("/ping")
    public ApiResponse<Map<String, Object>> ping() {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        return ApiResponse.success(gamificationService.ping(userId));
    }

    /** POST /events — advance quests tied to an activity (VIEW_OPPORTUNITY, APPLY, …). */
    @PostMapping("/events")
    public ApiResponse<Map<String, Object>> recordEvent(@RequestBody EventRequest body) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        int amount = body.amount() == null ? 1 : body.amount();
        return ApiResponse.success(gamificationService.recordEvent(userId, body.event(), amount));
    }

    /** POST /quests/{scope}/{key}/claim — claim a completed quest's EXP reward. */
    @PostMapping("/quests/{scope}/{key}/claim")
    public ApiResponse<Map<String, Object>> claim(@PathVariable String scope, @PathVariable String key) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        return ApiResponse.success(gamificationService.claim(userId, scope, key));
    }

    record EventRequest(String event, Integer amount) {}
}
