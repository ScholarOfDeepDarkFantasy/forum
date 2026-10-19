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

  it('原始 HTML 中的事件属性不会生效', () => {
    const div = parse(
      renderMarkdown('<img src=x onerror="alert(1)"> <a href="https://e.com" onmouseover="x">a</a>')
    )
    expect(div.querySelector('[onerror]')).toBeNull()
    expect(div.querySelector('[onmouseover]')).toBeNull()
    expect(div.innerHTML).toContain('&lt;img')
  })

  it('未注册语言按纯文本输出且不抛错', () => {
    const html = renderMarkdown('```kotlin\nfun main() {}\n```')
    expect(html).toContain('language-kotlin')
    expect(html).toContain('fun main')
  })

  it('裸链接被自动识别为外链', () => {
    const html = renderMarkdown('访问 https://example.com 查看')
    expect(html).toContain('href="https://example.com"')
    expect(html).toContain('target="_blank"')
  })

  it('站内、锚点与邮件链接不补 target', () => {
    const div = parse(renderMarkdown('[站内](/posts/1) [锚点](#section) [邮件](mailto:a@b.com)'))
    expect(div.querySelectorAll('a')).toHaveLength(3)
    expect(div.querySelector('a[target]')).toBeNull()
  })

  it('协议相对链接也按外链处理', () => {
    const div = parse(renderMarkdown('[x](//evil.example/a)'))
    const a = div.querySelector('a')
    expect(a).not.toBeNull()
    expect(a.getAttribute('target')).toBe('_blank')
    expect(a.getAttribute('rel')).toBe('noopener noreferrer')
  })

  it('表格包在横向滚动容器里', () => {
    const div = parse(renderMarkdown('| a | b |\n| - | - |\n| 1 | 2 |'))
    expect(div.querySelector('.table-wrap table')).not.toBeNull()
  })

  it('无语言代码块中的 HTML 被转义', () => {
    const html = renderMarkdown('```\n<b>x</b>\n```')
    expect(html).toContain('&lt;b&gt;')
  })

  it('javascript: 链接不会变成可点击链接', () => {
    const html = renderMarkdown('[点我](javascript:alert(1))')
    expect(html).not.toMatch(/href\s*=\s*["']?\s*javascript:/i)
    expect(html).not.toContain('<a ')
    expect(html).toContain('点我')
  })
})
