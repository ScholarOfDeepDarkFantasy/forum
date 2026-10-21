import { describe, it, expect } from 'vitest'
import { highlightSegments, buildSnippet } from './search'

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
  })

  it('多处命中时逐段输出，相邻命中之间不产生空段', () => {
    expect(highlightSegments('aa-aa', 'aa')).toEqual([
      { text: 'aa', hit: true },
      { text: '-', hit: false },
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
    expect(() => highlightSegments('数组 [0] 越界', '[0]')).not.toThrow()
  })

  it('关键词等于整段时只有一段 hit', () => {
    expect(highlightSegments('Java', 'java')).toEqual([{ text: 'Java', hit: true }])
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
})
