package com.nextplease.backend.dto.response;

import com.nextplease.backend.dto.CredentialDto;
import com.nextplease.backend.dto.ExperienceDto;
import java.util.List;
import java.util.Map;

public record PortfolioResponse(
        String name,
        String headline,
        String school,
        String location,
        String bio,
        List<String> skills,
        Map<String, Object> avatar,
        List<ExperienceDto> experiences,
        List<CredentialDto> credentials
) {
}
