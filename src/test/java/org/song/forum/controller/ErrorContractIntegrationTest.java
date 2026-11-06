package org.song.forum.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import org.song.forum.dto.auth.RegisterRequest;
import org.song.forum.exception.GlobalExceptionHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误响应契约：坏请求必须是 4xx + Result（带 code），而不是 500 + ERROR 堆栈。
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

    private Logger exceptionLogger;
    private ListAppender<ILoggingEvent> exceptionAppender;

    @BeforeEach
    void attachLogAppender() {
        exceptionLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        exceptionAppender = new ListAppender<>();
        exceptionAppender.start();
        exceptionLogger.addAppender(exceptionAppender);
    }

    @AfterEach
    void detachLogAppender() {
        if (exceptionLogger != null && exceptionAppender != null) {
            exceptionLogger.detachAppender(exceptionAppender);
            exceptionAppender.stop();
            exceptionAppender.list.clear();
        }
    }

    @Test
    void malformedJsonToLoginReturns400() {
        ResponseEntity<String> response = postJson("/api/auth/login", "{\"username\":");

        assertResultError(response, HttpStatus.BAD_REQUEST, "BAD_JSON", "请求体格式错误");
    }

    @Test
    void malformedJsonToRegisterReturns400() {
        ResponseEntity<String> response = postJson("/api/auth/register", "not-a-json");

        assertResultError(response, HttpStatus.BAD_REQUEST, "BAD_JSON", "请求体格式错误");
    }

    @Test
    void jsonArrayToLoginReturns400() {
        ResponseEntity<String> response = postJson("/api/auth/login", "[]");

        assertResultError(response, HttpStatus.BAD_REQUEST, "BAD_JSON", "请求体格式错误");
    }

    @Test
    void plainTextContentTypeReturns415() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        ResponseEntity<String> response = rest.exchange(
                url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"a\",\"password\":\"b\"}", headers), String.class);

        assertResultError(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE", "不支持的 Content-Type");
    }

    @Test
    void plainTextContentTypeReturnsEnglishMessageWhenRequested() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "en");
        ResponseEntity<String> response = rest.exchange(
                url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"a\",\"password\":\"b\"}", headers), String.class);

        assertResultError(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE", "Unsupported content type");
    }

    @Test
    void emptyJsonObjectReturns400ByValidation() {
        ResponseEntity<String> response = postJson("/api/auth/login", "{}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .contains("\"success\":false")
                .contains("\"code\":\"VALIDATION_FAILED\"")
                .contains("\"message\":\"参数校验失败\"")
                .contains("\"data\":{")
                .contains("\"username\"")
                .contains("\"timestamp\":");
        assertNoErrorLogs();
    }

    @Test
    void duplicateUsernameCarriesBusinessErrorCode() {
        String username = "r15dup_" + System.nanoTime();
        RegisterRequest body = new RegisterRequest();
        body.setUsername(username);
        body.setPassword("password123");
        body.setEmail(username + "@test.com");

        ResponseEntity<String> first = rest.postForEntity(url("/api/auth/register"), body, String.class);
        assertThat(first.getStatusCode().value()).isEqualTo(201);

        ResponseEntity<String> second = rest.postForEntity(url("/api/auth/register"), body, String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(second.getBody())
                .contains("\"code\":\"BUSINESS_ERROR\"")
                .contains("\"message\":\"用户名已被占用\"")
                .contains("\"timestamp\":");
        assertNoErrorLogs();
    }

    private ResponseEntity<String> postJson(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private void assertResultError(ResponseEntity<String> response, HttpStatus status,
                                   String code, String message) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody())
                .contains("\"success\":false")
                .contains("\"code\":\"" + code + "\"")
                .contains("\"message\":\"" + message + "\"")
                .contains("\"timestamp\":");
        assertNoErrorLogs();
    }

    /** 客户端错误不得打 ERROR（只有兜底处理器会打；排障级别为 DEBUG）。 */
    private void assertNoErrorLogs() {
        assertThat(exceptionAppender.list)
                .noneMatch(event -> event.getLevel() == Level.ERROR);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
