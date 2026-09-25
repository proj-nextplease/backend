package com.nextplease.backend.controller;

import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.service.DiscussionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Diễn đàn Thảo Luận. GET đọc được khi chưa đăng nhập; mọi thao tác ghi thì không. */
@RestController
@RequestMapping("/api/v1/discussions")
public class DiscussionController {

    private final DiscussionService discussionService;

    public DiscussionController(DiscussionService discussionService) {
        this.discussionService = discussionService;
    }

    @GetMapping("/topics")
    public ApiResponse<List<Map<String, Object>>> getTopics() {
        return ApiResponse.success(discussionService.getTopics());
    }

    @PostMapping("/topics/{id}/follow")
    public ApiResponse<Map<String, Object>> toggleFollow(@PathVariable UUID id) {
        boolean following = discussionService.toggleFollowTopic(id);
        return ApiResponse.success(Map.of("isFollowing", following));
    }

    @GetMapping("/posts")
    public ApiResponse<List<Map<String, Object>>> getPosts(
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return ApiResponse.success(discussionService.getPosts(topic, sort, limit, offset));
    }

    @PostMapping("/posts")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> createPost(@RequestBody Map<String, Object> body) {
        Object options = body.get("pollOptions");
        return ApiResponse.success(discussionService.createPost(
                (String) body.get("topic"),
                (String) body.get("content"),
                options instanceof List<?> list ? (List<String>) list : null,
                Boolean.TRUE.equals(body.get("isAnonymous"))));
    }

    @DeleteMapping("/posts/{id}")
    public ApiResponse<String> deletePost(@PathVariable UUID id) {
        discussionService.deletePost(id);
        return ApiResponse.success("Đã xoá bài viết.");
    }

    @PostMapping("/posts/{id}/like")
    public ApiResponse<Map<String, Object>> toggleLike(@PathVariable UUID id) {
        return ApiResponse.success(discussionService.toggleLike(id));
    }

    @PostMapping("/posts/{id}/vote")
    public ApiResponse<Map<String, Object>> vote(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        return ApiResponse.success(discussionService.votePoll(id, UUID.fromString(body.get("optionId"))));
    }

    @GetMapping("/posts/{id}")
    public ApiResponse<Map<String, Object>> getPost(@PathVariable UUID id) {
        return ApiResponse.success(discussionService.getPost(id));
    }

    @GetMapping("/posts/{id}/comments")
    public ApiResponse<List<Map<String, Object>>> getComments(@PathVariable UUID id) {
        return ApiResponse.success(discussionService.getComments(id));
    }

    @PostMapping("/posts/{id}/comments")
    public ApiResponse<Map<String, Object>> addComment(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(discussionService.addComment(
                id,
                (String) body.get("content"),
                Boolean.TRUE.equals(body.get("isAnonymous"))));
    }
}
