-- =====================================================================
-- JavaForum V4 : 用户 token 版本号（用于 JWT 主动失效）
-- 约定：登出 / 封禁时把 users.token_version + 1；
--       JWT 的 ver claim 与库中值不一致即视为失效 token。
-- 兼容：本迁移之前的 token 没有 ver claim，按 0 处理（不强制全员重登）。
-- =====================================================================

-- 已有用户回填为 0：与「无 ver 的旧 token 按 0」保持一致
ALTER TABLE users ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN users.token_version IS 'JWT 版本号：登出或封禁时 +1，旧 token 的 ver 不等于该值即失效';
