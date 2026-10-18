# JavaForum 开发记录

> 本文件是项目的**单一事实来源（single source of truth）**：记录开发情况、当前进度、已知问题与后续计划。
> **每次开发（新功能、修复、优化、重构）结束前都必须更新本文件**，约定见 §0。
>
> 最近更新：2026-10-18 ｜ 文档版本：v1.1

---

## 0. 维护约定

每次开发收尾时按顺序检查以下几项，缺一不可：

| 步骤 | 要更新的内容 | 对应章节 |
| --- | --- | --- |
| 1 | 在第 8 节变更日志**最上方**追加一行（日期 / 类型 / 摘要 / 提交） | §8 |
| 2 | 若开发推进了阶段或轮次，更新进度总览表的状态与进度百分比 | §2 |
| 3 | 若代码规模、测试数量、接口数量、迁移脚本有变化，更新当前状态快照 | §3 |
| 4 | 若产生了新的待办、或完成/放弃了某条待办，更新待办清单 | §5 |
| 5 | 若引入了新的已知限制或修复了旧的，更新已知问题 | §6 |
| 6 | 若做了影响后续走向的技术决策，追加到决策记录 | §7 |

**数字纪律：** 本文件中的测试数量、文件数、行数必须与命令的实际输出一致，不得估算或沿用旧值。取值命令：

```bash
# 后端测试数与结果
mvn clean test
# 汇总：grep 'Tests run' target/surefire-reports/*.txt

# 前端测试数与结果
cd frontend && pnpm test --run
```

**变更日志自引用问题：** 提交前无法知道自己这次提交的哈希，所以变更日志里的「提交」列按以下二选一处理：

- 记录为 `本次提交`，提交后用一条单独的 `docs:` 提交回填真实哈希（本项目采用此方式）；
- 或先提交代码，再单独提交一条回填记录。

因此**变更日志行数会比 `git rev-list --count HEAD` 少 1~2 条**，属正常现象，不是漏记。

---

## 1. 项目概况

| 项 | 值 |
| --- | --- |
| 项目名称 | JavaForum |
| 定位 | 论坛网站 MVP（可运行、可演示、可继续迭代） |
| 根包名 | `org.song.forum` |
| 工作区 | `E:\JavaSpace\forum` |
| 仓库 | 本地 git（master 分支），提交者 宋欢 / 826353608@qq.com |
| 起点 | 2026-10-15 |
| 协作方式 | Vibe Coding，逐阶段/逐任务推进，每步确认后继续；每次修改自动提交 |

### 技术栈（均为实际在用的版本）

**后端**

| 组件 | 版本 / 说明 |
| --- | --- |
| Java | 17（`JAVA_HOME=C:\Program Files\Java\jdk-17.0.0.1`） |
| Maven | 3.9.11 |
| Spring Boot | 3.5.6（parent） |
| Spring Security | 6，JWT 无状态认证（`SessionCreationPolicy.STATELESS`，关闭 CSRF/表单登录/httpBasic） |
| Spring Data JPA | Hibernate，`ddl-auto: validate`（建表交给 Flyway） |
| PostgreSQL | 15.18（部署在 `raspi:5432`，库 `javaforum`，账号 `forum`） |
| Flyway | `flyway-core` + `flyway-database-postgresql` |
| JWT | jjwt 0.13.0（HS256，`JWT_SECRET` 至少 32 字节） |
| API 文档 | springdoc-openapi 2.8.13（`/swagger-ui.html`、`/v3/api-docs`） |
| 其他 | Lombok、Validation、DevTools（仅 dev profile） |

**前端**（`frontend/` 子目录）

| 组件 | 版本 |
| --- | --- |
| Vue | 3.5.42 |
| Vite | 8.3.0 |
| Pinia | 4.1.0 |
| Vue Router | 4.6.4 |
| Axios | 1.20.0 |
| Element Plus | 2.14.7（+ `@element-plus/icons-vue` 2.3.2） |
| Vitest | 5.0.4（+ `@vue/test-utils` 2.5.1、jsdom 30.1.3） |

**测试**

| 层 | 技术 |
| --- | --- |
| 后端 | JUnit 5 + Spring Boot Test + H2（`MODE=PostgreSQL`） |
| 前端 | Vitest + @vue/test-utils + jsdom |

---

## 2. 开发进度总览

### 2.1 阶段进度

| 阶段 | 内容 | 状态 | 完成日期 |
| --- | --- | --- | --- |
| 第 0 阶段 | 项目初始化（目录结构、pom、application.yml、主类、README） | ✅ 已完成 | 2026-10-15 |
| 第 1 阶段 | 数据层（11 张表 + 实体 + Repository + Flyway V1） | ✅ 已完成 | 2026-10-15 |
| 第 2 阶段 | 认证模块（注册/登录 + JWT + 个人资料） | ✅ 已完成 | 2026-10-15 |
| 第 3 阶段 | 板块与帖子（CRUD + 分页搜索 + 浏览量 + 软删除） | ✅ 已完成 | 2026-10-15 |
| 第 4 阶段 | 评论模块（评论/楼中楼 + 树形读取 + REPLY 通知） | ✅ 已完成 | 2026-10-15 |
| 第 5 阶段 | 权限与互动（ADMIN/MODERATOR + 置顶加精 + 点赞收藏 + 禁言封禁） | ✅ 已完成 | 2026-10-15 |
| 第 6 阶段 | Vue3 前端全页面 + PostgreSQL 联调修复 | ✅ 已完成 | 2026-10-15 |

**MVP 主体（第 0~6 阶段）已全部完成。**

### 2.2 迭代轮次

| 轮次 | 主题 | 任务数 | 状态 | 完成日期 |
| --- | --- | --- | --- | --- |
| 第一轮 | 体验迭代：视觉层级 + 响应式 + 通知闭环 | 13 | ✅ 已完成 | 2026-10-17 |
| 第二轮 | 优化：性能 + 安全 + 可维护性 | 7 | ✅ 已完成 | 2026-10-17 |
| 第三轮 | 待规划（候选见 §5.2） | — | ⬜ 未开始 | — |

**当前进度：MVP 100% ｜ 已完成 2 轮迭代 ｜ 共 50 个提交。**

---

## 3. 当前状态快照（2026-10-18）

### 3.1 代码规模

| 部分 | 文件数 | 行数 |
| --- | --- | --- |
| 后端主代码（`src/main/java`） | 66 | 2772 |
| 后端测试（`src/test/java`） | 8 | 1210 |
| 前端产品代码（`frontend/src`，不含 spec） | 23 | 2389 |
| 前端测试（`*.spec.js`） | 14 | 1637 |

后端分层（文件数）：`entity/` 12、`dto/` 16、`repository/` 9、`controller/` 7、`service/` 7、`security/` 5、`config/` 4、`util/` 3、`exception/` 2。

前端目录（文件数）：`views/` 13（8 个页面 + 5 个 spec）、`composables/` 4、`styles/` 4、`utils/` 4、`components/` 3、`stores/` 3、`api/` 2、`router/` 1。

### 3.2 测试基线

| 层 | 数量 | 结果 |
| --- | --- | --- |
| 后端 | **30** 个用例 / 8 个测试类 | 全绿 |
| 前端 | **102** 个用例 / 14 个 spec | 全绿 |
| 构建 | `pnpm build` | 通过 |

后端测试类分布：`ForumApplicationTests` 2、`AuthFlowIntegrationTest` 1、`PostFlowIntegrationTest` 4、`CommentFlowIntegrationTest` 6、`Stage5FlowIntegrationTest` 5、`RepositoryTests` 6、`JwtTokenProviderTest` 2、`NotificationServiceTest` 4。

前端 spec 分布：`usePostActions` 13、`AdminView` 11、`cards` 10、`notifications` 9、`format` 9、`HomeView` 8、`index`（api）7、`breakpoint` 7、`notificationLink` 7、`ProfileView` 6、`common` 5、`NotificationsView` 4、`useResponsive` 3、`PostDetailView` 3。

### 3.3 数据库

| 项 | 值 |
| --- | --- |
| 业务表 | 11 张：`users`、`roles`、`user_roles`、`boards`、`posts`、`comments`、`tags`、`post_tags`、`likes`、`favorites`、`notifications` |
| 迁移脚本 | `V1__init_schema.sql`（建表 + 角色种子）、`V2__notification_comment_id.sql`（通知表加 `comment_id`） |
| 审计字段 | 全部表含 `create_time`、`update_time`（由 `BaseEntity` + JPA Auditing 维护） |
| 软删除 | `posts.deleted`、`comments.deleted` |
| 默认管理员 | `admin` / `admin123456`（由 `DataInitializer` 幂等创建，角色 ADMIN+MODERATOR+USER） |

### 3.4 接口清单（31 个端点）

| 控制器 | 端点 |
| --- | --- |
| `HealthController` | `GET /api/health` |
| `AuthController` | `POST /api/auth/register`、`POST /api/auth/login`、`GET /api/auth/me`、`PUT /api/auth/profile`、`GET /api/auth/users/{id}` |
| `BoardController` | `GET /api/boards`、`GET /api/boards/{id}`、`POST /api/boards`、`PUT /api/boards/{id}`、`DELETE /api/boards/{id}` |
| `PostController` | `GET /api/posts`、`GET /api/posts/{id}`、`GET /api/posts/mine`、`POST /api/posts`、`PUT /api/posts/{id}`、`DELETE /api/posts/{id}`、`GET /api/posts/favorites/mine`、`POST /api/posts/{id}/like`、`POST /api/posts/{id}/favorite`、`PUT /api/posts/{id}/pin`、`PUT /api/posts/{id}/exquisite` |
| `CommentController` | `GET /api/posts/{postId}/comments`、`POST /api/posts/{postId}/comments`、`DELETE /api/posts/{postId}/comments/{id}` |
| `NotificationController` | `GET /api/notifications`、`GET /api/notifications/unread-count`、`POST /api/notifications/read-all`、`POST /api/notifications/{id}/read` |
| `AdminUserController` | `GET /api/admin/users`、`PUT /api/admin/users/{id}/status` |
| Actuator | `GET /actuator/health`（仅 ADMIN，仅暴露 health） |

### 3.5 前端页面

| 路由 | 页面 | 说明 |
| --- | --- | --- |
| `/` | `HomeView` | 板块导航 + 帖子列表 + 搜索 + 分页 + 点赞收藏 |
| `/login`、`/register` | `LoginView`、`RegisterView` | 认证 |
| `/posts/:id` | `PostDetailView` | 详情 + 评论树 + 楼中楼 + 评论锚点定位 + 版主操作 |
| `/posts/new`、`/posts/:id/edit` | `PostEditView` | 发帖 / 编辑 |
| `/profile` | `ProfileView` | 资料 / 我的帖子 / 我的收藏（三个 tab） |
| `/notifications` | `NotificationsView` | 通知列表 + 单条已读 + 点击跳转 |
| `/admin` | `AdminView` | 板块 CRUD + 用户禁言封禁（桌面表格 / 窄屏卡片双渲染） |

---

## 4. 已完成工作明细

### 4.1 第 0~6 阶段（MVP 主体）

| 提交 | 内容 |
| --- | --- |
| `6eecbdc` | 第 0+1 阶段：项目初始化与数据层 |
| `3bab6c6` | 第 2 阶段：认证模块（注册/登录 + JWT + 个人资料） |
| `b518a7e` | 第 3 阶段：板块与帖子（CRUD + 分页搜索 + 浏览量 + `PageResponse` 统一分页） |
| `a4e837f` | 第 4 阶段：评论模块（楼中楼 + 树形读取 + 软删除占位 + 计数联动 + REPLY 通知） |
| `0f057e9` | 第 5 阶段：权限与点赞收藏（角色体系 + 置顶加精 + 禁言封禁 + admin 种子） |
| `3bf7c62` | 第 6 阶段：Vue3 前端全页面 + 修复 PostgreSQL 无参列表 500 |

### 4.2 第一轮：体验迭代（13 个任务，2026-10-16 ~ 2026-10-17）

计划：`docs/superpowers/plans/2026-10-16-experience-iteration.md`
设计：`docs/superpowers/specs/2026-10-16-experience-iteration-design.md`

| 主题 | 内容 |
| --- | --- |
| 视觉层级 | 建立设计令牌层 `styles/tokens.css`（颜色/间距/圆角/字号），各视图接入令牌，移除硬编码魔数 |
| 响应式 | 768px 断点：首页板块横向滚动、列表元信息不溢出、评论缩进收窄、后台表格改卡片式 |
| 通知闭环 | 后端为通知补 `commentId`（V2 迁移），前端支持点击通知跳转到帖子并高亮定位具体评论 |
| 状态统一 | 通知未读数抽为 Pinia store（顶栏角标与列表同源）；点赞/收藏抽为 `usePostActions` 共享状态，跨列表与详情页一致 |
| 守卫测试 | `breakpoint.spec.js` 锁定断点数量/字面量；`common.spec.js` 锁定分页样式收敛 |

关键修复 `fe00d17`：详情页把共享互动状态**快照**进本地 `post` ref，RTT 竞态下列表与详情不一致且点按钮会误发「取消点赞」→ 改为 `computed` 实时读 `actions.stateFor(id)`。

### 4.3 第二轮：优化（7 个任务，2026-10-17）

计划：`docs/superpowers/plans/2026-10-17-optimization-round2.md`

| # | 主题 | 提交 | 内容 |
| --- | --- | --- | --- |
| — | 计划 | `0b2837d` | 基于真实日志与代码审计产出 7 任务计划 |
| 1 | 性能 | `1b66bf7` | 帖子/我的帖子/收藏列表改两步查询，消除 `HHH90003004` 集合分页全表加载与 tags N+1 |
| 2 | 安全 | `70cbf0b` | 数据库密码与 JWT 密钥外置为环境变量（无默认值），拆分 `dev`/`prod` profile |
| 3 | 功能 | `65d631c` | 通知单条已读（后端），带归属校验，越权与不存在统一 404 |
| 4 | 功能 | `9c2d3e0` | 前端接入单条已读，点击通知即消除未读 |
| 5 | 可维护性 | `48e078d` | 时间格式化收敛为 `utils/format.js`（替换 5 处重复） |
| 6 | 可维护性 | `4398bc8` | 后台卡片抽为 `components/UserCard.vue`/`BoardCard.vue`（AdminView 297→235 行） |
| 7 | 可维护性 | `92e548a` | 分页样式收敛为 `.jf-pager`（5 处重复 → 1 处）+ 文档更新 |

### 4.4 独立缺陷修复

| 提交 | 内容 |
| --- | --- |
| `53ac4b9` | 修正登录失败提示（`BadCredentialsException` 被父类处理器吞掉 + 前端拦截器把登录失败当会话过期）与「我的收藏」列表渲染（PageResponse 对象塞进 `ref([])` 导致 `length` 恒为 undefined） |

---

## 5. 待办与后续计划

### 5.1 技术债与风险（建议优先处理）

| # | 事项 | 严重度 | 说明 |
| --- | --- | --- | --- |
| T1 | **数据库密码仍在 git 历史中** | 高 | 自 `6eecbdc` 起 `application.yml` 含明文密码。彻底处理需 `git filter-repo` 改写历史**并轮换该密码**——只改历史不够，密码已泄露。属不可逆操作，需明确授权后执行 |
| T2 | **无 CI 流水线** | 中 | 目前靠本地 `mvn clean test` / `pnpm test` 手工把关。建议加 GitHub Actions：后端（JDK 17 + H2）+ 前端（pnpm test + build） |
| T3 | **无前端 E2E 测试** | 中 | 真实浏览器验证目前靠一次性 Playwright 脚本（在 `C:\Temp\jf-pw`，未入库）。建议把关键路径（登录→发帖→评论→通知跳转）固化为仓库内 E2E |
| T4 | **JWT 无法主动失效** | 中 | 无 refresh token、无黑名单。封禁用户后其已签发的 token 在 24 小时内仍可用（权限校验不查库）。修法：过滤器里查用户状态，或引入短期 token + refresh |
| T5 | **登录接口无速率限制** | 中 | 可被暴力破解。建议加基于 IP 的限流（如 Bucket4j）或失败次数锁定 |
| T6 | **REPLY 通知锚点只覆盖第一页评论** | 低 | 详见 §6 |
| T7 | **数据库连接串硬编码 `raspi`** | 低 | `application.yml` 的 `DB_URL` 默认值是 `localhost`，但实际部署在 `raspi`。建议在 `.env.example` 里写明真实部署形态 |
| T8 | **残留的冒烟测试数据** | 低 | 真实库中存在 `smoke_user`、`t4_op`、`t4_ap`、板块「冒烟测试板块」等手工测试数据，交付演示前应清理 |

### 5.2 候选功能（第三轮可选，均未开始）

第二轮明确列为「不做」的功能，各自需要独立设计：

| 功能 | 价值 | 预估工作量 | 备注 |
| --- | --- | --- | --- |
| Markdown 渲染 | 高 | 中 | 帖子正文目前是纯文本；需引入渲染库并做 XSS 白名单过滤 |
| 标签检索页 | 中 | 小 | 已有 `tags`/`post_tags` 表与 `TagRepository`，只缺按标签查询的接口与页面 |
| 头像上传 | 中 | 中 | 需要文件存储（本地或对象存储）+ 上传接口 + 校验 |
| 深色模式 | 低 | 小 | 令牌层已就绪，主要是加一套暗色变量与切换开关 |
| WebSocket 实时通知 | 中 | 大 | 目前是 120 秒轮询兜底 |
| 全文搜索 | 中 | 中 | PostgreSQL `tsvector` + GIN 索引，替换现有 `LIKE` |

**建议第三轮取 1~2 个**，按同样的「审计 → 计划 → 逐任务执行 → 验证 → 提交」流程推进，避免一次铺开。

---

## 6. 已知问题与限制

| 问题 | 影响 | 现状 |
| --- | --- | --- |
| REPLY 通知的评论锚点只覆盖第一页评论 | 帖子详情每页 10 条根评论。若目标根评论排在第 11 条之后，页面里没有对应元素 | 不静默失败：评论数 > 10 时提示「目标评论可能在后面的评论分页里，可翻页查看」。评论已删除也命中同一提示（无法区分），属已接受的误报 |
| 点赞/收藏不改变自己的未读数 | 后端对自发行为不写通知（避免自己给自己发通知） | 有意设计，非缺陷 |
| 浏览量在每次详情页请求时 +1 | 刷新即累加，非去重 UV | MVP 简化，未做去重 |
| 无国际化 | 界面文案硬编码中文 | 第一轮非目标 |
| `AdminView` 桌面表格与窄屏卡片仍需各维护一份 | 加字段要改 2 处（原为 4 处，第二轮已收敛一半） | 进一步收敛需抽列定义，属结构性改动 |
| 构建产物有 chunk > 500kB 警告 | 首屏加载体积偏大 | Element Plus 全量引入所致，可按需引入或分包 |

---

## 7. 关键决策记录

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 认证方式 | **JWT 无状态**，不用 Session 表单登录 | 原始需求写的是 Session + CSRF，确认后改为 JWT：前后端分离部署更简单，且省掉 CSRF token 的传递与校验 |
| 分页响应结构 | 自定义 `PageResponse<T>` record，不直接返回 Spring Data `Page` | `Page` 是接口，Jackson 无法反序列化（测试里直接报 `Type definition error`） |
| 关键词搜索空值处理 | 传空串而非 `null`，JPQL 判断 `:keyword = ''` | `null` 时 Hibernate 以无类型参数绑定，pgJDBC 发成 `bytea`，PostgreSQL 报 `lower(bytea) does not exist`（SQLState 42883） |
| 列表查询策略 | 两步查询（先分页取实体、再按 id 批取集合关联） | `@EntityGraph` 含集合关联 + `Pageable` 会触发 `HHH90003004`，Hibernate 退化为全表加载后内存分页 |
| 删除策略 | 软删除（`posts.deleted` / `comments.deleted`） | 保留数据以便审计与恢复；计数由 Service 维护在 `posts` 表 |
| 计数冗余 | `like_count`/`favorite_count`/`comment_count` 存在 `posts` 表 | 避免列表页对每帖做 `COUNT(*)`；由 Service 在事务内维护一致性 |
| 密码与密钥 | 环境变量且**故意不设默认值** | 缺了就启动失败，好过用弱默认值悄悄上线 |
| 配置分 profile | `application.yml` 只放公共配置，`dev`/`prod` 各自覆盖 | 生产不应打印全量 SQL（实测单条查询日志曾超 38KB） |
| 样式收敛 | 设计令牌 + 少量共用类（`.jf-pager`、`.jf-hscroll`） | 只有 ≥3 处使用的规则才进 `common.css`，一次性布局值留在组件内 |

---

## 8. 变更日志

> 新增条目请加在表格**最上方**（最新在前）。本表覆盖仓库全部提交，可用下方命令核对是否漏记：
>
> ```bash
> git log --format='%h' | wc -l          # 提交总数
> grep -c '^| 2026-' docs/DEVELOPMENT.md  # 本表行数
> ```

| 日期 | 类型 | 摘要 | 提交 |
| --- | --- | --- | --- |
| 2026-10-18 | docs | 补充变更日志的自引用说明与提交数同步规则 | 本次提交 |
| 2026-10-18 | docs | 补全变更日志至覆盖全部提交（原漏记 16 条） | `ddfb4e0` |
| 2026-10-18 | docs | 回填开发记录文档的提交哈希 | `8b30458` |
| 2026-10-18 | docs | 新增本开发记录文档，作为项目进度的单一事实来源 | `6587de6` |
| 2026-10-17 | refactor | 第二轮 Task 7：分页样式收敛为 `.jf-pager`，补 5 个守卫用例，更新 README 与设计文档 | `92e548a` |
| 2026-10-17 | refactor | 第二轮 Task 6：后台用户与板块卡片抽为 `UserCard.vue`/`BoardCard.vue` | `4398bc8` |
| 2026-10-17 | refactor | 第二轮 Task 5：时间格式化收敛为 `utils/format.js`，替换 5 处重复 | `48e078d` |
| 2026-10-17 | feat | 第二轮 Task 4：前端接入通知单条已读 | `9c2d3e0` |
| 2026-10-17 | feat | 第二轮 Task 3：通知支持单条标记已读并校验归属 | `65d631c` |
| 2026-10-17 | feat | 第二轮 Task 2：数据库密码与 JWT 密钥外置，拆分 dev/prod profile | `70cbf0b` |
| 2026-10-17 | perf | 第二轮 Task 1：帖子/我的帖子/收藏列表改两步查询，消除集合分页全表加载 | `1b66bf7` |
| 2026-10-17 | docs | 第二轮优化实施计划（7 个任务） | `0b2837d` |
| 2026-10-17 | fix | 修正登录失败提示与「我的收藏」列表渲染 | `53ac4b9` |
| 2026-10-17 | fix | 详情页响应式读取共享互动状态并加固断点守卫 | `fe00d17` |
| 2026-10-17 | docs | 补充前端测试与令牌说明，修正断点计数注释 | `725a728` |
| 2026-10-17 | style | 补齐通知页与后台的令牌与窄屏收尾 | `b00a263` |
| 2026-10-17 | style | 其余页面令牌对齐与窄屏表单适配 | `2c8b80a` |
| 2026-10-17 | fix | 移除无效的 font-size hack 并补窄屏空状态 | `5f6060f` |
| 2026-10-17 | feat | 后台用户与板块在窄屏改为卡片式渲染 | `0663b08` |
| 2026-10-17 | feat | 首页窄屏适配、令牌对齐与互动状态同步 | `b49523e` |
| 2026-10-16 | feat | 第一轮收尾：前端同步 liked/favorited 并锁定断点一致性 | `307166a` |
| 2026-10-16 | docs | 把 liked/favorited 的后端补充记录到设计文档 | `4f88b8f` |
| 2026-10-16 | test | 用例自建前置状态，创建接口按作者推导互动标志 | `8671fcc` |
| 2026-10-16 | feat | 帖子响应补 liked/favorited，便于前端同步互动状态 | `15039d1` |
| 2026-10-16 | feat | 评论锚点定位与共享点赞收藏状态 | `772c552` |
| 2026-10-16 | fix | 恢复「全部已读」的提示并防止重复提交 | `83b354a` |
| 2026-10-16 | feat | 通知页支持点击跳转并接入 store | `b1f4942` |
| 2026-10-16 | fix | 登录成功后刷新顶栏未读数 | `dd871b2` |
| 2026-10-16 | refactor | 顶栏未读数改用通知 store（轮询降为兜底） | `765be52` |
| 2026-10-16 | feat | 响应式判定 composable（768px 断点） | `35ccfd8` |
| 2026-10-16 | test | 补齐收藏回滚用例，说明计数回滚当前不可观测 | `9f3f04a` |
| 2026-10-16 | feat | 点赞收藏共享状态 composable | `83096dc` |
| 2026-10-16 | fix | 通知 store 失败时保留角标计数，导出分页常量并隔离空页对象 | `9fbbbb3` |
| 2026-10-16 | feat | 通知未读数 store 统一角标与列表状态 | `2bb15c0` |
| 2026-10-16 | fix | SYSTEM 通知不再可点击，补类型判断与测试 | `d91e7bf` |
| 2026-10-16 | docs | 说明 REPLY 通知无 `commentId` 时的回退行为 | `3bd2ab3` |
| 2026-10-16 | test | 引入 Vitest 并覆盖通知跳转映射 | `0f9b86b` |
| 2026-10-16 | docs | 澄清断点变量与 Element Plus 派生色注意事项，`style.css` 改用令牌 | `c9384cb` |
| 2026-10-16 | style | 新增设计令牌层并接入主入口 | `e756c1d` |
| 2026-10-16 | fix | 通知查询预取 comment 关联避免 N+1，并补充迁移说明 | `9ab3fbb` |
| 2026-10-16 | feat | 通知补 `commentId` 支持评论级跳转锚点（含 V2 迁移） | `c1b54f3` |
| 2026-10-16 | chore | 引入 `spring-boot-starter-actuator` 探针依赖 | `113df30` |
| 2026-10-16 | docs | 第一轮体验迭代实现计划（13 个任务，TDD 分步） | `4c34a1a` |
| 2026-10-16 | docs | 确认通知补 `commentId`（选项一）并更新设计文档 | `4a5c869` |
| 2026-10-16 | docs | 第一轮体验迭代设计文档（视觉层级 + 响应式 + 通知闭环） | `7b170aa` |
| 2026-10-15 | feat | 第 6 阶段：Vue3 前端全页面 + 修复 PG 无参列表 500 | `3bf7c62` |
| 2026-10-15 | feat | 第 5 阶段：权限与点赞收藏 | `0f057e9` |
| 2026-10-15 | feat | 第 4 阶段：评论模块 | `a4e837f` |
| 2026-10-15 | feat | 第 3 阶段：板块与帖子模块 | `b518a7e` |
| 2026-10-15 | feat | 第 2 阶段：认证模块 | `3bab6c6` |
| 2026-10-15 | chore | 第 0+1 阶段：项目初始化与数据层 | `6eecbdc` |
