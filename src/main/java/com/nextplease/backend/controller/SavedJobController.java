package com.nextplease.backend.controller;

import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.service.SavedJobService;
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
public class SavedJobController {

    private final SavedJobService savedJobService;

    public SavedJobController(SavedJobService savedJobService) {
        this.savedJobService = savedJobService;
    }

    @PostMapping("/jobs/{id}/save")
    public ApiResponse<String> save(@PathVariable UUID id) {
        savedJobService.save(id);
        return ApiResponse.success("Đã lưu tin tuyển dụng.");
    }

    @DeleteMapping("/jobs/{id}/save")
    public ApiResponse<String> unsave(@PathVariable UUID id) {
        savedJobService.unsave(id);
        return ApiResponse.success("Đã bỏ lưu tin tuyển dụng.");
    }

    @GetMapping("/me/saved-jobs/ids")
    public ApiResponse<List<UUID>> getSavedIds() {
        return ApiResponse.success(savedJobService.getSavedJobIds());
    }

    @GetMapping("/me/saved-jobs")
    public ApiResponse<List<Map<String, Object>>> getSavedJobs() {
        return ApiResponse.success(savedJobService.getSavedJobs());
    }
}
