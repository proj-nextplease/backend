package com.nextplease.backend.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminB2bService {

    private static final Logger log = LoggerFactory.getLogger(AdminB2bService.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AdminB2bService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> getPendingRegistrations() {
        log.info("Fetching all pending B2B registrations for admin approval queue");
        return jdbcTemplate.queryForList("""
                select c.id,
                       c.owner_user_id as "ownerUserId",
                       c.name,
                       c.company_type as "companyType",
                       c.description,
                       c.website_url as "websiteUrl",
                       c.logo_url as "logoUrl",
                       c.document_url as "documentUrl",
                       c.tax_code as "taxCode",
                       c.representative_name as "representativeName",
                       c.representative_phone as "representativePhone",
                       c.school_id as "schoolId",
                       c.fanpage_url as "fanpageUrl",
                       c.advisor_contact as "advisorContact",
                       c.verification_status as "verificationStatus",
                       c.created_at as "createdAt",
                       u.email as "ownerEmail",
                       s.name as "schoolName",
                       exists (
                           select 1
                           from companies c2
                           where c2.name = c.name
                             and c2.verification_status = 'APPROVED'
                             and c2.id <> c.id
                       ) as "isDuplicateNameApproved"
                from companies c
                join app_users u on c.owner_user_id = u.id
                left join schools s on c.school_id = s.id
                where c.verification_status = 'PENDING'
                  and (c.tax_code is not null or c.document_url is not null or c.representative_name is not null)
                order by c.created_at desc
                """, Map.of());
    }

    @Transactional
    public void approveB2b(UUID companyId, UUID adminUserId) {
        log.info("Approving B2B company/club with id: {} by admin: {}", companyId, adminUserId);

        int updated = jdbcTemplate.update("""
                update companies
                set verification_status = 'APPROVED',
                    rejection_reason = null,
                    updated_at = now()
                where id = :companyId
                """, Map.of("companyId", companyId));

        if (updated == 0) {
            throw new IllegalArgumentException("Không tìm thấy tổ chức yêu cầu phê duyệt.");
        }

        jdbcTemplate.update("""
                insert into audit_logs (actor_user_id, action, entity_type, entity_id, metadata)
                values (
                    :adminUserId,
                    'b2b.approved',
                    'company',
                    :companyId,
                    jsonb_build_object('company_id', :companyId)
                )
                """, Map.of(
                "adminUserId", adminUserId,
                "companyId", companyId
        ));
    }

    @Transactional
    public void rejectB2b(UUID companyId, String reason, UUID adminUserId) {
        log.info("Rejecting B2B company/club with id: {} by admin: {} for reason: {}", companyId, adminUserId, reason);

        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Lý do từ chối không được để trống.");
        }

        int updated = jdbcTemplate.update("""
                update companies
                set verification_status = 'REJECTED',
                    rejection_reason = :reason,
                    updated_at = now()
                where id = :companyId
                """, Map.of(
                "companyId", companyId,
                "reason", reason.trim()
        ));

        if (updated == 0) {
            throw new IllegalArgumentException("Không tìm thấy tổ chức yêu cầu phê duyệt.");
        }

        jdbcTemplate.update("""
                insert into audit_logs (actor_user_id, action, entity_type, entity_id, metadata)
                values (
                    :adminUserId,
                    'b2b.rejected',
                    'company',
                    :companyId,
                    jsonb_build_object('company_id', :companyId, 'reason', :reason)
                )
                """, Map.of(
                "adminUserId", adminUserId,
                "companyId", companyId,
                "reason", reason.trim()
        ));
    }
}
