package com.nextplease.backend.service;

import com.nextplease.backend.dto.response.MeResponse;
import com.nextplease.backend.exception.ResourceNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Candidate "saves" (bookmarks) a job to revisit later. Backed by the existing
 * {@code saved_jobs} table (V2 schema). Raw {@link NamedParameterJdbcTemplate}
 * SQL per this codebase's convention.
 */
@Service
public class SavedJobService {

    private final CurrentUserService currentUserService;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public SavedJobService(CurrentUserService currentUserService, NamedParameterJdbcTemplate jdbcTemplate) {
        this.currentUserService = currentUserService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Save is idempotent — saving twice is a no-op. Throws 404 if the job doesn't exist. */
    @Transactional
    public void save(UUID jobId) {
        MeResponse currentUser = currentUserService.getCurrentUser();
        Integer exists = jdbcTemplate.queryForObject(
                "select count(*) from jobs where id = :id and deleted_at is null",
                Map.of("id", jobId), Integer.class);
        if (exists == null || exists == 0) {
            throw new ResourceNotFoundException("Không tìm thấy tin tuyển dụng.");
        }
        jdbcTemplate.update("""
                insert into saved_jobs (user_id, job_id)
                values (:userId, :jobId)
                on conflict (user_id, job_id) do nothing
                """, Map.of("userId", currentUser.appUserId(), "jobId", jobId));
    }

    @Transactional
    public void unsave(UUID jobId) {
        MeResponse currentUser = currentUserService.getCurrentUser();
        jdbcTemplate.update("""
                delete from saved_jobs
                where user_id = :userId and job_id = :jobId
                """, Map.of("userId", currentUser.appUserId(), "jobId", jobId));
    }

    /** Ids of jobs the current user saved — the FE uses this to render bookmark state on cards. */
    @Transactional(readOnly = true)
    public List<UUID> getSavedJobIds() {
        MeResponse currentUser = currentUserService.getCurrentUser();
        return jdbcTemplate.query(
                "select job_id from saved_jobs where user_id = :userId",
                Map.of("userId", currentUser.appUserId()),
                (rs, i) -> (UUID) rs.getObject("job_id"));
    }

    /**
     * Full job cards for the saved-jobs view. Shape matches JobService.searchJobs
     * so the FE reuses the same card. Only returns saved jobs that are still OPEN
     * and not past deadline — a saved job that later closes drops off this list
     * (the "saved" view is for opportunities you can still act on).
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSavedJobs() {
        MeResponse currentUser = currentUserService.getCurrentUser();
        return jdbcTemplate.queryForList("""
                select j.id,
                       j.company_id as "companyId",
                       j.title,
                       j.description,
                       j.job_type as "jobType",
                       j.category,
                       j.specialty,
                       j.compensation,
                       j.min_req_rs as "minReqRs",
                       j.requires_premium as "requiresPremium",
                       j.location,
                       j.is_remote as "isRemote",
                       j.deadline_at as "deadlineAt",
                       j.status,
                       c.name as "companyName",
                       c.logo_url as "companyLogo"
                from saved_jobs sj
                join jobs j on j.id = sj.job_id
                join companies c on c.id = j.company_id
                where sj.user_id = :userId
                  and j.status = 'OPEN'
                  and j.deleted_at is null
                  and (j.deadline_at is null or j.deadline_at > now())
                order by sj.created_at desc
                """, Map.of("userId", currentUser.appUserId()));
    }
}
