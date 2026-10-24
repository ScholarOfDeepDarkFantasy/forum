# 第八轮：质量加固实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 每个 Task 由子代理执行，一次只勾一个 `- [ ]`，一个 Task 跑完全部 Step 并提交后才能开始下一个 Task。**不要读 spec 也能执行**——本计划把每个决策都内联在 Task 里；需要背景时再看 `docs/superpowers/specs/2026-10-24-round8-quality-hardening-design.md`。

**Goal:** 收敛第七轮双审遗留的 Important 项与 T12/T13 两条技术债：过滤器会话回查 fail-closed（不再 500 + 非 `Result` 体）、限流器构造校验与清理身份 CAS、首页关键词 LIKE 转义、前端登出静默与时序、主题跨标签同步；不加用户可见新功能。

**Architecture:** 六个独立 Task，每个 Task「先写红测试 → 最小实现 → 全量跑绿 → 一次提交」。后端改动集中在 `security/`（过滤器、限流器）、`repository/`+`service/`（JPQL 与转义）、`exception/`（日志级别）；前端改动集中在 `api/index.js`（拦截器 silent 分支）、`App.vue`（await）、`composables/useTheme.js`（storage 监听）。无数据库迁移、无新端点、无新依赖、无配置项变化，因此每个 Task 都能单独 revert。

**Tech Stack:** Java 17 / Spring Boot 3.5.6 / Spring Security 6 / Spring Data JPA / PostgreSQL 14+ / Flyway / jjwt 0.13.0 / Vue 3 + Pinia + Vitest。不新增任何依赖。

## 0. 执行约定

### 0.1 分支与提交

- 仓库：`E:\JavaSpace\forum`，Git Bash 路径 `/e/JavaSpace/forum`。**直接在 `master` 上提交**，不开分支、不提 PR、不 amend、不 rebase、不 squash、不 force push。
- **每个 Task 一次提交**，提交信息逐字使用本 Task 末尾给出的那一条（附录 A 是汇总）。共 7 次提交：Task 1–6 各一次 + 上线后回填一次。
- 只 `git add` 本 Task **Files:** 小节列出的路径（用显式路径，不要 `git add -A`；工作区里不应出现无关改动，提交前 `git status --short` 必须只显示本 Task 的文件）。
- 两个文档（本计划 + spec）随 **Task 1** 一起入库：它们在本轮开始前已写好，不单独占一次提交。
- 计划里的 checkbox 在 Task 内执行完即勾；最后一轮勾选（Task 6 的收尾）**并入回填提交**，不额外加第 8 次提交。

### 0.2 TDD 顺序（每个 Task 都按这个来）

1. **RED**：先写/改测试，运行本 Task 指定的测试命令，确认失败信息与本计划写的「预期」一致（不是随便红，要红在断言上）。
2. **GREEN**：写最小实现，再跑同一命令，直到全绿；不顺手重构无关代码。
3. **REGRESSION**：跑全量（后端 `mvn -B test`，前端 `pnpm test`），确认总数与本 Task 的累计数一致，再提交。

标注为「特征化用例（characterization）」的 Step 例外：当前实现即通过，它的价值是防回归；若它红了，说明仓库现状与本计划不符，**停下来核对并报告**，不要改测试去迁就实现。

### 0.3 命令环境

- shell：Git Bash。后端命令在 `/e/JavaSpace/forum` 执行；前端命令必须 `cd frontend`（`pnpm test` 只在 `frontend/` 下有 `package.json`）。
- 构建必须带 `MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH`，否则 Git Bash 会把 `/forum/` 当成路径转换成 `E:/forum/`：
  ```bash
  cd frontend && MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build
  ```
- 后端测试用 H2 内存库（`@ActiveProfiles("test")` + `MODE=PostgreSQL`），**不需要**本地 PostgreSQL，也**不需要**设置任何环境变量。不要在 `mvn` 前 `export JWT_SECRET=...` 之类，测试类自带 properties。
- 前端测试是 `vitest run`（非 watch）：`cd frontend && pnpm test`；只跑单文件用 `cd frontend && pnpm vitest run src/api/index.spec.js`。
- 提交前如需看差异：`git diff --stat` 与 `git diff`（不要用 GUI）。
- 推送（Task 6）：`git push git@github.com:ScholarOfDeepDarkFantasy/forum.git master:master`（GitHub HTTPS 直连不可用，必须用 SSH）。

### 0.4 测试与接口基线（执行前）

| 项 | 当前值（`d56a8aa`） | 本轮目标值 |
| --- | --- | --- |
| 后端用例 | 163（1 skipped） | **176**（1 skipped） |
| 后端测试类 | 20 | **21** |
| 前端用例 | 314 | **321** |
| 前端 spec 文件 | 31 | 31（不变） |
| HTTP 端点 | 39 | 39（不变） |
| 数据库迁移 | V4 | V4（不变） |

> 任务书写「后端预期 177」。按本轮用例清单精确相加是 `163 + 7 + 2 + 3 + 1 = 176`（R1 7+2、R2 3、R3 1）。本计划以**用例清单**为准：写 176。执行时若实测 177，说明某个测试文件多写了一例，应核对清单而不是改预期数字。

### 0.5 本计划的裁决点（执行时不再临时决定）

1. **计划/spec 归属提交**：两个文档随 Task 1 入库，不单独提交（理由：它们是执行前提，先入库才能被后续 Task 引用）。
2. **测试计数 176 而非 177**：见 §0.4 的算式。文档（README/DEVELOPMENT）写 176。
3. **限流器校验消息格式**：`"app.security.login.max-failures 必须 >= 1，当前 " + maxFailures`（三条同构，配置全名 + 当前值）。运维改的是 `.env` 的 `LOGIN_MAX_FAILURES`，但日志要能 grep yml 键名。
4. **429 日志降级**：`GlobalExceptionHandler` 降为 `log.debug`；`LoginRateLimiter.recordFailure` 的 WARN **不动**（那是唯一带 IP 的事实日志）。
5. **过滤器单测不 mock `Claims`**：用真实 `JwtTokenProvider` + mock `UserRepository`，避免 Mockito 严格模式的 `UnnecessaryStubbingException`，并让签名/解析路径进入覆盖。
6. **`silent` 只影响提示与跳转**：401 + silent **仍然**清 `jf_token`/`jf_user`（否则死 token 会继续被带在后续请求上）。不改全局 401 行为。
7. **`authApi.logout` 的三个参数**：`api.post('/auth/logout', null, { silent: true })`——第二参必须显式 `null`，否则 axios 把 config 当请求体。
8. **`App.spec.js` 的改造范围**：把 `vue-router` 的 `push` 换成 hoisted 共享 spy；`element-plus` 用 `importOriginal` 局部 mock（只覆盖 `ElMessage`），不要整体替换（`ThemeToggle` 等模块从该包具名导入，整体 mock 会让它们变成 `undefined`）。
9. **DEVELOPMENT 的落点**：第八轮记录**新建 §4.13**（与 §4.1–§4.12「一轮一节」的惯例一致），§4.12 只做「删除已修复的取舍项 + 一行指针」。上线验收回填写在 §4.13 的「上线验收」小节（任务书里说的「§4.12 上线验收」指的是同一处记录，落点以本节为准）。
10. **`Retry-After` 断言**：`Long.parseLong(...)` 后 `isBetween(1L, 60L)`。lock 60000ms 时上界就是 60，区间断言不依赖「同一秒」。
11. **不修 `stores/auth.js`**：R4 只改 `api/index.js` 与 `App.vue`；store 的 `try/catch/finally`（`stores/auth.js:51-59`）已经是「先调后端、后清本地」，两个新测试只是把它锁住。
12. **`useTheme` 的监听绑在模块顶层**：`useTheme()` 被 App / ThemeToggle / 路由守卫多处调用，绑在函数里会重复注册；用模块级 `storageListenerBound` 标志 + 顶层调用。

## 1. 现状与差距

| 现状（`d56a8aa`） | 差距 | 本轮落点 |
| --- | --- | --- |
| `JwtAuthenticationFilter.isSessionAlive`（`:71-82`）只 catch `NumberFormatException`，查库（`:78-81`）在 try 之外；类无 logger | DB 故障时 `DataAccessException` 穿出过滤器 → 500 + 非 `Result` 体；无单测覆盖过滤器的四条否定路径 | Task 1 |
| `AuthInvalidationIntegrationTest` 6 例都走「status + 版本」同时生效的路径 | 删掉 status 判断或不 +1 版本，6 例仍全绿 | Task 1 |
| `LoginRateLimiter` 构造器（`:40-48`）直接赋值；`cleanupExpired`（`:118`）`iterator.remove()` | `lock-ms<=0` 静默关闭锁定；`remove()` 按 key 删，竞态下会误删并发新建的窗口 | Task 2 |
| `GlobalExceptionHandler:147` 每条 429 一条 WARN（不含 IP） | 锁定期可被刷日志，信息量为零 | Task 2 |
| `LoginRateLimitIntegrationTest:71` `isIn("59","60")` | 跨秒即假红 | Task 2 |
| `PostService.list:135` 直接 `blankToEmpty`；`PostRepository.searchIds:29-30` 无 `ESCAPE` | 首页 `%`/`_` 当通配符，与 `/api/posts/search` 语义分叉 | Task 3 |
| `frontend/src/api/index.js:57-63` 对一切 401 清态 + 提示 + `location.href`；`:77` `logout` 无 config | 重复登出先「已退出」再「请先登录」并整页跳转 | Task 4 |
| `frontend/src/App.vue:26` `auth.logout()` 不 await，`:29` 立刻提示 | 后端未返回就宣布成功 | Task 4 |
| `frontend/src/composables/useTheme.js` 全文无 `storage` 监听 | 同源多标签主题各走各的，刷新后才一致（T12） | Task 5 |

### 1.1 文件结构（本轮全部改动）

| 动作 | 路径 | 归属 |
| --- | --- | --- |
| Modify | `src/main/java/org/song/forum/security/JwtAuthenticationFilter.java` | Task 1 |
| Create | `src/test/java/org/song/forum/security/JwtAuthenticationFilterTest.java` | Task 1 |
| Modify | `src/test/java/org/song/forum/controller/AuthInvalidationIntegrationTest.java` | Task 1 |
| Create | `docs/superpowers/specs/2026-10-24-round8-quality-hardening-design.md` | Task 1（随提交） |
| Create | `docs/superpowers/plans/2026-10-24-round8-quality-hardening.md` | Task 1（随提交） |
| Modify | `src/main/java/org/song/forum/security/LoginRateLimiter.java` | Task 2 |
| Modify | `src/main/java/org/song/forum/exception/GlobalExceptionHandler.java` | Task 2 |
| Modify | `src/test/java/org/song/forum/security/LoginRateLimiterTest.java` | Task 2 |
| Modify | `src/test/java/org/song/forum/controller/LoginRateLimitIntegrationTest.java` | Task 2 |
| Modify | `src/main/java/org/song/forum/service/PostService.java` | Task 3 |
| Modify | `src/main/java/org/song/forum/repository/PostRepository.java` | Task 3 |
| Modify | `src/test/java/org/song/forum/controller/PostFlowIntegrationTest.java` | Task 3 |
| Modify | `frontend/src/api/index.js` | Task 4 |
| Modify | `frontend/src/App.vue` | Task 4 |
| Modify | `frontend/src/api/index.spec.js` | Task 4 |
| Modify | `frontend/src/stores/auth.spec.js` | Task 4 |
| Modify | `frontend/src/App.spec.js` | Task 4 |
| Modify | `frontend/src/composables/useTheme.js` | Task 5 |
| Modify | `frontend/src/composables/useTheme.spec.js` | Task 5 |
| Modify | `README.md` | Task 6 |
| Modify | `docs/DEVELOPMENT.md` | Task 6 |
| Modify | `docs/deployment/README.md` | Task 6 |

不改动（明确不出现在任何 Task 的 Files 里）：`pom.xml`、`frontend/package.json`、`frontend/vite.config.js`、`src/main/resources/**`（含 `application.yml`、Flyway `db/migration/**`）、`deploy/**`、`Jenkinsfile`（若存在）、`frontend/src/stores/auth.js`。

约定：

1. 每个 Task 的 **Files:** 小节是执行时的权威清单；本表只用于全局核对。
2. 新增文件只有 3 个：`JwtAuthenticationFilterTest.java` 与两个文档。
3. 若某 Task 发现必须多改一个文件，先停下来说明原因，不要自行扩大范围。

---

## Task 1：JWT 过滤器会话回查失败按未认证处理（R1）

**Files:**

- Modify: `src/main/java/org/song/forum/security/JwtAuthenticationFilter.java`
- Create: `src/test/java/org/song/forum/security/JwtAuthenticationFilterTest.java`
- Modify: `src/test/java/org/song/forum/controller/AuthInvalidationIntegrationTest.java`
- Add to commit: `docs/superpowers/specs/2026-10-24-round8-quality-hardening-design.md`、`docs/superpowers/plans/2026-10-24-round8-quality-hardening.md`

### Step 1-1：先写 7 例过滤器单测（RED）

新建 `src/test/java/org/song/forum/security/JwtAuthenticationFilterTest.java`。用**真实** `JwtTokenProvider` 造 token、mock `UserRepository`，`ReflectionTestUtils` 注入 `@Value` 字段（手动 `new` 时注解不生效），`MockHttpServletRequest`/`MockHttpServletResponse`/`MockFilterChain` 走真实 `doFilter`。每个用例结束都清 `SecurityContextHolder`，避免线程复用导致串味。

```java
package org.song.forum.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.song.forum.entity.UserStatus;
import org.song.forum.repository.UserRepository;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 过滤器单测：签名/解析/会话回查三条路径的四条否定分支各一例。
 * <p>用真实 JwtTokenProvider 造 token（而不是 mock Claims）：mock Claims 要逐方法 stub，
 * Mockito 严格模式会因「某个用例没用到 roles stub」报 UnnecessaryStubbingException；
 * 真实 provider 顺带把签名与解析也纳入覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    /** HS256 要求密钥至少 256 bit，这里远超 32 字节 */
    private static final String SECRET = "round8-jwt-filter-test-secret-0123456789abcdefghijklmnopqrstuvwxyz";
    private static final long EXPIRATION_MS = 60_000L;

    @Mock
    private UserRepository userRepository;

    private JwtTokenProvider tokenProvider;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        tokenProvider = new JwtTokenProvider(SECRET, EXPIRATION_MS);
        filter = new JwtAuthenticationFilter(tokenProvider, userRepository);
        ReflectionTestUtils.setField(filter, "headerName", "Authorization");
        ReflectionTestUtils.setField(filter, "prefix", "Bearer ");
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTokenEstablishesAuthentication() throws Exception {
        String token = tokenProvider.createToken(42L, "alice", List.of("USER"), 0L);
        when(userRepository.findTokenStateById(42L))
                .thenReturn(Optional.of(state(42L, UserStatus.ACTIVE, 0L)));

        filter.doFilter(request(token), new MockHttpServletResponse(), new MockFilterChain());

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo("alice");
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
        assertThat(((ForumUserDetails) authentication.getPrincipal()).getUserId()).isEqualTo(42L);
    }

    @Test
    void noAuthorizationHeaderPassesThroughWithoutAuthentication() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/api/health"),
                new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(userRepository);
    }

    @Test
    void invalidTokenDoesNotAuthenticate() throws Exception {
        filter.doFilter(request("not-a-jwt"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(userRepository);
    }

    @Test
    void nonNumericSubjectDoesNotAuthenticate() throws Exception {
        // 手工签发 sub 非数字的 token：validate 能通过（签名有效），但解析 userId 失败
        String token = Jwts.builder()
                .subject("not-a-number")
                .claim("username", "alice")
                .claim("roles", List.of("USER"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRATION_MS))
                .signWith(key())
                .compact();

        filter.doFilter(request(token), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        // 顺序保证：subject 解析在查库之前，坏 subject 不应该打库
        verifyNoInteractions(userRepository);
    }

    @Test
    void repositoryFailureIsTreatedAsUnauthenticated() throws Exception {
        String token = tokenProvider.createToken(7L, "bob", List.of("USER"), 0L);
        when(userRepository.findTokenStateById(7L))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatCode(() -> filter.doFilter(request(token), new MockHttpServletResponse(),
                new MockFilterChain()))
                .doesNotThrowAnyException();

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userRepository).findTokenStateById(7L);
    }

    @Test
    void bannedUserDoesNotAuthenticate() throws Exception {
        String token = tokenProvider.createToken(8L, "carol", List.of("USER"), 0L);
        when(userRepository.findTokenStateById(8L))
                .thenReturn(Optional.of(state(8L, UserStatus.BANNED, 0L)));

        filter.doFilter(request(token), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void versionMismatchDoesNotAuthenticate() throws Exception {
        String token = tokenProvider.createToken(9L, "dave", List.of("USER"), 3L);
        when(userRepository.findTokenStateById(9L))
                .thenReturn(Optional.of(state(9L, UserStatus.ACTIVE, 4L)));

        filter.doFilter(request(token), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ---------- 辅助 ----------

    private MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    private static UserRepository.UserTokenState state(Long id, UserStatus status, long tokenVersion) {
        return new UserRepository.UserTokenState() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public UserStatus getStatus() {
                return status;
            }

            @Override
            public long getTokenVersion() {
                return tokenVersion;
            }
        };
    }
}
```

运行：

```bash
cd /e/JavaSpace/forum
mvn -B test -Dtest=JwtAuthenticationFilterTest
```

预期（RED）：7 例里 **6 例绿、1 例红**。红的是 `repositoryFailureIsTreatedAsUnauthenticated`，因为现状下 `DataAccessException` 直接穿出 `isSessionAlive`：

```
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 1.2 s <<< FAILURE! -- in org.song.forum.security.JwtAuthenticationFilterTest
[ERROR] org.song.forum.security.JwtAuthenticationFilterTest.repositoryFailureIsTreatedAsUnauthenticated -- Time elapsed: 0.02 s <<< FAILURE!
java.lang.AssertionError:
Expecting code not to raise a throwable but caught
  <"org.springframework.dao.DataAccessResourceFailureException: db down
	at org.song.forum.repository.UserRepository.findTokenStateById(UserRepository.java)
	..."
```

若红的是别的用例（例如 `validTokenEstablishesAuthentication`），先核对 `ForumUserDetails.fromClaims` 的 `ROLE_` 前缀与 `createToken` 参数顺序，不要改断言凑绿。

### Step 1-2：让用例 5 转绿（GREEN）

改 `src/main/java/org/song/forum/security/JwtAuthenticationFilter.java`：

1. import 增加（按字母序插到既有 import 之间）：

```java
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
```

2. 类注解加 `@Slf4j`（放在 `@Component` 之前，与 `LoginRateLimiter` 的写法一致）：

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
```

3. 把 `isSessionAlive` 改成两段 try（注意：**catch 必须包住查库**，只包 `Long.valueOf` 等于没改）：

```java
    /**
     * 会话是否仍然有效：用户存在、未被封禁、且 token 版本与库中一致。
     * <p>旧 token 没有 ver claim 时 {@link JwtTokenProvider#tokenVersion(Claims)} 返回 0，
     * 因此版本为 0 的老用户不会被强制重登；一旦版本变成 1，旧 token 立即被拒。</p>
     * <p>数据库不可用时按<b>未认证</b>处理（fail-closed）：受保护接口由 entry point 返回 401，
     * 公开 GET 仍匿名可用。这里不修改任何持久化状态，因此库恢复后同一 token 依然有效——
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
            // 只吞数据访问异常：NPE 之类的编程错误仍应冒出来，不能被伪装成「未登录」。
            // 用 debug 而不是 warn：库故障期间每请求一条 WARN 会刷屏，真正的失败在数据源层已有日志。
            log.debug("回查会话状态失败，按未认证处理: {}", e.toString());
            return false;
        }
    }
```

运行：

```bash
mvn -B test -Dtest=JwtAuthenticationFilterTest
```

预期（GREEN）：

```
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Step 1-3：补封禁双保险的两条集成用例（特征化，直接绿）

在 `src/test/java/org/song/forum/controller/AuthInvalidationIntegrationTest.java` 的「用例」区（`legacyTokenWithoutVersionWorksUntilUserLogsOut` 之后、`// ---------- 辅助方法 ----------` 之前）追加两例。两条各只触发**一条**保险：

```java
    @Test
    void bannedStatusWithoutVersionBumpRejectsToken() {
        AuthResponse victim = register("inval_status_only");

        // 只改 status、不动 token_version：版本仍然匹配，401 只能来自过滤器自己对 status 的检查
        jdbcTemplate.update("update users set status = 'BANNED' where id = ?", victim.getId());

        ResponseEntity<String> resp = me(victim.getToken());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("未登录或登录已过期");
    }

    @Test
    void unbanDoesNotReviveOldToken() {
        AuthResponse victim = register("inval_unban");
        AuthResponse admin = login("admin", "admin123456");

        // 走管理 API 封禁：服务端会把 token_version +1
        changeStatus(admin.getToken(), victim.getId(), "BANNED", null);
        assertThat(me(victim.getToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 解封只改状态，版本不回退 → 旧 token 不能复活
        changeStatus(admin.getToken(), victim.getId(), "ACTIVE", null);
        assertThat(me(victim.getToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 对照：重新登录拿到的新 token 可用，证明上面的 401 来自版本而不是账号不可用
        AuthResponse fresh = login("inval_unban", "password123");
        assertThat(me(fresh.getToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
```

运行：

```bash
mvn -B test -Dtest=AuthInvalidationIntegrationTest
```

预期：

```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**这是特征化用例：当前实现即通过。** 若其中一条红了，说明仓库现状与「双保险」的描述不符（例如 `AdminUserService.changeStatus` 的 BANNED 分支不再 `+1`），停下来报告，不要改测试。

### Step 1-4：全量验证并提交

```bash
cd /e/JavaSpace/forum
mvn -B test
```

预期：

```
[INFO] Tests run: 172, Failures: 0, Errors: 0, Skipped: 1
[INFO] BUILD SUCCESS
```

（163 + 9 = 172；1 个 skipped 仍是 Windows 上的 POSIX 权限用例。）

```bash
git status --short
git add src/main/java/org/song/forum/security/JwtAuthenticationFilter.java \
        src/test/java/org/song/forum/security/JwtAuthenticationFilterTest.java \
        src/test/java/org/song/forum/controller/AuthInvalidationIntegrationTest.java \
        docs/superpowers/specs/2026-10-24-round8-quality-hardening-design.md \
        docs/superpowers/plans/2026-10-24-round8-quality-hardening.md
git commit -m "fix: JWT 过滤器会话回查失败按未认证处理并补测试"
```

预期 `git status --short` 在 add 之前只有这 5 项（其中 2 个是 `??`，3 个是 ` M`）。

---

## Task 2：限流器清理竞态与配置校验加固（R2）

**Files:**

- Modify: `src/main/java/org/song/forum/security/LoginRateLimiter.java`
- Modify: `src/main/java/org/song/forum/exception/GlobalExceptionHandler.java`
- Modify: `src/test/java/org/song/forum/security/LoginRateLimiterTest.java`
- Modify: `src/test/java/org/song/forum/controller/LoginRateLimitIntegrationTest.java`

### Step 2-1：先写 3 例构造器校验测试（RED）

在 `LoginRateLimiterTest` 的「辅助」分隔线之前追加三例（放在 `cleanupKeepsLockedWindowsAndRemovesStaleOnes` 之后）：

```java
    @Test
    void rejectsMaxFailuresBelowOne() {
        assertThatThrownBy(() -> new LoginRateLimiter(clock, 0, WINDOW_MS, LOCK_MS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.security.login.max-failures");
    }

    @Test
    void rejectsNonPositiveWindow() {
        assertThatThrownBy(() -> new LoginRateLimiter(clock, MAX_FAILURES, 0, LOCK_MS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.security.login.window-ms");
    }

    @Test
    void rejectsNonPositiveLockWindow() {
        assertThatThrownBy(() -> new LoginRateLimiter(clock, MAX_FAILURES, WINDOW_MS, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.security.login.lock-ms");
    }
```

运行：

```bash
mvn -B test -Dtest=LoginRateLimiterTest
```

预期（RED）：9 例绿、3 例红，红的三条都是「预期抛异常但没抛」：

```
[ERROR] Tests run: 12, Failures: 3, Errors: 0, Skipped: 0
[ERROR]   LoginRateLimiterTest.rejectsMaxFailuresBelowOne:NN
Expecting code to raise a throwable.
[ERROR]   LoginRateLimiterTest.rejectsNonPositiveWindow:NN
Expecting code to raise a throwable.
[ERROR]   LoginRateLimiterTest.rejectsNonPositiveLockWindow:NN
Expecting code to raise a throwable.
```

### Step 2-2：构造器 fail fast（GREEN）

把 `LoginRateLimiter` 的构造器（`:40-48`）替换为：

```java
    public LoginRateLimiter(Clock clock,
                            @Value("${app.security.login.max-failures:5}") int maxFailures,
                            @Value("${app.security.login.window-ms:900000}") long windowMs,
                            @Value("${app.security.login.lock-ms:900000}") long lockMs) {
        // 配置写错时启动即失败，而不是在生产表现为「限流悄悄失效」：
        // maxFailures < 1 会让每次失败都立刻锁定；windowMs <= 0 让窗口永远为空；
        // lockMs <= 0 让锁定瞬间到期（等于没有锁定）。
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

运行同一命令，预期 `Tests run: 12, Failures: 0`。

### Step 2-3：清理改成 key + 实例身份 CAS

把 `cleanupExpired()`（`:105-124`）整体替换为：

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
                // 按 key + 实例身份删除：上面的判断与这里之间可能被 recordSuccess/recordFailure
                // 换成了新窗口，ConcurrentHashMap 的迭代器 remove 只认 key，会误删新窗口。
                if (stale && windows.remove(entry.getKey(), window)) {
                    removed++;
                }
            }
        }
        return removed;
    }
```

同时删除不再使用的 `import java.util.Iterator;`（`:12`）。

运行：

```bash
mvn -B test -Dtest=LoginRateLimiterTest
```

预期：

```
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
```

（`cleanupKeepsLockedWindowsAndRemovesStaleOnes` 断言返回 2，身份 CAS 下语义不变。）

### Step 2-4：`Retry-After` 断言稳定化

改 `LoginRateLimitIntegrationTest:71`：

```java
    @Test
    void thirdFailureLocksIpAndBlocksCorrectPassword() {
        ensureUser();
        String ip = "198.51.100.11";

        for (int i = 0; i < 3; i++) {
            assertThat(login(ip, USER, WRONG).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<String> locked = login(ip, USER, PASSWORD);

        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // lock-ms=60000：剩余时间 <= 60s，向上取整后恒在 [1, 60]。
        // 原来断言 "59"/"60" 隐含「3 次失败与第 4 次请求在同一秒」，慢 CI 上会假红。
        long retryAfterSeconds = Long.parseLong(locked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertThat(retryAfterSeconds).isBetween(1L, 60L);
        assertThat(locked.getBody()).contains("登录失败次数过多，请 1 分钟后再试");
    }
```

运行：

```bash
mvn -B test -Dtest=LoginRateLimitIntegrationTest
```

预期：`Tests run: 4, Failures: 0`。

### Step 2-5：429 日志降为 debug

`GlobalExceptionHandler.java` 的第 147 行：

```java
        log.warn("登录失败次数超限: {}", e.getMessage());
```

改成：

```java
        // 事实日志在 LoginRateLimiter.recordFailure（带 IP、阈值与锁定时长）；
        // 这里每条 429 都打 WARN 不带 IP，锁定期间等于给流量刷日志的放大器。
        log.debug("登录失败次数超限: {}", e.getMessage());
```

（无新增测试：它不改返回值，只改日志级别；核对手段是 `grep -n "登录失败次数超限" src/main/java/org/song/forum/exception/GlobalExceptionHandler.java` 应显示 `log.debug`。）

### Step 2-6：全量验证并提交

```bash
mvn -B test
```

预期：

```
[INFO] Tests run: 175, Failures: 0, Errors: 0, Skipped: 1
[INFO] BUILD SUCCESS
```

（172 + 3 = 175。）

```bash
git add src/main/java/org/song/forum/security/LoginRateLimiter.java \
        src/main/java/org/song/forum/exception/GlobalExceptionHandler.java \
        src/test/java/org/song/forum/security/LoginRateLimiterTest.java \
        src/test/java/org/song/forum/controller/LoginRateLimitIntegrationTest.java
git commit -m "fix: 登录限流清理竞态与配置校验加固"
```

---

## Task 3：首页关键词过滤转义 LIKE 通配符（R3）

**Files:**

- Modify: `src/main/java/org/song/forum/service/PostService.java`
- Modify: `src/main/java/org/song/forum/repository/PostRepository.java`
- Modify: `src/test/java/org/song/forum/controller/PostFlowIntegrationTest.java`

### Step 3-1：先写 stage5（RED）

在 `PostFlowIntegrationTest` 的 `stage4_myPostsAndSoftDelete` 之后、类结束大括号之前追加：

```java
    @Test
    @Order(5)
    void stage5_keywordWildcardsAreLiteral() {
        // 四篇标题两两成对：若 % 或 _ 被当成通配符，会误命中对照组
        createPost("1000 items");     // 若 % 通配，"100%" 会命中它
        createPost("100% done");      // 唯一应被 "100%" 命中的
        createPost("a_b note");       // 唯一应被 "a_b" 命中的
        createPost("axb note");       // 若 _ 通配，"a_b" 会命中它

        // 用 URI 模板变量传关键词，由 RestTemplate 统一编码（与 stage3 的写法一致）
        ResponseEntity<Result<PageResponse<PostSummaryResponse>>> percent =
                rest.exchange(url("/api/posts?keyword={kw}"), HttpMethod.GET, null,
                        new ParameterizedTypeReference<>() {},
                        java.util.Map.of("kw", "100%"));
        assertThat(percent.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(percent.getBody().getData().totalElements()).isEqualTo(1);
        assertThat(percent.getBody().getData().content())
                .extracting(PostSummaryResponse::getTitle)
                .containsExactly("100% done");

        ResponseEntity<Result<PageResponse<PostSummaryResponse>>> underscore =
                rest.exchange(url("/api/posts?keyword={kw}"), HttpMethod.GET, null,
                        new ParameterizedTypeReference<>() {},
                        java.util.Map.of("kw", "a_b"));
        assertThat(underscore.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(underscore.getBody().getData().totalElements()).isEqualTo(1);
        assertThat(underscore.getBody().getData().content())
                .extracting(PostSummaryResponse::getTitle)
                .containsExactly("a_b note");
    }

    /** stage5 建帖：正文刻意不含 % 与 _，避免正文命中干扰标题断言 */
    private void createPost(String title) {
        PostRequest req = new PostRequest();
        req.setBoardId(boardId);
        req.setTitle(title);
        req.setContent("第八轮转义用例正文 " + System.nanoTime());
        ResponseEntity<Result<PostDetailResponse>> resp = rest.exchange(url("/api/posts"),
                HttpMethod.POST, new HttpEntity<>(req, bearer(adminToken)),
                new ParameterizedTypeReference<>() {});
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
    }
```

运行：

```bash
mvn -B test -Dtest=PostFlowIntegrationTest
```

预期（RED）：4 个 stage 绿、stage5 红，两条断言都失败（现状 `%` 与 `_` 都是通配符）：

```
[ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0
[ERROR]   PostFlowIntegrationTest.stage5_keywordWildcardsAreLiteral:NN
expected: 1L
 but was: 2L
[ERROR]   PostFlowIntegrationTest.stage5_keywordWildcardsAreLiteral:NN
Expecting actual:
  ["100% done", "1000 items"]
to contain exactly:
  ["100% done"]
```

（两条断言顺序执行，第一个失败后 JUnit 不再继续，因此实际输出只报第一条；第二条改绿后会一起验证。若第一个 `expected 1 but was 2` 与这里不同，核对帖子是否真的进了同一个 `boardId`。）

### Step 3-2：service 先转义（GREEN）

`PostService.list`（`:135-136`）：

```java
        String kw = blankToEmpty(keyword);
        if (!kw.isEmpty()) {
            // 与 GET /api/posts/search 一致：%/_/! 按字面量匹配（转义后 JPQL 用 ESCAPE '!'）。
            // 空串必须保持空串：JPQL 用 :keyword = '' 表示「无关键字」，转义成 '!!' 会让条件永远为假。
            kw = escapeLike(kw.toLowerCase(Locale.ROOT));
        }
        Page<Post> skeletons = postRepository.searchIds(boardId, kw, pageable);
```

（`escapeLike` 是 `:279-293` 的既有静态方法，`Locale` 已在文件中 import。）

### Step 3-3：Repository 两处 LIKE 加 `ESCAPE '!'`

`PostRepository.searchIds`（`:25-35`）改成：

```java
    /**
     * 首页列表的骨架查询：只取 id（项目数 + 分页在 SQL 侧完成），再由 service 批量 hydrate。
     * <p>keyword 已由 service 用 '!' 转义，因此这里必须带 ESCAPE '!'，否则 %/_ 仍被当通配符。</p>
     */
    @Query("select p.id from Post p where p.deleted = false "
            + "and (:boardId is null or p.board.id = :boardId) "
            + "and (:keyword = '' "
            + "or lower(p.title) like lower(concat('%', :keyword, '%')) escape '!' "
            + "or lower(p.content) like lower(concat('%', :keyword, '%')) escape '!')")
    Page<Long> searchIds(@Param("boardId") Long boardId,
                         @Param("keyword") String keyword,
                         Pageable pageable);
```

保留原来的方法注释里关于「pgJDBC 把 null 绑定成 bytea」/「两步查避免 HHH90003004」的说明（它们解释的是别的坑，不要删）。**两处 LIKE 都要加 `escape '!'`**，漏一处则通过正文仍能命中通配符。

运行：

```bash
mvn -B test -Dtest=PostFlowIntegrationTest
```

预期：

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
```

### Step 3-4：全量验证并提交

```bash
mvn -B test
```

预期：`Tests run: 176, Failures: 0, Errors: 0, Skipped: 1`（175 + 1 = 176）。特别注意 `SearchFlowIntegrationTest`（14 例）必须仍全绿——它覆盖 `/api/posts/search`，共用 `escapeLike`。

```bash
git add src/main/java/org/song/forum/service/PostService.java \
        src/main/java/org/song/forum/repository/PostRepository.java \
        src/test/java/org/song/forum/controller/PostFlowIntegrationTest.java
git commit -m "fix: 首页关键词过滤转义 LIKE 通配符"
```

---

## Task 4：前端登出静默处理 401 并保证清态时序（R4）

**Files:**

- Modify: `frontend/src/api/index.js`
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/api/index.spec.js`
- Modify: `frontend/src/stores/auth.spec.js`
- Modify: `frontend/src/App.spec.js`

### Step 4-1：先写 4 例前端测试（RED ×3 + 特征化 ×1）

#### (a) `frontend/src/api/index.spec.js`

1. 给 `httpError` 加第 4 个参数（保留既有三参调用不变）：

```js
/** 构造一个 axios 错误对象，模拟后端返回 Result.fail 的响应体 */
function httpError(status, message, url, extraConfig = {}) {
  return {
    config: { url, ...extraConfig },
    response: { status, data: { success: false, message } },
    message: 'Request failed'
  }
}
```

2. 在 `describe('api 拦截器')` 内、`'业务失败（HTTP 200 + success:false）…'` 之前插入两例：

```js
  it('silent 请求 401 仍清登录态，但不弹提示、不跳转（重复登出场景）', async () => {
    localStorage.setItem('jf_token', 'expired')
    localStorage.setItem('jf_user', '{"id":1}')
    const err = httpError(401, '未登录或登录已过期', '/auth/logout', { silent: true })

    await expect(handlers.responseError(err)).rejects.toBe(err)

    // 清理是正确副作用：留着死 token 会让后续请求继续带 Bearer 并继续 401
    expect(localStorage.getItem('jf_token')).toBeNull()
    expect(localStorage.getItem('jf_user')).toBeNull()
    // 但不应再制造「已退出登录 + 请先登录」两条矛盾提示，也不整页跳转
    expect(elMessageError).not.toHaveBeenCalled()
    // 用「没有跳到登录页」而不是断言具体值：各 spec 的 location 桩初始 href 可能不同
    expect(window.location.href).not.toContain('/login')
  })

  it('silent 请求的 429 与网络错误也不弹提示', async () => {
    const tooMany = httpError(429, '登录失败次数过多，请 1 分钟后再试', '/auth/logout', {
      silent: true
    })
    await expect(handlers.responseError(tooMany)).rejects.toBe(tooMany)

    const network = { config: { url: '/auth/logout', silent: true }, message: 'Network Error' }
    await expect(handlers.responseError(network)).rejects.toBe(network)

    expect(elMessageError).not.toHaveBeenCalled()
  })
```

3. 把 `describe('authApi 请求路径')` 里既有的 logout 用例改为三参断言：

```js
  it('logout 以 silent 方式 POST /auth/logout', async () => {
    apiPost.mockResolvedValueOnce(undefined)

    await authApi.logout()

    expect(apiPost).toHaveBeenCalledTimes(1)
    expect(apiPost).toHaveBeenCalledWith('/auth/logout', null, { silent: true })
  })
```

运行：

```bash
cd frontend && pnpm vitest run src/api/index.spec.js
```

预期（RED）：15 + 2 = 17 例，3 例红：

```
 FAIL  src/api/index.spec.js > api 拦截器 > silent 请求 401 仍清登录态，但不弹提示、不跳转（重复登出场景）
AssertionError: expected "spy" to not be called at all, but actually been called with [ '请先登录' ]
 FAIL  src/api/index.spec.js > api 拦截器 > silent 请求的 429 与网络错误也不弹提示
AssertionError: expected "spy" to not be called at all, but actually been called with [ '登录失败次数过多，请 1 分钟后再试' ]
 FAIL  src/api/index.spec.js > authApi 请求路径 > logout 以 silent 方式 POST /auth/logout
AssertionError: expected "spy" to be called with arguments: [ '/auth/logout', null, { silent: true } ]
```

#### (b) `frontend/src/stores/auth.spec.js`（特征化，直接绿）

在 `'调用后端登出接口并清空本地状态'` 之后追加：

```js
  it('先调用后端登出、后清本地态（顺序反转会让服务端收到已清掉的 token）', async () => {
    localStorage.setItem('jf_token', 'token-order')
    const store = useAuthStore()
    let tokenAtRequestTime = null
    // 在「请求发出」的那一刻读 localStorage：此时 token 必须还在
    authApi.logout.mockImplementationOnce(async () => {
      tokenAtRequestTime = localStorage.getItem('jf_token')
    })

    await store.logout()

    expect(tokenAtRequestTime).toBe('token-order')
    expect(localStorage.getItem('jf_token')).toBeNull()
  })
```

#### (c) `frontend/src/App.spec.js`

1. 把 hoisted 块扩成四个 spy：

```js
// vi.mock 的工厂会被提升到 import 之前，跨行引用必须用 vi.hoisted
const { streamMock, useNotificationStreamMock, routerPush, messageSuccess } = vi.hoisted(() => {
  const stream = { start: vi.fn(), stop: vi.fn(), state: { value: 'closed' } }
  return {
    streamMock: stream,
    useNotificationStreamMock: vi.fn(),
    routerPush: vi.fn(),
    messageSuccess: vi.fn()
  }
})
```

2. 把 `vue-router` 的 mock 换成共享 `routerPush`：

```js
// App.vue 的 setup 调用 useRouter()；不装真实路由插件时注入不到，mock 掉最省事。
vi.mock('vue-router', () => ({ useRouter: () => ({ push: routerPush }) }))
```

3. 新增 `element-plus` 的**局部** mock（必须 `importOriginal`，否则 `ThemeToggle` 等模块从该包的具名导入会变成 `undefined`）：

```js
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, ElMessage: { success: messageSuccess, error: vi.fn() } }
})
```

4. 把 `el-dropdown` 的 stub 换成可 `$emit('command')` 的组件（其余 stub 不动）：

```js
        'el-dropdown': {
          // 声明 emits 后 $emit('command') 才会调用父组件的 onCommand；
          // 不声明会按原生事件透传，父监听永远不触发。
          emits: ['command'],
          template: '<div class="el-dropdown-stub"><slot /></div>'
        },
```

5. 补齐 import（**只补缺的行，不要重复插入已存在的 import**）：

```js
// 若 mount 已导入，只追加 flushPromises
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
```

6. 在 `describe('App.vue 实时通知接线')` 之后追加新 describe（或放在同一 describe 内末尾）：

```js
describe('App.vue 退出登录', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('后端未返回前不提示，返回后才提示成功并跳首页', async () => {
    const auth = useAuthStore()
    auth.token = 'token-1'
    auth.user = { id: 1, username: 'alice' }
    let resolveLogout
    const pending = new Promise((resolve) => {
      resolveLogout = resolve
    })
    vi.spyOn(auth, 'logout').mockImplementation(() => pending)

    const wrapper = mountApp()
    // 用 CSS 选择器取到 stub 的组件实例（VTU 的 findComponent 支持类选择器），再手动 emit
    wrapper.findComponent('.el-dropdown-stub').vm.$emit('command', 'logout')
    await nextTick()

    // 关键断言：后端还没返回，不能先宣布「已退出登录」，也不能先跳首页
    expect(auth.logout).toHaveBeenCalledTimes(1)
    expect(messageSuccess).not.toHaveBeenCalled()
    expect(routerPush).not.toHaveBeenCalled()

    resolveLogout()
    await flushPromises()

    expect(messageSuccess).toHaveBeenCalledWith('已退出登录')
    expect(routerPush).toHaveBeenCalledWith('/')
  })
})
```

运行：

```bash
cd frontend && pnpm vitest run src/App.spec.js
```

预期（RED）：2 + 1 = 3 例，新例红在第一条 `not.toHaveBeenCalled()`（现状是同步提示 + 同步跳转）：

```
 FAIL  src/App.spec.js > App.vue 退出登录 > 后端未返回前不提示，返回后才提示成功并跳首页
AssertionError: expected "spy" to not be called at all, but actually been called with [ Arguments: [ '已退出登录' ] ]
```

### Step 4-2：拦截器加 silent 分支（GREEN，api）

`frontend/src/api/index.js` 的错误分支（`:53-68`）改为：

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

并把 `authApi.logout`（`:77`）改为：

```js
  logout: () => api.post('/auth/logout', null, { silent: true }),
```

（第二参必须显式 `null`：`axios.post(url, data, config)` 的三参位置固定，把 config 放第二位会被当成请求体。）

### Step 4-3：`App.vue` 改 async + await（GREEN，App）

```js
async function handleUserCommand(command) {
  if (command === 'profile') {
    router.push('/profile')
  } else if (command === 'admin') {
    router.push('/admin')
  } else if (command === 'logout') {
    // 等后端尽力（成功或失败都已在 store 内部处理完）再宣布结果，
    // 否则后端 15s 超时窗口里提示已经出现，与事实不符。
    await auth.logout()
    // 角标/列表/分页等会话状态由 useNotificationStream 的 token watch 统一清空，
    // 这里不再各自清一半，避免登出/换号两条路径的重置逻辑漂移。
    ElMessage.success('已退出登录')
    router.push('/')
  }
}
```

运行：

```bash
cd frontend && pnpm vitest run src/App.spec.js src/api/index.spec.js src/stores/auth.spec.js
```

预期：

```
 Test Files  3 passed (3)
      Tests  3 + 17 + 4 = 24 passed
```

（`index.spec.js` 15 → 17、`auth.spec.js` 3 → 4、`App.spec.js` 2 → 3。）

### Step 4-4：全量验证并提交

```bash
cd frontend && pnpm test
```

预期：

```
 Test Files  31 passed (31)
      Tests  318 passed (318)
```

（314 + 4 = 318；spec 文件数不变。）

```bash
cd /e/JavaSpace/forum
git add frontend/src/api/index.js frontend/src/App.vue \
        frontend/src/api/index.spec.js frontend/src/stores/auth.spec.js frontend/src/App.spec.js
git commit -m "fix: 前端登出静默处理 401 并保证清态时序"
```

---

## Task 5：主题选择跨标签页同步（R5）

**Files:**

- Modify: `frontend/src/composables/useTheme.js`
- Modify: `frontend/src/composables/useTheme.spec.js`

### Step 5-1：先写 3 例 storage 同步测试（RED）

在 `useTheme.spec.js` 的 `describe('useTheme')` 末尾（`'initTheme 在已保存 dark 时返回 true'` 之后）追加：

```js
  it('其他标签页切到 dark 后本页同步（storage 事件）', async () => {
    const theme = await loadTheme()

    localStorage.setItem(STORAGE_KEY, 'dark')
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: STORAGE_KEY,
        newValue: 'dark',
        storageArea: localStorage
      })
    )
    await nextTick()

    expect(theme.mode.value).toBe('dark')
    expect(theme.isDark.value).toBe(true)
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('其他标签页清空主题（key 为 null）时回退 system', async () => {
    localStorage.setItem(STORAGE_KEY, 'dark')
    const theme = await loadTheme()
    expect(theme.mode.value).toBe('dark')

    localStorage.removeItem(STORAGE_KEY)
    window.dispatchEvent(
      new StorageEvent('storage', { key: null, newValue: null, storageArea: localStorage })
    )
    await nextTick()

    expect(theme.mode.value).toBe('system')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('其他标签页写入坏值时回退 system，且不把坏值写回 localStorage', async () => {
    const theme = await loadTheme()

    localStorage.setItem(STORAGE_KEY, 'purple')
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: STORAGE_KEY,
        newValue: 'purple',
        storageArea: localStorage
      })
    )
    await nextTick()

    expect(theme.mode.value).toBe('system')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
    // 不写回：写回会再次触发其他标签页的 storage 事件，形成回环
    expect(localStorage.getItem(STORAGE_KEY)).toBe('purple')
  })
```

若文件顶部还没有 `nextTick` 的导入，补上：

```js
import { nextTick } from 'vue'
```

运行：

```bash
cd frontend && pnpm vitest run src/composables/useTheme.spec.js
```

预期（RED）：17 + 3 = 20 例，3 例红在第一条模式断言：

```
 FAIL  src/composables/useTheme.spec.js > useTheme > 其他标签页切到 dark 后本页同步（storage 事件）
AssertionError: expected 'system' to be 'dark'
```

> 若 jsdom 拒绝 `storageArea` 选项（抛 `TypeError`），先去掉 `storageArea` 再跑：实现里的守卫是 `event.storageArea && event.storageArea !== localStorage`，缺省（`undefined`）会正常放行，断言不受影响。不要为了迁就 jsdom 改实现。

### Step 5-2：实现模块级 storage 监听（GREEN）

`useTheme.js` 在 `watchEffect`（`:96-101`）之后、`initTheme` 之前插入：

```js
// 跨标签页同步：其他标签页 setMode() 后会触发本页的 storage 事件。
// 不信任 event.newValue：key 被删除（removeItem/clear）时它是 null，坏值也需要回退 system，
// 统一从 storage 回读一次，与模块初始化走同一段判定（readStoredMode）。
function onStorageChange(event) {
  if (event.storageArea && event.storageArea !== localStorage) return
  if (event.key !== null && event.key !== STORAGE_KEY) return
  // 故意不写回 localStorage：写回会再次触发其他标签页的 storage 事件，形成回环。
  mode.value = readStoredMode()
}

let storageListenerBound = false

// 绑在模块顶层：useTheme() 被 App / ThemeToggle / 路由守卫多处调用，
// 绑在函数里会重复注册监听器。
function bindStorageListener() {
  if (storageListenerBound || typeof window === 'undefined') return
  window.addEventListener('storage', onStorageChange)
  storageListenerBound = true
}

bindStorageListener()
```

注意 `mode` 是模块内的 `ref`（`:21`），`useTheme()` 暴露的是 `readonly(mode)`；在模块内部直接写 `mode.value` 是正确入口，不要改成 `setMode()`（`setMode` 会 `persist` 写回，触发回环）。

运行同一命令，预期 `Tests: 20 passed`。

### Step 5-3：全量验证并提交

```bash
cd frontend && pnpm test
```

预期：

```
 Test Files  31 passed (31)
      Tests  321 passed (321)
```

（318 + 3 = 321。）

```bash
cd /e/JavaSpace/forum
git add frontend/src/composables/useTheme.js frontend/src/composables/useTheme.spec.js
git commit -m "feat: 主题选择跨标签页同步"
```

---

## Task 6：文档、全量验证与上线（R6）

**Files:**

- Modify: `README.md`
- Modify: `docs/DEVELOPMENT.md`
- Modify: `docs/deployment/README.md`
- Modify: `docs/superpowers/plans/2026-10-24-round8-quality-hardening.md`（勾选 checkbox，并入回填提交）

### Step 6-1：全量后端

```bash
cd /e/JavaSpace/forum
mvn -B clean test
```

预期：

```
[INFO] Tests run: 176, Failures: 0, Errors: 0, Skipped: 1
[INFO] BUILD SUCCESS
```

（`clean` 是为了确认没有增量编译残留；21 个测试类。）

### Step 6-2：全量前端

```bash
cd frontend && pnpm test
```

预期：

```
 Test Files  31 passed (31)
      Tests  321 passed (321)
```

### Step 6-3：构建

```bash
cd frontend && MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build
```

预期：`✓ built in ...`，产物在 `frontend/dist`；仍会出现 `index-*.js` 体积警告（本轮不做 chunk 分包，见 spec §2.2），**这是预期的**。

### Step 6-4：更新文档

#### (a) `README.md`

- 第 243 行：`当前为 **20 个测试类、163 个用例**` → `当前为 **21 个测试类、176 个用例**`（同句其余文字不动，包括「其中 1 个 POSIX 文件权限用例在 Windows 上跳过」）。
- 第 253 行：`当前为 **31 个 spec、314 个用例**` → `当前为 **31 个 spec、321 个用例**`。

#### (b) `docs/DEVELOPMENT.md` §3.1 代码规模

先量一遍（Git Bash，仓库根目录）：

```bash
find src/main/java -name '*.java' | wc -l
find src/main/java -name '*.java' -exec cat {} + | wc -l
find src/test/java -name '*.java' | wc -l
find src/test/java -name '*.java' -exec cat {} + | wc -l
find frontend/src -type f \( -name '*.js' -o -name '*.vue' \) ! -name '*.spec.js' | wc -l
find frontend/src -type f \( -name '*.js' -o -name '*.vue' \) ! -name '*.spec.js' -exec cat {} + | wc -l
find frontend/src -name '*.spec.js' | wc -l
find frontend/src -name '*.spec.js' -exec cat {} + | wc -l
```

把表格四行的「文件数 / 行数」换成实测值。注意口径：既有表格里前端产品代码**不含** `assets/` 静态资源（3 文件 53 行），若 `find` 结果包含了它们，按同一口径减掉并在括号里保留原说明。预期变化量：

| 行 | 变化 |
| --- | --- |
| 后端主代码 | 82 文件不变，行数 +约 25～35 |
| 后端测试 | 21 → **22** 文件，行数 +约 250～320 |
| 前端产品代码 | 37 文件不变，行数 +约 12 |
| 前端测试 | 31 文件不变，行数 +约 60 |

若实测文件数不是 82/22/37/31，先核对是不是数进了 `assets/` 或别的目录，再改表。

#### (c) `docs/DEVELOPMENT.md` §3.2 测试基线

- 表格「后端」行：`163` → `176`，`20` → `21`。
- 表格「前端」行：`314` → `321`（spec 数 31 不变）。
- 构建行不动。
- 后端类分布：`LoginRateLimiterTest` 9 → **12**；`AuthInvalidationIntegrationTest` 6 → **8**；`PostFlowIntegrationTest` 4 → **5**；在 `JwtTokenProviderTest` 4 之后插入 `JwtAuthenticationFilterTest` **7**。
- 前端 spec 分布：`useTheme` 17 → **20**；`index`（api）15 → **17**；`auth` 3 → **4**；`App` 2 → **3**。
- 在分布行末尾追加一句复核提示（该行历史上加起来比总数少 4，属既有记录偏差）：
  > 分布行历史上与实际总数有 4 例偏差（2026-10-24 复核）；以此处标注的合计与 `mvn` 实际输出为准。

#### (d) `docs/DEVELOPMENT.md` §3.4 接口清单

在全文搜索段末尾那句「关键词里的 `%`、`_`、`!` 按字面量匹配（服务端统一转义后 SQL 用 `ESCAPE '!'`）」之后补半句：

> （首页 `GET /api/posts?keyword=` 在第八轮起同样如此）

端点总数 39 不变，不要动表头与计数。

#### (e) `docs/DEVELOPMENT.md` §4.12 已知取舍

1. 在 `#### 已知取舍（代码审查记录）` 的列表**末尾**追加一行指针：
   > 上述编号 1、2、3、5、6 已在第八轮修复，修复记录见 §4.13；本轮新暴露的取舍也记在那里。
2. 删除以下五条（内容已在 §4.13 覆盖）：
   - 第 1 条 `DataAccessException → 500 且响应体不是 Result`
   - 第 2 条 `封禁双保险没有独立用例`
   - 第 3 条 `重复登出不幂等（双 toast + 跳转）`
   - 第 5 条 `cleanupExpired 迭代器按 key 删除的竞态 + 4 个阈值无校验`
   - 第 6 条 `每条 429 一条不含 IP 的 WARN`
3. 保留第 4、7、8、10、11 条；第 9 条改写成：
   > 9. 测试加固仍缺两处：`token_version` 非零值持久化的端到端断言、`validate()` 成功路径的独立单测；「登出先调后端后清本地」已由 `stores/auth.spec.js` 覆盖（第八轮）。
4. 剩余条目重新编号为 1–6（4→1、7→2、8→3、9→4、10→5、11→6），保持 Markdown 有序列表连续。

#### (f) `docs/DEVELOPMENT.md` 新增 §4.13

在 §4.12 的「修复记录（2026-10-24）」段落之后、`## 5. 待办与后续计划` 之前插入（把 `<h1>`…`<h5>` 换成 Task 1–5 的实际短哈希，用 `git log --format='%h %s' -6` 取）：

```markdown
### 4.13 第八轮：质量加固（2026-10-24）

- 设计：[2026-10-24-round8-quality-hardening-design.md](superpowers/specs/2026-10-24-round8-quality-hardening-design.md)
- 计划：[2026-10-24-round8-quality-hardening.md](superpowers/plans/2026-10-24-round8-quality-hardening.md)

**目标**：收敛第七轮双审遗留的 Important 项与 T12/T13 两条技术债；不新增用户可见功能（主题跨标签同步是唯一 UX 修复）。

**修复清单**：

| # | 问题 | 修法 | 提交 |
| --- | --- | --- | --- |
| 1 | 过滤器会话回查遇 `DataAccessException` 抛 500，响应体不是统一 `Result` | `isSessionAlive` 外层 catch → `log.debug` + `return false`（fail-closed，401 由 entry point 返回） | `<h1>` |
| 2 | 封禁双保险没有各自动独立的用例 | 直接改 `status`（不动版本）→ 401；封禁 → 解封 → 旧 token 仍 401（版本不回退） | `<h1>` |
| 3 | 限流器阈值无校验；`cleanupExpired` 按 key 盲删 | 构造器 fail fast（消息含配置全名）；`windows.remove(key, window)` 身份 CAS | `<h2>` |
| 4 | 每条 429 一条不含 IP 的 WARN | 处理器降为 `log.debug`；事实日志保留在 `recordFailure` | `<h2>` |
| 5 | `Retry-After` 断言依赖「同一秒」（`isIn("59","60")`） | 解析为 long 后 `isBetween(1L, 60L)` | `<h2>` |
| 6 | 首页 `keyword` 不转义 LIKE 通配符（T13） | service 先 `escapeLike`，`searchIds` 两处 LIKE 加 `ESCAPE '!'` | `<h3>` |
| 7 | 重复登出：双提示 + 整页跳转 | `authApi.logout` 带 `{ silent: true }`；401 静默分支仍清本地态 | `<h4>` |
| 8 | 登出不 await，后端未返回就提示成功 | `handleUserCommand` 改 `async`，`await auth.logout()` 后再提示与跳转 | `<h4>` |
| 9 | 主题选择不跨标签页同步（T12） | `useTheme` 模块级 `storage` 监听，回读 `localStorage`、不写回（避免回环） | `<h5>` |

**测试与验证**：后端 163 → **176** 用例 / 20 → **21** 类（新增 `JwtAuthenticationFilterTest` 7 例；`AuthInvalidationIntegrationTest` +2、`LoginRateLimiterTest` +3、`PostFlowIntegrationTest` +1）；前端 314 → **321** 用例 / 31 spec 不变（`api/index.spec` +2、`stores/auth.spec` +1、`App.spec` +1、`useTheme.spec` +3）。端点 39 个不变，无迁移、无新配置项、无新增依赖。

**已知限制**：本轮没有新增部署要求（无迁移、无配置项、无 nginx 改动），见 `docs/deployment/README.md` §7 末尾。保留的取舍见 §4.12。

**上线验收**：（Task 6 上线后回填：Build 号、镜像 tag、冒烟结果）
```

#### (g) `docs/DEVELOPMENT.md` §5.1 技术债

T12 与 T13 两行改成「保留原文 + 删除线 + 已关闭」的样式（与 T4/T5 的既有写法一致）：

```markdown
| T12 | ~~**主题选择不跨标签页同步**~~ | 已关闭 | 2026-10-24 第八轮：`useTheme` 增加模块级 `storage` 监听，回读 localStorage 且不写回（避免回环），坏值/清空回退 system（见 §4.13）。原记录：主题存 localStorage 的 jf_theme，但未监听 storage 事件；同源多标签下切换主题不会互相更新，刷新后一致。修法：在 useTheme 里监听 window.addEventListener('storage', ...) |
| T13 | ~~**首页关键词过滤不转义 LIKE 通配符**~~ | 已关闭 | 2026-10-24 第八轮：`PostService.list` 先 `escapeLike`，`searchIds` 两处 LIKE 加 `ESCAPE '!'`，与 `/api/posts/search` 同一约定（见 §4.13）。原记录：首页 GET /api/posts?keyword= 走 searchIds 的 LOWER(...) LIKE LOWER(CONCAT('%', :keyword, '%'))，未转义 %/_，搜「100%」会命中「1000」。本轮只给新搜索接口做了转义；修首页需同步改 JPQL 与前端提示，属独立小改动 |
```

#### (h) `docs/DEVELOPMENT.md` §5.2 候选功能

在 §5.2 末尾那段「第三轮已取前两项…」之后补一句：

> 第八轮同样不加业务功能，只做质量加固（见 §4.13）；「限流改共享存储」仍是多实例部署的前置条件。

#### (i) `docs/DEVELOPMENT.md` §8 变更日志

在表格**最上方**（`> 新增条目请加在表格最上方` 之后、现有第一行之前）插入 6 行，顺序为最新在前：

```markdown
| 2026-10-24 | docs | 记录第八轮质量加固（测试基线、已知限制与变更日志） | 本次提交 |
| 2026-10-24 | feat | 主题选择跨标签页同步（T12） | `<h5>` |
| 2026-10-24 | fix | 前端登出静默处理 401 并保证清态时序 | `<h4>` |
| 2026-10-24 | fix | 首页关键词过滤转义 LIKE 通配符（T13） | `<h3>` |
| 2026-10-24 | fix | 登录限流清理竞态与配置校验加固 | `<h2>` |
| 2026-10-24 | fix | JWT 过滤器会话回查失败按未认证处理并补测试 | `<h1>` |
```

#### (j) `docs/deployment/README.md` §7 已知限制

在 §7 列表**末尾**追加一条：

```markdown
- **第八轮（2026-10-24 质量加固）没有新增部署要求**：无数据库迁移、无新配置项、无 nginx/location 改动；发布即镜像替换（Jenkins 自动构建 + `deploy.sh`）。回滚只需把镜像 tag 切到上一版并重跑 `deploy.sh`。
```

#### (k) 本计划勾选

把 Task 1–6 的 `- [ ]` 全部改成 `- [x]`（**这一步并入步骤 6-8 的回填提交**）。

### Step 6-5：提交文档

```bash
cd /e/JavaSpace/forum
git status --short
git add README.md docs/DEVELOPMENT.md docs/deployment/README.md
git commit -m "docs: 记录第八轮质量加固（测试基线、已知限制与变更日志）"
```

预期 `git status --short` 只显示这 3 个 ` M`（计划文件此时**不** add——它和回填一起提交，见 6-8）。

### Step 6-6：推送并读 Jenkins 状态

```bash
git push git@github.com:ScholarOfDeepDarkFantasy/forum.git master:master
```

推送成功后记下本次 HEAD 的 6 个短哈希：

```bash
git log --format='%h %s' -7
```

轮询 Jenkins（pollSCM，最长 5 分钟）。构建号用下面命令找最新：

```bash
python /tmp/forum-ssh.py "ls -1 /var/lib/jenkins/jobs/javaforum/builds | sort -n | tail -3"
```

等构建结束后读状态（把 `<N>` 换成实际构建号）：

```bash
python /tmp/forum-ssh.py "sudo grep -o 'Finished: [A-Z]*' /var/lib/jenkins/jobs/javaforum/builds/<N>/log"
```

预期：`Finished: SUCCESS`。失败时先取日志尾部再决定是否回滚：

```bash
python /tmp/forum-ssh.py "sudo tail -60 /var/lib/jenkins/jobs/javaforum/builds/<N>/log"
```

### Step 6-7：上线冒烟

按**附录 B** 逐条执行，把结果（Build 号、镜像 tag、各检查的 HTTP 状态与关键响应片段）记下来，供 Step 6-8 回填。

### Step 6-8：回填验收与哈希，第二次提交

1. `docs/DEVELOPMENT.md` §4.13 的「**上线验收**」一行替换为实际内容（示例，按实际填）：
   ```markdown
   **上线验收（2026-10-24）**：push `<h1>…<h6 首位>` → Jenkins Build #<N> `Finished: SUCCESS` → 镜像 `<tag>` 部署。冒烟：`/api/health` 200；登录 200；`POST /api/auth/logout` 200；随后 `GET /api/auth/me` 401；重复 `POST /api/auth/logout` 401；`GET /api/posts?keyword=100%25` 200 且命中项标题均含字面量 `100%`（无此类数据时为 0 条）；对照 `keyword=100` 命中的条数 ≥ 上一项。无迁移、无配置变更。
   ```
2. §8 变更日志**最上方**再加一行：
   ```markdown
   | 2026-10-24 | docs | 回填第八轮提交哈希与上线验收（Build #<N>、冒烟通过） | 本次提交 |
   ```
3. 勾选本计划的 Task 1–6 checkbox。
4. 提交并推送：

```bash
git add docs/DEVELOPMENT.md docs/superpowers/plans/2026-10-24-round8-quality-hardening.md
git commit -m "docs: 回填第八轮提交哈希与上线验收"
git push git@github.com:ScholarOfDeepDarkFantasy/forum.git master:master
```

预期：共 **7 次提交**（Task 1–6 各一次 + 本次回填），工作区干净（`git status --short` 无输出）。

---

## 7. 新增 / 修改文件清单

### 7.1 新增

| 文件 | 行数（约） | 说明 |
| --- | --- | --- |
| `src/test/java/org/song/forum/security/JwtAuthenticationFilterTest.java` | 210 | 过滤器 7 例单测（真实 `JwtTokenProvider` + mock `UserRepository`） |
| `docs/superpowers/specs/2026-10-24-round8-quality-hardening-design.md` | 约 330 | 本轮设计规格 |
| `docs/superpowers/plans/2026-10-24-round8-quality-hardening.md` | 本文件 | 本计划 |

### 7.2 修改

| 文件 | 改动 |
| --- | --- |
| `src/main/java/org/song/forum/security/JwtAuthenticationFilter.java` | `@Slf4j`；`isSessionAlive` 查库包 try/catch `DataAccessException` → `debug` + `false` |
| `src/main/java/org/song/forum/security/LoginRateLimiter.java` | 构造器三处校验；`cleanupExpired` 改身份 CAS；删 `Iterator` import |
| `src/main/java/org/song/forum/exception/GlobalExceptionHandler.java` | 429 处理器 `log.warn` → `log.debug` |
| `src/main/java/org/song/forum/service/PostService.java` | `list()` 对非空 keyword 先小写 + `escapeLike` |
| `src/main/java/org/song/forum/repository/PostRepository.java` | `searchIds` 两处 LIKE 加 `ESCAPE '!'` |
| `src/test/java/org/song/forum/controller/AuthInvalidationIntegrationTest.java` | +2 例（status 单侧、封禁-解封不复活） |
| `src/test/java/org/song/forum/security/LoginRateLimiterTest.java` | +3 例（构造器三阈值） |
| `src/test/java/org/song/forum/controller/LoginRateLimitIntegrationTest.java` | `Retry-After` 断言改 `isBetween(1L, 60L)` |
| `src/test/java/org/song/forum/controller/PostFlowIntegrationTest.java` | +`@Order(5)` stage5 + `createPost` 辅助 |
| `frontend/src/api/index.js` | 错误分支加 `silent`；`authApi.logout` 三参 |
| `frontend/src/App.vue` | `handleUserCommand` 改 `async` + `await auth.logout()` |
| `frontend/src/api/index.spec.js` | `httpError` 加第 4 参；+2 例；logout 断言改三参 |
| `frontend/src/stores/auth.spec.js` | +1 例（请求瞬间 token 仍在） |
| `frontend/src/App.spec.js` | hoisted `routerPush`/`messageSuccess`；`element-plus` 局部 mock；`el-dropdown` 可 emit stub；+1 例 |
| `frontend/src/composables/useTheme.js` | 模块级 `storage` 监听（回读、不写回、单例绑定） |
| `frontend/src/composables/useTheme.spec.js` | +3 例 |
| `README.md` | 测试基线 176/21、321/31 |
| `docs/DEVELOPMENT.md` | §3.1/§3.2/§3.4、§4.12、新增 §4.13、§5.1、§5.2、§8 |
| `docs/deployment/README.md` | §7 追加「本轮无新增部署要求」 |

---

## 8. 测试数量预期

| 层 | 现状 | 新增 | 预计 |
| --- | --- | --- | --- |
| 后端用例 | 163 | +13 | **176** |
| 后端测试类 | 20 | +1 | **21** |
| 前端用例 | 314 | +7 | **321** |
| 前端 spec 文件 | 31 | 0 | **31** |
| HTTP 端点 | 39 | 0 | **39** |
| 数据库迁移 | V4 | 0 | **V4** |

逐任务核对（后端 / 前端）：

| Task | 本任务新增 | 完成后累计 |
| --- | --- | --- |
| 1 | +9（`JwtAuthenticationFilterTest` 7、`AuthInvalidationIntegrationTest` 2） | 172 / 21 类；前端 314 |
| 2 | +3（`LoginRateLimiterTest`） | 175 / 21 类；前端 314 |
| 3 | +1（`PostFlowIntegrationTest`） | 176 / 21 类；前端 314 |
| 4 | 前端 +4（`index.spec` 2、`auth.spec` 1、`App.spec` 1） | 后端 176；前端 318 |
| 5 | 前端 +3（`useTheme.spec`） | 后端 176；前端 321 |
| 6 | 0 | 176 / 21 类；321 / 31 spec |

算式（把这条留给复核者）：`163 + 7 + 2 + 3 + 1 = 176`、`314 + 2 + 1 + 1 + 3 = 321`。任务书写的 177 与清单不符，见 §0.5 裁决点 2。

---

## 9. 风险与回滚

| 风险 | 触发条件 | 处置 |
| --- | --- | --- |
| fail-closed 让 DB 抖动表现为「集体 401」 | 数据库重启/连接池耗尽期间访问受保护接口 | 先看 `/api/health` 与数据源日志确认 DB；**不要**因此回滚 Task 1——回滚只会把 401 换成 500 + 非 `Result` 体 |
| 首页关键词行为变化 | 有人依赖 `%` 通配 | 文档从未承诺通配；单独 revert Task 3 的提交即可 |
| 过滤器单测在新环境变红（依赖真实 JWT 解析） | jjwt 版本/密钥长度变化 | 检查 `SECRET` 是否 ≥ 32 字节；不要改成 mock `Claims` |
| `cleanupExpired` 返回值变化 | 身份 CAS 让删除计数变少（误删被避免） | 既有用例断言 2，若变 1 说明有窗口被并发替换，属正确行为；先核对而不是改断言 |
| 前端 `element-plus` 局部 mock 影响其它用例 | `App.spec.js` 的 mock 泄漏到别的文件 | Vitest 的 `vi.mock` 是文件级的，不会跨文件；若 `App.spec.js` 内部其它用例变红，检查是否有具名导入被覆盖 |
| `storage` 监听在真实浏览器抖动 | 某些浏览器 `storageArea` 不可比 | 可单独 revert Task 5；守卫是 `event.storageArea && ...`，不会误判 |
| 文档数字与实际不符 | 执行时多写/少写用例 | 以 `mvn`/`vitest` 实测为准，回头改 §8 与 README，但**不要**为了凑 176 删测试 |

回滚（按 Task 粒度，全部可单独 revert；无迁移、无配置，回滚后不需要数据修复）：

```bash
# 只回滚某一轮
git revert --no-edit <hash>     # 例：git revert --no-edit <h5> 回滚主题同步

# 回滚整轮（保留历史，不用 reset）
git revert --no-edit <h6..h1>   # 或逐个 revert 上表 5 个代码提交

git push git@github.com:ScholarOfDeepDarkFantasy/forum.git master:master
```

> 顺序必须是「先 revert 文档提交、再 revert 代码提交」或一次性按提交列表倒序 revert；不要 `git reset --hard` 已推送的 master，也不要在 revert 后 force push。

---

## 10. Self-Review

### 10.1 spec 覆盖

| spec 小节 | 计划落点 | 覆盖方式 |
| --- | --- | --- |
| §5 R1 代码 | Task 1 Step 1-2 | 完整 `isSessionAlive` 与注解/import |
| §5.3 7 例单测 | Task 1 Step 1-1 | 完整测试文件 + 预期 RED 原文 |
| §5.4 双保险用例 | Task 1 Step 1-3 | 两例源码 + 「特征化」标注 |
| §6.1 构造器校验 | Task 2 Step 2-2 | 完整构造器；消息格式见 §0.5-3 |
| §6.2 身份 CAS | Task 2 Step 2-3 | 完整 `cleanupExpired` + import 删除 |
| §6.3 日志降级 | Task 2 Step 2-5 | 精确替换 + grep 核对 |
| §6.4 `Retry-After` | Task 2 Step 2-4 | 精确片段 |
| §7 首页转义 | Task 3 Step 3-2/3-3 | service 片段 + 完整 `@Query` |
| §7.3 stage5 | Task 3 Step 3-1 | 完整用例 + `createPost` 辅助 |
| §8.4 silent | Task 4 Step 4-2 | 完整错误分支 + `authApi.logout` |
| §8.5 await | Task 4 Step 4-3 | 完整 `handleUserCommand` |
| §8.6 前端测试 | Task 4 Step 4-1 | 四段完整测试代码 |
| §9.3 storage 监听 | Task 5 Step 5-2 | 完整实现块 |
| §9.4 三个用例 | Task 5 Step 5-1 | 完整代码 + RED 原文 |
| §12 上线验收 | Task 6 Step 6-6~6-8 + 附录 B | 命令 + 预期 + 回填模板 |
| §13 风险 | §9 本计划 | 表格逐条对应 |

### 10.2 占位符扫描

执行前/后都应无输出（`<hash>` 是有意的模板占位，只在文档回填前存在）：

```bash
cd /e/JavaSpace/forum
grep -n 'TBD\|TODO\|待填\|待补充\|类似 Task\|此处省略\|xxx' docs/superpowers/plans/2026-10-24-round8-quality-hardening.md
grep -n '待填' docs/superpowers/specs/2026-10-24-round8-quality-hardening-design.md
```

本计划正文里出现的 `<N>`/`<tag>`/`<h1>` 都是**执行期才会确定的运行事实**（构建号、镜像 tag、提交哈希），不是待补的空白；执行完 Task 6 后它们应全部被真实值替换。

### 10.3 类型与命名一致性

| 概念 | 数据库 | Java / JS | JSON / HTTP | 配置 |
| --- | --- | --- | --- | --- |
| 会话有效性 | `users.status`、`users.token_version` | `isSessionAlive`、`UserTokenState` | — | — |
| 封禁 | `status='BANNED'` | `UserStatus.BANNED` | `{"status":"BANNED"}` | — |
| 限流阈值 | — | `maxFailures`/`windowMs`/`lockMs` | `Retry-After`（秒） | `app.security.login.max-failures` / `window-ms` / `lock-ms` |
| 关键词转义 | — | `escapeLike`（`!` 前缀） | `keyword` 查询参数 | — |
| 静默请求 | — | `error.config.silent === true` | — | — |
| 主题存储 | — | `STORAGE_KEY = 'jf_theme'`，值 `light`/`dark`/`system` | — | — |

交叉检查：

- 前端 `silent` 必须是 axios 的 config 字段（`api.post(url, data, config)`），不是 body。
- 后端 `Retry-After` 是**秒**的整数字符串；测试用 `Long.parseLong` 校验这个契约。
- `escapeLike` 的转义符 `!` 必须与 JPQL 的 `ESCAPE '!'` 一致；只改一边会让 `!` 本身失去字面量语义。
- `STORAGE_KEY` 在 `useTheme.js` 与 `useTheme.spec.js` 之间通过 import 共享，不硬编码字符串。

### 10.4 测试可运行性

- 后端：`JwtAuthenticationFilterTest` 只依赖 spring-boot-starter-test（JUnit 5 + Mockito + AssertJ + spring-test）与 jjwt，均已在依赖树里；不启动 Spring 上下文，秒级。
- `PostFlowIntegrationTest` 的 stage5 依赖前面 stage 的 `boardId`/`adminToken`/`userToken`（`@Order` 保证顺序）；单独跑 `-Dtest=PostFlowIntegrationTest` 也按 1→5 执行，不会因缺 `boardId` 失败。
- 前端：`App.spec.js` 新增的 `element-plus` 局部 mock 与 `el-dropdown` stub 只影响本文件；`useTheme.spec.js` 的 `vi.resetModules()` 会让模块级监听器在多实例间累积，但监听器只读不写，断言只看当前实例的 `mode`。
- jsdom 对 `StorageEvent` 的 `storageArea` 选项支持若异常，按 Step 5-1 的说明去掉该选项（断言不变）。
- 所有新测试都不依赖网络、时间（用 `MutableClock`/可控 promise）、不 sleep。

### 10.5 实现顺序与提交粒度

1. Task 1 先做（其它任务的文档/测试不依赖它，但它是本轮唯一改「鉴权主路径」的提交，越早暴露越好）。
2. Task 2/3 相互独立，可任意顺序；建议 2 → 3（小 → 大）。
3. Task 4/5 前端，互不依赖；建议 4 → 5（4 改拦截器，5 只改 composable）。
4. Task 6 必须最后（全量 + 文档 + 上线）。
5. 每个 Task 一次提交；提交信息与附录 A 逐字一致，不改标点、不加 scope。

### 10.6 已裁决的模糊点（复核用）

1. 后端计数 176（不是 177）：见 §0.5-2。
2. `JwtAuthenticationFilterTest` 用真实 provider：见 §0.5-5。
3. `silent` 不清 token、只静音 UI：见 §0.5-6。
4. 429 只降处理器日志、不动 `recordFailure`：见 §0.5-4。
5. `App.spec.js` 用 `importOriginal` 局部 mock：见 §0.5-8。
6. 第八轮记录新建 §4.13，§4.12 只留指针：见 §0.5-9。
7. 勾选并入回填提交，共 7 次提交：见 §0.1。

---

## 11. Execution Handoff

- [ ] Task 1：JWT 过滤器会话回查失败按未认证处理
- [ ] Task 2：限流器清理竞态与配置校验加固
- [ ] Task 3：首页关键词过滤转义 LIKE 通配符
- [ ] Task 4：前端登出静默处理 401 并保证清态时序
- [ ] Task 5：主题选择跨标签页同步
- [ ] Task 6：文档、全量验证与上线

执行时最容易写错的 7 个点：

1. `api.post('/auth/logout', null, { silent: true })` 的第二参不能省，否则 config 变成请求体。
2. 过滤器的 `catch (DataAccessException)` 必须**包住查库**；只包 `Long.valueOf` 等于没改，RED 用例仍会红。
3. 清理必须 `windows.remove(entry.getKey(), window)`；写成 `windows.remove(key)` 与现状等价，竞态依旧。
4. `ESCAPE '!'` 要加在**两处** LIKE（title 与 content），漏一处则通过正文仍能命中 `%`。
5. `escapeLike` 只对非空关键词调用；空串保持 `""`，否则 `:keyword = ''` 永远为假，首页列表直接空。
6. `App.spec.js` 的 `el-dropdown` stub 必须声明 `emits: ['command']`，否则 `$emit` 触发不了父组件的 `@command`。
7. `useTheme` 的监听必须绑在模块顶层并带 `storageListenerBound` 标志；写在 `useTheme()` 里会随每次调用重复注册。

---

## 附录 A：本轮提交信息（逐字）

```text
fix: JWT 过滤器会话回查失败按未认证处理并补测试
fix: 登录限流清理竞态与配置校验加固
fix: 首页关键词过滤转义 LIKE 通配符
fix: 前端登出静默处理 401 并保证清态时序
feat: 主题选择跨标签页同步
docs: 记录第八轮质量加固（测试基线、已知限制与变更日志）
docs: 回填第八轮提交哈希与上线验收
```

说明：共 7 条，与 Task 1–6 + 上线后回填一一对应；计划勾选并入最后一条，不再单独提交。每条都要在对应 Task 的 Step 末尾**原样**使用（`git commit -m "<该行>"`），不要加 `-m "…"` 第二段、不要加 Co-authored-by 等 trailer。

## 附录 B：Task 6 冒烟（raspi）

前置：

- 远端操作统一用 `python /tmp/forum-ssh.py "<远端命令>"`（本机→raspi 的 SSH 包装脚本，凭据已配好）。
- 后端容器名与端口沿用第七轮验收时的地址：直连 `http://127.0.0.1:8086`，经 nginx 用 `/forum/` 前缀。先确认容器在跑：
  ```bash
  python /tmp/forum-ssh.py "sudo podman ps --format '{{.Names}} {{.Status}} {{.Ports}}' | grep -i forum"
  ```
  预期至少一行含 `forum-backend` 与 `Up ... (healthy)`。若端口不是 8086，以 `podman ps` 输出为准，下面命令同步替换。
- 每步把结果记下来，供 Step 6-8 回填。

### B.1 健康

```bash
python /tmp/forum-ssh.py "curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8086/api/health"
python /tmp/forum-ssh.py "curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1/forum/api/health"
```

预期：两条都输出 `200`。

### B.2 登录拿 token

```bash
python /tmp/forum-ssh.py "curl -s -X POST http://127.0.0.1:8086/api/auth/login -H 'Content-Type: application/json' -d '{\"username\":\"admin\",\"password\":\"admin123456\"}'"
```

预期：200，响应体 `success:true` 且 `data.token` 非空。把 token 记作 `$T`（下面用占位符 `<T>`）。

### B.3 登出 → me 401 → 重复登出 401

```bash
python /tmp/forum-ssh.py "curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8086/api/auth/logout -H 'Authorization: Bearer <T>'"
python /tmp/forum-ssh.py "curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8086/api/auth/me -H 'Authorization: Bearer <T>'"
python /tmp/forum-ssh.py "curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8086/api/auth/logout -H 'Authorization: Bearer <T>'"
```

预期：`200` → `401` → `401`。第三条就是本轮 R4 静默分支对应的服务端行为（浏览器端不再双提示 + 整页跳转，由 `api/index.spec.js` 的两例锁定）。

### B.4 首页关键词按字面量匹配

```bash
python /tmp/forum-ssh.py "curl -sG --data-urlencode 'keyword=100%' 'http://127.0.0.1:8086/api/posts?size=5'"
```

预期：200；`totalElements` 为 0（线上若无含字面量 `100%` 的标题），或每个 `content[].title` 都含字面量 `100%`。**不应**出现只有 `1000` 而没有 `%` 的标题。

### B.5 负向对照（证明查询仍工作）

```bash
python /tmp/forum-ssh.py "curl -sG --data-urlencode 'keyword=100' 'http://127.0.0.1:8086/api/posts?size=5'"
```

预期：200，且 `totalElements` ≥ B.4 的条数（`100` 是 `100%` 的超串，命中集合只会更大）。若 B.4/B.5 都是 0，改用一个确定存在的关键词（例如某条真实帖子的标题片段）重做这两步，并在回填里注明用的是哪个词。

### B.6 前端资源与镜像

```bash
python /tmp/forum-ssh.py "sudo podman images --format '{{.Repository}}:{{.Tag}} {{.CreatedSince}}' | grep -i forum | head -3"
python /tmp/forum-ssh.py "curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1/forum/"
```

预期：镜像 tag 与本次 Build 号对应（部署脚本打 tag 的方式沿用第七轮），`/forum/` 返回 `200`。

> 主题跨标签同步无法用 curl 验证：由 `useTheme.spec.js` 的 3 例锁定。如需人工验收：浏览器开两个 `/forum/` 标签页，在 A 页切换主题，B 页应立刻跟随（不需要刷新）；A 页切回「跟随系统」时 B 页也应回退。

### B.7 回填

把 B.1–B.6 的实际输出填进 `docs/DEVELOPMENT.md` §4.13「上线验收」，并按 Step 6-8 回填 §8 哈希行与计划 checkbox，然后第二次 push。

---

## 12. 实施记录与偏差（2026-10-24）

**实际提交 9 次**（计划为 7 次）：除 Task 1–6 各一次外，多出两个 test-only 收尾提交 `ef791fb`（`fix: 补齐首页搜索正文分支的通配符转义守护`）与 `94b1970`（`test: 加固登出时序与主题跨标签的回归守护`）。原因：规格/质量双审发现测试守护不足（正文分支的 `ESCAPE` 无独立回归、登出「清态等待响应」与主题跨标签的断言强度不够），修复记录见 `docs/DEVELOPMENT.md` §4.13 表第 10、11 行。两个提交只改测试与注释，不改变用例数。

**测试基线不变**：后端 176 用例 / 21 个测试类（1 skipped），前端 321 用例 / 31 spec；端点 39 个；无迁移、无新配置项、无新增依赖。

**与计划文本的三处已知不一致及取舍**：

1. 计划 Task 3 的示意查询是 `select p.id` + `Page<Long>`，与 Step 3-2 及基线实现（`SELECT p` + `EntityGraph`）矛盾；实现按基线走，只给既有查询加 `escapeLike` 与 `ESCAPE '!'`，不改查询形状（改形状会破坏既有分页与排序断言）。
2. 计划示意整段替换 `searchIds` 的 javadoc，但同一段正文又要求「不要删原注释」，两者矛盾；实现保留原注释，只做必要订正（`PostService` 空串分支的注释在 `ef791fb` 纠正）。
3. 计划 Task 5 预测 RED 为 3 红：实际必然只有 2 红，第 3 例（「坏值从显式 dark 起步」）在没有实现时是平凡成立的；已在 `94b1970` 把该例改为从显式 dark 起步，使它在无实现时不成立，恢复 RED 的有效性。

**文档编辑偏差**：计划 Step 6-4 标注的行号基于 `d56a8aa`，本轮编辑前均先 `grep -n` 实测再改；§8 变更日志按实际 9 次提交写成 8 行（docs 行「本次提交」+ 7 个已有哈希），§4.13 修复清单写成 11 行。另：README/DEVELOPMENT 的代码规模与测试分布数字按 `find` / `target/surefire-reports` / `pnpm test` 实测填写，未采用计划里的估算增量。
