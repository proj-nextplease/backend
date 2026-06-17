package com.nextplease.backend.controller;

import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.dto.response.MeResponse;
import com.nextplease.backend.service.CredentialService;
import com.nextplease.backend.service.CredentialService.SubmitCredentialRequest;
import com.nextplease.backend.service.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/credentials")
public class CredentialController {

    private final CredentialService credentialService;
    private final CurrentUserService currentUserService;

    public CredentialController(CredentialService credentialService, CurrentUserService currentUserService) {
        this.credentialService = credentialService;
        this.currentUserService = currentUserService;
    }

    /** GET /api/v1/credentials — list my proof submissions */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> getMySubmissions() {
        MeResponse me = currentUserService.getCurrentUser();
        List<Map<String, Object>> submissions = credentialService.getMySubmissions(me.appUserId());
        return ApiResponse.success(submissions);
    }

    /** POST /api/v1/credentials — submit a new proof-of-work */
    @PostMapping
    public ApiResponse<Map<String, Object>> submit(@Valid @RequestBody SubmitRequest body) {
        MeResponse me = currentUserService.getCurrentUser();
        SubmitCredentialRequest req = new SubmitCredentialRequest(
                body.projectName(),
                body.position(),
                body.category(),
                body.roleLevel(),
                body.description(),
                body.proofLink(),
                body.startedAt(),
                body.endedAt()
        );
        Map<String, Object> result = credentialService.submit(me.appUserId(), req);
        return ApiResponse.success(result);
    }

    public record SubmitRequest(
            @NotBlank(message = "Tên hoạt động không được để trống")
            @Size(max = 200)
            String projectName,

            @NotBlank(message = "Vai trò / chức danh không được để trống")
            @Size(max = 150)
            String position,

            @NotBlank(message = "Loại hình không được để trống")
            String category,

            @NotBlank(message = "Cấp bậc vai trò không được để trống")
            String roleLevel,

            @Size(max = 1000)
            String description,

            @Size(max = 500)
            String proofLink,

            String startedAt,
            String endedAt
    ) {}
}
