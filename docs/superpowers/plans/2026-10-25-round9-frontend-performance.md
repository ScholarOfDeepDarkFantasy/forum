# 第九轮：前端性能优化实现计划（Element Plus 按需引入、产物预算与传输层缓存）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 每个 Task 由子代理执行，一次只勾一个 `- [ ]`，一个 Task 跑完全部 Step 并提交后才能开始下一个 Task。**不要读 spec 也能执行**——本计划把每个决策都内联在 Task 里；需要背景时再看 `docs/superpowers/specs/2026-10-25-round9-frontend-performance-design.md`。

**Goal:** 把 `frontend` 的入口 chunk 从 878.12 kB 压到 **150 kB 预算内（实测 84.43 kB）**、入口 CSS 从 369.97 kB 压到 **70 kB 预算内（实测 39.02 kB）**、让 Vite 不再输出 `chunks larger than 500 kB` 警告，并用一个零依赖的体积预算脚本（入口 JS/CSS、单 chunk、CSS 总量、JS 总量 + 9 个关键 CSS 选择器覆盖）+ Jenkins 步骤把体积锁住；同时给静态资源加 `immutable` 长缓存、给入口页加 `no-cache`，最后顺手收掉技术债 T17（标签搜索 LIKE 未转义）。**不改任何用户可见功能与交互。**

**Architecture:** 五个 Task，每个 Task「先写红测试 → 最小实现 → 全量跑绿 → 一次提交」。构建链改动集中在 `frontend/vite.config.js`：必须改成**工厂函数**，`mode === 'test'` 时不挂 `unplugin-vue-components`（否则 vitest 里 `el-*` 不再命中既有 `global.stubs`，实测 63 例 / 7 个 spec 失败），只有构建期才挂；`frontend/src/main.js` 删全量 `ElementPlus` 与 `dist/index.css`，保留 `ElLoading` 指令与 4 条编程式 API 样式。本地化从 `app.use()` 迁到 `App.vue` 的 `<el-config-provider>`；CI 改动是 `Jenkinsfile` 里加一行 `pnpm check:bundle`；传输层改动只有 `deploy/nginx/forum.conf` 的一个 location 改写 + 一个新增 location；后端改动只有 `TagRepository` 的 `ESCAPE '!'` 与 `TagService` 的 `escapeLike`。无数据库迁移、无新端点、无配置项变化，因此每个 Task 都能单独 revert。

**Tech Stack:** Vue 3.5.42 / Vue Router 4.6.4 / Pinia 4.1.0 / Element Plus 2.14.7 / Vite 8.3.0（rolldown 版）+ `@vitejs/plugin-vue` 6.0.8 / Vitest 5.0.4（jsdom 30）/ `unplugin-vue-components` 32.x（**本计划唯一新增依赖，devDependency**）；后端 Java 17 / Spring Boot 3.5.6 / Spring Data JPA / H2（测试）/ PostgreSQL 14+（运行）；nginx 由 raspi 系统包提供。

## 0. 执行约定

### 0.1 分支与提交

- 仓库：`E:\JavaSpace\forum`，Git Bash 路径 `/e/JavaSpace/forum`。**直接在 `master` 上提交**，不开分支、不提 PR、不 amend、不 rebase、不 squash、不 force push。
- 基线提交 `db16214`（`docs: 回填第八轮提交哈希与上线验收`）。开工前 `git status --short` 必须为空。
- **本轮共 7 次提交**，顺序与信息如下（附录 A 是汇总）。第 0 次是 **Lead 在开工前单独提交的 spec + 计划**（两份文档先入库，执行者不要重复提交它们），之后才是执行者的 6 次：

  | # | 提交信息 | 归属 |
  | --- | --- | --- |
  | 0 | `docs: 新增第九轮前端性能优化设计与计划`（信息由 Lead 定） | Lead（开工前，spec + 本计划） |
  | 1 | `perf: 按需引入 Element Plus 并配置本地化提供者` | Task 1 |
  | 2 | `perf: 增加产物预算检查并接入构建流程` | Task 2 |
  | 3 | `perf: 静态资源长缓存与入口页禁用缓存` | Task 3 |
  | 4 | `fix: 标签搜索转义 LIKE 通配符` | Task 4 |
  | 5 | `docs: 记录第九轮前端性能优化（按需引入、产物预算与传输层）` | Task 5 |
  | 6 | `docs: 回填第九轮构建与冒烟验收` | Task 5（上线后） |

- 只 `git add` 本 Task **Files:** 小节列出的路径（用显式路径，不要 `git add -A`）。提交前 `git status --short` 必须只显示本 Task 的文件。
- **spec 与计划在第 0 次提交入库**（Lead 提交），Task 1 不再包含文档；Task 5 的 4 个任务文件是 `README.md`、`docs/DEVELOPMENT.md`、`docs/deployment/README.md` 与本计划（勾选 + §12 回填）。理由见 §0.5 裁决点 1。
- 计划里的 checkbox 在 Task 内执行完即勾；最后一轮勾选（Task 5 收尾）**并入第 6 次提交**，不额外加一次提交。

### 0.2 TDD 顺序（每个 Task 都按这个来）

1. **RED**：先写/改测试，运行本 Task 指定的测试命令，确认失败信息与本计划写的「预期」一致（不是随便红，要红在断言上）。
2. **GREEN**：写最小实现，再跑同一命令，直到全绿；不顺手重构无关代码。
3. **REGRESSION**：跑全量（后端 `mvn -B clean test`，前端 `pnpm test`），确认总数与本 Task 的累计数一致，再提交。

标注为「特征化用例（characterization）」的 Step 例外：当前实现即通过，它的价值是防回归；若它红了，说明仓库现状与本计划不符，**停下来核对并报告**，不要改测试去迁就实现。

Task 2（预算脚本）与 Task 3（nginx）没有单元测试：它们的 RED/GREEN 由「对历史产物跑脚本」和「`curl -sI` 看响应头」自证，Step 里给了逐字命令与预期输出。

### 0.3 命令环境

- shell：Git Bash。后端命令在 `/e/JavaSpace/forum` 执行；前端命令必须 `cd frontend`（`pnpm` 脚本只在 `frontend/` 下有 `package.json`）。
- 构建必须带 `MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH`，否则 Git Bash 会把 `/forum/` 当成路径转换成 `E:/forum/`：

  ```bash
  cd /e/JavaSpace/forum/frontend && MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build
  ```

- 安装依赖必须带 npmmirror registry（本机直连 npm 官方源不通）：

  ```bash
  cd /e/JavaSpace/forum/frontend && pnpm add -D unplugin-vue-components@^32 --registry=https://registry.npmmirror.com
  ```

- 本机版本：node v25.2.1 / pnpm 11.22.0；Jenkins 用 node v24.16.0（`/home/pi/.nvm/versions/node/v24.16.0/bin/pnpm`）。`unplugin-vue-components@32` 的 `engines.node` 是 `>=20.19.0`，两端都满足。
- 后端测试用 H2 内存库（`@ActiveProfiles("test")` + `MODE=PostgreSQL`），**不需要**本地 PostgreSQL，也**不需要**任何环境变量。
- 前端测试是 `vitest run`（非 watch）：`cd frontend && pnpm test`；只跑单文件用 `cd frontend && pnpm vitest run src/plugins/elementAutoImport.spec.js`。
- 提交前看差异：`git diff --stat` 与 `git diff`（不要用 GUI）。
- 推送（Task 5）：`git push git@github.com:ScholarOfDeepDarkFantasy/forum.git master:master`（GitHub HTTPS 直连不可用，必须 SSH）。

### 0.4 测试与体积基线（执行前）

| 项 | 当前值（`db16214`） | 本轮目标值 |
| --- | --- | --- |
| 后端用例 | 176（1 skipped） | **177**（1 skipped） |
| 后端测试类 | 21 | 21（不变） |
| 前端用例 | 321 | **323** |
| 前端 spec 文件 | 31 | **32** |
| HTTP 端点 | 39 | 39（不变） |
| 数据库迁移 | V4 | V4（不变） |
| 非 devDependency 数量 | 9 | 9（不变） |
| devDependency 数量 | 5 | **6**（+`unplugin-vue-components`） |
| 入口 JS `index-*.js` | 878,120 B（878.12 kB） | ≤ 150,000 B（实测 84,431 B） |
| 入口 CSS `index-*.css` | 369,967 B（369.97 kB） | ≤ 70,000 B（实测 39,019 B） |
| 最大 JS chunk | 878,120 B | ≤ 300,000 B（实测 233,744 B，Vite 不再告警） |
| JS 总量（19 chunk） | 1,344,673 B（1344.67 kB） | ≤ 950,000 B（实测 835,623 B） |
| CSS 总量（13 文件） | 381,751 B（381.75 kB） | ≤ 260,000 B（实测 181,570 B） |
| gzip 入口 | 279.66 kB | 31.68 kB（实测） |
| CSS 覆盖 | 未检查 | 9 个关键选择器全部存在（`.el-button` 等，见 Task 2） |

> 体积一律按 `bytes / 1000` 换算（Vite 8 的口径：878,120 B 显示为 878.12 kB）。本计划所有数字都用这个口径，不要换成 KiB。

### 0.5 本计划的裁决点（执行时不再临时决定）

1. **文档先作为第 0 次提交单独入库**：`spec` + 本计划在开工前由 Lead 作为一次独立的 docs 提交（`docs: 新增第九轮前端性能优化设计与计划`）入库，不计入 5 个 Task。理由：前 4 个 Task 是「构建链 + CI + 传输层 + 一条 SQL」，每个都能独立 revert；文档描述的是整轮结果（含上线回填），先入库才能在执行中随时对照。Task 5 只改 `README.md`/`docs/DEVELOPMENT.md`/部署手册并把实测数字回填进本计划，不再新增 spec/plan。
2. **依赖形态**：`unplugin-vue-components` 只进 `devDependencies`（`^32`，实测最新 32.1.0），`pnpm-lock.yaml` 与 `package.json` **同一提交**。Jenkins 用 `--frozen-lockfile`，lockfile 不同步会直接红在 install。
3. **不引入 `unplugin-auto-import`**：本轮只要组件与样式按需；自动导入 `ref`/`ElMessage` 会改变所有 `.vue` 的写法，且编程式 API 的样式仍要手引，收益与风险不匹配（spec §2.2）。
4. **显式传 `dirs: []`**：关闭插件对 `src/components` 的目录扫描。仓库里自有组件全部显式 `import`（Vue 编译器优先用 setup 绑定），扫描不改变解析结果，但会把 `src/components` 下所有 SFC 都注册一遍，白白增加解析开销与不确定性。`dts: false`：仓库没有 TypeScript，不生成 `components.d.ts`。
5. **`importStyle: 'css'`**：复用 `theme-chalk` 的 css 产物，不引入 `sass` 编译链（spec §6）。
6. **编程式样式清单固定为 4 条**：`message`、`message-box`、`notification`、`loading` 的 `style/css`。前三条服务 `ElMessage`/`ElMessageBox`/`ElNotification`；`loading` 服务 `v-loading` 指令（`ElLoading.directive` 在 `main.js` 里全局注册）。新增编程式 API 时必须同步这里——这条写进 `main.js` 注释。
7. **本地化**：`App.vue` 根部 `<el-config-provider :locale="zhCn">`；`ElMessageBox.confirm` 的三处**必须**显式传 `confirmButtonText: '确定'` / `cancelButtonText: '取消'` / `distinguishCancelAndClose: true`。不调用 EP 内部 `provideGlobalConfig`（非公开 API）。
8. **预算为实测派生的硬上限**：入口 JS 150 kB、入口 CSS 70 kB、单 chunk 300 kB、CSS 总量 260 kB、JS 总量 950 kB（全部按 bytes/1000；基线分别是 878.12 / 369.97 / 878.12 / 381.75 / 1344.67 kB）。这些值由 Task 1 实测产物（84.43 / 39.02 / 233.74 / 181.57 / 835.62 kB）向上留余量得到，**只允许收紧、不允许放宽**——调高任何一项都等于放宽本轮验收。定稿值记在 `scripts/check-bundle.mjs` 的常量注释与 §12 实施记录里。
9. **检查放 Jenkins 的 Build Frontend 阶段**（`pnpm build` 之后），不放 Tests 阶段：预算依赖 `dist/`，Tests 阶段（`RUN_TESTS=true` 才跑）不构建前端。不放 pre-commit hook：仓库没有钩子设施，不为此引入第二个依赖。
10. **基线产物存证放仓库外**：`cp -r frontend/dist /e/tmp/forum-dist-baseline`（脚本用 `--dist E:/tmp/forum-dist-baseline` 指向它）。不进 git、不进 `.gitignore`、不留在 `frontend/` 里。
11. **nginx 写法**：assets 只写 `add_header Cache-Control "public, max-age=31536000, immutable"` 并**删掉 `expires 7d`**（两者同写会产生两条 `Cache-Control`）；入口页用 `location = /forum/index.html` + `add_header Cache-Control "no-cache"`（`index` 与 `try_files` 都内部重定向到它，一个 location 覆盖 `/forum/` 与 `/forum/xxx`）；不用 `expires -1`。
12. **缓存头断言放 `deploy.sh` 冒烟段**：部署即验证（nginx reload 之后），失败让部署当场红；部署手册 §5 同步同一组命令。
13. **T17 实现方式**：`TagService` 复制 `PostService.escapeLike` 的 11 行（带注释说明出处），**不动 `PostService`**；JPQL 加 `ESCAPE '!'`。测试加在 `TagFlowIntegrationTest` 的 `@Order(9)`（最后一个），自己造数据，不动 `@Order(2)`/`@Order(7)` 的断言。
14. **测试期关闭按需解析（关键取舍）**：`vite.config.js` 用工厂函数按 `mode` 分支——`mode === 'test'` 时 `plugins` 只有 `vue()`，构建/开发时才挂 `Components(...)`。理由：既有 31 个 spec 用 `global.stubs` 拦截 `el-*` 标签；若测试期也注入真实组件，实测 **63 个用例失败、7 个 spec 文件**变红（stub 不再命中，真实 EP 组件渲染并做 props 校验）。代价是单测不覆盖「构建期解析链」，由 `elementAutoImport.spec.js`（源码/配置断言）+ 构建后 `check:bundle`（体积 + CSS 覆盖）+ 部署冒烟共同守护，见 §9 风险 3。
15. **所有文档表格的体积数字用 bytes/1000**，并同时给字节数（`878,120 B`）与 Vite 读数（`878.12 kB`），避免歧义。

## 1. 现状与差距

| 现状（`db16214`） | 差距 | 本轮落点 |
| --- | --- | --- |
| `frontend/src/main.js:3` `import ElementPlus from 'element-plus'`、`:23` `app.use(ElementPlus, { locale: zhCn })` | 60+ 组件全量注册，入口 chunk 878.12 kB | Task 1 |
| `frontend/src/main.js:4` `import 'element-plus/dist/index.css'` | 入口 CSS 369.97 kB，含全部组件样式 | Task 1 |
| `frontend/vite.config.js:23` `plugins: [vue()]` | 没有编译期解析 `el-*` 的能力 | Task 1 |
| `frontend/src/App.vue:47` 模板根是 `<el-container class="layout">` | locale 无处可挂（`app.use` 被删后） | Task 1 |
| `frontend/src/views/AdminView.vue:59`、`PostDetailView.vue:138`、`:167` 的 `ElMessageBox.confirm(...)` | 未传按钮文案；`ElMessageBox` 独立 app 实例拿不到 provider，会退回英文 | Task 1 |
| `frontend/src/styles/tokens.spec.js:106-125` 的 `order` 数组（`:108` 起共 8 条，首条 `:109` 是 `element-plus/dist/index.css`） | `main.js` 改动后必红；需同步成 12 条 | Task 1 |
| 31 个 spec 全用 stub/mock（15 个用 `global.stubs`），`TagsView.spec.js:9` 还整体 mock 了 `element-plus` | 「构建期配置是否按需」不会被任何用例发现 | Task 1（新增 2 例源码/配置断言） |
| `frontend/package.json:6-12` scripts 无体积检查 | 体积回涨无门禁 | Task 2 |
| `Jenkinsfile:72-81` Build Frontend 只 install + build | 产物回涨、CSS 覆盖漏装都能绿灯发布 | Task 2 |
| `deploy/nginx/forum.conf:15-19` `/forum/assets/` `expires 7d` | 实测 `Cache-Control: max-age=604800`，非 `immutable` | Task 3 |
| `deploy/nginx/forum.conf:32-36` SPA fallback 直出 `index.html` | 实测 `index.html` **无** `Cache-Control`（只有 ETag/Last-Modified） | Task 3 |
| `src/main/java/org/song/forum/repository/TagRepository.java:23` LIKE 无 `ESCAPE` | 标签搜索把 `%`/`_` 当通配符（T17，`docs/DEVELOPMENT.md:687`） | Task 4 |
| `src/main/java/org/song/forum/service/TagService.java:22` `kw` 未转义 | 同上；`PostService.escapeLike` 已有对照实现 | Task 4 |
| `docs/DEVELOPMENT.md:661`、`:718`、`README.md:289` | 文档写着「878 kB 警告是预期」「8 条样式导入」 | Task 5 |

### 1.1 文件结构（本轮全部改动）

| 动作 | 路径 | 归属 |
| --- | --- | --- |
| Modify | `frontend/package.json` | Task 1、Task 2 |
| Modify | `frontend/pnpm-lock.yaml` | Task 1（必须同提交） |
| Modify | `frontend/vite.config.js` | Task 1 |
| Modify | `frontend/src/main.js` | Task 1 |
| Modify | `frontend/src/App.vue` | Task 1 |
| Modify | `frontend/src/views/AdminView.vue` | Task 1 |
| Modify | `frontend/src/views/PostDetailView.vue` | Task 1 |
| Modify | `frontend/src/styles/tokens.spec.js` | Task 1 |
| Create | `frontend/src/plugins/elementAutoImport.spec.js` | Task 1 |
| Create | `frontend/scripts/check-bundle.mjs` | Task 2 |
| Modify | `Jenkinsfile` | Task 2 |
| Modify | `deploy/nginx/forum.conf` | Task 3 |
| Modify | `deploy/deploy.sh` | Task 3 |
| Modify | `src/main/java/org/song/forum/repository/TagRepository.java` | Task 4 |
| Modify | `src/main/java/org/song/forum/service/TagService.java` | Task 4 |
| Modify | `src/test/java/org/song/forum/controller/TagFlowIntegrationTest.java` | Task 4 |
| Create | `docs/superpowers/specs/2026-10-25-round9-frontend-performance-design.md` | 第 0 次提交（Lead） |
| Create | `docs/superpowers/plans/2026-10-25-round9-frontend-performance.md`（本文件） | 第 0 次提交（Lead） |
| Modify | `README.md` | Task 5 |
| Modify | `docs/DEVELOPMENT.md` | Task 5 |
| Modify | `docs/deployment/README.md` | Task 3（§5 冒烟小节）+ Task 5（§7 与回填） |

### 1.2 不改动（明确不出现在任何 Task 的 Files 里）

- `frontend/src/utils/markdown.js`、`frontend/src/composables/useTheme.js`、`frontend/src/api/index.js`、`frontend/src/router/index.js`、`frontend/src/stores/*`：本轮不碰。
- 任何 `.vue` 视图的**模板结构**（除 `App.vue` 包一层 `el-config-provider`）：不改布局、不改文案。
- `docker-compose.yml`、`Dockerfile`、`deploy/deploy.sh` 的部署流程（Task 3 只在「冒烟测试」段末尾追加断言，不动 rsync/reload/清理逻辑）。
- `src/main/resources/db/migration/**`（无迁移）、`pom.xml`（无新 Java 依赖）。
- `docs/DEVELOPMENT.md` §8 变更日志：**本轮由 Task 5 只补 §4.14，不动 §8**（§8 是「每次提交一行」的流水表，回填提交时若需要再补；见 Task 5 Step 5-2 的说明）。

## Task 1：按需引入 Element Plus 并配置本地化提供者

**Files:**

- Modify: `frontend/package.json`（devDependency）
- Modify: `frontend/pnpm-lock.yaml`（`pnpm add` 生成）
- Modify: `frontend/vite.config.js`
- Modify: `frontend/src/main.js`
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/views/AdminView.vue`
- Modify: `frontend/src/views/PostDetailView.vue`
- Modify: `frontend/src/styles/tokens.spec.js`
- Create: `frontend/src/plugins/elementAutoImport.spec.js`
- Add to commit: 上面全部 9 个路径

### Step 1-1：保存基线构建产物（Task 2 的 RED 证据）

不改任何代码，先把「优化前」的产物留一份在仓库外。Task 2 要用它对预算脚本自证 RED。

- [ ] **Step 1-1：复制基线 dist 到 `E:/tmp/forum-dist-baseline`**

```bash
cd /e/JavaSpace/forum/frontend
# 若 dist 不存在（新克隆的仓库），先按基线构建一次再复制
[ -d dist ] || MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build
rm -rf /e/tmp/forum-dist-baseline
mkdir -p /e/tmp
cp -r dist /e/tmp/forum-dist-baseline
ls -l /e/tmp/forum-dist-baseline/assets | head -3
```

预期输出（体积与 §0.4 一致，文件名 hash 可能因构建环境略有不同，但**入口必须是 878,120 B**）：

```
-rw-r--r-- … 211977 … _plugin-vue_export-helper-DNLUZkv0.js
-rw-r--r-- … 878120 … index-Dk6GNTfn.js
-rw-r--r-- … 369967 … index-7FnP3hU_.css
```

- [ ] **Step 1-2：记下基线数字**（写进你的执行日志，Task 5 回填要用）

```bash
cd /e/tmp/forum-dist-baseline/assets
node -e "const fs=require('fs');const f=fs.readdirSync('.').filter(n=>n.endsWith('.js'));const js=f.reduce((s,n)=>s+fs.statSync(n).size,0);console.log('js chunks',f.length,'total',js)"
```

预期：`js chunks 19 total 1344673`。

### Step 1-2：写守护测试（RED）

- [ ] **Step 1-2a：创建 `frontend/src/plugins/elementAutoImport.spec.js`**

`src/plugins/` 目录当前不存在，先建：

```bash
cd /e/JavaSpace/forum/frontend && mkdir -p src/plugins
```

内容（全文件）：

```js
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')

describe('Element Plus 按需引入（构建期）', () => {
  it('main.js 不再全量注册 Element Plus，并保留 v-loading 指令与程序式样式', () => {
    const main = read('../main.js')
    expect(main).not.toContain('app.use(ElementPlus')
    expect(main).not.toContain("from 'element-plus/dist/index.css'")
    expect(main).toContain("app.directive('loading', ElLoading.directive)")
    expect(main).toContain('element-plus/theme-chalk/base.css')
    expect(main).toContain('element-plus/theme-chalk/dark/css-vars.css')
    for (const name of ['message', 'message-box', 'notification', 'loading']) {
      expect(main).toContain(`element-plus/es/components/${name}/style/css`)
    }
  })

  it('vite 配置在构建期挂载按需解析插件、测试期不挂载；App.vue 提供本地化', () => {
    const app = read('../App.vue')
    expect(app).toContain('el-config-provider')
    expect(app).toContain(':locale="zhCn"')
    return import('../../vite.config.js').then(async ({ default: config }) => {
      const testCfg = await config({ mode: 'test' })
      const prodCfg = await config({ mode: 'production' })
      expect(testCfg.plugins).toHaveLength(1)
      expect(prodCfg.plugins).toHaveLength(2)
      expect(prodCfg.plugins.some((p) => String(p?.name).includes('unplugin-vue-components'))).toBe(true)
    })
  })
})
```

- [ ] **Step 1-2b：跑 RED**

```bash
cd /e/JavaSpace/forum/frontend && pnpm vitest run src/plugins/elementAutoImport.spec.js
```

预期 RED（**两例都红**，退出码 1；此时 `main.js` 与 `vite.config.js` 都还没改）：

```
 ❯ src/plugins/elementAutoImport.spec.js (2 tests | 2 failed) …ms
   × Element Plus 按需引入（构建期） > main.js 不再全量注册 Element Plus，并保留 v-loading 指令与程序式样式 …ms
     → expected '…app.use(ElementPlus…' not to contain 'app.use(ElementPlus'
   × Element Plus 按需引入（构建期） > vite 配置在构建期挂载按需解析插件、测试期不挂载；App.vue 提供本地化 …ms
     → TypeError: config is not a function

 Test Files  1 failed (1)
      Tests  2 failed (2)
```

> 第二例的失败形态取决于改到哪一步：`vite.config.js` 还是对象导出时报 `TypeError: config is not a function`；已经改成工厂函数但还没挂插件时报 `expected 1 to be 2`（test 模式 1 个插件、production 模式 2 个插件）。两种都算 RED 达标。
>
> 为什么不用「挂载真实 SFC」的测试：双模式下 vitest 不解析组件（见 Step 1-4），模板 `el-*` 仍由测试里的 `global.stubs` 拦截，挂载型断言会永久红。构建期解析链改由「本 spec 的配置断言 + Step 1-12 构建 + Task 2 的 CSS 覆盖断言 + 部署冒烟」共同守护。

### Step 1-3：安装 `unplugin-vue-components`（devDependency）

- [ ] **Step 1-3：安装并确认 lockfile 一起变了**

```bash
cd /e/JavaSpace/forum/frontend
pnpm add -D unplugin-vue-components@^32 --registry=https://registry.npmmirror.com
git -C /e/JavaSpace/forum status --short
```

预期：`frontend/package.json` 与 `frontend/pnpm-lock.yaml` 都被修改（**两个都要提交**），`package.json` 的 `devDependencies` 多出：

```json
    "unplugin-vue-components": "^32.1.0",
```

确认版本与 engines：

```bash
cd /e/JavaSpace/forum/frontend
node -p "require('./node_modules/unplugin-vue-components/package.json').version"
node -p "require('./node_modules/unplugin-vue-components/package.json').engines"
```

预期：`32.1.0` 与 `{ node: '>=20.19.0' }`。

> 若 install 报网络错误：确认命令里带了 `--registry=https://registry.npmmirror.com`（本机直连官方源不通）。若报 lockfile 冲突：`git checkout -- frontend/pnpm-lock.yaml` 后重试，**不要**手改 lockfile。

### Step 1-4：把 `vite.config.js` 改成双模式工厂函数（构建期挂插件、测试期不挂）

> **这是本轮最关键的一步。** 插件若在 vitest 也生效，模板里的 `el-*` 会在测试期被解析成真实组件，既有用例里的 `global.stubs` 不再命中，实测 **63 个用例失败 / 7 个 spec 文件**（真实 EP 组件渲染 + props 校验报错）。所以 `vite.config.js` 必须导出工厂函数，按 `mode` 决定挂不挂插件。

- [ ] **Step 1-4a：在 import 区追加两行**

`frontend/vite.config.js` 顶部（`import { fileURLToPath, URL } from 'node:url'` 之后）插入：

```js
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
```

- [ ] **Step 1-4b：把对象导出改成工厂函数，并替换 `plugins`**

把 `export default defineConfig({` 改成 `export default defineConfig(({ mode }) => ({`；把文件末尾的 `})` 改成 `}))`。`base` / `resolve` / `server` / `test` 四段原样保留——`test` 段是 vitest 的配置（`environment: 'jsdom'`、`globals: true`、`include: ['src/**/*.spec.js']`），工厂函数不影响它。

然后把 `plugins: [vue()],` 替换成：

```js
  plugins: [
    vue(),
    // 关键：vitest 下保持「不解析组件」——测试用 global.stubs 拦截 el-* 标签；
    // 若测试期也注入组件，stub 不再命中，既有 321 个用例会大面积失效（实测 63 例 / 7 spec 失败）。
    // 构建期才挂按需解析：模板里的 <el-*> 在编译期变成
    //   import { ElButton } from 'element-plus/es'
    //   import 'element-plus/es/components/button/style/css'
    // importStyle: 'css' 复用官方 theme-chalk 的 css 产物，不引入 sass 编译链。
    // dirs: [] 关闭目录扫描（不把 src/components 全部注册成全局组件；自有组件都显式 import）。
    // dts: false —— 仓库没有 TypeScript，不生成 components.d.ts。
    ...(mode === 'test'
      ? []
      : [Components({ resolvers: [ElementPlusResolver({ importStyle: 'css' })], dirs: [], dts: false })])
  ],
```

- [ ] **Step 1-4c：确认两种 mode 的插件数**

```bash
cd /e/JavaSpace/forum/frontend
node -e "import('./vite.config.js').then(async (m) => { const c = m.default; console.log('test', (await c({ mode: 'test' })).plugins.map((p) => p.name)); console.log('prod', (await c({ mode: 'production' })).plugins.map((p) => p.name)) })"
```

预期：`test [ 'vite:vue' ]`；`prod` 有 2 项，第二项名字包含 `unplugin-vue-components`（不同版本可能叫 `unplugin-vue-components:component-resolver` 或 `unplugin-vue-components`）。

> 若 `node -e` 报 `c is not a function`：工厂函数没改完（默认导出还是对象）。若 `test` 也出现 2 个插件：`mode === 'test'` 判断写错（vitest 传入的 mode 就是 `test`，不要改写成 `process.env.NODE_ENV`）。

- [ ] **Step 1-4d：跑守护测试看 GREEN**

```bash
cd /e/JavaSpace/forum/frontend && pnpm vitest run src/plugins/elementAutoImport.spec.js
```

预期：

```
 Test Files  1 passed (1)
      Tests  2 passed (2)
```

> 如果这里仍然红：先确认 `Components` 是从 `unplugin-vue-components/vite` 导入、`ElementPlusResolver` 从 `unplugin-vue-components/resolvers` 导入；再 `rm -rf node_modules/.vite` 清缓存重试。**不要**在测试里手动 `app.use(ElementPlus)` 绕过——那等于放弃了本轮的守护。

### Step 1-5：重写 `frontend/src/main.js`（删全量引入）

- [ ] **Step 1-5：全文件替换为**

```js
import { createApp } from 'vue'
import { createPinia } from 'pinia'
// Element Plus 的组件由 unplugin-vue-components 在构建期按需注入（见 vite.config.js）。
// v-loading 是指令，插件解析不到，必须手工注册（ElLoading.directive）。
import { ElLoading } from 'element-plus'
// 这里只保留「编译器看不见」的样式：基础 CSS 变量、暗色变量、4 条编程式 API 的样式。
// base.css 每个组件的 style/css 也会引一次，重复 import 会被 Vite 去重；
// 显式写在这里是为了让「变量来源」在入口可见（tokens.css 覆盖 --el-color-primary 依赖它先加载）。
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
// 编程式 API 不出现在模板里，插件解析不到，必须手工引样式。清单与源码一一对应：
//   ElMessage      → src/App.vue、src/api/index.js 与 7 个视图
//   ElMessageBox   → views/AdminView.vue、views/PostDetailView.vue
//   ElNotification → composables/useNotificationStream.js
//   ElLoading      → 本文件的 app.directive('loading', ElLoading.directive)
// 将来新增 ElImageViewer 等编程式调用时，这里要补一条 style/css。
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/notification/style/css'
import 'element-plus/es/components/loading/style/css'
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
// 语言包不再在这里注入：改成 App.vue 根部的 <el-config-provider :locale="zhCn">。
// 按需引入后没有 app.use(ElementPlus)，全局配置唯一的落点就是组件树。
app.directive('loading', ElLoading.directive)

app.mount('#app')
```

- [ ] **Step 1-6：确认没有残留的全量引用**

```bash
cd /e/JavaSpace/forum/frontend
grep -n "element-plus/dist/index.css\|app.use(ElementPlus" src/main.js || echo 'OK: 无残留'
grep -c "^import 'element-plus/" src/main.js
```

预期：第一行输出 `OK: 无残留`；第二行输出 `6`（base、dark、message、message-box、notification、loading）。

### Step 1-7：`App.vue` 换用 `<el-config-provider>`

- [ ] **Step 1-7a：在 `<script setup>` 的 import 区加语言包**

`frontend/src/App.vue` 第 4 行（`import { ElMessage } from 'element-plus'`）之后插入：

```js
// 语言包随组件树提供（第九轮：不再 app.use(ElementPlus, { locale: zhCn })）
import zhCn from 'element-plus/es/locale/lang/zh-cn'
```

- [ ] **Step 1-7b：模板根部包一层 provider**

`frontend/src/App.vue` 的 `<template>` 里，把根节点 `<el-container class="layout">` 整段包起来（开标签在 :47、闭标签在 :99，内部缩进整体 +2 空格），形状变成：

```vue
<template>
  <el-config-provider :locale="zhCn">
    <el-container class="layout">
      <!-- …原有的 el-header / el-main / el-footer 全部原样保留，缩进整体 +2 空格… -->
    </el-container>
  </el-config-provider>
</template>
```

要求：`</el-config-provider>` 必须在 `</el-container>` 之后、`</template>` 之前；内部所有标签只改缩进，不改内容（`<router-view :key="$route.fullPath" />` 的位置与 key 不动）。

- [ ] **Step 1-7c：确认包裹正确**

```bash
cd /e/JavaSpace/forum/frontend
grep -n "el-config-provider\|zhCn" src/App.vue
grep -c "el-container" src/App.vue
```

预期：`zhCn` 出现 2 次（import + `:locale`），`el-config-provider` 出现 2 次（开/闭），`el-container` 出现 2 次（开/闭，未重复嵌套）。

### Step 1-8：三处 `ElMessageBox.confirm` 显式给中文按钮文案

> 为什么必须做：`ElMessageBox.confirm()` 会创建独立 app 实例渲染（`render(vnode, container)`），**不继承** `<el-config-provider>` 的 provide；它读 locale 走的是模块级 globalConfig，而那份配置原本由 `app.use(ElementPlus)` 写入。去掉全量注册后不显式给文案，按钮会从「确定/取消」退回英文 `OK`/`Cancel` —— 这是用户可见变更。

- [ ] **Step 1-8a：`frontend/src/views/AdminView.vue:59`**

把：

```js
  await ElMessageBox.confirm(`确定删除板块「${b.name}」吗？（板块下有帖子时会被拒绝）`, '提示', { type: 'warning' })
```

改成：

```js
  await ElMessageBox.confirm(`确定删除板块「${b.name}」吗？（板块下有帖子时会被拒绝）`, '提示', {
    type: 'warning',
    // 第九轮：按需引入后 ElMessageBox 拿不到 <el-config-provider>，显式给中文按钮文案
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    distinguishCancelAndClose: true
  })
```

- [ ] **Step 1-8b：`frontend/src/views/PostDetailView.vue:138`**

把：

```js
  await ElMessageBox.confirm('确定删除该评论吗？', '提示', { type: 'warning' })
```

改成：

```js
  await ElMessageBox.confirm('确定删除该评论吗？', '提示', {
    type: 'warning',
    // 第九轮：按需引入后 ElMessageBox 拿不到 <el-config-provider>，显式给中文按钮文案
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    distinguishCancelAndClose: true
  })
```

- [ ] **Step 1-8c：`frontend/src/views/PostDetailView.vue:167`**

把：

```js
  await ElMessageBox.confirm('确定删除该帖子吗？（软删除，不可恢复显示）', '提示', { type: 'warning' })
```

改成：

```js
  await ElMessageBox.confirm('确定删除该帖子吗？（软删除，不可恢复显示）', '提示', {
    type: 'warning',
    // 第九轮：按需引入后 ElMessageBox 拿不到 <el-config-provider>，显式给中文按钮文案
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    distinguishCancelAndClose: true
  })
```

- [ ] **Step 1-8d：确认三处都改到了**

```bash
cd /e/JavaSpace/forum/frontend
grep -rn "cancelButtonText" src/views | wc -l
grep -rn "ElMessageBox.confirm" src/views | wc -l
```

预期：两条都是 `3`。

### Step 1-9：同步 `tokens.spec.js` 的样式顺序清单

- [ ] **Step 1-9：把 `frontend/src/styles/tokens.spec.js` 里的 `describe('main.js 样式导入顺序', …)` 整块替换为**

```js
describe('main.js 样式导入顺序', () => {
  const source = readFileSync(MAIN_FILE, 'utf8')
  // 第九轮：Element Plus 改为按需引入，入口样式 = 基础变量 + 暗色变量 + 4 条编程式 API 样式
  // + 项目自己的令牌与工具类（base/dark/4 条程序式 = 6 条 Element Plus 样式 + 6 条本地样式 = 12 条）。
  // 顺序要求：base.css 必须最先（tokens.css 覆盖 --el-color-primary
  // 依赖它先加载），暗色变量次之，token 层排在所有 Element Plus 样式之后。
  const order = [
    'element-plus/theme-chalk/base.css',
    'element-plus/theme-chalk/dark/css-vars.css',
    'element-plus/es/components/message/style/css',
    'element-plus/es/components/message-box/style/css',
    'element-plus/es/components/notification/style/css',
    'element-plus/es/components/loading/style/css',
    './styles/tokens.css',
    './styles/common.css',
    './styles/markdown.css',
    'highlight.js/styles/github.css',
    './styles/highlight-dark.css',
    './style.css'
  ]

  it('12 条样式导入按设计顺序出现', () => {
    const positions = order.map((path) => source.indexOf(path))
    for (const [index, position] of positions.entries()) {
      expect(position, `${order[index]} 未导入`).toBeGreaterThan(-1)
    }
    expect(positions).toEqual([...positions].sort((a, b) => a - b))
  })
})
```

- [ ] **Step 1-10：单独跑这个 spec**

```bash
cd /e/JavaSpace/forum/frontend && pnpm vitest run src/styles/tokens.spec.js
```

预期：`Tests  12 passed (12)`（用例数不变，只改了断言内容）。

> 若先改 `main.js` 再改这个 spec，中间态会红在 `element-plus/dist/index.css 未导入`——这是预期的「清单锁死」行为，说明这条测试确实在起作用。

### Step 1-11：全量前端测试与兼容性排查

- [ ] **Step 1-11a：跑全量**

```bash
cd /e/JavaSpace/forum/frontend && pnpm test
```

预期：

```
 Test Files  32 passed (32)
      Tests  323 passed (323)
```

- [ ] **Step 1-11b：若有用例失败，按下面的顺序排查（不要改既有断言、不要加真组件 stub）**

> 测试期插件是关的（`mode === 'test'`），`el-*` 仍由各 spec 的 `global.stubs` 拦截，所以**不会**出现「真实组件渲染」类失败。实测 `mode: 'test'` 下基线 321 例全绿。

| 失败形态 | 原因 | 处置 |
| --- | --- | --- |
| `TypeError: config is not a function` | `vite.config.js` 的默认导出还是对象，工厂函数没改完 | 按 Step 1-4b 改成 `defineConfig(({ mode }) => ({ … }))` |
| 某个既有 spec 里出现真实 EP 组件渲染 / `Failed to resolve` 消失 | 插件在测试期也生效了 | 检查 `plugins` 里的 `mode === 'test' ? [] : [...]` 条件；确认 vitest 传入的 `mode` 是 `test` |
| 只有 `tokens.spec.js` 红在 `element-plus/dist/index.css 未导入` | `main.js` 改了、Step 1-9 的清单还没同步 | 按 Step 1-9 替换 `order` 数组与条数 |
| 新增的 `elementAutoImport.spec.js` 红 | 还没改 `main.js` / `vite.config.js` / `App.vue`，或改得与断言不一致 | 对照 Step 1-5 / 1-4 / 1-7，逐条核对断言字符串 |

**停下条件**：需要改动任一既有 spec（`tokens.spec.js` 除外），或前端用例总数不再是 323 → 停下来把失败清单报告 Lead，不要继续。

- [ ] **Step 1-11c：确认 spec 文件数**

```bash
cd /e/JavaSpace/forum/frontend && find src -name '*.spec.js' | wc -l
```

预期：`32`。

### Step 1-12：构建并记录体积（决定 Task 2 的阈值）

- [ ] **Step 1-12a：构建**

```bash
cd /e/JavaSpace/forum/frontend && MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build
```

预期：命令成功；日志里**不再出现** `(!) Some chunks are larger than 500 kB after minification` 这一行（若仍出现，说明还有 1 个 >500 kB 的 chunk，把它记下来，Task 2 的 RED 会告诉你它是谁）。

- [ ] **Step 1-12b：量体积**

```bash
cd /e/JavaSpace/forum/frontend/dist/assets
ls -l index-*.js index-*.css
node -e "const fs=require('fs');const f=fs.readdirSync('.').filter(n=>n.endsWith('.js'));const js=f.map(n=>[n,fs.statSync(n).size]).sort((a,b)=>b[1]-a[1]);console.log(js.slice(0,3));console.log('total',js.reduce((s,x)=>s+x[1],0))"
```

预期（实测值；`VITE_BASE_PATH=/forum/`，构建约 1.29s）：

| 指标 | 实测值（Task 1 后） |
| --- | --- |
| 入口 `index-*.js` | 84,431 B（84.43 kB） |
| 入口 `index-*.css` | 39,019 B（39.02 kB） |
| 最大 JS chunk | 233,744 B（233.74 kB，`_plugin-vue_export-helper-*.js`） |
| JS 总量（24 chunk） | 835,623 B（835.62 kB） |
| CSS 总量（20 个 CSS） | 181,570 B（181.57 kB） |
| gzip 入口（`gzip -c index-*.js \| wc -c`） | 31,680 B（31.68 kB） |
| 产物文件数 | 47（44 个 assets + `index.html` 等） |

> 与基线对照：入口 JS 878,120 → 84,431 B（-90.4%）；入口 CSS 369,967 → 39,019 B；JS 总量 1,344,673 → 835,623 B；CSS 总量 381,751 → 181,570 B。日志里**没有** `(!) Some chunks are larger than 500 kB after minification`。

- [ ] **Step 1-12c：把上面五个体积数字记到执行日志**（Task 2 定阈值 + Task 5 回填都要用）

```bash
cd /e/JavaSpace/forum/frontend/dist/assets
for f in index-*.js index-*.css; do echo "$f $(stat -c%s "$f")"; done
```

- [ ] **Step 1-12d：人工回归（5 分钟，浏览器）**

`pnpm dev` 后逐项确认（**这是本轮唯一的人工验证，不能跳过**）：

1. 首页列表能加载（`v-loading` 遮罩出现又消失，说明指令被解析）。
2. 顶栏头像下拉、徽标、按钮样式正常（`el-dropdown`/`el-badge`/`el-button` 解析成功）。
3. 标签页与搜索页分页文案是中文「共 N 条」（`el-config-provider` 生效）。
4. 后台板块列表点「删除」→ 确认框按钮是「确定 / 取消」（不是 OK / Cancel）→ 点「取消」。
5. 切到暗色主题，页面与组件颜色都跟着变（`dark/css-vars.css` 仍在）。

### Step 1-13：提交

- [ ] **Step 1-13a：确认改动面**

```bash
cd /e/JavaSpace/forum && git status --short
```

预期（顺序无关，共 9 项，不含 `dist/`——`dist` 已被 gitignore）：

```
 M frontend/package.json
 M frontend/pnpm-lock.yaml
 M frontend/src/App.vue
 M frontend/src/main.js
 M frontend/src/styles/tokens.spec.js
 M frontend/src/views/AdminView.vue
 M frontend/src/views/PostDetailView.vue
 M frontend/vite.config.js
?? frontend/src/plugins/
```

- [ ] **Step 1-13b：提交**

```bash
cd /e/JavaSpace/forum
git add frontend/package.json frontend/pnpm-lock.yaml frontend/vite.config.js \
        frontend/src/main.js frontend/src/App.vue \
        frontend/src/views/AdminView.vue frontend/src/views/PostDetailView.vue \
        frontend/src/styles/tokens.spec.js \
        frontend/src/plugins/elementAutoImport.spec.js
git commit -m "perf: 按需引入 Element Plus 并配置本地化提供者"
```

- [ ] **Step 1-13c：确认提交只含预期文件**

```bash
cd /e/JavaSpace/forum && git show --stat --oneline HEAD
```

预期：9 个文件、无 `dist/`、无文档。

## Task 2：增加产物预算检查并接入构建流程

**Files:**

- Create: `frontend/scripts/check-bundle.mjs`
- Modify: `frontend/package.json`（scripts 加 `check:bundle`）
- Modify: `Jenkinsfile`（Build Frontend 阶段）
- Add to commit: 上面 3 个路径

> 前置：Task 1 已完成并提交；`E:/tmp/forum-dist-baseline` 已存在（Step 1-1）。

### Step 2-1：写预算脚本

- [ ] **Step 2-1：创建 `frontend/scripts/check-bundle.mjs`（全文件）**

`scripts/` 目录当前不存在，先建：

```bash
cd /e/JavaSpace/forum/frontend && mkdir -p scripts
```


```js
#!/usr/bin/env node
/**
 * 构建产物预算检查（第九轮）。
 *
 * 用法：
 *   node scripts/check-bundle.mjs                                   # 检查 ./dist
 *   node scripts/check-bundle.mjs --dist E:/tmp/forum-dist-baseline  # 检查历史产物（自证 RED）
 *
 * 预算（单位 B；Vite 8 的报告口径是 bytes/1000，84431 B 显示为 84.43 kB）：
 *   入口 JS       <= 150,000 B   首屏必须下载的应用代码 + 首屏用到的 Element Plus 组件（实测 84,431）
 *   入口 CSS      <=  70,000 B   同上，按需引入后的入口样式（实测 39,019）
 *   任一 JS chunk <= 300,000 B   防止单个懒加载块重新膨胀（实测 233,744）
 *   CSS 总量      <= 260,000 B   按需样式拆成多个小 css，总量同样要设上限（实测 181,570）
 *   JS 总量       <= 950,000 B   回归护栏（不是优化目标）：防止「拆成很多小 chunk 但总量暴涨」（实测 835,623）
 *
 * 另有 CSS 覆盖断言：合并 dist/assets/*.css 后必须包含 9 个关键组件选择器。
 * 体积绿了但按需样式没装上时，组件会「裸样式」，体积检查看不出来，所以单独断言。
 *
 * 阈值定稿规则（计划 §0.5 裁决点 8）：
 *   以 Task 1 实测值为准向上取整到整十 kB 并留余量；每项都必须显著低于旧基线
 *   （入口 JS 878,120 / 入口 CSS 369,967 / CSS 总量 381,751 / JS 总量 1,344,673）。
 *   改常量时把「实测 → 预算」的算式写在常量行尾注释里。
 */
import { readdir, readFile, stat } from 'node:fs/promises'
import { join, resolve } from 'node:path'

const KB = 1000

// 第九轮定稿值（实测见 docs/DEVELOPMENT.md §4.14 的体积对比表）
const BUDGETS = {
  entryJs: 150 * KB, // 实测 84,431 B；150,000 = 实测的 1.78×，吸收首屏组件增长
  entryCss: 70 * KB, // 实测 39,019 B；70,000 = 实测的 1.79×
  chunkJs: 300 * KB, // 实测 233,744 B；300,000 低于 Vite 的 500 kB 告警线
  totalCss: 260 * KB, // 实测 181,570 B；260,000 = 实测的 1.43×
  totalJs: 950 * KB // 实测 835,623 B；仍比基线 1,344,673 B 低 394,673 B（-29.4%）
}

const LABELS = {
  entryJs: '入口 JS',
  entryCss: '入口 CSS',
  chunkJs: '最大 JS chunk',
  totalCss: 'CSS 总量',
  totalJs: 'JS 总量'
}

// 按需样式必须覆盖的关键组件：缺一个就说明「组件解析到了、样式没跟上」
const REQUIRED_SELECTORS = [
  '.el-button',
  '.el-dialog',
  '.el-table',
  '.el-message-box',
  '.el-notification',
  '.el-pagination',
  '.el-loading-mask',
  '.el-form',
  '.el-select'
]

function fail(message) {
  console.error(`[check-bundle] ${message}`)
  process.exit(1)
}

function parseDistArg(argv) {
  const at = argv.indexOf('--dist')
  if (at === -1) {
    return 'dist'
  }
  const value = argv[at + 1]
  if (!value || value.startsWith('--')) {
    fail('--dist 缺少目录参数')
  }
  return value
}

const formatKb = (bytes) => `${(bytes / KB).toFixed(2)} kB`

/** 中文按 2 列宽计算，表格才不会错位 */
function pad(text, width) {
  const visual = [...text].reduce((n, ch) => n + (ch.charCodeAt(0) > 0x2e80 ? 2 : 1), 0)
  return text + ' '.repeat(Math.max(0, width - visual))
}

async function loadAssets(dist) {
  const assetsDir = join(dist, 'assets')
  let names
  try {
    names = await readdir(assetsDir)
  } catch {
    fail(`找不到 ${assetsDir}：先在 frontend/ 跑 pnpm build（或用 --dist 指向别的产物目录）`)
  }
  const files = []
  for (const name of names) {
    const info = await stat(join(assetsDir, name))
    if (info.isFile()) {
      files.push({ name, size: info.size })
    }
  }
  return { assetsDir, files }
}

const dist = resolve(parseDistArg(process.argv.slice(2)))
const { assetsDir, files } = await loadAssets(dist)

const jsFiles = files.filter((f) => f.name.endsWith('.js'))
const cssFiles = files.filter((f) => f.name.endsWith('.css'))
const entryJs = jsFiles.filter((f) => /^index-.+\.js$/.test(f.name))
const entryCss = cssFiles.filter((f) => /^index-.+\.css$/.test(f.name))

const rows = []
const problems = []

function row(key, name, size, budget) {
  return { key, name, size, budget, ok: size <= budget }
}

// 入口必须恰好一个：Vite 改了命名规则时宁可红，也不要静默「什么都没检查」
if (entryJs.length !== 1) {
  problems.push(
    `入口 JS 应恰好 1 个（index-*.js），实际 ${entryJs.length} 个：${entryJs.map((f) => f.name).join(', ') || '无'}`
  )
} else {
  rows.push(row('entryJs', entryJs[0].name, entryJs[0].size, BUDGETS.entryJs))
}

if (entryCss.length !== 1) {
  problems.push(
    `入口 CSS 应恰好 1 个（index-*.css），实际 ${entryCss.length} 个：${entryCss.map((f) => f.name).join(', ') || '无'}`
  )
} else {
  rows.push(row('entryCss', entryCss[0].name, entryCss[0].size, BUDGETS.entryCss))
}

if (jsFiles.length === 0) {
  problems.push('dist/assets 里没有任何 .js 文件')
} else {
  const biggest = [...jsFiles].sort((a, b) => b.size - a.size)[0]
  rows.push(row('chunkJs', biggest.name, biggest.size, BUDGETS.chunkJs))
  const totalJs = jsFiles.reduce((sum, f) => sum + f.size, 0)
  rows.push(row('totalJs', `${jsFiles.length} 个 chunk`, totalJs, BUDGETS.totalJs))
}

let missingSelectors = REQUIRED_SELECTORS
if (cssFiles.length === 0) {
  problems.push('dist/assets 里没有任何 .css 文件')
} else {
  const totalCss = cssFiles.reduce((sum, f) => sum + f.size, 0)
  rows.push(row('totalCss', `${cssFiles.length} 个文件`, totalCss, BUDGETS.totalCss))

  // 合并全部 css 文本做覆盖断言：按需引入后样式被拆成很多文件，只看体积会漏掉「少装样式」
  let cssText = ''
  for (const f of cssFiles) {
    cssText += await readFile(join(assetsDir, f.name), 'utf8')
  }
  missingSelectors = REQUIRED_SELECTORS.filter((sel) => !cssText.includes(sel))
  if (missingSelectors.length > 0) {
    problems.push(`CSS 缺少关键选择器：${missingSelectors.join(', ')}（按需样式没装上？）`)
  }
}

console.log(`[check-bundle] dist = ${dist}`)
console.log(`${pad('项', 14)}${pad('文件', 40)}${pad('实测', 13)}${pad('预算', 13)}结果`)
for (const r of rows) {
  const verdict = r.ok ? '✓' : `✗ 超 ${formatKb(r.size - r.budget)}`
  console.log(
    `${pad(LABELS[r.key], 14)}${pad(r.name, 40)}${pad(formatKb(r.size), 13)}${pad(formatKb(r.budget), 13)}${verdict}`
  )
}
const covered = REQUIRED_SELECTORS.length - missingSelectors.length
console.log(
  `[check-bundle] CSS 覆盖 ${covered}/${REQUIRED_SELECTORS.length}` +
    (covered === REQUIRED_SELECTORS.length ? ' ✓' : `，缺：${missingSelectors.join(', ')}`)
)
for (const message of problems) {
  console.log(`✗ ${message}`)
}

const failed = rows.filter((r) => !r.ok).length + problems.length
if (failed > 0) {
  console.log(`✗ ${failed} 项超预算（退出码 1）`)
  process.exit(1)
}
console.log('✓ 全部在预算内（退出码 0）')
```

- [ ] **Step 2-2：语法自检（不构建也能跑，找不到目录会按设计失败）**

```bash
cd /e/JavaSpace/forum/frontend && node --check scripts/check-bundle.mjs && echo 'syntax OK'
```

预期：`syntax OK`（`--check` 只验语法；脚本里的顶层 `await` 在 ESM 下合法）。若你本机的 Node 对 `.mjs` 的顶层 `await` 报 `SyntaxError: await is only valid in async functions`，说明 `--check` 按 CommonJS 解析了，跳过这步——Step 2-3 直接运行脚本本身就能暴露语法错误。

### Step 2-2：对基线产物跑 RED（自证脚本真的会拦）

- [ ] **Step 2-3：用**优化前**的产物跑脚本，必须失败**

```bash
cd /e/JavaSpace/forum/frontend
node scripts/check-bundle.mjs --dist E:/tmp/forum-dist-baseline; echo "exit=$?"
```

预期输出（**五项全红**，`exit=1`；列宽由 `pad()` 按中文 2 列计算，你终端里的空格数可能略有差异，重点看五个数字与退出码）：

```
[check-bundle] dist = E:/tmp/forum-dist-baseline
项            文件                                      实测         预算         结果
入口 JS       index-Dk6GNTfn.js                         878.12 kB    150.00 kB    ✗ 超 728.12 kB
入口 CSS      index-7FnP3hU_.css                        369.97 kB    70.00 kB     ✗ 超 299.97 kB
最大 JS chunk index-Dk6GNTfn.js                         878.12 kB    300.00 kB    ✗ 超 578.12 kB
CSS 总量      13 个文件                                 381.75 kB    260.00 kB    ✗ 超 121.75 kB
JS 总量       19 个 chunk                               1344.67 kB   950.00 kB    ✗ 超 394.67 kB
[check-bundle] CSS 覆盖 9/9 ✓
✗ 5 项超预算（退出码 1）
exit=1
```

> 如果这里的数字与你 Step 1-2 记录的基线不一致（例如入口不是 878.12 kB），说明 `E:/tmp/forum-dist-baseline` 已被覆盖或复制的不是基线产物——回到 Step 1-1 重新复制，**不要**改预期输出。

### Step 2-3：对 Task 1 的产物跑 GREEN

- [ ] **Step 2-4：在当前 `dist`（Task 1 的构建结果）上跑**

```bash
cd /e/JavaSpace/forum/frontend
node scripts/check-bundle.mjs; echo "exit=$?"
```

预期（**全部达标**，`exit=0`；以下为 Task 1 的实测值）：

```
[check-bundle] dist = E:/JavaSpace/forum/frontend/dist
项            文件                                      实测         预算         结果
入口 JS       index-D1-b0-D_.js                         84.43 kB     150.00 kB    ✓
入口 CSS      index-JYelfnRp.css                        39.02 kB     70.00 kB     ✓
最大 JS chunk _plugin-vue_export-helper-<hash>.js       233.74 kB    300.00 kB    ✓
CSS 总量      20 个文件                                 181.57 kB    260.00 kB    ✓
JS 总量       24 个 chunk                               835.62 kB    950.00 kB    ✓
[check-bundle] CSS 覆盖 9/9 ✓
✓ 全部在预算内（退出码 0）
exit=0
```

- [ ] **Step 2-5：如果某一条红，按 §0.5 裁决点 8 处理**

预算已按 Task 1 实测值定稿，**只允许收紧、不允许放宽**。若实测与 Step 1-12 记录的差异超过 20%，先停下核对：是不是构建没带 `VITE_BASE_PATH=/forum/`、或 `dist` 里混进了旧产物（`rm -rf dist` 后重新构建）。

- 入口 JS/CSS、单 chunk、CSS 总量、JS 总量五项都是硬上限：**150,000 / 70,000 / 300,000 / 260,000 / 950,000 B**。调高任何一项都等于放宽本轮验收，不允许。
- 只有「收紧」可以在常量行尾更新算式，例如：

  ```js
  totalJs: 900 * KB, // 实测 835,623 B，收紧到 900,000 留 7.7% 余量（仍比基线 1,344,673 B 低 444,673 B）
  ```

- 覆盖断言必须 9/9：缺哪个就说明该组件的 `style/css` 没被解析进产物，回到 Task 1 检查 `Components` / `resolver` 配置，**不要**把该选择器从 `REQUIRED_SELECTORS` 里删掉。

**拿不准就停下报告 Lead。**

### Step 2-4：把脚本挂到 `package.json`

- [ ] **Step 2-6：在 `frontend/package.json` 的 `scripts` 里加一行（放在 `"build"` 之后）**

```json
    "build": "vite build",
    "check:bundle": "node scripts/check-bundle.mjs",
```

- [ ] **Step 2-7：用 pnpm 跑一遍，确认入口可用**

```bash
cd /e/JavaSpace/forum/frontend && pnpm check:bundle; echo "exit=$?"
```

预期：与 Step 2-4 相同输出，`exit=0`。

- [ ] **Step 2-8：确认脚本对「没有 dist」也会按设计失败**

```bash
cd /e/JavaSpace/forum/frontend && node scripts/check-bundle.mjs --dist E:/tmp/no-such-dist; echo "exit=$?"
```

预期：

```
[check-bundle] 找不到 E:/tmp/no-such-dist/assets：先在 frontend/ 跑 pnpm build（或用 --dist 指向别的产物目录）
exit=1
```

### Step 2-5：接入 Jenkins

- [ ] **Step 2-9：`Jenkinsfile` 的 Build Frontend 阶段，在 build 之后追加一行**

把：

```groovy
                dir('frontend') {
                    // pnpm store 放 /pd，避免占满只剩 5G 的根盘
                    sh "${PNPM} install --frozen-lockfile --registry=${NPM_REGISTRY} --store-dir /pd/pnpm-store"
                    // 部署在子路径 /forum/：资源、路由、axios baseURL 三处联动（见 README「部署到子路径」）
                    sh "VITE_BASE_PATH=/forum/ ${PNPM} build"
                }
```

改成：

```groovy
                dir('frontend') {
                    // pnpm store 放 /pd，避免占满只剩 5G 的根盘
                    sh "${PNPM} install --frozen-lockfile --registry=${NPM_REGISTRY} --store-dir /pd/pnpm-store"
                    // 部署在子路径 /forum/：资源、路由、axios baseURL 三处联动（见 README「部署到子路径」）
                    sh "VITE_BASE_PATH=/forum/ ${PNPM} build"
                    // 产物预算（第九轮）：入口 JS/CSS、单 chunk、CSS 总量、JS 总量 + CSS 覆盖断言，超阈值让流水线红。
                    // 阈值与规则见 frontend/scripts/check-bundle.mjs 与 docs/DEVELOPMENT.md §4.14
                    sh "${PNPM} check:bundle"
                }
```

- [ ] **Step 2-10：确认 Jenkinsfile 结构没坏（Groovy 花括号配对）**

```bash
cd /e/JavaSpace/forum && node -e "const s=require('fs').readFileSync('Jenkinsfile','utf8');const o=(s.match(/\{/g)||[]).length,c=(s.match(/\}/g)||[]).length;console.log('braces',o,c,o===c?'OK':'MISMATCH')"
grep -n "check:bundle" Jenkinsfile
```

预期：`braces N N OK` 与一行 `check:bundle`。

### Step 2-6：提交

- [ ] **Step 2-11：确认改动面并提交**

```bash
cd /e/JavaSpace/forum && git status --short
git add frontend/scripts/check-bundle.mjs frontend/package.json Jenkinsfile
git commit -m "perf: 增加产物预算检查并接入构建流程"
git show --stat --oneline HEAD
```

预期改动面 3 项（`frontend/pnpm-lock.yaml` **不应**变化：本轮没装新依赖；若它变了说明你在 Task 1 之后又跑过一次 install，先用 `git checkout -- frontend/pnpm-lock.yaml` 确认无差异）。

## Task 3：静态资源长缓存与入口页禁用缓存

**Files:**

- Modify: `deploy/nginx/forum.conf`
- Modify: `deploy/deploy.sh`
- Modify: `docs/deployment/README.md`（§5 冒烟新增小节；Task 5 再回填上线结果）
- Add to commit: 上面 3 个路径

> 前置：Task 2 已完成。本 Task 不跑单元测试，验证方式是 `bash -n` 语法检查 + `curl -sI` 看响应头（部署后由 `deploy.sh` 自动断言）。

### Step 3-1：`/forum/assets/` 改长缓存

- [ ] **Step 3-1：替换 `deploy/nginx/forum.conf` 里 assets 的 location**

把：

```nginx
    # 静态资源：vite 产物文件名带 hash，可以长缓存
    location ^~ /forum/assets/ {
        alias /pd/Program/forum/frontend/assets/;
        expires 7d;
        access_log off;
    }
```

改成：

```nginx
    # 静态资源：vite 产物文件名带内容 hash，可以长缓存。
    # 只写 add_header，不写 expires —— expires 也会生成一条 Cache-Control，
    # 两者同写会让响应出现两个 Cache-Control 头（语义非法，中间层合并行为不一致）。
    location ^~ /forum/assets/ {
        alias /pd/Program/forum/frontend/assets/;
        add_header Cache-Control "public, max-age=31536000, immutable";
        access_log off;
    }
```

> 顶部的注释原文可能是「静态资源：vite 产物文件名带 hash，可以长缓存」——若与上面不完全一致也没关系，**以 `location ^~ /forum/assets/` 到 `}` 这一整块为替换边界**，注释行按你文件里的原文保留即可。

### Step 3-2：新增 `index.html` 的精确 location

- [ ] **Step 3-2：在 SPA 的 `location /forum/ { … }` 块**之后**插入**

```nginx
    # 入口页：必须每次回源校验。`index index.html` 与 `try_files … /forum/index.html`
    # 都是「内部重定向到 /forum/index.html」，因此下面这个精确匹配同时覆盖
    # /forum/ 与 /forum/xxx 两条路径，不需要在两个地方各写一次。
    # 不把 no-cache 写进 location /forum/：那会让 favicon 等其它直出文件也带上它。
    location = /forum/index.html {
        alias /pd/Program/forum/frontend/index.html;
        add_header Cache-Control "no-cache";
    }
```

- [ ] **Step 3-3：确认两个 location 都存在，且没有残留的 `expires 7d` 在 assets 块里**

```bash
cd /e/JavaSpace/forum
grep -n "location\|Cache-Control\|expires" deploy/nginx/forum.conf
```

预期：

- `location ^~ /forum/assets/` 块里有 `add_header Cache-Control "public, max-age=31536000, immutable";`，**没有** `expires 7d`。
- 新增 `location = /forum/index.html` 块，里面有 `add_header Cache-Control "no-cache";`。
- `location ^~ /forum/uploads/` 仍是 `expires 7d;`（本轮不动）。
- `grep -c expires deploy/nginx/forum.conf` = `1`（只剩 uploads 那条）。

### Step 3-3：`deploy.sh` 冒烟加缓存头断言

- [ ] **Step 3-4：在「冒烟测试」段里、`/forum/api/posts` 检查之后插入**

锚点（原文件里这两行）：

```bash
curl -fsS -H 'Host: raspi' "http://127.0.0.1/forum/api/posts?page=0&size=1" >/dev/null \
    || die "经 nginx 访问 /forum/api/posts 失败（检查反代与数据库连通性）"
```

在它们**之后**插入：

```bash
# 静态资源必须带 immutable 长缓存、入口页必须 no-cache（第九轮）：
# 抓真实 asset 路径而不是硬编码文件名——每次构建的 hash 都会变
asset_path="$(curl -fsS -H 'Host: raspi' "http://127.0.0.1/forum/" | grep -o '/forum/assets/[^"]*\.js' | head -1)"
[ -n "$asset_path" ] || die "/forum/ 首页没有引用任何 /forum/assets/*.js"
asset_cache="$(curl -sSI -H 'Host: raspi' "http://127.0.0.1$asset_path" | tr -d '\r' | grep -i '^cache-control:' || true)"
echo "$asset_cache" | grep -q 'immutable' \
    || die "$asset_path 的 Cache-Control 不含 immutable（实际：${asset_cache:-无}；检查 nginx snippet 是否已 reload）"
echo "$asset_cache" | grep -q 'max-age=31536000' \
    || die "$asset_path 的 Cache-Control 不是一年（实际：${asset_cache:-无}）"

index_headers="$(curl -sSI -H 'Host: raspi' "http://127.0.0.1/forum/index.html" | tr -d '\r')"
echo "$index_headers" | grep -qi '^cache-control: no-cache' \
    || die "/forum/index.html 的 Cache-Control 不含 no-cache（实际：$(echo "$index_headers" | grep -i '^cache-control:' || echo 无)）"
[ "$(echo "$index_headers" | grep -ci '^cache-control:')" = "1" ] \
    || die "/forum/index.html 返回了多条 Cache-Control 头（是否同时写了 expires 与 add_header？）"
```

- [ ] **Step 3-5：语法检查**

```bash
cd /e/JavaSpace/forum && bash -n deploy/deploy.sh && echo 'deploy.sh syntax OK'
```

预期：`deploy.sh syntax OK`。

- [ ] **Step 3-6：本地无法验证 nginx，确认「验证责任」已经落到部署上**

本机是 Windows，没有 nginx；`nginx -t` 只能在 raspi 上跑。`deploy.sh` 的顺序是：

1. `rsync` 前端产物；
2. `sudo -n cp deploy/nginx/forum.conf /etc/nginx/snippets/forum.conf`；
3. `sudo -n /usr/sbin/nginx -t`（**配置语法错会在这一步红，不会带病 reload**）；
4. `sudo -n /usr/bin/systemctl reload nginx`；
5. 冒烟（含 Step 3-4 新增的缓存头断言）。

所以 Task 3 的验证在 Jenkins #20 部署时自动完成；如果 #20 红在 `nginx -t`，照报错改配置（最常见：`alias` 少分号、location 写在 include 之外）。

### Step 3-4：部署手册补冒烟命令

- [ ] **Step 3-7：在 `docs/deployment/README.md` 的 §5 冒烟检查末尾（`### 登录限流与真实客户端 IP（V4）` 小节之后、`## 6. 排障` 之前）插入**

````markdown
### 静态资源缓存头（第九轮）验证

第九轮把 `/forum/assets/` 改成一年 + `immutable`（只写一条 `Cache-Control`），并给入口页加了 `no-cache`。`deploy.sh` 会自动断言这两点，这里给手工复核命令：

```bash
# 1) 资源：必须带 immutable，且只有一条 Cache-Control
ASSET=$(curl -fsS -H 'Host: raspi' http://127.0.0.1/forum/ | grep -o '/forum/assets/[^"]*\.js' | head -1)
echo "asset=$ASSET"
curl -sI -H 'Host: raspi' -H 'Accept-Encoding: gzip' "http://127.0.0.1$ASSET" | grep -iE '^(HTTP/|cache-control:|content-encoding:)'
# 期望：HTTP/1.1 200 OK
#       cache-control: public, max-age=31536000, immutable
#       content-encoding: gzip
# 注意 content-encoding 那行只有带 Accept-Encoding: gzip 才会出现（curl -I 默认不发）

# 2) 入口页与 SPA 回退：必须 no-cache
curl -sI -H 'Host: raspi' http://127.0.0.1/forum/ | grep -i '^cache-control:'
# 期望：cache-control: no-cache
curl -sI -H 'Host: raspi' http://127.0.0.1/forum/posts/1 | grep -i '^cache-control:'
# 期望：cache-control: no-cache

# 3) 不能出现两条 Cache-Control（同时写了 expires 与 add_header 就会）
curl -sI -H 'Host: raspi' "http://127.0.0.1$ASSET" | grep -ci '^cache-control:'
# 期望：1
```

> 必须带 `-H 'Host: raspi'`：raspi 上 nginx 的 server 块写了 `server_name raspi`，不带 Host 时请求落到默认 server，测不到 `/forum/` 的配置。
>
> 发布后如果用户反馈「还是旧界面」，让用户强制刷新一次即可：旧 `index.html` 里引用的旧 hash 资源已被 `rsync --delete` 删除，`no-cache` 保证下一次不会再用旧 html。
````

- [ ] **Step 3-8：确认小节位置正确**

```bash
cd /e/JavaSpace/forum
grep -n "静态资源缓存头（第九轮）验证\|^## 6. 排障" docs/deployment/README.md
```

预期：新小节的行号小于 `## 6. 排障` 的行号。

### Step 3-5：提交

- [ ] **Step 3-9：提交**

```bash
cd /e/JavaSpace/forum && git status --short
git add deploy/nginx/forum.conf deploy/deploy.sh docs/deployment/README.md
git commit -m "perf: 静态资源长缓存与入口页禁用缓存"
git show --stat --oneline HEAD
```

预期改动面 3 项。

## Task 4：标签搜索转义 LIKE 通配符（T17）

**Files:**

- Modify: `src/main/java/org/song/forum/repository/TagRepository.java`
- Modify: `src/main/java/org/song/forum/service/TagService.java`
- Modify: `src/test/java/org/song/forum/controller/TagFlowIntegrationTest.java`
- Add to commit: 上面 3 个路径

> 前置：Task 1–3 已提交。本 Task 不依赖前端，可单独 revert。
>
> 现象：`/api/tags?keyword=100%25` 会把 `%` 当通配符，搜索 `100%` 连 `1000` 一起返回；`a_b` 会命中 `axb`。标签名允许 `%`/`_`（`PostService.resolveTags` 只做 `trim().toLowerCase()`），所以搜索侧必须转义。技术债登记在 `docs/DEVELOPMENT.md` §5.1 的 T17。

### Step 4-1：写 RED 测试

- [ ] **Step 4-1a：在 `TagFlowIntegrationTest.java` 的 import 区追加三个 import**

在 `import java.util.List;` 之前插入（保持 `java.*` 分组内字母序）：

```java
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
```

- [ ] **Step 4-1b：在类末尾（最后一个 `@Test` 方法之后、类的 `}` 之前）追加测试与 helper**

```java
    /**
     * T17（第九轮）：标签关键词里的 '%' 与 '_' 必须按字面量匹配。
     *
     * 必须放在最后（@Order(9)）：本用例会新建标签，而 @Order(2) 断言总览恰好
     * containsExactly("java","spring","spring-boot")、@Order(7) 断言 totalElements()==3，
     * 提前建标签会把它们弄红。
     *
     * 关键词必须自己编码（URLEncoder + URI），不能让 RestTemplate 二次编码：
     * 它是 URI 模板展开器，'%' 会被当成非法字符或再次转义。
     */
    @Test
    @Order(9)
    void tagKeywordTreatsLikeWildcardsAsLiterals() {
        String tagger = registerAndLogin("wildcard-tagger");
        // 两对「一个含通配符、一个能被通配符误命中」的标签
        createPost(tagger, "百分比标签", List.of("100%", "1000"));
        createPost(tagger, "下划线标签", List.of("a_b", "axb"));

        // '%' 不转义时 LIKE '%100%%' 会连 "1000" 一起命中
        assertThat(searchTagNames("100%")).containsExactly("100%");
        // '_' 不转义时 LIKE '%a_b%' 会连 "axb" 一起命中
        assertThat(searchTagNames("a_b")).containsExactly("a_b");
        // ESCAPE 符本身也必须能被转义：搜 '!' 不应把整张标签表都匹配出来
        assertThat(searchTagNames("!")).isEmpty();
    }

    private List<String> searchTagNames(String keyword) {
        String encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        ResponseEntity<Result<PageResponse<TagResponse>>> resp = rest.exchange(
                URI.create(url("/api/tags?keyword=" + encoded)),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {
                });
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return resp.getBody().getData().content().stream().map(TagResponse::name).toList();
    }
```

- [ ] **Step 4-1c：跑 RED**

```bash
cd /e/JavaSpace/forum && mvn -B test -Dtest=TagFlowIntegrationTest
```

预期 RED（`Tests run: 9, Failures: 1`；断言行在新文件第 250 行附近，精确行号随插入位置略有变化）：

```
[ERROR] Tests run: 9, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: … s <<< FAILURE! -- in org.song.forum.controller.TagFlowIntegrationTest
[ERROR] org.song.forum.controller.TagFlowIntegrationTest.tagKeywordTreatsLikeWildcardsAsLiterals -- Time elapsed: … s <<< FAILURE!
java.lang.AssertionError:
Expecting actual:
  ["100%", "1000"]
to contain exactly (and in same order):
  ["100%"]
	at org.song.forum.controller.TagFlowIntegrationTest.tagKeywordTreatsLikeWildcardsAsLiterals(TagFlowIntegrationTest.java:…)
```

> 如果三条断言里只有 `a_b` 那条红（H2 的 `_` 行为差异）或只有 `100%` 红，也是有效 RED：说明该通配符确实没被转义。若三条全绿，先确认 `TagService` 里此刻**没有** `escapeLike`（可能被别人提前改了），再用 `git diff` 核对基线。

### Step 4-2：给 JPQL 加 `ESCAPE '!'`

- [ ] **Step 4-2：替换 `TagRepository.java` 的查询**

把：

```java
    @Query("""
            SELECT t.id, t.name, COUNT(p.id) FROM Post p JOIN p.tags t
            WHERE p.deleted = false
              AND (:keyword = '' OR LOWER(t.name) LIKE LOWER(CONCAT('%', :keyword, '%')))
            GROUP BY t.id, t.name
            """)
```

改成（注意 `ESCAPE '!'` 在 LIKE 表达式**之后**，与 `PostRepository.searchByKeyword` 的写法一致；关键词的转义在 `TagService` 做）：

```java
    // 关键词由 TagService 用 escapeLike 转义，'!' 是约定的转义符（技术债 T17，第九轮修复）。
    // 不写 ESCAPE 时 '%'、'_' 会被当成通配符：搜「100%」会连「1000」一起返回。
    @Query("""
            SELECT t.id, t.name, COUNT(p.id) FROM Post p JOIN p.tags t
            WHERE p.deleted = false
              AND (:keyword = '' OR LOWER(t.name) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '!')
            GROUP BY t.id, t.name
            """)
```

### Step 4-3：`TagService` 转义关键词

- [ ] **Step 4-3a：改一行查询调用**

把：

```java
        List<TagResponse> all = tagRepository.findWithPostCounts(kw).stream()
```

改成：

```java
        // 关键词进 LIKE 前必须转义（T17）：'%'、'_' 是通配符，'!' 是 JPQL 里 ESCAPE 指定的转义符
        List<TagResponse> all = tagRepository.findWithPostCounts(escapeLike(kw)).stream()
```

- [ ] **Step 4-3b：在类的最后一个方法之后、类的 `}` 之前追加 `escapeLike`**

锚点（`TagService.java` 末尾两段）：

```java
        int totalPages = (int) ((all.size() + (long) safeSize - 1) / safeSize);
        return new PageResponse<>(all.subList(from, to), safePage, safeSize, all.size(), totalPages, to >= all.size());
    }
}
```

改成：

```java
        int totalPages = (int) ((all.size() + (long) safeSize - 1) / safeSize);
        return new PageResponse<>(all.subList(from, to), safePage, safeSize, all.size(), totalPages, to >= all.size());
    }

    /**
     * 转义 LIKE 通配符：'!' 是 JPQL 里 ESCAPE 指定的转义符，所以它自己也要先转义。
     * 与 PostService.escapeLike 同款（那边搜索/列表都靠它）；标签名允许 '%'、'_'
     * （PostService.resolveTags 只做 trim + 小写），所以标签搜索侧也必须转义（T17）。
     * 抽公共工具类不划算：只有这两个 Service 用，且两边注释各自解释了「为什么」。
     */
    private static String escapeLike(String keyword) {
        StringBuilder sb = new StringBuilder(keyword.length() + 8);
        for (int i = 0; i < keyword.length(); i++) {
            char c = keyword.charAt(i);
            if (c == '!' || c == '%' || c == '_') {
                sb.append('!');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
```

### Step 4-4：自检改动落位，然后跑 GREEN

- [ ] **Step 4-4：grep 自检（`mvn -B test-compile` 之前先确认 import 没漏、`ESCAPE` 只加了一处）**

```bash
cd /e/JavaSpace/forum && grep -n "escapeLike\|ESCAPE '!'" src/main/java/org/song/forum/service/TagService.java src/main/java/org/song/forum/repository/TagRepository.java
```

预期：`TagService.java` 里 2 处（调用 1 + 方法定义 1，方法体内的 `'!'` 判断不算），`TagRepository.java` 里 1 处。

- [ ] **Step 4-5：单类绿**

```bash
cd /e/JavaSpace/forum && mvn -B test -Dtest=TagFlowIntegrationTest
```

预期：

```
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 4-6：全量绿（后端 177 / 21 类）**

```bash
cd /e/JavaSpace/forum && mvn -B clean test
```

预期（关键行）：

```
[INFO] Tests run: 177, Failures: 0, Errors: 0, Skipped: 1
[INFO] BUILD SUCCESS
```

`Skipped: 1` 是那个 POSIX 权限用例（Windows 跳过），不是失败。若 `Tests run` 不是 177，先看是否有用例被 `-Dtest` 残留影响，再核对是否漏跑测试类。

- [ ] **Step 4-7：确认前端没被牵连（后端改动不影响前端，但提交前跑一次更稳）**

```bash
cd /e/JavaSpace/forum/frontend && pnpm test
```

预期：`Test Files 32 passed (32)` / `Tests 323 passed (323)`。

### Step 4-5：提交

- [ ] **Step 4-8：提交**

```bash
cd /e/JavaSpace/forum && git status --short
git add src/main/java/org/song/forum/repository/TagRepository.java \
        src/main/java/org/song/forum/service/TagService.java \
        src/test/java/org/song/forum/controller/TagFlowIntegrationTest.java
git commit -m "fix: 标签搜索转义 LIKE 通配符"
git show --stat --oneline HEAD
```

预期改动面 3 项（`+` 行数约 60、`-` 行数约 4）。

## Task 5：文档、上线与回填（2 次提交）

**Files:**

- Modify: `README.md`
- Modify: `docs/DEVELOPMENT.md`
- Modify: `docs/deployment/README.md`
- Modify: `docs/superpowers/plans/2026-10-25-round9-frontend-performance.md`（勾选 + §12 回填）
- Add to commit（第 6 次）：`README.md`、`docs/DEVELOPMENT.md`、`docs/deployment/README.md`、本计划
- Add to commit（第 7 次）：`docs/DEVELOPMENT.md`、`docs/deployment/README.md`、本计划的 §12

> 前置：第 0 次文档提交（spec + 本计划）已由 Lead 入库，Task 1–4 已提交，`git log --oneline -5` 能看到那 4 条 `perf:`/`fix:` 提交叠在文档提交之上。本 Task 只改 3 个既有文档 + 本计划，spec 不再改动。

### Step 5-1：`README.md` 更新

- [ ] **Step 5-1a：替换「样式与设计令牌」段里的导入顺序描述**

`README.md` 的「样式与设计令牌」段里（`README.md:289`，即以「第四轮起颜色令牌分成两套」开头的那一段），要改的是**其中的一句话**（原文逐字）：

```markdown
样式导入顺序固定为 Element Plus 基础样式 → 官方暗色变量 → `tokens.css` → `common.css` → `markdown.css` → `github.css` → `highlight-dark.css` → `style.css`（共 8 条，`tokens.spec.js` 逐条校验）。
```

把这一句改成（同段其余文字——两套令牌名称必须一致、`tokens.spec.js` 守住哪些断言、768px 断点——**保持原样**）：

```markdown
样式导入顺序固定为 `element-plus/theme-chalk/base.css` → 官方暗色变量 → `message` → `message-box` → `notification` → `loading` 四条编程式 API 样式 → `tokens.css` → `common.css` → `markdown.css` → `github.css` → `highlight-dark.css` → `style.css`（共 12 条，`tokens.spec.js` 逐条校验；第九轮起 Element Plus 按需引入，组件样式随组件进各自 chunk，入口只留这 6 条 EP 样式）。
```

紧跟这句再补一段：

```markdown
`base.css` 必须先于 `tokens.css`：`tokens.css` 覆盖 `--el-color-primary` 依赖基础变量先加载。模板里的 `<el-*>` 与 `v-loading` 由 `unplugin-vue-components` 在编译期解析（见 `frontend/vite.config.js`）；`loading` 的样式服务 `v-loading` 指令（`ElLoading.directive` 在 `main.js` 全局注册）。新增编程式 API（如后续的 `ElTooltip` 单例）时，除了从 `element-plus` 具名导入，还要在 `frontend/src/main.js` 补一条 `element-plus/es/components/<name>/style/css`——这是按需引入唯一的「手工账」。
```

- [ ] **Step 5-1b：在「测试」段补体积检查命令**

在 `README.md` 前端测试小节（`### 前端测试` 之后）追加：

````markdown
### 产物体积预算

```bash
cd frontend
pnpm build                     # 部署到子路径时：MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build
pnpm check:bundle              # 入口 JS/CSS、单 chunk、CSS 总量、JS 总量与 9 个关键 CSS 选择器是否在预算内（CI 同一条命令）
node scripts/check-bundle.mjs --dist /tmp/some-dist   # 也可以检查任意一份历史产物
```

预算常量在 `frontend/scripts/check-bundle.mjs` 顶部；阈值调整规则与历史实测见 `docs/DEVELOPMENT.md` §4.14。
````

### Step 5-2：`docs/DEVELOPMENT.md` 更新

- [ ] **Step 5-2a：改进度行与快照日期**

把 `:126` 的：

```markdown
**当前进度：MVP 100% ｜ 已完成 7 轮迭代。**
```

改成：

```markdown
**当前进度：MVP 100% ｜ 已完成 9 轮迭代。**
```

把 `:130` 的：

```markdown
## 3. 当前状态快照（2026-10-24）
```

改成：

```markdown
## 3. 当前状态快照（2026-10-25）
```

- [ ] **Step 5-2b：更新 §3.2 测试基线表里的数字**

把表中前端一行与后端一行的用例数改成：后端 `177`（21 个测试类，1 例 POSIX 权限用例 Windows 跳过）、前端 `323`（32 个 spec）。同时更新紧随其后的「前端 spec 分布」合计为 `323`。

> 若前端产品/测试**行数**（§3.1 表）也顺手统计了一遍，可以只改用例数不改行数——§3.1 是快照，不要求每轮重算。改与不改都要在 §4.14 的「测试与验证」里写明用例数与 spec 数，二者一致即可。

- [ ] **Step 5-2c：新建 `### 4.14 第九轮：前端性能优化（2026-10-25）`**

插在 §4.13 结束处（§5.1 之前，原 `#### 4.13` 段落末尾之后）。内容如下（`⟨…⟩` 是**上线后回填**的字段，第 6 次提交时先留成 `（待回填）`，第 7 次提交填数字）：

````markdown
### 4.14 第九轮：前端性能优化（2026-10-25）

设计：[2026-10-25-round9-frontend-performance-design.md](superpowers/specs/2026-10-25-round9-frontend-performance-design.md) ｜ 计划：[2026-10-25-round9-frontend-performance.md](superpowers/plans/2026-10-25-round9-frontend-performance.md)

**目标**：压掉 878 kB 的入口 chunk 与 Vite 的 `chunks larger than 500 kB` 警告，建立可回归的体积预算，补静态资源缓存头，顺带收掉 T17。不改任何用户可见功能与交互。

**改动清单**

| # | 改动 | 落点 | 提交 |
| --- | --- | --- | --- |
| 1 | Element Plus 改按需引入（插件解析 + 4 条编程式样式） | `frontend/vite.config.js`、`frontend/src/main.js` | `perf: 按需引入 Element Plus 并配置本地化提供者` |
| 2 | 语言包从 `app.use()` 迁到 `<el-config-provider :locale="zhCn">`，`ElMessageBox` 三处显式中文按钮文案 | `frontend/src/App.vue`、`AdminView.vue`、`PostDetailView.vue` | 同上 |
| 3 | 新增守护测试 2 例（源码/配置断言：`main.js` 不再全量注册、构建期挂载按需插件、测试期不挂载） | `frontend/src/plugins/elementAutoImport.spec.js` | 同上 |
| 4 | 体积预算脚本 + Jenkins 步骤 | `frontend/scripts/check-bundle.mjs`、`Jenkinsfile` | `perf: 增加产物预算检查并接入构建流程` |
| 5 | `assets` 一年 immutable、`index.html` no-cache + 部署时断言 | `deploy/nginx/forum.conf`、`deploy/deploy.sh` | `perf: 静态资源长缓存与入口页禁用缓存` |
| 6 | 标签搜索转义 LIKE 通配符（T17） | `TagRepository.java`、`TagService.java` | `fix: 标签搜索转义 LIKE 通配符` |

**体积对比（构建号 ⟨#20⟩，bytes/1000 口径）**

| 产物 | 第八轮基线 | 第九轮 | 变化 |
| --- | --- | --- | --- |
| 入口 JS `index-*.js` | 878,120 B（878.12 kB） | 84,431 B（84.43 kB） | -793,689 B（-90.4%） |
| 入口 JS gzip | 279,660 B（279.66 kB） | 31,680 B（31.68 kB） | -247,980 B（-88.7%） |
| 入口 CSS `index-*.css` | 369,967 B（369.97 kB） | 39,019 B（39.02 kB） | -330,948 B（-89.5%） |
| 入口 CSS gzip | 50,520 B（50.52 kB） | 7,150 B（7.15 kB） | -43,370 B（-85.8%） |
| 最大 JS chunk | 878,120 B | 233,744 B（233.74 kB） | -644,376 B（-73.4%） |
| JS 总量（24 chunk） | 1,344,673 B（1344.67 kB） | 835,623 B（835.62 kB） | -509,050 B（-37.9%） |
| CSS 总量（20 文件） | 381,751 B（381.75 kB） | 181,570 B（181.57 kB） | -200,181 B（-52.4%） |
| Vite `>500 kB` 告警 | 有 | 无 | — |

> 上表第九轮数字来自 Task 1 的实测构建（`VITE_BASE_PATH=/forum/`，本机 1.29 s）。Task 5 回填时用 Jenkins 构建日志复核一遍；文件名 hash 会变，但字节数应一致（略有出入以实测为准）。

**预算阈值（`frontend/scripts/check-bundle.mjs`）**

| 项 | 阈值 | 定稿依据 |
| --- | --- | --- |
| 入口 JS | 150 kB | 实测 84.43 kB，留约 78% 余量；硬上限 |
| 入口 CSS | 70 kB | 实测 39.02 kB；硬上限 |
| 任一 JS chunk | 300 kB | 实测最大 233.74 kB；远低于 Vite 的 500 kB 告警线 |
| CSS 总量 | 260 kB | 实测 181.57 kB；硬上限 |
| JS 总量 | 950 kB | 实测 835.62 kB；必须低于基线 1344.67 kB |
| 9 个 CSS 选择器 | 全部存在 | `.el-button`、`.el-dialog`、`.el-table`、`.el-message-box`、`.el-notification`、`.el-pagination`、`.el-loading-mask`、`.el-form`、`.el-select` |

**测试与验证**

- 后端：176 → **177** 用例、21 个测试类不变（`TagFlowIntegrationTest` 8 → 9）。
- 前端：321 → **323** 用例、31 → **32** 个 spec（新增 `src/plugins/elementAutoImport.spec.js` 2 例，源码/配置断言，不做 mount）。
- 端点 39 不变、Flyway 仍 4 个版本、无新增依赖（devDependencies 5 → 6，只加 `unplugin-vue-components`）。
- 构建：`MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build` ⟨待回填：耗时⟩ 成功，无 >500 kB 告警；`pnpm check:bundle` 全绿。
- 传输层：`/forum/assets/*.js` 返回 `Cache-Control: public, max-age=31536000, immutable` 且只有一条；`/forum/` 与 `/forum/posts/1` 的 `index.html` 返回 `Cache-Control: no-cache`；gzip 仍生效。

**已知限制**

1. 去掉全量注册后，`ElMessageBox`/`ElMessage`/`ElNotification` 的样式依赖 `main.js` 的手工清单；新增编程式 API 时必须同步补 `style/css`，否则弹窗会「无样式但能用」（已写进 `main.js` 注释与 README）。
2. 按需引入后每个视图 chunk 会带上自己用到的组件与样式（体积分散），因此「JS 总量」降幅远小于入口降幅；本轮不以总量为目标，只设回归上限。
3. `el-config-provider` 只覆盖组件树内的组件；编程式弹窗（`ElMessageBox`）拿不到它，故三处 `confirm` 显式传中文按钮文案。
4. 入口页 `no-cache` 依赖 `location = /forum/index.html` 的精确匹配；若将来把 SPA 目录改成 nginx 的 root 而非 alias，需要重新核对这个 location（`alias` 与 `try_files` 组合对路径敏感）。
5. `<el-config-provider>` 没有引入 `size`/`zIndex` 等其它全局配置，保持与第八轮一致的视觉。
6. **测试期不挂载按需插件**：`vite.config.js` 在 `mode === 'test'` 时只保留 `vue()`，因为既有 31 个 spec 用 `global.stubs` 拦截 `el-*`；若测试期注入真实组件，实测 63 例 / 7 个 spec 会红。代价是单测不覆盖构建期解析链，由 `elementAutoImport.spec.js`（源码/配置断言）+ `pnpm check:bundle`（体积 + 9 个 CSS 选择器覆盖）+ 部署冒烟共同守护。

**上线验收（2026-10-25）**

⟨待回填：Jenkins 构建号 / 镜像 tag / 提交范围 / 健康检查 / 缓存头实测 / 体积实测 / 人工回归结论⟩
````

- [ ] **Step 5-2d：把 §5.1 的 T17 标为已关闭**

`docs/DEVELOPMENT.md` §5.1 技术债表里 T17 那一行（`docs/DEVELOPMENT.md:687`，原文逐字）：

```markdown
| T17 | **标签检索的 LIKE 通配符未转义** | 低 | 第八轮 T13 只覆盖首页 `GET /api/posts?keyword=` 与既有的 `/api/posts/search`；`TagRepository.findWithPostCounts` 的标签关键词 LIKE 仍把 `%`/`_` 当通配符（`GET /api/tags?keyword=`），搜「a_b」会命中「axb」。修法同 T13：service 先 `escapeLike`，JPQL 加 `ESCAPE '!'` |
```

按同一张表里 T12/T13 的既有写法（标题加删除线、状态列写「已关闭」、描述开头写日期与轮次、末尾保留「原记录」）改成：

```markdown
| T17 | ~~**标签检索的 LIKE 通配符未转义**~~ | 已关闭 | 2026-10-25 第九轮：`TagService` 先 `escapeLike`（转义 `!`/`%`/`_`），`TagRepository.findWithPostCounts` 的 JPQL 加 `ESCAPE '!'`；`TagFlowIntegrationTest` 新增 `@Order(9)` 覆盖 `100%`、`a_b`、`!` 三个关键词（见 §4.14）。原记录：第八轮 T13 只覆盖首页 `GET /api/posts?keyword=` 与既有的 `/api/posts/search`；`TagRepository.findWithPostCounts` 的标签关键词 LIKE 仍把 `%`/`_` 当通配符（`GET /api/tags?keyword=`），搜「a_b」会命中「axb」。修法同 T13：service 先 `escapeLike`，JPQL 加 `ESCAPE '!'` |
```

- [ ] **Step 5-2e：删掉 §6 已知限制里「构建产物 >500 kB 警告」那一条**

`docs/DEVELOPMENT.md` §6 已知问题表里（`docs/DEVELOPMENT.md:718`，原文逐字）：

```markdown
| 构建产物有 chunk > 500kB 警告 | 首屏加载体积偏大 | Element Plus 全量引入所致，可按需引入或分包 |
```

整行删除（该限制已由第九轮消除）。删后不动其它行，表头与分隔行保持原样；顺手确认 §6 里再没有别的「chunk / 体积」类行：

```bash
cd /e/JavaSpace/forum && grep -n "500kB\|chunk" docs/DEVELOPMENT.md
```

预期：只剩 §4.13 历史记录里「本轮不处理 chunk 分包」那句（历史记录不回改），以及 §4.14 新增的小节。

### Step 5-3：`docs/deployment/README.md` 更新

- [ ] **Step 5-3a：§5 的冒烟小节已在 Task 3 加好**，本步只确认它在 `## 6. 排障` 之前

```bash
cd /e/JavaSpace/forum && grep -n "静态资源缓存头（第九轮）验证\|^## 6. 排障" docs/deployment/README.md
```

- [ ] **Step 5-3b：在 §7 已知限制追加一条**

在 §7「已知限制」列表末尾追加：

```markdown
- **静态资源缓存一年 + 入口页 no-cache（第九轮起）**：`/forum/assets/*` 带内容 hash，发布后文件名变化，旧文件被 `rsync --delete` 删除，因此长缓存安全；`index.html` 必须每次回源，否则用户会一直加载旧入口、请求已删除的旧 hash 资源（表现为白屏）。若出现白屏，让用户强制刷新一次即可，不要改成给 `index.html` 加长缓存。
```

### Step 5-4：勾选本计划的 checkbox

- [ ] **Step 5-4：把已完成的 `- [ ]` 改成 `- [x]`**

```bash
cd /e/JavaSpace/forum && grep -c '^- \[ \] \*\*Step [1-4]-' docs/superpowers/plans/2026-10-25-round9-frontend-performance.md
```

预期：`0`（Task 1–4 的 Step 全部是 `- [x]`）。仍处于 `- [ ]` 的只剩本步之后的 5-5…5-9 与 §12/附录 B 的复选框，它们留到第 7 次提交再勾。

### Step 5-5：第 6 次提交 + 推送

- [ ] **Step 5-5a：确认本轮 4 条代码提交都在**

```bash
cd /e/JavaSpace/forum && git log --oneline -5
```

预期（`git log` 最新的在最上面）：`… fix: 标签搜索转义 LIKE 通配符`、`… perf: 静态资源长缓存与入口页禁用缓存`、`… perf: 增加产物预算检查并接入构建流程`、`… perf: 按需引入 Element Plus 并配置本地化提供者`，第五条是 `db16214`。四条代码提交之间的相对顺序取决于你实际执行 Task 的顺序（先做 Task 4 也行），只要 4 条都在 `db16214` 之上、且没有夹带别的提交即可。

- [ ] **Step 5-5b：提交文档**

```bash
cd /e/JavaSpace/forum
git add README.md docs/DEVELOPMENT.md docs/deployment/README.md \
        docs/superpowers/plans/2026-10-25-round9-frontend-performance.md
git commit -m "docs: 记录第九轮前端性能优化（按需引入、产物预算与传输层）"
git show --stat --oneline HEAD
```

预期改动面 4 项（三个既有文档 + 本计划）。

- [ ] **Step 5-5c：推送**

```bash
cd /e/JavaSpace/forum && git push git@github.com:ScholarOfDeepDarkFantasy/forum.git master:master
```

预期：`master -> master`，6 条提交（第 0 次文档 + 4 条代码 + 本次文档）一并上去。

### Step 5-6：Jenkins 构建 + 部署

- [ ] **Step 5-6a：确认轮询已触发（Jenkins `pollSCM('H/5 * * * *')`，最多等 5 分钟）**

构建号应为 **#20**（第八轮 #18、回填 #19）。若 10 分钟没触发，去 Jenkins 手动 Build Now，并在 §12 记录「手动触发」。

- [ ] **Step 5-6b：等构建结束后读结果**

必须全部 SUCCESS 的 stage：Checkout → Build Backend → **Build Frontend**（含新的 `pnpm check:bundle`）→ Build Image → Deploy。

- [ ] **Step 5-6c：记录回填数据**

```bash
cd /e/JavaSpace/forum
git log --oneline -2
git rev-parse --short HEAD
```

Jenkins 控制台里抄：构建号、镜像 tag（= 构建号）、`pnpm build` 的三个体积数字、`check:bundle` 表格、Deploy 阶段冒烟输出（含 `/forum/assets/...` 的 cache-control 断言）。

### Step 5-7：上线冒烟

- [ ] **Step 5-7：按附录 B 逐条执行并记录结果**

附录 B 里的命令都在 raspi 上跑（`ssh pi@raspi` 或 Jenkins 控制台 `Deploy` 阶段已自动跑过一部分）。**任何一条不符预期就停下**：先 `git revert` 对应提交并重新发布，再排查，不要在线上改 nginx。

### Step 5-8：第 7 次提交（回填）

- [ ] **Step 5-8a：回填 `docs/DEVELOPMENT.md` §4.14 的 `⟨待回填⟩`**

至少填：构建号、四个体积数字与降幅、gzip、最大 chunk、JS 总量与 chunk 数、预算定稿值、构建耗时、上线验收段落（健康检查、缓存头、人工回归三条）。体积数字直接从 `frontend/dist/assets` 与 Jenkins 日志取，**不要估算**。

- [ ] **Step 5-8b：回填本计划 §12 实施记录与偏差**

把 §12 表的「实际」列填上（每条：与计划是否一致、差在哪、为什么）。

- [ ] **Step 5-8c：回填 `docs/deployment/README.md` §5 的验收日期与结果**

在新增的「### 静态资源缓存头（第九轮）验证」小节末尾加一行：

```markdown
> 2026-10-25 实测（构建 ⟨#20⟩）：`/forum/assets/⟨index-*.js⟩` → `cache-control: public, max-age=31536000, immutable`（1 条）、`content-encoding: gzip`；`/forum/` 与 `/forum/posts/1` → `cache-control: no-cache`。
```

- [ ] **Step 5-8d：提交**

```bash
cd /e/JavaSpace/forum
git add docs/DEVELOPMENT.md docs/deployment/README.md \
        docs/superpowers/plans/2026-10-25-round9-frontend-performance.md
git commit -m "docs: 回填第九轮构建与冒烟验收"
git push git@github.com:ScholarOfDeepDarkFantasy/forum.git master:master
```

- [ ] **Step 5-9：收尾核对**

```bash
cd /e/JavaSpace/forum && git status --short && git log --oneline -8
```

预期：`git status --short` 为空；`git log` 恰好 7 条本轮提交（第 0 次文档 + 6 次执行）+ 基线 `db16214`。

## 7. 新增 / 修改文件清单

### 7.1 新增（4 个）

| 路径 | 行数量级 | 作用 |
| --- | --- | --- |
| `docs/superpowers/specs/2026-10-25-round9-frontend-performance-design.md` | ~600 | 设计规格（背景/范围/审计/7 条决策/风险/验收） |
| `docs/superpowers/plans/2026-10-25-round9-frontend-performance.md` | ~2090 | 本计划 |
| `frontend/scripts/check-bundle.mjs` | ~140 | 产物预算检查（零依赖，Node ESM） |
| `frontend/src/plugins/elementAutoImport.spec.js` | ~60 | 2 例源码/配置断言：`main.js` 不再全量注册、构建期挂载按需插件、测试期不挂载、`App.vue` 提供本地化 |

### 7.2 修改（17 个）

| 路径 | 归属 | 改动性质 |
| --- | --- | --- |
| `frontend/package.json` | Task 1 / Task 2 | devDependency + scripts |
| `frontend/pnpm-lock.yaml` | Task 1 | `pnpm add` 生成（必须同提交，否则 Jenkins frozen-lockfile 失败） |
| `frontend/vite.config.js` | Task 1 | 工厂函数（按 `mode` 分支）+ 两个 import + `plugins` 数组 |
| `frontend/src/main.js` | Task 1 | 删全量 ElementPlus/dist css，改为 base + dark + 4 条编程式样式 |
| `frontend/src/App.vue` | Task 1 | `zhCn` import + 根部 `<el-config-provider>` |
| `frontend/src/views/AdminView.vue` | Task 1 | 1 处 `ElMessageBox.confirm` 文案 |
| `frontend/src/views/PostDetailView.vue` | Task 1 | 2 处 `ElMessageBox.confirm` 文案 |
| `frontend/src/styles/tokens.spec.js` | Task 1 | 样式顺序清单 8 条 → 12 条 |
| `Jenkinsfile` | Task 2 | Build Frontend 加 `pnpm check:bundle` |
| `deploy/nginx/forum.conf` | Task 3 | assets 长缓存 + `index.html` no-cache |
| `deploy/deploy.sh` | Task 3 | 冒烟加缓存头断言 |
| `src/main/java/org/song/forum/repository/TagRepository.java` | Task 4 | JPQL 加 `ESCAPE '!'` |
| `src/main/java/org/song/forum/service/TagService.java` | Task 4 | 调用处 + `escapeLike` |
| `src/test/java/org/song/forum/controller/TagFlowIntegrationTest.java` | Task 4 | 3 个 import + `@Order(9)` 用例 + helper |
| `README.md` | Task 5 | 样式顺序段 + 体积预算用法 |
| `docs/DEVELOPMENT.md` | Task 5 | 进度/快照/§3.2 数字/§4.14 新章/T17 关闭/§6 删一条 |
| `docs/deployment/README.md` | Task 3 + Task 5 | §5 缓存头验证小节 + §7 已知限制 + 回填 |

## 8. 测试数量预期

### 8.1 总表

| 套件 | 本轮前（`db16214`） | 本轮后 | 差值 | 校验命令 |
| --- | --- | --- | --- | --- |
| 后端用例 | 176（1 skipped） | **177**（1 skipped） | +1 | `cd /e/JavaSpace/forum && mvn -B clean test` |
| 后端测试类 | 21 | 21 | 0 | 同上（`Tests run` 末尾总数 + 类名列表） |
| 前端用例 | 321 | **323** | +2 | `cd /e/JavaSpace/forum/frontend && pnpm test` |
| 前端 spec 文件 | 31 | **32** | +1 | `cd /e/JavaSpace/forum/frontend && find src -name '*.spec.js' \| wc -l` |
| HTTP 端点 | 39 | 39 | 0 | `grep -rn "@GetMapping\|@PostMapping\|@PutMapping\|@DeleteMapping\|@PatchMapping" src/main/java --include=*.java \| wc -l` |
| 数据库迁移 | V4 | V4 | 0 | `ls src/main/resources/db/migration` |
| 运行依赖 | 9 | 9 | 0 | `cd frontend && node -p "Object.keys(require('./package.json').dependencies).length"` |
| 开发依赖 | 5 | 6 | +1 | 同上换 `devDependencies` |

### 8.2 逐任务累计

| 完成到 | 后端用例 / 类 | 前端用例 / spec | 备注 |
| --- | --- | --- | --- |
| 基线 | 176 / 21 | 321 / 31 | — |
| Task 1 后 | 176 / 21 | **323 / 32** | +2：`elementAutoImport.spec.js`（2 例源码/配置断言）；`tokens.spec.js` 用例数不变（只改断言） |
| Task 2 后 | 176 / 21 | 323 / 32 | 脚本无单测，靠基线 RED + 当前 GREEN 自证 |
| Task 3 后 | 176 / 21 | 323 / 32 | nginx 改动由 `bash -n` + 部署断言验证 |
| Task 4 后 | **177 / 21** | 323 / 32 | +1：`TagFlowIntegrationTest` 8 → 9 |
| Task 5 后 | 177 / 21 | 323 / 32 | 纯文档 |

### 8.3 算式

- 前端用例：`321 + 2 = 323`；spec：`31 + 1 = 32`。
- 后端用例：`176 + 1 = 177`；测试类不变（`TagFlowIntegrationTest` 内在加一例，不新建类）。
- 端点：`39 + 0 = 39`（没有新增/删除映射）。
- 若任一数字对不上，先判断是「计划算错」还是「实现漏写」，**不要修改本表去迁就实现**——差异记在 §12。

## 9. 风险与回滚

| # | 风险 | 概率/影响 | 前置信号 | 应对 | 回滚 |
| --- | --- | --- | --- | --- | --- |
| 1 | 去掉全量注册后某个视图真的缺组件（模板里拼错标签名，以前被全局注册“兜住”） | 中 / 高（页面崩） | `pnpm dev` 控制台出现 `Failed to resolve component` | Step 1-12d 的人工回归覆盖 5 条主路径；发现即修标签名 | `git revert` Task 1 提交 |
| 2 | 入口 JS 压不到 150 kB（EP 按需后仍偏大） | 中 / 中 | Step 1-12b 实测 > 150,000 B | 预算已按实测 84.43 kB 定稿；若明显回涨，先查插件是否在构建期生效、有没有新的全量引入；> 150 kB 则停下报告 Lead（可能需 manualChunks，属扩范围） | 同上（不回滚也能发布，预算硬上限不放宽） |
| 3 | 测试期关闭按需解析 → 单测不覆盖构建期解析链 | 中 / 中 | `elementAutoImport.spec.js` 两例通过但产物仍缺组件样式；或有人把插件无条件挂上，导致 63 例 / 7 个 spec 变红 | `elementAutoImport.spec.js` 断言 `mode: 'test'` 只有 1 个插件、`production` 有 2 个；Task 2 的 CSS 覆盖断言（9/9）与部署冒烟兜底 | 同上 |
| 4 | 某处 `ElMessageBox` 漏改文案 → 用户看到英文按钮 | 低 / 中（可见变更） | Step 1-8d 的 `grep -c cancelButtonText` 不是 3 | 补第 3 处 | 同上 |
| 5 | `pnpm-lock.yaml` 未随提交 → Jenkins frozen-lockfile 红 | 中 / 高（发布阻塞） | Jenkins #20 在 Build Frontend 的 install 步骤失败 | Task 1 提交里必须含 lockfile（Step 1-3 已强调）；失败则补一次提交把 lockfile 带上 | 无需回滚 |
| 6 | nginx 出现两条 `Cache-Control`（`expires` 与 `add_header` 并存） | 中 / 中（缓存语义不确定） | `deploy.sh` 的 `grep -ci '^cache-control:' = 1` 断言失败 | 删掉 `expires 7d`（Step 3-1 已写） | `git revert` Task 3，`deploy.sh` 会重新下发旧 conf 并 reload |
| 7 | `index.html` 的 `no-cache` 没生效（用户拿到旧入口 → 白屏） | 中 / 高（线上白屏） | 冒烟 `curl -sI` 无 `no-cache`，或用户反馈白屏 | 核对该 location 是否被 `location /forum/` 的匹配抢先（`=` 精确匹配优先级最高，正常不会）；确认 `nginx -t` 与 reload 成功 | 同上；紧急时可临时在 `location /forum/` 加 `add_header Cache-Control "no-cache";` |
| 8 | T17 修复后标签搜索行为变化（如关键词含 `!` 时结果变空） | 低 / 低 | `TagFlowIntegrationTest` 新用例通过但手工搜 `!` 有疑问 | `!` 是 ESCAPE 符，转义后按字面量匹配，语义正确；`TagService` 已无其它调用点 | `git revert` Task 4（纯后端，独立回滚） |

回滚命令（按提交信息定位，不依赖 hash）：

```bash
cd /e/JavaSpace/forum
git log --oneline --grep 'perf: 按需引入 Element Plus'      # 找 Task 1
git revert --no-edit <hash>                                  # 生成一条 revert 提交
git push git@github.com:ScholarOfDeepDarkFantasy/forum.git master:master
# Jenkins 轮询会在 5 分钟内自动发一次；想立刻回滚线上可用：
#   sudo -u pi /usr/bin/bash -c 'cd /pd/jenkins-workspace/forum && /usr/bin/bash deploy/deploy.sh <上一个镜像 tag>'
```

> 只回滚后端（Task 4）时同理，`git revert <Task 4 hash>` 后重新发布即可，前端产物不变。**禁止** `git reset --hard` + `push -f`：`master` 是唯一分支，别人可能已拉到。

## 10. Self-Review

### 10.1 spec 覆盖

| spec 决策 | 本计划落点 | 覆盖 |
| --- | --- | --- |
| R1 构建期按需引入（`unplugin-vue-components` + `ElementPlusResolver`） | Task 1 Step 1-3、1-4 | ✅ |
| R2 样式策略（`importStyle: 'css'` + base/dark/4 条编程式） | Task 1 Step 1-5、1-9 | ✅ |
| R3 本地化（`el-config-provider` + MessageBox 显式文案） | Task 1 Step 1-7、1-8 | ✅ |
| R4 产物预算（脚本 + Jenkins） | Task 2 Step 2-1…2-11 | ✅ |
| R5 传输层缓存（assets immutable / index no-cache / 冒烟断言） | Task 3 Step 3-1…3-9 | ✅ |
| R6 T17（LIKE 转义） | Task 4 Step 4-1…4-8 | ✅ |
| R7 文档落点（README/DEVELOPMENT/部署手册） | Task 5 Step 5-1…5-4、5-8 | ✅ |
| 非目标（auto-import/manualChunks/SSR/PWA/CDN/brotli/后端重构） | §1.2 不改动清单 + §0.5 裁决点 3、8 | ✅ |

### 10.2 占位符扫描

本计划刻意保留的占位符只有一处：Task 5 的 `⟨待回填⟩`（第 6 次提交留空、第 7 次提交填写）。开工前顺手确认没有别的记号残留：

```bash
cd /e/JavaSpace/forum
grep -nE "TODO|TBD|FIXME" docs/superpowers/plans/2026-10-25-round9-frontend-performance.md
grep -c "待回填" docs/superpowers/plans/2026-10-25-round9-frontend-performance.md
```

预期：第一条**无输出**；第二条输出一个正整数（`⟨待回填⟩` 只出现在 Task 5 的 §4.14 模板、Step 5-8 说明与附录 B.7 里）。若第一条有输出，说明计划里还有含糊项，补掉再开工。命令里的 `/forum/xxx` 是路由占位写法，不含上面三个记号。

### 10.3 命名一致性

| 名字 | 唯一写法 | 出现位置 |
| --- | --- | --- |
| 预算脚本 | `check-bundle.mjs`，命令 `pnpm check:bundle` | `package.json`、`Jenkinsfile`、README、§4.14 |
| 守护测试 | `src/plugins/elementAutoImport.spec.js`（源码/配置断言，不做 mount） | Task 1、§7.1、§8.2 |
| 本地化 | `<el-config-provider :locale="zhCn">` | `App.vue`、spec §7、§4.14 |
| 转义方法 | `TagService.escapeLike`（与 `PostService.escapeLike` 同名同语义） | Task 4、spec §10 |
| 基线目录 | `E:/tmp/forum-dist-baseline`（Windows 写法）／Git Bash 里 `/e/tmp/forum-dist-baseline` | Task 1、Task 2 |

> 两个路径写法指同一目录：node 在 Windows 上不认 `/e/tmp`，所以脚本参数用 `E:/tmp/...`；bash 的 `cp` 用 `/e/tmp/...`。

### 10.4 测试可运行性

- 每个 Task 的验证命令都在 `0.3` 声明的环境里可直接执行；没有「先配置 X」的前置。
- 所有改文件的 Step 都用**原文锚点 + 完整替换块**，锚点在本计划的 `§1 现状与差距` 里逐条对应到 `file:line`；行号会随提交漂移，所以锚点用代码文本而不是行号。
- Task 2 的 RED 依赖 `E:/tmp/forum-dist-baseline`：Step 1-1 保证它存在；若被清理，Step 2-3 的红不会出现（脚本会对当前 dist 判绿），这不是脚本坏了。
- Task 4 的 RED 依赖「`TagService` 此刻没有 `escapeLike`」：文档基线 `db16214` 成立；若别人提前修了 T17，RED 会直接绿——此时按 Step 4-1c 的提示停下核对。

### 10.5 实现顺序

Task 1 → Task 2 有硬依赖（预算阈值要用 Task 1 的实测值；基线 RED 要用 Task 1 之前的 dist）。Task 3 与 Task 4 相互独立、也与 Task 1/2 独立，可按任意顺序做，但**必须都在 Task 5 之前**（文档要写最终数字）。Task 5 的第 7 次提交依赖线上部署结果，不能提前。

### 10.6 已裁决的模糊点

- 「`dirs` 传不传」→ §0.5 裁决点 4（显式传 `dirs: []`）。
- 「测试期要不要挂按需插件」→ §0.5 裁决点 14（不挂；否则 63 例 / 7 个 spec 红）。
- 「JS 总量预算怎么定」→ §0.5 裁决点 8（按 Task 1 实测 835.62 kB 留余量到 950 kB，硬上限、只许收紧）。
- 「`index.html` 用 `add_header` 还是 `expires -1`」→ 裁决点 11（`add_header`，理由：server 层没有其它 `add_header`，不存在顶掉继承头的问题）。
- 「Task 3 要不要改文档」→ 是（§5 新增冒烟小节），因为部署手册是运维执行面，改配置不改手册等于没交付。
- 「T17 抽公共工具还是复制」→ 裁决点 13（复制 11 行，两个 Service 各自注释，避免为两个调用点引入新类）。

## 11. Execution Handoff

- [ ] **Task 1**：按需引入 Element Plus 并配置本地化提供者（Steps 1-1…1-13，提交 `perf: 按需引入 Element Plus 并配置本地化提供者`）
- [ ] **Task 2**：增加产物预算检查并接入构建流程（Steps 2-1…2-11，提交 `perf: 增加产物预算检查并接入构建流程`）
- [ ] **Task 3**：静态资源长缓存与入口页禁用缓存（Steps 3-1…3-9，提交 `perf: 静态资源长缓存与入口页禁用缓存`）
- [ ] **Task 4**：标签搜索转义 LIKE 通配符（Steps 4-1…4-8，提交 `fix: 标签搜索转义 LIKE 通配符`）
- [ ] **Task 5**：文档、上线与回填（Steps 5-1…5-9，提交 `docs: 记录第九轮前端性能优化（按需引入、产物预算与传输层）` 与 `docs: 回填第九轮构建与冒烟验收`）

**最容易写错的 7 个点**

1. **忘了提交 `frontend/pnpm-lock.yaml`**：Jenkins 用 `--frozen-lockfile`，lockfile 不同步会让 Build Frontend 第一步就红，而且报错信息（`ERR_PNPM_OUTDATED_LOCKFILE`）看起来像网络问题。
2. **`main.js` 里漏了编程式样式**：`ElMessage`/`ElMessageBox`/`ElNotification` 的样式不在模板里出现，插件解析不到；漏了的表现是「弹窗能弹出但没样式」，本地开发很容易忽略（样式在 dev 时可能被别的 chunk 顺带引入）。
3. **`ElMessageBox` 的按钮文案**：`<el-config-provider>` 只作用于组件树，`ElMessageBox.confirm` 是独立 app 实例，不显式传文案就是英文 OK/Cancel —— 这是**用户可见变更**，违反本轮约束。
4. **`tokens.spec.js` 的顺序断言**：它按 `indexOf` 比位置，`element-plus/theme-chalk/base.css` 与 `element-plus/es/components/message/style/css` 有公共前缀，顺序写错时错误信息只会说「未导入」，很容易误判成漏了 import。
5. **预算脚本的单位**：Vite 报 `878.12 kB` 是 `878120 / 1000`；如果用 `1024` 换算，阈值会凭空少 2.4%，看起来「刚好达标」其实是误判。
6. **nginx 的 `expires` 与 `add_header` 同写**：会输出两条 `Cache-Control`，浏览器取第一条（`max-age=604800`），于是「改了一年」实际还是 7 天；`deploy.sh` 的「只有一条」断言就是为这个准备的。
7. **T17 的测试位置**：`TagFlowIntegrationTest` 的方法有 `@Order` 且共享数据，新用例写到 `@Order(2)` 之前会让 `containsExactly("java","spring","spring-boot")` 与 `totalElements()==3` 变红，症状看起来像「转义把查询改坏了」。

## 附录 A：本轮提交信息（逐字）

```text
docs: 新增第九轮前端性能优化设计与计划
perf: 按需引入 Element Plus 并配置本地化提供者
perf: 增加产物预算检查并接入构建流程
perf: 静态资源长缓存与入口页禁用缓存
fix: 标签搜索转义 LIKE 通配符
docs: 记录第九轮前端性能优化（按需引入、产物预算与传输层）
docs: 回填第九轮构建与冒烟验收
```

对应 `git log --oneline` 时会看到 `perf:` 三条、`fix:` 一条、`docs:` 三条（第 0 次由 Lead 提交，其余六次由执行者提交）。**不要**加 scope、不要加 `Co-Authored-By`、不要加 `🤖 Generated with` 尾巴。

## 附录 B：Task 5 上线冒烟（raspi）

执行前提：Jenkins #20 已 SUCCESS 且 Deploy 阶段完成；`/pd/Program/forum/frontend/assets` 里的文件名带新 hash。所有命令在 raspi 上跑（`ssh pi@raspi`），或在 Jenkins 控制台的 `Deploy` 阶段输出里核对——`deploy.sh` 已自动跑过 B.2/B.3/B.5。

### B.1 发布面

- [ ] **B.1：确认产物与镜像**

```bash
# 前端产物时间戳是本次发布
ls -l --time-style=long-iso /pd/Program/forum/frontend/assets | head -5
# 镜像 tag 与 Jenkins 构建号一致（第八轮是 18、回填是 19，本轮应为 20）
sudo -u pi podman images --format '{{.Repository}}:{{.Tag}} {{.Created}}' | grep forum-backend | head -3
# 后端在跑 + 健康检查（本轮无迁移，不需要核对 flyway 版本）
sudo -u pi podman ps --format '{{.Names}} {{.Status}}' | grep forum-backend
curl -fsS http://127.0.0.1:8086/api/health && echo
```

预期：assets 时间为本次发布；`localhost/forum-backend:20` 存在；容器 `Up`；health 返回 `{"status":"UP"...}`（或项目既有格式）。

### B.2 页面与静态资源

- [ ] **B.2：入口页、SPA 回退、资源可访问**

```bash
curl -fsS -H 'Host: raspi' http://127.0.0.1/forum/ | grep -o '/forum/assets/[^"]*\.js' | head -3
curl -fsS -H 'Host: raspi' http://127.0.0.1/forum/posts/1 | grep -c 'id="app"'
# 取第一个 asset 验证 200 + gzip（-H 'Accept-Encoding: gzip' 才会有 content-encoding 行）
ASSET=$(curl -fsS -H 'Host: raspi' http://127.0.0.1/forum/ | grep -o '/forum/assets/[^"]*\.js' | head -1)
curl -sI -H 'Host: raspi' -H 'Accept-Encoding: gzip' "http://127.0.0.1$ASSET" | grep -iE '^(HTTP/|content-encoding:|cache-control:)'
```

预期：第一条输出 1–3 条 `/forum/assets/index-*.js`；第二条 `1`；第三条 `HTTP/1.1 200`、`content-encoding: gzip`、`cache-control: public, max-age=31536000, immutable`。

### B.3 缓存头（本轮核心验收）

- [ ] **B.3：assets 一条 immutable、index.html no-cache、没有双头**

```bash
ASSET=$(curl -fsS -H 'Host: raspi' http://127.0.0.1/forum/ | grep -o '/forum/assets/[^"]*\.js' | head -1)
echo "--- asset ---"; curl -sI -H 'Host: raspi' "http://127.0.0.1$ASSET" | grep -ci '^cache-control:'
curl -sI -H 'Host: raspi' "http://127.0.0.1$ASSET" | grep -i '^cache-control:'
echo "--- /forum/ ---"; curl -sI -H 'Host: raspi' http://127.0.0.1/forum/ | grep -i '^cache-control:'
echo "--- /forum/posts/1 ---"; curl -sI -H 'Host: raspi' http://127.0.0.1/forum/posts/1 | grep -i '^cache-control:'
echo "--- uploads（本轮不动）---"; curl -sI -H 'Host: raspi' http://127.0.0.1/forum/uploads/nope.png | grep -iE '^(HTTP/|cache-control:)'
```

预期：asset 的计数为 `1`，值为 `cache-control: public, max-age=31536000, immutable`；`/forum/` 与 `/forum/posts/1` 都是 `cache-control: no-cache`；uploads 仍是 `max-age=604800`（且 404 时可能没有 cache-control，属正常）。

### B.4 体积（回填用）

- [ ] **B.4：记录线上产物体积（bytes）**

```bash
cd /pd/Program/forum/frontend/assets
for f in index-*.js index-*.css; do printf '%s %s\n' "$(stat -c%s "$f")" "$f"; done
node -e "const fs=require('fs');const f=fs.readdirSync('.').filter(n=>n.endsWith('.js'));const js=f.map(n=>[n,fs.statSync(n).size]).sort((a,b)=>b[1]-a[1]);console.log('chunks',f.length,'total',js.reduce((s,x)=>s+x[1],0));console.log('top',js[0])"
```

预期：入口 JS ≤ 150,000 B（实测 84,431 B）、入口 CSS ≤ 70,000 B（实测 39,019 B）、最大 chunk ≤ 300,000 B（实测 233,744 B）、CSS 总量 ≤ 260,000 B（实测 181,570 B）、JS 总量 ≤ 950,000 B（实测 835,623 B）。这些数字就是 §4.14 体积对比表的「第九轮」列。

### B.5 既有功能回归（后端 + SSE + uploads）

- [ ] **B.5：API、SSE、uploads 三条既有冒烟**

```bash
# API
curl -fsS -H 'Host: raspi' 'http://127.0.0.1/forum/api/posts?page=0&size=1' | head -c 200; echo
# 标签搜索（T17）：'100%25' 只应命中名字含 '100%' 的标签
curl -fsS -H 'Host: raspi' 'http://127.0.0.1/forum/api/tags?keyword=100%25&size=100' | head -c 300; echo
# SSE：未登录必须 401（不能 200，也不能 502）
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: raspi' http://127.0.0.1/forum/api/notifications/stream
# uploads：不存在的文件必须 404
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: raspi' http://127.0.0.1/forum/uploads/nope.png
```

预期：posts 返回 `Result` JSON；tags 返回的 `content` 里只含 `100%`（若部署数据里没有 `100%` 标签，返回空数组也算通过）；SSE `401`；uploads `404`。

### B.6 人工回归（浏览器，5 分钟）

- [ ] **B.6：逐条确认**

1. 打开 `http://raspi/forum/`：首页列表渲染、`v-loading` 遮罩正常、无控制台 `Failed to resolve component`。
2. 任意帖子详情：评论列表、`el-tag` 样式、Markdown 高亮正常。
3. 标签页 → 分页组件文案是中文（`共 N 条`）。
4. 登录后台（admin）→ 板块/用户列表 → 删除按钮弹确认框，按钮是 **确定 / 取消**（不是 OK / Cancel）→ 取消。
5. 切换暗色主题：页面与 Element Plus 组件颜色同时变化；刷新后保持。
6. 顶栏头像下拉、未读徽标正常。

### B.7 回填数据清单

- [ ] **B.7：把下面每个值抄进 `docs/DEVELOPMENT.md` §4.14**

| 字段 | 来源 |
| --- | --- |
| Jenkins 构建号 / 镜像 tag | Jenkins 控制台、B.1 |
| 入口 JS / CSS 字节数与 gzip | B.4 + `gzip -c` |
| 最大 chunk 名与字节 | B.4 的 `top` |
| JS 总量与 chunk 数 | B.4 的 `chunks`/`total` |
| 预算定稿值 | `frontend/scripts/check-bundle.mjs` 的 `BUDGETS` |
| 构建耗时 | Jenkins `Build Frontend` 阶段日志 |
| 缓存头实测 | B.3 |
| 人工回归结论 | B.6 |

## 12. 实施记录与偏差

> 执行时逐行填写；「计划预期」列**不要改**（它是基线，改了就看不出偏差）。填完的内容随第 7 次提交入库。

| # | 项 | 计划预期 | 实际 | 偏差原因 / 处理 |
| --- | --- | --- | --- | --- |
| 1 | 依赖安装 | `unplugin-vue-components@32.1.0`，lockfile 同提交 | （待填） | |
| 2 | 入口 JS | ≤ 150,000 B（实测 84,431 B） | （待填） | |
| 3 | 入口 CSS | ≤ 70,000 B（实测 39,019 B） | （待填） | |
| 4 | 最大 chunk | ≤ 300,000 B（实测 233,744 B），Vite 无告警 | （待填） | |
| 5 | JS 总量 | ≤ 950,000 B（实测 835,623 B）；CSS 总量 ≤ 260,000 B（实测 181,570 B） | （待填） | |
| 6 | 前端用例 | 323 / 32 spec | （待填） | |
| 7 | 既有 spec 改动 | 仅 `tokens.spec.js` 1 个（改断言，不新增用例） | （待填） | 若 > 12 个需记录原因 |
| 8 | 后端用例 | 177 / 21 类 | （待填） | |
| 9 | 缓存头 | assets 1 条 immutable；index 1 条 no-cache | （待填） | |
| 10 | Jenkins | #20，全 stage SUCCESS（含 check:bundle） | （待填） | |
| 11 | 人工回归 | B.6 六条全过 | （待填） | |
| 12 | 提交数 | 7 条（第 0 次 Lead + 6 次执行），信息与附录 A 逐字一致 | （待填） | |

**执行中做出的、计划没写的决定**（有就写，没有写「无」）：

| 日期 | 决定 | 原因 | 影响面 |
| --- | --- | --- | --- |
| （待填） | | | |

