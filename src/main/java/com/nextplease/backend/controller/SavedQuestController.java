package com.nextplease.backend.controller;

import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.service.SavedQuestService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SavedQuestController {

    private final SavedQuestService savedQuestService;

    public SavedQuestController(SavedQuestService savedQuestService) {
        this.savedQuestService = savedQuestService;
    }

    @PostMapping("/quests/{id}/save")
    public ApiResponse<String> save(@PathVariable UUID id) {
        savedQuestService.save(id);
        return ApiResponse.success("Đã lưu Quest.");
    }

    @DeleteMapping("/quests/{id}/save")
    public ApiResponse<String> unsave(@PathVariable UUID id) {
        savedQuestService.unsave(id);
        return ApiResponse.success("Đã bỏ lưu Quest.");
    }

    @GetMapping("/me/saved-quests/ids")
    public ApiResponse<List<UUID>> getSavedIds() {
        return ApiResponse.success(savedQuestService.getSavedQuestIds());
    }

    @GetMapping("/me/saved-quests")
    public ApiResponse<List<Map<String, Object>>> getSavedQuests() {
        return ApiResponse.success(savedQuestService.getSavedQuests());
    }
}
