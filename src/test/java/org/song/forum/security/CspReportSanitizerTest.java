package org.song.forum.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSP 上报清洗器纯单元测试：legacy 解析、批量过滤、字面量保留、截断、坏输入。
 */
class CspReportSanitizerTest {

    private CspReportSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        sanitizer = new CspReportSanitizer(new ObjectMapper());
    }

    @Test
    void parsesLegacyReportAndStripsQueryAndFragment() {
        String body = "{\"csp-report\":{"
                + "\"document-uri\":\"https://example.com/post/1?token=secret#frag\","
                + "\"effective-directive\":\"script-src\","
                + "\"blocked-uri\":\"https://evil.example/x.js\","
                + "\"source-file\":\"https://example.com/app.js?v=1\","
                + "\"script-sample\":\"alert(1)\","
                + "\"disposition\":\"enforce\"}}";

        List<CspViolation> violations = sanitize("application/csp-report", body);

        assertThat(violations).containsExactly(new CspViolation(
                "https://example.com/post/1",
                "script-src",
                "https://evil.example/x.js",
                "https://example.com/app.js",
                "alert(1)",
                "enforce"));
    }

    @Test
    void batchFormatOnlyKeepsCspViolationEntries() {
        String body = "["
                + "{\"type\":\"csp-violation\",\"body\":{"
                + "\"document-uri\":\"https://example.com/page?q=1\","
                + "\"effective-directive\":\"script-src\","
                + "\"blocked-uri\":\"https://cdn.example/lib.js\","
                + "\"disposition\":\"enforce\"}},"
                + "{\"type\":\"deprecation\",\"body\":{"
                + "\"document-uri\":\"https://example.com/ignored\"}}]";

        List<CspViolation> violations = sanitize("application/reports+json; charset=UTF-8", body);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).directive()).isEqualTo("script-src");
        assertThat(violations.get(0).documentUri()).isEqualTo("https://example.com/page");
        assertThat(violations.get(0).blockedUri()).isEqualTo("https://cdn.example/lib.js");
    }

    @Test
    void keepsInlineAndEvalLiteralsAndFallsBackToViolatedDirective() {
        String inline = "{\"csp-report\":{"
                + "\"document-uri\":\"https://example.com/\","
                + "\"violated-directive\":\"img-src\","
                + "\"blocked-uri\":\"inline\"}}";
        String eval = "{\"csp-report\":{"
                + "\"document-uri\":\"https://example.com/\","
                + "\"effective-directive\":\"script-src\","
                + "\"blocked-uri\":\"eval\"}}";

        List<CspViolation> inlineViolations = sanitize("application/csp-report", inline);
        List<CspViolation> evalViolations = sanitize("application/csp-report", eval);

        assertThat(inlineViolations.get(0).blockedUri()).isEqualTo("inline");
        assertThat(inlineViolations.get(0).directive()).isEqualTo("img-src");
        assertThat(evalViolations.get(0).blockedUri()).isEqualTo("eval");
        assertThat(evalViolations.get(0).directive()).isEqualTo("script-src");
    }

    @Test
    void truncatesLongSampleAndUriAndRemovesControlCharacters() {
        String longSample = "a\\nb".repeat(80);
        String longUri = "https://example.com/" + "a".repeat(400);
        String body = "{\"csp-report\":{"
                + "\"document-uri\":\"" + longUri + "\","
                + "\"effective-directive\":\"script-src\","
                + "\"script-sample\":\"" + longSample + "\"}}";

        CspViolation violation = sanitize("application/csp-report", body).get(0);

        assertThat(violation.documentUri()).hasSize(300);
        assertThat(violation.sample()).hasSize(120);
        assertThat(violation.sample()).doesNotContain("\n").doesNotContain("\r");
        assertThat(violation.sample()).isEqualTo("ab".repeat(60));
    }

    @Test
    void stripsControlCharactersFromDirectiveDispositionAndUrlFields() {
        // 这些字段会原样进 WARN 日志：CR/LF 可伪造日志行，ESC 可做 ANSI 终端注入（CWE-117）
        String body = "{\"csp-report\":{"
                + "\"document-uri\":\"https://example.com/post/1\\r\\nFAKE-LINE?token=secret\","
                + "\"effective-directive\":\"script-src\\nFAKE\\u001b[31m\","
                + "\"blocked-uri\":\"inline\\r\\nFAKE\","
                + "\"disposition\":\"enforce\\nFAKE\"}}";

        CspViolation violation = sanitize("application/csp-report", body).get(0);

        for (String value : List.of(violation.documentUri(), violation.directive(),
                violation.blockedUri(), violation.disposition())) {
            assertThat(value).doesNotContain("\r").doesNotContain("\n").doesNotContain("\u001b");
        }
        assertThat(violation.documentUri()).isEqualTo("https://example.com/post/1FAKE-LINE");
        assertThat(violation.directive()).isEqualTo("script-srcFAKE[31m");
        assertThat(violation.blockedUri()).isEqualTo("inlineFAKE");
        assertThat(violation.disposition()).isEqualTo("enforceFAKE");
    }

    @Test
    void returnsEmptyListForBadJsonEmptyBodyOrMissingReportNode() {
        assertThat(sanitize("application/csp-report", "{not-json")).isEmpty();
        assertThat(sanitize("application/csp-report", "   ")).isEmpty();
        assertThat(sanitizer.sanitize("application/csp-report", new byte[0])).isEmpty();
        assertThat(sanitize("application/csp-report", "{\"other\":1}")).isEmpty();
        assertThat(sanitize("application/csp-report", "{\"csp-report\":\"not-an-object\"}")).isEmpty();
        assertThat(sanitize("application/reports+json", "{\"not\":\"an-array\"}")).isEmpty();
    }

    // ---------- 辅助 ----------

    private List<CspViolation> sanitize(String contentType, String body) {
        return sanitizer.sanitize(contentType, body.getBytes(StandardCharsets.UTF_8));
    }
}
