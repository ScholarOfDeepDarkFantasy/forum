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
        // 流是持久连接，不能落进任何 HTTP 缓存
        assertThat(opened.headers().firstValue("Cache-Control").orElse(""))
                .contains("no-cache");
        opened.body().close();

        assertThat(streamReject(ticket).statusCode()).isEqualTo(401);
    }

    private long unreadCount(String token) {
        // 接口返回 Result<Map<String, Long>>（{"count": n}），不是裸 Long
        Map<String, Long> data = rest.exchange(url("/api/notifications/unread-count"), HttpMethod.GET,
                new HttpEntity<>(bearer(token)),
                new ParameterizedTypeReference<Result<Map<String, Long>>>() {}).getBody().getData();
        return data.get("count");
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

}
