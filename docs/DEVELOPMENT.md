# JavaForum 开发记录

> 本文件是项目的**单一事实来源（single source of truth）**：记录开发情况、当前进度、已知问题与后续计划。
> **每次开发（新功能、修复、优化、重构）结束前都必须更新本文件**，约定见 §0。
>
> 最近更新：2026-10-21 ｜ 文档版本：v1.5

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

因此最新一条记录的「提交」列可能是「本次提交」，由紧随其后的 `docs:` 回填提交改为真实哈希；这只会造成 1~2 条的时序性差异。另有**历史缺口**：本表并非自首个提交起逐条记录，早期提交从未补录（用上方命令对账，差值远大于 1~2），属既有欠账，不回填历史；但本轮起新增提交必须逐条记录，回填时一并核对上一条。

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
| 容器化 | `Dockerfile`（多阶段：Maven 构建 → Spring Boot 分层提取 → `eclipse-temurin:17-jre-alpine`）+ `docker-compose.yml`（后端 + `postgres:15-alpine`） |

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
| Markdown | markdown-it 15.0.3（`html: false`，客户端渲染） |
| HTML 过滤 | DOMPurify 3.4.17（白名单 + `afterSanitizeAttributes` 钩子） |
| 代码高亮 | highlight.js 11.12.0（core + 11 种语言） |

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
| 第三轮 | Markdown 渲染 + 标签检索 | 7 | ✅ 已完成 | 2026-10-19 |
| 部署工程化 | raspi：Jenkins + rootless podman + nginx 自动化部署（见 §4.7） | — | ✅ 已完成 | 2026-10-19 |
| 第四轮 | 深色模式 + 头像上传 | 6 | ✅ 已完成 | 2026-10-20 |
| 第五轮 | 全文搜索：搜索接口 + pg_trgm 索引 + 顶栏入口与搜索结果页 | 5 | ✅ 已完成 | 2026-10-21 |
| 第六轮 | 实时通知：SSE 推送 + 一次性 ticket + 断线重连与 120s 兜底轮询 | 5 | ✅ 已完成 | 2026-10-22 |
| 第七轮 | 安全加固：JWT 主动失效（`token_version`）+ 登录失败按 IP 限流（429 + `Retry-After`） | 6 | ✅ 已完成 | 2026-10-23 |

**当前进度：MVP 100% ｜ 已完成 9 轮迭代。** 全部提交见 §8 变更日志；实时总数用 `git rev-list --count HEAD` 查询（本文件不记录精确总数，因为每次提交都会改变它）。

---

## 3. 当前状态快照（2026-10-25）

### 3.1 代码规模

| 部分 | 文件数 | 行数 |
| --- | --- | --- |
| 后端主代码（`src/main/java`） | 82 | 5083 |
| 后端测试（`src/test/java`） | 22 | 5105 |
| 前端产品代码（`frontend/src`，不含 spec 与 `assets/` 静态资源 3 文件 53 行） | 37 | 4780 |
| 前端测试（`*.spec.js`） | 32 | 5746 |

后端分层（文件数）：`entity/` 12、`dto/` 18、`repository/` 9、`controller/` 9、`service/` 11、`security/` 7（+`ClientIpResolver`、`LoginRateLimiter`，`exception/` 另加 `TooManyLoginAttemptsException`）、`config/` 7（+`ClockConfig`）、`util/` 4、`exception/` 4。

前端目录（文件数）：`views/` 20（11 个页面 + 9 个 spec）、`components/` 11（6 个组件 + 5 个 spec）、`composables/` 8（4 个组合式函数 + 4 个 spec）、`styles/` 7（4 个 css + 3 个 spec）、`utils/` 10（5 个纯函数 + 5 个 spec）、`stores/` 4（2 个 store + 2 个 spec）、`api/` 2（1 个封装 + 1 个 spec）、`router/` 1、根目录 5（`App.vue`、`main.js`、`style.css`、`viteConfig.spec.js`、`App.spec.js`）。

### 3.2 测试基线

| 层 | 数量 | 结果 |
| --- | --- | --- |
| 后端 | **177** 个用例 / 21 个测试类 | 全绿（其中 1 个 POSIX 文件权限用例在 Windows 上跳过） |
| 前端 | **323** 个用例 / 32 个 spec | 全绿 |
| 构建 | `VITE_BASE_PATH=/forum/ pnpm build` | 通过 |

后端测试类分布（合计 177）：`AvatarImageProcessorTest` 27、`AvatarFlowIntegrationTest` 20、`SearchFlowIntegrationTest` 14、`NotificationStreamServiceTest` 12、`LoginRateLimiterTest` 12、`NotificationServiceTest` 10、`RepositoryTests` 9、`TagFlowIntegrationTest` 9、`ClientIpResolverTest` 8、`AuthInvalidationIntegrationTest` 8、`JwtAuthenticationFilterTest` 7、`StreamTicketServiceTest` 6、`CommentFlowIntegrationTest` 6、`Stage5FlowIntegrationTest` 5、`PostFlowIntegrationTest` 5、`SseFlowIntegrationTest` 4、`LoginRateLimitIntegrationTest` 4、`AvatarServiceTest` 4、`JwtTokenProviderTest` 4、`ForumApplicationTests` 2、`AuthFlowIntegrationTest` 1。

前端 spec 分布（合计 323）：`search` 30、`useNotificationStream` 22、`useTheme` 20、`SearchView` 19、`ProfileView` 18、`index`（api）17、`notifications` 16、`markdown` 14、`usePostActions` 13、`tokens` 12、`AdminView` 11、`cards` 10、`UserAvatar` 10、`HeaderSearch` 9、`avatar` 9、`format` 9、`PostListItem` 8、`HomeView` 8、`NotificationsView` 8、`notificationLink` 7、`breakpoint` 7、`viteConfig` 6、`ThemeToggle` 5、`common` 5、`PostDetailView` 5、`TagPostsView` 5、`TagsView` 5、`auth` 4、`useResponsive` 3、`PostEditView` 3、`App` 3、`elementAutoImport` 2。

> 分布行在此前的记录里与实际总数对不上（2026-10-24 复核时后端、前端两行均已按 `target/surefire-reports` 与 `pnpm test` 输出重新点数并对齐）；此后以本行标注的合计与 `mvn` / `vitest` 的实际输出为准。

### 3.3 数据库

| 项 | 值 |
| --- | --- |
| 业务表 | 11 张：`users`、`roles`、`user_roles`、`boards`、`posts`、`comments`、`tags`、`post_tags`、`likes`、`favorites`、`notifications` |
| 迁移脚本 | `V1__init_schema.sql`（建表 + 角色种子）、`V2__notification_comment_id.sql`（通知表加 `comment_id`）、`V3__post_search_trgm_index.sql`（pg_trgm 扩展 + 标题/正文 GIN 部分索引）、`V4__add_user_token_version.sql`（用户表加 `token_version`，默认 0） |
| 审计字段 | 全部表含 `create_time`、`update_time`（由 `BaseEntity` + JPA Auditing 维护） |
| 软删除 | `posts.deleted`、`comments.deleted` |
| 默认管理员 | `admin` / `admin123456`（由 `DataInitializer` 幂等创建，角色 ADMIN+MODERATOR+USER） |

### 3.4 接口清单（39 个端点）

| 控制器 | 端点 |
| --- | --- |
| `HealthController` | `GET /api/health` |
| `AuthController` | `POST /api/auth/register`、`POST /api/auth/login`、`POST /api/auth/logout`、`GET /api/auth/me`、`PUT /api/auth/profile`、`GET /api/auth/users/{id}` |
| `BoardController` | `GET /api/boards`、`GET /api/boards/{id}`、`POST /api/boards`、`PUT /api/boards/{id}`、`DELETE /api/boards/{id}` |
| `PostController` | `GET /api/posts`、`GET /api/posts/search`、`GET /api/posts/{id}`、`GET /api/posts/mine`、`POST /api/posts`、`PUT /api/posts/{id}`、`DELETE /api/posts/{id}`、`GET /api/posts/favorites/mine`、`POST /api/posts/{id}/like`、`POST /api/posts/{id}/favorite`、`PUT /api/posts/{id}/pin`、`PUT /api/posts/{id}/exquisite` |
| `TagController` | `GET /api/tags`、`GET /api/tags/posts` |
| `CommentController` | `GET /api/posts/{postId}/comments`、`POST /api/posts/{postId}/comments`、`DELETE /api/posts/{postId}/comments/{id}` |
| `NotificationController` | `GET /api/notifications`、`GET /api/notifications/unread-count`、`POST /api/notifications/read-all`、`POST /api/notifications/{id}/read`、`POST /api/notifications/stream-ticket`、`GET /api/notifications/stream` |
| `AdminUserController` | `GET /api/admin/users`、`PUT /api/admin/users/{id}/status` |
| `UserController` | `POST /api/users/me/avatar`、`DELETE /api/users/me/avatar` |
| Actuator | `GET /actuator/health`（仅 ADMIN，仅暴露 health） |

`POST /api/auth/logout` 需要登录，成功返回 200 与消息「已退出登录」，并让该用户此前签发的所有 JWT 立即失效（`users.token_version` +1）；`POST /api/auth/login` 在密码连续错误达到阈值后返回 **429**，响应头带 `Retry-After: <秒>`，响应体为 `Result.fail(...)`。

两个标签端点均为公开 GET（`SecurityConfig` 的 `PUBLIC_GET` 含 `/api/tags/**`），响应统一包在 `Result<T>` 里：

| 端点 | 参数 | 返回 |
| --- | --- | --- |
| `GET /api/tags` | `keyword`（可选，标签名忽略大小写模糊匹配）、`page`（默认 0）、`size`（默认 30，钳制到 1~100） | `PageResponse<TagResponse>`；`TagResponse` 为 `id`/`name`/`postCount`，按 `postCount` 降序、同数按 `name` 升序，只统计未删除帖子 |
| `GET /api/tags/posts` | `name`（必填，标签名，可含 `/`）、`page`（默认 0）、`size`（默认 10，钳制到 1~50） | `PageResponse<PostSummaryResponse>`；未知标签返回空页（200），缺 `name` 或 `page`/`size` 非数字返回 400 |

全文搜索端点为公开 GET（`SecurityConfig` 的 `PUBLIC_GET` 已包含 `/api/posts/**`），响应统一包在 `Result<T>` 里：

| 端点 | 参数 | 返回 |
| --- | --- | --- |
| `GET /api/posts/search` | `keyword`（必填，去首尾空白后 1~50 个 code point）、`page`（默认 0，负数按 0）、`size`（默认 10，钳制到 1~50） | `PageResponse<PostSummaryResponse>`；标题命中优先、同档按 `createTime` 倒序（同时间再按 `id` 倒序），软删除不出现；错误统一 400：`缺少必需参数: keyword`、`搜索关键词不能为空`、`搜索关键词不能超过 50 个字符`、`参数格式不正确: page`（`size` 同理） |

`PostSummaryResponse` 在搜索接口里额外带一个可空字段 `snippet`：正文命中处左右各 80 字符的纯文本窗口（**不清洗 Markdown、不含省略号**，由前端 `utils/search.js` 的 `buildSnippet` 完成清洗与省略号）；窗口端点吸附到 code point 边界，不会劈开 emoji/生僻字；标题命中且正文不含关键词时是空串，其它列表接口通过 `@JsonInclude(NON_NULL)` 省略该键（而非 `null`）。关键词里的 `%`、`_`、`!` 按字面量匹配（服务端统一转义后 SQL 用 `ESCAPE '!'`；首页 `GET /api/posts?keyword=` 在第八轮起同样如此）。

头像端点由 `UserController`（`@RequestMapping("/api/users")`）提供，只操作当前登录用户，`DELETE` 幂等：

| 端点 | 参数 | 返回 |
| --- | --- | --- |
| `POST /api/users/me/avatar` | `multipart/form-data` 的 `file`（必填，JPEG/PNG，≤2MB） | `Result<UserResponse>`（消息「头像已更新」），`avatarUrl` 变为 `/uploads/avatars/<uuid>.jpg` |
| `DELETE /api/users/me/avatar` | 无 | `Result<UserResponse>`（消息「头像已移除」）；本来没有头像也返回 200 |

错误统一为 400：`仅支持 JPEG/PNG 图片`（魔数不符）、`无法解析图片`（解码失败、单边超 4096 或总像素超 2^24）、`图片不能超过 2MB`（multipart 超限或文件字节超限）、`请选择图片文件`（空文件）、`读取图片失败`（读流异常）、`保存头像失败`（落盘失败，消息可读但 HTTP 仍是 400）。服务端按 EXIF Orientation 校正方向后居中裁剪为 256×256 JPEG（quality 0.9）；文件名是服务端生成的 UUID（客户端文件名不参与路径拼接），权限 0644，先写临时文件再原子改名，事务提交后才删除旧文件（旧 URL 仍被其他用户引用时不删）。`GET /uploads/avatars/**` 是公开 GET（`SecurityConfig` 的 `PUBLIC_GET`）；本地开发由 `WebConfig` 把 `/uploads/**` 映射到 `app.upload-dir`，生产环境该前缀由 nginx 的 `location ^~ /forum/uploads/` 从宿主机目录直出，不经过 Spring。

实时通知端点（`NotificationController`，`@RequestMapping("/api/notifications")`）采用「一次性票据 + SSE 长连接」：先用 JWT 换一张 60 秒有效、只能消费一次的 ticket，再用 ticket 建立连接；ticket 在 URL 查询串里，避免浏览器 `EventSource` 无法自定义请求头的限制（JWT 不落到 URL/日志）。

| 端点 | 参数 | 返回 |
| --- | --- | --- |
| `POST /api/notifications/stream-ticket` | 无（需 `Authorization: Bearer`） | `Result<StreamTicketResponse>`：`ticket`（UUID）、`expiresIn`（秒，60） |
| `GET /api/notifications/stream` | `ticket`（必填，查询串） | `text/event-stream`：`event:heartbeat`（每 15s 一条注释/心跳）、`event:notification`（`data` 为 `NotificationResponse` JSON）；ticket 缺失/过期/已用/伪造统一 **401**，错误体为 `Result` 的「通知连接凭证无效或已过期」 |

服务端每用户最多保留 5 条连接（超出时 complete 最早一条），单条连接最长 30 分钟（`app.sse.timeout-ms`），到点由服务端 complete、前端换票重连；连接注册表在应用内存中（单实例部署，见 §6 已知限制）。通知只在**事务提交后**推送（`NotificationService.create` 内用 `TransactionSynchronization.afterCommit`，无事务时直接推），回滚不会误推；推送失败只记日志，不影响业务写入。`GET /api/notifications/stream` 由 `SecurityConfig` 单独 `permitAll`（鉴权由 ticket 完成），`POST /stream-ticket` 仍需 JWT。

### 3.5 前端页面

| 路由 | 页面 | 说明 |
| --- | --- | --- |
| `/` | `HomeView` | 板块导航 + 帖子列表 + 搜索 + 分页 + 点赞收藏 |
| `/login`、`/register` | `LoginView`、`RegisterView` | 认证 |
| `/posts/:id` | `PostDetailView` | 详情 + 评论树 + 楼中楼 + 评论锚点定位 + 版主操作 |
| `/posts/new`、`/posts/:id/edit` | `PostEditView` | 发帖 / 编辑 |
| `/profile` | `ProfileView` | 资料（含头像上传/移除）/ 我的帖子 / 我的收藏（三个 tab） |
| `/notifications` | `NotificationsView` | 通知列表 + 单条已读 + 点击跳转 |
| `/tags` | `TagsView` | 标签云（按帖子数降序）+ 关键词检索 + 分页 |
| `/tags/:name` | `TagPostsView` | 某标签下的帖子列表（复用 `PostListItem` + 分页） |
| `/search` | `SearchView` | 全文搜索结果页（`?q=`、结果计数、分页、标题/片段高亮、三种空态） |
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

### 4.5 工程化：后端容器化（2026-10-18）

新增三个文件，均为部署用，不改变任何业务代码：

| 文件 | 要点 |
| --- | --- |
| `Dockerfile` | 两阶段：`maven:3.9-eclipse-temurin-17` 编译 → `java -Djarmode=tools ... extract --layers --launcher` 分层 → `eclipse-temurin:17-jre-alpine` 运行。先单独 `COPY pom.xml` 再 `dependency:go-offline` 以复用依赖缓存；非 root 用户 `app`(1001)；`HEALTHCHECK` 探测 `/api/health`；`ENTRYPOINT` 用 `sh -c "exec java $JAVA_OPTS ..."` 让 java 成为 PID 1 以便优雅停机 |
| `.dockerignore` | 排除 `target/`、`frontend/`、`node_modules/`、`.env`、`docs/`。**特别是排除 `target/`**：本机曾出现 `BaseEntity.class` 被写坏，若把本地 target 带进构建会把损坏产物复制进镜像 |
| `docker-compose.yml` | `db`（`postgres:15-alpine`）+ `backend`；`depends_on: condition: service_healthy` 保证库就绪后 Flyway 才连；只在容器网络内暴露 5432；日志挂 `backend-logs` 卷 |

三个刻意设计：

1. **探针用 `/api/health` 而不是 `/actuator/health`**——后者在本项目被 `hasRole('ADMIN')` 保护，匿名探测必然 401，会让容器永远 unhealthy。
2. **镜像内不含凭据**：`DB_PASSWORD`/`JWT_SECRET` 在 compose 里用 `${VAR:?}` 强制必填，缺失时 compose 直接报错退出，而不是把空值传进容器。
3. **容器 profile 用独立变量 `APP_PROFILE`（默认 `prod`）**，不复用 `SPRING_PROFILES_ACTIVE`——本地 `.env` 里写的是 `dev`，复用会让容器也打印全量 SQL。

> **2026-10-19 更新**：为适配 raspi（podman，主机无 maven 镜像），`Dockerfile` 已重写为「运行时单阶段 + 预构建 jar」，`docker-compose.yml` 改为只跑 backend 并复用主机已有 PostgreSQL。上面这套多阶段方案作为历史记录保留，最新形态见 §4.7。

### 4.6 部署路径：修复非法 base 并打通子路径联动

起因：`frontend/vite.config.js` 被改成 `base: './forum'`（注释写「设置基础路径为 /forum」）。**该值是非法值**：Vite 对 `base` 只接受绝对 URL、`'./'` 或空串，非法值不报错退出，只在构建日志留一条

```
(!) invalid "base" option: "./forum". The value can only be an absolute URL, "./", or an empty string.
```

然后**静默按 `/` 处理**——产物里仍是 `/assets/...`，上线到 `/forum/` 必然整站 404。属于「构建成功但部署失败」的静默降级。

修复分三处（只改一处，子路径下仍会坏）：

| 位置 | 改动 | 不这样改的后果 |
| --- | --- | --- |
| `frontend/vite.config.js` | `base: process.env.VITE_BASE_PATH \|\| '/'`；开发代理键改为按 base 计算的 `apiPrefix`（根路径 `/api`，子路径 `/forum/api`） | base 非法 → 产物路径错；代理写死 `/api` → 子路径下开发代理失效 |
| `frontend/src/router/index.js` | `createWebHistory(import.meta.env.BASE_URL)` | 路由按根路径解析，子路径下点导航跑出应用外 |
| `frontend/src/api/index.js` | `baseURL: ${BASE_URL}api`；401 跳转用 `${BASE_URL}login` | 接口打到根路径 `/api` 而非 `/forum/api`；跳登录页跳丢子路径 |

新增 `frontend/src/viteConfig.spec.js`（6 个守卫用例）：禁止 `base: './xxx'` 写法、要求 base 由 `VITE_BASE_PATH` 决定且默认 `/`、代理必须用 `apiPrefix`、路由必须用 `BASE_URL`、axios 前缀必须由 `BASE_URL` 派生、登录跳转不得写死 `/login`。5 个变异全部被捕获。

默认 `base` 仍是 `/`，**开发与既有部署行为零变化**（已实测 dev server 200、`/api/health` 经代理返回 502 证明代理生效）。

### 4.7 部署环境化：raspi + Jenkins + podman + nginx（2026-10-19）

需求：应用部署到 Linux 主机（SSH 别名 `raspi`）；新增 Jenkins 文件，由主机上的 Jenkins 自动化部署；主机用 podman，按已有镜像调整 Docker 文件；前端不进容器，直接由 nginx 托管。

目标形态（入口 `http://raspi/forum/`）：nginx（80，系统服务）直出 `/pd/Program/forum/frontend/`（Vue dist），`/forum/api/` 反代 `127.0.0.1:8086`（`forum-backend` 容器）；数据库复用主机已有 rootless `postgresql` 容器里的 `javaforum` 库（数据保留），容器内经 `host.containers.internal:5432` 访问。

| 文件 | 要点 |
| --- | --- |
| `Dockerfile` | 改为**运行时单阶段**：`eclipse-temurin:17-jre-jammy`（主机已有 arm64 镜像）+ 预构建 jar；数字 UID 1001（不依赖 `useradd`）；`HEALTHCHECK` 用 bash `/dev/tcp`（jammy 无 curl），需 `--format docker` 构建才会保留 |
| `docker-compose.yml` | 只保留 `backend`（podman-compose 1.0.3 兼容：无顶层 `name:`、无 `depends_on.condition`、无 volume `:U`）；绑定 `127.0.0.1:${HOST_PORT:-8086}`；不写 `mem_limit`（内核 `cgroup_disable=memory`），内存靠 `JAVA_OPTS -Xms128m -Xmx512m` |
| `Jenkinsfile` | 工作区固定 `/pd/jenkins-workspace/forum`；`tools { jdk 'Java17'; maven 'M3' }`；pnpm 绝对路径 + npmmirror；`podman build --format docker`；部署用 `sudo -u pi` |
| `deploy/deploy.sh` | compose 同步 → `podman-compose -p forum down/up` → 轮询 `/api/health`（120s）→ rsync dist → nginx `cp/-t/reload` → 4 项冒烟 → 只清旧数字 tag（保留 2 个） |
| `deploy/nginx/forum.conf` | `/forum/` alias + SPA 回退（`try_files`）；`/forum/api/` 反代 8086；`/forum/assets/` 长缓存 |
| `docs/deployment/README.md` | 一次性引导、日常发布、回滚、排障手册 |

关键取舍：

- **不在主机拉取新基础镜像**：主机已有 `eclipse-temurin:17-jre-jammy`，因此把 jar 构建上移到 Jenkins（Maven `M3` 3.8.7 + `Java17` 工具），Dockerfile 只做运行时组装；主机没有 maven 镜像，无需联网拉取。
- **复用已有数据库**：`javaforum` 库与 12 张表来自早前真实部署，数据保留；部署前用 CRC32 复算 Flyway 历史 checksum 与 V1/V2 迁移一致，启动 `validate` 不会失败；`DataInitializer` 幂等。
- **前端不进容器**（需求 4）：nginx 直接 `alias` dist，发布即 `rsync --delete`，比前端镜像更简单；子路径联动沿用 §4.6 的 `VITE_BASE_PATH=/forum/`。
- **用 8086 而非 8080**：主机 8080 被 Jenkins 占用；后端只绑回环，不暴露到局域网。
- **密钥只落在部署机**：`/pd/Program/forum/deploy/.env`（0600）；旧密码曾出现在 git 历史（见 T1），引导时已重置 `forum` 角色密码。
- **触发靠 SCM 轮询**：主机没有公网入口，GitHub webhook 进不来，用 `SCMTrigger H/5` + 手动 Build。

验收（2026-10-19）：Build #1 红在部署（raspi 内核 `cgroup_disable=memory`，`mem_limit` 触发 crun 写 `memory.max` 失败）；修正后 Build #2 **SUCCESS**：Maven 打包、前端 `VITE_BASE_PATH=/forum/` 构建、`podman build --format docker`、`podman-compose -p forum up`、`/api/health` 轮询、nginx snippet 同步与 reload、4 项冒烟、旧镜像清理全部通过。实测：`forum-backend` Up (healthy) 仅监听 `127.0.0.1:8086`；`/forum/` 首页引用 `/forum/assets/` 且资源 200；`/forum/api/posts` 返回真实数据；`/forum/api/auth/login`（admin）返回 JWT；Flyway 历史 V1/V2 `success=true`；`postgresql`/`sillytavern` 等原有容器不受影响。

### 4.8 第三轮：Markdown 渲染 + 标签检索（2026-10-19）

计划：`docs/superpowers/plans/2026-10-19-round3-markdown-tags.md`（7 个任务：Markdown 渲染工具与样式 → 详情页渲染与标签跳转 → 编辑页分栏预览 → 后端标签接口 → 前端标签页面与公共帖子行 → 文档更新 → 全量验证与上线）

目标：帖子正文从纯文本升级为 Markdown 渲染；把已有 `tags`/`post_tags` 数据变成可用功能——提供标签总览页与按标签筛选的帖子列表。

新增接口（清单见 §3.4，两条均为公开 GET）：

| 端点 | 参数 | 返回 |
| --- | --- | --- |
| `GET /api/tags` | `keyword`（可选，忽略大小写模糊匹配）、`page`（默认 0）、`size`（默认 30，钳制 1~100） | `PageResponse<TagResponse>`（`id`/`name`/`postCount`），按 `postCount` 降序、同数按 `name` 升序 |
| `GET /api/tags/posts` | `name`（必填，可含 `/`）、`page`（默认 0）、`size`（默认 10，钳制 1~50） | `PageResponse<PostSummaryResponse>`；未知标签返回空页 200，缺参/类型错误 400 |

新增与修改的文件：

| 位置 | 文件 | 要点 |
| --- | --- | --- |
| 后端 | `dto/tag/TagResponse.java` | 标签聚合响应（`id`/`name`/`postCount`） |
| 后端 | `service/TagService.java` | 聚合查询 + 排序 + 服务层分页（用 `long` 运算防 `page*size` 溢出） |
| 后端 | `controller/TagController.java` | 两个公开 GET，`page`/`size` 服务端钳制 |
| 后端 | `repository/TagRepository.findWithPostCounts` | JPQL `group by` 投影，只统计 `deleted = false` 的帖子 |
| 后端 | `repository/PostRepository.searchByTagName` | 按标签名取帖子 id 骨架分页（排序由服务层 `pinnedFirstSort` 传入） |
| 后端 | `service/PostService.listByTag` | 复用两步水合管道；标签名小写归一，未知标签直接返回空页 |
| 后端 | `config/SecurityConfig` | `PUBLIC_GET` 增加 `/api/tags/**` |
| 后端 | `exception/GlobalExceptionHandler` | 新增两个 400 处理器：缺 `@RequestParam`（`MissingServletRequestParameterException`）、参数类型不匹配（`MethodArgumentTypeMismatchException`） |
| 前端 | `utils/markdown.js` | `markdown-it`（GFM、`html: false`、`breaks: true`、`linkify`）+ DOMPurify 白名单 + highlight.js 高亮 + 外链/图片加固 |
| 前端 | `styles/markdown.css` | Markdown 排版样式（复用设计令牌），`main.js` 引入 |
| 前端 | `components/PostListItem.vue` | 从 `HomeView` 抽出的公共帖子行（置顶/精华标记、标签、互动计数、点赞事件） |
| 前端 | `views/TagsView.vue`、`views/TagPostsView.vue` | 标签总览（检索 + 分页 + 空态/错误重试）与标签下帖子列表（路由变化重置分页） |
| 前端 | `api/index.js` | 新增 `tagApi.list`、`tagApi.posts` |
| 前端 | `router/index.js`、`App.vue` | `/tags`、`/tags/:name` 路由与顶栏「标签」导航 |
| 前端 | `views/PostDetailView.vue` | 正文改 Markdown 渲染（`v-html` 绑定已消毒 HTML，容器带 `.markdown-body`） |
| 前端 | `views/PostEditView.vue` | 桌面左右分栏实时预览、窄屏编辑/预览 tab |
| 前端 | `views/HomeView.vue` | 列表改用 `PostListItem`，消除重复的帖子行模板 |
| 前端 | `styles/breakpoint.spec.js`、`styles/tokens.css` | 断点数量守卫 5→6（新增标签页媒体查询）；同步 tokens.css 断点字面量注释（7→8 处），未新增令牌 |

关键取舍：

- **客户端渲染 + DOMPurify 白名单**：Markdown 在浏览器端用 `markdown-it` 渲染（`html: false` 先转义原始 HTML），再用 DOMPurify（`USE_PROFILES: { html: true }`）白名单过滤；`afterSanitizeAttributes` 钩子统一为外链补 `target="_blank" rel="noopener noreferrer"`、为图片补 `loading="lazy"`。后端继续只存纯文本，不做服务端渲染，避免 HTML 入库与二次转义问题。
- **`breaks: true` 兼容历史纯文本帖子**：单换行转 `<br>`，让老帖子的换行观感不退化；代价是纯文本里像 Markdown 的写法会被解释（见 §6）。
- **代码高亮只注册 11 种语言**：highlight.js 全量入口会把所有语言打进包体，只注册 java/javascript/typescript/bash/json/xml/css/sql/python/yaml/markdown，未注册语言降级为纯文本。
- **标签聚合在服务层分页**：`postCount` 由 JPQL `group by` 一次取回，排序与分页在内存完成（标签量级小，避免为分页写窗口函数/原生 SQL）。分页边界用 `long` 运算后收窄，防止 `page=Integer.MAX_VALUE` 时 `page*size` 溢出成负数导致 `subList` 抛 500。
- **标签名走 query 参数**：标签名可能含 `/`（如 `CI/CD`），放进路径段会被 Tomcat 拒绝或需要 `%2F`（易被代理/容器还原成 `/`），因此用 `GET /api/tags/posts?name=...`。
- **复用既有分页管道**：标签帖子查询沿用「骨架分页取 id → 批量取实体 → 批量取标签」的两步水合与 `pinnedFirstSort`，置顶帖仍排标签列表首位，且不触发 `HHH90003004` 集合分页退化。

测试与验证（2026-10-19 实测）：

- 后端 `mvn -B test`：9 个测试类、39 个用例全绿。新增 `TagFlowIntegrationTest` 8 例（按帖子数降序、关键词过滤、分页与标签携带、未知标签空页且 `size` 钳制、置顶优先、总览分页边界与溢出、缺参/非法参数 400）与 `RepositoryTests.tagAggregationCountsOnlyUndeletedPosts`。
- 前端 `pnpm test`：20 个 spec、143 个用例全绿。新增 `markdown.spec.js` 14 例、`TagsView.spec.js` 5 例、`TagPostsView.spec.js` 5 例、`PostListItem.spec.js` 4 例、`PostEditView.spec.js` 3 例；`api/index.spec.js` 增 `tagApi` 路径断言（7→9 例）；`PostDetailView.spec.js` 由 3 例增至 5 例（Markdown 渲染）。
- 构建：`VITE_BASE_PATH=/forum/ pnpm build` 通过（第三轮 Task 5 验证，子路径产物引用 `/forum/assets/`）。
- 上线验收（2026-10-19）：推送 `97a00b6` 后 Jenkins Build #4 **SUCCESS**（镜像 `localhost/forum-backend:4`）；`forum-backend` Up (healthy) 仅监听 `127.0.0.1:8086`；`/forum/api/health` 返回 UP；`/forum/api/tags` 返回真实标签（`smoke`，postCount=1）；`/forum/api/tags/posts?name=smoke` 返回对应帖子；未知标签 `name=java` 返回 200 空页；`/forum/` 首页引用 `/forum/assets/` 且 `markdown-*.js` 懒加载块 200；`admin` 登录返回 JWT。

已知限制：

- **无独立 404 标签页**：未知标签返回 200 + 空页，`TagPostsView` 与「标签下暂无帖子」复用同一空态，不区分「标签不存在」与「标签下无帖子」；拼错标签名时页面显示空列表而非 404。
- **标签聚合在应用层**：聚合结果一次性取回后在内存排序分页，标签数量级变大时需改为 SQL 分页 + 索引。
- **Markdown 会解释历史纯文本帖子**：详见 §6。

### 4.9 第四轮：深色模式 + 头像上传（2026-10-20）

计划：`docs/superpowers/plans/2026-10-20-round4-darkmode-avatar.md`（6 个任务：深色模式前端 → 头像上传后端 → 头像上传前端 → 部署支持上传目录 → 文档 → 全量验证与上线）

目标：在既有令牌层上增加暗色配色，支持跟随系统/手动切换且刷新不闪白；把「头像」从纯文本 URL 字段升级为可上传、可移除、服务端裁剪压缩并落盘到宿主机目录的真实文件。

新增/修改的文件：

| 位置 | 文件 | 要点 |
| --- | --- | --- |
| 后端 | `util/AvatarImageProcessor.java` | 只按魔数识别 JPEG/PNG；`ImageIO` 解码后按 EXIF Orientation 1–8 校正，居中裁剪为 256×256，输出 JPEG（quality 0.9）；限制单边 4096、总像素 2^24 |
| 后端 | `service/AvatarService.java` | 校验（空文件/2MB/魔数/解码）→ 转码 → 临时文件 + `ATOMIC_MOVE` → 更新 `users.avatar_url` → 提交后删旧文件；UUID 文件名、权限 0644，旧文件仍被其他用户引用则不删 |
| 后端 | `controller/UserController.java` | `POST/DELETE /api/users/me/avatar`，只允许登录用户操作自己（`SecurityUtils.currentUserId()`） |
| 后端 | `config/SecurityConfig.java`、`config/WebConfig.java` | `PUBLIC_GET` 放行 `/uploads/avatars/**`；本地开发把 `/uploads/**` 映射到 `app.upload-dir` |
| 后端 | `exception/GlobalExceptionHandler.java`、`application.yml` | multipart 2MB/3MB 上限；`MaxUploadSizeExceededException` → 400「图片不能超过 2MB」 |
| 前端 | `composables/useTheme.js`、`components/ThemeToggle.vue` | `system/light/dark` 三态、`localStorage` 的 `jf_theme`，系统模式跟随 `matchMedia`；顶栏下拉切换 |
| 前端 | `styles/tokens.css`、`styles/highlight-dark.css`、`styles/tokens.spec.js` | `:root` 亮色 + `html.dark` 暗色两套同名令牌；暗色代码高亮逐条 `html.dark .hljs-*` |
| 前端 | `index.html`、`main.js` | 首屏内联脚本先挂 `html.dark` 防闪白；8 条样式导入顺序固定 |
| 前端 | `utils/avatar.js`、`components/UserAvatar.vue` | 站内 `/uploads/` 拼 `BASE_URL`；加载失败回退昵称首字母 |
| 前端 | `views/ProfileView.vue`、`api/index.js` | 头像选择/上传/移除 + 客户端类型与 2MB 预校验，`uploadAvatar` 超时 60s |
| 部署 | `Dockerfile`、`docker-compose.yml`、`deploy/deploy.sh`、`deploy/nginx/forum.conf`、`.env.example` | 容器内 `/app/uploads` 卷、`UPLOAD_HOST_DIR`、目录创建与 `podman unshare chown/chmod`、`^~ /forum/uploads/` 直出 + `nosniff` |

关键取舍：

- **主题不引入 UI 库之外的依赖**：复用 Element Plus 官方暗色 css-vars，再叠加自建 `--jf-color-*` 双套令牌，避免再引一套主题框架；首屏闪白用 `index.html` 内联脚本解决（Vue 挂载前就定好 `html.dark`）。
- **服务端转码而不是原图直存**：统一裁成 256×256 JPEG，既控制体积，也顺手剥掉 EXIF/GPS 等元数据；只认魔数，改扩展名的伪图片会被「仅支持 JPEG/PNG 图片」挡住。
- **文件落在宿主机 bind mount**：头像不随镜像发布、也不进数据库，升级/回滚容器不影响已有头像；代价是备份要单独覆盖该目录（T11）。
- **写临时文件 + 原子改名**：中途失败不会留下半张图；数据库提交成功后才删旧文件，回滚时旧头像仍可用。
- **文件权限显式 0644**：nginx worker 与后端容器不是同一 uid，历史上 umask 产出 0600 导致线上 403；`deploy.sh` 每次部署 `podman unshare chmod -R a+rX` 顺带治愈旧文件。
- **nginx 用 `^~ /forum/uploads/` 且排在 SPA 回退之前**：否则 `/forum/uploads/xxx` 会命中 `try_files ... /forum/index.html` 返回 200 HTML；`nosniff` 防止浏览器把内容当脚本嗅探。
- **图片 URL 只做站内拼接**：`resolveAvatarUrl` 对 `/uploads/` 前缀拼 `BASE_URL`，外链与 data URL 原样透传，兼容历史用户自填的头像 URL。

测试与验证（2026-10-20 实测）：

- 后端 `mvn -B clean test`：12 个测试类、90 个用例全绿（1 例 POSIX 权限用例在 Windows 上跳过）。新增 `AvatarImageProcessorTest` 27 例、`AvatarFlowIntegrationTest` 20 例、`AvatarServiceTest` 4 例。
- 前端 `pnpm test`：25 个 spec、211 个用例全绿。新增 `useTheme` 17 例、`tokens` 12 例、`UserAvatar` 10 例、`avatar` 9 例、`ThemeToggle` 5 例；`ProfileView` 由 6 例增至 18 例（含头像上传/移除）。
- 构建：`VITE_BASE_PATH=/forum/ pnpm build` 通过（index chunk 约 874kB，仍有 >500kB 警告）。
- 上线验收（2026-10-20）：推送 `65b44bb` 后 Jenkins Build #6 **SUCCESS**（镜像 `localhost/forum-backend:6`），`forum-backend` Up (healthy) 仅监听 `127.0.0.1:8086`；随构建发布的 nginx snippet 含 `^~ /forum/uploads/`（`nosniff`）与 `/forum/api/` 的 `client_max_body_size 3m`，`nginx -t` 通过并 reload；宿主 `/pd/Program/forum/uploads/{,avatars}` 已建（属主为 rootless 容器的 subuid `101000`，`chmod -R a+rX` 后 `drwxr-sr-x`）；`/forum/`、`/forum/api/health`、`/forum/api/tags` 均 200；`/forum/uploads/__probe__/missing.jpg` 为 **404**（未被 SPA 回退吃掉）；实测上传 8.7KB 与 1.8MB JPEG 均 200（后者证明 nginx 1m 默认限制已放开）、取回 `200 image/jpeg` 且文件 `-rw-r--r--`；2.98MB 返回 400「图片不能超过 2MB」、3.2MB 由 nginx 返回 413；重启容器后 `avatarUrl` 与头像文件仍在（bind mount），删除接口返回 200 且旧文件即刻 404、目录清空。

已知限制：

- **头像文件不在备份范围**（T11）：数据库备份不含 `/pd/Program/forum/uploads/`，恢复时需单独 `tar` 并保留属主，之后重跑 `deploy.sh` 修正权限；孤儿文件（换头像/删号后残留）没有清理任务。
- **主题选择不跨标签页同步**（T12）：`localStorage` 变化不会通知其他标签页，多标签下可能一亮一暗，刷新后一致。

---

### 4.10 第五轮：全文搜索（2026-10-21）

计划：`docs/superpowers/plans/2026-10-21-round5-fulltext-search.md`（5 个任务：后端接口 + V3 迁移 → 搜索文本工具 → 顶栏入口与搜索结果页 → 文档 → 全量验证与上线）

目标：把首页已有的关键词 `LIKE` 过滤升级为独立的搜索结果页与公开搜索接口——标题命中优先、同档按发帖时间倒序，配合 pg_trgm GIN 索引支撑「包含」语义的子串检索；顶栏提供搜索入口，窄屏收成图标。

新增/修改的文件：

| 位置 | 文件 | 要点 |
| --- | --- | --- |
| 后端 | `repository/PostRepository.java` | `searchByKeyword`：`LOWER(title/content) LIKE CONCAT('%', :pattern, '%') ESCAPE '!'`，`CASE WHEN 标题命中 THEN 0 ELSE 1` + `createTime DESC, id DESC`，EntityGraph 只 join `board`/`author` |
| 后端 | `service/PostService.java` | `search(rawKeyword, page, size)`：归一化 1~50 code point、`escapeLike`（`! % _` 转义）、clamp、**无 Sort** 的 `PageRequest`、两步分页 + `snippetOf` 截正文窗口 |
| 后端 | `controller/PostController.java` | `GET /api/posts/search`，放在 `/{id}` 之前；controller 与 service 各夹取一次 `size` |
| 后端 | `dto/post/PostSummaryResponse.java` | 新增可空 `snippet`：仅搜索接口填充，`@JsonInclude(NON_NULL)` 让其它列表的 JSON 省略该键 |
| 后端 | `db/migration/V3__post_search_trgm_index.sql` | `CREATE EXTENSION IF NOT EXISTS pg_trgm` + 标题/正文两个 `USING gin (LOWER(...) gin_trgm_ops) WHERE deleted = false` |
| 前端 | `utils/search.js` | `highlightSegments`（indexOf 循环、不构造正则）、`buildSnippet`（Markdown 轻量清洗 + 命中窗口 + 省略号） |
| 前端 | `components/HeaderSearch.vue` | 桌面 220px 输入框、窄屏图标按钮，`useResponsive` + `v-if`，零新增 `@media` |
| 前端 | `views/SearchView.vue` | `/search?q=`：结果计数、分页、三种空态、`requestSeq` 防竞态，点赞复用 `usePostActions` |
| 前端 | `components/PostListItem.vue` | 可选 `keyword`/`snippet` props，文本层 `<mark class="hit">` 高亮（不引入 `v-html`） |
| 前端 | `api/index.js`、`router/index.js`、`App.vue` | `postApi.search`、`/search` 懒加载路由、顶栏挂载（`.header-inner` 改 `flex-start` + `.nav-actions` 自动左推） |

关键取舍：

- **pg_trgm 而不是 tsvector**：中文没有空格分词，`to_tsvector('simple', ...)` 只能整段匹配；pg_trgm 的三元组索引对「包含」语义的子串检索有效，也不需要额外的分词插件。
- **标题优先用 CASE，不做相关度评分**：`similarity()`/`ts_rank` 的排序用户无法预期；「标题命中的排前面、同档按时间」既好解释又稳定可测。
- **两步分页**：`@EntityGraph` 带 `tags` 集合再分页会触发 `HHH90003004`（全表加载后内存分页），与首页/标签页保持同一套 `searchByKeyword` → `hydrate` → `mapSummaries`。
- **`ESCAPE '!'`**：`%`/`_` 是 LIKE 通配符，不转义时搜「100%」会命中「1000」；service 统一转义，repository 只拼 `%pattern%`。
- **片段由服务端截窗口、前端清洗**：列表 DTO 不带正文（避免搜索页响应随正文膨胀），服务端只给命中位置左右各 80 字符的窗口，并把窗口端点吸附到 code point 边界（避免劈开 emoji/生僻字）；Markdown 清洗、省略号与高亮统一在 `buildSnippet` 一处实现。
- **H2 测试关闭 Flyway**：V3 的 `CREATE EXTENSION` 在 H2 上无法执行，测试库统一 `spring.flyway.enabled=false` + `ddl-auto=create-drop`。

测试与验证：后端新增 `SearchFlowIntegrationTest` 14 例（契约 400、标题优先、时间倒序、分页与夹取、通配符转义、软删除、片段不劈开代理对、长度按 code point、大小写不敏感、既有响应无 `snippet` 键），`RepositoryTests` 新增 1 例覆盖 JPQL 的 CASE 排序与 `ESCAPE '!'`；前端新增 `search`/`HeaderSearch`/`SearchView` 三个 spec 并扩充 `PostListItem`/`api`。基线数字见 §3.2；上线验收由 Task 5 回填。

已知限制：

- 中文按子串匹配，没有分词与相关度排序；1–2 个字的关键词无法有效利用 trigram 索引，退化为顺序扫描（T14）。
- `posts.content` 没有长度上限，超长正文的片段截取与传输仍有成本（T15）。
- 跨列 OR（标题或正文）可能让查询规划器放弃 GIN 索引；数据量继续增长时可拆成两条查询再 `UNION`（未立项）。

上线验收（2026-10-21）：

- 推送 `67c13a6` 后 Jenkins Build **#8** **SUCCESS**（镜像 `localhost/forum-backend:8`），`forum-backend` 重建后 `Up (healthy)`，仅监听 `127.0.0.1:8086`。
- V3 由容器启动时的 Flyway 自动执行：`flyway_schema_history` 最新记录 `version=3 / description=post search trgm index / success=t`；`\dx pg_trgm` 显示 `pg_trgm 1.6` 已安装，`\d posts` 显示 `idx_posts_title_trgm`、`idx_posts_content_trgm` 两个 `gin` 部分索引（`WHERE deleted = false`）。`forum` 角色以非超级用户完成 `CREATE EXTENSION`（pg_trgm 为 trusted 扩展），未额外授权。
- 冒烟：`/forum/api/health`、`/forum/api/posts/search?keyword=测试`（中文命中并返回 `snippet`，标题命中行为空串）、`/forum/search?q=Jenkins`（SPA 回退）均 200；空白关键词、缺参数、51 字符关键词均 400，返回 JSON 错误体。
- `EXPLAIN`：在现有 17 篇帖子的数据量下走 `Seq Scan`，属预期（数据量低、关键词短，用不上 trigram 索引）。
- 全量测试：后端 13 个测试类 / 105 个用例（1 例 POSIX 权限用例在 Windows 上跳过）；前端 28 个 spec / 274 个用例；`VITE_BASE_PATH=/forum/ pnpm build` 通过。

### 4.11 第六轮：实时通知（SSE）（2026-10-22）

计划：`docs/superpowers/plans/2026-10-22-round6-sse-notifications.md`（5 个任务：后端 SSE 基础设施 → 通知创建事务后推送 → 前端连接/退避/角标 → 通知页即时插入与横幅 → 部署、文档与上线）

设计：`docs/superpowers/specs/2026-10-22-round6-sse-notifications-design.md`

目标：把 120 秒轮询的通知体验改成「产生即推送」——顶栏角标即时 +1、右上角轻提示、通知页第 1 页即时插入新行；断线按 1s→30s 指数退避自动重连，重连后校准未读数；SSE 不可用时退回 120s 轮询兜底。

新增/修改的文件：

| 位置 | 文件 | 要点 |
| --- | --- | --- |
| 后端 | `service/NotificationStreamService.java` | 连接注册表 `ConcurrentHashMap<Long, Set<SseEmitter>>`：注册即发首帧注释、每 15s 心跳、单连接 30 分钟超时、每用户上限 5（超出 complete 最早一条）；`send` 走 2 线程有界队列，慢客户端不阻塞请求线程；锁内不改 I/O（先改注册表，锁外 complete） |
| 后端 | `service/StreamTicketService.java` | UUID ticket + 60s TTL + 单次消费（consume 即删除）+ 定时清理过期票 |
| 后端 | `config/SseConfig.java` | `@EnableScheduling` 驱动心跳与过期清理 |
| 后端 | `exception/InvalidStreamTicketException.java`、`exception/GlobalExceptionHandler.java` | ticket 无效统一 401「通知连接凭证无效或已过期」 |
| 后端 | `controller/NotificationController.java` | `POST /stream-ticket`（需 JWT）、`GET /stream?ticket=`（返回 `SseEmitter`） |
| 后端 | `service/NotificationService.java` | `create` 内 `TransactionSynchronization.afterCommit` 推送（无同步时直接推），推送异常只记日志 |
| 后端 | `service/CommentService.java`、`service/LikeService.java` | REPLY/LIKE/FAVORITE 三个触发点改调 `NotificationService.create`，事务边界知识集中在通知服务 |
| 后端 | `application.yml`、`application-test.yml` | `app.sse.timeout-ms=1800000`、`heartbeat-ms=15000`、`ticket-ttl-ms=60000`、`max-connections-per-user=5` |
| 前端 | `api/index.js` | `notificationApi.streamTicket()` |
| 前端 | `composables/useNotificationStream.js` | 状态机 `connecting/open/retrying/disabled/closed`、1s→30s 指数退避、401 进入 disabled 且不再重连、坏 JSON 忽略、页面隐藏暂停、120s 兜底轮询（重连成功即停）、`stop` 清理定时器与连接 |
| 前端 | `stores/notifications.js` | `handleIncoming`（去重、第 1 页插入、累计 `pendingNewCount`）、`loadList(0)` 与 `markAllRead` 清零 pending |
| 前端 | `App.vue` | 登录后取票并 `start`；登出/账号切换 `stop` 并重置；SSE 不可用时保留 120s 轮询，替换原来的「轮询 + 双轨」逻辑 |
| 前端 | `views/NotificationsView.vue` | `viewOpen` 置位与卸载复位、横幅（列表为空也显示）、点击 `load(0)` 回第 1 页并清零 |
| 部署 | `deploy/nginx/forum.conf` | `location = /forum/api/notifications/stream` 精确匹配：`proxy_buffering off`、`proxy_cache off`、`proxy_read_timeout 3600s`、`proxy_set_header Connection ''`、`$is_args$args` |
| 部署 | `deploy/deploy.sh` | 冒烟新增「无 ticket → 401」断言，防止 nginx 漏配时把请求兜到 SPA 返回 200 |
| 文档 | `README.md`、`docs/DEVELOPMENT.md`、`docs/deployment/README.md` | 功能清单、SSE 配置项、开发/部署/排障记录 |

关键取舍：

- **SSE 而非 WebSocket**：本项目通知只需服务端单向推送；SSE 走普通 HTTP、nginx 原生支持、`EventSource` 自带重连语义，省掉 WS 握手与网关升级配置。代价是单向（通知没有客户端上行需求）。
- **一次性 ticket 而非 URL 带 JWT**：`EventSource` 不能自定义请求头，把长期 JWT 放进查询串会进 access log；ticket 60 秒有效、消费即失效、只够换一条连接，泄露窗口极小。
- **事务提交后推送**：在 `NotificationService.create` 内 `afterCommit`，业务回滚不会推「不存在的点赞/回复」；无事务同步时直接推，便于单测。
- **推送异步化 + 每用户连接上限**：慢客户端不阻塞写接口；最多 5 条连接，超出挤掉最早一条，多标签页/反复重连都不会无限堆积。
- **断线期间保留 120s 轮询兜底**：SSE 不可用时退回改造前的轮询行为，重连成功后自动停止，避免「SSE 一挂就完全收不到通知」。
- **通知页即时插入只在第 1 页且页面可见时**：翻到第 2 页或页面隐藏时不改列表，只累计 `pendingNewCount` 显示「有 N 条新通知，点击刷新」，避免把用户正在读的分页顶乱。

测试与验证：后端新增 `NotificationStreamServiceTest`（注册/上限/心跳/超时/重复注销/异步发送，12 例）、`StreamTicketServiceTest`（过期/重放/清理，6 例）、`SseFlowIntegrationTest`（ticket 契约、401、真实订阅，4 例）；`NotificationServiceTest` 扩到 10 例（afterCommit 推送、回滚不推、推送异常隔离）。前端新增 `useNotificationStream`（22 例）与 `App`（2 例），`notifications` 扩到 16 例、`NotificationsView` 扩到 8 例。基线数字见 §3.2。

已知限制：见 §6「实时通知是单实例的」「SSE 需要反向代理配合」两条。

上线验收（2026-10-22）：

- 推送 `9ead0d0` 后 Jenkins Build **#10** **SUCCESS**（镜像 `localhost/forum-backend:10`），`forum-backend` 重建后 `Up (healthy)`，仅监听 `127.0.0.1:8086`。
- nginx：snippet 含 SSE 精确 location（`proxy_buffering off`、`proxy_read_timeout 3600s`、`$is_args$args`），`nginx -t` 通过并 reload；`curl` 无 ticket → **401**、同一 ticket 第二次 → **401**。
- 冒烟：`/forum/`、`/forum/api/health`、`/forum/api/notifications/unread-count` 均 200；已登录用户取票后 `curl -N` 收到 `text/event-stream` 与 `:connected`/`:heartbeat`，另一账号楼中楼回复其评论后收到 `event:notification`（`data` 含 `type=REPLY`，未读数 0 → 1）；`deploy.sh` 的 stream 401 断言随构建通过。
- 全量测试：后端 16 个测试类 / 133 个用例（1 例 POSIX 权限用例在 Windows 上跳过）；前端 30 个 spec / 309 个用例；`VITE_BASE_PATH=/forum/ pnpm build` 通过。

### 4.12 第七轮：安全加固（JWT 主动失效与登录限流）（2026-10-23）

计划：`docs/superpowers/plans/2026-10-23-round7-security-hardening.md`（6 个任务：数据模型与签发版本 → 请求态校验、登出与封禁 → 登录失败限流 → 前端登出 → 文档 → 全量验证与上线）

设计：`docs/superpowers/specs/2026-10-23-round7-jwt-invalidation-rate-limit-design.md`

目标：解决 §5.1 的 T4、T5 两条技术债——封禁与登出必须让**已签发**的 JWT 立即失效，登录接口必须按客户端 IP 抗暴力破解；公开 GET 的语义与缓存行为保持不变。

新增/修改的文件：

| 位置 | 文件 | 要点 |
| --- | --- | --- |
| 后端 | `db/migration/V4__add_user_token_version.sql` | `users` 加 `token_version BIGINT NOT NULL DEFAULT 0`，历史用户与旧 token 都从 0 开始 |
| 后端 | `entity/User.java` | `@Column(name = "token_version", nullable = false) @Builder.Default private long tokenVersion = 0L` |
| 后端 | `security/JwtTokenProvider.java` | `createToken(..., long tokenVersion)` 写 `ver` 声明；`static long tokenVersion(Claims)` 用 `Number` 读（jjwt 对小整数返回 `Integer`，超出 int 范围为 `Long`；代码统一按 `Number` 读），缺声明按 0 |
| 后端 | `repository/UserRepository.java` | 只读投影 `UserTokenState { getId / getStatus / getTokenVersion }` + `findTokenStateById`，不加载 roles 集合 |
| 后端 | `security/JwtAuthenticationFilter.java` | 解析签名后回查一次库：用户不存在 / `BANNED` / `ver` 与库中版本不符 → 不建立认证态 |
| 后端 | `service/AuthService.java`、`controller/AuthController.java` | `logout(userId)` 在事务内 `tokenVersion + 1`（脏检查即可，不 save）；`POST /api/auth/logout` 需要登录 |
| 后端 | `service/AdminUserService.java` | `case BANNED` 分支在 `save` 前同样 `tokenVersion + 1`；`MUTED`/`ACTIVE` 不动版本 |
| 后端 | `config/SecurityConfig.java` | `/api/auth/logout` 与 `/api/auth/me`、`/api/auth/profile` 并列 `authenticated()` |
| 后端 | `security/ClientIpResolver.java` | 非环回、非私网地址直接信任；环回（nginx 反代）或 RFC1918 私网（容器网关）时取 `X-Real-IP`，非法再退 `X-Forwarded-For` 首个合法字面量；全程正则校验，不做 DNS |
| 后端 | `security/LoginRateLimiter.java`、`config/ClockConfig.java` | `ConcurrentHashMap<String, Window>`（`Deque<Long> failures` + `lockedUntil`），`Clock` 注入；`@Scheduled` 定期清理过期窗口 |
| 后端 | `exception/TooManyLoginAttemptsException.java`、`exception/GlobalExceptionHandler.java` | 锁定抛异常 → 429 + `Retry-After` + `Result.fail("登录失败次数过多，请 X 分钟后再试")` |
| 后端 | `controller/AuthController.java` | `login` 先 `check(ip)` 再验密；`BadCredentialsException` 才 `recordFailure`，成功 `recordSuccess`（账号不存在也算凭据失败） |
| 后端 | `application.yml` | `app.security.login.{max-failures,window-ms,lock-ms,cleanup-interval-ms}`，全部可用 `LOGIN_*` 覆盖（已由 `docker-compose.yml` 的 `environment` 透传，生产只改 `.env` 重启容器即生效，见下方第 7 条） |
| 前端 | `api/index.js`、`stores/auth.js` | `authApi.logout()`；`logout()` 改异步、尽力而为地调后端，`finally` 里统一清态 |
| 前端 | `stores/auth.spec.js` | 新增 3 例：成功清态、后端失败仍清态、未登录不抛错 |
| 文档 | `README.md`、`docs/DEVELOPMENT.md`、`docs/deployment/README.md` | 功能、迁移、配置项、测试基线、接口清单、上线记录 |

关键取舍：

- **`token_version` 而非黑名单**：单实例部署、不引入 Redis；用户表的一行版本号既能让登出/封禁立即生效，又天然支持「一次性让某用户全部设备下线」。代价是每个带 token 的请求多一次主键查询（只取 3 列），以及角色变更仍不即时（本轮不处理）。
- **版本校验放在过滤器而不是业务 Service**：认证态只在请求入口建立一次，后续 `@PreAuthorize` 与 `SecurityUtils.currentUserId()` 都读同一份认证态，不会出现「一半请求看到新状态、一半看到旧状态」。
- **旧 token 缺 `ver` 按 0**：上线时不必强制所有人重新登录；只要用户没登出/被封禁，版本仍是 0，旧 token 继续可用。
- **计数放在 Controller 而不是认证过滤器**：只有 `BadCredentialsException`（用户名不存在或密码错）才计入失败，其它 401（token 过期、格式错）不污染登录失败窗口；锁定判断发生在验密之前，锁定期间不消耗 CPU 做 BCrypt。
- **信任 `X-Real-IP` 的前提是 nginx 覆盖写**：`deploy/nginx/forum.conf` 两个 `location` 都写了 `proxy_set_header X-Real-IP $remote_addr;`，客户端自带的同名头会被覆盖；后端只在 `remoteAddr` 是环回或 RFC1918 私网时才读它。代价是后端端口只绑宿主机回环，本机进程可直连 8086 伪造该头；若将来扩大端口发布范围，这个信任模型必须重新评估。
- **限流用内存窗口而不是 Bucket4j**：与「不新增依赖」的既有约定一致，5 次/15 分钟的粗粒度窗口不需要令牌桶的精度；窗口淘汰靠时间戳裁剪 + 定时清理，`MutableClock` 让单测无需 sleep。

测试与验证：后端新增 `AuthInvalidationIntegrationTest`（真实登出/封禁/版本不符/旧 token/禁言仍可用，6 例）、`ClientIpResolverTest`（8 例）、`LoginRateLimiterTest`（9 例）、`LoginRateLimitIntegrationTest`（429 与 `Retry-After`、锁定内正确密码仍 429、其它 IP 不受影响、成功登录清零，4 例）；`JwtTokenProviderTest` 加 `ver` 断言、`RepositoryTests` 加默认值断言。前端 `auth.spec.js` 3 例、`index.spec.js` 加 2 例（登出请求路径、429 不清登录态）。基线数字见 §3.2。

已知限制：见 §6「登录限流是单实例的」与 §5.2 的候选「限流改共享存储」。

#### 已知取舍（代码审查记录）

本轮 Task 1–4 的两阶段审查（规格符合性 + 代码质量）接受并延后以下问题，均不阻塞本轮上线，后续迭代择机处理：

1. `ClientIpResolver` 的信任范围已从「仅环回」放宽到「环回 + RFC1918 私网」（修复容器端口映射下 `remoteAddr` 为网关导致的全站共桶）；残留：`X-Forwarded-For` 兜底仍取**第一个**合法字面量（攻击者可用伪造首段更换计数桶）、IPv4-mapped loopback（`::ffff:127.0.0.1`）未被识别、无代理头且来源非环回时仍回落到共享桶。
2. `docker-compose.yml` 已透传 `LOGIN_*`（默认值与 `application.yml` 一致），只改 `.env` 重启容器即可生效（修复前需同时改 compose）。
3. SSE 长连接不会因 token 失效立即断开（最长 30 分钟超时兜底）；前端登出时本地清态等待后端响应（最长全局 15s 超时），该窗口内界面仍显示已登录。
4. 测试加固仍缺两处：`token_version` 非零值持久化的端到端断言、`validate()` 成功路径的独立单测；「登出先调后端后清本地」已由 `stores/auth.spec.js` 覆盖（第八轮）。
5. 成功登录会清空该 IP 的**全部**失败记录（`LoginRateLimiter.recordSuccess` 直接 `remove(ip)`），而 `POST /api/auth/register` 是公开接口：攻击者可「4 次错密码 + 1 次自己账号成功登录」循环，使失败窗口永远归零、锁定永不触发（这是 spec 的既定语义，不是实现偏差）。后续可考虑按 `(IP, username)` 计数，或只在该窗口的失败都指向同一账号时清零。
6. 前端登出在后端失败时静默：断网 / 5xx / 15s 超时时 `authApi.logout()` 的异常被 `catch {}` 吞掉，本地态已清且界面提示「已退出登录」，但服务端 `token_version` 未 +1，被窃 token 在剩余有效期内仍可用（默认 24h）；用户看不到任何失败提示。后续应在失败时提示「服务端会话未能失效，建议修改密码」或自动重试一次。

上述编号 1–6 依次对应本轮修复前的第 4、7、8、9、10、11 条；原第 1、2、3、5、6 条已在第八轮修复，修复记录见 §4.13；本轮新暴露的取舍也记在那里。

上线验收（2026-10-23）：

- 推送 `14ceda7` 后 Jenkins Build **#12** **SUCCESS**（轮询文件系统看到 `builds/12` 与 `Finished: SUCCESS`），`forum-backend` 重建后 `Up (healthy)`，镜像 `localhost/forum-backend:12` 与构建号一致。
- Flyway 自动执行 V4：`flyway_schema_history` 最新记录 `4|add user token version|t`；`information_schema.columns` 显示 `token_version` 为 `bigint / is_nullable=NO / column_default=0`；`SELECT count(*) FROM users WHERE token_version IS NULL` 返回 **0**。
- 冒烟：`GET /api/health` 直连 8086 返回 `{"success":true,"data":{"status":"UP"}}`，经 nginx（`Host: raspi`，`/forum/api/health`）返回 200；管理员 `POST /api/auth/logout` 返回 200，旧 token 再访问 `/api/auth/me` 返回 401，重新登录 200；新注册用户被封禁（`PUT /api/admin/users/11/status`）返回 200，其原 token 立即 401。
- 限流：`X-Real-IP: 203.0.113.9` 连续 5 次错密码，第 6 次返回 **429** 且响应头 `Retry-After: 89x~900`（锁定后立即执行为 900，稍等几秒为 899/89x，属正常；本次实测 900）。
- 环境偏差（如实记录）：在本机 `127.0.0.1:8086` 直连时，podman 端口映射（`127.0.0.1:${HOST_PORT:-8086}:8080`）让后端看到的 `remoteAddr` 是容器网关地址 `10.89.3.15` 而非环回，`ClientIpResolver` 因此忽略代理头，**所有客户端共用一个计数桶**：换 IP（198.51.100.7/8/9）仍是 429，经 nginx 登录同样 429。改用容器内环回直连复核后逻辑本身正确——`X-Real-IP: 203.0.113.11` 连续 5 次失败后该 IP 被锁（日志 `IP 203.0.113.11 登录失败 5 次，锁定 900000 ms`），换 `X-Real-IP: 203.0.113.12` 用正确密码返回 200。生产链路要让按 IP 限流真正生效，需让 nginx→后端连接保留环回源地址（或把信任条件放宽到容器私网段），已记入上方「已知取舍」第 4 条。（该全站共桶现象已在 Build #15 修复，见下方修复记录。）
- 全量测试：后端 20 个测试类 / 161 个用例（1 例 POSIX 权限用例在 Windows 上跳过）；前端 31 个 spec / 314 个用例；`VITE_BASE_PATH=/forum/ pnpm build` 通过。

修复记录（2026-10-24）：B.5 冒烟发现容器部署下限流失效——本机直连 `127.0.0.1:8086` 时换 `X-Real-IP`（198.51.100.7/8/9）仍是 429，改经 nginx 登录同样 429，日志为 `IP 10.89.3.15 登录失败 5 次`。根因：podman 端口映射使后端看到的 `remoteAddr` 是容器网关 `10.89.3.15`（私网），旧 `ClientIpResolver` 只在环回源时才读代理头，于是所有客户端落进同一个计数桶。修复：信任范围从「仅环回」放宽到「环回 + RFC1918 私网」（新增 `isTrustedProxy` / `isPrivateIpv4`），并让 `docker-compose.yml` 透传 4 个 `LOGIN_*` 阈值。测试：`ClientIpResolverTest` 6→8 例（新增私网网关采信 `X-Real-IP`、无私网代理头回落 `remoteAddr`），后端全量 163 例。部署验证：推送 `419be00` 后 Jenkins Build **#15** SUCCESS（镜像 `localhost/forum-backend:15`，容器 `Up (healthy)`）；重跑冒烟 B.5：`X-Real-IP: 203.0.113.9` 连续 5 次错密码均 401、第 6 次 **429** 且 `Retry-After: 892`；换 `X-Real-IP: 198.51.100.7` 用正确密码 **200**；经 nginx 正确密码 **200**；容器内 `LOGIN_*` 4 个变量均已注入（`LOGIN_MAX_FAILURES=5`、`LOGIN_WINDOW_MS=900000`、`LOGIN_LOCK_MS=900000`、`LOGIN_CLEANUP_INTERVAL_MS=60000`）。

### 4.13 第八轮：质量加固（2026-10-24）

- 设计：[2026-10-24-round8-quality-hardening-design.md](superpowers/specs/2026-10-24-round8-quality-hardening-design.md)
- 计划：[2026-10-24-round8-quality-hardening.md](superpowers/plans/2026-10-24-round8-quality-hardening.md)

**目标**：收敛第七轮双审遗留的 Important 项与 T12/T13 两条技术债；不新增用户可见功能（主题跨标签同步是唯一 UX 修复）。

**修复清单**：

| # | 问题 | 修法 | 提交 |
| --- | --- | --- | --- |
| 1 | 过滤器会话回查遇 `DataAccessException` 抛 500，响应体不是统一 `Result` | `isSessionAlive` 外层 catch → `log.debug` + `return false`（fail-closed，401 由 entry point 返回） | `f141928` |
| 2 | 封禁双保险没有各自独立的用例 | 直接改 `status`（不动版本）→ 401；封禁 → 解封 → 旧 token 仍 401（版本不回退） | `f141928` |
| 3 | 限流器阈值无校验；`cleanupExpired` 按 key 盲删 | 构造器 fail fast（消息含配置全名）；`windows.remove(key, window)` 身份 CAS | `31a320d` |
| 4 | 每条 429 一条不含 IP 的 WARN | 处理器降为 `log.debug`；事实日志保留在 `recordFailure` | `31a320d` |
| 5 | `Retry-After` 断言依赖「同一秒」（`isIn("59","60")`） | 解析为 long 后 `isBetween(1L, 60L)` | `31a320d` |
| 6 | 首页 `keyword` 不转义 LIKE 通配符（T13） | service 先 `escapeLike`，`searchIds` 两处 LIKE 加 `ESCAPE '!'` | `71a2c47` |
| 7 | 重复登出：双提示 + 整页跳转 | `authApi.logout` 带 `{ silent: true }`；401 静默分支仍清本地态 | `9b502dd` |
| 8 | 登出不 await，后端未返回就提示成功 | `handleUserCommand` 改 `async`，`await auth.logout()` 后再提示与跳转 | `9b502dd` |
| 9 | 主题选择不跨标签页同步（T12） | `useTheme` 模块级 `storage` 监听，回读 `localStorage`、不写回（避免回环） | `8de656b` |
| 10 | 首页搜索正文分支的 `ESCAPE` 无独立回归守护 | stage5 追加「仅正文含 `%`」命中对照（`body pct one/two`）并加 `&size=1` 覆盖 count 路径；`PostService` 空串注释纠正 | `ef791fb` |
| 11 | 登出时序与主题跨标签的测试守护不足 | `auth.spec` 用 deferred promise 锁「清态等待响应」；`useTheme.spec` 覆盖真实 `removeItem`、无关键忽略、坏值从显式 dark 起步 | `94b1970` |

**测试与验证**：后端 163 → **176** 用例 / 20 → **21** 类（新增 `JwtAuthenticationFilterTest` 7 例；`AuthInvalidationIntegrationTest` +2、`LoginRateLimiterTest` +3、`PostFlowIntegrationTest` +1）；前端 314 → **321** 用例 / 31 spec 不变（`api/index.spec` +2、`stores/auth.spec` +1、`App.spec` +1、`useTheme.spec` +3）。其中第 10、11 行对应的两个提交（`ef791fb`、`94b1970`）为规格/质量双审后追加的 test-only 收尾，只加固断言与用例写法，不改变上述用例数。端点 39 个不变，无迁移、无新配置项、无新增依赖。

**已知限制**：本轮没有新增部署要求（无迁移、无配置项、无 nginx 改动），见 `docs/deployment/README.md` §7 末尾。除 §4.12 保留的取舍外，本轮双审还记录以下遗留项（均不阻塞上线）：

1. `LoginRateLimiter` 的第 4 个阈值 `cleanup-interval-ms` **没有**构造器显式校验：它由 `@Scheduled` 的占位符消费，而不是构造器参数，因此 `=0` 不会得到前三个阈值那样的 fail fast 消息，而是在启动时抛裸 `IllegalArgumentException`（其余三个阈值已按第 3 行修法校验）。
2. `TagRepository.findWithPostCounts` 的 tag 关键词 LIKE 仍未转义（本轮 T13 只覆盖首页 `GET /api/posts?keyword=` 与既有的 `/api/posts/search`）；已作为技术债 T17 记入 §5.1。
3. `App.spec.js` 的登出用例会触发一次真实 jsdom XHR（`notifications.refreshUnread`，异常被 store 吞掉，不会产生未处理 rejection）；`auth.spec` 的「无关键忽略」断言依赖 `vi.resetModules()` 提供的模块实例隔离，而非真实的多标签页环境。
4. `api/index.js` 的成功分支（HTTP 200 + `success: false`）不识别 `silent`，仍会弹提示；后端目前没有这条返回路径，属防御性缺口。
5. `JwtAuthenticationFilter` 的 `DataAccessException` 分支 debug 日志未带 `userId`（生产默认 info 级，不可见），排障时需临时调级。
6. 本轮不处理 chunk 分包，构建仍会出现 `index-*.js` 约 878 kB 的体积警告（预期，见 spec §2.2）。

**上线验收（2026-10-24）**：

- 推送 `0e691b9` 后 Jenkins Build **#18** **SUCCESS**（pollSCM 触发，16:42:11–16:45:55 +08:00，约 3m44s）；`forum-backend` 重建后 `Up (healthy)`，镜像 `localhost/forum-backend:18` 与构建号一致；`localhost/forum-backend:17` 保留为回滚目标（部署脚本按 KeepLast2 清理，`:16` 已删除属预期）。
- 健康检查：直连 `127.0.0.1:8086/api/health` 与经 nginx（`Host: raspi`，`/forum/api/health`）均返回 200，`status":"UP"`。
- 登出回归：`me=200 → logout=200 → me(旧 token)=401 → 重复 logout=401`，与第七轮行为一致。
- 首页关键词（第八轮修复）：`GET /api/posts?keyword=100%25` 只命中标题含 `100%` 的帖子（`totalElements=1`），未命中 `1000`；`keyword=a_b` 只命中真正含字面量 `a_b` 的帖子，只含 `axb`/`acb` 的对照帖未被命中；`keyword=100` 命中 2 条（`1000` + `100%`），符合子串语义。
- 登录限流回归：`X-Real-IP: 203.0.113.9` 连续 5 次错密码均 401、第 6 次 **429** 且 `Retry-After: 900`（`登录失败次数过多，请 15 分钟后再试`）；换 `X-Real-IP: 198.51.100.7` 用正确密码 **200**，按 IP 隔离正常（只有测试保留网段被锁 15 分钟，不影响真实客户端）。
- Flyway：最新迁移 `4|add user token version|t`；`token_version` 为 `bigint / NO / 0`、无 NULL 值（第八轮无新迁移）。
- 部署面：容器内 `LOGIN_MAX_FAILURES=5`、`LOGIN_WINDOW_MS=900000`、`LOGIN_LOCK_MS=900000`、`LOGIN_CLEANUP_INTERVAL_MS=60000` 均生效；`/app` 含 `app.jar`、`logs`、`uploads`；`/forum/` 与 `/forum/posts/1` 返回 200，`/forum/assets/` 前缀正确，`/forum/api/notifications/stream` 无票据 401。
- 说明：raspi 的 `.env` 目前没有写 `LOGIN_*` 覆盖项，阈值来自 `docker-compose.yml` 的 `${LOGIN_...:-默认值}` 链；要调整阈值仍只需在 `.env` 追加对应键并重启容器（无需改镜像）。
- 冒烟测试数据：`admin` 在板块 1 新增 6 帖（id 21–26，标题以「R8冒烟-」开头），未新建用户；已在 §5.1 T8 记录，演示前可清理。
- 全量测试：后端 21 个测试类 / 176 个用例（1 例 POSIX 权限用例在 Windows 上跳过）；前端 31 个 spec / 321 个用例；`VITE_BASE_PATH=/forum/ pnpm build` 通过（既有 `index-*.js` 约 878 kB 警告为本轮预期）。
- 主题跨标签同步无法用 curl 验证（由 `useTheme.spec.js` 的 3 例 + 变异测试锁定）；人工验收：两个 `/forum/` 标签页在 A 页切换主题，B 页应立即跟随、无需刷新。

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
| 7 | 部署冒烟断言加固（grep 无匹配不再静默退出、assets Cache-Control 单条计数、README gzip 改 GET） | `deploy/deploy.sh`、`docs/deployment/README.md` | `fix: 修正部署缓存头冒烟的失败路径与计数断言` |

**体积对比（构建号 ⟨#20⟩，bytes/1000 口径）**

| 产物 | 第八轮基线 | 第九轮 | 变化 |
| --- | --- | --- | --- |
| 入口 JS `index-*.js` | 878,120 B（878.12 kB） | 84,431 B（84.43 kB） | -793,689 B（-90.4%） |
| 入口 JS gzip | 279,660 B（279.66 kB） | 31,680 B（31.68 kB） | -247,980 B（-88.7%） |
| 入口 CSS `index-*.css` | 369,967 B（369.97 kB） | 39,019 B（39.02 kB） | -330,948 B（-89.5%） |
| 入口 CSS gzip | 50,520 B（50.52 kB） | 7,150 B（7.15 kB） | -43,370 B（-85.8%） |
| 最大 JS chunk | 878,120 B | 233,744 B（233.74 kB） | -644,376 B（-73.4%） |
| JS 总量（24 chunk） | 1,344,673 B（1344.67 kB） | 835,869 B（835.87 kB） | -508,804 B（-37.8%） |
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
| JS 总量 | 950 kB | 实测 835.87 kB；必须低于基线 1344.67 kB |
| 9 个 CSS 选择器 | 全部存在 | `.el-button`、`.el-dialog`、`.el-table`、`.el-message-box`、`.el-notification`、`.el-pagination`、`.el-loading-mask`、`.el-form`、`.el-select` |

**测试与验证**

- 后端：176 → **177** 用例、21 个测试类不变（`TagFlowIntegrationTest` 8 → 9）。
- 前端：321 → **323** 用例、31 → **32** 个 spec（新增 `src/plugins/elementAutoImport.spec.js` 2 例，源码/配置断言，不做 mount）。
- 端点 39 不变、Flyway 仍 4 个版本、无新增运行时依赖（devDependencies 5 → 6，只加 `unplugin-vue-components`）。
- 构建：`MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build` 约 0.8 s（788 ms）成功，无 >500 kB 告警；`pnpm check:bundle` 全绿。
- 传输层：`/forum/assets/*.js` 返回 `Cache-Control: public, max-age=31536000, immutable` 且只有一条；`/forum/` 与 `/forum/posts/1` 的 `index.html` 返回 `Cache-Control: no-cache`；gzip 仍生效。

**已知限制**

1. 去掉全量注册后，`ElMessageBox`/`ElMessage`/`ElNotification` 的样式依赖 `main.js` 的手工清单；新增编程式 API 时必须同步补 `style/css`，否则弹窗会「无样式但能用」（已写进 `main.js` 注释与 README）。
2. 按需引入后每个视图 chunk 会带上自己用到的组件与样式（体积分散），因此「JS 总量」降幅远小于入口降幅；本轮不以总量为目标，只设回归上限。
3. 实测 `<el-config-provider :locale="zhCn">` 的 locale 会被 `ElMessageBox` 取到（ConfigProvider 写模块级全局 fallback，`ElMessageBox` 以 `inject(configProviderContextKey, globalConfig)` 兜底），按钮文案本就是「取消/确定」；三处 `confirm` 仍显式传中文文案，作为「provider 挂载前先弹框」这一极端时序的防御。
4. 入口页 `no-cache` 依赖 `location = /forum/index.html` 的精确匹配；若将来把 SPA 目录改成 nginx 的 `root` 而非 `alias`，需要重新核对这个 location（`alias` 与 `try_files` 组合对路径敏感）。这两个 location 各自定义了 `add_header`，按 nginx 语义不再继承 server 级 `add_header`；上线后需 `sudo nginx -T | grep -n add_header` 确认没有丢掉安全头。
5. `<el-config-provider>` 没有引入 `size`/`zIndex` 等其它全局配置，保持与第八轮一致的视觉。
6. **测试期不挂载按需插件**：`vite.config.js` 在 `mode === 'test'` 时只保留 `vue()`，因为既有的 31 个组件/页面 spec 用 `global.stubs` 拦截 `el-*`（第 32 个 spec 是源码/配置断言，不挂载组件）；若测试期注入真实组件，实测 63 例 / 7 个 spec 会红。代价是单测不覆盖构建期解析链，由 `elementAutoImport.spec.js`（源码/配置断言）+ `pnpm check:bundle`（体积 + 9 个 CSS 选择器覆盖）+ 部署冒烟共同守护。
7. `check-bundle.mjs` 只接受 `--dist <dir>` 形式；写成 `--dist=<dir>` 或未知参数会被忽略并检查当前目录的 `dist`（CI 不传参，不受影响）。
8. 预算脚本只拦「构建体积回归」，不拦「视觉回归」；按需引入后浏览器端组件的真实渲染仍需部署后人工回归（见下方验收）。
9. `deploy/deploy.sh` 的首页探针仍分两处（`:111` 的 `curl -fsS … | grep -q` 与 `:124` 带状态码的抓取）：前者对 5xx/连接失败会先以「VITE_BASE_PATH 没生效？」退出，导致新增的 HTTP 状态诊断只对「200 但无 js」可达；且 `curl | grep -q` 在 `pipefail` 下可能因 grep 提前退出让 curl 收到 SIGPIPE(23) 而偶发误退（本轮未引入，概率低）。`asset_hits` 统计的是匹配行数（单行压缩产物里多个引用仍为 1）；`index_headers`、`uploads_code`、`stream_code` 三处命令替换尚未补 `|| true`——失败仍是红，只是丢失对应 `die` 文案。属既有诊断文案问题，不影响「坏环境必红」，待后续清理。

**上线验收（2026-10-25）**

- 构建：Jenkins **Build #20** SUCCESS（提交范围 `db16214..3abfa85`，镜像 `localhost/forum-backend:20`，容器 `Up (healthy)`）；构建日志含 `pnpm check:bundle` 且预算是全绿（`>500 kB` 告警为 0）。部署由 `deploy.sh` 自动完成，新增的缓存头断言与首页/资源探针全部通过。回填提交 `8e6d71b` 触发的 **Build #21** 亦 SUCCESS（docs-only 重建，镜像 `:21` healthy，产物字节数与本记录一致），此后不再单独记录构建号。
- 体积口径：下表「入口 JS/CSS」只统计 `dist/index.html` 直接引用的 `index-*` 文件（check-bundle 同口径）；首屏实际下载还包括 `index.html` 的 `modulepreload`（`_plugin-vue_export-helper` 233,744 B、`css-*.js` 51,751 B）与首屏 CSS links，合计首屏 JS **369,926 B（−57.9%）**、首屏 CSS **81,766 B（−77.9%）**。入口单文件 −90.4% 是 `index-*` 自身的降幅，不要读成首屏下载量降幅。
- 线上产物（`/pd/Program/forum/frontend/assets/`）：入口 JS `index-Dm2pwhnr.js` **84,431 B**、入口 CSS `index-DZY9d3G8.css` **39,019 B**、JS chunk 24 个、assets 目录合计 **1,021,535 B**；与本地构建字节数一致（hash 不同因构建时间戳）。
- 缓存头实测（`curl -H 'Host: raspi'`）：
  - `/forum/assets/index-Dm2pwhnr.js`：`HTTP/1.1 200`、**1 条** `Cache-Control: public, max-age=31536000, immutable`、`Content-Encoding: gzip`。
  - `/forum/` 与 `/forum/posts/1`：`Cache-Control: no-cache`（SPA 回退与首页都命中精确 location）。
  - `/forum/uploads/nope.png`：404（无 Cache-Control，属正常）。
- 会话回归：登录 200 → `me` 200 → `logout` 200 → 旧 token `me` 401（V4 版本号机制未受影响）；`/forum/api/health` 经 nginx 200。
- 人工回归（Playwright + Edge，1440×1000，真实部署环境，截图见执行记录）：
  1. 首屏样式：帖子列表 10 条正常渲染，`.el-button` 圆角 4px、`.el-tag` 4 个、`.el-input__wrapper` 高 32px，均为 Element Plus 默认外观。
  2. 分页中文：管理页表格 11 行、分页两页；分页按钮 `aria-label` 为「上一页」「下一页」，`el-table` 单元格颜色正常。
  3. 删除确认框：标题「提示」、正文「确定删除该帖子吗？（软删除，不可恢复显示）」、按钮「取消」「确定」，样式完整（MessageBox 的 `style/css` 手工账生效）。
  4. `v-loading`：延迟帖子列表请求 3 s，首页出现 `.el-loading-mask`（`rgba(255,255,255,0.9)`）与 spinner，加载结束消失。
  5. 暗色模式：下拉项「跟随系统/浅色/深色」中文；切深色后 `html.dark` 生效、页面底色 `rgb(245,246,248) → rgb(10,10,10)`、卡片 `rgb(29,30,31)`、详情页暗色正常。
  6. 控制台：除「点取消」时 ElMessageBox 自身 `cancel` 拒绝被记为一条 error 外，无其它 error/warning；无「组件已解析/样式缺失」类告警。
- 结论：第九轮上线验收通过；唯一遗留是 `deploy.sh` 首页探针的诊断可达性与 `ElMessageBox` 取消时的控制台噪音（均记入已知限制）。

---

## 5. 待办与后续计划

### 5.1 技术债与风险（建议优先处理）

| # | 事项 | 严重度 | 说明 |
| --- | --- | --- | --- |
| T1 | **数据库密码仍在 git 历史中** | 高 | 自 `6eecbdc` 起 `application.yml` 含明文密码。2026-10-19 部署引导时已把 raspi 上 `forum` 角色密码重置为随机值（历史里那份仅对旧环境有效），但明文字符串仍在历史中。彻底处理需 `git filter-repo` 改写历史，属不可逆操作，需明确授权后执行 |
| T2 | **无 PR 级 CI** | 低 | 主分支的构建/部署已由 raspi Jenkins 承担（2026-10-19，见 §4.7）。主机没有公网入口，无法做 GitHub PR 触发，PR 级校验仍需 GitHub Actions（后端 JDK 17 + H2；前端 pnpm test + build） |
| T3 | **无前端 E2E 测试** | 中 | 真实浏览器验证目前靠一次性 Playwright 脚本（在 `C:\Temp\jf-pw`，未入库）。建议把关键路径（登录→发帖→评论→通知跳转）固化为仓库内 E2E |
| T4 | ~~**JWT 无法主动失效**~~ | 已关闭 | 2026-10-23 第七轮：`users.token_version` + 过滤器回查，登出与封禁各 +1，旧 token 立即 401（见 §4.12）。角色变更仍不即时，属遗留的小口子 |
| T5 | ~~**登录接口无速率限制**~~ | 已关闭 | 2026-10-23 第七轮：按客户端 IP 的失败窗口 + 锁定，429 带 `Retry-After`（见 §4.12）。计数在单实例内存里，多实例需换共享存储（见 §5.2） |
| T6 | **REPLY 通知锚点只覆盖第一页评论** | 低 | 详见 §6 |
| T7 | **数据库连接串硬编码 `raspi`** | 低 | `application.yml` 默认值仍是 `localhost`（面向本地开发）；实际部署形态已写入 `.env.example` 与 `docs/deployment/README.md`（容器内 `host.containers.internal:5432`）。代码内默认值没有改成 `raspi`，避免本地开发被污染 |
| T8 | **残留的冒烟测试数据** | 低 | 真实库中存在 `smoke_user`、`t4_op`、`t4_ap`、板块「冒烟测试板块」等手工测试数据；第八轮冒烟又在板块 1 新增 6 帖（id 21–26，标题前缀「R8冒烟-」）。交付演示前应一并清理 |
| T9 | ~~Docker 镜像未经实际构建验证~~ | 已关闭 | 2026-10-19 起在 raspi 上由 Jenkins 用 podman 实际构建并部署（`--format docker` 保留 HEALTHCHECK），见 §4.7 |
| T10 | ~~前端未容器化~~ | 已关闭 | 需求明确前端不进容器：由主机 nginx 直接托管 dist（见 §4.7），不再作为技术债 |
| T11 | **头像文件不在备份范围** | 中 | 头像落在宿主机 `/pd/Program/forum/uploads/`，数据库备份不含它；恢复时需单独 `tar` 该目录并保留属主，再跑 `deploy.sh` 修正权限（`podman unshare chmod a+rX`）。孤儿文件（换头像/删号后残留）目前无清理任务，见 §4.9 |
| T12 | ~~**主题选择不跨标签页同步**~~ | 已关闭 | 2026-10-24 第八轮：`useTheme` 增加模块级 `storage` 监听，回读 localStorage 且不写回（避免回环），坏值/清空回退 system（见 §4.13）。原记录：主题存 localStorage 的 jf_theme，但未监听 storage 事件；同源多标签下切换主题不会互相更新，刷新后一致。修法：在 useTheme 里监听 window.addEventListener('storage', ...) |
| T13 | ~~**首页关键词过滤不转义 LIKE 通配符**~~ | 已关闭 | 2026-10-24 第八轮：`PostService.list` 先 `escapeLike`，`searchIds` 两处 LIKE 加 `ESCAPE '!'`，与 `/api/posts/search` 同一约定（见 §4.13）。原记录：首页 GET /api/posts?keyword= 走 searchIds 的 LOWER(...) LIKE LOWER(CONCAT('%', :keyword, '%'))，未转义 %/_，搜「100%」会命中「1000」。本轮只给新搜索接口做了转义；修首页需同步改 JPQL 与前端提示，属独立小改动 |
| T14 | **搜索无分词、无相关度排序** | 中 | 采用 pg_trgm 子串匹配：中文不分词，1–2 个字的关键词的 trigram 覆盖不足，规划器会放弃索引改走顺序扫描；排序只有「标题命中优先 + 时间倒序」，没有 `ts_rank`/`similarity` 之类相关度。数据量增长后的升级路径是 `tsvector`（需分词扩展）或两阶段检索 |
| T15 | **`posts.content` 无长度上限** | 低 | 正文列是 `TEXT` 且无应用层长度校验；超长正文会放大 `snippet` 截取与索引维护成本，也让 `pg_trgm` 的 GIN 索引更大。可加与标题一致的长度提示或截断策略 |
| T17 | ~~**标签检索的 LIKE 通配符未转义**~~ | 已关闭 | 2026-10-25 第九轮：`TagService` 先 `escapeLike`（转义 `!`/`%`/`_`），`TagRepository.findWithPostCounts` 的 JPQL 加 `ESCAPE '!'`；`TagFlowIntegrationTest` 新增 `@Order(9)` 覆盖 `100%`、`a_b`、`!` 三个关键词（见 §4.14）。原记录：第八轮 T13 只覆盖首页 `GET /api/posts?keyword=` 与既有的 `/api/posts/search`；`TagRepository.findWithPostCounts` 的标签关键词 LIKE 仍把 `%`/`_` 当通配符（`GET /api/tags?keyword=`），搜「a_b」会命中「axb」。修法同 T13：service 先 `escapeLike`，JPQL 加 `ESCAPE '!'` |

### 5.2 候选功能（第七轮新增「限流共享存储」一项）

第二轮明确列为「不做」的功能，各自需要独立设计：

| 功能 | 价值 | 预估工作量 | 备注 |
| --- | --- | --- | --- |
| ~~Markdown 渲染~~ ✅ 已完成 | 高 | 中 | 第三轮已交付：客户端 `markdown-it` 渲染 + DOMPurify 白名单 + 编辑页实时预览（§4.8） |
| ~~标签检索页~~ ✅ 已完成 | 中 | 小 | 第三轮已交付：标签总览 + 按标签筛选帖子，两个公开 GET 接口（§4.8） |
| ~~头像上传~~ ✅ 已完成 | 中 | 中 | 第四轮已交付：JPEG/PNG 校验 + EXIF 校正 + 256×256 裁剪、宿主机上传目录、全站头像展示与移除（§4.9） |
| ~~深色模式~~ ✅ 已完成 | 低 | 小 | 第四轮已交付：双套设计令牌 + 跟随系统/手动切换 + 首屏防闪白（§4.9） |
| ~~全文搜索~~ ✅ 已完成 | 中 | 中 | 第五轮已交付：公开 `GET /api/posts/search` + pg_trgm GIN 部分索引、独立 `/search?q=` 结果页与顶栏入口（§4.10） |
| ~~WebSocket 实时通知~~ ✅ 已完成 | 中 | 大 | 第六轮已交付：以 SSE 实现（而非 WebSocket）一次性 ticket 鉴权 + 断线指数退避重连 + 120s 轮询兜底，见 §4.11 |
| 限流改共享存储（多实例） | 中 | 中 | 当前 `LoginRateLimiter` 的失败计数在单实例内存，多实例部署时各算各的、重启清零（§4.12、§6）。改法：把 `Window` 换成 Redis 的 `INCR`+`EXPIRE` 或 Bucket4j 的分布式后端，接口保持不变 |

第三轮已取前两项（见 §4.8），第四轮已取头像上传与深色模式（见 §4.9），第五轮已取全文搜索（见 §4.10），第六轮已取实时通知（见 §4.11），第七轮不再新增业务功能、只做安全加固（见 §4.12）；「限流改共享存储」是加固后新暴露的运维型候选，等到真的多实例部署时再取。后续新功能需重新评估价值与工作量，按同样的「审计 → 计划 → 逐任务执行 → 验证 → 提交」流程推进。

> 第八轮同样不加业务功能，只做质量加固（见 §4.13）；「限流改共享存储」仍是多实例部署的前置条件。

> 第九轮同样不加业务功能，只做前端性能优化与 T17（见 §4.14）；双审顺带记录了两条候选：两处 `escapeLike`（`PostService`/`TagService`）重复 11 行，将来若改转义字符需双改；`check-bundle.mjs` 的参数解析只认 `--dist <dir>` 形式。

---

## 6. 已知问题与限制

| 问题 | 影响 | 现状 |
| --- | --- | --- |
| REPLY 通知的评论锚点只覆盖第一页评论 | 帖子详情每页 10 条根评论。若目标根评论排在第 11 条之后，页面里没有对应元素 | 不静默失败：评论数 > 10 时提示「目标评论可能在后面的评论分页里，可翻页查看」。评论已删除也命中同一提示（无法区分），属已接受的误报 |
| 点赞/收藏不改变自己的未读数 | 后端对自发行为不写通知（避免自己给自己发通知） | 有意设计，非缺陷 |
| 浏览量在每次详情页请求时 +1 | 刷新即累加，非去重 UV | MVP 简化，未做去重 |
| 无国际化 | 界面文案硬编码中文 | 第一轮非目标 |
| `AdminView` 桌面表格与窄屏卡片仍需各维护一份 | 加字段要改 2 处（原为 4 处，第二轮已收敛一半） | 进一步收敛需抽列定义，属结构性改动 |
| Markdown 会解释历史纯文本帖子 | 正文里恰好像 Markdown 的写法（`# 标题`、`*斜体*`、`[文字](链接)`）被渲染成格式而非原样显示 | 单换行已转 `<br>` 以保留换行观感；需要原样展示可转义或用代码块；未做「按帖子版本区分渲染」 |
| 无独立 404 标签页，标签聚合在应用层 | 未知标签与「标签下无帖子」共用空态；标签数量级大时聚合排序分页在应用层完成 | 已接受：标签数据量小；接口语义为「未知标签返回空页」（见 §4.8） |
| 实时通知是单实例的 | SSE 连接注册表在应用内存中；多实例部署时事件只从产生通知的那个实例推给连在它上面的客户端 | 当前单容器部署不构成问题；发版/重启时在线连接断开，客户端自动换票重连并校准未读数 |
| SSE 依赖反向代理正确配置 | 漏配 `proxy_buffering off` 表现为通知要等十几秒才到，漏配 `proxy_read_timeout` 表现为连接每分钟断一次 | snippet 内置精确 location + `deploy.sh` 冒烟断言 401；部署手册有「通知延迟」排障条目 |
| SSE 断开期间的新通知不补推 | 断线期间发出的事件已丢失，重连只校准未读数，不重放列表 | 已接受：刷新或重新进入通知页即可看到；不做服务端离线队列（T16） |
| 登录限流是单实例的 | 失败计数在应用内存（`LoginRateLimiter` 的 `ConcurrentHashMap`），多实例部署时各实例独立计数、重启清零 | 当前单容器部署不构成问题；要跨实例一致需换共享存储（§5.2） |

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
| 部署目标 | **raspi（Debian 12/aarch64）+ Jenkins + rootless podman + 系统 nginx** | 主机是既有环境：Jenkins 占着 8080，已有容器/镜像/数据库；复用比新建省内存与磁盘，也符合「按已有镜像调整」的要求 |
| 前端托管 | **nginx 直出 dist，不做前端镜像** | 需求 4 明确；静态发布用 `rsync --delete` 比镜像更新简单，且不额外占内存 |
| 数据库 | **复用主机已有 `javaforum` 库** | 数据保留、迁移成本最低；Flyway checksum 与当前迁移一致、`DataInitializer` 幂等；代价是依赖已有 postgres 容器生命周期（已写入已知限制） |
| CI 触发 | **SCM 轮询 H/5 + 手动**，不用 webhook | 主机没有公网入口，GitHub webhook 无法送达；轮询最长延迟约 5 分钟，可接受 |
| 镜像构建策略 | jar 在 Jenkins 构建，Dockerfile 只组装运行时 | 主机没有 maven 镜像；`--format docker` 保留 HEALTHCHECK；小内存机不采用分层提取 |
| 搜索实现 | **pg_trgm 子串匹配**，不用 `tsvector` | 中文没有空格分词，`to_tsvector('simple', ...)` 只能整段命中；pg_trgm 的三元组索引对「包含」语义有效，且 PostgreSQL 15 的 pg_trgm 是 trusted 扩展，`forum` 角色可直接 `CREATE EXTENSION` |
| 搜索排序 | **标题命中优先 + `createTime` 倒序**，不做相关度评分 | 相关度分数用户无法预期；「标题命中的排前面」规则可解释、可测试，也避免引入 `similarity()` 的排序开销 |
| 搜索片段 | **服务端截窗口、前端清洗与高亮** | 列表 DTO 保持轻量（不带正文）；Markdown 清洗、省略号与 `<mark>` 只在 `utils/search.js` 实现一次，避免 Java/JS 两套规则漂移 |
| 实时通知传输 | **SSE**，不用 WebSocket | 只要服务端单向推送；SSE 走普通 HTTP、nginx 原生支持、`EventSource` 自带重连语义，省掉 WS 握手与网关升级配置 |
| SSE 鉴权 | **一次性 ticket**（60s、单次消费）而不是查询串带 JWT | `EventSource` 不能自定义请求头；长期 JWT 放进 URL 会进 access log，ticket 泄露窗口小且消费后立即失效 |
| 通知推送时机 | **事务提交后** `afterCommit` 推送 | 回滚不能推「不存在的点赞/回复」；无事务同步时直接推，便于单测 |
| 实时通知降级 | **断线退回 120s 轮询**，重连成功即停 | 保留改造前的兜底行为，避免 SSE 不可用时完全收不到通知 |
| Token 主动失效 | **用户级 `token_version` 版本号**，不用 Redis 黑名单 | 单实例部署、不新增中间件；登出/封禁各 +1，过滤器每请求比一次即失效，还能一次性踢掉某用户全部设备；代价是每请求多一次只取 3 列的主键查询 |
| 登录限流位置 | **Controller 层计数 + 内存滑动窗口**，不放认证过滤器 | 只统计真正的凭据错误（`BadCredentialsException`），token 过期/格式错的 401 不占用失败额度；锁定判断在 BCrypt 之前，被锁时不做无谓的哈希计算 |

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
| 2026-10-25 | docs | 回填第九轮构建与冒烟验收（Build #20、缓存头实测、人工回归） | 本次提交 |
| 2026-10-25 | docs | 记录第九轮前端性能优化（按需引入、产物预算与传输层） | `3abfa85` |
| 2026-10-25 | fix | 修正部署缓存头冒烟的失败路径与计数断言 | `d474b18` |
| 2026-10-25 | fix | 标签搜索转义 LIKE 通配符（T17） | `371c2cc` |
| 2026-10-25 | perf | 静态资源长缓存与入口页禁用缓存 | `0ff12fe` |
| 2026-10-25 | perf | 增加产物预算检查并接入构建流程 | `a83806c` |
| 2026-10-25 | perf | 按需引入 Element Plus 并配置本地化提供者 | `8446d3a` |
| 2026-10-25 | docs | 新增第九轮前端性能优化设计与计划 | `07736d8` |
| 2026-10-24 | docs | 回填第八轮上线验收（Build #18、冒烟结果与提交哈希） | `db16214` |
| 2026-10-24 | docs | 记录第八轮质量加固（测试基线、已知限制与变更日志） | `0e691b9` |
| 2026-10-24 | test | 加固登出时序与主题跨标签的回归守护 | `94b1970` |
| 2026-10-24 | test | 补齐首页搜索正文分支的通配符转义守护 | `ef791fb` |
| 2026-10-24 | feat | 主题选择跨标签页同步（T12） | `8de656b` |
| 2026-10-24 | fix | 前端登出静默处理 401 并保证清态时序 | `9b502dd` |
| 2026-10-24 | fix | 首页关键词过滤转义 LIKE 通配符（T13） | `71a2c47` |
| 2026-10-24 | fix | 登录限流清理竞态与配置校验加固 | `31a320d` |
| 2026-10-24 | fix | JWT 过滤器会话回查失败按未认证处理并补测试 | `f141928` |
| 2026-10-24 | docs | 第七轮终审记录（已知取舍补第 10/11 条、计划实施偏差、文档订正） | `d56a8aa` |
| 2026-10-24 | docs | 回填第七轮登录限流修复验证（Build #15 与 B.5 重跑） | `4afc9d7` |
| 2026-10-24 | fix | 修复容器部署下登录限流未按真实客户端 IP 隔离（信任私网代理源 + compose 透传 LOGIN_*） | `419be00` |
| 2026-10-23 | docs | 第七轮安全加固上线验收（JWT 主动失效与登录限流、Build #13）与变更日志回填 | `68d4e10` |
| 2026-10-23 | docs | 记录第七轮安全加固（README、开发记录、部署手册、测试基线） | `14ceda7` |
| 2026-10-23 | feat | 第七轮前端：登出调用后端失效接口，失败也清本地态 | `ff99b73` |
| 2026-10-23 | feat | 第七轮后端：登录失败按 IP 限流并返回 429 | `3cc36af` |
| 2026-10-23 | feat | 第七轮后端：封禁与登出即时失效旧 token | `1fe3098` |
| 2026-10-23 | feat | 第七轮后端：用户 token 增加版本号并写入 JWT | `b700803` |
| 2026-10-22 | docs | 第六轮实时通知上线验收（Build #10、SSE 全链路实测）与变更日志回填 | 本次提交 |
| 2026-10-22 | docs | 记录第六轮实时通知（SSE）功能（README、开发记录、部署手册、计划验收步骤） | `9ead0d0` |
| 2026-10-22 | fix | 修正通知横幅令牌与点击断言强度 | `b3a50a5` |
| 2026-10-22 | feat | 第六轮通知页：SSE 事件即时插入第 1 页、新通知横幅、`viewOpen` 生命周期 | `a73fe41` |
| 2026-10-22 | test | 锁定旧世代取票 401 不污染新会话 | `798a202` |
| 2026-10-22 | fix | 修正 SSE 账号切换与取票竞态的生命周期处理 | `cda1b7b` |
| 2026-10-22 | feat | 第六轮前端：SSE 连接、指数退避重连、120s 兜底轮询与实时角标 | `257cc57` |
| 2026-10-22 | fix | 加固通知推送失败隔离与事务后推送测试 | `a83df85` |
| 2026-10-22 | feat | 第六轮后端：通知创建事务提交后实时推送 | `9f37a48` |
| 2026-10-22 | fix | 修正 SSE 连接注册表的锁内 I/O 与断连处理 | `5c2047f` |
| 2026-10-22 | feat | 第六轮后端：SSE 通知基础设施（一次性 ticket 鉴权 + 连接注册表） | `104d288` |
| 2026-10-22 | docs | 记录第六轮实时通知实现计划 | `6aa1d52` |
| 2026-10-22 | docs | 记录第六轮实时通知（SSE）设计 | `a677986` |
| 2026-10-21 | docs | 第五轮全文搜索上线验收（Build #8 成功、pg_trgm 索引与冒烟实测）与变更日志回填 | 本次提交 |
| 2026-10-21 | docs | 记录第五轮全文搜索功能（README、开发记录、部署手册） | `67c13a6` |
| 2026-10-21 | fix | 修正搜索框码点上限与同词回车短路 | `debe1d7` |
| 2026-10-21 | fix | 修正搜索页状态清理与顶栏搜索交互细节 | `4f59db8` |
| 2026-10-21 | feat | 第五轮搜索前端：`postApi.search`、顶栏 `HeaderSearch`、`/search` 结果页、命中词高亮与正文片段 | `aac049b` |
| 2026-10-21 | fix | 修正搜索片段清洗的代码块与表格误伤 | `2e4027f` |
| 2026-10-21 | fix | 修正搜索工具的大小写折叠与片段清洗边界 | `c39cc60` |
| 2026-10-21 | feat | 第五轮搜索文本工具：`highlightSegments` 与 `buildSnippet` | `29e14ca` |
| 2026-10-21 | fix | 加固全文搜索片段边界与排序稳定性 | `7658bca` |
| 2026-10-21 | docs | 优化第五轮实现计划的自审细节 | `2a2c8ce` |
| 2026-10-21 | feat | 第五轮全文搜索后端：`GET /api/posts/search`、标题优先排序、pg_trgm 索引与测试隔离 | `5fa2644` |
| 2026-10-21 | docs | 新增第五轮全文搜索实现计划 | `070c3c4` |
| 2026-10-21 | docs | 记录第五轮全文搜索设计 | `ad8ad9e` |
| 2026-10-20 | docs | 第四轮上线验收记录（Build #6 成功、头像全链路实测）与变更日志回填 | `b3533e7` |
| 2026-10-20 | docs | 记录第四轮深色模式与头像上传功能（README、开发记录、部署手册） | `dc961a5` |
| 2026-10-20 | chore | 部署支持头像上传目录：`UPLOAD_HOST_DIR` 卷、目录创建与属主/权限修正、nginx `/forum/uploads/` 直出 | `85940c3` |
| 2026-10-20 | feat | 第四轮头像上传前端：资料页上传/移除、全站 `UserAvatar`、站内 URL 拼接与失败回退 | `c171279` |
| 2026-10-20 | feat | 第四轮头像上传后端：`POST/DELETE /api/users/me/avatar`、魔数校验 + EXIF 校正 + 256×256 裁剪、原子落盘 | `6fad63c` |
| 2026-10-20 | feat | 第四轮深色模式：双套设计令牌、跟随系统/手动切换、首屏防闪白、暗色代码高亮 | `1e3b94c` |
| 2026-10-19 | feat | 第三轮功能：Markdown 渲染 + 标签检索（`7e0f9ab` 工具与样式、`3089446` 详情页、`936347e` 编辑页预览、`1bbd026` 标签接口、`aacc70e` 标签页与公共帖子行） | `7e0f9ab`/`3089446`/`936347e`/`1bbd026`/`aacc70e` |
| 2026-10-19 | docs | 部署验收记录（Build #2 成功与实测结果）与变更日志回填 | `5a81f3f` |
| 2026-10-19 | fix | 适配 raspi 无内存 cgroup：去掉 compose `mem_limit`，`JAVA_OPTS` 改显式 `-Xmx512m`，`deploy.sh` 启动后立校验容器状态 | `771990d` |
| 2026-10-19 | feat | 部署环境化：raspi Jenkins + rootless podman + nginx 自动化部署（运行时单阶段 Dockerfile、podman-compose 兼容 compose、Jenkinsfile + deploy.sh + nginx snippet、复用主机 Postgres、子路径 `/forum/` 发布） | `1554869` |
| 2026-10-18 | fix | 修复 `base` 非法值（Vite 静默降级为 `/`）：改为 `VITE_BASE_PATH` 可控，路由与 axios 前缀统一由 `BASE_URL` 派生，新增 `viteConfig.spec.js` 6 个守卫 | `8c687ac` |
| 2026-10-18 | feat | 后端容器化：新增 `Dockerfile`（多阶段 + 分层 + 非 root + 健康检查）、`.dockerignore`、`docker-compose.yml`，并在 README 增加「运行（Docker）」章节 | `830651e` |
| 2026-10-18 | docs | 补充变更日志的自引用说明与提交数同步规则 | `cbb7cad` |
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
