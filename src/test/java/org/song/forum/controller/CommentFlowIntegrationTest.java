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
import org.song.forum.dto.auth.AuthResponse;
import org.song.forum.dto.auth.LoginRequest;
import org.song.forum.dto.auth.RegisterRequest;
import org.song.forum.dto.board.BoardRequest;
import org.song.forum.dto.board.BoardResponse;
import org.song.forum.dto.comment.CommentRequest;
import org.song.forum.dto.comment.CommentResponse;
import org.song.forum.dto.post.PostDetailResponse;
import org.song.forum.dto.post.PostRequest;
import org.song.forum.util.PageResponse;
import org.song.forum.util.Result;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 4 阶段集成测试（H2 + 随机端口）：
 * 发评论 → 楼中楼回复 → 计数联动 → REPLY 通知 → 软删除占位 → 越权删除被拒
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:comment-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CommentFlowIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private static String opToken;   // 楼主
    private static String apToken;   // 回复者
    private static Long postId;
    private static Long rootCommentId;
    private static Long replyCommentId;

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
    void stage1_preparePost() {
        opToken = registerAndLogin("op_user");
        String adminToken = loginAdmin();
        BoardRequest board = new BoardRequest();
        board.setName("评论测试板块");
        board.setSortOrder(1);
        Long boardId = rest.exchange(url("/api/boards"), HttpMethod.POST,
                new HttpEntity<>(board, bearer(adminToken)),
                new ParameterizedTypeReference<Result<BoardResponse>>() {}).getBody().getData().getId();

        PostRequest post = new PostRequest();
        post.setBoardId(boardId);
        post.setTitle("评论功能测试帖");
        post.setContent(" welcome to comment ");
        postId = rest.exchange(url("/api/posts"), HttpMethod.POST,
                new HttpEntity<>(post, bearer(opToken)),
                new ParameterizedTypeReference<Result<PostDetailResponse>>() {}).getBody().getData().getId();
        assertThat(postId).isNotNull();
    }

    @Test
    @Order(2)
    void stage2_createCommentsAndReplies() {
        apToken = registerAndLogin("ap_user");

        // 楼主被回复者 ap_user 发顶级评论
        CommentRequest root = new CommentRequest();
        root.setContent("这是第一条评论");
        ResponseEntity<Result<CommentResponse>> rootResp = rest.exchange(
                url("/api/posts/" + postId + "/comments"), HttpMethod.POST,
                new HttpEntity<>(root, bearer(apToken)),
                new ParameterizedTypeReference<Result<CommentResponse>>() {});
        assertThat(rootResp.getStatusCode().value()).isEqualTo(201);
        rootCommentId = rootResp.getBody().getData().getId();

        // 楼主回复 ap_user 的评论（楼中楼）
        CommentRequest reply = new CommentRequest();
        reply.setContent("楼主回复你啦");
        reply.setParentId(rootCommentId);
        ResponseEntity<Result<CommentResponse>> replyResp = rest.exchange(
                url("/api/posts/" + postId + "/comments"), HttpMethod.POST,
                new HttpEntity<>(reply, bearer(opToken)),
                new ParameterizedTypeReference<Result<CommentResponse>>() {});
        assertThat(replyResp.getStatusCode().value()).isEqualTo(201);
        assertThat(replyResp.getBody().getData().getParentId()).isEqualTo(rootCommentId);
        replyCommentId = replyResp.getBody().getData().getId();

        // 楼中楼再回复 → 应扁平化挂到顶级评论下
        CommentRequest nested = new CommentRequest();
        nested.setContent("再回复一层");
        nested.setParentId(replyCommentId);
        ResponseEntity<Result<CommentResponse>> nestedResp = rest.exchange(
                url("/api/posts/" + postId + "/comments"), HttpMethod.POST,
                new HttpEntity<>(nested, bearer(apToken)),
                new ParameterizedTypeReference<Result<CommentResponse>>() {});
        assertThat(nestedResp.getBody().getData().getParentId()).isEqualTo(rootCommentId);

        // 评论计数联动：posts.comment_count = 3
        ResponseEntity<Result<PostDetailResponse>> detail = rest.exchange(
                url("/api/posts/" + postId), HttpMethod.GET, null,
                new ParameterizedTypeReference<Result<PostDetailResponse>>() {});
        assertThat(detail.getBody().getData().getCommentCount()).isEqualTo(3);
    }

    @Test
    @Order(3)
    void stage3_treeListAndNotification() {
        // 游客读评论树：1 条根评论 + 2 条回复
        ResponseEntity<Result<PageResponse<CommentResponse>>> tree = rest.exchange(
                url("/api/posts/" + postId + "/comments"), HttpMethod.GET, null,
                new ParameterizedTypeReference<Result<PageResponse<CommentResponse>>>() {});
        PageResponse<CommentResponse> page = tree.getBody().getData();
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content().get(0).getReplies()).hasSize(2);

        // ap_user 应收到 1 条 REPLY 通知（楼主回复了他的评论）
        ResponseEntity<Result<PageResponse<Map<String, Object>>>> notifs = rest.exchange(
                url("/api/notifications"), HttpMethod.GET,
                new HttpEntity<>(bearer(apToken)),
                new ParameterizedTypeReference<Result<PageResponse<Map<String, Object>>>>() {});
        assertThat(notifs.getBody().getData().totalElements()).isEqualTo(1);
        assertThat(notifs.getBody().getData().content().get(0).get("type")).isEqualTo("REPLY");

        // 新增：REPLY 通知应携带被回复评论的 id，供前端锚点跳转
        Map<String, Object> replyNotif = notifs.getBody().getData().content().stream()
                .filter(n -> "REPLY".equals(n.get("type")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 REPLY 通知"));
        assertThat(replyNotif.get("commentId")).as("REPLY 通知应携带被回复评论的 id").isNotNull();
        assertThat(((Number) replyNotif.get("commentId")).longValue()).isEqualTo(rootCommentId);

        // 楼主不应收到通知（他只回复别人，顶级评论是 ap_user 发的，楼主自己没被回复）
        ResponseEntity<Result<PageResponse<Map<String, Object>>>> opNotifs = rest.exchange(
                url("/api/notifications"), HttpMethod.GET,
                new HttpEntity<>(bearer(opToken)),
                new ParameterizedTypeReference<Result<PageResponse<Map<String, Object>>>>() {});
        assertThat(opNotifs.getBody().getData().totalElements()).isEqualTo(0);
    }

    @Test
    @Order(4)
    void stage4_softDeleteAndPermission() {
        // ap_user 删除自己的顶级评论 → 占位，子回复保留
        ResponseEntity<Result<Void>> del = rest.exchange(
                url("/api/posts/" + postId + "/comments/" + rootCommentId), HttpMethod.DELETE,
                new HttpEntity<>(bearer(apToken)),
                new ParameterizedTypeReference<Result<Void>>() {});
        assertThat(del.getStatusCode().is2xxSuccessful()).isTrue();

        // 树里根评论变占位（不再出现在树中——顶级查询过滤 deleted），但子回复应仍可见
        // MVP 策略：顶级软删除后从树中消失，楼中楼随之不可见但未删除（数据保留）
        ResponseEntity<Result<PageResponse<CommentResponse>>> tree = rest.exchange(
                url("/api/posts/" + postId + "/comments"), HttpMethod.GET, null,
                new ParameterizedTypeReference<Result<PageResponse<CommentResponse>>>() {});
        assertThat(tree.getBody().getData().totalElements()).isEqualTo(0);

        // 计数联动：comment_count 3 → 2
        ResponseEntity<Result<PostDetailResponse>> detail = rest.exchange(
                url("/api/posts/" + postId), HttpMethod.GET, null,
                new ParameterizedTypeReference<Result<PostDetailResponse>>() {});
        assertThat(detail.getBody().getData().getCommentCount()).isEqualTo(2);

        // 越权删除：ap_user 发一条新评论，op_user 尝试删除 → 400
        CommentRequest mine = new CommentRequest();
        mine.setContent("ap_user 的新评论");
        ResponseEntity<Result<CommentResponse>> mineResp = rest.exchange(
                url("/api/posts/" + postId + "/comments"), HttpMethod.POST,
                new HttpEntity<>(mine, bearer(apToken)),
                new ParameterizedTypeReference<Result<CommentResponse>>() {});
        Long apCommentId = mineResp.getBody().getData().getId();

        ResponseEntity<Result<Void>> forbidden = rest.exchange(
                url("/api/posts/" + postId + "/comments/" + apCommentId), HttpMethod.DELETE,
                new HttpEntity<>(bearer(opToken)),
                new ParameterizedTypeReference<Result<Void>>() {});
        assertThat(forbidden.getStatusCode().value()).isEqualTo(400);

        // 未登录发评论 → 401
        ResponseEntity<String> anon = rest.postForEntity(
                url("/api/posts/" + postId + "/comments"), new HttpEntity<>(Map.of("content", "匿名")), String.class);
        assertThat(anon.getStatusCode().value()).isEqualTo(401);
    }
}
