# Markdown 渲染 + 标签检索 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 帖子正文支持 GFM Markdown（编辑页实时分栏预览、详情页渲染），并新增标签总览页与标签帖子页。

**Architecture:** 前端读时渲染（`markdown-it` + `DOMPurify` + `highlight.js`，单文件工具 `utils/markdown.js` 供详情页与编辑页预览共用）；后端新增只读标签接口，标签帖子列表复用现有「两步分页」管线（`searchIds`/`searchByTagName` → `findWithAssociations` → `hydrate + mapSummaries`），不做数据库迁移，不引新存储。

**Tech Stack:** Spring Boot 3.5 + Spring Data JPA（JPQL 聚合）+ H2（测试）/ PostgreSQL（运行）；Vue 3.5 + Vite 8 + Element Plus + Vitest + @vue/test-utils；新增前端依赖 `markdown-it`、`dompurify`、`highlight.js`。

**约定：** 本仓库历史轮次均直接提交 `master`，push 后由 raspi 上的 Jenkins `pollSCM`（H/5）自动构建部署到 `http://raspi/forum/`。本计划沿用该约定，不另开 worktree；每个任务结束都提交一次，任务 7 统一 push 并验证部署。

**设计文档：** `docs/superpowers/specs/2026-10-19-round3-markdown-tags-design.md`（提交 30b045e）

---

## 文件结构总览

### 后端（新增）

| 文件 | 职责 |
|---|---|
| `src/main/java/org/song/forum/dto/tag/TagResponse.java` | 标签总览项：`id`、`name`、`postCount` |
| `src/main/java/org/song/forum/service/TagService.java` | 标签聚合查询、排序、服务层分页 |
| `src/main/java/org/song/forum/controller/TagController.java` | `GET /api/tags`、`GET /api/tags/posts` |
| `src/test/java/org/song/forum/controller/TagFlowIntegrationTest.java` | 标签接口集成测试（H2 + 随机端口） |

### 后端（修改）

| 文件 | 改动 |
|---|---|
| `src/main/java/org/song/forum/repository/TagRepository.java` | 新增聚合查询 `findWithPostCounts` |
| `src/main/java/org/song/forum/repository/PostRepository.java` | 新增 `searchByTagName` |
| `src/main/java/org/song/forum/service/PostService.java` | 新增 `listByTag`（复用私有管线） |
| `src/main/java/org/song/forum/config/SecurityConfig.java` | `PUBLIC_GET` 显式加入 `/api/tags/**` |
| `src/test/java/org/song/forum/repository/RepositoryTests.java` | 新增标签聚合查询用例 |

### 前端（新增）

| 文件 | 职责 |
|---|---|
| `frontend/src/utils/markdown.js` | `renderMarkdown`：GFM → 消毒 HTML（详情页与预览共用） |
| `frontend/src/styles/markdown.css` | `.markdown-body` 排版样式（复用 `--jf-*` 令牌） |
| `frontend/src/views/TagsView.vue` | 标签总览（过滤、标签云、分页） |
| `frontend/src/views/TagPostsView.vue` | 单标签帖子列表（分页、空状态） |
| `frontend/src/components/PostListItem.vue` | 首页/标签页共用的帖子行 |
| `frontend/src/utils/markdown.spec.js` | 渲染 + XSS 单测 |
| `frontend/src/views/TagsView.spec.js` / `TagPostsView.spec.js` | 标签页面单测 |
| `frontend/src/components/PostListItem.spec.js` | 帖子行单测 |
| `frontend/src/views/PostEditView.spec.js` | 编辑页预览单测 |

### 前端（修改）

| 文件 | 改动 |
|---|---|
| `frontend/src/main.js` | 引入 `markdown.css` 与 highlight.js github 主题 |
| `frontend/src/api/index.js` | 新增 `tagApi` |
| `frontend/src/router/index.js` | 新增 `/tags`、`/tags/:name` |
| `frontend/src/App.vue` | 头部新增「标签」入口 |
| `frontend/src/views/PostDetailView.vue` | 正文 Markdown 渲染；标签可点击 |
| `frontend/src/views/PostEditView.vue` | 正文改分栏/预览 tab |
| `frontend/src/views/HomeView.vue` | 帖子行改用 `PostListItem`；标签可点击 |
| `frontend/src/views/PostDetailView.spec.js`、`HomeView.spec.js` | 适配新渲染与组件 |
| `frontend/package.json` / `pnpm-lock.yaml` | 新增三个依赖 |

### 文档

| 文件 | 改动 |
|---|---|
| `README.md` | 功能列表、已知取舍 |
| `docs/DEVELOPMENT.md` | 版本 1.3、接口表、§4 本轮记录、§5、§3 基线、§8 变更日志 |

---

## Task 1: Markdown 渲染工具与样式

**Files:**
- Create: `frontend/src/utils/markdown.js`
- Create: `frontend/src/styles/markdown.css`
- Modify: `frontend/src/main.js`
- Test: `frontend/src/utils/markdown.spec.js`

> 说明：markdown.css 使用 --jf-color-text-3（--jf-color-text-2 已在第二轮移除，不再新增令牌）。

- [x] **Step 1: 安装依赖**

```bash
cd frontend
pnpm add markdown-it dompurify highlight.js
```

Expected: `package.json` 的 `dependencies` 出现三个包，`pnpm-lock.yaml` 更新。

- [x] **Step 2: 先写失败的测试**

创建 `frontend/src/utils/markdown.spec.js`：

```js
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
```

markdown-it 默认校验链接协议：javascript: 链接不会生成 <a>，断言的是真实安全属性（无可执行链接）而不是输出中不出现该字符串。

- [x] **Step 3: 运行测试确认失败**

Run: `cd frontend && pnpm exec vitest run src/utils/markdown.spec.js`
Expected: FAIL，`Failed to resolve import "@/utils/markdown"`（文件还不存在）。

- [x] **Step 4: 实现渲染工具**

创建 `frontend/src/utils/markdown.js`：

```js
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/core'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import typescript from 'highlight.js/lib/languages/typescript'
import bash from 'highlight.js/lib/languages/bash'
import json from 'highlight.js/lib/languages/json'
import xml from 'highlight.js/lib/languages/xml'
import css from 'highlight.js/lib/languages/css'
import sql from 'highlight.js/lib/languages/sql'
import python from 'highlight.js/lib/languages/python'
import yaml from 'highlight.js/lib/languages/yaml'
import markdown from 'highlight.js/lib/languages/markdown'

// 只注册常用语言：highlight.js 全量入口会把所有语言打进包体
const LANGUAGES = {
  java,
  javascript,
  typescript,
  bash,
  json,
  xml,
  css,
  sql,
  python,
  yaml,
  markdown
}
Object.entries(LANGUAGES).forEach(([name, lang]) => hljs.registerLanguage(name, lang))

const md = new MarkdownIt({
  html: false, // 不解析原始 HTML，XSS 第一层防线
  linkify: true,
  breaks: true, // 历史纯文本帖子：单换行保持为 <br>，观感不退化
  highlight(code, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return hljs.highlight(code, { language: lang, ignoreIllegals: true }).value
      } catch {
        /* 高亮失败按纯文本处理 */
      }
    }
    return ''
  }
})

// 宽表格横向滚动容器：table 用外层 div 包一层，避免撑破正文列
const defaultTableOpen =
  md.renderer.rules.table_open ||
  ((tokens, idx, options, env, self) => self.renderToken(tokens, idx, options))
const defaultTableClose =
  md.renderer.rules.table_close ||
  ((tokens, idx, options, env, self) => self.renderToken(tokens, idx, options))
md.renderer.rules.table_open = (tokens, idx, options, env, self) =>
  `<div class="table-wrap">${defaultTableOpen(tokens, idx, options, env, self)}`
md.renderer.rules.table_close = (tokens, idx, options, env, self) =>
  `${defaultTableClose(tokens, idx, options, env, self)}</div>`

// 渲染后统一加安全属性：DOMPurify 会过滤 target（不在其允许属性列表），rel 即使保留也不会自动补，
// 也不区分链接协议，因此在消毒后统一为外链（含 // 协议相对链接）和图片补全同一套安全属性。
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A') {
    const href = node.getAttribute('href') || ''
    if (/^(?:https?:)?\/\//i.test(href)) {
      node.setAttribute('target', '_blank')
      node.setAttribute('rel', 'noopener noreferrer')
    }
  } else if (node.tagName === 'IMG') {
    node.setAttribute('loading', 'lazy')
  }
})

/** 渲染失败时的兜底：按纯文本展示，避免整页白屏 */
export function escapeHtml(text) {
  return String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/**
 * 帖子正文渲染入口：详情页与编辑页预览共用，保证「预览所见 = 详情所得」。
 * 返回的 HTML 只能绑定到带 .markdown-body 的元素的 v-html 上；
 * 禁止把原始 post.content 直接插值到模板，也不要绑定到普通容器上。
 * @param {string} source Markdown 源文本
 * @returns {string} 已消毒的 HTML
 */
export function renderMarkdown(source) {
  if (source == null || source === '') return ''
  try {
    return DOMPurify.sanitize(md.render(String(source)), { USE_PROFILES: { html: true } })
  } catch {
    return escapeHtml(String(source)).replace(/\n/g, '<br>')
  }
}
```

创建 `frontend/src/styles/markdown.css`：

```css
/* 帖子正文 Markdown 排版。作用域限定 .markdown-body，避免污染评论等纯文本区域 */
.markdown-body {
  font-size: 15px;
  line-height: 1.8;
  color: var(--jf-color-text-1);
  word-wrap: break-word;
}
.markdown-body > :first-child {
  margin-top: 0;
}
.markdown-body > :last-child {
  margin-bottom: 0;
}
.markdown-body h1,
.markdown-body h2,
.markdown-body h3,
.markdown-body h4 {
  margin: 1.2em 0 0.6em;
  line-height: 1.4;
}
.markdown-body h1 {
  font-size: 1.6em;
}
.markdown-body h2 {
  font-size: 1.35em;
}
.markdown-body h3 {
  font-size: 1.15em;
}
.markdown-body p,
.markdown-body ul,
.markdown-body ol,
.markdown-body blockquote,
.markdown-body pre {
  margin: 0.8em 0;
}
.markdown-body ul,
.markdown-body ol {
  padding-left: 1.6em;
}
.markdown-body blockquote {
  padding: 0.2em 1em;
  color: var(--jf-color-text-3);
  border-left: 4px solid var(--jf-color-border);
  background: var(--jf-color-bg-hover);
}
.markdown-body code {
  padding: 0.15em 0.4em;
  font-size: 0.9em;
  border-radius: var(--jf-radius-sm);
  background: var(--jf-color-bg-hover);
}
.markdown-body pre {
  padding: var(--jf-space-3);
  overflow-x: auto;
  border-radius: var(--jf-radius-md);
  background: #f6f8fa;
}
.markdown-body pre code {
  padding: 0;
  font-size: 0.9em;
  background: transparent;
}
.markdown-body .table-wrap {
  overflow-x: auto;
  margin: 0.8em 0;
}
.markdown-body table {
  width: max-content;
  min-width: 100%;
  border-collapse: collapse;
}
.markdown-body th,
.markdown-body td {
  padding: 6px 10px;
  border: 1px solid var(--jf-color-border);
}
.markdown-body th {
  background: var(--jf-color-bg-hover);
}
.markdown-body img {
  max-width: 100%;
  border-radius: var(--jf-radius-sm);
}
.markdown-body a {
  color: var(--jf-color-primary);
  text-decoration: none;
}
.markdown-body a:hover {
  text-decoration: underline;
}
.markdown-body hr {
  margin: 1.2em 0;
  border: none;
  border-top: 1px solid var(--jf-color-border);
}
```

修改 `frontend/src/main.js`，在 `./styles/common.css` 之后加两行：

```js
import './styles/markdown.css'
import 'highlight.js/styles/github.css'
```

- [x] **Step 5: 运行测试确认通过**

Run: `cd frontend && pnpm exec vitest run src/utils/markdown.spec.js`
Expected: PASS（14 个用例）。

- [x] **Step 6: 提交**

```bash
git add frontend/package.json frontend/pnpm-lock.yaml frontend/src/utils/markdown.js frontend/src/utils/markdown.spec.js frontend/src/styles/markdown.css frontend/src/main.js
git commit -m "feat: 新增帖子 Markdown 渲染工具与排版样式"
```

---

## Task 2: 详情页 Markdown 渲染与标签跳转

**Files:**
- Modify: `frontend/src/views/PostDetailView.vue`
- Test: `frontend/src/views/PostDetailView.spec.js`

- [x] **Step 1: 先写失败测试**

在 `frontend/src/views/PostDetailView.spec.js` 末尾新增独立 `describe`（沿用文件顶部的 mock、`mountDetail` 与 `beforeEach` 状态初始化约定）：

```js
/** Markdown 渲染与标签导航：与互动状态无关，单独成组 */
describe('PostDetailView Markdown 渲染与标签导航', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    commentApi.list.mockResolvedValue({ content: [], totalElements: 0 })
  })

  it('正文按 Markdown 渲染而不是原样输出', async () => {
    postApi.detail.mockResolvedValue(detailPayload({ id: 301, content: '## 小标题\n\n**加粗**' }))
    const wrapper = mountDetail()
    await flush()

    const content = wrapper.find('.post-content')
    expect(content.classes()).toContain('markdown-body')
    expect(content.html()).toContain('<h2>小标题</h2>')
    expect(content.html()).toContain('<strong>加粗</strong>')
  })

  it('点击标签 chip 跳转标签帖子页，标签名经过编码', async () => {
    postApi.detail.mockResolvedValue(detailPayload({ id: 302, tags: ['spring-boot', 'C#'] }))
    const wrapper = mountDetail()
    await flush()

    const chips = wrapper.findAll('.post-tags .tag-link')
    expect(chips).toHaveLength(2)
    await chips[0].trigger('click')
    expect(push).toHaveBeenCalledWith('/tags/spring-boot')
    await chips[1].trigger('click')
    expect(push).toHaveBeenCalledWith('/tags/C%23')
  })
})
```

- [x] **Step 2: 运行确认失败**

Run: `cd frontend && pnpm exec vitest run src/views/PostDetailView.spec.js`
Expected: FAIL —— `.post-content` 仍是纯文本、无点击行为。

- [x] **Step 3: 实现**

在 `PostDetailView.vue` `<script setup>` 中：

```js
import { renderMarkdown } from '@/utils/markdown'

// 正文渲染：渲染函数与编辑页预览共用，保证所见即所得
const renderedContent = computed(() => (post.value ? renderMarkdown(post.value.content) : ''))
```

模板中把：

```vue
<div class="post-content">{{ post.content }}</div>
```

替换为：

```vue
<div class="post-content markdown-body" v-html="renderedContent"></div>
```

标签区改为可点击：

```vue
<div class="post-tags" v-if="post.tags?.length">
  <el-tag
    v-for="t in post.tags"
    :key="t"
    size="small"
    type="info"
    effect="plain"
    class="tag-link"
    @click="router.push(`/tags/${encodeURIComponent(t)}`)"
  >
    {{ t }}
  </el-tag>
</div>
```

`<style scoped>` 中：

```css
.tag-link {
  cursor: pointer;
}
```

（注意：`.post-content` 只保留为查询/测试钩子，排版交给全局 `.markdown-body`，因此删除其 `white-space: pre-wrap` 与字号/行高；评论样式不动。）

- [x] **Step 4: 运行确认通过**

Run: `cd frontend && pnpm exec vitest run src/views/PostDetailView.spec.js`
Expected: PASS（原 3 个用例 + 新增 2 个）。

- [x] **Step 5: 提交**

```bash
git add frontend/src/views/PostDetailView.vue frontend/src/views/PostDetailView.spec.js
git commit -m "feat: 帖子详情页渲染 Markdown，标签支持跳转"
```

---

## Task 3: 编辑页实时分栏预览

**Files:**
- Modify: `frontend/src/views/PostEditView.vue`
- Test: `frontend/src/views/PostEditView.spec.js`（新建）

- [x] **Step 1: 先写失败测试**

创建 `frontend/src/views/PostEditView.spec.js`：

```js
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'

const push = vi.fn()
const route = { params: {}, query: {}, fullPath: '/posts/create' }
vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ push, back: vi.fn() })
}))

vi.mock('@/api', () => ({
  boardApi: { list: vi.fn(() => Promise.resolve([{ id: 1, name: '后端' }])) },
  postApi: { detail: vi.fn(), create: vi.fn(), update: vi.fn() }
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn() }
}))

import PostEditView from './PostEditView.vue'

// el-input 必须真实实现 v-model，否则没法通过输入驱动预览
const stubs = {
  'el-card': { template: '<div><slot /></div>' },
  'el-form': { template: '<form><slot /></form>', methods: { validate: () => Promise.resolve() } },
  'el-form-item': { template: '<div><slot /></div>' },
  'el-select': { template: '<div><slot /></div>' },
  'el-option': { template: '<div />' },
  'el-button': { template: '<button><slot /></button>' },
  'el-input': {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
  },
  'el-tabs': { template: '<div class="tabs"><slot /></div>' },
  'el-tab-pane': { template: '<div><slot /></div>' }
}

function mountEditor() {
  return mount(PostEditView, {
    global: {
      stubs,
      directives: { loading: () => {} },
      provide: {}
    }
  })
}

function flush() {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

describe('PostEditView Markdown 预览', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.matchMedia = vi.fn().mockImplementation((query) => ({
      matches: false,
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn()
    }))
  })

  it('桌面端左右分栏，输入正文时预览实时渲染', async () => {
    const wrapper = mountEditor()
    await flush()

    expect(wrapper.find('.editor-grid').exists()).toBe(true)
    await wrapper.find('textarea').setValue('## 预览标题')
    expect(wrapper.find('.preview').html()).toContain('<h2>预览标题</h2>')
  })

  it('窄屏显示编辑/预览 tab 而不是分栏', async () => {
    window.matchMedia = vi.fn().mockImplementation((query) => ({
      matches: true,
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn()
    }))
    const wrapper = mountEditor()
    await flush()

    expect(wrapper.find('.editor-grid').exists()).toBe(false)
    expect(wrapper.find('.tabs').exists()).toBe(true)
  })
})
```

> 偏差说明（2026-10-19 实施时同步）：上面 Step 1 的测试骨架在实施时有两处必要修正，语义与断言未变：
> 1. `mountEditor` 需注入 Pinia（`createPinia()`/`setActivePinia` + `global.plugins`），因为组件 setup 里调用了 `useAuthStore()`，否则挂载即抛 "no active Pinia"；写法同 `frontend/src/views/PostDetailView.spec.js` 先例。
> 2. `el-input` stub 需按键位区分：`type === 'textarea'` 渲染 `<textarea>`，否则渲染 `<input>`。原骨架对所有 `el-input` 都渲染 `<textarea>`，`find('textarea')` 会命中排在前面的标题输入框，正文预览断言永远无法通过。测试用例正文与断言保持原样。

- [x] **Step 2: 运行确认失败**

Run: `cd frontend && pnpm exec vitest run src/views/PostEditView.spec.js`
Expected: FAIL —— 编辑页还没有 `.editor-grid` / `.preview`。

- [x] **Step 3: 实现**

`PostEditView.vue` `<script setup>` 增加：

```js
import { useResponsive } from '@/composables/useResponsive'
import { renderMarkdown } from '@/utils/markdown'

const { isMobile } = useResponsive()
// 窄屏两个 tab：edit / preview；桌面端不分 tab
const mobileTab = ref('edit')
const previewHtml = computed(() => renderMarkdown(form.content))
```

模板中把正文表单项替换为：

```vue
<el-form-item label="正文（支持 Markdown）" prop="content">
  <div v-if="!isMobile" class="editor-grid">
    <el-input
      v-model="form.content"
      type="textarea"
      :rows="18"
      placeholder="正文内容…支持 Markdown：标题、列表、代码块、表格、链接、图片"
    />
    <div class="preview markdown-body" v-html="previewHtml"></div>
  </div>
  <template v-else>
    <el-tabs v-model="mobileTab" class="tabs">
      <el-tab-pane label="编辑" name="edit">
        <el-input v-model="form.content" type="textarea" :rows="14" placeholder="正文内容…支持 Markdown" />
      </el-tab-pane>
      <el-tab-pane label="预览" name="preview">
        <div class="preview markdown-body" v-html="previewHtml"></div>
      </el-tab-pane>
    </el-tabs>
  </template>
  <div class="md-hint">支持 GFM：标题、列表、代码块、表格、链接、图片（仅外链）</div>
</el-form-item>
```

`<style scoped>` 增加：

```css
.editor-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--jf-space-3);
  width: 100%;
}
.editor-grid .preview,
.tabs .preview {
  min-height: 200px;
  padding: var(--jf-space-3);
  border: 1px solid var(--jf-color-border);
  border-radius: var(--jf-radius-sm);
  overflow: auto;
}
.md-hint {
  margin-top: 6px;
  color: var(--jf-color-text-3);
  font-size: var(--jf-font-meta);
}
```

> 偏差说明（2026-10-19 实施时同步）：原计划在此追加 `@media (max-width: 768px) { .editor-grid { grid-template-columns: 1fr } }`。实施时确认该规则不可达：模板已用 `v-if="!isMobile"`（与 `breakpoint.spec.js` 同一 768px 断点）在分栏与 tab 之间切换，窄屏下 `.editor-grid` 根本不会渲染；同时 `breakpoint.spec.js` 把 `frontend/src` 的断点字面量精确锁定为 5 处，新增第 6 处须同步 tokens.css / README 的人工闸门。故省略该媒体查询，源码中以注释记录原因。
>
> 补充（2026-10-19 复审修正）：上面的模板与样式片段漏掉了 spec §4.3 编辑页要求的空预览占位「预览将实时显示在这里」。`.preview` 自带 `min-height: 200px` 与边框，正文为空时会渲染出一个有边框的空面板；现补为 `previewHtml` 非空时才渲染原 `.preview.markdown-body` 并绑 `v-html`，为空时渲染 `<div class="preview preview-empty">预览将实时显示在这里</div>`。占位文案走文本插值、不经过 `v-html`（不变量：`v-html` 只接收 `renderMarkdown` 输出），`.preview-empty` 只复用既有 token（`color: var(--jf-color-text-3)` + flex 居中），未引入新 token 或 `@media`。同时用 `lazy` 让窄屏预览 tab 首次展示前不跑 markdown/hljs。

- [x] **Step 4: 运行确认通过**

Run: `cd frontend && pnpm exec vitest run src/views/PostEditView.spec.js`
Expected: PASS（2 个用例）。

- [x] **Step 5: 提交**

```bash
git add frontend/src/views/PostEditView.vue frontend/src/views/PostEditView.spec.js
git commit -m "feat: 帖子编辑页新增 Markdown 实时预览（桌面分栏/窄屏 tab）"
```

---

## Task 4: 后端标签接口（集成测试先行）

**Files:**
- Create: `src/main/java/org/song/forum/dto/tag/TagResponse.java`
- Create: `src/main/java/org/song/forum/service/TagService.java`
- Create: `src/main/java/org/song/forum/controller/TagController.java`
- Create: `src/test/java/org/song/forum/controller/TagFlowIntegrationTest.java`
- Modify: `src/main/java/org/song/forum/repository/TagRepository.java`
- Modify: `src/main/java/org/song/forum/repository/PostRepository.java`
- Modify: `src/main/java/org/song/forum/service/PostService.java`
- Modify: `src/main/java/org/song/forum/config/SecurityConfig.java`
- Modify: `src/test/java/org/song/forum/repository/RepositoryTests.java`

- [x] **Step 1: 写集成测试（先失败）**

创建 `src/test/java/org/song/forum/controller/TagFlowIntegrationTest.java`：

```java
package org.song.forum.controller;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.song.forum.dto.auth.AuthResponse;
import org.song.forum.dto.auth.LoginRequest;
import org.song.forum.dto.auth.RegisterRequest;
import org.song.forum.dto.board.BoardRequest;
import org.song.forum.dto.board.BoardResponse;
import org.song.forum.dto.post.PostRequest;
import org.song.forum.dto.post.PostSummaryResponse;
import org.song.forum.dto.tag.TagResponse;
import org.song.forum.util.PageResponse;
import org.song.forum.util.Result;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第三轮标签接口集成测试（H2 + 随机端口）：
 * 建板块 → 发帖带标签 → 标签总览（数量排序/过滤）→ 标签帖子列表（分页/置顶优先/空标签）
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:tag-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TagFlowIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private static String adminToken;
    private static String userToken;
    private static Long boardId;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private String loginAdmin() {
        LoginRequest l = new LoginRequest();
        l.setUsername("admin");
        l.setPassword("admin123456");
        ResponseEntity<Result<AuthResponse>> resp = rest.exchange(url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>(l), new ParameterizedTypeReference<>() {});
        return resp.getBody().getData().getToken();
    }

    private String registerAndLogin(String name) {
        RegisterRequest r = new RegisterRequest();
        r.setUsername(name);
        r.setPassword("password123");
        r.setEmail(name + "@test.com");
        rest.postForEntity(url("/api/auth/register"), r, Result.class);
        LoginRequest l = new LoginRequest();
        l.setUsername(name);
        l.setPassword("password123");
        ResponseEntity<Result<AuthResponse>> resp = rest.exchange(url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>(l), new ParameterizedTypeReference<>() {});
        return resp.getBody().getData().getToken();
    }

    private void createPost(String token, String title, List<String> tags) {
        PostRequest req = new PostRequest();
        req.setBoardId(boardId);
        req.setTitle(title);
        req.setContent(title + " 正文");
        req.setTags(tags);
        rest.exchange(url("/api/posts"), HttpMethod.POST, new HttpEntity<>(req, bearer(token)),
                new ParameterizedTypeReference<Result<PostDetailResponse>>() {});
    }

    @Test
    @Order(1)
    void setupBoardAndPosts() {
        adminToken = loginAdmin();
        BoardRequest board = new BoardRequest();
        board.setName("标签测试板块");
        board.setDescription("用于标签接口测试");
        board.setSortOrder(1);
        ResponseEntity<Result<BoardResponse>> created = rest.exchange(url("/api/boards"), HttpMethod.POST,
                new HttpEntity<>(board, bearer(adminToken)), new ParameterizedTypeReference<>() {});
        boardId = created.getBody().getData().getId();

        userToken = registerAndLogin("tagger");
        createPost(userToken, "Java 第一篇", List.of("java", "Spring"));
        createPost(userToken, "Java 第二篇", List.of("java"));
        createPost(userToken, "Spring Boot 笔记", List.of("spring-boot"));

        assertThat(boardId).isNotNull();
    }

    @Test
    @Order(2)
    void tagsOverviewSortsByPostCountDesc() {
        // 游客可访问（公开 GET）
        ResponseEntity<Result<PageResponse<TagResponse>>> resp = rest.exchange(url("/api/tags"), HttpMethod.GET,
                null, new ParameterizedTypeReference<>() {});
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        List<TagResponse> tags = resp.getBody().getData().getContent();
        // java(2) 在 spring(1)/spring-boot(1) 之前
        assertThat(tags.get(0).name()).isEqualTo("java");
        assertThat(tags.get(0).postCount()).isEqualTo(2);
        assertThat(tags).extracting(TagResponse::name).containsExactly("java", "spring", "spring-boot");
    }

    @Test
    @Order(3)
    void tagsOverviewFiltersByKeyword() {
        ResponseEntity<Result<PageResponse<TagResponse>>> resp = rest.exchange(url("/api/tags?keyword=SPR"),
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        assertThat(resp.getBody().getData().getContent())
                .extracting(TagResponse::name)
                .containsExactly("spring", "spring-boot");
    }

    @Test
    @Order(4)
    void tagPostsArePagedAndCarryTags() {
        ResponseEntity<Result<PageResponse<PostSummaryResponse>>> resp = rest.exchange(
                url("/api/tags/posts?name=java&page=0&size=10"), HttpMethod.GET,
                null, new ParameterizedTypeReference<>() {});
        PageResponse<PostSummaryResponse> page = resp.getBody().getData();
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content()).allSatisfy(p -> assertThat(p.getTags()).contains("java"));
    }

    @Test
    @Order(5)
    void unknownTagReturnsEmptyPageAndSizeIsClamped() {
        ResponseEntity<Result<PageResponse<PostSummaryResponse>>> unknown = rest.exchange(
                url("/api/tags/posts?name=no-such-tag"), HttpMethod.GET,
                null, new ParameterizedTypeReference<>() {});
        assertThat(unknown.getStatusCode().value()).isEqualTo(200);
        assertThat(unknown.getBody().getData().totalElements()).isZero();

        ResponseEntity<Result<PageResponse<PostSummaryResponse>>> clamped = rest.exchange(
                url("/api/tags/posts?name=java&size=999"), HttpMethod.GET,
                null, new ParameterizedTypeReference<>() {});
        assertThat(clamped.getBody().getData().size()).isEqualTo(50);
    }
}
```

同时给 `RepositoryTests` 追加（放在类末尾）：

```java
@Test
void tagAggregationCountsOnlyUndeletedPosts() {
    User author = user("tag-owner");
    Board board = boardRepository.save(Board.builder().name("标签板块").description("d").build());
    Tag java = tagRepository.save(Tag.builder().name("java").build());
    Tag spring = tagRepository.save(Tag.builder().name("spring").build());

    Post alive = postRepository.save(Post.builder()
            .board(board).author(author).title("a").content("a").build());
    alive.getTags().add(java);
    postRepository.save(alive);

    Post deleted = postRepository.save(Post.builder()
            .board(board).author(author).title("b").content("b").build());
    deleted.getTags().add(java);
    deleted.getTags().add(spring);
    deleted.setDeleted(true);
    postRepository.save(deleted);

    assertThat(tagRepository.findWithPostCounts(""))
            .extracting(row -> row[1], row -> ((Number) row[2]).longValue())
            .containsExactly(org.assertj.core.groups.Tuple.tuple("java", 1L));
}
```

- [x] **Step 2: 运行确认失败**

Run: `cd /e/JavaSpace/forum && mvn -B -q -Dtest=TagFlowIntegrationTest,RepositoryTests test`
Expected: 编译失败 —— `TagResponse`/`findWithPostCounts`/`TagController` 尚不存在。

- [x] **Step 3: 实现后端**

`src/main/java/org/song/forum/dto/tag/TagResponse.java`：

```java
package org.song.forum.dto.tag;

/** 标签总览项：名称 + 未删除帖子数 */
public record TagResponse(Long id, String name, long postCount) {
}
```

`TagRepository` 追加：

```java
/**
 * 标签聚合：按未删除帖子的引用次数统计。
 * 返回 Object[] {id, name, count}，聚合与分页在服务层做——group by 与 Pageable 同用时
 * Spring Data 派生的 count 查询容易出错，标签量级小，服务层排序分页更稳。
 */
@Query("""
        SELECT t.id, t.name, COUNT(p.id) FROM Post p JOIN p.tags t
        WHERE p.deleted = false
          AND (:keyword = '' OR LOWER(t.name) LIKE LOWER(CONCAT('%', :keyword, '%')))
        GROUP BY t.id, t.name
        """)
List<Object[]> findWithPostCounts(@Param("keyword") String keyword);
```

（需要 import `java.util.List`、`org.springframework.data.jpa.repository.Query`、`org.springframework.data.repository.query.Param`——文件里若缺则补。）

`PostRepository` 追加：

```java
/** 某标签下的帖子分页第一步：只取骨架，不 join tags 集合（原因同 searchIds） */
@Query("SELECT p FROM Post p JOIN p.tags t WHERE p.deleted = false AND t.name = :name")
@EntityGraph(attributePaths = {"board", "author"})
Page<Post> searchByTagName(@Param("name") String name, Pageable pageable);
```

`PostService` 追加方法（放在 `myPosts` 之后）：

```java
/** 标签帖子列表：复用与首页相同的两步查询与互动状态计算；标签不存在时返回空页 */
@Transactional(readOnly = true)
public PageResponse<PostSummaryResponse> listByTag(String tagName, int page, int size) {
    String normalized = tagName == null ? "" : tagName.trim().toLowerCase(Locale.ROOT);
    if (normalized.isEmpty() || tagRepository.findByName(normalized).isEmpty()) {
        return new PageResponse<>(List.of(), page, size, 0, 0, true);
    }
    Pageable pageable = PageRequest.of(page, size, pinnedFirstSort());
    Page<Post> skeletons = postRepository.searchByTagName(normalized, pageable);
    return PageResponse.of(mapSummaries(hydrate(skeletons)));
}
```

`TagService`：

```java
package org.song.forum.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.song.forum.dto.tag.TagResponse;
import org.song.forum.repository.TagRepository;
import org.song.forum.util.PageResponse;

import java.util.Comparator;
import java.util.List;

/** 标签总览：聚合查询 + 排序 + 服务层分页 */
@Service
@RequiredArgsConstructor
public class TagService {

    private final TagRepository tagRepository;

    @Transactional(readOnly = true)
    public PageResponse<TagResponse> list(String keyword, int page, int size) {
        String kw = (keyword == null || keyword.isBlank()) ? "" : keyword.trim();
        List<TagResponse> all = tagRepository.findWithPostCounts(kw).stream()
                .map(row -> new TagResponse(
                        ((Number) row[0]).longValue(),
                        (String) row[1],
                        ((Number) row[2]).longValue()))
                .sorted(Comparator.comparingLong(TagResponse::postCount).reversed()
                        .thenComparing(TagResponse::name))
                .toList();

        // 分页用 long 运算再收窄：page=Integer.MAX_VALUE&size=100 时 int 乘法会溢出成负数，
        // subList(from, to) 随即抛 IndexOutOfBoundsException（500）。size 在此处再做一次兜底，
        // 保证 from/to 恒落在 [0, all.size()] 且 to >= from。
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(size, 1);
        long fromL = (long) safePage * safeSize;
        int from = (int) Math.min(fromL, all.size());
        int to = (int) Math.min(fromL + safeSize, all.size());
        if (to < from) {
            to = from;
        }
        // totalPages 同样用 long 计算，避免 (all.size()+size-1) 在 int 下溢出
        int totalPages = (int) ((all.size() + (long) safeSize - 1) / safeSize);
        return new PageResponse<>(all.subList(from, to), safePage, safeSize, all.size(), totalPages,
                to >= all.size());
    }
}
```

`TagController`：

```java
package org.song.forum.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.song.forum.dto.post.PostSummaryResponse;
import org.song.forum.dto.tag.TagResponse;
import org.song.forum.service.PostService;
import org.song.forum.service.TagService;
import org.song.forum.util.PageResponse;
import org.song.forum.util.Result;

/** 标签接口：总览与标签帖子列表（均为公开 GET） */
@RestController
@RequestMapping("/api/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;
    private final PostService postService;

    /** 标签总览：?keyword=&page=&size=（按帖子数降序） */
    @GetMapping
    public Result<PageResponse<TagResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return Result.ok(tagService.list(keyword, Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
    }

    /** 标签帖子列表：?name=&page=&size=（name 用 query 参数，避免标签名含 / 时路径被 Tomcat 拒绝） */
    @GetMapping("/posts")
    public Result<PageResponse<PostSummaryResponse>> posts(
            @RequestParam String name,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(postService.listByTag(name, Math.max(page, 0), Math.min(Math.max(size, 1), 50)));
    }
}
```

`SecurityConfig.PUBLIC_GET` 数组里加一行 `"/api/tags/**"`。

- [x] **Step 4: 运行后端测试确认通过**

Run: `cd /e/JavaSpace/forum && mvn -B -q -Dtest=TagFlowIntegrationTest,RepositoryTests test`
Expected: BUILD SUCCESS，`Tests run: 5, Failures: 0`（标签类）+ RepositoryTests 全绿。

- [x] **Step 5: 跑全量后端测试**

Run: `cd /e/JavaSpace/forum && mvn -B -q test`
Expected: BUILD SUCCESS，无失败。

- [x] **Step 6: 提交**

```bash
git add src/main/java src/test/java
git commit -m "feat: 新增标签总览与标签帖子接口"
```

> 偏差说明（2026-10-19 质量评审修正，提交 `fix: 修复标签分页溢出并为参数错误返回 400`）：
> 1. **新增两个 400 异常处理器（计划未涉及）**：`GlobalExceptionHandler` 增加 `MissingServletRequestParameterException`（缺 `name` 等必需请求参数）与 `MethodArgumentTypeMismatchException`（如 `size=abc`）处理器。修正前两者都会落到 `Exception` 兜底返回 500「服务器内部错误」，把客户端错误误报为服务端故障；现在返回 400，消息分别为「缺少必需参数: xxx」「参数格式不正确: xxx」，响应仍是既有 `Result` 错误结构。
> 2. **`TagService` 分页 long 化**：原 `page * size` 在 int 下溢出，`GET /api/tags?page=2147483647&size=100` 时 `from` 变负，`subList` 抛 `IndexOutOfBoundsException` → 500。改为先 `long` 运算再安全收窄到 `int`，并保证 `to >= from`、`totalPages` 用 long 计算不溢出；`page`/`size` 在服务层再做一次兜底（负数归零、size 至少 1）。上面 Step 3 的 `TagService` 代码片段已按修正后的实现更新。
> 3. **补充测试**：`TagFlowIntegrationTest` 由 5 例增至 8 例——新增置顶优先（管理员 `PUT /api/posts/{id}/pin?pinned=true` 后断言置顶帖在 `/api/tags/posts?name=java` 首位且 `isPinned=true`）、分页边界（`size=999` 收窄为 100、`page=-1` 归零、`page=2147483647` 返回 200 且 content 为空）、参数错误 400（缺 `name`、`size=abc`）。Step 4 中的「Tests run: 5」为当时记录，修正后该类为 8 例；全量 9 类 39 例。
> 4. **已知 minor（本次未改，仅登记）**：LIKE 通配符未转义、`findWithPostCounts` 返回 `Object[]` 未改投影、`SecurityConfig` 未把标签接口限定为 GET、标签排序依赖数据库 locale。

---

## Task 5: 前端标签页面与公共帖子行

**Files:**
- Create: `frontend/src/components/PostListItem.vue`
- Create: `frontend/src/views/TagsView.vue`
- Create: `frontend/src/views/TagPostsView.vue`
- Create: `frontend/src/components/PostListItem.spec.js`
- Create: `frontend/src/views/TagsView.spec.js`
- Create: `frontend/src/views/TagPostsView.spec.js`
- Modify: `frontend/src/api/index.js`
- Modify: `frontend/src/router/index.js`
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/views/HomeView.vue`
- Modify: `frontend/src/styles/breakpoint.spec.js`（断点闸门计数 5→6）
- Modify: `frontend/src/styles/tokens.css`（断点说明同步）
- Modify: `README.md`（断点段落同步）

- [x] **Step 1: 先写失败测试**

`frontend/src/components/PostListItem.spec.js`：

```js
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

import PostListItem from './PostListItem.vue'
import { usePostActions } from '@/composables/usePostActions'

const stubs = {
  'el-tag': { template: '<span><slot /></span>' },
  'el-icon': { template: '<i><slot /></i>' }
}

describe('PostListItem', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  it('点击行进入详情，点击标签进入标签页且不触发行跳转', async () => {
    const post = { id: 7, title: 'T', tags: ['java'], authorUsername: 'u', boardName: 'b', viewCount: 1, commentCount: 2, likeCount: 3, liked: false }
    const wrapper = mount(PostListItem, { props: { post }, global: { stubs } })

    await wrapper.trigger('click')
    expect(push).toHaveBeenCalledWith('/posts/7')

    push.mockClear()
    await wrapper.find('.tags span').trigger('click')
    expect(push).toHaveBeenCalledWith('/tags/java')
  })

  it('点赞按钮发出 like 事件并使用共享计数', async () => {
    const post = { id: 8, title: 'T', tags: [], authorUsername: 'u', boardName: 'b', viewCount: 0, commentCount: 0, likeCount: 5, liked: true }
    usePostActions().syncFromPost(post)
    const wrapper = mount(PostListItem, { props: { post }, global: { stubs } })

    expect(wrapper.find('.like-btn').text()).toContain('5')
    await wrapper.find('.like-btn').trigger('click')
    expect(wrapper.emitted('like')).toEqual([[post]])
  })
})
```

`frontend/src/views/TagsView.spec.js`：

```js
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
vi.mock('@/api', () => ({
  tagApi: { list: vi.fn(), posts: vi.fn() }
}))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn() } }))

import TagsView from './TagsView.vue'
import { tagApi } from '@/api'

const stubs = {
  'el-card': { template: '<div><slot /></div>' },
  'el-input': { template: '<input />' },
  'el-button': { template: '<button><slot /></button>' },
  'el-tag': { template: '<span class="tag"><slot /></span>' },
  'el-pagination': { template: '<div />' },
  'el-empty': { template: '<div />' }
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('TagsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    tagApi.list.mockResolvedValue({
      content: [
        { id: 1, name: 'java', postCount: 9 },
        { id: 2, name: 'spring', postCount: 3 }
      ],
      totalElements: 2,
      totalPages: 1
    })
  })

  it('渲染标签与帖子数，点击跳转标签帖子页', async () => {
    const wrapper = mount(TagsView, { global: { stubs } })
    await flush()

    expect(tagApi.list).toHaveBeenCalled()
    expect(wrapper.text()).toContain('java')
    expect(wrapper.text()).toContain('9')
    await wrapper.find('.tag').trigger('click')
    expect(push).toHaveBeenCalledWith('/tags/java')
  })
})
```

`frontend/src/views/TagPostsView.spec.js`：

```js
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'

const push = vi.fn()
const route = { params: { name: 'java' }, query: {}, fullPath: '/tags/java', name: 'tag-posts' }
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push }) }))
vi.mock('@/api', () => ({
  tagApi: { list: vi.fn(), posts: vi.fn() }
}))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn() } }))

import TagPostsView from './TagPostsView.vue'
import { tagApi } from '@/api'

const stubs = {
  'el-card': { template: '<div><slot /></div>' },
  'el-tag': { template: '<span><slot /></span>' },
  'el-pagination': { template: '<div />' },
  'el-empty': { template: '<div class="empty" />' },
  'el-icon': { template: '<i><slot /></i>' }
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('TagPostsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('按路由标签名加载帖子并显示空状态', async () => {
    tagApi.posts.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 })
    const wrapper = mount(TagPostsView, { global: { stubs } })
    await flush()

    expect(tagApi.posts).toHaveBeenCalledWith('java', expect.objectContaining({ page: 0 }))
    expect(wrapper.text()).toContain('java')
    expect(wrapper.find('.empty').exists()).toBe(true)
  })
})
```

- [x] **Step 2: 运行确认失败**

Run: `cd frontend && pnpm exec vitest run src/components/PostListItem.spec.js src/views/TagsView.spec.js src/views/TagPostsView.spec.js`
Expected: FAIL —— 组件文件不存在。

- [x] **Step 3: 实现**

`frontend/src/api/index.js` 在评论接口之前加：

```js
// ========== 标签 ==========
export const tagApi = {
  list: (params) => api.get('/tags', { params }),
  posts: (name, params) => api.get('/tags/posts', { params: { name, ...params } })
}
```

`frontend/src/router/index.js` 增加两条路由（放在 `post-detail` 之后）：

```js
{ path: '/tags', name: 'tags', component: () => import('@/views/TagsView.vue') },
{ path: '/tags/:name', name: 'tag-posts', component: () => import('@/views/TagPostsView.vue') },
```

`frontend/src/App.vue`：品牌链接之后加

```vue
<router-link to="/tags" class="nav-link">标签</router-link>
```

`.header-inner` 内放三个元素的布局改为 `justify-content: space-between` 时，`nav-link` 放 `brand` 之后即可；样式：

```css
.nav-link {
  margin-left: var(--jf-space-4);
  color: var(--jf-color-text-2);
}
.nav-link.router-link-active {
  color: var(--jf-color-primary);
  font-weight: 600;
}
```

`frontend/src/components/PostListItem.vue`（从 `HomeView.vue` 抽取的帖子行，类名保持不变以复用既有测试选择器）：

```vue
<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { View, ChatDotRound, CaretTop, Medal, Star } from '@element-plus/icons-vue'
import { usePostActions } from '@/composables/usePostActions'
import { formatRelative } from '@/utils/format'

const props = defineProps({
  post: { type: Object, required: true }
})
const emit = defineEmits(['like'])
const router = useRouter()
const actions = usePostActions()

// 点赞计数一律读共享状态（与首页原实现一致），避免响应字段回退旧值
const state = computed(() => actions.stateFor(props.post.id))

function openPost() {
  router.push(`/posts/${props.post.id}`)
}

function openTag(tag) {
  router.push(`/tags/${encodeURIComponent(tag)}`)
}
</script>

<template>
  <div class="post-item" @click="openPost">
    <div class="post-main">
      <div class="post-title-line">
        <el-tag v-if="post.isPinned" type="danger" size="small" class="flag">
          <el-icon><CaretTop /></el-icon>置顶
        </el-tag>
        <el-tag v-if="post.isExquisite" type="warning" size="small" class="flag">
          <el-icon><Medal /></el-icon>精华
        </el-tag>
        <span class="post-title">{{ post.title }}</span>
      </div>
      <div class="post-meta">
        <span>{{ post.authorNickname || post.authorUsername }}</span>
        <span>· {{ post.boardName }}</span>
        <span>· {{ formatRelative(post.createTime) }}</span>
        <span v-if="post.tags?.length" class="tags">
          <el-tag
            v-for="t in post.tags"
            :key="t"
            size="small"
            type="info"
            effect="plain"
            class="tag-link"
            @click.stop="openTag(t)"
          >
            {{ t }}
          </el-tag>
        </span>
      </div>
    </div>
    <div class="post-stats">
      <span><el-icon><View /></el-icon>{{ post.viewCount }}</span>
      <span><el-icon><ChatDotRound /></el-icon>{{ post.commentCount }}</span>
    </div>
    <button
      type="button"
      class="like-btn"
      :class="{ active: state.liked }"
      :title="state.liked ? '取消点赞' : '点赞'"
      @click.stop="emit('like', post)"
    >
      <el-icon><Star /></el-icon>{{ state.likeCount }}
    </button>
  </div>
</template>

<style scoped>
.post-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px var(--jf-space-2);
  border-bottom: 1px solid var(--jf-color-border);
  cursor: pointer;
}
.post-item:last-of-type {
  border-bottom: none;
}
.post-item:hover {
  background: var(--jf-color-bg-hover);
}
.post-title-line {
  display: flex;
  align-items: center;
  gap: 6px;
}
.flag {
  flex-shrink: 0;
}
.post-title {
  font-size: var(--jf-font-title-md);
  font-weight: 600;
}
.post-meta {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--jf-color-text-3);
  font-size: var(--jf-font-meta);
  flex-wrap: wrap;
}
.tags {
  display: inline-flex;
  gap: var(--jf-space-1);
}
.tag-link {
  cursor: pointer;
}
.post-stats {
  display: flex;
  gap: 14px;
  color: var(--jf-color-text-3);
  font-size: var(--jf-font-meta);
  flex-shrink: 0;
}
.post-stats span {
  display: inline-flex;
  align-items: center;
  gap: var(--jf-space-1);
}
.like-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--jf-space-1);
  margin-left: var(--jf-space-3);
  padding: var(--jf-space-1) var(--jf-space-2);
  border: 1px solid var(--jf-color-border);
  border-radius: var(--jf-radius-sm);
  background: var(--jf-color-bg-card);
  color: var(--jf-color-text-3);
  font-size: var(--jf-font-meta);
  font-family: inherit;
  line-height: 1.5;
  cursor: pointer;
  flex-shrink: 0;
}
.like-btn:hover {
  background: var(--jf-color-bg-hover);
}
.like-btn.active {
  border-color: var(--jf-color-primary);
  color: var(--jf-color-primary);
}

@media (max-width: 768px) {
  .post-item {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .post-meta {
    gap: var(--jf-space-1);
  }
  .post-stats {
    margin-left: var(--jf-space-2);
  }
}
</style>
```

> **断点闸门同步（本任务必做，与上面媒体查询同一提交）**：`.post-item` 的窄屏规则从 `HomeView` 整体迁移到 `PostListItem`，`frontend/src` 下的 `@media (max-width: 768px)` 字面量因此从 5 处变为 6 处（`HomeView` 仍保留板块/工具栏的媒体查询）。`frontend/src/styles/breakpoint.spec.js` 把数量精确锁定为 5，故必须同步：
> - `frontend/src/styles/breakpoint.spec.js`：`EXPECTED_MEDIA_BREAKPOINTS` 5 → 6；用例标题「数量仍为 5」→「数量仍为 6」；文件清单注释加入 `components/PostListItem.vue`。
> - `frontend/src/styles/tokens.css`：「断点字面量目前共七处」→「共八处」、「其余六处」→「其余七处」，并把 `PostListItem` 加入媒体查询位置清单。
> - `README.md`「样式与设计令牌」段落：「五个视图」→ 六个位置（含 `components/PostListItem`），「断点数量不为 5」→「不为 6」。
> - 跑 `pnpm test` 确认 `breakpoint.spec.js` 的精确计数用例通过。

`frontend/src/views/HomeView.vue`：删掉内联的 `.post-item` 模板块，改为：

```vue
<PostListItem
  v-for="p in pageData.content"
  :key="p.id"
  :post="p"
  @like="toggleLike"
/>
```

`<script setup>` 增加 `import PostListItem from '@/components/PostListItem.vue'`；`toggleLike` 改为接收 post 参数（`async function toggleLike(p) { ... }` 与原实现一致）；不再使用的图标 import 从 HomeView 移除；`<style scoped>` 中与帖子行相关的规则可删除（保留 `.board-*`、`.toolbar`、`.search-input` 等仍使用的部分）。

`frontend/src/views/TagsView.vue`：

```vue
<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { tagApi } from '@/api'

const router = useRouter()
const keyword = ref('')
const pageData = ref({ content: [], totalElements: 0, totalPages: 0 })
const page = ref(0)
const size = 30
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    pageData.value = await tagApi.list({ keyword: keyword.value || undefined, page: page.value, size })
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 0
  load()
}

function changePage(p) {
  page.value = p - 1
  load()
}

function openTag(name) {
  router.push(`/tags/${encodeURIComponent(name)}`)
}

onMounted(load)
</script>

<template>
  <div class="tags-page">
    <el-card shadow="never">
      <template #header>
        <div class="head">
          <span class="title">标签</span>
          <el-input
            v-model="keyword"
            placeholder="按标签名筛选…"
            clearable
            class="search-input"
            @keyup.enter="search"
            @clear="search"
          >
            <template #append>
              <el-button :icon="Search" @click="search" />
            </template>
          </el-input>
        </div>
      </template>

      <div v-loading="loading" class="tag-cloud">
        <el-tag
          v-for="t in pageData.content"
          :key="t.id"
          size="large"
          effect="plain"
          class="tag"
          @click="openTag(t.name)"
        >
          {{ t.name }} <span class="count">{{ t.postCount }}</span>
        </el-tag>
        <el-empty v-if="!pageData.content.length" description="暂无标签" :image-size="80" />
      </div>

      <el-pagination
        v-if="pageData.totalPages > 1"
        layout="prev, pager, next"
        :total="pageData.totalElements"
        :page-size="size"
        :current-page="page + 1"
        class="jf-pager"
        @current-change="changePage"
      />
    </el-card>
  </div>
</template>

<style scoped>
.tags-page {
  padding-top: var(--jf-space-4);
}
.head {
  display: flex;
  align-items: center;
  gap: var(--jf-space-3);
}
.title {
  font-size: var(--jf-font-title-md);
  font-weight: 600;
}
.search-input {
  max-width: 320px;
}
.tag-cloud {
  display: flex;
  flex-wrap: wrap;
  gap: var(--jf-space-3);
  min-height: 80px;
}
.tag {
  cursor: pointer;
  font-size: var(--jf-font-body);
}
.count {
  color: var(--jf-color-text-3);
  margin-left: 4px;
}
</style>
```

`frontend/src/views/TagPostsView.vue`：

```vue
<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { postApi } from '@/api'
import PostListItem from '@/components/PostListItem.vue'
import { usePostActions } from '@/composables/usePostActions'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const actions = usePostActions()

const pageData = ref({ content: [], totalElements: 0, totalPages: 0 })
const page = ref(0)
const size = 10
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const data = await postApi.list({ tag: route.params.name, page: page.value, size })
    data.content.forEach((p) => actions.syncFromPost(p))
    pageData.value = data
  } finally {
    loading.value = false
  }
}
```

改为调用标签接口（上面 `tagApi.posts`）：

```js
import { tagApi } from '@/api'
...
const data = await tagApi.posts(route.params.name, { page: page.value, size })
```

完整实现：

```vue
<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { tagApi } from '@/api'
import PostListItem from '@/components/PostListItem.vue'
import { usePostActions } from '@/composables/usePostActions'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const actions = usePostActions()

const pageData = ref({ content: [], totalElements: 0, totalPages: 0 })
const page = ref(0)
const size = 10
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const data = await tagApi.posts(route.params.name, { page: page.value, size })
    data.content.forEach((p) => actions.syncFromPost(p))
    pageData.value = data
  } finally {
    loading.value = false
  }
}

function changePage(p) {
  page.value = p - 1
  load()
}

async function toggleLike(p) {
  if (!auth.isLoggedIn) {
    router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  try {
    await actions.toggleLike(p.id)
  } catch {
    // 失败回滚由 usePostActions 完成，提示由 axios 拦截器统一给出
  }
}

onMounted(load)
</script>

<template>
  <div class="tag-posts-page">
    <el-card shadow="never">
      <template #header>
        <div class="head">
          <router-link to="/tags" class="back">← 全部标签</router-link>
          <span class="title">标签：{{ route.params.name }}</span>
          <span class="total">{{ pageData.totalElements }} 篇</span>
        </div>
      </template>

      <div v-loading="loading">
        <template v-if="pageData.content.length">
          <PostListItem
            v-for="p in pageData.content"
            :key="p.id"
            :post="p"
            @like="toggleLike"
          />
        </template>
        <el-empty v-else description="该标签下暂无帖子" :image-size="80" />
      </div>

      <el-pagination
        v-if="pageData.totalPages > 1"
        layout="prev, pager, next"
        :total="pageData.totalElements"
        :page-size="size"
        :current-page="page + 1"
        class="jf-pager"
        @current-change="changePage"
      />
    </el-card>
  </div>
</template>

<style scoped>
.tag-posts-page {
  padding-top: var(--jf-space-4);
}
.head {
  display: flex;
  align-items: center;
  gap: var(--jf-space-3);
}
.back {
  color: var(--jf-color-text-3);
  font-size: var(--jf-font-meta);
}
.title {
  font-size: var(--jf-font-title-md);
  font-weight: 600;
}
.total {
  color: var(--jf-color-text-3);
  font-size: var(--jf-font-meta);
}
</style>
```

- [x] **Step 4: 运行确认通过**

Run: `cd frontend && pnpm exec vitest run src/components/PostListItem.spec.js src/views/TagsView.spec.js src/views/TagPostsView.spec.js`
Expected: PASS。

- [x] **Step 5: 跑全量前端测试，修掉 HomeView 既有用例的适配问题**

Run: `cd frontend && pnpm test`
Expected: 全绿。若 `HomeView.spec.js` 因组件抽取失败，按失败断言调整测试（选择器 `.like-btn`/`.post-item` 已在 `PostListItem` 中保留，通常无需改动）。

- [x] **Step 6: 构建验证**

Run: `cd frontend && VITE_BASE_PATH=/forum/ pnpm build`（bash 下可用 `VITE_BASE_PATH=/forum/ pnpm build`；Windows 本地可用 `cross-env` 等价写法 `$env:VITE_BASE_PATH='/forum/'; pnpm build`）
Expected: 构建成功，`dist/index.html` 引用 `/forum/assets/...`。

- [x] **Step 7: 提交**

```bash
git add frontend/src frontend/package.json frontend/pnpm-lock.yaml README.md
git commit -m "feat: 新增标签总览页与标签帖子页，抽取公共帖子行组件"
```

> 偏差说明（2026-10-19 规格评审补记，提交 `fix: 修复标签帖子页路由切换不刷新并补标签接口测试`）：
> 下面 6 项为 Task 5 实施时自报、但计划中未登记的偏差，本次评审补记；其后的 P1/P2 为本次评审要求的修复。
> 1. **测试用例 13 例（计划骨架 4 例）**：三个新 spec 在计划骨架上补了分页、空态、错误重试、标签名 URL 编码、共享计数、置顶/精华渲染等断言，实际为 `PostListItem.spec.js` 4 例、`TagsView.spec.js` 5 例、`TagPostsView.spec.js` 4 例（P1 修复后该类增至 5 例）。
> 2. **spec 挂载补丁（挂载必需，非空转）**：`el-card` stub 需渲染 header 槽（关键词输入框在卡片头里）；`el-input` stub 需实现 v-model 与 keyup（搜索用例）；`TagPostsView.spec.js` 需注入 Pinia（组件 setup 调 `useAuthStore()`）；`v-loading` 用空指令 `directives: { loading: () => {} }` 消掉解析告警。
> 3. **错误态与重试优于计划**：计划 `load()` 无 catch，接口失败会成为 unhandled rejection 且页面一片空白；实现给 `TagsView` / `TagPostsView` 增加 `error` 态、`catch` 与「重试」按钮，失败时清空列表并隐藏分页，与 axios 拦截器的统一提示配合；空态与错误态互斥。
> 4. **`.nav-link` 普通色用 `--jf-color-text-3` 而非计划片段的 `--jf-color-text-2`**：`--jf-color-text-2` 已在本轮 `tokens.css` 中作为无消费方令牌删除，照抄计划会引用未定义变量；`text-3` 是当前次级文本令牌。
> 5. **README 断点段落 5→6 属闸门必需**：`PostListItem` 承接原属 `HomeView` 的 `.post-item` 窄屏 `@media (max-width: 768px)`，断点字面量因此从 5 处变为 6 处；已同步 `breakpoint.spec.js`、`tokens.css`、`README.md` 三处闸门。
> 6. **额外默认构建无副作用**：除计划要求的 `VITE_BASE_PATH=/forum/ pnpm build` 外，实施时还跑过默认 `pnpm build` 验证根路径构建；产物未提交，无代码影响。
> 7. **P1 修复（本次评审新增，质量评审后修订措辞）**：`TagPostsView.vue` 原先只在 `onMounted` 加载，本意是覆盖同一路由记录内从 `/tags/java` 切到 `/tags/spring` 的复用场景；后经质量评审确认，`App.vue` 既有的 `<router-view :key="$route.fullPath">` 已使标签切换必然重建组件，故该缺陷在当前代码下不可复现。`watch(() => route.params.name)`（重置 `page = 0` 并 `load()`，请求序号丢弃过期响应）作为防御性守卫保留，避免将来去掉 key 时出现列表不刷新；`TagPostsView.spec.js` 的「路由标签名变化」用例覆盖无 key 的防御路径。
> 8. **P2 补测（本次评审新增）**：设计规格 §6 要求更新 `frontend/src/api/index.spec.js` 覆盖 `tagApi` 路径，实施时遗漏；现补两条断言——`tagApi.list({keyword,page,size})` → `GET /tags` 带 params，`tagApi.posts('java',{page})` → `GET /tags/posts` 且 name 在 params 中。

---

## Task 6: 文档更新

**Files:**
- Modify: `README.md`
- Modify: `docs/DEVELOPMENT.md`

- [ ] **Step 1: 更新 README**

在功能列表（含板块/帖子/评论/通知等）中补两项：

```markdown
- 帖子正文支持 GFM Markdown（标题/列表/代码块/表格/链接/外链图片），编辑页实时预览
- 标签检索：标签总览（按帖子数排序）与标签帖子列表，帖子卡片与详情页标签可点击
```

并在「已知限制」补：

```markdown
- 帖子正文按 Markdown 渲染。历史纯文本帖子中，以 `#`、`1.`、`-`、`>` 开头的行会被解释为 Markdown 语法（预期行为）。
```

- [ ] **Step 2: 更新 docs/DEVELOPMENT.md**

- 头部版本 `1.2` → `1.3`，日期改 `2026-10-19`。
- §3.2 基线：按 Task 4/5 实际执行结果填写（后端类数/用例数、前端文件数/用例数；不要沿用旧数字）。
- §4 新增 `4.8 第三轮：Markdown 渲染 + 标签检索（2026-10-19）`，写入：新增接口 `GET /api/tags`、`GET /api/tags/posts`；新增文件清单（对照本计划「文件结构总览」）；关键取舍（客户端渲染、标签聚合服务层分页、标签名走 query 参数）。
- §5.2 第三轮候选：把「Markdown 渲染」「标签检索」标记为已完成（其余候选保持未做）。
- §8 变更日志顶部加一行 `2026-10-19 feat 第三轮功能（提交哈希待回填）`。
- 接口总数从 31 更新为 33（§3.4 或对应小节）。

- [ ] **Step 3: 提交**

```bash
git add README.md docs/DEVELOPMENT.md
git commit -m "docs: 记录第三轮 Markdown 渲染与标签检索功能"
```

---

## Task 7: 全量验证与上线

- [ ] **Step 1: 后端全量测试**

Run: `cd /e/JavaSpace/forum && mvn -B test`
Expected: BUILD SUCCESS，0 failures。

- [ ] **Step 2: 前端全量测试与构建**

Run: `cd frontend && pnpm test && VITE_BASE_PATH=/forum/ pnpm build`
Expected: 测试全绿，构建成功。

- [ ] **Step 3: 提交并推送（触发 Jenkins 自动部署）**

```bash
git status --porcelain   # 应为空
git push origin master
```

- [ ] **Step 4: 等待 Jenkins 构建成功**

在 raspi 上查看（本机有 SSH 别名 `raspi`，pi 可 sudo）：

```bash
ssh raspi "sudo -n tail -n 5 /var/lib/jenkins/jobs/javaforum/builds/\\$(ls -1t /var/lib/jenkins/jobs/javaforum/builds | grep -E '^[0-9]+$' | head -1)/log; echo '---'; sudo -n cat /var/lib/jenkins/jobs/javaforum/builds/\\$(ls -1t /var/lib/jenkins/jobs/javaforum/builds | grep -E '^[0-9]+$' | head -1)/build.xml | grep -o '<result>[^<]*</result>'"
```

Expected: `Finished: SUCCESS`；若失败，按日志修复后重复 Task 7（每次修复都是一次提交）。

- [ ] **Step 5: 冒烟验证（在 raspi 上执行，nginx 只认 Host: raspi）**

```bash
ssh raspi "curl -s -H 'Host: raspi' http://127.0.0.1/forum/ | head -c 200; echo; \
curl -s -H 'Host: raspi' 'http://127.0.0.1/forum/api/tags' | head -c 300; echo; \
curl -s -H 'Host: raspi' 'http://127.0.0.1/forum/api/tags/posts?name=java' | head -c 300; echo; \
curl -s -H 'Host: raspi' 'http://127.0.0.1/forum/api/posts/1' | head -c 200"
```

Expected：`/forum/` 返回 index.html（含 `/forum/assets/`）；`/api/tags` 返回 `success:true` 与标签数组；`/api/tags/posts?name=java` 返回帖子分页；详情接口正常。

- [ ] **Step 6: 回填变更日志哈希**

```bash
git add docs/DEVELOPMENT.md
git commit -m "docs: 回填第三轮功能提交哈希"
git push origin master
```

（该 docs 提交同样会触发一次 Jenkins 构建；等其 SUCCESS 即可。）

---

## Self-Review 记录

- **Spec 覆盖**：§4 Markdown（Task 1/2/3）、§5 标签后端（Task 4）、§5.3–5.5 前端（Task 5）、§6 测试（各任务 TDD 步骤）、§7 文档与上线（Task 6/7）均有对应任务；§4.4 兼容与安全由 `markdown.spec.js` 与 breaks 配置覆盖。
- **类型/命名一致性**：`TagResponse(id, name, postCount)` 在集成测试与 `TagService` 中同名使用；`PostService.listByTag(tagName, page, size)` 与 Controller 调用一致；`tagApi.posts(name, params)` 与 `TagPostsView` 一致；`PostListItem` 保留 `.post-item`/`.like-btn`/`.tags` 类名以兼容既有测试选择器。
- **占位符检查**：无 TBD/TODO；文档基线数值要求执行后按实测填写（属数据依赖，不是未定义内容）。
- **风险**：`el-input` stub 必须实现 v-model（已在 Task 3 测试中给出）；`window.matchMedia` 在 jsdom 需手动打桩（Task 3/5 已给）。
