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
import org.song.forum.security.CspReportService;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSP 上报端点集成测试：走完整过滤器链与真实 HTTP，并挂 Logback appender 断言日志。
 * <p>每个用例使用不同的 X-Real-IP，避免共享的限速窗口互相污染。</p>
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:csp-report-test;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.security.csp-report.max-per-minute=3"
})
class CspReportIntegrationTest {

    private static final String LEGACY_JSON = "{\"csp-report\":{"
            + "\"document-uri\":\"https://example.com/post/1?token=secret#frag\","
            + "\"effective-directive\":\"script-src\","
            + "\"blocked-uri\":\"https://evil.example/x.js\","
            + "\"script-sample\":\"alert(1)\","
            + "\"disposition\":\"enforce\"}}";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachLogAppender() {
        serviceLogger = (Logger) LoggerFactory.getLogger(CspReportService.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void detachLogAppender() {
        if (serviceLogger != null && appender != null) {
            serviceLogger.detachAppender(appender);
            appender.stop();
            appender.list.clear();
        }
    }

    @Test
    void legacyReportReturns204WithEmptyBodyAndOneWarnLog() {
        ResponseEntity<String> response = post("198.51.100.30", "application/csp-report", LEGACY_JSON);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNullOrEmpty();
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");

        List<ILoggingEvent> warns = cspWarns();
        assertThat(warns).hasSize(1);
        String message = warns.get(0).getFormattedMessage();
        assertThat(message).contains("ip=198.51.100.30");
        assertThat(message).contains("directive=script-src");
        assertThat(message).contains("blocked=https://evil.example/x.js");
        assertThat(message).contains("doc=https://example.com/post/1");
    }

    @Test
    void batchReportReturns204AndLogsDirective() {
        String body = "[{\"type\":\"csp-violation\",\"body\":{"
                + "\"document-uri\":\"https://example.com/page?q=1\","
                + "\"effective-directive\":\"script-src\","
                + "\"blocked-uri\":\"inline\","
                + "\"disposition\":\"enforce\"}},"
                + "{\"type\":\"deprecation\",\"body\":{\"document-uri\":\"https://example.com/ignored\"}}]";

        ResponseEntity<String> response = post("198.51.100.31", "application/reports+json", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        List<ILoggingEvent> warns = cspWarns();
        assertThat(warns).hasSize(1);
        assertThat(warns.get(0).getFormattedMessage()).contains("directive=script-src");
    }

    @Test
    void fourthReportFromSameIpIsThrottledButStillReturns204() {
        String ip = "198.51.100.32";
        for (int i = 0; i < 4; i++) {
            assertThat(post(ip, "application/csp-report", LEGACY_JSON).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
        }

        assertThat(cspWarns()).hasSize(3);
    }

    @Test
    void invalidInputsStillReturn204WithoutWarnLogs() {
        String bigBody = "{\"csp-report\":{\"document-uri\":\"https://example.com/\","
                + "\"script-sample\":\"" + "a".repeat(20_000) + "\"}}";

        assertThat(post("198.51.100.33", "application/csp-report", "{not-json").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(post("198.51.100.34", "application/csp-report", bigBody).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(post("198.51.100.35", "text/plain", "not-a-report").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(cspWarns()).isEmpty();
    }

    @Test
    void getIsNotAllowed() {
        ResponseEntity<String> response = rest.exchange(
                url("/api/csp-report"), HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    // ---------- 辅助 ----------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> post(String ip, String contentType, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.set("X-Real-IP", ip);
        return rest.exchange(url("/api/csp-report"), HttpMethod.POST,
                new HttpEntity<>(body.getBytes(StandardCharsets.UTF_8), headers), String.class);
    }

    private List<ILoggingEvent> cspWarns() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getFormattedMessage().startsWith("CSP 违规上报"))
                .toList();
    }
}
