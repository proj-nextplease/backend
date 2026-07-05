package com.nextplease.backend.dto.request;

import com.nextplease.backend.dto.CredentialDto;
import com.nextplease.backend.dto.ExperienceDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

public record PortfolioRequest(
        @NotBlank(message = "Họ và tên không được để trống")
        String name,
        
        String headline,
        String school,
        String location,
        String bio,
        List<String> skills,
        Map<String, Object> avatar,
        
        @Valid
        List<ExperienceDto> experiences,

        @Valid
        List<CredentialDto> credentials,

        Boolean openToWork,
        Map<String, Object> socialLinks
) {
}

