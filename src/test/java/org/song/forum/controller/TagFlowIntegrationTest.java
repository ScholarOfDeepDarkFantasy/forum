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
 * 建板块 → 发帖带标签 → 标签总览（数量排序/过滤）→ 标签帖子列表（分页/置顶优先/空标签）
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

    private void createPost(String token, String title, List<String> tags) {
        PostRequest req = new PostRequest();
        req.setBoardId(boardId);
        req.setTitle(title);
        req.setContent(title + " 正文");
        req.setTags(tags);
        rest.exchange(url("/api/posts"), HttpMethod.POST, new HttpEntity<>(req, bearer(token)),
                new ParameterizedTypeReference<Result<PostDetailResponse>>() {});
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
        createPost(userToken, "Java 第一篇", List.of("java", "Spring"));
        createPost(userToken, "Java 第二篇", List.of("java"));
        createPost(userToken, "Spring Boot 笔记", List.of("spring-boot"));

        assertThat(boardId).isNotNull();
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
}
