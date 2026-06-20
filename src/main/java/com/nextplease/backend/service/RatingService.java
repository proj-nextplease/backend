package com.nextplease.backend.service;

import com.nextplease.backend.exception.AppException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đánh giá ứng viên (1 chiều: tổ chức → ứng viên), gắn theo từng đơn ứng tuyển.
 *
 * Quy tắc nghiệp vụ (Phương A):
 *  - Chỉ OWNER/MANAGER của tổ chức được chấm.
 *  - Đơn phải ở trạng thái COMPLETED mới được chấm.
 *  - Mỗi đơn chỉ có 1 đánh giá (unique index trong DB lo việc này).
 *  - 5 sao → trao 2000 NP cho ứng viên (idempotent, chỉ trao 1 lần/đơn, không thu hồi).
 *  - Cho sửa trong vòng 3 giờ kể từ lúc tạo.
 */
@Service
public class RatingService {

    private static final Logger log = LoggerFactory.getLogger(RatingService.class);
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int FIVE_STAR_REWARD_NP = 2_000;
    private static final long EDIT_WINDOW_HOURS = 3;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final CompanyAccessService companyAccessService;

    public RatingService(NamedParameterJdbcTemplate jdbcTemplate, CompanyAccessService companyAccessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.companyAccessService = companyAccessService;
    }

    /** Thông tin đơn cần để kiểm tra quyền + trạng thái. */
    private record AppContext(UUID companyId, UUID candidateId, String status) {}

    /**
     * Tạo đánh giá mới cho một đơn (Job hoặc Quest).
     * Đúng một trong hai applicationId / questApplicationId được truyền.
     */
    @Transactional
    public Map<String, Object> createRating(UUID raterUserId, UUID applicationId, UUID questApplicationId,
                                            int score, String comment) {
        validateScore(score);
        boolean isQuest = resolveSource(applicationId, questApplicationId);
        UUID sourceId = isQuest ? questApplicationId : applicationId;

        AppContext ctx = loadContext(isQuest, sourceId);
        companyAccessService.assertCanManagePostings(raterUserId, ctx.companyId());
        if (!"COMPLETED".equals(ctx.status())) {
            throw new AppException(HttpStatus.CONFLICT,
                    "Chỉ có thể đánh giá ứng viên sau khi đơn được đánh dấu Hoàn thành.");
        }

        UUID ratingId;
        try {
            ratingId = jdbcTemplate.queryForObject("""
                    insert into ratings
                        (application_id, quest_application_id, rater_user_id, candidate_user_id, score, comment)
                    values
                        (:appId, :questAppId, :rater, :candidate, :score, :comment)
                    returning id
                    """, new MapSqlParameterSource()
                    .addValue("appId", isQuest ? null : sourceId)
                    .addValue("questAppId", isQuest ? sourceId : null)
                    .addValue("rater", raterUserId)
                    .addValue("candidate", ctx.candidateId())
                    .addValue("score", score)
                    .addValue("comment", comment),
                    UUID.class);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new AppException(HttpStatus.CONFLICT, "Đơn này đã được đánh giá rồi.");
        }

        if (score == 5) {
            grantFiveStarReward(ctx.candidateId(), isQuest, sourceId);
        }

        log.info("[RatingService] User {} rated candidate {} ({} sao) cho {} {}",
                raterUserId, ctx.candidateId(), score, isQuest ? "quest_application" : "application", sourceId);

        Map<String, Object> result = new HashMap<>();
        result.put("ratingId", ratingId);
        result.put("score", score);
        return result;
    }

    /** Sửa đánh giá trong vòng 3 giờ. Nếu nâng lên 5 sao thì trao thưởng (idempotent). */
    @Transactional
    public void updateRating(UUID raterUserId, UUID ratingId, int score, String comment) {
        validateScore(score);

        Map<String, Object> rating;
        try {
            rating = jdbcTemplate.queryForMap("""
                    select id, application_id, quest_application_id, candidate_user_id, created_at
                    from ratings where id = :id and rater_user_id = :rater
                    """, Map.of("id", ratingId, "rater", raterUserId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new AppException(HttpStatus.NOT_FOUND, "Không tìm thấy đánh giá hoặc bạn không có quyền sửa.");
        }

        OffsetDateTime createdAt = toOffset(rating.get("created_at"));
        if (createdAt == null || createdAt.plusHours(EDIT_WINDOW_HOURS).isBefore(OffsetDateTime.now(VN))) {
            throw new AppException(HttpStatus.CONFLICT,
                    "Đã quá thời hạn 3 giờ để sửa đánh giá.");
        }

        jdbcTemplate.update("""
                update ratings set score = :score, comment = :comment where id = :id
                """, Map.of("id", ratingId, "score", score, "comment", comment));

        // Nếu nâng lên 5 sao → trao thưởng nếu chưa từng trao (idempotent). Không bao giờ thu hồi.
        if (score == 5) {
            UUID candidateId = (UUID) rating.get("candidate_user_id");
            boolean isQuest = rating.get("quest_application_id") != null;
            UUID sourceId = isQuest ? (UUID) rating.get("quest_application_id") : (UUID) rating.get("application_id");
            grantFiveStarReward(candidateId, isQuest, sourceId);
        }

        log.info("[RatingService] User {} sửa đánh giá {} → {} sao", raterUserId, ratingId, score);
    }

    /** Trả về đánh giá hiện có của một đơn (hoặc null), kèm cờ canEdit. */
    public Map<String, Object> getRatingForApplication(UUID applicationId, UUID questApplicationId) {
        boolean isQuest = resolveSource(applicationId, questApplicationId);
        UUID sourceId = isQuest ? questApplicationId : applicationId;
        String col = isQuest ? "quest_application_id" : "application_id";

        var rows = jdbcTemplate.queryForList(
                "select id, score, comment, created_at from ratings where " + col + " = :id",
                Map.of("id", sourceId));
        if (rows.isEmpty()) return null;

        Map<String, Object> r = new HashMap<>(rows.get(0));
        OffsetDateTime createdAt = toOffset(r.get("created_at"));
        boolean canEdit = createdAt != null
                && createdAt.plusHours(EDIT_WINDOW_HOURS).isAfter(OffsetDateTime.now(VN));
        r.put("canEdit", canEdit);
        return r;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void grantFiveStarReward(UUID candidateId, boolean isQuest, UUID sourceId) {
        String idemKey = "rating_reward_" + (isQuest ? "quest_" : "job_") + sourceId;

        // Đã trao rồi? (idempotent — chỉ trao 1 lần/đơn)
        Integer existing = jdbcTemplate.queryForObject(
                "select count(*) from wallet_transactions where idempotency_key = :k",
                Map.of("k", idemKey), Integer.class);
        if (existing != null && existing > 0) return;

        // Đảm bảo ví tồn tại
        jdbcTemplate.update("""
                insert into wallets (user_id, np_balance, locked_np_balance)
                values (:userId, 0, 0)
                on conflict (user_id) do nothing
                """, Map.of("userId", candidateId));

        Map<String, Object> wallet = jdbcTemplate.queryForMap(
                "select id, np_balance from wallets where user_id = :userId for update",
                Map.of("userId", candidateId));
        int newBalance = ((Number) wallet.get("np_balance")).intValue() + FIVE_STAR_REWARD_NP;

        jdbcTemplate.update("update wallets set np_balance = :b, updated_at = now() where id = :id",
                Map.of("b", newBalance, "id", wallet.get("id")));

        jdbcTemplate.update("""
                insert into wallet_transactions
                    (wallet_id, amount_np, balance_after_np, transaction_type, reason, source_type, source_id, idempotency_key)
                values
                    (:walletId, :amount, :balanceAfter, 'REWARD', 'Thưởng đánh giá 5 sao', :sourceType, :sourceId, :ikey)
                """, new MapSqlParameterSource()
                .addValue("walletId", wallet.get("id"))
                .addValue("amount", FIVE_STAR_REWARD_NP)
                .addValue("balanceAfter", newBalance)
                .addValue("sourceType", isQuest ? "quest_application" : "application")
                .addValue("sourceId", sourceId)
                .addValue("ikey", idemKey));

        log.info("[RatingService] Trao {} NP (5 sao) cho ứng viên {} từ {}", FIVE_STAR_REWARD_NP, candidateId, idemKey);
    }

    private AppContext loadContext(boolean isQuest, UUID sourceId) {
        try {
            Map<String, Object> row = isQuest
                    ? jdbcTemplate.queryForMap("""
                            select q.company_id, qa.candidate_id, qa.status
                            from quest_applications qa
                            join quests q on q.id = qa.quest_id
                            where qa.id = :id
                            """, Map.of("id", sourceId))
                    : jdbcTemplate.queryForMap("""
                            select j.company_id, a.candidate_id, a.status
                            from applications a
                            join jobs j on j.id = a.job_id
                            where a.id = :id
                            """, Map.of("id", sourceId));
            return new AppContext(
                    (UUID) row.get("company_id"),
                    (UUID) row.get("candidate_id"),
                    (String) row.get("status"));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new AppException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn ứng tuyển.");
        }
    }

    private boolean resolveSource(UUID applicationId, UUID questApplicationId) {
        if ((applicationId == null) == (questApplicationId == null)) {
            throw new AppException(HttpStatus.BAD_REQUEST,
                    "Phải truyền đúng một trong applicationId hoặc questApplicationId.");
        }
        return questApplicationId != null;
    }

    private void validateScore(int score) {
        if (score < 1 || score > 5) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Điểm đánh giá phải từ 1 đến 5 sao.");
        }
    }

    private OffsetDateTime toOffset(Object raw) {
        if (raw instanceof OffsetDateTime odt) return odt;
        if (raw instanceof java.sql.Timestamp ts) return ts.toInstant().atZone(VN).toOffsetDateTime();
        if (raw != null) {
            try { return OffsetDateTime.parse(raw.toString()); } catch (Exception ignored) {}
        }
        return null;
    }
}
