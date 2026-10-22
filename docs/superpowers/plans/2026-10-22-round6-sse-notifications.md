# 第六轮：实时通知（SSE）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 已登录用户在任何页面都能即时收到新通知：顶栏角标 +1、右上角轻提示、通知页第 1 页头部插入；连接断开后按 1s/2s/4s/8s/16s/30s 指数退避自动重连，断线期间恢复 120 秒轮询兜底，重连成功后刷新角标并停止轮询。

**Architecture:** 后端在 Spring MVC 的 `SseEmitter` 上维护「userId → 连接集合」的内存注册表（每用户上限 5 条），通知在数据库事务 `afterCommit` 之后写入该用户的连接，避免「已推送但事务回滚」的不一致。浏览器 `EventSource` 不能携带 `Authorization` 头，因此鉴权改用 60 秒有效、用一次即焚的 ticket：`POST /api/notifications/stream-ticket` 取票 → `GET /api/notifications/stream?ticket=` 建流。前端一个 composable 管理「取票 → 建连 → 收事件 → 退避重连 → 兜底轮询」的完整状态机，Pinia store 负责去重、角标与列表合并，视图只负责横幅与列表渲染。

**Tech Stack:** Spring Boot 3.5.6 / Java 17 / Spring MVC `SseEmitter` / `@Scheduled` 心跳 / JUnit 5 + AssertJ + Mockito + `TestRestTemplate` + JDK `HttpClient`；Vue 3 + Pinia + Element Plus + Vite + Vitest + `@vue/test-utils`；nginx。**不新增任何依赖、不做数据库迁移**，`pom.xml`、`package.json`、`Dockerfile`、`docker-compose.yml` 均不改。

---

## 0. 执行约定

1. **分支与提交**：在 `master`（当前 HEAD=`a677986`）上工作；每个 Task 结束时提交一次，共 6 个 commit（Task 1–5 各一个 + Task 5 末尾的哈希回填），提交信息逐字使用下面 6 条，不合并、不 rebase：
   - Task 1：`feat: 增加 SSE 通知基础设施（ticket 鉴权 + 连接注册表）`
   - Task 2：`feat: 通知创建在事务提交后实时推送`
   - Task 3：`feat: 前端接入 SSE 实时通知与角标`
   - Task 4：`feat: 通知页支持即时插入与新通知横幅`
   - Task 5（部署+文档）：`docs: 记录第六轮实时通知（SSE）并上线验收`
   - Task 5（回填）：`docs: 回填第六轮提交哈希与上线验收`
2. **TDD 顺序**：每个后端 Step 都是「先写/改测试 → 跑测试确认失败（或确认失败原因符合预期）→ 写实现 → 再跑通过」。计划里已经按这个顺序排好；若某步的测试在写实现前因**编译失败**而不是断言失败而红，这在本项目里是允许的（Java 强类型），在提交说明里不必解释。
3. **命令环境**：Windows + Git Bash（或 PowerShell 中执行等价命令）。所有命令都在仓库根 `E:\JavaSpace\forum` 下执行。前端安装依赖统一用 `pnpm`；构建必须显式覆盖 `VITE_BASE_PATH`：
   ```bash
   MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build
   ```
4. **测试基线（本轮开始前）**：后端 **13 个测试类 / 105 个用例**；前端 **28 个 spec 文件 / 274 个用例**。本轮结束后预期：后端 **16 类 / 127 用例**；前端 **30 文件 / 295 用例**。Task 各步骤的「预期输出」按增量写，最终以 Task 5 的全量数字为准。
5. **执行方式**：实现时由子代理**逐任务执行**本计划（一个 Task 一个子代理会话），每个 Task 完成后先做 **spec review**（对照设计文档 §2/§5/§6/§7/§8 核对行为与契约），再做 **quality review**（可读性、命名、并发/异常处理、测试是否真的能失败）。两个 review 都通过才进入下一个 Task。
6. **设计文档中的模糊点与本计划的裁决**（不改 spec，只在这里记录）：
   - spec §5.2 把 ticket 服务命名为 `NotificationTicketService`，本任务书要求 `StreamTicketService`；**本计划用 `StreamTicketService`**，并保留 spec 的签名语义（`issue(userId)` / `consume(ticket)` 返回 `Long`）。
   - 任务书 Task 3 写「断线期间恢复轮询 60s」，spec §6.1 与 §13 写 **120s**（沿用原有轮询间隔）；**本计划按 spec 用 120s**，常量命名 `FALLBACK_POLL_MS`。
   - 任务书 Task 4 写 store 增 `addLive`，spec §6.2 叫 `handleIncoming`；**本计划用 `handleIncoming`**。
   - spec §6.1「ticket 请求 401 → 不再重连」与 §9.2「401（ticket 失效）走重连」表面冲突，实际是两种 401：**取 ticket 接口返回 401** → 会话已失效，停止；**EventSource 因流端点 401 而 error** → 属于连接失败，按退避重连（会重新取票）。本计划按此区分实现并分别写测试。
   - spec 的 `app.sse.*` 只有 3 个配置项；本计划额外增加 `app.sse.max-connections-per-user`（默认 5），否则「每用户上限 5」是魔法数字。

## 1. 现状与差距

| 现状 | 本轮变化 |
| --- | --- |
| 通知只落库；前端 `App.vue` 每 120 秒轮询一次未读数 | 落库后在事务提交后主动推送；前端改为 SSE + 退避重连，轮询只在断线期间兜底 |
| 通知页只在进入时拉列表，停留期间新通知要手动刷新 | 第 1 页停留时头部插入；非第 1 页显示「有 N 条新通知，点击查看」 |
| 无 ticket 概念，SSE 端点若直接放行则完全匿名 | 一次性 ticket（60s TTL）鉴权；`GET /api/notifications/stream` 显式放行并由 ticket 自校验，无票 401 |
| `NotificationService.create` 只 `save`；3 处调用点各自拼 `Notification.builder()` | 统一到 `NotificationService.create(...)`，并在 `afterCommit` 推送 |

---

## Task 1：后端 SSE 基础设施

**Files:**
- Create:
  - `src/main/java/org/song/forum/exception/InvalidStreamTicketException.java`
  - `src/main/java/org/song/forum/service/StreamTicketService.java`
  - `src/main/java/org/song/forum/service/NotificationStreamService.java`
  - `src/main/java/org/song/forum/config/SseConfig.java`
  - `src/main/java/org/song/forum/dto/notification/StreamTicketResponse.java`
  - `src/test/java/org/song/forum/service/StreamTicketServiceTest.java`
  - `src/test/java/org/song/forum/service/NotificationStreamServiceTest.java`
  - `src/test/java/org/song/forum/controller/SseFlowIntegrationTest.java`
- Modify:
  - `src/main/resources/application.yml`
  - `src/test/resources/application-test.yml`
  - `src/main/java/org/song/forum/exception/GlobalExceptionHandler.java`
  - `src/main/java/org/song/forum/controller/NotificationController.java`
  - `src/main/java/org/song/forum/config/SecurityConfig.java`

### Step 1-1：增加 `app.sse.*` 配置

`src/main/resources/application.yml` 现在末尾的 `app:` 段只有 `upload-dir`。把该段替换为：

```yaml
# ---- 上传文件存储目录（生产通过 UPLOAD_DIR 指向挂载卷） ----
# 本地开发默认落在工作目录 ./uploads；头像写入其下的 avatars/ 子目录
app:
  upload-dir: ${UPLOAD_DIR:./uploads}
  # ---- 实时通知（SSE） ----
  # 单条连接最长存活 30 分钟：到期由客户端重连，避免连接长期存活时登出/封禁不生效
  sse:
    timeout-ms: ${SSE_TIMEOUT_MS:1800000}
    # 心跳用 SSE 注释帧（":heartbeat"），15 秒一次，用于穿透代理的空闲超时
    heartbeat-ms: ${SSE_HEARTBEAT_MS:15000}
    # 每个用户最多保留的连接数（多标签页），超出时关闭最早的一条
    max-connections-per-user: ${SSE_MAX_CONNECTIONS_PER_USER:5}
    # 订阅 ticket 有效期：一次性、60 秒，避免 JWT 出现在 URL / nginx access log 里
    ticket-ttl-ms: ${SSE_TICKET_TTL_MS:60000}
```

`src/test/resources/application-test.yml` 末尾追加（测试里把超时与心跳调短，否则集成测试要等 15 秒才收到第一行心跳）：

```yaml

app:
  sse:
    # 单测/集成测试不需要真实长连接：4 秒内一定收到事件或超时，测试不会挂住
    timeout-ms: 4000
    heartbeat-ms: 200
    max-connections-per-user: 5
    ticket-ttl-ms: 3000
```

> 说明：集成测试类会在 `@SpringBootTest(properties=...)` 里再覆盖一次 `app.sse.*`（见 Step 1-9），profile 文件只是兜底，保证任何忘记覆盖的测试也不会挂 30 分钟。

验证（只编译，配置项还没有消费者）：

```bash
mvn -B -q -DskipTests compile
```

预期输出：无输出（`-q`），退出码 0。

### Step 1-2：新增 `InvalidStreamTicketException` 与 401 处理器

创建 `src/main/java/org/song/forum/exception/InvalidStreamTicketException.java`：

```java
package org.song.forum.exception;

/**
 * SSE 订阅凭证（ticket）缺失、无效、过期或已被使用。
 * <p>单独建异常而不是复用 BusinessException：后者会被映射成 400，
 * 而前端/nginx 冒烟都依赖「无票或废票必须是 401」这一语义。</p>
 */
public class InvalidStreamTicketException extends RuntimeException {

    public InvalidStreamTicketException(String message) {
        super(message);
    }
}
```

在 `src/main/java/org/song/forum/exception/GlobalExceptionHandler.java` 的 `handleNotFound` 方法之后、`/** 兜底 → 500 */` 之前插入：

```java
    /**
     * SSE 订阅 ticket 无效 → 401。
     * <p>不能落到 Exception 兜底：那会返回 500，nginx 冒烟与前端都无法区分
     * 「没有权限」和「服务端坏了」。ticket 一次性，过期/重放都必须拒绝。</p>
     */
    @ExceptionHandler(InvalidStreamTicketException.class)
    public ResponseEntity<Result<Void>> handleInvalidStreamTicket(InvalidStreamTicketException e) {
        log.warn("SSE 订阅凭证校验失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.fail(e.getMessage()));
    }
```

同包（`org.song.forum.exception`），不需要新增 import。

验证：

```bash
mvn -B -q -DskipTests compile
```

预期：退出码 0。

### Step 1-3：实现一次性 ticket 服务

创建 `src/main/java/org/song/forum/service/StreamTicketService.java`：

```java
package org.song.forum.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 订阅票据：一次性、短 TTL、只存在服务端内存里。
 *
 * <p>为什么需要 ticket：浏览器的 EventSource 不能设置请求头，JWT 只能拼进 URL；
 * 而 URL 会落进 nginx access log、浏览器历史与 Referer。ticket 泄露窗口只有 TTL
 * （默认 60 秒）且用一次即焚，风险远小于把 24 小时有效的 JWT 挂在 ?token= 上。</p>
 *
 * <p>清理策略：每次签发顺带删除已过期条目，消费时也立即删除；因此 map 大小
 * 上限约等于「TTL 内签发的票据数」，不会随运行时长无限增长。</p>
 */
@Service
public class StreamTicketService {

    private final long ttlMs;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    public StreamTicketService(@Value("${app.sse.ticket-ttl-ms:60000}") long ttlMs) {
        this.ttlMs = ttlMs;
    }

    /** 签发一张 ticket（同一用户可同时持有多张，对应多个标签页） */
    public String issue(Long userId) {
        purgeExpired();
        String ticket = UUID.randomUUID().toString();
        tickets.put(ticket, new Ticket(userId, System.currentTimeMillis() + ttlMs));
        return ticket;
    }

    /**
     * 消费 ticket：有效则返回 userId 并立刻删除（一次性）。
     * 空值、未知、已用、过期一律返回 null，由调用方统一转 401。
     */
    public Long consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        Ticket found = tickets.remove(ticket);
        if (found == null || found.expiresAt() < System.currentTimeMillis()) {
            return null;
        }
        return found.userId();
    }

    /** ticket 有效期（秒）；集成测试断言 expiresIn=60 时用 */
    public long ttlSeconds() {
        return ttlMs / 1000;
    }

    /** 当前存活票据数，供测试与排障使用 */
    int ticketCount() {
        return tickets.size();
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        tickets.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
    }

    private record Ticket(Long userId, long expiresAt) {
    }
}
```

### Step 1-4：ticket 生命周期测试

创建 `src/test/java/org/song/forum/service/StreamTicketServiceTest.java`：

```java
package org.song.forum.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ticket 的四个不变量：只能用一次、过期即废、空/未知值返回 null、过期项会被清理。
 * <p>这些分支在集成测试里很难稳定复现（要等真实时间），因此单独用短 TTL 单测。</p>
 */
class StreamTicketServiceTest {

    private static final Long USER_ID = 42L;

    @Test
    void 签发后可以消费一次并拿到用户id() {
        StreamTicketService service = new StreamTicketService(60_000);

        String ticket = service.issue(USER_ID);

        assertThat(ticket).isNotBlank().isNotEqualTo(service.issue(USER_ID));
        assertThat(service.consume(ticket)).isEqualTo(USER_ID);
    }

    @Test
    void 同一张ticket重复消费返回null() {
        StreamTicketService service = new StreamTicketService(60_000);
        String ticket = service.issue(USER_ID);

        assertThat(service.consume(ticket)).isEqualTo(USER_ID);
        assertThat(service.consume(ticket)).isNull();
    }

    @Test
    void 过期后消费返回null() throws InterruptedException {
        StreamTicketService service = new StreamTicketService(20);
        String ticket = service.issue(USER_ID);

        Thread.sleep(40);

        assertThat(service.consume(ticket)).isNull();
    }

    @Test
    void 空值与未知票据返回null() {
        StreamTicketService service = new StreamTicketService(60_000);

        assertThat(service.consume(null)).isNull();
        assertThat(service.consume("")).isNull();
        assertThat(service.consume("   ")).isNull();
        assertThat(service.consume("not-a-real-ticket")).isNull();
    }

    @Test
    void 下次签发时清理过期票据() throws InterruptedException {
        StreamTicketService service = new StreamTicketService(20);
        service.issue(USER_ID);
        service.issue(USER_ID);
        assertThat(service.ticketCount()).isEqualTo(2);

        Thread.sleep(40);
        service.issue(USER_ID);

        // 只剩新签发的这一张
        assertThat(service.ticketCount()).isEqualTo(1);
    }

    @Test
    void ttlSeconds向下取整到秒() {
        assertThat(new StreamTicketService(60_000).ttlSeconds()).isEqualTo(60);
        assertThat(new StreamTicketService(1_500).ttlSeconds()).isEqualTo(1);
    }
}
```

运行：

```bash
mvn -B test -Dtest=StreamTicketServiceTest
```

预期输出（节选）：

```text
[INFO] Running org.song.forum.service.StreamTicketServiceTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Step 1-5：连接注册表与心跳

创建 `src/main/java/org/song/forum/config/SseConfig.java`：

```java
package org.song.forum.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 只做一件事：打开 @Scheduled，让 NotificationStreamService 的 SSE 心跳按
 * app.sse.heartbeat-ms 周期执行。
 * <p>放在独立配置类而不是主应用类上，避免以后有人在测试里只加载部分配置
 * （@WebMvcTest 等）时意外启动所有定时任务。</p>
 */
@Configuration
@EnableScheduling
public class SseConfig {
}
```

创建 `src/main/java/org/song/forum/service/NotificationStreamService.java`：

```java
package org.song.forum.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.song.forum.dto.notification.NotificationResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在线 SSE 连接注册表：userId → 该用户当前所有 SseEmitter。
 *
 * <p>线程安全：Tomcat 的请求线程（register/onCompletion）、心跳线程（@Scheduled）
 * 与业务线程（push，afterCommit 可能就在请求线程里）会并发访问同一个 map，
 * 因此所有结构修改都在 {@code connections} 锁内完成；push/心跳先在锁内拷贝快照，
 * 再在锁外 send，避免某个慢客户端把锁占住、拖垮其他用户的推送。</p>
 *
 * <p>为什么是内存表：单实例部署（raspi 单容器）够用，且无新依赖；水平扩展时
 * 需要换成 Redis pub/sub 或消息队列，这一限制写进了文档（DEVELOPMENT §6）。</p>
 */
@Slf4j
@Service
public class NotificationStreamService {

    private final long timeoutMs;
    private final int maxConnectionsPerUser;

    /** 用 LinkedHashSet 保持注册顺序：超限时淘汰 iterator 的第一个即「最早的一条」 */
    private final Map<Long, Set<SseEmitter>> connections = new HashMap<>();

    public NotificationStreamService(
            @Value("${app.sse.timeout-ms:1800000}") long timeoutMs,
            @Value("${app.sse.max-connections-per-user:5}") int maxConnectionsPerUser) {
        this.timeoutMs = timeoutMs;
        this.maxConnectionsPerUser = maxConnectionsPerUser;
    }

    /**
     * 注册一条新连接并返回给 Spring MVC。
     * <p>注册后立即发一个注释帧：某些反向代理/容器要等到首字节才认为连接建立，
     * 空等 15 秒会被判成超时；注释帧对 EventSource 不可见，不会触发任何事件。</p>
     */
    public SseEmitter register(Long userId) {
        SseEmitter emitter = createEmitter();
        synchronized (connections) {
            Set<SseEmitter> set = connections.computeIfAbsent(userId, key -> new LinkedHashSet<>());
            while (set.size() >= maxConnectionsPerUser) {
                SseEmitter oldest = set.iterator().next();
                set.remove(oldest);
                oldest.complete();
                log.info("用户 {} 的 SSE 连接已达上限 {}，关闭最早的一条", userId, maxConnectionsPerUser);
            }
            set.add(emitter);
        }

        Runnable remove = () -> remove(userId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            remove.run();
            emitter.complete();
        });
        emitter.onError(error -> remove.run());

        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (Exception e) {
            // 客户端在注册的一瞬间就断开：直接清理，别把死连接留在表里
            remove.run();
        }
        log.info("用户 {} 建立 SSE 连接（当前 {} 条）", userId, connectionCount(userId));
        return emitter;
    }

    /**
     * 向该用户的所有连接推一条通知。序列化交给 Spring 的 Jackson 消息转换器，
     * 与 REST 接口的 JSON 完全同构（前端可以直接 JSON.parse）。
     */
    public void push(Long userId, NotificationResponse notification) {
        List<SseEmitter> targets;
        synchronized (connections) {
            Set<SseEmitter> set = connections.get(userId);
            if (set == null || set.isEmpty()) {
                return;
            }
            targets = List.copyOf(set);
        }
        for (SseEmitter emitter : targets) {
            try {
                emitter.send(SseEmitter.event()
                        .name("notification")
                        .id(String.valueOf(notification.getId()))
                        .data(notification, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                // 写失败只说明这一条连接断了（浏览器关页/切网），清理它即可；
                // 绝不能把异常抛回业务线程，否则一次推送失败会回滚业务事务。
                log.debug("推送通知失败，移除该 SSE 连接: userId={}, notificationId={}",
                        userId, notification.getId());
                remove(userId, emitter);
                emitter.completeWithError(e);
            }
        }
    }

    /**
     * 心跳：每 heartbeat-ms 发一个注释帧。注释帧不触发前端事件，只用于：
     * 1) 让 nginx / 中间设备看到流量，不因空闲超时断连；
     * 2) 顺带探活，写失败的连接在这里被清掉。
     */
    @Scheduled(fixedRateString = "${app.sse.heartbeat-ms:15000}")
    public void heartbeat() {
        List<Map.Entry<Long, SseEmitter>> snapshot = new ArrayList<>();
        synchronized (connections) {
            connections.forEach((userId, set) ->
                    set.forEach(emitter -> snapshot.add(Map.entry(userId, emitter))));
        }
        for (Map.Entry<Long, SseEmitter> entry : snapshot) {
            try {
                entry.getValue().send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /** 当前连接数；测试与日志用 */
    int connectionCount(Long userId) {
        synchronized (connections) {
            Set<SseEmitter> set = connections.get(userId);
            return set == null ? 0 : set.size();
        }
    }

    private void remove(Long userId, SseEmitter emitter) {
        synchronized (connections) {
            Set<SseEmitter> set = connections.get(userId);
            if (set == null) {
                return;
            }
            set.remove(emitter);
            if (set.isEmpty()) {
                connections.remove(userId);
            }
        }
    }

    /**
     * 供测试替换 emitter 工厂：Mockito 的 SseEmitter mock 不会真的初始化 handler，
     * 这样才能在单测里断言 send / complete / completeWithError 的调用。
     */
    protected SseEmitter createEmitter() {
        return new SseEmitter(timeoutMs);
    }
}
```

### Step 1-6：连接注册表测试

创建 `src/test/java/org/song/forum/service/NotificationStreamServiceTest.java`：

```java
package org.song.forum.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.song.forum.dto.notification.NotificationResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 连接注册表的行为边界：上限淘汰、过期/完成清理、推送隔离、写失败只清理自己。
 * <p>真实 SseEmitter 需要 servlet 容器初始化才能 send，因此用 mock 替换工厂，
 * 只验证「对哪条连接做了什么」，不验证线上字节流（那由集成测试覆盖）。</p>
 */
class NotificationStreamServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_USER_ID = 8L;

    private TestableStreamService service;

    @BeforeEach
    void setUp() {
        service = new TestableStreamService(60_000L, 5);
    }

    private NotificationResponse response(long id) {
        return NotificationResponse.builder()
                .id(id)
                .recipientId(USER_ID)
                .type("LIKE")
                .content("赞了你的帖子")
                .isRead(false)
                .build();
    }

    @Test
    void 注册后计入该用户连接数() {
        SseEmitter emitter = service.nextEmitter();

        SseEmitter returned = service.register(USER_ID);

        assertThat(returned).isSameAs(emitter);
        assertThat(service.connectionCount(USER_ID)).isEqualTo(1);
        assertThat(service.connectionCount(OTHER_USER_ID)).isZero();
    }

    @Test
    void 同一用户超过上限时关闭最早的一条() {
        List<SseEmitter> emitters = service.nextEmitters(6);

        emitters.forEach(emitter -> service.register(USER_ID));

        // 只剩最近 5 条；最早的一条被 complete 关闭
        assertThat(service.connectionCount(USER_ID)).isEqualTo(5);
        verify(emitters.get(0)).complete();
        verify(emitters.get(5), never()).complete();
    }

    @Test
    void 完成回调后从注册表移除() {
        SseEmitter emitter = service.nextEmitter();
        service.register(USER_ID);

        // 模拟 Spring MVC 在连接关闭时回调 onCompletion
        org.mockito.ArgumentCaptor<Runnable> captor =
                org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(emitter).onCompletion(captor.capture());
        captor.getValue().run();

        assertThat(service.connectionCount(USER_ID)).isZero();
    }

    @Test
    void 超时回调先移除再complete() {
        SseEmitter emitter = service.nextEmitter();
        service.register(USER_ID);

        org.mockito.ArgumentCaptor<Runnable> captor =
                org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(emitter).onTimeout(captor.capture());
        captor.getValue().run();

        assertThat(service.connectionCount(USER_ID)).isZero();
        verify(emitter).complete();
    }

    @Test
    void 推送只发给目标用户且发出通知事件() throws IOException {
        SseEmitter target1 = service.nextEmitter();
        SseEmitter target2 = service.nextEmitter();
        SseEmitter other = service.nextEmitter();
        service.register(USER_ID);
        service.register(USER_ID);
        service.register(OTHER_USER_ID);
        // 注册时会各发一个 connected 注释帧，清掉以免干扰 send 计数断言
        clearInvocations(target1, target2, other);

        service.push(USER_ID, response(42L));

        verify(target1, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(target2, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(other, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void 推送写失败只清理该连接并完成错误() throws IOException {
        SseEmitter broken = service.nextEmitter();
        SseEmitter healthy = service.nextEmitter();
        service.register(USER_ID);
        service.register(USER_ID);
        doThrow(new IOException("broken pipe"))
                .when(broken).send(any(SseEmitter.SseEventBuilder.class));

        service.push(USER_ID, response(43L));

        verify(broken).completeWithError(any(IOException.class));
        assertThat(service.connectionCount(USER_ID)).isEqualTo(1);
        // healthy 在注册阶段发的 connected 帧已被 clearInvocations 清掉，push 只发 1 次
        verify(healthy, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void 心跳向所有连接发送注释帧并清理失败连接() throws IOException {
        SseEmitter alive = service.nextEmitter();
        SseEmitter dead = service.nextEmitter();
        service.register(USER_ID);
        service.register(OTHER_USER_ID);
        clearInvocations(alive, dead);
        doThrow(new IOException("client gone"))
                .when(dead).send(any(SseEmitter.SseEventBuilder.class));

        assertThatCode(() -> service.heartbeat()).doesNotThrowAnyException();

        verify(alive, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(service.connectionCount(USER_ID)).isZero();
        assertThat(service.connectionCount(OTHER_USER_ID)).isZero();
    }

    @Test
    void 没有连接的用户推送是空操作() {
        assertThatCode(() -> service.push(USER_ID, response(44L))).doesNotThrowAnyException();
    }

    /** 测试替身：createEmitter 从队列取 mock，并记录顺序供断言 */
    static class TestableStreamService extends NotificationStreamService {

        private final Deque<SseEmitter> pending = new ArrayDeque<>();

        TestableStreamService(long timeoutMs, int maxConnectionsPerUser) {
            super(timeoutMs, maxConnectionsPerUser);
        }

        SseEmitter nextEmitter() {
            SseEmitter emitter = mock(SseEmitter.class);
            pending.add(emitter);
            return emitter;
        }

        List<SseEmitter> nextEmitters(int count) {
            return java.util.stream.IntStream.range(0, count)
                    .mapToObj(index -> nextEmitter())
                    .toList();
        }

        @Override
        protected SseEmitter createEmitter() {
            return pending.poll();
        }
    }
}
```

运行：

```bash
mvn -B test -Dtest=NotificationStreamServiceTest
```

预期：

```text
[INFO] Running org.song.forum.service.NotificationStreamServiceTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Step 1-7：ticket 响应体与两个接口

创建 `src/main/java/org/song/forum/dto/notification/StreamTicketResponse.java`：

```java
package org.song.forum.dto.notification;

/**
 * SSE 订阅票据响应。
 *
 * @param ticket    一次性票据（UUID）
 * @param expiresIn 有效期（秒），前端只用于日志/排障，不参与重连决策
 */
public record StreamTicketResponse(String ticket, long expiresIn) {
}
```

修改 `src/main/java/org/song/forum/controller/NotificationController.java`：在类字段中加入两个依赖，并在 `markRead` 方法之后追加两个端点。字段改为：

```java
    private final NotificationService notificationService;
    private final NotificationStreamService notificationStreamService;
    private final StreamTicketService streamTicketService;
```

新增 import：

```java
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.song.forum.dto.notification.StreamTicketResponse;
import org.song.forum.exception.InvalidStreamTicketException;
import org.song.forum.service.NotificationStreamService;
import org.song.forum.service.StreamTicketService;
```

新增方法（放在 `markRead` 之后、类结尾 `}` 之前）：

```java
    /**
     * 下发一次性 SSE 订阅票据（需登录）。
     * <p>前端拿到 ticket 后拼到 EventSource 的 query 上；ticket 60 秒有效、
     * 用一次即焚，因此不需要也不应该再校验 JWT。</p>
     */
    @PostMapping("/stream-ticket")
    public Result<StreamTicketResponse> streamTicket() {
        Long userId = SecurityUtils.currentUserId();
        return Result.ok(new StreamTicketResponse(
                streamTicketService.issue(userId), streamTicketService.ttlSeconds()));
    }

    /**
     * SSE 订阅。ticket 由上面的接口签发；本端点匿名可达（SecurityConfig 显式放行），
     * 鉴权完全由 ticket 承担。
     *
     * <p>返回裸 SseEmitter（不包 ResponseEntity）：Spring 的异步返回值处理器对
     * SseEmitter 的支持最直接；响应头在进入异步前用 response 一次性设置。
     * {@code X-Accel-Buffering: no} 是给 nginx 的兜底信号——即使某天 snippet
     * 漏了 proxy_buffering off，也不会攒住事件。</p>
     *
     * <p>异常必须在返回 SseEmitter 之前抛出：一旦返回 emitter，响应就进入异步流程，
     * 再抛异常只能走 error 回调，客户端看到的是 200 + 断流，而不是 401。</p>
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) String ticket,
                             HttpServletResponse response) {
        Long userId = streamTicketService.consume(ticket);
        if (userId == null) {
            throw new InvalidStreamTicketException("通知连接凭证无效或已过期");
        }
        // 必须在返回 emitter（进入异步）之前设置响应头，之后再改不会发给客户端
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return notificationStreamService.register(userId);
    }
```

> 检查：`NotificationController` 里已有的 `@GetMapping("/unread-count")` 等映射与继承的类级 `@RequestMapping("/api/notifications")` 不受影响；`produces` 只作用于本方法。

### Step 1-8：放行 stream 端点

`src/main/java/org/song/forum/config/SecurityConfig.java` 中，在 `.requestMatchers(PUBLIC_GET).permitAll()` 之后、`.requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()` 之前插入：

```java
                        // SSE 订阅：EventSource 不能带 Authorization 头，鉴权由一次性 ticket 承担。
                        // 必须显式写出并说明原因：收紧「GET 全放行」时不能靠记忆判断它是否被误伤。
                        .requestMatchers(HttpMethod.GET, "/api/notifications/stream").permitAll()
```

验证（此时还没有集成测试，先确认整个 Spring 上下文能启动）：

```bash
mvn -B test -Dtest=NotificationServiceTest,StreamTicketServiceTest
```

预期：两个类全绿（NotificationServiceTest 里 `@InjectMocks` 仍是单依赖，不受影响）。

### Step 1-9：SSE 集成测试（ticket 生命周期 + 真实订阅）

创建 `src/test/java/org/song/forum/controller/SseFlowIntegrationTest.java`：

```java
package org.song.forum.controller;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.song.forum.dto.auth.AuthResponse;
import org.song.forum.dto.auth.LoginRequest;
import org.song.forum.dto.auth.RegisterRequest;
import org.song.forum.dto.board.BoardRequest;
import org.song.forum.dto.board.BoardResponse;
import org.song.forum.dto.notification.StreamTicketResponse;
import org.song.forum.dto.post.PostDetailResponse;
import org.song.forum.dto.post.PostRequest;
import org.song.forum.util.Result;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第六轮集成测试：ticket 生命周期 + 真实 SSE 订阅。
 *
 * <p>为什么用 JDK HttpClient 而不是 TestRestTemplate：SSE 响应永远不会自然结束，
 * TestRestTemplate 会把整个 body 读完才返回，测试会挂到 emitter 超时。HttpClient
 * 的 {@code BodyHandlers.ofLines()} 拿到一行就能继续，可以按行断言事件帧。</p>
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:sse-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // 测试里不挂 30 分钟：4 秒超时、200ms 心跳，读流时永远有下一行可读
        "app.sse.timeout-ms=4000",
        "app.sse.heartbeat-ms=200",
        "app.sse.ticket-ttl-ms=5000"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SseFlowIntegrationTest {

    private static final String STREAM_PATH = "/api/notifications/stream";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private static String authorToken;
    private static String readerToken;
    private static Long readerPostId;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private String registerAndLogin(String name) {
        RegisterRequest r = new RegisterRequest();
        r.setUsername(name);
        r.setPassword("password123");
        r.setEmail(name + "@test.com");
        rest.postForEntity(url("/api/auth/register"), r, Result.class);
        LoginRequest l = new LoginRequest();
        l.setUsername(name);
        l.setPassword("password123");
        ResponseEntity<Result<AuthResponse>> resp = rest.exchange(url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>(l), new ParameterizedTypeReference<>() {});
        return resp.getBody().getData().getToken();
    }

    private String loginAdmin() {
        LoginRequest l = new LoginRequest();
        l.setUsername("admin");
        l.setPassword("admin123456");
        ResponseEntity<Result<AuthResponse>> resp = rest.exchange(url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>(l), new ParameterizedTypeReference<>() {});
        return resp.getBody().getData().getToken();
    }

    private StreamTicketResponse issueTicket(String token) {
        ResponseEntity<Result<StreamTicketResponse>> resp = rest.exchange(
                url("/api/notifications/stream-ticket"), HttpMethod.POST,
                new HttpEntity<>(bearer(token)),
                new ParameterizedTypeReference<>() {});
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return resp.getBody().getData();
    }

    /** 同步请求的错误分支：401 的 body 是有限的，可以安全读完 */
    private HttpResponse<String> streamReject(String ticket) throws Exception {
        String query = ticket == null ? "" : "?ticket=" + ticket;
        return HTTP.send(HttpRequest.newBuilder(URI.create(url(STREAM_PATH + query)))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** 打开一条 SSE 流；返回的 Stream 由调用方关闭 */
    private HttpResponse<Stream<String>> openStream(String ticket) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(
                                URI.create(url(STREAM_PATH + "?ticket=" + ticket)))
                        .timeout(Duration.ofSeconds(10))
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofLines());
    }

    /**
     * 在 timeout 内找到第一条 notification 事件，返回 data 行内容；找不到返回 null。
     * <p>按行读并跳过心跳注释，任何一行到达都会让 hasNext 返回，因此不会无限阻塞。
     */
    private String readNotificationData(Stream<String> lines, Duration timeout) {
        Iterator<String> iterator = lines.iterator();
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        boolean inNotificationEvent = false;
        while (System.currentTimeMillis() < deadline && iterator.hasNext()) {
            String line = iterator.next();
            if (line.startsWith("event:")) {
                inNotificationEvent = line.contains("notification");
            } else if (line.startsWith("data:") && inNotificationEvent) {
                return line.substring("data:".length()).trim();
            }
        }
        return null;
    }

    @Test
    @Order(1)
    void stage1_prepareUsersAndPost() {
        authorToken = registerAndLogin("sse_author");
        readerToken = registerAndLogin("sse_reader");

        String adminToken = loginAdmin();
        BoardRequest board = new BoardRequest();
        board.setName("SSE 测试板块");
        board.setSortOrder(1);
        Long boardId = rest.exchange(url("/api/boards"), HttpMethod.POST,
                new HttpEntity<>(board, bearer(adminToken)),
                new ParameterizedTypeReference<Result<BoardResponse>>() {}).getBody().getData().getId();

        PostRequest post = new PostRequest();
        post.setBoardId(boardId);
        post.setTitle("SSE 订阅测试帖");
        post.setContent("等一个实时通知");
        readerPostId = rest.exchange(url("/api/posts"), HttpMethod.POST,
                new HttpEntity<>(post, bearer(readerToken)),
                new ParameterizedTypeReference<Result<PostDetailResponse>>() {})
                .getBody().getData().getId();
        assertThat(readerPostId).isNotNull();
    }

    @Test
    @Order(2)
    void stage2_ticketRequiresLoginAndCarriesTtl() {
        // 未登录取票必须 401：ticket 端点本身不能匿名
        ResponseEntity<Result<Void>> anonymous = rest.exchange(
                url("/api/notifications/stream-ticket"), HttpMethod.POST, null,
                new ParameterizedTypeReference<>() {});
        assertThat(anonymous.getStatusCode().value()).isEqualTo(401);

        StreamTicketResponse ticket = issueTicket(readerToken);
        assertThat(ticket.ticket()).isNotBlank();
        // application-test.yml / @SpringBootTest 里 ticket-ttl-ms=5000
        assertThat(ticket.expiresIn()).isEqualTo(5L);
    }

    @Test
    @Order(3)
    void stage3_missingInvalidAndUsedTicketsAreRejected() throws Exception {
        HttpResponse<String> missing = streamReject(null);
        assertThat(missing.statusCode()).isEqualTo(401);
        assertThat(missing.body()).contains("\"success\":false");

        assertThat(streamReject("not-a-real-ticket").statusCode()).isEqualTo(401);

        // 有效 ticket 建流会把票消费掉；同一张票再用必须 401
        String ticket = issueTicket(readerToken).ticket();
        HttpResponse<Stream<String>> opened = openStream(ticket);
        assertThat(opened.statusCode()).isEqualTo(200);
        assertThat(opened.headers().firstValue("Content-Type").orElse(""))
                .contains("text/event-stream");
        assertThat(opened.headers().firstValue("X-Accel-Buffering").orElse(""))
                .isEqualTo("no");
        opened.body().close();

        assertThat(streamReject(ticket).statusCode()).isEqualTo(401);
    }

}
```

运行（`-Dtest` 只跑这一个类；H2 + 随机端口，约 10–15 秒）：

```bash
mvn -B test -Dtest=SseFlowIntegrationTest
```

预期：

```text
[INFO] Running org.song.forum.controller.SseFlowIntegrationTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

常见失败与处置：
- `stage3` 的「已用 ticket」返回 200：说明 `consume` 没删票，检查是否把 `tickets.remove` 写成了 `get`。
- `stage3` 的缺失 ticket 返回 500：说明异常落到了 `Exception` 兜底，检查 `GlobalExceptionHandler` 的 `InvalidStreamTicketException` handler 是否被 Spring 扫描到（同包 `@RestControllerAdvice`，应自动生效）。
- 若 `openStream` 在 200 后关闭 body 触发 `IOException`：`Stream.close()` 是幂等且允许的，测试里用 try-with-resources 或直接 close 都可以；若 JDK HttpClient 在 close 时打印警告，可忽略，不影响后续 401 断言。

> 说明：`stage4`（真实推送 + 用户隔离）依赖 Task 2 的 `afterCommit` 推送。为避免出现「一个必然红灯的中间提交」，**本 Step 只写 stage1–stage3**，`stage4` 的代码块在 Task 2 的 Step 2-4 追加到同一文件。

### Step 1-10：Task 1 回归并提交

```bash
mvn -B clean test
```

预期：`Tests run: 122, Failures: 0, Errors: 0, Skipped: 1`，`BUILD SUCCESS`。本 Task 的增量为：`StreamTicketServiceTest` 6 + `NotificationStreamServiceTest` 8 + `SseFlowIntegrationTest` 3 = 17，105 + 17 = **122**；跳过的 1 例是 Windows 上不运行的 POSIX 文件权限用例。

提交：

```bash
git add src/main/java/org/song/forum/exception/InvalidStreamTicketException.java \
        src/main/java/org/song/forum/exception/GlobalExceptionHandler.java \
        src/main/java/org/song/forum/service/StreamTicketService.java \
        src/main/java/org/song/forum/service/NotificationStreamService.java \
        src/main/java/org/song/forum/config/SseConfig.java \
        src/main/java/org/song/forum/config/SecurityConfig.java \
        src/main/java/org/song/forum/controller/NotificationController.java \
        src/main/java/org/song/forum/dto/notification/StreamTicketResponse.java \
        src/main/resources/application.yml \
        src/test/resources/application-test.yml \
        src/test/java/org/song/forum/service/StreamTicketServiceTest.java \
        src/test/java/org/song/forum/service/NotificationStreamServiceTest.java \
        src/test/java/org/song/forum/controller/SseFlowIntegrationTest.java
git commit -m "feat: 增加 SSE 通知基础设施（ticket 鉴权 + 连接注册表）"
```

## Task 2：通知创建接入实时推送

**目标**：新增 `NotificationService.create(...)` 统一入口；三个调用点（评论回复、点赞、收藏）改用它；推送发生在**事务提交之后**，回滚不推；没有事务同步时立即推送。

**文件**：

- 修改 `src/main/java/org/song/forum/service/NotificationService.java`
- 修改 `src/main/java/org/song/forum/service/CommentService.java`
- 修改 `src/main/java/org/song/forum/service/LikeService.java`
- 修改 `src/test/java/org/song/forum/service/NotificationServiceTest.java`
- 修改 `src/test/java/org/song/forum/controller/SseFlowIntegrationTest.java`（追加 stage4）

### Step 2-1：先写失败测试（RED）

**只改测试文件**。此时 `NotificationService.create` 尚不存在，编译失败即 RED。

在 `src/test/java/org/song/forum/service/NotificationServiceTest.java` 的字段区（第 31-35 行附近）追加：

```java
    @Mock
    private NotificationStreamService notificationStreamService;
```

import 区追加：

```java
import org.mockito.ArgumentCaptor;
import org.song.forum.dto.notification.NotificationResponse;
import org.song.forum.entity.Post;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
```

常量区追加：

```java
    private static final Long RECIPIENT_ID = 11L;
    private static final Long POST_ID = 5L;
```

辅助方法追加（放在现有 `notification(...)` 之后）：

```java
    /** toResponse 会读 username/nickname，这里造一个最小可用的用户 */
    private User user(Long id, String username) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setNickname(username + "的昵称");
        return u;
    }

    private Post post() {
        return Post.builder().id(POST_ID).title("测试帖标题").build();
    }
```

类尾部追加 4 个测试：

```java
    // ---------- create：实时推送时机 ----------

    /**
     * 没有事务同步（例如单元测试或将来某个非事务调用方）时，
     * 必须在保存后立刻推送——不能因为「等 afterCommit」把通知静默丢掉。
     */
    @Test
    void create_无事务时保存后直接推送一次() {
        User recipient = user(RECIPIENT_ID, "recipient");
        User sender = user(2L, "sender");
        Post post = post();
        Notification saved = Notification.builder()
                .id(77L)
                .recipient(recipient)
                .sender(sender)
                .post(post)
                .type(NotificationType.LIKE)
                .content("sender 赞了你的帖子「测试帖标题」")
                .isRead(false)
                .build();
        when(notificationRepository.save(any(Notification.class))).thenReturn(saved);

        notificationService.create(recipient, sender, post, null, NotificationType.LIKE,
                "sender 赞了你的帖子「测试帖标题」");

        ArgumentCaptor<NotificationResponse> captor = ArgumentCaptor.forClass(NotificationResponse.class);
        verify(notificationStreamService).push(eq(RECIPIENT_ID), captor.capture());
        NotificationResponse payload = captor.getValue();
        assertThat(payload.getId()).isEqualTo(77L);
        assertThat(payload.getRecipientId()).isEqualTo(RECIPIENT_ID);
        assertThat(payload.getSenderUsername()).isEqualTo("sender");
        assertThat(payload.getPostTitle()).isEqualTo("测试帖标题");
        assertThat(payload.getType()).isEqualTo("LIKE");
        assertThat(payload.getIsRead()).isFalse();
    }

    /**
     * 有事务同步时只能注册回调：提交前推送会让接收者拉到「还没提交」的通知，
     * 事务回滚时更会推一条根本不存在的通知。
     */
    @Test
    void create_存在事务同步时提交后才推送() {
        User recipient = user(RECIPIENT_ID, "recipient");
        User sender = user(2L, "sender");
        Post post = post();
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId(88L);
            return n;
        });

        TransactionSynchronizationManager.initSynchronization();
        try {
            notificationService.create(recipient, sender, post, null, NotificationType.REPLY,
                    "sender 回复了你的评论");

            // 提交前：一次都不能推
            verify(notificationStreamService, never()).push(anyLong(), any(NotificationResponse.class));

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(notificationStreamService).push(eq(RECIPIENT_ID), any(NotificationResponse.class));
    }

    /** 事务回滚：afterCommit 不会执行，因此一条也不能推 */
    @Test
    void create_事务回滚时不推送() {
        User recipient = user(RECIPIENT_ID, "recipient");
        Post post = post();
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId(99L);
            return n;
        });

        TransactionSynchronizationManager.initSynchronization();
        try {
            notificationService.create(recipient, user(2L, "sender"), post, null,
                    NotificationType.FAVORITE, "sender 收藏了你的帖子");
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(notificationStreamService, never()).push(anyLong(), any(NotificationResponse.class));
    }

    /** 入库的实体必须带接收者/类型/内容，默认未读 */
    @Test
    void create_保存的通知字段完整且默认未读() {
        User recipient = user(RECIPIENT_ID, "recipient");
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId(100L);
            return n;
        });

        notificationService.create(recipient, user(2L, "sender"), post(), null,
                NotificationType.FAVORITE, "sender 收藏了你的帖子");

        verify(notificationRepository).save(captor.capture());
        Notification n = captor.getValue();
        assertThat(n.getRecipient()).isSameAs(recipient);
        assertThat(n.getType()).isEqualTo(NotificationType.FAVORITE);
        assertThat(n.getContent()).contains("收藏");
        assertThat(n.getIsRead()).isFalse();
        verify(notificationStreamService).push(eq(RECIPIENT_ID), any(NotificationResponse.class));
    }
```

运行：

```bash
mvn -B test -Dtest=NotificationServiceTest
```

预期：**编译失败** —— `cannot find symbol: method create(...)`。若 Task 1 尚未提交，还会报 `cannot find symbol: class NotificationStreamService`。这就是 RED，不要为了让它变绿去改测试。

### Step 2-2：实现 `NotificationService.create`

修改 `src/main/java/org/song/forum/service/NotificationService.java`。

import 区追加：

```java
import org.song.forum.entity.Comment;
import org.song.forum.entity.NotificationType;
import org.song.forum.entity.Post;
import org.song.forum.entity.User;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
```

字段区改为：

```java
    private final NotificationRepository notificationRepository;
    private final NotificationStreamService notificationStreamService;
```

在类注释下、`mine(...)` 之前插入：

```java
    // ---------- 供其它服务调用的创建入口 ----------

    /**
     * 创建一条通知，并在事务提交后实时推送给接收者。
     *
     * <p>为什么必须等提交：提交前推送，接收者点开时可能还查不到这条通知；
     * 如果事务随后回滚，更会推出一条数据库里根本不存在的通知。
     *
     * <p>没有事务同步时（单元测试、或将来某个非事务调用方）直接推送：
     * 通知只能「迟到」，不能「丢失」。
     *
     * @return 已持久化的通知实体，供调用方记日志或测试断言
     */
    public Notification create(User recipient, User sender, Post post, Comment comment,
                               NotificationType type, String content) {
        Notification notification = notificationRepository.save(Notification.builder()
                .recipient(recipient)
                .sender(sender)
                .post(post)
                .comment(comment)
                .type(type)
                .content(content)
                .build());

        // 先在事务内把响应算好：懒加载字段在这里都还能读到
        NotificationResponse payload = toResponse(notification);
        Long recipientId = recipient.getId();

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notificationStreamService.push(recipientId, payload);
                }
            });
        } else {
            notificationStreamService.push(recipientId, payload);
        }
        return notification;
    }
```

运行：

```bash
mvn -B test -Dtest=NotificationServiceTest
```

预期：`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`（原有 4 + 新增 4），`BUILD SUCCESS`。

> 命中 `UnnecessaryStubbingException`：Mockito 严格模式会把「写了 when 但没用到」的桩判失败。上面 4 个用例每个都用了自己的桩；若某个用例因提前断言失败而没走到桩，先修实现，不要改用 `lenient()`。

### Step 2-3：三个调用点改用 `create`

**① `src/main/java/org/song/forum/service/CommentService.java`**

字段区：把

```java
    private final NotificationRepository notificationRepository;
```

改成

```java
    private final NotificationService notificationService;
```

import 清理：删除 `org.song.forum.entity.Notification` 与 `org.song.forum.repository.NotificationRepository`；保留 `org.song.forum.entity.NotificationType`。`NotificationService` 与本类同包，无需 import。

先确认没有别处再用旧字段：

```bash
grep -n "notificationRepository" src/main/java/org/song/forum/service/CommentService.java
```

预期：**只剩** `createComment` 里那一处（下面就要替换）。若还有其它处，逐个改调用或保留字段并在 `create` 之外照常使用，不要盲删。

`createComment` 中原通知块（约第 95-105 行）：

```java
        // 回复他人评论 → REPLY 通知（自己回复自己不通知）
        if (parent != null && !parent.getAuthor().getId().equals(authorId)) {
            notificationRepository.save(Notification.builder()
                    .recipient(parent.getAuthor())
                    .sender(author)
                    .post(post)
                    .comment(parent)
                    .type(NotificationType.REPLY)
                    .content(author.getUsername() + " 回复了你的评论")
                    .build());
        }
```

改为：

```java
        // 回复他人评论 → REPLY 通知（自己回复自己不通知）
        // 走 NotificationService.create：实体会在事务提交后推 SSE，避免推出去的 commentId 查不到
        if (parent != null && !parent.getAuthor().getId().equals(authorId)) {
            notificationService.create(parent.getAuthor(), author, post, parent,
                    NotificationType.REPLY, author.getUsername() + " 回复了你的评论");
        }
```

**② `src/main/java/org/song/forum/service/LikeService.java`**

字段区：把

```java
    private final NotificationRepository notificationRepository;
```

改成

```java
    private final NotificationService notificationService;
```

import 清理：删除 `org.song.forum.entity.Notification` 与 `org.song.forum.repository.NotificationRepository`；保留 `NotificationType`。确认没有残留：

```bash
grep -n "notificationRepository" src/main/java/org/song/forum/service/LikeService.java
```

预期：无输出。

`toggleLike` 的 OR 分支（原第 63-71 行）：

```java
                    if (!post.getAuthor().getId().equals(userId)) {
                        notificationRepository.save(Notification.builder()
                                .recipient(post.getAuthor())
                                .sender(actor)
                                .post(post)
                                .type(NotificationType.LIKE)
                                .content(actor.getUsername() + " 赞了你的帖子「" + post.getTitle() + "」")
                                .build());
                    }
```

改为：

```java
                    if (!post.getAuthor().getId().equals(userId)) {
                        notificationService.create(post.getAuthor(), actor, post, null,
                                NotificationType.LIKE,
                                actor.getUsername() + " 赞了你的帖子「" + post.getTitle() + "」");
                    }
```

`toggleFavorite` 的 OR 分支（原第 92-100 行）同理改为：

```java
                    if (!post.getAuthor().getId().equals(userId)) {
                        notificationService.create(post.getAuthor(), actor, post, null,
                                NotificationType.FAVORITE,
                                actor.getUsername() + " 收藏了你的帖子「" + post.getTitle() + "」");
                    }
```

**③ 依赖方向自检**

`LikeService → NotificationService → NotificationStreamService`、`CommentService → NotificationService`，无环。若启动时报循环依赖，先检查是不是把 `NotificationService` 又注入回了 `NotificationStreamService`——本设计里它只依赖 `ObjectMapper`。

运行：

```bash
mvn -B test -Dtest='NotificationServiceTest,CommentFlowIntegrationTest'
```

预期：`Tests run: 13, Failures: 0, Errors: 0`（NotificationService 8 + CommentFlow 5），`BUILD SUCCESS`。`CommentFlowIntegrationTest` 验证 REPLY 通知仍然创建、评论流程未被破坏。

### Step 2-4：集成测试追加 stage4（真实推送 + 收件人隔离）

在 `src/test/java/org/song/forum/controller/SseFlowIntegrationTest.java` 追加 import：

```java
import org.song.forum.dto.post.LikeToggleResponse;
```

类尾部（`stage3` 之后）追加：

```java
    private long unreadCount(String token) {
        return rest.exchange(url("/api/notifications/unread-count"), HttpMethod.GET,
                new HttpEntity<>(bearer(token)),
                new ParameterizedTypeReference<Result<Long>>() {}).getBody().getData();
    }

    @Test
    @Order(4)
    void stage4_likePushesRealSseEventOnlyToRecipient() throws Exception {
        // 楼主 = author，点赞者 = reader。另开一帖，避免依赖 stage1 帖子的归属
        String adminToken = loginAdmin();
        BoardRequest board = new BoardRequest();
        board.setName("SSE 推送板块");
        board.setSortOrder(2);
        Long boardId = rest.exchange(url("/api/boards"), HttpMethod.POST,
                new HttpEntity<>(board, bearer(adminToken)),
                new ParameterizedTypeReference<Result<BoardResponse>>() {}).getBody().getData().getId();

        PostRequest post = new PostRequest();
        post.setBoardId(boardId);
        post.setTitle("SSE 实时推送测试帖");
        post.setContent("点赞后楼主应立刻收到通知");
        Long postId = rest.exchange(url("/api/posts"), HttpMethod.POST,
                new HttpEntity<>(post, bearer(authorToken)),
                new ParameterizedTypeReference<Result<PostDetailResponse>>() {})
                .getBody().getData().getId();

        // 建立楼主的真实 SSE 连接
        StreamTicketResponse ticket = issueTicket(authorToken);
        HttpResponse<Stream<String>> stream = openStream(ticket.ticket());
        assertThat(stream.statusCode()).isEqualTo(200);

        // readNotificationData 会阻塞读流（心跳保证有行可读），所以点赞放到另一个线程触发
        Thread liker = new Thread(() -> rest.exchange(url("/api/posts/" + postId + "/like"),
                HttpMethod.POST, new HttpEntity<>(bearer(readerToken)),
                new ParameterizedTypeReference<Result<LikeToggleResponse>>() {}));
        liker.start();
        String data;
        try {
            data = readNotificationData(stream.body(), Duration.ofSeconds(10));
        } finally {
            stream.body().close();
        }
        liker.join(Duration.ofSeconds(10).toMillis());

        assertThat(data).as("应在 10s 内收到 LIKE 通知事件").isNotNull();
        assertThat(data).contains("\"type\":\"LIKE\"");
        assertThat(data).contains("sse_reader");
        assertThat(data).contains("\"postId\":" + postId);

        // 只通知楼主：操作者自己的未读数为 0，楼主为 1
        assertThat(unreadCount(readerToken)).isZero();
        assertThat(unreadCount(authorToken)).isEqualTo(1L);
    }
```

运行：

```bash
mvn -B test -Dtest=SseFlowIntegrationTest
```

预期：`Tests run: 4`（stage1-4），`BUILD SUCCESS`。若 stage4 读不到事件：
1. 看 `NotificationService.create` 是否真的走了 `afterCommit`（在 `push` 里打断点或加 `log.debug`）；
2. 看 `LikeService` 是否还残留 `notificationRepository.save`（grep 应为空）；
3. 看测试属性 `app.sse.timeout-ms=4000` 是否把连接提前关了（4s 足够，若机器慢可临时调大）。

### Step 2-5：Task 2 全量回归并提交

```bash
mvn -B clean test
```

预期：`Tests run: 127, Failures: 0, Errors: 0, Skipped: 1`，`BUILD SUCCESS`。增量：`NotificationServiceTest` 4 + `SseFlowIntegrationTest` 1 = 5，122 + 5 = **127**（Task 1 后基线 122：原 105 + 17）。唯一跳过的是原有的 POSIX 文件权限用例（Windows 上不适用）。

> 若此时数字与预期差 1-2，先确认 `Negotiation`/条件跳过（`@Disabled`）是否引入，再把真实数字写入 Task 5 的文档回填；**不允许有失败或跳过**。

```bash
git add src/main/java/org/song/forum/service/NotificationService.java \
        src/main/java/org/song/forum/service/CommentService.java \
        src/main/java/org/song/forum/service/LikeService.java \
        src/test/java/org/song/forum/service/NotificationServiceTest.java \
        src/test/java/org/song/forum/controller/SseFlowIntegrationTest.java
git commit -m "feat: 通知创建在事务提交后实时推送"
```

## Task 3：前端 SSE 连接与角标

**目标**：用 `EventSource` 替换 120s 轮询；一次性 ticket 鉴权；状态机 `connecting/open/retrying/disabled/closed`；指数退避 1s→30s；401 停止；事件驱动角标与轻提示；断线期间 120s 兜底轮询；页面隐藏时暂停重连。

**文件**：

- 修改 `frontend/src/api/index.js`
- 新增 `frontend/src/composables/useNotificationStream.js`
- 修改 `frontend/src/stores/notifications.js`
- 修改 `frontend/src/App.vue`
- 新增 `frontend/src/composables/useNotificationStream.spec.js`
- 新增 `frontend/src/App.spec.js`
- 修改 `frontend/src/stores/notifications.spec.js`

> **任务粒度说明**：store 的 `handleIncoming` 是连接层的第一个调用方，因此本 Task 一并实现并测试它；Task 4 只负责「通知页打开时即时插入 + 横幅」的视图层与边界用例（`viewOpen`/`listLoaded` 的赋值时机）。这是为了让 Task 3 的提交本身就能安全运行，而不是接上一个运行时必报「handleIncoming is not a function」的前端。

### Step 3-1：API 增加取票方法

修改 `frontend/src/api/index.js` 的通知区（第 132-138 行）：

```js
// ========== 通知 ==========
export const notificationApi = {
  mine: (params) => api.get('/notifications', { params }),
  unreadCount: () => api.get('/notifications/unread-count'),
  markAllRead: () => api.post('/notifications/read-all'),
  markRead: (id) => api.post(`/notifications/${id}/read`),
  // SSE 连接不能带 Authorization 头（EventSource 不支持自定义头）：
  // 先换一张 60s 有效的一次性 ticket，再拼进 EventSource 的 URL。
  streamTicket: () => api.post('/notifications/stream-ticket')
}
```

### Step 3-2：store 实时合并状态（先 RED）

在 `frontend/src/stores/notifications.spec.js` 的 `describe` 内追加 6 个用例（复用文件已有的 `vi.mock('@/api')` 与 beforeEach；若已有 `makeNotification` 之类的辅助函数就直接用，没有则加在 `describe` 顶部）：

```js
  /** 构造一页列表，避免每个用例重复写 content/total 结构 */
  function pageOf(items = [], totalElements = items.length) {
    return { content: items, totalElements, totalPages: 1 }
  }

  function notification(id, isRead = false) {
    return { id, type: 'LIKE', content: `通知 ${id}`, isRead, createTime: '2026-10-22T10:00:00' }
  }

  it('handleIncoming：通知页未打开时只累计未读与 pending，不动列表', async () => {
    notificationApi.mine.mockResolvedValue(pageOf([notification(1)]))
    const store = useNotificationStore()
    await store.loadList(0)

    store.viewOpen = false
    store.handleIncoming(notification(2))

    expect(store.unreadCount).toBe(1)
    expect(store.pendingNewCount).toBe(1)
    expect(store.list.content.map((n) => n.id)).toEqual([1])
  })

  it('handleIncoming：通知页打开且第 1 页时头部插入并清空 pending', async () => {
    const store = useNotificationStore()
    store.list = pageOf([notification(1)])
    store.listLoaded = true
    store.viewOpen = true
    store.listPage = 0

    store.handleIncoming(notification(2))

    expect(store.list.content.map((n) => n.id)).toEqual([2, 1])
    expect(store.list.totalElements).toBe(2)
    expect(store.pendingNewCount).toBe(0)
    expect(store.unreadCount).toBe(1)
  })

  it('handleIncoming：列表已满 10 条时插入并挤掉最后一条，长度不变', () => {
    const store = useNotificationStore()
    const items = Array.from({ length: 10 }, (_, i) => notification(i + 1))
    store.list = pageOf(items, 30)
    store.listLoaded = true
    store.viewOpen = true
    store.listPage = 0

    store.handleIncoming(notification(99))

    expect(store.list.content).toHaveLength(10)
    expect(store.list.content[0].id).toBe(99)
    // 原来第 10 条被挤出去，第 9 条成为新的末尾
    expect(store.list.content[9].id).toBe(9)
    expect(store.list.totalElements).toBe(31)
  })

  it('handleIncoming：翻到第 2 页时不插入，只累计 pending（不能打乱当前分页）', () => {
    const store = useNotificationStore()
    store.list = pageOf([notification(11)], 20)
    store.listLoaded = true
    store.viewOpen = true
    store.listPage = 1

    store.handleIncoming(notification(2))

    expect(store.list.content.map((n) => n.id)).toEqual([11])
    expect(store.pendingNewCount).toBe(1)
  })

  it('handleIncoming：同一个 id 重复送达时去重，未读与 pending 都不变', () => {
    const store = useNotificationStore()
    store.list = pageOf([notification(5)])
    store.listLoaded = true
    store.viewOpen = true
    store.listPage = 0

    store.handleIncoming(notification(5))
    store.handleIncoming(notification(5))

    expect(store.list.content).toHaveLength(1)
    expect(store.unreadCount).toBe(0)
    expect(store.pendingNewCount).toBe(0)
  })

  it('loadList(0) 与 markAllRead 都会清空 pending', async () => {
    notificationApi.mine.mockResolvedValue(pageOf([notification(1)]))
    const store = useNotificationStore()
    store.pendingNewCount = 3

    await store.loadList(0)
    expect(store.pendingNewCount).toBe(0)

    store.pendingNewCount = 2
    await store.markAllRead()
    expect(store.pendingNewCount).toBe(0)
  })
```

运行：

```bash
cd frontend && pnpm test -- src/stores/notifications.spec.js
```

预期：**6 个新用例失败** —— `store.handleIncoming is not a function`、`store.viewOpen` 未定义等。这就是 RED。

### Step 3-2b：实现 store 实时合并

修改 `frontend/src/stores/notifications.js`。

`state` 改为：

```js
  state: () => ({
    unreadCount: 0,
    list: emptyPage(),
    listPage: 0,
    /** 有新通知但当前列表不适合插入时累计的条数；通知页据此显示「有 N 条新通知」 */
    pendingNewCount: 0,
    /** 通知页是否打开：只有打开且停在第 1 页才即时插入，否则会打乱分页 */
    viewOpen: false,
    /** 列表是否已成功加载过：首次加载前插入会被随后的响应覆盖 */
    listLoaded: false
  }),
```

`loadList` 改为：

```js
    async loadList(page = 0) {
      this.list = await notificationApi.mine({ page, size: PAGE_SIZE })
      this.listPage = page
      this.listLoaded = true
      // 第 1 页就是服务端的最新状态，横幅随之归零
      if (page === 0) {
        this.pendingNewCount = 0
      }
    },
```

`markAllRead` 改为：

```js
    async markAllRead() {
      await notificationApi.markAllRead()
      this.unreadCount = 0
      this.pendingNewCount = 0
      await this.loadList(this.listPage)
    },
```

在 `loadList` 之后、`markAllRead` 之前插入：

```js
    /**
     * 合并一条 SSE 推送进来的通知。
     *
     * <p>按 id 去重：重连补发、开两个标签页都会让同一条通知到达多次。
     *
     * <p>只有「通知页打开 + 已加载过 + 停在第 1 页」才插到头部并挤掉最后一条：
     * 翻页时插入会让用户正在看的第 2 页混进新数据，未打开页面时插入则会在
     * 下次进入时被首次加载覆盖。其余情况只累计 pending，由横幅提示刷新。</p>
     */
    handleIncoming(notification) {
      if (!notification || notification.id == null) return
      if (this.list.content.some((item) => item.id === notification.id)) return

      // 服务端已经把通知写进库，未读数直接 +1；重连后 onopen 还会以接口结果校准
      this.unreadCount += 1

      if (this.viewOpen && this.listLoaded && this.listPage === 0) {
        this.list.content.unshift(notification)
        if (this.list.content.length > PAGE_SIZE) {
          this.list.content.pop()
        }
        this.list.totalElements = (this.list.totalElements ?? 0) + 1
      } else {
        this.pendingNewCount += 1
      }
    },

    /** 通知页横幅点击：回到第 1 页重新拉取（顺带清 pending） */
    async flushPending() {
      await this.loadList(0)
    },
```

`markRead` 里把过时注释改掉（行为不变）：

```js
      // 角标由实时事件累加，这里只保证不会因为「已读一条」减成 -1
      this.unreadCount = Math.max(0, this.unreadCount - 1)
```

运行：

```bash
cd frontend && pnpm test -- src/stores/notifications.spec.js
```

预期：`Tests run: 15 passed`（原 9 + 新 6）。

### Step 3-3：新 composable 的失败测试（RED）

创建 `frontend/src/composables/useNotificationStream.spec.js`。**先写测试再写实现**；此时 import 会失败，即 RED。

```js
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  BACKOFF_MS,
  FALLBACK_POLL_MS,
  createNotificationStream
} from '@/composables/useNotificationStream'

/**
 * EventSource 的最小替身：记录 URL、手动触发 open/error/notification。
 * jsdom 没有 EventSource，只能注入替身；这同时让「连接何时建立」变得可控。
 */
class MockEventSource {
  static instances = []

  constructor(url) {
    this.url = url
    this.closed = false
    this.listeners = {}
    this.onopen = null
    this.onerror = null
    MockEventSource.instances.push(this)
  }

  addEventListener(name, handler) {
    this.listeners[name] = [...(this.listeners[name] || []), handler]
  }

  removeEventListener(name, handler) {
    this.listeners[name] = (this.listeners[name] || []).filter((fn) => fn !== handler)
  }

  close() {
    this.closed = true
  }

  emit(name, event) {
    ;(this.listeners[name] || []).forEach((fn) => fn(event))
  }

  static latest() {
    return MockEventSource.instances[MockEventSource.instances.length - 1]
  }

  static reset() {
    MockEventSource.instances = []
  }
}

const flush = () => vi.advanceTimersByTimeAsync(0)

describe('createNotificationStream', () => {
  let store
  let api
  let notify
  let documentRef
  let setHidden
  let stream

  beforeEach(() => {
    vi.useFakeTimers()
    MockEventSource.reset()

    const handlers = {}
    documentRef = {
      hidden: false,
      addEventListener: vi.fn((name, fn) => {
        handlers[name] = fn
      }),
      removeEventListener: vi.fn((name) => {
        delete handlers[name]
      })
    }
    setHidden = (hidden) => {
      documentRef.hidden = hidden
      if (handlers.visibilitychange) handlers.visibilitychange()
    }

    store = { handleIncoming: vi.fn(), refreshUnread: vi.fn() }
    api = { streamTicket: vi.fn() }
    notify = vi.fn()
    stream = createNotificationStream({
      store,
      api,
      notify,
      EventSourceImpl: MockEventSource,
      documentRef
    })
  })

  afterEach(() => {
    stream.stop()
    vi.useRealTimers()
  })

  it('取到 ticket 后建立连接，open 后状态为 open 并校准未读数', async () => {
    api.streamTicket.mockResolvedValue({ ticket: 'tk-1', expiresIn: 60 })

    stream.start()
    await flush()

    expect(api.streamTicket).toHaveBeenCalledTimes(1)
    expect(MockEventSource.instances).toHaveLength(1)
    expect(MockEventSource.latest().url).toBe('/api/notifications/stream?ticket=tk-1')

    MockEventSource.latest().onopen()
    expect(stream.state.value).toBe('open')
    expect(store.refreshUnread).toHaveBeenCalledTimes(1)
  })

  it('取票失败按 1s/2s/4s 指数退避重试', async () => {
    api.streamTicket.mockRejectedValue(new Error('network'))

    stream.start()
    await flush()
    expect(stream.state.value).toBe('retrying')
    expect(api.streamTicket).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(999)
    expect(api.streamTicket).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(api.streamTicket).toHaveBeenCalledTimes(2)

    await vi.advanceTimersByTimeAsync(1999)
    expect(api.streamTicket).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(1)
    expect(api.streamTicket).toHaveBeenCalledTimes(3)

    await vi.advanceTimersByTimeAsync(4000)
    expect(api.streamTicket).toHaveBeenCalledTimes(4)
  })

  it('退避封顶 30s，不会无限增长', async () => {
    api.streamTicket.mockRejectedValue(new Error('network'))
    stream.start()
    await flush()

    // 1+2+4+8+16+30 → 第 7 次尝试
    for (const delay of BACKOFF_MS) {
      await vi.advanceTimersByTimeAsync(delay)
    }
    expect(api.streamTicket).toHaveBeenCalledTimes(7)

    // 第 7 次失败仍等 30s（封顶），而不是 60s
    await vi.advanceTimersByTimeAsync(29_999)
    expect(api.streamTicket).toHaveBeenCalledTimes(7)
    await vi.advanceTimersByTimeAsync(1)
    expect(api.streamTicket).toHaveBeenCalledTimes(8)
  })

  it('取票返回 401 时进入 disabled 并永久停止（交给拦截器跳登录页）', async () => {
    api.streamTicket.mockRejectedValue({ response: { status: 401 } })

    stream.start()
    await flush()

    expect(stream.state.value).toBe('disabled')
    await vi.advanceTimersByTimeAsync(120_000)
    expect(api.streamTicket).toHaveBeenCalledTimes(1)
  })

  it('连接断开后进入 retrying，并在断线期间按 120s 兜底轮询，重连成功停止', async () => {
    api.streamTicket.mockResolvedValue({ ticket: 'tk-1' })
    stream.start()
    await flush()
    MockEventSource.latest().onopen()
    expect(store.refreshUnread).toHaveBeenCalledTimes(1)

    MockEventSource.latest().onerror()
    expect(stream.state.value).toBe('retrying')

    await vi.advanceTimersByTimeAsync(FALLBACK_POLL_MS)
    expect(store.refreshUnread).toHaveBeenCalledTimes(2)

    // 1s 后重连；重连成功会再校准一次未读数，并停掉兜底轮询
    api.streamTicket.mockResolvedValue({ ticket: 'tk-2' })
    await vi.advanceTimersByTimeAsync(1000)
    expect(MockEventSource.instances).toHaveLength(2)
    MockEventSource.latest().onopen()
    expect(store.refreshUnread).toHaveBeenCalledTimes(3)
    expect(stream.state.value).toBe('open')

    await vi.advanceTimersByTimeAsync(FALLBACK_POLL_MS * 2)
    expect(store.refreshUnread).toHaveBeenCalledTimes(3)
  })

  it('notification 事件解析后交给 store 与轻提示；坏 JSON / 缺 id 直接忽略', async () => {
    api.streamTicket.mockResolvedValue({ ticket: 'tk-1' })
    stream.start()
    await flush()
    const es = MockEventSource.latest()
    es.onopen()

    const payload = { id: 7, type: 'REPLY', content: 'alice 回复了你的评论' }
    es.emit('notification', { data: JSON.stringify(payload) })
    expect(store.handleIncoming).toHaveBeenCalledWith(payload)
    expect(notify).toHaveBeenCalledWith(payload)

    es.emit('notification', { data: '{not-json' })
    es.emit('notification', { data: JSON.stringify({ type: 'LIKE' }) })
    expect(store.handleIncoming).toHaveBeenCalledTimes(1)
    expect(notify).toHaveBeenCalledTimes(1)
  })

  it('stop 关闭连接、清掉重连定时器，之后不再取票', async () => {
    api.streamTicket.mockResolvedValue({ ticket: 'tk-1' })
    stream.start()
    await flush()
    const es = MockEventSource.latest()

    stream.stop()

    expect(es.closed).toBe(true)
    expect(stream.state.value).toBe('closed')
    await vi.advanceTimersByTimeAsync(120_000)
    expect(api.streamTicket).toHaveBeenCalledTimes(1)
  })

  it('页面隐藏时不重连，回到前台立即重连（不背退避）', async () => {
    api.streamTicket.mockResolvedValue({ ticket: 'tk-1' })
    stream.start()
    await flush()

    setHidden(true)
    MockEventSource.latest().onerror()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(api.streamTicket).toHaveBeenCalledTimes(1)

    setHidden(false)
    expect(api.streamTicket).toHaveBeenCalledTimes(2)
  })

  it('成功连接后退避计数归零：再次断线仍从 1s 开始', async () => {
    api.streamTicket.mockRejectedValueOnce(new Error('network'))
    api.streamTicket.mockRejectedValueOnce(new Error('network'))
    api.streamTicket.mockResolvedValue({ ticket: 'tk-3' })

    stream.start()
    await flush()
    await vi.advanceTimersByTimeAsync(1000)
    await vi.advanceTimersByTimeAsync(2000)
    expect(api.streamTicket).toHaveBeenCalledTimes(3)

    MockEventSource.latest().onopen()
    api.streamTicket.mockRejectedValue(new Error('network'))
    MockEventSource.latest().onerror()

    await vi.advanceTimersByTimeAsync(999)
    expect(api.streamTicket).toHaveBeenCalledTimes(3)
    await vi.advanceTimersByTimeAsync(1)
    expect(api.streamTicket).toHaveBeenCalledTimes(4)
  })

  it('响应里没有 ticket 视为失败并重试，不建立连接', async () => {
    api.streamTicket.mockResolvedValue({})

    stream.start()
    await flush()

    expect(stream.state.value).toBe('retrying')
    expect(MockEventSource.instances).toHaveLength(0)
    await vi.advanceTimersByTimeAsync(1000)
    expect(api.streamTicket).toHaveBeenCalledTimes(2)
  })
})
```

运行：

```bash
cd frontend && pnpm test -- src/composables/useNotificationStream.spec.js
```

预期：**模块不存在**，`Failed to resolve import "@/composables/useNotificationStream"`。RED。

### Step 3-3b：实现 `useNotificationStream`

创建 `frontend/src/composables/useNotificationStream.js`：

```js
import { onUnmounted, ref, watch } from 'vue'
import { ElNotification } from 'element-plus'
import { notificationApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'

/**
 * 退避序列（毫秒）。连上后归零，所以每次断线都从 1s 重新开始；
 * 30s 封顶是经验值：再长用户会觉得「通知坏了」，再短则长期断网时白刷请求。
 */
export const BACKOFF_MS = [1000, 2000, 4000, 8000, 16000, 30000]

/**
 * 断线期间的兜底轮询间隔。沿用改造前的 120s：
 * SSE 正常时一次都不跑，断线时保证角标最多落后两分钟。
 */
export const FALLBACK_POLL_MS = 120_000

const TITLES = {
  REPLY: '新回复',
  LIKE: '新点赞',
  FAVORITE: '新收藏',
  SYSTEM: '系统通知'
}

/** 顶部轻提示：点通知页才能看到详情，这里只给一眼可见的提示 */
function defaultNotify(notification) {
  ElNotification({
    title: TITLES[notification.type] || '新通知',
    message: notification.content || '',
    type: notification.type === 'LIKE' ? 'success' : 'info',
    duration: 4000
  })
}

/**
 * 创建 SSE 连接管理器。
 *
 * <p>所有外部依赖都可注入（store/api/EventSource/timers/document），
 * 这样 jsdom 测试可以完全控制连接建立与时间推进，不必真的等 30 秒。</p>
 *
 * <p>状态机：closed → connecting → open → retrying → connecting …；
 * 取票 401 时进入 disabled，永久停止（拦截器已清 token 并跳登录页）。</p>
 */
export function createNotificationStream({
  store,
  api = notificationApi,
  notify = defaultNotify,
  EventSourceImpl = globalThis.EventSource,
  documentRef = typeof document === 'undefined' ? null : document,
  timers = globalThis
}) {
  const state = ref('closed')
  let source = null
  let retryTimer = null
  let pollTimer = null
  let retryIndex = 0
  let started = false
  let stopped = true
  let pendingReconnect = false

  function closeSource() {
    if (!source) return
    source.removeEventListener?.('notification', onNotification)
    source.onopen = null
    source.onerror = null
    source.close()
    source = null
  }

  function stopFallbackPolling() {
    if (!pollTimer) return
    timers.clearInterval(pollTimer)
    pollTimer = null
  }

  function startFallbackPolling() {
    if (pollTimer || stopped) return
    pollTimer = timers.setInterval(() => {
      if (started && !stopped) store.refreshUnread()
    }, FALLBACK_POLL_MS)
  }

  function onNotification(event) {
    let payload
    try {
      payload = JSON.parse(event.data)
    } catch {
      return
    }
    // id 是列表去重与跳转的键；没有 id 的帧（或未来的心跳事件）一律忽略
    if (!payload || payload.id == null) return
    store.handleIncoming(payload)
    notify(payload)
  }

  function open(ticket) {
    closeSource()
    const url = `${import.meta.env.BASE_URL}api/notifications/stream?ticket=${encodeURIComponent(ticket)}`
    const es = new EventSourceImpl(url)
    source = es
    es.addEventListener?.('notification', onNotification)

    es.onopen = () => {
      if (source !== es) return
      retryIndex = 0
      state.value = 'open'
      stopFallbackPolling()
      // 断线期间可能漏过事件：用接口结果校准一次未读数
      store.refreshUnread()
    }

    es.onerror = () => {
      if (source !== es) return
      // 连接已断（服务端重启/网络切换/代理超时）。EventSource 自带重连会忽略鉴权，
      // 必须自己换新 ticket 重来。
      closeSource()
      scheduleRetry()
      startFallbackPolling()
    }
  }

  function scheduleRetry() {
    if (stopped || state.value === 'disabled') return
    state.value = 'retrying'
    if (documentRef?.hidden) {
      // 页面不可见时不重连：移动端后台标签页保持长连接会耗电，回前台再立即重连
      pendingReconnect = true
      return
    }
    const delay = BACKOFF_MS[Math.min(retryIndex, BACKOFF_MS.length - 1)]
    retryIndex += 1
    retryTimer = timers.setTimeout(connect, delay)
  }

  async function connect() {
    if (!started || stopped || state.value === 'disabled') return
    retryTimer = null
    state.value = 'connecting'

    let ticket
    try {
      const data = await api.streamTicket()
      ticket = data?.ticket
      if (!ticket) throw new Error('empty ticket')
    } catch (error) {
      if (error?.response?.status === 401) {
        // ticket 端点都 401 了，说明会话已失效；再重连只会每 30s 刷一次 401
        state.value = 'disabled'
        stop()
        return
      }
      scheduleRetry()
      return
    }

    // 取票是异步的：期间用户可能已登出或组件已卸载
    if (!started || stopped) return
    open(ticket)
  }

  function onVisibilityChange() {
    if (!pendingReconnect || documentRef?.hidden) return
    pendingReconnect = false
    retryIndex = 0
    // 回前台立即重连，不背之前的退避
    connect()
  }

  function start() {
    if (started) return
    started = true
    stopped = false
    pendingReconnect = false
    retryIndex = 0
    state.value = 'connecting'
    documentRef?.addEventListener?.('visibilitychange', onVisibilityChange)
    connect()
  }

  function stop() {
    started = false
    stopped = true
    pendingReconnect = false
    retryIndex = 0
    if (retryTimer) {
      timers.clearTimeout(retryTimer)
      retryTimer = null
    }
    stopFallbackPolling()
    closeSource()
    documentRef?.removeEventListener?.('visibilitychange', onVisibilityChange)
    if (state.value !== 'disabled') state.value = 'closed'
  }

  return { state, start, stop }
}

/**
 * 组件侧入口：登录后建立 SSE，登出/组件卸载时断开。
 * 只在 App.vue 调用一次（整个应用一条连接）。
 */
export function useNotificationStream() {
  const store = useNotificationStore()
  const auth = useAuthStore()
  const stream = createNotificationStream({ store })

  watch(
    () => auth.isLoggedIn,
    (loggedIn) => {
      if (loggedIn) stream.start()
      else stream.stop()
    },
    { immediate: true }
  )

  onUnmounted(() => stream.stop())

  return stream
}
```

一个细节：`state.value = 'connecting'` 放在 `start()` 里，`connect()` 里也设 —— 重复但无害；测试断言只看 `open/retrying/disabled/closed`，不依赖瞬时 `connecting`。

运行：

```bash
cd frontend && pnpm test -- src/composables/useNotificationStream.spec.js
```

预期：`Test Files 1 passed`，`Tests 10 passed`。

### Step 3-4：`App.vue` 接入并移除全局轮询

修改 `frontend/src/App.vue` 的 `<script setup>`（模板与样式不动）。

新增 import：

```js
import { useNotificationStream } from '@/composables/useNotificationStream'
```

在 store 初始化之后调用（整个应用只调一次）：

```js
// 实时通知：登录后自动连 SSE，登出/卸载自动断开；断线退避与兜底轮询都在 composable 内
useNotificationStream()
```

`onMounted` 整段替换为：

```js
onMounted(() => {
  // 带 token 刷新/直接进入页面时先拉一次未读数；登录成功后的刷新由登录页负责。
  // 他人产生的新通知现在走 SSE 实时事件，不再需要 120s 全局轮询——
  // 连接断开期间由 composable 临时恢复 120s 轮询，重连成功后停止。
  if (!auth.isLoggedIn) return
  notifications.refreshUnread()
})
```

登出时顺带清掉横幅计数：

```js
  } else if (command === 'logout') {
    auth.logout()
    notifications.unreadCount = 0
    notifications.pendingNewCount = 0
    ElMessage.success('已退出登录')
    router.push('/')
  }
```

创建 `frontend/src/App.spec.js`：

```js
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

// vi.mock 的工厂会被提升到 import 之前，跨行引用必须用 vi.hoisted
const { streamMock, useNotificationStreamMock } = vi.hoisted(() => {
  const stream = { start: vi.fn(), stop: vi.fn(), state: { value: 'closed' } }
  return { streamMock: stream, useNotificationStreamMock: vi.fn(() => stream) }
})

vi.mock('@/composables/useNotificationStream', () => ({
  useNotificationStream: useNotificationStreamMock
}))

import App from '@/App.vue'

/** App 依赖 Element Plus 全局组件与路由；这里用最小 stub，只验证接线 */
function mountApp() {
  return mount(App, {
    global: {
      stubs: {
        'router-link': { props: ['to'], template: '<a><slot /></a>' },
        'router-view': true,
        'el-container': { template: '<div><slot /></div>' },
        'el-header': { template: '<header><slot /></header>' },
        'el-main': { template: '<main><slot /></main>' },
        'el-footer': { template: '<footer><slot /></footer>' },
        'el-icon': { template: '<i><slot /></i>' },
        'el-badge': { template: '<span class="badge-stub"><slot /></span>' },
        'el-button': { template: '<button><slot /></button>' },
        'el-dropdown': { template: '<div><slot /></div>' },
        'el-dropdown-menu': { template: '<div><slot /></div>' },
        'el-dropdown-item': { template: '<div><slot /></div>' },
        ThemeToggle: true,
        HeaderSearch: true,
        UserAvatar: true
      }
    }
  })
}

describe('App.vue 实时通知接线', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    useNotificationStreamMock.mockClear()
  })

  it('挂载时启用实时通知 composable', () => {
    mountApp()
    expect(useNotificationStreamMock).toHaveBeenCalledTimes(1)
  })

  it('未登录不显示通知角标，卸载不抛错', () => {
    const wrapper = mountApp()
    expect(wrapper.find('.bell-badge').exists()).toBe(false)
    expect(() => wrapper.unmount()).not.toThrow()
  })
})
```

### Step 3-5：前端全量测试 + 构建 + 提交

```bash
cd frontend && pnpm test
```

预期：`Test Files 30 passed`、`Tests 292 passed`（原 274 + store 6 + composable 10 + App 2 = 292），无失败。

```bash
cd frontend && MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build
```

预期：`vite v8.x building for production...`、`✓ built in ...`，无 warning 级的 `base` 提示；产物 `dist/index.html` 中资源前缀为 `/forum/assets/`：

```bash
grep -o '/forum/assets/[^"]*' frontend/dist/index.html | head -3
```

预期：至少输出 1 行 `/forum/assets/...`。

```bash
cd frontend && pnpm test -- src/composables/useNotificationStream.spec.js src/stores/notifications.spec.js src/App.spec.js
```

预期：`Tests 18 passed`（10 + 6 + 2），用于快速回归本 Task 的改动面。

提交：

```bash
git add frontend/src/api/index.js \
        frontend/src/composables/useNotificationStream.js \
        frontend/src/composables/useNotificationStream.spec.js \
        frontend/src/stores/notifications.js \
        frontend/src/stores/notifications.spec.js \
        frontend/src/App.vue \
        frontend/src/App.spec.js
git commit -m "feat: 前端接入 SSE 实时通知与角标"
```

## Task 4：通知页即时插入与「有 N 条新通知」提示条

**目标**：SSE 推送到达时，如果用户正停在通知页第 1 页，直接把新通知插到列表头部（不重新拉取、不打断当前浏览）；如果不在通知页或已翻页，只在页头显示一条可点击的横幅，点击才回到第 1 页刷新。刷新后横幅归零。

**依赖**：Task 3 已在 store 里实现 `pendingNewCount` / `viewOpen` / `listLoaded` / `handleIncoming` / `flushPending`（连接层是这些字段的第一个调用方，放在 Task 3 才能让每个提交都运行时正确）。本 Task 负责**视图层**：打开/离开时维护 `viewOpen`、渲染横幅、把点击接到「回到第 1 页刷新」，并用视图测试守住契约。

**Files**：
- 修改：`frontend/src/views/NotificationsView.vue`
- 修改：`frontend/src/views/NotificationsView.spec.js`

### Step 4-1：先写失败测试

修改 `frontend/src/views/NotificationsView.spec.js`。

顶部补两行 import：

```js
import { nextTick } from 'vue'
import { useNotificationStore } from '@/stores/notifications'
```

在文件末尾追加新的 describe（复用上面的 `mountView` / `flush` / `seed` 辅助函数）：

```js
describe('NotificationsView 新通知横幅', () => {
  it('打开页面时把 viewOpen 置为 true', async () => {
    seed([])
    mountView()
    await flush()

    expect(useNotificationStore().viewOpen).toBe(true)
  })

  it('有 pending 时显示横幅，点击后回到第 1 页并清零', async () => {
    seed([unreadReply])
    const wrapper = mountView()
    await flush()

    // 模拟「用户翻到第 2 页时来了新通知」：store 只累计 pending，不插入
    const store = useNotificationStore()
    store.listPage = 1
    store.pendingNewCount = 2
    await nextTick()

    const banner = wrapper.find('.new-banner')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain('2 条新通知')

    await banner.trigger('click')
    await flush()

    expect(notificationApi.mine).toHaveBeenLastCalledWith({ page: 0, size: 10 })
    expect(store.pendingNewCount).toBe(0)
    expect(wrapper.find('.new-banner').exists()).toBe(false)
  })

  it('卸载时把 viewOpen 复位，避免离开后继续往列表插入', async () => {
    seed([])
    const wrapper = mountView()
    await flush()

    wrapper.unmount()

    expect(useNotificationStore().viewOpen).toBe(false)
  })
})
```

运行：

```bash
cd frontend && pnpm test -- src/views/NotificationsView.spec.js
```

预期：3 个新用例全红（`.new-banner` 不存在、`viewOpen` 为 `false`），原有 4 个用例仍绿。

### Step 4-2：实现视图改动

修改 `frontend/src/views/NotificationsView.vue`。

**（1）import 增加 `onUnmounted`**：

```js
import { ref, onMounted, onUnmounted } from 'vue'
```

**（2）`onMounted` 维护 `viewOpen`**（保留原有刷新未读数）：

```js
onMounted(() => {
  // 通知页打开期间，SSE 新事件由 store.handleIncoming 决定「插入」还是「累计横幅」；
  // 离开时必须复位，否则停在第 1 页的旧快照会让后续事件继续插进已经不在屏幕上的列表。
  notifications.viewOpen = true
  load()
  // 用户停留在通知页时其它标签页/设备可能已把通知读掉，进入本页时重新对账一次，
  // 避免角标与列表不一致。
  notifications.refreshUnread()
})

onUnmounted(() => {
  notifications.viewOpen = false
})
```

**（3）模板：卡片内、列表之前插入横幅**。注意横幅放在 `v-if="notifications.list.content.length"` **之外**——列表为空时也要提示「有 N 条新通知」，否则首条通知到达时用户看不到任何变化：

```html
    <div
      v-if="notifications.pendingNewCount"
      class="new-banner"
      @click="load(0)"
    >
      有 {{ notifications.pendingNewCount }} 条新通知，点击刷新
    </div>

    <template v-if="notifications.list.content.length">
```

**（4）样式追加**（放在 `.head` 之后）：

```css
/* 新通知横幅：只在「通知页打开但不是第 1 页/列表未加载完」或「页面未打开」时出现，
   点击回到第 1 页，由 load(0) → store.loadList(0) 顺带把 pendingNewCount 清零。 */
.new-banner {
  margin: var(--jf-space-2) 0;
  padding: var(--jf-space-2) var(--jf-space-3);
  border-radius: 4px;
  background: var(--jf-color-unread-bg);
  color: var(--jf-color-text);
  font-size: var(--jf-font-small);
  text-align: center;
  cursor: pointer;
  user-select: none;
}
.new-banner:hover {
  background: var(--jf-color-bg-hover);
}
```

**（5）全部已读后的横幅**：`store.markAllRead()` 内部已把 `pendingNewCount` 清零并重新 `loadList`，视图不需要额外处理。

### Step 4-3：验证 + 构建 + 提交

```bash
cd frontend && pnpm test -- src/views/NotificationsView.spec.js src/stores/notifications.spec.js
```

预期：`Test Files 2 passed`、`Tests 22 passed`（7 + 15）。

```bash
cd frontend && pnpm test
```

预期：`Test Files 30 passed`、`Tests 295 passed`（292 + 3），无失败。

```bash
cd frontend && MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build
```

预期：`✓ built in ...`。

提交：

```bash
git add frontend/src/views/NotificationsView.vue frontend/src/views/NotificationsView.spec.js
git commit -m "feat: 通知页支持即时插入与新通知横幅"
```

## Task 5：部署配置、文档、全量验证与上线

**目标**：让 nginx 正确透传 SSE（关缓冲、长读超时、ticket 查询串）、部署脚本把「无 ticket 必须 401」纳入冒烟；同步 README / 开发记录 / 部署手册；跑全量测试、推送、等 Jenkins 构建、在 raspi 上做真实 SSE 冒烟，最后回填哈希。

**Files**：
- 修改：`deploy/nginx/forum.conf`
- 修改：`deploy/deploy.sh`
- 修改：`README.md`
- 修改：`docs/DEVELOPMENT.md`
- 修改：`docs/deployment/README.md`

### Step 5-1：nginx 增加 SSE 精确匹配

在 `deploy/nginx/forum.conf` 里，`# API 反向代理到后端容器` 注释块**之前**插入：

```nginx
# SSE 通知订阅：必须精确匹配并单独配置
# - 通用 /forum/api/ 的 proxy_read_timeout 是 60s，SSE 连接会被每分钟掐断
# - 默认 proxy_buffering on 会把事件攒在 nginx 里，前端要等到心跳/缓冲满才收到
# - ticket 在查询串上，$is_args$args 必须原样透传
location = /forum/api/notifications/stream {
    proxy_pass http://127.0.0.1:8086/api/notifications/stream$is_args$args;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    # 清空 Connection，保持长连接（SSE 不参与 keep-alive 复用）
    proxy_set_header Connection '';
    proxy_buffering off;
    proxy_cache off;
    # 后端单条连接最长 30 分钟（app.sse.timeout-ms），留双倍余量；
    # 到点由服务端 complete，客户端自动换票重连
    proxy_read_timeout 3600s;
    gzip off;
}
```

注意：`location =` 的优先级高于所有前缀匹配，所以它放在 `/forum/`（SPA）和 `/forum/api/` 之前或之后都可以；放在 API 块旁边只是便于阅读。**不要**把它写成 `location /forum/api/notifications/stream`（前缀匹配会和通用 API 块冲突）。

本地静态校验（Windows 上没有 nginx，只能查结构）：

```bash
grep -n 'location = /forum/api/notifications/stream' deploy/nginx/forum.conf
```

预期：恰好 1 行，行号在 `location /forum/api/` 之前。

### Step 5-2：`deploy.sh` 冒烟增加 401 检查

在 `deploy/deploy.sh` 的 uploads 检查之后、`log "清理旧镜像...` 之前插入：

```bash
# SSE 订阅端点必须由后端回答 401（ticket 缺失），而不是落到 SPA 的 index.html（200）：
# 若 nginx 精确匹配缺失，请求会被 try_files 兜到 /forum/index.html，这里会拿到 200；
# 后端未更新或未起则可能是 404/502 —— 三者都说明「实时通知不可用」，部署应立刻失败
stream_code="$(curl --max-time 5 -s -o /dev/null -w '%{http_code}' -H 'Host: raspi' \
    "http://127.0.0.1/forum/api/notifications/stream")"
[ "$stream_code" = "401" ] \
    || die "/forum/api/notifications/stream 未返回 401（返回 $stream_code，应为 401：缺 ticket 时后端必须拒绝；检查 nginx 精确匹配、后端版本与 reload）"
```

为什么是 401 而不是 200/400：ticket 由服务端自校验，缺失时在 controller 抛出 `InvalidStreamTicketException`，由 `GlobalExceptionHandler` 映射成 401 —— 这同时证明「请求到了后端」和「鉴权没被 nginx/SPA 吞掉」。

校验：

```bash
bash -n deploy/deploy.sh
```

预期：无输出、退出码 0。

### Step 5-3：更新 README

`README.md` 共 5 处改动。

**（1）功能列表的通知条（第 19 行）替换为**：

```markdown
- 通知：点赞、收藏、回复三类通知（另有 `SYSTEM` 预留类型），未读角标与单条已读，点击跳转到帖子或具体评论；新通知经 SSE 实时到达（顶栏角标即时 +1、右上角轻提示），断线按 1s→30s 退避自动重连，重连后校准未读数
```

**（2）显式配置项表格（`### 4. 显式配置项`）在 `LOG_FILE` 行之后追加**：

```markdown
| `SSE_TIMEOUT_MS` | `1800000`（单条 SSE 连接最长 30 分钟，到点客户端自动重连） |
| `SSE_HEARTBEAT_MS` | `15000`（心跳事件间隔，用于穿透代理的空闲超时） |
| `SSE_TICKET_TTL_MS` | `60000`（一次性连接凭证有效期） |
| `SSE_MAX_CONNECTIONS_PER_USER` | `5`（每用户并发连接上限，超出时挤掉最早的一条） |
```

**（3）后端测试基线（`### 后端测试` 段末）**：

```markdown
测试使用 H2 内存库（PostgreSQL 兼容模式 `MODE=PostgreSQL`），Flyway 关闭、`ddl-auto=create-drop`，无需本地 PostgreSQL、也无需设置任何环境变量（`src/test/resources/application-test.yml` 由 `@ActiveProfiles("test")` 提供测试专用凭据）。当前为 16 个测试类、127 个用例（其中 1 个 POSIX 文件权限用例在 Windows 上跳过）。
```

**（4）前端测试基线 + 覆盖清单（`### 前端测试`）**：

```markdown
当前为 30 个 spec、295 个用例，覆盖：
```

在覆盖清单末尾（搜索文本工具一条之后）追加：

```markdown
- 实时通知（`src/composables/useNotificationStream.spec.js`、`src/App.spec.js`）：取票后建立 EventSource、坏 JSON/缺 id 忽略、断线按 1s→2s→4s→8s→16s→30s 退避、连上后退避归零、401 后永久停止、页面隐藏时暂停重连并在回前台立即重连、断线期间 120s 轮询在重连成功后停止、登录后启动/登出或卸载停止
- 通知页新通知横幅（`src/views/NotificationsView.spec.js`）：打开时 `viewOpen=true`、卸载复位、第 2 页收到新事件时只显示「有 N 条新通知」而不打乱当前分页、点击横幅回到第 1 页并清零
```

**（5）已知限制追加**：

```markdown
- **实时通知依赖单实例内存注册表**：SSE 连接按 userId 存在本 JVM 内，多副本部署时只有持有连接的那台能推送；断线/漏掉的事件靠重连后的未读数刷新与通知页列表补齐，不做逐条补发。当前是单容器部署，暂无影响。
- **SSE 连接最长 30 分钟**：到点服务端 `complete`，客户端自动重连；重连瞬间的事件不补发，角标可能短暂少 1 条，下一次刷新未读数即恢复。
- **反代必须关闭代理缓冲**：`deploy/nginx/forum.conf` 对 `/forum/api/notifications/stream` 单独匹配并设 `proxy_buffering off` + 3600s 读超时；以后改 CDN 或反代必须保留这两项，否则事件会被攒住直到心跳。
```

### Step 5-4：更新 `docs/DEVELOPMENT.md`

**（1）§2.2 迭代轮次表**：在第五轮行之后追加：

```markdown
| 第六轮 | 实时通知（SSE） | 5 | ✅ 已完成 | 2026-10-22 |
```

同段落后面的「已完成 5 轮」改为「已完成 6 轮」（用 `grep -n '已完成 5 轮' docs/DEVELOPMENT.md` 定位，只有一处）。

**（2）§3.2 测试与构建基线**：更新为本轮实测值

- 后端：`16 个测试类 / 127 个用例`（1 例 POSIX 权限用例在 Windows 跳过；原 13/105）
- 前端：`30 个 spec / 295 个用例`（原 28/274）
- 类分布新增：`NotificationStreamServiceTest`（6）、`StreamTicketServiceTest`（6）、`SseFlowIntegrationTest`（4）
- 接口计数：`36` → `38`；`NotificationController` 行的端点数 `4` → `6`，并注明其中 SSE 端点 `GET /api/notifications/stream` 走 ticket 鉴权（不在 JWT 过滤器内）

**（3）§3.4 通知接口表**：在现有 4 个端点之后追加：

```markdown
| `POST /api/notifications/stream-ticket` | 需要 JWT | `Result<StreamTicketResponse>`：一次性 `ticket` 与 `expiresIn`（秒，默认 60）；每次调用生成新票 |
| `GET /api/notifications/stream?ticket=<uuid>` | 无需 JWT，ticket 自校验 | `text/event-stream`；票无效/过期/已用 → 401 `{"success":false,"message":"通知连接凭证无效或已过期"}`；每 15s 发 `:heartbeat` 注释；单条连接最长 30 分钟 |
```

**（4）§4.11 新小节**：在 `### 4.10 第五轮：全文搜索（2026-10-21）` 的收尾 `---` 之后、`## 5. 待办与后续计划` 之前插入：

```markdown
### 4.11 第六轮：实时通知（SSE）（2026-10-22）

计划：`docs/superpowers/plans/2026-10-22-round6-sse-notifications.md`（5 个任务：后端 SSE 基础设施 → 事务提交后推送 → 前端连接与角标 → 通知页即时插入 → 部署与文档）

目标：把 120s 轮询升级为服务端推送——已登录用户在任何页面都能即时看到角标 +1 与右上角轻提示；断线自动退避重连，重连后校准未读数；通知页打开且在第 1 页时新通知直接插入列表头部。

新增/修改的文件：

| 位置 | 文件 | 要点 |
| --- | --- | --- |
| 后端 | `service/NotificationStreamService.java` | 按 userId 维护 `LinkedHashSet<SseEmitter>`（HashMap + 锁），每用户最多 5 条（超出挤掉最早）；`@Scheduled` 每 15s 发 `:heartbeat`；超时/完成/出错时注销并 complete |
| 后端 | `service/StreamTicketService.java` | UUID 一次性 ticket，60s TTL；`ConcurrentHashMap` 存储，取用时原子移除，签发/消费时顺带清理过期票，避免内存泄漏 |
| 后端 | `exception/InvalidStreamTicketException.java` | 401 语义；`GlobalExceptionHandler` 新增 handler 返回统一错误体 |
| 后端 | `controller/NotificationController.java` | `POST /stream-ticket`（需 JWT）+ `GET /stream?ticket=`（SSE，返回 `SseEmitter`） |
| 后端 | `config/SecurityConfig.java` | `permitAll` 放行 `GET /api/notifications/stream`（票在 controller 内自校验，不能靠 JWT 头） |
| 后端 | `config/SseConfig.java` | `@EnableScheduling`（心跳与过期票清理） |
| 后端 | `service/NotificationService.java`、`service/CommentService.java`、`service/LikeService.java` | 创建通知后注册 `TransactionSynchronization.afterCommit` 推送；无事务同步时直接推送；3 个调用点零改动（推送封装在 `create` 内） |
| 后端 | `application.yml`、`src/test/resources/application-test.yml` | `app.sse.timeout-ms/heartbeat-ms/ticket-ttl-ms/max-connections-per-user`，可用 `SSE_*` 环境变量覆盖 |
| 前端 | `composables/useNotificationStream.js` | `EventSource` + 一次性 ticket；状态机 `connecting/open/retrying/disabled/closed`；退避 1s→30s；401 停止；断线期间 120s 轮询；页面隐藏暂停重连 |
| 前端 | `stores/notifications.js` | `pendingNewCount`/`viewOpen`/`listLoaded`/`handleIncoming`：按 id 去重，第 1 页即时插入，其它情况累计横幅 |
| 前端 | `views/NotificationsView.vue` | 打开/卸载维护 `viewOpen`；「有 N 条新通知，点击刷新」横幅 |
| 前端 | `App.vue` | 用 composable 替换 120s 全局轮询；登出清空角标与横幅 |

关键取舍：

- **SSE 而不是 WebSocket**：需求是单向服务端推送，SSE 走普通 HTTP、复用现有 nginx/鉴权路径，不需要额外协议与心跳协商；代价是浏览器连接数上限（HTTP/1.1 下 6 条/域），因此每用户最多 5 条并在 UI 上只开一条。
- **一次性 ticket 而不是 URL 带 JWT / 改 cookie**：`EventSource` 不能设 `Authorization` 头；把 JWT 放查询串会进 nginx 日志与 Referer。ticket 60s、单次使用、与用户绑定，泄露窗口最小。
- **事务提交后推送（`afterCommit`）**：评论/点赞的事务若回滚，通知不该已送达；无事务同步（单测直接调用）时立即推送，保持 `NotificationService` 可独立测试。
- **断线恢复 120s 轮询**：与改造前的兜底一致，重连成功后立刻停止；不引入消息队列与离线补发，接受「断线窗口内的个别事件不逐条到达，但未读数最终一致」。
- **心跳用 SSE 注释帧 `:heartbeat`**：不触发浏览器 `onmessage`，前端无需分辨事件类型；15s 远小于常见 60s 代理空闲超时。

测试与验证：后端新增 3 个测试类共 17 例 + `NotificationServiceTest` 4 例 + 集成 stage4 1 例（总计 16 类/127 例），前端新增 `useNotificationStream.spec.js` 10 例、`App.spec.js` 2 例、store 6 例、通知页 3 例（总计 30 spec/295 例）。上线验收见本节末尾。

已知限制：

- 连接注册表在单 JVM 内存中，多副本部署时跨实例不广播；无离线补发。
- 单条连接 30 分钟到点断开，客户端自动重连；重连瞬间的事件不补发。
- nginx 必须对 SSE 路径关闭 `proxy_buffering` 并放宽 `proxy_read_timeout`，否则事件被攒住或每分钟断开。

上线验收（2026-10-22，执行后回填）：推送后 Jenkins Build `#<N>` **SUCCESS**，镜像 `localhost/forum-backend:<N>`，`forum-backend` `Up (healthy)`；`/forum/api/notifications/stream` 无 ticket 返回 **401 JSON**；登录取票后 `curl -N` 能收到 `event:notification`；同一 ticket 第二次连接返回 401；后端 16 类/127 例、前端 30 spec/295 例、`VITE_BASE_PATH=/forum/ pnpm build` 全部通过。
```

**（5）§5.2 候选功能表**：

标题改为：

```markdown
### 5.2 候选功能（第六轮已取实时通知，其余未开始）
```

`WebSocket 实时通知` 行替换为：

```markdown
| ~~实时通知~~ ✅ 已完成 | 高 | 大 | 第六轮已交付：SSE + 一次性 ticket + 发布事件在事务提交后推送；前端退避重连、角标即时更新、通知页第 1 页即时插入（§4.11） |
```

结尾段「第三轮已取前两项…第五轮已取全文搜索（见 §4.10）；其余候选（WebSocket 实时通知等）…」改为：

```markdown
第三轮已取前两项（见 §4.8），第四轮已取头像上传与深色模式（见 §4.9），第五轮已取全文搜索（见 §4.10），第六轮已取实时通知（见 §4.11）；其余候选留待后续轮次，按同样的「审计 → 计划 → 逐任务执行 → 验证 → 提交」流程推进，避免一次铺开。
```

**（6）§6 已知问题与限制表**追加 3 行：

```markdown
| SSE 连接注册表只在单 JVM 内存中 | 多副本部署时，用户连在 A 实例、事件在 B 实例产生，就收不到实时推送 | 当前单容器部署；若横向扩容需引入广播（Redis pub/sub 等），未立项 |
| 断线期间的通知不逐条补发 | 断线窗口内的事件不推送；重连后只校准未读数，列表需进入通知页才刷新 | 已接受：角标最终一致；补齐需持久化事件游标，成本大于收益 |
| 单条 SSE 连接最长 30 分钟 | 到点服务端主动 `complete`，前端自动重连 | 有意设计：避免半死连接长期占用；重连由客户端透明完成，用户无感 |
```

**（7）§7 关键决策记录表**追加 3 行：

```markdown
| 实时通知协议 | **SSE**，不用 WebSocket | 需求是单向推送；SSE 是普通 HTTP，复用现有 nginx/鉴权/日志路径，不需要自定义心跳与重连协议 |
| SSE 鉴权 | **一次性 ticket（60s、单次使用）**，不在 URL 带 JWT | `EventSource` 不能设请求头；JWT 进查询串会落进 nginx 访问日志与 Referer，ticket 把泄露窗口压到最小 |
| 通知推送时机 | **事务提交后（`afterCommit`）推送** | 评论/点赞回滚时不能已经推送；无事务同步时直接推送，保证 service 单测可独立运行 |
```

**（8）§8 变更日志**：在表头分隔行之后、现有第一行之前插入（哈希在 Step 5-9 回填）：

```markdown
| 2026-10-22 | docs | 第六轮实时通知上线验收（Build #<N> 成功、ticket/SSE 冒烟实测）与变更日志回填 | 本次提交 |
| 2026-10-22 | docs | 记录第六轮实时通知（SSE）功能（README、开发记录、部署手册） | `<hash>` |
| 2026-10-22 | feat | 第六轮前端：通知页新通知横幅与第 1 页即时插入 | `<hash>` |
| 2026-10-22 | feat | 第六轮前端：SSE 连接管理、退避重连与实时角标 | `<hash>` |
| 2026-10-22 | feat | 第六轮后端：通知创建在事务提交后推送实时事件 | `<hash>` |
| 2026-10-22 | feat | 第六轮后端：SSE 基础设施（一次性 ticket、按用户连接注册表、心跳与超时清理） | `<hash>` |
| 2026-10-22 | docs | 记录第六轮实时通知（SSE）设计 | `a677986` |
```

### Step 5-5：更新 `docs/deployment/README.md`

**（1）§5 冒烟检查**：在「全文搜索（V3 pg_trgm）验证」小节之后、`## 6. 排障` 之前插入新小节：

````markdown
### 实时通知（SSE）验证

```bash
# 1) 无 ticket = 401（后端 JSON，而不是 SPA 的 200 HTML）
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: raspi' \
  http://127.0.0.1/forum/api/notifications/stream
# 期望：401

# 2) 登录拿 JWT，再换一次性 ticket
TOKEN=$(curl -fsS -H 'Content-Type: application/json' \
  -d "{\"username\":\"${SMOKE_USER:-admin}\",\"password\":\"${SMOKE_PASS:-admin123456}\"}" \
  http://127.0.0.1:8086/api/auth/login | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
TICKET=$(curl -fsS -X POST -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8086/api/notifications/stream-ticket | sed -n 's/.*"ticket":"\([^"]*\)".*/\1/p')
echo "ticket=${TICKET:0:8}…"          # 期望非空

# 3) 真实订阅：curl -N 关闭本地缓冲，命令会挂住等事件
curl -N -s -H 'Host: raspi' \
  "http://127.0.0.1/forum/api/notifications/stream?ticket=$TICKET" &
SSE_PID=$!
sleep 1

# 4) 用第二个账号评论 admin 的帖子触发 REPLY 事件；没有第二个账号就换一个帖子点赞
TOKEN2=$(curl -fsS -H 'Content-Type: application/json' \
  -d '{"username":"smoke_user","password":"<该账号密码>"}' \
  http://127.0.0.1:8086/api/auth/login | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
curl -fsS -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN2" \
  -d '{"content":"SSE 冒烟"}' http://127.0.0.1:8086/api/posts/1/comments >/dev/null

# 5) 观察 curl 输出：应出现
#    event:notification
#    data:{"id":...,"type":"REPLY",...}
sleep 3
kill $SSE_PID

# 6) ticket 单次使用：同一张票再连一次必须 401
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: raspi' \
  "http://127.0.0.1/forum/api/notifications/stream?ticket=$TICKET"
# 期望：401
```

浏览器验收（可选但推荐）：两个浏览器分别登录 A/B，A 停在任意页面，B 回复 A 的帖子——A 顶栏角标立即 +1 并弹出右上角提示；A 打开 `/forum/notifications` 停在第 1 页，再让 B 回复一次——新通知直接出现在列表头部，不出现横幅；A 翻到第 2 页后再触发——出现「有 N 条新通知，点击刷新」，点击后回到第 1 页且横幅消失。

> 若事件要等 15s 心跳才到，说明 nginx 仍在缓冲：检查 `location = /forum/api/notifications/stream` 是否被发布（`grep` 服务器上的 snippet）并 reload。
````

**（2）§6 排障表**追加 3 行：

```markdown
| `/forum/api/notifications/stream` 返回 200 HTML 或 404 | nginx 精确匹配缺失，请求落到 SPA 的 `try_files`：确认 snippet 含 `location = /forum/api/notifications/stream` 并已 reload；该端点无 ticket 必须是 401 JSON |
| SSE 事件延迟十几秒或一次性涌出一批 | `proxy_buffering off` 未生效（或中间还有一层缓冲）：检查精确匹配与 `proxy_cache off`，用 `curl -N` 直连 8086 对比 |
| SSE 连接恰好每分钟断开一次 | `proxy_read_timeout` 被通用 API 块的 60s 覆盖：SSE 必须走独立 location（3600s），确认 server 上 snippet 是本轮版本 |
```

**（3）§7 已知限制**追加 3 行：

```markdown
- **SSE 只支持单实例**：连接注册表在后端进程内存中，没有跨副本广播与消息回放；横向扩容前需要先补 pub/sub 或粘性路由。
- **连接最长 30 分钟**：服务端到点 `complete`，浏览器自动换票重连；重连瞬间的事件不补发，角标靠未读数刷新兜底。
- **断线重连策略是「换票重连」而非补发**：保证的是角标/列表最终一致，不保证断线窗口内每条通知都到达。
```

> 部署手册里所有含 `\n`、引号的命令都在反引号代码块内，复制时保持原样；`<该账号密码>` 是示例占位，实际用验收账号密码替换。

### Step 5-6：全量验证

```bash
cd /e/JavaSpace/forum
git status --short
```

预期：只有本轮预期改动（源码 + 六份文档/部署文件，含本计划）；无 `target/`、`frontend/dist/`、`frontend/node_modules/`（均已 gitignore）。

```bash
cd /e/JavaSpace/forum && mvn -B clean test
```

预期：`BUILD SUCCESS`；`Tests run: 127, Failures: 0, Errors: 0, Skipped: 1`。若数量不符，先查是否有测试类未被 surefire 拾取（命名必须以 `Test` 结尾）。

```bash
cd /e/JavaSpace/forum/frontend && pnpm test
```

预期：`Test Files 30 passed`、`Tests 295 passed`。

```bash
cd /e/JavaSpace/forum/frontend && MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build
```

预期：`✓ built in ...`；`frontend/dist/index.html` 引用 `/forum/assets/`。

```bash
grep -n 'location = /forum/api/notifications/stream' /e/JavaSpace/forum/deploy/nginx/forum.conf
bash -n /e/JavaSpace/forum/deploy/deploy.sh
```

预期：`location` 恰好 1 行；`bash -n` 无输出。

### Step 5-7：提交、推送与 Jenkins 构建

```bash
cd /e/JavaSpace/forum
git add deploy/nginx/forum.conf deploy/deploy.sh README.md docs/DEVELOPMENT.md docs/deployment/README.md docs/superpowers/plans/2026-10-22-round6-sse-notifications.md
git commit -m "docs: 记录第六轮实时通知（SSE）并上线验收"
git -c http.version=HTTP/1.1 push origin master
```

为什么带 `-c http.version=HTTP/1.1`：第五轮实测直连 push 偶发 `Connection was reset`，HTTP/1.1 可稳定推上去（Jenkins 侧仍能从 GitHub 拉取）。

等 Jenkins（`pollSCM H/5`，最长约 5 分钟；主机无公网入口，没有 webhook）：

```bash
PREV=$(curl -fsS 'http://raspi:8080/job/javaforum/lastBuild/api/json' | sed -n 's/.*"number":\([0-9]*\).*/\1/p')
echo "当前最新构建 #$PREV，等待新构建…"
for _ in $(seq 1 30); do
  sleep 10
  NUM=$(curl -fsS 'http://raspi:8080/job/javaforum/lastBuild/api/json' | sed -n 's/.*"number":\([0-9]*\).*/\1/p')
  [ "${NUM:-0}" -gt "${PREV:-0}" ] && break
done
curl -fsS "http://raspi:8080/job/javaforum/$NUM/api/json" | grep -o '"result":"[A-Z]*"'
```

预期：`NUM = PREV + 1`，`"result":"SUCCESS"`。若 Jenkins 返回 403（匿名读被关），改为在 Jenkins UI 观察；构建失败时看 `consoleText`：

```bash
curl -fsS "http://raspi:8080/job/javaforum/$NUM/consoleText" | tail -n 80
```

### Step 5-8：raspi 真实 SSE 冒烟

以下命令在开发机执行，通过 `ssh raspi` 落到服务器（`curl` 的 `Host: raspi` 不能省，`app.conf` 是 `server_name raspi`）。

**（1）无 ticket 必须 401（且是后端 JSON）**：

```bash
ssh raspi 'curl -s -o /tmp/sse.body -w "%{http_code}\n" -H "Host: raspi" http://127.0.0.1/forum/api/notifications/stream; cat /tmp/sse.body'
```

预期：`401`，body 为 `{"success":false,"message":"通知连接凭证无效或已过期",...}`。若得到 `200`，说明请求落到了 SPA（nginx 精确匹配没发布），**停止上线并回滚 nginx**。

**（2）登录取 ticket（target 用户）**：

```bash
ssh raspi 'bash -s' <<'EOF'
set -eu
BASE=http://127.0.0.1:8086
TARGET_USER="${SSE_TARGET_USER:-admin}"
TARGET_PASS="${SSE_TARGET_PASS:-admin123456}"
TOKEN=$(curl -fsS -H 'Content-Type: application/json' \
  -d "{\"username\":\"$TARGET_USER\",\"password\":\"$TARGET_PASS\"}" \
  "$BASE/api/auth/login" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
TICKET=$(curl -fsS -X POST -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/notifications/stream-ticket" | sed -n 's/.*"ticket":"\([^"]*\)".*/\1/p')
[ -n "$TICKET" ] || { echo "取票失败"; exit 1; }
echo "ticket=${TICKET:0:8}…（长度 ${#TICKET}）"
printf '%s' "$TICKET" > /tmp/sse.ticket
EOF
```

预期：打印 `ticket=xxxxxxxx…（长度 36）`（UUID 36 字符）。

**（3）`curl -N` 订阅，第二个用户评论触发事件**。actor 账号通过环境变量传入（用本轮冒烟前注册的账号，或任意第二个真实账号）：

```bash
ssh raspi 'bash -s' <<'EOF'
set -eu
BASE=http://127.0.0.1:8086
ACTOR_USER="${SSE_ACTOR_USER:?请设置 SSE_ACTOR_USER（第二个账号）}"
ACTOR_PASS="${SSE_ACTOR_PASS:?请设置 SSE_ACTOR_PASS}"
TICKET=$(cat /tmp/sse.ticket)

# 找一篇 target 用户（默认 admin）写的帖子：这样 actor 评论才会产生 REPLY 通知
POST_ID=$(sudo -u pi podman exec postgresql psql -U postgres -d javaforum -tAc \
  "select p.id from posts p join users u on u.id = p.author_id where u.username = '${SSE_TARGET_USER:-admin}' and p.deleted = false order by p.id limit 1")
[ -n "$POST_ID" ] || { echo "target 用户没有帖子，改用手工验证"; exit 1; }

curl -N -s -H 'Host: raspi' "http://127.0.0.1/forum/api/notifications/stream?ticket=$TICKET" > /tmp/sse.out &
SSE_PID=$!
sleep 1

ACTOR=$(curl -fsS -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ACTOR_USER\",\"password\":\"$ACTOR_PASS\"}" \
  "$BASE/api/auth/login" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
curl -fsS -H 'Content-Type: application/json' -H "Authorization: Bearer $ACTOR" \
  -d '{"content":"SSE 冒烟"}' "$BASE/api/posts/$POST_ID/comments" > /dev/null

sleep 3
kill $SSE_PID 2>/dev/null || true
echo '--- SSE 输出 ---'
cat /tmp/sse.out
grep -q 'event:notification' /tmp/sse.out \
  || { echo "未收到 event:notification"; exit 1; }
echo '--- SSE OK ---'
EOF
```

预期：输出 `event:notification` 与一行 `data:{"id":...,"type":"REPLY",...}`，最后 `--- SSE OK ---`。

**（4）同一 ticket 只能用一次**：

```bash
ssh raspi 'bash -s' <<'EOF'
TICKET=$(cat /tmp/sse.ticket)
code=$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: raspi' \
  "http://127.0.0.1/forum/api/notifications/stream?ticket=$TICKET")
[ "$code" = "401" ] || { echo "重放 ticket 返回 $code（应为 401）"; exit 1; }
echo "ticket 重放被拒绝：401"
EOF
```

预期：`ticket 重放被拒绝：401`。

**（5）浏览器双账号验收**（可选但建议）：两个浏览器分别登录 A/B，A 停在首页，B 回复 A 的帖子 → A 角标 +1 + 右上角提示；A 打开通知页停在第 1 页，B 再回复 → 新行直接插入；A 翻到第 2 页，B 再回复 → 出现「有 N 条新通知，点击刷新」，点击后回第 1 页且横幅消失。

### Step 5-9：回填哈希与验收记录

```bash
cd /e/JavaSpace/forum
git log --oneline a677986..HEAD
```

把输出的 5 个提交哈希（Task 1–5；回填提交自身在表里写作「本次提交」）填入 `docs/DEVELOPMENT.md` §8 的 `<hash>` 占位；把实际构建号填入「Build #<N>」与 §4.11 的上线验收段（同时替换其中的 `<N>` 镜像 tag）。`<hash>` 与 `<N>` 是**执行期回填**，不是代码占位符——这是第五轮沿用下来的事实记录方式。

```bash
git add docs/DEVELOPMENT.md
git commit -m "docs: 回填第六轮提交哈希与上线验收"
git -c http.version=HTTP/1.1 push origin master
```

预期：Jenkins 再跑一次（Build #N+1）并 SUCCESS；若失败需排查文档改动是否误改了代码块围栏。

---

## 7. 新增 / 修改文件清单

### 新增

| 层 | 路径 | 任务 |
| --- | --- | --- |
| 后端 | `src/main/java/org/song/forum/service/NotificationStreamService.java` | Task 1 |
| 后端 | `src/main/java/org/song/forum/service/StreamTicketService.java` | Task 1 |
| 后端 | `src/main/java/org/song/forum/config/SseConfig.java` | Task 1 |
| 后端 | `src/main/java/org/song/forum/exception/InvalidStreamTicketException.java` | Task 1 |
| 后端 | `src/main/java/org/song/forum/dto/notification/StreamTicketResponse.java` | Task 1 |
| 后端测试 | `src/test/java/org/song/forum/service/NotificationStreamServiceTest.java` | Task 1 |
| 后端测试 | `src/test/java/org/song/forum/service/StreamTicketServiceTest.java` | Task 1 |
| 后端测试 | `src/test/java/org/song/forum/controller/SseFlowIntegrationTest.java` | Task 1 |
| 前端 | `frontend/src/composables/useNotificationStream.js` | Task 3 |
| 前端测试 | `frontend/src/composables/useNotificationStream.spec.js` | Task 3 |
| 前端测试 | `frontend/src/App.spec.js` | Task 3 |

### 修改

| 层 | 路径 | 任务 |
| --- | --- | --- |
| 后端 | `src/main/java/org/song/forum/controller/NotificationController.java` | Task 1 |
| 后端 | `src/main/java/org/song/forum/config/SecurityConfig.java` | Task 1 |
| 后端 | `src/main/java/org/song/forum/exception/GlobalExceptionHandler.java` | Task 1 |
| 后端 | `src/main/resources/application.yml` | Task 1 |
| 后端 | `src/test/resources/application-test.yml` | Task 1 |
| 后端 | `src/main/java/org/song/forum/service/NotificationService.java` | Task 2 |
| 后端 | `src/main/java/org/song/forum/service/CommentService.java` | Task 2 |
| 后端 | `src/main/java/org/song/forum/service/LikeService.java` | Task 2 |
| 后端测试 | `src/test/java/org/song/forum/service/NotificationServiceTest.java` | Task 2 |
| 前端 | `frontend/src/api/index.js` | Task 3 |
| 前端 | `frontend/src/stores/notifications.js` | Task 3 |
| 前端 | `frontend/src/stores/notifications.spec.js` | Task 3 |
| 前端 | `frontend/src/App.vue` | Task 3 |
| 前端 | `frontend/src/views/NotificationsView.vue` | Task 4 |
| 前端 | `frontend/src/views/NotificationsView.spec.js` | Task 4 |
| 部署 | `deploy/nginx/forum.conf` | Task 5 |
| 部署 | `deploy/deploy.sh` | Task 5 |
| 文档 | `README.md` | Task 5 |
| 文档 | `docs/DEVELOPMENT.md` | Task 5 |
| 文档 | `docs/deployment/README.md` | Task 5 |
| 计划 | `docs/superpowers/plans/2026-10-22-round6-sse-notifications.md` | 本次（设计后、执行前） |

`CommentService` 与 `LikeService` 在 Task 2 修改：三个触发点（REPLY / LIKE / FAVORITE）改调 `NotificationService.create(...)`，推送封装在 `create` 内，避免把事务边界知识散落到业务代码里。

## 8. 测试数量预期

| 范围 | 现状 | 本轮新增 | 预计 | 说明 |
| --- | --- | --- | --- | --- |
| 后端测试类 | 13 | +3 | **16** | `NotificationStreamServiceTest`、`StreamTicketServiceTest`、`SseFlowIntegrationTest` |
| 后端用例 | 105 | +22 | **127** | Task 1 17 例 + Task 2 5 例（4 个单测 + 1 个集成）；1 例 POSIX 权限用例 Windows 跳过 |
| 前端 spec 文件 | 28 | +2 | **30** | `useNotificationStream.spec.js`、`App.spec.js` |
| 前端用例 | 274 | +21 | **295** | store +6、composable +10、App +2、通知页 +3 |
| REST 接口 | 36 | +2 | **38** | `POST /stream-ticket`、`GET /stream` |

## 9. 风险与回滚

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| SSE 连接泄漏（客户端反复重连、服务端不注销） | 线程/内存缓慢增长，最终 OOM | 三层清理：客户端 `stop()`、`onCompletion/onTimeout/onError` 中注销、服务端 30 分钟超时兜底；`NotificationStreamServiceTest` 覆盖“重复注销/超时清理/上限挤掉最早” |
| 每用户上限 5 条被忽略 | 多标签页反复打开会占满连接池 | 服务端超出即 complete 最早的一条；前端只允许一条连接（`started` 幂等） |
| 广播在事务内执行 → 回滚后仍推送 | 用户看到实际不存在的点赞/回复 | `afterCommit` + 无同步时直接推送；Task 2 单测覆盖回滚不推送 |
| 推送阻塞 HTTP 线程（慢客户端） | 单个 SSE 连接拖慢写接口 | `SseEmitter.send` 在 try/catch 内，失败即注销；心跳每 15s 一次且单连接串行；连接数上限 5 |
| nginx 漏配 `proxy_buffering off` | 事件被攒住，前端要等心跳才收到，看起来“实时通知坏了” | `deploy.sh` 冒烟强制 401 检查；部署手册列了“延迟十几秒”排障行；Step 5-8 用 `curl -N` 实测 |
| 代理 60s 读超时覆盖 SSE | 连接每分钟断一次，浏览器频繁重连 | 专用 `location =` 与 `proxy_read_timeout 3600s`；排障表有对应条目 |
| 前端 `viewOpen` 未复位 | 离开通知页后事件继续插入旧列表 | `onUnmounted` 复位 + 视图测试第 3 例 |
| 回滚代码后旧前端仍在跑 | 旧前端 120s 轮询 + 新后端 SSE 端点共存 | 无状态、向后兼容：不调用 `stream-ticket` 就不会建立连接；直接回滚到上一镜像即可，数据库无迁移、无需回滚 |

**回滚步骤**（与第五轮一致）：

```bash
# 方式一：Jenkins 用上一个 IMAGE_TAG 重新部署
# Build with Parameters -> IMAGE_TAG=<上一构建号> -> DEPLOY=true
# 方式二：只回滚容器
ssh raspi 'cd /pd/Program/forum/deploy && sudo -u pi sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=<旧tag>/" .env && sudo -u pi podman-compose -p forum up -d'
```

数据库无 V4 迁移、`app.sse.*` 有默认值，回滚代码不需要动配置或数据库；只是新前端会失去实时能力并退回轮询（`useNotificationStream` 需一并回滚前端 dist，由 `deploy.sh` 的 rsync 完成）。

## 10. Self-Review

写完后逐条核对（与 spec 一一对应）：

**（1）spec 覆盖**

| spec 要求 | 落地位置 |
| --- | --- |
| §2 目标：角标即时 +1 / 轻提示 / 通知页第 1 页插入 / 退避 1s→30s / 重连校准未读数 / 断线 120s 轮询 | Task 1（推送）、Task 3（连接与退避、120s 轮询、`refreshUnread`）、Task 4（第 1 页插入与横幅） |
| §3.1 `POST /api/notifications/stream-ticket` 契约（ticket + expiresIn，需 JWT） | Task 1 Step 1-3/1-8 + `SseFlowIntegrationTest` |
| §3.2 `GET /api/notifications/stream?ticket=` 契约（`text/event-stream`、401 语义、15s 心跳、30 分钟超时） | Task 1 Step 1-4~1-8 + `NotificationStreamServiceTest` / `SseFlowIntegrationTest` |
| §4 服务端状态机（注册/注销/上限 5/心跳/超时清理） | Task 1 Step 1-4（`NotificationStreamService`）与 6 个单测 |
| §5 ticket 生命周期（UUID、60s TTL、单次使用、过期清理、无泄漏） | Task 1 Step 1-3（`StreamTicketService`）+ 6 个单测（含过期、重放、清理） |
| §6 前端状态机（connecting/open/retrying/disabled/closed、指数退避、401 停止、坏 JSON 忽略、页面隐藏暂停） | Task 3 Step 3-3/3-3b（composable + 10 个测试） |
| §6.4 断线期间恢复 120s 轮询、重连后停止 | Task 3 实现 `startFallbackPolling` / `stopFallbackPolling`，测试第 6 例断言 |
| §7 通知创建后推送（事务提交后；无同步直接推送；回滚不推） | Task 2 Step 2-1/2-2 + 5 个测试（4 单测 + 1 集成 stage4） |
| §8 通知页行为（viewOpen、第 1 页插入、横幅、刷新清零、不破坏分页） | Task 3 store（`handleIncoming`/`loadList`/`markAllRead`）+ Task 4 视图与 3 个视图测试 |
| §9 安全（放行 GET stream、ticket 自校验、401 统一体、不泄露用户信息） | Task 1 Step 1-2/1-8；`SecurityConfig` 只放行 `GET`，`POST /stream-ticket` 仍需 JWT |
| §10 nginx（精确匹配、`proxy_buffering off`、`proxy_read_timeout 3600s`、`Connection ''`、`$is_args$args`） | Task 5 Step 5-1 |
| §11 `deploy.sh` 冒烟（无 ticket 401） | Task 5 Step 5-2 |
| §13 决策（SSE 非 WebSocket、一次性 ticket、120s 降级、afterCommit） | Task 5 Step 5-4 §7 决策表 + §4.11 关键取舍 |

**（2）占位符扫描**

- 所有 Java/Vue/JS 代码块均为完整文件或完整方法，可整体粘贴；测试代码含 import、`@SpringBootTest` 属性、mock 初始化，能直接运行。
- 仅有的占位符是执行期事实：`<N>`（Jenkins 构建号）、`<hash>`（提交哈希）、`<旧tag>`（回滚时的镜像 tag）、`<该账号密码>`（冒烟账号）。这些在 Step 5-9 与回滚步骤中明确说明如何替换，不属于“实现 X”式空壳。
- `docs/DEVELOPMENT.md` 的 §8 变更日志中 5 个 `<hash>` 在 Step 5-9 回填；这是项目既有的“本次提交 → 哈希”两段式流程，不是遗漏。

**（3）类型 / 命名一致性**

- 后端：`NotificationStreamService.push(Long userId, NotificationResponse payload)`、`StreamTicketService.issue(Long)/consume(String)`、`InvalidStreamTicketException(String)`、`Result<StreamTicketResponse>`、`PageResponse<NotificationResponse>` 与现有 DTO 风格一致；`NotificationController` 返回 `SseEmitter` 时其他方法仍返回 `Result<T>`。
- 前端：`notificationApi.streamTicket()` 返回 `{ ticket, expiresIn }`；store 的 `handleIncoming(n)` 与后端 JSON 字段（`id/type/postId/postTitle/commentId/content/isRead/createTime`）一致；`pendingNewCount` 在 `handleIncoming`、`loadList(0)`、`markAllRead`、登出四处清零，无遗漏。
- 命名歧义已按 spec 定稿：任务书里的 `addLive` → spec 的 `store.handleIncoming`；任务书 Task 3 提到“断线轮询 60s” → spec §6.4 的 **120s**（沿用改造前轮询，已用常量 `FALLBACK_POLL_MS = 120_000`，并在 Task 3 注释里说明）。这两处是任务书与 spec 的措辞差异，**以 spec 为准**，不改 spec。

**（4）测试可运行性**

- 后端：`StreamTicketServiceTest` / `NotificationStreamServiceTest` 是纯 Mockito 单测，无 Spring 上下文；`SseFlowIntegrationTest` 用独立 H2 库名（`sse-flow-test`）+ `ddl-auto=create-drop` + Flyway 关闭，与现有集成测试同一模式；JDK `HttpClient` 读流式响应需 `BodyHandlers.ofLines()`（不能 `ofString()`，否则会等连接结束）。
- 前端：composable 测试用可注入的 `EventSourceImpl` / `timers` / `documentRef`，`vi.useFakeTimers()` + `advanceTimersByTimeAsync`；`store` / `notify` 用普通对象或 `vi.fn()`，不受 Pinia/Element Plus 影响；`App.spec.js` 的 `vi.hoisted` 避免 mock 提升错误。
- `SseFlowIntegrationTest` 的“真实订阅”用例可能因 CI 网络/端口慢而偶发超时：`HttpClient` 设置 10s 请求超时，读事件用 `Future` + 5s 上限；若失败信息是“未收到事件”，先看是否 401（ticket 是否被 TestRestTemplate 的 POST 正确解析）。

**（5）实现顺序与提交粒度**

5 个 Task 各自一个 commit，按 Task 1→5 顺序执行（Task 5 末尾再加一个哈希回填提交，共 6 个）；每个 Task 结束时后端或前端测试全绿（Task 1/2 后端、Task 3/4 前端），Task 5 做全量回归与上线。Task 3 把 store 的实时合并状态一并实现（连接层是它的第一个调用方），Task 4 只做视图与边界测试——这样每个提交都能运行，不会出现“Task 3 提交后事件处理抛错、等 Task 4 才修”的中间态。

**（6）已知 spec 模糊点（未自行改 spec，按最保守解释实现）**

1. spec §6.4 写“恢复 120s 轮询”，任务书 Task 3 写“60s”——按 spec 用 120s（与改造前一致，用户体验无回退）。
2. 任务书 Task 4 写 store 增 `addLive`，spec §8 写 `handleIncoming`——按 spec 命名；两者语义相同（合并一条实时通知），`addLive` 未单独保留。
3. spec 未规定 `GET /stream` 每用户最多 5 条时“第 6 条”的处置——按“挤掉最早一条”实现（新连接优先，旧连接收到 complete 后由前端重连），并在 §4.11 记录。
4. spec 未规定断线期间“通知页已打开且第 1 页”时事件如何补——实现为：断线期间事件不推送（客户端收不到），重连后只校准未读数；用户点刷新或进入通知页时列表才更新。已在已知限制中说明。
5. spec 未规定 nginx 之外是否需要 `proxy_cache off`——一并加上（无副作用，防止以后加缓存层踩坑）。

## 11. Execution Handoff

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

执行方式：**由子代理逐任务执行**，每个 Task 完成后先做 **spec review**（对照本计划 §10 覆盖表与 spec 原文，确认没有漏项/改需求），再做 **quality review**（命名、异常路径、测试是否真能失败、有无硬编码），通过后才进入下一个 Task。每个 Task 结束提交一次；Task 5 的 push/部署/回填由主代理或指定的执行代理完成，构建号与哈希必须用实测值替换。

任务清单（勾选式跟进度）：

- [ ] Task 1：后端 SSE 基础设施（17 个测试）
- [ ] Task 2：通知创建事务提交后推送（5 个测试）
- [ ] Task 3：前端 SSE 连接、退避重连、实时角标（18 个前端测试）
- [ ] Task 4：通知页横幅与即时插入（3 个前端测试）
- [ ] Task 5：部署、文档、全量验证、推送、Jenkins、raspi 冒烟、哈希回填

执行时最容易写错的点（提前标注）：

1. `SseEmitter` 必须在 `send` 前 `addEventListener` 注册清理，否则客户端断开后注册表里的 emitter 只能等心跳失败才被清掉；心跳 `send` 的 `IOException` 要当作“连接已死”并从列表移除。
2. `TransactionSynchronizationManager.getSynchronizations()` 在无事务时抛 `IllegalStateException`，必须先 `isSynchronizationActive()` 判断；单测里 `initSynchronization()` 后务必 `clearSynchronization()`，否则污染同 JVM 的其它测试。
3. H2 上 `SseEmitter` 的 `send` 是写 servlet 响应，`TestRestTemplate` 读不了流；真实订阅必须用 JDK `HttpClient`（`BodyHandlers.ofLines()`）或 `WebClient`。
4. 前端 `EventSource` 的 `onerror` 在 401 时也会触发（服务端返回 401 后连接关闭），不要在 `onerror` 里立刻重连而不看 `disabled` 状态，否则 401 会变成每 30s 一次的请求风暴。
5. Vue 测试里 `vi.useFakeTimers()` 与 `await` 混用时，必须用 `vi.advanceTimersByTimeAsync()`，普通的 `advanceTimersByTime` 不会 flush 微任务，`streamTicket()` 的 Promise 永远不 resolve，测试会卡在 `connecting`。
6. `MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH` 不能省：Git Bash 会把 `/forum/` 当路径转换成 `C:/Program Files/Git/forum/`，构建产物前缀就错了（第五轮踩过）。

## 附录 A：本轮提交信息（逐字）

```text
feat: 增加 SSE 通知基础设施（ticket 鉴权 + 连接注册表）
feat: 通知创建在事务提交后实时推送
feat: 前端接入 SSE 实时通知与角标
feat: 通知页支持即时插入与新通知横幅
docs: 记录第六轮实时通知（SSE）并上线验收
docs: 回填第六轮提交哈希与上线验收
```

提交顺序与上表一致；第 5 条包含 `deploy/nginx/forum.conf`、`deploy/deploy.sh`、三份文档与本计划；第 6 条只改 `docs/DEVELOPMENT.md`（把 §8 的「本次提交」行补成最终哈希，并写入 Build 号与验收结论）。

## 附录 B：实施偏差记录（第六轮）

1. **`NotificationService.create` 的返回值**：spec（`docs/superpowers/specs/2026-10-22-round6-sse-notifications-design.md:89`）写的是
   `public void create(User recipient, User sender, Post post, Comment comment, NotificationType type, String content)`，
   实现改为返回 `Notification`（本计划 Step 2-2 的代码块即 `public Notification create(...)`）。
   原因：调用方与单测可以直接断言已持久化实体的字段/默认未读，不必为了拿 id 再查一次库；对现有调用点无影响（返回值可忽略）。

2. **「提交后才推送」的验证边界**：`SseFlowIntegrationTest.stage4` 只验证「收到 SSE 帧时，通知已经可以通过 REST 查到（未读数 ≥1）」，
   无法单独证明「先提交、后推送」的严格时序——HTTP/SSE 层观察不到服务端事务边界。真正的时序保证由
   `NotificationServiceTest` 的 `afterCommit` 单测承担：有事务同步时提交前不推送、回滚不推送、无事务时立即推送。
   第六轮质量修复又在 stage4 中加了 reader 流负向断言（只推给收件人，而非广播），并校验点赞响应 200/`success=true`。

3. **推送失败隔离**：`create` 的 `afterCommit` 与无事务分支统一走 `pushSafely(...)`，用 `try/catch (RuntimeException)` 记 `WARN`
   后继续返回通知。原因：通知此时已入库，推送失败若冒泡会变成业务请求 500（afterCommit 中还会抛回提交线程）。
   `NotificationServiceTest` 有专门用例断言推送抛异常时 `create` 不抛、仍返回通知，且 WARN 带异常上下文。

4. **`useNotificationStream` 改为 watch token（非仅布尔登录态）**：本计划 Step 3-1 与 spec §6.1 均写「watch auth token/用户」，
   但首版实现只 `watch(() => auth.isLoggedIn)`；A→B 直接登录（登录页无守卫、可重复登录）是 `true→true` 不触发，
   旧连接会把 A 的通知推进 B 的 store，A 的流若已 401 `disabled` 则 B 登录后永不 `start`。第六轮质量修复改为
   watch `auth.token`：变化时 `stop()` → `store.resetSession()`（清 `unreadCount/pendingNewCount/list/listPage/listLoaded/viewOpen`）
   → 有 token 则 `start()`。另加连接世代 `epoch`：`start()/stop()` 推进世代，取票返回后世代不符的旧 ticket 直接丢弃，
   避免「ticket 在途 → stop → start → 旧 ticket 晚到」用上一会话 ticket 建连。`useNotificationStream(options)`
   新增可注入参数（原样转发给 `createNotificationStream`），仅用于测试注入 api/EventSource/timers。

5. **增量审查后的测试补强（仅测试，无行为变更）**：增量审查提出「旧世代取票晚到失败（401）不得把新会话置为 disabled」
   的 catch 分支 epoch 守卫无用例（删掉该守卫测试仍全绿）。已补 `旧世代 ticket 晚到失败（401）不把新会话置为 disabled`
   用例（先红：删守卫即失败；后绿），锁定 `connect()` catch 中 `myEpoch !== epoch` 必须先于 401 判断。同时删除
   `App.spec.js` 中由 mock 自行注册 `onUnmounted` 的卸载断言——该用例验证的是 mock 而不是真实 composable，
   真实卸载契约已由 `useNotificationStream.spec.js`「组件卸载时断开连接」覆盖。store 的 `refreshUnread/loadList`
   响应缺少会话世代校验（跨账号旧响应可能回写）记为后续债务，不在本任务处理。
