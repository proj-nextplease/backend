package com.nextplease.backend.service;

import com.nextplease.backend.exception.ResourceNotFoundException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Candidate gamification: daily activity streak + daily/weekly quests.
 *
 * <p>EXP rewards are always granted via {@link ExpService} so the append-only
 * exp_events log stays the single source of truth (no double counting).
 */
@Service
public class GamificationService {

    private static final Logger log = LoggerFactory.getLogger(GamificationService.class);
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    /** A quest definition. {@code event} is the activity key that advances it (null = system-driven). */
    private record QuestDef(String key, String scope, String title, String desc, int target, int exp, String event) {}

    private static final List<QuestDef> CATALOG = List.of(
            new QuestDef("DAILY_LOGIN", "DAILY", "Ghé thăm mỗi ngày",
                    "Đăng nhập để giữ chuỗi streak của bạn.", 1, 20, null),
            new QuestDef("DAILY_VIEW_OPPORTUNITIES", "DAILY", "Khám phá 3 cơ hội",
                    "Xem 3 tin tuyển dụng hoặc Quest phù hợp.", 3, 30, "VIEW_OPPORTUNITY"),
            new QuestDef("DAILY_APPLY", "DAILY", "Ứng tuyển 1 cơ hội hôm nay",
                    "Gửi 1 đơn ứng tuyển việc làm hoặc Quest.", 1, 30, "APPLY"),
            new QuestDef("WEEKLY_APPLY", "WEEKLY", "Ứng tuyển 3 cơ hội",
                    "Gửi 3 đơn ứng tuyển trong tuần này.", 3, 150, "APPLY"),
            new QuestDef("WEEKLY_PROOF", "WEEKLY", "Có 1 minh chứng được duyệt",
                    "Một minh chứng của bạn được admin xác thực.", 1, 200, "SUBMIT_PROOF")
    );

    private final NamedParameterJdbcTemplate jdbc;
    private final ExpService expService;

    public GamificationService(NamedParameterJdbcTemplate jdbc, ExpService expService) {
        this.jdbc = jdbc;
        this.expService = expService;
    }

    private UUID profileId(UUID userId) {
        try {
            return jdbc.queryForObject(
                    "select id from profiles where user_id = :userId",
                    Map.of("userId", userId), UUID.class);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Không tìm thấy hồ sơ ứng viên.");
        }
    }

    private static LocalDate weekStart(LocalDate d) {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** Read-only snapshot of streak + quests + level progress. */
    @Transactional
    public Map<String, Object> getState(UUID userId) {
        UUID pid = profileId(userId);
        LocalDate today = LocalDate.now(VN);
        ensureQuestRows(pid, today);
        return buildState(pid, today);
    }

    /**
     * Mark the user active today and roll the streak forward.
     * Yesterday → +1; today → unchanged; gap (with a freeze) → consume freeze and keep;
     * larger gap → reset to 1. Also completes the DAILY_LOGIN quest.
     */
    @Transactional
    public Map<String, Object> ping(UUID userId) {
        UUID pid = profileId(userId);
        LocalDate today = LocalDate.now(VN);

        Map<String, Object> p = jdbc.queryForMap("""
                select current_streak, longest_streak, last_active_date, streak_freezes
                from profiles where id = :pid for update
                """, Map.of("pid", pid));

        int streak = ((Number) p.get("current_streak")).intValue();
        int longest = ((Number) p.get("longest_streak")).intValue();
        int freezes = ((Number) p.get("streak_freezes")).intValue();
        LocalDate last = p.get("last_active_date") == null ? null
                : ((java.sql.Date) p.get("last_active_date")).toLocalDate();

        if (last == null || last.isBefore(today)) {
            if (last != null && last.isEqual(today.minusDays(1))) {
                streak += 1;
            } else if (last != null && freezes > 0 && last.isBefore(today.minusDays(1))) {
                freezes -= 1; // a freeze protects a single missed day, streak preserved
            } else {
                streak = 1;
            }
            longest = Math.max(longest, streak);
            jdbc.update("""
                    update profiles
                    set current_streak = :streak, longest_streak = :longest,
                        last_active_date = :today, streak_freezes = :freezes, updated_at = now()
                    where id = :pid
                    """, Map.of("streak", streak, "longest", longest,
                    "today", java.sql.Date.valueOf(today), "freezes", freezes, "pid", pid));
        }

        ensureQuestRows(pid, today);
        // Visiting completes the daily login quest.
        jdbc.update("""
                update daily_quest_progress
                set progress = target, completed_at = coalesce(completed_at, now())
                where profile_id = :pid and quest_key = 'DAILY_LOGIN' and quest_date = :today
                """, Map.of("pid", pid, "today", java.sql.Date.valueOf(today)));

        return buildState(pid, today);
    }

    /**
     * Advance every quest tied to {@code event} by {@code amount}. Called when the
     * candidate performs an activity (viewing opportunities, applying, submitting proof…).
     */
    @Transactional
    public Map<String, Object> recordEvent(UUID userId, String event, int amount) {
        UUID pid = profileId(userId);
        LocalDate today = LocalDate.now(VN);
        ensureQuestRows(pid, today);
        int add = Math.max(1, amount);

        for (QuestDef q : CATALOG) {
            if (!event.equals(q.event())) continue;
            if ("DAILY".equals(q.scope())) {
                jdbc.update("""
                        update daily_quest_progress
                        set progress = least(target, progress + :add),
                            completed_at = case when progress + :add >= target and completed_at is null
                                                then now() else completed_at end
                        where profile_id = :pid and quest_key = :key and quest_date = :today
                        """, Map.of("add", add, "pid", pid, "key", q.key(),
                        "today", java.sql.Date.valueOf(today)));
            } else {
                jdbc.update("""
                        update weekly_quest_progress
                        set progress = least(target, progress + :add),
                            completed_at = case when progress + :add >= target and completed_at is null
                                                then now() else completed_at end
                        where profile_id = :pid and quest_key = :key and week_start = :ws
                        """, Map.of("add", add, "pid", pid, "key", q.key(),
                        "ws", java.sql.Date.valueOf(weekStart(today))));
            }
        }
        return buildState(pid, today);
    }

    /** Claim the EXP reward of a completed-but-unclaimed quest. */
    @Transactional
    public Map<String, Object> claim(UUID userId, String scope, String questKey) {
        UUID pid = profileId(userId);
        LocalDate today = LocalDate.now(VN);
        QuestDef def = CATALOG.stream()
                .filter(q -> q.key().equals(questKey) && q.scope().equalsIgnoreCase(scope))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Nhiệm vụ không hợp lệ."));

        String table = "DAILY".equalsIgnoreCase(scope) ? "daily_quest_progress" : "weekly_quest_progress";
        String dateCol = "DAILY".equalsIgnoreCase(scope) ? "quest_date" : "week_start";
        LocalDate dateVal = "DAILY".equalsIgnoreCase(scope) ? today : weekStart(today);

        ensureQuestRows(pid, today);
        Map<String, Object> row = jdbc.queryForMap(
                "select id, completed_at, exp_awarded from " + table
                        + " where profile_id = :pid and quest_key = :key and " + dateCol + " = :d for update",
                Map.of("pid", pid, "key", questKey, "d", java.sql.Date.valueOf(dateVal)));

        boolean awarded = Boolean.TRUE.equals(row.get("exp_awarded"));
        boolean completed = row.get("completed_at") != null;
        if (completed && !awarded) {
            UUID rowId = (UUID) row.get("id");
            expService.addExp(pid, def.exp(), "QUEST_REWARD", table, rowId);
            jdbc.update("update " + table + " set exp_awarded = true where id = :id", Map.of("id", rowId));
            log.info("[Gamification] Profile {} claimed {} quest {} (+{} EXP)", pid, scope, questKey, def.exp());
        }
        return buildState(pid, today);
    }

    /** Create today's daily rows and this week's weekly rows if missing. */
    private void ensureQuestRows(UUID pid, LocalDate today) {
        LocalDate ws = weekStart(today);
        for (QuestDef q : CATALOG) {
            if ("DAILY".equals(q.scope())) {
                jdbc.update("""
                        insert into daily_quest_progress (profile_id, quest_key, quest_date, target)
                        values (:pid, :key, :d, :target)
                        on conflict (profile_id, quest_key, quest_date) do nothing
                        """, Map.of("pid", pid, "key", q.key(),
                        "d", java.sql.Date.valueOf(today), "target", q.target()));
            } else {
                jdbc.update("""
                        insert into weekly_quest_progress (profile_id, quest_key, week_start, target)
                        values (:pid, :key, :ws, :target)
                        on conflict (profile_id, quest_key, week_start) do nothing
                        """, Map.of("pid", pid, "key", q.key(),
                        "ws", java.sql.Date.valueOf(ws), "target", q.target()));
            }
        }
    }

    private Map<String, Object> buildState(UUID pid, LocalDate today) {
        Map<String, Object> profile = jdbc.queryForMap("""
                select total_exp, current_level, reputation_score,
                       current_streak, longest_streak, streak_freezes, last_active_date
                from profiles where id = :pid
                """, Map.of("pid", pid));

        long totalExp = ((Number) profile.get("total_exp")).longValue();
        int level = ((Number) profile.get("current_level")).intValue();
        long levelStart = expService.expToReachLevel(level);
        long span = expService.expForLevelSpan(level);
        long into = Math.max(0, totalExp - levelStart);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("level", level);
        result.put("totalExp", totalExp);
        result.put("expIntoLevel", into);
        result.put("expForNextLevel", span);
        result.put("reputationScore", ((Number) profile.get("reputation_score")).intValue());
        result.put("currentStreak", ((Number) profile.get("current_streak")).intValue());
        result.put("longestStreak", ((Number) profile.get("longest_streak")).intValue());
        result.put("streakFreezes", ((Number) profile.get("streak_freezes")).intValue());
        Object lastActive = profile.get("last_active_date");
        result.put("activeToday", lastActive != null
                && ((java.sql.Date) lastActive).toLocalDate().isEqual(today));

        result.put("dailyQuests", readQuests(pid, "daily_quest_progress", "quest_date",
                java.sql.Date.valueOf(today)));
        result.put("weeklyQuests", readQuests(pid, "weekly_quest_progress", "week_start",
                java.sql.Date.valueOf(weekStart(today))));
        return result;
    }

    private List<Map<String, Object>> readQuests(UUID pid, String table, String dateCol, java.sql.Date date) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select quest_key, progress, target, completed_at, exp_awarded from " + table
                        + " where profile_id = :pid and " + dateCol + " = :d",
                Map.of("pid", pid, "d", date));
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) byKey.put((String) r.get("quest_key"), r);

        List<Map<String, Object>> out = new ArrayList<>();
        for (QuestDef q : CATALOG) {
            if (!q.scope().equalsIgnoreCase(table.startsWith("daily") ? "DAILY" : "WEEKLY")) continue;
            Map<String, Object> r = byKey.get(q.key());
            int progress = r == null ? 0 : ((Number) r.get("progress")).intValue();
            boolean completed = r != null && r.get("completed_at") != null;
            boolean claimed = r != null && Boolean.TRUE.equals(r.get("exp_awarded"));
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("key", q.key());
            dto.put("scope", q.scope());
            dto.put("title", q.title());
            dto.put("desc", q.desc());
            dto.put("progress", progress);
            dto.put("target", q.target());
            dto.put("exp", q.exp());
            dto.put("completed", completed);
            dto.put("claimed", claimed);
            out.add(dto);
        }
        return out;
    }
}
