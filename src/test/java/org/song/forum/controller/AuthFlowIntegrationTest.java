package org.song.forum.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.song.forum.dto.auth.AuthResponse;
import org.song.forum.dto.auth.LoginRequest;
import org.song.forum.dto.auth.RegisterRequest;
import org.song.forum.util.Result;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 认证全流程集成测试（H2 + 随机端口）：
 * 注册 → 登录 → 携带 Token 访问 /me → 未携带 Token 被 401 → 重复用户名被拒
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:auth-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AuthFlowIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private RegisterRequest registerReq(String name) {
        RegisterRequest r = new RegisterRequest();
        r.setUsername(name);
        r.setPassword("password123");
        r.setEmail(name + "@test.com");
        return r;
    }

    @Test
    void registerLoginMeFullFlow() {
        // 1. 注册
        ResponseEntity<Result> regResp = rest.postForEntity(url("/api/auth/register"),
                registerReq("charlie"), Result.class);
        assertThat(regResp.getStatusCode().value()).isEqualTo(201);
        assertThat(regResp.getBody().isSuccess()).isTrue();

        // 2. 重复用户名 → 400
        ResponseEntity<Result> dupResp = rest.postForEntity(url("/api/auth/register"),
                registerReq("charlie"), Result.class);
        assertThat(dupResp.getStatusCode().value()).isEqualTo(400);

        // 3. 登录拿 Token（响应为 Result<AuthResponse> 包装结构，token 在 data 字段）
        LoginRequest loginReq = new LoginRequest();
        loginReq.setUsername("charlie");
        loginReq.setPassword("password123");
        ResponseEntity<Result<AuthResponse>> loginResp = rest.exchange(url("/api/auth/login"),
                HttpMethod.POST, new HttpEntity<>(loginReq),
                new org.springframework.core.ParameterizedTypeReference<>() {});
        assertThat(loginResp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(loginResp.getBody().isSuccess()).isTrue();
        AuthResponse auth = loginResp.getBody().getData();
        assertThat(auth.getToken()).isNotBlank();
        assertThat(auth.getRoles()).containsExactly("USER");

        // 4. 携带 Token 访问 /me（同样为 Result 包装）
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(auth.getToken());
        ResponseEntity<Result<AuthResponse>> meResp = rest.exchange(url("/api/auth/me"),
                HttpMethod.GET, new HttpEntity<>(headers),
                new org.springframework.core.ParameterizedTypeReference<>() {});
        assertThat(meResp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(meResp.getBody().getData().getUsername()).isEqualTo("charlie");

        // 5. 未携带 Token → 401
        ResponseEntity<String> anonResp = rest.getForEntity(url("/api/auth/me"), String.class);
        assertThat(anonResp.getStatusCode().value()).isEqualTo(401);

        // 6. 错误密码 → 401，且消息必须是「用户名或密码错误」而不是「未登录或登录已过期」：
        //    BadCredentialsException 是 AuthenticationException 的子类，处理器若只认父类，
        //    登录失败会被误报成会话过期，前端就只能弹出与操作无关的提示。
        LoginRequest bad = new LoginRequest();
        bad.setUsername("charlie");
        bad.setPassword("wrong-password");
        ResponseEntity<Result> badResp = rest.postForEntity(url("/api/auth/login"), bad, Result.class);
        assertThat(badResp.getStatusCode().value()).isEqualTo(401);
        assertThat(badResp.getBody().isSuccess()).isFalse();
        assertThat(badResp.getBody().getMessage()).isEqualTo("用户名或密码错误");

        // 7. 不存在的用户名 → 同样 401，且不得泄露「用户不存在」（避免用户名枚举）
        LoginRequest ghost = new LoginRequest();
        ghost.setUsername("no_such_user_here");
        ghost.setPassword("whatever");
        ResponseEntity<Result> ghostResp = rest.postForEntity(url("/api/auth/login"), ghost, Result.class);
        assertThat(ghostResp.getStatusCode().value()).isEqualTo(401);
        assertThat(ghostResp.getBody().getMessage()).isEqualTo("用户名或密码错误");
    }
}
