# JavaForum 体验迭代（第一轮）设计文档

- 日期：2026-10-16
- 状态：已确认（含 2 处后端小改动）
- 范围：视觉信息层级（A） + 手机窄屏适配（B） + 通知与互动闭环（C）
- 明确不做：Markdown 渲染、标签检索、公开个人主页、头像上传、深色模式
- 用户决策：接受后端补 `comment_id`（选项一），通知精确锚定被回复评论
- 用户决策（追加）：接受后端补 `liked` / `favorited`（选项一），使互动状态在刷新后仍正确

## 1. 背景与目标

JavaForum 六个阶段的 MVP 已完成：后端 Spring Boot 3.5 + PostgreSQL，前端 Vue3 + Vite + Pinia + Element Plus，
具备注册登录、板块与帖子、评论楼中楼、点赞收藏、通知、个人中心与管理后台。当前问题不在功能缺失，而在体验：

- 样式数值散落在各视图的 `<style scoped>` 中，卡片圆角、间距、字号不统一，信息层级靠字号硬撑。
- 布局按桌面宽度写死，窄屏下板块导航、帖子元信息、评论缩进与后台表格会溢出或挤压。
- 通知只能阅读，不能跳转到对应帖子与评论；未读数只在 60 秒轮询后更新；点赞/收藏状态在列表与详情之间不同步。

本轮目标：在不新增业务功能、不改数据库结构的前提下，把上述三类体验问题收敛，并建立可复用的样式令牌与前端测试基线。

## 2. 非目标

- 除通知补 `commentId` 外，不引入其他后端接口变更；如发现必须改动，先回到设计确认。
- 不做主题切换与深色模式。
- 不做国际化。
- 不调整既有权限模型与安全配置。

## 3. 架构与分层

前端新增三个关注点，保持视图只负责编排：

```
frontend/src/
├── styles/
│   ├── tokens.css        # 设计令牌：颜色、间距、圆角、阴影、字号阶梯、断点变量
│   └── responsive.css    # 通用响应式工具类（仅在工具类确实复用≥3 处时新增）
├── composables/
│   ├── useResponsive.js  # 基于 matchMedia('(max-width: 768px)') 的 isMobile 判定
│   └── usePostActions.js # 点赞/收藏的统一状态来源与切换逻辑
├── utils/
│   └── notificationLink.js # 通知 → 路由目标（path + hash 锚点）的纯函数映射
└── stores/
    ├── auth.js           # 既有，保留
    └── notifications.js  # 新增：未读数与列表状态，供顶栏角标与通知页共用
```

### 3.1 设计令牌（styles/tokens.css）

以 CSS 自定义属性承载，挂在 `:root`。Element Plus 变量通过覆盖同名 `--el-*` 变量对齐主色，避免逐组件穿透样式。

- 颜色：`--jf-color-primary`（沿用 Element Plus 蓝 `#409eff`）、`--jf-color-text-1/2/3`、`--jf-color-border`、`--jf-color-bg-page`、`--jf-color-bg-card`
- 间距阶梯：`--jf-space-1..6` 对应 4/8/12/16/24/32px
- 圆角：`--jf-radius-sm/md/lg` 对应 4/8/12px
- 阴影：`--jf-shadow-card`、`--jf-shadow-hover`
- 字号阶梯：`--jf-font-title-lg`（22px 详情标题）、`--jf-font-title-md`（16px 列表标题）、`--jf-font-body`（14px）、`--jf-font-meta`（12-13px 元信息）
- 断点：`--jf-breakpoint-mobile: 768px`（CSS 侧供参考；JS 侧在 `useResponsive` 中以常量 768 保持一致，两处数值必须在同一提交内一起修改）

### 3.2 响应式策略

单一断点 `768px`。桌面（>768px）保持现有左右双栏；窄屏（≤768px）行为如下：

| 区域 | 桌面 | 窄屏（≤768px） |
| --- | --- | --- |
| 首页板块导航 | 卡片内标签换行 | 单行横向滚动，不换行，隐藏滚动条 |
| 首页工具栏 | 搜索框 + 发帖按钮同排 | 搜索框占满一行，发帖按钮收为图标按钮 |
| 帖子列表项 | 标题行与右侧统计同排 | 统计移到元信息行末尾，`flex-wrap` 允许换行 |
| 帖子详情操作区 | 按钮同排 | 按钮同排但允许换行，触控高度 ≥36px |
| 评论缩进 | 子回复左缩进 32px | 缩进收窄至 16px，左侧竖线保留 |
| 管理后台表格 | Element Plus 表格 | 表格改为卡片式堆叠（每行一张卡片，字段名在前） |
| 顶栏 | 品牌 + 通知 + 用户下拉 | 保留同样元素，缩小间距；品牌文字在极窄屏隐藏仅留图标 |

实现方式：优先用 CSS 媒体查询与 flex 换行；需要 JS 分支的仅一处——管理后台表格与卡片两种渲染二选一，由 `useResponsive().isMobile` 决定。

### 3.3 通知与互动闭环

**通知跳转映射（utils/notificationLink.js）**

纯函数 `resolveNotificationTarget(notification)` 返回 `{ path, hash }` 或 `null`：

- `REPLY` 且 `postId` 存在 → `{ path: '/posts/{postId}', hash: '#comment-{commentId}' }`
- `LIKE` / `FAVORITE` 且 `postId` 存在 → `{ path: '/posts/{postId}', hash: '' }`
- `SYSTEM` 或无 `postId` → `null`（不可点击，仅展示）

约束：`NotificationResponse` 当前不含 `commentId`。经用户确认（选项一），本轮接受后端小改动补该字段：

- **V2 Flyway 迁移**：`ALTER TABLE notifications ADD COLUMN comment_id BIGINT REFERENCES comments(id) ON DELETE SET NULL;`
- **实体与写入**：`entity/Notification.java` 增加 `comment` 关联（`@ManyToOne(LAZY)`，可空）；`CommentService` 在创建 REPLY 通知时写入被回复的评论。
- **响应**：`NotificationResponse` 增加 `commentId`；`NotificationService.toResponse()` 补该字段。
- **用途**：`REPLY` 类型通知携带 `commentId` 时映射为 `#comment-{commentId}` 锚点；`LIKE`/`FAVORITE` 无该值，跳转到帖子。

后端改动须保持既有 19 个测试全绿，并为通知携带 `commentId` 增加至少 1 条集成测试断言（完成后总计 20 条）。

**评论锚点渲染**：帖子详情页每条评论容器加 `id="comment-{id}"`；路由进入带 `hash` 时滚动到该元素并加高亮类，2 秒后移除。

**未读数即时刷新（stores/notifications.js）**

- state：`unreadCount`、`list`、`listPage`
- actions：`refreshUnread()`、`loadList(page)`、`markAllRead()`
- 顶栏角标与通知页共用该 store。角标刷新的时机共四处：登录后（`App.vue` 挂载）、进入通知页（`NotificationsView` 挂载）、`markAllRead()` 归零、以及 120 秒兜底轮询（替代原先仅靠 60 秒轮询的做法，间隔放宽到 120 秒）。
- **实现期修正（原文有误）**：本节原先写「点赞、收藏、评论成功后由调用方触发 `refreshUnread()`」，但该行为不可能产生效果——后端对自发行为不写通知（`LikeService` 与 `CommentService` 均跳过 recipient 为操作者本人的通知），当前用户自己的任何操作都不会改变自己的未读数，调用只会白跑一次请求。故 `submitComment` 中已有的一次 `refreshUnread()` 不构成刷新来源，点赞/收藏路径不触发；角标反映的是**他人**操作产生的通知，最迟 120 秒后由轮询补齐。

**互动状态一致性（composables/usePostActions.js）**

- 以 `postId` 为键维护 `{ liked, likeCount, favorited, favoriteCount }` 的响应式映射，首页列表与详情页读写同一来源。
- 切换动作调用现有 `postApi.like/favorite`，用返回的 `active` 与 `count` 覆盖本地状态；失败时回滚并提示。
- 说明：后端切换接口返回的是本次操作后的状态与计数，因此无需额外查询即可保持一致性。
- **实现期补充（后端）**：`liked` / `favorited` 原先不存在于任何响应中，导致刷新后点赞按钮恒为未选中，`syncFromPost` 也无从写入这两个标志。已新增 `PostDetailResponse` / `PostSummaryResponse` 的 `liked` / `favorited`（登录用户视角，游客为 `false`），列表路径用两条批量 `IN` 查询取当前用户在当页的点赞/收藏集合，详情路径用单行查询。前端 `syncFromPost` 据此写入标志。

## 4. 数据流

1. 用户点击通知 → `resolveNotificationTarget` 得到 `path + hash` → `router.push` → 详情页 `onMounted` 读取 `route.hash` 并滚动定位。
2. 用户在详情页点赞 → `usePostActions.toggleLike(postId)` → 调用 API → 更新共享状态（首页列表与详情页同时反映；不刷新未读角标，原因见 §3.3）。
3. 用户回到首页 → 列表项从共享状态读取 `liked/likeCount`，与详情页显示一致。

## 5. 错误处理

- 通知跳转目标为 `null` 时，通知项渲染为不可点击且不显示指针样式。
- 滚动定位失败（评论已删除，或目标根评论位于后面的评论分页）时页面停留在顶部，不阻塞操作；当该帖评论总数大于 10 时给一条非阻塞 `ElMessage.info` 提示「目标评论可能在后面的评论分页里」，避免用户以为点击无效。评论已删除会命中同一提示，是本轮接受的误报。
  - 权衡：更彻底的做法是按 `commentId` 从第 0 页逐页回溯（列表按 `createTime` 升序，可顺序扫描，上限如 10 页），找到后选中该页再滚动。本轮未采用：目标根评论越新越靠后（用户刚收到回复的评论往往在最后一页），乐观估计也要多次串行请求，且超过页数上限后仍会失败——用一条诚实的提示换取零额外请求，更符合本轮「小而准」的目标。
- 互动切换失败：回滚本地状态 + 由既有 axios 拦截器统一提示。

## 6. 测试策略

前端引入 Vitest + @vue/test-utils + jsdom（devDependencies）：

- `notificationLink.spec.js`：覆盖 REPLY/LIKE/FAVORITE/SYSTEM、缺失 postId、缺失 commentId 的分支。
- `usePostActions.spec.js`：mock API，验证切换成功后状态与计数更新、失败后回滚。
- `notifications.store.spec.js`：验证 `markAllRead` 后未读归零、`refreshUnread` 更新计数。
- 组件级：`HomeView` 窄屏渲染分支（mock `matchMedia` 为 mobile）断言工具栏与列表结构。

后端：除 §3.3 已确认的 `commentId` 改动外不改动；回归门槛为原 19 条用例保持全绿，加上新增的 1 条集成测试断言后共 20 条。

## 7. 验收标准

- [ ] `frontend/src/styles/tokens.css` 建立，各视图不再出现硬编码的间距/字号魔数（允许保留 Element Plus 内部样式与一次性布局值）
- [ ] 768px 及以下宽度下：首页板块导航横向滚动、列表元信息不溢出、评论缩进收窄、后台表格以卡片呈现
- [ ] 点击 REPLY 类通知可跳转到帖子并定位高亮对应评论；无 `postId` 的通知不可点击
- [ ] 他人操作触发的通知在进入通知页或 120 秒轮询后反映到角标；登录后角标立即拉取一次（自己点赞/收藏/评论不改变自己的未读数：后端对自发行为不写通知）
- [ ] 同一帖子的点赞状态在首页列表与详情页显示一致
- [ ] `pnpm test` 前端测试通过；`mvn clean test` 20 个测试全绿
- [ ] `pnpm build` 无错误

## 8. 风险与待确认

1. **评论级锚点需要后端补 `commentId`**（见 3.3），是本轮唯一可能的后端改动，需先确认是否接受。
2. Element Plus 变量覆盖可能影响既有组件外观，需要逐页目视回归。
3. 后台表格改卡片式是结构级改动，需同时保留桌面端表格行为。

## 9. 第二轮调整（优化）

第一轮完成后，对运行日志与代码做了针对性审计（审计对象是真实日志与真实文件，而非设想），发现并修复了以下问题。

### 9.1 性能

- **列表分页退化为全表加载**：`PostRepository.search` 在 `tags` 集合上用 `@EntityGraph` 同时分页，Hibernate 打印 `HHH90003004: firstResult/maxResults specified with collection fetch; applying in memory`，把全部匹配行读进内存后再切页。改为「先分页取实体（只 fetch 单值关联 `board`/`author`）、再按 id 批量取 `tags`」的两步查询；`IN` 查询不保证顺序，故用第一步的 `Page` 逐项映射回填以保住「置顶优先 + 时间倒序」。
- **「我的帖子」同类问题**：`findByAuthorIdAndDeletedFalse` 同样在 `tags` 集合上分页，一并改为两步。
- **收藏列表 tags N+1**：`FavoriteRepository` 的 `@EntityGraph` 覆盖了 `post.board`/`post.author` 但漏了 `post.tags`，每行触发一次查询。改为对当页 `postId` 批量补 tags。
- 守卫测试用 logback `ListAppender` 挂在 `org.hibernate.orm.query` 上断言 `HHH90003004` 不出现——该告警是纯日志，不回传异常，`StatementInspector` 拿不到文本。已做变异验证（把新方法临时转发回旧查询，测试报 `Failures: 1` 并打出该告警）。

### 9.2 安全与运维

- **凭据入库**：`application.yml` 曾含明文数据库密码且已被 git 跟踪。改为 `DB_PASSWORD` / `JWT_SECRET` 环境变量且**故意不设默认值**（缺了就启动失败，避免误用弱默认值上线）；`.env.example` 提供样板，`.env` 已在 `.gitignore`。
- **生产日志**：原配置在生产也打印全部 SQL，实测单条查询日志超 38KB。拆分 `dev` / `prod` profile：`dev` 保留 SQL 与绑定参数（配合 DevTools），`prod` 降到 `warn`。**`org.hibernate.orm.query` 在 prod 仍保持 `warn`**——`HHH90003004` 在这个级别下才可见，删掉等于关掉性能告警。
- **Actuator**：仅暴露 `health` 且 `show-details: never`，并在 `SecurityConfig` 中以 `ADMIN_ONLY = {"/actuator/**"}` 限制为管理员。该规则必须排在 `GET permitAll()` 之前，否则会被匿名放行。
- 测试配置改用 `application-test.yml` + `@ActiveProfiles("test")`：测试 classpath 下的 `application.yml` 是**覆盖**而非合并主配置，直接放同名文件会丢掉 `jwt.expiration-ms` 等键。

### 9.3 功能

- **通知单条已读**：原先只有 `read-all`，用户点进通知后那条仍是未读，角标与视觉状态不一致。新增 `POST /api/notifications/{id}/read`。
- 服务层先 `findById` 判断存在性与归属，再执行更新：更新语句影响 0 行无法区分「不存在 / 不属于你 / 本就已读」，直接返回会让越权请求看起来像成功。归属不符与不存在统一抛 `NoSuchElementException`（对外 404），不泄露 id 有效性；已读时提前返回，不重复发 `UPDATE`。
- 前端点击未读通知时先请求后改本地状态（失败不留假状态），不 `await`、失败不阻断跳转；未读数用 `Math.max(0, ...)` 钳住。

### 9.4 可维护性

- **时间格式化**：`fmt`/`fmtTime` 在 5 个视图中重复（计划漏算了 `ProfileView`）。收敛为 `utils/format.js` 的 `formatDateTime` 与 `formatRelative`。不使用 `Intl`：后端返回的是无时区 `LocalDateTime` 串，按本地时区补偏移会漂移。`formatRelative` 对未来时间返回「刚刚」而非负数。
- **后台卡片**：`AdminView.vue` 用户与板块各有一套「表格 + 窄屏卡片」实现，加字段要改 4 处。抽为 `components/UserCard.vue`、`BoardCard.vue`，视图从 297 行降到 235 行。组件不 import api、不读 store，可脱离 pinia 独立挂载；删除前的二次确认留在视图，组件只发事件。
- **分页样式**：`.pager` 规则在 5 个视图中重复（6 个 `el-pagination`）。收敛为 `styles/common.css` 的 `.jf-pager`，并在 `main.js` 中于 `tokens.css` **之后**引入（规则里用了 `var(--jf-space-4)`）。
- 三处收敛都配了守卫测试：`format.spec.js`、`cards.spec.js`、`common.spec.js`。其中 `common.spec.js` 会扫描 `src/**/*.vue`，断言没有视图重新定义 `.pager`、每个 `el-pagination` 都带 `jf-pager`、且 `jf-pager` 出现次数与 `el-pagination` 数量**精确相等**（多出来的 class 会把 `margin-top`/`justify-content` 套到非分页元素上）。五项变异均被捕获。

### 9.5 遗留

- 数据库密码仍存在于 git 历史（自 `6eecbdc` 起）。彻底处理需 `git filter-repo` 改写历史**并轮换该密码**——只改历史不够，密码已泄露。
- 计划中「`.pager` 重复 4 处」「时间格式化重复 4 处」的计数偏低，实际均为 5 处。
