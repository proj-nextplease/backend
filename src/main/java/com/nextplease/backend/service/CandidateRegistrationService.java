package com.nextplease.backend.service;

import com.nextplease.backend.dto.request.CandidateRegistrationOtpRequest;
import com.nextplease.backend.dto.request.CandidateRegistrationVerifyRequest;
import com.nextplease.backend.dto.response.CandidateRegistrationCompleteResponse;
import com.nextplease.backend.dto.response.CandidateRegistrationOtpResponse;
import com.nextplease.backend.enums.RoleCode;
import com.nextplease.backend.exception.AppException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CandidateRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(CandidateRegistrationService.class);

    private static final int OTP_TTL_MINUTES = 10;
    private static final int MAX_ATTEMPTS = 5;
    /** Minimum seconds between OTP requests for the same email to prevent spam. */
    private static final int OTP_COOLDOWN_SECONDS = 60;
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final EmailDeliveryService emailDeliveryService;
    private final SupabaseAdminService supabaseAdminService;
    private final ReputationService reputationService;
    private final ConfigService configService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final boolean exposeDevOtp;

    public CandidateRegistrationService(
            NamedParameterJdbcTemplate jdbcTemplate,
            EmailDeliveryService emailDeliveryService,
            SupabaseAdminService supabaseAdminService,
            ReputationService reputationService,
            ConfigService configService,
            @Value("${app.auth.registration.expose-dev-otp:false}") boolean exposeDevOtp
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.emailDeliveryService = emailDeliveryService;
        this.supabaseAdminService = supabaseAdminService;
        this.reputationService = reputationService;
        this.configService = configService;
        this.exposeDevOtp = exposeDevOtp;
    }

    /**
     * Step 1: Validate form data, store password hash, generate OTP, send OTP email.
     * The Supabase auth user is NOT created here – it is deferred to verifyOtp().
     */
    @Transactional
    public CandidateRegistrationOtpResponse requestOtp(CandidateRegistrationOtpRequest request) {
        String normalizedEmail = normalizeEmail(request.email());
        String normalizedStudentEmail = normalizeEmail(request.studentEmail());
        ensureCandidateDoesNotExist(normalizedEmail);

        // Enforce cooldown: reject if a PENDING attempt was created less than OTP_COOLDOWN_SECONDS ago
        enforceCooldown(normalizedEmail);

        // Revoke all previous pending attempts for this email
        jdbcTemplate.update("""
                update candidate_registration_attempts
                set status = 'REVOKED'
                where status = 'PENDING'
                  and lower(email) = :email
                """, Map.of(
                "email", normalizedEmail
        ));

        String otp = generateOtp();
        Instant expiresAt = Instant.now().plus(OTP_TTL_MINUTES, ChronoUnit.MINUTES);
        String passwordHash = passwordEncoder.encode(request.password());

        UUID registrationId = jdbcTemplate.queryForObject("""
                insert into candidate_registration_attempts (
                    email,
                    display_name,
                    student_email,
                    otp_hash_sha256,
                    password_hash,
                    max_attempts,
                    expires_at
                )
                values (
                    :email,
                    :displayName,
                    :studentEmail,
                    :otpHash,
                    :passwordHash,
                    :maxAttempts,
                    :expiresAt
                )
                returning id
                """, new MapSqlParameterSource()
                .addValue("email", normalizedEmail)
                .addValue("displayName", request.displayName().trim())
                .addValue("studentEmail", normalizedStudentEmail)
                .addValue("otpHash", sha256(otp))
                .addValue("passwordHash", passwordHash)
                .addValue("maxAttempts", MAX_ATTEMPTS)
                .addValue("expiresAt", Timestamp.from(expiresAt)), UUID.class);

        UUID emailLogId = jdbcTemplate.queryForObject("""
                insert into email_logs (recipient_email, template_code, provider, status, metadata)
                values (
                    :email,
                    'candidate_registration_otp',
                    :provider,
                    'PENDING',
                    jsonb_build_object('registration_id', :registrationId)
                )
                returning id
                """, Map.of(
                "email", normalizedEmail,
                "provider", emailDeliveryService.isEnabled() ? "smtp" : "mock",
                "registrationId", registrationId.toString()
        ), UUID.class);

        OffsetDateTime vietnamExpiresAt = expiresAt.atZone(VIETNAM_ZONE).toOffsetDateTime();
        sendOtpEmail(emailLogId, normalizedEmail, request.displayName(), otp, vietnamExpiresAt);

        return new CandidateRegistrationOtpResponse(
                registrationId,
                normalizedEmail,
                vietnamExpiresAt,
                exposeDevOtp ? otp : null
        );
    }

    /**
     * Step 2: Verify OTP, create Supabase auth user (server-side), create app_user + profile + wallet.
     */
    @Transactional
    public CandidateRegistrationCompleteResponse verifyOtp(CandidateRegistrationVerifyRequest request) {
        CandidateRegistrationAttempt attempt = findAttemptForUpdate(request.registrationId());

        if (!"PENDING".equals(attempt.status())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Registration attempt is not pending");
        }

        if (attempt.expiresAt().isBefore(Instant.now())) {
            jdbcTemplate.update("""
                    update candidate_registration_attempts
                    set status = 'EXPIRED'
                    where id = :id
                    """, Map.of("id", attempt.id()));
            throw new AppException(HttpStatus.BAD_REQUEST, "Registration OTP has expired");
        }

        if (!sha256(request.otp()).equals(attempt.otpHashSha256())) {
            int nextAttempts = attempt.attempts() + 1;
            String nextStatus = nextAttempts >= attempt.maxAttempts() ? "LOCKED" : "PENDING";
            jdbcTemplate.update("""
                    update candidate_registration_attempts
                    set attempts = :attempts,
                        status = :status
                    where id = :id
                    """, Map.of(
                    "attempts", nextAttempts,
                    "status", nextStatus,
                    "id", attempt.id()
            ));
            throw new AppException(HttpStatus.BAD_REQUEST, "Invalid registration OTP");
        }

        ensureCandidateDoesNotExist(attempt.email());

        // Verify the password matches what was submitted in the OTP request step
        if (attempt.passwordHash() != null && !passwordEncoder.matches(request.password(), attempt.passwordHash())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Mật khẩu không khớp với thông tin đã đăng ký. Vui lòng nhập lại.");
        }

        // --- Create Supabase auth user server-side (email already confirmed, no email sent) ---
        UUID supabaseUserId = supabaseAdminService.createUser(
                attempt.email(),
                request.password(),
                Map.of(
                        "display_name", attempt.displayName(),
                        "student_email", attempt.studentEmail(),
                        "role_intent", "candidate_free"
                )
        );

        // Update the registration attempt with the Supabase user ID
        jdbcTemplate.update("""
                update candidate_registration_attempts
                set supabase_user_id = :supabaseUserId
                where id = :id
                """, Map.of(
                "supabaseUserId", supabaseUserId,
                "id", attempt.id()
        ));

        UUID userId = jdbcTemplate.queryForObject("""
                insert into app_users (
                    supabase_user_id,
                    email,
                    display_name,
                    status,
                    auth_provider,
                    student_email_verified
                )
                values (
                    :supabaseUserId,
                    :email,
                    :displayName,
                    'ACTIVE',
                    'supabase',
                    false
                )
                returning id
                """, new MapSqlParameterSource()
                .addValue("supabaseUserId", supabaseUserId)
                .addValue("email", attempt.email())
                .addValue("displayName", attempt.displayName()), UUID.class);

        jdbcTemplate.update("""
                insert into user_roles (user_id, role_code)
                values (:userId, :roleCode)
                """, Map.of(
                "userId", userId,
                "roleCode", RoleCode.candidate_free.name()
        ));

        UUID profileId = jdbcTemplate.queryForObject("""
                insert into profiles (user_id, headline, visibility)
                values (:userId, :headline, '{}'::jsonb)
                returning id
                """, Map.of(
                "userId", userId,
                "headline", "Ứng viên nextplease"
        ), UUID.class);

        jdbcTemplate.update("""
                insert into wallets (user_id, np_balance, locked_np_balance)
                values (:userId, 0, 0)
                """, Map.of("userId", userId));

        // +10 RS for verifying student email — idempotent via unique index on source
        reputationService.addReputation(profileId, configService.getInt("rs_email_verified", 10), "EMAIL_VERIFIED", "registration", attempt.id());

        jdbcTemplate.update("""
                insert into audit_logs (actor_user_id, action, entity_type, entity_id, metadata)
                values (
                    :userId,
                    'candidate.registered',
                    'app_user',
                    :userId,
                    jsonb_build_object(
                        'registration_id', :registrationId,
                        'student_email', :studentEmail,
                        'role', :roleCode
                    )
                )
                """, Map.of(
                "userId", userId,
                "registrationId", attempt.id().toString(),
                "studentEmail", attempt.studentEmail(),
                "roleCode", RoleCode.candidate_free.name()
        ));

        jdbcTemplate.update("""
                update candidate_registration_attempts
                set status = 'VERIFIED',
                    verified_at = now(),
                    password_hash = null
                where id = :id
                """, Map.of("id", attempt.id()));

        return new CandidateRegistrationCompleteResponse(
                userId,
                supabaseUserId,
                profileId,
                attempt.email(),
                attempt.displayName(),
                RoleCode.candidate_free.name(),
                "ACTIVE"
        );
    }

    private void ensureCandidateDoesNotExist(String email) {
        Integer existingCount = jdbcTemplate.queryForObject("""
                select count(*)
                from app_users
                where lower(email) = :email
                """, Map.of(
                "email", normalizeEmail(email)
        ), Integer.class);

        if (existingCount != null && existingCount > 0) {
            throw new AppException(HttpStatus.CONFLICT, "Candidate account already exists");
        }
    }

    /**
     * Prevent rapid-fire OTP requests for the same email.
     * If a PENDING attempt was created less than OTP_COOLDOWN_SECONDS ago, reject.
     */
    private void enforceCooldown(String normalizedEmail) {
        Integer recentCount = jdbcTemplate.queryForObject("""
                select count(*)
                from candidate_registration_attempts
                where lower(email) = :email
                  and status = 'PENDING'
                  and created_at > now() - interval '1 second' * :cooldown
                """, Map.of(
                "email", normalizedEmail,
                "cooldown", OTP_COOLDOWN_SECONDS
        ), Integer.class);

        if (recentCount != null && recentCount > 0) {
            throw new AppException(HttpStatus.TOO_MANY_REQUESTS,
                    "Vui lòng đợi " + OTP_COOLDOWN_SECONDS + " giây trước khi yêu cầu mã OTP mới.");
        }
    }

    private CandidateRegistrationAttempt findAttemptForUpdate(UUID registrationId) {
        try {
            return jdbcTemplate.queryForObject("""
                    select id,
                           supabase_user_id,
                           email,
                           display_name,
                           student_email,
                           otp_hash_sha256,
                           password_hash,
                           status,
                           attempts,
                           max_attempts,
                           expires_at
                    from candidate_registration_attempts
                    where id = :id
                    for update
                    """, Map.of("id", registrationId), (rs, rowNum) -> new CandidateRegistrationAttempt(
                    rs.getObject("id", UUID.class),
                    rs.getObject("supabase_user_id", UUID.class),
                    rs.getString("email"),
                    rs.getString("display_name"),
                    rs.getString("student_email"),
                    rs.getString("otp_hash_sha256"),
                    rs.getString("password_hash"),
                    rs.getString("status"),
                    rs.getInt("attempts"),
                    rs.getInt("max_attempts"),
                    rs.getTimestamp("expires_at").toInstant()
            ));
        } catch (EmptyResultDataAccessException exception) {
            throw new AppException(HttpStatus.NOT_FOUND, "Registration attempt not found");
        }
    }

    private void sendOtpEmail(
            UUID emailLogId,
            String recipientEmail,
            String displayName,
            String otp,
            OffsetDateTime expiresAt
    ) {
        if (!emailDeliveryService.isEnabled()) {
            return;
        }

        try {
            emailDeliveryService.sendCandidateRegistrationOtp(recipientEmail, displayName, otp, expiresAt);
            jdbcTemplate.update("""
                    update email_logs
                    set status = 'SENT',
                        sent_at = now(),
                        metadata = metadata || jsonb_build_object('sent_via', 'smtp')
                    where id = :id
                    """, Map.of("id", emailLogId));
        } catch (EmailDeliveryService.EmailSendException exception) {
            jdbcTemplate.update("""
                    update email_logs
                    set status = 'FAILED',
                        metadata = metadata || jsonb_build_object('error', :error)
                    where id = :id
                    """, Map.of(
                    "id", emailLogId,
                    "error", exception.getMessage()
            ));
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "Không thể gửi mã OTP qua email. Kiểm tra cấu hình SMTP rồi thử lại nhé.");
        }
    }

    private String generateOtp() {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }

    private String normalizeEmail(String email) {
        return Optional.ofNullable(email)
                .map(String::trim)
                .map(String::toLowerCase)
                .orElse("");
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private record CandidateRegistrationAttempt(
            UUID id,
            UUID supabaseUserId,
            String email,
            String displayName,
            String studentEmail,
            String otpHashSha256,
            String passwordHash,
            String status,
            int attempts,
            int maxAttempts,
            Instant expiresAt
    ) {
    }
}
