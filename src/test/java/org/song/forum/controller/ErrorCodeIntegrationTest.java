package org.song.forum.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.song.forum.exception.ErrorCode;
import org.song.forum.exception.ErrorMessages;
import org.song.forum.util.Result;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第十五轮错误码契约：失败响应带机器可读 code；框架文案按 Accept-Language 中/英切换，
 * 且「只有显式 en 才英文，其余一律中文」。另含 Result 序列化与 ErrorMessages 归一用例。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:error-code-test;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ErrorCodeIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ErrorMessages errorMessages;

    @Test
    void malformedJsonReturnsBadJsonCode() {
        ResponseEntity<String> response = postBadJsonWithLocale(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .contains("\"success\":false")
                .contains("\"code\":\"BAD_JSON\"")
                .contains("\"message\":\"请求体格式错误\"")
                .contains("\"timestamp\":");
    }

    @Test
    void plainTextContentTypeReturnsUnsupportedMediaTypeCode() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        ResponseEntity<String> response = rest.exchange(url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"a\",\"password\":\"b\"}", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody())
                .contains("\"code\":\"UNSUPPORTED_MEDIA_TYPE\"")
                .contains("\"message\":\"不支持的 Content-Type\"");
    }

    @Test
    void nonPostToCspReportReturnsMethodNotAllowedCode() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(loginAsFreshUser());
        ResponseEntity<String> response = rest.exchange(url("/api/csp-report"), HttpMethod.PUT,
                new HttpEntity<>("{}", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().get(HttpHeaders.ALLOW)).contains("POST");
        assertThat(response.getBody())
                .contains("\"code\":\"METHOD_NOT_ALLOWED\"")
                .contains("\"message\":\"请求方法不被支持\"")
                .contains("\"timestamp\":");
    }

    @Test
    void anonymousRequestReturnsUnauthorizedCodeFromSecurity() {
        ResponseEntity<String> response = rest.getForEntity(url("/api/auth/me"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody())
                .contains("\"success\":false")
                .contains("\"code\":\"UNAUTHORIZED\"")
                .contains("\"message\":\"未登录或登录已过期\"")
                .contains("\"timestamp\":");
    }

    @Test
    void englishAcceptLanguageReturnsEnglishMessage() {
        ResponseEntity<String> response = postBadJsonWithLocale("en");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .contains("\"code\":\"BAD_JSON\"")
                .contains("\"message\":\"Malformed request body\"");
    }

    @Test
    void unknownLocaleFallsBackToChinese() {
        ResponseEntity<String> response = postBadJsonWithLocale("fr");

        assertThat(response.getBody()).contains("\"message\":\"请求体格式错误\"");
    }

    @Test
    void failureResultSerializesCode() throws Exception {
        String json = objectMapper.writeValueAsString(Result.fail(ErrorCode.BAD_JSON, "x"));

        assertThat(json).contains("\"success\":false").contains("\"code\":\"BAD_JSON\"");
    }

    @Test
    void successResultOmitsCode() throws Exception {
        String json = objectMapper.writeValueAsString(Result.ok("hello"));

        assertThat(json).contains("\"success\":true").doesNotContain("\"code\"");
    }

    @Test
    void errorMessagesResolvesEnglish() {
        assertThat(errorMessages.getForLocale(ErrorCode.INTERNAL_ERROR, Locale.ENGLISH))
                .isEqualTo("Internal server error. Please try again later");
    }

    @Test
    void errorMessagesNormalizesUnknownLocaleToChinese() {
        assertThat(errorMessages.getForLocale(ErrorCode.INTERNAL_ERROR, Locale.FRENCH))
                .isEqualTo("服务器内部错误，请稍后重试");
    }

    private ResponseEntity<String> postBadJsonWithLocale(String language) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (language != null) {
            headers.set(HttpHeaders.ACCEPT_LANGUAGE, language);
        }
        return rest.exchange(url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>("{\"username\":", headers), String.class);
    }

    private String loginAsFreshUser() throws Exception {
        String username = "r15_" + System.nanoTime();
        RegisterRequest register = new RegisterRequest();
        register.setUsername(username);
        register.setPassword("password123");
        register.setEmail(username + "@test.com");
        ResponseEntity<String> registered = rest.postForEntity(
                url("/api/auth/register"), register, String.class);
        assertThat(registered.getStatusCode().value()).isEqualTo(201);

        LoginRequest login = new LoginRequest();
        login.setUsername(username);
        login.setPassword("password123");
        ResponseEntity<String> response = rest.postForEntity(url("/api/auth/login"), login, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data").path("token").asText();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
