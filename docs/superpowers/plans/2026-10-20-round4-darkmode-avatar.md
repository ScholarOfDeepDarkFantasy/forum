# 第四轮：深色模式 + 头像上传 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 JavaForum 增加「跟随系统 + 手动覆盖并持久化」的深色模式，以及「服务端居中裁剪为 256×256 JPEG、nginx 直出、可移除」的头像功能，并随 Jenkins 自动部署到 raspi。

**Architecture:** 前端用模块级单例 composable `useTheme` 维护 `mode`，靠 `html.dark` 类 + `tokens.css` 双套 CSS 变量 + Element Plus 官方暗色变量文件联动配色，`index.html` 内联脚本负责首屏定色；头像走后端 multipart 接口：魔数校验 → EXIF 方向校正 → 居中裁剪 → 256×256 重编码 JPEG → 落 `${app.upload-dir}/avatars/<uuid>.jpg` → 更新 `users.avatar_url`，生产由 nginx `alias` 直出 `/forum/uploads/`，容器卷挂载主机目录。

**Tech Stack:** Vue 3.5 / Vite 8 / Pinia / Element Plus 2.14.7 / Vitest 5；Spring Boot 3.5.6 / Java 17 / metadata-extractor 2.19.0 / JUnit 5 + H2；podman-compose + nginx + Jenkins。

**约定：**
- 每个 Step 只做一件事；测试先红后绿，红了才写实现。
- 前端命令先 `cd frontend`（`vitest` 的 `process.cwd()` 必须是 `frontend`，`breakpoint.spec.js` / `tokens.spec.js` 都依赖这一点）；后端命令在仓库根 `cd /e/JavaSpace/forum`。
- 提交信息按 spec §7 逐字使用；Task 1~5 只 `git commit`，Task 6 才 `git push`。
- 不新增 Flyway 迁移；不新增 `@media` 字面量（`frontend/src/styles/breakpoint.spec.js` 精确要求全 `src` 恰有 6 处 `@media (max-width: 768px)`，且禁止 `min-width`）。
- 所有前端文件保持 LF；新文件用 `write` 工具一次性写入完整内容。
- 既有 143 个前端用例、39 个后端用例必须保持绿。

**设计文档：** `docs/superpowers/specs/2026-10-20-round4-darkmode-avatar-design.md`

---

## 文件结构总览

### 后端（新增）

| 文件 | 职责 |
|---|---|
| `src/main/java/org/song/forum/util/AvatarImageProcessor.java` | 纯函数图片处理：魔数判定、EXIF Orientation 读取、方向校正、居中裁剪、缩放到 256×256、JPEG 重编码 |
| `src/main/java/org/song/forum/service/AvatarService.java` | 编排上传/删除：尺寸校验、调处理器、落盘 `${app.upload-dir}/avatars/`、删旧文件、更新 `users.avatar_url` |
| `src/main/java/org/song/forum/controller/UserController.java` | `POST/DELETE /api/users/me/avatar` |
| `src/main/java/org/song/forum/config/WebConfig.java` | 把 `/uploads/**` 映射到 `file:${app.upload-dir}/`（仅本地开发用，生产由 nginx 直出） |
| `src/test/java/org/song/forum/util/AvatarImageProcessorTest.java` | 图片处理单测：方向 1–8、居中裁剪、256 缩放、魔数、坏数据 |
| `src/test/java/org/song/forum/controller/AvatarFlowIntegrationTest.java` | 头像接口 10 个集成用例（`@DynamicPropertySource` + `@TempDir`） |

### 后端（修改）

| 文件 | 改动 |
|---|---|
| `pom.xml` | 新增 `metadata-extractor:2.19.0` 依赖 |
| `src/main/resources/application.yml` | `spring.servlet.multipart`（2MB/3MB）+ `app.upload-dir: ${UPLOAD_DIR:./uploads}` |
| `src/main/java/org/song/forum/config/SecurityConfig.java` | `PUBLIC_GET` 增加 `/uploads/**` |
| `src/main/java/org/song/forum/exception/GlobalExceptionHandler.java` | `MaxUploadSizeExceededException` → 400「图片不能超过 2MB」 |
| `src/main/java/org/song/forum/dto/user/UpdateProfileRequest.java` | 删除 `avatarUrl` 字段 |
| `src/main/java/org/song/forum/service/AuthService.java` | `updateProfile` 不再 set `avatarUrl` |

### 前端（新增）

| 文件 | 职责 |
|---|---|
| `frontend/src/composables/useTheme.js` | 模块级单例主题状态：`mode`/`isDark`/`setMode`/`toggle`/`initTheme`，同步 `html.dark` 与 `colorScheme` |
| `frontend/src/components/ThemeToggle.vue` | 顶栏主题下拉：跟随系统 / 浅色 / 深色，当前项打勾 |
| `frontend/src/styles/highlight-dark.css` | highlight.js `github-dark` 规则加 `html.dark` 前缀（只在暗色生效） |
| `frontend/src/styles/tokens.spec.js` | 令牌守卫：双套集合一致、11 个令牌齐全、6 位十六进制、必须有消费方；另守 `main.js` 导入顺序、`index.html` 引导脚本、4 处字面量已令牌化 |
| `frontend/src/composables/useTheme.spec.js` | 主题状态机单测（默认 system、跟随系统变化、持久化、坏值、toggle、matchMedia 缺失） |
| `frontend/src/components/ThemeToggle.spec.js` | 下拉三项、图标、aria、当前项打勾、切换后的 class 与存储 |
| `frontend/src/utils/avatar.js` | `resolveAvatarUrl(url)`：`/uploads/` 前缀拼 `BASE_URL`，其余原样 |
| `frontend/src/components/UserAvatar.vue` | 统一头像组件：有 url 用 `el-avatar :src`，无 url / `@error` 回退首字母 |
| `frontend/src/utils/avatar.spec.js` | `resolveAvatarUrl` 单测 |
| `frontend/src/components/UserAvatar.spec.js` | 头像组件单测（有 url、无 url、error 回退） |

### 前端（修改）

| 文件 | 改动 |
|---|---|
| `frontend/src/styles/tokens.css` | `:root` 增 4 个语义色令牌 + 新增 `html.dark` 覆盖块（11 个令牌） |
| `frontend/src/main.js` | 样式导入固定为 8 行顺序（含 EP 暗色变量、`highlight-dark.css`），`app.mount` 前调 `initTheme()` |
| `frontend/index.html` | `<head>` 内新增防闪烁内联脚本 |
| `frontend/src/App.vue` | `.nav-actions` 最左加 `<ThemeToggle />`；顶栏头像换 `UserAvatar` |
| `frontend/src/styles/markdown.css` | `pre` 背景改 `var(--jf-color-code-bg)`；追加 `pre code.hljs { background: transparent }` |
| `frontend/src/views/PostDetailView.vue` | 2 处字面量改令牌；评论/回复头像换 `UserAvatar` |
| `frontend/src/views/NotificationsView.vue` | 2 处字面量改令牌 |
| `frontend/src/views/ProfileView.vue` | 删除「头像 URL」输入框，改为上传/移除区块（含客户端校验） |
| `frontend/src/views/ProfileView.spec.js` | 新增上传/移除/非法类型/超限 4 个用例，`@/api` mock 增加 `userApi` |
| `frontend/src/api/index.js` | 新增 `userApi.uploadAvatar/removeAvatar` |
| `frontend/src/api/index.spec.js` | 新增 `userApi` 两个端点断言 |

### 部署与文档

| 文件 | 改动 |
|---|---|
| `Dockerfile` | `mkdir -p /app/logs /app/uploads` + `ENV UPLOAD_DIR=/app/uploads` |
| `docker-compose.yml` | `volumes` 挂 `${UPLOAD_HOST_DIR:-./uploads}:/app/uploads`、`environment` 加 `UPLOAD_DIR` |
| `deploy/deploy.sh` | 建上传目录、`podman unshare chown`、`.env` 写 `UPLOAD_HOST_DIR`、`/forum/uploads/` 必须 404 的冒烟守卫 |
| `deploy/nginx/forum.conf` | `/forum/` 之前新增 `location ^~ /forum/uploads/` |
| `.gitignore` | 新增 `/uploads/` |
| `.env.example` | raspi 段新增 `UPLOAD_HOST_DIR` |
| `README.md` | 功能列表、测试计数、部署文件表、令牌与目录结构 |
| `docs/DEVELOPMENT.md` | §3.2 基线、§3.4 接口清单（33→35）、新增 §4.9、§8 变更日志（哈希先写 `本次提交`，Task 6 回填） |
| `docs/deployment/README.md` | 上传目录创建/属主/备份、`UPLOAD_HOST_DIR`、冒烟 404 |

---

## Task 1: 深色模式前端

**Files:**
- Create: `frontend/src/styles/tokens.spec.js`
- Modify: `frontend/src/styles/tokens.css`
- Create: `frontend/src/composables/useTheme.spec.js`
- Create: `frontend/src/composables/useTheme.js`
- Create: `frontend/src/components/ThemeToggle.spec.js`
- Create: `frontend/src/components/ThemeToggle.vue`
- Create: `frontend/src/styles/highlight-dark.css`
- Modify: `frontend/src/styles/markdown.css:53-63`
- Modify: `frontend/src/main.js:8-12`
- Modify: `frontend/index.html:7`
- Modify: `frontend/src/views/PostDetailView.vue:390,400,417`
- Modify: `frontend/src/views/NotificationsView.vue:141,159`
- Modify: `frontend/src/App.vue:5-8,53`

- [ ] **Step 1: 写令牌守卫 spec（此时应失败）**

Create `frontend/src/styles/tokens.spec.js`：

```js
import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'

const SRC_DIR = resolve(process.cwd(), 'src')
const TOKENS_FILE = join(SRC_DIR, 'styles', 'tokens.css')

const EXPECTED_COLOR_TOKENS = [
  '--jf-color-primary',
  '--jf-color-text-1',
  '--jf-color-text-3',
  '--jf-color-border',
  '--jf-color-bg-page',
  '--jf-color-bg-card',
  '--jf-color-bg-hover',
  '--jf-color-code-bg',
  '--jf-color-text-weak',
  '--jf-color-unread-bg',
  '--jf-color-anchor-bg'
]

function blockOf(css, selector) {
  const start = css.indexOf(`${selector} {`)
  if (start === -1) return ''
  const from = css.indexOf('{', start)
  const to = css.indexOf('}', from)
  return css.slice(from + 1, to)
}

function colorTokens(block) {
  const tokens = {}
  for (const line of block.split('\n')) {
    const match = line.match(/^\s*(--jf-color-[\w-]+)\s*:\s*([^;]+);/)
    if (match) tokens[match[1]] = match[2].trim()
  }
  return tokens
}

function walk(dir) {
  const files = []
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) files.push(...walk(full))
    else if (/\.(vue|css|js)$/.test(name)) files.push(full)
  }
  return files
}

const css = readFileSync(TOKENS_FILE, 'utf8')
const lightTokens = colorTokens(blockOf(css, ':root'))
const darkTokens = colorTokens(blockOf(css, 'html.dark'))

describe('颜色令牌', () => {
  it(':root 与 html.dark 的 --jf-color-* 集合完全一致', () => {
    expect(Object.keys(darkTokens).sort()).toEqual(Object.keys(lightTokens).sort())
  })

  it('令牌全集与设计文档一致（11 个）', () => {
    expect(Object.keys(lightTokens).sort()).toEqual([...EXPECTED_COLOR_TOKENS].sort())
  })

  it('每个颜色令牌都是 6 位小写十六进制', () => {
    for (const [name, value] of Object.entries({ ...lightTokens, ...darkTokens })) {
      expect(value, `${name} 的取值格式`).toMatch(/^#[0-9a-f]{6}$/)
    }
  })

  it('每个颜色令牌在 src 下至少有一处 var() 消费', () => {
    const consumers = walk(SRC_DIR)
      .filter((file) => file !== TOKENS_FILE && !file.endsWith('.spec.js'))
      .map((file) => readFileSync(file, 'utf8'))
      .join('\n')
    for (const name of EXPECTED_COLOR_TOKENS) {
      expect(consumers.includes(`var(${name})`), `${name} 缺少消费方`).toBe(true)
    }
  })

  it('页面底/卡片底在亮暗两套中不同，主色保持同值', () => {
    expect(lightTokens['--jf-color-bg-page']).not.toBe(darkTokens['--jf-color-bg-page'])
    expect(lightTokens['--jf-color-bg-card']).not.toBe(darkTokens['--jf-color-bg-card'])
    expect(darkTokens['--jf-color-primary']).toBe(lightTokens['--jf-color-primary'])
  })
})
```

- [ ] **Step 2: 运行 spec，确认失败**

Run: `cd frontend && pnpm exec vitest run src/styles/tokens.spec.js`
Expected: FAIL —— `html.dark` 块还不存在，第一个用例报 `expected [] to deeply equal [...]`；第 2 个用例报缺少 4 个新令牌。

- [ ] **Step 3: 在 `tokens.css` 里补齐双套令牌**

在 `frontend/src/styles/tokens.css` 的 `:root` 中，`--jf-color-bg-hover: #f8f9fb;`（第 13 行）之后插入 4 行：

```css
  --jf-color-code-bg: #f6f8fa;
  --jf-color-text-weak: #c0c4cc;
  --jf-color-unread-bg: #f0f7ff;
  --jf-color-anchor-bg: #fff8e6;
```

然后在文件末尾（第 53 行 `}` 之后）追加：

```css
/* 深色模式：html.dark 由 useTheme / index.html 内联脚本挂到 <html> 上。
   令牌集合必须与 :root 完全一致，tokens.spec.js 会守卫。
   取值对齐 Element Plus 暗色调色板（--el-bg-color-page:#0a0a0a、
   --el-bg-color-overlay:#1d1e1f、--el-text-color-primary:#e5eaf3 等），
   避免自研区域与 EP 组件出现两套灰阶。 */
html.dark {
  --jf-color-primary: #409eff;
  --jf-color-text-1: #e5eaf3;
  --jf-color-text-3: #a3a6ad;
  --jf-color-border: #4c4d4f;
  --jf-color-bg-page: #0a0a0a;
  --jf-color-bg-card: #1d1e1f;
  --jf-color-bg-hover: #262727;
  --jf-color-code-bg: #161b22;
  --jf-color-text-weak: #8d9095;
  --jf-color-unread-bg: #1b2b3d;
  --jf-color-anchor-bg: #2f2a1a;
}
```

- [ ] **Step 4: 运行 spec，确认通过**

Run: `cd frontend && pnpm exec vitest run src/styles/tokens.spec.js`
Expected: PASS（5 个用例）。

- [ ] **Step 5: 写 `useTheme` 的失败测试**

Create `frontend/src/composables/useTheme.spec.js`：

```js
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { nextTick } from 'vue'

const STORAGE_KEY = 'jf_theme'
let changeHandlers = []
let systemPrefersDark = false

function installMatchMedia(prefersDark) {
  systemPrefersDark = prefersDark
  changeHandlers = []
  window.matchMedia = (query) => ({
    get matches() {
      return systemPrefersDark
    },
    media: query,
    onchange: null,
    addEventListener: (type, handler) => {
      if (type === 'change') changeHandlers.push(handler)
    },
    removeEventListener: (type, handler) => {
      changeHandlers = changeHandlers.filter((item) => item !== handler)
    },
    addListener: (handler) => changeHandlers.push(handler),
    removeListener: (handler) => {
      changeHandlers = changeHandlers.filter((item) => item !== handler)
    },
    dispatchEvent: () => false
  })
}

function emitSystemChange(prefersDark) {
  systemPrefersDark = prefersDark
  for (const handler of [...changeHandlers]) handler({ matches: prefersDark })
}

async function loadTheme() {
  vi.resetModules()
  const mod = await import('./useTheme.js')
  return mod.useTheme()
}

beforeEach(() => {
  localStorage.clear()
  document.documentElement.className = ''
  document.documentElement.style.colorScheme = ''
  installMatchMedia(false)
})

describe('useTheme', () => {
  it('默认跟随系统，isDark 取系统值', async () => {
    const theme = await loadTheme()
    expect(theme.mode.value).toBe('system')
    expect(theme.isDark.value).toBe(false)
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('system 模式下系统主题变化实时同步 html 类', async () => {
    const theme = await loadTheme()
    emitSystemChange(true)
    await nextTick()
    expect(theme.isDark.value).toBe(true)
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('setMode("dark") 写 localStorage、挂 dark 类并同步 colorScheme', async () => {
    const theme = await loadTheme()
    theme.setMode('dark')
    await nextTick()
    expect(localStorage.getItem(STORAGE_KEY)).toBe('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(document.documentElement.style.colorScheme).toBe('dark')
  })

  it('setMode("light") 在系统为暗时仍是亮色', async () => {
    installMatchMedia(true)
    const theme = await loadTheme()
    theme.setMode('light')
    await nextTick()
    expect(localStorage.getItem(STORAGE_KEY)).toBe('light')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
    expect(document.documentElement.style.colorScheme).toBe('light')
  })

  it('localStorage 坏值回退 system', async () => {
    localStorage.setItem(STORAGE_KEY, 'purple')
    const theme = await loadTheme()
    expect(theme.mode.value).toBe('system')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('已保存 dark 时导入即应用 dark', async () => {
    localStorage.setItem(STORAGE_KEY, 'dark')
    const theme = await loadTheme()
    expect(theme.mode.value).toBe('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('选择跟随系统时删除 localStorage 键', async () => {
    const theme = await loadTheme()
    theme.setMode('dark')
    theme.setMode('system')
    await nextTick()
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull()
  })

  it('toggle 在当前实际效果上取反', async () => {
    installMatchMedia(true)
    const theme = await loadTheme()
    expect(theme.isDark.value).toBe(true)
    theme.toggle()
    await nextTick()
    expect(theme.mode.value).toBe('light')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('matchMedia 缺失时按 false 处理且不抛错', async () => {
    delete window.matchMedia
    const theme = await loadTheme()
    expect(theme.mode.value).toBe('system')
    expect(theme.isDark.value).toBe(false)
  })

  it('切到显式模式后不再监听系统变化', async () => {
    const theme = await loadTheme()
    expect(changeHandlers.length).toBe(1)
    theme.setMode('dark')
    await nextTick()
    expect(changeHandlers.length).toBe(0)
  })
})
```

- [ ] **Step 6: 运行测试，确认失败**

Run: `cd frontend && pnpm exec vitest run src/composables/useTheme.spec.js`
Expected: FAIL —— `Failed to resolve import "./useTheme.js"`。

- [ ] **Step 7: 实现 `useTheme.js`**

Create `frontend/src/composables/useTheme.js`：

```js
import { computed, ref, watchEffect } from 'vue'

export const STORAGE_KEY = 'jf_theme'
const VALID_MODES = ['system', 'light', 'dark']

function readStoredMode() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    return saved === 'light' || saved === 'dark' ? saved : 'system'
  } catch {
    return 'system'
  }
}

function darkMediaQuery() {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return null
  return window.matchMedia('(prefers-color-scheme: dark)')
}

// 模块级单例：组件、路由守卫与入口 initTheme 共用同一份状态。
const mode = ref(readStoredMode())
const query = darkMediaQuery()
const systemPrefersDark = ref(query ? query.matches : false)
const isDark = computed(
  () => mode.value === 'dark' || (mode.value === 'system' && systemPrefersDark.value)
)

let listenerBound = false

function onSystemChange(event) {
  systemPrefersDark.value = event.matches
}

function syncSystemListener() {
  if (!query) return
  if (mode.value === 'system' && !listenerBound) {
    query.addEventListener('change', onSystemChange)
    listenerBound = true
  } else if (mode.value !== 'system' && listenerBound) {
    query.removeEventListener('change', onSystemChange)
    listenerBound = false
  }
}

function applyClass(dark) {
  if (typeof document === 'undefined') return
  const root = document.documentElement
  root.classList.toggle('dark', dark)
  root.style.colorScheme = dark ? 'dark' : 'light'
}

function persist(next) {
  try {
    if (next === 'system') localStorage.removeItem(STORAGE_KEY)
    else localStorage.setItem(STORAGE_KEY, next)
  } catch {
    // 隐私模式 / localStorage 被禁用：忽略，仅当次会话生效
  }
}

function setMode(next) {
  mode.value = VALID_MODES.includes(next) ? next : 'system'
  persist(mode.value)
}

function toggle() {
  setMode(isDark.value ? 'light' : 'dark')
}

watchEffect(() => {
  applyClass(isDark.value)
  syncSystemListener()
})

function initTheme() {
  applyClass(isDark.value)
  return isDark.value
}

export function useTheme() {
  return { mode, isDark, systemPrefersDark, setMode, toggle, initTheme }
}
```

- [ ] **Step 8: 运行测试，确认通过**

Run: `cd frontend && pnpm exec vitest run src/composables/useTheme.spec.js`
Expected: PASS（10 个用例）。

- [ ] **Step 9: 写 `ThemeToggle` 的失败测试**

Create `frontend/src/components/ThemeToggle.spec.js`：

```js
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { Moon, MoonNight, Sunny } from '@element-plus/icons-vue'
import ThemeToggle from './ThemeToggle.vue'
import { useTheme } from '@/composables/useTheme'

const stubs = {
  'el-dropdown': {
    name: 'ElDropdown',
    emits: ['command'],
    template: '<div class="dropdown-stub"><slot /><slot name="dropdown" /></div>'
  },
  'el-dropdown-menu': {
    name: 'ElDropdownMenu',
    template: '<div class="menu-stub"><slot /></div>'
  },
  'el-dropdown-item': {
    name: 'ElDropdownItem',
    props: ['command'],
    template: '<div class="menu-item"><slot /></div>'
  },
  'el-button': {
    name: 'ElButton',
    props: ['icon', 'circle', 'text'],
    template: '<button><slot /></button>'
  },
  'el-icon': { name: 'ElIcon', template: '<i><slot /></i>' }
}

function mountToggle() {
  return mount(ThemeToggle, { global: { stubs } })
}

beforeEach(async () => {
  localStorage.clear()
  document.documentElement.className = ''
  document.documentElement.style.colorScheme = ''
  useTheme().setMode('system')
  await nextTick()
})

describe('ThemeToggle', () => {
  it('渲染三个菜单项，触发按钮带 aria-label', () => {
    const wrapper = mountToggle()
    expect(wrapper.findAll('.menu-item')).toHaveLength(3)
    expect(wrapper.text()).toContain('跟随系统')
    expect(wrapper.text()).toContain('浅色')
    expect(wrapper.text()).toContain('深色')
    expect(wrapper.find('button').attributes('aria-label')).toBe('切换主题')
  })

  it('默认为 system：当前项打勾，按钮图标是 MoonNight', () => {
    const wrapper = mountToggle()
    expect(wrapper.find('.is-active').text()).toBe('跟随系统')
    expect(wrapper.findComponent({ name: 'ElButton' }).props('icon')).toBe(MoonNight)
  })

  it('选择深色后写入 localStorage、挂 dark 类并切换图标', async () => {
    const wrapper = mountToggle()
    wrapper.findComponent({ name: 'ElDropdown' }).vm.$emit('command', 'dark')
    await nextTick()
    expect(localStorage.getItem('jf_theme')).toBe('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(wrapper.find('.is-active').text()).toBe('深色')
    expect(wrapper.findComponent({ name: 'ElButton' }).props('icon')).toBe(Moon)
  })

  it('选择浅色后移除 dark 类并保存 light', async () => {
    const wrapper = mountToggle()
    wrapper.findComponent({ name: 'ElDropdown' }).vm.$emit('command', 'dark')
    await nextTick()
    wrapper.findComponent({ name: 'ElDropdown' }).vm.$emit('command', 'light')
    await nextTick()
    expect(localStorage.getItem('jf_theme')).toBe('light')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
    expect(wrapper.findComponent({ name: 'ElButton' }).props('icon')).toBe(Sunny)
  })

  it('选择跟随系统后删除 localStorage 键', async () => {
    const wrapper = mountToggle()
    wrapper.findComponent({ name: 'ElDropdown' }).vm.$emit('command', 'dark')
    await nextTick()
    wrapper.findComponent({ name: 'ElDropdown' }).vm.$emit('command', 'system')
    await nextTick()
    expect(localStorage.getItem('jf_theme')).toBeNull()
    expect(wrapper.find('.is-active').text()).toBe('跟随系统')
  })
})
```

- [ ] **Step 10: 运行测试，确认失败**

Run: `cd frontend && pnpm exec vitest run src/components/ThemeToggle.spec.js`
Expected: FAIL —— `Failed to resolve import "./ThemeToggle.vue"`。

- [ ] **Step 11: 实现 `ThemeToggle.vue`**

Create `frontend/src/components/ThemeToggle.vue`：

```vue
<script setup>
import { computed } from 'vue'
import { Check, Moon, MoonNight, Sunny } from '@element-plus/icons-vue'
import { useTheme } from '@/composables/useTheme'

const { mode, setMode } = useTheme()

const MODE_ITEMS = [
  { value: 'system', label: '跟随系统', icon: MoonNight },
  { value: 'light', label: '浅色', icon: Sunny },
  { value: 'dark', label: '深色', icon: Moon }
]

const currentIcon = computed(
  () => (MODE_ITEMS.find((item) => item.value === mode.value) || MODE_ITEMS[0]).icon
)
const currentLabel = computed(
  () => (MODE_ITEMS.find((item) => item.value === mode.value) || MODE_ITEMS[0]).label
)

function handleCommand(command) {
  setMode(command)
}
</script>

<template>
  <el-dropdown trigger="click" @command="handleCommand">
    <el-button
      circle
      text
      :icon="currentIcon"
      aria-label="切换主题"
      :title="`主题：${currentLabel}`"
    />
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item v-for="item in MODE_ITEMS" :key="item.value" :command="item.value">
          <span class="theme-item" :class="{ 'is-active': mode === item.value }">
            <el-icon>
              <Check v-if="mode === item.value" />
              <component :is="item.icon" v-else />
            </el-icon>
            <span class="theme-label">{{ item.label }}</span>
          </span>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style scoped>
.theme-item {
  display: flex;
  align-items: center;
  gap: var(--jf-space-2);
}
.theme-label {
  font-size: var(--jf-font-body);
}
</style>
```

- [ ] **Step 12: 运行测试，确认通过**

Run: `cd frontend && pnpm exec vitest run src/components/ThemeToggle.spec.js`
Expected: PASS（5 个用例）。

- [ ] **Step 13: 追加 `main.js` 导入顺序与 `index.html` 引导脚本的失败断言**

在 `frontend/src/styles/tokens.spec.js` 末尾追加：

```js
const MAIN_FILE = join(SRC_DIR, 'main.js')
const INDEX_HTML = resolve(process.cwd(), 'index.html')

describe('main.js 样式导入顺序', () => {
  const source = readFileSync(MAIN_FILE, 'utf8')
  const order = [
    'element-plus/dist/index.css',
    'element-plus/theme-chalk/dark/css-vars.css',
    './styles/tokens.css',
    './styles/common.css',
    './styles/markdown.css',
    'highlight.js/styles/github.css',
    './styles/highlight-dark.css',
    './style.css'
  ]

  it('8 个样式导入按设计顺序出现', () => {
    const positions = order.map((path) => source.indexOf(path))
    for (const [index, position] of positions.entries()) {
      expect(position, `${order[index]} 未导入`).toBeGreaterThan(-1)
    }
    expect(positions).toEqual([...positions].sort((a, b) => a - b))
  })
})

describe('index.html 防闪烁引导脚本', () => {
  const html = readFileSync(INDEX_HTML, 'utf8')

  it('在第一个模块脚本之前读取 jf_theme', () => {
    const inline = html.indexOf("localStorage.getItem('jf_theme')")
    const moduleScript = html.indexOf('type="module"')
    expect(inline).toBeGreaterThan(-1)
    expect(moduleScript).toBeGreaterThan(-1)
    expect(inline).toBeLessThan(moduleScript)
  })

  it('暗色时添加 dark 类并设置 colorScheme', () => {
    expect(html).toContain("classList.add('dark')")
    expect(html).toContain('style.colorScheme')
  })
})
```

- [ ] **Step 14: 运行测试，确认新增断言失败**

Run: `cd frontend && pnpm exec vitest run src/styles/tokens.spec.js`
Expected: FAIL —— `element-plus/theme-chalk/dark/css-vars.css 未导入` 与 `expected -1 to be greater than -1`（`index.html` 里没有脚本）。

- [ ] **Step 15: 改 `main.js` 与 `index.html`**

`frontend/src/main.js` 全文替换为：

```js
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import App from './App.vue'
import router from './router'
import './styles/tokens.css'
import './styles/common.css'
import './styles/markdown.css'
import 'highlight.js/styles/github.css'
import './styles/highlight-dark.css'
import './style.css'
import { initTheme } from './composables/useTheme'

initTheme()

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
```

`frontend/index.html` 的 `<title>` 之后、`</head>` 之前插入：

```html
    <script>
      ;(function () {
        try {
          var saved = localStorage.getItem('jf_theme')
          var dark =
            saved === 'dark' ||
            (saved !== 'light' && window.matchMedia('(prefers-color-scheme: dark)').matches)
          if (dark) document.documentElement.classList.add('dark')
          document.documentElement.style.colorScheme = dark ? 'dark' : 'light'
        } catch (e) {}
      })()
    </script>
```

- [ ] **Step 16: 运行测试，确认通过**

Run: `cd frontend && pnpm exec vitest run src/styles/tokens.spec.js`
Expected: PASS（8 个用例：令牌 5 + 导入顺序/首屏脚本/暗色应用 3）。

- [ ] **Step 17: 写「4 处字面量已令牌化」的失败断言**

在 `frontend/src/styles/tokens.spec.js` 末尾追加：

```js
describe('深色模式字面量清理', () => {
  const files = {
    'styles/markdown.css': join(SRC_DIR, 'styles', 'markdown.css'),
    'views/PostDetailView.vue': join(SRC_DIR, 'views', 'PostDetailView.vue'),
    'views/NotificationsView.vue': join(SRC_DIR, 'views', 'NotificationsView.vue')
  }
  const banned = ['#f6f8fa', '#c0c4cc', '#f0f7ff', '#fff8e6']

  for (const [label, file] of Object.entries(files)) {
    it(`${label} 不再出现一次性语义色`, () => {
      const source = readFileSync(file, 'utf8')
      for (const hex of banned) {
        expect(source.includes(hex), `${label} 仍含 ${hex}`).toBe(false)
      }
    })
  }
})
```

- [ ] **Step 18: 运行测试，确认失败**

Run: `cd frontend && pnpm exec vitest run src/styles/tokens.spec.js`
Expected: FAIL —— `styles/markdown.css 仍含 #f6f8fa`、`views/PostDetailView.vue 仍含 #c0c4cc` 等 3 个用例。

- [ ] **Step 19: 替换 4 处字面量**

`frontend/src/styles/markdown.css` 第 53-63 行改为：

```css
.markdown-body pre {
  padding: var(--jf-space-3);
  overflow-x: auto;
  border-radius: var(--jf-radius-md);
  background: var(--jf-color-code-bg);
}
.markdown-body pre code {
  padding: 0;
  font-size: 0.9em;
  background: transparent;
}
.markdown-body pre code.hljs {
  background: transparent;
}
```

`frontend/src/views/PostDetailView.vue`：

```css
.comment-time {
  color: var(--jf-color-text-weak);
  font-size: var(--jf-font-small);
}
```

```css
.comment-content.deleted {
  color: var(--jf-color-text-weak);
  font-style: italic;
}
```

```css
.anchor-highlight {
  background: var(--jf-color-anchor-bg);
  transition: background 0.4s ease;
}
```

`frontend/src/views/NotificationsView.vue`：

```css
.notif.unread {
  background: var(--jf-color-unread-bg);
}
```

```css
.notif-time {
  color: var(--jf-color-text-weak);
  font-size: var(--jf-font-small);
  flex-shrink: 0;
}
```

（这三处原有的「一次性语义色」注释一并删除或改写为令牌说明；只删注释、不改声明外的其它规则。）

- [ ] **Step 20: 运行测试，确认通过**

Run: `cd frontend && pnpm exec vitest run src/styles/tokens.spec.js`
Expected: PASS（11 个用例：8 + 字面量清理 3）。

- [ ] **Step 21: 新增 `highlight-dark.css` 并接线**

Create `frontend/src/styles/highlight-dark.css`：

```css
/**
 * 来自 highlight.js 11.12 github-dark 主题（MIT），由构建期手工同步。
 * 与亮色 github.css 并存：所有规则加 html.dark 前缀，只在暗色生效，
 * 因此切换主题不需要换 <link>，也没有闪烁。
 * 背景统一交给容器（.markdown-body pre 用 --jf-color-code-bg），
 * 这里只保留文字色，避免与容器底色叠成「双层深色」。
 */
html.dark .hljs {
  color: #c9d1d9;
}
html.dark .hljs-doctag,
html.dark .hljs-keyword,
html.dark .hljs-meta .hljs-keyword,
html.dark .hljs-template-tag,
html.dark .hljs-template-variable,
html.dark .hljs-type,
html.dark .hljs-variable.language_ {
  color: #ff7b72;
}
html.dark .hljs-title,
html.dark .hljs-title.class_,
html.dark .hljs-title.class_.inherited__,
html.dark .hljs-title.function_ {
  color: #d2a8ff;
}
html.dark .hljs-attr,
html.dark .hljs-attribute,
html.dark .hljs-literal,
html.dark .hljs-meta,
html.dark .hljs-number,
html.dark .hljs-operator,
html.dark .hljs-variable,
html.dark .hljs-selector-attr,
html.dark .hljs-selector-class,
html.dark .hljs-selector-id {
  color: #79c0ff;
}
html.dark .hljs-regexp,
html.dark .hljs-string,
html.dark .hljs-meta .hljs-string {
  color: #a5d6ff;
}
html.dark .hljs-built_in,
html.dark .hljs-symbol {
  color: #ffa657;
}
html.dark .hljs-comment,
html.dark .hljs-code,
html.dark .hljs-formula {
  color: #8b949e;
}
html.dark .hljs-name,
html.dark .hljs-quote,
html.dark .hljs-selector-tag,
html.dark .hljs-selector-pseudo {
  color: #7ee787;
}
html.dark .hljs-subst {
  color: #c9d1d9;
}
html.dark .hljs-section {
  color: #1f6feb;
  font-weight: bold;
}
html.dark .hljs-bullet {
  color: #f2cc60;
}
html.dark .hljs-emphasis {
  color: #c9d1d9;
  font-style: italic;
}
html.dark .hljs-strong {
  color: #c9d1d9;
  font-weight: bold;
}
html.dark .hljs-addition {
  color: #aff5b4;
  background-color: #033a16;
}
html.dark .hljs-deletion {
  color: #ffdcd7;
  background-color: #67060c;
}
```

`frontend/src/main.js` 的导入已在 Step 15 加好（`./styles/highlight-dark.css` 紧跟 `github.css`），本步只需确认文件存在。

- [ ] **Step 22: 在顶栏接入开关**

`frontend/src/App.vue` 第 7 行（`import { useNotificationStore } from '@/stores/notifications'`）之后插入一行导入：

```js
import ThemeToggle from '@/components/ThemeToggle.vue'
```

`frontend/src/App.vue` 的 `<div class="nav-actions">`（第 53 行）内、`<template v-if="auth.isLoggedIn">` 之前插入：

```html
          <ThemeToggle />
```

- [ ] **Step 23: 跑前端全量测试**

Run: `cd frontend && pnpm test`
Expected: 全绿（20 个既有文件 + 3 个新文件；既有 143 例不回归）。若 `breakpoint.spec.js` 报断点数不对，说明误加了 `@media`，删掉新断点而不是改断言。

- [ ] **Step 24: 提交**

```bash
git add frontend/index.html frontend/src/App.vue frontend/src/main.js \
  frontend/src/styles/tokens.css frontend/src/styles/tokens.spec.js \
  frontend/src/styles/markdown.css frontend/src/styles/highlight-dark.css \
  frontend/src/composables/useTheme.js frontend/src/composables/useTheme.spec.js \
  frontend/src/components/ThemeToggle.vue frontend/src/components/ThemeToggle.spec.js \
  frontend/src/views/PostDetailView.vue frontend/src/views/NotificationsView.vue
git commit -m "feat: 新增深色模式与主题切换"
```

---

## Task 2: 头像上传后端

**Files:**
- Create: `src/test/java/org/song/forum/util/AvatarImageProcessorTest.java`
- Modify: `pom.xml:78`（Flyway 依赖之后）
- Create: `src/main/java/org/song/forum/util/AvatarImageProcessor.java`
- Create: `src/test/java/org/song/forum/controller/AvatarFlowIntegrationTest.java`
- Create: `src/main/java/org/song/forum/service/AvatarService.java`
- Create: `src/main/java/org/song/forum/controller/UserController.java`
- Create: `src/main/java/org/song/forum/config/WebConfig.java`
- Modify: `src/main/resources/application.yml:15-16`（multipart）与文件末尾（`app.upload-dir`）
- Modify: `src/main/java/org/song/forum/config/SecurityConfig.java:31-40`
- Modify: `src/main/java/org/song/forum/exception/GlobalExceptionHandler.java:13-15`（import）与类末尾（handler）
- Modify: `src/main/java/org/song/forum/dto/user/UpdateProfileRequest.java:16-17`
- Modify: `src/main/java/org/song/forum/service/AuthService.java:91`

- [ ] **Step 1: 写图片处理的失败测试**

Create `src/test/java/org/song/forum/util/AvatarImageProcessorTest.java`：

```java
package org.song.forum.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.song.forum.exception.BusinessException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 纯函数测试：方向映射、居中裁剪、缩放到 256、魔数判定、坏数据。
 */
class AvatarImageProcessorTest {

    /** 每个像素的颜色编码自身坐标：(x &lt;&lt; 16) | (y &lt;&lt; 8) | 7，便于断言映射 */
    private static BufferedImage gridImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, (x << 16) | (y << 8) | 7);
            }
        }
        return image;
    }

    private static int expectedRgb(int x, int y) {
        return (x << 16) | (y << 8) | 7;
    }

    private static int colorAt(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) & 0xFFFFFF;
    }

    private static int[] sourcePixel(int orientation, int x, int y, int width, int height) {
        return switch (orientation) {
            case 2 -> new int[]{width - 1 - x, y};
            case 3 -> new int[]{width - 1 - x, height - 1 - y};
            case 4 -> new int[]{x, height - 1 - y};
            case 5 -> new int[]{y, x};
            case 6 -> new int[]{y, height - 1 - x};
            case 7 -> new int[]{width - 1 - y, height - 1 - x};
            case 8 -> new int[]{width - 1 - y, x};
            default -> new int[]{x, y};
        };
    }

    private static byte[] jpegBytes(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private static byte[] pngBytes(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void jpegMagicNumberDetected() throws IOException {
        assertThat(AvatarImageProcessor.detectType(jpegBytes(10, 10)))
                .isEqualTo(AvatarImageProcessor.ImageType.JPEG);
    }

    @Test
    void pngMagicNumberDetected() throws IOException {
        assertThat(AvatarImageProcessor.detectType(pngBytes(10, 10)))
                .isEqualTo(AvatarImageProcessor.ImageType.PNG);
    }

    @Test
    void unsupportedMagicNumberRejected() {
        assertThat(AvatarImageProcessor.detectType("GIF89a....".getBytes())).isNull();
        assertThat(AvatarImageProcessor.detectType("hello".getBytes())).isNull();
        assertThat(AvatarImageProcessor.detectType(new byte[]{1, 2})).isNull();
        assertThat(AvatarImageProcessor.detectType(null)).isNull();
    }

    @Test
    void readOrientationDefaultsToOneWithoutExif() throws IOException {
        assertThat(AvatarImageProcessor.readOrientation(jpegBytes(10, 10))).isEqualTo(1);
        assertThat(AvatarImageProcessor.readOrientation("not an image".getBytes())).isEqualTo(1);
    }

    @ParameterizedTest(name = "orientation {0}: 3x2 -> {1}x{2}")
    @CsvSource({
            "2, 3, 2",
            "3, 3, 2",
            "4, 3, 2",
            "5, 2, 3",
            "6, 2, 3",
            "7, 2, 3",
            "8, 2, 3"
    })
    void orientationTransformMapsPixels(int orientation, int outWidth, int outHeight) {
        BufferedImage source = gridImage(3, 2);
        BufferedImage result = AvatarImageProcessor.orientationTransform(source, orientation);

        assertThat(result.getWidth()).isEqualTo(outWidth);
        assertThat(result.getHeight()).isEqualTo(outHeight);
        for (int y = 0; y < outHeight; y++) {
            for (int x = 0; x < outWidth; x++) {
                int[] source0 = sourcePixel(orientation, x, y, 3, 2);
                assertThat(colorAt(result, x, y))
                        .as("orientation %d: dst(%d,%d) 应取自 src(%d,%d)",
                                orientation, x, y, source0[0], source0[1])
                        .isEqualTo(expectedRgb(source0[0], source0[1]));
            }
        }
    }

    @Test
    void orientationOneIsIdentity() {
        BufferedImage source = gridImage(3, 2);
        BufferedImage result = AvatarImageProcessor.orientationTransform(source, 1);

        assertThat(result.getWidth()).isEqualTo(3);
        assertThat(result.getHeight()).isEqualTo(2);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 3; x++) {
                assertThat(colorAt(result, x, y)).isEqualTo(expectedRgb(x, y));
            }
        }
    }

    @Test
    void invalidOrientationReturnsSameImage() {
        BufferedImage source = gridImage(3, 2);
        assertThat(AvatarImageProcessor.orientationTransform(source, 9)).isSameAs(source);
    }

    @Test
    void centerCropWideImageTakesMiddleSquare() {
        BufferedImage cropped = AvatarImageProcessor.centerCrop(gridImage(6, 4));

        assertThat(cropped.getWidth()).isEqualTo(4);
        assertThat(cropped.getHeight()).isEqualTo(4);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertThat(colorAt(cropped, x, y)).isEqualTo(expectedRgb(x + 1, y));
            }
        }
    }

    @Test
    void centerCropTallImageTakesMiddleSquare() {
        BufferedImage cropped = AvatarImageProcessor.centerCrop(gridImage(4, 6));

        assertThat(cropped.getWidth()).isEqualTo(4);
        assertThat(cropped.getHeight()).isEqualTo(4);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertThat(colorAt(cropped, x, y)).isEqualTo(expectedRgb(x, y + 1));
            }
        }
    }

    @Test
    void centerCropSquareKeepsAllPixels() {
        BufferedImage cropped = AvatarImageProcessor.centerCrop(gridImage(4, 4));

        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertThat(colorAt(cropped, x, y)).isEqualTo(expectedRgb(x, y));
            }
        }
    }

    @Test
    void resizeOutputsExactTargetSize() {
        BufferedImage resized = AvatarImageProcessor.resizeTo(gridImage(6, 4), 256, 256);

        assertThat(resized.getWidth()).isEqualTo(256);
        assertThat(resized.getHeight()).isEqualTo(256);
    }

    @Test
    void processProduces256SquareJpeg() throws IOException {
        byte[] output = AvatarImageProcessor.process(jpegBytes(400, 300));

        assertThat(AvatarImageProcessor.detectType(output))
                .isEqualTo(AvatarImageProcessor.ImageType.JPEG);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(output));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(256);
        assertThat(image.getHeight()).isEqualTo(256);
    }

    @Test
    void processAcceptsPngInput() throws IOException {
        byte[] output = AvatarImageProcessor.process(pngBytes(300, 500));

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(output));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(256);
        assertThat(image.getHeight()).isEqualTo(256);
    }

    @Test
    void processRejectsUnsupportedMagic() {
        assertThatThrownBy(() -> AvatarImageProcessor.process("GIF89a......".getBytes()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅支持 JPEG/PNG");
    }

    @Test
    void processThrowsOnCorruptJpegPayload() {
        byte[] corrupt = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4, 5, 6, 7};

        assertThatThrownBy(() -> AvatarImageProcessor.process(corrupt))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无法解析图片");
    }
}
```

- [ ] **Step 2: 运行测试，确认编译失败**

Run: `cd /e/JavaSpace/forum && mvn -B -q -Dtest=AvatarImageProcessorTest test`
Expected: 编译失败 —— `cannot find symbol: class AvatarImageProcessor`。

- [ ] **Step 3: 加依赖并实现 `AvatarImageProcessor`**

在 `pom.xml` 的 Flyway 依赖块（第 78 行 `</dependency>`）之后插入：

```xml
        <!-- 图片元数据：读取头像 JPEG 的 EXIF Orientation -->
        <dependency>
            <groupId>com.drewnoakes</groupId>
            <artifactId>metadata-extractor</artifactId>
            <version>2.19.0</version>
        </dependency>
```

Create `src/main/java/org/song/forum/util/AvatarImageProcessor.java`：

```java
package org.song.forum.util;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import org.song.forum.exception.BusinessException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * 头像图片处理：全部是纯函数，方便单测。
 * <p>安全策略：只按魔数接受 JPEG/PNG，并且一定重新编码为 JPEG——不落原始字节，
 * 因此 SVG 脚本、EXIF 元数据、伪造 Content-Type 都不会被写入磁盘。</p>
 */
public final class AvatarImageProcessor {

    /** 规格固定 256×256 */
    public static final int AVATAR_SIZE = 256;
    private static final float JPEG_QUALITY = 0.9f;
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private AvatarImageProcessor() {
    }

    public enum ImageType {
        JPEG, PNG
    }

    /** 魔数判定；不认识返回 null */
    public static ImageType detectType(byte[] data) {
        if (data == null) {
            return null;
        }
        if (startsWith(data, JPEG_MAGIC)) {
            return ImageType.JPEG;
        }
        if (startsWith(data, PNG_MAGIC)) {
            return ImageType.PNG;
        }
        return null;
    }

    /** EXIF Orientation，1–8；无 EXIF / 读取失败一律按 1（不旋转） */
    public static int readOrientation(byte[] data) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(data)) {
            Metadata metadata = ImageMetadataReader.readMetadata(in);
            ExifIFD0Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (directory == null || !directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return 1;
            }
            int orientation = directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            return orientation >= 1 && orientation <= 8 ? orientation : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * 按 EXIF Orientation（1–8）做旋转/镜像。
     * <p>每种取值对应一个精确的仿射矩阵，输出尺寸在 5–8 时交换宽高：</p>
     * <ul>
     *   <li>2 水平镜像、(3) 旋转 180°、(4) 垂直镜像</li>
     *   <li>5 主对角线转置、(6) 顺时针 90°、(7) 副对角线转置、(8) 逆时针 90°</li>
     * </ul>
     */
    public static BufferedImage orientationTransform(BufferedImage source, int orientation) {
        if (orientation < 2 || orientation > 8) {
            return source;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        AffineTransform transform = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
            default -> new AffineTransform();
        };
        boolean swapAxes = orientation >= 5;
        int outWidth = swapAxes ? height : width;
        int outHeight = swapAxes ? width : height;
        BufferedImage target = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        graphics.drawImage(source, transform, null);
        graphics.dispose();
        return target;
    }

    /** 取 min(宽, 高) 为边长居中裁剪正方形 */
    public static BufferedImage centerCrop(BufferedImage source) {
        int size = Math.min(source.getWidth(), source.getHeight());
        int x = (source.getWidth() - size) / 2;
        int y = (source.getHeight() - size) / 2;
        BufferedImage cropped = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = cropped.createGraphics();
        graphics.drawImage(source, 0, 0, size, size, x, y, x + size, y + size, null);
        graphics.dispose();
        return cropped;
    }

    /** 缩放到指定尺寸（小图允许放大） */
    public static BufferedImage resizeTo(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return target;
    }

    /** 重编码为 JPEG，质量 0.9 */
    public static byte[] toJpegBytes(BufferedImage image) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new BusinessException("服务端不支持 JPEG 编码");
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(toRgb(image), null, null), param);
        } catch (IOException e) {
            throw new BusinessException("头像编码失败");
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** 一次完成：魔数校验 → EXIF 方向 → 解码 → 居中裁剪 → 256×256 → JPEG 字节 */
    public static byte[] process(byte[] raw) {
        if (detectType(raw) == null) {
            throw new BusinessException("仅支持 JPEG/PNG 图片");
        }
        BufferedImage decoded;
        try {
            decoded = ImageIO.read(new ByteArrayInputStream(raw));
        } catch (IOException e) {
            throw new BusinessException("无法解析图片");
        }
        if (decoded == null) {
            throw new BusinessException("无法解析图片");
        }
        BufferedImage oriented = orientationTransform(decoded, readOrientation(raw));
        BufferedImage squared = centerCrop(oriented);
        BufferedImage resized = resizeTo(squared, AVATAR_SIZE, AVATAR_SIZE);
        return toJpegBytes(resized);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** 带 alpha 的 PNG 铺白底再转 RGB，避免透明区被编码成黑块 */
    private static BufferedImage toRgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();
        return rgb;
    }
}
```

- [ ] **Step 4: 运行处理器测试，确认通过**

Run: `cd /e/JavaSpace/forum && mvn -B -q -Dtest=AvatarImageProcessorTest test`
Expected: BUILD SUCCESS；方向 7 个参数化用例 + 其它用例全部通过（`Tests run:` 无 failures）。

- [ ] **Step 5: 写头像接口集成测试**

Create `src/test/java/org/song/forum/controller/AvatarFlowIntegrationTest.java`：

```java
package org.song.forum.controller;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.song.forum.dto.auth.AuthResponse;
import org.song.forum.dto.auth.LoginRequest;
import org.song.forum.dto.auth.RegisterRequest;
import org.song.forum.dto.user.UserResponse;
import org.song.forum.util.Result;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 头像全链路：上传 → 换图删旧 → 非法/超限被拒 → 静态直出 → 删除幂等 → 资料接口不再改头像。
 * 上传目录指向 JUnit 的 @TempDir，避免污染工作区。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:avatar-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AvatarFlowIntegrationTest {

    private static final String AVATAR_PREFIX = "/uploads/avatars/";

    @TempDir
    static Path uploadDir;

    /**
     * JUnit 的 @TempDir 与 Spring 的上下文创建都挂在 beforeAll，
     * 谁先执行取决于扩展顺序；这里在属性求值时兜底建目录，保证两条路径都拿得到同一个目录。
     */
    @DynamicPropertySource
    static void uploadProperties(DynamicPropertyRegistry registry) {
        registry.add("app.upload-dir", AvatarFlowIntegrationTest::resolveUploadDir);
    }

    private static Path resolveUploadDir() {
        if (uploadDir == null) {
            try {
                uploadDir = Files.createTempDirectory("avatar-upload-");
            } catch (IOException e) {
                throw new IllegalStateException("无法创建临时上传目录", e);
            }
        }
        return uploadDir;
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private static String token;
    private static String avatarUrl;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders bearer(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken);
        return headers;
    }

    private String registerAndLogin(String name) {
        RegisterRequest register = new RegisterRequest();
        register.setUsername(name);
        register.setPassword("password123");
        register.setEmail(name + "@test.com");
        rest.postForEntity(url("/api/auth/register"), register, Result.class);

        LoginRequest login = new LoginRequest();
        login.setUsername(name);
        login.setPassword("password123");
        ResponseEntity<Result<AuthResponse>> resp = rest.exchange(url("/api/auth/login"),
                HttpMethod.POST, new HttpEntity<>(login), new ParameterizedTypeReference<>() {
                });
        return resp.getBody().getData().getToken();
    }

    private ResponseEntity<Result<UserResponse>> upload(String bearerToken, byte[] bytes, String filename) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        return rest.exchange(url("/api/users/me/avatar"), HttpMethod.POST,
                new HttpEntity<>(body, headers), new ParameterizedTypeReference<>() {
                });
    }

    private ResponseEntity<Result<UserResponse>> deleteAvatar() {
        return rest.exchange(url("/api/users/me/avatar"), HttpMethod.DELETE,
                new HttpEntity<>(bearer(token)), new ParameterizedTypeReference<>() {
                });
    }

    private Path uploadedFile(String url) {
        return uploadDir.resolve("avatars").resolve(url.substring(AVATAR_PREFIX.length()));
    }

    private byte[] jpegBytes(int width, int height, Color color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private byte[] pngBytes(int width, int height, Color color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    @Order(1)
    void anonymousUploadRejected() throws IOException {
        ResponseEntity<Result<UserResponse>> resp = upload(null, jpegBytes(200, 200, Color.ORANGE), "a.jpg");

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @Order(2)
    void uploadJpegStoresSquareJpeg() throws IOException {
        token = registerAndLogin("avatar-user");

        ResponseEntity<Result<UserResponse>> resp = upload(token, jpegBytes(400, 300, Color.ORANGE), "photo.jpg");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        avatarUrl = resp.getBody().getData().getAvatarUrl();
        assertThat(avatarUrl).startsWith(AVATAR_PREFIX).endsWith(".jpg");
        Path stored = uploadedFile(avatarUrl);
        assertThat(stored).exists();
        BufferedImage image = ImageIO.read(stored.toFile());
        assertThat(image.getWidth()).isEqualTo(256);
        assertThat(image.getHeight()).isEqualTo(256);
        byte[] bytes = Files.readAllBytes(stored);
        assertThat(bytes[0]).isEqualTo((byte) 0xFF);
        assertThat(bytes[1]).isEqualTo((byte) 0xD8);
    }

    @Test
    @Order(3)
    void uploadPngAccepted() throws IOException {
        ResponseEntity<Result<UserResponse>> resp = upload(token, pngBytes(300, 500, Color.BLUE), "photo.png");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().getData().getAvatarUrl()).startsWith(AVATAR_PREFIX);
    }

    @Test
    @Order(4)
    void replacingAvatarDeletesOldFile() throws IOException {
        String old = avatarUrl;

        ResponseEntity<Result<UserResponse>> resp = upload(token, jpegBytes(500, 500, Color.GREEN), "again.jpg");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        avatarUrl = resp.getBody().getData().getAvatarUrl();
        assertThat(avatarUrl).isNotEqualTo(old);
        assertThat(uploadedFile(old)).doesNotExist();
        assertThat(uploadedFile(avatarUrl)).exists();
    }

    @Test
    @Order(5)
    void textFileRenamedJpgRejected() {
        ResponseEntity<Result<UserResponse>> resp = upload(token, "not an image".getBytes(), "fake.jpg");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getMessage()).contains("仅支持 JPEG/PNG");
    }

    @Test
    @Order(6)
    void oversizeUploadRejected() throws IOException {
        byte[] jpeg = jpegBytes(300, 300, Color.RED);
        byte[] oversize = new byte[2 * 1024 * 1024 + 1024];
        System.arraycopy(jpeg, 0, oversize, 0, jpeg.length);

        ResponseEntity<Result<UserResponse>> resp = upload(token, oversize, "big.jpg");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getMessage()).contains("2MB");
    }

    @Test
    @Order(7)
    void uploadedFileServedByWebConfig() {
        ResponseEntity<byte[]> resp = rest.getForEntity(url(avatarUrl), byte[].class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getContentType().toString()).contains("image/jpeg");
    }

    @Test
    @Order(8)
    void deleteRemovesAvatarAndFile() {
        String target = avatarUrl;

        ResponseEntity<Result<UserResponse>> resp = deleteAvatar();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().getData().getAvatarUrl()).isNull();
        assertThat(uploadedFile(target)).doesNotExist();
    }

    @Test
    @Order(9)
    void deleteIsIdempotent() {
        ResponseEntity<Result<UserResponse>> resp = deleteAvatar();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().getData().getAvatarUrl()).isNull();
    }

    @Test
    @Order(10)
    void profileUpdateNoLongerChangesAvatar() throws IOException {
        ResponseEntity<Result<UserResponse>> uploaded = upload(token, jpegBytes(200, 200, Color.MAGENTA), "keep.jpg");
        String current = uploaded.getBody().getData().getAvatarUrl();
        assertThat(current).isNotNull();

        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"nickname\":\"新昵称\",\"avatarUrl\":\"/uploads/avatars/evil.jpg\",\"bio\":\"hi\"}";
        ResponseEntity<Result<UserResponse>> resp = rest.exchange(url("/api/auth/profile"), HttpMethod.PUT,
                new HttpEntity<>(body, headers), new ParameterizedTypeReference<>() {
                });

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().getData().getNickname()).isEqualTo("新昵称");
        assertThat(resp.getBody().getData().getAvatarUrl()).isEqualTo(current);
    }
}
```

- [ ] **Step 6: 运行集成测试，确认编译失败**

Run: `cd /e/JavaSpace/forum && mvn -B -q -Dtest=AvatarFlowIntegrationTest test`
Expected: 编译失败 —— `cannot find symbol: class UserController`（以及 `AvatarService`）。

- [ ] **Step 7: 实现 `AvatarService`、`UserController`、`WebConfig`**

Create `src/main/java/org/song/forum/service/AvatarService.java`：

```java
package org.song.forum.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.song.forum.dto.user.UserResponse;
import org.song.forum.entity.User;
import org.song.forum.exception.BusinessException;
import org.song.forum.repository.UserRepository;
import org.song.forum.util.AvatarImageProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 头像编排：校验 → 转码 → 落盘 → 更新 users.avatar_url → 清旧文件。
 * <p>文件一定被重编码为 JPEG，文件名是服务端生成的 UUID，
 * 客户端文件名不参与路径拼接。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarService {

    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final String AVATAR_URL_PREFIX = "/uploads/avatars/";

    private final UserRepository userRepository;

    @Value("${app.upload-dir}")
    private String uploadDir;

    @Transactional
    public UserResponse upload(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择图片文件");
        }
        // 双保险之一：multipart 限制之外再挡一次，避免配置被改小后失效
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException("图片不能超过 2MB");
        }
        byte[] raw;
        try {
            raw = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("读取图片失败");
        }
        byte[] jpeg = AvatarImageProcessor.process(raw);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        Path avatarDir = avatarDir();
        String filename = UUID.randomUUID() + ".jpg";
        try {
            Files.createDirectories(avatarDir);
            Files.write(avatarDir.resolve(filename), jpeg);
        } catch (IOException e) {
            log.error("保存头像失败: dir={}", avatarDir, e);
            throw new BusinessException("保存头像失败");
        }

        String oldUrl = user.getAvatarUrl();
        user.setAvatarUrl(AVATAR_URL_PREFIX + filename);
        user = userRepository.save(user);
        deleteAvatarFile(oldUrl);
        log.info("用户 {} 更新头像: {}", user.getUsername(), user.getAvatarUrl());
        return toUserResponse(user);
    }

    /** 幂等：没有头像时直接返回当前用户 */
    @Transactional
    public UserResponse remove(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        String oldUrl = user.getAvatarUrl();
        if (oldUrl == null) {
            return toUserResponse(user);
        }
        user.setAvatarUrl(null);
        user = userRepository.save(user);
        deleteAvatarFile(oldUrl);
        return toUserResponse(user);
    }

    private Path avatarDir() {
        return Paths.get(uploadDir, "avatars");
    }

    /** 只删除自己目录里、由本服务命名的文件；路径穿越/外链一律跳过 */
    private void deleteAvatarFile(String avatarUrl) {
        if (avatarUrl == null || !avatarUrl.startsWith(AVATAR_URL_PREFIX)) {
            return;
        }
        String filename = avatarUrl.substring(AVATAR_URL_PREFIX.length());
        if (filename.isBlank() || filename.contains("/") || filename.contains("..")) {
            return;
        }
        Path dir = avatarDir();
        try {
            Path target = dir.resolve(filename).toRealPath();
            if (!target.startsWith(dir.toRealPath())) {
                return;
            }
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("删除旧头像失败: {}", avatarUrl, e);
        }
    }

    private UserResponse toUserResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatarUrl(user.getAvatarUrl())
                .bio(user.getBio())
                .createTime(user.getCreateTime())
                .build();
    }
}
```

Create `src/main/java/org/song/forum/controller/UserController.java`：

```java
package org.song.forum.controller;

import lombok.RequiredArgsConstructor;
import org.song.forum.dto.user.UserResponse;
import org.song.forum.security.SecurityUtils;
import org.song.forum.service.AvatarService;
import org.song.forum.util.Result;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 当前用户的头像接口。只有 POST/DELETE，不落在任何公开规则里，
 * 因此由 SecurityConfig 的 anyRequest().authenticated() 兜住。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AvatarService avatarService;

    @PostMapping("/me/avatar")
    public Result<UserResponse> uploadAvatar(@RequestParam("file") MultipartFile file) {
        return Result.ok(avatarService.upload(SecurityUtils.currentUserId(), file), "头像已更新");
    }

    @DeleteMapping("/me/avatar")
    public Result<UserResponse> removeAvatar() {
        return Result.ok(avatarService.remove(SecurityUtils.currentUserId()), "头像已移除");
    }
}
```

Create `src/main/java/org/song/forum/config/WebConfig.java`：

```java
package org.song.forum.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * 本地开发时让 /uploads/** 能直接访问头像文件（生产由 nginx 的
 * location ^~ /forum/uploads/ 直出，不经过后端）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.upload-dir}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Paths.get(uploadDir).toAbsolutePath().normalize().toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler("/uploads/**").addResourceLocations(location);
    }
}
```

- [ ] **Step 8: 接线：配置、安全、异常、DTO**

`src/main/resources/application.yml` 在 `hikari.minimum-idle: 2`（第 15 行）之后插入：

```yaml
  # ---- 文件上传（头像）----
  # 2MB 是规格上限；请求体留 1MB 余量给 multipart 边界与其它字段。
  servlet:
    multipart:
      max-file-size: 2MB
      max-request-size: 3MB
```

同一文件末尾追加：

```yaml
# ---- 上传目录（头像落盘位置）----
# 容器里由 UPLOAD_DIR=/app/uploads 覆盖；本地开发默认 ./uploads（已加入 .gitignore）
app:
  upload-dir: ${UPLOAD_DIR:./uploads}
```

`src/main/java/org/song/forum/config/SecurityConfig.java` 的 `PUBLIC_GET` 中，`"/api/auth/users/*",`（第 36 行）之后插入：

```java
            "/uploads/**",
```

`src/main/java/org/song/forum/exception/GlobalExceptionHandler.java`：

导入区（第 14 行 `RestControllerAdvice` 之后）加：

```java
import org.springframework.web.multipart.MaxUploadSizeExceededException;
```

类末尾（最后一个 handler 之后、类闭合 `}` 之前）加：

```java
    /** 上传文件超过 multipart 限制 → 400 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件过大: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Result.fail("图片不能超过 2MB"));
    }
```

`src/main/java/org/song/forum/dto/user/UpdateProfileRequest.java` 删除第 16-17 行（`@Size(max = 255, message = "头像地址过长")` 与 `private String avatarUrl;`），保留下来的字段只剩 `nickname` / `bio` / `emailPlaceholder`。

`src/main/java/org/song/forum/service/AuthService.java` 删除第 91 行：

```java
        if (req.getAvatarUrl() != null) user.setAvatarUrl(req.getAvatarUrl());
```

- [ ] **Step 9: 运行集成测试，确认通过**

Run: `cd /e/JavaSpace/forum && mvn -B -q -Dtest=AvatarFlowIntegrationTest test`
Expected: BUILD SUCCESS，`Tests run: 10, Failures: 0, Errors: 0`。

- [ ] **Step 10: 跑后端全量测试**

Run: `cd /e/JavaSpace/forum && mvn -B test`
Expected: BUILD SUCCESS，0 failures（既有 9 类 39 例 + 新增 2 类；`UpdateProfileRequest` 删字段不影响既有用例，因为它们都没有引用 `avatarUrl`）。

- [ ] **Step 11: 提交**

```bash
git add pom.xml src/main/resources/application.yml \
  src/main/java/org/song/forum/util/AvatarImageProcessor.java \
  src/main/java/org/song/forum/service/AvatarService.java \
  src/main/java/org/song/forum/service/AuthService.java \
  src/main/java/org/song/forum/controller/UserController.java \
  src/main/java/org/song/forum/config/WebConfig.java \
  src/main/java/org/song/forum/config/SecurityConfig.java \
  src/main/java/org/song/forum/exception/GlobalExceptionHandler.java \
  src/main/java/org/song/forum/dto/user/UpdateProfileRequest.java \
  src/test/java/org/song/forum/util/AvatarImageProcessorTest.java \
  src/test/java/org/song/forum/controller/AvatarFlowIntegrationTest.java
git commit -m "feat: 新增头像上传后端接口与图片处理"
```

---

## Task 3: 头像上传前端

**Files:**
- Create: `frontend/src/utils/avatar.spec.js`
- Create: `frontend/src/utils/avatar.js`
- Create: `frontend/src/components/UserAvatar.spec.js`
- Create: `frontend/src/components/UserAvatar.vue`
- Modify: `frontend/src/api/index.spec.js:49,164`
- Modify: `frontend/src/api/index.js:79`
- Modify: `frontend/src/views/ProfileView.spec.js:11-17,23-38,137`
- Modify: `frontend/src/views/ProfileView.vue:2-46,87-89,141-169`
- Modify: `frontend/src/App.vue:8,60`
- Modify: `frontend/src/views/PostDetailView.vue:1-11,279,293`

- [ ] **Step 1: 写 `resolveAvatarUrl` 的失败测试**

Create `frontend/src/utils/avatar.spec.js`：

```js
import { describe, it, expect } from 'vitest'
import { resolveAvatarUrl } from './avatar.js'

describe('resolveAvatarUrl', () => {
  it('空值返回空串', () => {
    expect(resolveAvatarUrl('')).toBe('')
    expect(resolveAvatarUrl(null)).toBe('')
    expect(resolveAvatarUrl(undefined)).toBe('')
  })

  it('/uploads/ 前缀拼上 BASE_URL', () => {
    expect(resolveAvatarUrl('/uploads/avatars/a.jpg')).toBe(
      `${import.meta.env.BASE_URL}uploads/avatars/a.jpg`
    )
  })

  it('外链 http(s) 原样返回', () => {
    expect(resolveAvatarUrl('https://cdn.example.com/a.png')).toBe('https://cdn.example.com/a.png')
    expect(resolveAvatarUrl('http://cdn.example.com/a.png')).toBe('http://cdn.example.com/a.png')
  })

  it('data URL 原样返回', () => {
    const dataUrl = 'data:image/png;base64,iVBORw0KGgo='
    expect(resolveAvatarUrl(dataUrl)).toBe(dataUrl)
  })

  it('其它站内路径原样返回', () => {
    expect(resolveAvatarUrl('/assets/a.png')).toBe('/assets/a.png')
  })
})
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd frontend && pnpm exec vitest run src/utils/avatar.spec.js`
Expected: FAIL —— `Failed to resolve import "./avatar.js"`。

- [ ] **Step 3: 实现 `avatar.js`**

Create `frontend/src/utils/avatar.js`：

```js
/**
 * 头像地址解析：后端上传的头像存的是站内绝对路径 /uploads/avatars/xxx.jpg。
 * 子路径部署（/forum/）时必须补上 BASE_URL，否则请求会打到域名根而 404。
 * 外链、data URL 等历史数据原样返回。
 */
export function resolveAvatarUrl(url) {
  if (!url) return ''
  if (url.startsWith('/uploads/')) return `${import.meta.env.BASE_URL}${url.slice(1)}`
  return url
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd frontend && pnpm exec vitest run src/utils/avatar.spec.js`
Expected: PASS（5 个用例）。

- [ ] **Step 5: 写 `UserAvatar` 的失败测试**

Create `frontend/src/components/UserAvatar.spec.js`：

```js
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import UserAvatar from './UserAvatar.vue'

const stubs = {
  'el-avatar': {
    name: 'ElAvatar',
    props: ['size', 'src'],
    emits: ['error'],
    template: '<div class="avatar-stub" :data-src="src"><slot /></div>'
  }
}

function mountAvatar(props) {
  return mount(UserAvatar, { props, global: { stubs } })
}

describe('UserAvatar', () => {
  it('有 url 时把解析后的地址交给 el-avatar', () => {
    const wrapper = mountAvatar({ url: '/uploads/avatars/a.jpg', name: '爱丽丝' })

    expect(wrapper.find('.avatar-stub').attributes('data-src')).toBe('/uploads/avatars/a.jpg')
    expect(wrapper.findComponent({ name: 'ElAvatar' }).props('size')).toBe(28)
  })

  it('size 透传给 el-avatar', () => {
    const wrapper = mountAvatar({ url: '', name: 'bob', size: 64 })

    expect(wrapper.findComponent({ name: 'ElAvatar' }).props('size')).toBe(64)
  })

  it('外链头像原样透传', () => {
    const wrapper = mountAvatar({ url: 'https://cdn.example.com/a.png', name: 'bob' })

    expect(wrapper.find('.avatar-stub').attributes('data-src')).toBe('https://cdn.example.com/a.png')
  })

  it('无 url 时只显示首字母', () => {
    const wrapper = mountAvatar({ url: '', name: '爱丽丝' })

    expect(wrapper.find('.avatar-stub').attributes('data-src')).toBeUndefined()
    expect(wrapper.text()).toBe('爱')
  })

  it('图片加载失败后回退首字母', async () => {
    const wrapper = mountAvatar({ url: '/uploads/avatars/a.jpg', name: 'bob' })

    wrapper.findComponent({ name: 'ElAvatar' }).vm.$emit('error')
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.avatar-stub').attributes('data-src')).toBeUndefined()
    expect(wrapper.text()).toBe('b')
  })

  it('name 为空时回退问号', () => {
    const wrapper = mountAvatar({ url: '', name: '' })

    expect(wrapper.text()).toBe('?')
  })
})
```

- [ ] **Step 6: 运行测试，确认失败**

Run: `cd frontend && pnpm exec vitest run src/components/UserAvatar.spec.js`
Expected: FAIL —— `Failed to resolve import "./UserAvatar.vue"`。

- [ ] **Step 7: 实现 `UserAvatar.vue`**

Create `frontend/src/components/UserAvatar.vue`：

```vue
<script setup>
import { computed, ref, watch } from 'vue'
import { resolveAvatarUrl } from '@/utils/avatar'

const props = defineProps({
  url: { type: String, default: '' },
  name: { type: String, default: '' },
  size: { type: Number, default: 28 }
})

// 图片 404 / 损坏时 el-avatar 不会自动消失，用 @error 切回字母头像
const failed = ref(false)
const src = computed(() => resolveAvatarUrl(props.url))
const showImage = computed(() => Boolean(props.url) && !failed.value)
const initial = computed(() => (props.name || '?').slice(0, 1))

watch(
  () => props.url,
  () => {
    failed.value = false
  }
)
</script>

<template>
  <el-avatar :size="size" :src="showImage ? src : undefined" @error="failed = true">
    {{ initial }}
  </el-avatar>
</template>
```

- [ ] **Step 8: 运行测试，确认通过**

Run: `cd frontend && pnpm exec vitest run src/components/UserAvatar.spec.js`
Expected: PASS（6 个用例）。

- [ ] **Step 9: 写 `userApi` 的失败断言**

`frontend/src/api/index.spec.js` 第 49 行改为：

```js
const { tagApi, userApi } = await import('./index.js')
```

文件末尾追加：

```js
describe('userApi 请求路径', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('uploadAvatar 用 FormData 的 file 字段 POST /users/me/avatar', () => {
    const file = new File(['x'], 'me.jpg', { type: 'image/jpeg' })

    userApi.uploadAvatar(file)

    expect(apiPost).toHaveBeenCalledTimes(1)
    const [url, body] = apiPost.mock.calls[0]
    expect(url).toBe('/users/me/avatar')
    expect(body).toBeInstanceOf(FormData)
    expect(body.get('file')).toBe(file)
  })

  it('removeAvatar 请求 DELETE /users/me/avatar', () => {
    userApi.removeAvatar()

    expect(apiDelete).toHaveBeenCalledWith('/users/me/avatar')
  })
})
```

- [ ] **Step 10: 运行测试，确认失败**

Run: `cd frontend && pnpm exec vitest run src/api/index.spec.js`
Expected: FAIL —— `userApi is not a function` / `Cannot read properties of undefined`。

- [ ] **Step 11: 实现 `userApi`**

`frontend/src/api/index.js` 在 `authApi` 块（第 79 行 `}`）之后插入：

```js
// ========== 用户（头像） ==========
export const userApi = {
  uploadAvatar: (file) => {
    const fd = new FormData()
    fd.append('file', file)
    return api.post('/users/me/avatar', fd)
  },
  removeAvatar: () => api.delete('/users/me/avatar')
}
```

- [ ] **Step 12: 运行测试，确认通过**

Run: `cd frontend && pnpm exec vitest run src/api/index.spec.js`
Expected: PASS（9 个既有 + 2 个新增 = 11 个用例）。

- [ ] **Step 13: 写 `ProfileView` 头像交互的失败测试**

`frontend/src/views/ProfileView.spec.js` 第 1-17 行（三个 import + `vue-router` mock + `@/api` mock）改为下面这段；第 19-21 行的 `import ProfileView` / `import { postApi }` / `import { useAuthStore }` 保持不变：

```js
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const { uploadAvatar, removeAvatar, warning, success } = vi.hoisted(() => ({
  uploadAvatar: vi.fn(),
  removeAvatar: vi.fn(),
  warning: vi.fn(),
  success: vi.fn()
}))

const push = vi.fn()
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {}, name: 'profile', fullPath: '/profile', path: '/profile' }),
  useRouter: () => ({ push })
}))

vi.mock('@/api', () => ({
  postApi: {
    mine: vi.fn(),
    myFavorites: vi.fn(),
    remove: vi.fn()
  },
  userApi: { uploadAvatar, removeAvatar }
}))

vi.mock('element-plus', () => ({ ElMessage: { success, warning, error: vi.fn() } }))
```

`stubs` 增加 `el-avatar`（因为 `ProfileView` 里渲染了 `UserAvatar`）：

```js
  'el-avatar': { props: ['size', 'src'], template: '<div class="avatar-stub" />' },
```

文件末尾追加：

```js
describe('ProfileView 头像上传', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.user = {
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      avatarUrl: '/uploads/avatars/old.jpg',
      roles: ['USER']
    }
    auth.fetchMe = vi.fn().mockResolvedValue()
    postApi.mine.mockResolvedValue(page([]))
    postApi.myFavorites.mockResolvedValue(page([]))
    uploadAvatar.mockResolvedValue({ avatarUrl: '/uploads/avatars/new.jpg' })
    removeAvatar.mockResolvedValue({ avatarUrl: null })
  })

  async function pick(wrapper, file) {
    const input = wrapper.find('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await flush()
  }

  it('上传合法 JPEG 调用 userApi.uploadAvatar 并刷新用户', async () => {
    const wrapper = mountProfile()
    await flush()
    const file = new File(['x'], 'me.jpg', { type: 'image/jpeg' })

    await pick(wrapper, file)

    expect(uploadAvatar).toHaveBeenCalledWith(file)
    expect(useAuthStore().fetchMe).toHaveBeenCalled()
    expect(success).toHaveBeenCalledWith('头像已更新')
  })

  it('非法类型不发请求并提示', async () => {
    const wrapper = mountProfile()
    await flush()

    await pick(wrapper, new File(['x'], 'me.gif', { type: 'image/gif' }))

    expect(uploadAvatar).not.toHaveBeenCalled()
    expect(warning).toHaveBeenCalledWith('仅支持 JPEG/PNG 图片')
  })

  it('超过 2MB 不发请求并提示', async () => {
    const wrapper = mountProfile()
    await flush()
    const big = new File([new Uint8Array(2 * 1024 * 1024 + 1)], 'big.jpg', { type: 'image/jpeg' })

    await pick(wrapper, big)

    expect(uploadAvatar).not.toHaveBeenCalled()
    expect(warning).toHaveBeenCalledWith('图片不能超过 2MB')
  })

  it('移除头像调用 userApi.removeAvatar 并刷新用户', async () => {
    const wrapper = mountProfile()
    await flush()

    await wrapper.find('.avatar-remove').trigger('click')
    await flush()

    expect(removeAvatar).toHaveBeenCalled()
    expect(useAuthStore().fetchMe).toHaveBeenCalled()
    expect(success).toHaveBeenCalledWith('头像已移除')
  })
})
```

- [ ] **Step 14: 运行测试，确认失败**

Run: `cd frontend && pnpm exec vitest run src/views/ProfileView.spec.js`
Expected: FAIL —— `Cannot call trigger on an empty DOMWrapper`（页面还没有 `input[type=file]` 与 `.avatar-remove`）。

- [ ] **Step 15: 改造 `ProfileView.vue`**

`frontend/src/views/ProfileView.vue` 第 1-46 行的脚本替换为：

```js
<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { postApi, userApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/format'
import UserAvatar from '@/components/UserAvatar.vue'

const router = useRouter()
const auth = useAuthStore()

const tab = ref('profile')
const profileForm = reactive({
  nickname: '',
  bio: ''
})
const savingProfile = ref(false)

const avatarInput = ref(null)
const uploadingAvatar = ref(false)
const removingAvatar = ref(false)

const AVATAR_MAX_BYTES = 2 * 1024 * 1024
const AVATAR_TYPES = ['image/jpeg', 'image/png']

const myPosts = ref({ content: [], totalElements: 0, totalPages: 0 })
const myPostPage = ref(0)
// 后端返回的是分页对象 { content, totalElements, totalPages }，不是数组。
// 早先这里声明成 ref([]) 并把整个分页对象赋进去，模板读 myFavorites.length 恒为 undefined，
// 于是收藏成功后永远显示「还没有收藏」。
const myFavorites = ref({ content: [], totalElements: 0, totalPages: 0 })
const myFavoritePage = ref(0)

async function loadProfile() {
  profileForm.nickname = auth.user?.nickname || ''
  profileForm.bio = auth.user?.bio || ''
}

async function saveProfile() {
  savingProfile.value = true
  try {
    await auth.updateProfile({
      nickname: profileForm.nickname || undefined,
      bio: profileForm.bio || undefined
    })
    ElMessage.success('资料已保存')
  } finally {
    savingProfile.value = false
  }
}

function pickAvatar() {
  avatarInput.value?.click()
}

async function onAvatarChange(event) {
  const file = event.target.files?.[0]
  // 清空 value，允许连续选择同一个文件
  event.target.value = ''
  if (!file) return
  if (!AVATAR_TYPES.includes(file.type)) {
    ElMessage.warning('仅支持 JPEG/PNG 图片')
    return
  }
  if (file.size > AVATAR_MAX_BYTES) {
    ElMessage.warning('图片不能超过 2MB')
    return
  }
  uploadingAvatar.value = true
  try {
    await userApi.uploadAvatar(file)
    await auth.fetchMe()
    ElMessage.success('头像已更新')
  } finally {
    uploadingAvatar.value = false
  }
}

async function removeAvatar() {
  removingAvatar.value = true
  try {
    await userApi.removeAvatar()
    await auth.fetchMe()
    ElMessage.success('头像已移除')
  } finally {
    removingAvatar.value = false
  }
}
```

（`loadMyPosts` / `loadFavorites` / `removePost` / `onMounted` 保持原样，接在上面的 `removeAvatar` 之后。）

第 87-89 行的「头像 URL」表单项替换为：

```html
          <el-form-item label="头像">
            <div class="avatar-block">
              <UserAvatar
                :url="auth.user?.avatarUrl"
                :name="auth.user?.nickname || auth.user?.username"
                :size="64"
              />
              <div class="avatar-actions">
                <el-button :loading="uploadingAvatar" @click="pickAvatar">选择图片</el-button>
                <el-button
                  v-if="auth.user?.avatarUrl"
                  class="avatar-remove"
                  text
                  type="danger"
                  :loading="removingAvatar"
                  @click="removeAvatar"
                >移除头像</el-button>
              </div>
              <input
                ref="avatarInput"
                class="avatar-input"
                type="file"
                accept="image/jpeg,image/png"
                @change="onAvatarChange"
              />
              <p class="avatar-hint">支持 JPEG/PNG，不超过 2MB；服务端会居中裁剪为 256×256。</p>
            </div>
          </el-form-item>
```

`<style scoped>` 内追加：

```css
.avatar-block {
  display: flex;
  flex-direction: column;
  gap: var(--jf-space-2);
}
.avatar-actions {
  display: flex;
  align-items: center;
  gap: var(--jf-space-2);
}
.avatar-input {
  display: none;
}
.avatar-hint {
  margin: 0;
  color: var(--jf-color-text-3);
  font-size: var(--jf-font-small);
}
```

- [ ] **Step 16: 运行测试，确认通过**

Run: `cd frontend && pnpm exec vitest run src/views/ProfileView.spec.js`
Expected: PASS（6 个既有收藏用例 + 4 个新增头像用例 = 10 个）。

- [ ] **Step 17: 全站头像替换**

`frontend/src/App.vue` 在第 8 行 `import ThemeToggle from '@/components/ThemeToggle.vue'` 之后加：

```js
import UserAvatar from '@/components/UserAvatar.vue'
```

第 60 行的首字母头像替换为：

```html
                <UserAvatar
                  :url="auth.user?.avatarUrl"
                  :name="auth.user?.nickname || auth.user?.username"
                  :size="28"
                />
```

`frontend/src/views/PostDetailView.vue` 在脚本导入区（第 1-11 行内，`formatDateTime` 之后）加：

```js
import UserAvatar from '@/components/UserAvatar.vue'
```

第 279 行评论头像替换为：

```html
                <UserAvatar
                  :url="c.authorAvatar"
                  :name="c.authorNickname || c.authorUsername"
                  :size="26"
                />
```

第 293 行回复头像替换为：

```html
                <UserAvatar
                  :url="r.authorAvatar"
                  :name="r.authorNickname || r.authorUsername"
                  :size="22"
                />
```

（`CommentResponse` 的头像字段名是 `authorAvatar`，已由 Task 2 之外的后端现状提供，无需改动。）

- [ ] **Step 18: 跑前端全量测试与构建**

Run: `cd frontend && pnpm test && VITE_BASE_PATH=/forum/ pnpm build`
Expected: 全绿（25 个 spec 文件），构建成功，`dist/index.html` 引用 `/forum/assets/...`。

- [ ] **Step 19: 提交**

```bash
git add frontend/src/api/index.js frontend/src/api/index.spec.js \
  frontend/src/utils/avatar.js frontend/src/utils/avatar.spec.js \
  frontend/src/components/UserAvatar.vue frontend/src/components/UserAvatar.spec.js \
  frontend/src/views/ProfileView.vue frontend/src/views/ProfileView.spec.js \
  frontend/src/App.vue frontend/src/views/PostDetailView.vue
git commit -m "feat: 新增头像上传前端交互与全站头像展示"
```

---

## Task 4: 部署支持上传目录

**Files:**
- Modify: `Dockerfile:16-20`、`Dockerfile:26`
- Modify: `docker-compose.yml:23-38`
- Modify: `deploy/nginx/forum.conf:14-26`
- Modify: `deploy/deploy.sh:39-45`、`deploy/deploy.sh:85-94`
- Modify: `.gitignore:28-34`
- Modify: `.env.example:32-39`

- [ ] **Step 1: 镜像内建上传目录并声明默认值**

`Dockerfile` 第 16-20 行的 ENV 块改为（新增 `UPLOAD_DIR` 一行）：

```dockerfile
ENV TZ=Asia/Shanghai \
    SERVER_PORT=8080 \
    SPRING_PROFILES_ACTIVE=prod \
    LOG_FILE=/app/logs/javaforum.log \
    UPLOAD_DIR=/app/uploads \
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
```

第 26 行改为（同一条 `RUN` 里建目录，避免多一层）：

```dockerfile
RUN mkdir -p /app/logs /app/uploads && chown -R 1001:1001 /app
```

- [ ] **Step 2: compose 挂卷并传目录**

`docker-compose.yml` 第 23-38 行的 `environment` / `ports` / `restart` 段改为：

```yaml
    environment:
      SPRING_PROFILES_ACTIVE: prod
      DB_URL: ${DB_URL}
      DB_USERNAME: ${DB_USERNAME}
      DB_PASSWORD: ${DB_PASSWORD}
      JWT_SECRET: ${JWT_SECRET}
      SERVER_PORT: "8080"
      LOG_FILE: /app/logs/javaforum.log
      UPLOAD_DIR: /app/uploads
      TZ: ${TZ:-Asia/Shanghai}
      # 显式 -Xmx 而不是 -XX:MaxRAMPercentage：raspi 内核 cmdline 带 cgroup_disable=memory，
      # 容器看不到内存上限，MaxRAMPercentage 会按整机内存算，反而容易 OOM 整机
      JAVA_OPTS: ${JAVA_OPTS:--Xms128m -Xmx512m -XX:+ExitOnOutOfMemoryError}
    volumes:
      # 头像落在宿主机目录，容器重建不丢文件；宿主目录由 deploy.sh 创建并调整属主
      - ${UPLOAD_HOST_DIR:-./uploads}:/app/uploads
    ports:
      # 只绑本机回环：由 nginx 反代，局域网不直接暴露后端
      - "127.0.0.1:${HOST_PORT:-8086}:8080"
    restart: unless-stopped
```

- [ ] **Step 3: nginx 直出 `/forum/uploads/`（必须排在 `/forum/` 之前）**

`deploy/nginx/forum.conf` 第 14-26 行现在是：

```nginx
# 静态资源：vite 产物文件名带 hash，可以长缓存
location ^~ /forum/assets/ {
    alias /pd/Program/forum/frontend/assets/;
    expires 7d;
    access_log off;
}

# SPA：history 路由，未命中的路径回退到 index.html
location /forum/ {
    alias /pd/Program/forum/frontend/;
    index index.html;
    try_files $uri $uri/ /forum/index.html;
}
```

改为（在 assets 与 SPA 之间插入 uploads；`^~` 保证不会被 `/forum/` 的前缀匹配抢走）：

```nginx
# 静态资源：vite 产物文件名带 hash，可以长缓存
location ^~ /forum/assets/ {
    alias /pd/Program/forum/frontend/assets/;
    expires 7d;
    access_log off;
}

# 头像：容器外的宿主机目录，直接由 nginx 直出，不经过后端
# 文件名是服务端生成的 UUID，内容不会变，可以长缓存
location ^~ /forum/uploads/ {
    alias /pd/Program/forum/uploads/;
    expires 7d;
    access_log off;
}

# SPA：history 路由，未命中的路径回退到 index.html
location /forum/ {
    alias /pd/Program/forum/frontend/;
    index index.html;
    try_files $uri $uri/ /forum/index.html;
}
```

> 顺序是关键：若把 uploads 放到 `/forum/` 之后，nginx 会用前缀更长的 `/forum/` 命中并回退 `index.html`，未命中的头像会返回 200 的 HTML 而不是 404，Task 4 Step 5 的冒烟守卫正是用来抓这个错误。

- [ ] **Step 4: 部署脚本创建目录、改属主、写 `.env`**

`deploy/deploy.sh` 第 39-45 行（`IMAGE_TAG` upsert 与 `chmod 600`）之后插入：

```bash
# 头像目录在宿主机上，容器内 UID 1001 要以卷写权限：
# rootless podman 把容器 1001 映射到宿主的 subuid（100000+1000），
# 因此必须在 podman 的 user namespace 里 chown；宿主上直接 chown 1001:1001 是错的用户
log "准备头像目录 $ROOT/uploads"
mkdir -p "$ROOT/uploads/avatars"
podman unshare chown -R 1001:1001 "$ROOT/uploads"

# 卷的宿主目录路径写进 .env，compose 每次 up 都从这里读
if grep -q '^UPLOAD_HOST_DIR=' "$ENV_FILE"; then
    sed -i "s|^UPLOAD_HOST_DIR=.*|UPLOAD_HOST_DIR=$ROOT/uploads|" "$ENV_FILE"
else
    printf 'UPLOAD_HOST_DIR=%s\n' "$ROOT/uploads" >> "$ENV_FILE"
fi
```

- [ ] **Step 5: 冒烟加「上传目录返回 404」守卫**

`deploy/deploy.sh` 第 92-93 行（`/forum/api/posts` 冒烟）之后插入：

```bash
# 上传目录必须由 nginx 的 ^~ /forum/uploads/ 直出：
# 命中 alias 时，不存在的文件是 404；若配置没生效，请求会落到 SPA 的
# try_files 而返回 200 + index.html —— 这正是要抓的静默错误
uploads_code="$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: raspi' \
    "http://127.0.0.1/forum/uploads/__probe__/missing.jpg")"
[ "$uploads_code" = "404" ] \
    || die "/forum/uploads/ 未直出（返回 $uploads_code，应为 404；检查 nginx snippet 是否已 reload）"
```

- [ ] **Step 6: 忽略本地上传目录**

`.gitignore` 第 28-34 段的「# 环境变量」之后插入一段：

```gitignore
# 上传文件（本地开发头像落盘目录）
/uploads/
```

- [ ] **Step 7: 环境变量模板补充 `UPLOAD_HOST_DIR`**

`.env.example` 第 36-39 行的 raspi 段，在 `# IMAGE_TAG=latest` 之后插入：

```bash
# UPLOAD_HOST_DIR=/pd/Program/forum/uploads   # 头像目录的宿主路径，deploy.sh 会自动写入
#   ↑ 容器内固定 /app/uploads；宿主目录由 deploy.sh mkdir + podman unshare chown 1001:1001
```

- [ ] **Step 8: 校验脚本语法与改动范围**

Run:

```bash
cd /e/JavaSpace/forum && bash -n deploy/deploy.sh && echo "deploy.sh 语法 OK"
grep -n 'uploads' deploy/deploy.sh deploy/nginx/forum.conf docker-compose.yml | head -20
git diff --stat
```

Expected:
- `deploy.sh 语法 OK`（`bash -n` 无输出即通过）
- grep 命中 `^~ /forum/uploads/`、`UPLOAD_HOST_DIR`、`/app/uploads` 等行
- `git diff --stat` 只含 `Dockerfile`、`docker-compose.yml`、`deploy/deploy.sh`、`deploy/nginx/forum.conf`、`.gitignore`、`.env.example` 六个文件

- [ ] **Step 9: 提交**

```bash
git add Dockerfile docker-compose.yml deploy/deploy.sh deploy/nginx/forum.conf .gitignore .env.example
git commit -m "chore: 部署支持头像上传目录"
```

---

## Task 5: 文档

**Files:**
- Modify: `README.md:15-22`、`README.md:138-147`、`README.md:225`、`README.md:235-247`、`README.md:263-265`、`README.md:275-291`
- Modify: `docs/DEVELOPMENT.md:116-122`、`docs/DEVELOPMENT.md:126-151`、`docs/DEVELOPMENT.md:163-182`、`docs/DEVELOPMENT.md:373`（新增 §4.9）、`docs/DEVELOPMENT.md:390`（新增 T11/T12）、`docs/DEVELOPMENT.md:398-405`、`docs/DEVELOPMENT.md:454-457`
- Modify: `docs/deployment/README.md:8-19`、`docs/deployment/README.md:30-44`、`docs/deployment/README.md:89-98`、`docs/deployment/README.md:110-115`、`docs/deployment/README.md:117-121`

- [ ] **Step 1: README 功能列表**

第 15 行改为：

```markdown
- 用户：注册 / 登录 / 个人资料修改（昵称、简介、邮箱展示），JWT 无状态会话；资料页支持上传与移除头像
```

第 21 行（`**标签检索**` 那行）之后插入两行：

```markdown
- **深色模式**：默认跟随系统，可手动切浅色/深色，选择存于 `localStorage`（`jf_theme`）；首屏由 `index.html` 的内联脚本应用，避免闪白；配色来自 `tokens.css` 的双套 CSS 变量与 Element Plus 官方暗色变量
- **头像上传**：资料页选择 JPEG/PNG（≤2MB），服务端按 EXIF 方向校正后居中裁剪为 256×256 JPEG，落盘到宿主机 `/pd/Program/forum/uploads/avatars/` 并由 nginx 直出；支持移除，图片缺失时回退首字母
```

- [ ] **Step 2: README 部署章节**

第 138-147 行的「相关文件」表格之后插入：

```markdown
头像文件不在镜像里：`/pd/Program/forum/uploads/` 以卷挂载进容器（`.env` 的 `UPLOAD_HOST_DIR`，容器内固定 `/app/uploads`），再由 nginx 的 `location ^~ /forum/uploads/` 直出。`deploy/deploy.sh` 每次部署都会创建该目录并用 `podman unshare chown -R 1001:1001` 调整属主（rootless podman 下容器 UID 1001 映射到宿主 subuid，宿主直接 `chown 1001` 是错的用户）。
```

- [ ] **Step 3: README 测试章节**

第 225 行改为：

```markdown
测试使用 H2 内存库（PostgreSQL 兼容模式 `MODE=PostgreSQL`），Flyway 关闭、`ddl-auto=create-drop`，无需本地 PostgreSQL、也无需设置任何环境变量（`src/test/resources/application-test.yml` 由 `@ActiveProfiles("test")` 提供测试专用凭据）。当前为 11 个测试类、70 个用例。
```

第 235 行改为：

```markdown
当前为 25 个 spec、186 个用例，覆盖：
```

第 247 行（`编辑页 Markdown 预览` 那条）之后插入两条：

```markdown
- 深色模式（`src/composables/useTheme.js`、`src/components/ThemeToggle.vue`、`src/styles/tokens.spec.js`）：默认跟随系统、系统主题变化实时同步 `html.dark`、手动选择写入 `localStorage`、坏值回退 `system`、`toggle` 按当前实际效果取反、`matchMedia` 缺失不抛错、切到显式模式后停止监听系统、双套令牌集合一致且每个颜色令牌都有 `var()` 消费方、`main.js` 的 8 条样式导入顺序、`index.html` 的首屏引导脚本在模块脚本之前
- 头像（`src/utils/avatar.js`、`src/components/UserAvatar.vue`、`src/views/ProfileView.spec.js`）：站内 `/uploads/` 前缀拼 `BASE_URL`、外链与 data URL 原样透传、加载失败回退首字母、上传成功调用 `userApi` 并刷新用户、非法类型与超过 2MB 的客户端校验、移除头像
```

- [ ] **Step 4: README 样式与设计令牌**

第 263 行末（「新增令牌时应连同首个消费方一起加。」之后）追加：

```markdown
第四轮起令牌分两套：`:root` 为亮色、`html.dark` 为暗色，两套的 `--jf-color-*` 名称必须完全一致。`styles/tokens.spec.js` 守卫四件事：两套集合相等、每个颜色都是 6 位小写十六进制、每个颜色令牌在 `src` 下至少有一处 `var()` 消费、页面底与卡片底在两套中取值不同（否则切换主题看不出变化）。`html.dark` 由 `composables/useTheme.js` 与 `index.html` 的内联脚本共同维护；代码块底色走 `--jf-color-code-bg`，暗色语法高亮在 `styles/highlight-dark.css` 里以 `html.dark` 前缀叠加。
```

- [ ] **Step 5: README 目录结构**

第 283-291 行改为：

```markdown
frontend/src/
├── api/           # Axios 实例与接口封装
├── components/    # 可复用组件（UserCard、BoardCard、UserAvatar、ThemeToggle）
├── composables/   # 组合式函数（useResponsive、usePostActions、useTheme）
├── router/        # 路由表与登录/管理员守卫
├── stores/        # Pinia store（auth、notifications）
├── styles/        # tokens.css 双套令牌、common.css 共用类、markdown.css、highlight-dark.css
├── utils/         # 纯函数（通知跳转映射、时间格式化、头像地址解析）
└── views/         # 页面组件
```

第 275-281 行的「# 部署相关」块，在 `.env.example` 那行之后插入：

```markdown
uploads/             # 本地开发的头像落盘目录（运行时生成，已 gitignore；生产在 /pd/Program/forum/uploads）
```

- [ ] **Step 6: DEVELOPMENT 进度与快照**

第 120 行（`部署工程化` 那行）之后插入：

```markdown
| 第四轮 | 深色模式 + 头像上传 | 6 | ✅ 已完成 | 2026-10-20 |
```

第 122 行改为：

```markdown
**当前进度：MVP 100% ｜ 已完成 4 轮迭代。** 全部提交见 §8 变更日志；实时总数用 `git rev-list --count HEAD` 查询（本文件不记录精确总数，因为每次提交都会改变它）。
```

第 126 行标题改为 `## 3. 当前状态快照（2026-10-20）`。

第 130-139 行的代码规模表与分层清单，用下面四条命令的输出回填（表格结构不变，只改数字）：

```bash
cd /e/JavaSpace/forum
git ls-files 'src/main/java/**/*.java' | wc -l
git ls-files 'src/main/java/**/*.java' | xargs wc -l | tail -1
git ls-files 'frontend/src/**/*.js' 'frontend/src/**/*.vue' 'frontend/src/**/*.css' | grep -v '\.spec\.js$' | wc -l
git ls-files 'frontend/src/**/*.spec.js' | xargs wc -l | tail -1
```

后端分层清单里 `config/` 由 4 改为 5（新增 `WebConfig`）、`util/` 由 3 改为 4（新增 `AvatarImageProcessor`）、`controller/` 由 8 改为 9（新增 `UserController`）、`service/` 由 8 改为 9（新增 `AvatarService`）；前端目录清单里 `components/` 由 5 改为 7、`composables/` 由 4 改为 5、`styles/` 由 5 改为 6、`utils/` 由 6 改为 7、测试文件 `views/` 那行的 spec 数由 8 改为 10。

第 141-151 行的测试基线改为：

```markdown
### 3.2 测试基线

| 层 | 数量 | 结果 |
| --- | --- | --- |
| 后端 | **70** 个用例 / 11 个测试类 | 全绿 |
| 前端 | **186** 个用例 / 25 个 spec | 全绿 |
| 构建 | `VITE_BASE_PATH=/forum/ pnpm build` | 通过 |

后端测试类分布：`AvatarFlowIntegrationTest` 10、`AvatarImageProcessorTest` 21、`CommentFlowIntegrationTest` 6、`NotificationServiceTest` 4、`PostFlowIntegrationTest` 4、`RepositoryTests` 7、`Stage5FlowIntegrationTest` 5、`TagFlowIntegrationTest` 8、`AuthFlowIntegrationTest` 1、`ForumApplicationTests` 2、`JwtTokenProviderTest` 2。

前端 spec 分布：`markdown` 14、`usePostActions` 13、`tokens` 11、`useTheme` 10、`AdminView` 11、`UserAvatar` 6、`ThemeToggle` 5、`cards` 10、`notifications` 9、`format` 9、`index`（api）11、`HomeView` 8、`breakpoint` 7、`notificationLink` 7、`ProfileView` 10、`viteConfig` 6、`common` 5、`TagsView` 5、`TagPostsView` 5、`PostDetailView` 5、`NotificationsView` 4、`PostListItem` 4、`avatar` 5、`PostEditView` 3、`useResponsive` 3。
```

（分布之和为 186，须与上表一致；若 Task 6 实测不等，先改这两处再 push。）

- [ ] **Step 7: DEVELOPMENT 接口清单**

第 163 行标题改为 `### 3.4 接口清单（35 个端点）`。

第 174 行（`AdminUserController` 那行）之后插入：

```markdown
| `UserController` | `POST /api/users/me/avatar`、`DELETE /api/users/me/avatar` |
```

第 182 行（标签接口详解表）之后插入：

```markdown
头像两个端点都需要登录（未登录 401），`POST` 为 `multipart/form-data`、字段名 `file`：

| 端点 | 参数 | 返回 |
| --- | --- | --- |
| `POST /api/users/me/avatar` | `file`（JPEG/PNG，≤2MB；按魔数判定，不信任 Content-Type 与扩展名） | `UserResponse`（`avatarUrl` 为 `/uploads/avatars/<uuid>.jpg`）；非图片 400「仅支持 JPEG/PNG 图片」、坏数据 400「无法解析图片」、超限 400「图片不能超过 2MB」 |
| `DELETE /api/users/me/avatar` | — | `UserResponse`（`avatarUrl` 为 `null`）；幂等，无头像时同样返回 200 |

`GET /uploads/**` 是公开静态资源（`SecurityConfig` 的 `PUBLIC_GET` 与 `WebConfig` 的 `ResourceHandler`），生产实际由 nginx 直出，不经过后端。
```

- [ ] **Step 8: DEVELOPMENT 新增 §4.9**

第 373 行（§4.8 结尾的 `---`）之前插入：

```markdown
### 4.9 第四轮：深色模式 + 头像上传（2026-10-20）

计划：`docs/superpowers/plans/2026-10-20-round4-darkmode-avatar.md`（6 个任务：深色模式前端 → 头像上传后端 → 头像上传前端 → 部署支持上传目录 → 文档 → 全量验证与上线）

设计：`docs/superpowers/specs/2026-10-20-round4-darkmode-avatar-design.md`

目标：全站支持深色模式（默认跟随系统，可手动覆盖并记住选择）；个人资料页支持上传与移除头像，服务端统一裁剪为 256×256 JPEG，生产由 nginx 直出。

新增接口：见 §3.4 的 `UserController` 两行与「头像两个端点」小节。

新增与修改的文件：

| 位置 | 文件 | 要点 |
| --- | --- | --- |
| 前端 | `composables/useTheme.js` | 模块级单例：`mode`（system/light/dark）、`isDark` 计算、`setMode`/`toggle`/`initTheme`；`watchEffect` 同步 `html.dark` 与 `style.colorScheme`；仅 system 模式监听 `prefers-color-scheme` |
| 前端 | `components/ThemeToggle.vue` | 顶栏最左的图标下拉（跟随系统/浅色/深色），当前项打勾，`aria-label="切换主题"` |
| 前端 | `styles/tokens.css`、`styles/highlight-dark.css` | 新增 4 个语义色令牌并给出 `html.dark` 第二套；暗色语法高亮逐条加 `html.dark` 前缀 |
| 前端 | `index.html`、`main.js` | 首屏内联脚本按 `jf_theme` 提前挂 `dark` 类；`main.js` 固定 8 条样式导入顺序（Element Plus 亮/暗 → 令牌 → 共用 → Markdown → 亮/暗高亮 → 全局） |
| 前端 | `components/UserAvatar.vue`、`utils/avatar.js` | 头像组件（`url`/`name`/`size`，`@error` 回退首字母）与 `resolveAvatarUrl`（`/uploads/` 前缀拼 `BASE_URL`） |
| 前端 | `api/index.js` | 新增 `userApi.uploadAvatar`（FormData）与 `userApi.removeAvatar` |
| 前端 | `views/ProfileView.vue` | 头像上传/移除 UI + 客户端类型与 2MB 校验，删除「头像 URL」输入框 |
| 前端 | `App.vue`、`views/PostDetailView.vue` | 顶栏 28px、评论 26px、回复 22px 全部换成 `UserAvatar` |
| 后端 | `util/AvatarImageProcessor.java` | 纯函数：魔数判定、EXIF 方向 1–8、居中裁剪、缩放 256、JPEG 质量 0.9 |
| 后端 | `service/AvatarService.java` | 校验 → 转码 → UUID 落盘 → 更新 `users.avatar_url` → 删除旧文件（路径穿越防护、删除幂等） |
| 后端 | `controller/UserController.java`、`config/WebConfig.java` | 两个 `me/avatar` 端点；本地开发 `/uploads/**` 静态映射 |
| 后端 | `config/SecurityConfig.java`、`exception/GlobalExceptionHandler.java` | `/uploads/**` 公开读；`MaxUploadSizeExceededException` → 400 |
| 后端 | `application.yml`、`pom.xml` | multipart 2MB/3MB、`app.upload-dir`；新增 `metadata-extractor:2.19.0` |
| 后端 | `dto/user/UpdateProfileRequest.java`、`service/AuthService.java` | 从资料更新里移除 `avatarUrl`，头像只能走上传/删除接口 |
| 部署 | `Dockerfile`、`docker-compose.yml` | 镜像内 `/app/uploads` + `UPLOAD_DIR`；卷 `${UPLOAD_HOST_DIR}:/app/uploads` |
| 部署 | `deploy/deploy.sh`、`deploy/nginx/forum.conf` | 建目录 + `podman unshare chown 1001:1001` + `.env` upsert + 404 冒烟；`^~ /forum/uploads/` 排在 `/forum/` 之前 |

关键取舍：

- **双套 CSS 变量 + `html.dark`，不做两套主题包**：令牌已收敛在 `tokens.css`，加一套暗色取值即可覆盖全站；Element Plus 用官方 `dark/css-vars.css`（选择器也是 `html.dark`），两边同步切换，无需换样式表、无闪烁。
- **首屏脚本写在 `index.html`**：JS 包加载前就把 `dark` 类挂上，避免白屏闪一下；`useTheme` 之后接手同一份状态，两边都以 `localStorage.jf_theme` 为准。
- **只支持 system/light/dark 三档，不做主题色自定义**：范围为「跟随系统 + 手动覆盖」，跨设备同步与自定义色留作后续（见 §5.2）。
- **头像一律服务端重编码**：只按魔数接受 JPEG/PNG，重编码为 JPEG，文件名由服务端生成 UUID。客户端文件名不参与路径拼接，因此伪造扩展名、SVG 脚本、EXIF 注入都进不了磁盘。
- **按 EXIF 方向校正**：手机照片常带 Orientation 1–8，不校正会出现「躺倒的头像」；`metadata-extractor` 只读元数据，`orientationTransform` 用 8 条显式仿射矩阵实现并逐像素单测。
- **存储用宿主机卷而不是容器内目录**：容器重建不丢头像；nginx 用 `alias` 直出，静态文件不经过后端，也不占容器写层。
- **资料接口不再接受 `avatarUrl`**：否则用户可以把头像指向任意外链，删除旧文件的逻辑也会被绕过；头像只能通过上传/删除两个接口变更（未知字段被 Jackson 忽略，集成测试守住「传了也不生效」）。
- **不新增 Flyway 迁移**：`users.avatar_url` 早已存在（V1），本轮只改它的取值来源。

测试与验证（2026-10-20 实测）：

- 后端 `mvn -B clean test`：11 个测试类、70 个用例全绿。新增 `AvatarImageProcessorTest` 21 例（方向 1–8 逐像素映射、居中裁剪宽/高/方图、缩放到 256、JPEG/PNG 魔数、坏数据）、`AvatarFlowIntegrationTest` 10 例（未登录 401、JPEG/PNG 上传、换图删旧、伪造类型 400、超限 400、`/uploads/**` 直出、删除与幂等、资料接口不再改头像）。
- 前端 `pnpm test`：25 个 spec、186 个用例全绿。新增 `tokens.spec.js` 11 例、`useTheme.spec.js` 10 例、`ThemeToggle.spec.js` 5 例、`avatar.spec.js` 5 例、`UserAvatar.spec.js` 6 例；`api/index.spec.js` 由 9 例增至 11 例、`ProfileView.spec.js` 由 6 例增至 10 例。
- 构建：`VITE_BASE_PATH=/forum/ pnpm build` 通过（子路径产物引用 `/forum/assets/`）。
- 上线验收（2026-10-20）：推送后 Jenkins 构建 **SUCCESS**；`/forum/uploads/` 不存在文件返回 404（证明 nginx 直出）；登录后上传头像返回 `/uploads/avatars/<uuid>.jpg`，经 nginx 取回 200 `image/jpeg`；容器 `down/up` 重建后头像仍可访问（卷生效）。

已知限制：

- **多标签页主题不同步**：`jf_theme` 变化只作用于当前标签页，其它标签页要刷新才跟随；监听 `storage` 事件可解决，本轮未做（见 §5.1 T12）。
- **头像文件无备份与清理**：`/pd/Program/forum/uploads/avatars/` 不在数据库备份范围内；删除用户不会级联删文件，长期运行会留下孤儿文件。
```

- [ ] **Step 9: DEVELOPMENT 技术债与候选功能**

第 390 行（T10 那行）之后插入两行：

```markdown
| T11 | **头像文件不在备份范围内** | 低 | 头像存在宿主机 `/pd/Program/forum/uploads/avatars/`，数据库备份不含它；容器重建不丢（卷），但整机重装会丢。删除账号/换头像产生的孤儿文件也没有清理任务 |
| T12 | **主题选择不跨标签页同步** | 低 | `jf_theme` 只写当前标签页的 `localStorage`，其它已打开标签页不会实时切换；监听 `storage` 事件即可修，本轮未做 |
```

第 398-401 行的候选功能表，把「头像上传」「深色模式」两行改为已完成：

```markdown
| ~~头像上传~~ ✅ 已完成 | 中 | 中 | 第四轮已交付：服务端裁剪 256×256 JPEG + 宿主机卷 + nginx 直出（§4.9） |
| ~~深色模式~~ ✅ 已完成 | 低 | 小 | 第四轮已交付：双套令牌 + `html.dark` + 跟随系统/手动覆盖（§4.9） |
```

第 405 行改为：

```markdown
前两轮候选已全部交付（第三轮 Markdown 渲染与标签检索见 §4.8，第四轮深色模式与头像上传见 §4.9）；其余候选留待后续轮次，按同样的「审计 → 计划 → 逐任务执行 → 验证 → 提交」流程推进，避免一次铺开。
```

- [ ] **Step 10: DEVELOPMENT 变更日志**

第 456 行（`2026-10-19 feat 第三轮功能` 那行）之前插入五行（提交列先按 §0 约定写 `本次提交`，Task 6 回填前四行）：

```markdown
| 2026-10-20 | docs | 记录第四轮深色模式与头像上传功能 | 本次提交 |
| 2026-10-20 | chore | 部署支持头像上传目录 | 本次提交 |
| 2026-10-20 | feat | 头像上传前端交互与全站头像展示 | 本次提交 |
| 2026-10-20 | feat | 头像上传后端接口与图片处理 | 本次提交 |
| 2026-10-20 | feat | 深色模式与主题切换 | 本次提交 |
```

- [ ] **Step 11: 部署手册更新**

`docs/deployment/README.md` 第 8-17 行的形态图，在 `/forum/` 那行之后插入一行：

```
    ├─ /forum/uploads/ → /pd/Program/forum/uploads/   头像（nginx 直出，容器外）
```

第 30-42 行的 `.env` heredoc，在 `JAVA_OPTS` 行之前插入：

```bash
UPLOAD_HOST_DIR=/pd/Program/forum/uploads
```

并在 heredoc 结束的 `EOF` 之后、`chmod 600 .env` 之前插入：

```bash
# 头像目录（容器内 UID 1001 经 rootless podman 映射到宿主 subuid，必须用 podman unshare chown）
mkdir -p /pd/Program/forum/uploads/avatars
podman unshare chown -R 1001:1001 /pd/Program/forum/uploads
```

第 91-96 行的冒烟代码块末尾追加：

```bash
# 头像目录必须由 nginx 直出：不存在的文件 404（若返回 200 则是落到了 SPA 回退）
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: raspi' \
  http://127.0.0.1/forum/uploads/__probe__/missing.jpg    # 期望 404
# 上传头像并取回：TOKEN 从登录接口现取，PNG 用 base64 现造
printf '%s' 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==' \
  | base64 -d > /tmp/jf-avatar.png
TOKEN="$(curl -fsS -H 'Host: raspi' -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123456"}' \
  http://127.0.0.1/forum/api/auth/login | sed -E 's/.*"token":"([^"]+)".*/\1/')"
curl -fsS -H 'Host: raspi' -H "Authorization: Bearer $TOKEN" \
  -F 'file=@/tmp/jf-avatar.png' http://127.0.0.1/forum/api/users/me/avatar
```

第 110-115 行的排障表，在「后端启动即退出」那行之后插入三行：

```markdown
| 上传头像 413 / 400「图片不能超过 2MB」 | 前端已挡 2MB，后端 multipart 也是 2MB（`application.yml`）；确需放宽要同时改两处，并同步 `AvatarService.MAX_BYTES` |
| `/forum/uploads/xxx.jpg` 返回 200 但内容是 HTML | nginx 的 `location ^~ /forum/uploads/` 没生效或排在 `/forum/` 之后；`sudo nginx -T \| grep -A3 uploads` 检查，然后 `sudo systemctl reload nginx` |
| 头像上传 500「保存头像失败」 | 宿主目录属主不对：`sudo ls -ld /pd/Program/forum/uploads`，应为 subuid 映射后的属主；重跑 `podman unshare chown -R 1001:1001 /pd/Program/forum/uploads` |
```

第 117-121 行的已知限制末尾追加：

```markdown
- **头像文件不在数据库备份里**：`/pd/Program/forum/uploads/avatars/` 与本机其它业务数据一样只存在于 `/pd` 盘；整机重装需连同该目录一起迁移，否则头像全部 404（前端会回退首字母，不影响其它功能）。
```

- [ ] **Step 12: 校验文档改动**

Run:

```bash
cd /e/JavaSpace/forum && grep -c '本次提交' docs/DEVELOPMENT.md
grep -n 'round4-darkmode-avatar' docs/DEVELOPMENT.md
grep -n 'uploads' docs/deployment/README.md | head -10
git diff --stat
```

Expected:
- `本次提交` 出现 6 次（第四轮五行 + 原有 2026-10-19 的部署验收行）
- `round4-darkmode-avatar` 命中 §4.9 的「计划」行
- uploads 命中形态图、引导、冒烟、排障四处
- `git diff --stat` 只含 `README.md`、`docs/DEVELOPMENT.md`、`docs/deployment/README.md` 三个文件

- [ ] **Step 13: 提交**

```bash
git add README.md docs/DEVELOPMENT.md docs/deployment/README.md
git commit -m "docs: 记录第四轮深色模式与头像上传功能"
```

---

## Task 6: 全量验证与上线

**Files:**
- Modify: `docs/DEVELOPMENT.md:454-460`（§8 变更日志回填哈希）

- [ ] **Step 1: 后端全量测试**

Run:

```bash
cd /e/JavaSpace/forum && mvn -B clean test
grep -h 'Tests run' target/surefire-reports/*.txt
```

Expected: `BUILD SUCCESS`；逐类汇总 `Tests run: 70, Failures: 0, Errors: 0, Skipped: 0`（11 个测试类）。

- [ ] **Step 2: 前端全量测试与子路径构建**

Run:

```bash
cd frontend && pnpm test
cd /e/JavaSpace/forum/frontend && VITE_BASE_PATH=/forum/ pnpm build
```

Expected: `Test Files 25 passed`、`Tests 186 passed`；构建成功，`dist/index.html` 里是 `/forum/assets/...`。

- [ ] **Step 3: 产物打包**

Run: `cd /e/JavaSpace/forum && mvn -B -DskipTests package`
Expected: `BUILD SUCCESS`，生成 `target/forum-0.0.1-SNAPSHOT.jar`。

- [ ] **Step 4: 核对文档数字（不一致先修）**

比对 Step 1/2 的实测与 Task 5 写进 `docs/DEVELOPMENT.md` §3.2 的数字（70 / 11、186 / 25）：

- 完全一致 → 跳到 Step 5。
- 不一致 → 按实测改 `docs/DEVELOPMENT.md` §3.2 的计数与分布、`README.md` 的两处计数，然后：

```bash
git add README.md docs/DEVELOPMENT.md
git commit -m "docs: 校正第四轮测试基线数字"
```

**判据：** 任何用例失败都必须在这里修掉（红的就是红的，不许 `-DskipTests` 上线）。修不动且确认是计划错误时，回退对应任务重做，不带病发布。

- [ ] **Step 5: 确认工作区干净并推送**

Run:

```bash
cd /e/JavaSpace/forum && git status --short && git log --oneline -6
git push origin master
```

Expected: `git status --short` 无输出；`git log --oneline -6` 能看到 4 个功能提交 + 2 个文档提交；`git push` 成功。

- [ ] **Step 6: 等待 Jenkins（≤5 分钟轮询）**

Run（每 15 秒查一次，最多 20 次）：

```bash
for i in $(seq 1 20); do
  status="$(ssh raspi 'f=$(sudo ls -1t /var/lib/jenkins/jobs/javaforum/builds/*/log | head -1); sudo grep -h "^Finished:" "$f" | tail -1')"
  echo "第 $i 次: ${status:-构建中}"
  case "$status" in
    *SUCCESS*) echo "构建成功"; break ;;
    *FAILURE*|*ABORTED*) echo "构建失败"; break ;;
  esac
  sleep 15
done
```

Expected: 约 5 分钟内出现 `Finished: SUCCESS`。若失败，取日志：

```bash
ssh raspi 'sudo tail -n 80 /var/lib/jenkins/jobs/javaforum/builds/'"$(date +%s)"'/log' 2>/dev/null || \
ssh raspi 'sudo tail -n 80 "$(sudo ls -1t /var/lib/jenkins/jobs/javaforum/builds/*/log | head -1)"'
```

失败处理：先看是「测试红 / 构建失败 / deploy.sh 冒烟守卫失败」哪一段。`deploy.sh` 的冒烟守卫报 `/forum/uploads/ 未直出` 时，检查 snippet 是否随镜像提交一起发布（`sudo nginx -T | grep -A2 uploads`）后手动 Build 一次；其余失败按日志修代码，重新提交并重复 Step 6。

- [ ] **Step 7: raspi 冒烟（API / 前端 / uploads）**

Run:

```bash
ssh raspi 'curl -fsS http://127.0.0.1:8086/api/health'              # {"success":true,...}
ssh raspi "curl -fsS -H 'Host: raspi' http://127.0.0.1/forum/ | grep -c '/forum/assets/'"
ssh raspi "curl -fsS -H 'Host: raspi' http://127.0.0.1/forum/posts/1 | grep -c 'id=\"app\"'"
ssh raspi "curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: raspi' http://127.0.0.1/forum/uploads/__probe__/missing.jpg"
```

Expected: 健康检查返回 `"success":true`；两个 `grep -c` 都 ≥1；最后一个输出 `404`（**不能**是 200 —— 200 说明请求被 SPA 回退吃掉了）。

- [ ] **Step 8: raspi 头像上传全链路**

Run:

```bash
ssh raspi 'bash -s' <<'REMOTE'
set -euo pipefail
# 1×1 PNG（合法 PNG 魔数，服务端会放大并居中裁剪到 256×256）
printf '%s' 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==' \
  | base64 -d > /tmp/jf-avatar.png
TOKEN="$(curl -fsS -H 'Host: raspi' -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123456"}' \
  http://127.0.0.1/forum/api/auth/login | sed -E 's/.*"token":"([^"]+)".*/\1/')"
test -n "$TOKEN"
RESP="$(curl -fsS -H 'Host: raspi' -H "Authorization: Bearer $TOKEN" \
  -F 'file=@/tmp/jf-avatar.png' http://127.0.0.1/forum/api/users/me/avatar)"
echo "$RESP"
URL="$(printf '%s' "$RESP" | sed -E 's/.*"avatarUrl":"([^"]+)".*/\1/')"
echo "avatarUrl=$URL"
curl -fsS -o /dev/null -w 'nginx 取回头像: %{http_code} %{content_type}\n' \
  -H 'Host: raspi' "http://127.0.0.1/forum$URL"
ls -l /pd/Program/forum/uploads/avatars/ | tail -3
# 删除头像（幂等），再确认文件已消失、URL 404
curl -fsS -H 'Host: raspi' -H "Authorization: Bearer $TOKEN" \
  -X DELETE http://127.0.0.1/forum/api/users/me/avatar >/dev/null
curl -s -o /dev/null -w '删除后取回: %{http_code}\n' -H 'Host: raspi' "http://127.0.0.1/forum$URL"
REMOTE
```

Expected: 上传响应里 `avatarUrl` 形如 `/uploads/avatars/<uuid>.jpg`；`nginx 取回头像: 200 image/jpeg`；`ls` 看到新文件；`删除后取回: 404`。

- [ ] **Step 9: 容器重建后头像仍在**

Run:

```bash
ssh raspi 'sudo -u pi podman-compose -p forum -f /pd/Program/forum/deploy/docker-compose.yml down'
ssh raspi 'sudo -u pi podman-compose -p forum -f /pd/Program/forum/deploy/docker-compose.yml up -d'
ssh raspi 'for i in $(seq 1 40); do curl -fsS http://127.0.0.1:8086/api/health >/dev/null 2>&1 && break; sleep 3; done; curl -fsS http://127.0.0.1:8086/api/health'
ssh raspi 'sudo -u pi podman ps --filter name=forum-backend --format "{{.Names}} {{.Status}}"'
```

Expected: 后端重新就绪（`"success":true`），容器 `Up`；此时再执行一次 Step 8 的上传并确认宿主 `/pd/Program/forum/uploads/avatars/` 里的文件在容器重建后仍能被 nginx 200 取回（文件在卷里，不在容器写层）。

- [ ] **Step 10: 回填变更日志哈希**

Run:

```bash
cd /e/JavaSpace/forum && git log --oneline -6
```

把前四个功能/部署提交（`feat: 深色模式与主题切换`、`feat: 头像上传后端接口与图片处理`、`feat: 头像上传前端交互与全站头像展示`、`chore: 部署支持头像上传目录`）的短哈希，替换 `docs/DEVELOPMENT.md` §8 里对应四行的 `本次提交`（第五行是本次 docs 提交自身，按 §0 约定保留 `本次提交`）。改完核对：

```bash
grep -n '^| 2026-10-20' docs/DEVELOPMENT.md
```

Expected: 5 行里 4 行带真实短哈希，1 行仍是 `本次提交`。

- [ ] **Step 11: 提交哈希回填并推送**

```bash
git add docs/DEVELOPMENT.md
git commit -m "docs: 回填第四轮提交哈希"
git push origin master
```

Expected: 推送成功（会再触发一次 Jenkins 构建，属预期；该次构建的代码与镜像 tag 均已包含第四轮改动，构建成功后线上版本不变）。

---

## Self-Review 记录

### 1. Spec 覆盖对照

| Spec 条目 | 落点 |
| --- | --- |
| §1 范围内：深色模式（跟随系统 + 手动覆盖）、头像上传与移除、seed 目录、nginx 直出 | Task 1 / Task 2 / Task 3 / Task 4 |
| §1 范围外：主题色自定义、跨设备同步、头像审核、Flyway 迁移 | 明确不做：Task 4 不加迁移；§5.2 候选功能仍在文档中（Task 5 Step 9） |
| §3 现状：Element Plus 暗色文件、4 处一次性语义色、基线数字 | Task 1 Step 1/3/21/22（导入与令牌）、Task 1 Step 17-19（4 处字面量）、Task 5 Step 6（基线更新） |
| §4.1 `useTheme`（mode/isDark/setMode/toggle/initTheme、localStorage `jf_theme`、watchEffect 同步） | Task 1 Step 5-9（含 10 个 spec） |
| §4.2 令牌表（4 个新增 + 双套 + 三条守卫） | Task 1 Step 1-4（`tokens.spec.js` 5 + 3 + 3 例） |
| §4.3 `main.js` 8 条样式导入顺序 | Task 1 Step 14-16（顺序断言 + 实施） |
| §4.4 `highlight-dark.css` 逐条前缀、`pre` 底色、`pre code.hljs` 透明 | Task 1 Step 19（markdown.css）与 Step 21-22（highlight-dark.css 全文） |
| §4.5 `ThemeToggle.vue`（三档、打勾、aria-label、放 `.nav-actions` 最左） | Task 1 Step 10-13、Step 23 |
| §4.6 `index.html` 内联脚本（在样式与模块脚本之前） | Task 1 Step 15-16、Step 24 |
| §4.7 边界（坏 localStorage→system、matchMedia 缺失→false、多标签页不同步为技术债） | Task 1 Step 6-9 的 spec；Task 5 Step 9 的 T12 |
| §5.1 两个端点 + `GET /uploads/**` 公开 + 资料接口删除 `avatarUrl` | Task 2 Step 7-8；Task 5 Step 7 |
| §5.2 部署（Dockerfile/compose/deploy.sh/nginx/.gitignore/`.env.example`） | Task 4 全部 7 个文件 |
| §5.3 处理 8 步（魔数、EXIF 1–8、ImageIO、居中裁剪、256、质量 0.9、UUID 落盘、删旧文件、删除幂等） | Task 2 Step 3（`AvatarImageProcessor`）与 Step 7（`AvatarService`） |
| §5.4 前端（`userApi`、`resolveAvatarUrl`、`UserAvatar` 28/26/22/64、资料页上传/移除 + 校验） | Task 3 Step 1-16（含尺寸与替换点） |
| §5.5 安全（魔数、重编码、UUID 名、大小限制、路径穿越、公开只读） | Task 2 Step 3/7 的注释与实现；Task 2 Step 5 的伪造类型/超限用例；Task 5 Step 7 |
| §6.1 后端 10 个集成用例 | Task 2 Step 5（10 个 `@Order` 方法逐一对应） |
| §6.2 前端 6 个新增/更新 spec（26 文件约 165 例的目标） | Task 1（tokens/useTheme/ThemeToggle）、Task 3（avatar/UserAvatar/ProfileView/api）；实测 25 文件 186 例，差异见下「歧义解决」 |
| §6.3 raspi 手工验收 8 条 | Task 6 Step 7-9（健康/前端/SPA/uploads 404/上传/取回/删除/重建） |
| §7 提交拆分（5 个提交 + 上线 + 回滚口径） | Task 1-5 的 commit 步骤逐字使用 §7 文案；Task 6 推送与构建；回滚口径见 Task 6 Step 6 失败处理与 Task 5 Step 11 的部署手册 |
| §8 风险表（无备份、孤儿文件、nginx 顺序、EXIF 未覆盖分支等） | Task 4 Step 3 的顺序说明 + Task 4 Step 5 守卫；Task 5 Step 9（T11/T12）；本 Self-Review 第 5 节 |
| §9 验收清单 | Task 6 Step 1-9 |
| §10 技术债 | Task 5 Step 9（T11/T12） |

结论：无遗漏条目。逐任务复核过「每个 spec 小节都能指向至少一个 Step」。

### 2. 占位符扫描

对全篇执行了以下模式的检查（`TBD|TODO|待补|类似 Task|同上|见 Task [0-9]|补充错误处理|酌情`）：

- 无 `TBD` / `TODO` / 「类似 Task N」/ 「同上」。
- 代码块全部是可直接粘贴的完整实现或精确 before/after；测试块全部包含完整断言。
- 两处「数据依赖型」内容不是占位符，而是给了唯一来源：
  - `docs/DEVELOPMENT.md` §3.1 的文件数/行数 → Task 5 Step 6 给出四条 `git ls-files | wc -l` 命令与对应单元格。
  - §8 的提交哈希 → Task 5 Step 10 先写 `本次提交`（遵守 §0 维护约定），Task 6 Step 10 用 `git log --oneline -6` 回填。
- 修复记录：Task 1 早期草稿里 `tokens.spec.js` 的两次「运行测试」期望值写成 9/12，与实际的 8/11 不符，已改为 8 与 11（用例数：令牌 5 + 导入顺序/首屏脚本 3 + 字面量 3）。

### 3. 类型与命名一致性

| 名字 | 定义处 | 使用处 | 一致 |
| --- | --- | --- | --- |
| `STORAGE_KEY = 'jf_theme'` | Task 1 `useTheme.js` | `index.html` 内联脚本、`ThemeToggle` spec、Task 5 文档 | ✅ |
| `useTheme()` 返回 `{ mode, isDark, systemPrefersDark, setMode, toggle, initTheme }` | Task 1 | `ThemeToggle.vue` 只取 `mode`/`setMode`；spec 全取 | ✅ |
| `MODE_ITEMS` 的 `value` 取值 `system/light/dark` | Task 1 `ThemeToggle.vue` | spec 的 `command`、`setMode` 校验数组 `VALID_MODES` | ✅ |
| `resolveAvatarUrl(url)` | Task 3 `utils/avatar.js` | `UserAvatar` 的 `src` 计算属性 | ✅ |
| `UserAvatar` props `url/name/size`（默认 28） | Task 3 | ProfileView 64、App 28、PostDetail 26/22 | ✅ |
| `userApi.uploadAvatar(file)` / `userApi.removeAvatar()` | Task 3 `api/index.js` | ProfileView 上传/移除、`api/index.spec.js` 断言 | ✅ |
| `AvatarImageProcessor.detectType/readOrientation/orientationTransform/centerCrop/resizeTo/toJpegBytes/process` | Task 2 | `AvatarService.upload` 调 `process`；测试逐方法覆盖 | ✅ |
| `AvatarService.upload(Long, MultipartFile)` / `remove(Long)` → `UserResponse` | Task 2 | `UserController` 两处调用 | ✅ |
| 端点 `/api/users/me/avatar`（POST/DELETE） | Task 2 `UserController` | Task 3 `userApi`、Task 5 §3.4、Task 6 冒烟 | ✅ |
| 目录变量 `app.upload-dir` / 容器 `UPLOAD_DIR` / 宿主 `UPLOAD_HOST_DIR` | Task 2 `application.yml`、Task 4 三个文件 | `WebConfig`、`AvatarService`、compose、deploy.sh、`.env.example`、文档 | ✅ |
| `AVATAR_URL_PREFIX = "/uploads/avatars/"` | Task 2 `AvatarService` | 集成测试的正则断言、Task 3 `resolveAvatarUrl`、Task 4 nginx alias | ✅ |
| 令牌名 `--jf-color-code-bg/text-weak/unread-bg/anchor-bg` | Task 1 令牌表 | Task 1 的字面量替换、`tokens.spec.js` 的 11 项清单、Task 5 文档 | ✅ |
| 错误文案「仅支持 JPEG/PNG 图片」「无法解析图片」「图片不能超过 2MB」 | Task 2 处理器/服务/异常处理器 | 集成测试断言 `contains`、Task 3 前端提示复用同一措辞 | ✅ |

已知的「同一逻辑两处实现」：`AvatarService.toUserResponse` 与 `AuthService.toUserResponse` 各有一份 builder（均为私有方法）。本轮不抽公共 mapper，理由是改动面会扩到 `AuthService` 全部返回路径，风险大于收益；已在 §5.1 不新增技术债条目的前提下接受这点重复。

### 4. 歧义与解决方式

1. **spec §6.2 估计「26 文件约 165 例」，而按逐条测试写完是 25 文件 186 例。** 解决：以「文件的职责边界」而不是数量为准——`useTheme` 10 例、`tokens` 11 例、`avatar` 5 例、`UserAvatar` 6 例、`ThemeToggle` 5 例，多出的用例来自方向 1–8 与双套令牌逐项断言。计划与文档统一写实测值，并在 Task 6 Step 4 保留「不一致先修文档」的校正步骤。
2. **spec §6.1 说「`@DynamicPropertySource` + JUnit `@TempDir`」，但两者执行顺序在 Spring/JUnit 扩展之间不保证。** 解决：`uploadDir` 声明为 `static`，`@DynamicPropertySource` 指向 `resolveUploadDir()`，在属性求值时兜底 `Files.createTempDirectory`，两条路径拿到同一个目录；集成测试不依赖初始化顺序。
3. **spec 未说明「资料更新接口收到 `avatarUrl` 时该报错还是忽略」。** 解决：选「忽略」——字段已从 DTO 删除，Jackson 默认忽略未知字段，接口仍返回 200 但头像不变；用集成测试第 10 例把这个行为固定下来，避免以后有人误加回字段而无人发现。
4. **spec §4.2 只给了「默认浅色 + 手动覆盖」，没写 `toggle()` 在 `system` 模式下的语义。** 解决：按「当前实际效果取反」——系统为暗时 `toggle()` 得到 `light`，系统为亮时得到 `dark`；spec 里 `useTheme` 的 10 个用例覆盖这一条。
5. **spec §5.2 的 `podman unshare chown` 与「容器 UID 1001」的关系没有展开。** 解决：在 Task 4 Step 4 的脚本注释与 Task 5 的部署手册里写明「rootless podman 把容器 1001 映射为宿主 subuid，宿主直接 `chown 1001` 是错的用户」，排障表也给了对应条目。
6. **nginx 里 `^~ /forum/uploads/` 与 `/forum/assets/` 的先后。** spec 只要求「在 `/forum/` 之前」。解决：放在 assets 之后、SPA 之前，并用 Task 4 Step 5 的 404 冒烟守卫把「顺序写错」变成可自动发现的失败。

### 5. 风险提示（写给执行者）

- **`tokens.spec.js` 的消费方扫描**是本轮最容易被误伤的门槛：新增令牌必须同时给出 `var()` 使用处，否则该 spec 直接红。若确实需要「先加令牌、后接使用方」，应把两件事放进同一个提交。
- **`breakpoint.spec.js` 要求全 `src` 恰好 6 处 `@media (max-width: 768px)` 且禁止 `min-width`**：本计划所有新增样式都没有媒体查询，实施时不要顺手加。
- **`ProfileView.spec.js` 的 `vi.hoisted` + `element-plus` mock**：mock 整个 `element-plus` 后，`ElMessage` 之外的导入都会变成 undefined；本文件只用 `ElMessage`，若以后 ProfileView 引入别的 Element Plus 具名导出，需要同步扩充 mock。
- **`@TempDir` 目录与 H2 数据**：集成测试每次运行都会在系统临时目录留下 `avatar-upload-*`，由 JUnit 清理；不要把它指到仓库内，否则会污染 `git status`。
- **上线顺序**：Task 4 的 nginx 配置随 `deploy.sh` 一起发布，但**卷与目录**由 `deploy.sh` 主动创建；若手工跳过 `deploy.sh` 只 `podman-compose up`，容器会因宿主目录不存在而以 root 自动创建目录，导致 UID 1001 写不进去（表现为「保存头像失败」500）。遇到这种情形按 Task 4 Step 4 重跑一次 `podman unshare chown`。
- **回滚口径**：本轮没有数据库迁移，回滚只需 `IMAGE_TAG=<上一个 BUILD_NUMBER>` 重建容器 + 用旧 frontend dist 覆盖；`uploads/` 目录里的文件不会被回滚删除，旧版本前端不认识 `avatarUrl` 时会退回首字母头像，无数据损失。


## Task 1 质量修复记录（实施偏差）

- 监听绑定改为能力检测：优先 `addEventListener`，旧版 Safari 回退 `addListener`/`removeListener`，模块初始化不再抛错白屏。
- 切回 `system` 时先用 `query.matches` 刷新系统偏好再绑定；`watchEffect` 内先同步监听、后读 `isDark`，避免晚一帧才上类。
- `STORAGE_KEY` 仅由 `frontend/src/composables/useTheme.js` 导出，两个 spec 改为导入；`index.html` 内联脚本保留字面量并加注释，由 spec 断言键与 `dark` 类一致。
- `highlight.js` 由 `^11.12.0` 钉为 `11.12.0` 并同步 lockfile；`highlight-dark.css` 头注释改为 11.12.0 手工同步说明（调色板未改）。
- 补充测试：Storage 读写抛错、`initTheme()` 幂等/返回值、系统暗→亮、显式模式回切 system、旧版 `addListener` 环境。
- 小项：`useTheme()` 的 `mode` 改为 `readonly`；`ThemeToggle` 合并重复 `find`、激活项加 `aria-current`。

### Task 2 质量修复记录（实施偏差）

- 解压炸弹：`ImageInputStream` + `ImageReader` 解码前读头部，单边 >4096 或总像素 >16,777,216 抛 400「图片尺寸过大」；炸弹测试只伪造 IHDR，不分配真实像素。
- 透明 PNG：方向变换/裁剪/缩放改用 `TYPE_INT_ARGB`，只在 JPEG 编码前 `toRgb` 铺白底；透明角落变白、红方块保留。
- 畸形上传：新增 `MissingServletRequestPartException`→400「缺少图片文件」、`MultipartException`→400「请使用 multipart/form-data 上传图片」；缺 part/错名/非 multipart 各一例。
- 原子性：同目录临时文件 → `ATOMIC_MOVE` 改名 → 保存 DB；保存失败删新文件，旧文件在 `afterCommit` 删除（无事务时立即删）；`remove()` 同序。
- 跨用户误删：仅删服务端 UUID 文件名且 `existsByAvatarUrlAndIdNot` 为假；两用户共享 URL 时文件保留、另一用户 URL 仍有效。
- 测试卫生：去掉 `@TestMethodOrder`/静态 token，每例注册唯一用户；上传目录静态建一次并在 `@AfterAll` 清理，`resolveUploadDir()` 保留。
- 补测：WEBP/GIF 拒绝、2.1MB→400、真实 EXIF 方向 6、透明 PNG、炸弹、共享 URL；`AvatarServiceTest` 模拟保存失败覆盖文件/DB 顺序。
- 小项：旋转双线性、降采样分步减半；EXIF 失败 `log.debug`；`UserResponse.from` 合并 mapper；`PUBLIC_GET` 收窄为 `/uploads/avatars/**`；no-op 删除不再打 warn 栈。
- 偏差：计划 §「同一逻辑两处实现」原接受理由随 mapper 抽出失效，保留原文不改，仅在此记录；其余按计划执行，无功能延期。
