package com.nextplease.backend.controller;

import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.service.CompanyFollowService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CompanyFollowController {

    private final CompanyFollowService companyFollowService;

    public CompanyFollowController(CompanyFollowService companyFollowService) {
        this.companyFollowService = companyFollowService;
    }

    @PostMapping("/companies/{id}/follow")
    public ApiResponse<String> follow(@PathVariable UUID id) {
        companyFollowService.follow(id);
        return ApiResponse.success("Đã theo dõi đối tác.");
    }

    @DeleteMapping("/companies/{id}/follow")
    public ApiResponse<String> unfollow(@PathVariable UUID id) {
        companyFollowService.unfollow(id);
        return ApiResponse.success("Đã bỏ theo dõi đối tác.");
    }

    @GetMapping("/me/followed-companies")
    public ApiResponse<List<UUID>> getFollowed() {
        return ApiResponse.success(companyFollowService.getFollowedCompanyIds());
    }
}
