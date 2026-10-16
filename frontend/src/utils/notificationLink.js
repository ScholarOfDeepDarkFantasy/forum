/**
 * 把通知映射为路由目标。
 * 返回 { path, hash }，无法跳转时返回 null。
 *
 * REPLY 仅在 commentId 存在时才附锚点：V2 迁移新增的 comment_id 列为可空且未回填
 * （见 V2__notification_comment_id.sql），历史 REPLY 行该字段为 NULL，
 * 序列化时键会被省略，此时退化为只跳帖子。删掉该判断会产生
 * '#comment-undefined' 且静默失效。
 */
export function resolveNotificationTarget(notification) {
  if (!notification || !notification.postId) {
    return null
  }
  const path = `/posts/${notification.postId}`
  const hash =
    notification.type === 'REPLY' && notification.commentId
      ? `#comment-${notification.commentId}`
      : ''
  return { path, hash }
}
