# 第七轮：安全加固（JWT 主动失效与登录失败限流）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 每个 Task 由子代理执行，步骤按「先写失败测试 → 跑出红色 → 写最小实现 → 跑绿 → 提交」推进；一次只勾一个 `- [ ]`，勾完再动下一步。

**Goal:** 让「封禁」与「登出」对已签发的 JWT 立即生效（不等 24 小时过期），并给登录接口加上按客户端 IP 的失败次数限流（超限返回 HTTP 429 + `Retry-After`），锁定期内即使密码正确也拒绝。

**Architecture:** 给 `users` 表加 `token_version`（JWT 里对应 `ver` claim）。`JwtAuthenticationFilter` 每个请求用一次主键投影查询取回 `(status, token_version)`：用户被封禁、或 `ver` 与库中版本不一致，就不建立认证态，由 `SecurityConfig` 的 entry point 统一返回 401。登出与封禁都把 `token_version + 1`。登录限流放在 `AuthController.login`（凭据校验之前先 `check`，凭据失败后 `recordFailure`，成功 `recordSuccess`），单实例内存 `ConcurrentHashMap`，键是 `ClientIpResolver` 解析出的客户端 IP；超过阈值抛 `TooManyLoginAttemptsException`，由 `GlobalExceptionHandler` 转 429 并写 `Retry-After`。前端登出从「只清本地状态」改为「尽力调用 `/api/auth/logout`，再清本地状态」。

**Tech Stack:** Java 17 / Spring Boot 3.5.6 / Spring Security 6 / Spring Data JPA / PostgreSQL 14+ / Flyway / jjwt 0.13.0 / Vue 3 + Pinia + Vitest。不新增任何依赖。

---

## 0. 执行约定

**分支与提交**

- 在 `master` 上直接提交（沿用前六轮），不建分支、不 amend、不 rebase。
- 每个 Task 结束提交一次，共 6 次提交（Task 6 的回填提交是第 6 次）。提交信息逐字如下：

```text
feat: 为用户 token 增加版本号并写入 JWT
feat: 让封禁与登出即时失效旧 token
feat: 登录失败按 IP 限流并返回 429
feat: 前端登出调用后端失效接口
docs: 记录第七轮安全加固（JWT 主动失效与登录限流）
docs: 回填第七轮提交哈希与上线验收
```

- 本计划文件 `docs/superpowers/plans/2026-10-23-round7-security-hardening.md` 在 Task 1 的提交里一并入库（它是执行的输入，不单独占一次提交）。
- 每次提交前都要 `git status --short` 确认没有把 `target/`、`frontend/dist/`、`frontend/node_modules/` 带进来（均已 gitignore）。

**TDD 顺序（每个功能步骤都按这个来）**

1. 先写测试（或改已有测试的断言），命令 + 预期红色输出；
2. 写最小实现，命令 + 预期绿色输出；
3. 该 Task 收尾时跑一次全量回归（后端 `mvn -B test`，前端 `pnpm test`），再提交。

**命令环境**

- 所有后端命令在 `E:\JavaSpace\forum` 下执行，Git Bash 里写作 `/e/JavaSpace/forum`。
- 前端命令先 `cd frontend`；构建必须带 `MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH`，否则 Git Bash 会把 `/forum/` 转成 Windows 路径。
- 测试数据库：后端每个集成测试类用自己的 H2 内存库名 + `MODE=PostgreSQL` + `flyway.enabled=false` + `ddl-auto=create-drop`，彼此不共享数据；单元测试不启动 Spring 上下文。

**测试与接口基线（执行前）**

| 项 | 当前值 | 本轮目标值 |
| --- | --- | --- |
| 后端测试 | 16 个测试类 / 133 个用例（1 个 POSIX 用例在 Windows 跳过） | 20 个测试类 / 161 个用例（1 skipped） |
| 前端测试 | 30 个 spec / 309 个用例 | 31 个 spec / 314 个用例 |
| HTTP 端点 | 38 个 | 39 个（新增 `POST /api/auth/logout`） |

**spec 与本计划的裁决点（先写在这里，执行时不再临时决定）**

1. **限流清理方法的签名**：spec §7.1 写 `@Scheduled` 的 `cleanupExpired()` 同时返回清理条数。Spring 的 `@Scheduled` 只允许 `void` 方法，否则启动即报 `Only void-returning methods may be annotated with @Scheduled`。因此拆成两个方法：`@Scheduled void cleanupScheduled()` 只负责定时调用，`int cleanupExpired()` 承载逻辑供单测断言返回值（同包可见）。行为与 spec 一致。
2. **清理间隔默认值**：spec §7.2 写 `cleanup-interval-ms` 默认 `300000`，任务书要求 `60000`，环境变量名要求 `LOGIN_CLEANUP_INTERVAL_MS`（spec 示例写作 `LOGIN_FAIL_WINDOW_MS`）。本计划统一取任务书：`60000` + `LOGIN_*` 四个变量名，并在 README/部署手册里按这四个名字记录。
3. **旧 token（没有 `ver`）**：spec §5 明确「无 `ver` 按 0 处理，上线不强制全员重登」。因此**版本 0 的用户**用第七轮之前的 token 仍可访问；一旦该用户登出或被封禁（版本变 1），旧 token 立即 401。Task 2 的集成测试第 6 例同时锁定这两半。
4. **限流只统计凭据失败**：用户名不存在、密码错误都算失败（两者在 `AuthService.login` 里都是 `BadCredentialsException`）；参数校验失败（400）、账号被封禁（400 `BusinessException`）不计入，避免把「已封禁账号反复登录」变成对同一 IP 的额外惩罚。
5. **`X-Real-IP` 的信任模型**：只有请求来自环回地址（`127.0.0.0/8`、`::1`）时才读代理头。生产环境 nginx 的 `/forum/api/` 与 SSE location 都用 `proxy_set_header X-Real-IP $remote_addr;` **覆盖**写入，客户端伪造的同名头不生效；`X-Forwarded-For` 只在没有合法 `X-Real-IP` 时兜底，取第一个合法字面量。全程只做正则形状校验，不调用 `InetAddress#getByName`（避免 DNS 解析阻塞与 SSRF 面）。

> **2026-10-24 实施偏差（上线后修订）**：本条的「仅环回」信任模型在部署冒烟后放宽为「环回 + RFC1918 私网」——rootless podman 的端口映射使后端 `remoteAddr` 为容器网关 `10.89.3.15`，仅信环回会导致全站共用一个计数桶。修复见提交 `419be00` 与 `docs/DEVELOPMENT.md` §4.12「修复记录」；本节及后文相关表述（Task 3、Task 6、附录 B.5）以文末 **§12 实施偏差** 为准。

6. **投影返回类型**：`UserTokenState.getTokenVersion()` 用 primitive `long`（列 `NOT NULL DEFAULT 0`），与 spec §6.2 的 `Long` 语义等价，同时避免投影代理的拆箱歧义；比较时与 `JwtTokenProvider.tokenVersion(claims)` 同为 `long`。

---

## 1. 现状与差距

| 现状 | 差距 | 本轮落点 |
| --- | --- | --- |
| JWT 无状态，`JwtAuthenticationFilter` 只校验签名与过期时间 | 封禁用户后，其已签发 token 在 `jwt.expiration-ms`（24h）内仍可用；登出只清前端 `localStorage` | Task 1 加 `users.token_version` + `ver` claim；Task 2 过滤器每请求回查状态与版本，登出/封禁递增版本 |
| `AuthService.login` 失败直接抛 `BadCredentialsException` → 401 | 可无限次尝试，暴力破解无成本 | Task 3 按客户端 IP 计失败次数，达阈值后 429 + `Retry-After`，锁定期内不校验密码 |
| 后端没有「当前请求来自哪个客户端」的概念 | `request.getRemoteAddr()` 在 nginx 反代下永远是 `127.0.0.1`，直接拿它做限流会误伤所有人 | Task 3 新增 `ClientIpResolver`：非环回直用，环回读 nginx 覆盖写入的 `X-Real-IP` |
| 前端 `auth.logout()` 是同步的，只清 `jf_token`/`jf_user` | 服务端不知道用户已登出，token 继续有效 | Task 4 `authApi.logout()` + store 的 `async logout()`（尽力调用、`finally` 清态） |
| 端点 38 个，`docs/DEVELOPMENT.md` §3.4 与 §2.2/§3.2/§5/§7/§8 均需同步 | 文档滞后于代码会让下一轮误判基线 | Task 5 一次性更新 README、开发记录、部署手册 |
| `deploy/nginx/forum.conf` 已写入 `X-Real-IP $remote_addr` | 后端不用它，等于白写 | Task 3 消费该头；Task 5 在部署手册写明「为什么不能改成透传客户端值」 |

## 1.1 文件结构（本轮全部改动）

下面这张表是全局视图，只列路径与归属 Task；每个 Task 自己的 **Files:** 小节才是执行时的权威清单（含 Create / Modify / Test 标注与精确动作）。所有路径相对仓库根 `E:\JavaSpace\forum`。

| 动作 | 路径 | 归属 |
| --- | --- | --- |
| 新增 | `src/main/resources/db/migration/V4__add_user_token_version.sql` | Task 1 |
| 修改 | `src/main/java/org/song/forum/entity/User.java` | Task 1 |
| 修改 | `src/main/java/org/song/forum/security/JwtTokenProvider.java` | Task 1 |
| 修改 | `src/main/java/org/song/forum/service/AuthService.java` | Task 1、Task 2、Task 3 |
| 修改 | `src/test/java/org/song/forum/security/JwtTokenProviderTest.java` | Task 1 |
| 修改 | `src/test/java/org/song/forum/repository/RepositoryTests.java` | Task 1 |
| 修改 | `src/main/java/org/song/forum/repository/UserRepository.java` | Task 2 |
| 修改 | `src/main/java/org/song/forum/security/JwtAuthenticationFilter.java` | Task 2 |
| 修改 | `src/main/java/org/song/forum/controller/AuthController.java` | Task 2、Task 3 |
| 修改 | `src/main/java/org/song/forum/config/SecurityConfig.java` | Task 2 |
| 修改 | `src/main/java/org/song/forum/service/AdminUserService.java` | Task 2 |
| 新增 | `src/test/java/org/song/forum/controller/AuthInvalidationIntegrationTest.java` | Task 2 |
| 新增 | `src/main/java/org/song/forum/security/ClientIpResolver.java` | Task 3 |
| 新增 | `src/main/java/org/song/forum/security/LoginRateLimiter.java` | Task 3 |
| 新增 | `src/main/java/org/song/forum/exception/TooManyLoginAttemptsException.java` | Task 3 |
| 新增 | `src/main/java/org/song/forum/config/ClockConfig.java` | Task 3 |
| 修改 | `src/main/java/org/song/forum/exception/GlobalExceptionHandler.java` | Task 3 |
| 修改 | `src/main/resources/application.yml` | Task 3 |
| 新增 | `src/test/java/org/song/forum/security/ClientIpResolverTest.java` | Task 3 |
| 新增 | `src/test/java/org/song/forum/security/LoginRateLimiterTest.java` | Task 3 |
| 新增 | `src/test/java/org/song/forum/controller/LoginRateLimitIntegrationTest.java` | Task 3 |
| 修改 | `frontend/src/api/index.js` | Task 4 |
| 修改 | `frontend/src/stores/auth.js` | Task 4 |
| 修改 | `frontend/src/api/index.spec.js` | Task 4 |
| 新增 | `frontend/src/stores/auth.spec.js` | Task 4 |
| 修改 | `README.md` | Task 5 |
| 修改 | `docs/DEVELOPMENT.md` | Task 5、Task 6 |
| 修改 | `docs/deployment/README.md` | Task 5 |
| 修改 | `docs/superpowers/plans/2026-10-23-round7-security-hardening.md`（本文件） | 全部 |
| 不改动 | `deploy/nginx/forum.conf`（已有 `X-Real-IP`）、`frontend/src/App.vue`（已 fire-and-forget 调用 `auth.logout()`）、`frontend/src/views/LoginView.vue`（429 消息由拦截器统一展示） | — |

约定：

1. `V4` 只是加一列并写列注释，PostgreSQL 上对已有行是 `DEFAULT 0` 的常量默认值，不重写表；H2 测试关闭 Flyway，由 `ddl-auto=create-drop` 直接按实体建表，因此「迁移与实体必须同时改」是本轮最容易漏的一步（Task 1 的两个 Step 刻意相邻）。
2. `AuthService` 被三个 Task 改到，但每次只动一个方法：Task 1 改 `buildAuthResponse` 的 `createToken` 调用，Task 2 新增 `logout`，Task 3 不动 `AuthService`（限流在 Controller 层），所以三次改动不会互相踩。
3. `AuthController` 被 Task 2 与 Task 3 各改一次：前者只加 `/logout` 端点，后者只改 `login` 的签名与异常处理。Task 3 的代码块基于 Task 2 完成后的文件，因此顺序不能交换。

## Task 1：数据模型与签发版本

**Files:**

- Create: `src/main/resources/db/migration/V4__add_user_token_version.sql`
- Modify: `src/main/java/org/song/forum/entity/User.java`
- Modify: `src/main/java/org/song/forum/security/JwtTokenProvider.java`
- Modify: `src/main/java/org/song/forum/service/AuthService.java`
- Modify: `src/test/java/org/song/forum/security/JwtTokenProviderTest.java`
- Modify: `src/test/java/org/song/forum/repository/RepositoryTests.java`
- Add to commit: `docs/superpowers/plans/2026-10-23-round7-security-hardening.md`

- [x] **Step 1-1：先写失败测试（RED）**

把 `src/test/java/org/song/forum/security/JwtTokenProviderTest.java` 整个替换为下面内容。改动点：`createToken` 多传版本号；新增「`ver` 能往返」与「没有 `ver` 的旧 token 读作 0」两个用例。

```java
package org.song.forum.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtTokenProvider 单元测试：签发、解析、版本号 claim、旧 token 兼容。
 * 不启动 Spring 上下文，构造器直接注入测试密钥与 60s 有效期。
 */
class JwtTokenProviderTest {

    private static final String SECRET = "unit-test-secret-key-must-be-at-least-256-bit-long!!";

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(SECRET, 60_000);
    }

    @Test
    void createAndParse() {
        String token = provider.createToken(1L, "alice", List.of("USER", "ADMIN"), 0L);
        Claims claims = provider.parseToken(token);
        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(claims.get("username", String.class)).isEqualTo("alice");
    }

    @Test
    void versionClaimSurvivesRoundTrip() {
        String token = provider.createToken(7L, "bob", List.of("USER"), 3L);
        Claims claims = provider.parseToken(token);
        assertThat(JwtTokenProvider.tokenVersion(claims)).isEqualTo(3L);
    }

    @Test
    void legacyTokenWithoutVersionReadsAsZero() {
        // 第七轮之前签发的 token 没有 ver claim；按 spec §5 视为版本 0，不能让老用户集体掉线
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String legacy = Jwts.builder()
                .subject("9")
                .claim("username", "carol")
                .claim("roles", List.of("USER"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key)
                .compact();

        assertThat(JwtTokenProvider.tokenVersion(provider.parseToken(legacy))).isZero();
    }

    @Test
    void invalidTokenReturnsNull() {
        assertThat(provider.validate("not-a-jwt")).isNull();
        assertThat(provider.validate("")).isNull();
    }
}
```

再在 `src/test/java/org/song/forum/repository/RepositoryTests.java` 末尾（最后一个 `}` 之前）追加一个用例，验证 JPA 默认值：

```java
    @Test
    void userTokenVersionDefaultsToZero() {
        User saved = user("version_zero");
        em.flush();
        em.clear();

        User reloaded = userRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getTokenVersion()).isZero();
    }
```

运行：

```bash
cd /e/JavaSpace/forum && mvn -B test -Dtest=JwtTokenProviderTest
```

预期：`BUILD FAILURE`，编译错误像这样（方法签名还没改，测试先红）：

```text
[ERROR] COMPILATION ERROR :
[ERROR] /E:/JavaSpace/forum/src/test/java/org/song/forum/security/JwtTokenProviderTest.java: method createToken in class JwtTokenProvider cannot be applied to given types;
```

```bash
cd /e/JavaSpace/forum && mvn -B test -Dtest=RepositoryTests#userTokenVersionDefaultsToZero
```

预期：同样是 `COMPILATION ERROR`（`User` 还没有 `getTokenVersion()`）。

- [x] **Step 1-2：加数据库迁移**

新建 `src/main/resources/db/migration/V4__add_user_token_version.sql`。H2 测试库关闭了 Flyway（V3 的 `pg_trgm` 在 H2 上不可用），因此这条脚本只在 PostgreSQL 上跑；`COMMENT ON` 是 PostgreSQL 语法，写在这里是安全的。

```sql
-- =====================================================================
-- JavaForum V4 : 用户 token 版本号（用于 JWT 主动失效）
-- 约定：登出 / 封禁时把 users.token_version + 1；
--       JWT 的 ver claim 与库中值不一致即视为失效 token。
-- 兼容：本迁移之前的 token 没有 ver claim，按 0 处理（不强制全员重登）。
-- =====================================================================

-- 已有用户回填为 0：与「无 ver 的旧 token 按 0」保持一致
ALTER TABLE users ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN users.token_version IS 'JWT 版本号：登出或封禁时 +1，旧 token 的 ver 不等于该值即失效';
```

- [x] **Step 1-3：实体增加 `tokenVersion`**

修改 `src/main/java/org/song/forum/entity/User.java`，在 `mutedUntil` 之后、`roles` 之前插入字段（字段顺序与 V1 的列顺序无关，但保持可读）：

```java
    /** JWT 版本号：登出/封禁时 +1，旧 token 立即失效。 */
    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private long tokenVersion = 0L;
```

改完后 `User` 的字段区应为：

```java
    private String username;
    private String password;
    private String email;
    private String nickname;
    private String avatarUrl;
    private String bio;
    private UserStatus status;
    private LocalDateTime mutedUntil;

    /** JWT 版本号：登出/封禁时 +1，旧 token 立即失效。 */
    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private long tokenVersion = 0L;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    @Builder.Default
    private Set<Role> roles = new HashSet<>();
```

先跑仓储测试确认字段生效：

```bash
cd /e/JavaSpace/forum && mvn -B test -Dtest=RepositoryTests#userTokenVersionDefaultsToZero
```

预期：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`、`BUILD SUCCESS`。

- [x] **Step 1-4：签发 token 时写入 `ver`，并加读取工具**

修改 `src/main/java/org/song/forum/security/JwtTokenProvider.java`：

```java
    /** 签发 Token：subject=用户ID，claims 携带用户名、角色与 token 版本号 */
    public String createToken(Long userId, String username, List<String> roles, long tokenVersion) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("roles", roles)
                .claim("ver", tokenVersion)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }
```

在 `getExpirationMs()` 之后新增（jjwt 对小整数返回 `Integer`，必须走 `Number`，不能强转 `Long`）：

```java
    /**
     * 读取 token 里的版本号。
     * <p>第七轮之前签发的 token 没有 {@code ver} claim，按 0 处理；jjwt 反序列化小整数给的是
     * {@link Integer}，因此统一按 {@link Number} 取 long。</p>
     */
    public static long tokenVersion(Claims claims) {
        Object raw = claims.get("ver");
        return raw instanceof Number number ? number.longValue() : 0L;
    }
```

修改 `src/main/java/org/song/forum/service/AuthService.java` 的 `buildAuthResponse`（第 121–123 行）：

```java
    private AuthResponse buildAuthResponse(User user) {
        List<String> roles = user.getRoles().stream().map(r -> r.getName()).toList();
        String token = jwtTokenProvider.createToken(
                user.getId(), user.getUsername(), roles, user.getTokenVersion());
```

运行单元测试：

```bash
cd /e/JavaSpace/forum && mvn -B test -Dtest=JwtTokenProviderTest
```

预期：`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`、`BUILD SUCCESS`。

- [x] **Step 1-5：Task 1 全量回归并提交**

```bash
cd /e/JavaSpace/forum && mvn -B clean test
```

预期：`BUILD SUCCESS`；`Tests run: 136, Failures: 0, Errors: 0, Skipped: 1`（133 + 2 个 JwtTokenProviderTest 新用例 + 1 个 RepositoryTests 新用例）。若数字不符，先确认测试类名以 `Test` 结尾（surefire 只拾取这种命名），并确认没有测试因为 `token_version` 列而在启动时 `ddl-auto=validate` 失败（测试是 `create-drop`，不会失败；真实库要等 Task 6 的 Flyway 执行）。

```bash
cd /e/JavaSpace/forum && git status --short
```

预期：只出现下面这些路径（没有 `target/`）：

```text
 M src/main/java/org/song/forum/entity/User.java
 M src/main/java/org/song/forum/security/JwtTokenProvider.java
 M src/main/java/org/song/forum/service/AuthService.java
 M src/test/java/org/song/forum/repository/RepositoryTests.java
 M src/test/java/org/song/forum/security/JwtTokenProviderTest.java
?? docs/superpowers/plans/2026-10-23-round7-security-hardening.md
?? src/main/resources/db/migration/V4__add_user_token_version.sql
```

提交：

```bash
cd /e/JavaSpace/forum
git add src/main/resources/db/migration/V4__add_user_token_version.sql \
        src/main/java/org/song/forum/entity/User.java \
        src/main/java/org/song/forum/security/JwtTokenProvider.java \
        src/main/java/org/song/forum/service/AuthService.java \
        src/test/java/org/song/forum/repository/RepositoryTests.java \
        src/test/java/org/song/forum/security/JwtTokenProviderTest.java \
        docs/superpowers/plans/2026-10-23-round7-security-hardening.md
git commit -m "feat: 为用户 token 增加版本号并写入 JWT"
```

预期：`7 files changed, ... insertions(...)`；`git status --short` 为空。

## Task 2：请求态校验、登出与封禁

**Files:**

- Modify: `src/main/java/org/song/forum/repository/UserRepository.java`
- Modify: `src/main/java/org/song/forum/security/JwtAuthenticationFilter.java`
- Modify: `src/main/java/org/song/forum/service/AuthService.java`
- Modify: `src/main/java/org/song/forum/controller/AuthController.java`
- Modify: `src/main/java/org/song/forum/config/SecurityConfig.java`
- Modify: `src/main/java/org/song/forum/service/AdminUserService.java`
- Create: `src/test/java/org/song/forum/controller/AuthInvalidationIntegrationTest.java`

- [x] **Step 2-1：先写失败测试（RED）**

新建 `src/test/java/org/song/forum/controller/AuthInvalidationIntegrationTest.java`。这个类专门验证「token 签发之后，服务端状态一变，旧 token 立刻不可用」。

```java
package org.song.forum.controller;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.song.forum.dto.auth.AuthResponse;
import org.song.forum.dto.auth.LoginRequest;
import org.song.forum.dto.auth.RegisterRequest;
import org.song.forum.dto.user.UserStatusRequest;
import org.song.forum.util.Result;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第七轮安全加固集成测试：登出 / 封禁 / 版本号变化 / 旧 token 兼容，四条路径共用同一个服务端状态源。
 * 6 个用例互相独立（各自注册新用户），不依赖执行顺序。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:auth-invalidation-test;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AuthInvalidationIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${jwt.secret}")
    private String jwtSecret;

    // ---------- 用例 ----------

    @Test
    void validTokenStillWorks() {
        AuthResponse auth = register("inval_valid");

        ResponseEntity<String> resp = me(auth.getToken());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("inval_valid");
    }

    @Test
    void logoutInvalidatesTokenImmediately() {
        AuthResponse auth = register("inval_logout");

        ResponseEntity<String> logoutResp = rest.exchange(url("/api/auth/logout"), HttpMethod.POST,
                new HttpEntity<>(bearer(auth.getToken())), String.class);

        assertThat(logoutResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(logoutResp.getBody()).contains("已退出登录");

        ResponseEntity<String> after = me(auth.getToken());
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(after.getBody()).contains("未登录或登录已过期");
    }

    @Test
    void bannedUserTokenRejectedImmediately() {
        AuthResponse victim = register("inval_banned");
        AuthResponse admin = login("admin", "admin123456");

        changeStatus(admin.getToken(), victim.getId(), "BANNED", null);

        ResponseEntity<String> resp = me(victim.getToken());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("未登录或登录已过期");
    }

    @Test
    void mutedUserTokenStillWorks() {
        AuthResponse user = register("inval_muted");
        AuthResponse admin = login("admin", "admin123456");

        changeStatus(admin.getToken(), user.getId(), "MUTED", 30);

        assertThat(me(user.getToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void bumpedTokenVersionRejectsOldToken() {
        AuthResponse auth = register("inval_bumped");

        // 直接改库模拟「另一台实例/另一个入口把版本 +1」，验证过滤器比对的是数据库而不是内存
        jdbcTemplate.update("update users set token_version = token_version + 1 where id = ?", auth.getId());

        assertThat(me(auth.getToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void legacyTokenWithoutVersionWorksUntilUserLogsOut() {
        AuthResponse auth = register("inval_legacy");
        String legacy = legacyTokenFor(auth.getId(), "inval_legacy");

        // 没有 ver 的旧 token 按 0 处理：版本为 0 的用户上线后不会被强制重登
        assertThat(me(legacy).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> logoutResp = rest.exchange(url("/api/auth/logout"), HttpMethod.POST,
                new HttpEntity<>(bearer(auth.getToken())), String.class);
        assertThat(logoutResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(me(legacy).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---------- 辅助方法 ----------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private RegisterRequest registerReq(String username) {
        RegisterRequest req = new RegisterRequest();
        req.setUsername(username);
        req.setPassword("password123");
        req.setEmail(username + "@test.com");
        return req;
    }

    private AuthResponse register(String username) {
        ResponseEntity<Result<AuthResponse>> resp = rest.exchange(url("/api/auth/register"),
                HttpMethod.POST, new HttpEntity<>(registerReq(username)),
                new ParameterizedTypeReference<>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).isNotNull();
        return resp.getBody().getData();
    }

    private AuthResponse login(String username, String password) {
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword(password);
        ResponseEntity<Result<AuthResponse>> resp = rest.exchange(url("/api/auth/login"),
                HttpMethod.POST, new HttpEntity<>(req),
                new ParameterizedTypeReference<>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        return resp.getBody().getData();
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private ResponseEntity<String> me(String token) {
        return rest.exchange(url("/api/auth/me"), HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class);
    }

    private void changeStatus(String adminToken, Long userId, String status, Integer muteMinutes) {
        UserStatusRequest req = new UserStatusRequest();
        req.setStatus(status);
        req.setMuteMinutes(muteMinutes);
        HttpHeaders headers = bearer(adminToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = rest.exchange(url("/api/admin/users/" + userId + "/status"),
                HttpMethod.PUT, new HttpEntity<>(req, headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** 手工签一个第七轮之前格式的 token（无 ver claim） */
    private String legacyTokenFor(Long userId, String username) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("roles", List.of("USER"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key)
                .compact();
    }
}
```

运行：

```bash
cd /e/JavaSpace/forum && mvn -B test -Dtest=AuthInvalidationIntegrationTest
```

预期（RED）：`Tests run: 6, Failures: 4, Errors: 0, Skipped: 0`，`BUILD FAILURE`。4 个失败分别是：登出返回 500（未映射路径被兜底异常处理器吞掉，接口还不存在）、封禁后旧 token 仍 200、直接改库后旧 token 仍 200、旧 token 用例的登出步骤 500。`validTokenStillWorks` 与 `mutedUserTokenStillWorks` 应当已经通过——它们证明改动前的行为是「只要签名对就一直有效」。

- [x] **Step 2-2：仓储增加只读投影查询**

把 `src/main/java/org/song/forum/repository/UserRepository.java` 替换为：

```java
package org.song.forum.repository;

import org.song.forum.entity.User;
import org.song.forum.entity.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    /** 是否仍有其他用户引用同一个头像地址（用于避免删除共享文件）。 */
    boolean existsByAvatarUrlAndIdNot(String avatarUrl, Long id);

    /**
     * 过滤器每请求只需三列：不加载 roles 集合，避免一次鉴权触发 N+1。
     * <p>投影必须写别名（as id / as status / as tokenVersion），否则 JPA 无法把元组映射到接口。</p>
     */
    @Query("select u.id as id, u.status as status, u.tokenVersion as tokenVersion "
            + "from User u where u.id = :id")
    Optional<UserTokenState> findTokenStateById(@Param("id") Long id);

    /** 只读投影：认证过滤器判定「这次请求还能不能建立认证态」。 */
    interface UserTokenState {

        Long getId();

        UserStatus getStatus();

        long getTokenVersion();
    }
}
```

- [x] **Step 2-3：过滤器回查用户状态与版本**

把 `src/main/java/org/song/forum/security/JwtAuthenticationFilter.java` 替换为：

```java
package org.song.forum.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.song.forum.entity.UserStatus;
import org.song.forum.repository.UserRepository;
import org.song.forum.util.JwtAuthUtil;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器：从 Authorization: Bearer &lt;token&gt; 恢复认证态。
 * <p>第七轮起，签名有效不再等于会话有效：每个请求都要回查用户当前状态与 token 版本，
 * 封禁（status=BANNED）或登出/封禁导致的版本 +1 都会让旧 token 当场失效。
 * 不建立认证态后由 SecurityConfig 的 entry point 统一返回 401，不在这里写响应体。</p>
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final UserRepository userRepository;

    @Value("${jwt.header:Authorization}")
    private String headerName;

    @Value("${jwt.prefix:Bearer }")
    private String prefix;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(headerName);
        if (StringUtils.hasText(header) && header.startsWith(prefix)) {
            String token = header.substring(prefix.length()).trim();
            Claims claims = tokenProvider.validate(token);
            if (claims != null
                    && SecurityContextHolder.getContext().getAuthentication() == null
                    && isSessionAlive(claims)) {
                Long userId = Long.valueOf(claims.getSubject());
                String username = claims.get("username", String.class);
                List<String> roles = JwtAuthUtil.claimRoles(claims);
                ForumUserDetails principal = ForumUserDetails.fromClaims(userId, username, roles);
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 会话是否仍然有效：用户存在、未被封禁、且 token 版本与库中一致。
     * <p>旧 token 没有 ver claim 时 {@link JwtTokenProvider#tokenVersion(Claims)} 返回 0，
     * 因此版本为 0 的老用户不会被强制重登；一旦版本变成 1，旧 token 立即被拒。</p>
     */
    private boolean isSessionAlive(Claims claims) {
        Long userId;
        try {
            userId = Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            return false;
        }
        return userRepository.findTokenStateById(userId)
                .filter(state -> state.getStatus() != UserStatus.BANNED)
                .map(state -> state.getTokenVersion() == JwtTokenProvider.tokenVersion(claims))
                .orElse(false);
    }
}
```

- [x] **Step 2-4：登出服务、接口与安全规则**

在 `src/main/java/org/song/forum/service/AuthService.java` 的 `login` 之后插入登出方法（`@Transactional` 靠脏检查落库，不要多写一次 `save`）：

```java
    /** 登出：递增 token 版本号，该用户已签发的所有 token 在下一个请求即失效 */
    @Transactional
    public void logout(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        user.setTokenVersion(user.getTokenVersion() + 1);
        log.info("用户 {} 已登出，token 版本升为 {}", user.getUsername(), user.getTokenVersion());
    }
```

在 `src/main/java/org/song/forum/controller/AuthController.java` 的 `login` 之后插入：

```java
    /** 登出（需登录）：递增 token 版本，旧 token 立即失效 */
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout(SecurityUtils.currentUserId());
        return Result.ok(null, "已退出登录");
    }
```

修改 `src/main/java/org/song/forum/config/SecurityConfig.java` 第 70–71 行：

```java
                        // 必须先于下面的 GET 放行规则声明，否则匿名请求会直达 Controller 抛 500
                        .requestMatchers("/api/auth/me", "/api/auth/profile", "/api/auth/logout").authenticated()
```

说明：`/api/auth/logout` 是 POST，理论上会落到 `.anyRequest().authenticated()`；这里显式列出，是为了将来放宽 `anyRequest` 时不会漏掉它（登出必须携带 token，否则无法知道该递增谁）。

- [x] **Step 2-5：封禁时递增版本**

修改 `src/main/java/org/song/forum/service/AdminUserService.java` 的 `switch`：

```java
            case BANNED -> {
                user.setStatus(UserStatus.BANNED);
                user.setMutedUntil(null);
                // 封禁要立刻踢掉已签发的 token：只改 status 的话过滤器要等版本变化才拒绝
                user.setTokenVersion(user.getTokenVersion() + 1);
            }
```

注意 MUTED 与 ACTIVE 分支**不要**递增：禁言只限制发帖，解封也不应该把用户踢下线。

运行：

```bash
cd /e/JavaSpace/forum && mvn -B test -Dtest=AuthInvalidationIntegrationTest
```

预期（GREEN）：`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`、`BUILD SUCCESS`。

- [x] **Step 2-6：Task 2 全量回归并提交**

```bash
cd /e/JavaSpace/forum && mvn -B clean test
```

预期：`BUILD SUCCESS`；`Tests run: 142, Failures: 0, Errors: 0, Skipped: 1`（Task 1 的 136 + 新增 6）。

```bash
cd /e/JavaSpace/forum && git status --short
```

预期：

```text
 M src/main/java/org/song/forum/config/SecurityConfig.java
 M src/main/java/org/song/forum/controller/AuthController.java
 M src/main/java/org/song/forum/repository/UserRepository.java
 M src/main/java/org/song/forum/security/JwtAuthenticationFilter.java
 M src/main/java/org/song/forum/service/AdminUserService.java
 M src/main/java/org/song/forum/service/AuthService.java
?? src/test/java/org/song/forum/controller/AuthInvalidationIntegrationTest.java
```

```bash
cd /e/JavaSpace/forum
git add src/main/java/org/song/forum/config/SecurityConfig.java \
        src/main/java/org/song/forum/controller/AuthController.java \
        src/main/java/org/song/forum/repository/UserRepository.java \
        src/main/java/org/song/forum/security/JwtAuthenticationFilter.java \
        src/main/java/org/song/forum/service/AdminUserService.java \
        src/main/java/org/song/forum/service/AuthService.java \
        src/test/java/org/song/forum/controller/AuthInvalidationIntegrationTest.java
git commit -m "feat: 让封禁与登出即时失效旧 token"
```

预期：`7 files changed, ...`；`git status --short` 为空。

## Task 3：登录失败限流

**Files:**

- Create: `src/main/java/org/song/forum/security/ClientIpResolver.java`
- Create: `src/main/java/org/song/forum/security/LoginRateLimiter.java`
- Create: `src/main/java/org/song/forum/exception/TooManyLoginAttemptsException.java`
- Create: `src/main/java/org/song/forum/config/ClockConfig.java`
- Modify: `src/main/java/org/song/forum/exception/GlobalExceptionHandler.java`
- Modify: `src/main/java/org/song/forum/controller/AuthController.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/java/org/song/forum/security/ClientIpResolverTest.java`
- Create: `src/test/java/org/song/forum/security/LoginRateLimiterTest.java`
- Create: `src/test/java/org/song/forum/controller/LoginRateLimitIntegrationTest.java`

- [x] **Step 3-1：先写 `ClientIpResolver` 的失败测试（RED）**

新建 `src/test/java/org/song/forum/security/ClientIpResolverTest.java`：

```java
package org.song.forum.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 客户端 IP 解析：只信环回跳数进来的 X-Real-IP；非环回请求一律用 remoteAddr，
 * 防止客户端自己塞一个 X-Real-IP 就绕过限流。
 */
class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver();

    @Test
    void nonLoopbackRemoteAddrWinsAndProxyHeadersAreIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.20.30.40");
        request.addHeader("X-Real-IP", "203.0.113.7");
        request.addHeader("X-Forwarded-For", "198.51.100.1");

        assertThat(resolver.resolve(request)).isEqualTo("10.20.30.40");
    }

    @Test
    void loopbackRequestUsesRealIp() {
        MockHttpServletRequest request = loopback();
        request.addHeader("X-Real-IP", "203.0.113.7");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void invalidRealIpFallsBackToFirstValidForwardedForEntry() {
        MockHttpServletRequest request = loopback();
        request.addHeader("X-Real-IP", "not-an-ip");
        request.addHeader("X-Forwarded-For", "unknown, 198.51.100.9, 203.0.113.1");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
    }

    @Test
    void forwardedForSkipsOutOfRangeEntries() {
        MockHttpServletRequest request = loopback();
        request.addHeader("X-Forwarded-For", "300.1.1.1, 198.51.100.9");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
    }

    @Test
    void loopbackWithoutProxyHeadersFallsBackToRemoteAddr() {
        MockHttpServletRequest request = loopback();

        assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void acceptsIpv6LiteralButRejectsPortSuffix() {
        MockHttpServletRequest ipv6 = loopback();
        ipv6.addHeader("X-Real-IP", "2001:db8::1");
        assertThat(resolver.resolve(ipv6)).isEqualTo("2001:db8::1");

        MockHttpServletRequest withPort = loopback();
        withPort.addHeader("X-Real-IP", "203.0.113.7:5678");
        assertThat(resolver.resolve(withPort)).isEqualTo("127.0.0.1");
    }

    private MockHttpServletRequest loopback() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
```

运行：

```bash
cd /e/JavaSpace/forum && mvn -B test -Dtest=ClientIpResolverTest
```

预期（RED）：`BUILD FAILURE`，编译错误：

```text
[ERROR] /E:/JavaSpace/forum/src/test/java/org/song/forum/security/ClientIpResolverTest.java: cannot find symbol
  symbol:   class ClientIpResolver
```

- [x] **Step 3-2：实现 `ClientIpResolver` 并跑绿**

新建 `src/main/java/org/song/forum/security/ClientIpResolver.java`：

```java
package org.song.forum.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 解析登录请求的真实客户端 IP。
 *
 * <p>信任模型：只有来自环回地址（本机 nginx）的请求才读代理头。生产环境
 * {@code deploy/nginx/forum.conf} 的两个 API location 都用
 * {@code proxy_set_header X-Real-IP $remote_addr;} <b>覆盖</b>写入，客户端伪造的同名头不会穿透。
 * 从其他地址直连后端时，代理头一律忽略，直接使用 TCP 对端地址，避免「自己填 X-Real-IP 换一个
 * 桶继续爆破」。</p>
 *
 * <p>只做字面量形状校验（长度 ≤ 45、IPv4 每段 ≤ 255、IPv6 只允许十六进制与冒号），
 * 不调用 {@code InetAddress#getByName}——那会触发 DNS 解析，既慢又给了内网探测面。</p>
 */
@Component
public class ClientIpResolver {

    /** IPv6 文本表示的最大长度（含 IPv4-mapped 形式）。 */
    private static final int MAX_IP_LENGTH = 45;

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");

    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]+$");

    /**
     * @return 客户端 IP；请求没有可用信息时返回 {@code remoteAddr}（可能是环回地址）
     */
    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr != null && !isLoopback(remoteAddr)) {
            return remoteAddr;
        }

        String realIp = request.getHeader("X-Real-IP");
        if (isIpLiteral(realIp)) {
            return realIp.trim();
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            for (String candidate : forwarded.split(",")) {
                if (isIpLiteral(candidate)) {
                    return candidate.trim();
                }
            }
        }
        return remoteAddr;
    }

    static boolean isLoopback(String ip) {
        return "::1".equals(ip)
                || "0:0:0:0:0:0:0:1".equals(ip)
                || ip.startsWith("127.");
    }

    static boolean isIpLiteral(String candidate) {
        if (candidate == null) {
            return false;
        }
        String value = candidate.trim();
        if (value.isEmpty() || value.length() > MAX_IP_LENGTH) {
            return false;
        }
        // 同时出现点与冒号：要么是 1.2.3.4:80（端口），要么是 IPv4-mapped IPv6，都不接受
        if (value.indexOf(':') >= 0 && value.indexOf('.') >= 0) {
            return false;
        }
        if (IPV4.matcher(value).matches()) {
            for (String part : value.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    return false;
                }
            }
            return true;
        }
        return value.indexOf(':') >= 0 && IPV6.matcher(value).matches();
    }
}
```

运行：

```bash
cd /e/JavaSpace/forum && mvn -B test -Dtest=ClientIpResolverTest
```

预期（GREEN）：`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`、`BUILD SUCCESS`。

- [x] **Step 3-3：先写限流器与 429 异常的失败测试（RED）**

新建 `src/test/java/org/song/forum/security/LoginRateLimiterTest.java`。时间用可变 `Clock` 推进，整个类不出现 `Thread.sleep`，在 CI 上稳定且毫秒级完成。

```java
package org.song.forum.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.song.forum.exception.TooManyLoginAttemptsException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 限流器纯单元测试：窗口累积、阈值锁定、锁定到期、成功清零、窗口过期、清理、IP 隔离。
 */
class LoginRateLimiterTest {

    private static final int MAX_FAILURES = 3;
    private static final long WINDOW_MS = 60_000L;
    private static final long LOCK_MS = 60_000L;
    private static final String IP = "203.0.113.5";

    private MutableClock clock;
    private LoginRateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(1_700_000_000_000L);
        limiter = new LoginRateLimiter(clock, MAX_FAILURES, WINDOW_MS, LOCK_MS);
    }

    @Test
    void freshIpIsNotBlocked() {
        assertThatCode(() -> limiter.check(IP)).doesNotThrowAnyException();
    }

    @Test
    void failuresBelowThresholdDoNotLock() {
        limiter.recordFailure(IP);
        limiter.recordFailure(IP);

        assertThatCode(() -> limiter.check(IP)).doesNotThrowAnyException();
    }

    @Test
    void reachingThresholdLocksForTheWholeLockWindow() {
        failTimes(MAX_FAILURES);

        assertThatThrownBy(() -> limiter.check(IP))
                .isInstanceOf(TooManyLoginAttemptsException.class)
                .hasMessage("登录失败次数过多，请 1 分钟后再试");
        assertThat(retryAfterSeconds()).isEqualTo(60L);
    }

    @Test
    void successClearsPreviousFailures() {
        limiter.recordFailure(IP);
        limiter.recordFailure(IP);
        limiter.recordSuccess(IP);
        limiter.recordFailure(IP);
        limiter.recordFailure(IP);

        assertThatCode(() -> limiter.check(IP)).doesNotThrowAnyException();
    }

    @Test
    void failuresOutsideWindowDoNotAccumulate() {
        limiter.recordFailure(IP);
        clock.advance(WINDOW_MS + 1);
        limiter.recordFailure(IP);
        clock.advance(WINDOW_MS + 1);
        limiter.recordFailure(IP);

        assertThatCode(() -> limiter.check(IP)).doesNotThrowAnyException();
    }

    @Test
    void lockExpiresAfterLockWindowAndCounterResets() {
        failTimes(MAX_FAILURES);

        clock.advance(LOCK_MS + 1);

        assertThatCode(() -> limiter.check(IP)).doesNotThrowAnyException();

        limiter.recordFailure(IP);
        limiter.recordFailure(IP);
        assertThatCode(() -> limiter.check(IP)).doesNotThrowAnyException();
    }

    @Test
    void retryAfterSecondsRoundsUpAndIsAtLeastOne() {
        failTimes(MAX_FAILURES);

        clock.advance(LOCK_MS - 500);

        assertThat(retryAfterSeconds()).isEqualTo(1L);
    }

    @Test
    void lockedIpDoesNotAffectOtherIps() {
        failTimes(MAX_FAILURES);

        assertThatThrownBy(() -> limiter.check(IP)).isInstanceOf(TooManyLoginAttemptsException.class);
        assertThatCode(() -> limiter.check("198.51.100.77")).doesNotThrowAnyException();
    }

    @Test
    void cleanupKeepsLockedWindowsAndRemovesStaleOnes() {
        failTimes(MAX_FAILURES);                 // IP 进入锁定
        clock.advance(LOCK_MS / 2);              // 锁未到期
        limiter.recordFailure("198.51.100.3");   // 另一个 IP 一次失败

        assertThat(limiter.cleanupExpired()).isZero();

        clock.advance(LOCK_MS + 1);              // 锁过期，且另一个窗口的失败也超出窗口

        assertThat(limiter.cleanupExpired()).isEqualTo(2);
    }

    // ---------- 辅助 ----------

    private void failTimes(int times) {
        for (int i = 0; i < times; i++) {
            limiter.recordFailure(IP);
        }
    }

    private long retryAfterSeconds() {
        try {
            limiter.check(IP);
        } catch (TooManyLoginAttemptsException e) {
            return e.getRetryAfterSeconds();
        }
        throw new AssertionError("预期该 IP 已被限流，但 check 未抛异常");
    }

    /** 可手动推进的 Clock，避免测试里 sleep。 */
    private static final class MutableClock extends Clock {

        private long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        private void advance(long deltaMs) {
            this.millis += deltaMs;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
```

运行：

```bash
cd /e/JavaSpace/forum && mvn -B test -Dtest=LoginRateLimiterTest
```

预期（RED）：`BUILD FAILURE`，编译错误：

```text
[ERROR] .../LoginRateLimiterTest.java: cannot find symbol
  symbol:   class LoginRateLimiter
[ERROR] .../LoginRateLimiterTest.java: cannot find symbol
  symbol:   class TooManyLoginAttemptsException
```

- [x] **Step 3-4：实现异常、Clock、限流器与 429 处理器**

新建 `src/main/java/org/song/forum/exception/TooManyLoginAttemptsException.java`：

```java
package org.song.forum.exception;

/**
 * 登录失败次数超限（HTTP 429）。
 * <p>携带 {@code retryAfterSeconds}，由 GlobalExceptionHandler 写入 {@code Retry-After} 响应头；
 * 文案按「向上取整的分钟数、至少 1 分钟」生成，避免出现「请 0 分钟后再试」。</p>
 */
public class TooManyLoginAttemptsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyLoginAttemptsException(long retryAfterSeconds) {
        super(message(retryAfterSeconds));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    private static String message(long retryAfterSeconds) {
        long minutes = Math.max(1L, (retryAfterSeconds + 59) / 60);
        return "登录失败次数过多，请 " + minutes + " 分钟后再试";
    }
}
```

新建 `src/main/java/org/song/forum/config/ClockConfig.java`：

```java
package org.song.forum.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 统一的时间来源：登录限流器注入 Clock，单测用可变 Clock 推进时间，不 sleep。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
```

新建 `src/main/java/org/song/forum/security/LoginRateLimiter.java`：

```java
package org.song.forum.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.song.forum.exception.TooManyLoginAttemptsException;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按客户端 IP 的登录失败限流（单实例内存实现）。
 *
 * <p>算法：窗口内累积失败时间戳；达到 {@code maxFailures} 时把 {@code lockedUntil} 设为
 * now + {@code lockMs} 并清空失败队列；锁定期内所有请求直接 429，<b>不再校验密码</b>，
 * 避免「锁定期间继续试密码」既拖慢响应又继续消耗 BCrypt 算力。成功登录直接删掉该 IP 的记录。</p>
 *
 * <p>为什么不做成滑动窗口计数（只存一个 int）：失败时间戳能同时支撑「窗口过期」与
 * 「锁到期后重新计数」两种判断，且成本只是每 IP 一个小队列。单 IP 队列最多
 * {@code maxFailures} 个元素（达到阈值即清空并锁定），内存上界可控。</p>
 *
 * <p>局限（见 README「已知限制」）：多实例部署时每个实例各算各的，需要换成共享存储。</p>
 */
@Slf4j
@Component
public class LoginRateLimiter {

    private final Clock clock;
    private final int maxFailures;
    private final long windowMs;
    private final long lockMs;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public LoginRateLimiter(Clock clock,
                            @Value("${app.security.login.max-failures:5}") int maxFailures,
                            @Value("${app.security.login.window-ms:900000}") long windowMs,
                            @Value("${app.security.login.lock-ms:900000}") long lockMs) {
        this.clock = clock;
        this.maxFailures = maxFailures;
        this.windowMs = windowMs;
        this.lockMs = lockMs;
    }

    /** 进入登录流程前调用：锁定中直接抛 429 异常，不再校验凭据 */
    public void check(String ip) {
        Window window = windows.get(ip);
        if (window == null) {
            return;
        }
        synchronized (window) {
            long now = clock.millis();
            if (window.lockedUntil > now) {
                long retryAfterSeconds = Math.max(1L, (window.lockedUntil - now + 999) / 1000);
                throw new TooManyLoginAttemptsException(retryAfterSeconds);
            }
        }
    }

    /** 凭据失败后调用：修剪窗口、追加当前时间、必要时锁定 */
    public void recordFailure(String ip) {
        Window window = windows.computeIfAbsent(ip, key -> new Window());
        synchronized (window) {
            long now = clock.millis();
            if (window.lockedUntil > 0 && window.lockedUntil <= now) {
                // 锁已到期：计数归零，重新开始
                window.lockedUntil = 0L;
                window.failures.clear();
            }
            long cutoff = now - windowMs;
            while (!window.failures.isEmpty() && window.failures.peekFirst() <= cutoff) {
                window.failures.pollFirst();
            }
            window.failures.addLast(now);
            if (window.failures.size() >= maxFailures) {
                window.lockedUntil = now + lockMs;
                window.failures.clear();
                log.warn("IP {} 登录失败 {} 次，锁定 {} ms", ip, maxFailures, lockMs);
            }
        }
    }

    /** 登录成功后调用：清空该 IP 的失败记录 */
    public void recordSuccess(String ip) {
        windows.remove(ip);
    }

    /** 定时清理入口：{@code @Scheduled} 只允许 void 方法，逻辑放在可断言返回值的 cleanupExpired() */
    @Scheduled(fixedDelayString = "${app.security.login.cleanup-interval-ms:60000}")
    public void cleanupScheduled() {
        int removed = cleanupExpired();
        if (removed > 0) {
            log.debug("清理登录限流窗口 {} 个，剩余 {}", removed, windows.size());
        }
    }

    /**
     * 清理无锁且已过窗口、或锁已过期的 IP 记录，返回清理条数（供单测断言）。
     */
    public int cleanupExpired() {
        long now = clock.millis();
        int removed = 0;
        Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            Window window = iterator.next().getValue();
            synchronized (window) {
                if (window.lockedUntil > now) {
                    continue;
                }
                Long lastFailure = window.failures.peekLast();
                boolean stale = lastFailure == null || lastFailure <= now - windowMs;
                if (stale) {
                    iterator.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    private static final class Window {

        private final Deque<Long> failures = new ArrayDeque<>();

        private long lockedUntil;
    }
}
```

说明：`@Scheduled` 依赖容器里已有的 `@EnableScheduling`（`src/main/java/org/song/forum/config/SseConfig.java` 第 13 行），本轮不重复开启，否则会出现重复调度的告警。

修改 `src/main/java/org/song/forum/exception/GlobalExceptionHandler.java`：import 区增加 `import org.springframework.http.HttpHeaders;`（放在 `HttpStatus` 之前），并在 `handleInvalidStreamTicket` 之后插入：

```java
    /**
     * 登录失败限流 → 429 + Retry-After。
     * <p>不能返回 200 或 401：429 才能让客户端与网关区分「凭据错了」与「先别试了」；
     * Retry-After 给的是秒数，前端可据此提示，运维也能在压测脚本里据此退避。</p>
     */
    @ExceptionHandler(TooManyLoginAttemptsException.class)
    public ResponseEntity<Result<Void>> handleTooManyLoginAttempts(TooManyLoginAttemptsException e) {
        log.warn("登录失败次数超限: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(Result.fail(e.getMessage()));
    }
```

`TooManyLoginAttemptsException` 与 `GlobalExceptionHandler` 同包，不需要额外 import。

运行：

```bash
cd /e/JavaSpace/forum && mvn -B test -Dtest=LoginRateLimiterTest
```

预期（GREEN）：`Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`、`BUILD SUCCESS`。

- [x] **Step 3-5：先写限流集成测试（RED）**

新建 `src/test/java/org/song/forum/controller/LoginRateLimitIntegrationTest.java`。阈值与窗口用测试专用属性调小，避免测试等 15 分钟。

```java
package org.song.forum.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.song.forum.dto.auth.LoginRequest;
import org.song.forum.dto.auth.RegisterRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录限流集成测试：走完整过滤器链与真实 HTTP。
 * 每个用例使用不同的 X-Real-IP，互不污染（限流状态是单例里的 Map）。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:login-rate-limit-test;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.security.login.max-failures=3",
        "app.security.login.window-ms=60000",
        "app.security.login.lock-ms=60000"
})
class LoginRateLimitIntegrationTest {

    private static final String USER = "rate_limit_user";
    private static final String PASSWORD = "password123";
    private static final String WRONG = "wrong-password";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void failuresBelowThresholdDoNotLock() {
        ensureUser();
        String ip = "198.51.100.10";

        assertThat(login(ip, USER, WRONG).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(ip, USER, WRONG).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(ip, USER, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void thirdFailureLocksIpAndBlocksCorrectPassword() {
        ensureUser();
        String ip = "198.51.100.11";

        for (int i = 0; i < 3; i++) {
            assertThat(login(ip, USER, WRONG).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<String> locked = login(ip, USER, PASSWORD);

        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(locked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isIn("59", "60");
        assertThat(locked.getBody()).contains("登录失败次数过多，请 1 分钟后再试");
    }

    @Test
    void otherIpIsNotAffected() {
        ensureUser();
        String blockedIp = "198.51.100.12";
        for (int i = 0; i < 3; i++) {
            login(blockedIp, USER, WRONG);
        }

        assertThat(login("198.51.100.13", USER, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void successResetsFailureCounter() {
        ensureUser();
        String ip = "198.51.100.14";

        login(ip, USER, WRONG);
        login(ip, USER, WRONG);
        assertThat(login(ip, USER, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        login(ip, USER, WRONG);
        login(ip, USER, WRONG);
        assertThat(login(ip, USER, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---------- 辅助 ----------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private void ensureUser() {
        RegisterRequest req = new RegisterRequest();
        req.setUsername(USER);
        req.setPassword(PASSWORD);
        req.setEmail("rate_limit@test.com");
        rest.postForEntity(url("/api/auth/register"), req, String.class);
    }

    private ResponseEntity<String> login(String ip, String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Real-IP", ip);
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword(password);
        return rest.exchange(url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>(req, headers), String.class);
    }
}
```

运行：

```bash
cd /e/JavaSpace/forum && mvn -B test -Dtest=LoginRateLimitIntegrationTest
```

预期（RED）：`Tests run: 4, Failures: 1, Errors: 0, Skipped: 0`、`BUILD FAILURE`。失败的是 `thirdFailureLocksIpAndBlocksCorrectPassword`——此时还没有限流，第 4 次正确密码返回 200；断言期望 429。

- [x] **Step 3-6：接入控制器与配置（GREEN）**

把 `src/main/java/org/song/forum/controller/AuthController.java` 替换为：

```java
package org.song.forum.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.*;
import org.song.forum.dto.auth.AuthResponse;
import org.song.forum.dto.auth.LoginRequest;
import org.song.forum.dto.auth.RegisterRequest;
import org.song.forum.dto.user.UserResponse;
import org.song.forum.security.ClientIpResolver;
import org.song.forum.security.LoginRateLimiter;
import org.song.forum.security.SecurityUtils;
import org.song.forum.service.AuthService;
import org.song.forum.util.Result;

/**
 * 认证接口：注册 / 登录 / 登出 / 当前用户 / 个人资料
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final ClientIpResolver clientIpResolver;
    private final LoginRateLimiter loginRateLimiter;

    /** 注册 */
    @PostMapping("/register")
    public ResponseEntity<Result<AuthResponse>> register(@Valid @RequestBody RegisterRequest req) {
        AuthResponse resp = authService.register(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.ok(resp, "注册成功"));
    }

    /**
     * 登录：登录前先查限流，凭据失败计数，成功清零。
     * <p>参数校验失败（400）与被封禁（400）都不计数：前者不是凭据尝试，后者不该变成
     * 对同一 IP 的额外惩罚。锁定期间直接 429，不校验密码。</p>
     */
    @PostMapping("/login")
    public Result<AuthResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
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
    }

    /** 登出（需登录）：递增 token 版本，旧 token 立即失效 */
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout(SecurityUtils.currentUserId());
        return Result.ok(null, "已退出登录");
    }

    /** 当前登录用户信息（需携带 Token） */
    @GetMapping("/me")
    public Result<AuthResponse> me() {
        return Result.ok(authService.me(SecurityUtils.currentUserId()));
    }

    /** 更新个人资料（需登录） */
    @PutMapping("/profile")
    public Result<UserResponse> updateProfile(@Valid @RequestBody org.song.forum.dto.user.UpdateProfileRequest req) {
        return Result.ok(authService.updateProfile(SecurityUtils.currentUserId(), req), "资料已更新");
    }

    /** 用户公开主页信息（游客可访问） */
    @GetMapping("/users/{id}")
    public Result<UserResponse> publicProfile(@PathVariable Long id) {
        return Result.ok(authService.getPublicProfile(id));
    }
}
```

修改 `src/main/resources/application.yml`：在 `app:` 段的 `sse:` 块之后、同样的两格缩进追加（**不要**另起一个 `app:` 键，YAML 后出现的同名键会覆盖前面的整块）：

```yaml
  security:
    login:
      max-failures: ${LOGIN_MAX_FAILURES:5}
      window-ms: ${LOGIN_WINDOW_MS:900000}
      lock-ms: ${LOGIN_LOCK_MS:900000}
      cleanup-interval-ms: ${LOGIN_CLEANUP_INTERVAL_MS:60000}
```

改完后 `app:` 段应形如：

```yaml
app:
  upload-dir: ${UPLOAD_DIR:./uploads}
  sse:
    timeout-ms: ${SSE_TIMEOUT_MS:1800000}
    heartbeat-ms: ${SSE_HEARTBEAT_MS:15000}
    max-connections-per-user: ${SSE_MAX_CONNECTIONS_PER_USER:5}
    ticket-ttl-ms: ${SSE_TICKET_TTL_MS:60000}
  security:
    login:
      max-failures: ${LOGIN_MAX_FAILURES:5}
      window-ms: ${LOGIN_WINDOW_MS:900000}
      lock-ms: ${LOGIN_LOCK_MS:900000}
      cleanup-interval-ms: ${LOGIN_CLEANUP_INTERVAL_MS:60000}
```

运行：

```bash
cd /e/JavaSpace/forum && mvn -B test -Dtest=LoginRateLimitIntegrationTest
```

预期（GREEN）：`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`、`BUILD SUCCESS`。

- [x] **Step 3-7：Task 3 全量回归并提交**

```bash
cd /e/JavaSpace/forum && mvn -B clean test
```

预期：`BUILD SUCCESS`；`Tests run: 161, Failures: 0, Errors: 0, Skipped: 1`（Task 2 的 142 + 6 + 9 + 4）。

```bash
cd /e/JavaSpace/forum && git status --short
```

预期：

```text
 M src/main/java/org/song/forum/controller/AuthController.java
 M src/main/java/org/song/forum/exception/GlobalExceptionHandler.java
 M src/main/resources/application.yml
?? src/main/java/org/song/forum/config/ClockConfig.java
?? src/main/java/org/song/forum/exception/TooManyLoginAttemptsException.java
?? src/main/java/org/song/forum/security/ClientIpResolver.java
?? src/main/java/org/song/forum/security/LoginRateLimiter.java
?? src/test/java/org/song/forum/controller/LoginRateLimitIntegrationTest.java
?? src/test/java/org/song/forum/security/ClientIpResolverTest.java
?? src/test/java/org/song/forum/security/LoginRateLimiterTest.java
```

```bash
cd /e/JavaSpace/forum
git add src/main/java/org/song/forum/controller/AuthController.java \
        src/main/java/org/song/forum/exception/GlobalExceptionHandler.java \
        src/main/resources/application.yml \
        src/main/java/org/song/forum/config/ClockConfig.java \
        src/main/java/org/song/forum/exception/TooManyLoginAttemptsException.java \
        src/main/java/org/song/forum/security/ClientIpResolver.java \
        src/main/java/org/song/forum/security/LoginRateLimiter.java \
        src/test/java/org/song/forum/controller/LoginRateLimitIntegrationTest.java \
        src/test/java/org/song/forum/security/ClientIpResolverTest.java \
        src/test/java/org/song/forum/security/LoginRateLimiterTest.java
git commit -m "feat: 登录失败按 IP 限流并返回 429"
```

预期：`10 files changed, ...`；`git status --short` 为空。

## Task 4：前端登出

**Files:**

- Modify: `frontend/src/api/index.js`
- Modify: `frontend/src/stores/auth.js`
- Create: `frontend/src/stores/auth.spec.js`
- Modify: `frontend/src/api/index.spec.js`

- [x] **Step 4-1：先写 auth store 的失败测试（RED）**

新建 `frontend/src/stores/auth.spec.js`。三个用例覆盖：成功后清态、失败也清态、未登录不抛错。

```js
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { authApi } from '@/api'
import { useAuthStore } from './auth'

vi.mock('@/api', () => ({
  authApi: {
    logout: vi.fn()
  }
}))

/**
 * 前端登出：本地状态必须清干净，且后端调用失败不能阻断登出。
 */
describe('auth store 登出', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('调用后端登出接口并清空本地状态', async () => {
    localStorage.setItem('jf_token', 'token-1')
    localStorage.setItem('jf_user', JSON.stringify({ id: 1, username: 'alice' }))
    const store = useAuthStore()
    authApi.logout.mockResolvedValue(undefined)

    await store.logout()

    expect(authApi.logout).toHaveBeenCalledTimes(1)
    expect(store.token).toBe('')
    expect(store.user).toBeNull()
    expect(localStorage.getItem('jf_token')).toBeNull()
    expect(localStorage.getItem('jf_user')).toBeNull()
  })

  it('后端登出失败也清空本地状态', async () => {
    const store = useAuthStore()
    store.token = 'token-2'
    store.user = { id: 2, username: 'bob' }
    authApi.logout.mockRejectedValue(new Error('network down'))

    await expect(store.logout()).resolves.toBeUndefined()

    expect(store.token).toBe('')
    expect(store.user).toBeNull()
    expect(localStorage.getItem('jf_token')).toBeNull()
  })

  it('未登录时调用登出也不抛异常', async () => {
    const store = useAuthStore()
    authApi.logout.mockRejectedValue(new Error('401'))

    await expect(store.logout()).resolves.toBeUndefined()

    expect(store.token).toBe('')
    expect(store.user).toBeNull()
  })
})
```

运行：

```bash
cd /e/JavaSpace/forum/frontend && pnpm vitest run src/stores/auth.spec.js
```

预期（RED）：`Tests  1 failed | 2 passed (3)`，失败信息：

```text
FAIL  src/stores/auth.spec.js > auth store 登出 > 调用后端登出接口并清空本地状态
AssertionError: expected "vi.fn()" to be called 1 times, but got 0 times
```

- [x] **Step 4-2：补 API 层测试（RED）**

修改 `frontend/src/api/index.spec.js` 第 49 行，把 `authApi` 加入解构：

```js
const { tagApi, userApi, postApi, authApi } = await import('./index.js')
```

在 `describe('api 拦截器')` 内、`'非 401 错误展示后端消息，不动登录态'` 用例之后追加一个 429 回归用例：

```js
  it('登录限流 429 展示后端文案、保留登录态、不跳转', async () => {
    localStorage.setItem('jf_token', 'still-valid')
    const err = httpError(429, '登录失败次数过多，请 1 分钟后再试', '/auth/login')

    await expect(handlers.responseError(err)).rejects.toBe(err)

    expect(elMessageError).toHaveBeenCalledWith('登录失败次数过多，请 1 分钟后再试')
    expect(elMessageError).not.toHaveBeenCalledWith('请先登录')
    expect(localStorage.getItem('jf_token')).toBe('still-valid')
    expect(window.location.href).toBe('/')
  })
```

在文件末尾（最后一个 `describe` 之后）追加登出路径用例：

```js
describe('authApi 请求路径', () => {
  it('logout 调用 POST /auth/logout', async () => {
    apiPost.mockResolvedValueOnce(undefined)

    await authApi.logout()

    expect(apiPost).toHaveBeenCalledTimes(1)
    expect(apiPost).toHaveBeenCalledWith('/auth/logout')
  })
})
```

运行：

```bash
cd /e/JavaSpace/forum/frontend && pnpm vitest run src/api/index.spec.js
```

预期（RED）：`Tests  1 failed | 14 passed (15)`（原 13 个用例 + 1 个 429 + 1 个登出；失败的是登出用例）：

```text
TypeError: authApi.logout is not a function
```

- [x] **Step 4-3：API 增加登出方法**

修改 `frontend/src/api/index.js` 的 `authApi`（第 73–79 行），在 `me` 之后插入一行：

```js
export const authApi = {
  register: (data) => api.post('/auth/register', data),
  login: (data) => api.post('/auth/login', data),
  me: () => api.get('/auth/me'),
  logout: () => api.post('/auth/logout'),
  updateProfile: (data) => api.put('/auth/profile', data),
  publicProfile: (id) => api.get(`/auth/users/${id}`)
}
```

运行：

```bash
cd /e/JavaSpace/forum/frontend && pnpm vitest run src/api/index.spec.js
```

预期（GREEN）：`Tests  15 passed (15)`。

- [x] **Step 4-4：store 登出改为异步并抽出 `clearAuth()`**

把 `frontend/src/stores/auth.js` 的 `logout()` 替换为：

```js
    /**
     * 登出：先尽力通知后端把 token 版本 +1，再清本地状态。
     * 后端不可达/已过期也必须清本地——否则用户以为退出了，实际 token 还在。
     */
    async logout() {
      try {
        await authApi.logout()
      } catch {
        // 忽略：本地登出不能因为服务端失败而失败
      } finally {
        this.clearAuth()
      }
    },

    /** 只清本地登录态（401 拦截器与 logout 共用同一套清理逻辑） */
    clearAuth() {
      this.token = ''
      this.user = null
      localStorage.removeItem('jf_token')
      localStorage.removeItem('jf_user')
    }
```

运行：

```bash
cd /e/JavaSpace/forum/frontend && pnpm vitest run src/stores/auth.spec.js
```

预期（GREEN）：`Tests  3 passed (3)`。

- [x] **Step 4-5：前端全量测试、构建并提交**

```bash
cd /e/JavaSpace/forum/frontend && pnpm test
```

预期：`Test Files  31 passed (31)`、`Tests  314 passed (314)`（原 30 个 spec / 309 个用例 + 1 个新 spec / 3 个用例 + index.spec 新增 2 个用例）。

```bash
cd /e/JavaSpace/forum/frontend && MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build
```

预期：`✓ built in ...`，产物在 `frontend/dist/`（已 gitignore，不要提交）。

```bash
cd /e/JavaSpace/forum && git status --short
```

预期：

```text
 M frontend/src/api/index.js
 M frontend/src/api/index.spec.js
 M frontend/src/stores/auth.js
?? frontend/src/stores/auth.spec.js
```

```bash
cd /e/JavaSpace/forum
git add frontend/src/api/index.js frontend/src/api/index.spec.js \
        frontend/src/stores/auth.js frontend/src/stores/auth.spec.js
git commit -m "feat: 前端登出调用后端失效接口"
```

预期：`4 files changed, ...`；`git status --short` 为空。

## Task 5：文档

**Files:**
- Modify: `README.md`
- Modify: `docs/DEVELOPMENT.md`
- Modify: `docs/deployment/README.md`
- Modify: `docs/superpowers/plans/2026-10-23-round7-security-hardening.md`（勾选本计划 Task 1–5 的复选框）
- Test: 无新增测试；验证方式是 `git diff --stat` 与下面的 grep 断言

> 文档任务的 TDD 等价物是「先用 grep 断言旧数字还在，再改，再断言新数字出现」。每一步都给出前后两条命令。

- [x] **Step 5-1：README 增加功能条目、V4 迁移与四个配置项**

先确认旧文本存在：

```bash
cd /e/JavaSpace/forum
grep -n 'SSE_MAX_CONNECTIONS_PER_USER\|16 个测试类、133 个用例\|30 个 spec、309 个用例' README.md
```

预期：三条命中，行号分别为 120、235、245。

（1）功能列表：在 `README.md` 第 18 行 `- 权限：...封禁` 之后插入一行：

```markdown
- **会话安全**：登出与封禁会让旧 JWT 立即失效（`users.token_version` 版本号校验，过滤器每请求比对一次）；登录失败按客户端 IP 计数，`LOGIN_MAX_FAILURES`（默认 5）次密码错误后锁定 `LOGIN_LOCK_MS`（默认 15 分钟），锁定期间返回 429 并带 `Retry-After` 秒数，即使密码正确也拒绝
```

（2）数据库准备：在 V3 那一行（第 44 行）之后插入：

```markdown
- `V4__add_user_token_version.sql`：`users` 加 `token_version BIGINT NOT NULL DEFAULT 0`，登出/封禁时 +1，用于让已签发的 JWT 立即失效（旧 token 没有 `ver` 声明时按 0 处理，升级不强制重登）
```

（3）配置项表：在 `| SSE_MAX_CONNECTIONS_PER_USER | ... |` 之后追加：

```markdown
| `LOGIN_MAX_FAILURES` | `5`（同一 IP 在失败窗口内允许的错误密码次数，达到即锁定） |
| `LOGIN_WINDOW_MS` | `900000`（失败计数窗口，15 分钟；窗口外的失败自动作废） |
| `LOGIN_LOCK_MS` | `900000`（锁定时长，15 分钟；锁定内即使密码正确也返回 429） |
| `LOGIN_CLEANUP_INTERVAL_MS` | `60000`（清理过期窗口的间隔；只为回收内存，不影响判定） |
```

（4）测试基线：第 235 行改为：

```markdown
测试使用 H2 内存库（PostgreSQL 兼容模式 `MODE=PostgreSQL`），Flyway 关闭、`ddl-auto=create-drop`，无需本地 PostgreSQL、也无需设置任何环境变量（`src/test/resources/application-test.yml` 由 `@ActiveProfiles("test")` 提供测试专用凭据）。当前为 20 个测试类、161 个用例（其中 1 个 POSIX 文件权限用例在 Windows 上跳过）。
```

第 245 行改为：

```markdown
当前为 31 个 spec、314 个用例，覆盖：
```

（5）已知限制：在 §已知限制 中「实时通知是单实例的」那一条之后追加：

```markdown
- **登录限流是单实例的**：失败计数存在应用内存（`LoginRateLimiter` 的 `ConcurrentHashMap`），多实例部署时每个实例各算各的，重启即清零。当前单容器部署不构成问题；要跨实例一致需换成 Redis 之类的共享窗口（见 `docs/DEVELOPMENT.md` §5.2）。
```

验证：

```bash
cd /e/JavaSpace/forum
grep -n '会话安全\|V4__add_user_token_version\|LOGIN_MAX_FAILURES\|20 个测试类、161 个用例\|31 个 spec、314 个用例\|登录限流是单实例的' README.md
```

预期：6 条命中；`grep -c '^| 20 个' README.md` 不再命中旧句子（旧句已被替换）。

- [x] **Step 5-2：DEVELOPMENT 更新进度、代码规模、测试基线、迁移与接口清单**

（1）§2.2 迭代轮次表：在第六轮那一行（第 123 行）之后追加：

```markdown
| 第七轮 | 安全加固：JWT 主动失效（`token_version`）+ 登录失败按 IP 限流（429 + `Retry-After`） | 6 | ✅ 已完成 | 2026-10-23 |
```

第 125 行改为：

```markdown
**当前进度：MVP 100% ｜ 已完成 7 轮迭代。** 全部提交见 §8 变更日志；实时总数用 `git rev-list --count HEAD` 查询（本文件不记录精确总数，因为每次提交都会改变它）。
```

（2）§3 标题（第 129 行）改为 `## 3. 当前状态快照（2026-10-23）`。

（3）§3.1 代码规模：运行

```bash
cd /e/JavaSpace/forum
git ls-files 'src/main/java/**/*.java' | wc -l
git ls-files 'src/test/java/**/*.java' | wc -l
git ls-files 'frontend/src/**/*.js' 'frontend/src/**/*.vue' 'frontend/src/**/*.css' | grep -v '\.spec\.js$' | wc -l
git ls-files 'frontend/src/*.spec.js' 'frontend/src/**/*.spec.js' | wc -l
```

注意：`src/test/java/**/*.java` 目前是 21 个文件，其中 20 个测试类 + `util/TestImageFixtures.java` 非测试辅助类；填「测试类数」时用 20，不要直接填 21。

把四个数字填进表格：后端新增 4 个类（`ClientIpResolver`、`LoginRateLimiter`、`TooManyLoginAttemptsException`、`ClockConfig`）、新增 4 个测试类（`AuthInvalidationIntegrationTest`、`LoginRateLimitIntegrationTest`、`ClientIpResolverTest`、`LoginRateLimiterTest`）、前端新增 1 个 spec（`stores/auth.spec.js`）；行数用同法统计：

```bash
git ls-files 'src/main/java/**/*.java' | xargs wc -l | tail -1
git ls-files 'src/test/java/**/*.java' | xargs wc -l | tail -1
```

后端分层那一行（第 140 行）改为：

```markdown
后端分层（文件数）：`entity/` 12、`dto/` 18、`repository/` 9、`controller/` 9、`service/` 11、`security/` 7（+`ClientIpResolver`、`LoginRateLimiter`，`exception/` 另加 `TooManyLoginAttemptsException`）、`config/` 7（+`ClockConfig`）、`util/` 4、`exception/` 4。
```

（4）§3.2 测试基线表：两行分别改为：

```markdown
| 后端 | **161** 个用例 / 20 个测试类 | 全绿（其中 1 个 POSIX 文件权限用例在 Windows 上跳过） |
| 前端 | **314** 个用例 / 31 个 spec | 全绿 |
```

第 152 行后端分布改为（在原有列表里替换三个数字、追加四个新类）：

```markdown
后端测试类分布（合计 161）：`AvatarImageProcessorTest` 27、`AvatarFlowIntegrationTest` 20、`SearchFlowIntegrationTest` 14、`NotificationStreamServiceTest` 12、`NotificationServiceTest` 10、`LoginRateLimiterTest` 9、`RepositoryTests` 9、`TagFlowIntegrationTest` 8、`ClientIpResolverTest` 6、`StreamTicketServiceTest` 6、`CommentFlowIntegrationTest` 6、`AuthInvalidationIntegrationTest` 6、`Stage5FlowIntegrationTest` 5、`SseFlowIntegrationTest` 4、`LoginRateLimitIntegrationTest` 4、`PostFlowIntegrationTest` 4、`AvatarServiceTest` 4、`JwtTokenProviderTest` 4、`ForumApplicationTests` 2、`AuthFlowIntegrationTest` 1。
```

第 154 行前端分布改为（`index` 由 13 变 15、新增 `auth` 3）：

```markdown
前端 spec 分布（合计 314）：`search` 30、`useNotificationStream` 22、`SearchView` 19、`ProfileView` 18、`useTheme` 17、`notifications` 16、`index`（api）15、`markdown` 14、`usePostActions` 13、`tokens` 12、`AdminView` 11、`cards` 10、`UserAvatar` 10、`HeaderSearch` 9、`avatar` 9、`format` 9、`PostListItem` 8、`HomeView` 8、`NotificationsView` 8、`notificationLink` 7、`breakpoint` 7、`viteConfig` 6、`ThemeToggle` 5、`common` 5、`PostDetailView` 5、`TagPostsView` 5、`TagsView` 5、`auth` 3、`useResponsive` 3、`PostEditView` 3、`App` 2。
```

（5）§3.3 迁移脚本那一行（第 161 行）改为：

```markdown
| 迁移脚本 | `V1__init_schema.sql`（建表 + 角色种子）、`V2__notification_comment_id.sql`（通知表加 `comment_id`）、`V3__post_search_trgm_index.sql`（pg_trgm 扩展 + 标题/正文 GIN 部分索引）、`V4__add_user_token_version.sql`（用户表加 `token_version`，默认 0） |
```

（6）§3.4 标题改为 `### 3.4 接口清单（39 个端点）`；`AuthController` 行改为：

```markdown
| `AuthController` | `POST /api/auth/register`、`POST /api/auth/login`、`POST /api/auth/logout`、`GET /api/auth/me`、`PUT /api/auth/profile`、`GET /api/auth/users/{id}` |
```

并在该表格之后的正文里补一段（放在「两个标签端点均为公开 GET」之前）：

```markdown
`POST /api/auth/logout` 需要登录，成功返回 200 与消息「已退出登录」，并让该用户此前签发的所有 JWT 立即失效（`users.token_version` +1）；`POST /api/auth/login` 在密码连续错误达到阈值后返回 **429**，响应头带 `Retry-After: <秒>`，响应体为 `Result.fail(...)`。
```

验证：

```bash
cd /e/JavaSpace/forum
grep -n '第七轮\|161\|314\|V4__add_user_token_version\|39 个端点\|/api/auth/logout' docs/DEVELOPMENT.md | head -20
```

预期：至少 8 条命中，且 `grep -n '133 个用例 / 16 个测试类' docs/DEVELOPMENT.md` 无输出。

- [x] **Step 5-3：DEVELOPMENT 新增 §4.12**

在 §4.11 的 `---`（第 545 行）之前、§4.11 最后一段之后插入下面整节：

```markdown
### 4.12 第七轮：安全加固（JWT 主动失效与登录限流）（2026-10-23）

计划：`docs/superpowers/plans/2026-10-23-round7-security-hardening.md`（6 个任务：数据模型与签发版本 → 请求态校验、登出与封禁 → 登录失败限流 → 前端登出 → 文档 → 全量验证与上线）

设计：`docs/superpowers/specs/2026-10-23-round7-jwt-invalidation-rate-limit-design.md`

目标：解决 §5.1 的 T4、T5 两条技术债——封禁与登出必须让**已签发**的 JWT 立即失效，登录接口必须按客户端 IP 抗暴力破解；公开 GET 的语义与缓存行为保持不变。

新增/修改的文件：

| 位置 | 文件 | 要点 |
| --- | --- | --- |
| 后端 | `db/migration/V4__add_user_token_version.sql` | `users` 加 `token_version BIGINT NOT NULL DEFAULT 0`，历史用户与旧 token 都从 0 开始 |
| 后端 | `entity/User.java` | `@Column(name = "token_version", nullable = false) @Builder.Default private long tokenVersion = 0L` |
| 后端 | `security/JwtTokenProvider.java` | `createToken(..., long tokenVersion)` 写 `ver` 声明；`static long tokenVersion(Claims)` 用 `Number` 读（jjwt 对小数返回 `Integer`），缺声明按 0 |
| 后端 | `repository/UserRepository.java` | 只读投影 `UserTokenState { getId / getStatus / getTokenVersion }` + `findTokenStateById`，不加载 roles 集合 |
| 后端 | `security/JwtAuthenticationFilter.java` | 解析签名后回查一次库：用户不存在 / `BANNED` / `ver` 与库中版本不符 → 不建立认证态 |
| 后端 | `service/AuthService.java`、`controller/AuthController.java` | `logout(userId)` 在事务内 `tokenVersion + 1`（脏检查即可，不 save）；`POST /api/auth/logout` 需要登录 |
| 后端 | `service/AdminUserService.java` | `case BANNED` 分支在 `save` 前同样 `tokenVersion + 1`；`MUTED`/`ACTIVE` 不动版本 |
| 后端 | `config/SecurityConfig.java` | `/api/auth/logout` 与 `/api/auth/me`、`/api/auth/profile` 并列 `authenticated()` |
| 后端 | `security/ClientIpResolver.java` | 非环回地址直接信任；环回（nginx 反代）时取 `X-Real-IP`，非法再退 `X-Forwarded-For` 首个合法字面量；全程正则校验，不做 DNS |
| 后端 | `security/LoginRateLimiter.java`、`config/ClockConfig.java` | `ConcurrentHashMap<String, Window>`（`Deque<Long> failures` + `lockedUntil`），`Clock` 注入；`@Scheduled` 定期清理过期窗口 |
| 后端 | `exception/TooManyLoginAttemptsException.java`、`exception/GlobalExceptionHandler.java` | 锁定抛异常 → 429 + `Retry-After` + `Result.fail("登录失败次数过多，请 X 分钟后再试")` |
| 后端 | `controller/AuthController.java` | `login` 先 `check(ip)` 再验密；`BadCredentialsException` 才 `recordFailure`，成功 `recordSuccess`（账号不存在也算凭据失败） |
| 后端 | `application.yml` | `app.security.login.{max-failures,window-ms,lock-ms,cleanup-interval-ms}`，全部可用 `LOGIN_*` 覆盖 |
| 前端 | `api/index.js`、`stores/auth.js` | `authApi.logout()`；`logout()` 改异步、尽力而为地调后端，`finally` 里统一清态 |
| 前端 | `stores/auth.spec.js` | 新增 3 例：成功清态、后端失败仍清态、未登录不抛错 |
| 文档 | `README.md`、`docs/DEVELOPMENT.md`、`docs/deployment/README.md` | 功能、迁移、配置项、测试基线、接口清单、上线记录 |

关键取舍：

- **`token_version` 而非黑名单**：单实例部署、不引入 Redis；用户表的一行版本号既能让登出/封禁立即生效，又天然支持「一次性让某用户全部设备下线」。代价是每个带 token 的请求多一次主键查询（只取 3 列），以及角色变更仍不即时（本轮不处理）。
- **版本校验放在过滤器而不是业务 Service**：认证态只在请求入口建立一次，后续 `@PreAuthorize` 与 `SecurityUtils.currentUserId()` 都读同一份认证态，不会出现「一半请求看到新状态、一半看到旧状态」。
- **旧 token 缺 `ver` 按 0**：上线时不必强制所有人重新登录；只要用户没登出/被封禁，版本仍是 0，旧 token 继续可用。
- **计数放在 Controller 而不是认证过滤器**：只有 `BadCredentialsException`（用户名不存在或密码错）才计入失败，其它 401（token 过期、格式错）不污染登录失败窗口；锁定判断发生在验密之前，锁定期间不消耗 CPU 做 BCrypt。
- **信任 `X-Real-IP` 的前提是 nginx 覆盖写**：`deploy/nginx/forum.conf` 两个 `location` 都写了 `proxy_set_header X-Real-IP $remote_addr;`，客户端自带的同名头会被覆盖；因此只在 `remoteAddr` 是环回地址时才读它，直连 8086 无法伪造成任意 IP。
- **限流用内存窗口而不是 Bucket4j**：与「不新增依赖」的既有约定一致，5 次/15 分钟的粗粒度窗口不需要令牌桶的精度；窗口淘汰靠时间戳裁剪 + 定时清理，`MutableClock` 让单测无需 sleep。

测试与验证：后端新增 `AuthInvalidationIntegrationTest`（真实登出/封禁/版本不符/旧 token/禁言仍可用，6 例）、`ClientIpResolverTest`（6 例）、`LoginRateLimiterTest`（9 例）、`LoginRateLimitIntegrationTest`（429 与 `Retry-After`、锁定内正确密码仍 429、其它 IP 不受影响、成功登录清零，4 例）；`JwtTokenProviderTest` 加 `ver` 断言、`RepositoryTests` 加默认值断言。前端 `auth.spec.js` 3 例、`index.spec.js` 加 2 例（登出请求路径、429 不清登录态）。基线数字见 §3.2。

已知限制：见 §6「登录限流是单实例的」与 §5.2 的候选「限流改共享存储」。

上线验收（2026-10-23）：

- 推送 `<hash>` 后 Jenkins Build **#<N>** **SUCCESS**，`forum-backend` 重建后 `Up (healthy)`。
- Flyway 自动执行 V4：`flyway_schema_history` 最新记录 `version=4 / description=add user token version / success=t`；`\d users` 显示 `token_version bigint not null default 0`。
- 冒烟：管理员 `POST /api/auth/logout` 返回 200，旧 token 再访问 `/api/auth/me` 返回 401；新建用户被管理员封禁后其 token 立即 401；`X-Real-IP: 203.0.113.9` 连续 5 次错密码后第 6 次返回 **429** 且响应头 `Retry-After: 900`，锁定期间用正确密码仍 429。
- 全量测试：后端 20 个测试类 / 161 个用例（1 例 POSIX 权限用例在 Windows 上跳过）；前端 31 个 spec / 314 个用例；`VITE_BASE_PATH=/forum/ pnpm build` 通过。
```

> `<hash>`/`<N>` 在 Task 6 的 Step 6-7 回填，与本轮其它计划一致，属执行期事实。

- [x] **Step 5-4：DEVELOPMENT 关闭 T4/T5、补候选、决策与变更日志**

（1）§5.1：T4、T5 两行（第 556、557 行）改为：

```markdown
| T4 | ~~**JWT 无法主动失效**~~ | 已关闭 | 2026-10-23 第七轮：`users.token_version` + 过滤器回查，登出与封禁各 +1，旧 token 立即 401（见 §4.12）。角色变更仍不即时，属遗留的小口子 |
| T5 | ~~**登录接口无速率限制**~~ | 已关闭 | 2026-10-23 第七轮：按客户端 IP 的失败窗口 + 锁定，429 带 `Retry-After`（见 §4.12）。计数在单实例内存里，多实例需换共享存储（见 §5.2） |
```

（2）§5.2 标题与表尾：

```markdown
### 5.2 候选功能（第七轮新增「限流共享存储」一项）
```

在候选表末尾（第六轮 SSE 那一行之后）追加：

```markdown
| 限流改共享存储（多实例） | 中 | 中 | 当前 `LoginRateLimiter` 的失败计数在单实例内存，多实例部署时各算各的、重启清零（§4.12、§6）。改法：把 `Window` 换成 Redis 的 `INCR`+`EXPIRE` 或 Bucket4j 的分布式后端，接口保持不变 |
```

并把第 582 行的收尾段改为：

```markdown
第三轮已取前两项（见 §4.8），第四轮已取头像上传与深色模式（见 §4.9），第五轮已取全文搜索（见 §4.10），第六轮已取实时通知（见 §4.11），第七轮不再新增业务功能、只做安全加固（见 §4.12）；「限流改共享存储」是加固后新暴露的运维型候选，等到真的多实例部署时再取。后续新功能需重新评估价值与工作量，按同样的「审计 → 计划 → 逐任务执行 → 验证 → 提交」流程推进。
```

（3）§7 决策表：在最后一行（「实时通知降级」，第 628 行）之后追加：

```markdown
| Token 主动失效 | **用户级 `token_version` 版本号**，不用 Redis 黑名单 | 单实例部署、不新增中间件；登出/封禁各 +1，过滤器每请求比一次即失效，还能一次性踢掉某用户全部设备；代价是每请求多一次只取 3 列的主键查询 |
| 登录限流位置 | **Controller 层计数 + 内存滑动窗口**，不放认证过滤器 | 只统计真正的凭据错误（`BadCredentialsException`），token 过期/格式错的 401 不占用失败额度；锁定判断在 BCrypt 之前，被锁时不做无谓的哈希计算 |
```

（4）§8 变更日志：在表格最上方（第 643 行那条 `2026-10-22 ... 本次提交` 之前）插入 6 行：

```markdown
| 2026-10-23 | docs | 第七轮安全加固上线验收（JWT 主动失效与登录限流、Build #<N>）与变更日志回填 | 本次提交 |
| 2026-10-23 | docs | 记录第七轮安全加固（README、开发记录、部署手册、测试基线） | `<hash5>` |
| 2026-10-23 | feat | 第七轮前端：登出调用后端失效接口，失败也清本地态 | `<hash4>` |
| 2026-10-23 | feat | 第七轮后端：登录失败按 IP 限流并返回 429 | `<hash3>` |
| 2026-10-23 | feat | 第七轮后端：封禁与登出即时失效旧 token | `<hash2>` |
| 2026-10-23 | feat | 第七轮后端：用户 token 增加版本号并写入 JWT | `<hash1>` |
```

验证：

```bash
cd /e/JavaSpace/forum
grep -n 'T4 |\|T5 |\|限流改共享存储\|token_version 版本号\|第七轮后端：用户 token' docs/DEVELOPMENT.md
```

预期：至少 5 条命中；`grep -n 'JWT 无法主动失效\*\*' docs/DEVELOPMENT.md` 仍命中（删除线包裹的原文保留，便于追溯）。

- [x] **Step 5-5：部署手册新增「登录限流与真实客户端 IP」并补排障**

在 `docs/deployment/README.md` 的 SSE 小节最后一行（`> stream 端点的鉴权在 ticket 内部完成…`，第 189 行）之后、`## 6. 排障` 之前插入：

````markdown
### 登录限流与真实客户端 IP（V4）

V4 在 `forum-backend` 启动时由 Flyway 自动执行，给 `users` 加 `token_version`；后端过滤器每次带 token 的请求都会回查该列，因此封禁/登出立即生效（响应仍是既有的 401 `{"success":false,"message":"未登录或登录已过期"}`）。

```bash
# 1) 迁移与列类型
sudo -u pi podman exec postgresql psql -U postgres -d javaforum -tAc \
  "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1"
# 期望：4|add user token version|t
sudo -u pi podman exec postgresql psql -U postgres -d javaforum -tAc \
  "SELECT data_type, is_nullable, column_default FROM information_schema.columns WHERE table_name='users' AND column_name='token_version'"
# 期望：bigint|NO|0

# 2) 登出立即失效：拿 token → 登出 → 旧 token 立刻 401
TOKEN=$(curl -fsS -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123456"}' \
  http://127.0.0.1:8086/api/auth/login | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8086/api/auth/me                              # 期望 200
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8086/api/auth/logout                          # 期望 200
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8086/api/auth/me                              # 期望 401

# 3) 限流：换一个从未失败过的 IP，连续错密码到 429，并确认 Retry-After
for i in 1 2 3 4 5; do
  curl -s -o /dev/null -w '%{http_code} ' -H 'Content-Type: application/json' \
    -H 'X-Real-IP: 203.0.113.9' \
    -d '{"username":"admin","password":"wrong-password"}' \
    http://127.0.0.1:8086/api/auth/login
done; echo
# 期望：401 401 401 401 401（第 5 次错密码把窗口打满）
curl -s -D - -o /dev/null -H 'Content-Type: application/json' -H 'X-Real-IP: 203.0.113.9' \
  -d '{"username":"admin","password":"admin123456"}' \
  http://127.0.0.1:8086/api/auth/login | grep -Ei '^(HTTP/|retry-after)'
# 期望：HTTP/1.1 429 与 Retry-After: 900（锁定内正确密码同样被拒）

# 4) 其它 IP 不受影响（经 nginx 时必须带 Host，nginx 会用自己的 $remote_addr 覆盖 X-Real-IP）
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: raspi' -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123456"}' \
  http://127.0.0.1/forum/api/auth/login                      # 期望 200
```

> 直连 `127.0.0.1:8086` 时 `remoteAddr` 是环回地址，后端会读 `X-Real-IP`，所以上面的 `-H 'X-Real-IP: ...'` 能模拟任意客户端；经 nginx 时该头被覆盖为真实 `$remote_addr`，客户端无法伪造。若不希望影响本机管理员，冒烟可改用一次性用户（见计划 Task 6）。
````

在排障表末尾追加两行：

```markdown
| 登录返回 429 | 该 IP 的失败窗口已打满：等 `Retry-After` 秒（默认 900）或重启容器（内存计数清零）；确认不是 NAT 后整栋楼共用一个出口 IP 导致互相牵连 |
| 所有用户看到同一个 IP / 限流失效 | 反代链路上 `X-Real-IP` 被覆盖或缺失：确认 snippet 的两个 `location` 都有 `proxy_set_header X-Real-IP $remote_addr;`；直连 8086 时后端只认 `remoteAddr` 之外的 `X-Real-IP`，需自行带上 |
```

在「已知限制」末尾追加：

```markdown
- **登录限流的计数在进程内**：容器重启即清零，多实例部署时各实例独立计数；如需跨实例一致，需按 `docs/DEVELOPMENT.md` §5.2 换成共享存储。
```

验证：

```bash
cd /e/JavaSpace/forum
grep -n '登录限流与真实客户端 IP\|Retry-After\|token_version' docs/deployment/README.md
```

预期：至少 6 条命中。

- [x] **Step 5-6：勾选计划复选框并提交**

把本计划 Task 1–5 的全部步骤勾上（Task 6 保持未勾，等上线回填后再勾）：

```bash
cd /e/JavaSpace/forum
sed -i -E 's/^- \[ \] \*\*Step ([1-5])-/- [x] **Step \1-/' docs/superpowers/plans/2026-10-23-round7-security-hardening.md
grep -c '^- \[x\]' docs/superpowers/plans/2026-10-23-round7-security-hardening.md
grep -c '^- \[ \] \*\*Step 6-' docs/superpowers/plans/2026-10-23-round7-security-hardening.md
```

预期：第一条为 29（Task 1–5 的步骤数）；第二条为 7（Task 6 尚未执行，仍带 `[ ]`）。

```bash
cd /e/JavaSpace/forum && git status --short && git diff --stat
```

预期：`M README.md`、`M docs/DEVELOPMENT.md`、`M docs/deployment/README.md`、`M docs/superpowers/plans/2026-10-23-round7-security-hardening.md`；`git diff --stat` 显示 4 个文件。

```bash
cd /e/JavaSpace/forum
git add README.md docs/DEVELOPMENT.md docs/deployment/README.md \
        docs/superpowers/plans/2026-10-23-round7-security-hardening.md
git commit -m "docs: 记录第七轮安全加固（JWT 主动失效与登录限流）"
```

预期：`4 files changed, ...`；`git status --short` 为空。

## Task 6：全量验证与上线

**Files:**
- Modify: `docs/DEVELOPMENT.md`（§4.12 的 `<hash>`/`Build #<N>`、§8 的 6 条哈希）
- Modify: `docs/superpowers/plans/2026-10-23-round7-security-hardening.md`（勾选 Task 6）
- Test: 全量后端 + 前端 + 构建 + Jenkins + raspi 冒烟

> 本任务不产生新代码，只做验证、发布与回填。所有 `<hash>`/`<N>` 都是执行期事实，由下面的命令产出后再写回文档。

- [x] **Step 6-1：后端全量测试**

```bash
cd /e/JavaSpace/forum && mvn -B clean test 2>&1 | tail -25
```

预期：

```text
[INFO] Tests run: 161, Failures: 0, Errors: 0, Skipped: 1
[INFO] BUILD SUCCESS
```

`Skipped: 1` 是 `AvatarImageProcessorTest` 里那个 Windows 上无法验证 POSIX 权限的用例。若数字不等于 161，先看是不是某个集成测试类没被 surefire 拾取（类名必须以 `Test` 结尾），再核对 `git status --short` 是否干净。

```bash
cd /e/JavaSpace/forum && git status --short
```

预期：无输出。

- [x] **Step 6-2：前端全量测试与生产构建**

```bash
cd /e/JavaSpace/forum/frontend && pnpm test 2>&1 | tail -8
```

预期：

```text
 Test Files  31 passed (31)
      Tests  314 passed (314)
```

```bash
cd /e/JavaSpace/forum/frontend && MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build 2>&1 | tail -5
```

预期：`✓ built in ...`（`dist/` 已 gitignore，不会进提交）。

```bash
cd /e/JavaSpace/forum && git status --short
```

预期：无输出。

- [x] **Step 6-3：推送 master（含网络受限时的回退）**

```bash
cd /e/JavaSpace/forum
git log --oneline -5
```

预期：5 条本轮提交（Task 1–5），最上面是 `docs: 记录第七轮安全加固（JWT 主动失效与登录限流）`。

```bash
cd /e/JavaSpace/forum && git -c http.version=HTTP/1.1 push origin master
```

预期：`master -> master`，5 个提交上传。`http.version=HTTP/1.1` 是为了绕开这台机器上 `HTTP/2` 偶发的 `Connection was reset`。

若仍然失败（本机网络对 `github.com:443` 时通时断，典型报错 `Could not resolve host: github.com` 或 `Failed to connect to github.com port 443`），走代理 + 凭据管理器里的 token：

```bash
cd /e/JavaSpace/forum
TOKEN=$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill | sed -n 's/^password=//p')
test -n "$TOKEN" && echo "token length: ${#TOKEN}"
git -c http.version=HTTP/1.1 push \
  "https://x-access-token:${TOKEN}@ghfast.top/https://github.com/ScholarOfDeepDarkFantasy/forum.git" \
  master:master
```

预期：`master -> master`。注意三点：token 只出现在命令行、不写进 `git remote`（用完当前 shell 即丢弃）；`gh-proxy.org` 只用于 Jenkins 侧拉取代码，**不能推送**；`ghfast.top` 若也拒绝写入，改用 SSH（`git@github.com:ScholarOfDeepDarkFantasy/forum.git`）或换网络，不要改写历史。

- [x] **Step 6-4：等 Jenkins 构建并做 raspi 冒烟**

```bash
PREV=$(curl -fsS 'http://raspi:8080/job/javaforum/lastBuild/api/json' | sed -n 's/.*"number":\([0-9]*\).*/\1/p')
echo "PREV=$PREV"
for _ in $(seq 1 30); do
  sleep 10
  CUR=$(curl -fsS 'http://raspi:8080/job/javaforum/lastBuild/api/json' | sed -n 's/.*"number":\([0-9]*\).*/\1/p')
  if [ "$CUR" -gt "$PREV" ]; then
    RESULT=$(curl -fsS "http://raspi:8080/job/javaforum/${CUR}/api/json" | grep -o '"result":"[A-Z]*"')
    echo "build=$CUR $RESULT"
    case "$RESULT" in *SUCCESS*) break ;; *) exit 1 ;; esac
  fi
done
```

预期：轮询 `pollSCM` 最长 5 分钟，循环内出现 `build=11 "result":"SUCCESS"`（编号以实际为准）。

冒烟用 paramiko helper（`python /tmp/forum-ssh.py "<远端命令>"`，登录 raspi 的 pi 账号执行），三条最关键的先跑：

```bash
python /tmp/forum-ssh.py 'curl -fsS http://127.0.0.1:8086/api/health'
```

预期：`{"success":true,"data":{"status":"UP",...}}`。

```bash
python /tmp/forum-ssh.py 'curl -s -o /dev/null -w "%{http_code}\n" -H "Host: raspi" http://127.0.0.1/forum/api/health'
```

预期：`200`（经 nginx，带 `Host: raspi`，否则落到 default server）。

```bash
python /tmp/forum-ssh.py 'sudo -u pi podman exec postgresql psql -U postgres -d javaforum -tAc "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1"'
```

预期：`4|t`。

其余步骤（登出失效、封禁失效、限流 429 与 `Retry-After: 900`、锁定内正确密码仍 429、`token_version` 列定义）逐条见 **附录 B**，全部通过后再进入 Step 6-5。

- [x] **Step 6-5：回填 §4.12 验收与 §8 哈希**

```bash
cd /e/JavaSpace/forum && git log --format='%h %s' -5
```

预期：5 行，从下到上依次对应 Task 1 → Task 5。按下表把哈希填进 `docs/DEVELOPMENT.md` §8 新增的 6 行（第 1 行「本次提交」保持不变，其余 5 行）：

```text
hash1 = Task 1  feat: 为用户 token 增加版本号并写入 JWT
hash2 = Task 2  feat: 让封禁与登出即时失效旧 token
hash3 = Task 3  feat: 登录失败按 IP 限流并返回 429
hash4 = Task 4  feat: 前端登出调用后端失效接口
hash5 = Task 5  docs: 记录第七轮安全加固（JWT 主动失效与登录限流）
```

同时把 §4.12「上线验收」里的 `<hash>` 换成 Task 5 的 `hash5`（即「包含 §4.12 的那次文档提交」），`Build #<N>` 换成 Step 6-4 的 `CUR`。

验证：

```bash
cd /e/JavaSpace/forum
grep -n '<hash>\|<N>\|<hash[0-9]>' docs/DEVELOPMENT.md
```

预期：只剩 §8 第一行的「本次提交」不含哈希，无任何 `<...>` 尖括号占位。

- [x] **Step 6-6：提交回填、二次推送与第二次构建**

```bash
cd /e/JavaSpace/forum && git status --short && git diff --stat
```

预期：`M docs/DEVELOPMENT.md`，一行改动统计。

```bash
cd /e/JavaSpace/forum
git add docs/DEVELOPMENT.md
git commit -m "docs: 回填第七轮提交哈希与上线验收"
git -c http.version=HTTP/1.1 push origin master
```

预期：第二次构建被 `pollSCM` 拾起（编号 +1），`"result":"SUCCESS"`。若第一次推送用了 `ghfast.top`，这次同样用带 token 的 URL 推送。

- [x] **Step 6-7：勾选 Task 6 并输出最终报告**

```bash
cd /e/JavaSpace/forum
sed -i -E 's/^- \[ \] \*\*Step 6-/- [x] **Step 6-/' docs/superpowers/plans/2026-10-23-round7-security-hardening.md
sed -i -E 's/^- \[ \] (Task [1-6])/- [x] \1/' docs/superpowers/plans/2026-10-23-round7-security-hardening.md
grep -c '^- \[x\]' docs/superpowers/plans/2026-10-23-round7-security-hardening.md
grep -c '^- \[ \]' docs/superpowers/plans/2026-10-23-round7-security-hardening.md
```

预期：前者为全部步骤数 29 + 7 = 36，加上 §11 交接清单的 6 项，共 **42**；后者 `0`。

```bash
cd /e/JavaSpace/forum
git add docs/superpowers/plans/2026-10-23-round7-security-hardening.md
git commit -m "docs: 勾选第七轮计划终态"
git -c http.version=HTTP/1.1 push origin master
```

最终报告必须包含：两次 Build 编号与结果、冒烟每条的实测码、`flyway_schema_history` 与 `users.token_version` 的查询结果、后端/前端测试数量、4 个功能提交的哈希。

---

## 7. 新增 / 修改文件清单

### 7.1 新增

| 文件 | 行数（约） | 说明 |
| --- | --- | --- |
| `src/main/resources/db/migration/V4__add_user_token_version.sql` | 12 | `users.token_version` 列 + 注释 |
| `src/main/java/org/song/forum/security/ClientIpResolver.java` | 75 | 环回判定 + `X-Real-IP`/`X-Forwarded-For` 字面量校验 |
| `src/main/java/org/song/forum/security/LoginRateLimiter.java` | 105 | 内存滑动窗口 + 锁定 + 定时清理 |
| `src/main/java/org/song/forum/exception/TooManyLoginAttemptsException.java` | 22 | 携带 `retryAfterSeconds` |
| `src/main/java/org/song/forum/config/ClockConfig.java` | 16 | `Clock.systemUTC()`，让限流可测 |
| `src/test/java/org/song/forum/controller/AuthInvalidationIntegrationTest.java` | 235 | 登出/封禁/版本不符/旧 token/禁言 6 例 |
| `src/test/java/org/song/forum/controller/LoginRateLimitIntegrationTest.java` | 150 | 429、`Retry-After`、IP 隔离、成功清零 4 例 |
| `src/test/java/org/song/forum/security/ClientIpResolverTest.java` | 95 | 6 例（环回、真实 IP、非法头、XFF 回退、IPv6、超长） |
| `src/test/java/org/song/forum/security/LoginRateLimiterTest.java` | 205 | 9 例（阈值、窗口、锁定、成功清零、清理、多 IP 隔离） |
| `frontend/src/stores/auth.spec.js` | 70 | 3 例（成功/失败/未登录） |
| `docs/superpowers/plans/2026-10-23-round7-security-hardening.md` | 本文件 | 计划 |

### 7.2 修改

| 文件 | 改动 |
| --- | --- |
| `src/main/java/org/song/forum/entity/User.java` | `tokenVersion` 字段 |
| `src/main/java/org/song/forum/security/JwtTokenProvider.java` | `createToken` 4 参写 `ver`；新增 `tokenVersion(Claims)` |
| `src/main/java/org/song/forum/security/JwtAuthenticationFilter.java` | 注入 `UserRepository`，回查状态与版本 |
| `src/main/java/org/song/forum/repository/UserRepository.java` | `UserTokenState` 投影 + `findTokenStateById` |
| `src/main/java/org/song/forum/service/AuthService.java` | `buildAuthResponse` 传版本；新增 `logout` |
| `src/main/java/org/song/forum/controller/AuthController.java` | 新增 `POST /logout`；`login` 接 IP 与限流器 |
| `src/main/java/org/song/forum/service/AdminUserService.java` | `case BANNED` 递增版本 |
| `src/main/java/org/song/forum/config/SecurityConfig.java` | `/api/auth/logout` 需认证 |
| `src/main/java/org/song/forum/exception/GlobalExceptionHandler.java` | 429 + `Retry-After` 处理器 |
| `src/main/resources/application.yml` | `app.security.login.*` 四项 |
| `src/test/java/org/song/forum/security/JwtTokenProviderTest.java` | 4 参调用 + `ver` 断言 |
| `src/test/java/org/song/forum/repository/RepositoryTests.java` | 默认版本 0 断言 |
| `frontend/src/api/index.js` | `authApi.logout` |
| `frontend/src/api/index.spec.js` | 引入 `authApi`；登出路径与 429 不清态 2 例 |
| `frontend/src/stores/auth.js` | `logout` 异步 + `clearAuth()` |
| `README.md`、`docs/DEVELOPMENT.md`、`docs/deployment/README.md` | 功能、迁移、配置、基线、接口、上线记录 |

---

## 8. 测试数量预期

| 层 | 现状 | 新增 | 预计 |
| --- | --- | --- | --- |
| 后端用例 | 133 | +3（Task 1）+ 6（Task 2）+ 19（Task 3） | **161** |
| 后端测试类 | 16 | +4 | **20** |
| 前端 spec | 30 | +1 | **31** |
| 前端用例 | 309 | +3（`auth.spec.js`）+ 2（`index.spec.js`） | **314** |
| 接口端点 | 38 | +1（`POST /api/auth/logout`） | **39** |

逐任务核对：Task 1 后 136、Task 2 后 142、Task 3 后 161；前端 Task 4 后 314。

---

## 9. 风险与回滚

| 风险 | 触发条件 | 处置 |
| --- | --- | --- |
| 旧 token 在用户登出被误伤 | `ver` 声明解析为 `Integer` 而非 `Long` | 统一走 `JwtTokenProvider.tokenVersion(Claims)`（内部按 `Number` 读），并有用例锁定；若线上仍 401，先查日志确认版本值，不要急着回滚 |
| 每请求多一次主键查询拖慢接口 | 高并发 + 慢库 | 只取 3 列、主键命中；`app` 层无缓存是为了正确性，若压测显示瓶颈，再加 5 秒本地缓存并以版本号失效（列为后续候选） |
| NAT 出口误伤整栋楼 | 多人共用公网 IP | 把 `LOGIN_MAX_FAILURES` 调大（如 20）并重启容器；彻底解法见 §5.2 的共享存储候选 |
| 限流计数占内存 | 被大量伪造 IP 刷 | `cleanupExpired` 每 60 秒清理过期窗口；IP 字面量正则限制长度 ≤45，且 `ConcurrentHashMap` 的 key 数量与真实客户端数同阶 |
| V4 迁移失败 | 生产库 `users` 表被手工改过 | 迁移只加列，不改既有列；失败时看 `flyway_schema_history` 与容器日志，修好后重启容器让它重跑，不要手工 `flyway repair` 掩盖 |

回滚步骤（按影响面从小到大）：

```bash
# 1) 只关限流（不停机）：把阈值调到极大后重启容器
#    .env 追加 LOGIN_MAX_FAILURES=100000，再 sudo -u pi podman-compose -p forum up -d
# 2) 回滚代码：revert 本轮 5 个提交（保留 V4 列，旧代码不认识它也无害）
cd /e/JavaSpace/forum
git revert --no-edit <hash5> <hash4> <hash3> <hash2> <hash1>
git -c http.version=HTTP/1.1 push origin master
# 3) 列本身不必删：token_version 有默认值 0，旧代码的 INSERT/UPDATE 不受影响
```

> 顺序必须是「先关限流、再回滚代码」：回滚后限流配置失效，若此时仍有攻击流量，登录会被 BCrypt 拖满 CPU。

## 10. Self-Review

### 10.1 spec 覆盖

| spec 小节 | 计划落点 | 覆盖方式 |
| --- | --- | --- |
| §5 数据模型与 V4 | Step 1-2、Step 1-3 | SQL 全文 + 实体字段 + `RepositoryTests` 默认值断言 |
| §6.1 签发带 `ver` | Step 1-4 | `createToken(..., long)` + `tokenVersion(Claims)` + 调用点 |
| §6.2 请求态校验 | Step 2-2、Step 2-3 | 投影查询 + 过滤器回查；3 个否定条件各有用例 |
| §6.3 登出 | Step 2-4 | Service/Controller/SecurityConfig 三处 + 集成用例 |
| §6.4 封禁递增 | Step 2-5 | `case BANNED` + 集成用例 |
| §6.5 语义与状态码 | Step 2-1、Step 3-4 | 401 沿用 entry point；429 + `Retry-After` + `Result.fail` |
| §7.1 限流器 | Step 3-3、Step 3-4 | 9 个单元用例覆盖窗口/锁定/清理 |
| §7.2 配置 | Step 3-6 | yaml 四项 + `LOGIN_*` 覆盖 + README 表格 |
| §7.3 IP 解析 | Step 3-1、Step 3-2 | 6 个用例（环回、真实 IP、非法头、XFF、IPv6、超长） |
| §7.4 Controller 接入 | Step 3-6 | `check` 先行、只记 `BadCredentialsException`、成功清零 |
| §7.5 429 响应 | Step 3-4 | 异常类 + handler + 集成测试断言响应头 |
| §8 前端 | Step 4-1 ~ Step 4-4 | API + store + 3 个 store 用例 + 2 个 API 用例 |
| §10 测试策略 | Task 1–4 的 RED/GREEN 步骤 | 每个测试文件先失败再实现 |
| §12 风险 | §9、§6 已知限制、§5.2 候选 | 逐条给出处置或后续候选 |

### 10.2 占位符扫描

```bash
cd /e/JavaSpace/forum
grep -n 'TBD\|TODO\|待填\|待补充\|类似 Task\|此处省略\|xxx' docs/superpowers/plans/2026-10-23-round7-security-hardening.md
grep -n '`<hash>`\|<N>\|<hash[0-9]>' docs/superpowers/plans/2026-10-23-round7-security-hardening.md
```

预期：第一条无输出（代码块里的 `...` 均为编译/日志截断，已用 `tail`/`2>&1 | tail` 明确说明，不属于占位符）；第二条只命中 §4.12 的 `<hash>`/`Build #<N>`（计划阶段留白，Task 6 回填）与 Task 6 中说明回填规则的三处。

### 10.3 类型与命名一致性

| 概念 | 数据库 | Java | JSON / HTTP | 配置 |
| --- | --- | --- | --- | --- |
| token 版本 | `users.token_version BIGINT NOT NULL DEFAULT 0` | `private long tokenVersion` | JWT 声明 `ver` | — |
| 用户状态 | `users.status VARCHAR(20)` | `UserStatus`（`ACTIVE/MUTED/BANNED`） | 字符串 | — |
| 失败阈值 | — | `LoginRateLimiter.maxFailures` | — | `app.security.login.max-failures` / `LOGIN_MAX_FAILURES` |
| 失败窗口 | — | `windowMs` | — | `window-ms` / `LOGIN_WINDOW_MS` |
| 锁定时长 | — | `lockMs` | — | `lock-ms` / `LOGIN_LOCK_MS` |
| 清理间隔 | — | `cleanupExpired()` 的调度周期 | — | `cleanup-interval-ms` / `LOGIN_CLEANUP_INTERVAL_MS` |
| 重试秒数 | — | `TooManyLoginAttemptsException.retryAfterSeconds` | `Retry-After` 头 | — |

交叉检查项：`JwtTokenProvider.createToken` 的四处调用（`AuthService.register`、`AuthService.login` 经 `buildAuthResponse`、`JwtTokenProviderTest` 两处）全部是 4 参；`UserTokenState.getTokenVersion()` 返回 `long`，投影方法名 `getTokenVersion` 必须与 `select ... as tokenVersion` 的别名一致；`findTokenStateById` 的 `@Param("id")` 与 JPQL 的 `:id` 一致。

### 10.4 测试可运行性

- 每个 `@SpringBootTest` 用独立 H2 库名（`auth-invalidation-test`、`login-rate-limit-test`），`flyway.enabled=false` + `ddl-auto=create-drop`，类之间不共享数据，可单独运行也可全量运行。
- 集成测试用户名固定（如 `inval_valid`、`rate_limited`）；每个测试类用独立 H2 库名（`auth-invalidation-test`、`login-rate-limit-test`），类间不共享数据，重复运行不会撞唯一约束。
- 限流单测注入 `MutableClock`，不 `sleep`；集成测试通过 `properties` 把阈值压到 3，避免真的等 15 分钟。
- `LoginRateLimitIntegrationTest` 的每个用例用不同 `X-Real-IP`（`198.51.100.x`、`203.0.113.x`，均为 RFC 5737 文档地址段），互不污染内存窗口。
- 429 用例断言 `Retry-After` 是 `60`（由 `lock-ms=60000` 推出），不是写死的 900（900 是生产默认值）。

### 10.5 实现顺序与提交粒度

依赖关系：Task 1（列 + 声明）→ Task 2（读列 + 失效）→ Task 3（与 token 无关，可并行）→ Task 4（依赖 Task 2 的接口）→ Task 5/6（文档与上线）。每个 Task 一次提交、提交信息逐字给出；Task 6 额外产生两次「回填/勾选」提交，这是仓库既有惯例（第六轮同样如此）。

### 10.6 spec 中已裁决的模糊点

1. `cleanup-interval-ms` 默认值：spec §7.2 写 300000，用户任务书写 60000；取 **60000**（清理只是回收内存，频率高无害），并在 §0 与本表记录。
2. 环境变量名：spec 用 `LOGIN_FAIL_WINDOW_MS`，任务书用 `LOGIN_WINDOW_MS`；统一取 **`LOGIN_WINDOW_MS`**，因为仓库既有命名没有 `FAIL_` 前缀。
3. `@Scheduled` 方法必须无参无返回才能被代理：拆成 `cleanupScheduled()`（`@Scheduled`，只调用下面的方法）与 `int cleanupExpired()`（可单测、返回清理条数）。
4. 旧 token 无 `ver`：**接受**（按 0），上线不强制重登；一旦该用户登出/被封禁，版本变 1，旧 token 立即失效。
5. 过滤器是否加载 roles：**不加载**。认证态里的 roles 来自 token 声明（既有行为），本轮只补「用户是否还存在 / 是否被封 / 版本是否一致」三项；角色变更不即时属已知限制。

---

## 11. Execution Handoff

- [x] Task 1：数据模型与签发版本
- [x] Task 2：请求态校验、登出与封禁
- [x] Task 3：登录失败限流
- [x] Task 4：前端登出
- [x] Task 5：文档
- [x] Task 6：全量验证与上线

计划完成后，会话应交接给新的执行者（`docs/superpowers/plans/` 下的文件即交接物），执行者按 Task 顺序逐任务「读计划 → 写测试 → 跑红 → 实现 → 跑绿 → 提交」。

执行时最容易写错的 6 个点：

1. `UserRepository` 的投影查询漏写 `as` 别名（`select u.id as id, ...`），Spring Data 会抛 `IllegalArgumentException: Not a managed type` 或返回 null。
2. jjwt 把小整数读成 `Integer`，`claims.get("ver", Long.class)` 会抛 `RequiredTypeException`；必须走 `Number#longValue()`。
3. `JwtAuthenticationFilter` 用 `@RequiredArgsConstructor`：新增 `UserRepository` 字段必须 `private final`，否则不会被注入。
4. `AuthService.logout` 只 `setTokenVersion(...)` 不 `save`，靠 `@Transactional` 脏检查；漏 `@Transactional` 会静默不生效。
5. `LoginRateLimiter.check` 抛异常时**不要**先 `recordFailure`，否则「锁定中继续尝试」会无限延长锁定；只有验密失败才计数。
6. 前端 `logout()` 里 `try/catch/finally` 的顺序：`clearAuth()` 必须在 `finally`，否则 401 时本地态清不掉，用户看起来「登出失败」。

---

## 附录 A：本轮提交信息（逐字）

```text
feat: 为用户 token 增加版本号并写入 JWT
feat: 让封禁与登出即时失效旧 token
feat: 登录失败按 IP 限流并返回 429
feat: 前端登出调用后端失效接口
docs: 记录第七轮安全加固（JWT 主动失效与登录限流）
docs: 回填第七轮提交哈希与上线验收
docs: 勾选第七轮计划终态
```

前 6 条与 Task 1–6 一一对应；第 7 条是本计划文件自身的终态提交（勾选复选框）。

---

## 附录 B：Task 6 冒烟（raspi）

前置：Step 6-4 的 Jenkins 构建为 `SUCCESS`，`forum-backend` 已重建。所有命令通过 paramiko helper 在 raspi 上执行：

```bash
# helper 约定：python /tmp/forum-ssh.py "<远端命令>"，以 pi 账号登录，输出即远端 stdout/stderr
python /tmp/forum-ssh.py 'echo ok'
```

预期：`ok`。以下 6 组命令全部以 `python /tmp/forum-ssh.py '...'` 包裹，为节省篇幅只写引号内的远端命令；**外层单引号必须保留**，否则 `$TOKEN`、`\"` 会被本地 shell 提前展开。

### B.1 服务健康与版本

```bash
curl -fsS http://127.0.0.1:8086/api/health
curl -s -o /dev/null -w "%{http_code}\n" -H "Host: raspi" http://127.0.0.1/forum/api/health
```

预期：第一行 `{"success":true,"data":{...}}`，第二行 `200`。

### B.2 Flyway V4 与列定义

```bash
sudo -u pi podman exec postgresql psql -U postgres -d javaforum -tAc "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1"
sudo -u pi podman exec postgresql psql -U postgres -d javaforum -tAc "SELECT data_type, is_nullable, column_default FROM information_schema.columns WHERE table_name='users' AND column_name='token_version'"
sudo -u pi podman exec postgresql psql -U postgres -d javaforum -tAc "SELECT count(*) FROM users WHERE token_version IS NULL"
```

预期：`4|add user token version|t`；`bigint|NO|0`；`0`（无 NULL）。

### B.3 登出即时失效

```bash
TOKEN=$(curl -fsS -H "Content-Type: application/json" -d "{\"username\":\"admin\",\"password\":\"admin123456\"}" http://127.0.0.1:8086/api/auth/login | sed -n 's/.*"token":"\([^"]*\)".*/\1/p'); \
echo "me-before=$(curl -s -o /dev/null -w '%{http_code}' -H \"Authorization: Bearer $TOKEN\" http://127.0.0.1:8086/api/auth/me)"; \
echo "logout=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H \"Authorization: Bearer $TOKEN\" http://127.0.0.1:8086/api/auth/logout)"; \
echo "me-after=$(curl -s -o /dev/null -w '%{http_code}' -H \"Authorization: Bearer $TOKEN\" http://127.0.0.1:8086/api/auth/me)"; \
echo "relogin=$(curl -s -o /dev/null -w '%{http_code}' -H \"Content-Type: application/json\" -d \"{\\\"username\\\":\\\"admin\\\",\\\"password\\\":\\\"admin123456\\\"}\" http://127.0.0.1:8086/api/auth/login)"
```

预期：`me-before=200`、`logout=200`、`me-after=401`、`relogin=200`（登出只踢 token，不禁登录）。

### B.4 封禁即时失效

```bash
ADMIN=$(curl -fsS -H "Content-Type: application/json" -d "{\"username\":\"admin\",\"password\":\"admin123456\"}" http://127.0.0.1:8086/api/auth/login | sed -n 's/.*"token":"\([^"]*\)".*/\1/p'); \
NAME="smoke7_$(date +%s)"; \
REG=$(curl -fsS -H "Content-Type: application/json" -d "{\"username\":\"$NAME\",\"password\":\"password123\",\"email\":\"$NAME@test.com\"}" http://127.0.0.1:8086/api/auth/register); \
UID_=$(echo "$REG" | sed -n 's/.*"id":\([0-9]*\).*/\1/p'); \
USER_TOKEN=$(echo "$REG" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p'); \
echo "uid=$UID_"; \
echo "ban=$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" -d "{\"status\":\"BANNED\"}" http://127.0.0.1:8086/api/admin/users/$UID_/status)"; \
echo "me-after-ban=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $USER_TOKEN" http://127.0.0.1:8086/api/auth/me)"
```

预期：`uid=<数字>`、`ban=200`、`me-after-ban=401`。注意 `X-Real-IP` 不参与这条；注册接口不要求登录。

### B.5 限流：429、`Retry-After` 与锁定内正确密码

```bash
for i in 1 2 3 4 5; do curl -s -o /dev/null -w "%{http_code} " -H "Content-Type: application/json" -H "X-Real-IP: 203.0.113.9" -d "{\"username\":\"ghost_$i\",\"password\":\"wrong\"}" http://127.0.0.1:8086/api/auth/login; done; echo; \
curl -s -D - -o /dev/null -H "Content-Type: application/json" -H "X-Real-IP: 203.0.113.9" -d "{\"username\":\"admin\",\"password\":\"admin123456\"}" http://127.0.0.1:8086/api/auth/login | grep -Ei "^(HTTP/|retry-after)"; \
curl -s -o /dev/null -w "other-ip=%{http_code}\n" -H "Content-Type: application/json" -H "X-Real-IP: 198.51.100.7" -d "{\"username\":\"admin\",\"password\":\"admin123456\"}" http://127.0.0.1:8086/api/auth/login
```

预期：第一行 `401 401 401 401 401`；第二段 `HTTP/1.1 429` 与 `Retry-After: 900`（锁定内即使密码正确也 429）；第三行 `other-ip=200`。

> 用不存在的用户名 `ghost_$i` 制造失败，避免锁死真实账号；`203.0.113.9` 是 RFC 5737 的文档地址，线上不会与真实客户端撞车。

### B.6 收尾核对

```bash
sudo -u pi podman ps --filter name=forum-backend --format "{{.Names}} {{.Status}}"
sudo -u pi podman images --format "{{.Repository}}:{{.Tag}}" | grep forum-backend | head -3
```

预期：`forum-backend Up ... (healthy)`；镜像 tag 与本次 Build 编号一致（如 `localhost/forum-backend:11`）。

冒烟结论记录到 `docs/DEVELOPMENT.md` §4.12「上线验收」，并确认 §8 的 6 行哈希与 Build 编号一致，然后才执行 Step 6-6 的第二次推送。

---

## 12. 实施偏差（2026-10-24，上线后补充）

本计划正文保留立项时的原始规格（作为评审依据），实际实施与验证结果以本节为准，差异均已在上线后修复并复验通过：

| 项 | 计划（正文） | 实际 | 原因与处置 |
| --- | --- | --- | --- |
| `X-Real-IP` 信任模型 | 仅环回地址（§0(5)、Task 3 ClientIpResolver、Task 6/附录 B.5 均按此验收） | 环回 + RFC1918 私网（`isTrustedProxy` / `isPrivateIpv4`） | 部署冒烟 B.5 发现 rootless podman 的 `127.0.0.1:8086:8080` 端口映射使后端 `remoteAddr` 为容器网关 `10.89.3.15`，仅信环回会导致全站共用一个计数桶（任何来源 5 次错密码锁死全站登录）。提交 `419be00` 修复，重跑 B.5 全部通过 |
| `LOGIN_*` 环境变量 | `application.yml` 支持环境变量覆盖 | 同时在 `docker-compose.yml` 的 `environment` 透传 4 个变量 | 生产只改 `.env` 重启容器即生效；随 `419be00` 上线，Build #15 起容器内可查到 4 个变量 |
| 后端测试数 | 20 个测试类 / 161 个用例（§8 基准表） | 20 个测试类 / **163** 个用例（1 skipped） | `ClientIpResolverTest` 6→8 例（新增「私网网关采信 `X-Real-IP`」「私网无代理头回落 `remoteAddr`」）；§8 表格未回改，以本节与 `docs/DEVELOPMENT.md` §3.2 为准 |
| 提交数 | 附录 A 的 7 条（Task 1–6 + 回填 + 勾选） | 7 条 + `419be00`（修复）+ `4afc9d7`（修复验证回填）+ 终审记录提交 | 修复与回填为计划外的上线后动作，均已推送 |
| 部署验证 | 计划只要求 Build #12 一次验收 | Build **#12/#13/#14**（初次上线）与 **#15/#16**（修复与回填） | 均 `Finished: SUCCESS`；镜像 tag 与构建号一致、容器 `Up (healthy)` |
| 已知取舍补充 | §10 未列 | `docs/DEVELOPMENT.md` §4.12 新增第 10 条（成功登录清空整 IP 窗口，可被公开注册接口绕过）与第 11 条（前端登出后端失败静默，服务端 token 仍有效） | 来自上线后整体终审的 Important 项，接受并延后处理 |

仍与正文一致、未变更的约定：版本校验放在过滤器、旧 token 缺 `ver` 按 0、只统计 `BadCredentialsException`、限流计数在 Controller 层、`UserTokenState.getTokenVersion()` 用 `long`、`@Scheduled` 拆 `cleanupScheduled()`/`cleanupExpired()`。
