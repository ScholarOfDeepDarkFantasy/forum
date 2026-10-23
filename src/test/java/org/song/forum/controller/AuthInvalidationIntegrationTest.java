package org.song.forum.controller;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.song.forum.dto.auth.AuthResponse;
import org.song.forum.dto.auth.LoginRequest;
import org.song.forum.dto.auth.RegisterRequest;
import org.song.forum.dto.user.UserStatusRequest;
import org.song.forum.util.Result;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第七轮安全加固集成测试：登出 / 封禁 / 版本号变化 / 旧 token 兼容，四条路径共用同一个服务端状态源。
 * 6 个用例互相独立（各自注册新用户），不依赖执行顺序。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:auth-invalidation-test;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AuthInvalidationIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${jwt.secret}")
    private String jwtSecret;

    // ---------- 用例 ----------

    @Test
    void validTokenStillWorks() {
        AuthResponse auth = register("inval_valid");

        ResponseEntity<String> resp = me(auth.getToken());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("inval_valid");
    }

    @Test
    void logoutInvalidatesTokenImmediately() {
        AuthResponse auth = register("inval_logout");

        ResponseEntity<String> logoutResp = rest.exchange(url("/api/auth/logout"), HttpMethod.POST,
                new HttpEntity<>(bearer(auth.getToken())), String.class);

        assertThat(logoutResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(logoutResp.getBody()).contains("已退出登录");

        ResponseEntity<String> after = me(auth.getToken());
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(after.getBody()).contains("未登录或登录已过期");
    }

    @Test
    void bannedUserTokenRejectedImmediately() {
        AuthResponse victim = register("inval_banned");
        AuthResponse admin = login("admin", "admin123456");

        changeStatus(admin.getToken(), victim.getId(), "BANNED", null);

        ResponseEntity<String> resp = me(victim.getToken());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("未登录或登录已过期");
    }

    @Test
    void mutedUserTokenStillWorks() {
        AuthResponse user = register("inval_muted");
        AuthResponse admin = login("admin", "admin123456");

        changeStatus(admin.getToken(), user.getId(), "MUTED", 30);

        assertThat(me(user.getToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void bumpedTokenVersionRejectsOldToken() {
        AuthResponse auth = register("inval_bumped");

        // 直接改库模拟「另一台实例/另一个入口把版本 +1」，验证过滤器比对的是数据库而不是内存
        jdbcTemplate.update("update users set token_version = token_version + 1 where id = ?", auth.getId());

        assertThat(me(auth.getToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void legacyTokenWithoutVersionWorksUntilUserLogsOut() {
        AuthResponse auth = register("inval_legacy");
        String legacy = legacyTokenFor(auth.getId(), "inval_legacy");

        // 没有 ver 的旧 token 按 0 处理：版本为 0 的用户上线后不会被强制重登
        assertThat(me(legacy).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> logoutResp = rest.exchange(url("/api/auth/logout"), HttpMethod.POST,
                new HttpEntity<>(bearer(auth.getToken())), String.class);
        assertThat(logoutResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(me(legacy).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---------- 辅助方法 ----------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private RegisterRequest registerReq(String username) {
        RegisterRequest req = new RegisterRequest();
        req.setUsername(username);
        req.setPassword("password123");
        req.setEmail(username + "@test.com");
        return req;
    }

    private AuthResponse register(String username) {
        ResponseEntity<Result<AuthResponse>> resp = rest.exchange(url("/api/auth/register"),
                HttpMethod.POST, new HttpEntity<>(registerReq(username)),
                new ParameterizedTypeReference<>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).isNotNull();
        return resp.getBody().getData();
    }

    private AuthResponse login(String username, String password) {
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword(password);
        ResponseEntity<Result<AuthResponse>> resp = rest.exchange(url("/api/auth/login"),
                HttpMethod.POST, new HttpEntity<>(req),
                new ParameterizedTypeReference<>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        return resp.getBody().getData();
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private ResponseEntity<String> me(String token) {
        return rest.exchange(url("/api/auth/me"), HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class);
    }

    private void changeStatus(String adminToken, Long userId, String status, Integer muteMinutes) {
        UserStatusRequest req = new UserStatusRequest();
        req.setStatus(status);
        req.setMuteMinutes(muteMinutes);
        HttpHeaders headers = bearer(adminToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = rest.exchange(url("/api/admin/users/" + userId + "/status"),
                HttpMethod.PUT, new HttpEntity<>(req, headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** 手工签一个第七轮之前格式的 token（无 ver claim） */
    private String legacyTokenFor(Long userId, String username) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("roles", List.of("USER"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key)
                .compact();
    }
}
