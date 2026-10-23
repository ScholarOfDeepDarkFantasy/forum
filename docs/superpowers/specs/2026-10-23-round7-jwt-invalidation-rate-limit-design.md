# 第七轮设计：安全加固（JWT 主动失效 + 登录失败限流）

- 日期：2026-10-23
- 状态：设计已确认，待实现
- 范围：让「封禁 / 登出」即时生效（T4）；给登录接口增加按 IP 的失败锁定（T5）

## 1. 背景与目标

现状（审计自 `docs/DEVELOPMENT.md` §5.1）：

- **T4**：JWT 无状态认证，`JwtAuthenticationFilter` 只校验签名与过期时间，权限完全来自 claims。管理员封禁用户后，该用户已签发的 token 在 24 小时内仍可用；也没有登出接口能主动让 token 失效。
- **T5**：`POST /api/auth/login` 没有任何失败计数，可被无限次尝试爆破，仅靠 BCrypt 的慢哈希拖延。

目标（用户可见）：

1. 管理员封禁某用户后，其所有已签发 token 立即失效（下一次受保护请求 401）。
2. 用户主动登出后，当前 token 立即失效；再次使用该 token 返回 401。
3. 同一 IP 连续输错密码达到阈值后，登录接口在锁定期内返回 429 并带 `Retry-After`，即使密码正确也先拒绝；锁定到期后可正常登录，成功登录清零失败计数。
4. 公开浏览（游客、无 token 的 GET）行为不变。

## 2. 范围

### 2.1 本轮做

| 项 | 说明 |
| --- | --- |
| 用户 token 版本 | `users.token_version`（V4 迁移）+ `User` 实体字段 |
| 签发携带版本 | `JwtTokenProvider.createToken(...)` 增加 `ver` claim |
| 请求态校验 | `JwtAuthenticationFilter` 查用户状态投影：不存在 / BANNED / 版本不符 → 不建立认证态 |
| 主动登出 | `POST /api/auth/logout`：`token_version + 1` |
| 封禁失效 | `AdminUserService.changeStatus` 置 BANNED 时 `token_version + 1` |
| 登录限流 | `LoginRateLimiter`：按客户端 IP 的失败窗口 + 锁定；429 + `Retry-After` |
| 客户端 IP 解析 | `ClientIpResolver`：loopback 场景信任 nginx 的 `X-Real-IP` |
| 前端 | `authApi.logout()`；store 登出先调接口再清态；登录页展示 429 文案 |
| 测试与文档 | 后端单测/集成测试、前端单测、README/DEVELOPMENT/部署手册更新 |

### 2.2 本轮不做

- 不做 refresh token、不引入 Redis / 分布式会话；限流与状态校验都是单实例内存/本地库。
- 不做多实例共享限流（当前单机部署；将来多实例需共享存储）。
- 不做「登出全部设备」的独立管理接口（登出即让该用户全部旧 token 失效，语义已覆盖）。
- 不做修改密码功能（`AuthService` 现无此能力）。
- 不改变角色来源：角色仍取 token 内 claims，角色变更仍需重新登录才生效（见 §12）。
- 不改 nginx 配置（`X-Real-IP` 现有配置已满足信任边界）。

## 3. 现状与约束

- 认证：`SecurityConfig` 无状态；`JwtAuthenticationFilter` 校验签名后直接用 claims 构造 `ForumUserDetails`。
- 登录：`AuthService.login` 查 `users` → `passwordEncoder.matches` → `checkStatus`（BANNED 拒绝登录，MUTED 放行）→ 签发 token。
- 封禁：`AdminUserService.changeStatus` 支持 ACTIVE / MUTED / BANNED，禁止操作管理员。
- 部署：后端容器只监听 `127.0.0.1:8086`；nginx `/forum/api/` 反代，并设置 `proxy_set_header X-Real-IP $remote_addr` 与 `X-Forwarded-For $proxy_add_x_forwarded_for`；因此后端看到的 `remoteAddr` 恒为 127.0.0.1。
- 前端：`stores/auth.js` 已有 `logout()`（清本地 token），顶栏下拉入口已接；axios 拦截器对 401 清态跳登录，429 未处理。
- 基线：后端 16 个测试类 / 133 用例；前端 30 个文件 / 309 用例；公开+受保护接口共 38 个。

## 4. 方案总览

```
签发：AuthService.buildAuthResponse
        → JwtTokenProvider.createToken(id, username, roles, user.tokenVersion)
        → JWT claims: sub / username / roles / ver / iat / exp

校验：JwtAuthenticationFilter
        validate 签名与过期
          → 读 claims.sub、claims.ver（缺省 0）
          → userRepository.findTokenStateById(id)   ← 只查 id/status/token_version
              ├─ 无记录 / status=BANNED / tokenVersion != ver → 不建立认证态（→ 401）
              └─ 通过 → 用 claims 的 username/roles 建立认证态

失效：POST /api/auth/logout → token_version + 1（当前用户全部旧 token 失效）
      AdminUserService.changeStatus(BANNED) → token_version + 1（封禁即时生效）

登录：POST /api/auth/login
        → ClientIpResolver 解析客户端 IP（loopback 时信任 X-Real-IP）
        → LoginRateLimiter.check(ip)  —— 锁定中 → 429 + Retry-After
        → AuthService.login
             ├─ BadCredentialsException → recordFailure(ip) 后原样抛出（401）
             └─ 成功 → recordSuccess(ip)
```

## 5. 数据模型与迁移

新增迁移 `src/main/resources/db/migration/V4__add_user_token_version.sql`：

```sql
-- T4：用户 token 版本号。登出 / 封禁时 +1，旧 token 的 ver 声明随之失配。
ALTER TABLE users ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;
```

- `User` 实体新增：

```java
/** token 版本号：登出或封禁时 +1，使旧 token（claims.ver）失配 */
@Column(name = "token_version", nullable = false)
@Builder.Default
private long tokenVersion = 0L;
```

- 迁移是纯增量列，Flyway 在应用启动时自动应用；无需数据回填（默认 0）。
- 旧 token 没有 `ver` claim，按 0 处理：上线不会导致所有会话掉线，只有 `token_version` 被递增过（封禁/登出过）的用户的旧 token 失效。

## 6. 后端实现

### 6.1 签发

`JwtTokenProvider.createToken(Long userId, String username, List<String> roles, long tokenVersion)`：在原 claims 上增加 `.claim("ver", tokenVersion)`。`AuthService.buildAuthResponse` 传入 `user.getTokenVersion()`。

### 6.2 过滤器校验

`JwtAuthenticationFilter` 注入 `UserRepository`，新增封闭投影：

```java
public interface UserTokenState {
    Long getId();
    UserStatus getStatus();
    Long getTokenVersion();
}

@Query("select u.id as id, u.status as status, u.tokenVersion as tokenVersion "
        + "from User u where u.id = :id")
Optional<UserTokenState> findTokenStateById(@Param("id") Long id);
```

过滤逻辑（仅当 Authorization 头合法且 claims 校验通过时触发一次查询）：

1. `ver = claims.get("ver")`，为空按 `0L`；
2. `state = repository.findTokenStateById(userId)`；
3. `state` 不存在、`state.status == BANNED`、`state.tokenVersion != ver` → **不建立认证态**，随后继续过滤器链（受保护接口由现有 entry point 返回 401「未登录或登录已过期」，公开 GET 匿名可访问）；
4. 通过 → 用 claims 的 username/roles 构造 `ForumUserDetails`（与现状一致）。

说明：只读三列，不加载 roles 集合，避免每请求一次集合查询。

### 6.3 登出

`AuthService`：

```java
@Transactional
public void logout(Long userId) {
    User user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException("用户不存在"));
    user.setTokenVersion(user.getTokenVersion() + 1);
    log.info("用户 {} 已登出，token 版本 {} -> {}", ...);
}
```

`AuthController`：

```java
@PostMapping("/logout")
public Result<Void> logout() {
    authService.logout(SecurityUtils.currentUserId());
    return Result.ok(null, "已退出登录");
}
```

`SecurityConfig`：`/api/auth/logout` 属于 POST，现有规则 `anyRequest().authenticated()` 已覆盖，无需新增规则；但为避免误伤，显式把它与 `/api/auth/me`、`/api/auth/profile` 并列声明为 `authenticated()`。

### 6.4 封禁

`AdminUserService.changeStatus`：`case BANNED ->` 分支在设置状态的同时 `user.setTokenVersion(user.getTokenVersion() + 1)`。重复封禁再 +1 无副作用（幂等语义：旧 token 均失配）。MUTED / ACTIVE 不递增版本号，因此「禁言到期自动恢复」不会把用户踢下线。

### 6.5 HTTP 语义

- 失效 token：不抛 500、不进 Controller；受保护接口 401 `{"success":false,"message":"未登录或登录已过期"}`（沿用 `SecurityConfig` entry point）。
- 登出成功：200 + `Result.ok(null, "已退出登录")`。
- 登录锁定：429 + `Retry-After: <秒>` + `Result.fail("登录失败次数过多，请 X 分钟后再试")`。
- 其余错误语义不变。

## 7. 登录失败限流

### 7.1 `LoginRateLimiter`

- 状态：`ConcurrentHashMap<String, Window>`；`Window { ArrayDeque<Long> failures; long lockedUntil; }`，对 Window 内部同步。
- 方法：
  - `void check(String ip)`：`now < lockedUntil` → 抛 `TooManyLoginAttemptsException(retryAfterSeconds = ceil((lockedUntil - now)/1000))`。
  - `void recordFailure(String ip)`：先修剪 `now - windowMs` 之前的失败；追加当前时间；若失败数 ≥ `maxFailures` → `lockedUntil = now + lockMs`，清空窗口（锁定期满后重新计数）。
  - `void recordSuccess(String ip)`：移除该 IP 记录。
- 清理：`@Scheduled(fixedDelayString = "${app.security.login.cleanup-interval-ms:300000}")` 删除“窗口内无失败且未锁定”的键，避免 Map 无限增长。
- 时钟：构造器注入 `Clock`；新增 `Clock` bean（`Clock.systemUTC()`，放在现有配置类或新建小配置类），单测直接用可控时钟构造 `LoginRateLimiter` 验证窗口与锁定到期，不依赖 `Thread.sleep`。
- 线程安全：单实例、低并发下足够；锁内只做 O(窗口大小) 修剪。

### 7.2 配置

```yaml
app:
  security:
    login:
      max-failures: ${LOGIN_MAX_FAILURES:5}
      window-ms: ${LOGIN_FAIL_WINDOW_MS:900000}     # 15 分钟
      lock-ms: ${LOGIN_LOCK_MS:900000}               # 15 分钟
      cleanup-interval-ms: 300000                    # 5 分钟
```

> 实现裁决：清理间隔默认取 `60000`、环境变量统一为 `LOGIN_*` 命名（`LOGIN_MAX_FAILURES` / `LOGIN_WINDOW_MS` / `LOGIN_LOCK_MS` / `LOGIN_CLEANUP_INTERVAL_MS`），原因见实现计划 `docs/superpowers/plans/2026-10-23-round7-security-hardening.md` §0。


### 7.3 客户端 IP 解析 `ClientIpResolver`

```java
String resolve(HttpServletRequest request)
```

优先级：

1. `request.getRemoteAddr()` 非环回地址 → 直接返回；
2. 环回地址（`127.0.0.0/8`、`::1`、IPv4-mapped loopback）→ 取 `X-Real-IP`（nginx 用 `$remote_addr` 覆盖写入，客户端伪造无效）并校验为 IPv4/IPv6 字面量；
3. `X-Real-IP` 缺失/不合法 → 取 `X-Forwarded-For` 第一个合法字面量（仅兜底；nginx 的 `$proxy_add_x_forwarded_for` 会追加客户端输入，故不作主判定）；
4. 都不可用 → `remoteAddr`。

约束与理由：

- 后端只绑定 `127.0.0.1:8086`，外部无法直接访问，因此“来自 loopback 的请求 = 来自本机 nginx”这一信任假设成立。
- 只做字面量语法校验（IPv4 点分十进制、IPv6 十六进制/冒号），**不用** `InetAddress#getByName`（避免对恶意输入做 DNS 解析）。
- 长度上限 45 字符；空串/超长/非法字符一律忽略。

### 7.4 接线

`AuthController.login(@Valid @RequestBody LoginRequest req, HttpServletRequest request)`：

```java
String ip = clientIpResolver.resolve(request);
loginRateLimiter.check(ip);
try {
    AuthResponse resp = authService.login(req);
    loginRateLimiter.recordSuccess(ip);
    return Result.ok(resp, "登录成功");
} catch (BadCredentialsException e) {
    loginRateLimiter.recordFailure(ip);
    throw e;
}
```

- 只统计凭据失败（`BadCredentialsException`）；参数校验失败、账号封禁（BusinessException）不计入。
- 锁定期内不验密，避免用 429 时间差推算密码正确性。

### 7.5 异常与处理器

新增 `TooManyLoginAttemptsException extends RuntimeException`（字段 `long retryAfterSeconds`）。`GlobalExceptionHandler` 新增：

```java
@ExceptionHandler(TooManyLoginAttemptsException.class)
public ResponseEntity<Result<Void>> handleTooManyAttempts(TooManyLoginAttemptsException e) {
    log.warn("登录失败次数过多: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
            .body(Result.fail(e.getMessage()));
}
```

消息文案由异常构造：`登录失败次数过多，请 %d 分钟后再试`（分钟数 = `ceil(retryAfterSeconds / 60)`，至少 1）。

## 8. 前端改动

- `frontend/src/api/index.js`：新增 `authApi.logout = () => http.post('/auth/logout')`。
- `frontend/src/stores/auth.js`：`logout()` 改为 `try { await authApi.logout() } catch { /* 尽力而为 */ } finally { 清 token、清用户态 }`；保留现有 `clearAuth()` 语义。
- 登录页（`LoginView.vue`）：登录失败时展示后端 `Result.message`；429 与 401 走同一错误展示路径，不做特殊跳转；提交中禁用按钮的现有行为不变。
- 无新增路由/组件；登出后由现有 401 拦截器保证其它标签页/后续请求回登录页。
- 测试：`stores/auth.spec.js` 增登出成功调用与失败容错；`LoginView.spec.js` 若已有错误展示用例则补 429 文案断言。

## 9. 接口契约

| 方法 | 路径 | 鉴权 | 成功 | 失败 |
| --- | --- | --- | --- | --- |
| POST | `/api/auth/logout` | 需登录 | 200 `Result.ok(null,"已退出登录")` | 401 未登录/旧 token |
| POST | `/api/auth/login` | 公开 | 200 `Result.ok(AuthResponse)` | 401 凭据错误；**429 限流（新）** |

429 响应头：`Retry-After: <整数秒>`；响应体：`Result.fail("登录失败次数过多，请 X 分钟后再试")`。

## 10. 测试策略

后端（当前 16 类 / 133 用例，预计 → 18–19 类 / 150±5）：

- `LoginRateLimiterTest`（约 9 个）：窗口内累计、阈值触发锁定、锁定期 `check` 抛异常且秒数正确、到期恢复、成功清零、清理过期键、多 IP 隔离、失败时间窗口滑动、清理不误删锁定中的键。
- `ClientIpResolverTest`（约 5 个）：非环回直用 remoteAddr；环回优先 X-Real-IP；X-Real-IP 非法回退 XFF 首值；两者都非法回退 remoteAddr；超长/非法字符忽略。
- `AuthFlowIntegrationTest` 增补（约 6–8 个）：登出后旧 token 访问 `/api/auth/me` → 401；封禁后旧 token → 401；未登出 token 正常；第 N 次错误密码后 429 + `Retry-After`；锁定期间正确密码仍 429；成功登录清零（先错 4 次再成功再错 1 次不锁）；两个不同 `X-Real-IP` 互不影响（用小阈值属性）。
- `JwtAuthenticationFilter`：不单独建 Mock 单测，走上面的集成路径（Ban/Logout/正常）验证端到端。
- 迁移：`mvn -B clean test` 全量（H2 集成测试 `flyway.enabled=false` + `ddl-auto=create-drop`，V4 由部署环境 Flyway 实际应用验证）。

前端（当前 30 文件 / 309 用例 → 约 30 文件 / 311）：

- `stores/auth.spec.js`：登出调用接口成功后清态；接口失败仍清态；未登录登出不报错。
- `LoginView.spec.js`：429 错误 message 展示。

构建：`VITE_BASE_PATH=/forum/ pnpm build`（Windows 下 `MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH` 前缀）。

## 11. 部署与验收

- 无 Dockerfile / compose / nginx 改动；`deploy.sh` 不改。V4 迁移随容器启动由 Flyway 应用。
- push `master` → Jenkins `javaforum` 轮询（≤5 分钟）自动 Build + Deploy → 容器健康后验收：
  1. `GET /api/health` 200；
  2. 正确登录 200；
  3. 同一 `X-Real-IP` 连续 5 次错误密码 → 第 6 次 429，响应头 `Retry-After` 为正整数；锁定期内用正确密码仍 429；
  4. 换一个 `X-Real-IP` 登录正常（隔离生效）；
  5. 登出后旧 token 调 `/api/auth/me` 401；
  6. 管理员封禁用户后该用户旧 token 调 `/api/auth/me` 401（验收用现有 admin 账号，测试目标账号若不便封禁则用集成测试覆盖，验收记录写明）。
- 文档：README（接口/功能/测试基线）、`docs/DEVELOPMENT.md`（§3.2 基线、§3.4 接口表、§4.12 第七轮记录、§5.1 T4/T5 关闭、§5.2 候选更新、§8 变更日志，哈希上线后回填）、`docs/deployment/README.md`（X-Real-IP 信任与小节说明）。

## 12. 风险与限制

- **限流是单实例内存态**：重启清零；多实例部署时各实例独立计数。当前单机部署可接受，spec 显式记录不扩展。
- **锁定会误伤 NAT 后用户**：同一公网出口的多个用户共享 IP；锁 15 分钟。属可接受的取舍（阈值 5 次、窗口 15 分钟）。
- **角色变更不即时生效**：角色仍来自 claims；本轮只校验状态与版本号。若需要，可在版本号递增入口扩展（如管理员变更角色时 +1），不在本轮范围。
- **每带 token 请求多一次主键查询**：单行投影、索引主键，开销可接受；不做缓存以保持失效即时。
- **X-Real-IP 信任依赖部署形态**：后端只绑 127.0.0.1 是安全前提；若将来直连暴露后端，必须重新评估（文档写明）。
- **旧 token 无 ver**：按 0 处理，上线不强制全量重登；已封禁/已登出的用户仍会因 status/版本号失配被拒。

## 13. 交付物与计划衔接

- 代码：V4 迁移、`User`/`JwtTokenProvider`/`JwtAuthenticationFilter`、`AuthService`/`AuthController`、`AdminUserService`、`LoginRateLimiter`、`ClientIpResolver`、`TooManyLoginAttemptsException` + `GlobalExceptionHandler`、`application.yml`、前端 api/store/登录页。
- 测试：上述后端/前端用例。
- 文档：README、DEVELOPMENT、部署手册、本 spec；实现完成后按既有流程写实现计划（`docs/superpowers/plans/2026-10-23-round7-security-hardening.md`），逐任务执行并在上线后回填哈希与验收记录。
