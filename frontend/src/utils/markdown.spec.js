import { describe, it, expect } from 'vitest'
import { renderMarkdown } from '@/utils/markdown'

/** 只关心「渲染出的 DOM 行为」，用容器解析后断言结构 */
function parse(html) {
  const div = document.createElement('div')
  div.innerHTML = html
  return div
}

describe('renderMarkdown', () => {
  it('空内容返回空串', () => {
    expect(renderMarkdown('')).toBe('')
    expect(renderMarkdown(null)).toBe('')
  })

  it('渲染 GFM 标题、列表与表格', () => {
    const html = renderMarkdown('## 标题\n\n- a\n- b\n\n| 列1 | 列2 |\n|---|---|\n| 1 | 2 |')
    expect(html).toContain('<h2>标题</h2>')
    expect(html).toContain('<li>a</li>')
    expect(html).toContain('<table>')
  })

  it('代码块带 language 类并高亮', () => {
    const html = renderMarkdown('```java\nint a = 1;\n```')
    expect(html).toContain('language-java')
    expect(html).toContain('hljs')
  })

  it('单换行转成 <br>（兼容历史纯文本帖子）', () => {
    expect(renderMarkdown('第一行\n第二行')).toContain('<br>')
  })

  it('外链补 target/rel，图片补 loading=lazy', () => {
    const div = parse(renderMarkdown('[外站](https://example.com) ![图](https://example.com/a.png)'))
    const a = div.querySelector('a')
    expect(a.getAttribute('target')).toBe('_blank')
    expect(a.getAttribute('rel')).toBe('noopener noreferrer')
    expect(div.querySelector('img').getAttribute('loading')).toBe('lazy')
  })

  it('原始 HTML 被转义而不是执行', () => {
    const html = renderMarkdown('<script>alert(1)</script>')
    expect(html).not.toContain('<script')
    expect(html).toContain('&lt;script&gt;')
  })

  it('img onerror 之类的事件属性被清除', () => {
    const html = renderMarkdown('![x](https://example.com/a.png)')
    expect(html).not.toContain('onerror')
  })

  it('javascript: 链接不会变成可点击链接', () => {
    const html = renderMarkdown('[点我](javascript:alert(1))')
    expect(html).not.toMatch(/href\s*=\s*["']?\s*javascript:/i)
    expect(html).not.toContain('<a ')
    expect(html).toContain('点我')
  })
})
