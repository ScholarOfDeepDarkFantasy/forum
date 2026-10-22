# 第六轮设计：实时通知（SSE）

- 日期：2026-10-22
- 状态：设计已确认，待实现
- 范围：服务端推送（Server-Sent Events）替代「通知靠 120 秒轮询」的现状

## 1. 背景与目标

现状：通知（回复 / 点赞 / 收藏）落库后，前端只有 120 秒轮询兜底（`frontend/src/App.vue:41`），通知页进入时才刷新；用户收到通知最多可能延迟两分钟。

目标（用户可见）：

1. 已登录用户在任何页面收到新通知时：顶栏角标即时 +1；右上角弹出轻提示；通知页若正打开且在第 1 页，列表头部即时插入该条。
2. 断线自动重连（退避 1s→2s→4s→8s→16s→30s 封顶），重连成功后刷新未读数；通知页打开时静默重拉第 1 页。
3. 断线期间自动恢复 120 秒轮询兜底，重连成功后停止轮询。
4. 现有 REST 契约与「自己操作不通知自己」等业务规则不变。

## 2. 范围

### 2.1 本轮做

| 项 | 说明 |
| --- | --- |
| SSE 通道 | `NotificationStreamService` + `SseEmitter`，单实例内存连接注册表 |
| 一次性 ticket | EventSource 不能带 `Authorization`，用短时一次性 ticket 换取连接 |
| 通知发布统一化 | `NotificationService.create(...)` 落库 + 事务提交后推送；改造 3 处调用 |
| 前端连接管理 | `useNotificationStream` composable（重连、退避、轮询降级、生命周期） |
| 前端状态与提示 | store 去重/插入、`pendingNewCount`、`ElNotification` 轻提示、通知页提示条 |
| 部署适配 | nginx 独立 stream location（关缓冲、长读超时）、`deploy.sh` 增 401 冒烟 |
| 测试与文档 | 后端集成测试、前端单测、README/DEVELOPMENT/部署手册更新 |

### 2.2 本轮不做

- 不做双向通信、在线状态、私聊。
- 不推送公开计数（帖子点赞数、评论数等面向所有用户的状态）。
- 不做多实例水平扩展（内存注册表；将来需 Redis pub/sub 或消息队列）。
- 不引入 STOMP / SockJS / WebSocket 依赖，不新增前端依赖（用原生 `EventSource`）。
- 不产生 SYSTEM 通知（当前无产生入口），不改通知落库规则。
- 不做 `Last-Event-ID` 增量补发（重连策略为刷新未读数 + 通知页首屏）。

## 3. 现状与约束

- 通知落库点：`CommentService`（REPLY，回复他人评论）、`LikeService`（LIKE / FAVORITE，仅当操作者不是帖子作者）。
- 现有接口（均需登录）：`GET /api/notifications`、`GET /api/notifications/unread-count`、`POST /api/notifications/read-all`、`POST /api/notifications/{id}/read`。
- 前端：顶栏 `el-badge` 读取 store `unreadCount`；`App.vue` 登录后 120 秒轮询；`NotificationsView` 进入时加载列表。
- 部署：前端 nginx 直出 `/forum/`，后端容器监听 `127.0.0.1:8086`，nginx `/forum/api/` 反代；Jenkins 构建/部署，容器单实例。
- 鉴权：无状态 JWT（`Authorization: Bearer`），`SecurityConfig` 对 GET 全放行、其余需登录；无 Session/CSRF。

## 4. 方案总览

```
浏览器 (任意页面)
  App.vue ──useNotificationStream──┐
     │ 1) POST /api/notifications/stream-ticket  (JWT)      → ticket
     │ 2) GET  /api/notifications/stream?ticket=…  (SSE)     ← 事件流
     │ 3) 断线 → 退避重连 + 期间 120s 轮询 /unread-count
     ▼
nginx :80  (snippet forum.conf)
  location = /forum/api/notifications/stream  → proxy_buffering off, 长读超时
  location   /forum/api/                      → 127.0.0.1:8086
     ▼
forum-backend 容器 (127.0.0.1:8086)
  NotificationController → NotificationStreamService（ticket、连接注册表、心跳、推送）
  CommentService / LikeService → NotificationService.create(...) → afterCommit 推送
```

## 5. 后端设计

### 5.1 连接注册表 `NotificationStreamService`

- `ConcurrentHashMap<Long, Set<SseEmitter>>`：一个用户可有多条连接（多标签页）。
- `register(userId)`：创建 `SseEmitter(timeoutMs)`，注册完成后返回；每个用户的连接数上限 **5**，超出时完成并移除最旧的一条，防止标签页泄漏堆积。
- 移除时机：`onCompletion`、`onTimeout`、`onError`、写失败。
- 心跳：`@Scheduled(fixedRateString = "${app.sse.heartbeat-ms:15000}")` 向所有连接写注释行 `:heartbeat`；写失败则清理该连接。新增 `config/SseConfig.java` 承载 `@EnableScheduling`（不放主类，避免污染 `@DataJpaTest` 等测试切片）；测试 profile 把心跳调长、超时调短，避免定时任务干扰断言。
- `push(Long recipientId, NotificationResponse response)`：序列化为 JSON，事件名 `notification`，`id` 为通知 id；对每条连接 try/catch，失败清理，不影响其他连接与调用方事务。
- 线程安全：推送可能来自任意 HTTP 线程（`afterCommit` 回调），写操作包在连接级同步块中；`SseEmitter` 本身线程安全，但避免同一事件的并发写乱序。

### 5.2 一次性 ticket `NotificationTicketService`

- `issue(userId)`：生成 UUID，记录 `(userId, expiresAt)`，默认 TTL 60 秒，签发时清理过期项（顺带限制 map 上限）。
- `consume(ticket)`：存在且未过期则返回 userId 并立即删除（一次性）；否则返回空。
- 内存实现，仅单实例有效；签发失败/连接失败不影响业务请求。

### 5.3 通知发布统一化

`NotificationService` 新增：

```java
public void create(User recipient, User sender, Post post, Comment comment,
                   NotificationType type, String content)
```

- 保存通知；若事务处于活动状态，注册 `TransactionSynchronization.afterCommit` 回调，在提交后把 `NotificationResponse` 推给接收者所有连接；无事务时直接推送。
- `NotificationResponse` 在事务内构造（`toResponse` 需要 lazy 的 sender/post/comment），推送阶段只做序列化与写出。
- 改造调用点：
  - `CommentService` 回复通知；
  - `LikeService` 点赞通知、收藏通知；
  - 三处的「自己操作不通知自己」判断保留在调用处，语义不变。

### 5.4 配置（`application.yml`，均可被环境变量覆盖）

| key | 默认 | 说明 |
| --- | --- | --- |
| `app.sse.timeout-ms` | `1800000` | 单条连接最长存活 30 分钟，到期客户端重连 |
| `app.sse.heartbeat-ms` | `15000` | 心跳间隔 |
| `app.sse.ticket-ttl-ms` | `60000` | ticket 有效期 |

`application-test.yml` 可调短（如 timeout 5s、ticket-ttl 1–2s）用于 TTL 测试。

### 5.5 错误与并发

- ticket 无效/过期/已用：`401` JSON `{"success":false,"message":"通知连接凭证无效或已过期"}`，不建立流。实现上抛专用异常 `InvalidStreamTicketException`，在 `GlobalExceptionHandler` 增加对应 `@ExceptionHandler` 返回 401（现有实现里未显式处理的异常会落到 500 兜底，不能依赖它）。
- 写失败（客户端已断开）：清理该连接，不影响其他连接；记录 debug 日志。
- 容器重建/重启：连接全部断开，前端退避重连并刷新未读数。
- 连接建立后不再校验 JWT：单条连接最长 30 分钟，封禁/登出最多延迟 30 分钟生效（与既有 T4 无主动失效问题一并记录）。

## 6. 前端设计

### 6.1 `frontend/src/composables/useNotificationStream.js`

状态机：`idle → connecting（取 ticket → new EventSource）→ open`；`error/close → 退避重连`。

- 连接：`notificationApi.streamTicket()` → `new EventSource(BASE_URL + 'api/notifications/stream?ticket=' + encodeURIComponent(ticket))`。
- `onopen`：停止兜底轮询；`refreshUnread()` 一次（补齐错过的角标）；重置退避。
- 通知事件：用 `es.addEventListener('notification', handler)` 监听（事件名不是默认 `message`），解析 JSON → `store.handleIncoming(payload)` → `showNotificationToast(payload)`。
- `onerror`：关闭当前 EventSource；启动 120 秒轮询兜底；按退避计划（1s/2s/4s/8s/16s/30s 封顶）重新取 ticket 连接。
- ticket 请求失败：401 交给既有 axios 拦截器（清 token 并跳登录），composable 不再重连；其他网络错误按退避重试。
- 生命周期：`watch` auth token/用户——有 token 启动（幂等），登出/401 关闭并清理；`App.vue` 在 `onMounted` 启动、`onUnmounted` 销毁。
- 计时器与 EventSource 构造函数可注入，便于 vitest 使用 fake timers 与 stub。
- 手动关闭（登出）置 `closed` 标志，禁止重连。

### 6.2 Pinia store 扩展（`frontend/src/stores/notifications.js`）

- 新增 state：`pendingNewCount`、`viewOpen`（通知页是否打开）、`listLoaded`（列表是否加载过）；`NotificationsView` 在 `onMounted/onUnmounted` 设置 `viewOpen`，`loadList` 成功后置 `listLoaded = true`。
- `handleIncoming(n)`：
  1. 若当前 `list.content` 已含该 id，忽略（去重）；
  2. `unreadCount += 1`；
  3. 若 `viewOpen && listLoaded && listPage === 0`：头部插入并截断到 `PAGE_SIZE`，`totalElements += 1`；
  4. 否则 `pendingNewCount += 1`。
- `flushPending()`：`loadList(0)` 后清零 `pendingNewCount`；`loadList(0)` 本身成功时也清零。
- 现有 `refreshUnread/loadList/markRead/markAllRead` 语义不变（`loadList` 仅新增 `listLoaded` 标记与第 0 页时的 `pendingNewCount` 清零）。

### 6.3 轻提示 `showNotificationToast(n)`

- 基于 `ElNotification`（右上角）：标题按类型「收到新回复 / 新点赞 / 新收藏」，正文用 `content`，4 秒自动关闭，点击跳 `/notifications`。
- 深浅主题由 Element Plus 变量自动适配，不新增样式文件。

### 6.4 通知页 `NotificationsView.vue`

- `pendingNewCount > 0`（说明不在第 1 页）时显示顶部提示条「有 N 条新通知，点击查看」，点击 `flushPending()` 回到第 1 页。
- 第 1 页时新通知由 store 直接插入头部。
- 分页、全部已读、单条已读语义不变。

### 6.5 `App.vue`

- 用 composable 的启动/停止替换现有 `setInterval(() => notifications.refreshUnread(), 120_000)`；登录成功后刷新未读数的逻辑保留。
- 顶栏模板不变（既有 `el-badge` 绑定 `unreadCount`）。

## 7. 接口契约

### 7.1 `POST /api/notifications/stream-ticket`（需登录）

```json
{ "success": true, "message": "ok", "data": { "ticket": "uuid", "expiresIn": 60 } }
```

### 7.2 `GET /api/notifications/stream?ticket=<uuid>`

- 成功：`200`，`Content-Type: text/event-stream`，响应头 `X-Accel-Buffering: no`、`Cache-Control: no-cache`。
- 失败（ticket 缺失/无效/过期/已用/非本人）：`401` JSON，不建立流。
- 帧格式：

```
:heartbeat                     ← 每 15 秒
event: notification
id: 42
data: {"id":42,"type":"REPLY",...}   ← NotificationResponse 同构 JSON

```

## 8. 部署变更

### 8.1 nginx（`deploy/nginx/forum.conf`，由 `deploy.sh` 同步）

在通用 `/forum/api/` 之前新增精确匹配：

```nginx
location = /forum/api/notifications/stream {
    proxy_pass http://127.0.0.1:8086/api/notifications/stream$is_args$args;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header Connection '';
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 3600s;
    proxy_send_timeout 3600s;
}
```

### 8.2 `deploy/deploy.sh`

- 冒烟新增：`curl -H 'Host: raspi' http://127.0.0.1/forum/api/notifications/stream` 必须返回 **401**（证明请求进入后端而非 SPA 回退 200 HTML）。

### 8.3 Jenkins / 容器

- 不新增后端依赖，不改 `Dockerfile` / `docker-compose.yml`；构建与部署流程不变。
- 部署重启容器会断开在线连接，前端自动重连并刷新未读数。

## 9. 测试计划

### 9.1 后端（H2 + `RANDOM_PORT`）

1. 未登录获取 ticket → 401。
2. 正常获取 ticket → 200 且含 `ticket/expiresIn`。
3. 无效 / 已用 ticket 连接流 → 401。
4. 有效 ticket 连接 → 200，`text/event-stream`。
5. 真实推送：A 点赞 B 的帖子，B 的流在超时内收到 `event: notification`，data 中 type=LIKE 且通知 id/内容正确；A 自己的流无该事件（隔离）。
6. 既有 13 类 / 105 用例全绿（通知相关流程测试不得回归）。

### 9.2 前端（vitest）

1. `useNotificationStream.spec`（stub `EventSource`、fake timers）：连接前先取 ticket；open 后停轮询且刷新未读数；error 后退避并用**新** ticket 重连；断线期间每 120s 轮询；登出/卸载关闭且不再重连；401（ticket 失效）走重连。
2. `stores/notifications.spec`：`handleIncoming` 去重、角标 +1、第 1 页头部插入/截断/total、非第 1 页 pending 计数、`flushPending`。
3. `NotificationsView.spec`：提示条出现/点击刷新。
4. `breakpoint.spec`：`@media` 仍为 6 处。

### 9.3 上线验收（raspi）

1. 部署成功、Build SUCCESS、容器 healthy（既有冒烟）。
2. 无 ticket 的 stream 请求返回 401（`deploy.sh` 冒烟）。
3. 通过 nginx 用真实 JWT 取 ticket 并读流，另一账号点赞该用户帖子 → 流内收到 notification 事件。
4. 浏览器：角标即时变化、右上角轻提示、通知页第 1 页头部插入；切到其他页显示「有 N 条新通知」。
5. 断网/关闭代理后恢复轮询，重连成功后刷新未读数。

## 10. 文档更新

- `README.md`：功能清单加「实时通知（SSE）」；更新测试基线与接口数量。
- `docs/DEVELOPMENT.md`：新增「§4.11 实时通知（SSE）」；§2.2 进度、§3.2 测试基线、§5.1 记录限制（单实例内存注册表、重连补齐策略、连接 30 分钟）、§5.2「WebSocket 实时通知」标记为已交付（注明改用 SSE 的决策）、§8 变更日志。
- `docs/deployment/README.md`：nginx 新 location、stream 冒烟语义、排障（事件延迟先查 `proxy_buffering` 与读超时）。

## 11. 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| 代理缓冲导致事件延迟 | 独立 location + `proxy_buffering off` + 后端 `X-Accel-Buffering: no`；部署冒烟 |
| 每用户连接数无界 | 每用户上限 5，淘汰最旧 |
| 推送线程/事务安全 | `afterCommit` 推送；DTO 事务内构造；失败只清理单连接 |
| ticket 泄漏/复用 | 一次性 + 60s TTL；不入日志（URL 仅一次性出现） |
| 连接长期存活绕过登出 | 连接最长 30 分钟；写入 T4 说明 |
| 单实例限制 | 文档明确水平扩展前提 |
| 回滚成本 | 无数据库迁移；回退前端/nginx 即恢复轮询模式 |

## 12. 验收标准

1. 后端新增用例全部通过，既有 105 用例不回归。
2. 前端新增用例全部通过，既有 274 用例不回归，`breakpoint.spec` 6 处 `@media`。
3. raspi 部署成功，`deploy.sh` 冒烟全绿（含 stream 401）。
4. 真实双账号验证：点赞触发即时通知（角标/轻提示/通知页插入）；断线重连与轮询降级可用。
5. 文档与变更日志更新，提交哈希回填。

## 13. 决策记录

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 通道 | SSE 而非 WebSocket | 需求单向（服务端→用户）；Spring MVC `SseEmitter` 无新依赖；浏览器自动重连语义+可手写退避；复用 `/forum/api/` 反代，仅需关缓冲与读超时；测试与运维更简单 |
| 鉴权 | 一次性 ticket | `EventSource` 无法设置 `Authorization`；避免 JWT 出现在 URL/日志；60s + 一次性 |
| 降级 | SSE 断线期间恢复 120s 轮询 | 代理/中间设备可能屏蔽长连接；轮询成本可控，重连成功即停 |
| 补齐 | 重连刷新未读数 + 通知页首屏 | 简单可靠；`Last-Event-ID` 增量补发收益小、状态多 |
| 发布位置 | 事务 `afterCommit` 推送 | 避免通知已推送但事务回滚的不一致 |
