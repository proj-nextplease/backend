package com.nextplease.backend.controller;

import com.nextplease.backend.dto.request.PortfolioRequest;
import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.dto.response.PortfolioResponse;
import com.nextplease.backend.dto.response.PublicPortfolioResponse;
import com.nextplease.backend.service.ProfileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/profiles")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/me")
    public ApiResponse<PortfolioResponse> getMyPortfolio() {
        return ApiResponse.success(profileService.getPortfolio());
    }

    @GetMapping("/{userId}/public")
    public ApiResponse<PublicPortfolioResponse> getPublicProfile(@PathVariable UUID userId) {
        return ApiResponse.success(profileService.getPortfolioByUserId(userId));
    }

    /** Hồ sơ công khai tra theo đường dẫn chữ, ví dụ /p/phat-nguyen. */
    @GetMapping("/by-slug/{slug}/public")
    public ApiResponse<PublicPortfolioResponse> getPublicProfileBySlug(@PathVariable String slug) {
        return ApiResponse.success(profileService.getPortfolioBySlug(slug));
    }

    /** Đổi đường dẫn công khai của chính mình. */
    @PatchMapping("/me/slug")
    public ApiResponse<Map<String, String>> updateMySlug(@RequestBody Map<String, String> body) {
        String slug = profileService.updatePublicSlug(body.get("slug"));
        return ApiResponse.success(Map.of("publicSlug", slug));
    }

    @PutMapping("/me")
    public ApiResponse<String> updateMyPortfolio(
            @Valid @RequestBody PortfolioRequest request,
            @RequestParam(value = "draft", defaultValue = "false") boolean isDraft
    ) {
        profileService.updatePortfolio(request, isDraft);
        return ApiResponse.success("Lưu portfolio thành công!");
    }
}
