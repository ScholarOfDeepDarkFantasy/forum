-- =====================================================================
-- JavaForum V2 : 通知表补 comment_id，使 REPLY 通知可精确锚定评论
-- =====================================================================

ALTER TABLE notifications
    ADD COLUMN comment_id BIGINT REFERENCES comments(id) ON DELETE SET NULL;

CREATE INDEX idx_notifications_comment ON notifications (comment_id);
