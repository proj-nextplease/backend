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
 * Candidate "saves" (bookmarks) a quest to revisit later. Backed by the new
 * {@code saved_quests} table. Raw {@link NamedParameterJdbcTemplate}
 * SQL per this codebase's convention.
 */
@Service
public class SavedQuestService {

    private final CurrentUserService currentUserService;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public SavedQuestService(CurrentUserService currentUserService, NamedParameterJdbcTemplate jdbcTemplate) {
        this.currentUserService = currentUserService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Save is idempotent — saving twice is a no-op. Throws 404 if the quest doesn't exist. */
    @Transactional
    public void save(UUID questId) {
        MeResponse currentUser = currentUserService.getCurrentUser();
        Integer exists = jdbcTemplate.queryForObject(
                "select count(*) from quests where id = :id and deleted_at is null",
                Map.of("id", questId), Integer.class);
        if (exists == null || exists == 0) {
            throw new ResourceNotFoundException("Không tìm thấy Quest.");
        }
        jdbcTemplate.update("""
                insert into saved_quests (user_id, quest_id)
                values (:userId, :questId)
                on conflict (user_id, quest_id) do nothing
                """, Map.of("userId", currentUser.appUserId(), "questId", questId));
    }

    @Transactional
    public void unsave(UUID questId) {
        MeResponse currentUser = currentUserService.getCurrentUser();
        jdbcTemplate.update("""
                delete from saved_quests
                where user_id = :userId and quest_id = :questId
                """, Map.of("userId", currentUser.appUserId(), "questId", questId));
    }

    /** Ids of quests the current user saved — the FE uses this to render bookmark state on cards. */
    @Transactional(readOnly = true)
    public List<UUID> getSavedQuestIds() {
        MeResponse currentUser = currentUserService.getCurrentUser();
        return jdbcTemplate.query(
                "select quest_id from saved_quests where user_id = :userId",
                Map.of("userId", currentUser.appUserId()),
                (rs, i) -> (UUID) rs.getObject("quest_id"));
    }

    private List<Map<String, Object>> getQuestFormFields(UUID questId) {
        return jdbcTemplate.queryForList("""
                select id, label, field_type as "fieldType", options, required, sort_order as "sortOrder"
                from quest_form_fields where quest_id = :questId order by sort_order
                """, Map.of("questId", questId));
    }

    /**
     * Full quest cards for the saved-quests view. Shape matches QuestService.searchQuests
     * so the FE reuses the same card. Only returns saved quests that are still OPEN
     * and not past deadline.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSavedQuests() {
        MeResponse currentUser = currentUserService.getCurrentUser();
        List<Map<String, Object>> quests = jdbcTemplate.queryForList("""
                select q.id,
                       q.title,
                       q.description,
                       q.category,
                       q.exp_reward as "expReward",
                       q.np_reward as "npReward",
                       q.min_req_rs as "minReqRs",
                       q.capacity,
                       q.starts_at as "startsAt",
                       q.ends_at as "endsAt",
                       q.banner_url as "bannerUrl",
                       q.banner_pos as "bannerPos",
                       q.status,
                       c.id as "companyId",
                       c.name as "companyName",
                       c.logo_url as "companyLogo",
                       c.company_type as "companyType",
                       (select count(*) from quest_applications qa
                        where qa.quest_id = q.id
                          and qa.status not in ('WITHDRAWN', 'REJECTED')) as "applicantCount"
                from saved_quests sq
                join quests q on q.id = sq.quest_id
                join companies c on c.id = q.company_id
                where sq.user_id = :userId
                  and q.status = 'OPEN'
                  and q.deleted_at is null
                  and (q.ends_at is null or q.ends_at > now())
                order by sq.created_at desc
                """, Map.of("userId", currentUser.appUserId()));

        for (Map<String, Object> q : quests) {
            q.put("formFields", getQuestFormFields((UUID) q.get("id")));
        }
        return quests;
    }
}
