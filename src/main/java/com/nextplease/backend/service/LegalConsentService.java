package com.nextplease.backend.service;

import com.nextplease.backend.dto.response.MeResponse;
import com.nextplease.backend.exception.AppException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi nhận và tra cứu việc đồng ý với văn bản pháp lý.
 *
 * Xem V49__legal_consent.sql để biết vì sao dùng bảng riêng thay vì cột.
 */
@Service
public class LegalConsentService {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;

    public LegalConsentService(NamedParameterJdbcTemplate jdbcTemplate, CurrentUserService currentUserService) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
    }

    @Transactional
    public void accept(String version) {
        if (version == null || version.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Thiếu phiên bản văn bản pháp lý.");
        }
        MeResponse currentUser = currentUserService.getCurrentUser();
        // on conflict do nothing: bấm hai lần hoặc tải lại trang không sinh thêm
        // bản ghi, và thời điểm đồng ý ĐẦU TIÊN được giữ nguyên — đó mới là mốc
        // có ý nghĩa pháp lý.
        jdbcTemplate.update("""
                insert into user_consents (user_id, version)
                values (:userId, :version)
                on conflict (user_id, version) do nothing
                """, Map.of("userId", currentUser.appUserId(), "version", version));
    }

    /**
     * Phiên bản mới nhất mà người dùng đã đồng ý, hoặc null nếu chưa bao giờ.
     * FE so nó với LEGAL_VERSION để quyết định có chặn hay không.
     */
    @Transactional(readOnly = true)
    public String latestAcceptedVersion(java.util.UUID appUserId) {
        return jdbcTemplate.query("""
                select version from user_consents
                where user_id = :userId
                order by accepted_at desc
                limit 1
                """, Map.of("userId", appUserId),
                rs -> rs.next() ? rs.getString("version") : null);
    }
}
