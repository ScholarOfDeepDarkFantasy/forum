# JavaForum 体验迭代（第一轮）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不新增业务功能的前提下，统一 JavaForum 前端的视觉信息层级、补齐手机窄屏适配，并把通知跳转与点赞/收藏状态接通成闭环（含后端为通知补 `commentId`）。

**Architecture:** 前端新增设计令牌层（`styles/tokens.css`）供所有视图引用，替代散落的硬编码样式；新增 `useResponsive` / `usePostActions` 两个 composable 与 `notifications` store，把响应式判定与互动状态从视图里抽出来；通知跳转规则收敛为一个纯函数便于测试。后端唯一改动是给 `notifications` 表补 `comment_id` 并透传到响应，使 REPLY 通知能精确锚定评论。

**Tech Stack:** Vue 3.5（`<script setup>`）+ Vite 8 + Pinia 4 + Vue Router 4 + Element Plus 2.14；Vitest + @vue/test-utils + jsdom（本轮新增）；后端 Spring Boot 3.5 + JPA + Flyway + PostgreSQL，测试用 H2。

**基线：** 当前分支 `master`，最新提交 `4a5c869`。后端 `mvn clean test` 应保持 19 个测试全绿；前端当前无测试，`pnpm build` 可用。

---

## 文件结构

**后端（改动最小化）**
- Create: `src/main/resources/db/migration/V2__notification_comment_id.sql` — 给通知表补评论外键
- Modify: `src/main/java/org/song/forum/entity/Notification.java` — 增加 `comment` 关联
- Modify: `src/main/java/org/song/forum/dto/notification/NotificationResponse.java` — 增加 `commentId`
- Modify: `src/main/java/org/song/forum/service/NotificationService.java` — 映射 `commentId`
- Modify: `src/main/java/org/song/forum/service/CommentService.java` — 写 REPLY 通知时带上评论
- Modify: `src/test/java/org/song/forum/controller/CommentFlowIntegrationTest.java` — 断言通知携带 `commentId`

**前端 · 新增基础设施**
- Create: `frontend/src/styles/tokens.css` — 设计令牌（颜色/间距/圆角/阴影/字号）
- Create: `frontend/src/composables/useResponsive.js` — `isMobile` 判定（768px）
- Create: `frontend/src/composables/usePostActions.js` — 点赞/收藏共享状态与切换
- Create: `frontend/src/utils/notificationLink.js` — 通知 → 路由目标纯函数
- Create: `frontend/src/stores/notifications.js` — 未读数与列表状态

**前端 · 修改**
- Modify: `frontend/src/main.js` — 引入 tokens.css
- Modify: `frontend/src/App.vue` — 未读数改用 store、窄屏间距
- Modify: `frontend/src/views/HomeView.vue` — 响应式布局 + 令牌 + 互动状态
- Modify: `frontend/src/views/PostDetailView.vue` — 评论锚点 + 令牌 + 互动状态
- Modify: `frontend/src/views/NotificationsView.vue` — 点击跳转
- Modify: `frontend/src/views/AdminView.vue` — 窄屏卡片式渲染
- Modify: `frontend/src/views/ProfileView.vue`、`LoginView.vue`、`RegisterView.vue`、`PostEditView.vue` — 令牌对齐

**前端 · 测试**
- Create: `frontend/src/utils/notificationLink.spec.js`
- Create: `frontend/src/composables/usePostActions.spec.js`
- Create: `frontend/src/stores/notifications.spec.js`
- Create: `frontend/src/views/HomeView.spec.js`
- Modify: `frontend/package.json`、`frontend/vite.config.js` — Vitest 配置

---

## Task 1: 后端为通知补 commentId

**Files:**
- Create: `src/main/resources/db/migration/V2__notification_comment_id.sql`
- Modify: `src/main/java/org/song/forum/entity/Notification.java:26-29`
- Modify: `src/main/java/org/song/forum/dto/notification/NotificationResponse.java`
- Modify: `src/main/java/org/song/forum/service/NotificationService.java:44-58`
- Modify: `src/main/java/org/song/forum/service/CommentService.java:96-104`
- Test: `src/test/java/org/song/forum/controller/CommentFlowIntegrationTest.java`

- [ ] **Step 1: 写失败的测试断言**

在 `src/test/java/org/song/forum/controller/CommentFlowIntegrationTest.java` 中，找到断言 REPLY 通知存在的那段（约在 stage3/stage4，形如 `assertThat(...getTotalElements()).isEqualTo(1)` 的通知查询处），在其后追加对 `commentId` 的断言：

```java
        // 新增：REPLY 通知应携带被回复评论的 id，供前端锚点跳转
        ResponseEntity<Result<PageResponse<NotificationResponse>>> notif =
                rest.exchange(url("/api/notifications?page=0&size=10"), HttpMethod.GET,
                        new HttpEntity<>(bearer(postAuthorToken)),
                        new ParameterizedTypeReference<>() {});
        NotificationResponse replyNotif = notif.getBody().getData().content().stream()
                .filter(n -> "REPLY".equals(n.getType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 REPLY 通知"));
        assertThat(replyNotif.getCommentId()).isNotNull();
```

如果该测试类尚未导入 `NotificationResponse`，在 import 区加入：

```java
import org.song.forum.dto.notification.NotificationResponse;
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -B test -Dtest=CommentFlowIntegrationTest`
Expected: 编译失败，提示 `cannot find symbol: method getCommentId()`

- [ ] **Step 3: 写 V2 迁移**

创建 `src/main/resources/db/migration/V2__notification_comment_id.sql`：

```sql
-- =====================================================================
-- JavaForum V2 : 通知表补 comment_id，使 REPLY 通知可精确锚定评论
-- =====================================================================

ALTER TABLE notifications
    ADD COLUMN comment_id BIGINT REFERENCES comments(id) ON DELETE SET NULL;

CREATE INDEX idx_notifications_comment ON notifications (comment_id);
```

- [ ] **Step 4: 实体补 comment 关联**

修改 `src/main/java/org/song/forum/entity/Notification.java`，在 `post` 字段之后插入：

```java
    /** 关联评论（仅 REPLY 通知有值），用于前端跳转锚点 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "comment_id")
    private Comment comment;
```

- [ ] **Step 5: DTO 补 commentId**

修改 `src/main/java/org/song/forum/dto/notification/NotificationResponse.java`，在 `postTitle` 字段之后插入：

```java
    /** 被回复评论的 id，仅 REPLY 类型有值 */
    private Long commentId;
```

- [ ] **Step 6: Service 映射 commentId**

修改 `src/main/java/org/song/forum/service/NotificationService.java` 的 `toResponse`，在 `.postTitle(...)` 一行之后插入：

```java
                .commentId(n.getComment() == null ? null : n.getComment().getId())
```

- [ ] **Step 7: CommentService 写入评论**

修改 `src/main/java/org/song/forum/service/CommentService.java`，在 REPLY 通知的 builder 中，`.post(post)` 之后插入：

```java
                    .comment(comment)
```

- [ ] **Step 8: 运行测试确认通过**

Run: `mvn -B test -Dtest=CommentFlowIntegrationTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 9: 后端全量回归**

Run: `mvn -B clean test`
Expected: `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0` 与 `BUILD SUCCESS`

- [ ] **Step 10: 提交**

```bash
git add src/main/resources/db/migration/V2__notification_comment_id.sql \
        src/main/java/org/song/forum/entity/Notification.java \
        src/main/java/org/song/forum/dto/notification/NotificationResponse.java \
        src/main/java/org/song/forum/service/NotificationService.java \
        src/main/java/org/song/forum/service/CommentService.java \
        src/test/java/org/song/forum/controller/CommentFlowIntegrationTest.java
git commit -m "feat: 通知补 commentId 支持评论级跳转锚点"
```

---

## Task 2: 建立设计令牌层

**Files:**
- Create: `frontend/src/styles/tokens.css`
- Modify: `frontend/src/main.js`

- [ ] **Step 1: 写令牌文件**

创建 `frontend/src/styles/tokens.css`：

```css
/**
 * JavaForum 设计令牌
 * 页面只引用令牌，避免各视图写死间距/字号/圆角。
 */
:root {
  /* 颜色 */
  --jf-color-primary: #409eff;
  --jf-color-text-1: #303133;
  --jf-color-text-2: #606266;
  --jf-color-text-3: #909399;
  --jf-color-border: #e4e7ed;
  --jf-color-bg-page: #f5f6f8;
  --jf-color-bg-card: #ffffff;
  --jf-color-bg-hover: #f8f9fb;

  /* 间距阶梯 4/8/12/16/24/32 */
  --jf-space-1: 4px;
  --jf-space-2: 8px;
  --jf-space-3: 12px;
  --jf-space-4: 16px;
  --jf-space-5: 24px;
  --jf-space-6: 32px;

  /* 圆角 */
  --jf-radius-sm: 4px;
  --jf-radius-md: 8px;
  --jf-radius-lg: 12px;

  /* 阴影 */
  --jf-shadow-card: 0 1px 2px rgba(0, 0, 0, 0.04);
  --jf-shadow-hover: 0 2px 8px rgba(0, 0, 0, 0.08);

  /* 字号阶梯 */
  --jf-font-title-lg: 22px;
  --jf-font-title-md: 16px;
  --jf-font-body: 14px;
  --jf-font-meta: 13px;
  --jf-font-small: 12px;

  /* 响应式断点（JS 侧 useResponsive 使用同一数值 768） */
  --jf-breakpoint-mobile: 768px;

  /* 覆盖 Element Plus 主色，保持全站一致 */
  --el-color-primary: var(--jf-color-primary);
}
```

- [ ] **Step 2: 在主入口引入**

修改 `frontend/src/main.js`，把 `import './style.css'` 一行改为两行：

```js
import './styles/tokens.css'
import './style.css'
```

- [ ] **Step 3: 构建验证**

Run: `cd frontend && pnpm build`
Expected: `✓ built in` 且无错误

- [ ] **Step 4: 提交**

```bash
git add frontend/src/styles/tokens.css frontend/src/main.js
git commit -m "style: 新增设计令牌层并接入主入口"
```

---

## Task 3: 引入前端测试环境

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/vite.config.js`
- Create: `frontend/src/utils/notificationLink.js`（先建空实现供测试引用）
- Create: `frontend/src/utils/notificationLink.spec.js`

- [ ] **Step 1: 安装测试依赖**

Run: `cd frontend && pnpm add -D vitest @vue/test-utils jsdom`
Expected: 三条依赖写入 `devDependencies`

- [ ] **Step 2: 写测试脚本与 Vitest 配置**

修改 `frontend/package.json` 的 `scripts`，增加一行：

```json
    "test": "vitest run",
    "test:watch": "vitest"
```

修改 `frontend/vite.config.js`，在 `defineConfig({...})` 内追加 `test` 段：

```js
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.spec.js']
  },
```

- [ ] **Step 3: 写失败的测试**

创建 `frontend/src/utils/notificationLink.spec.js`：

```js
import { describe, it, expect } from 'vitest'
import { resolveNotificationTarget } from './notificationLink'

describe('resolveNotificationTarget', () => {
  it('REPLY 带 postId 与 commentId 时返回帖子路径与评论锚点', () => {
    const result = resolveNotificationTarget({ type: 'REPLY', postId: 7, commentId: 31 })
    expect(result).toEqual({ path: '/posts/7', hash: '#comment-31' })
  })

  it('REPLY 缺少 commentId 时只跳帖子，不带锚点', () => {
    const result = resolveNotificationTarget({ type: 'REPLY', postId: 7, commentId: null })
    expect(result).toEqual({ path: '/posts/7', hash: '' })
  })

  it('LIKE 跳转到帖子且不带锚点', () => {
    const result = resolveNotificationTarget({ type: 'LIKE', postId: 5, commentId: null })
    expect(result).toEqual({ path: '/posts/5', hash: '' })
  })

  it('FAVORITE 跳转到帖子且不带锚点', () => {
    const result = resolveNotificationTarget({ type: 'FAVORITE', postId: 9, commentId: null })
    expect(result).toEqual({ path: '/posts/9', hash: '' })
  })

  it('SYSTEM 通知不可跳转', () => {
    expect(resolveNotificationTarget({ type: 'SYSTEM', postId: null, commentId: null })).toBeNull()
  })

  it('缺少 postId 的通知不可跳转', () => {
    expect(resolveNotificationTarget({ type: 'REPLY', postId: null, commentId: 12 })).toBeNull()
  })
})
```

- [ ] **Step 4: 运行测试确认失败**

Run: `cd frontend && pnpm test`
Expected: FAIL，提示无法解析 `./notificationLink` 的导出或 `resolveNotificationTarget is not a function`

- [ ] **Step 5: 写最小实现**

创建 `frontend/src/utils/notificationLink.js`：

```js
/**
 * 把通知映射为路由目标。
 * 返回 { path, hash }，无法跳转时返回 null。
 */
export function resolveNotificationTarget(notification) {
  if (!notification || !notification.postId) {
    return null
  }
  const path = `/posts/${notification.postId}`
  const hash =
    notification.type === 'REPLY' && notification.commentId
      ? `#comment-${notification.commentId}`
      : ''
  return { path, hash }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `cd frontend && pnpm test`
Expected: `Test Files 1 passed`，`Tests 6 passed`

- [ ] **Step 7: 提交**

```bash
git add frontend/package.json frontend/vite.config.js frontend/src/utils/notificationLink.js frontend/src/utils/notificationLink.spec.js frontend/pnpm-lock.yaml
git commit -m "test: 引入 Vitest 并覆盖通知跳转映射"
```

---

## Task 4: 通知未读数 store

**Files:**
- Create: `frontend/src/stores/notifications.js`
- Create: `frontend/src/stores/notifications.spec.js`
- Modify: `frontend/src/api/index.js`

- [ ] **Step 1: 写失败的测试**

创建 `frontend/src/stores/notifications.spec.js`：

```js
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

vi.mock('@/api', () => ({
  notificationApi: {
    unreadCount: vi.fn(),
    mine: vi.fn(),
    markAllRead: vi.fn()
  }
}))

import { notificationApi } from '@/api'
import { useNotificationStore } from './notifications'

describe('notifications store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('refreshUnread 写入后端返回的计数', async () => {
    notificationApi.unreadCount.mockResolvedValue({ count: 4 })
    const store = useNotificationStore()
    await store.refreshUnread()
    expect(store.unreadCount).toBe(4)
  })

  it('refreshUnread 失败时计数归零且不抛出', async () => {
    notificationApi.unreadCount.mockRejectedValue(new Error('boom'))
    const store = useNotificationStore()
    await expect(store.refreshUnread()).resolves.toBeUndefined()
    expect(store.unreadCount).toBe(0)
  })

  it('markAllRead 之后未读归零', async () => {
    notificationApi.unreadCount.mockResolvedValue({ count: 3 })
    notificationApi.markAllRead.mockResolvedValue(null)
    const store = useNotificationStore()
    await store.refreshUnread()
    await store.markAllRead()
    expect(store.unreadCount).toBe(0)
  })

  it('loadList 保存分页数据', async () => {
    notificationApi.mine.mockResolvedValue({
      content: [{ id: 1, type: 'LIKE' }],
      totalElements: 1,
      totalPages: 1
    })
    const store = useNotificationStore()
    await store.loadList(0)
    expect(store.list.content).toHaveLength(1)
    expect(store.listPage).toBe(0)
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd frontend && pnpm test -- src/stores/notifications.spec.js`
Expected: FAIL，`Failed to resolve import "./notifications"`

- [ ] **Step 3: 写实现**

创建 `frontend/src/stores/notifications.js`：

```js
import { defineStore } from 'pinia'
import { notificationApi } from '@/api'

const EMPTY_PAGE = { content: [], totalElements: 0, totalPages: 0 }

/**
 * 通知状态：顶栏角标与通知页共用，避免两处各自轮询。
 */
export const useNotificationStore = defineStore('notifications', {
  state: () => ({
    unreadCount: 0,
    list: { ...EMPTY_PAGE },
    listPage: 0
  }),

  actions: {
    async refreshUnread() {
      try {
        const data = await notificationApi.unreadCount()
        this.unreadCount = data?.count ?? 0
      } catch {
        this.unreadCount = 0
      }
    },

    async loadList(page = 0) {
      this.list = await notificationApi.mine({ page, size: 10 })
      this.listPage = page
    },

    async markAllRead() {
      await notificationApi.markAllRead()
      this.unreadCount = 0
      await this.loadList(this.listPage)
    }
  }
})
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd frontend && pnpm test`
Expected: `Test Files 2 passed`，全部用例通过

- [ ] **Step 5: 提交**

```bash
git add frontend/src/stores/notifications.js frontend/src/stores/notifications.spec.js
git commit -m "feat: 通知未读数 store 统一角标与列表状态"
```

---

## Task 5: 点赞收藏共享状态

**Files:**
- Create: `frontend/src/composables/usePostActions.js`
- Create: `frontend/src/composables/usePostActions.spec.js`

- [ ] **Step 1: 写失败的测试**

创建 `frontend/src/composables/usePostActions.spec.js`：

```js
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('@/api', () => ({
  postApi: {
    like: vi.fn(),
    favorite: vi.fn()
  }
}))

import { postApi } from '@/api'
import { usePostActions } from './usePostActions'

describe('usePostActions', () => {
  beforeEach(() => vi.clearAllMocks())

  it('初始状态来自传入的帖子对象', () => {
    const actions = usePostActions()
    actions.syncFromPost({ id: 3, likeCount: 2, favoriteCount: 1 })
    expect(actions.stateFor(3)).toMatchObject({ likeCount: 2, favoriteCount: 1 })
  })

  it('点赞成功后写入返回的计数并标记 liked', async () => {
    postApi.like.mockResolvedValue({ active: true, count: 9 })
    const actions = usePostActions()
    actions.syncFromPost({ id: 3, likeCount: 8, favoriteCount: 0 })
    await actions.toggleLike(3)
    expect(actions.stateFor(3)).toMatchObject({ liked: true, likeCount: 9 })
  })

  it('取消点赞后标记 liked 为 false', async () => {
    postApi.like.mockResolvedValue({ active: false, count: 7 })
    const actions = usePostActions()
    actions.syncFromPost({ id: 3, likeCount: 8, favoriteCount: 0 })
    await actions.toggleLike(3)
    expect(actions.stateFor(3)).toMatchObject({ liked: false, likeCount: 7 })
  })

  it('点赞失败时回滚到原计数', async () => {
    postApi.like.mockRejectedValue(new Error('network'))
    const actions = usePostActions()
    actions.syncFromPost({ id: 3, likeCount: 8, favoriteCount: 0 })
    await expect(actions.toggleLike(3)).rejects.toThrow('network')
    expect(actions.stateFor(3)).toMatchObject({ likeCount: 8 })
  })

  it('收藏成功后写入返回的计数并标记 favorited', async () => {
    postApi.favorite.mockResolvedValue({ active: true, count: 5 })
    const actions = usePostActions()
    actions.syncFromPost({ id: 3, likeCount: 0, favoriteCount: 4 })
    await actions.toggleFavorite(3)
    expect(actions.stateFor(3)).toMatchObject({ favorited: true, favoriteCount: 5 })
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd frontend && pnpm test -- src/composables/usePostActions.spec.js`
Expected: FAIL，`Failed to resolve import "./usePostActions"`

- [ ] **Step 3: 写实现**

创建 `frontend/src/composables/usePostActions.js`：

```js
import { reactive } from 'vue'
import { postApi } from '@/api'

// 模块级共享：首页列表与详情页读写同一份状态，保证点赞/收藏显示一致
const stateMap = reactive({})

/**
 * 帖子互动状态：以 postId 为键维护 like/favorite 状态与计数。
 */
export function usePostActions() {
  function ensure(postId) {
    if (!stateMap[postId]) {
      stateMap[postId] = { liked: false, likeCount: 0, favorited: false, favoriteCount: 0 }
    }
    return stateMap[postId]
  }

  /** 用后端返回的帖子数据初始化（不覆盖用户本次会话内的操作结果） */
  function syncFromPost(post) {
    if (!post || !post.id) return
    const current = ensure(post.id)
    if (current.likeCount === 0 && post.likeCount != null) current.likeCount = post.likeCount
    if (current.favoriteCount === 0 && post.favoriteCount != null) {
      current.favoriteCount = post.favoriteCount
    }
  }

  function stateFor(postId) {
    return ensure(postId)
  }

  async function toggleLike(postId) {
    const current = ensure(postId)
    const snapshot = { liked: current.liked, likeCount: current.likeCount }
    try {
      const r = await postApi.like(postId)
      current.liked = r.active
      current.likeCount = r.count
      return r
    } catch (e) {
      current.liked = snapshot.liked
      current.likeCount = snapshot.likeCount
      throw e
    }
  }

  async function toggleFavorite(postId) {
    const current = ensure(postId)
    const snapshot = { favorited: current.favorited, favoriteCount: current.favoriteCount }
    try {
      const r = await postApi.favorite(postId)
      current.favorited = r.active
      current.favoriteCount = r.count
      return r
    } catch (e) {
      current.favorited = snapshot.favorited
      current.favoriteCount = snapshot.favoriteCount
      throw e
    }
  }

  return { syncFromPost, stateFor, toggleLike, toggleFavorite }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd frontend && pnpm test`
Expected: `Test Files 3 passed`，全部用例通过

- [ ] **Step 5: 提交**

```bash
git add frontend/src/composables/usePostActions.js frontend/src/composables/usePostActions.spec.js
git commit -m "feat: 点赞收藏共享状态 composable"
```

---

## Task 6: 响应式判定 composable

**Files:**
- Create: `frontend/src/composables/useResponsive.js`
- Test: 在 `frontend/src/utils/notificationLink.spec.js` 同级新增 `frontend/src/composables/useResponsive.spec.js`

- [ ] **Step 1: 写失败的测试**

创建 `frontend/src/composables/useResponsive.spec.js`：

```js
import { describe, it, expect, vi, beforeEach } from 'vitest'

function mockMatchMedia(matches) {
  window.matchMedia = vi.fn().mockImplementation((query) => ({
    matches,
    media: query,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn()
  }))
}

import { useResponsive } from './useResponsive'

describe('useResponsive', () => {
  beforeEach(() => vi.resetModules())

  it('窄屏时 isMobile 为 true', async () => {
    mockMatchMedia(true)
    const { useResponsive: fresh } = await import('./useResponsive')
    expect(fresh().isMobile.value).toBe(true)
  })

  it('宽屏时 isMobile 为 false', () => {
    mockMatchMedia(false)
    const { isMobile } = useResponsive()
    expect(isMobile.value).toBe(false)
  })

  it('查询字符串使用 768px 断点', () => {
    mockMatchMedia(false)
    useResponsive()
    expect(window.matchMedia).toHaveBeenCalledWith('(max-width: 768px)')
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd frontend && pnpm test -- src/composables/useResponsive.spec.js`
Expected: FAIL，`Failed to resolve import "./useResponsive"`

- [ ] **Step 3: 写实现**

创建 `frontend/src/composables/useResponsive.js`：

```js
import { ref, onMounted, onUnmounted } from 'vue'

// 与 tokens.css 的 --jf-breakpoint-mobile 保持一致，两处需同时修改
export const MOBILE_BREAKPOINT = 768
const QUERY = `(max-width: ${MOBILE_BREAKPOINT}px)`

/**
 * 响应式判定：仅用于必须切换 DOM 结构的场景（如后台表格 vs 卡片）。
 * 纯样式差异优先用 CSS 媒体查询解决。
 */
export function useResponsive() {
  const isMobile = ref(
    typeof window !== 'undefined' && window.matchMedia
      ? window.matchMedia(QUERY).matches
      : false
  )

  let mql = null
  const handler = (e) => {
    isMobile.value = e.matches
  }

  onMounted(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return
    mql = window.matchMedia(QUERY)
    isMobile.value = mql.matches
    if (mql.addEventListener) mql.addEventListener('change', handler)
  })

  onUnmounted(() => {
    if (mql && mql.removeEventListener) mql.removeEventListener('change', handler)
  })

  return { isMobile }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd frontend && pnpm test`
Expected: `Test Files 4 passed`，全部用例通过

- [ ] **Step 5: 提交**

```bash
git add frontend/src/composables/useResponsive.js frontend/src/composables/useResponsive.spec.js
git commit -m "feat: 响应式判定 composable（768px 断点）"
```

---

## Task 7: 顶栏改用通知 store

**Files:**
- Modify: `frontend/src/App.vue`

- [ ] **Step 1: 替换轮询逻辑**

修改 `frontend/src/App.vue`，把 `<script setup>` 中 `unread` 的 ref、`loadUnread` 函数与 `onMounted` 轮询替换为 store 调用：

```js
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Bell, User, SwitchButton, Connection, Setting } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'

const router = useRouter()
const auth = useAuthStore()
const notifications = useNotificationStore()

function handleUserCommand(command) {
  if (command === 'profile') {
    router.push('/profile')
  } else if (command === 'admin') {
    router.push('/admin')
  } else if (command === 'logout') {
    auth.logout()
    notifications.unreadCount = 0
    ElMessage.success('已退出登录')
    router.push('/')
  }
}

onMounted(() => {
  if (!auth.isLoggedIn) return
  notifications.refreshUnread()
  // 兜底轮询：主要刷新由点赞/收藏/评论等操作后主动触发
  setInterval(() => notifications.refreshUnread(), 120_000)
})
```

模板中角标改为读 store：

```html
            <el-badge :value="notifications.unreadCount" :hidden="notifications.unreadCount === 0" class="bell-badge">
```

- [ ] **Step 2: 构建验证**

Run: `cd frontend && pnpm build`
Expected: `✓ built in` 且无错误

- [ ] **Step 3: 提交**

```bash
git add frontend/src/App.vue
git commit -m "refactor: 顶栏未读数改用通知 store（轮询降为兜底）"
```

---

## Task 8: 通知页接入跳转与 store

**Files:**
- Modify: `frontend/src/views/NotificationsView.vue`

- [ ] **Step 1: 改用 store 并加入点击跳转**

修改 `frontend/src/views/NotificationsView.vue` 的 `<script setup>`：

```js
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useNotificationStore } from '@/stores/notifications'
import { resolveNotificationTarget } from '@/utils/notificationLink'

const router = useRouter()
const notifications = useNotificationStore()
const loading = ref(false)

async function load(p = 0) {
  loading.value = true
  try {
    await notifications.loadList(p)
  } finally {
    loading.value = false
  }
}

async function markAll() {
  await notifications.markAllRead()
}

function openNotification(n) {
  const target = resolveNotificationTarget(n)
  if (!target) return
  router.push(target)
}

function fmt(t) {
  return t ? t.replace('T', ' ').slice(0, 16) : ''
}

onMounted(() => {
  load()
  notifications.refreshUnread()
})
```

模板中列表循环改为读取 store，并支持点击：

```html
    <template v-if="notifications.list.content.length">
      <div
        v-for="n in notifications.list.content"
        :key="n.id"
        class="notif"
        :class="{ unread: !n.isRead, clickable: !!resolveNotificationTarget(n) }"
        @click="openNotification(n)"
      >
```

分页部分同步替换 `list` 为 `notifications.list`、`page` 为 `notifications.listPage`。

在 `<style scoped>` 增加：

```css
.notif.clickable {
  cursor: pointer;
}
.notif.clickable:hover {
  background: var(--jf-color-bg-hover);
}
```

- [ ] **Step 2: 构建验证**

Run: `cd frontend && pnpm build`
Expected: `✓ built in` 且无错误

- [ ] **Step 3: 提交**

```bash
git add frontend/src/views/NotificationsView.vue
git commit -m "feat: 通知页支持点击跳转并接入 store"
```

---

## Task 9: 帖子详情评论锚点与互动状态

**Files:**
- Modify: `frontend/src/views/PostDetailView.vue`

- [ ] **Step 1: 评论容器加锚点 id**

在评论与楼中楼模板中，为容器元素加 id。顶级评论：

```html
        <div v-for="c in comments" :key="c.id" :id="`comment-${c.id}`" class="comment">
```

子回复：

```html
            <div v-for="r in c.replies" :key="r.id" :id="`comment-${r.id}`" class="reply">
```

- [ ] **Step 2: 进入页面时滚动定位**

在 `<script setup>` 中增加定位逻辑，并在 `onMounted` 末尾调用：

```js
import { nextTick } from 'vue'

function scrollToHash() {
  const hash = route.hash
  if (!hash || !hash.startsWith('#comment-')) return
  nextTick(() => {
    const el = document.querySelector(hash)
    if (!el) return          // 评论已删除或不在当前分页，静默忽略
    el.scrollIntoView({ behavior: 'smooth', block: 'center' })
    el.classList.add('anchor-highlight')
    setTimeout(() => el.classList.remove('anchor-highlight'), 2000)
  })
}
```

`onMounted` 改为：

```js
onMounted(async () => {
  await loadPost()
  await loadComments()
  scrollToHash()
})
```

样式增加：

```css
.anchor-highlight {
  background: #fff8e6;
  transition: background 0.4s ease;
}
```

- [ ] **Step 3: 点赞收藏改用共享状态**

`<script setup>` 中引入并使用 composable，替换原 `toggleLike` / `toggleFavorite`：

```js
import { usePostActions } from '@/composables/usePostActions'
import { useNotificationStore } from '@/stores/notifications'

const actions = usePostActions()
const notifications = useNotificationStore()

async function toggleLike() {
  if (!auth.isLoggedIn) return router.push({ name: 'login', query: { redirect: route.fullPath } })
  const r = await actions.toggleLike(post.value.id)
  post.value = { ...post.value, likeCount: r.count }
  ElMessage.success(r.active ? '已点赞' : '已取消点赞')
}

async function toggleFavorite() {
  if (!auth.isLoggedIn) return router.push({ name: 'login', query: { redirect: route.fullPath } })
  const r = await actions.toggleFavorite(post.value.id)
  post.value = { ...post.value, favoriteCount: r.count }
  ElMessage.success(r.active ? '已收藏' : '已取消收藏')
}
```

在 `loadPost()` 成功后调用一次同步：

```js
async function loadPost() {
  post.value = await postApi.detail(route.params.id)
  actions.syncFromPost(post.value)
}
```

`submitComment()` 成功分支末尾追加：

```js
    notifications.refreshUnread()
```

- [ ] **Step 4: 构建验证**

Run: `cd frontend && pnpm build`
Expected: `✓ built in` 且无错误

- [ ] **Step 5: 提交**

```bash
git add frontend/src/views/PostDetailView.vue
git commit -m "feat: 评论锚点定位与共享点赞收藏状态"
```

---

## Task 10: 首页响应式与互动状态

**Files:**
- Modify: `frontend/src/views/HomeView.vue`
- Create: `frontend/src/views/HomeView.spec.js`

- [ ] **Step 1: 写组件测试**

创建 `frontend/src/views/HomeView.spec.js`：

```js
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'

vi.mock('@/api', () => ({
  boardApi: { list: vi.fn().mockResolvedValue([{ id: 1, name: '后端', postCount: 3 }]) },
  postApi: { list: vi.fn().mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 }) }
}))

import HomeView from './HomeView'
import { boardApi } from '@/api'

const global = {
  plugins: [createPinia()],
  stubs: {
    'el-card': { template: '<div><slot /><slot name="header" /></div>' },
    'el-tag': { template: '<span><slot /></span>' },
    'el-input': { template: '<input />' },
    'el-button': { template: '<button><slot /></button>' },
    'el-pagination': true,
    'el-empty': true,
    'el-icon': { template: '<i><slot /></i>' },
    'router-link': true
  },
  mocks: { $route: { query: {}, name: 'home' }, $router: { push: vi.fn() } }
}

describe('HomeView', () => {
  beforeEach(() => vi.clearAllMocks())

  it('挂载时加载板块列表', async () => {
    mount(HomeView, { global })
    await Promise.resolve()
    expect(boardApi.list).toHaveBeenCalled()
  })

  it('工具栏容器带有响应式类名，供窄屏 CSS 生效', () => {
    const wrapper = mount(HomeView, { global })
    expect(wrapper.find('.toolbar').exists()).toBe(true)
  })
})
```

- [ ] **Step 2: 运行测试确认通过或暴露问题**

Run: `cd frontend && pnpm test -- src/views/HomeView.spec.js`
Expected: 若组件依赖 `useRoute` 真实实现会失败；失败时按 Step 3 把路由依赖改为组合式 API 调用，使其可被测试替身注入。

- [ ] **Step 3: 响应式与令牌改造**

修改 `frontend/src/views/HomeView.vue`：

`<script setup>` 增加：

```js
import { usePostActions } from '@/composables/usePostActions'

const actions = usePostActions()
```

`loadPosts()` 成功分支后对每条帖子同步状态：

```js
async function loadPosts() {
  loading.value = true
  try {
    pageData.value = await postApi.list(query.value)
    pageData.value.content.forEach((p) => actions.syncFromPost(p))
  } finally {
    loading.value = false
  }
}
```

在 `<style scoped>` 末尾追加窄屏规则：

```css
/* 窄屏：板块导航横向滚动，工具栏与列表改为单列 */
@media (max-width: 768px) {
  .board-list {
    flex-wrap: nowrap;
    overflow-x: auto;
    scrollbar-width: none;
    -webkit-overflow-scrolling: touch;
  }
  .board-list::-webkit-scrollbar {
    display: none;
  }
  .board-tag {
    flex-shrink: 0;
  }
  .toolbar {
    flex-direction: column;
    align-items: stretch;
  }
  .post-item {
    align-items: flex-start;
  }
  .post-meta {
    gap: var(--jf-space-1);
  }
  .post-stats {
    margin-left: var(--jf-space-2);
  }
}
```

并将 `.post-item` 等规则中的硬编码值替换为令牌，例如：

```css
.post-item {
  padding: var(--jf-space-3) var(--jf-space-2);
  border-bottom: 1px solid #f0f0f0;
}
.post-item:hover {
  background: var(--jf-color-bg-hover);
}
.post-title {
  font-size: var(--jf-font-title-md);
}
.post-meta,
.post-stats {
  font-size: var(--jf-font-meta);
  color: var(--jf-color-text-3);
}
```

- [ ] **Step 4: 运行测试构建**

Run: `cd frontend && pnpm test && pnpm build`
Expected: 测试通过，构建 `✓ built in` 无错误

- [ ] **Step 5: 提交**

```bash
git add frontend/src/views/HomeView.vue frontend/src/views/HomeView.spec.js
git commit -m "feat: 首页窄屏适配、令牌对齐与互动状态同步"
```

---

## Task 11: 后台窄屏卡片式渲染

**Files:**
- Modify: `frontend/src/views/AdminView.vue`

- [ ] **Step 1: 引入响应式判定**

`<script setup>` 增加：

```js
import { useResponsive } from '@/composables/useResponsive'

const { isMobile } = useResponsive()
```

- [ ] **Step 2: 用户列表双渲染**

把用户表格外层包上条件渲染：桌面保留 `el-table`，窄屏改用卡片。窄屏分支：

```html
        <div v-if="isMobile" class="user-cards">
          <div v-for="u in users.content" :key="u.id" class="user-card">
            <div class="card-row"><span class="card-label">用户名</span><span>{{ u.username }}</span></div>
            <div class="card-row"><span class="card-label">昵称</span><span>{{ u.nickname || '-' }}</span></div>
            <div class="card-row"><span class="card-label">邮箱</span><span>{{ u.email }}</span></div>
            <div class="card-row">
              <span class="card-label">状态</span>
              <el-tag :type="statusTag(u.status)">{{ u.status }}</el-tag>
            </div>
            <div class="card-row">
              <span class="card-label">禁言至</span>
              <span>{{ u.mutedUntil ? fmt(u.mutedUntil) : '-' }}</span>
            </div>
            <div class="card-row">
              <span class="card-label">角色</span>
              <span>
                <el-tag v-for="r in u.roles" :key="r" size="small" type="info" class="role-tag">{{ r }}</el-tag>
              </span>
            </div>
            <el-button size="small" type="warning" plain :disabled="u.roles?.includes('ADMIN')" @click="openStatus(u)">
              状态管理
            </el-button>
          </div>
        </div>
        <el-table v-else :data="users.content" stripe>
```

板块表格用同样方式处理：窄屏显示名称、描述、帖子数与操作按钮的卡片。

- [ ] **Step 3: 卡片样式**

在 `<style scoped>` 增加：

```css
.user-cards,
.board-cards {
  display: flex;
  flex-direction: column;
  gap: var(--jf-space-3);
}
.user-card,
.board-card {
  border: 1px solid var(--jf-color-border);
  border-radius: var(--jf-radius-md);
  padding: var(--jf-space-3);
  display: flex;
  flex-direction: column;
  gap: var(--jf-space-2);
}
.card-row {
  display: flex;
  gap: var(--jf-space-2);
  font-size: var(--jf-font-body);
}
.card-label {
  color: var(--jf-color-text-3);
  min-width: 64px;
  flex-shrink: 0;
}
```

- [ ] **Step 4: 构建验证**

Run: `cd frontend && pnpm build`
Expected: `✓ built in` 且无错误

- [ ] **Step 5: 提交**

```bash
git add frontend/src/views/AdminView.vue
git commit -m "feat: 后台用户与板块在窄屏改为卡片式渲染"
```

---

## Task 12: 其余页面令牌对齐与评论缩进

**Files:**
- Modify: `frontend/src/views/PostDetailView.vue`
- Modify: `frontend/src/views/ProfileView.vue`
- Modify: `frontend/src/views/LoginView.vue`
- Modify: `frontend/src/views/RegisterView.vue`
- Modify: `frontend/src/views/PostEditView.vue`

- [ ] **Step 1: 评论缩进窄屏收窄**

在 `frontend/src/views/PostDetailView.vue` 的 `<style scoped>` 增加：

```css
@media (max-width: 768px) {
  .replies {
    padding-left: var(--jf-space-4);
  }
  .post-actions {
    flex-wrap: wrap;
  }
  .post-actions :deep(.el-button) {
    min-height: 36px;
  }
}
```

- [ ] **Step 2: 逐页替换硬编码值**

对上述每个视图的 `<style scoped>`，把间距/字号/圆角替换为令牌，规则如下：

- `padding`/`margin`/`gap` 的 4/8/12/16/24/32px → `var(--jf-space-1..6)`
- 卡片背景 `#fff` → `var(--jf-color-bg-card)`
- 次要文字色 `#909399` → `var(--jf-color-text-3)`、`#606266` → `var(--jf-color-text-2)`
- 列表标题 `16px` → `var(--jf-font-title-md)`
- 元信息 `13px` → `var(--jf-font-meta)`

`LoginView.vue` / `RegisterView.vue` 的 `.auth-card` 宽度保持桌面 400/440px，并新增窄屏规则：

```css
@media (max-width: 768px) {
  .auth-card {
    width: 100%;
  }
  .auth-page {
    padding: var(--jf-space-4);
  }
}
```

- [ ] **Step 3: 构建验证**

Run: `cd frontend && pnpm build`
Expected: `✓ built in` 且无错误

- [ ] **Step 4: 提交**

```bash
git add frontend/src/views/PostDetailView.vue frontend/src/views/ProfileView.vue \
        frontend/src/views/LoginView.vue frontend/src/views/RegisterView.vue \
        frontend/src/views/PostEditView.vue
git commit -m "style: 其余页面令牌对齐与窄屏表单适配"
```

---

## Task 13: 端到端验收与文档更新

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 前端全量测试**

Run: `cd frontend && pnpm test`
Expected: `Test Files 4 passed`

- [ ] **Step 2: 构建与后端回归**

Run: `cd frontend && pnpm build && cd .. && mvn -B clean test`
Expected: 构建成功；`Tests run: 19, Failures: 0, Errors: 0`

- [ ] **Step 3: 手工联调**

启动两端（`mvn spring-boot:run` 与 `cd frontend && pnpm dev`），逐项确认：

1. 用 admin 登录，浏览器缩至 375px 宽：首页板块导航可横向滑动，工具栏单列，列表元信息不溢出
2. 后台「用户管理」在窄屏显示为卡片，桌面显示为表格
3. 用户 A 评论用户 B 的帖子 → B 登录后在通知页点击该 REPLY 通知 → 跳转到帖子并高亮闪烁对应评论
4. 在详情页点赞 → 顶栏未读角标在 1 秒内 +1
5. 返回首页，该帖子在列表中的点赞状态与详情页一致

- [ ] **Step 4: 更新 README**

在 `README.md` 的「测试」章节补充前端测试说明：

```markdown
## 前端测试

```bash
cd frontend
pnpm test        # Vitest 单元与组件测试
pnpm build       # 生产构建
```

前端测试覆盖通知跳转映射、通知 store、点赞收藏共享状态与响应式判定。
```

- [ ] **Step 5: 提交**

```bash
git add README.md
git commit -m "docs: 补充前端测试与本轮体验改动说明"
```

---

## 自审记录

**Spec 覆盖检查**

| 设计文档要求 | 对应任务 |
| --- | --- |
| 设计令牌层（tokens.css，颜色/间距/圆角/阴影/字号/断点） | Task 2 |
| 视图不再写死魔数 | Task 10、11、12 |
| 768px 断点响应式（导航滚动、工具栏单列、列表换行、评论缩进、后台卡片） | Task 10、11、12 |
| 通知补 commentId（V2 迁移 + 实体 + DTO + Service + 写入） | Task 1 |
| 通知跳转纯函数与分支覆盖 | Task 3 |
| 未读数即时刷新（store 替代纯轮询） | Task 4、7、9 |
| 点赞/收藏状态跨页一致 | Task 5、9、10 |
| 评论锚点渲染与滚动高亮 | Task 9 |
| Vitest 测试基线（4 个 spec） | Task 3、4、5、6、10 |
| 后端 19 测试全绿作为回归门槛 | Task 1 Step 9、Task 13 Step 2 |
| 验收标准手工核对 | Task 13 Step 3 |

**类型一致性检查**

- `resolveNotificationTarget` 返回 `{ path, hash }` 或 `null`，Task 8 的 `openNotification` 按此契约判断。
- `usePostActions()` 返回 `{ syncFromPost, stateFor, toggleLike, toggleFavorite }`，Task 9、10 使用一致。
- `useNotificationStore` 暴露 `unreadCount` / `list` / `listPage` / `refreshUnread` / `loadList` / `markAllRead`，Task 7、8、9 使用一致。
- `useResponsive()` 返回 `{ isMobile }`，Task 11 使用一致。
- 后端 `NotificationResponse.commentId` 与前端 `notification.commentId` 命名一致。

**占位符扫描**：无 TBD / TODO；所有代码步骤均给出完整代码；命令均含预期输出。
