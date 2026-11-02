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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误响应契约：坏请求必须是 4xx + Result，而不是 500 + ERROR 堆栈。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:error-contract-test;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ErrorContractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void malformedJsonToLoginReturns400() {
        ResponseEntity<String> response = postJson("/api/auth/login", "{\"username\":");

        assertResultError(response, HttpStatus.BAD_REQUEST, "请求体格式错误");
    }

    @Test
    void malformedJsonToRegisterReturns400() {
        ResponseEntity<String> response = postJson("/api/auth/register", "not-a-json");

        assertResultError(response, HttpStatus.BAD_REQUEST, "请求体格式错误");
    }

    @Test
    void jsonArrayToLoginReturns400() {
        ResponseEntity<String> response = postJson("/api/auth/login", "[]");

        assertResultError(response, HttpStatus.BAD_REQUEST, "请求体格式错误");
    }

    @Test
    void plainTextContentTypeReturns415() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        ResponseEntity<String> response = rest.exchange(
                url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"a\",\"password\":\"b\"}", headers), String.class);

        assertResultError(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "不支持的 Content-Type");
    }

    @Test
    void emptyJsonObjectReturns400ByValidation() {
        ResponseEntity<String> response = postJson("/api/auth/login", "{}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"success\":false");
        assertThat(response.getBody()).contains("\"timestamp\":");
    }

    private ResponseEntity<String> postJson(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private void assertResultError(ResponseEntity<String> response, HttpStatus status, String message) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody())
                .contains("\"success\":false")
                .contains("\"message\":\"" + message + "\"")
                .contains("\"timestamp\":");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
