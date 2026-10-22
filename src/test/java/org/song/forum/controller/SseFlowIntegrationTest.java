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
import org.song.forum.dto.post.LikeToggleResponse;
import org.song.forum.dto.post.PostDetailResponse;
import org.song.forum.dto.post.PostRequest;
import org.song.forum.util.Result;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
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
        return requireData(resp, "登录 " + name).getToken();
    }

    private String loginAdmin() {
        LoginRequest l = new LoginRequest();
        l.setUsername("admin");
        l.setPassword("admin123456");
        ResponseEntity<Result<AuthResponse>> resp = rest.exchange(url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>(l), new ParameterizedTypeReference<>() {});
        return requireData(resp, "管理员登录").getToken();
    }

    private StreamTicketResponse issueTicket(String token) {
        ResponseEntity<Result<StreamTicketResponse>> resp = rest.exchange(
                url("/api/notifications/stream-ticket"), HttpMethod.POST,
                new HttpEntity<>(bearer(token)),
                new ParameterizedTypeReference<>() {});
        return requireData(resp, "取 SSE ticket");
    }

    /**
     * 取 Result.data 并带上可读的失败上下文：HTTP 状态/响应体/data 任一为空都直接断言失败，
     * 而不是在后续 {@code getId()} 处抛 NPE 或把错误状态码误读成业务结果。
     */
    private <T> T requireData(ResponseEntity<Result<T>> resp, String what) {
        assertThat(resp).as("%s：HTTP 响应", what).isNotNull();
        assertThat(resp.getStatusCode().is2xxSuccessful()).as("%s：HTTP 状态 %s", what, resp.getStatusCode())
                .isTrue();
        Result<T> body = resp.getBody();
        assertThat(body).as("%s：响应体", what).isNotNull();
        T data = body.getData();
        assertThat(data).as("%s：响应体 data", what).isNotNull();
        return data;
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
     * <p>接收 {@link Iterator} 而不是 {@link Stream}：一个 Stream 只能取一次 iterator，
     * stage4 需要对同一条 reader 流做「建流确认」和「泄漏检查」两次读。</p>
     */
    private String readNotificationData(Iterator<String> iterator, Duration timeout) {
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
        ResponseEntity<Result<BoardResponse>> boardResp = rest.exchange(url("/api/boards"), HttpMethod.POST,
                new HttpEntity<>(board, bearer(adminToken)),
                new ParameterizedTypeReference<>() {});
        Long boardId = requireData(boardResp, "创建 SSE 测试板块").getId();

        PostRequest post = new PostRequest();
        post.setBoardId(boardId);
        post.setTitle("SSE 订阅测试帖");
        post.setContent("等一个实时通知");
        ResponseEntity<Result<PostDetailResponse>> postResp = rest.exchange(url("/api/posts"), HttpMethod.POST,
                new HttpEntity<>(post, bearer(readerToken)),
                new ParameterizedTypeReference<>() {});
        readerPostId = requireData(postResp, "创建 SSE 订阅测试帖").getId();
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
        // 流是持久连接，不能落进任何 HTTP 缓存
        assertThat(opened.headers().firstValue("Cache-Control").orElse(""))
                .contains("no-cache");
        opened.body().close();

        assertThat(streamReject(ticket).statusCode()).isEqualTo(401);
    }

    private long unreadCount(String token) {
        // 接口返回 Result<Map<String, Long>>（{"count": n}），不是裸 Long
        ResponseEntity<Result<Map<String, Long>>> resp = rest.exchange(
                url("/api/notifications/unread-count"), HttpMethod.GET,
                new HttpEntity<>(bearer(token)),
                new ParameterizedTypeReference<>() {});
        Map<String, Long> data = requireData(resp, "查询未读数");
        Long count = data.get("count");
        assertThat(count).as("未读数响应缺少 count 字段").isNotNull();
        return count;
    }

    /**
     * 轮询未读数直到达到期望值或超时（50ms 一次）。
     * <p>推送发生在 afterCommit 之后，正常应立即可见；重试窗口只是容忍提交可见性抖动，
     * 避免把偶发的读延迟误判成「推送早于提交」。</p>
     */
    private void waitForUnreadAtLeast(String token, long expected, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        long last = -1L;
        while (System.currentTimeMillis() < deadline) {
            last = unreadCount(token);
            if (last >= expected) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(last).as("未读数应在 %s 内达到 %d", timeout, expected).isGreaterThanOrEqualTo(expected);
    }

    @Test
    @Order(4)
    void stage4_likePushesRealSseEventOnlyToRecipient() throws Exception {
        // 楼主 = author，点赞者 = reader。另开一帖，避免依赖 stage1 帖子的归属
        String adminToken = loginAdmin();
        BoardRequest board = new BoardRequest();
        board.setName("SSE 推送板块");
        board.setSortOrder(2);
        ResponseEntity<Result<BoardResponse>> boardResp = rest.exchange(url("/api/boards"), HttpMethod.POST,
                new HttpEntity<>(board, bearer(adminToken)),
                new ParameterizedTypeReference<>() {});
        Long boardId = requireData(boardResp, "创建 SSE 推送板块").getId();

        PostRequest post = new PostRequest();
        post.setBoardId(boardId);
        post.setTitle("SSE 实时推送测试帖");
        post.setContent("点赞后楼主应立刻收到通知");
        ResponseEntity<Result<PostDetailResponse>> postResp = rest.exchange(url("/api/posts"), HttpMethod.POST,
                new HttpEntity<>(post, bearer(authorToken)),
                new ParameterizedTypeReference<>() {});
        Long postId = requireData(postResp, "创建 SSE 推送测试帖").getId();

        // 同时打开楼主和 reader 两条真实 SSE 连接。只开楼主一条时，若实现退化成
        // 「广播给所有在线连接」，测试依然会过（reader 未读数仍为 0），因此必须开 reader 流做负向断言。
        HttpResponse<Stream<String>> authorStream = openStream(issueTicket(authorToken).ticket());
        assertThat(authorStream.statusCode()).isEqualTo(200);
        HttpResponse<Stream<String>> readerStream = openStream(issueTicket(readerToken).ticket());
        assertThat(readerStream.statusCode()).isEqualTo(200);
        // 一个 Stream 只能取一次 iterator；两次读（建流确认/泄漏检查）复用同一个
        Iterator<String> authorLines = authorStream.body().iterator();
        Iterator<String> readerLines = readerStream.body().iterator();

        // 先读 reader 的一个心跳窗口，确认服务端已登记该连接。否则「reader 收不到」
        // 可能只是因为它的连接还没建立，负向断言会变成 vacuous。
        assertThat(readNotificationData(readerLines, Duration.ofMillis(300)))
                .as("点赞前 reader 流不应出现 notification 帧")
                .isNull();

        // 点赞同步执行：连接已经打开，帧会先进入客户端缓冲区，随后按行读取即可，
        // 无需另起线程。响应必须当场校验，否则 500 会被误报成「10s 内未收到事件」。
        ResponseEntity<Result<LikeToggleResponse>> like = rest.exchange(
                url("/api/posts/" + postId + "/like"), HttpMethod.POST,
                new HttpEntity<>(bearer(readerToken)),
                new ParameterizedTypeReference<>() {});
        assertThat(like.getStatusCode().value()).as("点赞请求 HTTP 状态").isEqualTo(200);
        assertThat(like.getBody()).as("点赞响应体").isNotNull();
        assertThat(like.getBody().isSuccess()).as("点赞业务状态 success").isTrue();
        assertThat(like.getBody().getData()).as("点赞响应 data").isNotNull();
        assertThat(like.getBody().getData().isActive()).as("点赞应生效").isTrue();

        String data;
        String readerLeak;
        try {
            data = readNotificationData(authorLines, Duration.ofSeconds(10));
            // 收到帧时作者流仍保持打开：此刻立即走 REST 查未读数，证明「推送时通知已可见」。
            // 真正的「提交前 vs 提交后」时序由 NotificationServiceTest 的 afterCommit 单测保证，
            // 集成测试无法观察事务边界，只能验证这个可见性结果。
            waitForUnreadAtLeast(authorToken, 1L, Duration.ofSeconds(2));
            // 点赞请求已结束：给 reader 流一个 400ms 的观察窗口，期间只允许心跳。
            // 若服务端把通知广播给所有连接，这里会读到 notification 帧。
            readerLeak = readNotificationData(readerLines, Duration.ofMillis(400));
        } finally {
            authorStream.body().close();
            readerStream.body().close();
        }

        assertThat(data).as("应在 10s 内收到 LIKE 通知事件").isNotNull();
        assertThat(data).contains("\"type\":\"LIKE\"");
        assertThat(data).contains("sse_reader");
        assertThat(data).contains("\"postId\":" + postId);

        // 只通知楼主：reader 的流上没有帧，reader 未读数为 0，楼主至少为 1
        assertThat(readerLeak).as("reader 不应收到该通知的 SSE 帧").isNull();
        assertThat(unreadCount(readerToken)).isZero();
        assertThat(unreadCount(authorToken)).isGreaterThanOrEqualTo(1L);
    }

}
