package com.nextplease.backend.controller;

import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.service.CurrentUserService;
import com.nextplease.backend.service.QuestService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class QuestController {

    private final QuestService questService;
    private final CurrentUserService currentUserService;

    public QuestController(QuestService questService, CurrentUserService currentUserService) {
        this.questService = questService;
        this.currentUserService = currentUserService;
    }

    // ── Public / Candidate ────────────────────────────────────────────────────

    /** GET /api/v1/quests?keyword=&category= */
    @GetMapping("/quests")
    public ApiResponse<List<Map<String, Object>>> searchQuests(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category
    ) {
        return ApiResponse.success(questService.searchQuests(keyword, category, 0));
    }

    /** POST /api/v1/quests/{id}/apply */
    @PostMapping("/quests/{id}/apply")
    public ApiResponse<Map<String, Object>> applyToQuest(
            @PathVariable UUID id,
            @RequestBody(required = false) QuestApplyRequest body
    ) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        String coverNote = body != null ? body.coverNote() : "";
        return ApiResponse.success(questService.applyToQuest(userId, id, coverNote));
    }

    /** GET /api/v1/me/quest-applications */
    @GetMapping("/me/quest-applications")
    public ApiResponse<List<Map<String, Object>>> getMyQuestApplications() {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        return ApiResponse.success(questService.getMyQuestApplications(userId));
    }

    /** PATCH /api/v1/me/quest-applications/{id}/withdraw — candidate withdraws */
    @PatchMapping("/me/quest-applications/{id}/withdraw")
    public ApiResponse<String> withdrawQuestApplication(@PathVariable UUID id) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        questService.withdrawQuestApplication(userId, id);
        return ApiResponse.success("Đã rút đơn Quest.");
    }

    // ── Organizer ─────────────────────────────────────────────────────────────

    /** POST /api/v1/organizer/quests — create a new quest */
    @PostMapping("/organizer/quests")
    public ApiResponse<Map<String, Object>> createQuest(@RequestBody Map<String, Object> body) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        return ApiResponse.success(questService.createQuest(userId, body));
    }

    /** GET /api/v1/organizer/quests — list own quests */
    @GetMapping("/organizer/quests")
    public ApiResponse<List<Map<String, Object>>> getOrganizerQuests() {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        return ApiResponse.success(questService.getOrganizerQuests(userId));
    }

    /** GET /api/v1/organizer/quests/{id} — get single quest for editing */
    @GetMapping("/organizer/quests/{id}")
    public ApiResponse<Map<String, Object>> getOrganizerQuestById(@PathVariable UUID id) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        return ApiResponse.success(questService.getOrganizerQuestById(userId, id));
    }

    /** PUT /api/v1/organizer/quests/{id} — update quest */
    @PutMapping("/organizer/quests/{id}")
    public ApiResponse<String> updateQuest(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        questService.updateQuest(userId, id, body);
        return ApiResponse.success("Cập nhật Quest thành công.");
    }

    /** PATCH /api/v1/organizer/quests/{id}/close — close an OPEN quest */
    @PatchMapping("/organizer/quests/{id}/close")
    public ApiResponse<String> closeQuest(@PathVariable UUID id) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        questService.closeQuest(userId, id);
        return ApiResponse.success("Đã đóng Quest.");
    }

    /** DELETE /api/v1/organizer/quests/{id} — delete quest */
    @DeleteMapping("/organizer/quests/{id}")
    public ApiResponse<String> deleteQuest(@PathVariable UUID id) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        questService.deleteQuest(userId, id);
        return ApiResponse.success("Xoá Quest thành công.");
    }

    /** GET /api/v1/organizer/quests/{id}/applications */
    @GetMapping("/organizer/quests/{id}/applications")
    public ApiResponse<List<Map<String, Object>>> getQuestApplicants(@PathVariable UUID id) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        return ApiResponse.success(questService.getQuestApplicants(id, userId));
    }

    /** PATCH /api/v1/organizer/quest-applications/{id}/status */
    @PatchMapping("/organizer/quest-applications/{id}/status")
    public ApiResponse<String> updateQuestApplicationStatus(
            @PathVariable UUID id,
            @RequestBody QuestStatusRequest body
    ) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        questService.updateQuestApplicationStatus(id, userId, body.status(), body.rejectReason());
        return ApiResponse.success("Cập nhật trạng thái thành công.");
    }

    // ── Request records ───────────────────────────────────────────────────────

    record QuestApplyRequest(String coverNote) {}
    record QuestStatusRequest(String status, String rejectReason) {}
}
