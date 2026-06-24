package com.nextplease.backend.dto.response;

import com.nextplease.backend.dto.CredentialDto;
import com.nextplease.backend.dto.ExperienceDto;
import java.util.List;
import java.util.Map;

/**
 * Public view of a candidate portfolio (endpoint {@code GET /profiles/{userId}/public}).
 *
 * <p>Deliberately a separate type from {@link PortfolioResponse}: it MUST NOT expose
 * private wallet data ({@code npBalance}) and only carries verified (APPROVED)
 * experiences. Keep it free of any field a stranger should not see.
 */
public record PublicPortfolioResponse(
        String name,
        String headline,
        String school,
        String location,
        String bio,
        List<String> skills,
        Map<String, Object> avatar,
        List<ExperienceDto> experiences,
        List<CredentialDto> credentials,
        int reputationScore,
        long totalExp,
        int currentLevel
) {
}
