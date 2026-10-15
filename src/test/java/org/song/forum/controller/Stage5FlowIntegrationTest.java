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
import org.song.forum.dto.notification.NotificationResponse;
import org.song.forum.dto.post.LikeToggleResponse;
import org.song.forum.dto.post.PostDetailResponse;
import org.song.forum.dto.post.PostRequest;
import org.song.forum.dto.post.PostSummaryResponse;
import org.song.forum.dto.user.AdminUserResponse;
import org.song.forum.dto.user.UserStatusRequest;
import org.song.forum.util.PageResponse;
import org.song.forum.util.Result;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 5 阶段集成测试：管理员权限（板块 CRUD/置顶/加精/删帖/删评论/用户管理）
 * + 点赞收藏切换 + LIKE/FAVORITE 通知 + 禁言封禁拦截。
 * <p>管理员账号使用 DataInitializer 启动时种下的 admin / admin123456。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:stage5-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Stage5FlowIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private static String adminToken;
    private static String userToken;
    private static String mutedToken;
    private static Long boardId;
    private static Long postId;
    private static Long commentId;
    private static Long targetUserId;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
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

    @Test
    @Order(1)
    void stage1_adminLoginAndBoardCrud() {
        // DataInitializer 种的 admin 账号登录
        LoginRequest l = new LoginRequest();
        l.setUsername("admin");
        l.setPassword("admin123456");
        ResponseEntity<Result<AuthResponse>> login = rest.exchange(url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>(l), new ParameterizedTypeReference<>() {});
        assertThat(login.getStatusCode().is2xxSuccessful()).isTrue();
        adminToken = login.getBody().getData().getToken();
        assertThat(login.getBody().getData().getRoles()).contains("ADMIN");

        // 管理员建板块 201
        BoardRequest req = new BoardRequest();
        req.setName("管理员板块");
        req.setSortOrder(1);
        ResponseEntity<Result<BoardResponse>> create = rest.exchange(url("/api/boards"), HttpMethod.POST,
                new HttpEntity<>(req, bearer(adminToken)), new ParameterizedTypeReference<>() {});
        assertThat(create.getStatusCode().value()).isEqualTo(201);
        boardId = create.getBody().getData().getId();

        // 普通用户建板块 → 403（@PreAuthorize 生效）
        userToken = registerAndLogin("plain_user");
        ResponseEntity<Result<BoardResponse>> forbidden = rest.exchange(url("/api/boards"), HttpMethod.POST,
                new HttpEntity<>(req, bearer(userToken)), new ParameterizedTypeReference<>() {});
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @Order(2)
    void stage2_likeFavoriteToggleAndNotify() {
        PostRequest pr = new PostRequest();
        pr.setBoardId(boardId);
        pr.setTitle("点赞收藏测试帖");
        pr.setContent("正文内容");
        ResponseEntity<Result<PostDetailResponse>> create = rest.exchange(url("/api/posts"), HttpMethod.POST,
                new HttpEntity<>(pr, bearer(userToken)), new ParameterizedTypeReference<>() {});
        postId = create.getBody().getData().getId();

        mutedToken = registerAndLogin("liker");

        // 点赞 → active=true count=1；再点 → active=false count=0
        ResponseEntity<Result<LikeToggleResponse>> like1 = rest.exchange(url("/api/posts/" + postId + "/like"),
                HttpMethod.POST, new HttpEntity<>(bearer(mutedToken)), new ParameterizedTypeReference<>() {});
        assertThat(like1.getBody().getData().isActive()).isTrue();
        assertThat(like1.getBody().getData().getCount()).isEqualTo(1);

        ResponseEntity<Result<LikeToggleResponse>> like2 = rest.exchange(url("/api/posts/" + postId + "/like"),
                HttpMethod.POST, new HttpEntity<>(bearer(mutedToken)), new ParameterizedTypeReference<>() {});
        assertThat(like2.getBody().getData().isActive()).isFalse();
        assertThat(like2.getBody().getData().getCount()).isEqualTo(0);

        // 收藏 → active=true；帖子详情 favoriteCount=1
        ResponseEntity<Result<LikeToggleResponse>> fav = rest.exchange(url("/api/posts/" + postId + "/favorite"),
                HttpMethod.POST, new HttpEntity<>(bearer(mutedToken)), new ParameterizedTypeReference<>() {});
        assertThat(fav.getBody().getData().isActive()).isTrue();

        // 帖子作者收到 LIKE 通知（点赞已取消但通知已产生）
        ResponseEntity<Result<PageResponse<NotificationResponse>>> notifications =
                rest.exchange(url("/api/notifications"), HttpMethod.GET, new HttpEntity<>(bearer(userToken)),
                        new ParameterizedTypeReference<Result<PageResponse<NotificationResponse>>>() {});
        assertThat(notifications.getBody().getData().content())
                .extracting(NotificationResponse::getType)
                .contains("LIKE");

        // 我的收藏列表含该帖
        ResponseEntity<Result<PageResponse<PostSummaryResponse>>> favorites =
                rest.exchange(url("/api/posts/favorites/mine"), HttpMethod.GET,
                        new HttpEntity<>(bearer(mutedToken)),
                        new ParameterizedTypeReference<Result<PageResponse<PostSummaryResponse>>>() {});
        assertThat(favorites.getBody().getData().content())
                .extracting(PostSummaryResponse::getId)
                .containsExactly(postId);
    }

    @Test
    @Order(3)
    void stage3_pinExquisiteAndModDelete() {
        // 普通用户置顶 → 403
        ResponseEntity<Result<PostDetailResponse>> forbidden = rest.exchange(
                url("/api/posts/" + postId + "/pin?pinned=true"), HttpMethod.PUT,
                new HttpEntity<>(bearer(userToken)), new ParameterizedTypeReference<>() {});
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);

        // 管理员置顶 + 加精
        ResponseEntity<Result<PostDetailResponse>> pin = rest.exchange(
                url("/api/posts/" + postId + "/pin?pinned=true"), HttpMethod.PUT,
                new HttpEntity<>(bearer(adminToken)), new ParameterizedTypeReference<>() {});
        assertThat(pin.getBody().getData().getIsPinned()).isTrue();

        ResponseEntity<Result<PostDetailResponse>> ex = rest.exchange(
                url("/api/posts/" + postId + "/exquisite?exquisite=true"), HttpMethod.PUT,
                new HttpEntity<>(bearer(adminToken)), new ParameterizedTypeReference<>() {});
        assertThat(ex.getBody().getData().getIsExquisite()).isTrue();

        // 管理员删别人的评论：先让 plain_user 评论，admin 删除 → 200
        CommentRequest cr = new CommentRequest();
        cr.setContent("待管理员删除的评论");
        ResponseEntity<Result<CommentResponse>> comment = rest.exchange(
                url("/api/posts/" + postId + "/comments"), HttpMethod.POST,
                new HttpEntity<>(cr, bearer(userToken)), new ParameterizedTypeReference<>() {});
        commentId = comment.getBody().getData().getId();

        ResponseEntity<Result<Void>> delComment = rest.exchange(
                url("/api/posts/" + postId + "/comments/" + commentId), HttpMethod.DELETE,
                new HttpEntity<>(bearer(adminToken)), new ParameterizedTypeReference<>() {});
        assertThat(delComment.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @Order(4)
    void stage4_muteBanAndUserAdmin() {
        // 查目标用户 id（liker）
        ResponseEntity<Result<PageResponse<AdminUserResponse>>> users = rest.exchange(
                url("/api/admin/users"), HttpMethod.GET, new HttpEntity<>(bearer(adminToken)),
                new ParameterizedTypeReference<Result<PageResponse<AdminUserResponse>>>() {});
        assertThat(users.getStatusCode().is2xxSuccessful()).isTrue();
        targetUserId = users.getBody().getData().content().stream()
                .filter(u -> u.getUsername().equals("liker"))
                .findFirst().orElseThrow().getId();

        // 普通用户访问管理接口 → 403
        ResponseEntity<Result<PageResponse<AdminUserResponse>>> forbidden = rest.exchange(
                url("/api/admin/users"), HttpMethod.GET, new HttpEntity<>(bearer(userToken)),
                new ParameterizedTypeReference<Result<PageResponse<AdminUserResponse>>>() {});
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);

        // 禁言 liker 60 分钟
        UserStatusRequest mute = new UserStatusRequest();
        mute.setStatus("MUTED");
        mute.setMuteMinutes(60);
        ResponseEntity<Result<AdminUserResponse>> muted = rest.exchange(
                url("/api/admin/users/" + targetUserId + "/status"), HttpMethod.PUT,
                new HttpEntity<>(mute, bearer(adminToken)), new ParameterizedTypeReference<>() {});
        assertThat(muted.getBody().getData().getStatus()).isEqualTo("MUTED");

        // 禁言用户发帖 → 400（禁言拦截）
        PostRequest pr = new PostRequest();
        pr.setBoardId(boardId);
        pr.setTitle("禁言用户想发帖");
        pr.setContent("should fail");
        ResponseEntity<Result<PostDetailResponse>> mutedPost = rest.exchange(url("/api/posts"), HttpMethod.POST,
                new HttpEntity<>(pr, bearer(mutedToken)), new ParameterizedTypeReference<>() {});
        assertThat(mutedPost.getStatusCode().value()).isEqualTo(400);

        // 封禁 liker → 登录被拒 401（BadCredentials→401）或 400，验证账号不可用
        UserStatusRequest ban = new UserStatusRequest();
        ban.setStatus("BANNED");
        rest.exchange(url("/api/admin/users/" + targetUserId + "/status"), HttpMethod.PUT,
                new HttpEntity<>(ban, bearer(adminToken)), new ParameterizedTypeReference<>() {});
        LoginRequest banLogin = new LoginRequest();
        banLogin.setUsername("liker");
        banLogin.setPassword("password123");
        ResponseEntity<Result<AuthResponse>> banned = rest.exchange(url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>(banLogin), new ParameterizedTypeReference<>() {});
        assertThat(banned.getBody().isSuccess()).isFalse();

        // 解封后可登录
        UserStatusRequest active = new UserStatusRequest();
        active.setStatus("ACTIVE");
        rest.exchange(url("/api/admin/users/" + targetUserId + "/status"), HttpMethod.PUT,
                new HttpEntity<>(active, bearer(adminToken)), new ParameterizedTypeReference<>() {});
        ResponseEntity<Result<AuthResponse>> reLogin = rest.exchange(url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>(banLogin), new ParameterizedTypeReference<>() {});
        assertThat(reLogin.getBody().isSuccess()).isTrue();

        // 不能对 admin 操作
        Long adminId = users.getBody().getData().content().stream()
                .filter(u -> u.getUsername().equals("admin")).findFirst().orElseThrow().getId();
        UserStatusRequest banAdmin = new UserStatusRequest();
        banAdmin.setStatus("BANNED");
        ResponseEntity<Result<AdminUserResponse>> protect = rest.exchange(
                url("/api/admin/users/" + adminId + "/status"), HttpMethod.PUT,
                new HttpEntity<>(banAdmin, bearer(adminToken)), new ParameterizedTypeReference<>() {});
        assertThat(protect.getStatusCode().value()).isEqualTo(400);
    }
}
