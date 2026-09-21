-- ============================================================================
--  SEED: bài thảo luận cho tab Thảo luận  (chỉ dùng cho môi trường phát triển)
-- ============================================================================
--  Dán vào Supabase → SQL Editor → Run.
--
--  ĐỌC TRƯỚC KHI CHẠY:
--    • Script CHỈ ghi vào discussion_posts / discussion_comments /
--      discussion_post_likes. Không đụng tới tài khoản, công ty hay tin tuyển
--      dụng.
--    • Tác giả được CHỌN từ các tài khoản ứng viên đã có sẵn trong DB, không
--      tạo tài khoản ma. Nếu DB chưa có ứng viên nào, script dừng và báo —
--      chạy dev_create_candidate_account.sql trước.
--    • Idempotent: chống trùng theo (topic_id, content). Chạy lại nhiều lần
--      không sinh thêm bài.
--    • Bảng discussion_posts KHÔNG có cột tiêu đề. Dòng đầu của content chính
--      là thứ giao diện hiện lên như tiêu đề, nên câu đầu mỗi bài được viết
--      ngắn và đứng được một mình.
--
--  GỠ BỎ: xem khối "DỌN DẸP" ở cuối file.
-- ============================================================================

BEGIN;

DO $$
DECLARE
    v_authors uuid[];
    v_n       int;
    v_topic   uuid;
    v_post    uuid;
    v_author  uuid;
    v_i       int := 0;

    -- slug chủ đề, nội dung bài, số ngày trước (để dòng thời gian không bằng
    -- phẳng), và vài bình luận.
    v_rows constant jsonb := $json$[
      {
        "topic": "phong-van",
        "days": 1,
        "content": "Được hỏi \"điểm yếu lớn nhất của em là gì\" thì trả lời sao cho thật mà không tự dìm?\n\nMình đi phỏng vấn thực tập tuần rồi, nói thật là hay trì hoãn, xong thấy chị HR hơi nhíu mày. Về nhà nghĩ lại thấy mình dại. Mọi người thường trả lời câu này kiểu gì ạ?",
        "comments": [
          "Mình hay nói một điểm yếu THẬT nhưng kèm theo cách đang khắc phục. Kiểu \"em hay ôm việc một mình, giờ em tập chia task và báo tiến độ mỗi thứ 6\". Quan trọng là phần sau chứ không phải phần đầu.",
          "Đừng bao giờ dùng \"em quá cầu toàn\" nha, HR nghe câu đó mòn tai rồi.",
          "Thật ra nhíu mày chưa chắc là chấm điểm xấu đâu bạn, nhiều khi người ta đang ghi chú thôi."
        ]
      },
      {
        "topic": "open-to-work",
        "days": 2,
        "content": "Có nên để trạng thái Open to Work công khai khi đang còn đi học không?\n\nMình năm 3, đang muốn kiếm part-time đúng ngành. Sợ để công khai thì chỗ thực tập hiện tại thấy lại kỳ."
      },
      {
        "topic": "nang-cap-ky-nang",
        "days": 3,
        "content": "Học Figma bao lâu thì đủ để nhận job freelance nhỏ?\n\nMình tự học được tầm 2 tháng, làm được vài bản redesign app cho vui. Không biết vậy đã dám nhận việc thật chưa hay nên làm thêm portfolio đã.",
        "comments": [
          "Mình nghĩ cứ nhận job nhỏ đi bạn, làm thật 1 dự án bằng tự học 2 tháng. Nhưng nhớ chốt scope rõ ràng từ đầu không là sửa tới sửa lui.",
          "Portfolio 3 bài tự nghĩ ra không bằng 1 bài có khách hàng thật và có nói được vì sao mình chọn phương án đó."
        ]
      },
      {
        "topic": "kham-pha-nghe-nghiep",
        "days": 5,
        "content": "Học Kinh tế nhưng thích làm data, chuyển hướng lúc này có muộn không?\n\nMình đang năm 3 ngành Kinh tế đối ngoại. Càng học càng thấy mình thích mảng phân tích số liệu hơn. Có anh chị nào từng chuyển ngang không cho em xin lời khuyên với.",
        "comments": [
          "Không muộn đâu em. Nền kinh tế cộng thêm SQL với Python là hồ sơ rất ổn cho vị trí Business Analyst, nhiều chỗ còn thích hơn dân IT thuần.",
          "Em thử làm 1-2 dự án phân tích dữ liệu thật rồi viết lại quá trình, cái đó nói thay CV."
        ]
      },
      {
        "topic": "kham-pha-ban-than",
        "days": 8,
        "content": "Làm sao biết mình hợp với môi trường startup hay công ty lớn?\n\nMình đi thực tập ở một startup 15 người, vui nhưng loạn. Bạn mình ở công ty lớn thì bảo ổn định mà chán. Chưa biết mình thuộc kiểu nào."
      },
      {
        "topic": "viec-tim-nguoi",
        "days": 12,
        "content": "CLB mình đang tìm 2 bạn làm content cho mùa tuyển thành viên\n\nCần bạn viết được caption và biết dựng video ngắn cơ bản. Không yêu cầu kinh nghiệm, có anh chị hướng dẫn. Ai quan tâm comment bên dưới nha."
      }
    ]$json$;
    v_row jsonb;
    v_cmt jsonb;
BEGIN
    -- ── Tác giả ─────────────────────────────────────────────────────────────
    --  Lấy các tài khoản ứng viên còn sống. Sắp theo created_at để lần chạy
    --  nào cũng ra cùng thứ tự — không thì mỗi lần chạy lại bài đổi chủ.
    SELECT array_agg(u.id ORDER BY u.created_at)
      INTO v_authors
      FROM app_users u
      JOIN user_roles r ON r.user_id = u.id
     WHERE r.role_code LIKE 'candidate%'
       AND u.status <> 'DELETED'
       AND u.deleted_at IS NULL;

    v_n := coalesce(array_length(v_authors, 1), 0);

    IF v_n = 0 THEN
        RAISE EXCEPTION 'Không có tài khoản ứng viên nào trong DB. Chạy scripts/dev_create_candidate_account.sql trước đã.';
    END IF;

    RAISE NOTICE 'Có % tài khoản ứng viên để làm tác giả.', v_n;

    -- ── Bài viết ────────────────────────────────────────────────────────────
    FOR v_row IN SELECT * FROM jsonb_array_elements(v_rows)
    LOOP
        SELECT id INTO v_topic
          FROM discussion_topics
         WHERE slug = v_row->>'topic';

        IF v_topic IS NULL THEN
            RAISE NOTICE 'Bỏ qua: không có chủ đề slug=%', v_row->>'topic';
            CONTINUE;
        END IF;

        -- Xoay vòng tác giả. Nếu DB chỉ có 1 ứng viên thì mọi bài cùng một
        -- người — đúng với thực tế DB đang có, không giả vờ đông hơn.
        v_author := v_authors[(v_i % v_n) + 1];
        v_i := v_i + 1;

        -- Chống trùng theo (chủ đề, nội dung): chạy lại không sinh thêm bài.
        SELECT id INTO v_post
          FROM discussion_posts
         WHERE topic_id = v_topic
           AND content  = v_row->>'content'
           AND deleted_at IS NULL;

        IF v_post IS NOT NULL THEN
            RAISE NOTICE 'Đã có, bỏ qua: %', left(v_row->>'content', 40);
            CONTINUE;
        END IF;

        INSERT INTO discussion_posts (topic_id, author_user_id, content,
                                      content_flag, created_at, updated_at)
        VALUES (v_topic, v_author, v_row->>'content', false,
                now() - ((v_row->>'days')::int * interval '1 day'),
                now() - ((v_row->>'days')::int * interval '1 day'))
        RETURNING id INTO v_post;

        -- ── Bình luận ───────────────────────────────────────────────────────
        --  Tác giả bình luận là người KHÁC tác giả bài khi DB có từ 2 ứng viên
        --  trở lên; tự trả lời bài của chính mình trông rất giả.
        IF v_row ? 'comments' THEN
            FOR v_cmt IN SELECT * FROM jsonb_array_elements(v_row->'comments')
            LOOP
                -- Bảng này KHÔNG có cột updated_at (xem V47__discussion_forum.sql),
                -- khác với discussion_posts. Liệt kê nó ở đây là lỗi cú pháp.
                INSERT INTO discussion_comments (post_id, author_user_id, content,
                                                 content_flag, created_at)
                VALUES (v_post,
                        v_authors[((v_i) % v_n) + 1],
                        v_cmt #>> '{}',
                        false,
                        now() - ((v_row->>'days')::int * interval '1 day')
                              + (v_i * interval '3 hours'));
                v_i := v_i + 1;
            END LOOP;
        END IF;

        -- ── Lượt thích ──────────────────────────────────────────────────────
        --  Mỗi tài khoản nhiều nhất một lượt (bảng có ràng buộc duy nhất), nên
        --  số like không thể vượt quá số người dùng thật đang có.
        INSERT INTO discussion_post_likes (post_id, user_id)
        SELECT v_post, a
          FROM unnest(v_authors[1:greatest(1, least(v_n, 3))]) AS a
        ON CONFLICT DO NOTHING;

        RAISE NOTICE 'Đã tạo bài: %', left(v_row->>'content', 40);
    END LOOP;
END
$$;

COMMIT;


-- ============================================================================
--  KIỂM TRA — chạy riêng khối này sau khi Run ở trên
-- ============================================================================
SELECT t.name                                   AS chu_de,
       left(p.content, 50)                      AS dong_dau,
       coalesce(nullif(btrim(u.display_name), ''),
                split_part(u.email, '@', 1))    AS tac_gia,
       (SELECT count(*) FROM discussion_comments c
         WHERE c.post_id = p.id AND c.deleted_at IS NULL) AS so_binh_luan,
       (SELECT count(*) FROM discussion_post_likes l
         WHERE l.post_id = p.id)                AS so_thich,
       p.created_at::date                       AS ngay_dang
  FROM discussion_posts p
  JOIN discussion_topics t ON t.id = p.topic_id
  JOIN app_users u         ON u.id = p.author_user_id
 WHERE p.deleted_at IS NULL
 ORDER BY p.created_at DESC;

-- Kỳ vọng: 6 bài, trải trên 6 chủ đề khác nhau, 4 bài có bình luận.


-- ============================================================================
--  DỌN DẸP — chỉ chạy khi muốn gỡ toàn bộ bài seed
-- ============================================================================
--  Chạy SELECT trước để NHÌN chính xác những gì sắp bị xoá. Chỉ khi danh sách
--  đúng ý thì mới đổi thành DELETE. Bình luận và lượt thích tự đi theo bài
--  (khoá ngoại ON DELETE CASCADE), không phải xoá riêng.
--
--  SELECT p.id, t.name, left(p.content, 60), p.created_at
--    FROM discussion_posts p
--    JOIN discussion_topics t ON t.id = p.topic_id
--   WHERE p.content LIKE 'Được hỏi "điểm yếu lớn nhất%'
--      OR p.content LIKE 'Có nên để trạng thái Open to Work%'
--      OR p.content LIKE 'Học Figma bao lâu%'
--      OR p.content LIKE 'Học Kinh tế nhưng thích làm data%'
--      OR p.content LIKE 'Làm sao biết mình hợp với môi trường startup%'
--      OR p.content LIKE 'CLB mình đang tìm 2 bạn làm content%';
--
--  Đổi `SELECT p.id, t.name, left(p.content, 60), p.created_at FROM` thành
--  `DELETE FROM` (và bỏ phần JOIN) khi đã chắc chắn.
-- ============================================================================
