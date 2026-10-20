/**
 * 头像地址解析：后端上传的头像存的是站内绝对路径 /uploads/avatars/xxx.jpg。
 * 子路径部署（/forum/）时必须补上 BASE_URL，否则请求会打到域名根而 404。
 * 外链、data URL 等历史数据原样返回。
 */
export function resolveAvatarUrl(url) {
  if (!url) return ''
  if (url.startsWith('/uploads/')) return `${import.meta.env.BASE_URL}${url.slice(1)}`
  return url
}
