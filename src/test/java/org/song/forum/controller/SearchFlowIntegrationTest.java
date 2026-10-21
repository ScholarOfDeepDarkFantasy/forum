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
import org.song.forum.dto.post.PostDetailResponse;
import org.song.forum.dto.post.PostRequest;
import org.song.forum.dto.post.PostSummaryResponse;
import org.song.forum.util.PageResponse;
import org.song.forum.util.Result;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第五轮全文搜索接口集成测试（H2 + 随机端口）：
 * 游客可搜 → 4 类 400 → 标题优先 → 正文命中与软删除 → 分页 → 参数夹取 → 通配符转义 → 时间倒序
 * <p>每个用例用自己的关键词与独立帖子，避免用例间数据互相命中。</p>
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:search-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SearchFlowIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private static String adminToken;
    private static String userToken;
    private static Long boardId;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private String loginAdmin() {
        LoginRequest l = new LoginRequest();
        l.setUsername("admin");
        l.setPassword("admin123456");
        ResponseEntity<Result<AuthResponse>> resp = rest.exchange(url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>(l), new ParameterizedTypeReference<>() {});
        return resp.getBody().getData().getToken();
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

    private Long createPost(String token, String title, String content) {
        PostRequest req = new PostRequest();
        req.setBoardId(boardId);
        req.setTitle(title);
        req.setContent(content);
        req.setTags(List.of());
        ResponseEntity<Result<PostDetailResponse>> resp = rest.exchange(url("/api/posts"), HttpMethod.POST,
                new HttpEntity<>(req, bearer(token)), new ParameterizedTypeReference<>() {});
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        return resp.getBody().getData().getId();
    }

    /**
     * 调搜索接口：rawQuery 直接拼在 '?' 之后，百分号编码由调用方负责。
     * 用 URI 重载而不是 String 重载——String 走 URI 模板展开，会把已经编码好的 %E5 再转义一次。
     */
    private ResponseEntity<Result<PageResponse<PostSummaryResponse>>> search(String rawQuery) {
        URI uri = URI.create(url("/api/posts/search?" + rawQuery));
        return rest.exchange(uri, HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
    }

    /** 关键词百分号编码：'100%' → '100%25'，中文与 '+&' 也能安全进 query */
    private String encode(String keyword) {
        return URLEncoder.encode(keyword, StandardCharsets.UTF_8);
    }

    /**
     * createTime 是微秒精度的 LocalDateTime，两次连续 HTTP 请求几乎不会撞在同一微秒；
     * 排序用例之间显式 sleep 一下，避免「时间倒序」的断言偶发不稳定。
     */
    private void pauseBetweenCreates() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试线程被中断", e);
        }
    }

    @Test
    @Order(1)
    void guestCanSearch() {
        adminToken = loginAdmin();
        BoardRequest board = new BoardRequest();
        board.setName("搜索测试板块");
        board.setDescription("用于全文搜索接口测试");
        board.setSortOrder(1);
        ResponseEntity<Result<BoardResponse>> created = rest.exchange(url("/api/boards"), HttpMethod.POST,
                new HttpEntity<>(board, bearer(adminToken)), new ParameterizedTypeReference<>() {});
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        boardId = created.getBody().getData().getId();
        userToken = registerAndLogin("searcher");
        createPost(userToken, "搜索测试帖", "全文搜索接口的准备数据");

        // 不带 Authorization：搜索是公开 GET
        ResponseEntity<Result<PageResponse<PostSummaryResponse>>> resp = search("keyword=" + encode("全文搜索"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().isSuccess()).isTrue();
        assertThat(resp.getBody().getData()).isNotNull();
    }

    @Test
    @Order(2)
    void missingKeywordReturns400() {
        ResponseEntity<Result<PageResponse<PostSummaryResponse>>> resp = search("page=0&size=10");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getMessage()).contains("keyword");
    }

    @Test
    @Order(3)
    void blankKeywordReturns400() {
        ResponseEntity<Result<PageResponse<PostSummaryResponse>>> resp = search("keyword=%20%20");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getMessage()).isEqualTo("搜索关键词不能为空");
    }

    @Test
    @Order(4)
    void keywordLongerThan50CodePointsReturns400() {
        ResponseEntity<Result<PageResponse<PostSummaryResponse>>> resp = search("keyword=" + "a".repeat(51));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getMessage()).contains("不能超过 50 个字符");
    }

    @Test
    @Order(5)
    void titleHitRanksBeforeContentHit() {
        // 标题命中先建、正文命中后建：只按时间倒序时正文会排第一，只有 CASE 生效才能通过
        Long titleHit = createPost(userToken, "标题优先词 出现在标题里", "正文与关键词无关");
        pauseBetweenCreates();
        Long contentHit = createPost(userToken, "正文命中排第二", "这段正文包含 标题优先词 但标题不含");

        PageResponse<PostSummaryResponse> page = search("keyword=" + encode("标题优先词") + "&page=0&size=10")
                .getBody().getData();

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content()).extracting(PostSummaryResponse::getId)
                .containsExactly(titleHit, contentHit);
        // 标题命中且正文不含关键词：不给片段，避免在标题下面重复显示一遍标题
        assertThat(page.content().get(0).getSnippet()).isEmpty();
        // 正文命中：服务端给的是命中处窗口，交给前端 buildSnippet 清洗与高亮
        assertThat(page.content().get(1).getSnippet()).contains("标题优先词");
    }

    @Test
    @Order(6)
    void contentHitIsSearchableUntilSoftDeleted() {
        Long postId = createPost(userToken, "软删除验证", "这里正文包含 正文命中词 用于搜索");

        PageResponse<PostSummaryResponse> before = search("keyword=" + encode("正文命中词")).getBody().getData();
        assertThat(before.content()).extracting(PostSummaryResponse::getId).contains(postId);

        ResponseEntity<Result<Void>> deleted = rest.exchange(url("/api/posts/" + postId), HttpMethod.DELETE,
                new HttpEntity<>(bearer(userToken)), new ParameterizedTypeReference<>() {});
        assertThat(deleted.getStatusCode().value()).isEqualTo(200);

        PageResponse<PostSummaryResponse> after = search("keyword=" + encode("正文命中词")).getBody().getData();
        assertThat(after.totalElements()).isZero();
        assertThat(after.content()).isEmpty();
    }

    @Test
    @Order(7)
    void paginationReportsTotalAndLastFlag() {
        createPost(userToken, "分页检索词 一", "分页检索词 正文一");
        createPost(userToken, "分页检索词 二", "分页检索词 正文二");
        createPost(userToken, "分页检索词 三", "分页检索词 正文三");

        PageResponse<PostSummaryResponse> first = search("keyword=" + encode("分页检索词") + "&page=0&size=2")
                .getBody().getData();
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(first.content()).hasSize(2);
        assertThat(first.size()).isEqualTo(2);
        assertThat(first.last()).isFalse();

        PageResponse<PostSummaryResponse> second = search("keyword=" + encode("分页检索词") + "&page=1&size=2")
                .getBody().getData();
        assertThat(second.content()).hasSize(1);
        assertThat(second.last()).isTrue();
    }

    @Test
    @Order(8)
    void pageAndSizeAreClamped() {
        createPost(userToken, "钳制词标题", "钳制词正文");

        PageResponse<PostSummaryResponse> bigSize = search("keyword=" + encode("钳制词") + "&page=0&size=999")
                .getBody().getData();
        assertThat(bigSize.size()).isEqualTo(50);

        PageResponse<PostSummaryResponse> negativePage = search("keyword=" + encode("钳制词") + "&page=-1&size=10")
                .getBody().getData();
        assertThat(negativePage.page()).isZero();
        assertThat(negativePage.size()).isEqualTo(10);
    }

    @Test
    @Order(9)
    void likeWildcardsAreEscaped() {
        createPost(userToken, "100%完成", "进度说明");
        createPost(userToken, "1000完成", "进度说明");
        createPost(userToken, "100_完成", "进度说明");
        createPost(userToken, "100!完成", "进度说明");

        // '%' 不转义时会当通配符，搜 100% 会误命中 1000完成
        PageResponse<PostSummaryResponse> percent = search("keyword=" + encode("100%")).getBody().getData();
        assertThat(percent.totalElements()).isEqualTo(1);
        assertThat(percent.content().get(0).getTitle()).isEqualTo("100%完成");

        // '_' 同理（单个字符通配）
        PageResponse<PostSummaryResponse> underscore = search("keyword=" + encode("100_")).getBody().getData();
        assertThat(underscore.totalElements()).isEqualTo(1);
        assertThat(underscore.content().get(0).getTitle()).isEqualTo("100_完成");

        // '!' 是 ESCAPE 指定的转义符，它自己也要能被搜到
        PageResponse<PostSummaryResponse> bang = search("keyword=" + encode("100!")).getBody().getData();
        assertThat(bang.totalElements()).isEqualTo(1);
        assertThat(bang.content().get(0).getTitle()).isEqualTo("100!完成");
    }

    @Test
    @Order(10)
    void titleHitsAreOrderedByCreateTimeDesc() {
        Long older = createPost(userToken, "时间排序词 旧帖", "正文一");
        pauseBetweenCreates();
        Long newer = createPost(userToken, "时间排序词 新帖", "正文二");

        PageResponse<PostSummaryResponse> page = search("keyword=" + encode("时间排序词")).getBody().getData();

        assertThat(page.content()).extracting(PostSummaryResponse::getId).containsExactly(newer, older);
    }
}
