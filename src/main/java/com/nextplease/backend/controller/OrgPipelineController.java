package com.nextplease.backend.controller;

import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.service.CurrentUserService;
import com.nextplease.backend.service.PipelineService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Organizer pipeline customization (labels/colors/visibility over canonical statuses). */
@RestController
@RequestMapping("/api/v1/organizer/pipeline")
public class OrgPipelineController {

    private final PipelineService pipelineService;
    private final CurrentUserService currentUserService;

    public OrgPipelineController(PipelineService pipelineService, CurrentUserService currentUserService) {
        this.pipelineService = pipelineService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> get() {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        return ApiResponse.success(pipelineService.getPipeline(userId));
    }

    @PutMapping
    public ApiResponse<List<Map<String, Object>>> save(@RequestBody List<Map<String, Object>> stages) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        return ApiResponse.success(pipelineService.savePipeline(userId, stages));
    }
}
