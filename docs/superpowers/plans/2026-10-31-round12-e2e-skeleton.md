# 第十二轮实现计划：前端 E2E 骨架

- 日期：2026-10-31
- 设计：`docs/superpowers/specs/2026-10-31-round12-e2e-skeleton-design.md`
- 仓库根：`E:\JavaSpace\forum`（Git Bash：`/e/JavaSpace/forum`）
- 目标基线：后端 177（21 类）、前端 Vitest 330（33 spec）、新增 E2E 5 spec / 8 test（单列）

## 0. 执行约定

- 直接在 `master` 提交，不建分支、不 amend、不 rebase、不 force push；提交信息逐字使用附录 A，每个 Task 提交后工作区必须干净。
- 计划 6 次提交：设计计划 → Playwright 与 bootstrap/home → 主题/详情/登录 → 管理页 → 文档 → 外网冒烟回填。
- 前端命令一律在 `frontend/` 下执行；Git Bash 下构建必须 `MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build`。
- **不改业务代码**：`frontend/src/**`、`src/main/java/**`、`src/test/java/**` 零 diff；只新增 `frontend/e2e/`、`frontend/playwright.config.js`，修改 `frontend/package.json`、`pnpm-lock.yaml`、`frontend/.gitignore` 与文档。
- 不把 E2E 加进 `Jenkinsfile`，`RUN_TESTS` 语义不变；E2E 只在本地与发布前手工跑。
- 不把真实管理员密码写进仓库：公网通过 `E2E_ADMIN_USERNAME` / `E2E_ADMIN_PASSWORD` 传入。
- 用例数先按设计写 8 条，实际以 `playwright test --list` 为准；Playwright 版本以 `pnpm add` 实际写入的为准（文档记 `^1.x`），二者都回填 §12。
- 每个 Task 的「预期结果」包含红灯证据；没有红灯的改动不算完成。命令统一用 `pnpm exec playwright ...`（参数透传比 `pnpm test:e2e` 直观）。

## 1. 现状与文件清单

### 1.1 已确认事实

- HEAD `1eb9bd2`，工作区干净；`frontend/.gitignore` **已存在**（非新建）。
- `frontend/package.json` 6-13 行 6 个 scripts、25-32 行 devDependencies；无 `@playwright/test`；`test:watch` 是最后一个 script。
- `frontend/vite.config.js` 45 行端口 5173，47-51 行 `/api` → `http://localhost:8080`，53-57 行 Vitest `include: ['src/**/*.spec.js']`。
- `frontend/src/router/index.js` 4-42 行有 `/`、`/login`、`/posts/:id`、`/tags`、`/search`、`/admin`（`requiresAuth + requiresAdmin`）。
- `LoginView.vue` 42-50 行 placeholder「用户名」「密码」、`class="submit"`、文案「登录」，29 行成功 toast「登录成功」。
- `HomeView.vue` 95-116 行 `.board-tag`（首个「全部」），136-156 行 `.post-item`，145 行空态「暂无帖子」。
- `PostDetailView.vue` 214 行 `h1.post-title`，236 行 `.markdown-body`，252-254 行点赞按钮；172-183 行删除确认标题「提示」。
- `AdminView.vue` 129-141 行板块表格（表头含「帖子数」），58-69 行 `removeBoard`；**板块表格无分页**（分页只在用户管理 tab，191-199 行）。
- `ThemeToggle.vue` 8-11 行「跟随系统/浅色/深色」，29 行 `aria-label="切换主题"`。
- `App.vue` 52-55 行 `.brand` 内「JavaForum」，63-88 行 header（登录后 `.user-chip`/`.username`，未登录按钮「登录」）。
- `auth.js` 9-10 行 `jf_token`/`jf_user`，62-67 行 `clearAuth` 同时移除两键。
- `DataInitializer.java` **只创建 admin**（`admin123456`、昵称「管理员」、ADMIN+MODERATOR+USER），不建板块/帖子。
- `AuthController.login` 50-64 行；`BoardController` 的 POST/PUT/DELETE 均 `@PreAuthorize("hasRole('ADMIN')")`；`PostController` POST 65-70 行、DELETE 78-82 行。
- 登录限流：5 次失败 / 15 分钟窗口；成功登录清空该 IP 窗口。
- `Jenkinsfile` Tests stage 71-79 行（仅 `RUN_TESTS=true`），Build Frontend 92-97 行。
- `check:bundle` 是 5 项体积预算 + 9 个关键 CSS 选择器，不是「60 组」。

### 1.2 本轮文件

| 文件 | 动作 | Task |
| --- | --- | --- |
| `docs/superpowers/specs/2026-10-31-round12-e2e-skeleton-design.md` | 新增 | 1 |
| `docs/superpowers/plans/2026-10-31-round12-e2e-skeleton.md` | 新增 | 1 |
| `frontend/package.json`、`frontend/pnpm-lock.yaml` | 修改（依赖 + 2 script） | 2 |
| `frontend/playwright.config.js` | 新增 | 2 |
| `frontend/e2e/support/api.js`、`fixtures.js` | 新增 | 2 |
| `frontend/e2e/home.spec.js` | 新增 | 2 |
| `frontend/e2e/{theme,post-detail,auth}.spec.js` | 新增 | 3 |
| `frontend/e2e/admin.spec.js` | 新增 | 4 |
| `frontend/.gitignore` | 修改（3 行） | 2 |
| `docs/DEVELOPMENT.md`、`README.md`、`docs/deployment/README.md` | 修改 | 5 |

### 1.3 不改的文件

`frontend/src/**`（含全部 `.vue`/`.js`/`.spec.js`）、`frontend/vite.config.js`、`frontend/index.html`、`src/main/java/**`、`src/test/java/**`、`pom.xml`、`Jenkinsfile`、`deploy/**`、`frontend/scripts/check-bundle.mjs`。

## 2. Task 1：设计与计划文档

**Files**：新建 `docs/superpowers/specs/2026-10-31-round12-e2e-skeleton-design.md`、`docs/superpowers/plans/2026-10-31-round12-e2e-skeleton.md`。

**Step 1-1** 写入两份文档：spec 九节（背景/目标与非目标/现状审计/方案/验证矩阵/测试与基线/风险与回滚/文档改动/上线验收）；plan 为 §0 + Task 1–6 + 附录 A/B + §12。

**Step 1-2** 自检：

```bash
cd /e/JavaSpace/forum
wc -l docs/superpowers/specs/2026-10-31-round12-e2e-skeleton-design.md \
      docs/superpowers/plans/2026-10-31-round12-e2e-skeleton.md
grep -n '^## ' docs/superpowers/specs/2026-10-31-round12-e2e-skeleton-design.md
grep -n '^## ' docs/superpowers/plans/2026-10-31-round12-e2e-skeleton.md
```

预期：spec 约 150–220 行、plan 约 600–900 行；日期均为 `2026-10-31`。

**Step 1-3** 提交：

```bash
git add docs/superpowers/specs/2026-10-31-round12-e2e-skeleton-design.md \
        docs/superpowers/plans/2026-10-31-round12-e2e-skeleton.md
git commit -F - <<'EOF'
docs: 新增第十二轮前端 E2E 骨架设计与计划

记录 Playwright 引入、确定性数据引导、5 个关键路径 spec 与
CI 不加强制阶段的取舍；给出 6 个 Task 的执行步骤与验收命令。
EOF
git status --short
```

预期：提交成功，`git status --short` 为空。

## 3. Task 2：引入 Playwright 与确定性数据引导

**Files**：修改 `frontend/package.json`、`frontend/pnpm-lock.yaml`、`frontend/.gitignore`；新建 `frontend/playwright.config.js`、`frontend/e2e/support/api.js`、`frontend/e2e/support/fixtures.js`、`frontend/e2e/home.spec.js`。

### Step 3-1 安装依赖
```bash
cd /e/JavaSpace/forum/frontend
pnpm add -D @playwright/test --registry=https://registry.npmmirror.com
```

预期：devDependencies 末尾出现 `"@playwright/test": "^1.x.y"`；`pnpm-lock.yaml` 更新；记下实际版本留到 §12。

### Step 3-2 scripts 与 .gitignore
`package.json` 在 `"test:watch": "vitest"` 之后追加（前行补逗号）：

```json
    "test:e2e": "playwright test",
    "test:e2e:install": "playwright install chromium"
```

`frontend/.gitignore` 末尾追加：

```text
# Playwright
test-results/
playwright-report/
blob-report/
```

### Step 3-3 `frontend/e2e/support/api.js`
```js
const BASE_URL = (process.env.E2E_BASE_URL || 'http://127.0.0.1:5173').replace(/\/+$/, '')
const ADMIN_USERNAME = process.env.E2E_ADMIN_USERNAME || 'admin'
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD || 'admin123456'

async function request(path, { method = 'GET', token, body } = {}) {
  const res = await fetch(`${BASE_URL}/api${path}`, {
    method,
    headers: {
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let payload = null
  try { payload = text ? JSON.parse(text) : null } catch { /* 非 JSON 保留原文 */ }
  if (!res.ok || payload?.success !== true) {
    throw new Error(`[E2E] ${method} ${path} -> HTTP ${res.status}: ${payload?.message || text.slice(0, 200)}`)
  }
  return payload.data
}

const markdownContent = () =>
  ['# E2E 标题', '', '**加粗** 的正文，用于验证 Markdown 渲染。', '',
   '```js', "const e2e = 'ok'", '```', '',
   '![E2E 图片](https://picsum.photos/seed/javaforum-e2e/320/180)'].join('\n')

export async function bootstrapData() {
  const stamp = Date.now()
  const title = `E2E 冒烟 ${stamp}`
  const credentials = { username: ADMIN_USERNAME, password: ADMIN_PASSWORD }
  const auth = await request('/auth/login', { method: 'POST', body: credentials })
  if (!auth?.token) throw new Error('[E2E] 登录成功但响应里没有 token')
  const token = auth.token

  let boardId
  let boardCreated = false
  const boards = await request('/boards', { token })
  if (Array.isArray(boards) && boards.length > 0) {
    boardId = boards[0].id
  } else {
    const board = await request('/boards', {
      method: 'POST',
      token,
      body: { name: `E2E 板块 ${stamp}`, description: 'Playwright 自动创建，测试后删除', sortOrder: 999 },
    })
    boardId = board.id
    boardCreated = true
  }

  let post
  try {
    post = await request('/posts', {
      method: 'POST',
      token,
      body: { boardId, title, content: markdownContent() },
    })
  } catch (err) {
    if (boardCreated) await request(`/boards/${boardId}`, { method: 'DELETE', token }).catch(() => {})
    throw err
  }
  if (!post?.id) throw new Error('[E2E] 发帖成功但响应里没有 id')

  const cleanup = async () => {
    const failures = []
    const remove = async (path) => {
      try {
        await request(path, { method: 'DELETE', token })
      } catch (err) {
        if (!/HTTP 404/.test(err.message)) failures.push(err.message) // 已手工删除视为清理成功
      }
    }
    await remove(`/posts/${post.id}`)
    if (boardCreated) await remove(`/boards/${boardId}`)
    if (failures.length) throw new Error(`[E2E] 清理失败，请手工删除 board=${boardId} post=${post.id}:\n${failures.join('\n')}`)
  }

  return { boardId, postId: post.id, title, boardCreated, adminToken: token, credentials, cleanup }
}
```

> 正文代码围栏以数组元素拼接，避免与外层模板字符串冲突；`tags` 不传，避免在真实库留下 E2E 标签。

### Step 3-4 `frontend/e2e/support/fixtures.js`
```js
import { test as base, expect } from '@playwright/test'
import { bootstrapData } from './api.js'

const CSP_ERROR = /Content Security Policy|Refused to/i

export const test = base.extend({
  // 一个 worker 只引导一次数据，所有 spec 共享；测试结束统一清理。
  e2e: [
    async ({}, use, workerInfo) => {
      const data = await bootstrapData()
      console.log(`[E2E] worker ${workerInfo.workerIndex}: board=${data.boardId} post=${data.postId}`)
      await use(data)
      await data.cleanup()
    },
    { scope: 'worker' },
  ],

  // auto: true 让每个 spec 自动获得守卫，无需在 test 参数里声明。
  consoleGuard: [
    async ({ page }, use) => {
      const problems = []
      page.on('console', (msg) => {
        if (msg.type() === 'error' && CSP_ERROR.test(msg.text())) problems.push(`console: ${msg.text()}`)
      })
      page.on('pageerror', (error) => {
        const message = typeof error === 'string' ? error : error?.message || String(error)
        // 管理页点「取消」时 ElMessageBox.confirm 以 'cancel' 拒绝，Vue 会自行记录，这里兜底放行。
        if (message === 'cancel') return
        problems.push(`pageerror: ${message}`)
      })
      await use(problems)
      expect(problems, `浏览器控制台/页面异常:\n${problems.join('\n')}`).toEqual([])
    },
    { auto: true },
  ],
})

export { expect }
```

### Step 3-5 `frontend/e2e/home.spec.js`
```js
import { test, expect } from './support/fixtures.js'

test.describe('首页', () => {
  test('展示品牌、帖子列表与板块筛选', async ({ page, e2e }) => {
    await page.goto('/', { waitUntil: 'domcontentloaded' })
    await expect(page.locator('.header .brand')).toContainText('JavaForum')
    await expect(page.locator('.post-item').first()).toBeVisible()
    await expect(page.locator('.board-tag').first()).toContainText('全部')
    expect(e2e.boardId).toBeTruthy()
  })

  test('未登录时 header 提供登录入口', async ({ page }) => {
    await page.goto('/', { waitUntil: 'domcontentloaded' })
    await expect(page.locator('.header').getByRole('button', { name: '登录' })).toBeVisible()
  })
})
```

### Step 3-6 红灯证据（先不要建 config）
```bash
cd /e/JavaSpace/forum/frontend
pnpm exec playwright test --list
```

预期（红灯）：没有 config 时 Playwright 以 `frontend/` 为 `testDir`，会扫描 `src/**/*.spec.js`——列出大量 Vitest 用例，或在加载 Vitest spec 时抛 `Playwright Test did not expect test() to be called here` / `vitest` 收集错误。无论哪种都证明默认配置不可用，必须 `testDir: './e2e'`。

### Step 3-7 `frontend/playwright.config.js`
```js
import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.E2E_BASE_URL || 'http://127.0.0.1:5173'
const channel = process.env.E2E_CHANNEL || undefined
const isRemote = !/^https?:\/\/(127\.0\.0\.1|localhost)(:\d+)?(\/|$)/.test(baseURL)

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: isRemote ? 1 : 0,
  timeout: 30_000,
  expect: { timeout: 8_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL,
    locale: 'zh-CN',
    colorScheme: 'light',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  projects: [
    {
      name: 'chromium',
      // 默认 Chromium；E2E_CHANNEL=msedge 时改用系统 Edge，免下载浏览器
      use: { ...devices['Desktop Chrome'], channel },
    },
  ],
})
```

### Step 3-8 绿灯：只列 E2E 用例
```bash
cd /e/JavaSpace/forum/frontend
pnpm exec playwright test --list
```

预期：只列 `e2e/home.spec.js` 的 2 条，不再出现 `src/` 下的 spec。

### Step 3-9 运行 home spec
前置（两个终端）：

```bash
# 终端 1：后端（需要本地 PostgreSQL，按 README 第 1 节配置）
cd /e/JavaSpace/forum && mvn spring-boot:run
# 终端 2：前端 dev server
cd /e/JavaSpace/forum/frontend && pnpm dev
```

```bash
cd /e/JavaSpace/forum/frontend
pnpm test:e2e:install     # 下载 Chromium；或用 E2E_CHANNEL=msedge 跳过
pnpm exec playwright test e2e/home.spec.js
```

预期：`2 passed`；日志出现 `[E2E] worker 0: board=<id> post=<id>`；产物目录被 gitignore。

失败态证据：停掉后端再跑，bootstrap 报 `[E2E] POST /auth/login -> HTTP 0: fetch failed`（或 `ECONNREFUSED 127.0.0.1:8080`；经 Vite 代理时是 500/502 + HTML），能定位到「服务没起」而非笼统超时。

### Step 3-10 提交
```bash
cd /e/JavaSpace/forum
git add frontend/package.json frontend/pnpm-lock.yaml frontend/.gitignore \
        frontend/playwright.config.js frontend/e2e/support/api.js \
        frontend/e2e/support/fixtures.js frontend/e2e/home.spec.js
git commit -F - <<'EOF'
test(e2e): 引入 Playwright 与确定性数据引导

- 新增 @playwright/test devDependency 与 test:e2e/test:e2e:install 脚本
- playwright.config.js：E2E_BASE_URL / E2E_CHANNEL / 单 worker
- e2e/support：登录、建板块与帖子、清理，以及控制台 CSP 守卫
- e2e/home.spec.js：品牌、帖子列表、板块筛选、未登录登录入口
EOF
git status --short
```

预期：只含上述 7 个文件；`frontend/src` 无 diff。

## 4. Task 3：覆盖主题、帖子详情与登录登出

**Files**：新建 `frontend/e2e/theme.spec.js`、`frontend/e2e/post-detail.spec.js`、`frontend/e2e/auth.spec.js`。

### Step 4-1 `frontend/e2e/theme.spec.js`
```js
import { test, expect } from './support/fixtures.js'

const THEME_KEY = 'jf_theme'

test.describe('主题切换', () => {
  test('选择深色后加 html.dark 并持久化，刷新后保持', async ({ page }) => {
    await page.goto('/', { waitUntil: 'domcontentloaded' })
    await page.getByRole('button', { name: '切换主题' }).click()
    await page.getByRole('menuitem', { name: '深色' }).click()

    await expect(page.locator('html')).toHaveClass(/dark/)
    await expect.poll(() => page.evaluate((key) => localStorage.getItem(key), THEME_KEY)).toBe('dark')

    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('html')).toHaveClass(/dark/)
  })

  test('选择浅色后移除 dark 类', async ({ page }) => {
    await page.goto('/', { waitUntil: 'domcontentloaded' })
    await page.getByRole('button', { name: '切换主题' }).click()
    await page.getByRole('menuitem', { name: '浅色' }).click()

    await expect(page.locator('html')).not.toHaveClass(/dark/)
    await expect.poll(() => page.evaluate((key) => localStorage.getItem(key), THEME_KEY)).toBe('light')
  })
})
```

### Step 4-2 `frontend/e2e/post-detail.spec.js`
```js
import { test, expect } from './support/fixtures.js'

test.describe('帖子详情', () => {
  test('渲染标题、Markdown 与点赞按钮', async ({ page, e2e }) => {
    await page.goto(`/posts/${e2e.postId}`, { waitUntil: 'domcontentloaded' })
    await expect(page.getByRole('heading', { level: 1, name: e2e.title })).toBeVisible()

    const body = page.locator('.markdown-body')
    await expect(body.locator('h1')).toHaveText('E2E 标题')
    await expect(body.locator('strong')).toHaveText('加粗')
    await expect(body.locator('pre code').first()).toBeVisible()
    await expect(page.getByRole('button', { name: /👍 点赞/ })).toBeVisible()
  })
})
```

### Step 4-3 `frontend/e2e/auth.spec.js`
```js
import { test, expect } from './support/fixtures.js'

test.describe('登录与登出', () => {
  test('错误密码提示后端文案', async ({ page, e2e }) => {
    await page.goto('/login', { waitUntil: 'domcontentloaded' })
    await page.getByPlaceholder('用户名').fill(e2e.credentials.username)
    await page.getByPlaceholder('密码').fill(`${e2e.credentials.password}-wrong`)
    await page.locator('form .submit').click()
    await expect(page.locator('.el-message').filter({ hasText: '用户名或密码错误' })).toBeVisible()
  })

  test('正确密码登录后显示用户菜单，登出后清理本地状态', async ({ page, e2e }) => {
    await page.goto('/login', { waitUntil: 'domcontentloaded' })
    await page.getByPlaceholder('用户名').fill(e2e.credentials.username)
    await page.getByPlaceholder('密码').fill(e2e.credentials.password)
    await page.locator('form .submit').click()

    await expect(page.locator('.el-message').filter({ hasText: '登录成功' })).toBeVisible()
    await expect(page.locator('.header .username')).toBeVisible()
    // header 展示 nickname || username，公网账号可能改过昵称，不写死「管理员」
    expect((await page.locator('.header .username').innerText()).trim()).not.toBe('')

    await page.locator('.header .user-chip').click()
    await page.getByRole('menuitem', { name: '退出登录' }).click()

    await expect(page.locator('.header').getByRole('button', { name: '登录' })).toBeVisible()
    await expect.poll(() => page.evaluate(() => ({
      token: localStorage.getItem('jf_token'),
      user: localStorage.getItem('jf_user'),
    }))).toEqual({ token: null, user: null })
  })
})
```

> 错误密码用例会产生一次 401，拦截器弹文案后 `LoginView.submit` 的 rejection 被 Vue 事件处理捕获，不构成 `pageerror`；控制台守卫只匹配 CSP 文案，不受影响。

### Step 4-4 运行
```bash
cd /e/JavaSpace/forum/frontend
pnpm exec playwright test e2e/theme.spec.js e2e/post-detail.spec.js e2e/auth.spec.js
```

预期：`5 passed`（2 + 1 + 2）。首次红时按序判断：① `menuitem` 找不到 → 确认 Element Plus 仍渲染 `role="menuitem"`，必要时退 `page.locator('.el-dropdown-menu__item').filter({ hasText: '深色' })` 并在 §12 记录；② `.markdown-body h1` 找不到 → 检查 bootstrap 日志的 post id 与页面加载；③ toast 找不到 → `--headed` 观察是否被跳转带走（`LoginView` 成功后 push `/`，toast 仍在 body 上，正常可见）。

### Step 4-5 断言有效性自检（红灯）
```bash
cd /e/JavaSpace/forum/frontend
sed -i "s/name: '深色'/name: '暗色'/" e2e/theme.spec.js
pnpm exec playwright test e2e/theme.spec.js   # 预期 1 failed：找不到 menuitem
sed -i "s/name: '暗色'/name: '深色'/" e2e/theme.spec.js
pnpm exec playwright test e2e/theme.spec.js   # 预期 2 passed
```

第一次仍 passed 说明断言没真正等待目标选择器，必须修正。

### Step 4-6 提交
```bash
cd /e/JavaSpace/forum
git add frontend/e2e/theme.spec.js frontend/e2e/post-detail.spec.js frontend/e2e/auth.spec.js
git commit -F - <<'EOF'
test(e2e): 覆盖主题、帖子详情与登录登出

- theme.spec.js：深色/浅色切换、localStorage 与刷新保持
- post-detail.spec.js：标题、Markdown 渲染、点赞按钮
- auth.spec.js：错误密码文案、登录成功、登出清理本地状态
EOF
git status --short
```

## 5. Task 4：覆盖管理页与删除确认框

**Files**：新建 `frontend/e2e/admin.spec.js`。

### Step 5-1 `frontend/e2e/admin.spec.js`
```js
import { test, expect } from './support/fixtures.js'

test.describe('管理后台', () => {
  test('板块表格可见，删除确认框可取消', async ({ page, e2e }) => {
    await page.goto('/login', { waitUntil: 'domcontentloaded' })
    await page.getByPlaceholder('用户名').fill(e2e.credentials.username)
    await page.getByPlaceholder('密码').fill(e2e.credentials.password)
    await page.locator('form .submit').click()
    await expect(page.locator('.header .username')).toBeVisible()

    await page.goto('/admin', { waitUntil: 'domcontentloaded' })
    const boardTable = page.locator('.el-table').first()
    await expect(boardTable.getByText('帖子数', { exact: true })).toBeVisible()

    const rows = boardTable.locator('.el-table__row')
    expect(await rows.count()).toBeGreaterThanOrEqual(1)
    await rows.first().getByRole('button', { name: '删除' }).click()

    const box = page.locator('.el-message-box')
    await expect(box).toBeVisible()
    await expect(box).toContainText('提示')
    await expect(box).toContainText('确定删除板块')

    // 只取消，绝不点「确定」，保证不产生删除
    await box.getByRole('button', { name: '取消' }).click()
    await expect(box).toBeHidden()
    await expect(rows.first()).toBeVisible()
  })
})
```

### Step 5-2 运行
```bash
cd /e/JavaSpace/forum/frontend
pnpm exec playwright test e2e/admin.spec.js
```

预期：`1 passed`；板块数量不变（bootstrap 数据由 worker fixture 清理）。

### Step 5-3 断言有效性自检（红灯，不得触碰「确定」）
```bash
cd /e/JavaSpace/forum/frontend
sed -i "s/getByText('帖子数'/getByText('帖子数量'/" e2e/admin.spec.js
pnpm exec playwright test e2e/admin.spec.js   # 预期 1 failed：找不到「帖子数量」
sed -i "s/getByText('帖子数量'/getByText('帖子数'/" e2e/admin.spec.js
pnpm exec playwright test e2e/admin.spec.js   # 预期 1 passed
```

红灯仍 passed 说明表格断言没真正命中表头。**任何时候都不允许把「取消」替换成「确定」来验证。**

### Step 5-4 提交
```bash
cd /e/JavaSpace/forum
git add frontend/e2e/admin.spec.js
git commit -F - <<'EOF'
test(e2e): 覆盖管理页与删除确认框

- admin.spec.js：管理员进入 /admin，板块表格表头与行数
- 打开删除确认框后只点“取消”，不产生删除
EOF
git status --short
```

## 6. Task 5：记录第十二轮 E2E 骨架

**Files**：修改 `docs/DEVELOPMENT.md`、`README.md`、`docs/deployment/README.md`。

### Step 6-1 DEVELOPMENT
**(a) §2.2**：第十一轮（约 128 行）后加一行，130 行「已完成 11 轮」改 12 轮：

```markdown
| 第十二轮 | 前端 E2E 骨架：Playwright + 确定性数据 bootstrap + 5 个关键路径 spec | 6 | ✅ 已完成 | 2026-10-31 |
```

**(b) §3.1**：在「前端测试」行后加 `| 前端 E2E（frontend/e2e，不含 support/ 与 config） | 5 | wc -l frontend/e2e/*.spec.js 的合计 |`（填实际行数）；前端目录段落补 `e2e/`（5 spec + 2 support）。

**(c) §3.2**：表格加 `| 前端 E2E | **8** 个用例 / 5 个 spec | 全绿（Playwright ^1.x + Chromium；需后端与 dev server 或公网） |`；spec 分布补「E2E 单列：home 2、theme 2、post-detail 1、auth 2、admin 1」。

**(d) 新增 §4.17**（插在 §4.16 与 §5 之间，结构对齐 §4.16）：

```markdown
### 4.17 第十二轮：前端 E2E 骨架（2026-10-31）

**背景**：Vitest 330 个用例跑在 jsdom 里，覆盖不到真实浏览器中的路由守卫、传送到 body 的 Element Plus 弹层、localStorage 生命周期与实际 CSP 执行；T3 记录的真实浏览器验证仍靠 `C:\Temp\jf-pw` 的一次性脚本。

**改动**

| 文件 | 改了什么 |
| --- | --- |
| `frontend/playwright.config.js` | 新增：`testDir: ./e2e`、`baseURL` 可配、`E2E_CHANNEL`、单 worker、失败保留 trace |
| `frontend/e2e/support/api.js` | 新增：API 登录 → 找/建板块 → 建 Markdown 帖子 → 清理 |
| `frontend/e2e/support/fixtures.js` | 新增：worker 级数据 fixture + 自动控制台 CSP/pageerror 守卫 |
| `frontend/e2e/*.spec.js` | 新增 5 个 spec / 8 个 test：首页、主题、帖子详情、登录登出、管理页 |
| `frontend/package.json` / `pnpm-lock.yaml` | `@playwright/test` devDependency 与 2 个 script |
| `frontend/.gitignore` | 忽略 `test-results/`、`playwright-report/`、`blob-report/` |

**设计要点**

- 数据确定性优先：`DataInitializer` 只建 admin，E2E 用真实接口自建板块/帖子，不依赖库里已有数据；公网模式会短暂创建一条「E2E 冒烟 <时间戳>」帖子，结束删除。
- 选择器优先语义：`aria-label="切换主题"`、placeholder、`getByRole('menuitem')`；只有 Element Plus 表格/消息框用 `.el-*` 类。
- 控制台守卫自动启用：命中 `Content Security Policy|Refused to` 的 console error 或 pageerror 会失败；点「取消」产生的 `cancel` 显式放行。
- CI 不加强制阶段：raspi 无 Chromium、盘余量小、公网跑会写真实数据；E2E 定位为本地/发布前手工回归，Jenkins 集成记 T20。
- 与 Vitest 三重隔离：目录（`e2e/` vs `src/`）、`testDir`、`include`。

**基线**：后端 177/21 不变；Vitest 330/33 不变；E2E 5 spec / 8 test 单列；`pnpm build` + `pnpm check:bundle` 不变。
```

**(e) §5.1**：T3 整行替换为 `| T3 | ~~**无前端 E2E 测试**~~ | 已关闭 | 2026-10-31 第十二轮：Playwright 骨架入库（5 spec / 8 test），覆盖首页、主题、帖子详情、登录登出、管理页；Jenkins 强制阶段仍不做，见 T20（§4.17） |`；T19 后新增：

```markdown
| T20 | **Jenkins 无可选 E2E 阶段** | 低 | E2E 目前只在本地/发布前手工跑：raspi 无 Chromium、`/` 盘余量小、公网跑会写入真实数据。若要进流水线，需要缓存 `~/.cache/ms-playwright`、注入 `E2E_ADMIN_*`、并接受运行时长（见 §4.17） |
```

**(f) §5.2**：第十一轮说明后追加：

```markdown
> 第十二轮不加业务功能，只补前端 E2E 骨架（见 §4.17）；新增候选：①Jenkins 可选 E2E 阶段与浏览器缓存（记 T20）；②把 E2E 扩展到发帖/评论/搜索/SSE（需要多浏览器上下文与更长的数据清理链）。
```

**(g) §8**：表头后、现有最新行前插入 5 行（哈希用 `git log --format='%h %s' -5` 取，本次提交写「本次提交」）：

```markdown
| 2026-10-31 | docs | 记录第十二轮前端 E2E 骨架（Playwright、5 spec / 8 test、T20） | 本次提交 |
| 2026-10-31 | test | 覆盖管理页板块表格与删除确认框取消 | `<hash4>` |
| 2026-10-31 | test | 覆盖主题切换、帖子详情与登录登出 | `<hash3>` |
| 2026-10-31 | test | 引入 Playwright 与确定性 E2E 数据引导 | `<hash2>` |
| 2026-10-31 | docs | 新增第十二轮前端 E2E 骨架设计与计划 | `<hash1>` |
```

### Step 6-2 README
「前端测试」小节（约 250-276 行）Vitest 列表之后、「产物体积预算」之前插入：

````markdown
### 前端 E2E 测试

```bash
cd frontend
pnpm test:e2e:install   # 首次：下载 Chromium；已有系统 Edge 可用 E2E_CHANNEL=msedge 跳过
pnpm test:e2e           # 需先启动后端与 pnpm dev

# 直接对公网（会创建并删除 1 条「E2E 冒烟」帖子；密码不写进仓库）
E2E_BASE_URL=https://treenewbee.men/forum E2E_CHANNEL=msedge \
  E2E_ADMIN_USERNAME=admin E2E_ADMIN_PASSWORD='<你的 admin 密码>' pnpm test:e2e
```

当前为 5 个 spec、8 个用例，覆盖首页、主题切换、帖子详情、登录登出、管理后台板块表格。E2E 不计入 `pnpm test` 的 330 基线；它依赖真实后端与数据，公网模式会在测试期间创建并删除 1 条帖子（空库时另建 1 个 `E2E 板块`），清理失败会显式报错并打印 id。
````

「已知限制」加：

```markdown
- **前端 E2E 需要真实后端与浏览器**：`pnpm test:e2e` 不是纯前端测试，必须先启动后端与 `pnpm dev`（或指向公网）；默认需要 Chromium，`E2E_CHANNEL=msedge` 可复用系统 Edge。它不在 Jenkins 中执行，发布前需手工跑（见 `docs/DEVELOPMENT.md` T20）。
```

### Step 6-3 部署手册
`docs/deployment/README.md` §5 的 CSP 小节后新增：

````markdown
### 公网 E2E 冒烟（第十二轮）

```bash
cd frontend
E2E_BASE_URL=https://treenewbee.men/forum \
E2E_CHANNEL=msedge \
E2E_ADMIN_USERNAME=admin \
E2E_ADMIN_PASSWORD='<你的 admin 密码>' \
pnpm test:e2e
```

期望 `8 passed`。会创建 1 条 `E2E 冒烟 <时间戳>` 帖子并在结束时删除（空库时另建 1 个 `E2E 板块`）；失败时按输出里的 `board=`/`post=` id 手工删除，并用 `GET /api/posts?keyword=E2E冒烟` 复核。`E2E_CHANNEL=msedge` 使用系统 Edge，无需 `playwright install`。
````

§7 已知限制加：

```markdown
- **E2E 会写真实数据**：公网模式每次运行创建 1 条 `E2E 冒烟 <时间戳>` 帖子（空库时另建 1 个 `E2E 板块`），正常结束即删除；异常中断会留下带时间戳的数据，按测试输出里的 id 手工清理。E2E 不在 Jenkins 流水线中，发布前手工执行。
```

### Step 6-4 验证与提交
```bash
cd /e/JavaSpace/forum
grep -n '第十二轮' docs/DEVELOPMENT.md README.md docs/deployment/README.md
grep -n 'T20\|4.17' docs/DEVELOPMENT.md
cd frontend && pnpm test     # 仍为 330 / 33
```

```bash
cd /e/JavaSpace/forum
git add docs/DEVELOPMENT.md README.md docs/deployment/README.md
git commit -F - <<'EOF'
docs: 记录第十二轮前端 E2E 骨架

- DEVELOPMENT：§2.2 轮次、§3.2 E2E 基线、§4.17、§5.1 关闭 T3 并新增 T20
- README：E2E 命令与基线、已知限制
- 部署手册：公网 E2E 冒烟与副作用说明
EOF
git status --short
```

## 7. Task 6：外网 E2E 冒烟与回填

**Files**：修改本计划（§12、附录 B）、`docs/DEVELOPMENT.md`（实测数/版本/构建号）、必要时 `docs/deployment/README.md`。

### Step 7-1 最终本地全量
```bash
cd /e/JavaSpace/forum/frontend
pnpm exec playwright test --list          # 记录实际 spec/test 数
pnpm test:e2e                             # 本地全部 passed（设计值 8）
pnpm test                                 # 330 / 33
cd /e/JavaSpace/forum && mvn -B clean test  # 177 / 21 类 / 1 skip
```

记录每条耗时与数字，填 §12.3。

### Step 7-2 公网运行
```bash
cd /e/JavaSpace/forum/frontend
E2E_BASE_URL=https://treenewbee.men/forum \
E2E_CHANNEL=msedge \
E2E_ADMIN_USERNAME=admin \
E2E_ADMIN_PASSWORD='<你的 admin 密码>' \
pnpm test:e2e
```

预期：全部 passed（8 条）；耗时记 §12。remote 已开 1 次重试；重试后仍红就停下排查，不通过删断言「修绿」。调试：

```bash
E2E_BASE_URL=https://treenewbee.men/forum E2E_CHANNEL=msedge \
  E2E_ADMIN_USERNAME=admin E2E_ADMIN_PASSWORD='<...>' \
  pnpm exec playwright test e2e/admin.spec.js --headed --trace=on
```

### Step 7-3 复核无残留
```bash
TOKEN=$(curl -fsS -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<你的 admin 密码>"}' \
  https://treenewbee.men/forum/api/auth/login | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
curl -fsS -H "Authorization: Bearer $TOKEN" \
  'https://treenewbee.men/forum/api/posts?keyword=E2E%20%E5%86%92%E7%83%9F&page=0&size=10'
# 期望 totalElements 为 0（或只剩历史手工数据）
curl -fsS 'https://treenewbee.men/forum/api/boards' | grep -o 'E2E 板块 [0-9]*' || true
```

有残留时按测试输出的 id 手工删除，并在 §12.2 记原因。

### Step 7-4 构建与预算
```bash
cd /e/JavaSpace/forum/frontend
MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build
pnpm check:bundle
```

预期：构建成功；`check:bundle` 打印 5 项体积 + 9 个选择器覆盖，退出码 0。

### Step 7-5 Jenkins
推送后触发一次构建（`RUN_TESTS=true` 可选），记录 Build 号与结果。E2E 不在 Jenkins 内，Jenkins 只需证明新增 devDependency 后 `pnpm install --frozen-lockfile`、`pnpm build`、`check:bundle` 仍绿。

```bash
git push git@github.com:ScholarOfDeepDarkFantasy/forum.git master:master
python /c/Temp/forum-ssh.py 'sudo grep -o "Finished: [A-Z]*" /var/lib/jenkins/jobs/javaforum/builds/<N>/log'
```

### Step 7-6 回填并提交
回填 §12.1（6 个哈希、Build 号、镜像 tag）、§12.2（版本/用例数/公网耗时/重试/清理/选择器兜底）、§12.3（实测数字），勾选附录 B，并给 `docs/DEVELOPMENT.md` §4.17/§3.2/§8 补第 6 条日志。

```bash
cd /e/JavaSpace/forum
git add docs/superpowers/plans/2026-10-31-round12-e2e-skeleton.md docs/DEVELOPMENT.md docs/deployment/README.md
git commit -F - <<'EOF'
docs: 回填第十二轮外网 E2E 冒烟与构建

- 记录公网 Playwright 运行结果与实测用例数
- 回填 §12 实施记录、实际版本与构建号
EOF
git status --short
```

## 附录 A：提交信息（逐字）

### A.1
```text
docs: 新增第十二轮前端 E2E 骨架设计与计划

记录 Playwright 引入、确定性数据引导、5 个关键路径 spec 与
CI 不加强制阶段的取舍；给出 6 个 Task 的执行步骤与验收命令。
```

### A.2
```text
test(e2e): 引入 Playwright 与确定性数据引导

- 新增 @playwright/test devDependency 与 test:e2e/test:e2e:install 脚本
- playwright.config.js：E2E_BASE_URL / E2E_CHANNEL / 单 worker
- e2e/support：登录、建板块与帖子、清理，以及控制台 CSP 守卫
- e2e/home.spec.js：品牌、帖子列表、板块筛选、未登录登录入口
```

### A.3
```text
test(e2e): 覆盖主题、帖子详情与登录登出

- theme.spec.js：深色/浅色切换、localStorage 与刷新保持
- post-detail.spec.js：标题、Markdown 渲染、点赞按钮
- auth.spec.js：错误密码文案、登录成功、登出清理本地状态
```

### A.4
```text
test(e2e): 覆盖管理页与删除确认框

- admin.spec.js：管理员进入 /admin，板块表格表头与行数
- 打开删除确认框后只点“取消”，不产生删除
```

### A.5
```text
docs: 记录第十二轮前端 E2E 骨架

- DEVELOPMENT：§2.2 轮次、§3.2 E2E 基线、§4.17、§5.1 关闭 T3 并新增 T20
- README：E2E 命令与基线、已知限制
- 部署手册：公网 E2E 冒烟与副作用说明
```

### A.6
```text
docs: 回填第十二轮外网 E2E 冒烟与构建

- 记录公网 Playwright 运行结果与实测用例数
- 回填 §12 实施记录、实际版本与构建号
```

## 附录 B：冒烟清单

### B.1 本地 E2E
- [x] 后端（8080）与 `pnpm dev`（5173）已启动。
- [x] `pnpm test:e2e:install` 成功，或使用 `E2E_CHANNEL=msedge`。
- [x] `pnpm exec playwright test --list` 只列 `e2e/*.spec.js`。
- [x] `pnpm test:e2e` 实际用例全部 passed（设计值 8）；无 CSP 守卫失败。
- [x] 运行后 `git status` 无 `test-results/`、`playwright-report/`。

### B.2 公网 E2E
- [x] `E2E_BASE_URL=https://treenewbee.men/forum`、`E2E_CHANNEL=msedge`（或已装 Chromium）。
- [x] 账号密码经 `E2E_ADMIN_USERNAME` / `E2E_ADMIN_PASSWORD` 传入，未写入仓库。
- [x] 实际用例全部 passed（remote 允许 1 次重试）。
- [x] 结束后无本轮 `E2E 冒烟 <时间戳>` 与 `E2E 板块 <时间戳>` 残留。

### B.3 Vitest 基线
- [x] `cd frontend && pnpm test` → 330 passed / 33 spec。
- [x] `src/**/*.spec.js` 无新增/删除。

### B.4 后端基线
- [x] `mvn -B clean test` → 177 passed / 21 类 / 1 skip。
- [x] `src/main/java` 与 `src/test/java` 无 diff。

### B.5 构建产物
- [x] `MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build` 成功。
- [x] `pnpm check:bundle` 5 项体积在预算内、9 个选择器覆盖，退出码 0。
- [x] `frontend/dist` 未被提交。

### B.6 Jenkins 构建
- [x] 推送后 Jenkins 触发成功。
- [x] `pnpm install --frozen-lockfile` 通过（lock 与 package.json 一致）。
- [x] Build Frontend（build + check:bundle）通过；记录 Build 号、镜像 tag、`Finished: SUCCESS`。

## 12. 实施记录与偏差

> 执行时回填。未发生的行标「未执行」；不预填构建号与哈希。

### 12.1 提交与构建链

| # | 提交 | 实际哈希 | 说明 |
| --- | --- | --- | --- |
| 1 | `docs: 新增第十二轮前端 E2E 骨架设计与计划` | `38519dd` | 2 文件 +1096 |
| 2 | `test(e2e): 引入 Playwright 与确定性数据引导` | `957ed4c` | 7 文件 +201/−1 |
| — | （计划外）`fix(e2e): 修正鉴权用例对 E2E 基础设施的副作用` | `ba0398f` | 清理前重登录 + 401 pageerror 窄白名单 |
| 3 | `test(e2e): 覆盖主题、帖子详情与登录登出` | `14a9e97` | 3 文件 +73 |
| 4 | `test(e2e): 覆盖管理页与删除确认框` | `e3b7057` | 1 文件 +31 |
| — | （计划外）`fix(e2e): 修正公网子路径解析与空库清理副作用` | `38913f4` | 7 文件 +74/−25（审查 C1/C2/I1–I4） |
| 5 | `docs: 记录第十二轮前端 E2E 骨架` | `c18304d` | 4 文件 +144/−42 |
| 6 | `docs: 回填第十二轮外网 E2E 冒烟与构建` | 本次提交 | 触发 Build #37 |

| 构建/运行 | 编号 | 结果 |
| --- | --- | --- |
| Jenkins Build | #36（`c18304d`） | SUCCESS；回填提交触发 #37 |
| 镜像 tag | `forum-backend:36` | `Up (healthy)`，`/api/health` 200 |
| 本地 E2E | — | 8 passed / 19.8 s |
| 公网 E2E | — | 8 passed / 50.6 s（对 Build #36 部署复跑） |

### 12.2 偏差与决定

| 日期 | 偏差或决定 | 处理 |
| --- | --- | --- |
| 2026-10-31 | 设计初稿把「前端 E2E」技术债记在 §5.2，实际在 §5.1 T3（884 行），§5.2 没有该候选行 | 以文档为准：Task 5 关闭 T3，§5.2 只加第十二轮说明 |
| 2026-10-31 | 任务描述提到「板块表格 + 分页」，实际 `AdminView.vue` 板块表格无分页，分页只在用户管理 tab（191-199 行） | admin.spec 只断言表头与行数 |
| 2026-10-31 | 登录后 header 显示 `nickname \|\| username`（admin 默认昵称「管理员」） | 断言 `.username` 非空，不写死昵称 |
| 2026-10-31 | 任务描述称 `check:bundle` 为「60 组」，实际是 5 项体积 + 9 个 CSS 选择器 | 文档按实际脚本描述 |
| 2026-10-31 | Playwright 实际版本 | `@playwright/test ^1.64.1`（lock 已提交）；`failOnFlakyTests` 需要 1.64+ |
| 2026-10-31 | 实际 spec / test 数量 | 5 spec / 8 test（`--list` 8 tests in 5 files） |
| 2026-10-31 | 公网耗时与重试次数 | 48.0 s；`retries=1` 但未触发（一次全绿） |
| 2026-10-31 | 公网清理结果 | 帖子软删除（行在、用户不可见）；空库板块 board=1 按设计保留；`keyword=E2E 冒烟` 为 0 |
| 2026-10-31 | 选择器兜底 | 管理页用 `.el-table`、确认框用 `.el-message-box`；dropdown `menuitem` 直接可用，无其它兜底 |

### 12.3 实测数据

| 项 | 设计值 | 实测 |
| --- | --- | --- |
| Playwright 版本 | `^1.x` | 1.64.1 |
| E2E spec / test | 5 / 8 | 5 / 8 |
| 本地 E2E 耗时 | — | 8 passed / 19.8 s（`E2E_CHANNEL=msedge`，maxWorkers=1） |
| 公网 E2E 耗时 | — | 8 passed / 48.0 s（对 Build #35 部署，retries 未触发） |
| Vitest | 330 / 33 | 330 / 33 全绿 |
| 后端 | 177 / 21 类 / 1 skip | 177 / 21 类 / 1 skip（无 Java 改动） |
| 构建 5 项体积 | 在预算内 | 84.43/150、39.02/70、233.74/300、835.87/950、181.57/260 kB，exit 0 |
| 9 个 CSS 选择器 | 全覆盖 | 9/9 覆盖 |
