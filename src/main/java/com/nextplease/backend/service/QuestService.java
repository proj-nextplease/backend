package com.nextplease.backend.service;

import com.nextplease.backend.exception.AppException;
import com.nextplease.backend.exception.ResourceNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuestService {

    private static final Logger log = LoggerFactory.getLogger(QuestService.class);

    // EXP rewards by quest category
    private static final Map<String, Integer> EXP_BY_CATEGORY = Map.of(
            "SMALL_EVENT",       100,
            "SCHOOL_CAMPAIGN",   300,
            "COMPANY_PROJECT",   500,
            "SHORT_INTERNSHIP",  500,
            "FREELANCE_GIG",     300
    );

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ExpService expService;
    private final ReputationService reputationService;
    private final CompanyAccessService companyAccessService;

    public QuestService(NamedParameterJdbcTemplate jdbcTemplate,
                        ExpService expService,
                        ReputationService reputationService,
                        CompanyAccessService companyAccessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.expService = expService;
        this.reputationService = reputationService;
        this.companyAccessService = companyAccessService;
    }

    // ── Candidate: Search / Browse ────────────────────────────────────────────

    public List<Map<String, Object>> searchQuests(String keyword, String category, int candidateRs) {
        String searchPattern = keyword != null && !keyword.isBlank()
                ? "%" + keyword.trim().toLowerCase() + "%" : null;
        String sql = """
                select
                    q.id,
                    q.title,
                    q.description,
                    q.category,
                    q.exp_reward   as "expReward",
                    q.np_reward    as "npReward",
                    q.min_req_rs   as "minReqRs",
                    q.capacity,
                    q.starts_at    as "startsAt",
                    q.ends_at      as "endsAt",
                    q.status,
                    c.id           as "companyId",
                    c.name         as "companyName",
                    c.logo_url     as "companyLogo",
                    c.company_type as "companyType",
                    (select count(*) from quest_applications qa
                     where qa.quest_id = q.id
                       and qa.status not in ('WITHDRAWN', 'REJECTED')) as "applicantCount"
                from quests q
                join companies c on c.id = q.company_id
                where q.status = 'OPEN'
                  and q.deleted_at is null
                  and (q.ends_at is null or q.ends_at > now())
                  and (:category::text is null or q.category = :category)
                  and (:keyword::text is null or lower(q.title) like :keyword or lower(q.description) like :keyword)
                order by q.created_at desc
                limit 50
                """;
        return jdbcTemplate.queryForList(sql, new MapSqlParameterSource()
                .addValue("category", category)
                .addValue("keyword", searchPattern));
    }

    // ── Candidate: Apply ──────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> applyToQuest(UUID userId, UUID questId, String coverNote) {
        // 1. Fetch quest
        Map<String, Object> quest = fetchQuestOrThrow(questId);

        String status = (String) quest.get("status");
        if (!"OPEN".equals(status)) {
            throw new AppException(HttpStatus.CONFLICT,
                    "Quest này không còn nhận đơn (trạng thái: " + status + ").");
        }

        // 1b. Deadline check
        Object endsAtRaw = quest.get("ends_at");
        if (endsAtRaw instanceof java.sql.Timestamp endsTs) {
            if (endsTs.toInstant().isBefore(java.time.Instant.now())) {
                throw new AppException(HttpStatus.CONFLICT,
                        "Quest này đã hết thời hạn tham gia.");
            }
        }

        // 2. Capacity check
        Integer capacity = quest.get("capacity") != null
                ? ((Number) quest.get("capacity")).intValue() : null;
        if (capacity != null && capacity > 0) {
            Integer accepted = jdbcTemplate.queryForObject("""
                    select count(*) from quest_applications
                    where quest_id = :questId and status = 'ACCEPTED'
                    """, Map.of("questId", questId), Integer.class);
            if (accepted != null && accepted >= capacity) {
                throw new AppException(HttpStatus.CONFLICT,
                        "Quest này đã đủ người tham gia.");
            }
        }

        // 3. RS gate
        Map<String, Object> candidate = fetchCandidateOrThrow(userId);
        int myRs = ((Number) candidate.get("reputation_score")).intValue();
        int minRs = ((Number) quest.get("min_req_rs")).intValue();
        if (myRs < minRs) {
            throw new AppException(HttpStatus.FORBIDDEN,
                    String.format("Điểm RS của bạn chưa đủ. Cần %d RS, bạn có %d RS.", minRs, myRs),
                    "RS_TOO_LOW");
        }

        // 4. Insert
        UUID applicationId;
        try {
            applicationId = jdbcTemplate.queryForObject("""
                    insert into quest_applications (quest_id, candidate_id, status, cover_note)
                    values (:questId, :userId, 'SUBMITTED', :coverNote)
                    returning id
                    """, new MapSqlParameterSource()
                    .addValue("questId", questId)
                    .addValue("userId", userId)
                    .addValue("coverNote", coverNote != null ? coverNote : ""),
                    UUID.class);
        } catch (DuplicateKeyException e) {
            throw new AppException(HttpStatus.CONFLICT,
                    "Bạn đã ứng tuyển Quest này rồi.", "ALREADY_APPLIED");
        }

        log.info("[QuestService] User {} applied to quest {} → qa {}", userId, questId, applicationId);
        return Map.of("applicationId", applicationId, "questId", questId, "status", "SUBMITTED");
    }

    // ── Candidate: My Quest Applications ─────────────────────────────────────

    public List<Map<String, Object>> getMyQuestApplications(UUID userId) {
        return jdbcTemplate.queryForList("""
                select
                    qa.id,
                    qa.status,
                    qa.cover_note,
                    qa.reject_reason as "rejectReason",
                    qa.applied_at as "appliedAt",
                    qa.updated_at as "updatedAt",
                    q.id          as "questId",
                    q.title       as "questTitle",
                    q.category,
                    q.exp_reward  as "expReward",
                    q.np_reward   as "npReward",
                    q.ends_at     as "endsAt",
                    c.name        as "companyName",
                    c.logo_url    as "companyLogo",
                    r.score       as "ratingScore",
                    r.comment     as "ratingComment",
                    r.created_at  as "ratingAt"
                from quest_applications qa
                join quests q on q.id = qa.quest_id
                join companies c on c.id = q.company_id
                left join ratings r on r.quest_application_id = qa.id
                where qa.candidate_id = :userId
                order by qa.applied_at desc
                """, Map.of("userId", userId));
    }

    /** Candidate withdraws their own quest application. */
    @org.springframework.transaction.annotation.Transactional
    public void withdrawQuestApplication(UUID userId, UUID applicationId) {
        Map<String, Object> qa;
        try {
            qa = jdbcTemplate.queryForMap("""
                    select id, status from quest_applications
                    where id = :id and candidate_id = :userId
                    """, Map.of("id", applicationId, "userId", userId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new com.nextplease.backend.exception.AppException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy đơn Quest.");
        }
        String current = (String) qa.get("status");
        if (java.util.List.of("WITHDRAWN", "ACCEPTED", "COMPLETED", "REJECTED").contains(current)) {
            throw new com.nextplease.backend.exception.AppException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Không thể rút đơn khi trạng thái là " + current + ".");
        }
        jdbcTemplate.update("""
                update quest_applications
                set status = 'WITHDRAWN', updated_at = now()
                where id = :id
                """, Map.of("id", applicationId));
    }

    // ── Organizer: Create Quest ───────────────────────────────────────────────

    @Transactional
    public Map<String, Object> createQuest(UUID organizerUserId, Map<String, Object> request) {
        // Verify organizer owns a company
        UUID companyId = (UUID) companyAccessService.resolveApprovedCompanyForUser(organizerUserId).get("id");
        // Only OWNER/MANAGER may create postings (MEMBER is view + review only)
        companyAccessService.assertCanManagePostings(organizerUserId, companyId);

        String category = (String) request.get("category");
        int expReward = EXP_BY_CATEGORY.getOrDefault(category, 100);
        int npReward = request.containsKey("npReward")
                ? ((Number) request.get("npReward")).intValue() : 0;
        int minReqRs = request.containsKey("minReqRs")
                ? ((Number) request.get("minReqRs")).intValue() : 0;
        Integer capacity = request.containsKey("capacity") && request.get("capacity") != null
                ? ((Number) request.get("capacity")).intValue() : null;

        UUID questId = jdbcTemplate.queryForObject("""
                insert into quests
                    (company_id, created_by, title, description, category, exp_reward, np_reward,
                     min_req_rs, capacity, starts_at, ends_at, status)
                values
                    (:companyId, :createdBy, :title, :description, :category, :expReward, :npReward,
                     :minReqRs, :capacity, :startsAt::timestamptz, :endsAt::timestamptz, 'PENDING')
                returning id
                """, new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("createdBy", organizerUserId)
                .addValue("title", request.get("title"))
                .addValue("description", request.get("description"))
                .addValue("category", category)
                .addValue("expReward", expReward)
                .addValue("npReward", npReward)
                .addValue("minReqRs", minReqRs)
                .addValue("capacity", capacity)
                .addValue("startsAt", request.get("startsAt"))
                .addValue("endsAt", request.get("endsAt")),
                UUID.class);

        log.info("[QuestService] Organizer {} created quest {} ({})", organizerUserId, questId, category);
        return Map.of("questId", questId, "status", "PENDING", "expReward", expReward);
    }

    // ── Organizer: List Own Quests ────────────────────────────────────────────

    public List<Map<String, Object>> getOrganizerQuests(UUID organizerUserId) {
        return jdbcTemplate.queryForList("""
                select
                    q.id,
                    q.title,
                    q.category,
                    q.status,
                    q.rejection_reason as "rejectionReason",
                    q.exp_reward  as "expReward",
                    q.np_reward   as "npReward",
                    q.min_req_rs  as "minReqRs",
                    q.capacity,
                    q.starts_at   as "startsAt",
                    q.ends_at     as "endsAt",
                    q.created_at  as "createdAt",
                    (select count(*) from quest_applications qa where qa.quest_id = q.id
                     and qa.status not in ('WITHDRAWN','REJECTED')) as "applicantCount"
                from quests q
                join companies c on c.id = q.company_id
                where exists (select 1 from authority_nodes an
                              where an.company_id = c.id and an.user_id = :userId
                                and an.status = 'ACTIVE' and an.deleted_at is null)
                  and q.deleted_at is null
                order by q.created_at desc
                """, Map.of("userId", organizerUserId));
    }

    // ── Organizer: Get Applicants for a Quest ─────────────────────────────────

    public List<Map<String, Object>> getQuestApplicants(UUID questId, UUID organizerUserId) {
        verifyQuestOwnership(questId, organizerUserId);
        return jdbcTemplate.queryForList("""
                select
                    qa.id,
                    qa.status,
                    qa.cover_note,
                    qa.applied_at as "appliedAt",
                    u.id            as "candidateId",
                    u.display_name  as "candidateName",
                    u.email         as "candidateEmail",
                    p.reputation_score,
                    p.current_level,
                    p.total_exp,
                    p.headline,
                    p.major,
                    p.avatar_url,
                    s.name          as "school"
                from quest_applications qa
                join app_users u on u.id = qa.candidate_id
                join profiles p on p.user_id = u.id
                left join schools s on s.id = p.school_id
                where qa.quest_id = :questId
                order by qa.applied_at asc
                """, Map.of("questId", questId));
    }

    // ── Organizer: Update Application Status ─────────────────────────────────

    @Transactional
    public void updateQuestApplicationStatus(UUID applicationId, UUID organizerUserId,
                                              String newStatus, String rejectReason) {
        List<String> allowed = List.of("ACCEPTED", "REJECTED", "COMPLETED");
        if (!allowed.contains(newStatus)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Trạng thái không hợp lệ: " + newStatus);
        }

        // Verify ownership via quest → company
        Map<String, Object> qaInfo;
        try {
            qaInfo = jdbcTemplate.queryForMap("""
                    select qa.id, qa.candidate_id, q.id as quest_id, q.category,
                           q.exp_reward, q.np_reward
                    from quest_applications qa
                    join quests q on q.id = qa.quest_id
                    join companies c on c.id = q.company_id
                    where qa.id = :appId
                      and exists (select 1 from authority_nodes an
                                  where an.company_id = c.id and an.user_id = :ownerId
                                    and an.status = 'ACTIVE' and an.deleted_at is null)
                    """, Map.of("appId", applicationId, "ownerId", organizerUserId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new AppException(HttpStatus.FORBIDDEN, "Không có quyền cập nhật đơn này.");
        }

        jdbcTemplate.update("""
                update quest_applications
                set status        = :status,
                    reject_reason = :rejectReason,
                    updated_at    = now()
                where id = :id
                """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", applicationId)
                .addValue("status", newStatus)
                .addValue("rejectReason", "REJECTED".equals(newStatus) ? rejectReason : null));

        // If completing, award EXP and NP
        if ("COMPLETED".equals(newStatus)) {
            UUID candidateUserId = (UUID) qaInfo.get("candidate_id");
            int expReward = ((Number) qaInfo.get("exp_reward")).intValue();
            int npReward = qaInfo.get("np_reward") != null
                    ? ((Number) qaInfo.get("np_reward")).intValue() : 0;

            // Award EXP
            UUID profileId = getProfileId(candidateUserId);
            String category = (String) qaInfo.get("category");
            expService.addExp(profileId, expReward,
                    category + "_COMPLETED", "quest_application", applicationId);

            // Award NP if any
            if (npReward > 0) {
                awardNp(candidateUserId, npReward, applicationId, qaInfo.get("quest_id").toString());
            }

            // +5 RS for completing a quest
            reputationService.addReputation(profileId, 5,
                    "QUEST_COMPLETED", "quest_application", applicationId);

            log.info("[QuestService] Quest application {} completed → +{} EXP, +{} NP, +5 RS to user {}",
                    applicationId, expReward, npReward, candidateUserId);
        }

        log.info("[QuestService] Organizer {} updated qa {} → {}", organizerUserId, applicationId, newStatus);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private Map<String, Object> fetchQuestOrThrow(UUID questId) {
        try {
            return jdbcTemplate.queryForMap("""
                    select id, title, status, category, min_req_rs, capacity, exp_reward, np_reward, company_id, ends_at
                    from quests where id = :id and deleted_at is null
                    """, Map.of("id", questId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Không tìm thấy Quest: " + questId);
        }
    }

    private Map<String, Object> fetchCandidateOrThrow(UUID userId) {
        try {
            return jdbcTemplate.queryForMap("""
                    select u.id, p.reputation_score, p.total_exp, p.current_level
                    from app_users u join profiles p on p.user_id = u.id
                    where u.id = :userId
                    """, Map.of("userId", userId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Không tìm thấy hồ sơ ứng viên.");
        }
    }

    private void verifyQuestOwnership(UUID questId, UUID organizerUserId) {
        try {
            jdbcTemplate.queryForObject("""
                    select q.id from quests q
                    join companies c on c.id = q.company_id
                    where q.id = :questId
                      and exists (select 1 from authority_nodes an
                                  where an.company_id = c.id and an.user_id = :ownerId
                                    and an.status = 'ACTIVE' and an.deleted_at is null)
                    """, Map.of("questId", questId, "ownerId", organizerUserId), UUID.class);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new AppException(HttpStatus.FORBIDDEN,
                    "Quest không tồn tại hoặc bạn không có quyền xem.");
        }
    }

    // ── Organizer: Get Single Quest ───────────────────────────────────────────

    public Map<String, Object> getOrganizerQuestById(UUID userId, UUID questId) {
        verifyQuestOwnership(questId, userId);
        try {
            return jdbcTemplate.queryForMap("""
                    select id, title, description, category, status, exp_reward as "expReward",
                           min_req_rs as "minReqRs", capacity, ends_at as "endsAt",
                           rejection_reason as "rejectionReason"
                    from quests
                    where id = :questId and deleted_at is null
                    """, Map.of("questId", questId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Không tìm thấy Quest này.");
        }
    }

    // ── Organizer: Update Quest ───────────────────────────────────────────────

    @Transactional
    public void updateQuest(UUID userId, UUID questId, Map<String, Object> request) {
        verifyQuestOwnership(questId, userId);
        companyAccessService.assertCanManagePostings(userId, getQuestCompanyId(questId));

        // Any status can be edited; always resets to PENDING for Admin re-review
        jdbcTemplate.update("""
                update quests
                set title       = coalesce(:title, title),
                    description = coalesce(:description, description),
                    category    = coalesce(:category, category),
                    min_req_rs  = coalesce(:minReqRs, min_req_rs),
                    capacity    = coalesce(:capacity, capacity),
                    ends_at     = coalesce(cast(:endsAt as timestamptz), ends_at),
                    status      = 'PENDING',
                    updated_at  = now()
                where id = :questId
                """, new MapSqlParameterSource()
                .addValue("title",       request.get("title"))
                .addValue("description", request.get("description"))
                .addValue("category",    request.get("category"))
                .addValue("minReqRs",    request.get("minReqRs"))
                .addValue("capacity",    request.get("capacity"))
                .addValue("endsAt",      request.get("endsAt"))
                .addValue("questId",     questId));
    }

    // ── Organizer: Close Quest ────────────────────────────────────────────────

    @Transactional
    public void closeQuest(UUID userId, UUID questId) {
        verifyQuestOwnership(questId, userId);
        UUID companyId = getQuestCompanyId(questId);
        companyAccessService.assertCanManagePostings(userId, companyId);

        int rows = jdbcTemplate.update("""
                update quests set status = 'CLOSED', updated_at = now()
                where id = :questId and status = 'OPEN' and deleted_at is null
                """, Map.of("questId", questId));
        if (rows == 0) {
            throw new AppException(HttpStatus.CONFLICT,
                    "Quest không ở trạng thái OPEN hoặc không tìm thấy.");
        }
    }

    // ── Organizer: Delete Quest ───────────────────────────────────────────────

    @Transactional
    public void deleteQuest(UUID userId, UUID questId) {
        verifyQuestOwnership(questId, userId);
        UUID companyId = getQuestCompanyId(questId);
        companyAccessService.assertCanManagePostings(userId, companyId);

        // Block deleting OPEN quests — must close first
        String status = jdbcTemplate.queryForObject(
                "select status from quests where id = :id and deleted_at is null",
                Map.of("id", questId), String.class);
        if ("OPEN".equals(status)) {
            throw new AppException(HttpStatus.CONFLICT,
                    "Không thể xoá Quest đang OPEN. Hãy đóng Quest trước.");
        }

        int rows = jdbcTemplate.update(
                "update quests set deleted_at = now() where id = :questId",
                Map.of("questId", questId));
        if (rows == 0) throw new ResourceNotFoundException("Quest không tồn tại.");
    }

    private UUID getQuestCompanyId(UUID questId) {
        try {
            return jdbcTemplate.queryForObject(
                    "select company_id from quests where id = :id", Map.of("id", questId), UUID.class);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Quest không tồn tại.");
        }
    }

    private UUID getProfileId(UUID userId) {
        try {
            return jdbcTemplate.queryForObject(
                    "select id from profiles where user_id = :userId",
                    Map.of("userId", userId), UUID.class);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Profile không tồn tại cho user " + userId);
        }
    }

    private void awardNp(UUID userId, int npAmount, UUID questApplicationId, String questId) {
        try {
            Map<String, Object> wallet = jdbcTemplate.queryForMap(
                    "select id, np_balance from wallets where user_id = :userId for update",
                    Map.of("userId", userId));
            int newBalance = ((Number) wallet.get("np_balance")).intValue() + npAmount;
            jdbcTemplate.update(
                    "update wallets set np_balance = :balance, updated_at = now() where id = :walletId",
                    Map.of("walletId", wallet.get("id"), "balance", newBalance));

            jdbcTemplate.update("""
                    insert into wallet_transactions
                        (wallet_id, amount_np, balance_after_np, transaction_type, reason,
                         source_type, source_id, idempotency_key)
                    values
                        (:walletId, :amount, :balanceAfter, 'QUEST_REWARD',
                         :reason, 'quest_application', :sourceId, :ikey)
                    on conflict (idempotency_key) do nothing
                    """, new MapSqlParameterSource()
                    .addValue("walletId", wallet.get("id"))
                    .addValue("amount", npAmount)
                    .addValue("balanceAfter", newBalance)
                    .addValue("reason", String.format("Phần thưởng Quest: +%d NP", npAmount))
                    .addValue("sourceId", questApplicationId)
                    .addValue("ikey", "quest_reward_" + questApplicationId));
        } catch (Exception e) {
            log.error("[QuestService] Failed to award NP to user {}: {}", userId, e.getMessage());
        }
    }
}
