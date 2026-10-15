-- =====================================================================
-- JavaForum V1 : 初始Schema（PostgreSQL）
-- 约定：所有表包含 create_time / update_time；主键 BIGSERIAL
-- =====================================================================

-- ----------------------------
-- 用户表
-- ----------------------------
CREATE TABLE users (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(50)  NOT NULL,
    password    VARCHAR(100) NOT NULL,               -- BCrypt 密文
    email       VARCHAR(100) NOT NULL,
    nickname    VARCHAR(50),
    avatar_url  VARCHAR(255),
    bio         VARCHAR(500),
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE', -- ACTIVE / MUTED / BANNED
    muted_until TIMESTAMP,
    create_time TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_users_username UNIQUE (username),
    CONSTRAINT uk_users_email    UNIQUE (email)
);

-- ----------------------------
-- 角色表 + 种子数据
-- ----------------------------
CREATE TABLE roles (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(20) NOT NULL,                -- USER / MODERATOR / ADMIN
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_roles_name UNIQUE (name)
);

INSERT INTO roles (name) VALUES ('USER'), ('MODERATOR'), ('ADMIN');

-- ----------------------------
-- 用户-角色 关联表
-- ----------------------------
CREATE TABLE user_roles (
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id     BIGINT NOT NULL REFERENCES roles(id),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role_id)
);

-- ----------------------------
-- 板块表
-- ----------------------------
CREATE TABLE boards (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL,
    description VARCHAR(255),
    sort_order  INT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_boards_name UNIQUE (name)
);

-- ----------------------------
-- 帖子表
-- ----------------------------
CREATE TABLE posts (
    id             BIGSERIAL PRIMARY KEY,
    board_id       BIGINT NOT NULL REFERENCES boards(id),
    author_id      BIGINT NOT NULL REFERENCES users(id),
    title          VARCHAR(200) NOT NULL,
    content        TEXT NOT NULL,
    view_count     BIGINT NOT NULL DEFAULT 0,
    like_count     INT    NOT NULL DEFAULT 0,
    favorite_count INT    NOT NULL DEFAULT 0,
    comment_count  INT    NOT NULL DEFAULT 0,
    is_pinned      BOOLEAN NOT NULL DEFAULT FALSE,   -- 置顶
    is_exquisite   BOOLEAN NOT NULL DEFAULT FALSE,   -- 精华
    deleted        BOOLEAN NOT NULL DEFAULT FALSE,   -- 软删除
    create_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_posts_board       ON posts (board_id);
CREATE INDEX idx_posts_author      ON posts (author_id);
CREATE INDEX idx_posts_create_time ON posts (create_time DESC);
CREATE INDEX idx_posts_pinned      ON posts (is_pinned, create_time DESC);

-- ----------------------------
-- 评论表（parent_id 支持楼中楼回复）
-- ----------------------------
CREATE TABLE comments (
    id          BIGSERIAL PRIMARY KEY,
    post_id     BIGINT NOT NULL REFERENCES posts(id),
    author_id   BIGINT NOT NULL REFERENCES users(id),
    parent_id   BIGINT REFERENCES comments(id),
    content     TEXT   NOT NULL,
    deleted     BOOLEAN NOT NULL DEFAULT FALSE,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_comments_post   ON comments (post_id);
CREATE INDEX idx_comments_parent ON comments (parent_id);

-- ----------------------------
-- 标签表 + 帖子-标签关联表
-- ----------------------------
CREATE TABLE tags (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(30) NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tags_name UNIQUE (name)
);

CREATE TABLE post_tags (
    post_id     BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    tag_id      BIGINT NOT NULL REFERENCES tags(id),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_post_tags PRIMARY KEY (post_id, tag_id)
);
CREATE INDEX idx_post_tags_tag ON post_tags (tag_id);

-- ----------------------------
-- 点赞表（同一用户对同一帖子只能点赞一次）
-- ----------------------------
CREATE TABLE likes (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    post_id     BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_likes_user_post UNIQUE (user_id, post_id)
);
CREATE INDEX idx_likes_post ON likes (post_id);

-- ----------------------------
-- 收藏表（同一用户对同一帖子只能收藏一次）
-- ----------------------------
CREATE TABLE favorites (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    post_id     BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_favorites_user_post UNIQUE (user_id, post_id)
);
CREATE INDEX idx_favorites_user ON favorites (user_id);
CREATE INDEX idx_favorites_post ON favorites (post_id);

-- ----------------------------
-- 通知表
-- ----------------------------
CREATE TABLE notifications (
    id           BIGSERIAL PRIMARY KEY,
    recipient_id BIGINT NOT NULL REFERENCES users(id),
    sender_id    BIGINT REFERENCES users(id),
    post_id      BIGINT REFERENCES posts(id),
    type         VARCHAR(20) NOT NULL,               -- REPLY / LIKE / FAVORITE / SYSTEM
    content      VARCHAR(500),
    is_read      BOOLEAN NOT NULL DEFAULT FALSE,
    create_time  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_notifications_recipient ON notifications (recipient_id, is_read);
