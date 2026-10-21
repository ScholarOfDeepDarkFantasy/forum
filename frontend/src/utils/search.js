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

/**
 * 大小写折叠并记录下标映射。
 *
 * 不能直接 `source.toLowerCase()` 后拿折叠串下标切原文：Unicode 折叠可能改变 UTF-16
 * 长度（例如 `'İ'.toLowerCase()` 是 'i' + 组合点，两个码元），长度一变下标就错位，
 * 切片会切出乱码。这里逐码点折叠，记录每个折叠码元对应的原文起止下标：`map[k]` 是
 * 该折叠码元在原文里的起点，`endMap[k]` 是它所在原文码点的终点；`map[folded.length]`
 * 等于原文长度，便于命中区间映射回原文后再切。
 */
function foldWithMap(text) {
  const source = String(text ?? '')
  const parts = []
  const map = []
  const endMap = []
  for (let i = 0; i < source.length; ) {
    const width = source.codePointAt(i) > 0xffff ? 2 : 1
    const lower = source.slice(i, i + width).toLowerCase()
    for (let k = 0; k < lower.length; k += 1) {
      map.push(i)
      endMap.push(i + width)
    }
    parts.push(lower)
    i += width
  }
  map.push(source.length)
  return { folded: parts.join(''), map, endMap }
}

/** 大小写折叠（关键词只需要折叠结果；与 foldWithMap 逐码点口径一致） */
function fold(text) {
  return foldWithMap(text).folded
}

function isHighSurrogate(code) {
  return code >= 0xd800 && code <= 0xdbff
}

function isLowSurrogate(code) {
  return code >= 0xdc00 && code <= 0xdfff
}

/**
 * 把文本按关键词切成若干段，命中段 hit=true。
 * 空文本、空关键词、无命中都返回单段（hit=false），调用方不必判空。
 * 命中区间通过 foldWithMap 的映射回到原文下标，因此 `İ` 这类折叠后变长的字符也能正确高亮。
 */
export function highlightSegments(text, keyword) {
  const source = String(text ?? '')
  const kw = String(keyword ?? '')
  if (!source || !kw) return [{ text: source, hit: false }]

  const { folded, map, endMap } = foldWithMap(source)
  const needle = fold(kw)
  if (!needle) return [{ text: source, hit: false }]

  const segments = []
  let from = 0
  let index = folded.indexOf(needle)
  while (index !== -1) {
    const start = map[index]
    const end = endMap[index + needle.length - 1]
    if (start > from) segments.push({ text: source.slice(from, start), hit: false })
    segments.push({ text: source.slice(start, end), hit: true })
    from = end
    index = folded.indexOf(needle, index + needle.length)
  }
  // 无命中时 from 仍是 0，这里会把整段原文作为未命中段返回，与显式判空等价
  if (from < source.length) segments.push({ text: source.slice(from), hit: false })
  return segments
}

/** 片段窗口半径：命中位置左右各保留的字符数 */
const DEFAULT_RADIUS = 40
const ELLIPSIS = '…'

/**
 * 轻量清洗 Markdown，只保留适合「一行片段」展示的纯文本。
 *
 * 这是面向搜索片段的近似清洗，不是完整的 Markdown 解析器：嵌套、转义、setext 标题等
 * 边角写法不保证与渲染结果一致。这里刻意不走 markdown-it 的渲染流程——它已经是帖子
 * 详情页的渲染依赖，但本工具只需要纯文本；先渲染成 <p>/<strong> 再剥标签更慢，也容易
 * 漏标签。步骤顺序有依赖：围栏先于行内代码，图片先于链接，自动链接先于通用 HTML 标签。
 */
function plainText(markdown) {
  return String(markdown ?? '')
    // 围栏代码块：闭合的整段丢弃；未闭合的从围栏行丢弃到文末（近似处理）
    .replace(/^ {0,3}(`{3,}|~{3,})[^\n]*\n[\s\S]*?^ {0,3}\1\s*$/gm, ' ')
    .replace(/^ {0,3}(?:`{3,}|~{3,})[^\n]*[\s\S]*/m, ' ')
    // 行内代码：只去掉标记，保留内容
    .replace(/`([^`]*)`/g, '$1')
    // 水平分割线（---、***、___，允许中间有空格）
    .replace(/(^|\s)([-*_])(?:\s*\2){2,}(?=\s|$)/g, ' ')
    // 表格分隔行（如 | --- | :---: |），先于管道符替换
    .replace(/^ {0,3}\|?(?:\s*:?-{3,}:?\s*\|)+(?:\s*:?-{3,}:?\s*)?$/gm, ' ')
    // 图片先于链接：只留 alt 文本（URL 允许一层成对括号）
    .replace(/!\[([^\]]*)\]\((?:[^()\s]|\([^()]*\))*\)/g, '$1')
    // 链接：只留文字（URL 里的一层括号不会被截断，不留下多余的 ')'）
    .replace(/\[([^\]]*)\]\((?:[^()\s]|\([^()]*\))*\)/g, '$1')
    // 自动链接 <https://...>：保留 URL 文本，必须先于通用 HTML 标签剥离
    .replace(/<((?:https?:\/\/|mailto:)[^>\s]+)>/gi, '$1')
    // 原始 HTML 标签：替换为空格，保留标签之间的可见文字
    .replace(/<\/?[a-zA-Z][^>]*>/g, ' ')
    // 强调符号：先成对双标记，再成对单标记（粗体/斜体/删除线）
    .replace(/~~(?=\S)([^~]+?)(?<=\S)~~/g, '$1')
    .replace(/\*\*(?=\S)([^*]+?)(?<=\S)\*\*/g, '$1')
    .replace(/__(?=\S)([^_]+?)(?<=\S)__/g, '$1')
    .replace(/\*(?=\S)([^*\n]+?)(?<=\S)\*/g, '$1')
    .replace(/_(?=\S)([^_\n]+?)(?<=\S)_/g, '$1')
    // 标题 / 引用 / 无序列表 / 有序列表前缀（逐行）
    .replace(/^\s{0,3}#{1,6}\s+/gm, '')
    .replace(/^\s{0,3}>\s?/gm, '')
    .replace(/^\s{0,3}(?:[-*+]|\d+\.)\s+/gm, '')
    // 表格管道符
    .replace(/\|/g, ' ')
    // 换行与连续空白折叠为单空格
    .replace(/\s+/g, ' ')
    .trim()
}

/**
 * 生成一行搜索片段：先清洗 Markdown，再按关键词定位窗口。
 * 有命中时以第一处命中为中心；无命中时取开头窗口（标题命中也能看到一点正文上下文）。
 * 截断处补省略号；窗口越界时自动收敛到文本边界，并吸附到 code point 边界，避免劈开
 * emoji/生僻字。radius 非有限值（undefined/NaN/Infinity）时回退 DEFAULT_RADIUS，负数
 * 按 0 处理；radius = 0 时无命中返回空串，有命中只保留关键词本身（两侧仍按是否截断补
 * 省略号）。
 */
export function buildSnippet(content, keyword, radius = DEFAULT_RADIUS) {
  const text = plainText(content)
  if (!text) return ''

  const limit = Number.isFinite(radius) ? Math.max(0, Math.floor(radius)) : DEFAULT_RADIUS
  const needle = fold(keyword)
  let pos = -1
  let hitEnd = -1
  if (needle) {
    const { folded, map, endMap } = foldWithMap(text)
    const index = folded.indexOf(needle)
    if (index !== -1) {
      pos = map[index]
      hitEnd = endMap[index + needle.length - 1]
    }
  }

  let start = 0
  let end = 0
  if (pos === -1) {
    end = Math.min(text.length, limit * 2)
  } else {
    start = Math.max(0, pos - limit)
    end = Math.min(text.length, hitEnd + limit)
  }
  // 命中窗口端点吸附到 code point 边界：start 落在低位代理上时回退到配对高位，
  // end 前一个码元是高位代理（说明切开了代理对）时把低位代理一并纳入。
  if (start > 0 && isLowSurrogate(text.charCodeAt(start))) start -= 1
  if (end > 0 && end < text.length && isHighSurrogate(text.charCodeAt(end - 1))) end += 1

  if (start === 0 && end === 0) return ''
  const head = start > 0 ? ELLIPSIS : ''
  const tail = end < text.length ? ELLIPSIS : ''
  return head + text.slice(start, end) + tail
}
