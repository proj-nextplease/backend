package com.nextplease.backend.service;

import com.nextplease.backend.dto.request.UpdateNotificationPreferencesRequest;
import com.nextplease.backend.dto.request.UpdatePrivacySettingsRequest;
import com.nextplease.backend.dto.response.AccountSettingsResponse;
import com.nextplease.backend.dto.response.MeResponse;
import com.nextplease.backend.exception.AppException;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Candidate "Account Settings" (basic info, notification preferences, danger
 * zone) — distinct from {@link ProfileService}, which owns the public
 * Portfolio (name/bio/skills/experiences). Follows this codebase's existing
 * convention of raw {@link NamedParameterJdbcTemplate} SQL over JPA entity
 * saves (see AuthService/ProfileService) for consistency.
 */
@Service
public class AccountSettingsService {

    private final CurrentUserService currentUserService;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final SupabaseAdminService supabaseAdminService;

    public AccountSettingsService(
            CurrentUserService currentUserService,
            NamedParameterJdbcTemplate jdbcTemplate,
            SupabaseAdminService supabaseAdminService
    ) {
        this.currentUserService = currentUserService;
        this.jdbcTemplate = jdbcTemplate;
        this.supabaseAdminService = supabaseAdminService;
    }

    @Transactional(readOnly = true)
    public AccountSettingsResponse getSettings() {
        MeResponse currentUser = currentUserService.getCurrentUser();

        Map<String, Object> user = jdbcTemplate.queryForMap("""
                select display_name, email, status
                from app_users
                where id = :userId
                """, Map.of("userId", currentUser.appUserId()));

        // notification_preferences has no row until the user first saves a
        // change — fall back to the table's own column defaults.
        boolean emailEnabled = true;
        boolean pushEnabled = false;
        boolean inAppEnabled = true;
        try {
            Map<String, Object> prefs = jdbcTemplate.queryForMap("""
                    select email_enabled, push_enabled, in_app_enabled
                    from notification_preferences
                    where user_id = :userId
                    """, Map.of("userId", currentUser.appUserId()));
            emailEnabled = (Boolean) prefs.get("email_enabled");
            pushEnabled = (Boolean) prefs.get("push_enabled");
            inAppEnabled = (Boolean) prefs.get("in_app_enabled");
        } catch (EmptyResultDataAccessException ignored) {
            // Use defaults declared above.
        }

        // Profile row always exists by the time a user can reach Account
        // Settings (onboarding creates it — see ProfileService#getPortfolio),
        // but default defensively in case it ever doesn't.
        boolean isPublic = true;
        boolean openToWork = false;
        try {
            Map<String, Object> profile = jdbcTemplate.queryForMap("""
                    select is_public, open_to_work from profiles where user_id = :userId
                    """, Map.of("userId", currentUser.appUserId()));
            isPublic = profile.get("is_public") == null || (Boolean) profile.get("is_public");
            openToWork = profile.get("open_to_work") != null && (Boolean) profile.get("open_to_work");
        } catch (EmptyResultDataAccessException ignored) {
            // Use defaults declared above.
        }

        return new AccountSettingsResponse(
                (String) user.get("display_name"),
                (String) user.get("email"),
                (String) user.get("status"),
                emailEnabled,
                pushEnabled,
                inAppEnabled,
                isPublic,
                openToWork
        );
    }

    private static final java.util.regex.Pattern DISPLAY_NAME_PATTERN =
            java.util.regex.Pattern.compile("^(?=.*\\p{L})[\\p{L} .'-]+$");

    @Transactional
    public void updateDisplayName(String displayName) {
        MeResponse currentUser = currentUserService.getCurrentUser();
        String trimmed = displayName.trim();
        if (trimmed.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Tên hiển thị không được để trống.");
        }
        if (trimmed.length() < 2 || trimmed.length() > 50) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Tên hiển thị phải từ 2 đến 50 ký tự.");
        }
        if (!DISPLAY_NAME_PATTERN.matcher(trimmed).matches()) {
            throw new AppException(HttpStatus.BAD_REQUEST,
                    "Tên hiển thị chỉ được chứa chữ cái, khoảng trắng và các ký tự . ' -, không chứa số hoặc ký tự đặc biệt khác.");
        }

        jdbcTemplate.update("""
                update app_users
                set display_name = :displayName, updated_at = now()
                where id = :userId
                """, Map.of("displayName", trimmed, "userId", currentUser.appUserId()));

        jdbcTemplate.update("""
                insert into audit_logs (actor_user_id, action, entity_type, entity_id, metadata)
                values (:userId, 'account.display_name_updated', 'app_user', :userId,
                        jsonb_build_object('displayName', :displayName))
                """, Map.of("userId", currentUser.appUserId(), "displayName", trimmed));
    }

    @Transactional
    public void updateNotificationPreferences(UpdateNotificationPreferencesRequest request) {
        MeResponse currentUser = currentUserService.getCurrentUser();

        jdbcTemplate.update("""
                insert into notification_preferences (user_id, email_enabled, push_enabled, in_app_enabled, updated_at)
                values (:userId, :emailEnabled, :pushEnabled, :inAppEnabled, now())
                on conflict (user_id) do update set
                    email_enabled = excluded.email_enabled,
                    push_enabled = excluded.push_enabled,
                    in_app_enabled = excluded.in_app_enabled,
                    updated_at = now()
                """, Map.of(
                "userId", currentUser.appUserId(),
                "emailEnabled", request.emailEnabled(),
                "pushEnabled", request.pushEnabled(),
                "inAppEnabled", request.inAppEnabled()
        ));
    }

    /**
     * Toggles the two candidate-controlled visibility flags on {@code profiles}.
     * {@code isPublic} gates the unauthenticated share link (see
     * ProfileService#getPortfolioByUserId — owners/admins/businesses can still
     * always see it); {@code openToWork} is the same flag already editable from
     * the Portfolio editor, mirrored here for convenience.
     */
    @Transactional
    public void updatePrivacySettings(UpdatePrivacySettingsRequest request) {
        MeResponse currentUser = currentUserService.getCurrentUser();

        jdbcTemplate.update("""
                update profiles
                set is_public = :isPublic, open_to_work = :openToWork, updated_at = now()
                where user_id = :userId
                """, Map.of(
                "isPublic", request.isPublic(),
                "openToWork", request.openToWork(),
                "userId", currentUser.appUserId()
        ));
    }

    /**
     * "Đăng xuất khỏi tất cả thiết bị" — the app doesn't track individual
     * device/session records, so this is the coarse option: revoke every
     * refresh token for the account (GoTrue logout scope=global), including
     * the tab that requested it. Reuses the same mechanism as
     * {@link #deactivateAccount}.
     */
    public void signOutAllSessions(String callerAccessToken) {
        supabaseAdminService.signOutUser(callerAccessToken);
    }

    /**
     * Self-service "soft delete": freezes the account (blocks future logins,
     * see AuthService#login) rather than hard-deleting rows, since Portfolio /
     * Proof-of-Work data may still be referenced elsewhere (reviews, quest
     * history). Requires the current password as a confirmation step, then
     * revokes the caller's live Supabase session so the freeze takes effect
     * immediately instead of waiting for the access token to expire.
     */
    @Transactional
    public void deactivateAccount(String password, String callerAccessToken) {
        MeResponse currentUser = currentUserService.getCurrentUser();

        // Throws AppException(400) if the password doesn't match — reuses the
        // same Supabase password grant as normal login, no separate check needed.
        supabaseAdminService.authenticateUser(currentUser.email(), password);

        jdbcTemplate.update("""
                update app_users
                set status = 'FROZEN', updated_at = now()
                where id = :userId
                """, Map.of("userId", currentUser.appUserId()));

        jdbcTemplate.update("""
                insert into audit_logs (actor_user_id, action, entity_type, entity_id, metadata)
                values (:userId, 'account.deactivated', 'app_user', :userId, '{}'::jsonb)
                """, Map.of("userId", currentUser.appUserId()));

        supabaseAdminService.signOutUser(callerAccessToken);
    }

    /**
     * Người dùng TỰ XOÁ tài khoản — bắt buộc theo chính sách Google Play
     * (ứng dụng cho tạo tài khoản thì phải cho xoá ngay trong ứng dụng).
     *
     * Khác {@link #deactivateAccount}: đây là xoá thật, không mở lại được.
     *   - Xoá hồ sơ (profiles) — kéo theo kinh nghiệm, kỹ năng, minh chứng,
     *     tiến độ nhiệm vụ… vì mọi bảng con đều ON DELETE CASCADE.
     *   - Ẩn danh dòng app_users: email đổi thành địa chỉ giả để email thật
     *     đăng ký lại được (cột email là UNIQUE), tên thành "Người dùng đã xoá".
     *     Dòng này GIỮ LẠI vì đơn ứng tuyển, giao dịch ví, bài thảo luận…
     *     đang trỏ tới nó; xoá hẳn sẽ phá lịch sử của tổ chức và sổ sách.
     *   - Xoá tài khoản đăng nhập trên Supabase, nên không đăng nhập lại được.
     *
     * Chặn admin và người đang làm chủ tổ chức — cùng lý do như
     * AdminDashboardController#deleteUser.
     */
    @Transactional
    public void deleteOwnAccount(String confirmEmail) {
        MeResponse currentUser = currentUserService.getCurrentUser();
        java.util.UUID userId = currentUser.appUserId();

        if (confirmEmail == null || currentUser.email() == null
                || !confirmEmail.trim().equalsIgnoreCase(currentUser.email().trim())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Email xác nhận không khớp với email đăng nhập.");
        }
        if (currentUser.roles() != null && currentUser.roles().contains("admin")) {
            throw new AppException(HttpStatus.FORBIDDEN, "Tài khoản quản trị viên không tự xoá được.");
        }
        Integer ownsCompanies = jdbcTemplate.queryForObject(
                "select count(*) from companies where owner_user_id = :id", Map.of("id", userId), Integer.class);
        if (ownsCompanies != null && ownsCompanies > 0) {
            throw new AppException(HttpStatus.CONFLICT,
                    "Bạn đang là chủ sở hữu một tổ chức. Hãy chuyển quyền sở hữu trước khi xoá tài khoản.");
        }

        jdbcTemplate.update("""
                update authority_nodes set status = 'REJECTED', deleted_at = now(), updated_at = now()
                where user_id = :id and status <> 'REJECTED' and deleted_at is null
                """, Map.of("id", userId));

        jdbcTemplate.update("delete from profiles where user_id = :id", Map.of("id", userId));

        jdbcTemplate.update("""
                update app_users
                set status = 'DELETED',
                    deleted_at = now(),
                    email = 'deleted-' || id::text || '@deleted.fonlio.online',
                    display_name = 'Người dùng đã xoá',
                    updated_at = now()
                where id = :id
                """, Map.of("id", userId));

        jdbcTemplate.update("""
                insert into audit_logs (actor_user_id, action, entity_type, entity_id, metadata)
                values (:id, 'account.deleted_by_user', 'app_user', :id, '{}'::jsonb)
                """, Map.of("id", userId));

        // Gọi Supabase SAU CÙNG: nếu một câu SQL ở trên hỏng thì giao dịch
        // huỷ và người dùng vẫn còn đăng nhập được để thử lại, thay vì mất
        // tài khoản đăng nhập mà dữ liệu thì vẫn nằm đó.
        if (currentUser.supabaseUserId() != null) {
            supabaseAdminService.deleteUser(currentUser.supabaseUserId());
        }
    }
}
