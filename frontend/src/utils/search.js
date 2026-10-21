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
 * 该折叠码元在原文里的起点，`endMap[k]` 是它所在原文码点的终点；命中区间的右端用
 * `endMap[末位]` 回推。`map` 末尾额外压一个原文长度哨兵，当前切片不读它。
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

/** 行内代码占位符：正文入口先剥掉 NUL，因此 `\u0000<编号>\u0000` 不会与内容冲突 */
const PLACEHOLDER = '\u0000'

/** 围栏：3 个以上同字符、缩进不超过 3 空格；info string 只影响能否识别（见 fenceOpening） */
const FENCE_OPEN = /^ {0,3}(`{3,}|~{3,})(.*)$/
const FENCE_CLOSE = /^ {0,3}(`{3,}|~{3,})[ \t]*$/
/** 分割线：整行只有空格与 3 个以上 - / * / _（允许中间有空格） */
const THEMATIC_BREAK = /^ {0,3}(?:(?:\*[ \t]*){3,}|(?:-[ \t]*){3,}|(?:_[ \t]*){3,})$/
/** 表格分隔行：[可选 |] 单元格（冒号 + 连字符）[| 单元格…] [可选 |] */
const TABLE_SEPARATOR = /^ {0,3}\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$/
/** 图片先于链接：alt 文本保留，可选的 "标题"/'标题'/(标题) 一并丢弃 */
const IMAGE = /!\[([^\]]*)\]\(\s*(?:[^()\s]|\([^()]*\))*(?:\s+(?:"[^"]*"|'[^']*'|\([^()]*\)))?\s*\)/g
/** 链接：只留文字（URL 允许一层成对括号，可选标题同样丢弃） */
const LINK = /\[([^\]]*)\]\(\s*(?:[^()\s]|\([^()]*\))*(?:\s+(?:"[^"]*"|'[^']*'|\([^()]*\)))?\s*\)/g
/** 自动链接：<https://...> / <mailto:...> 与 <a@b.c>，保留链接文本 */
const AUTOLINK_URL = /<((?:https?:\/\/|mailto:)[^>\s]+)>/gi
const AUTOLINK_EMAIL = /<([^<>\s@]+@[^<>\s@]+\.[^<>\s@]+)>/g
/** 原始 HTML 标签：标签名后必须是空白、/ 或 >，避免把 <foo@bar.example.com> 当成标签删掉 */
const HTML_TAG = /<\/?[a-zA-Z][a-zA-Z0-9-]*(?:\s[^>]*)?\/?>/g

/**
 * 识别围栏起始行。反引号围栏的 info string 里不允许再出现反引号（CommonMark），
 * 否则这一行不是围栏而是单行行内代码，例如 ```` `code` ````。
 */
function fenceOpening(line) {
  const match = FENCE_OPEN.exec(line)
  if (!match) return null
  if (match[1][0] === '`' && match[2].includes('`')) return null
  return { char: match[1][0], length: match[1].length }
}

/** 闭合围栏必须与起始围栏同字符，长度不短于起始围栏，且除缩进/尾随空白外没有内容 */
function fenceClosing(line, opening) {
  const match = FENCE_CLOSE.exec(line)
  return Boolean(match) && match[1][0] === opening.char && match[1].length >= opening.length
}

/** 先按行扫围栏代码块：闭合段整段丢弃；未闭合段丢弃到文末（近似处理） */
function stripFencedCode(lines) {
  const kept = []
  for (let i = 0; i < lines.length; ) {
    const opening = fenceOpening(lines[i])
    if (!opening) {
      kept.push(lines[i])
      i += 1
      continue
    }
    let close = i + 1
    while (close < lines.length && !fenceClosing(lines[close], opening)) close += 1
    if (close >= lines.length) break
    i = close + 1
  }
  return kept
}

function isTableSeparator(line) {
  return line.includes('|') && TABLE_SEPARATOR.test(line)
}

/**
 * 按行去掉整行分割线与表格块。
 * 只有整行都是 - / * / _ 才是分割线，`5 - - - 6` 这种行内连字符要原样保留；
 * 只有「表头行 + 分隔行」才进入表格块，块内管道符折叠为空格，块外管道符原样保留。
 */
function stripBlockMarkers(text) {
  const lines = text.split('\n')
  const kept = []
  for (let i = 0; i < lines.length; ) {
    if (THEMATIC_BREAK.test(lines[i])) {
      i += 1
      continue
    }
    if (i + 1 < lines.length && lines[i].includes('|') && isTableSeparator(lines[i + 1])) {
      kept.push(lines[i].replace(/\|/g, ' '))
      i += 2
      while (i < lines.length && lines[i].includes('|')) {
        kept.push(lines[i].replace(/\|/g, ' '))
        i += 1
      }
      continue
    }
    kept.push(lines[i])
    i += 1
  }
  return kept.join('\n')
}

/**
 * 行内代码用占位符保护起来，避免后续的链接/HTML/强调/管道清洗误伤代码里的字面量。
 * 返回 `{ text, codes }`，`codes[n]` 是第 n 段代码的内容（已按 CommonMark 去掉两端各
 * 一个空格）。未闭合的反引号串原样保留；空行是段落边界，行内代码不跨段配对（近似处理）。
 */
function protectInlineCode(text) {
  const codes = []
  let out = ''
  let i = 0
  while (i < text.length) {
    if (text[i] !== '`') {
      out += text[i]
      i += 1
      continue
    }
    let width = 0
    while (text[i + width] === '`') width += 1
    let cursor = i + width
    let close = -1
    while (cursor < text.length) {
      if (text[cursor] === '\n' && text[cursor + 1] === '\n') break
      if (text[cursor] !== '`') {
        cursor += 1
        continue
      }
      let run = 0
      while (text[cursor + run] === '`') run += 1
      if (run === width) {
        close = cursor
        break
      }
      cursor += run
    }
    if (close === -1) {
      out += text.slice(i, i + width)
      i += width
      continue
    }
    let inner = text.slice(i + width, close)
    if (inner.length >= 2 && inner.startsWith(' ') && inner.endsWith(' ') && inner.trim() !== '') {
      inner = inner.slice(1, -1)
    }
    out += PLACEHOLDER + codes.length + PLACEHOLDER
    codes.push(inner)
    i = close + width
  }
  return { text: out, codes }
}

/** 还原行内代码占位符；兜底剥掉任何残留的 NUL，保证占位符不泄漏到片段里 */
function restoreInlineCode(text, codes) {
  return text
    .replace(/\u0000(\d+)\u0000/g, (_, index) => codes[Number(index)] ?? '')
    .replace(/\u0000/g, '')
}

/**
 * 轻量清洗 Markdown，只保留适合「一行片段」展示的纯文本。
 *
 * 这是面向搜索片段的近似清洗，不是完整的 Markdown 解析器：setext 标题、嵌套列表、
 * 四空格缩进的代码块、嵌套引用（只去一层 `>`）、转义写法等边角情况不保证与渲染结果
 * 一致。这里刻意不走 markdown-it 的渲染流程——它已经是帖子详情页的渲染依赖，但本工具
 * 只需要纯文本；先渲染成 <p>/<strong> 再剥标签更慢，也容易漏标签。
 *
 * 步骤顺序有依赖：先按行扫掉围栏代码块、整行分割线与表格块；行内代码用占位符先保护
 * 起来（避免代码里的强调/管道被误伤），图片先于链接，自动链接先于通用 HTML 标签；最后
 * 还原代码内容并折叠空白。
 */
function plainText(markdown) {
  // NUL 只用于内部占位：入口先剥掉正文里的 NUL，保证占位符不会与内容冲突
  const source = String(markdown ?? '')
    .replace(/\u0000/g, '')
    .replace(/\r\n?/g, '\n')
  const { text, codes } = protectInlineCode(stripFencedCode(source.split('\n')).join('\n'))
  const cleaned = stripBlockMarkers(text)
    // 图片先于链接：只留 alt 文本（URL 允许一层成对括号，可选标题一并丢弃）
    .replace(IMAGE, '$1')
    // 链接：只留文字（URL 里的一层括号不会被截断，不留下多余的 ')'）
    .replace(LINK, '$1')
    // 自动链接：保留链接文本，必须先于通用 HTML 标签剥离
    .replace(AUTOLINK_URL, '$1')
    .replace(AUTOLINK_EMAIL, '$1')
    // 原始 HTML 标签：替换为空格，保留标签之间的可见文字
    .replace(HTML_TAG, ' ')
    // 强调符号：先成对双标记，再成对单标记（粗体/斜体/删除线）。
    // 下划线遵循 CommonMark 的 intraword 规则（`a_b_c` 不是强调）；星号允许词内强调。
    .replace(/~~(?=\S)([^~]+?)(?<=\S)~~/g, '$1')
    .replace(/\*\*(?=\S)([^*]+?)(?<=\S)\*\*/g, '$1')
    .replace(/(?<![\p{L}\p{N}])__(?=\S)([^_]+?)(?<=\S)__(?![\p{L}\p{N}])/gu, '$1')
    .replace(/\*(?=\S)([^*\n]+?)(?<=\S)\*/g, '$1')
    .replace(/(?<![\p{L}\p{N}])_(?=\S)([^_\n]+?)(?<=\S)_(?![\p{L}\p{N}])/gu, '$1')
    // 标题 / 引用 / 无序列表 / 有序列表前缀（逐行；只去一层引用，嵌套引用是近似局限）
    .replace(/^\s{0,3}#{1,6}\s+/gm, '')
    .replace(/^\s{0,3}>\s?/gm, '')
    .replace(/^\s{0,3}(?:[-*+]|\d+\.)\s+/gm, '')
  // 行内代码最后还原（强调/链接/HTML 清洗已完成，代码里的字面量不会再被误伤），
  // 再折叠空白，保证片段始终是单行
  return restoreInlineCode(cleaned, codes).replace(/\s+/g, ' ').trim()
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
