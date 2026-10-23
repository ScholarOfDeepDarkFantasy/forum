package org.song.forum.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.song.forum.dto.auth.LoginRequest;
import org.song.forum.dto.auth.RegisterRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录限流集成测试：走完整过滤器链与真实 HTTP。
 * 每个用例使用不同的 X-Real-IP，互不污染（限流状态是单例里的 Map）。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:login-rate-limit-test;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.security.login.max-failures=3",
        "app.security.login.window-ms=60000",
        "app.security.login.lock-ms=60000"
})
class LoginRateLimitIntegrationTest {

    private static final String USER = "rate_limit_user";
    private static final String PASSWORD = "password123";
    private static final String WRONG = "wrong-password";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void failuresBelowThresholdDoNotLock() {
        ensureUser();
        String ip = "198.51.100.10";

        assertThat(login(ip, USER, WRONG).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(ip, USER, WRONG).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(ip, USER, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void thirdFailureLocksIpAndBlocksCorrectPassword() {
        ensureUser();
        String ip = "198.51.100.11";

        for (int i = 0; i < 3; i++) {
            assertThat(login(ip, USER, WRONG).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<String> locked = login(ip, USER, PASSWORD);

        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(locked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isIn("59", "60");
        assertThat(locked.getBody()).contains("登录失败次数过多，请 1 分钟后再试");
    }

    @Test
    void otherIpIsNotAffected() {
        ensureUser();
        String blockedIp = "198.51.100.12";
        for (int i = 0; i < 3; i++) {
            login(blockedIp, USER, WRONG);
        }

        assertThat(login("198.51.100.13", USER, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void successResetsFailureCounter() {
        ensureUser();
        String ip = "198.51.100.14";

        login(ip, USER, WRONG);
        login(ip, USER, WRONG);
        assertThat(login(ip, USER, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        login(ip, USER, WRONG);
        login(ip, USER, WRONG);
        assertThat(login(ip, USER, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---------- 辅助 ----------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private void ensureUser() {
        RegisterRequest req = new RegisterRequest();
        req.setUsername(USER);
        req.setPassword(PASSWORD);
        req.setEmail("rate_limit@test.com");
        rest.postForEntity(url("/api/auth/register"), req, String.class);
    }

    private ResponseEntity<String> login(String ip, String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Real-IP", ip);
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword(password);
        return rest.exchange(url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>(req, headers), String.class);
    }
}
