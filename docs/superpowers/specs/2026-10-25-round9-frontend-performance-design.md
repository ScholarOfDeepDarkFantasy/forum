# 第九轮设计：前端性能优化（Element Plus 按需引入、产物预算与传输层缓存）

- 日期：2026-10-25
- 状态：设计已确认，待实现
- 基线：`db16214`（前端 31 个 spec / 321 用例；后端 21 个测试类 / 176 用例；HTTP 端点 39 个；工作区干净）
- 范围：把 878.12 kB 的入口 chunk 压下来、给构建产物加可回归的体积预算、补静态资源缓存头，并顺手收掉技术债 T17（标签搜索 LIKE 未转义）。**不改任何用户可见功能与交互**。
- 计划：`docs/superpowers/plans/2026-10-25-round9-frontend-performance.md`（5 个 Task，共 7 次提交：第 0 次由 Lead 单独提交本 spec 与计划，执行者 6 次）

## 1. 背景与目标

第八轮把质量加固做完后，`docs/DEVELOPMENT.md` §4.13 的「已知限制」里留了两条与前端体积有关的记录：

1. 构建产物出现 `chunks larger than 500 kB` 警告，`dist/assets/index-*.js` 878.12 kB（gzip 279.66 kB）。
2. `TagRepository.findWithPostCounts` 的标签关键词 LIKE 仍未转义（技术债 T17，`docs/DEVELOPMENT.md` §5.1）。

第八轮的 spec 也明确把这件事留给了独立一轮（`docs/superpowers/specs/2026-10-24-round8-quality-hardening-design.md` §2.2 第一行）：

> 要真正降下来必须把 Element Plus 改成按需引入……留独立一轮按「构建链改造 → 测试全量复核 → 体积对比」推进。

这一轮就是那一轮。本轮不动业务逻辑，只动**构建链、静态传输层**和一条 SQL 通配符转义。

现状数字（`db16214`，`cd frontend && MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build`，3.80s）：

| 产物 | 字节 | Vite 报告 | 说明 |
| --- | --- | --- | --- |
| `dist/assets/index-Dk6GNTfn.js` | 878,120 | 878.12 kB | 入口：Element Plus 全量 + 应用代码 + pinia |
| `dist/assets/index-7FnP3hU_.css` | 369,967 | 369.97 kB | 入口 CSS：Element Plus 全量 `dist/index.css` |
| `dist/assets/_plugin-vue_export-helper-DNLUZkv0.js` | 211,977 | 211.97 kB | 共享 chunk：Vue 运行时 + axios 等 |
| `dist/assets/markdown-JxXqY1xQ.js` | 195,465 | 195.46 kB | 懒加载：markdown-it + DOMPurify |
| 其余 16 个视图 chunk | 1,659–9,224 | 1.66–9.22 kB | 11 条路由全部 `() => import()` 懒加载 |
| JS 合计（19 个 chunk） | 1,344,673 | 1,344.67 kB | — |

> Vite 8 的体积口径是 `bytes / 1000`（878,120 B → 878.12 kB）。本轮的预算脚本与文档表格一律用同一口径，避免出现「878 kB vs 857 KiB」的争论。

入口 chunk 为什么这么大：`frontend/src/main.js:3` `import ElementPlus from 'element-plus'` + `:23` `app.use(ElementPlus, { locale: zhCn })` 把 60 多个组件**全量注册**进应用，Rollup 无法摇掉任何一个；`:4` 又整体引入了 `element-plus/dist/index.css`（370 kB，全部组件样式）。模板里实际只用到 36 种 `el-*`：

| 组件 | 次数 | 组件 | 次数 | 组件 | 次数 |
| --- | --- | --- | --- | --- | --- |
| el-button | 44 | el-form-item | 20 | el-tag | 16 |
| el-table-column | 14 | el-card | 13 | el-icon | 11 |
| el-empty | 11 | el-pagination | 9 | el-tab-pane | 7 |
| el-form | 6 | el-dropdown-item | 4 | el-tabs / el-radio | 3 |
| el-table / el-select / el-input-number / el-dropdown-menu / el-dropdown / el-dialog | 2 | el-input | 20 | el-radio-group / el-option / el-main / el-header / el-footer / el-divider / el-container / el-badge / el-avatar / el-alert | 1 |

（`el-*` 计数用 `grep -o` 逐文件统计，去重后 36 种；图标已经按文件具名导入，`utils/markdown.js` 的 highlight.js 也已经用 `lib/core` + 11 种语言做过 tree-shake，两者本轮都不动。）

目标（可验收）：

1. 入口 JS 从 878.12 kB 降到 **≤ 150 kB（实测 84.43 kB）**，`index-*.css` 从 369.97 kB 降到 **≤ 70 kB（实测 39.02 kB）**，最大 chunk **≤ 300 kB（实测 233.74 kB）**，Vite 构建**不再输出 `chunks larger than 500 kB` 警告**。（R1、R2、R4）
2. 按需引入不引入「组件解析失败」的新坑：`vite.config.js` 必须是**双模式工厂函数**——`mode === 'test'` 时不挂 `unplugin-vue-components`（否则模板 `el-*` 不再命中既有 `global.stubs`，实测 **63 个用例失败 / 7 个 spec 文件**），构建/开发期才挂；新增 `src/plugins/elementAutoImport.spec.js` 2 例**源码/配置断言**（不 mount），把「main.js 无全量注册 + 有 v-loading 指令 + 4 条编程式样式」「构建期 2 个插件、测试期 1 个插件」「App.vue 有 el-config-provider」钉住；`v-loading`（4 个视图）由 `ElementPlusResolver` 指令解析保证。（R3）
3. 构建产物有可回归的预算门禁（入口 JS / 入口 CSS / 单个 chunk / CSS 总量 / JS 总量**五条**），另加 **9 个关键 CSS 选择器覆盖断言**（`.el-button`、`.el-dialog`、`.el-table`、`.el-message-box`、`.el-notification`、`.el-pagination`、`.el-loading-mask`、`.el-form`、`.el-select`，防止按需样式漏装）；`pnpm check:bundle` 可在本地跑，Jenkins 在 `pnpm build` 之后自动执行；超限时退出码 1 并打印明细。（R4）
4. 静态资源响应头变成 `Cache-Control: public, max-age=31536000, immutable`（**只有一条** `Cache-Control`），`index.html` 与 SPA 回退变成 `Cache-Control: no-cache`；`deploy/deploy.sh` 冒烟自动断言这两个头。（R5）
5. `GET /api/tags?keyword=` 的 `%`、`_`、`!` 按字面量匹配，与首页 `GET /api/posts?keyword=`、`GET /api/posts/search` 的语义一致（T17）。（R6）
6. 用户可见功能与交互**零变化**：界面语言仍是中文（包括 `ElMessageBox` 的「确定/取消」按钮）、主题切换、分页、通知、SSE 行为都不变。（R2）
7. 全量测试、构建、上线冒烟与文档回填完成，基线抬到后端 21 类 / **177** 例、前端 **32** spec / **323** 例、端点 39 个不变。（R7）

## 2. 范围

### 2.1 本轮做

| 项 | 内容 | 主要落点 | 新增测试 |
| --- | --- | --- | --- |
| R1 | Element Plus 改为构建期按需引入：新增 devDependency `unplugin-vue-components@^32`，`vite.config.js` 改成工厂函数 `defineConfig(({ mode }) => …)`，只在非 `test` 模式挂 `Components({ resolvers: [ElementPlusResolver({ importStyle: 'css' })], dirs: [], dts: false })`；`main.js` 删掉全量 `import ElementPlus` 与 `dist/index.css` | `frontend/package.json`、`frontend/pnpm-lock.yaml`、`frontend/vite.config.js`、`frontend/src/main.js` | 0 |
| R2 | 样式与本地化：保留 `theme-chalk/base.css`（CSS 变量）+ `dark/css-vars.css`，补 4 条编程式/指令样式（`message`、`message-box`、`notification`、`loading`）；`main.js` 保留 `import { ElLoading }` 与 `app.directive('loading', ElLoading.directive)`；locale 从 `app.use(ElementPlus, { locale })` 改为 `App.vue` 根部的 `<el-config-provider :locale="zhCn">`；3 处 `ElMessageBox.confirm` 显式给中文按钮文案（`confirmButtonText`/`cancelButtonText`/`distinguishCancelAndClose`） | `frontend/src/main.js`、`frontend/src/App.vue`、`frontend/src/views/AdminView.vue`、`frontend/src/views/PostDetailView.vue` | 0 |
| R3 | 守护测试与既有测试同步：新增 `src/plugins/elementAutoImport.spec.js`（2 例源码/配置断言，不 mount、不建探针 SFC）；`src/styles/tokens.spec.js` 的样式顺序清单从 8 条更新为 12 条（用例数不变） | `frontend/src/plugins/elementAutoImport.spec.js`（新）、`frontend/src/styles/tokens.spec.js` | +2（前端） |
| R4 | 产物预算：`frontend/scripts/check-bundle.mjs`（Node ESM，读 `dist/assets`，`--dist` 可指向历史产物；5 项体积预算 + 9 个 CSS 选择器覆盖）、`package.json` 加 `check:bundle`、`Jenkinsfile` Build Frontend 阶段 build 后追加检查 | `frontend/scripts/check-bundle.mjs`（新）、`frontend/package.json`、`Jenkinsfile` | 0（脚本自证 RED/GREEN） |
| R5 | 传输层缓存：`/forum/assets/` 改 `Cache-Control: public, max-age=31536000, immutable`（删 `expires 7d`），新增 `location = /forum/index.html` 给 `no-cache`；`deploy/deploy.sh` 冒烟加两个 `curl -sI` 断言 | `deploy/nginx/forum.conf`、`deploy/deploy.sh`、`docs/deployment/README.md` | 0（curl 断言） |
| R6 | T17：`TagRepository.findWithPostCounts` 加 `ESCAPE '!'`，`TagService.list` 先 `escapeLike` 再进查询 | `src/main/java/org/song/forum/repository/TagRepository.java`、`src/main/java/org/song/forum/service/TagService.java`、`src/test/java/org/song/forum/controller/TagFlowIntegrationTest.java` | +1（后端） |
| R7 | 全量验证 + README / DEVELOPMENT §4.14 / 部署手册更新 + 上线（#20）与回填 | 文档、Jenkins、raspi 冒烟 | 0 |

合计：后端 +1 用例（176 → 177），测试类数不变（21）；前端 +2 用例（321 → 323）、+1 spec 文件（31 → 32）；端点 39 个不变；无数据库迁移；新增 1 个 devDependency（`unplugin-vue-components`）。

### 2.2 本轮不做（附理由）

| 不做的事 | 为什么不做 |
| --- | --- |
| `unplugin-auto-import`（自动导入 `ref`/`ElMessage` 等 API） | 它会连带改写所有 `.vue` 的 import 习惯，并且需要 `ElMessage` 这类**编程式 API 的样式**继续手工引入——本轮真正要解决的是组件与 CSS 体积，自动导入非必需，反而让「哪些样式必须手引」更难讲清。一次只加一个构建期依赖。 |
| 手工 `manualChunks` 精细分包（把 element-plus 拆成独立 vendor chunk） | 按需引入后入口已经不再是「一个 878 kB 的巨块」，此时再手工拆 vendor 只会增加请求数、收益不可测。本轮把「不超标」交给预算脚本守住；真需要再拆时有数据可依。 |
| SSR / PWA / CDN / 图片优化（webp、`srcset`、懒加载） | 内网部署、无公网、无移动端 KPI；SSR 要引入 Node 运行时与两套入口，PWA 要 Service Worker 与缓存策略治理，图片优化要先有真实图片流量。都不属「压掉 878 kB 入口」这个目标。 |
| markdown-it / highlight.js 的进一步拆分 | 它们已经在懒加载 chunk 里（195.46 kB），只在帖子详情与编辑页用到；再拆是「按语言拆 highlight」这种细粒度优化，与入口体积无关。 |
| 路由级 prefetch / 预加载策略 | 本轮不改用户感知的导航行为；prefetch 会让「首屏请求数」这种可观测指标变复杂，需要单独设计。 |
| brotli / 预压缩 `.gz` / HTTP/2 | nginx 现在是 `listen 80` + 动态 gzip（`/etc/nginx/nginx.conf` 的 `gzip on; gzip_vary on`）；brotli 要装模块，HTTP/2 要证书与端口改造，属主机层面的独立变更。 |
| 迁 TypeScript / 换组件库 / 重写样式体系 | 与目标无关，收益不可验证，风险最大。 |
| 后端重构（除 T17 那 11 行） | 本轮是前端性能轮；T17 只是因为「同一处 LIKE 约定」顺手收尾，改动限制在 2 个文件 + 1 个测试。 |
| E2E 框架（T3） | 仍需浏览器二进制与 CI 集成，工作量大于本轮全部改动之和（第八轮已判过）。 |

## 3. 现状审计（file:line）

审计基线 `db16214`，逐条核对过源码（行号即当前文件行号）。

| # | 现象 | 位置 | 后果 | 本轮动作 |
| --- | --- | --- | --- | --- |
| 1 | 全量注册 Element Plus | `frontend/src/main.js:3`（`import ElementPlus`）、`:23`（`app.use(ElementPlus, { locale: zhCn })`） | 60+ 组件全部进入入口 chunk，Rollup 无法摇树；首屏要下载用不到的组件代码 | 删掉全量注册，改构建期解析（R1） |
| 2 | 全量引入组件样式 | `frontend/src/main.js:4`（`import 'element-plus/dist/index.css'`） | 入口 CSS 369.97 kB，其中大半是当前路由根本不渲染的组件样式 | 改 `importStyle: 'css'` + 仅保留 base/暗色/编程式样式（R2） |
| 3 | 构建链只有 vue 插件 | `frontend/vite.config.js:23`（`plugins: [vue()]`） | 没有解析 `el-*` 的手段，模板里的组件只能靠全局注册 | 改工厂函数 + `Components()`（R1） |
| 4 | 本地化挂在 `app.use()` 上 | `frontend/src/main.js:6`、`:23` | 去掉全量注册后 `locale` 无处可挂；`ElMessageBox` 是独立 app 实例渲染，拿不到组件树上的 provider | `<el-config-provider>` + 3 处显式按钮文案（R2、§7） |
| 5 | 编程式 API 的样式无人管 | `src/App.vue:4`、`src/api/index.js`、`src/views/{AdminView,PostDetailView,LoginView,NotificationsView,PostEditView,ProfileView,RegisterView}.vue`、`src/composables/useNotificationStream.js` | 按需引入只覆盖**模板里出现的**组件；`ElMessage/ElMessageBox/ElNotification` 不出现在模板中，样式会丢（消息框裸奔） | `main.js` 手引 `message`/`message-box`/`notification`/`loading` 四条 `style/css`（R2） |
| 6 | 测试无法发现「插件没生效」 | `frontend/src/**/*.spec.js`（15 个 spec 用 `global.stubs`，`TagsView.spec.js` 还整体 mock 了 `element-plus`） | 现有 321 例全部靠 stub/mock 绕开真实组件，把 `Components()` 删掉也不会有一例变红；而测试期若真的挂上插件，实测 63 例 / 7 个 spec 反而因 stub 不命中而红 | 新增 2 例源码/配置断言 spec（R3、§8） |
| 7 | 样式导入顺序被测试锁死 | `frontend/src/styles/tokens.spec.js:106-125`（`order` 数组从 `:108` 开始共 8 条，`:109` 是 `element-plus/dist/index.css`） | 改 `main.js` 必然让这条用例变红；不改测试就得留着 370 kB 的全量 CSS | 同步改成 12 条（base、dark、4 条程序式、6 条本地）（R3） |
| 8 | 没有体积门禁 | `frontend/package.json:6-12`（scripts 只有 dev/build/preview/test） | 体积只能靠人看构建日志；本轮优化后下一次改动可以悄悄涨回去 | 新增 `scripts/check-bundle.mjs`（R4） |
| 9 | CI 不检查体积 | `Jenkinsfile:72-81`（Build Frontend 只 `install` + `build`） | 产物回涨、按需样式漏装都能一路绿灯发布 | build 之后追加 `pnpm check:bundle`（R4） |
| 10 | 静态资源只缓存 7 天且非 immutable | `deploy/nginx/forum.conf:15-19`（`expires 7d`，实测响应 `Cache-Control: max-age=604800`） | 资源名带内容 hash，本可以永久缓存；7 天一次回源校验是纯浪费（弱网/离线更明显） | 改 `add_header … immutable`（R5） |
| 11 | `index.html` 没有任何 `Cache-Control` | `deploy/nginx/forum.conf:32-36`（SPA fallback 直接 `try_files … /forum/index.html`） | 浏览器用启发式缓存（`Last-Modified` + `ETag`）自行决定新鲜期，新版本发布后可能拿着旧 html 去找已删除的旧 hash → 白屏到缓存过期 | 新增 `location = /forum/index.html` 给 `no-cache`（R5） |
| 12 | 标签关键词 LIKE 未转义 | `src/main/java/org/song/forum/repository/TagRepository.java:23`（`LIKE LOWER(CONCAT('%', :keyword, '%'))`，无 `ESCAPE`）、`src/main/java/org/song/forum/service/TagService.java:22`（`kw` 未转义） | 搜「a_b」命中「axb」、搜「100%」命中「1000」；与首页/搜索接口语义分叉（T17，`docs/DEVELOPMENT.md:687`） | `escapeLike` + `ESCAPE '!'`（R6） |
| 13 | 文档仍写着「体积警告是预期」 | `docs/DEVELOPMENT.md:718`（§6 已知问题）、`README.md:289`（8 条样式导入） | 优化完成后文档会与事实相反 | Task 5 回填（R7） |

## 4. 方案总览

### 4.1 构建与运行时的数据流（改造后）

```
.vue 模板里的 <el-button> / v-loading
        │
        ▼  （Vite transform 阶段，unplugin-vue-components）
import { ElButton } from 'element-plus/es'
import 'element-plus/es/components/button/style/css'   ← 样式随组件走
        │
        ▼  （Rollup）
入口 chunk 只含「首屏用到的组件」；其余组件随各自的懒加载视图进 view chunk
```

四类「编译器看不见」的东西留在 `main.js` 手写：

1. `element-plus/theme-chalk/base.css`：`:root` 变量（7856 B），`styles/tokens.css` 覆盖 `--el-color-primary` 依赖它先加载。
2. `element-plus/theme-chalk/dark/css-vars.css`：`html.dark` 变量（2946 B），主题切换依赖它。
3. `element-plus/es/components/{message,message-box,notification}/style/css`：编程式 API 的样式（每个文件内部还会带上 base / button / overlay / progress 等依赖样式）。
4. `import { ElLoading }` + `element-plus/es/components/loading/style/css` + `app.directive('loading', ElLoading.directive)`：`v-loading` 指令在 4 个视图里用；指令本身由 resolver 解析，但这里同时要显式注册指令与样式，保证首屏之外（如错误态）也不丢 `el-loading-mask` 外观。

### 4.2 变更前后对照

| 维度 | 改造前（`db16214`） | 改造后（实测） |
| --- | --- | --- |
| 组件注册 | `app.use(ElementPlus)` 全量 | 构建期按模板解析、按需 import；**测试期不挂插件**（保住既有 stub） |
| 入口 JS | 878.12 kB | ≤ 150 kB（实测 84.43 kB，gzip 31.68 kB） |
| 入口 CSS | 369.97 kB | ≤ 70 kB（实测 39.02 kB，gzip 7.15 kB） |
| 最大 chunk | 878.12 kB（Vite 告警） | ≤ 300 kB（实测 233.74 kB，**警告消失**） |
| JS 总量 | 1,344.67 kB | ≤ 950 kB（实测 835.62 kB） |
| CSS 总量 | 381.75 kB | ≤ 260 kB（实测 181.57 kB） |
| CSS 覆盖 | 无 | 9 个关键选择器必须在合并后的 CSS 里出现 |
| 语言包 | `app.use(ElementPlus, { locale })` | `<el-config-provider :locale="zhCn">` + MessageBox 显式文案 |
| 体积门禁 | 无 | `pnpm check:bundle`（5 条预算 + 覆盖断言）+ Jenkins |
| 资源缓存 | `expires 7d` | `public, max-age=31536000, immutable`（单条） |
| 入口页缓存 | 无 `Cache-Control` | `no-cache`（`/forum/` 与 `/forum/xxx` 都命中） |
| 标签搜索 | `%`/`_` 当通配符 | 字面量匹配（T17） |

### 4.3 本轮改动文件树

```
frontend/
├── package.json                       # M: devDep unplugin-vue-components, scripts.check:bundle
├── pnpm-lock.yaml                     # M: 必须与 package.json 同一提交（Jenkins frozen-lockfile）
├── vite.config.js                     # M: 工厂函数；构建期 Components()（dirs: []、dts: false），test 模式不挂
├── scripts/check-bundle.mjs           # A: 产物预算脚本（5 条预算 + 9 个 CSS 选择器覆盖）
└── src/
    ├── main.js                        # M: 删全量 EP，保留 base/dark + message/message-box/notification/loading 4 条程序式样式 + ElLoading 指令
    ├── App.vue                        # M: 根部 <el-config-provider :locale="zhCn">
    ├── plugins/elementAutoImport.spec.js # A: 2 例源码/配置断言（不 mount）
    ├── styles/tokens.spec.js          # M: 样式顺序清单 8 → 12 条
    └── views/
        ├── AdminView.vue              # M: MessageBox 显式中文按钮
        └── PostDetailView.vue         # M: MessageBox 显式中文按钮（2 处）
deploy/
├── nginx/forum.conf                   # M: assets immutable + index.html no-cache
└── deploy.sh                          # M: 冒烟加 Cache-Control 断言
Jenkinsfile                            # M: build 后 pnpm check:bundle
src/main/java/org/song/forum/
├── repository/TagRepository.java      # M: ESCAPE '!'
└── service/TagService.java            # M: escapeLike
src/test/java/org/song/forum/controller/TagFlowIntegrationTest.java  # M: +1 例（@Order(9)）
docs/superpowers/{specs,plans}/2026-10-25-round9-*  # 第 0 次提交由 Lead 入库；Task 5 只回填 README/DEVELOPMENT/部署手册
```

## 5. R1 决策：用 `unplugin-vue-components` 做构建期解析，而不是手写注册

决策：引入 `unplugin-vue-components@^32` 作为 **devDependency**，在 `vite.config.js` 里用**工厂函数**按 `mode` 分支：`mode === 'test'` 时不挂插件，其余模式挂 `Components({ resolvers: [ElementPlusResolver({ importStyle: 'css' })], dirs: [], dts: false })`；`main.js` 不再 `import ElementPlus`。

候选方案：

| 方案 | 入口 JS | 维护成本 | 漏注册的后果 | 结论 |
| --- | --- | --- | --- | --- |
| 保持 `app.use(ElementPlus)`（现状） | 878.12 kB | 0 | — | 与目标矛盾 |
| 手写 `app.component('ElButton', ElButton)` × 36 | 与按需相近 | 36 行 import + 36 行注册，新增组件必须记得加 | 模板里写了未注册的组件 → **运行时静默降级**成未知元素（白屏/错版），只有 `console.warn` | 不做：把「编译器知道的事」交给人手维护，最容易漏 |
| 每个视图局部 `components: { ElButton, … }` | 与按需相近 | 36 组件 × 11 视图的局部注册表 | 同上，且漏的地方分散在 11 个文件 | 不做 |
| `unplugin-vue-components` 构建期解析（测试期关闭） | 实测 84.43 kB | `vite.config.js` 4 行 + 1 个 `mode` 判断 | 插件被误删时由 `elementAutoImport.spec.js` 的配置断言 + `check:bundle` 的 CSS 覆盖立刻发现 | **选它** |

理由：

1. **单一事实来源**：模板里写了什么就 import 什么，不存在「注册表」与「模板」两处需要同步。
2. **失败可观测**：插件失效不是「悄悄变慢」——`elementAutoImport.spec.js` 断言 `production` 配置恰好 2 个插件、`test` 配置恰好 1 个，`check:bundle` 再断言 9 个关键选择器出现在产物 CSS 里。
3. **与 Element Plus 官方 vite 用法一致**：`ElementPlusResolver` 还顺带处理 `v-loading` 指令（4 个视图在用），不需要额外开 `directives`（`directives` 默认值就是「resolvers 里有 directive 类型则开启」）。
4. **只加一个依赖**：不引入 `unplugin-auto-import`（见 §2.2），不动 babel/eslint 配置。`dirs: []` 显式关闭 `src/components` 目录扫描——仓库自有组件全部显式 `import`，扫描不改变解析结果，只会把全部 SFC 多注册一遍。

### 5.1 `frontend/vite.config.js`（改动部分）

```js
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// 工厂函数：vitest 传入 mode: 'test'（不要用 process.env.NODE_ENV 判断）
export default defineConfig(({ mode }) => ({
  base: basePath,
  plugins: [
    vue(),
    // vitest 下保持「不解析组件」：测试用 global.stubs 拦截 el-* 标签；
    // 若测试期也注入组件，stub 不再命中，既有 321 个用例会大面积失效（实测 63 例 / 7 spec）。
    ...(mode === 'test'
      ? []
      : [Components({
          resolvers: [ElementPlusResolver({ importStyle: 'css' })],
          dirs: [], // 不扫描 src/components：自有组件全部显式 import
          dts: false // 仓库没有 TypeScript，不生成 components.d.ts
        })])
  ],
  // …其余（resolve.alias / server.proxy / test）保持不变
}))
```

> 测试期关闭插件不是可选优化，而是**前提**：既有 31 个 spec 用 `global.stubs` 拦截 `el-*` 标签，测试里根本没有真实组件；若测试期也挂 `Components()`，这些 stub 不再命中，实测 63 例失败、7 个 spec 文件变红。代价是单测不覆盖构建期解析链——由 `elementAutoImport.spec.js`（源码/配置断言）+ 构建后 `check:bundle`（体积 + CSS 覆盖）+ 部署冒烟共同守护（§13 风险）。

### 5.2 `frontend/src/main.js`（全量替换）

```js
import { createApp } from 'vue'
import { createPinia } from 'pinia'
// Element Plus 的组件由 unplugin-vue-components 在编译期按需注入（见 vite.config.js）。
// 这里只保留「编译器看不见」的东西：基础 CSS 变量、暗色变量、编程式 API 与指令的样式。
import { ElLoading } from 'element-plus'
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
// 编程式 API 不出现在模板里，插件解析不到，必须手工引样式。清单与源码一一对应：
//   ElMessage      → src/App.vue、src/api/index.js 与 7 个视图
//   ElMessageBox   → views/AdminView.vue、views/PostDetailView.vue
//   ElNotification → composables/useNotificationStream.js
//   ElLoading      → v-loading 指令（4 个视图）
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
// v-loading 指令：resolver 负责模板里的解析；这里显式注册一次，保证任何未被编译期解析到的
// v-loading 也有兜底实现（实测构建与运行正常，样式由上面的 loading/style/css 提供）。
app.directive('loading', ElLoading.directive)
// 语言包不再在这里注入：改成 App.vue 根部的 <el-config-provider :locale="zhCn">。
// 按需引入后没有 app.use(ElementPlus)，全局配置唯一的落点就是组件树。
app.mount('#app')
```

样式导入顺序（`tokens.spec.js` 同步成这 12 条）：`base.css` → `dark/css-vars.css` → `message` → `message-box` → `notification` → `loading` → `tokens.css` → `common.css` → `markdown.css` → `github.css` → `highlight-dark.css` → `style.css`。

## 6. R2 决策：`importStyle: 'css'`，样式只留「base + 暗色 + 编程式」

决策：`ElementPlusResolver({ importStyle: 'css' })`，`main.js` 保留 `theme-chalk/base.css`、`theme-chalk/dark/css-vars.css` 和 4 条编程式/指令样式（`message`、`message-box`、`notification`、`loading`），删掉 `element-plus/dist/index.css`。

候选方案：

| 方案 | CSS 体积 | 额外依赖 | 风险 | 结论 |
| --- | --- | --- | --- | --- |
| 保留 `dist/index.css`（现状 370 kB）+ 组件按需 | 370 kB 不变 | 无 | 目标「入口 CSS ≤ 70 kB」无法达成 | 不做 |
| `importStyle: 'sass'`（编译 `theme-chalk/src`） | 最小（只含用到的） | 需要 `sass` | 增加一条样式编译链、构建变慢、版本升级要跟着调 | 不做 |
| `importStyle: 'css'`（复用 `theme-chalk/el-*.css`） | ≈ 用到的组件之和 | 无 | 组件样式文件粒度比 sass 粗一点点（可忽略） | **选它** |
| `importStyle: false` + 自己手写样式 | 最小 | 无 | 等于自研组件库样式，用户可见样式必然漂移 | 不做 |

为什么 `base.css` 必须显式留一份：

- `theme-chalk/base.css`（7856 B，单行）定义了全部 `:root` CSS 变量（`--el-color-primary-rgb`、字号阶梯等）。`styles/tokens.css` 里的 `--el-color-primary` 覆盖依赖这组变量先存在；顺序错了主题色会失效（`tokens.spec.js` 正是为此存在）。
- 每个组件的 `style/css` 内部都会 `import 'element-plus/theme-chalk/base.css'`，所以即使不显式写也不会丢；显式写只是把「变量来源」摆在入口，重复导入由 Vite 去重。

为什么编程式样式必须显式引：

| 编程式 API | 出现位置 | 需要的样式入口 |
| --- | --- | --- |
| `ElMessage` | `src/App.vue:4`、`src/api/index.js`、`AdminView`、`LoginView`、`NotificationsView`、`PostDetailView`、`PostEditView`、`ProfileView`、`RegisterView` | `element-plus/es/components/message/style/css`（内部含 base + badge + `el-message.css`） |
| `ElMessageBox` | `AdminView.vue:59`、`PostDetailView.vue:138`、`:167` | `element-plus/es/components/message-box/style/css`（base + input + button + overlay + `el-message-box.css`） |
| `ElNotification` | `composables/useNotificationStream.js` | `element-plus/es/components/notification/style/css`（base + progress + `el-notification.css`） |
| `ElLoading` | `main.js` 的 `import { ElLoading }` 与 `app.directive('loading', ElLoading.directive)`（实测保留；`v-loading` 指令在 4 个视图） | `element-plus/es/components/loading/style/css`（`el-loading-mask` 外观） |
| `v-loading` 指令 | `HomeView.vue:136`、`SearchView.vue:137`、`TagPostsView.vue:89`、`TagsView.vue:73` | 由 `ElementPlusResolver` 自动解析 + `main.js` 显式注册兜底 |

> 清单是 `grep -rn "ElMessage\|ElMessageBox\|ElNotification" frontend/src` 的实测结果（已排除 `*.spec.js`）。EP 的 `package.json` `sideEffects` 里包含 `es/components/*/style/*`，所以这些样式 import **不会**被 Rollup 当成无用副作用摇掉。

## 7. R3 决策：本地化改 `<el-config-provider>`，`ElMessageBox` 显式给中文按钮

决策：`App.vue` 模板根部包一层 `<el-config-provider :locale="zhCn">`；三处 `ElMessageBox.confirm`（`AdminView.vue:59`、`PostDetailView.vue:138`、`:167`）显式传 `confirmButtonText: '确定'`、`cancelButtonText: '取消'`、`distinguishCancelAndClose: true`。

这是本轮**最容易被忽略的用户可见行为**，必须写清楚：

1. 按需引入后没有 `app.use(ElementPlus, { locale: zhCn })`，语言包唯一的落点是组件树，所以 `el-pagination`（「共 N 条」）、`el-empty`（「暂无数据」）、`el-tabs` 等模板内组件的文案要靠 `el-config-provider`。
2. 但 `ElMessageBox.confirm()` 是**函数调用**：EP 内部会 `createVNode(MessageBoxConstructor)` 后 `render(vnode, container)` 到一个独立的 app 实例上，**不继承**我们这棵组件树的 provide。它取 locale 的路径是模块级 globalConfig，而那份配置原本由 `app.use(ElementPlus)` 写入。去掉全量注册后，它读不到中文 → 按钮文案退回英文 `OK` / `Cancel`。
3. 结论：三处确认框显式给文案（这也是唯一不需要依赖 EP 内部 API 的做法）。文案与改造前完全一致，属「保持用户可见行为不变」，不是新功能。

候选方案：

| 方案 | 用户可见后果 | 改动面 | 结论 |
| --- | --- | --- | --- |
| 什么都不做，只加 `el-config-provider` | 3 个删除确认框按钮变英文（`OK`/`Cancel`） | 0 | 违反「零用户可见变化」 |
| 保留 `app.use(ElementPlus)` 只为设置 locale | 全量注册回来，体积优化白做 | 0 | 与目标矛盾 |
| 调用 EP 内部的 `provideGlobalConfig` | 行为最接近现状 | 依赖非公开 API（`element-plus/es/components/config-provider/src/hooks/use-global-config`），升级即碎 | 不做 |
| 3 处确认框显式传中文文案 | 与现状完全一致 | 3 个文件各加 2 行 | **选它** |

### 7.1 `frontend/src/App.vue`（改动两处）

```vue
<script setup>
// 新增：语言包随组件树提供（原来的 app.use(ElementPlus, { locale: zhCn }) 已移除）
import zhCn from 'element-plus/es/locale/lang/zh-cn'
// …原有 import 不变
</script>

<template>
  <el-config-provider :locale="zhCn">
    <el-container class="layout">
      <el-header class="header">…</el-header>
      <el-main class="main">
        <router-view :key="$route.fullPath" />
      </el-main>
      <el-footer class="footer">…</el-footer>
    </el-container>
  </el-config-provider>
</template>
```

### 7.2 `ElMessageBox.confirm` 三处（`AdminView.vue:59`、`PostDetailView.vue:138`、`:167`）

```js
await ElMessageBox.confirm(`确定删除板块「${b.name}」吗？（板块下有帖子时会被拒绝）`, '提示', {
  type: 'warning',
  confirmButtonText: '确定',
  cancelButtonText: '取消',
  distinguishCancelAndClose: true
})
```

三处都按这个形状补三个字段，确认/取消的**判空逻辑不变**（`await` 抛出即视为取消）。

## 8. R4 决策：产物预算脚本 `check-bundle.mjs` + Jenkins 接入

决策：新增 `frontend/scripts/check-bundle.mjs`（Node ESM，零依赖），`package.json` 加 `"check:bundle": "node scripts/check-bundle.mjs"`，`Jenkinsfile` 的 Build Frontend 阶段在 `pnpm build` 之后追加 `pnpm check:bundle`。

### 8.1 五条预算 + CSS 覆盖断言

| 项 | 预算（字节 / 1000 口径） | 实测 | 说明 |
| --- | --- | --- | --- |
| 入口 JS（`index-*.js`） | ≤ 150,000 | 84,431 | 基线 878,120；硬上限，只许收紧 |
| 入口 CSS（`index-*.css`） | ≤ 70,000 | 39,019 | 基线 369,967 |
| 任一 JS chunk | ≤ 300,000 | 233,744（`_plugin-vue_export-helper`） | 低于 Vite 的 500 kB 告警线，不能出现「Vite 警告但脚本绿」 |
| CSS 总量（所有 css 求和） | ≤ 260,000 | 181,570 | 防「入口小了、组件样式却重复膨胀」 |
| JS 总量（所有 chunk 求和） | ≤ 950,000（**回归护栏**，非优化目标） | 835,623 | 基线 1,344,673；防「拆成很多小 chunk 但总量暴涨」 |
| CSS 覆盖 | 9 个关键选择器必须出现 | 9/9 | `.el-button`、`.el-dialog`、`.el-table`、`.el-message-box`、`.el-notification`、`.el-pagination`、`.el-loading-mask`、`.el-form`、`.el-select`；合并 `dist/assets/*.css` 后逐一 `includes`，防止按需样式漏装 |

单位一律 `bytes / 1000`（与 Vite 报告一致）。脚本支持 `--dist <dir>`，用于对**历史产物**复跑：

```bash
# Task 2 的自证：先对 Task 1 之前的基线产物证明 RED，再对 Task 1 的产物证明 GREEN
cd frontend
node scripts/check-bundle.mjs --dist E:/tmp/forum-dist-baseline   # 退出码 1（5 项超标）
node scripts/check-bundle.mjs                                     # 退出码 0
```

为什么把检查放在 Jenkins，而不是 pre-commit hook / 本地脚本自愿执行：

1. 仓库没有 husky 之类的钩子设施，也不该为一个检查引入第二个依赖。
2. Jenkins 是**唯一的必经门**：本地谁都可以不跑，但 `Build Frontend` 绿是部署的前置条件。
3. 放在 build 之后（而不是 Tests 阶段）：预算检查依赖 `dist/`，只有 build 完才有意义；且它不跑测试，失败信息必须一眼能看出「哪个文件、多少、超了多少」。

### 8.2 脚本行为（细节在计划 Task 2）

- 读 `dist/assets`，找不到目录直接报错（提示先 `pnpm build`），退出码 1。
- 定位入口文件：`index-*.js` 与 `index-*.css` 必须各恰好 1 个；多于/少于 1 个都视为失败（防止 Vite 改了命名规则后脚本静默变成「没检查」）。
- 输出一行一个检查项：`项 / 文件 / 实测 / 预算 / 结果`；JS chunk 只列最大的 3 个，避免刷屏。
- 合并所有 `dist/assets/*.css` 的文本，逐个 `includes` 9 个关键选择器；缺失的选择器单独打印并计入失败。
- 末尾汇总「✗ N 项超预算 / M 个选择器缺失」或「✓ 全部在预算内，CSS 覆盖 9/9」，退出码 0/1。

## 9. R5 决策：静态资源长缓存 + 入口页 `no-cache`

决策：`/forum/assets/` 去掉 `expires 7d`，只留 `add_header Cache-Control "public, max-age=31536000, immutable"`；新增 `location = /forum/index.html` 给 `add_header Cache-Control "no-cache"`；`deploy.sh` 冒烟加断言。

现状（2026-10-25 raspi 实测）：

| 请求 | 现状响应头 | 问题 |
| --- | --- | --- |
| `/forum/assets/index-*.js` | `Cache-Control: max-age=604800`（`expires 7d` 生成），带 `Content-Encoding: gzip` | 资源名里有内容 hash，内容永不变；7 天回源一次纯浪费 |
| `/forum/`（`index.html`） | **无 `Cache-Control`**，只有 `ETag` / `Last-Modified` | 浏览器走启发式缓存，可能长期用旧 html → 引用的旧 hash 已删除 → 白屏 |
| `/forum/posts/1`（SPA 回退） | 同上（回退到 `index.html`） | 同上 |

候选方案（assets）：

| 方案 | 响应头 | 问题 |
| --- | --- | --- |
| 保持 `expires 7d` | `Cache-Control: max-age=604800` | 目标 4 不达成 |
| `expires 1y` + `add_header Cache-Control "…immutable"` | **两条** `Cache-Control` | 语义非法：多个 `Cache-Control` 头在缓存/代理上的合并行为不一致（有的取第一个、有的取并集），不可控 |
| 只写 `add_header Cache-Control "public, max-age=31536000, immutable"`，删掉 `expires` | 单条 | **选它** |
| `expires -1`（反向：入口页） | `Cache-Control: no-cache` + `Expires: 过去时间` | 能表达 `no-cache`，但本轮统一用显式 `Cache-Control`，且 `add_header` 在这里没有继承副作用（见下） |

`index.html` 的写法为什么用精确匹配 location：

1. `location /forum/` 里的 `index index.html` 与 `try_files $uri $uri/ /forum/index.html` 都通过**内部重定向**到 `/forum/index.html`，所以 `location = /forum/index.html` 同时覆盖 `GET /forum/` 与 `GET /forum/xxx` 两条路径，不需要写两处。
2. 不把 `no-cache` 塞进 `location /forum/`：那样 `favicon.ico` 等其它直出文件也会带 `no-cache`，虽然无害但语义混乱；精确匹配表达的是「只有入口页不能缓存」。
3. nginx 的 `add_header` 有「本层写了就不继承上层」的规则，但 `/etc/nginx/conf.d/app.conf` 的 server 块与 `/etc/nginx/nginx.conf` 的 http 块**都没有任何 `add_header`**（2026-10-25 实测），所以新增 location 里的 `add_header` 不会顶掉任何既有安全头。

不改 `/forum/uploads/`：它的文件名由服务端生成、已有 `expires 7d` 与 `X-Content-Type-Options nosniff`，头像替换的语义（是否复用文件名声）不属于本轮范围。

### 9.1 `deploy/nginx/forum.conf`（改动）

```nginx
    # 静态资源：vite 产物文件名带内容 hash，可以长缓存。
    # 只写 add_header，不写 expires —— expires 也会生成一条 Cache-Control，
    # 两处叠加会让响应出现两个 Cache-Control 头（语义非法，中间层行为不一致）。
    location ^~ /forum/assets/ {
        alias /pd/Program/forum/frontend/assets/;
        add_header Cache-Control "public, max-age=31536000, immutable";
        access_log off;
    }
```

```nginx
    # 入口页：必须每次回源校验。index 与 try_files 的回退都会内部重定向到
    # /forum/index.html，因此这一个精确匹配同时覆盖 /forum/ 与 /forum/xxx。
    location = /forum/index.html {
        alias /pd/Program/forum/frontend/index.html;
        add_header Cache-Control "no-cache";
    }
```

### 9.2 冒烟断言（`deploy/deploy.sh` 的「冒烟测试」段）

```bash
asset_path="$(curl -fsS -H 'Host: raspi' "http://127.0.0.1/forum/" | grep -o '/forum/assets/[^"]*\.js' | head -1)"
[ -n "$asset_path" ] || die "/forum/ 首页没有引用任何 /forum/assets/*.js"
curl -sSI -H 'Host: raspi' "http://127.0.0.1$asset_path" | grep -qi '^cache-control: public, max-age=31536000, immutable' \
    || die "$asset_path 缺少 immutable 长缓存头（检查 nginx snippet 是否已 reload）"
curl -sSI -H 'Host: raspi' "http://127.0.0.1/forum/index.html" | grep -qi '^cache-control: no-cache' \
    || die "/forum/index.html 缺少 no-cache 头"
```

## 10. R6 决策：T17 放本轮，用「复制 escapeLike」而不是抽公共工具

决策：`TagRepository.findWithPostCounts` 的 LIKE 加 `ESCAPE '!'`，`TagService.list` 在进查询前调用 `escapeLike`（与 `PostService.escapeLike` 同样的 11 行实现，带注释说明出处）。

为什么放在本轮：它是第八轮 T13 的**同一处约定**的收尾（首页与 `/api/posts/search` 已转义，只剩标签检索），只需要 1 个 Task + 1 个集成用例，与本轮「顺手把已知小债清掉」的节奏一致；单独为它开一轮不值当。

为什么复制而不是抽出 `LikeEscaper` 工具类：

| 方案 | 改动面 | 风险 | 结论 |
| --- | --- | --- | --- |
| 复制 11 行到 `TagService` | 1 个文件 +11 行 | 无（不影响既有搜索路径） | **选它** |
| 新建 `util/LikeEscaper`，`PostService.escapeLike` 改为委托 | 3 个文件 | 触碰首页与搜索两条已上线路径，收益只是消 11 行重复 | 本轮不做（`docs/DEVELOPMENT.md` §5.2 可挂候选） |
| 在 Controller 层转义 | 2 个文件 | 转义属于「查询语义」，放 controller 会让下一个调用方再漏一次 | 不做 |

转义规则与 T13 完全一致：`!`、`%`、`_` 前加 `!`（`!` 自己先转义），JPQL 用 `ESCAPE '!'`。空关键词仍然是空串（` :keyword = '' ` 分支保持不变），否则 Hibernate 会把 `null` 绑成 `bytea` 导致 `lower(bytea) does not exist`。

### 10.1 `TagRepository.java`（改动部分）

```java
    @Query("""
            SELECT t.id, t.name, COUNT(p.id) FROM Post p JOIN p.tags t
            WHERE p.deleted = false
              AND (:keyword = '' OR LOWER(t.name) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '!')
            GROUP BY t.id, t.name
            """)
    List<Object[]> findWithPostCounts(@Param("keyword") String keyword);
```

### 10.2 `TagService.list`（改动部分）

```java
        String kw = (keyword == null || keyword.isBlank()) ? "" : keyword.trim();
        // T17：LIKE 通配符先转义再进 JPQL（与 PostService.escapeLike 同一约定，JPQL 里 ESCAPE '!'）
        kw = escapeLike(kw);
```

```java
    /**
     * 转义 LIKE 通配符：'!' 是 JPQL 里 ESCAPE 指定的转义符，所以它自己也要先转义。
     * 不转义时 '%'（如标签「100%」）会被当成任意串，搜「100%」会误命中「1000」。
     * 与 PostService.escapeLike 保持同一实现（本轮不抽公共工具，理由见 spec §10）。
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
```

## 11. R7 决策：本周期的验收与文档落点

- 文档与代码**分开提交**：本轮 7 次提交里的第 0 次是 Lead 单独入库的 spec + 计划；4 个代码 Task 各自独立可回滚（第 1–4 次），文档统一在 Task 5 记录「设计 → 实现 → 上线」的完整事实（第 6 次）、回填实测（第 7 次）。这一点与第八轮不同（第八轮文档随 Task 1 入库），原因见计划 §0.5 裁决点 1。
- `docs/DEVELOPMENT.md` 新建 **§4.14 第九轮**（与 §4.1–§4.13「一轮一节」的惯例一致），并同步：§1 的「已完成 N 轮」、§3 快照日期、§3.2 测试基线（21 类 / 177 例、32 spec / 323 例）、§5.1 的 T17 行（标记已关闭）、§6 的「chunk > 500 kB 警告」行（删除或改写）。
- `README.md`：「样式与设计令牌」段（`README.md:289`）里那 8 条样式导入顺序要改写成 12 条（base → dark → message → message-box → notification → loading → 6 条本地），并说明「base.css 必须最先，tokens.css 覆盖 `--el-color-primary` 依赖它先加载」。
- `docs/deployment/README.md`：§5 冒烟检查新增一节「静态资源缓存头（第九轮）验证」，给出 `curl -sI` 命令与期望值（必须带 `-H 'Host: raspi'`）。
- 上线后回填：构建号（#20）、提交哈希、实测体积（入口 JS/CSS、gzip、最大 chunk、JS 总量）、`pnpm check:bundle` 输出、冒烟结果、`/forum/` 与 `/forum/posts/1` 的 `Cache-Control` 实测值。

## 12. 测试策略

| 层 | 新增 | 文件 | RED 形态（先写测试再实现） | 守住什么 |
| --- | --- | --- | --- | --- |
| 前端单元（R3） | +2 例（新 spec，源码/配置断言） | `frontend/src/plugins/elementAutoImport.spec.js` | 改代码前：第一例 `expected '...' not to contain 'app.use(ElementPlus'`；第二例 `expected 1 to be 2`（production 只有 `vue()` 一个插件）；改完两例转绿 | 「main.js 无全量注册 + 有 v-loading 指令 + 4 条程序式样式」与「构建期 2 插件 / 测试期 1 插件 + App.vue 有 el-config-provider」 |
| 前端既有（R3） | 0 例变化（仅改内容） | `frontend/src/styles/tokens.spec.js` | 改完 `main.js` 不更新清单时：`element-plus/theme-chalk/base.css 未导入` 失败（`-1` 不大于 `-1`） | 样式顺序仍然是「base → 暗色 → 4 条程序式 → tokens → …」（12 条） |
| 后端集成（R6） | +1 例（`@Order(9)`） | `src/test/java/org/song/forum/controller/TagFlowIntegrationTest.java` | `expected: ["100%"] but was: ["100%", "1000"]`、`expected: ["a_b"] but was: ["a_b", "axb"]` | `%` / `_` / `!` 三个字符都按字面量匹配 |
| 产物预算（R4） | 0 例（自证） | `frontend/scripts/check-bundle.mjs` | 对基线产物跑：5 项超标、退出码 1；对 Task 1 产物跑：全部达标 + 覆盖 9/9、退出码 0 | 体积不会悄悄回涨、按需样式不会漏装 |
| 传输层（R5） | 0 例（curl 断言） | `deploy/deploy.sh` 冒烟 + 部署手册 | 改 nginx 前 `curl -sI` 看不到 `immutable` / `no-cache`；headers 断言让部署当场失败 | 缓存策略真的生效（而不是「配置写了但没 reload」） |

为什么守护测试只断言源码/配置、不再挂载真实 SFC：本轮 `vite.config.js` 是双模式的，**测试期根本不挂 `unplugin-vue-components`**（挂上会让 63 例 / 7 个 spec 变红），所以任何 `mount()` 探针在 vitest 里都拿不到真实组件，写了也是永久红。构建期解析链改由三件事共同证明：① `elementAutoImport.spec.js` 断言「生产 2 个插件、测试 1 个插件」「main.js 的入口形状」「App.vue 的 provider」；② Task 1 的真实 `pnpm build` + Task 2 的 `check:bundle`（体积 + 9 个 CSS 选择器覆盖）；③ 部署冒烟与人工回归（B.6）。这比「文本断言通过、运行时未知元素」的单点 mount 更完整。

测试计数（累计见计划 §8）：

- 后端 176 → **177**（新用例在 `TagFlowIntegrationTest` 里，`@Order(9)` 放最后，不影响 `@Order(2)`/`@Order(7)` 的 `containsExactly` 与 `totalElements()==3` 断言）。
- 前端 321 → **323**（+2），spec 文件 31 → **32**（+1）。
- HTTP 端点 39 个不变（本轮不新增/不改契约）。

## 13. 风险与回滚

| # | 风险 | 触发迹象 | 处置 |
| --- | --- | --- | --- |
| 1 | **按需插件渗进测试期**：`mode === 'test'` 判断写错或插件被无条件挂上，模板 `el-*` 不再命中 `global.stubs`，真实 EP 组件在 jsdom 里渲染并做 props 校验 | `pnpm test` 大面积变红（实测 63 例 / 7 个 spec），失败点与 stub 未命中、props 校验有关 | 工厂函数里 `mode === 'test' ? [] : [Components(...)]`；`elementAutoImport.spec.js` 断言 test 配置 1 个插件、production 2 个；**不要**用 `process.env.NODE_ENV` 代替 `mode`。若既有 spec 大于 12 个需要改动或总数不再是 323，停下来报告 Lead |
| 2 | 入口 chunk 压不到 150 kB | `check:bundle` 首次 GREEN 失败，实测入口 > 150 kB | 先排查 `dirs` 扫描、未懒加载的静态 import、重复依赖；把证据记下来报告 Lead，**不擅自放宽预算**（预算是硬上限，只许收紧） |
| 3 | CSS 顺序变化导致主题色或暗色失效 | 页面主色变默认蓝、暗色下部分区域仍亮色 | `tokens.spec.js` 的 12 条顺序 + 人工看首页/详情/后台三页；`base.css` 必须在 `tokens.css` 之前 |
| 4 | `ElMessageBox` 文案退回英文 | 删除确认框按钮显示 `OK`/`Cancel` | 3 处显式文案（§7.2，含 `distinguishCancelAndClose`）+ Task 1 人工检查（点一次删除确认，不真的删） |
| 5 | `v-loading` 指令未被解析 / 样式漏装 | 4 个列表页加载中没有任何遮罩，或遮罩没有样式 | `ElementPlusResolver` 自动解析 + `main.js` 显式 `app.directive('loading', ElLoading.directive)` + `loading/style/css`；`check:bundle` 断言 `.el-loading-mask` 出现在产物 CSS 里；人工回归 4 个列表页 |
| 6 | Jenkins `frozen-lockfile` 失败 | Build Frontend 红在 `pnpm install`，报 lockfile 不同步 | `pnpm-lock.yaml` 必须与 `package.json` 同一提交入库（Task 1 的 Files 里显式列出） |
| 7 | `check:bundle` 误伤：Vite 改了资源命名规则 | 脚本报「找不到 index-*.js」 | 脚本对「入口文件数量不为 1」显式失败并打印实际文件名，便于当场发现；这条失败信息本身就是设计的一部分 |
| 8 | 缓存头改动导致发布后用户看不到新版 | 用户反馈「还是旧界面」，但 `curl` 看到新 html | `index.html` 的 `no-cache` 只在**部署后的第一次请求**生效；已缓存旧 html 的浏览器最多再用一次旧页面（因为旧 html 里引用的旧 hash 文件已被 `rsync --delete` 删掉，会白屏一次）。缓解：`deploy.sh` 冒烟断言两个头；排障文档写明「强制刷新一次即可」 |
| 9 | 双 `Cache-Control` 头 | `curl -sI` 看到两条 `cache-control` | 只写 `add_header`、删掉 `expires`（§9）；`deploy.sh` 可加一条「`grep -ci '^cache-control:'` 必须为 1」的断言 |
| 10 | **测试期关闭组件解析**意味着单测不覆盖构建期解析链（取舍，非缺陷） | 插件哪天被误删 / `importStyle` 被改错，单测仍全绿 | 由 `elementAutoImport.spec.js`（源码 + 配置断言）+ Task 1 真实构建 + Task 2 `check:bundle`（体积 + 9 个 CSS 选择器覆盖）+ 部署冒烟共同守护；这是本轮明确接受的代价（见 §12 末段） |

回滚：

- 每个 Task 一次提交，**逐 Task 可 revert**：`git revert --no-edit <sha>`（Task 1 回滚会同时移除 devDependency，需要重跑 `pnpm install`）。
- nginx/冒烟（Task 3）回滚后必须重跑一次 `deploy.sh`（它会把 snippet 同步回 raspi 并 reload）。
- 预算脚本（Task 2）如果阈值定得过紧，改常量即可，不必 revert。

## 14. 上线验收（待回填）

1. Jenkins Build **#20** SUCCESS（Build Frontend 阶段出现 `Check bundle` 的输出且 `✓ 全部在预算内`）。
2. `VITE_BASE_PATH=/forum/ pnpm build` 的日志里**不再出现** `chunks larger than 500 kB` 警告。
3. 实测体积表回填（入口 JS/CSS、gzip 后、最大 chunk、JS 总量、CSS 总量、与基线的百分比、CSS 覆盖 9/9）。
4. 冒烟：`/forum/`、`/forum/posts/1`、`/forum/api/posts?page=0&size=1`、uploads 404 探针、SSE 401 探针全部通过。
5. 缓存头：`/forum/assets/*.js` → `cache-control: public, max-age=31536000, immutable`（且只有一条 `cache-control`）；`/forum/` 与 `/forum/posts/1` → `cache-control: no-cache`。
6. 人工回归（浏览器）：首页/详情/标签/搜索/发帖/后台 6 个页面的样式与改造前一致；删除确认框按钮是「确定/取消」；分页文案「共 N 条」；主题切换（含暗色）无异常；`v-loading` 在 4 个列表页正常出现。
7. 全量测试：`mvn -B clean test`（后端 21 类 / 177 例，1 例 POSIX 跳过）、`cd frontend && pnpm test`（32 spec / 323 例）。

## 15. 已知限制（本轮之后仍然存在）

1. **没有真实性能指标**：本轮只压字节数（下载量），没有 LCP/TBT/首屏时间等字段测量；内网 + 无移动端 KPI，暂不引入 Lighthouse CI。
2. **gzip 仍是动态压缩**：`/etc/nginx/nginx.conf` 的 `gzip on` 每次请求压缩，没有预压缩 `.gz` 与 brotli；`gzip_min_length` 只在 `location /static/` 里显式设过，`/forum/` 走 nginx 默认值（20 B），小文件也会被压缩（无害，但会多一点 CPU）。
3. **组件样式碎片化**：按需引入后，EP 组件的 CSS 分散在各 view chunk 的附属 css 里，懒加载路由首次进入会多一次小 css 请求（HTTP/1.1 下可能有排队）；入口 CSS 变小是净收益，但请求数略增。
4. **预算脚本只看体积与 CSS 选择器**：不检查请求数、不检查重复依赖、不检查「某个组件被意外打进入口」（后者靠 R3 的源码/配置断言、`check:bundle` 的 9 个选择器覆盖与人工 review）。
5. **`ElMessageBox` 文案是硬编码中文**：仓库本来就没有 i18n（`README.md` 已知限制），本轮把「确定/取消」显式写进 3 处，将来做多语言时这 3 处要和 `el-config-provider` 一起改。
6. **入口页白屏一次的窗口**：已经缓存旧 `index.html` 的浏览器在发布后首次访问可能白屏一次（旧 html 引用已删除的旧 hash），`no-cache` 只保证「下次不再发生」。
7. **uploads 缓存策略未细化**：仍是 `expires 7d`，头像替换后的缓存语义没有单独设计。

## 16. 计划与提交切分

| Task | 提交信息（逐字） | 主要产出 |
| --- | --- | --- |
| Task 0（Lead） | `docs: 新增第九轮前端性能优化设计与计划` | 本 spec + 计划先入库（执行者不重复提交） |
| Task 1 | `perf: 按需引入 Element Plus 并配置本地化提供者` | R1 + R2 + R3 |
| Task 2 | `perf: 增加产物预算检查并接入构建流程` | R4 |
| Task 3 | `perf: 静态资源长缓存与入口页禁用缓存` | R5 |
| Task 4 | `fix: 标签搜索转义 LIKE 通配符` | R6 |
| Task 5 | `docs: 记录第九轮前端性能优化（按需引入、产物预算与传输层）` | 文档 + 计划勾选 |
| Task 5 | `docs: 回填第九轮构建与冒烟验收` | #20 上线与实测回填 |

实现细节、逐字命令、完整代码与验收步骤见 `docs/superpowers/plans/2026-10-25-round9-frontend-performance.md`。
