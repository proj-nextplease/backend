package com.nextplease.backend.service;

import com.nextplease.backend.dto.request.B2bRegistrationRequest;
import com.nextplease.backend.dto.request.B2bUpdateRequest;
import com.nextplease.backend.entity.AppUser;
import com.nextplease.backend.enums.RoleCode;
import com.nextplease.backend.exception.AppException;
import com.nextplease.backend.repository.AppUserRepository;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class B2bRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(B2bRegistrationService.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final SupabaseAdminService supabaseAdminService;
    private final AppUserRepository appUserRepository;
    private final CompanyAccessService companyAccessService;

    private final NotificationService notificationService;

    public B2bRegistrationService(
            NamedParameterJdbcTemplate jdbcTemplate,
            SupabaseAdminService supabaseAdminService,
            AppUserRepository appUserRepository,
            CompanyAccessService companyAccessService,
            NotificationService notificationService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.supabaseAdminService = supabaseAdminService;
        this.appUserRepository = appUserRepository;
        this.companyAccessService = companyAccessService;
        this.notificationService = notificationService;
    }

    @Transactional
    public void registerB2b(B2bRegistrationRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase();
        
        // 1. Ensure user does not already exist in local DB
        Integer existingCount = jdbcTemplate.queryForObject("""
                select count(*)
                from app_users
                where lower(email) = :email
                """, Map.of("email", normalizedEmail), Integer.class);

        if (existingCount != null && existingCount > 0) {
            throw new AppException(HttpStatus.CONFLICT, "Email này đã được đăng ký trên hệ thống.");
        }

        // 1.5. Ensure taxCode is not already registered and approved
        if (request.taxCode() != null && !request.taxCode().isBlank()) {
            Integer duplicateTaxCodeCount = jdbcTemplate.queryForObject("""
                    select count(*)
                    from companies
                    where tax_code = :taxCode
                      and verification_status = 'APPROVED'
                    """, Map.of("taxCode", request.taxCode().trim()), Integer.class);
            if (duplicateTaxCodeCount != null && duplicateTaxCodeCount > 0) {
                throw new AppException(HttpStatus.CONFLICT, "Mã số thuế này đã được đăng ký và xác thực bởi một doanh nghiệp khác.");
            }
        }

        // 2. Determine target role
        boolean isClub = "CLUB".equalsIgnoreCase(request.companyType());
        RoleCode targetRole = isClub ? RoleCode.organizer : RoleCode.employer_free;

        UUID supabaseUserId = null;
        try {
            // 3. Create Supabase Auth User
            log.info("Creating Supabase user for B2B registration: {} (role: {})", normalizedEmail, targetRole);
            supabaseUserId = supabaseAdminService.createUser(
                    normalizedEmail,
                    request.password(),
                    Map.of(
                            "display_name", request.displayName().trim(),
                            "role_intent", targetRole.name(),
                            "representative_name", request.representativeName().trim(),
                            "company_name", request.companyName().trim()
                    )
            );

            // 4. Create Local app_user
            UUID userId = UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into app_users (
                        id,
                        supabase_user_id,
                        email,
                        display_name,
                        status,
                        auth_provider,
                        created_at,
                        updated_at
                    )
                    values (
                        :userId,
                        :supabaseUserId,
                        :email,
                        :displayName,
                        'ACTIVE',
                        'supabase',
                        now(),
                        now()
                    )
                    """, Map.of(
                    "userId", userId,
                    "supabaseUserId", supabaseUserId,
                    "email", normalizedEmail,
                    "displayName", request.displayName().trim()
            ));

            // 5. Assign user role
            jdbcTemplate.update("""
                    insert into user_roles (user_id, role_code)
                    values (:userId, :roleCode)
                    """, Map.of(
                    "userId", userId,
                    "roleCode", targetRole.name()
            ));

            // 6. Create profile (to maintain database model consistency)
            jdbcTemplate.update("""
                    insert into profiles (user_id, headline, visibility)
                    values (:userId, :headline, '{}'::jsonb)
                    """, Map.of(
                    "userId", userId,
                    "headline", isClub ? "Câu lạc bộ / Tổ chức" : "Doanh nghiệp tuyển dụng"
            ));

            // 7. Parse conditional fields
            UUID schoolUuid = null;
            if (request.schoolId() != null && !request.schoolId().isBlank()) {
                try {
                    schoolUuid = UUID.fromString(request.schoolId());
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid schoolId UUID format passed: {}", request.schoolId());
                }
            }

            // 8. Create local company record
            jdbcTemplate.update("""
                    insert into companies (
                        owner_user_id,
                        name,
                        company_type,
                        description,
                        website_url,
                        logo_url,
                        document_url,
                        tax_code,
                        representative_name,
                        representative_phone,
                        school_id,
                        fanpage_url,
                        advisor_contact,
                        verification_status,
                        created_at,
                        updated_at
                    )
                    values (
                        :ownerUserId,
                        :name,
                        :companyType,
                        :description,
                        :websiteUrl,
                        :logoUrl,
                        :documentUrl,
                        :taxCode,
                        :representativeName,
                        :representativePhone,
                        :schoolId,
                        :fanpageUrl,
                        :advisorContact::jsonb,
                        'PENDING',
                        now(),
                        now()
                    )
                    """, new MapSqlParameterSource()
                    .addValue("ownerUserId", userId)
                    .addValue("name", request.companyName().trim())
                    .addValue("companyType", request.companyType().toUpperCase().trim())
                    .addValue("description", request.description())
                    .addValue("websiteUrl", request.websiteUrl())
                    .addValue("logoUrl", request.logoUrl())
                    .addValue("documentUrl", request.documentUrl())
                    .addValue("taxCode", request.taxCode() != null ? request.taxCode().trim() : null)
                    .addValue("representativeName", request.representativeName().trim())
                    .addValue("representativePhone", request.representativePhone().trim())
                    .addValue("schoolId", schoolUuid)
                    .addValue("fanpageUrl", request.fanpageUrl())
                    .addValue("advisorContact", request.advisorContact() != null ? request.advisorContact() : "{}")
            );

            // 8.5. Grant the registering user an OWNER authority node (membership model)
            jdbcTemplate.update("""
                    insert into authority_nodes (company_id, user_id, node_type, node_role, status, approved_by, approved_at)
                    select id,
                           owner_user_id,
                           case when company_type = 'CLUB' then 'CLUB_LEADER' else 'COMPANY_MANAGER' end,
                           'OWNER',
                           'ACTIVE',
                           owner_user_id,
                           now()
                    from companies
                    where owner_user_id = :userId
                    """, Map.of("userId", userId));

            // 9. Write audit log
            jdbcTemplate.update("""
                    insert into audit_logs (actor_user_id, action, entity_type, entity_id, metadata)
                    values (
                        :userId,
                        :action,
                        'company',
                        :userId,
                        jsonb_build_object(
                            'email', :email,
                            'company_name', :companyName,
                            'company_type', :companyType
                        )
                    )
                    """, Map.of(
                    "userId", userId,
                    "action", isClub ? "club.registered" : "company.registered",
                    "email", normalizedEmail,
                    "companyName", request.companyName().trim(),
                    "companyType", request.companyType()
            ));

            log.info("B2B User registration completed successfully for email: {} (company: {})", normalizedEmail, request.companyName());

            notificationService.notifyAdmins("B2B_PENDING",
                    "Hồ sơ tổ chức mới chờ duyệt",
                    "\"" + request.companyName().trim() + "\" vừa đăng ký và đang chờ duyệt.",
                    "/nextplease-admin-portal/b2b-reviews");

        } catch (Exception e) {
            if (supabaseUserId != null) {
                log.warn("Error during local B2B registration database transactions. Rolling back Supabase Auth user: {}", supabaseUserId);
                try {
                    supabaseAdminService.deleteUser(supabaseUserId);
                } catch (Exception ex) {
                    log.error("Failed to clean up Supabase Auth user during rollback: {}", supabaseUserId, ex);
                }
            }
            throw e;
        }
    }

    public Map<String, Object> getMyCompany(UUID userId) {
        log.info("Fetching B2B company profile for user: {}", userId);
        UUID companyId = companyAccessService.findActiveCompanyId(userId)
                .orElseThrow(() -> new com.nextplease.backend.exception.ResourceNotFoundException(
                        "Tài khoản này chưa được cấp quyền cho tổ chức đối tác nào."));
        Map<String, Object> company = jdbcTemplate.queryForMap("""
                select id,
                       owner_user_id as "ownerUserId",
                       name,
                       company_type as "companyType",
                       description,
                       website_url as "websiteUrl",
                       logo_url as "logoUrl",
                       document_url as "documentUrl",
                       tax_code as "taxCode",
                       representative_name as "representativeName",
                       representative_phone as "representativePhone",
                       school_id as "schoolId",
                       fanpage_url as "fanpageUrl",
                       advisor_contact as "advisorContact",
                       verification_status as "verificationStatus",
                       rejection_reason as "rejectionReason"
                from companies
                where id = :companyId
                """, Map.of("companyId", companyId));
        // Surface the caller's role so the UI can gate owner-only actions.
        company.put("myRole", companyAccessService.roleInCompany(userId, companyId));
        return company;
    }

    /** Resolves the company the user manages and asserts OWNER/MANAGER rights for mutations. */
    private UUID resolveManagedCompanyId(UUID userId) {
        UUID companyId = companyAccessService.findActiveCompanyId(userId)
                .orElseThrow(() -> new com.nextplease.backend.exception.ResourceNotFoundException(
                        "Không tìm thấy thông tin đối tác để cập nhật."));
        String role = companyAccessService.roleInCompany(userId, companyId);
        if (!"OWNER".equals(role) && !"MANAGER".equals(role)) {
            throw new AppException(HttpStatus.FORBIDDEN, "Bạn không có quyền chỉnh sửa hồ sơ tổ chức.");
        }
        return companyId;
    }

    @Transactional
    public void updateCompanyDocument(UUID userId, String documentUrl) {
        log.info("Resubmitting B2B verification document for user: {}", userId);
        UUID companyId = resolveManagedCompanyId(userId);
        int updated = jdbcTemplate.update("""
                update companies
                set document_url = :documentUrl,
                    verification_status = 'PENDING',
                    rejection_reason = null,
                    updated_at = now()
                where id = :companyId
                """, Map.of(
                "companyId", companyId,
                "documentUrl", documentUrl
        ));

        if (updated == 0) {
            throw new IllegalArgumentException("Không tìm thấy thông tin đối tác để cập nhật.");
        }

        jdbcTemplate.update("""
                insert into audit_logs (actor_user_id, action, entity_type, entity_id, metadata)
                values (
                    :userId,
                    'b2b.resubmitted_document',
                    'company',
                    :userId,
                    jsonb_build_object('document_url', :documentUrl)
                )
                """, Map.of(
                "userId", userId,
                "documentUrl", documentUrl
        ));
    }

    @Transactional
    public void updateCompanyProfile(UUID userId, B2bUpdateRequest request) {
        log.info("Updating B2B company profile for user: {}", userId);
        UUID companyId = resolveManagedCompanyId(userId);

        UUID schoolUuid = null;
        if (request.schoolId() != null && !request.schoolId().isBlank()) {
            try {
                schoolUuid = UUID.fromString(request.schoolId());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid schoolId UUID format passed: {}", request.schoolId());
            }
        }

        int updated = jdbcTemplate.update("""
                update companies
                set name = :name,
                    company_type = :companyType,
                    description = :description,
                    website_url = :websiteUrl,
                    logo_url = :logoUrl,
                    document_url = :documentUrl,
                    tax_code = :taxCode,
                    representative_name = :representativeName,
                    representative_phone = :representativePhone,
                    school_id = :schoolId,
                    fanpage_url = :fanpageUrl,
                    advisor_contact = :advisorContact::jsonb,
                    verification_status = 'PENDING',
                    rejection_reason = null,
                    updated_at = now()
                where id = :companyId
                """, new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("name", request.companyName().trim())
                .addValue("companyType", request.companyType().toUpperCase().trim())
                .addValue("description", request.description())
                .addValue("websiteUrl", request.websiteUrl())
                .addValue("logoUrl", request.logoUrl())
                .addValue("documentUrl", request.documentUrl())
                .addValue("taxCode", request.taxCode() != null ? request.taxCode().trim() : null)
                .addValue("representativeName", request.representativeName().trim())
                .addValue("representativePhone", request.representativePhone().trim())
                .addValue("schoolId", schoolUuid)
                .addValue("fanpageUrl", request.fanpageUrl())
                .addValue("advisorContact", request.advisorContact() != null ? request.advisorContact() : "{}")
        );

        if (updated == 0) {
            throw new com.nextplease.backend.exception.ResourceNotFoundException("Không tìm thấy thông tin đối tác để cập nhật.");
        }

        jdbcTemplate.update("""
                insert into audit_logs (actor_user_id, action, entity_type, entity_id, metadata)
                values (
                    :userId,
                    'b2b.profile_updated',
                    'company',
                    :userId,
                    jsonb_build_object(
                        'company_name', :companyName,
                        'company_type', :companyType
                    )
                )
                """, Map.of(
                "userId", userId,
                "companyName", request.companyName().trim(),
                "companyType", request.companyType()
        ));
    }
}

