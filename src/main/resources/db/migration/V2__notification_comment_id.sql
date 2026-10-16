-- =====================================================================
-- JavaForum V2 : 通知表补 comment_id，使 REPLY 通知可精确锚定评论
-- =====================================================================

-- 说明：comment_id 可空且无默认值，历史行的 REPLY 通知保持 NULL，无需回填
-- （历史通知未记录被回复评论，无法可靠推断）。ON DELETE SET NULL 与硬删除路径兼容。
ALTER TABLE notifications
    ADD COLUMN comment_id BIGINT
        CONSTRAINT fk_notifications_comment REFERENCES comments(id) ON DELETE SET NULL;

CREATE INDEX idx_notifications_comment ON notifications (comment_id);
