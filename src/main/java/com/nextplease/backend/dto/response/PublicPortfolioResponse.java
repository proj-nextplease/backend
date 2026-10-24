package com.nextplease.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.nextplease.backend.dto.CredentialDto;
import com.nextplease.backend.dto.ExperienceDto;
import com.nextplease.backend.dto.SkillItemDto;
import java.util.List;
import java.util.Map;

/**
 * Public view of a candidate portfolio (endpoint {@code GET /profiles/{userId}/public}).
 *
 * <p>Deliberately a separate type from {@link PortfolioResponse}: it MUST NOT expose
 * private wallet data ({@code npBalance}) and only carries verified (APPROVED)
 * experiences. Keep it free of any field a stranger should not see.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PublicPortfolioResponse(
        String name,
        String headline,
        String school,
        String location,
        String bio,
        List<String> skills,
        List<SkillItemDto> skillDetails,
        Map<String, Object> avatar,
        String avatarUrl,
        String coverBannerUrl,
        String coverBannerPos,
        List<ExperienceDto> experiences,
        List<CredentialDto> credentials,
        int reputationScore,
        long totalExp,
        int currentLevel,
        String selectedTheme,
        boolean themeUnlocked,
        boolean openToWork,
        Map<String, Object> socialLinks,
        /** Huy hiệu Premium trên link hồ sơ ứng viên gửi cho nhà tuyển dụng. */
        boolean isPremium,
        /** Đã xuất bản thành website chính thức (1.000 Điểm) hay vẫn là bản xem trước. */
        boolean isExported,
        String publishedAt
) {
    public PublicPortfolioResponse(
            String name,
            String headline,
            String school,
            String location,
            String bio,
            List<String> skills,
            List<SkillItemDto> skillDetails,
            Map<String, Object> avatar,
            String avatarUrl,
            String coverBannerUrl,
            String coverBannerPos,
            List<ExperienceDto> experiences,
            List<CredentialDto> credentials,
            int reputationScore,
            long totalExp,
            int currentLevel,
            String selectedTheme,
            boolean themeUnlocked,
            boolean openToWork,
            Map<String, Object> socialLinks,
            boolean isPremium,
            boolean isExported
    ) {
        this(name, headline, school, location, bio, skills, skillDetails, avatar, avatarUrl,
                coverBannerUrl, coverBannerPos, experiences, credentials, reputationScore, totalExp,
                currentLevel, selectedTheme, themeUnlocked, openToWork, socialLinks, isPremium, isExported, null);
    }
}

