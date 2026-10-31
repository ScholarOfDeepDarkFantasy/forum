# 第十二轮：前端 E2E 骨架设计

- 日期：2026-10-31
- 仓库：`E:\JavaSpace\forum`（Git Bash：`/e/JavaSpace/forum`）
- 上游：第十一轮《安全响应头收敛与 CSP》（`docs/superpowers/specs/2026-10-31-round11-security-headers-csp-design.md`）
- 状态：待实现

## 1. 背景

前端现有 330 个 Vitest 用例（33 个 spec），全部跑在 jsdom 里。它们能覆盖组件逻辑、接口封装和工具函数，但覆盖不到真实浏览器里的关键路径：路由守卫在真实导航下是否生效；`ElMessage`/`ElMessageBox`/`ElDropdown` 这些**传送到 body 的弹层**是否可见可点；`localStorage` 的 `jf_token`/`jf_user`/`jf_theme` 在刷新、登录、登出后的真实状态；以及浏览器实际执行 CSP 后控制台有没有 `Content Security Policy` / `Refused to` 报错。

`docs/DEVELOPMENT.md` §5.1 的 T3（第 884 行）明确记录：「真实浏览器验证目前靠一次性 Playwright 脚本（在 `C:\Temp\jf-pw`，未入库）」。第十一轮的 `frontend/src/plugins/csp.spec.js` 只在源码层断言 CSP 文本，不能证明浏览器接受它。本轮把一次性脚本固化成仓库内的 E2E 骨架。

难点在**数据**：`config/DataInitializer.java` 只幂等创建 `admin`（密码 `admin123456`、角色 ADMIN+MODERATOR+USER），**不创建任何板块或帖子**。而首页、帖子详情、管理页都以「至少有一个板块/帖子」为前提。如果 E2E 依赖真实库里碰巧存在的数据，换一台机器、换一个库就会红，因此本轮把「确定性数据引导」当成与用例同等重要的设计对象。

## 2. 目标与非目标

### 目标

- 把 `@playwright/test` 引入 `frontend` 的 devDependencies，不改任何业务代码（`frontend/src/**` 零改动）。
- 新增 `frontend/playwright.config.js` 与 `frontend/e2e/`：5 个 spec、6–9 个 test（目标 8 个），覆盖首页、主题切换、帖子详情、登录登出、管理页。
- 新增 `e2e/support/api.js`：用 Node 侧 `fetch` 走真实后端契约，登录 → 找/建板块 → 建带 Markdown 的帖子 → 测试后清理。所有 spec 共用同一份数据，不依赖仓库里已有内容。
- 新增 `e2e/support/fixtures.js`：worker 级 bootstrap fixture + 自动启用的控制台守卫（CSP/`Refused to`/`pageerror`）。
- 新增脚本 `test:e2e`、`test:e2e:install`；Playwright 产物加入 `frontend/.gitignore`。
- 支持两种目标：本地 `pnpm dev` + 本机后端（默认 `http://127.0.0.1:5173`），以及直接对公网 `https://treenewbee.men/forum`（`E2E_BASE_URL`）。
- 支持 `E2E_CHANNEL=msedge` 用系统 Edge，避免在无外网/受限环境下载 Chromium；文档给出可执行命令与失败调试方式。

### 非目标

- **不做视觉回归/截图比对**：不引入 `toHaveScreenshot` 基线图，避免字体、DPR、操作系统差异造成长期噪音。
- **不做跨浏览器矩阵**：只跑 Chromium；`msedge` 只是「不下载浏览器」的替代通道，不是第二个兼容性维度。Firefox/WebKit 不安装。
- **不往 Jenkinsfile 加强制 E2E 阶段**：raspi 没有浏览器依赖，下载与运行时长不可控；本轮只做本地/发布前手工跑（理由见 §4.7）。
- **不覆盖 SSE 实时推送**：双客户端在线推送需要两个浏览器上下文与计时，稳定性成本高于骨架目标，留给后续轮次。
- **不测上传头像**：需要真实图片文件与 multipart 流程，Vitest 已覆盖客户端校验，E2E 收益低。
- 不覆盖发帖/评论/搜索表单的完整 CRUD，不测分页翻页，不做并发/多 worker 压测。
- 不改变后端接口、数据库结构或 `DataInitializer`；不为 E2E 增加测试专用 API，也不把 E2E 计入 `pnpm test` 的 330 基线。

## 3. 现状审计

### 3.1 前端可测性锚点

| 位置 | 可依赖的锚点 | 行号（约） |
| --- | --- | --- |
| `frontend/src/App.vue` | `.header .brand` 内文字 `JavaForum`；未登录时 header 的 text 按钮「登录」；登录后 `.user-chip`、`.username`（显示 `nickname \|\| username`）；用户菜单项「退出登录」「管理后台」 | 52-55、63-88 |
| `frontend/src/views/LoginView.vue` | 输入框 placeholder「用户名」「密码」；提交按钮 `class="submit"`、文案「登录」；成功 `ElMessage.success('登录成功')` | 42-50、29 |
| `frontend/src/views/HomeView.vue` | 板块栏 `.board-tag`（首个是「全部」）；列表 `.post-item`；空态「暂无帖子」；搜索 placeholder「搜索帖子标题/内容…」 | 95-156 |
| `frontend/src/views/PostDetailView.vue` | 标题 `h1.post-title`；正文 `.post-content.markdown-body`；点赞按钮文案「👍 点赞 N」 | 214、236、252-254 |
| `frontend/src/views/AdminView.vue` | 板块表格表头「ID/名称/描述/排序/帖子数/操作」；行内「删除」；确认框标题「提示」、按钮「取消/确定」 | 129-141、58-69 |
| `frontend/src/components/ThemeToggle.vue` | 按钮 `aria-label="切换主题"`；菜单项「跟随系统/浅色/深色」 | 8-11、29 |
| `frontend/src/composables/useTheme.js` | `localStorage` 键 `jf_theme`；`html.dark` 类 | 全文 |
| `frontend/src/stores/auth.js` | `localStorage` 键 `jf_token` / `jf_user`；`clearAuth` 同时移除两键 | 9-10、62-67 |

浏览器侧选择器优先用 `getByRole` / `getByPlaceholder` / `aria-label`，只有 Element Plus 没有稳定 role 的表格、消息框才退回 `.el-*` 类名（见 §4.5）。

### 3.2 后端契约与数据前提

- 登录：`POST /api/auth/login`，body `{username, password}`，成功 `data` 为 `AuthResponse`（`token`、`nickname`、`roles` 等）。失败由 `AuthService` 抛 `BadCredentialsException`，`GlobalExceptionHandler` 转 401 + `Result.fail("用户名或密码错误")`；前端拦截器对 `/auth/login` 的 401 **原样展示后端文案、不清登录态**（`frontend/src/api/index.js` 34、70-73）。
- 登出：`POST /api/auth/logout`，返回「已退出登录」；前端 `auth.logout()` 无论成功失败都会 `clearAuth()`。
- 板块：`GET /api/boards` 公开；`POST /api/boards` 要求 `hasRole('ADMIN')`，body 为 `BoardRequest{name(≤50), description(≤255), sortOrder}`。
- 帖子：`POST /api/posts` 需要登录，body 为 `PostRequest{boardId, title(≤200), content, tags(≤5)}`，成功 201；`DELETE /api/posts/{id}` 允许作者或 ADMIN/MODERATOR。
- 响应统一包成 `{success, message, data}`，`data` 为分页时是 `PageResponse{content, totalElements, totalPages, ...}`。
- `DataInitializer` 只建 admin；因此 bootstrap 必须自己保证「至少一个板块 + 一条帖子」。

登录限流默认「5 次失败 / 15 分钟窗口 / 锁定 15 分钟」，但**成功登录会清空该 IP 的失败窗口**（`LoginRateLimiter`）。错误密码用例每次只制造 1 次失败，bootstrap 与正确登录用例会各成功一次，正常重复运行不会触发 429。

### 3.3 构建、脚本与 CI

- `frontend/package.json`：scripts 在 6-13 行（`dev`/`build`/`check:bundle`/`preview`/`test`/`test:watch`），devDependencies 在 25-32 行，当前没有 `@playwright/test`。
- `frontend/vite.config.js`：dev server 端口 5173（45 行），`/api` 代理到 `http://localhost:8080`（47-51 行），Vitest `include: ['src/**/*.spec.js']`（53-57 行）。
- `frontend/.gitignore` **已存在**，当前忽略 `node_modules`、`dist`、`*.local` 等；需追加 `test-results/`、`playwright-report/`、`blob-report/`。
- `Jenkinsfile` 的 Tests stage（71-79 行）只在 `RUN_TESTS=true` 时跑 `mvn -B test` 与 `pnpm test`；Build Frontend（92-97 行）跑 `pnpm install --frozen-lockfile`、`pnpm build`、`pnpm check:bundle`。`check:bundle` 固定检查 5 项体积预算 + 9 个关键 CSS 选择器，不是「60 组」；E2E 不改产物，因此不新增预算。

## 4. 方案

### 4.1 框架、目录、脚本与产物

新增依赖（devDependency，版本以实际安装为准，文档记 `^1.x`）：

```bash
cd /e/JavaSpace/forum/frontend
pnpm add -D @playwright/test --registry=https://registry.npmmirror.com
```

目录为 `frontend/playwright.config.js`（新增）、`frontend/e2e/support/{api.js,fixtures.js}`（新增）、`frontend/e2e/{home,theme,post-detail,auth,admin}.spec.js`（新增）；`package.json` 修改 scripts 与 devDependency，`pnpm-lock.yaml` 随安装更新。

scripts 追加在 `test:watch` 之后（保持现有顺序）：`"test:e2e": "playwright test"`、`"test:e2e:install": "playwright install chromium"`。Vitest 的 `include: ['src/**/*.spec.js']` 保持不变；Playwright 的 `testDir` 是 `./e2e`，两边互不拾取对方的 spec。

`frontend/.gitignore` 追加 `test-results/`、`playwright-report/`、`blob-report/`。`playwright-report/` 是 HTML 报告，`test-results/` 是 trace/screenshot，均不应入库；E2E 不产出 `dist`，不影响 `check:bundle`。

### 4.2 地址、浏览器通道与运行参数

- `baseURL = process.env.E2E_BASE_URL || 'http://127.0.0.1:5173'`。
- 默认项目 `chromium`；`use.channel = process.env.E2E_CHANNEL || undefined`，因此 `E2E_CHANNEL=msedge` 时用系统 Edge，无需 `playwright install`。
- `workers: 1`、`fullyParallel: false`：所有 spec 共用一份 bootstrap 数据，串行最简单也最可诊断。
- `use.colorScheme: 'light'`：让「跟随系统」的初始态确定，主题用例不随宿主系统漂移。
- 本机运行 `retries: 0`；`E2E_BASE_URL` 指向非 localhost 时 `retries: 1`，吸收公网抖动。
- `trace: 'retain-on-failure'`、`screenshot: 'only-on-failure'`；需要更细的调试时命令行加 `--trace=on` / `--headed`。
- `page.goto(..., { waitUntil: 'domcontentloaded' })`：Markdown 正文里有一张外链图片，不等 `load` 可避免图片慢导致超时；图片加载失败也不会命中本轮的 CSP 守卫。

### 4.3 确定性数据 bootstrap

`e2e/support/api.js` 用 Node 全局 `fetch`（Node 18+；Jenkins 侧 Node 24）直接打后端，不经过页面：

1. `POST /api/auth/login`，账号取 `E2E_ADMIN_USERNAME` / `E2E_ADMIN_PASSWORD`，本地默认 `admin` / `admin123456`；**对公网必须显式传环境变量**，不在仓库写死任何真实密码。
2. `GET /api/boards`，取第一个板块；为空则 `POST /api/boards` 建 `E2E 板块 <Date.now()>`（`sortOrder: 999`），并记下 `boardCreated = true`。
3. `POST /api/posts` 建 `E2E 冒烟 <Date.now()>`，正文固定含：`# E2E 标题`、`**加粗**`、一个 ```` ```js ```` 代码块、一张外链图片，用于验证 Markdown 渲染。
4. 返回 `{ boardId, postId, title, adminToken, credentials, cleanup }`。
5. 清理：先 `DELETE /api/posts/<postId>`（404 视为已删），仅当板块由本套件创建时再 `DELETE /api/boards/<boardId>`；板块下如果还有别的帖子，后端会拒绝删除，此时把错误汇总抛出，让人看到残留物而不是静默吞掉。

bootstrap 内部若在「建板块成功、建帖子失败」处中断，要回滚刚创建的板块再抛出；清理失败让本次运行显式红，不掩盖副作用。所有 spec 通过同一个 **worker 级** `e2e` fixture 拿数据：一个 worker 只 bootstrap 一次，测试结束清理一次。

### 4.4 共享 fixture 与控制台守卫

`e2e/support/fixtures.js` 导出扩展后的 `test` / `expect`：

- `e2e`（worker scope）：调用 `bootstrapData()`，`use()` 之后执行 `cleanup()`。
- `consoleGuard`（`auto: true`）：监听 `page.on('console')`，只收集 `msg.type() === 'error'` 且匹配 `/Content Security Policy|Refused to/i` 的文本；监听 `page.on('pageerror')`，放行值/消息为 `cancel` 的拒绝（管理页点「取消」时 `ElMessageBox.confirm` 会 reject，Vue 自己会打日志，这里只做兜底）。`use()` 之后若非空则 `expect(problems).toEqual([])`。
- 每个 spec 都必须 `import { test, expect } from './support/fixtures.js'`，这样守卫自动生效；不要直接 import `@playwright/test` 的 `test`。

### 4.5 用例矩阵

| spec | test | 关键断言 |
| --- | --- | --- |
| `home.spec.js` | 首页展示品牌、帖子与板块筛选 | `.header .brand` 含 `JavaForum`；`.post-item` 至少 1 条；`.board-tag` 首个含「全部」 |
| `home.spec.js` | 未登录 header 有登录入口 | `.header` 内 role=button、name=「登录」可见 |
| `theme.spec.js` | 选「深色」 | `html.dark` 出现；`jf_theme === 'dark'`；reload 后仍为深色 |
| `theme.spec.js` | 选「浅色」 | `html.dark` 消失；`jf_theme === 'light'` |
| `post-detail.spec.js` | bootstrap 帖子详情 | `h1` 标题为帖子标题；`.markdown-body` 内 `h1`=「E2E 标题」、`strong`=「加粗」、`pre code` 可见；「点赞」按钮可见 |
| `auth.spec.js` | 错误密码 | `.el-message` 含「用户名或密码错误」 |
| `auth.spec.js` | 正确密码 + 登出 | toast「登录成功」；`.header .username` 可见；点「退出登录」后 header 出现「登录」，`jf_token`/`jf_user` 均为 `null` |
| `admin.spec.js` | 管理员看板块表格并取消删除 | 表头含「帖子数」；板块行数 ≥1；点行内「删除」出现标题「提示」的确认框，点「取消」后关闭，**不点确定** |

选择器约定：`aria-label="切换主题"`、`getByPlaceholder('用户名'/'密码')`、`getByRole('menuitem', { name: '深色' })`（Element Plus 菜单项 role 实测为 `menuitem`）、`form .submit`（登录页 header 也有「登录」按钮，避免 role 撞名）、`.el-table` / `.el-message-box` / `.el-message`（Element Plus 未提供稳定语义角色）。toast 与弹层用 `expect(...).toBeVisible()` 自带的自动等待；`localStorage` 用 `expect.poll`；不写死 `waitForTimeout`。

### 4.6 CI 决策

**本轮不往 `Jenkinsfile` 加 E2E 阶段**：raspi 上没有 Chromium 依赖，`playwright install chromium` 需要下载并落盘（`/` 根盘只剩约 5G）；E2E 依赖「后端 + 前端 dev server 或公网入口 + 管理员密码」，Jenkins 当前没有这些运行参数，`RUN_TESTS=true` 的现有语义是「后端 H2 + Vitest」，不应被扩大；公网 E2E 会创建并删除一条真实帖子，不适合每次 push 自动执行。

因此：**本地开发后自跑 + 发布前对公网手工跑**。把「Jenkins 可选 E2E 阶段（浏览器缓存、密码注入）」记入技术债 **T20**；`README.md` 与 `docs/deployment/README.md` 写清命令。

### 4.7 提交序列

6 次提交，逐字信息见实现计划附录 A：设计计划 → 引入 Playwright 与 bootstrap/home → 主题/详情/登录 → 管理页 → 文档记录 → 回填外网冒烟与构建。

## 5. 验证矩阵

| # | 验证项 | 命令 | 期望 |
| --- | --- | --- | --- |
| V1 | Playwright 只拾取 e2e | `pnpm --dir frontend exec playwright test --list` | 只列 `e2e/*.spec.js`，共 8 条 |
| V2 | 本地 E2E | `cd frontend && pnpm test:e2e` | 8 passed；无 CSP 守卫失败 |
| V3 | 公网 E2E | `E2E_BASE_URL=https://treenewbee.men/forum E2E_CHANNEL=msedge ... pnpm test:e2e` | 8 passed；结束后无 `E2E 冒烟 *` 残留 |
| V4 | Vitest 基线 | `cd frontend && pnpm test` | 330 passed / 33 spec |
| V5 | 后端基线 | `mvn -B clean test` | 177 passed / 21 类 / 1 skip |
| V6 | 构建 | `MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build` | 成功 |
| V7 | 体积预算 | `pnpm check:bundle` | 5 项体积在预算内 + 9 个选择器覆盖，退出码 0 |
| V8 | 产物未入库 | `git status --short` | 无 `test-results/`、`playwright-report/` |

## 6. 测试与基线

| 层 | 数量 | 运行前提 | 是否计入现有基线 |
| --- | --- | --- | --- |
| 后端 JUnit | 177 / 21 类 | 无（H2） | 是 |
| 前端 Vitest | 330 / 33 spec | 无（jsdom） | 是 |
| 前端 Playwright | 5 spec / 8 test | 后端 + `pnpm dev`（或公网） | **否**，单列 |

E2E 的运行成本与不确定性明显高于前两层，因此不并入 `pnpm test`，也不作为 `pnpm build` 的前置。

## 7. 风险与回滚

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 依赖真实后端与数据 | 后端没起、库是空的就红 | API bootstrap 自建板块/帖子；失败信息带 HTTP 状态与响应体 |
| 公网模式产生真实写操作 | 每次跑创建 1 板块（仅空库时）+ 1 帖，结束删除 | 标题/板块名带时间戳；清理失败显式抛出并打印残留 id；文档写明副作用 |
| 浏览器安装成本 | `playwright install chromium` 需下载 | `E2E_CHANNEL=msedge` 用系统 Edge；文档给出两条路径 |
| 选择器脆弱 | 改 class/文案就红 | 优先 role/placeholder/aria-label；Element Plus 专用类只在无 role 时使用 |
| 时间敏感断言 | toast 消失、弹层动画导致偶发红 | 用 Playwright 自动等待 + `expect.poll`；禁 `waitForTimeout` |
| 登录限流 | 反复跑错密码用例触发 429 | 每次运行只有 1 次失败，bootstrap/正确登录成功会清窗口；文档给 429 处理 |
| 外链图片不可达 | 不影响断言 | `domcontentloaded` 不等图片；守卫只匹配 CSP 文案 |
| 与 Vitest 互相拾取 | `pnpm test` 变慢或报错 | 目录 + `testDir` + `include` 三重隔离 |

回滚：删除 `frontend/e2e/`、`frontend/playwright.config.js`，撤销 `package.json` 的两个 script 与 devDependency，删 `pnpm-lock.yaml` 中对应条目，移除 `.gitignore` 三行，再跑 `pnpm install --frozen-lockfile` 与 `pnpm test`（330）确认回到原状。E2E 不改业务代码，无数据迁移，回滚无残留逻辑。

## 8. 文档改动

| 文件 | 改动 |
| --- | --- |
| `docs/DEVELOPMENT.md` | §2.2 加第十二轮；§3.1/§3.2 加 E2E 规模与基线；新增 §4.17；§5.1 关闭 T3、新增 T20；§5.2 加一段说明；§8 加 5 条变更日志 |
| `README.md` | 「前端测试」增加 E2E 命令、环境变量、基线；「已知限制」加公网写操作与浏览器依赖 |
| `docs/deployment/README.md` | §5 增加公网 E2E 冒烟小节；§7 增加已知限制条目 |
| 本文档与实现计划 | 新增，不提交业务代码 |

## 9. 上线验收

1. V1–V8 全部通过；本地与公网各留下一次完整输出（8 passed）。
2. `git status` 干净，只有预期的 6 次提交；`frontend/src/**` 无 diff。
3. 公网跑完后用 `GET /api/posts?keyword=E2E 冒烟` 与 `GET /api/boards` 确认无残留。
4. 文档中的版本号、用例数、构建号在 §12 回填，不写未验证的精确值；验收记录写进实现计划 §12 与附录 B，并在父会话汇报差异项。
