-- 全文搜索：pg_trgm 三元组索引（PostgreSQL）
-- 说明：V3 只能跑在 PostgreSQL 上；H2 测试统一关闭 Flyway（见下），不会执行本迁移。
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_posts_title_trgm
    ON posts USING gin (LOWER(title) gin_trgm_ops)
    WHERE deleted = false;

CREATE INDEX IF NOT EXISTS idx_posts_content_trgm
    ON posts USING gin (LOWER(content) gin_trgm_ops)
    WHERE deleted = false;
