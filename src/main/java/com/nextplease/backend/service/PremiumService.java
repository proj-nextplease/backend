package com.nextplease.backend.service;

import com.nextplease.backend.exception.AppException;
import com.nextplease.backend.exception.ResourceNotFoundException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
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
public class PremiumService {

    private static final Logger log = LoggerFactory.getLogger(PremiumService.class);
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ConfigService configService;
    private final NotificationService notificationService;

    public PremiumService(NamedParameterJdbcTemplate jdbcTemplate, ConfigService configService,
                          NotificationService notificationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.configService = configService;
        this.notificationService = notificationService;
    }

    /** Returns current prices and durations of all premium features */
    public Map<String, Object> getPremiumConfig() {
        return Map.of(
            "boostPriceNp", configService.getInt("premium_boost_price_np", 15000),
            "boostDurationHours", configService.getInt("premium_boost_duration_hours", 48),
            "insightPriceNp", configService.getInt("application_insight_price_np", 10000),
            "expressPriceNp", configService.getInt("express_verification_price_np", 25000),
            "themePriceNp", configService.getInt("profile_theme_price_np", 50000),
            "matchAlertPriceNp", configService.getInt("job_match_alert_price_np", 19000),
            "earlyAccessHours", configService.getInt("job_match_early_access_hours", 12)
        );
    }

    /** Buy a boost for a specific job or Quest application. */
    @Transactional
    public Map<String, Object> boostApplication(UUID userId, UUID applicationId, String applicationType) {
        String type = normalizeApplicationType(applicationType);
        boolean isQuest = "QUEST".equals(type);

        Map<String, Object> app;
        try {
            app = isQuest
                    ? jdbcTemplate.queryForMap("""
                            select qa.id, qa.candidate_id, qa.boosted_until, qa.status,
                                   q.title as target_title
                            from quest_applications qa
                            join quests q on q.id = qa.quest_id
                            where qa.id = :appId
                            """, Map.of("appId", applicationId))
                    : jdbcTemplate.queryForMap("""
                            select a.id, a.candidate_id, a.boosted_until, a.status,
                                   j.title as target_title
                            from applications a
                            join jobs j on j.id = a.job_id
                            where a.id = :appId
                            """, Map.of("appId", applicationId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Không tìm thấy đơn ứng tuyển để đẩy tin nổi bật.");
        }

        UUID candidateId = (UUID) app.get("candidate_id");
        if (!userId.equals(candidateId)) {
            throw new AppException(HttpStatus.FORBIDDEN, "Bạn không có quyền thực hiện hành động này.");
        }

        String status = (String) app.get("status");
        if (List.of("WITHDRAWN", "REJECTED", "COMPLETED").contains(status)) {
            throw new AppException(HttpStatus.CONFLICT,
                    "Không thể boost đơn ứng tuyển đã rút, bị từ chối hoặc đã hoàn thành.");
        }

        Map<String, Object> wallet = lockWalletOrThrow(userId);
        int balance = ((Number) wallet.get("np_balance")).intValue();
        int price = configService.getInt("premium_boost_price_np", 15000);

        if (balance < price) {
            throw new AppException(HttpStatus.PAYMENT_REQUIRED,
                    String.format("Số dư NP không đủ. Cần %,d NP, hiện tại %,d NP.", price, balance),
                    "INSUFFICIENT_NP");
        }

        OffsetDateTime now = OffsetDateTime.now(VN);
        OffsetDateTime currentExpiry = null;
        Object expiryRaw = app.get("boosted_until");
        if (expiryRaw instanceof OffsetDateTime odt && odt.isAfter(now)) {
            currentExpiry = odt;
        } else if (expiryRaw instanceof java.sql.Timestamp ts && ts.toInstant().isAfter(now.toInstant())) {
            currentExpiry = OffsetDateTime.ofInstant(ts.toInstant(), VN);
        }

        int durationHours = configService.getInt("premium_boost_duration_hours", 48);
        OffsetDateTime newExpiry = (currentExpiry != null ? currentExpiry : now).plusHours(durationHours);

        int newBalance = balance - price;
        jdbcTemplate.update("update wallets set np_balance = :balance, updated_at = now() where id = :walletId",
                Map.of("balance", newBalance, "walletId", wallet.get("id")));

        jdbcTemplate.update("""
                insert into wallet_transactions (wallet_id, amount_np, balance_after_np, transaction_type, reason, source_type, source_id, idempotency_key)
                values (:walletId, :amount, :balanceAfter, 'SPEND', :reason, :sourceType, :sourceId, :ikey)
                """, new MapSqlParameterSource()
                .addValue("walletId", wallet.get("id"))
                .addValue("amount", -price)
                .addValue("balanceAfter", newBalance)
                .addValue("reason", String.format("Đẩy tin nổi bật (Profile Boost): %s", app.get("target_title")))
                .addValue("sourceId", applicationId)
                .addValue("sourceType", isQuest ? "quest_application" : "application")
                .addValue("ikey", "boost_" + applicationId + "_" + System.currentTimeMillis()));

        String applicationTable = isQuest ? "quest_applications" : "applications";
        jdbcTemplate.update("update " + applicationTable + " set boosted_until = :expiry, updated_at = now() where id = :appId",
                Map.of("expiry", newExpiry, "appId", applicationId));

        log.info("[PremiumService] User {} boosted {} application {} until {}", userId, type, applicationId, newExpiry);
        return Map.of(
                "boostedUntil", newExpiry.toString(),
                "npBalance", newBalance,
                "durationHours", durationHours
        );
    }

    /** Unlock competitive candidate insight for a job or Quest. */
    @Transactional
    public Map<String, Object> unlockInsight(UUID userId, UUID targetId, String applicationType) {
        String type = normalizeApplicationType(applicationType);
        boolean isQuest = "QUEST".equals(type);
        assertCandidateApplied(userId, targetId, type);

        boolean isUnlocked = checkInsightUnlocked(userId, targetId, type);
        if (isUnlocked) {
            return Map.of("unlocked", true);
        }

        String targetTitle;
        try {
            targetTitle = jdbcTemplate.queryForObject(
                    "select title from " + (isQuest ? "quests" : "jobs") + " where id = :targetId",
                    Map.of("targetId", targetId),
                    String.class);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException(isQuest ? "Không tìm thấy Quest." : "Không tìm thấy tin tuyển dụng.");
        }

        Map<String, Object> wallet = lockWalletOrThrow(userId);
        int balance = ((Number) wallet.get("np_balance")).intValue();
        int price = configService.getInt("application_insight_price_np", 10000);

        if (balance < price) {
            throw new AppException(HttpStatus.PAYMENT_REQUIRED,
                    String.format("Số dư NP không đủ. Cần %,d NP, hiện tại %,d NP.", price, balance),
                    "INSUFFICIENT_NP");
        }

        int newBalance = balance - price;
        jdbcTemplate.update("update wallets set np_balance = :balance, updated_at = now() where id = :walletId",
                Map.of("balance", newBalance, "walletId", wallet.get("id")));

        jdbcTemplate.update("""
                insert into wallet_transactions (wallet_id, amount_np, balance_after_np, transaction_type, reason, source_type, source_id, idempotency_key)
                values (:walletId, :amount, :balanceAfter, 'SPEND', :reason, :sourceType, :sourceId, :ikey)
                """, new MapSqlParameterSource()
                .addValue("walletId", wallet.get("id"))
                .addValue("amount", -price)
                .addValue("balanceAfter", newBalance)
                .addValue("reason", String.format("Xem Application Insight cho %s: %s",
                        isQuest ? "Quest" : "tin", targetTitle))
                .addValue("sourceType", isQuest ? "quest" : "job")
                .addValue("sourceId", targetId)
                .addValue("ikey", "insight_" + type.toLowerCase() + "_" + userId + "_" + targetId));

        if (isQuest) {
            jdbcTemplate.update("""
                    insert into quest_application_insights_unlocks (user_id, quest_id, unlocked_at)
                    values (:userId, :targetId, now())
                    """, Map.of("userId", userId, "targetId", targetId));
        } else {
            jdbcTemplate.update("""
                    insert into application_insights_unlocks (user_id, job_id, unlocked_at)
                    values (:userId, :targetId, now())
                    """, Map.of("userId", userId, "targetId", targetId));
        }

        log.info("[PremiumService] User {} unlocked {} insights for {}", userId, type, targetId);
        return Map.of(
                "unlocked", true,
                "npBalance", newBalance
        );
    }

    /** Retrieve competitive insights for a job or Quest. */
    public Map<String, Object> getInsight(UUID userId, UUID targetId, String applicationType) {
        String type = normalizeApplicationType(applicationType);
        boolean isQuest = "QUEST".equals(type);
        assertCandidateApplied(userId, targetId, type);
        boolean isUnlocked = checkInsightUnlocked(userId, targetId, type);

        String applicationsTable = isQuest ? "quest_applications" : "applications";
        String targetColumn = isQuest ? "quest_id" : "job_id";
        Integer totalApplicants = jdbcTemplate.queryForObject(
                "select count(*) from " + applicationsTable
                        + " where " + targetColumn + " = :targetId and status != 'WITHDRAWN'",
                Map.of("targetId", targetId), Integer.class);
        if (totalApplicants == null) totalApplicants = 0;

        Double avgRsVal = jdbcTemplate.queryForObject(
                "select avg(p.reputation_score) from " + applicationsTable
                        + " a join profiles p on p.user_id = a.candidate_id"
                        + " where a." + targetColumn + " = :targetId and a.status != 'WITHDRAWN'",
                Map.of("targetId", targetId), Double.class);
        int avgRs = avgRsVal != null ? avgRsVal.intValue() : 0;

        if (!isUnlocked) {
            return Map.of(
                "unlocked", false,
                "totalApplicants", totalApplicants,
                "averageRs", 0,
                "myRank", 0,
                "percentile", 0
            );
        }

        // Rank by reputation first, then total EXP (tie-break), then who applied earlier.
        List<Map<String, Object>> candidates = jdbcTemplate.queryForList(
                "select a.candidate_id, p.reputation_score from " + applicationsTable
                        + " a join profiles p on p.user_id = a.candidate_id"
                        + " where a." + targetColumn + " = :targetId and a.status != 'WITHDRAWN'"
                        + " order by p.reputation_score desc, p.total_exp desc, a.applied_at asc",
                Map.of("targetId", targetId));

        int myRank = -1;
        for (int i = 0; i < candidates.size(); i++) {
            UUID cId = (UUID) candidates.get(i).get("candidate_id");
            if (userId.equals(cId)) {
                myRank = i + 1;
                break;
            }
        }

        // "percentile" = the share of the pool this candidate outranks (higher is better).
        // e.g. rank 1 of 3 → beats 2 of 3 → 67%.
        int percentile = 0;
        if (myRank != -1 && totalApplicants > 0) {
            int beaten = totalApplicants - myRank;
            percentile = (int) Math.round((double) beaten * 100.0 / totalApplicants);
        }

        return Map.of(
            "unlocked", true,
            "totalApplicants", totalApplicants,
            "averageRs", avgRs,
            "myRank", myRank,
            "percentile", percentile
        );
    }

    /** Request express verification for a pending experience */
    @Transactional
    public Map<String, Object> requestExpressVerification(UUID userId, UUID experienceId) {
        // 1. Fetch experience details
        Map<String, Object> exp;
        try {
            exp = jdbcTemplate.queryForMap("""
                    select id, project_name, profile_id, verification_status, express_verification
                    from experiences
                    where id = :expId and deleted_at is null
                    """, Map.of("expId", experienceId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Không tìm thấy minh chứng kinh nghiệm.");
        }

        // Check ownership
        UUID profileId = (UUID) exp.get("profile_id");
        UUID profileOwnerId;
        try {
            profileOwnerId = jdbcTemplate.queryForObject("select user_id from profiles where id = :profileId",
                    Map.of("profileId", profileId), UUID.class);
        } catch (Exception e) {
            throw new AppException(HttpStatus.FORBIDDEN, "Hồ sơ liên kết không hợp lệ.");
        }

        if (!userId.equals(profileOwnerId)) {
            throw new AppException(HttpStatus.FORBIDDEN, "Bạn không có quyền thực hiện hành động này.");
        }

        // Check verification status
        String status = (String) exp.get("verification_status");
        if (!"PENDING".equalsIgnoreCase(status)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Chỉ có thể đăng ký duyệt nhanh cho minh chứng ở trạng thái Chờ duyệt.");
        }

        Boolean isExpress = (Boolean) exp.get("express_verification");
        if (Boolean.TRUE.equals(isExpress)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Minh chứng này đã được đăng ký duyệt nhanh.");
        }

        // 2. Lock wallet
        Map<String, Object> wallet = lockWalletOrThrow(userId);
        int balance = ((Number) wallet.get("np_balance")).intValue();
        int price = configService.getInt("express_verification_price_np", 25000);

        if (balance < price) {
            throw new AppException(HttpStatus.PAYMENT_REQUIRED,
                    String.format("Số dư NP không đủ. Cần %,d NP, hiện tại %,d NP.", price, balance),
                    "INSUFFICIENT_NP");
        }

        // 3. Deduct NP & write log
        int newBalance = balance - price;
        jdbcTemplate.update("update wallets set np_balance = :balance, updated_at = now() where id = :walletId",
                Map.of("balance", newBalance, "walletId", wallet.get("id")));

        jdbcTemplate.update("""
                insert into wallet_transactions (wallet_id, amount_np, balance_after_np, transaction_type, reason, source_type, source_id, idempotency_key)
                values (:walletId, :amount, :balanceAfter, 'SPEND', :reason, 'experience', :sourceId, :ikey)
                """, new MapSqlParameterSource()
                .addValue("walletId", wallet.get("id"))
                .addValue("amount", -price)
                .addValue("balanceAfter", newBalance)
                .addValue("reason", String.format("Xác thực nhanh (Express Verification): %s", exp.get("project_name")))
                .addValue("sourceId", experienceId)
                .addValue("ikey", "express_" + experienceId));

        // 4. Update experience
        jdbcTemplate.update("update experiences set express_verification = true, updated_at = now() where id = :expId",
                Map.of("expId", experienceId));

        // Alert the admin team so they can proactively contact the Authority Node (24h SLA promise).
        notificationService.notifyAdmins("EXPRESS_REQUESTED",
                "⚡ Yêu cầu Xác thực nhanh (24h)",
                "Một ứng viên vừa đăng ký Xác thực nhanh cho minh chứng \"" + exp.get("project_name")
                        + "\". Vui lòng ưu tiên liên hệ Authority Node và xử lý trong 24 giờ.",
                "/admin/verification-queue");

        log.info("[PremiumService] User {} registered express verification for experience {}", userId, experienceId);
        return Map.of(
                "expressVerification", true,
                "npBalance", newBalance
        );
    }

    /** Unlock profile customization themes trọn đời */
    @Transactional
    public Map<String, Object> unlockTheme(UUID userId) {
        // Check if unlocked
        Boolean isUnlocked = null;
        try {
            isUnlocked = jdbcTemplate.queryForObject("select theme_unlocked from profiles where user_id = :userId",
                    Map.of("userId", userId), Boolean.class);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Hồ sơ của bạn chưa được khởi tạo.");
        }

        if (Boolean.TRUE.equals(isUnlocked)) {
            return Map.of("themeUnlocked", true);
        }

        // Lock wallet
        Map<String, Object> wallet = lockWalletOrThrow(userId);
        int balance = ((Number) wallet.get("np_balance")).intValue();
        int price = configService.getInt("profile_theme_price_np", 50000);

        if (balance < price) {
            throw new AppException(HttpStatus.PAYMENT_REQUIRED,
                    String.format("Số dư NP không đủ. Cần %,d NP, hiện tại %,d NP.", price, balance),
                    "INSUFFICIENT_NP");
        }

        // Deduct NP & write log
        int newBalance = balance - price;
        jdbcTemplate.update("update wallets set np_balance = :balance, updated_at = now() where id = :walletId",
                Map.of("balance", newBalance, "walletId", wallet.get("id")));

        jdbcTemplate.update("""
                insert into wallet_transactions (wallet_id, amount_np, balance_after_np, transaction_type, reason, source_type, idempotency_key)
                values (:walletId, :amount, :balanceAfter, 'SPEND', 'Mở khóa nâng cấp Visual Theme trọn đời', 'profile', :ikey)
                """, new MapSqlParameterSource()
                .addValue("walletId", wallet.get("id"))
                .addValue("amount", -price)
                .addValue("balanceAfter", newBalance)
                .addValue("ikey", "theme_unlock_" + userId));

        // Update profile
        jdbcTemplate.update("update profiles set theme_unlocked = true, updated_at = now() where user_id = :userId",
                Map.of("userId", userId));

        log.info("[PremiumService] User {} unlocked profile visual themes", userId);
        return Map.of(
                "themeUnlocked", true,
                "npBalance", newBalance
        );
    }

    /** Select a theme for candidate profile */
    @Transactional
    public Map<String, Object> selectTheme(UUID userId, String themeCode) {
        // Validate inputs
        List<String> validThemes = List.of("DEFAULT", "DARK_GOLD", "CYBERPUNK", "EMERALD_CLASSIC");
        String code = themeCode != null ? themeCode.trim().toUpperCase() : "DEFAULT";
        if (!validThemes.contains(code)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Theme không hợp lệ.");
        }

        // Fetch unlock state
        Map<String, Object> profile;
        try {
            profile = jdbcTemplate.queryForMap("select theme_unlocked, selected_theme from profiles where user_id = :userId",
                    Map.of("userId", userId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Hồ sơ của bạn chưa được khởi tạo.");
        }

        Boolean isUnlocked = (Boolean) profile.get("theme_unlocked");
        if (!"DEFAULT".equals(code) && !Boolean.TRUE.equals(isUnlocked)) {
            throw new AppException(HttpStatus.FORBIDDEN,
                    "Bạn cần mở khóa tính năng Visual Upgrade để chọn theme chuyên nghiệp.",
                    "THEME_LOCKED");
        }

        jdbcTemplate.update("update profiles set selected_theme = :theme, updated_at = now() where user_id = :userId",
                Map.of("theme", code, "userId", userId));

        log.info("[PremiumService] User {} selected theme {}", userId, code);
        return Map.of(
                "selectedTheme", code
        );
    }

    /** Subscribe to personalized quest/job match alerts */
    @Transactional
    public Map<String, Object> subscribeJobMatchAlert(UUID userId) {
        // Lock wallet
        Map<String, Object> wallet = lockWalletOrThrow(userId);
        int balance = ((Number) wallet.get("np_balance")).intValue();
        int price = configService.getInt("job_match_alert_price_np", 19000);

        if (balance < price) {
            throw new AppException(HttpStatus.PAYMENT_REQUIRED,
                    String.format("Số dư NP không đủ. Cần %,d NP, hiện tại %,d NP.", price, balance),
                    "INSUFFICIENT_NP");
        }

        // Check for active subscription
        OffsetDateTime now = OffsetDateTime.now(VN);
        OffsetDateTime currentExpiry = null;
        try {
            Object raw = jdbcTemplate.queryForObject(
                    "select expires_at from subscriptions where user_id = :userId and plan_code = 'job_match_alert_monthly' and status = 'ACTIVE' and expires_at > now() order by expires_at desc limit 1",
                    Map.of("userId", userId), Object.class);
            currentExpiry = parseOffsetDateTime(raw);
        } catch (Exception ignored) {}

        OffsetDateTime newExpiry = (currentExpiry != null ? currentExpiry : now).plusDays(30);

        // Deduct NP & write log
        int newBalance = balance - price;
        jdbcTemplate.update("update wallets set np_balance = :balance, updated_at = now() where id = :walletId",
                Map.of("balance", newBalance, "walletId", wallet.get("id")));

        jdbcTemplate.update("""
                insert into wallet_transactions (wallet_id, amount_np, balance_after_np, transaction_type, reason, source_type, idempotency_key)
                values (:walletId, :amount, :balanceAfter, 'SPEND', 'Đăng ký nhận thông báo việc làm (Job Match Alert)', 'subscription', :ikey)
                """, new MapSqlParameterSource()
                .addValue("walletId", wallet.get("id"))
                .addValue("amount", -price)
                .addValue("balanceAfter", newBalance)
                .addValue("ikey", "match_alert_" + userId + "_" + System.currentTimeMillis()));

        // Insert subscription row
        jdbcTemplate.update("""
                insert into subscriptions (user_id, plan_code, price_np, status, starts_at, expires_at)
                values (:userId, 'job_match_alert_monthly', :price, 'ACTIVE', now(), :expiry)
                on conflict (user_id, plan_code) where status = 'ACTIVE'
                do update set expires_at = excluded.expires_at, updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("price", price)
                .addValue("expiry", newExpiry));

        log.info("[PremiumService] User {} subscribed to Job Match Alert → expires {}", userId, newExpiry);
        return Map.of(
                "npBalance", newBalance,
                "expiresAt", newExpiry.toString()
        );
    }

    /** Personalized Jobs and Quests recommendations (paid feature — requires active subscription/Premium) */
    public Map<String, Object> getPersonalizedRecommendations(UUID userId) {
        // Gate: only active Job Match Alert subscribers or Premium users may access AI recommendations.
        if (!hasJobMatchAlertAccess(userId)) {
            throw new AppException(HttpStatus.PAYMENT_REQUIRED,
                    "Tính năng gợi ý cá nhân hóa yêu cầu gói Job Match Alert. Đăng ký để mở khóa.",
                    "MATCH_ALERT_REQUIRED");
        }

        // Fetch candidate profile RS
        Map<String, Object> profile;
        try {
            profile = jdbcTemplate.queryForMap("select id, reputation_score from profiles where user_id = :userId",
                    Map.of("userId", userId));
        } catch (Exception e) {
            throw new ResourceNotFoundException("Hồ sơ của bạn chưa được khởi tạo.");
        }

        UUID profileId = (UUID) profile.get("id");
        int rs = ((Number) profile.get("reputation_score")).intValue();

        // 1. Fetch top matching jobs (by matching verified skills count)
        List<Map<String, Object>> recommendedJobs = jdbcTemplate.queryForList("""
                select j.id,
                       j.company_id as "companyId",
                       j.title,
                       j.job_type as "jobType",
                       j.category,
                       j.compensation,
                       j.min_req_rs as "minReqRs",
                       j.location,
                       j.is_remote as "isRemote",
                       j.deadline_at as "deadlineAt",
                       j.requires_premium as "requiresPremium",
                       c.name as "companyName",
                       c.logo_url as "companyLogo",
                       (select count(*) from job_skills js
                        join profile_skills ps on ps.skill_id = js.skill_id
                        where js.job_id = j.id and ps.profile_id = :profileId) as match_count
                from jobs j
                join companies c on j.company_id = c.id
                where j.status = 'OPEN'
                  and (j.deadline_at is null or j.deadline_at > now())
                  and j.min_req_rs <= :rs
                order by match_count desc, j.created_at desc
                limit 6
                """, Map.of("profileId", profileId, "rs", rs));

        // 2. Fetch top matching quests (by matching verified skills count)
        List<Map<String, Object>> recommendedQuests = jdbcTemplate.queryForList("""
                select q.id,
                       q.company_id as "companyId",
                       q.title,
                       q.category,
                       q.min_req_rs as "minReqRs",
                       q.exp_reward as "expReward",
                       q.np_reward as "npReward",
                       c.name as "companyName",
                       c.logo_url as "companyLogo",
                       (select count(*) from quest_skills qs
                        join profile_skills ps on ps.skill_id = qs.skill_id
                        where qs.quest_id = q.id and ps.profile_id = :profileId) as match_count
                from quests q
                join companies c on q.company_id = c.id
                where q.status = 'OPEN'
                  and (q.ends_at is null or q.ends_at > now())
                  and q.min_req_rs <= :rs
                order by match_count desc, q.starts_at desc
                limit 6
                """, Map.of("profileId", profileId, "rs", rs));

        return Map.of(
                "jobs", recommendedJobs,
                "quests", recommendedQuests
        );
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private String normalizeApplicationType(String applicationType) {
        String type = applicationType == null ? "JOB" : applicationType.trim().toUpperCase();
        if (!List.of("JOB", "QUEST").contains(type)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Loại đơn ứng tuyển không hợp lệ.");
        }
        return type;
    }

    private void assertCandidateApplied(UUID userId, UUID targetId, String applicationType) {
        boolean isQuest = "QUEST".equals(applicationType);
        String table = isQuest ? "quest_applications" : "applications";
        String targetColumn = isQuest ? "quest_id" : "job_id";
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from " + table
                        + " where candidate_id = :userId and " + targetColumn + " = :targetId",
                Map.of("userId", userId, "targetId", targetId),
                Integer.class);
        if (count == null || count == 0) {
            throw new AppException(HttpStatus.FORBIDDEN,
                    "Bạn chỉ có thể xem Insight cho đơn mà mình đã ứng tuyển.");
        }
    }

    private boolean checkInsightUnlocked(UUID userId, UUID targetId, String applicationType) {
        boolean isQuest = "QUEST".equals(applicationType);
        String table = isQuest
                ? "quest_application_insights_unlocks"
                : "application_insights_unlocks";
        String targetColumn = isQuest ? "quest_id" : "job_id";
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from " + table
                        + " where user_id = :userId and " + targetColumn + " = :targetId",
                Map.of("userId", userId, "targetId", targetId),
                Integer.class);
        return count != null && count > 0;
    }

    /** True if the user has an active Job Match Alert subscription or active Premium. */
    private boolean hasJobMatchAlertAccess(UUID userId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from app_users u
                where u.id = :userId
                  and ( u.premium_until > now()
                        or exists (select 1 from subscriptions s
                                   where s.user_id = u.id and s.plan_code = 'job_match_alert_monthly'
                                     and s.status = 'ACTIVE' and s.expires_at > now()) )
                """, Map.of("userId", userId), Integer.class);
        return count != null && count > 0;
    }

    private Map<String, Object> lockWalletOrThrow(UUID userId) {
        try {
            return jdbcTemplate.queryForMap(
                    "select id, np_balance, locked_np_balance from wallets where user_id = :userId for update",
                    Map.of("userId", userId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Ví NP của ứng viên chưa được khởi tạo.");
        }
    }

    private OffsetDateTime parseOffsetDateTime(Object raw) {
        if (raw == null) return null;
        if (raw instanceof OffsetDateTime odt) {
            return odt;
        }
        if (raw instanceof java.sql.Timestamp ts) {
            return OffsetDateTime.ofInstant(ts.toInstant(), VN);
        }
        if (raw instanceof java.time.LocalDateTime ldt) {
            return ldt.atZone(VN).toOffsetDateTime();
        }
        try {
            return OffsetDateTime.parse(raw.toString());
        } catch (Exception e) {
            log.warn("Failed to parse date string {}: {}", raw, e.getMessage());
            return null;
        }
    }
}
