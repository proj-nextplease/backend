package com.nextplease.backend.controller;

import com.nextplease.backend.dto.request.JobCreateRequest;
import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.dto.response.MeResponse;
import com.nextplease.backend.service.CurrentUserService;
import com.nextplease.backend.service.JobService;
import jakarta.validation.Valid;
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
public class JobController {

    private final JobService jobService;
    private final CurrentUserService currentUserService;

    public JobController(JobService jobService, CurrentUserService currentUserService) {
        this.jobService = jobService;
        this.currentUserService = currentUserService;
    }

    // --- Public / Candidate endpoints ---

    @GetMapping("/jobs")
    public ApiResponse<List<Map<String, Object>>> searchJobs(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String specialty,
            @RequestParam(required = false) Integer minRs,
            @RequestParam(required = false) Boolean isRemote,
            @RequestParam(required = false) String jobType,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        List<Map<String, Object>> list = jobService.searchJobs(
                query, category, specialty, minRs, isRemote, jobType, companyId, limit, offset
        );
        return ApiResponse.success(list);
    }

    @GetMapping("/jobs/{id}")
    public ApiResponse<Map<String, Object>> getJobDetails(@PathVariable UUID id) {
        Map<String, Object> job = jobService.getJobDetails(id);
        return ApiResponse.success(job);
    }

    // --- Secured Organizer/Employer endpoints ---

    @PostMapping("/organizer/jobs")
    public ApiResponse<UUID> createJob(@Valid @RequestBody JobCreateRequest request) {
        MeResponse currentUser = currentUserService.getCurrentUser();
        UUID jobId = jobService.createJob(currentUser.appUserId(), request);
        return ApiResponse.success(jobId);
    }

    @PutMapping("/organizer/jobs/{id}")
    public ApiResponse<String> updateJob(
            @PathVariable UUID id,
            @Valid @RequestBody JobCreateRequest request
    ) {
        MeResponse currentUser = currentUserService.getCurrentUser();
        jobService.updateJob(id, currentUser.appUserId(), request);
        return ApiResponse.success("Cập nhật tin tuyển dụng thành công!");
    }

    @GetMapping("/organizer/jobs")
    public ApiResponse<List<Map<String, Object>>> getOrganizerJobs() {
        MeResponse currentUser = currentUserService.getCurrentUser();
        List<Map<String, Object>> list = jobService.getOrganizerJobs(currentUser.appUserId());
        return ApiResponse.success(list);
    }

    @GetMapping("/organizer/jobs/{id}")
    public ApiResponse<Map<String, Object>> getOrganizerJobById(@PathVariable UUID id) {
        MeResponse currentUser = currentUserService.getCurrentUser();
        return ApiResponse.success(jobService.getOrganizerJobById(currentUser.appUserId(), id));
    }

    @PatchMapping("/organizer/jobs/{id}/close")
    public ApiResponse<String> closeJob(@PathVariable UUID id) {
        MeResponse currentUser = currentUserService.getCurrentUser();
        jobService.closeJob(currentUser.appUserId(), id);
        return ApiResponse.success("Đã đóng tin tuyển dụng.");
    }

    @DeleteMapping("/organizer/jobs/{id}")
    public ApiResponse<String> deleteJob(@PathVariable UUID id) {
        MeResponse currentUser = currentUserService.getCurrentUser();
        jobService.deleteJob(currentUser.appUserId(), id);
        return ApiResponse.success("Xoá tin tuyển dụng thành công.");
    }

    @GetMapping("/skills")
    public ApiResponse<List<Map<String, Object>>> getSkills() {
        List<Map<String, Object>> list = jobService.getAllSkills();
        return ApiResponse.success(list);
    }

    @GetMapping("/companies")
    public ApiResponse<List<Map<String, Object>>> getApprovedCompanies() {
        List<Map<String, Object>> list = jobService.getApprovedCompanies();
        return ApiResponse.success(list);
    }

    @GetMapping("/companies/{id}")
    public ApiResponse<Map<String, Object>> getCompanyDetails(@PathVariable UUID id) {
        Map<String, Object> company = jobService.getCompanyDetails(id);
        return ApiResponse.success(company);
    }
}
