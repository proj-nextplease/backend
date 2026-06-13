package com.nextplease.backend.controller;

import com.nextplease.backend.dto.request.CandidateRegistrationOtpRequest;
import com.nextplease.backend.dto.request.CandidateRegistrationVerifyRequest;
import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.dto.response.CandidateRegistrationCompleteResponse;
import com.nextplease.backend.dto.response.CandidateRegistrationOtpResponse;
import com.nextplease.backend.service.CandidateRegistrationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/candidates/register")
public class CandidateRegistrationController {

    private final CandidateRegistrationService candidateRegistrationService;

    public CandidateRegistrationController(CandidateRegistrationService candidateRegistrationService) {
        this.candidateRegistrationService = candidateRegistrationService;
    }

    @PostMapping("/request-otp")
    public ApiResponse<CandidateRegistrationOtpResponse> requestOtp(
            @Valid @RequestBody CandidateRegistrationOtpRequest request
    ) {
        return ApiResponse.success(candidateRegistrationService.requestOtp(request));
    }

    @PostMapping("/verify-otp")
    public ApiResponse<CandidateRegistrationCompleteResponse> verifyOtp(
            @Valid @RequestBody CandidateRegistrationVerifyRequest request
    ) {
        return ApiResponse.success(candidateRegistrationService.verifyOtp(request));
    }
}
