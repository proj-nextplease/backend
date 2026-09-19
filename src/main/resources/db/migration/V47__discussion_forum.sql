-- Diễn đàn Thảo Luận: chủ đề, bài viết, bình luận, lượt thích, bình chọn.
-- Bài đăng hiển thị ngay; ContentModerationService gắn cờ nội dung xấu để Admin
-- xem lại và ẩn nếu cần (cùng cơ chế content_flag như jobs/quests).

create table if not exists discussion_topics (
    id          uuid primary key default gen_random_uuid(),
    slug        varchar(80)  not null unique,
    name        varchar(150) not null,
    icon_type   varchar(50)  not null,   -- khoá sticker 3D phía FE
    description text,
    is_official boolean      not null default true,
    sort_order  integer      not null default 0,
    created_at  timestamptz  not null default now()
);

create table if not exists discussion_posts (
    id             uuid primary key default gen_random_uuid(),
    topic_id       uuid not null references discussion_topics(id),
    author_user_id uuid not null references app_users(id),
    content        text not null,
    content_flag   boolean not null default false,
    hidden_at      timestamptz,
    hidden_by      uuid references app_users(id),
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    deleted_at     timestamptz,
    constraint ck_discussion_posts_content check (length(btrim(content)) > 0)
);

create index if not exists idx_discussion_posts_topic   on discussion_posts(topic_id);
create index if not exists idx_discussion_posts_author  on discussion_posts(author_user_id);
create index if not exists idx_discussion_posts_created on discussion_posts(created_at desc);

create table if not exists discussion_comments (
    id             uuid primary key default gen_random_uuid(),
    post_id        uuid not null references discussion_posts(id) on delete cascade,
    author_user_id uuid not null references app_users(id),
    content        text not null,
    content_flag   boolean not null default false,
    created_at     timestamptz not null default now(),
    deleted_at     timestamptz,
    constraint ck_discussion_comments_content check (length(btrim(content)) > 0)
);

create index if not exists idx_discussion_comments_post on discussion_comments(post_id, created_at);

create table if not exists discussion_post_likes (
    post_id    uuid not null references discussion_posts(id) on delete cascade,
    user_id    uuid not null references app_users(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (post_id, user_id)
);

create table if not exists discussion_topic_follows (
    topic_id   uuid not null references discussion_topics(id) on delete cascade,
    user_id    uuid not null references app_users(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (topic_id, user_id)
);

-- Bình chọn: mỗi bài có tối đa một poll, biểu diễn bằng danh sách lựa chọn.
create table if not exists discussion_poll_options (
    id         uuid primary key default gen_random_uuid(),
    post_id    uuid not null references discussion_posts(id) on delete cascade,
    label      varchar(200) not null,
    sort_order integer not null default 0
);

create index if not exists idx_discussion_poll_options_post on discussion_poll_options(post_id, sort_order);

create table if not exists discussion_poll_votes (
    post_id   uuid not null references discussion_posts(id) on delete cascade,
    option_id uuid not null references discussion_poll_options(id) on delete cascade,
    user_id   uuid not null references app_users(id) on delete cascade,
    voted_at  timestamptz not null default now(),
    primary key (post_id, user_id)   -- mỗi người một phiếu cho mỗi bài
);

-- 6 chủ đề chính thức; slug khớp với đường dẫn ?chu-de= phía FE.
insert into discussion_topics (slug, name, icon_type, description, sort_order) values
    ('phong-van',            'Kinh nghiệm phỏng vấn', 'phong-van',            'Bí quyết trả lời phỏng vấn, xử lý câu hỏi hóc búa từ HR và case study thực tế.', 1),
    ('open-to-work',         'Open to Work',          'open-to-work',         'Không gian ứng viên chia sẻ CV, Portfolio và tìm kiếm cơ hội nghề nghiệp phù hợp.', 2),
    ('nang-cap-ky-nang',     'Nâng cấp kỹ năng',      'nang-cap-ky-nang',     'Tài liệu, khóa học và phương pháp tự học kỹ năng cứng lẫn kỹ năng mềm.', 3),
    ('kham-pha-ban-than',    'Khám phá bản thân',     'kham-pha-ban-than',    'Định vị năng lực, khám phá tính cách (MBTI, DISC) và tìm kiếm đam mê đích thực.', 4),
    ('kham-pha-nghe-nghiep', 'Khám phá nghề nghiệp',  'kham-pha-nghe-nghiep', 'Bức tranh toàn cảnh về các ngành nghề xu hướng, lộ trình thăng tiến và đãi ngộ.', 5),
    ('viec-tim-nguoi',       'Việc tìm người',        'viec-tim-nguoi',       'Tin tuyển dụng trực tiếp từ các doanh nghiệp, HR và Startup chất lượng.', 6)
on conflict (slug) do nothing;
