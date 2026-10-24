# 第八轮设计：质量加固（鉴权兜底、限流健壮性、首页转义、登出静默、主题同步）

- 日期：2026-10-24
- 状态：设计已确认，待实现
- 基线：`d56a8aa`（后端 163 用例 / 20 个测试类，其中 1 例 POSIX 权限用例在 Windows 跳过；前端 31 个 spec / 314 用例；HTTP 端点 39 个；工作区干净）
- 范围：收敛第七轮双审遗留的 Important 项与两条长期挂账的低危技术债（T12、T13）。**不加用户可见新功能**，主题跨标签同步是唯一的 UX 修复。
- 计划：`docs/superpowers/plans/2026-10-24-round8-quality-hardening.md`（6 个任务，逐任务提交）

## 1. 背景与目标

第七轮交付了 JWT 主动失效与登录失败限流。上线后做了两阶段代码审查（规格符合性 + 代码质量），把不阻塞上线的问题记录在 `docs/DEVELOPMENT.md` §4.12「已知取舍（代码审查记录）」共 11 条，其中：

- **4 条是「错误路径没有兜底」**：过滤器只吞 `NumberFormatException`，数据库异常会让受保护请求变成 500 且响应体不是统一 `Result`；429 每次打 WARN；`Retry-After` 断言依赖「两次请求落在同一秒」；重复登出会连弹两条自相矛盾的提示并整页跳转。
- **3 条是「有实现、没断言」**：封禁的双保险（过滤器查 status + 服务端版本 +1）没有互相独立的用例；清理逻辑的删除方式有竞态；限流构造器不校验阈值。
- **2 条是既有技术债**：T12（主题不跨标签同步）、T13（首页关键词不转义 LIKE 通配符）。
- 其余（`recordSuccess` 清空整 IP 窗口、`X-Forwarded-For` 首段伪造、IPv4-mapped 环回、SSE 长连接不即断、前端登出后端失败静默）是**设计取舍**，本轮不做，继续记录。

目标（可验收）：

1. 数据库不可用时，带 token 的请求**不会**变成 500 + 非 `Result` 响应体；受保护接口返回 401，公开 GET 仍匿名可用；数据库恢复后同一 token 仍然有效。（R1）
2. 过滤器自身的四条否定路径各有独立单测；封禁的「双保险」各有独立用例。（R1）
3. 限流器阈值配置非法时**启动即失败**；清理逻辑不再按 key 盲删并发新建的窗口。（R2）
4. 首页 `GET /api/posts?keyword=` 里的 `%`/`_`/`!` 按字面量匹配，与 `GET /api/posts/search` 语义一致。（R3）
5. 重复登出（token 已失效）不再出现「已退出登录」+「请先登录」两条提示与整页跳转；登出成功提示一定在后端尽力之后出现。（R4）
6. 主题选择在同源多标签页之间同步，无需刷新；坏值与清空都回退跟随系统。（R5）
7. 全量测试、构建、上线冒烟与文档回填完成，基线抬到后端 21 类 / 176 例、前端 31 spec / 321 例。（R6）

## 2. 范围

### 2.1 本轮做

| 项 | 内容 | 主要落点 | 新增测试 |
| --- | --- | --- | --- |
| R1 | 过滤器会话回查 fail-closed；过滤器 7 例单测；封禁双保险各一条独立集成用例 | `security/JwtAuthenticationFilter.java`、`security/JwtAuthenticationFilterTest.java`（新）、`controller/AuthInvalidationIntegrationTest.java` | +9 |
| R2 | 限流器构造器校验、清理改身份 CAS、429 日志降级、`Retry-After` 断言稳定化 | `security/LoginRateLimiter.java`、`exception/GlobalExceptionHandler.java`、`controller/LoginRateLimitIntegrationTest.java`、`security/LoginRateLimiterTest.java` | +3 |
| R3 | 首页关键词先转义再进 JPQL，`searchIds` 加 `ESCAPE '!'` | `service/PostService.java`、`repository/PostRepository.java`、`controller/PostFlowIntegrationTest.java` | +1 |
| R4 | 登出请求静默（401 不弹提示、不跳转，但仍清本地态）；`App.vue` await 后再提示 | `frontend/src/api/index.js`、`frontend/src/App.vue` 与三个 spec | +4（前端） |
| R5 | 主题跨标签同步（模块级 `storage` 监听，回读而非信任 `newValue`，不写回） | `frontend/src/composables/useTheme.js` 与 spec | +3（前端） |
| R6 | 全量验证 + README/DEVELOPMENT/部署手册更新 + 上线与回填 | 文档、CI、raspi 冒烟 | 0 |

合计：后端 +13 用例（163 → 176）、+1 测试类（20 → 21）；前端 +7 用例（314 → 321）、spec 文件数不变（31）；端点 39 个不变；无数据库迁移、无新增依赖、无配置项变化。

### 2.2 本轮不做（附理由）

| 不做的事 | 为什么不做 |
| --- | --- |
| **前端 chunk 分包（`index` 877.89 kB 体积警告）** | 要真正降下来必须把 Element Plus 改成按需引入，需要 `unplugin-vue-components` + `unplugin-auto-import` 两个构建链依赖并改写 `vite.config.js`；而现有 31 个 spec 大量直接挂载 `el-*` 组件（`App.spec.js`、`AdminView.spec.js` 等都靠 `global.stubs` 提供 `el-dropdown`/`el-button` 等），按需引入后组件注册方式改变，这些 mock/stub 的解析路径要整体重审——风险/收益不匹配。体积警告是可接受的（gzip 后约 280 kB，内网部署、无移动端 KPI），留独立一轮按「构建链改造 → 测试全量复核 → 体积对比」推进。 |
| 限流改共享存储 | 当前单机单容器部署，多实例才需要；换 Redis/Bucket4j 是引入中间件级别变更，与「不新增依赖」的既有约定冲突。已在 `docs/DEVELOPMENT.md` §5.2 挂为候选。 |
| T14 搜索分词/相关度 | 需要 `tsvector` 与中文分词扩展（如 `zhparser`/`pg_jieba`），属数据库能力变更，需独立设计（索引策略、迁移、回填）。 |
| T15 `posts.content` 长度上限 | 是产品规则（用户可接受的正文上限）而不是纯技术债，需要与「编辑器字数提示、历史超长数据、`snippet` 行为」一起定，避免突然拒绝已有内容。 |
| E2E 框架（T3） | 需要浏览器二进制与 CI 集成，仓库当前无前端 CI；一次性的 Playwright 脚本仍在仓库外，整轮工作量大于本轮所有改动之和。 |
| 登出/封禁后 SSE 立即断开 | 需要服务端主动找到该用户的所有连接并 `complete`，还要处理「封禁后重连」的 ticket 拒绝路径；当前最长 30 分钟兜底可接受（§4.12 第 8 条）。 |
| 改 401 的全局拦截语义 | 会话失效的「提示 + 跳登录页」是全站唯一兜底，不能为登出这一个场景牺牲；改用逐请求 `silent` 标志（见 §8.2）。 |
| 新增端点 / 迁移 / 配置项 | 本轮是加固，契约不变——这也让回滚可以只 revert 提交。 |

## 3. 现状审计（file:line）

审计基线 `d56a8aa`，逐条核对过源码（行号即当前文件行号）。

| # | 现象 | 位置 | 后果 | 本轮动作 |
| --- | --- | --- | --- | --- |
| 1 | `isSessionAlive` 只 catch `NumberFormatException`，随后直接查库 | `src/main/java/org/song/forum/security/JwtAuthenticationFilter.java:71-82`（catch 在 `:73-77`，查库在 `:78-81`） | 数据库抖动/连接池耗尽时 `DataAccessException` 穿出过滤器。`@ControllerAdvice` 只覆盖 DispatcherServlet 内的异常，过滤器异常直接抛给容器 → 走 `/error` → Spring Boot 默认 JSON（`timestamp/status/error/path`），**不是** `Result`，前端拦截器拿不到 `message`；监控上表现为 5xx 尖峰 | 外层 catch `DataAccessException` → `log.debug` + `return false`（fail-closed，§5） |
| 2 | 过滤器类没有 logger | `JwtAuthenticationFilter.java:29-31`（只有 `@Component`、`@RequiredArgsConstructor`） | 无法在过滤器内记录任何判断依据 | 加 `@Slf4j` |
| 3 | 封禁双保险没有独立用例 | `src/test/java/org/song/forum/controller/AuthInvalidationIntegrationTest.java:63-133`（6 例） | 「过滤器查 status」与「服务端版本 +1」任一失效，6 例仍全绿：删掉 status 判断不会红（版本也 +1 了），不 +1 版本也不会红（status 已是 BANNED） | 各加一条只触发单侧的用例（§5.4） |
| 4 | 限流器构造器不校验阈值 | `src/main/java/org/song/forum/security/LoginRateLimiter.java:40-48` | `lock-ms<=0` 会静默关闭锁定；`max-failures<1` 让每次失败都立刻锁；`window-ms<=0` 让窗口永远为空。配置写错只会在生产表现为「限流失效」 | 构造器 fail fast（§6.1） |
| 5 | `cleanupExpired()` 用迭代器按 key 删除 | `LoginRateLimiter.java:105-124`（`Iterator` 声明 `:108`，`iterator.remove()` 在 `:118`，import 在 `:12`） | `ConcurrentHashMap` 迭代器的 `remove()` 按 **key** 删，不校验 value 身份。`recordSuccess(ip)` 删键后 `recordFailure(ip)` 会 `computeIfAbsent` 新建窗口，此时旧迭代器手里的 entry 已过期，`remove()` 会把**新建**的窗口删掉 → 少计一次失败、甚至丢掉刚建立的锁定 | 改 `windows.remove(key, window)`（key + 实例身份 CAS，§6.2） |
| 6 | 429 处理器每次都 WARN | `src/main/java/org/song/forum/exception/GlobalExceptionHandler.java:145-151`（`log.warn` 在 `:147`） | 锁定期间每条请求一条 WARN，可被低成本流量刷日志；且消息里没有 IP，信息量为零（真正的事实日志是 `LoginRateLimiter.recordFailure` 的 `:83` WARN） | 降为 `log.debug`（§6.3） |
| 7 | `Retry-After` 断言依赖同一秒 | `src/test/java/org/song/forum/controller/LoginRateLimitIntegrationTest.java:71`（`isIn("59","60")`） | 3 次失败 + 1 次 check 跨过整秒边界（慢 CI、GC、Docker 冷启动）就得到 58，测试假红 | 解析为 long 后 `isBetween(1L, 60L)`（§6.4） |
| 8 | 首页关键词不转义 | `src/main/java/org/song/forum/service/PostService.java:126-138`（`String kw = blankToEmpty(keyword)` 在 `:135`，传给 `searchIds` 在 `:136`）；`src/main/java/org/song/forum/repository/PostRepository.java:25-35`（两处 `LIKE` 在 `:29-30`，无 `ESCAPE`） | 搜「100%」命中「1000 items」；搜 `a_b` 命中 `axb`。首页与 `/api/posts/search` 语义分叉（后者 `:172` 转义 + `PostRepository.java:47-49` `ESCAPE '!'`） | service 先转义 + JPQL 加 `ESCAPE '!'`（§7） |
| 9 | 登出请求走通用 401 分支 | `frontend/src/api/index.js:53-68`（401 分支 `:57-63`，`ElMessage.error('请先登录')` 在 `:60`，`location.href` 在 `:62`） | token 已失效时点登出：先弹「已退出登录」（`App.vue:29`），再弹「请先登录」并整页跳 `/login`；两条提示自相矛盾，且整页刷新丢掉 SPA 状态 | 逐请求 `silent` 标志（§8） |
| 10 | 登出不 await | `frontend/src/App.vue:20-32`（`auth.logout()` 在 `:26`，提示在 `:29`，跳转在 `:30`） | 后端还没返回就宣布「已退出登录」；后端 15s 超时的窗口里提示已经出现 | `async` + `await`（§8.5） |
| 11 | 主题不跨标签同步（T12） | `frontend/src/composables/useTheme.js:1-110`（无 `storage` 监听；`readStoredMode` 在 `:6-13`，`setMode` 在 `:87-90`） | 两个标签页一亮一暗，刷新后才一致 | 模块级 `storage` 监听（§9） |
| 12 | 首页转义缺文档（T13 附属） | `docs/DEVELOPMENT.md:637`（T13 条目）、`:195-197`（只写了搜索接口的关键词转义说明） | 文档与修复后的行为不一致 | R6 更新 §5.1 与 §3.4 |

审计复核结论：上述 12 条与任务书描述**全部一致**，无需修正审计前提。唯一与任务书不符的是**测试数量算术**：任务书预期后端 177，按 R1–R5 用例清单精确相加为 `163 + 7 + 2 + 3 + 1 = 176`（详见 §11 与计划 §8 的算式）。本设计以**用例清单**为准，计划写 176，并在 §11 注明差异来源。

## 4. 方案总览

```
请求 → JwtAuthenticationFilter
          validate 签名/过期
            ├─ null → 不认证
            └─ claims → isSessionAlive()
                  ├─ subject 非数字 → false
                  ├─ DataAccessException → log.debug + false   ← R1 新增（fail-closed）
                  ├─ 无记录 / BANNED / 版本不符 → false
                  └─ true → 建立认证态
          → 受保护接口由 entry point 返回 401（Result 体），公开 GET 匿名可用

首页列表：PostService.list
          keyword → trim → 小写 → escapeLike（! % _ 前加 !）        ← R3 新增
          → PostRepository.searchIds（JPQL 两处 LIKE 加 ESCAPE '!'） ← R3 新增

登出（前端）：authApi.logout() 带 { silent: true }
          → 401：清 jf_token/jf_user（保留），不弹提示、不跳转        ← R4 新增
          → 其它错误：不弹提示（仍 reject）
          → App.vue await auth.logout() 之后才 success + push('/')   ← R4 新增

主题：其他标签页写 localStorage('jf_theme')
          → window 'storage' 事件 → onStorageChange
                ├─ storageArea 不是 localStorage → 忽略
                ├─ key 既不是 null 也不是 STORAGE_KEY → 忽略
                └─ mode.value = readStoredMode()（不写回）           ← R5 新增
```

| 项 | 变更前 | 变更后 | 影响面 |
| --- | --- | --- | --- |
| 过滤器 | DB 异常 → 500 默认错误体 | DB 异常 → 401 `Result`，`debug` 日志 | 仅 DB 故障时表现；正常路径字节级不变 |
| 限流器 | 阈值不校验；清理按 key 删 | 构造期校验；身份 CAS 删除 | 启动期失败更早；清理不再误删 |
| 429 日志 | 每请求 WARN + 事实 WARN | 每请求 DEBUG + 事实 WARN | 日志量 |
| `Retry-After` 断言 | 59/60 二选一 | 1..60 区间 | 仅测试稳定性 |
| 首页 keyword | `%`/`_` 通配 | 字面量 | 匹配结果（修正） |
| 登出 | 双提示 + 整页跳转 | 静默 + 单提示 + SPA 跳转 | 仅前端交互 |
| 主题 | 标签页各自为政 | `storage` 事件同步 | 仅前端展示 |

## 5. R1：鉴权 fail-closed 与会话失效测试加固

### 5.1 决策：为什么 fail-closed

三个候选（在 `isSessionAlive` 里处理 `DataAccessException`）：

| 方案 | 做法 | 问题 |
| --- | --- | --- |
| A. 包装成 500 `Result` | 在过滤器里 try/catch 后手写 JSON 响应 | 违反「过滤器不写响应体」的既有约定（entry point 是唯一写 401 的地方，见 `JwtAuthenticationFilter.java:23-27` 的类注释）；DB 抖动期间 5xx 会污染监控/告警与 nginx 错误率 |
| B. 原样抛出（现状） | 什么都不做 | 500 + 非 `Result` 体，前端拿不到 `message`；日志里是长栈，容易被误判为代码 bug |
| C. **fail-closed** | `log.debug` + `return false` | 受保护接口 401，公开 GET 匿名可用；语义等同「这次请求没能证明会话有效」 |

选 **C**。三点理由：

1. **安全方向正确**：认证是「必须证明有效」的判断，依赖不可用时应当拒绝，而不是放行或崩溃。`return false` 与「用户不存在」「被封禁」「版本不符」走同一条路径，不会新增绕过。
2. **不把 DB 故障伪装成登出**：`isSessionAlive` 返回 false 只影响这一次请求的认证态建立，**不改数据库、不递增版本号**。数据库恢复后同一 token 仍然有效（版本没变），用户不需要重新登录——这一点和「登出」有本质区别，需在代码注释与文档里写清。
3. **错误语义可被前端消费**：401 走 `SecurityConfig` 的 entry point，响应体是统一 `Result.fail("未登录或登录已过期")`；前端的 401 分支已有清态 + 跳登录兜底（`frontend/src/api/index.js:57-63`），不需要新逻辑。

只 catch `org.springframework.dao.DataAccessException`：它是 Spring 对 JPA/JDBC 异常翻译后的基类，覆盖面足够；**不**catch `RuntimeException` 兜底——那会把 NPE 之类的编程错误也吞成 401，把 bug 变成「用户莫名被登出」，排查成本更高。

日志用 `log.debug` + `e.toString()`（与 `GlobalExceptionHandler.java:159-163` 处理断连的风格一致）：DB 故障期间每请求一条 WARN 会刷屏，而真正的失败在数据源层已有日志。为此类加 `@Slf4j`。

### 5.2 代码

`src/main/java/org/song/forum/security/JwtAuthenticationFilter.java`：类注解加 `@Slf4j`，`isSessionAlive` 拆成「解析 subject」与「查库」两段 try：

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final UserRepository userRepository;

    // headerName / prefix 的 @Value 字段保持不变

    /**
     * 会话是否仍然有效：用户存在、未被封禁、且 token 版本与库中一致。
     * <p>数据库不可用时按<b>未认证</b>处理（fail-closed）：受保护接口返回 401，
     * 公开 GET 仍匿名可用。这里不修改任何持久化状态，因此库恢复后同一 token 依然有效，
     * 与「登出会让 token 永久失效」是两回事。</p>
     */
    private boolean isSessionAlive(Claims claims) {
        Long userId;
        try {
            userId = Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            return false;
        }
        try {
            return userRepository.findTokenStateById(userId)
                    .filter(state -> state.getStatus() != UserStatus.BANNED)
                    .map(state -> state.getTokenVersion() == JwtTokenProvider.tokenVersion(claims))
                    .orElse(false);
        } catch (DataAccessException e) {
            log.debug("回查会话状态失败，按未认证处理: {}", e.toString());
            return false;
        }
    }
}
```

新增 import：`lombok.extern.slf4j.Slf4j`、`org.springframework.dao.DataAccessException`。

### 5.3 单测设计（`JwtAuthenticationFilterTest`，7 例）

用**真实** `JwtTokenProvider`（`new JwtTokenProvider(SECRET, 60_000)`）+ Mockito mock 的 `UserRepository`，而不是 mock `Claims`：

- mock `Claims` 要逐方法 stub，`MockitoExtension` 的严格模式会因「某个用例没用到 roles stub」报 `UnnecessaryStubbingException`；
- 真实 provider 让「签名 → 解析 → claims」整条路径也进入覆盖，`validate` 返回 null 的用例只要传一个非 JWT 字符串即可。

`UserTokenState` 用匿名实现（`getTokenVersion()` 是 primitive `long`，见 `UserRepository.java:31-38`），避免 mock 接口时的严格 stub 噪音。`headerName`/`prefix` 用 `ReflectionTestUtils.setField` 注入（`@Value` 在手动 new 时不生效），与 `AvatarServiceTest` 的既有写法一致。

| # | 用例 | 输入 | 期望 |
| --- | --- | --- | --- |
| 1 | `validTokenEstablishesAuthentication` | 有效 token（ver=0）+ 投影 ACTIVE/0 | 认证态存在，principal 的 userId/username/authorities 正确，链继续 |
| 2 | `noAuthorizationHeaderPassesThroughWithoutAuthentication` | 无头 | 无认证态、链继续、**仓库零交互** |
| 3 | `invalidTokenDoesNotAuthenticate` | `Bearer not-a-jwt` | `validate` 返回 null → 无认证态、仓库零交互 |
| 4 | `nonNumericSubjectDoesNotAuthenticate` | 手工签发 `sub=abc` | 无认证态、**仓库零交互**（在查库前就返回） |
| 5 | `repositoryFailureIsTreatedAsUnauthenticated` | 仓库抛 `DataAccessResourceFailureException` | **不抛异常**、无认证态、链继续（这是唯一的 RED 用例） |
| 6 | `bannedUserDoesNotAuthenticate` | 投影 BANNED/0 + 有效 token | 无认证态 |
| 7 | `versionMismatchDoesNotAuthenticate` | token ver=3 + 投影 ACTIVE/4 | 无认证态 |

用例 4 的意义是把「subject 解析在查库之前」这个顺序锁死：如果将来有人把 `Long.valueOf` 挪进查库之后，`verifyNoInteractions` 会立刻红。

### 5.4 集成测试：封禁双保险各一条

`AuthInvalidationIntegrationTest` 现有 6 例（`:63-133`）全部走「两条保险同时生效」的路径，删掉任意一条都不会红。新增 2 例，各只触发单侧：

| 用例 | 手法 | 锁定的保险 |
| --- | --- | --- |
| `bannedStatusWithoutVersionBumpRejectsToken` | `jdbcTemplate.update("update users set status = 'BANNED' where id = ?")`，**不动** `token_version` | 过滤器自己查 `status`（`JwtAuthenticationFilter.java:79`）。若这条判断被删，token 版本仍匹配 → 200 |
| `unbanDoesNotReviveOldToken` | 管理 API 封禁（版本 +1）→ 401 → 管理 API 改回 ACTIVE → 仍 401 → 重新登录 → 200 | 服务端版本递增（`AdminUserService.changeStatus` 的 BANNED 分支）。若版本不 +1，解封后旧 token 会复活 |

第二条的后半段「重新登录 200」很关键：它区分「版本失配」与「账号不可用」，否则用例无法证明 401 的原因是版本。

这两条是**特征化用例**（characterization test）：当前实现即通过，价值在于防回归。计划里明确标注「直接绿；若红说明现状与审计不符，停下来核对」。

## 6. R2：限流器健壮性

### 6.1 构造器校验

```java
public LoginRateLimiter(Clock clock,
                        @Value("${app.security.login.max-failures:5}") int maxFailures,
                        @Value("${app.security.login.window-ms:900000}") long windowMs,
                        @Value("${app.security.login.lock-ms:900000}") long lockMs) {
    if (maxFailures < 1) {
        throw new IllegalArgumentException(
                "app.security.login.max-failures 必须 >= 1，当前 " + maxFailures);
    }
    if (windowMs <= 0) {
        throw new IllegalArgumentException(
                "app.security.login.window-ms 必须 > 0，当前 " + windowMs);
    }
    if (lockMs <= 0) {
        throw new IllegalArgumentException(
                "app.security.login.lock-ms 必须 > 0，当前 " + lockMs);
    }
    this.clock = clock;
    this.maxFailures = maxFailures;
    this.windowMs = windowMs;
    this.lockMs = lockMs;
}
```

决策与理由：

- **在构造器里抛**，不用 `@PostConstruct` / `@Validated`：单测直接 `new LoginRateLimiter(clock, ...)` 就能覆盖，不需要 Spring 上下文；Spring 启动时自然也会失败（bean 创建期），运维在容器日志第一屏就能看到。
- **消息里带配置全名**（`app.security.login.max-failures` 等）：运维改的是 `.env` 的 `LOGIN_MAX_FAILURES`，但日志里要能 grep 到 yml 的键名，两种命名都能对上文档（`docs/DEVELOPMENT.md` §4.12 与 `.env.example` 都写了映射）。
- 三类阈值各有独立用例（+3），而不是一个参数化用例：失败消息不同，分开写更容易定位。

### 6.2 清理改成身份 CAS

现状 `LoginRateLimiter.java:105-124`：

```java
Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
while (iterator.hasNext()) {
    Window window = iterator.next().getValue();
    synchronized (window) {
        ...
        if (stale) {
            iterator.remove();   // 按 key 删
            removed++;
        }
    }
}
```

问题：`ConcurrentHashMap` 的迭代器 `remove()` 删除的是**当前 key 映射**，不校验值是否还是迭代时那个 `Window`。与 `recordSuccess`/`recordFailure` 交错时的坏序列：

1. 清理线程取到 `ip → A`，判定 A 已过期（`stale`）；
2. 成功登录线程 `recordSuccess(ip)` 删掉 `ip`；
3. 同一 IP 立刻又失败一次，`recordFailure` 的 `computeIfAbsent` **新建** `ip → B`（B 有失败记录，甚至可能已进入锁定）；
4. 清理线程执行 `iterator.remove()` → 把 **B** 删了。

后果是「少计一次失败」，极端情况下刚建立的锁定被抹掉。改成按 key + 实例身份删除：

```java
public int cleanupExpired() {
    long now = clock.millis();
    int removed = 0;
    for (Map.Entry<String, Window> entry : windows.entrySet()) {
        Window window = entry.getValue();
        synchronized (window) {
            if (window.lockedUntil > now) {
                continue;
            }
            Long lastFailure = window.failures.peekLast();
            boolean stale = lastFailure == null || lastFailure <= now - windowMs;
            // 按 key + 实例身份删除：即使上面的判断与删除之间被 recordSuccess/recordFailure
            // 换成了新窗口，也不会误删那个新窗口（ConcurrentHashMap 的迭代器 remove 只认 key）。
            if (stale && windows.remove(entry.getKey(), window)) {
                removed++;
            }
        }
    }
    return removed;
}
```

`windows.remove(key, value)` 是 `ConcurrentHashMap` 提供的原子「匹配才删」，等价于一次 CAS；`removed++` 只在真的删除时发生，返回值的语义（清理条数）保持不变，`cleanupScheduled()` 与既有 9 个用例都不用改。`java.util.Iterator` import 随之删除（`:12`）。

不为竞态补确定性用例：要稳定复现必须在「读完 value」与「remove」之间插桩，而当前设计没有可注入的 seam（加 seam 只为测试会污染产品代码）。既有 `cleanupKeepsLockedWindowsAndRemovesStaleOnes`（`LoginRateLimiterTest.java:110-121`）继续覆盖行为本身；竞态修复靠代码评审 + `remove(k,v)` 的语义保证。这条取舍写进 §13。

### 6.3 429 日志降级

`GlobalExceptionHandler.java:145-151` 的 `log.warn` → `log.debug`。

- 事实记录已经在 `LoginRateLimiter.recordFailure`（`:83`，带 IP、阈值、锁定时长），那是唯一有诊断价值的日志；
- 处理器这层每条 429 都打一条**不含 IP** 的 WARN，锁定期间等于给攻击者一个「刷日志放大器」，且 WARN 级别会进运维告警；
- 保留一条 debug 便于本地排查「为什么这个请求是 429」。

### 6.4 `Retry-After` 断言稳定化

`LoginRateLimitIntegrationTest.java:71` 的 `isIn("59","60")` 隐含「3 次失败与第 4 次请求落在同一秒」。锁定时长是 60000ms（`:35`），`retryAfterSeconds = ceil((lockedUntil - now)/1000)`，数学上界就是 60：只要请求在锁定之后，剩余 ≤ 60000，因此取值恒在 `[1, 60]`。改成：

```java
long retryAfterSeconds = Long.parseLong(locked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
assertThat(retryAfterSeconds).isBetween(1L, 60L);
```

用 `parseLong` 而不是断言字符串，顺带校验了「头里是整数秒」这个契约（真正会被前端/压测脚本消费的格式）。

## 7. R3：首页关键词 LIKE 转义

### 7.1 决策：`ESCAPE '!'`，与搜索接口同一约定

| 方案 | 问题 |
| --- | --- |
| `ESCAPE '\'` | HQL 里反斜杠本身是字符串转义符，要写 `ESCAPE '\\'`；不同数据库对 `standard_conforming_strings` 的处理不同，H2 与 PostgreSQL 行为容易分叉 |
| `ESCAPE '!'`（选它） | 与 `PostRepository.java:44-53` 的 `searchByKeyword` 完全一致；`PostService.escapeLike`（`:283-293`）本来就是按 `!` 写的，两个端点共用同一个转义函数，仓库里只有一种约定 |
| 应用层过滤（查出来再筛） | 会破坏分页（`totalElements` 与页内容不一致），且抵消 SQL 层过滤 |

### 7.2 转义顺序与小写

`PostService.list`（`:135`）：

```java
String kw = blankToEmpty(keyword);
if (!kw.isEmpty()) {
    // 与 GET /api/posts/search 一致：%/_/! 按字面量匹配（转义后 JPQL 用 ESCAPE '!'）。
    // 空串必须保持空串：JPQL 用 :keyword = '' 表示「无关键字」，转义后变成 '!!' 会让条件永远为假。
    kw = escapeLike(kw.toLowerCase(Locale.ROOT));
}
Page<Post> skeletons = postRepository.searchIds(boardId, kw, pageable);
```

- `%`/`_`/`!` 都不受大小写影响，所以「先小写再转义」与「先转义再小写」等价；取与 `search()`（`:172`）相同的顺序，避免两处实现看起来不一致。
- 大小写不敏感仍由 JPQL 的 `LOWER(...)` 保证；传入的 pattern 已小写，`LOWER` 幂等。
- 参数名保持 `keyword`，`searchIds` 签名不变（`PostRepository.java:33-35`），最小 diff。

`PostRepository.searchIds` 的两处 LIKE 加 `ESCAPE '!'`：

```java
@Query("""
        SELECT p FROM Post p
        WHERE p.deleted = false
          AND (:boardId IS NULL OR p.board.id = :boardId)
          AND (:keyword = '' OR LOWER(p.title) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '!'
                             OR LOWER(p.content) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '!')
        """)
```

Hibernate 6 支持 HQL 的 `LIKE ... ESCAPE '!'`（`searchByKeyword` 已在生产验证过），无需改 SQL 方言。

### 7.3 测试：`PostFlowIntegrationTest` 的 stage5

在 `@Order(4)`（软删）之后追加 `@Order(5) void stage5_keywordWildcardsAreLiteral()`，用已存在的 `boardId` 与 `adminToken` 建 4 篇帖子：

| 标题 | 用途 |
| --- | --- |
| `1000 items` | 若 `%` 被当通配符，`100%` 会命中它（错） |
| `100% done` | 唯一应被 `100%` 命中的帖子 |
| `a_b note` | 唯一应被 `a_b` 命中的帖子 |
| `axb note` | 若 `_` 被当通配符，`a_b` 会命中它（错） |

断言：`keyword=100%` → 只命中 `100% done`；`keyword=a_b` → 只命中 `a_b note`。

两个实现细节：

- **用 URI 模板变量传关键词**（`?keyword={kw}` + `Map.of("kw", "100%")`），让 `RestTemplate` 负责百分号编码。手工拼 `keyword=100%25` 也可以，但那样测试就在验证「我们手写的编码」，而不是「服务端解出来的关键词」；`stage3`（`:161-166`）已有同样的写法，保持一致。
- **`containsExactly` 是安全的**：此刻库里属于该板块的帖子只有 stage5 这 4 篇（stage2 那篇在 stage4 被软删，stage3/4 的断言已经锁定「删除后列表为空」），`100%` 与 `a_b` 各只有一个匹配。

## 8. R4：前端登出静默与时序

### 8.1 问题链

1. `App.vue:26` 调 `auth.logout()` 不 await，`:29` 立刻 `ElMessage.success('已退出登录')`；
2. store 的 `logout()`（`stores/auth.js:51-59`）真的去 `POST /api/auth/logout`；
3. 若 token 已失效（重复登出、或另一标签页先登出），服务端 401；
4. 拦截器 `api/index.js:57-63` 对 401 一律清态 + 弹「请先登录」+ `location.href` 跳 `/login`；
5. 用户看到「已退出登录」和「请先登录」两条提示，并且整页刷新（SPA 状态、未提交的表单全丢）。

### 8.2 决策：逐请求 `silent` 标志，而不是改全局 401 行为

| 方案 | 问题 |
| --- | --- |
| 全局取消 401 的提示/跳转 | 401 是「会话失效」的唯一兜底。去掉后 token 过期的用户会停留在页面上反复失败，且没有任何引导——影响面远大于登出这一个场景 |
| 在 store 里吞掉错误 | 错误已经在拦截器里弹过提示、也跳转过页面，store 层吞不掉副作用 |
| **`silent: true`（选它）** | 只影响「用户主动登出」这一条请求；标志随 axios config 走（`error.config.silent`），不需要额外的全局状态；默认行为（不带标志）逐字节不变 |

### 8.3 为什么 silent 的 401 仍然清 localStorage

清理是**正确副作用**：本地留着已失效的 token，后续请求会继续带 `Bearer` 并继续 401；清态让界面立刻回到未登录。去掉的只是 UI 噪音（提示 + 跳转）。所以 `silent` 只包住 `ElMessage.error` 与 `location.href`，不包住 `localStorage.removeItem`。

### 8.4 代码

`frontend/src/api/index.js`：

```js
  (error) => {
    const status = error.response?.status
    const message = error.response?.data?.message || error.message || '网络错误'
    const url = error.config?.url || ''
    // 静默请求（用户主动登出）：语义与普通请求一致（401 仍清本地态），只是不弹提示、不跳转。
    // 重复登出时服务端返回 401，若按普通 401 处理，用户会先看到「已退出登录」再看到
    // 「请先登录」并被整页刷回登录页。
    const silent = error.config?.silent === true
    if (status === 401 && !isCredentialEndpoint(url)) {
      localStorage.removeItem('jf_token')
      localStorage.removeItem('jf_user')
      if (!silent) {
        ElMessage.error('请先登录')
        if (!location.pathname.startsWith(LOGIN_PATH)) {
          location.href = LOGIN_PATH
        }
      }
    } else if (!silent) {
      // 凭据接口的 401 也走这里：显示后端原文（如「用户名或密码错误」），不动登录态
      ElMessage.error(message)
    }
    return Promise.reject(error)
  }
```

```js
  logout: () => api.post('/auth/logout', null, { silent: true }),
```

注意第二个参数必须显式写 `null`：`axios.post(url, data, config)` 的三个位置是固定的，把 config 放第二位会被当成请求体。

### 8.5 `App.vue` 的 await

```js
async function handleUserCommand(command) {
  if (command === 'profile') {
    router.push('/profile')
  } else if (command === 'admin') {
    router.push('/admin')
  } else if (command === 'logout') {
    await auth.logout()
    // 角标/列表/分页等会话状态由 useNotificationStream 的 token watch 统一清空，
    // 这里不再各自清一半，避免登出/换号两条路径的重置逻辑漂移。
    ElMessage.success('已退出登录')
    router.push('/')
  }
}
```

`await` 之后提示，语义才成立：store 的 `logout()` 内部 `try/catch/finally` 保证不会抛（`stores/auth.js:51-59`），所以「已退出登录」出现时本地态一定已清干净；后端失败时也只是不提示失败（这是 §4.12 第 11 条保留的取舍，本轮不改）。

### 8.6 测试（+4）

| 文件 | 用例 | 断言重点 |
| --- | --- | --- |
| `api/index.spec.js` | silent 401 清态但不提示、不跳转 | `elMessageError` 未被调用、两键已删、`location.href` 仍是 `/` |
| `api/index.spec.js` | silent 的 429 / 网络错误不弹 toast | 两类错误都不调用 `elMessageError`，且仍 reject |
| `stores/auth.spec.js` | 先调后端、后清本地 | 用 `mockImplementationOnce` 在请求发出的瞬间读 `localStorage`，断言此时 token **仍在**；随后断言已清 |
| `App.spec.js` | 未 resolve 前不提示、resolve 后才 success + push | 用可控 promise + `flushPromises`，断言调用顺序 |

配套的测试文件改动（都在 Task 4 的文件清单内）：

- `api/index.spec.js:227-234` 的既有断言从 `toHaveBeenCalledWith('/auth/logout')` 改为三参形式（否则会红）；
- `App.spec.js:22` 的 `vue-router` mock 改为 hoisted 的共享 `push` spy（否则无法断言跳转）；
- `App.spec.js` 增加 `element-plus` 的 `ElMessage` mock（`App.vue` 只从该模块取 `ElMessage`，其余 `el-*` 走 `global.stubs`，mock 后不影响既有 2 例）；
- `App.spec.js:43` 的 `el-dropdown` stub 换成会 `$emit('command', 'logout')` 的可点击 stub。

## 9. R5：主题跨标签同步

### 9.1 `storage` 事件的语义约束

- 只在**其他**同源文档写 `localStorage` 时触发（本标签页自己写不触发）；
- `key === null` 表示 `clear()`（本仓不用 `clear`，但删除键也会以 `key=STORAGE_KEY` 触发；`clear` 场景仍要正确处理）；
- `event.storageArea` 用来排除 `sessionStorage`（同一事件类型）。

### 9.2 决策：回读 `localStorage`，不信任 `event.newValue`，也不写回

| 决策 | 理由 |
| --- | --- |
| 回读 `readStoredMode()` | `key=null` 时 `newValue` 也是 `null`；坏值要回退 `system`；回读让「删除 → system」「坏值 → system」「正常值 → 该值」共用一条判定路径，且与模块初始化**完全同一段代码**，不会出现「初始化与同步两套规则」 |
| **不**写回 `localStorage` | 写回会再次触发其他标签页的 `storage` 事件：A 标签页收到坏值→纠正→写回→B 又收到→再纠正……形成跨标签页回环；而且写回没有语义收益（来源标签页已经写过它想要的值） |
| 模块级单次绑定 | `useTheme()` 被 `App.vue`、`ThemeToggle`、路由守卫等多处调用，绑在函数里会重复注册监听器。用模块级 `storageListenerBound` 标志 + 顶层调用，保证整个应用一份 |
| `typeof window === 'undefined'` 守卫 | 与 `darkMediaQuery()`（`:15-18`）的既有风格一致，SSR/构建期求值时不炸 |

### 9.3 代码

```js
// 跨标签页同步：其他标签页 setMode() 后会触发本页的 storage 事件。
// 不信任 event.newValue：key 被删除（removeItem/clear）时它是 null，坏值也需要回退 system，
// 统一从 storage 回读一次，与模块初始化走同一段判定。
function onStorageChange(event) {
  if (event.storageArea && event.storageArea !== localStorage) return
  if (event.key !== null && event.key !== STORAGE_KEY) return
  // 故意不写回 localStorage：写回会再次触发其他标签页的 storage 事件，形成回环。
  mode.value = readStoredMode()
}

let storageListenerBound = false

function bindStorageListener() {
  if (storageListenerBound || typeof window === 'undefined') return
  window.addEventListener('storage', onStorageChange)
  storageListenerBound = true
}

bindStorageListener()
```

`bindStorageListener()` 放在模块顶层（`watchEffect` 之后、导出之前），保证「导入即生效」——`main.js` 与 `index.html` 的内联防闪白脚本都依赖主题状态尽早就绪。

### 9.4 测试（+3）

| 用例 | 手法 | 断言 |
| --- | --- | --- |
| 其他标签页切到 dark | `localStorage.setItem(STORAGE_KEY, 'dark')` 后 `window.dispatchEvent(new StorageEvent('storage', { key: STORAGE_KEY, newValue: 'dark', storageArea: localStorage }))` | `mode === 'dark'`、`isDark === true`、`html.dark` 挂上 |
| key 为 null（清空/删除） | 先 `setMode('dark')`，删键后派发 `{ key: null, newValue: null }` | 回到 `system`、`html.dark` 摘掉 |
| 坏值 | 写 `'purple'` 后派发同值事件 | 回退 `system`，且 `localStorage` 里仍是 `'purple'`（证明**没有**写回） |

测试里手动 `dispatchEvent` 是必须的：`storage` 事件按规范不会在写入的文档里触发，jsdom 也不会自动派发。`useTheme.spec.js` 用 `vi.resetModules()` + 动态 import（`:55-59`），每个用例拿到独立模块实例；模块级监听器会随实例累积，但 `onStorageChange` 只读不写，旧实例只改自己的 `mode` ref，对当前用例无可观测影响（这一点在计划 §10.4 记录）。

`event.storageArea !== localStorage` 的 `sessionStorage` 分支不单独用例：它需要构造第二个 Storage 实例，而 jsdom 的 `sessionStorage` 与 `localStorage` 类型相同、语义不同，用例会变成「测 jsdom」；该分支按代码评审保证（一行 early return）。

## 10. 安全 / 并发 / 回滚影响

### 10.1 安全

- R1 是**收紧**：依赖不可用时拒绝建立认证态；不新增放行路径，也不改变正常路径的判断顺序（subject → 查库 → status → 版本）。
- R1 的 fail-closed 不制造「假登出」：不写库、不改版本，库恢复后同一 token 仍有效（与登出语义的区别已写进代码注释）。
- R3 是**输入面收敛**：`%` 不再能匹配任意串，减少「用通配符枚举标题」这种低价值探测面；主收益是语义正确。
- R4 不改变服务端鉴权，只改前端提示；silent 401 仍清本地 token，避免失效 token 被继续使用。
- R5 只影响展示层；`storage` 事件同源，不存在跨站注入面（值仍经 `readStoredMode()` 白名单校验）。
- 无新增依赖、无新增端点、无配置项 → 不扩大攻击面。

### 10.2 并发

- R2 的 `windows.remove(key, value)` 把「判断过期 → 删除」变成「判断过期 → 匹配实例才删」，消除误删并发新建窗口的竞态；窗口内部仍由 `synchronized (window)` 保护，锁顺序不变（先窗口锁、再 map 的结构性操作，`ConcurrentHashMap` 的 `remove` 不需要外部锁）。
- 过滤器不持锁、不加缓存，fail-closed 分支是纯读；DB 故障期间不会因为 401 而放大写压力。

### 10.3 回滚

- 每个 Task 一次提交，可单独 `git revert`；无迁移、无配置、无端点变化 → 回滚后不需要数据修复。
- R1 回滚后回到 500 默认错误体（可观测但可用）；R2 回滚后回到按 key 删除（竞态窗口仍在但极窄）；R3 回滚后 `%`/`_` 恢复通配；R4/R5 是纯前端，回滚只需重新发布前端静态资源（`deploy.sh` 会重跑 `pnpm build`）。
- R5 若在真实浏览器出现标签页抖动（例如某些浏览器的 `storageArea` 不为 `localStorage`），可单独 revert 该提交，不影响其它改动。

## 11. 测试策略与预期计数

### 11.1 后端

| Task | 新增 | 累计 | 说明 |
| --- | --- | --- | --- |
| 基线 | — | 163 / 20 类 | `d56a8aa` |
| Task 1 | +9（`JwtAuthenticationFilterTest` 7 + `AuthInvalidationIntegrationTest` 2） | 172 / 21 类 | 1 个 RED（DB 异常用例），2 个特征化用例直接绿 |
| Task 2 | +3（`LoginRateLimiterTest` 构造器 3 例） | 175 / 21 类 | 3 个 RED |
| Task 3 | +1（`PostFlowIntegrationTest.stage5`） | 176 / 21 类 | 1 个 RED（`%` 断言先失败；`_` 断言在同一用例内随后验证） |
| Task 4/5 | — | 176 / 21 类 | 前端任务不动后端 |
| Task 6 | — | **176 / 21 类**（1 skipped） | 全量 `mvn -B clean test` |

**与任务书的数字差异（必须知道）**：任务书写「预期 177」。按 R1–R5 的用例清单精确相加是 `163 + 7 + 2 + 3 + 1 = 176`。差的 1 例不是漏写——R1 要求 7 例过滤器单测、2 例集成、R2 要求 3 例、R3 要求 1 例，合计 13。本设计以**用例清单**为准，计划写 176；若执行时实测 177，说明某个文件多写了 1 例，应核对清单而不是改预期数字。

### 11.2 前端

| Task | 新增 | 累计 |
| --- | --- | --- |
| 基线 | — | 314 / 31 spec |
| Task 4 | +4（`api/index.spec` 2、`stores/auth.spec` 1、`App.spec` 1） | 318 / 31 spec |
| Task 5 | +3（`useTheme.spec`） | **321 / 31 spec** |
| Task 6 | — | 321 / 31 spec |

### 11.3 不变项

| 项 | 值 | 为什么不变 |
| --- | --- | --- |
| 后端测试类 | 20 → 21 | 只新增 `JwtAuthenticationFilterTest` |
| 前端 spec 文件 | 31 | 只改既有文件 |
| HTTP 端点 | 39 | 无新增/删除 |
| 数据库迁移 | V4（不变） | 无 schema 变更 |
| 配置项 | 不变 | 无新配置 |
| 依赖 | 不变 | 不新增依赖 |

### 11.4 验证命令

```bash
# 后端全量（E:\JavaSpace\forum）
mvn -B clean test                       # Tests run: 176, Failures: 0, Errors: 0, Skipped: 1（20 → 21 类）

# 前端全量
cd frontend && pnpm test                # Test Files 31 passed (31) / Tests 321 passed (321)

# 构建（Git Bash 必须带 MSYS2_ENV_CONV_EXCL，否则 /forum/ 会被转成 Windows 路径）
cd frontend && MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build
```

## 12. 上线与验收

- 无 Dockerfile / compose / nginx / `deploy.sh` 改动；只替换后端镜像与前端 `dist`。
- 推送 `master` → raspi Jenkins `javaforum` 轮询（≤5 分钟）构建 + 部署 → 容器健康后冒烟：

| # | 检查 | 期望 |
| --- | --- | --- |
| 1 | `GET /api/health`（直连 8086 与经 nginx） | 200 |
| 2 | `POST /api/auth/login`（admin） | 200，拿到 token |
| 3 | `GET /api/auth/me`（登录后） | 200 |
| 4 | `POST /api/auth/logout` | 200 |
| 5 | 用同一 token 再 `GET /api/auth/me` | 401（R1/R4 的服务端语义未变） |
| 6 | 用同一 token 再 `POST /api/auth/logout` | 401（前端的静默路径就是为这条服务的，浏览器端表现由 `index.spec.js` 锁定） |
| 7 | `GET /api/posts?keyword=100%25` | 200；每个返回项的标题都含字面量 `100%`（无此类帖子则为 0 条） |
| 8 | `GET /api/posts?keyword=100`（负向对照） | 200；条数 ≥ 第 7 项，证明查询本身仍工作 |
| 9 | 容器与镜像 | `forum-backend Up (healthy)`，镜像 tag 与 Build 号一致 |

- 主题跨标签同步无法用 curl 验证：由 `useTheme.spec.js` 的 3 例锁定；浏览器可选验收（开两个 `/forum/` 标签页切换主题，另一个立即跟随）。
- 上线后回填 `docs/DEVELOPMENT.md` §4.13「上线验收」与 §8 变更日志的哈希，然后第二次推送（`docs: 回填第八轮提交哈希与上线验收`）。

## 13. 风险与已知取舍

### 13.1 本轮引入的风险

| 风险 | 触发条件 | 处置 |
| --- | --- | --- |
| fail-closed 让 DB 抖动表现为「集体被登出」 | 连接池耗尽/数据库重启期间访问受保护接口 | 前端 401 会跳登录页，用户重新登录即可；DB 恢复后旧 token 仍有效。若持续时间长，先用 `/api/health` 与数据源日志定位 DB，不要回滚本轮——回滚只会把 401 换成 500 |
| 首页关键词行为变化 | 有人依赖 `%` 通配（文档从未承诺） | 属修正；若确有依赖，单独 revert Task 3 的提交（一个提交、无迁移） |
| `silent` 掩盖真实登出失败 | 后端 5xx/超时且 silent | 本地态已清、界面回到未登录；服务端 token 仍有效是**既有**取舍（§4.12 第 11 条），本轮不改变，也不因为 silent 而更糟（原本也是 `catch {}` 吞掉） |
| `storage` 监听被重复绑定 | 多次 `import`（测试里 `vi.resetModules`） | 模块级标志保证单应用一份；测试里的多实例监听器只读不写，无可观测影响 |
| 构造器校验让坏配置直接启动失败 | `.env` 里写了 `LOGIN_LOCK_MS=0` | 这是**目的**：与其静默关闭锁定，不如启动即失败并在日志首屏报出配置名 |

### 13.2 保留的已知取舍（不在本轮修）

| 取舍 | 为什么保留 |
| --- | --- |
| 前端登出在后端失败时静默（`stores/auth.js:54-56` 的 `catch {}`） | 要改需要产品决策（提示「服务端会话未能失效，建议修改密码」？自动重试？），且与「登出必须成功」的 UX 冲突；本轮只保证不再出现矛盾提示 |
| `recordSuccess` 清空整 IP 窗口，可被公开注册接口绕过 | 这是第七轮 spec 的既定语义（成功登录清零）；改成按 `(IP, username)` 计数要先定义「多账号共享出口」的行为，属独立设计 |
| `X-Forwarded-For` 兜底取第一个合法字面量；IPv4-mapped 环回未识别；无代理头且非环回时回落共享桶 | 信任边界依赖「后端只绑本机/容器网关」这个部署前提，改校验规则需要新的部署假设 |
| 限流计数在单实例内存 | 单容器部署；多实例需共享存储（§5.2 候选） |
| SSE 长连接不因 token 失效立即断开（≤30 分钟兜底） | 需要「按用户主动 complete 全部连接」的新机制与重连策略，收益低于成本 |
| 清理竞态没有确定性用例 | 需要在「读窗口」与「删窗口」之间插桩；加 seam 只为测试会污染产品代码，改用 `remove(k,v)` 的语义保证 + 评审 |
| IPv6 客户端 /64 轮换可换计数桶 | 需要把 IPv6 归一化到 /64 前缀，属新的地址解析规则；当前部署无 IPv6 外部入口 |
| `posts.content` 无长度上限（T15） | 产品规则，见 §2.2 |

### 13.3 刻意不做（再次强调）

chunk 分包的完整理由见 §2.2 第一行：**不是「没时间」，而是它的前置改造（Element Plus 按需引入）会波及 31 个 spec 的 `el-*` 解析方式**，与本轮「不动测试脚手架」的基调冲突。留独立一轮，按「构建链改造 → 全量测试复核 → 首屏体积对比」推进。

## 14. 交付物与计划衔接

- 代码：`JwtAuthenticationFilter`（fail-closed + `@Slf4j`）、`LoginRateLimiter`（校验 + CAS 清理）、`GlobalExceptionHandler`（429 日志）、`PostService.list` + `PostRepository.searchIds`（转义）、`frontend/src/api/index.js`（silent）、`frontend/src/App.vue`（await）、`frontend/src/composables/useTheme.js`（storage 同步）。
- 测试：`JwtAuthenticationFilterTest`（新，7 例）、`AuthInvalidationIntegrationTest`（+2）、`LoginRateLimiterTest`（+3）、`PostFlowIntegrationTest`（+1）、`api/index.spec.js`（+2）、`stores/auth.spec.js`（+1）、`App.spec.js`（+1）、`useTheme.spec.js`（+3）。
- 文档：README（测试基线）、`docs/DEVELOPMENT.md`（§3.1/§3.2/§3.4/§4.12/§4.13/§5.1/§5.2/§8）、`docs/deployment/README.md`（§7 已知限制）、本 spec 与实现计划。
- 执行：按 `docs/superpowers/plans/2026-10-24-round8-quality-hardening.md` 的 Task 1–6 逐任务「写测试 → 跑红 → 实现 → 跑绿 → 提交」，上线后回填哈希与验收记录。
