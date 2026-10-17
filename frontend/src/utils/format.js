/**
 * 后端时间字段是 LocalDateTime 序列化后的 "2026-10-17T10:23:34.827384"（无时区）。
 * 直接 new Date() 解析会按浏览器本地时区补偏移，这里统一按字符串截取，避免时区漂移。
 *
 * 不用 Intl.DateTimeFormat：那会把无时区字符串当本地时间再格式化，跨时区用户看到的
 * 会是"自己时区的那一刻"，而数据库里存的是服务器的本地时间，两者对不上。
 */
export function formatDateTime(value) {
  if (!value) return ''
  return String(value).replace('T', ' ').slice(0, 16)
}

const MINUTE = 60 * 1000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

/**
 * 相对时间：1 分钟内「刚刚」，1 小时内「N 分钟前」，1 天内「N 小时前」，再久回退到具体日期。
 * now 参数只为测试可注入，生产调用省略。
 *
 * 未来时间（时钟偏差、通知刚生成）一律显示「刚刚」：diff 为负会让
 * Math.floor 得到 -0 或负数，出现「-1 分钟前」这种明显是 bug 的文案。
 */
export function formatRelative(value, now = Date.now()) {
  if (!value) return ''
  const then = new Date(value).getTime()
  if (Number.isNaN(then)) return formatDateTime(value)
  const diff = now - then
  if (diff < MINUTE) return '刚刚'
  if (diff < HOUR) return `${Math.floor(diff / MINUTE)} 分钟前`
  if (diff < DAY) return `${Math.floor(diff / HOUR)} 小时前`
  return formatDateTime(value)
}
