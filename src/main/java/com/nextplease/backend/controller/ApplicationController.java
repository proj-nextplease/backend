package com.nextplease.backend.controller;

import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.service.ApplicationService;
import com.nextplease.backend.service.CurrentUserService;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ApplicationController {

    private final ApplicationService applicationService;
    private final CurrentUserService currentUserService;

    public ApplicationController(ApplicationService applicationService, CurrentUserService currentUserService) {
        this.applicationService = applicationService;
        this.currentUserService = currentUserService;
    }

    /** POST /api/v1/jobs/{id}/apply — candidate applies to a job */
    @PostMapping("/jobs/{id}/apply")
    public ApiResponse<Map<String, Object>> applyToJob(
            @PathVariable UUID id,
            @RequestBody(required = false) ApplyRequest body
    ) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        String coverNote = body != null ? body.coverNote() : null;
        Map<String, Object> result = applicationService.apply(userId, id, coverNote);
        return ApiResponse.success(result);
    }

    /** GET /api/v1/me/applications — list my applications, newest first */
    @GetMapping("/me/applications")
    public ApiResponse<List<Map<String, Object>>> getMyApplications() {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        List<Map<String, Object>> list = applicationService.getMyApplications(userId);
        return ApiResponse.success(list);
    }

    /** GET /api/v1/organizer/jobs/{id}/applications — organizer sees all applicants for a job */
    @GetMapping("/organizer/jobs/{id}/applications")
    public ApiResponse<List<Map<String, Object>>> getJobApplications(@PathVariable UUID id) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        List<Map<String, Object>> list = applicationService.getJobApplications(id, userId);
        return ApiResponse.success(list);
    }

    /** PATCH /api/v1/organizer/applications/{id}/status — organizer updates application status */
    @org.springframework.web.bind.annotation.PatchMapping("/organizer/applications/{id}/status")
    public ApiResponse<String> updateApplicationStatus(
            @PathVariable UUID id,
            @RequestBody StatusUpdateRequest body
    ) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        applicationService.updateApplicationStatus(id, userId, body.status(), body.rejectReason());
        return ApiResponse.success("Cập nhật trạng thái thành công.");
    }

    /** PATCH /api/v1/me/applications/{id}/withdraw — candidate withdraws own application */
    @PatchMapping("/me/applications/{id}/withdraw")
    public ApiResponse<String> withdrawApplication(@PathVariable UUID id) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        applicationService.withdrawApplication(userId, id);
        return ApiResponse.success("Đã rút đơn ứng tuyển.");
    }

    record ApplyRequest(@Size(max = 1000) String coverNote) {}
    record StatusUpdateRequest(String status, String rejectReason) {}
}
