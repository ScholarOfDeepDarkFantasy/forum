import { describe, it, expect } from 'vitest'
import { highlightSegments, buildSnippet } from './search'

function isHighSurrogate(code) {
  return code >= 0xd800 && code <= 0xdbff
}

function isLowSurrogate(code) {
  return code >= 0xdc00 && code <= 0xdfff
}

/** 检测字符串里是否有落单的代理项（切成两半的 emoji/生僻字） */
function hasLoneSurrogate(value) {
  for (let i = 0; i < value.length; i += 1) {
    const code = value.charCodeAt(i)
    if (isHighSurrogate(code)) {
      if (!isLowSurrogate(value.charCodeAt(i + 1))) return true
      i += 1
    } else if (isLowSurrogate(code)) {
      return true
    }
  }
  return false
}

describe('highlightSegments', () => {
  it('空文本或空关键词返回单段，hit 为 false', () => {
    expect(highlightSegments('', 'java')).toEqual([{ text: '', hit: false }])
    expect(highlightSegments('Java 帖子', '')).toEqual([{ text: 'Java 帖子', hit: false }])
    expect(highlightSegments(null, 'java')).toEqual([{ text: '', hit: false }])
  })

  it('命中在中间时切成三段，保留原文大小写', () => {
    expect(highlightSegments('前面 Java 后面', 'java')).toEqual([
      { text: '前面 ', hit: false },
      { text: 'Java', hit: true },
      { text: ' 后面', hit: false }
    ])
  })

  it('大小写不敏感，命中段用原文切片而不是关键词', () => {
    const segments = highlightSegments('JAVA 与 java', 'Java')
    expect(segments.filter((s) => s.hit).map((s) => s.text)).toEqual(['JAVA', 'java'])
    // 大小写混排的多处命中同样按原文输出
    expect(
      highlightSegments('Java JAVA java', 'jAvA')
        .filter((s) => s.hit)
        .map((s) => s.text)
    ).toEqual(['Java', 'JAVA', 'java'])
  })

  it('多处命中时逐段输出，相邻命中之间不产生空段', () => {
    expect(highlightSegments('aa-aa', 'aa')).toEqual([
      { text: 'aa', hit: true },
      { text: '-', hit: false },
      { text: 'aa', hit: true }
    ])
    // 相邻命中（无间隔）也要逐段输出，不能合并或漏掉第二处
    expect(highlightSegments('aaaa', 'aa')).toEqual([
      { text: 'aa', hit: true },
      { text: 'aa', hit: true }
    ])
  })

  it('无命中返回单段', () => {
    expect(highlightSegments('完全无关', 'java')).toEqual([{ text: '完全无关', hit: false }])
  })

  it('关键词里的正则元字符按字面匹配，不抛异常', () => {
    expect(highlightSegments('计算 a+b 与 (x)', 'a+b')).toEqual([
      { text: '计算 ', hit: false },
      { text: 'a+b', hit: true },
      { text: ' 与 (x)', hit: false }
    ])
    const segments = highlightSegments('数组 [0] 越界', '[0]')
    expect(() => highlightSegments('数组 [0] 越界', '[0]')).not.toThrow()
    expect(segments).toEqual([
      { text: '数组 ', hit: false },
      { text: '[0]', hit: true },
      { text: ' 越界', hit: false }
    ])
  })

  it('关键词等于整段时只有一段 hit', () => {
    expect(highlightSegments('Java', 'java')).toEqual([{ text: 'Java', hit: true }])
  })

  it('大小写折叠改变长度时仍按原文下标切片，不丢字符', () => {
    // 'İ'.toLowerCase() === 'i̇'（两个 UTF-16 码元），用折叠串下标切原文会错位
    expect(highlightSegments('İx', 'İ')).toEqual([
      { text: 'İ', hit: true },
      { text: 'x', hit: false }
    ])
    expect(highlightSegments('İİİİİx', 'x')).toEqual([
      { text: 'İİİİİ', hit: false },
      { text: 'x', hit: true }
    ])
    // 分段首尾相接必须还原原文，且命中段是原文而不是折叠结果
    const segments = highlightSegments('İİİİİx', 'x')
    expect(segments.map((s) => s.text).join('')).toBe('İİİİİx')
    expect(segments[1].text).toBe('x')
  })
})

describe('buildSnippet', () => {
  it('清洗 Markdown 标记：标题、引用、列表、行内代码、链接、图片只留文字', () => {
    const snippet = buildSnippet(
      '# 标题\n> 引用\n- 列表项\n`code`\n[链接文字](https://example.com)\n![图片说明](/a.png)',
      ''
    )

    expect(snippet).toBe('标题 引用 列表项 code 链接文字 图片说明')
    expect(snippet).not.toContain('#')
    expect(snippet).not.toContain('](')
  })

  it('代码围栏整段丢弃，避免代码里的符号混进片段', () => {
    const snippet = buildSnippet('说明文字\n```java\nint a = 1;\n```\n结尾', '')

    expect(snippet).toBe('说明文字 结尾')
    expect(snippet).not.toContain('int a')
  })

  it('换行与连续空白折叠为单个空格', () => {
    expect(buildSnippet('第一行\n\n第二行\t\t第三段', '')).toBe('第一行 第二行 第三段')
  })

  it('CRLF 换行同样折叠为单个空格', () => {
    expect(buildSnippet('# 标题\r\n> 引用\r\n- 列表项', '')).toBe('标题 引用 列表项')
    expect(buildSnippet('第一行\r\n\r\n第二行', '')).toBe('第一行 第二行')
  })

  it('空正文返回空串', () => {
    expect(buildSnippet('', 'java')).toBe('')
    expect(buildSnippet(null, 'java')).toBe('')
  })

  it('有命中时以命中为中心截窗口，两端补省略号', () => {
    const content = 'A'.repeat(100) + '关键词' + 'B'.repeat(100)

    const snippet = buildSnippet(content, '关键词', 10)

    expect(snippet.startsWith('…')).toBe(true)
    expect(snippet.endsWith('…')).toBe(true)
    expect(snippet).toContain('关键词')
    // radius=10：单侧最多 10 个字符，窗口远小于清洗前的 203 字符
    expect(snippet.length).toBeLessThan(40)
  })

  it('无命中时取开头窗口，超出长度才补尾部省略号', () => {
    const content = 'C'.repeat(200)

    const snippet = buildSnippet(content, '无关词', 10)

    expect(snippet.startsWith('C')).toBe(true)
    expect(snippet.endsWith('…')).toBe(true)
    expect(buildSnippet('短正文', '无关词', 10)).toBe('短正文')
  })

  it('统计口径不区分大小写：大写关键词也能定位到小写正文', () => {
    const snippet = buildSnippet('前面 java 后面', 'JAVA', 20)

    expect(snippet).toBe('前面 java 后面')
  })

  it('大小写折叠改变长度时片段不出现乱码，窗口下标映射回原文', () => {
    expect(buildSnippet('İİİİİx', 'x', 2)).toBe('…İİx')
    expect(buildSnippet('İx', 'İ', 10)).toBe('İx')
    expect(buildSnippet('İİİİİx', 'x', 2)).not.toMatch(/\uFFFD/)
  })

  it('进一步清洗强调、分割线、表格、原始 HTML 与自动链接', () => {
    expect(buildSnippet('**粗体** ---', '')).toBe('粗体')
    expect(buildSnippet('~~删除~~ __强调__ *斜体* _倾斜_', '')).toBe('删除 强调 斜体 倾斜')
    expect(buildSnippet('前\n***\n中\n___\n后', '')).toBe('前 中 后')
    expect(buildSnippet('| 名称 | 数量 |\n| --- | --- |\n| 苹果 | 3 |', '')).toBe(
      '名称 数量 苹果 3'
    )
    expect(buildSnippet('<div class="x">红色</div>文本', '')).toBe('红色 文本')
    expect(buildSnippet('<https://a.b>', '')).toBe('https://a.b')
  })

  it('4 个及以上反引号的围栏与未闭合围栏都整段丢弃', () => {
    expect(buildSnippet('说明\n````md\n```js\ncode\n```\n````\n结尾', '')).toBe('说明 结尾')
    expect(buildSnippet('开头\n```js\nint a = 1;', '')).toBe('开头')
    expect(buildSnippet('开头\n````\nint a = 1;', '')).toBe('开头')
  })

  it('链接 URL 里的一层成对括号不会留下多余的右括号', () => {
    expect(buildSnippet('[text](https://e.com/a_(b))', '')).toBe('text')
    expect(buildSnippet('![图](https://e.com/a_(b))', '')).toBe('图')
  })

  it('radius 非有限值回退默认值，负数按 0 处理', () => {
    const long = 'A'.repeat(100) + '关键词' + 'B'.repeat(100)

    expect(buildSnippet(long, '关键词', NaN)).toBe(buildSnippet(long, '关键词'))
    expect(buildSnippet(long, '关键词', Infinity)).toBe(buildSnippet(long, '关键词'))
    expect(buildSnippet(long, '关键词', undefined)).toBe(buildSnippet(long, '关键词'))
    expect(buildSnippet('前面 keyword 后面', 'keyword', -5)).toBe('…keyword…')
  })

  it('radius 为 0 时只保留关键词本身，无命中返回空串', () => {
    expect(buildSnippet('前面 keyword 后面', 'keyword', 0)).toBe('…keyword…')
    expect(buildSnippet('keyword 后面', 'keyword', 0)).toBe('keyword…')
    expect(buildSnippet('x'.repeat(10), '无命中', 0)).toBe('')
  })

  it('命中在开头或结尾时窗口收敛到文本边界', () => {
    expect(buildSnippet('关键词' + 'B'.repeat(100), '关键词', 5)).toBe('关键词BBBBB…')
    expect(buildSnippet('A'.repeat(100) + '关键词', '关键词', 5)).toBe('…AAAAA关键词')
  })

  it('窗口边界不劈开代理对', () => {
    const snippet = buildSnippet('a' + '😀'.repeat(5), '', 2)

    expect(snippet).toBe('a😀😀…')
    expect(hasLoneSurrogate(snippet)).toBe(false)
  })
})
