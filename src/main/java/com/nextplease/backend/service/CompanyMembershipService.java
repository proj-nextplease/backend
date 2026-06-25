package com.nextplease.backend.service;

import com.nextplease.backend.dto.response.LoginResponse;
import com.nextplease.backend.exception.AppException;
import com.nextplease.backend.exception.ResourceNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Membership & delegation for company/club (B2B) organizations.
 *
 * Replaces self-service account creation: an OWNER (or admin-provisioned representative) invites
 * teammates by email; they self-login via Supabase and redeem the token to gain an authority_node.
 * Roles: OWNER > MANAGER > MEMBER. Ownership can be transferred.
 */
@Service
public class CompanyMembershipService {

    private static final Logger log = LoggerFactory.getLogger(CompanyMembershipService.class);
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int INVITE_TTL_DAYS = 7;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final CompanyAccessService companyAccessService;
    private final EmailDeliveryService emailDeliveryService;
    private final SupabaseAdminService supabaseAdminService;
    private final String frontendBaseUrl;

    public CompanyMembershipService(
            NamedParameterJdbcTemplate jdbcTemplate,
            CompanyAccessService companyAccessService,
            EmailDeliveryService emailDeliveryService,
            SupabaseAdminService supabaseAdminService,
            @Value("${app.frontend.base-url:http://localhost:5173}") String frontendBaseUrl
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.companyAccessService = companyAccessService;
        this.emailDeliveryService = emailDeliveryService;
        this.supabaseAdminService = supabaseAdminService;
        this.frontendBaseUrl = frontendBaseUrl.endsWith("/")
                ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1)
                : frontendBaseUrl;
    }

    // ── Members ────────────────────────────────────────────────────────────────

    public List<Map<String, Object>> listMembers(UUID actorUserId) {
        UUID companyId = requireCompany(actorUserId);
        return jdbcTemplate.queryForList("""
                select an.user_id    as "userId",
                       an.node_role  as "nodeRole",
                       an.node_type  as "nodeType",
                       an.status,
                       an.created_at as "joinedAt",
                       u.display_name as "displayName",
                       u.email
                from authority_nodes an
                join app_users u on u.id = an.user_id
                where an.company_id = :companyId
                  and an.status = 'ACTIVE'
                  and an.deleted_at is null
                order by case an.node_role when 'OWNER' then 0 when 'MANAGER' then 1 else 2 end,
                         an.created_at asc
                """, Map.of("companyId", companyId));
    }

    // ── Invitations ──────────────────────────────────────────────────────────--

    /** Owner/Manager invites a teammate by email. Returns the redeem link. */
    @Transactional
    public Map<String, Object> inviteMember(UUID actorUserId, String email, String nodeRole) {
        UUID companyId = requireCompany(actorUserId);
        requireManager(actorUserId, companyId);

        String role = normalizeRole(nodeRole);
        if ("OWNER".equals(role)) {
            throw new AppException(HttpStatus.BAD_REQUEST,
                    "Không thể mời trực tiếp với vai trò Chủ sở hữu. Hãy dùng chức năng Chuyển quyền sở hữu.");
        }
        // A MANAGER may only invite plain MEMBERs; granting MANAGER is reserved for the OWNER.
        if ("MANAGER".equals(companyAccessService.roleInCompany(actorUserId, companyId)) && !"MEMBER".equals(role)) {
            throw new AppException(HttpStatus.FORBIDDEN,
                    "Quản lý chỉ được mời với vai trò Thành viên. Chỉ Chủ sở hữu mới cấp vai trò Quản lý.");
        }
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase();
        if (normalizedEmail.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Email người được mời không hợp lệ.");
        }

        String companyName = jdbcTemplate.queryForObject(
                "select name from companies where id = :id", Map.of("id", companyId), String.class);

        String rawToken = generateToken();
        String tokenHash = sha256Hex(rawToken);
        OffsetDateTime expiresAt = OffsetDateTime.now(VN_ZONE).plusDays(INVITE_TTL_DAYS);

        jdbcTemplate.update("""
                insert into company_invitations
                    (company_id, invited_email, node_role, token_hash, status, invited_by, expires_at)
                values
                    (:companyId, :email, :role, :tokenHash, 'PENDING', :invitedBy, :expiresAt)
                """, new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("email", normalizedEmail)
                .addValue("role", role)
                .addValue("tokenHash", tokenHash)
                .addValue("invitedBy", actorUserId)
                .addValue("expiresAt", expiresAt));

        String inviteUrl = frontendBaseUrl + "/business/accept-invite?token=" + rawToken;
        Map<String, Object> delivery = deliverInvite(normalizedEmail, companyName, role, inviteUrl, expiresAt);

        writeAudit(actorUserId, "company.member_invited", companyId,
                "{\"email\":\"" + normalizedEmail + "\",\"role\":\"" + role + "\"}");

        Map<String, Object> result = new HashMap<>();
        result.put("inviteUrl", inviteUrl);
        result.put("email", normalizedEmail);
        result.put("role", role);
        result.putAll(delivery);
        return result;
    }

    public List<Map<String, Object>> listInvitations(UUID actorUserId) {
        UUID companyId = requireCompany(actorUserId);
        return jdbcTemplate.queryForList("""
                select id,
                       invited_email as "invitedEmail",
                       node_role     as "nodeRole",
                       status,
                       expires_at    as "expiresAt",
                       created_at    as "createdAt"
                from company_invitations
                where company_id = :companyId and status = 'PENDING'
                order by created_at desc
                """, Map.of("companyId", companyId));
    }

    @Transactional
    public void revokeInvitation(UUID actorUserId, UUID invitationId) {
        UUID companyId = requireCompany(actorUserId);
        requireManager(actorUserId, companyId);
        int updated = jdbcTemplate.update("""
                update company_invitations
                set status = 'REVOKED', updated_at = now()
                where id = :id and company_id = :companyId and status = 'PENDING'
                """, Map.of("id", invitationId, "companyId", companyId));
        if (updated == 0) {
            throw new ResourceNotFoundException("Không tìm thấy lời mời đang chờ để thu hồi.");
        }
        writeAudit(actorUserId, "company.invitation_revoked", companyId, "{}");
    }

    /**
     * Returns {invitedEmail, companyName, nodeRole, isNewUser} for the invite landing page.
     * Public (no auth) so a brand-new invitee can decide between "set password" and "log in".
     */
    public Map<String, Object> previewInvitation(String rawToken) {
        Map<String, Object> invite = loadRedeemableInvite(rawToken);
        String invitedEmail = (String) invite.get("invited_email");
        String companyName = jdbcTemplate.queryForObject(
                "select name from companies where id = :id",
                Map.of("id", invite.get("company_id")), String.class);
        Integer existing = jdbcTemplate.queryForObject(
                "select count(*) from app_users where lower(email) = :email",
                Map.of("email", invitedEmail), Integer.class);
        boolean isNewUser = existing == null || existing == 0;
        return Map.of(
                "invitedEmail", invitedEmail,
                "companyName", companyName,
                "nodeRole", invite.get("node_role"),
                "isNewUser", isNewUser);
    }

    /** Logged-in user redeems an invitation token to join the company. */
    @Transactional
    public Map<String, Object> acceptInvitation(UUID userId, String rawToken) {
        Map<String, Object> invite = loadRedeemableInvite(rawToken);

        // Bind the invite to its email: the logged-in account must match invited_email.
        String invitedEmail = (String) invite.get("invited_email");
        String userEmail = jdbcTemplate.queryForObject(
                "select email from app_users where id = :id", Map.of("id", userId), String.class);
        if (userEmail == null || !invitedEmail.equalsIgnoreCase(userEmail.trim())) {
            throw new AppException(HttpStatus.FORBIDDEN,
                    "Email đăng nhập không khớp với email được mời. Hãy đăng nhập bằng đúng email nhận lời mời.");
        }

        return finalizeAcceptance(invite, userId);
    }

    /**
     * New invitee with no account: set a password (creates the Supabase user), then join.
     * Returns a LoginResponse so the frontend can sign the user in immediately.
     * Public (no auth).
     */
    @Transactional
    public LoginResponse registerAndAccept(String rawToken, String password) {
        if (password == null || password.length() < 6) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Mật khẩu phải có ít nhất 6 ký tự.");
        }
        Map<String, Object> invite = loadRedeemableInvite(rawToken);
        String invitedEmail = (String) invite.get("invited_email");

        Integer existing = jdbcTemplate.queryForObject(
                "select count(*) from app_users where lower(email) = :email",
                Map.of("email", invitedEmail), Integer.class);
        if (existing != null && existing > 0) {
            throw new AppException(HttpStatus.CONFLICT,
                    "Email này đã có tài khoản. Vui lòng đăng nhập để chấp nhận lời mời.");
        }

        boolean isClub = "CLUB".equals(jdbcTemplate.queryForObject(
                "select company_type from companies where id = :id",
                Map.of("id", invite.get("company_id")), String.class));

        // 1. Create the Supabase auth user (email pre-confirmed, same as self-register).
        UUID supabaseUserId = supabaseAdminService.createUser(
                invitedEmail, password,
                Map.of("display_name", invitedEmail.split("@")[0]));

        // 2. Create the local app_user + profile.
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into app_users (id, supabase_user_id, email, display_name, status, auth_provider, created_at, updated_at)
                values (:id, :supabaseUserId, :email, :displayName, 'ACTIVE', 'supabase', now(), now())
                """, Map.of(
                "id", userId,
                "supabaseUserId", supabaseUserId,
                "email", invitedEmail,
                "displayName", invitedEmail.split("@")[0]));
        jdbcTemplate.update("""
                insert into profiles (user_id, headline, visibility)
                values (:userId, :headline, '{}'::jsonb)
                """, Map.of(
                "userId", userId,
                "headline", isClub ? "Câu lạc bộ / Tổ chức" : "Doanh nghiệp tuyển dụng"));

        // 3. Grant membership (node + role + owner pointer) and consume the invite.
        finalizeAcceptance(invite, userId);

        // 4. Authenticate to mint tokens carrying the freshly-synced roles.
        Map<String, Object> auth = supabaseAdminService.authenticateUser(invitedEmail, password);
        String accessToken = (String) auth.get("access_token");
        String refreshToken = (String) auth.get("refresh_token");
        String tokenType = (String) auth.get("token_type");
        long expiresIn = auth.get("expires_in") instanceof Number n ? n.longValue() : 3600L;

        Set<String> roles = currentRoles(userId);
        String displayName = jdbcTemplate.queryForObject(
                "select display_name from app_users where id = :id", Map.of("id", userId), String.class);
        return new LoginResponse(accessToken, refreshToken, tokenType, expiresIn,
                new LoginResponse.UserDto(userId, invitedEmail, displayName, roles));
    }

    // ── Role changes / transfer / removal ──────────────────────────────────────

    @Transactional
    public void changeMemberRole(UUID actorUserId, UUID targetUserId, String newRole) {
        UUID companyId = requireCompany(actorUserId);
        requireOwner(actorUserId, companyId);
        if (actorUserId.equals(targetUserId)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Bạn không thể tự đổi vai trò của chính mình.");
        }
        String role = normalizeRole(newRole);
        if ("OWNER".equals(role)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Hãy dùng chức năng Chuyển quyền sở hữu để trao quyền OWNER.");
        }
        int updated = jdbcTemplate.update("""
                update authority_nodes
                set node_role = :role, updated_at = now()
                where company_id = :companyId and user_id = :targetUserId
                  and status = 'ACTIVE' and deleted_at is null and node_role <> 'OWNER'
                """, Map.of("companyId", companyId, "targetUserId", targetUserId, "role", role));
        if (updated == 0) {
            throw new ResourceNotFoundException("Không tìm thấy thành viên hợp lệ để đổi vai trò.");
        }
        writeAudit(actorUserId, "company.member_role_changed", companyId,
                "{\"target\":\"" + targetUserId + "\",\"role\":\"" + role + "\"}");
    }

    @Transactional
    public void transferOwnership(UUID actorUserId, UUID targetUserId) {
        UUID companyId = requireCompany(actorUserId);
        requireOwner(actorUserId, companyId);
        if (actorUserId.equals(targetUserId)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Bạn đã là chủ sở hữu.");
        }
        String targetRole = companyAccessService.roleInCompany(targetUserId, companyId);
        if (targetRole == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Người nhận quyền phải là thành viên đang hoạt động của tổ chức.");
        }
        // Demote current owner to MANAGER, promote target to OWNER.
        jdbcTemplate.update("""
                update authority_nodes set node_role = 'MANAGER', updated_at = now()
                where company_id = :companyId and user_id = :actor and status = 'ACTIVE' and deleted_at is null
                """, Map.of("companyId", companyId, "actor", actorUserId));
        jdbcTemplate.update("""
                update authority_nodes set node_role = 'OWNER', updated_at = now()
                where company_id = :companyId and user_id = :target and status = 'ACTIVE' and deleted_at is null
                """, Map.of("companyId", companyId, "target", targetUserId));
        jdbcTemplate.update("update companies set owner_user_id = :target, updated_at = now() where id = :companyId",
                Map.of("target", targetUserId, "companyId", companyId));
        writeAudit(actorUserId, "company.ownership_transferred", companyId,
                "{\"newOwner\":\"" + targetUserId + "\"}");
    }

    @Transactional
    public void removeMember(UUID actorUserId, UUID targetUserId) {
        UUID companyId = requireCompany(actorUserId);
        requireManager(actorUserId, companyId);
        if (actorUserId.equals(targetUserId)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Bạn không thể tự gỡ chính mình. Hãy dùng chức năng Rời tổ chức.");
        }
        String actorRole = companyAccessService.roleInCompany(actorUserId, companyId);
        String targetRole = companyAccessService.roleInCompany(targetUserId, companyId);
        if ("OWNER".equals(targetRole)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Không thể gỡ Chủ sở hữu. Hãy chuyển quyền trước.");
        }
        // A MANAGER may only remove plain MEMBERs; removing/replacing a MANAGER is reserved for the OWNER.
        if ("MANAGER".equals(actorRole) && !"MEMBER".equals(targetRole)) {
            throw new AppException(HttpStatus.FORBIDDEN,
                    "Quản lý chỉ được gỡ Thành viên. Việc gỡ Quản lý do Chủ sở hữu thực hiện.");
        }
        int updated = jdbcTemplate.update("""
                update authority_nodes
                set status = 'REJECTED', deleted_at = now(), updated_at = now()
                where company_id = :companyId and user_id = :targetUserId
                  and status = 'ACTIVE' and deleted_at is null
                """, Map.of("companyId", companyId, "targetUserId", targetUserId));
        if (updated == 0) {
            throw new ResourceNotFoundException("Không tìm thấy thành viên để gỡ.");
        }
        writeAudit(actorUserId, "company.member_removed", companyId,
                "{\"target\":\"" + targetUserId + "\"}");
    }

    /** Rotates the token of a PENDING invitation, extends its TTL, and re-sends the email. */
    @Transactional
    public Map<String, Object> resendInvitation(UUID actorUserId, UUID invitationId) {
        UUID companyId = requireCompany(actorUserId);
        requireManager(actorUserId, companyId);

        Map<String, Object> invite;
        try {
            invite = jdbcTemplate.queryForMap("""
                    select id, invited_email, node_role, status
                    from company_invitations
                    where id = :id and company_id = :companyId
                    """, Map.of("id", invitationId, "companyId", companyId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Không tìm thấy lời mời.");
        }
        if (!"PENDING".equals(invite.get("status"))) {
            throw new AppException(HttpStatus.CONFLICT, "Chỉ có thể gửi lại lời mời đang chờ.");
        }
        String role = (String) invite.get("node_role");
        if ("MANAGER".equals(companyAccessService.roleInCompany(actorUserId, companyId)) && !"MEMBER".equals(role)) {
            throw new AppException(HttpStatus.FORBIDDEN, "Quản lý chỉ được gửi lại lời mời vai trò Thành viên.");
        }

        String rawToken = generateToken();
        OffsetDateTime expiresAt = OffsetDateTime.now(VN_ZONE).plusDays(INVITE_TTL_DAYS);
        jdbcTemplate.update("""
                update company_invitations
                set token_hash = :tokenHash, expires_at = :expiresAt, updated_at = now()
                where id = :id
                """, Map.of("tokenHash", sha256Hex(rawToken), "expiresAt", expiresAt, "id", invitationId));

        String email = (String) invite.get("invited_email");
        String companyName = jdbcTemplate.queryForObject(
                "select name from companies where id = :id", Map.of("id", companyId), String.class);
        String inviteUrl = frontendBaseUrl + "/business/accept-invite?token=" + rawToken;
        Map<String, Object> delivery = deliverInvite(email, companyName, role, inviteUrl, expiresAt);

        writeAudit(actorUserId, "company.invitation_resent", companyId, "{\"email\":\"" + email + "\"}");

        Map<String, Object> result = new HashMap<>();
        result.put("inviteUrl", inviteUrl);
        result.put("email", email);
        result.putAll(delivery);
        return result;
    }

    /** Current user voluntarily leaves their organization. OWNER must transfer ownership first. */
    @Transactional
    public void leaveCompany(UUID userId) {
        UUID companyId = requireCompany(userId);
        String role = companyAccessService.roleInCompany(userId, companyId);
        if ("OWNER".equals(role)) {
            throw new AppException(HttpStatus.BAD_REQUEST,
                    "Bạn là Chủ sở hữu. Hãy chuyển quyền sở hữu cho người khác trước khi rời tổ chức.");
        }
        jdbcTemplate.update("""
                update authority_nodes
                set status = 'REJECTED', deleted_at = now(), updated_at = now()
                where company_id = :companyId and user_id = :userId
                  and status = 'ACTIVE' and deleted_at is null
                """, Map.of("companyId", companyId, "userId", userId));
        writeAudit(userId, "company.member_left", companyId, "{}");
    }

    // ── Admin provisioning ──────────────────────────────────────────────────────

    /** Admin provisions an APPROVED company shell and invites the representative as OWNER. */
    @Transactional
    public Map<String, Object> provisionCompany(UUID adminUserId, String name, String companyType, String representativeEmail) {
        if (name == null || name.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Tên tổ chức không được để trống.");
        }
        String email = representativeEmail == null ? "" : representativeEmail.trim().toLowerCase();
        if (email.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Email người đại diện không hợp lệ.");
        }
        String type = companyType == null || companyType.isBlank() ? "SME" : companyType.trim().toUpperCase();

        // owner_user_id is NOT NULL; seed it with the admin until the representative accepts the OWNER invite.
        UUID companyId = jdbcTemplate.queryForObject("""
                insert into companies (owner_user_id, name, company_type, verification_status, created_at, updated_at)
                values (:adminUserId, :name, :type, 'PENDING', now(), now())
                returning id
                """, new MapSqlParameterSource()
                .addValue("adminUserId", adminUserId)
                .addValue("name", name.trim())
                .addValue("type", type), UUID.class);

        String rawToken = generateToken();
        OffsetDateTime expiresAt = OffsetDateTime.now(VN_ZONE).plusDays(INVITE_TTL_DAYS);
        jdbcTemplate.update("""
                insert into company_invitations
                    (company_id, invited_email, node_role, token_hash, status, invited_by, expires_at)
                values
                    (:companyId, :email, 'OWNER', :tokenHash, 'PENDING', :invitedBy, :expiresAt)
                """, new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("email", email)
                .addValue("tokenHash", sha256Hex(rawToken))
                .addValue("invitedBy", adminUserId)
                .addValue("expiresAt", expiresAt));

        String inviteUrl = frontendBaseUrl + "/business/accept-invite?token=" + rawToken;
        Map<String, Object> delivery = deliverInvite(email, name.trim(), "OWNER", inviteUrl, expiresAt);

        writeAudit(adminUserId, "admin.company_provisioned", companyId,
                "{\"name\":\"" + name.trim() + "\",\"email\":\"" + email + "\"}");

        Map<String, Object> result = new HashMap<>();
        result.put("companyId", companyId);
        result.put("inviteUrl", inviteUrl);
        result.put("email", email);
        result.putAll(delivery);
        return result;
    }

    // ── helpers ───────────────────────────────────────────────────────────────--

    /**
     * Attempts to send the invitation email and reports the ACTUAL outcome.
     * Returns {emailSent: bool, emailError: string}. Never throws — the invite row is already saved
     * and the caller always returns the redeem link as a fallback.
     */
    private Map<String, Object> deliverInvite(String email, String companyName, String role,
                                              String inviteUrl, OffsetDateTime expiresAt) {
        boolean sent = false;
        String error = "";
        if (emailDeliveryService.isEnabled()) {
            try {
                emailDeliveryService.sendCompanyInvitation(email, companyName, roleLabel(role), inviteUrl, expiresAt);
                sent = true;
            } catch (Exception e) {
                error = e.getMessage() == null ? "Lỗi không xác định khi gửi email." : e.getMessage();
                log.warn("Invitation email delivery failed for {}: {}", email, error);
            }
        } else {
            error = "Tính năng gửi email đang tắt (APP_MAIL_ENABLED=false hoặc thiếu BREVO_API_KEY).";
        }
        Map<String, Object> m = new HashMap<>();
        m.put("emailSent", sent);
        m.put("emailError", error);
        return m;
    }

    /** Loads an invite by raw token and asserts it is PENDING and not expired. Marks EXPIRED if past TTL. */
    private Map<String, Object> loadRedeemableInvite(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Thiếu mã lời mời.");
        }
        Map<String, Object> invite;
        try {
            invite = jdbcTemplate.queryForMap("""
                    select id, company_id, node_role, status, expires_at, invited_email
                    from company_invitations
                    where token_hash = :tokenHash
                    """, Map.of("tokenHash", sha256Hex(rawToken.trim())));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new AppException(HttpStatus.NOT_FOUND, "Lời mời không tồn tại hoặc đã bị thu hồi.");
        }
        if (!"PENDING".equals(invite.get("status"))) {
            throw new AppException(HttpStatus.CONFLICT, "Lời mời này đã được sử dụng hoặc không còn hiệu lực.");
        }
        java.sql.Timestamp expiresAt = (java.sql.Timestamp) invite.get("expires_at");
        if (expiresAt != null && expiresAt.toInstant().isBefore(java.time.Instant.now())) {
            jdbcTemplate.update("update company_invitations set status='EXPIRED', updated_at=now() where id=:id",
                    Map.of("id", invite.get("id")));
            throw new AppException(HttpStatus.GONE, "Lời mời đã hết hạn. Vui lòng yêu cầu mời lại.");
        }
        return invite;
    }

    /** Grants membership (node + B2B role + owner pointer if OWNER) and consumes the invite. */
    private Map<String, Object> finalizeAcceptance(Map<String, Object> invite, UUID userId) {
        UUID companyId = (UUID) invite.get("company_id");
        String role = (String) invite.get("node_role");

        upsertActiveNode(companyId, userId, role);
        assignB2bRoleAndSync(companyId, userId);

        // OWNER invitations (admin-provisioned) make the redeemer the primary representative.
        if ("OWNER".equals(role)) {
            jdbcTemplate.update("update companies set owner_user_id = :userId, updated_at = now() where id = :companyId",
                    Map.of("userId", userId, "companyId", companyId));
        }

        jdbcTemplate.update("""
                update company_invitations
                set status = 'ACCEPTED', accepted_by = :userId, accepted_at = now(), updated_at = now()
                where id = :id
                """, Map.of("userId", userId, "id", invite.get("id")));

        writeAudit(userId, "company.invitation_accepted", companyId, "{\"role\":\"" + role + "\"}");
        return Map.of("companyId", companyId, "role", role);
    }

    /** Ensures the user holds the B2B role for the company type, then syncs roles into the Supabase JWT. */
    private void assignB2bRoleAndSync(UUID companyId, UUID userId) {
        boolean isClub = "CLUB".equals(jdbcTemplate.queryForObject(
                "select company_type from companies where id = :id", Map.of("id", companyId), String.class));
        String roleCode = isClub ? "organizer" : "employer_free";
        jdbcTemplate.update("""
                insert into user_roles (user_id, role_code)
                values (:userId, :roleCode)
                on conflict (user_id, role_code) do nothing
                """, Map.of("userId", userId, "roleCode", roleCode));

        UUID supabaseUserId = jdbcTemplate.queryForObject(
                "select supabase_user_id from app_users where id = :id", Map.of("id", userId), UUID.class);
        if (supabaseUserId != null) {
            try {
                supabaseAdminService.updateUserAppMetadata(supabaseUserId, currentRoles(userId));
            } catch (Exception e) {
                log.warn("Non-fatal: failed to sync app_metadata for user {}: {}", userId, e.getMessage());
            }
        }
    }

    private Set<String> currentRoles(UUID userId) {
        return new HashSet<>(jdbcTemplate.queryForList(
                "select role_code from user_roles where user_id = :userId",
                Map.of("userId", userId), String.class));
    }

    private UUID requireCompany(UUID userId) {
        return companyAccessService.findActiveCompanyId(userId)
                .orElseThrow(() -> new AppException(HttpStatus.FORBIDDEN,
                        "Tài khoản của bạn chưa được cấp quyền cho tổ chức đối tác nào."));
    }

    private void requireOwner(UUID userId, UUID companyId) {
        if (!"OWNER".equals(companyAccessService.roleInCompany(userId, companyId))) {
            throw new AppException(HttpStatus.FORBIDDEN, "Chỉ Chủ sở hữu mới có quyền thực hiện thao tác này.");
        }
    }

    private void requireManager(UUID userId, UUID companyId) {
        String role = companyAccessService.roleInCompany(userId, companyId);
        if (!"OWNER".equals(role) && !"MANAGER".equals(role)) {
            throw new AppException(HttpStatus.FORBIDDEN, "Bạn không có quyền quản lý thành viên.");
        }
    }

    private void upsertActiveNode(UUID companyId, UUID userId, String role) {
        String companyType = jdbcTemplate.queryForObject(
                "select company_type from companies where id = :id", Map.of("id", companyId), String.class);
        String nodeType = "CLUB".equals(companyType) ? "CLUB_LEADER" : "COMPANY_MANAGER";

        int updated = jdbcTemplate.update("""
                update authority_nodes
                set node_role = :role, status = 'ACTIVE', deleted_at = null,
                    node_type = :nodeType, approved_at = now(), updated_at = now()
                where company_id = :companyId and user_id = :userId and deleted_at is null
                """, Map.of("companyId", companyId, "userId", userId, "role", role, "nodeType", nodeType));
        if (updated == 0) {
            jdbcTemplate.update("""
                    insert into authority_nodes (company_id, user_id, node_type, node_role, status, approved_at)
                    values (:companyId, :userId, :nodeType, :role, 'ACTIVE', now())
                    """, Map.of("companyId", companyId, "userId", userId, "role", role, "nodeType", nodeType));
        }
    }

    private void writeAudit(UUID actorUserId, String action, UUID companyId, String metadataJson) {
        try {
            jdbcTemplate.update("""
                    insert into audit_logs (actor_user_id, action, entity_type, entity_id, metadata)
                    values (:actor, :action, 'company', :companyId, :metadata::jsonb)
                    """, Map.of("actor", actorUserId, "action", action, "companyId", companyId, "metadata", metadataJson));
        } catch (Exception e) {
            log.warn("Failed to write membership audit log ({}): {}", action, e.getMessage());
        }
    }

    private static String normalizeRole(String role) {
        String r = role == null ? "MEMBER" : role.trim().toUpperCase();
        return switch (r) {
            case "OWNER", "MANAGER", "MEMBER" -> r;
            default -> throw new AppException(HttpStatus.BAD_REQUEST, "Vai trò không hợp lệ: " + role);
        };
    }

    private static String roleLabel(String role) {
        return switch (role) {
            case "OWNER" -> "Chủ sở hữu";
            case "MANAGER" -> "Quản lý";
            default -> "Thành viên";
        };
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
