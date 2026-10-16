# JavaForum 体验迭代（第一轮）设计文档

- 日期：2026-10-16
- 状态：待用户审阅
- 范围：视觉信息层级（A） + 手机窄屏适配（B） + 通知与互动闭环（C）
- 明确不做：Markdown 渲染、标签检索、公开个人主页、头像上传、深色模式

## 1. 背景与目标

JavaForum 六个阶段的 MVP 已完成：后端 Spring Boot 3.5 + PostgreSQL，前端 Vue3 + Vite + Pinia + Element Plus，
具备注册登录、板块与帖子、评论楼中楼、点赞收藏、通知、个人中心与管理后台。当前问题不在功能缺失，而在体验：

- 样式数值散落在各视图的 `<style scoped>` 中，卡片圆角、间距、字号不统一，信息层级靠字号硬撑。
- 布局按桌面宽度写死，窄屏下板块导航、帖子元信息、评论缩进与后台表格会溢出或挤压。
- 通知只能阅读，不能跳转到对应帖子与评论；未读数只在 60 秒轮询后更新；点赞/收藏状态在列表与详情之间不同步。

本轮目标：在不新增业务功能、不改数据库结构的前提下，把上述三类体验问题收敛，并建立可复用的样式令牌与前端测试基线。

## 2. 非目标

- 不引入任何后端接口变更；如发现必须改动，先回到设计确认。
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

约束：`NotificationResponse` 当前不含 `commentId`。若跳转需要评论级锚点，需后端在通知响应中补该字段——**这属于后端变更，须在实现计划的第 0 步先确认**。若用户不接受后端改动，降级为帖子级跳转（`hash: ''`）。

**评论锚点渲染**：帖子详情页每条评论容器加 `id="comment-{id}"`；路由进入带 `hash` 时滚动到该元素并加高亮类，2 秒后移除。

**未读数即时刷新（stores/notifications.js）**

- state：`unreadCount`、`list`、`listPage`
- actions：`refreshUnread()`、`loadList(page)`、`markAllRead()`
- 顶栏角标与通知页共用该 store；点赞、收藏、评论成功后由调用方触发 `refreshUnread()`，替代原先仅靠 60 秒轮询的做法（轮询保留为兜底，间隔放宽到 120 秒）。

**互动状态一致性（composables/usePostActions.js）**

- 以 `postId` 为键维护 `{ liked, likeCount, favorited, favoriteCount }` 的响应式映射，首页列表与详情页读写同一来源。
- 切换动作调用现有 `postApi.like/favorite`，用返回的 `active` 与 `count` 覆盖本地状态；失败时回滚并提示。
- 说明：后端切换接口返回的是本次操作后的状态与计数，因此无需额外查询即可保持一致性。

## 4. 数据流

1. 用户点击通知 → `resolveNotificationTarget` 得到 `path + hash` → `router.push` → 详情页 `onMounted` 读取 `route.hash` 并滚动定位。
2. 用户在详情页点赞 → `usePostActions.toggleLike(postId)` → 调用 API → 更新共享状态 → 触发 `notifications.refreshUnread()`。
3. 用户回到首页 → 列表项从共享状态读取 `liked/likeCount`，与详情页显示一致。

## 5. 错误处理

- 通知跳转目标为 `null` 时，通知项渲染为不可点击且不显示指针样式。
- 滚动定位失败（评论已删除或不存在）时静默忽略，页面停留在顶部，不弹错误。
- 互动切换失败：回滚本地状态 + 由既有 axios 拦截器统一提示。

## 6. 测试策略

前端引入 Vitest + @vue/test-utils + jsdom（devDependencies）：

- `notificationLink.spec.js`：覆盖 REPLY/LIKE/FAVORITE/SYSTEM、缺失 postId、缺失 commentId 的分支。
- `usePostActions.spec.js`：mock API，验证切换成功后状态与计数更新、失败后回滚。
- `notifications.store.spec.js`：验证 `markAllRead` 后未读归零、`refreshUnread` 更新计数。
- 组件级：`HomeView` 窄屏渲染分支（mock `matchMedia` 为 mobile）断言工具栏与列表结构。

后端：不改动，保持 `mvn clean test` 19 个测试全绿作为回归门槛。

## 7. 验收标准

- [ ] `frontend/src/styles/tokens.css` 建立，各视图不再出现硬编码的间距/字号魔数（允许保留 Element Plus 内部样式与一次性布局值）
- [ ] 768px 及以下宽度下：首页板块导航横向滚动、列表元信息不溢出、评论缩进收窄、后台表格以卡片呈现
- [ ] 点击 REPLY 类通知可跳转到帖子并定位高亮对应评论；无 `postId` 的通知不可点击
- [ ] 点赞/收藏/评论成功后，顶栏未读角标在 1 秒内更新
- [ ] 同一帖子的点赞状态在首页列表与详情页显示一致
- [ ] `pnpm test` 前端测试通过；`mvn clean test` 19 个测试全绿
- [ ] `pnpm build` 无错误

## 8. 风险与待确认

1. **评论级锚点需要后端补 `commentId`**（见 3.3），是本轮唯一可能的后端改动，需先确认是否接受。
2. Element Plus 变量覆盖可能影响既有组件外观，需要逐页目视回归。
3. 后台表格改卡片式是结构级改动，需同时保留桌面端表格行为。
