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
        String avatarUrl,
        String publicSlug,
        List<ExperienceDto> experiences,
        List<CredentialDto> credentials,
        boolean onboardingCompleted,
        int reputationScore,
        long totalExp,
        int currentLevel,
        long npBalance,
        String selectedTheme,
        boolean themeUnlocked,
        boolean openToWork,
        Map<String, Object> socialLinks
) {
}
