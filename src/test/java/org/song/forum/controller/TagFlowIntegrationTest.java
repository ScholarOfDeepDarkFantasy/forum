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
import org.song.forum.dto.tag.TagResponse;
import org.song.forum.util.PageResponse;
import org.song.forum.util.Result;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第三轮标签接口集成测试（H2 + 随机端口）：
 * 建板块 → 发帖带标签 → 标签总览（数量排序/过滤/分页边界）→ 标签帖子列表（分页/置顶优先/空标签）
 * → 缺参数与参数类型错误返回 400（质量评审回归）
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:tag-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TagFlowIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private static String adminToken;
    private static String userToken;
    private static Long boardId;
    /** "Java 第一篇" 的帖子 ID：置顶用例用它验证置顶帖恒在标签列表首位 */
    private static Long firstJavaPostId;

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

    private Long createPost(String token, String title, List<String> tags) {
        PostRequest req = new PostRequest();
        req.setBoardId(boardId);
        req.setTitle(title);
        req.setContent(title + " 正文");
        req.setTags(tags);
        ResponseEntity<Result<PostDetailResponse>> resp = rest.exchange(url("/api/posts"), HttpMethod.POST,
                new HttpEntity<>(req, bearer(token)), new ParameterizedTypeReference<>() {});
        return resp.getBody().getData().getId();
    }

    @Test
    @Order(1)
    void setupBoardAndPosts() {
        adminToken = loginAdmin();
        BoardRequest board = new BoardRequest();
        board.setName("标签测试板块");
        board.setDescription("用于标签接口测试");
        board.setSortOrder(1);
        ResponseEntity<Result<BoardResponse>> created = rest.exchange(url("/api/boards"), HttpMethod.POST,
                new HttpEntity<>(board, bearer(adminToken)), new ParameterizedTypeReference<>() {});
        boardId = created.getBody().getData().getId();

        userToken = registerAndLogin("tagger");
        firstJavaPostId = createPost(userToken, "Java 第一篇", List.of("java", "Spring"));
        createPost(userToken, "Java 第二篇", List.of("java"));
        createPost(userToken, "Spring Boot 笔记", List.of("spring-boot"));

        assertThat(boardId).isNotNull();
        assertThat(firstJavaPostId).isNotNull();
    }

    @Test
    @Order(2)
    void tagsOverviewSortsByPostCountDesc() {
        // 游客可访问（公开 GET）
        ResponseEntity<Result<PageResponse<TagResponse>>> resp = rest.exchange(url("/api/tags"), HttpMethod.GET,
                null, new ParameterizedTypeReference<>() {});
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        List<TagResponse> tags = resp.getBody().getData().content();
        // java(2) 在 spring(1)/spring-boot(1) 之前
        assertThat(tags.get(0).name()).isEqualTo("java");
        assertThat(tags.get(0).postCount()).isEqualTo(2);
        assertThat(tags).extracting(TagResponse::name).containsExactly("java", "spring", "spring-boot");
    }

    @Test
    @Order(3)
    void tagsOverviewFiltersByKeyword() {
        ResponseEntity<Result<PageResponse<TagResponse>>> resp = rest.exchange(url("/api/tags?keyword=SPR"),
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        assertThat(resp.getBody().getData().content())
                .extracting(TagResponse::name)
                .containsExactly("spring", "spring-boot");
    }

    @Test
    @Order(4)
    void tagPostsArePagedAndCarryTags() {
        ResponseEntity<Result<PageResponse<PostSummaryResponse>>> resp = rest.exchange(
                url("/api/tags/posts?name=java&page=0&size=10"), HttpMethod.GET,
                null, new ParameterizedTypeReference<>() {});
        PageResponse<PostSummaryResponse> page = resp.getBody().getData();
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content()).allSatisfy(p -> assertThat(p.getTags()).contains("java"));
    }

    @Test
    @Order(5)
    void unknownTagReturnsEmptyPageAndSizeIsClamped() {
        ResponseEntity<Result<PageResponse<PostSummaryResponse>>> unknown = rest.exchange(
                url("/api/tags/posts?name=no-such-tag"), HttpMethod.GET,
                null, new ParameterizedTypeReference<>() {});
        assertThat(unknown.getStatusCode().value()).isEqualTo(200);
        assertThat(unknown.getBody().getData().totalElements()).isZero();

        ResponseEntity<Result<PageResponse<PostSummaryResponse>>> clamped = rest.exchange(
                url("/api/tags/posts?name=java&size=999"), HttpMethod.GET,
                null, new ParameterizedTypeReference<>() {});
        assertThat(clamped.getBody().getData().size()).isEqualTo(50);
    }

    @Test
    @Order(6)
    void pinnedPostIsFirstInTagPostList() {
        ResponseEntity<Result<PostDetailResponse>> pinned = rest.exchange(
                url("/api/posts/" + firstJavaPostId + "/pin?pinned=true"), HttpMethod.PUT,
                new HttpEntity<>(bearer(adminToken)), new ParameterizedTypeReference<>() {});
        assertThat(pinned.getStatusCode().value()).isEqualTo(200);
        assertThat(pinned.getBody().getData().getIsPinned()).isTrue();

        ResponseEntity<Result<PageResponse<PostSummaryResponse>>> resp = rest.exchange(
                url("/api/tags/posts?name=java"), HttpMethod.GET,
                null, new ParameterizedTypeReference<>() {});
        List<PostSummaryResponse> posts = resp.getBody().getData().content();
        // 置顶的是较早发布的"第一篇"，排序仍须把它放到最新的"第二篇"之前
        assertThat(posts).hasSize(2);
        assertThat(posts.get(0).getId()).isEqualTo(firstJavaPostId);
        assertThat(posts.get(0).getIsPinned()).isTrue();
        assertThat(posts.get(1).getIsPinned()).isFalse();
    }

    @Test
    @Order(7)
    void overviewPaginationBoundariesAreClampedAndOverflowSafe() {
        // size 超过上限按 100 收窄
        ResponseEntity<Result<PageResponse<TagResponse>>> big = rest.exchange(url("/api/tags?size=999"),
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        assertThat(big.getStatusCode().value()).isEqualTo(200);
        assertThat(big.getBody().getData().size()).isEqualTo(100);

        // page 为负数归零
        ResponseEntity<Result<PageResponse<TagResponse>>> negative = rest.exchange(url("/api/tags?page=-1"),
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        assertThat(negative.getStatusCode().value()).isEqualTo(200);
        assertThat(negative.getBody().getData().page()).isZero();

        // 回归：page*size 在 int 下溢出为负数，subList 抛 IndexOutOfBoundsException → 500
        ResponseEntity<Result<PageResponse<TagResponse>>> huge = rest.exchange(
                url("/api/tags?page=2147483647&size=100"), HttpMethod.GET,
                null, new ParameterizedTypeReference<>() {});
        assertThat(huge.getStatusCode().value()).isEqualTo(200);
        assertThat(huge.getBody().getData().content()).isEmpty();
        assertThat(huge.getBody().getData().totalElements()).isEqualTo(3);
    }

    @Test
    @Order(8)
    void missingOrMalformedParamsReturn400() {
        // 回归：缺 name 曾落到 Exception 兜底返回 500
        ResponseEntity<Result<Void>> missing = rest.exchange(url("/api/tags/posts"), HttpMethod.GET,
                null, new ParameterizedTypeReference<>() {});
        assertThat(missing.getStatusCode().value()).isEqualTo(400);
        assertThat(missing.getBody().isSuccess()).isFalse();
        assertThat(missing.getBody().getMessage()).contains("name");

        // 回归：size 类型不匹配同样应返回 400
        ResponseEntity<Result<Void>> malformed = rest.exchange(
                url("/api/tags/posts?name=java&size=abc"), HttpMethod.GET,
                null, new ParameterizedTypeReference<>() {});
        assertThat(malformed.getStatusCode().value()).isEqualTo(400);
        assertThat(malformed.getBody().isSuccess()).isFalse();
        assertThat(malformed.getBody().getMessage()).contains("size");
    }
}
