package org.song.forum.controller;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 3 阶段集成测试（H2 + 随机端口）：
 * 管理员建板块 → 用户发帖/编辑/软删除 → 游客列表/搜索/详情（浏览量自增）→ 越权编辑被拒 → /mine
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:post-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostFlowIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private static String adminToken;
    private static String userToken;
    private static Long boardId;
    private static Long postId;

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

    /** DataInitializer 种子管理员登录（板块 CRUD 已挂 ADMIN 权限，测试用种子账号） */
    private String loginAdmin() {
        LoginRequest l = new LoginRequest();
        l.setUsername("admin");
        l.setPassword("admin123456");
        ResponseEntity<Result<AuthResponse>> resp = rest.exchange(url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>(l), new ParameterizedTypeReference<>() {});
        return resp.getBody().getData().getToken();
    }

    @Test
    @Order(1)
    void stage1_adminCreatesBoard() {
        adminToken = loginAdmin();
        // 板块创建接口已挂 @PreAuthorize("hasRole('ADMIN')")，使用 DataInitializer 种子管理员
        BoardRequest req = new BoardRequest();
        req.setName("Java技术");
        req.setDescription("Java 相关讨论");
        req.setSortOrder(1);
        ResponseEntity<Result<BoardResponse>> resp = rest.exchange(url("/api/boards"), HttpMethod.POST,
                new HttpEntity<>(req, bearer(adminToken)), new ParameterizedTypeReference<>() {});
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        boardId = resp.getBody().getData().getId();
        assertThat(boardId).isNotNull();

        // 板块名重复 → 400
        ResponseEntity<Result<BoardResponse>> dup = rest.exchange(url("/api/boards"), HttpMethod.POST,
                new HttpEntity<>(req, bearer(adminToken)), new ParameterizedTypeReference<>() {});
        assertThat(dup.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @Order(2)
    void stage2_userCreatesEditsPost() {
        userToken = registerAndLogin("poster");
        PostRequest req = new PostRequest();
        req.setBoardId(boardId);
        req.setTitle("Spring Boot 3.5 新特性");
        req.setContent(" virtual threads 等内容... ");
        req.setTags(List.of("Spring-Boot", "java"));
        ResponseEntity<Result<PostDetailResponse>> create = rest.exchange(url("/api/posts"), HttpMethod.POST,
                new HttpEntity<>(req, bearer(userToken)), new ParameterizedTypeReference<>() {});
        assertThat(create.getStatusCode().value()).isEqualTo(201);
        PostDetailResponse post = create.getBody().getData();
        postId = post.getId();
        assertThat(post.getTitle()).isEqualTo("Spring Boot 3.5 新特性");
        assertThat(post.getTags()).containsExactly("spring-boot", "java");

        // 编辑自己的帖子
        req.setTitle("Spring Boot 3.5 新特性（修订）");
        ResponseEntity<Result<PostDetailResponse>> update = rest.exchange(url("/api/posts/" + postId),
                HttpMethod.PUT, new HttpEntity<>(req, bearer(userToken)),
                new ParameterizedTypeReference<>() {});
        assertThat(update.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(update.getBody().getData().getTitle()).endsWith("（修订）");

        // 越权编辑他人帖子 → 400（MVP 阶段 BusinessException）
        String other = registerAndLogin("evil_user");
        ResponseEntity<Result<PostDetailResponse>> forbidden = rest.exchange(url("/api/posts/" + postId),
                HttpMethod.PUT, new HttpEntity<>(req, bearer(other)), new ParameterizedTypeReference<>() {});
        assertThat(forbidden.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @Order(3)
    void stage3_guestBrowseSearchDetail() {
        // 游客列表
        ResponseEntity<Result<List<BoardResponse>>> boards = rest.exchange(url("/api/boards"),
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        assertThat(boards.getBody().getData()).extracting(BoardResponse::getName).contains("Java技术");

        // 游客帖子分页
        ResponseEntity<Result<PageResponse<PostSummaryResponse>>> page =
                rest.exchange(url("/api/posts?boardId=" + boardId + "&page=0&size=10"),
                        HttpMethod.GET, null,
                        new ParameterizedTypeReference<>() {});
        assertThat(page.getBody().getData().totalElements()).isEqualTo(1);

        // 关键词搜索（title 命中）——用 URI 变量传中文，由 RestTemplate 统一编码，避免手工 encode 后被二次编码
        ResponseEntity<Result<PageResponse<PostSummaryResponse>>> hit =
                rest.exchange(url("/api/posts?keyword={kw}"),
                        HttpMethod.GET, null,
                        new ParameterizedTypeReference<>() {},
                        java.util.Map.of("kw", "修订"));
        assertThat(hit.getBody().getData().totalElements()).isEqualTo(1);

        // 详情两次 → 浏览量 2
        rest.getForEntity(url("/api/posts/" + postId), Result.class);
        ResponseEntity<Result<PostDetailResponse>> detail = rest.exchange(url("/api/posts/" + postId),
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        assertThat(detail.getBody().getData().getViewCount()).isEqualTo(2);
    }

    @Test
    @Order(4)
    void stage4_myPostsAndSoftDelete() {
        // /mine
        HttpHeaders h = bearer(userToken);
        ResponseEntity<Result<PageResponse<PostSummaryResponse>>> mine =
                rest.exchange(url("/api/posts/mine"), HttpMethod.GET, new HttpEntity<>(h),
                        new ParameterizedTypeReference<>() {});
        assertThat(mine.getBody().getData().totalElements()).isEqualTo(1);

        // 未登录访问 /mine → 401
        ResponseEntity<String> anon = rest.getForEntity(url("/api/posts/mine"), String.class);
        assertThat(anon.getStatusCode().value()).isEqualTo(401);

        // 软删除后：列表不可见、详情 400
        ResponseEntity<Result<Void>> del = rest.exchange(url("/api/posts/" + postId), HttpMethod.DELETE,
                new HttpEntity<>(h), new ParameterizedTypeReference<>() {});
        assertThat(del.getStatusCode().is2xxSuccessful()).isTrue();

        ResponseEntity<Result<PageResponse<PostSummaryResponse>>> after =
                rest.exchange(url("/api/posts"), HttpMethod.GET, null,
                        new ParameterizedTypeReference<>() {});
        assertThat(after.getBody().getData().totalElements()).isEqualTo(0);
    }
}
