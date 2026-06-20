package com.nextplease.backend.controller;

import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.service.CurrentUserService;
import com.nextplease.backend.service.RatingService;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class RatingController {

    private final RatingService ratingService;
    private final CurrentUserService currentUserService;

    public RatingController(RatingService ratingService, CurrentUserService currentUserService) {
        this.ratingService = ratingService;
        this.currentUserService = currentUserService;
    }

    /** POST /api/v1/organizer/ratings — tổ chức đánh giá ứng viên của một đơn đã COMPLETED */
    @PostMapping("/organizer/ratings")
    public ApiResponse<Map<String, Object>> createRating(@RequestBody RatingRequest body) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        Map<String, Object> result = ratingService.createRating(
                userId, body.applicationId(), body.questApplicationId(), body.score(), body.comment());
        return ApiResponse.success(result);
    }

    /** PUT /api/v1/organizer/ratings/{id} — sửa đánh giá trong vòng 3 giờ */
    @PutMapping("/organizer/ratings/{id}")
    public ApiResponse<String> updateRating(@PathVariable UUID id, @RequestBody RatingUpdateRequest body) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        ratingService.updateRating(userId, id, body.score(), body.comment());
        return ApiResponse.success("Đã cập nhật đánh giá.");
    }

    /** GET /api/v1/organizer/ratings?applicationId=...  hoặc  ?questApplicationId=... */
    @GetMapping("/organizer/ratings")
    public ApiResponse<Map<String, Object>> getRating(
            @RequestParam(required = false) UUID applicationId,
            @RequestParam(required = false) UUID questApplicationId
    ) {
        return ApiResponse.success(ratingService.getRatingForApplication(applicationId, questApplicationId));
    }

    record RatingRequest(UUID applicationId, UUID questApplicationId, int score, @Size(max = 1000) String comment) {}
    record RatingUpdateRequest(int score, @Size(max = 1000) String comment) {}
}
