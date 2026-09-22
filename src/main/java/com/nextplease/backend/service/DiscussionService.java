package com.nextplease.backend.service;

import com.nextplease.backend.dto.response.MeResponse;
import com.nextplease.backend.exception.AppException;
import com.nextplease.backend.exception.ResourceNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

/**
 * Diễn đàn Thảo Luận: chủ đề, bài viết, bình luận, thích và bình chọn.
 *
 * Bài đăng hiển thị ngay sau khi gửi — không qua hàng đợi duyệt như Job/Quest.
 * {@link ContentModerationService} chỉ gắn cờ {@code content_flag} để Admin rà
 * lại; bài bị ẩn (hidden_at) sẽ biến mất khỏi feed công khai.
 *
 * Đọc feed không cần đăng nhập; mọi hành động ghi thì cần. Raw SQL với
 * {@link NamedParameterJdbcTemplate} theo đúng quy ước của codebase này.
 */
@Service
public class DiscussionService {

    private static final Logger log = LoggerFactory.getLogger(DiscussionService.class);

    /** Số bình luận mới nhất trả kèm mỗi bài trong feed; phần còn lại tải riêng. */
    private static final int PREVIEW_COMMENTS = 3;
    private static final int MAX_POLL_OPTIONS = 6;

    private final CurrentUserService currentUserService;
    private final ContentModerationService moderationService;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final NotificationService notificationService;

    public DiscussionService(CurrentUserService currentUserService,
                             ContentModerationService moderationService,
                             NamedParameterJdbcTemplate jdbcTemplate,
                             NotificationService notificationService) {
        this.currentUserService = currentUserService;
        this.moderationService = moderationService;
        this.jdbcTemplate = jdbcTemplate;
        this.notificationService = notificationService;
    }

    // ── Người dùng hiện tại ───────────────────────────────────────────────────

    /**
     * Id người đang đăng nhập, hoặc null khi khách vãng lai đọc feed.
     *
     * {@code getCurrentUser()} tự nó là {@code @Transactional} và ném khi không
     * có phiên đăng nhập. Nếu nó chạy BÊN TRONG một transaction đang mở, cú ném
     * đó đánh dấu transaction là rollback-only, và lần commit sau nổ
     * {@code UnexpectedRollbackException} — dù ta đã bắt exception ở đây. Vì vậy
     * các đường đọc gọi hàm này phải không có transaction bao ngoài; khi đó
     * getCurrentUser mở transaction riêng và chỉ rollback chính nó.
     */
    private UUID currentUserIdOrNull() {
        try {
            MeResponse me = currentUserService.getCurrentUser();
            return me == null ? null : me.appUserId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private UUID requireUserId() {
        UUID userId = currentUserIdOrNull();
        if (userId == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập để thực hiện thao tác này.");
        }
        return userId;
    }

    // ── Chủ đề ────────────────────────────────────────────────────────────────

    // Không @Transactional: xem currentUserIdOrNull(). Chỉ toàn câu SELECT.
    public List<Map<String, Object>> getTopics() {
        UUID userId = currentUserIdOrNull();
        return jdbcTemplate.queryForList("""
                select t.id,
                       t.slug,
                       t.name,
                       t.icon_type    as "iconType",
                       t.description,
                       t.is_official  as "official",
                       (select count(*) from discussion_topic_follows f where f.topic_id = t.id) as "followersCount",
                       (select count(*) from discussion_posts p
                         where p.topic_id = t.id and p.deleted_at is null and p.hidden_at is null) as "postsCount",
                       (:userId::uuid is not null and exists (
                            select 1 from discussion_topic_follows f
                            where f.topic_id = t.id and f.user_id = :userId)) as "isFollowing"
                from discussion_topics t
                order by t.sort_order, t.name
                """, new MapSqlParameterSource().addValue("userId", userId));
    }

    /** Bật/tắt theo dõi một chủ đề. Trả về trạng thái sau khi đổi. */
    @Transactional
    public boolean toggleFollowTopic(UUID topicId) {
        UUID userId = requireUserId();
        assertTopicExists(topicId);
        int removed = jdbcTemplate.update("""
                delete from discussion_topic_follows
                where topic_id = :topicId and user_id = :userId
                """, Map.of("topicId", topicId, "userId", userId));
        if (removed > 0) {
            return false;
        }
        jdbcTemplate.update("""
                insert into discussion_topic_follows (topic_id, user_id)
                values (:topicId, :userId)
                on conflict do nothing
                """, Map.of("topicId", topicId, "userId", userId));
        return true;
    }

    private void assertTopicExists(UUID topicId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from discussion_topics where id = :id",
                Map.of("id", topicId), Integer.class);
        if (count == null || count == 0) {
            throw new ResourceNotFoundException("Không tìm thấy chủ đề thảo luận này.");
        }
    }

    // ── Feed ──────────────────────────────────────────────────────────────────

    /**
     * Feed bài viết, kèm thông tin tác giả, số liệu tương tác, poll và vài bình
     * luận mới nhất.
     *
     * @param topicSlug lọc theo chủ đề, null = tất cả
     * @param sort      "highlight" xếp theo lượt thích, mặc định mới nhất trước
     */
    // Không @Transactional: xem currentUserIdOrNull(). Chỉ toàn câu SELECT.
    public List<Map<String, Object>> getPosts(String topicSlug, String sort, int limit, int offset) {
        UUID userId = currentUserIdOrNull();
        String orderBy = "highlight".equalsIgnoreCase(sort)
                ? " order by \"likesCount\" desc, p.created_at desc "
                : " order by p.created_at desc ";

        List<Map<String, Object>> posts = jdbcTemplate.queryForList("""
                select p.id,
                       p.content,
                       p.created_at   as "createdAt",
                       p.content_flag as "contentFlag",
                       t.id           as "topicId",
                       t.slug         as "topicSlug",
                       t.name         as "topicName",
                       u.id           as "authorId",
                       coalesce(nullif(btrim(u.display_name), ''), split_part(u.email, '@', 1)) as "authorName",
                       pr.avatar_url  as "authorAvatarUrl",
                       coalesce(pr.headline,
                                (select c.name from companies c
                                  where c.owner_user_id = u.id and c.deleted_at is null
                                  order by c.created_at limit 1),
                                'Thành viên NextPlease') as "authorRole",
                       (select count(*) from discussion_post_likes l where l.post_id = p.id) as "likesCount",
                       (select count(*) from discussion_comments cm
                         where cm.post_id = p.id and cm.deleted_at is null) as "commentsCount",
                       (:userId::uuid is not null and exists (
                            select 1 from discussion_post_likes l
                            where l.post_id = p.id and l.user_id = :userId)) as "hasLiked",
                       (p.author_user_id = :userId::uuid) as "isMine"
                from discussion_posts p
                join discussion_topics t on t.id = p.topic_id
                join app_users u on u.id = p.author_user_id
                left join profiles pr on pr.user_id = u.id
                where p.deleted_at is null
                  and p.hidden_at is null
                  and (:topicSlug::text is null or t.slug = :topicSlug)
                """ + orderBy + """
                limit :limit offset :offset
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("topicSlug", topicSlug == null || topicSlug.isBlank() ? null : topicSlug.trim())
                .addValue("limit", Math.min(Math.max(limit, 1), 50))
                .addValue("offset", Math.max(offset, 0)));

        if (posts.isEmpty()) {
            return posts;
        }

        List<UUID> postIds = posts.stream().map(p -> (UUID) p.get("id")).toList();
        Map<UUID, Map<String, Object>> pollsByPost = loadPolls(postIds, userId);
        Map<UUID, List<Map<String, Object>>> commentsByPost = loadPreviewComments(postIds);

        List<Map<String, Object>> result = new ArrayList<>(posts.size());
        for (Map<String, Object> post : posts) {
            Map<String, Object> mutable = new HashMap<>(post);
            UUID id = (UUID) post.get("id");
            mutable.put("poll", pollsByPost.get(id));
            mutable.put("comments", commentsByPost.getOrDefault(id, List.of()));
            result.add(mutable);
        }
        return result;
    }

    /** Poll của từng bài: lựa chọn, số phiếu và lựa chọn người dùng đã bỏ. */
    private Map<UUID, Map<String, Object>> loadPolls(List<UUID> postIds, UUID userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select o.id,
                       o.post_id as "postId",
                       o.label,
                       (select count(*) from discussion_poll_votes v where v.option_id = o.id) as votes
                from discussion_poll_options o
                where o.post_id in (:postIds)
                order by o.post_id, o.sort_order
                """, new MapSqlParameterSource().addValue("postIds", postIds));
        if (rows.isEmpty()) {
            return Map.of();
        }

        Map<UUID, UUID> myVote = new HashMap<>();
        if (userId != null) {
            jdbcTemplate.queryForList("""
                    select post_id as "postId", option_id as "optionId"
                    from discussion_poll_votes
                    where user_id = :userId and post_id in (:postIds)
                    """, new MapSqlParameterSource().addValue("userId", userId).addValue("postIds", postIds))
                    .forEach(r -> myVote.put((UUID) r.get("postId"), (UUID) r.get("optionId")));
        }

        Map<UUID, Map<String, Object>> polls = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            UUID postId = (UUID) row.get("postId");
            Map<String, Object> poll = polls.computeIfAbsent(postId, k -> {
                Map<String, Object> p = new HashMap<>();
                p.put("options", new ArrayList<Map<String, Object>>());
                p.put("totalVotes", 0L);
                p.put("votedOption", myVote.get(postId));
                return p;
            });
            long votes = ((Number) row.get("votes")).longValue();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> options = (List<Map<String, Object>>) poll.get("options");
            options.add(Map.of("id", row.get("id"), "text", row.get("label"), "votes", votes));
            poll.put("totalVotes", ((Number) poll.get("totalVotes")).longValue() + votes);
        }
        return polls;
    }

    /** Vài bình luận mới nhất của mỗi bài, dùng để hiển thị ngay dưới bài viết. */
    private Map<UUID, List<Map<String, Object>>> loadPreviewComments(List<UUID> postIds) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, "postId", author, "authorAvatarUrl", role, content, "createdAt"
                from (
                    select cm.id,
                           cm.post_id as "postId",
                           coalesce(nullif(btrim(u.display_name), ''), split_part(u.email, '@', 1)) as author,
                           pr.avatar_url as "authorAvatarUrl",
                           coalesce(pr.headline,
                                (select c.name from companies c
                                  where c.owner_user_id = u.id and c.deleted_at is null
                                  order by c.created_at limit 1),
                                'Thành viên NextPlease') as role,
                           cm.content,
                           cm.created_at as "createdAt",
                           row_number() over (partition by cm.post_id order by cm.created_at desc) as rn
                    from discussion_comments cm
                    join app_users u on u.id = cm.author_user_id
                    left join profiles pr on pr.user_id = u.id
                    where cm.post_id in (:postIds) and cm.deleted_at is null
                ) ranked
                where rn <= :preview
                order by "createdAt"
                """, new MapSqlParameterSource()
                .addValue("postIds", postIds)
                .addValue("preview", PREVIEW_COMMENTS));

        Map<UUID, List<Map<String, Object>>> byPost = new HashMap<>();
        for (Map<String, Object> row : rows) {
            byPost.computeIfAbsent((UUID) row.get("postId"), k -> new ArrayList<>()).add(row);
        }
        return byPost;
    }

    // ── Bài viết ──────────────────────────────────────────────────────────────

    /**
     * Đăng bài mới. {@code pollOptions} (nếu có) phải từ 2 tới
     * {@value #MAX_POLL_OPTIONS} lựa chọn không rỗng.
     */
    @Transactional
    public Map<String, Object> createPost(String topicSlug, String content, List<String> pollOptions) {
        UUID userId = requireUserId();

        String body = content == null ? "" : content.trim();
        if (body.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Nội dung bài viết không được để trống.");
        }
        if (body.length() > 5000) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Nội dung bài viết tối đa 5000 ký tự.");
        }

        UUID topicId;
        try {
            topicId = jdbcTemplate.queryForObject(
                    "select id from discussion_topics where slug = :slug",
                    Map.of("slug", topicSlug == null ? "" : topicSlug.trim()), UUID.class);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Không tìm thấy chủ đề thảo luận này.");
        }

        List<String> options = normalizePollOptions(pollOptions);

        UUID postId = jdbcTemplate.queryForObject("""
                insert into discussion_posts (topic_id, author_user_id, content, content_flag)
                values (:topicId, :userId, :content, :contentFlag)
                returning id
                """, new MapSqlParameterSource()
                .addValue("topicId", topicId)
                .addValue("userId", userId)
                .addValue("content", body)
                .addValue("contentFlag", moderationService.containsProfanity(body)), UUID.class);

        for (int i = 0; i < options.size(); i++) {
            jdbcTemplate.update("""
                    insert into discussion_poll_options (post_id, label, sort_order)
                    values (:postId, :label, :sortOrder)
                    """, new MapSqlParameterSource()
                    .addValue("postId", postId)
                    .addValue("label", options.get(i))
                    .addValue("sortOrder", i));
        }

        return Map.of("id", postId);
    }

    private List<String> normalizePollOptions(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> options = raw.stream()
                .map(o -> o == null ? "" : o.trim())
                .filter(o -> !o.isBlank())
                .map(o -> o.length() > 200 ? o.substring(0, 200) : o)
                .toList();
        if (options.isEmpty()) {
            return List.of();
        }
        if (options.size() < 2) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Bình chọn cần ít nhất 2 lựa chọn.");
        }
        if (options.size() > MAX_POLL_OPTIONS) {
            throw new AppException(HttpStatus.BAD_REQUEST,
                    "Bình chọn tối đa " + MAX_POLL_OPTIONS + " lựa chọn.");
        }
        return options;
    }

    /** Xoá mềm bài viết. Chỉ tác giả hoặc Admin được xoá. */
    @Transactional
    public void deletePost(UUID postId) {
        UUID userId = requireUserId();
        java.util.Set<String> roles = currentUserService.getCurrentUser().roles();
        boolean isAdmin = roles != null && roles.stream()
                .anyMatch(r -> r != null && r.toUpperCase().contains("ADMIN"));

        UUID authorId;
        try {
            authorId = jdbcTemplate.queryForObject(
                    "select author_user_id from discussion_posts where id = :id and deleted_at is null",
                    Map.of("id", postId), UUID.class);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Không tìm thấy bài viết này.");
        }
        if (!isAdmin && !userId.equals(authorId)) {
            throw new AppException(HttpStatus.FORBIDDEN, "Bạn chỉ có thể xoá bài viết của chính mình.");
        }
        jdbcTemplate.update(
                "update discussion_posts set deleted_at = now(), updated_at = now() where id = :id",
                Map.of("id", postId));
    }


    /**
     * Báo cho tác giả bài viết rằng có người vừa tương tác.
     *
     * Ba điều được xử lý ở đây chứ không ở nơi gọi, vì cả bình luận lẫn lượt
     * thích đều cần đúng ba điều đó:
     *
     *   1. KHÔNG tự báo cho chính mình. Tự bình luận vào bài của mình là
     *      chuyện bình thường (trả lời người khác), và nhận thông báo về hành
     *      động mình vừa làm chỉ làm chuông kêu vô nghĩa.
     *   2. Lấy tên người tương tác theo đúng công thức mà feed đang dùng —
     *      display_name, thiếu thì lấy phần trước @ của email — để tên trong
     *      thông báo trùng với tên hiển thị dưới bài.
     *   3. Fail-soft. notify() đã không bao giờ ném, nhưng hai truy vấn tra
     *      cứu ở đây thì có; bọc lại để một bài bị xoá giữa chừng không làm
     *      hỏng cả giao dịch thêm bình luận.
     */
    private void notifyPostAuthor(UUID postId, UUID actorId, String type,
                                  String title, java.util.function.Function<String, String> body) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                    select p.author_user_id as "authorId",
                           coalesce(nullif(btrim(a.display_name), ''),
                                    split_part(a.email, '@', 1)) as "actorName"
                    from discussion_posts p
                    cross join app_users a
                    where p.id = :postId and a.id = :actorId
                    """, Map.of("postId", postId, "actorId", actorId));

            UUID authorId = (UUID) row.get("authorId");
            if (authorId == null) {
                log.warn("[thông báo] {} bỏ qua: bài {} không có tác giả", type, postId);
                return;
            }
            if (authorId.equals(actorId)) {
                // Không phải lỗi — tự tương tác với bài của mình thì không báo.
                // Vẫn ghi lại vì đây là lý do phổ biến nhất khiến người ta
                // tưởng thông báo hỏng.
                log.info("[thông báo] {} bỏ qua: tác giả tự tương tác (bài {})",
                        type, postId);
                return;
            }

            notificationService.notify(authorId, type, title,
                    body.apply(String.valueOf(row.get("actorName"))),
                    "/discussions/" + postId);
            log.info("[thông báo] đã tạo {} cho người dùng {} (bài {})",
                    type, authorId, postId);
        } catch (Exception e) {
            // Ghi cả stack trace. Bản trước chỉ ghi getMessage(), nên khi lỗi
            // thật xảy ra thì dòng log không đủ để biết hỏng ở đâu — đúng tình
            // huống đã gặp.
            log.warn("[thông báo] KHÔNG gửi được {} cho bài {}", type, postId, e);
        }
    }

    // ── Tương tác ─────────────────────────────────────────────────────────────

    /** Bật/tắt lượt thích. Trả về trạng thái và tổng số lượt sau khi đổi. */
    @Transactional
    public Map<String, Object> toggleLike(UUID postId) {
        UUID userId = requireUserId();
        assertPostVisible(postId);

        int removed = jdbcTemplate.update(
                "delete from discussion_post_likes where post_id = :postId and user_id = :userId",
                Map.of("postId", postId, "userId", userId));
        if (removed == 0) {
            jdbcTemplate.update("""
                    insert into discussion_post_likes (post_id, user_id)
                    values (:postId, :userId)
                    on conflict do nothing
                    """, Map.of("postId", postId, "userId", userId));
        }

        // Chỉ báo khi VỪA THÍCH, không báo khi bỏ thích. Người ta bấm nhầm rồi
        // bấm lại là chuyện thường; bắn hai thông báo cho một lần lỡ tay là
        // cách nhanh nhất khiến người dùng tắt hết thông báo.
        if (removed == 0) {
            notifyPostAuthor(postId, userId, "DISCUSSION_LIKE",
                    "Bài của bạn được thích",
                    actor -> actor + " vừa thích bài viết của bạn.");
        }

        Integer total = jdbcTemplate.queryForObject(
                "select count(*) from discussion_post_likes where post_id = :postId",
                Map.of("postId", postId), Integer.class);
        return Map.of("hasLiked", removed == 0, "likesCount", total == null ? 0 : total);
    }

    /** Bỏ phiếu cho một lựa chọn. Mỗi người một phiếu cho mỗi bài, không đổi lại. */
    @Transactional
    public Map<String, Object> votePoll(UUID postId, UUID optionId) {
        UUID userId = requireUserId();
        assertPostVisible(postId);

        Integer valid = jdbcTemplate.queryForObject(
                "select count(*) from discussion_poll_options where id = :optionId and post_id = :postId",
                Map.of("optionId", optionId, "postId", postId), Integer.class);
        if (valid == null || valid == 0) {
            throw new ResourceNotFoundException("Lựa chọn bình chọn không hợp lệ.");
        }

        int inserted = jdbcTemplate.update("""
                insert into discussion_poll_votes (post_id, option_id, user_id)
                values (:postId, :optionId, :userId)
                on conflict (post_id, user_id) do nothing
                """, Map.of("postId", postId, "optionId", optionId, "userId", userId));
        if (inserted == 0) {
            throw new AppException(HttpStatus.CONFLICT, "Bạn đã bình chọn cho bài viết này rồi.");
        }

        return Map.of("votedOption", optionId);
    }

    private void assertPostVisible(UUID postId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from discussion_posts
                where id = :id and deleted_at is null and hidden_at is null
                """, Map.of("id", postId), Integer.class);
        if (count == null || count == 0) {
            throw new ResourceNotFoundException("Không tìm thấy bài viết này.");
        }
    }

    // ── Bình luận ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getComments(UUID postId) {
        assertPostVisible(postId);
        return jdbcTemplate.queryForList("""
                select cm.id,
                       coalesce(nullif(btrim(u.display_name), ''), split_part(u.email, '@', 1)) as author,
                       pr.avatar_url as "authorAvatarUrl",
                       coalesce(pr.headline,
                                (select c.name from companies c
                                  where c.owner_user_id = u.id and c.deleted_at is null
                                  order by c.created_at limit 1),
                                'Thành viên NextPlease') as role,
                       cm.content,
                       cm.created_at as "createdAt"
                from discussion_comments cm
                join app_users u on u.id = cm.author_user_id
                left join profiles pr on pr.user_id = u.id
                where cm.post_id = :postId and cm.deleted_at is null
                order by cm.created_at
                """, Map.of("postId", postId));
    }

    @Transactional
    public Map<String, Object> addComment(UUID postId, String content) {
        UUID userId = requireUserId();
        assertPostVisible(postId);

        String body = content == null ? "" : content.trim();
        if (body.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Nội dung bình luận không được để trống.");
        }
        if (body.length() > 2000) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Bình luận tối đa 2000 ký tự.");
        }

        UUID commentId = jdbcTemplate.queryForObject("""
                insert into discussion_comments (post_id, author_user_id, content, content_flag)
                values (:postId, :userId, :content, :contentFlag)
                returning id
                """, new MapSqlParameterSource()
                .addValue("postId", postId)
                .addValue("userId", userId)
                .addValue("content", body)
                .addValue("contentFlag", moderationService.containsProfanity(body)), UUID.class);

        notifyPostAuthor(postId, userId, "DISCUSSION_COMMENT",
                "Có người bình luận bài của bạn",
                actor -> actor + " vừa bình luận bài viết của bạn.");

        return jdbcTemplate.queryForMap("""
                select cm.id,
                       coalesce(nullif(btrim(u.display_name), ''), split_part(u.email, '@', 1)) as author,
                       pr.avatar_url as "authorAvatarUrl",
                       coalesce(pr.headline,
                                (select c.name from companies c
                                  where c.owner_user_id = u.id and c.deleted_at is null
                                  order by c.created_at limit 1),
                                'Thành viên NextPlease') as role,
                       cm.content,
                       cm.created_at as "createdAt"
                from discussion_comments cm
                join app_users u on u.id = cm.author_user_id
                left join profiles pr on pr.user_id = u.id
                where cm.id = :id
                """, Map.of("id", commentId));
    }
}
