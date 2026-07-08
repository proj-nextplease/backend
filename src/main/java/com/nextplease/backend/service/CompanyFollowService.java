package com.nextplease.backend.service;

import com.nextplease.backend.dto.response.MeResponse;
import com.nextplease.backend.exception.ResourceNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Candidate "follows" a partner (company/CLB) to bookmark it. Uses raw
 * {@link NamedParameterJdbcTemplate} SQL per this codebase's convention.
 */
@Service
public class CompanyFollowService {

    private final CurrentUserService currentUserService;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public CompanyFollowService(CurrentUserService currentUserService, NamedParameterJdbcTemplate jdbcTemplate) {
        this.currentUserService = currentUserService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Follow is idempotent — following twice is a no-op. Throws 404 if the company doesn't exist. */
    @Transactional
    public void follow(UUID companyId) {
        MeResponse currentUser = currentUserService.getCurrentUser();
        Integer exists = jdbcTemplate.queryForObject(
                "select count(*) from companies where id = :id and deleted_at is null",
                Map.of("id", companyId), Integer.class);
        if (exists == null || exists == 0) {
            throw new ResourceNotFoundException("Không tìm thấy đối tác.");
        }
        jdbcTemplate.update("""
                insert into company_follows (user_id, company_id)
                values (:userId, :companyId)
                on conflict (user_id, company_id) do nothing
                """, Map.of("userId", currentUser.appUserId(), "companyId", companyId));
    }

    @Transactional
    public void unfollow(UUID companyId) {
        MeResponse currentUser = currentUserService.getCurrentUser();
        jdbcTemplate.update("""
                delete from company_follows
                where user_id = :userId and company_id = :companyId
                """, Map.of("userId", currentUser.appUserId(), "companyId", companyId));
    }

    /** Company ids the current user follows — the FE uses this to render follow state. */
    @Transactional(readOnly = true)
    public List<UUID> getFollowedCompanyIds() {
        MeResponse currentUser = currentUserService.getCurrentUser();
        return jdbcTemplate.query(
                "select company_id from company_follows where user_id = :userId",
                Map.of("userId", currentUser.appUserId()),
                (rs, i) -> (UUID) rs.getObject("company_id"));
    }
}
