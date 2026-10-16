/**
 * 把通知映射为路由目标。
 * 返回 { path, hash }，无法跳转时返回 null。
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
