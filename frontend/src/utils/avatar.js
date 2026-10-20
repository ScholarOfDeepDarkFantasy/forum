/**
 * 头像地址解析：后端上传的头像存的是站内绝对路径 /uploads/avatars/xxx.jpg。
 * 子路径部署（/forum/）时必须补上 BASE_URL，否则请求会打到域名根而 404。
 * 外链、data URL 等历史数据原样返回。
 * base 默认取 import.meta.env.BASE_URL，显式传入是为了能用固定基址直接测子路径部署。
 */
export function resolveAvatarUrl(url, base = import.meta.env.BASE_URL) {
  if (!url) return ''
  const path = String(url).trim()
  if (!path) return ''
  // 只有前缀而没有具体文件段（'/uploads' 或 '/uploads/'）说明没有可用头像，
  // 拼出来只会是一个必然 404 的地址。
  if (path === '/uploads' || path === '/uploads/') return ''
  if (path.startsWith('/uploads/')) return `${base}${path.slice(1)}`
  return path
}
