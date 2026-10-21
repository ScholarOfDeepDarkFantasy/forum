/**
 * 搜索关键词的高亮与片段工具。
 *
 * 为什么不用正则：关键词来自用户输入，`(`、`[`、`*`、`?` 等字符会改变正则语义，
 * 轻则高亮错位，重则 new RegExp 抛异常；拼正则前还得再转义一遍——那等于把
 * 「怎么切字符串」同时写成两套实现。indexOf 循环只依赖大小写折叠，没有元字符问题。
 *
 * 为什么返回 { text, hit } 数组而不是 HTML 字符串：模板里用文本节点 + <mark> 渲染，
 * 天然转义，不需要 v-html，也就没有反射型 XSS 的口子（搜索词会原样出现在 URL 里，
 * 是典型的可控输入）。
 */

/** 大小写折叠：只做 toLowerCase（关键词是中文/英文混排，不需要 locale 相关折叠） */
function fold(text) {
  return String(text ?? '').toLowerCase()
}

/**
 * 把文本按关键词切成若干段，命中段 hit=true。
 * 空文本、空关键词、无命中都返回单段（hit=false），调用方不必判空。
 */
export function highlightSegments(text, keyword) {
  const source = String(text ?? '')
  const kw = String(keyword ?? '')
  if (!source || !kw) return [{ text: source, hit: false }]

  const segments = []
  const haystack = fold(source)
  const needle = fold(kw)
  let from = 0
  let index = haystack.indexOf(needle)
  while (index !== -1) {
    if (index > from) segments.push({ text: source.slice(from, index), hit: false })
    segments.push({ text: source.slice(index, index + needle.length), hit: true })
    from = index + needle.length
    index = haystack.indexOf(needle, from)
  }
  if (!segments.length) return [{ text: source, hit: false }]
  if (from < source.length) segments.push({ text: source.slice(from), hit: false })
  return segments
}

/** 片段窗口半径：命中位置左右各保留的字符数 */
const DEFAULT_RADIUS = 40
const ELLIPSIS = '…'

/**
 * 轻量清洗 Markdown，只保留适合「一行片段」展示的纯文本。
 * 刻意不引入 markdown-it：这里不需要渲染，只需要把标记符号去掉；
 * 用渲染器反而会先生成 <p>/<strong> 再剥一次标签，成本更高且可能漏标签。
 */
function plainText(markdown) {
  return String(markdown ?? '')
    // 代码围栏：整段（含内容）替换为空格，避免代码里的符号干扰片段
    .replace(/```[\s\S]*?```/g, ' ')
    // 行内代码：只去掉标记，保留内容
    .replace(/`([^`]*)`/g, '$1')
    // 图片：只留 alt 文本
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    // 链接：只留文字
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    // 标题 / 引用 / 无序列表 / 有序列表前缀（逐行）
    .replace(/^\s{0,3}#{1,6}\s+/gm, '')
    .replace(/^\s{0,3}>\s?/gm, '')
    .replace(/^\s{0,3}(?:[-*+]|\d+\.)\s+/gm, '')
    // 换行与连续空白折叠为单空格
    .replace(/\s+/g, ' ')
    .trim()
}

/**
 * 生成一行搜索片段：先清洗 Markdown，再按关键词定位窗口。
 * 有命中时以第一处命中为中心；无命中时取开头窗口（标题命中也能看到一点正文上下文）。
 * 截断处补省略号；命中窗口越界时自动收敛到文本边界。
 */
export function buildSnippet(content, keyword, radius = DEFAULT_RADIUS) {
  const text = plainText(content)
  if (!text) return ''
  const kw = String(keyword ?? '')
  const pos = kw ? fold(text).indexOf(fold(kw)) : -1
  const start = pos > radius ? pos - radius : 0
  const end = pos === -1 ? Math.min(text.length, radius * 2) : Math.min(text.length, pos + kw.length + radius)
  const head = start > 0 ? ELLIPSIS : ''
  const tail = end < text.length ? ELLIPSIS : ''
  return head + text.slice(start, end) + tail
}
