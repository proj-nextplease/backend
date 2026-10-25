package com.nextplease.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextplease.backend.exception.AppException;
import com.nextplease.backend.exception.ResourceNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

    private static final Map<String, Integer> JOB_TYPE_EXP = new HashMap<>();
    static {
        JOB_TYPE_EXP.put("INTERNSHIP", 500);
        JOB_TYPE_EXP.put("MICRO_INTERNSHIP", 500);
        JOB_TYPE_EXP.put("PART_TIME", 300);
        JOB_TYPE_EXP.put("FREELANCE", 300);
        JOB_TYPE_EXP.put("EVENT_STAFF", 200);
    }

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ExpService expService;
    private final ReputationService reputationService;
    private final ConfigService configService;
    private final NotificationService notificationService;

    public ApplicationService(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                              ExpService expService, ReputationService reputationService, ConfigService configService,
                              NotificationService notificationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.expService = expService;
        this.reputationService = reputationService;
        this.configService = configService;
        this.notificationService = notificationService;
    }

    /**
     * Candidate applies to a job.
     * Gates (in order): job must be OPEN → RS check → premium check → no duplicate.
     *
     * Error codes returned via AppException:
     *   RS_TOO_LOW       — RS < job.min_req_rs
     *   PREMIUM_REQUIRED — job.requires_premium = true AND user has no active premium
     *   ALREADY_APPLIED  — duplicate application
     */
    @Transactional
    public Map<String, Object> apply(UUID userId, UUID jobId, String coverNote, Map<String, String> answers) {
        // 1. Fetch job
        Map<String, Object> job = fetchJobOrThrow(jobId);

        String jobStatus = (String) job.get("status");
        if (!"OPEN".equals(jobStatus)) {
            throw new AppException(HttpStatus.CONFLICT,
                    "Cơ hội này hiện không mở nhận đơn (trạng thái: " + jobStatus + ").");
        }

        // 1b. Deadline check
        Object deadlineRaw = job.get("deadline_at");
        if (deadlineRaw instanceof java.sql.Timestamp deadlineTs) {
            if (deadlineTs.toInstant().isBefore(java.time.Instant.now())) {
                throw new AppException(HttpStatus.CONFLICT,
                        "Cơ hội này đã hết hạn nộp đơn.");
            }
        }

        // 2. Fetch candidate profile + user for RS and premium check
        Map<String, Object> candidate = fetchCandidateOrThrow(userId);

        int myRs = ((Number) candidate.get("reputation_score")).intValue();
        int minRs = ((Number) job.get("min_req_rs")).intValue();

        // 3. RS gate
        if (myRs < minRs) {
            throw new AppException(
                    HttpStatus.FORBIDDEN,
                    String.format("Điểm RS của bạn chưa đủ. Cần %d RS, hiện tại bạn có %d RS.", minRs, myRs),
                    "RS_TOO_LOW"
            );
        }

        // 4. (Deprecated) Premium gate removed per new point-based model

        // 5. Snapshot eligibility at time of application
        String snapshot = buildSnapshot(myRs, candidate);

        // 5b. Validate + snapshot custom form answers (denormalized [{label,value}])
        String customAnswersJson = buildCustomAnswers(jobId, answers);

        // 6. Insert application
        UUID applicationId;
        try {
            applicationId = jdbcTemplate.queryForObject("""
                    insert into applications (job_id, candidate_id, status, cover_note, eligibility_snapshot, custom_answers)
                    values (:jobId, :userId, 'SUBMITTED', :coverNote, :snapshot::jsonb, :answers::jsonb)
                    returning id
                    """, new MapSqlParameterSource()
                    .addValue("jobId", jobId)
                    .addValue("userId", userId)
                    .addValue("coverNote", coverNote)
                    .addValue("snapshot", snapshot)
                    .addValue("answers", customAnswersJson),
                    UUID.class);
        } catch (DuplicateKeyException e) {
            throw new AppException(HttpStatus.CONFLICT,
                    "Bạn đã ứng tuyển vị trí này rồi.", "ALREADY_APPLIED");
        }

        recordHistory(applicationId, "SUBMITTED");
        log.info("[ApplicationService] User {} applied to job {} → application {}", userId, jobId, applicationId);

        String jobTitle = (String) job.get("title");
        String companyName = null;
        UUID companyId = null;
        if (job.get("company_id") instanceof UUID cId) {
            companyId = cId;
            try {
                companyName = jdbcTemplate.queryForObject(
                        "select name from companies where id = :cId",
                        Map.of("cId", cId), String.class);
            } catch (Exception ignored) {}
        }
        String companyDisplay = (companyName != null && !companyName.isBlank()) ? companyName : "Nhà tuyển dụng";

        // 1. Gửi thông báo + email xác nhận ứng tuyển thành công cho ỨNG VIÊN
        notificationService.notify(userId, "APPLICATION_STATUS",
                "Ứng tuyển thành công: " + jobTitle,
                "Bạn đã ứng tuyển thành công vào vị trí \"" + jobTitle + "\" tại " + companyDisplay
                        + ". Hồ sơ của bạn đã được chuyển tới nhà tuyển dụng. Bạn có thể theo dõi tiến độ ứng tuyển tại mục Đơn ứng tuyển trên Fonlio.",
                "/candidates/dashboard/applications",
                true);

        // 2. Báo cho CẢ TỔ CHỨC qua thông báo in-app và email
        if (companyId != null) {
            notificationService.notifyCompanyMembers(companyId, userId, "NEW_APPLICATION",
                    "Có ứng viên mới: " + jobTitle,
                    "Một ứng viên vừa ứng tuyển vào vị trí \"" + jobTitle + "\". Hãy vào xem hồ sơ!",
                    "/businesses/dashboard/candidates?posting=" + jobId
                            + "&type=JOB&app=" + applicationId,
                    true);
        }

        return Map.of(
                "applicationId", applicationId,
                "jobId", jobId,
                "status", "SUBMITTED"
        );
    }

    /**
     * Organizer: list all applications for a specific job they own.
     * Verifies that the job belongs to the organizer's company.
     */
    public List<Map<String, Object>> getJobApplications(UUID jobId, UUID organizerUserId) {
        // Verify job ownership
        try {
            jdbcTemplate.queryForObject("""
                    select j.id from jobs j
                    join companies c on c.id = j.company_id
                    where j.id = :jobId
                      and exists (select 1 from authority_nodes an
                                  where an.company_id = c.id and an.user_id = :ownerId
                                    and an.status = 'ACTIVE' and an.deleted_at is null)
                    """, Map.of("jobId", jobId, "ownerId", organizerUserId), UUID.class);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new AppException(HttpStatus.FORBIDDEN, "Tin tuyển dụng không tồn tại hoặc bạn không có quyền xem.");
        }

        return jdbcTemplate.queryForList("""
                select
                    a.id,
                    a.status,
                    a.cover_note,
                    a.applied_at,
                    a.eligibility_snapshot,
                    a.custom_answers::text as custom_answers,
                    a.boosted_until as "boostedUntil",
                    u.id            as candidate_id,
                    u.display_name  as candidate_name,
                    u.email         as candidate_email,
                    -- Huy hiệu Premium cho nhà tuyển dụng thấy. CỐ Ý không đưa
                    -- vào order by: thứ tự đã do boosted_until quyết định, và
                    -- đó là dịch vụ riêng người dùng chủ động mua cho TỪNG đơn.
                    -- Để Premium đổi thứ tự nữa thì thành hai lớp trả tiền
                    -- chồng lên nhau, và một hồ sơ Proof tốt bị đẩy xuống dưới
                    -- chỉ vì người kia trả tiền theo tháng.
                    (u.premium_until > now()) as "isPremium",
                    p.reputation_score,
                    p.total_exp,
                    p.current_level,
                    p.headline,
                    p.major,
                    p.avatar_url,
                    s.name          as school
                from applications a
                join app_users u on u.id = a.candidate_id
                join profiles p on p.user_id = u.id
                left join schools s on s.id = p.school_id
                where a.job_id = :jobId
                order by (case when a.boosted_until > now() then 1 else 0 end) desc, a.applied_at asc
                """, Map.of("jobId", jobId));
    }

    /**
     * Organizer opened an applicant's detail: auto-advance SUBMITTED → VIEWED.
     * Idempotent — only the first view transitions (never overrides a later stage),
     * and the candidate is notified once that their profile was seen.
     */
    @Transactional
    public void markViewed(UUID applicationId, UUID organizerUserId) {
        Map<String, Object> appInfo;
        try {
            appInfo = jdbcTemplate.queryForMap("""
                    select a.id, a.candidate_id, a.status, a.job_id, j.title as job_title
                    from applications a
                    join jobs j on j.id = a.job_id
                    join companies c on c.id = j.company_id
                    where a.id = :appId
                      and exists (select 1 from authority_nodes an
                                  where an.company_id = c.id and an.user_id = :ownerId
                                    and an.status = 'ACTIVE' and an.deleted_at is null)
                    """, Map.of("appId", applicationId, "ownerId", organizerUserId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new AppException(HttpStatus.FORBIDDEN, "Không có quyền xem đơn ứng tuyển này.");
        }

        if (!"SUBMITTED".equals(appInfo.get("status"))) {
            return; // already viewed / further along — nothing to do
        }

        int rows = jdbcTemplate.update("""
                update applications set status = 'VIEWED', updated_at = now()
                where id = :id and status = 'SUBMITTED'
                """, Map.of("id", applicationId));

        if (rows > 0) {
            recordHistory(applicationId, "VIEWED");
            UUID candidateUserId = (UUID) appInfo.get("candidate_id");
            String jobTitle = (String) appInfo.get("job_title");
            notificationService.notify(candidateUserId, "APPLICATION_STATUS",
                    "Hồ sơ của bạn đã được xem: " + jobTitle,
                    "Nhà tuyển dụng vừa mở xem hồ sơ ứng tuyển vị trí \"" + jobTitle + "\" của bạn.",
                    "/candidates/dashboard/applications",
                    true);
            log.info("[ApplicationService] Application {} auto-marked VIEWED by organizer {}", applicationId, organizerUserId);
        }
    }

    /**
     * Organizer: update status of an application (SHORTLISTED, REJECTED, ACCEPTED).
     */
    @Transactional
    public void updateApplicationStatus(UUID applicationId, UUID organizerUserId, String newStatus, String rejectReason) {
        List<String> allowed = List.of("VIEWED", "SHORTLISTED", "OFFERED", "ACCEPTED", "REJECTED", "COMPLETED");
        if (!allowed.contains(newStatus)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Trạng thái không hợp lệ: " + newStatus);
        }

        // Verify ownership via job and fetch candidate + job_type
        Map<String, Object> appInfo;
        try {
            appInfo = jdbcTemplate.queryForMap("""
                    select a.id, a.candidate_id, a.status, a.job_id, j.job_type, j.title as job_title, j.capacity, c.name as company_name from applications a
                    join jobs j on j.id = a.job_id
                    join companies c on c.id = j.company_id
                    where a.id = :appId
                      and exists (select 1 from authority_nodes an
                                  where an.company_id = c.id and an.user_id = :ownerId
                                    and an.status = 'ACTIVE' and an.deleted_at is null)
                    """, Map.of("appId", applicationId, "ownerId", organizerUserId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new AppException(HttpStatus.FORBIDDEN, "Không có quyền cập nhật đơn ứng tuyển này.");
        }

        // Guard: only an ACCEPTED application can be marked COMPLETED
        String currentStatus = (String) appInfo.get("status");
        if ("COMPLETED".equals(newStatus) && !"ACCEPTED".equals(currentStatus)) {
            throw new AppException(HttpStatus.CONFLICT,
                    "Chỉ có thể đánh dấu hoàn thành khi đơn đang ở trạng thái 'Chấp nhận'.");
        }

        // Capacity guard: when accepting a NEW candidate (transitioning into ACCEPTED),
        // block if the job already reached its quota. capacity null/<=0 => unlimited.
        if ("ACCEPTED".equals(newStatus) && !"ACCEPTED".equals(currentStatus)) {
            Object capRaw = appInfo.get("capacity");
            if (capRaw != null) {
                int capacity = ((Number) capRaw).intValue();
                if (capacity > 0) {
                    Integer accepted = jdbcTemplate.queryForObject("""
                            select count(*) from applications
                            where job_id = :jobId and status = 'ACCEPTED'
                            """, Map.of("jobId", appInfo.get("job_id")), Integer.class);
                    if (accepted != null && accepted >= capacity) {
                        throw new AppException(HttpStatus.CONFLICT,
                                String.format("Tin này đã đủ chỉ tiêu (%d/%d). Không thể nhận thêm ứng viên.", accepted, capacity));
                    }
                }
            }
        }

        jdbcTemplate.update("""
                update applications
                set status        = :status,
                    reject_reason = :rejectReason,
                    updated_at    = now()
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("id", applicationId)
                .addValue("status", newStatus)
                .addValue("rejectReason", "REJECTED".equals(newStatus) ? rejectReason : null));

        if (!newStatus.equals(currentStatus)) {
            recordHistory(applicationId, newStatus);
        }

        // Award EXP when organizer marks a job application as COMPLETED
        if ("COMPLETED".equals(newStatus)) {
            UUID candidateUserId = (UUID) appInfo.get("candidate_id");
            String jobType = (String) appInfo.get("job_type");
            int expAmount = JOB_TYPE_EXP.getOrDefault(jobType, 300);
            UUID profileId = jdbcTemplate.queryForObject(
                    "select id from profiles where user_id = :userId",
                    Map.of("userId", candidateUserId), UUID.class);
            expService.addExp(profileId, expAmount,
                    "JOB_COMPLETED",
                    "job_application", applicationId);
            // +RS for completing a job (idempotent; mirrors quest completion)
            int rsReward = configService.getInt("rs_job_completed", 5);
            if (rsReward > 0) {
                reputationService.addReputation(profileId, rsReward,
                        "JOB_COMPLETED", "job_application", applicationId);
            }
            log.info("[ApplicationService] Awarded {} EXP + {} RS to candidate {} for completing job (type={})", expAmount, rsReward, candidateUserId, jobType);
        }

        log.info("[ApplicationService] Organizer {} updated application {} → {}", organizerUserId, applicationId, newStatus);

        // Notify the candidate about the status change.
        UUID candidateUserId = (UUID) appInfo.get("candidate_id");
        String jobTitle = (String) appInfo.get("job_title");
        String companyName = (String) appInfo.get("company_name");
        UUID jobId = (UUID) appInfo.get("job_id");

        String notifTitle;
        String notifBody;
        String notifLink;

        if ("OFFERED".equals(newStatus)) {
            notifTitle = "🎉 Lời mời nhận việc: " + jobTitle;
            notifBody = (companyName != null ? companyName : "Nhà tuyển dụng")
                    + " đã gửi lời mời nhận việc cho vị trí \"" + jobTitle + "\". Hãy vào Fonlio để kiểm tra và xác nhận nhận việc!";
            notifLink = "/candidates/dashboard/applications";
        } else if ("ACCEPTED".equals(newStatus)) {
            notifTitle = "🎉 Đơn ứng tuyển đã được chấp nhận: " + jobTitle;
            notifBody = "Đơn ứng tuyển \"" + jobTitle + "\" của bạn tại "
                    + (companyName != null ? companyName : "doanh nghiệp") + " đã được chấp nhận.";
            notifLink = "/candidates/dashboard/applications";
        } else if ("REJECTED".equals(newStatus)) {
            notifTitle = "Cập nhật đơn ứng tuyển: " + jobTitle;
            notifBody = "Đơn ứng tuyển \"" + jobTitle + "\" của bạn đã bị từ chối"
                    + (rejectReason != null && !rejectReason.isBlank() ? " (Lý do: " + rejectReason + ")" : "");
            notifLink = "/candidates/dashboard/applications";
        } else if ("COMPLETED".equals(newStatus)) {
            notifTitle = "Hoàn thành công việc: " + jobTitle;
            notifBody = "Đơn ứng tuyển \"" + jobTitle + "\" của bạn đã hoàn thành — bạn được cộng điểm!";
            notifLink = "/candidates/dashboard/applications";
        } else if ("SHORTLISTED".equals(newStatus)) {
            notifTitle = "Hồ sơ vào danh sách tiềm năng: " + jobTitle;
            notifBody = "Đơn ứng tuyển \"" + jobTitle + "\" của bạn tại "
                    + (companyName != null ? companyName : "doanh nghiệp") + " đã được đưa vào danh sách tiềm năng (Shortlist).";
            notifLink = "/candidates/dashboard/applications";
        } else if ("VIEWED".equals(newStatus)) {
            notifTitle = "Hồ sơ của bạn đã được xem: " + jobTitle;
            notifBody = "Nhà tuyển dụng tại " + (companyName != null ? companyName : "doanh nghiệp")
                    + " đã xem hồ sơ ứng tuyển \"" + jobTitle + "\" của bạn.";
            notifLink = "/candidates/dashboard/applications";
        } else {
            notifTitle = "Cập nhật đơn ứng tuyển: " + jobTitle;
            notifBody = "Đơn ứng tuyển \"" + jobTitle + "\" của bạn đã được cập nhật sang trạng thái: " + newStatus;
            notifLink = "/candidates/dashboard/applications";
        }

        notificationService.notify(candidateUserId, "APPLICATION_STATUS",
                notifTitle, notifBody, notifLink, true);
    }

    /** Returns all applications for the given candidate, newest first. */
    public List<Map<String, Object>> getMyApplications(UUID userId) {
        return jdbcTemplate.queryForList("""
                select
                    a.id,
                    a.status,
                    a.cover_note,
                    a.custom_answers::text as custom_answers,
                    a.reject_reason,
                    a.applied_at,
                    a.updated_at,
                    a.boosted_until as "boostedUntil",
                    (select coalesce(jsonb_agg(jsonb_build_object('status', h.status, 'at', h.created_at) order by h.created_at), '[]'::jsonb)::text
                     from application_status_history h where h.application_id = a.id) as "statusHistory",
                    j.id            as job_id,
                    j.title         as job_title,
                    j.job_type,
                    j.compensation,
                    j.min_req_rs,
                    j.requires_premium,
                    j.location,
                    j.is_remote,
                    j.deadline_at,
                    c.id            as company_id,
                    c.name          as company_name,
                    c.logo_url      as company_logo,
                    r.score         as rating_score,
                    r.comment       as rating_comment,
                    r.created_at    as rating_at
                from applications a
                join jobs j on j.id = a.job_id
                join companies c on c.id = j.company_id
                left join ratings r on r.application_id = a.id
                where a.candidate_id = :userId
                order by case when a.status = 'OFFERED' then 0 when a.status = 'SHORTLISTED' then 1 else 2 end, a.updated_at desc, a.applied_at desc
                """, Map.of("userId", userId));
    }

    /** Candidate withdraws their own application. Only allowed for non-terminal statuses. */
    @Transactional
    public void withdrawApplication(UUID userId, UUID applicationId) {
        Map<String, Object> app;
        try {
            app = jdbcTemplate.queryForMap("""
                    select id, status from applications
                    where id = :id and candidate_id = :userId
                    """, Map.of("id", applicationId, "userId", userId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new AppException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn ứng tuyển.");
        }
        String current = (String) app.get("status");
        if (List.of("WITHDRAWN", "ACCEPTED", "COMPLETED", "REJECTED").contains(current)) {
            throw new AppException(HttpStatus.CONFLICT,
                    "Không thể rút đơn khi trạng thái là " + current + ".");
        }
        jdbcTemplate.update("""
                update applications
                set status = 'WITHDRAWN', updated_at = now()
                where id = :id
                """, Map.of("id", applicationId));
        recordHistory(applicationId, "WITHDRAWN");
        log.info("[ApplicationService] Candidate {} withdrew application {}", userId, applicationId);
    }

    /**
     * Candidate accepts an employer's job offer.
     * If the job has compensation > 0, deducts paid_job_acceptance_fee_np (19,000 NP).
     * If unpaid/volunteer, deducts 0 NP.
     * Transitions status: OFFERED -> ACCEPTED.
     */
    @Transactional
    public Map<String, Object> acceptOffer(UUID userId, UUID applicationId) {
        Map<String, Object> app;
        try {
            app = jdbcTemplate.queryForMap("""
                    select a.id, a.candidate_id, a.status, a.job_id,
                           j.title as job_title, j.compensation, j.company_id, c.name as company_name
                    from applications a
                    join jobs j on j.id = a.job_id
                    join companies c on c.id = j.company_id
                    where a.id = :appId and a.candidate_id = :userId
                    """, Map.of("appId", applicationId, "userId", userId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new AppException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn ứng tuyển.");
        }

        String currentStatus = (String) app.get("status");
        if (!"OFFERED".equals(currentStatus)) {
            throw new AppException(HttpStatus.CONFLICT,
                    "Chỉ có thể xác nhận nhận việc khi đơn đang ở trạng thái 'Được mời nhận việc' (hiện tại: " + currentStatus + ").");
        }

        Object compRaw = app.get("compensation");
        boolean isPaidJob = false;
        if (compRaw instanceof Number n) {
            isPaidJob = n.doubleValue() > 0;
        }

        int fee = 0;
        int balanceAfter = 0;
        if (isPaidJob) {
            fee = configService.getInt("paid_job_acceptance_fee_np", 19000);
            // Lock candidate wallet
            Map<String, Object> wallet;
            try {
                wallet = jdbcTemplate.queryForMap(
                        "select id, np_balance from wallets where user_id = :userId for update",
                        Map.of("userId", userId));
            } catch (org.springframework.dao.EmptyResultDataAccessException e) {
                throw new AppException(HttpStatus.NOT_FOUND, "Không tìm thấy ví của bạn.");
            }

            int currentBalance = ((Number) wallet.get("np_balance")).intValue();
            if (currentBalance < fee) {
                throw new AppException(HttpStatus.PAYMENT_REQUIRED,
                        String.format("Số dư Điểm không đủ để xác nhận nhận việc. Cần %,d Điểm, hiện tại bạn có %,d Điểm. Vui lòng nạp thêm.", fee, currentBalance),
                        "INSUFFICIENT_NP");
            }

            balanceAfter = currentBalance - fee;
            UUID walletId = (UUID) wallet.get("id");

            jdbcTemplate.update("""
                    update wallets
                    set np_balance = :balanceAfter, updated_at = now()
                    where id = :walletId
                    """, Map.of("balanceAfter", balanceAfter, "walletId", walletId));

            jdbcTemplate.update("""
                    insert into wallet_transactions (
                        wallet_id, amount_np, balance_after_np, transaction_type, reason,
                        source_type, source_id
                    ) values (
                        :walletId, :amount, :balanceAfter, 'JOB_ACCEPTANCE_FEE', :reason,
                        'job_application', :applicationId
                    )
                    """, Map.of(
                    "walletId", walletId,
                    "amount", -fee,
                    "balanceAfter", balanceAfter,
                    "reason", "Phí xác nhận nhận việc có trả lương: " + app.get("job_title"),
                    "applicationId", applicationId
            ));
        }

        jdbcTemplate.update("""
                update applications
                set status = 'ACCEPTED', updated_at = now()
                where id = :id
                """, Map.of("id", applicationId));

        recordHistory(applicationId, "ACCEPTED");

        // Notify employer / organizers
        UUID companyId = (UUID) app.get("company_id");
        List<UUID> employerUserIds = jdbcTemplate.queryForList("""
                select user_id from authority_nodes
                where company_id = :companyId and status = 'ACTIVE' and deleted_at is null
                """, Map.of("companyId", companyId), UUID.class);

        String candidateName = null;
        try {
            candidateName = jdbcTemplate.queryForObject(
                    "select coalesce(display_name, 'Ứng viên') from app_users where id = :userId",
                    Map.of("userId", userId), String.class);
        } catch (Exception ignored) {
            candidateName = "Ứng viên";
        }

        for (UUID employerId : employerUserIds) {
            notificationService.notify(employerId, "APPLICATION_STATUS",
                    "Ứng viên đã nhận việc 🎉",
                    "Ứng viên " + candidateName + " đã đồng ý nhận việc cho vị trí \"" + app.get("job_title") + "\"!",
                    "/business/jobs/" + app.get("job_id"),
                    true);
        }

        log.info("[ApplicationService] Candidate {} accepted offer for app {} (fee={}, paid={})", userId, applicationId, fee, isPaidJob);

        return Map.of(
                "status", "ACCEPTED",
                "feeDeducted", fee,
                "message", "Bạn đã đồng ý nhận việc thành công!"
        );
    }

    /**
     * Candidate declines an employer's job offer.
     * No points deducted.
     * Transitions status: OFFERED -> OFFER_DECLINED.
     */
    @Transactional
    public Map<String, Object> declineOffer(UUID userId, UUID applicationId, String reason) {
        Map<String, Object> app;
        try {
            app = jdbcTemplate.queryForMap("""
                    select a.id, a.candidate_id, a.status, a.job_id,
                           j.title as job_title, j.company_id
                    from applications a
                    join jobs j on j.id = a.job_id
                    where a.id = :appId and a.candidate_id = :userId
                    """, Map.of("appId", applicationId, "userId", userId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new AppException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn ứng tuyển.");
        }

        String currentStatus = (String) app.get("status");
        if (!"OFFERED".equals(currentStatus)) {
            throw new AppException(HttpStatus.CONFLICT,
                    "Chỉ có thể từ chối khi đơn đang ở trạng thái 'Được mời nhận việc'.");
        }

        jdbcTemplate.update("""
                update applications
                set status = 'OFFER_DECLINED',
                    reject_reason = :reason,
                    updated_at = now()
                where id = :id
                """, Map.of("id", applicationId, "reason", reason != null ? reason : "Ứng viên từ chối nhận việc"));

        recordHistory(applicationId, "OFFER_DECLINED");

        // Notify employer
        UUID companyId = (UUID) app.get("company_id");
        List<UUID> employerUserIds = jdbcTemplate.queryForList("""
                select user_id from authority_nodes
                where company_id = :companyId and status = 'ACTIVE' and deleted_at is null
                """, Map.of("companyId", companyId), UUID.class);

        String candidateName = null;
        try {
            candidateName = jdbcTemplate.queryForObject(
                    "select coalesce(display_name, 'Ứng viên') from app_users where id = :userId",
                    Map.of("userId", userId), String.class);
        } catch (Exception ignored) {
            candidateName = "Ứng viên";
        }

        for (UUID employerId : employerUserIds) {
            notificationService.notify(employerId, "APPLICATION_STATUS",
                    "Ứng viên từ chối lời mời",
                    "Ứng viên " + candidateName + " đã từ chối nhận việc cho vị trí \"" + app.get("job_title") + "\"."
                            + (reason != null && !reason.isBlank() ? " (Lý do: " + reason + ")" : ""),
                    "/business/jobs/" + app.get("job_id"),
                    false);
        }

        log.info("[ApplicationService] Candidate {} declined offer for app {}", userId, applicationId);

        return Map.of(
                "status", "OFFER_DECLINED",
                "message", "Bạn đã từ chối lời mời nhận việc."
        );
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /** Append one row to the application's status timeline. Best-effort within the caller's transaction. */
    private void recordHistory(UUID applicationId, String status) {
        jdbcTemplate.update(
                "insert into application_status_history (application_id, status) values (:id, :status)",
                Map.of("id", applicationId, "status", status));
    }

    private Map<String, Object> fetchJobOrThrow(UUID jobId) {
        try {
            return jdbcTemplate.queryForMap("""
                    select id, title, status, min_req_rs, requires_premium, company_id, deadline_at, created_by, created_at
                    from jobs
                    where id = :id and deleted_at is null
                    """, Map.of("id", jobId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Không tìm thấy cơ hội: " + jobId);
        }
    }

    private Map<String, Object> fetchCandidateOrThrow(UUID userId) {
        try {
            return jdbcTemplate.queryForMap("""
                    select
                        u.id,
                        u.display_name,
                        u.email,
                        u.premium_until,
                        p.id                  as profile_id,
                        p.reputation_score,
                        p.total_exp,
                        p.current_level,
                        p.onboarding_completed
                    from app_users u
                    join profiles p on p.user_id = u.id
                    where u.id = :userId
                    """, Map.of("userId", userId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Hồ sơ ứng viên không tồn tại.");
        }
    }

    private boolean checkPremium(UUID userId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from app_users
                where id = :userId
                  and premium_until > now()
                """, Map.of("userId", userId), Integer.class);
        return count != null && count > 0;
    }

    private String buildSnapshot(int rsAtApply, Map<String, Object> candidate) {
        try {
            Map<String, Object> snap = Map.of(
                    "rsAtApply", rsAtApply,
                    "levelAtApply", candidate.get("current_level"),
                    "expAtApply", candidate.get("total_exp"),
                    "onboardingCompleted", candidate.get("onboarding_completed")
            );
            return objectMapper.writeValueAsString(snap);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Validates required custom fields and returns a denormalized JSON snapshot [{label,value}], or null. */
    private String buildCustomAnswers(UUID jobId, Map<String, String> answers) {
        List<Map<String, Object>> fields = jdbcTemplate.queryForList(
                "select id, label, required from job_form_fields where job_id = :jobId order by sort_order",
                Map.of("jobId", jobId));
        if (fields.isEmpty()) return null;

        List<Map<String, String>> out = new java.util.ArrayList<>();
        for (Map<String, Object> f : fields) {
            String fieldId = f.get("id").toString();
            String label = (String) f.get("label");
            boolean required = Boolean.TRUE.equals(f.get("required"));
            String val = answers != null ? answers.get(fieldId) : null;
            if (required && (val == null || val.isBlank())) {
                throw new AppException(HttpStatus.BAD_REQUEST,
                        "Vui lòng trả lời câu hỏi bắt buộc: " + label);
            }
            if (val != null && !val.isBlank()) {
                out.add(Map.of("label", label, "value", val.trim()));
            }
        }
        if (out.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            return null;
        }
    }
}
