package com.nextplease.backend.service;

import com.nextplease.backend.exception.AppException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Centralizes authorization for company/club (B2B) resources.
 *
 * Replaces the legacy 1-1 model (companies.owner_user_id = userId) with a membership model:
 * a user has access to a company when they hold an ACTIVE row in authority_nodes for that company.
 * companies.owner_user_id is kept only as the "current primary representative" pointer.
 */
@Service
public class CompanyAccessService {

    private static final Logger log = LoggerFactory.getLogger(CompanyAccessService.class);

    /**
     * SQL predicate fragment asserting that the user :authUserId holds an ACTIVE authority_node
     * for the company aliased as {@code c}. Callers must bind the :authUserId parameter.
     */
    public static final String ACTIVE_AUTHORITY_EXISTS =
            " exists (select 1 from authority_nodes an where an.company_id = c.id "
            + " and an.user_id = :authUserId and an.status = 'ACTIVE' and an.deleted_at is null) ";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public CompanyAccessService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Returns the primary company (id, verification_status, company_type) the user can act for,
     * preferring the company where they are OWNER. Throws 403 when the user belongs to none.
     */
    public Map<String, Object> resolveCompanyForUser(UUID userId) {
        try {
            return jdbcTemplate.queryForMap("""
                    select c.id, c.verification_status, c.company_type
                    from companies c
                    join authority_nodes an on an.company_id = c.id
                    where an.user_id = :userId
                      and an.status = 'ACTIVE'
                      and an.deleted_at is null
                    order by case an.node_role
                                 when 'OWNER' then 0
                                 when 'MANAGER' then 1
                                 else 2 end,
                             an.created_at asc
                    limit 1
                    """, Map.of("userId", userId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new AppException(HttpStatus.FORBIDDEN, "Tài khoản của bạn chưa được cấp quyền cho tổ chức đối tác nào.");
        }
    }

    /** Like {@link #resolveCompanyForUser} but also enforces verification_status = APPROVED. */
    public Map<String, Object> resolveApprovedCompanyForUser(UUID userId) {
        Map<String, Object> company = resolveCompanyForUser(userId);
        String status = (String) company.get("verification_status");
        if (!"APPROVED".equals(status)) {
            throw new AppException(HttpStatus.FORBIDDEN,
                    "Hồ sơ đối tác chưa được phê duyệt hoặc đang bị khóa. Bạn không thể thực hiện thao tác này.");
        }
        return company;
    }

    /** Resolves only the company id the user can act for (OWNER preferred), or empty. */
    public Optional<UUID> findActiveCompanyId(UUID userId) {
        try {
            UUID id = (UUID) resolveCompanyForUser(userId).get("id");
            return Optional.ofNullable(id);
        } catch (AppException e) {
            return Optional.empty();
        }
    }

    /** Returns the user's node_role (OWNER/MANAGER/MEMBER) within the company, or null if no active node. */
    public String roleInCompany(UUID userId, UUID companyId) {
        try {
            return jdbcTemplate.queryForObject("""
                    select node_role from authority_nodes
                    where company_id = :companyId and user_id = :userId
                      and status = 'ACTIVE' and deleted_at is null
                    """, Map.of("companyId", companyId, "userId", userId), String.class);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }

    public boolean hasActiveAuthority(UUID userId, UUID companyId) {
        return roleInCompany(userId, companyId) != null;
    }

    /**
     * Only OWNER/MANAGER may create or edit postings (jobs/quests). MEMBER is view + review only.
     */
    public void assertCanManagePostings(UUID userId, UUID companyId) {
        String role = roleInCompany(userId, companyId);
        if (!"OWNER".equals(role) && !"MANAGER".equals(role)) {
            throw new AppException(HttpStatus.FORBIDDEN,
                    "Chỉ Chủ sở hữu hoặc Quản lý mới được tạo/sửa tin đăng. Thành viên chỉ có thể xem và duyệt ứng viên.");
        }
    }
}
