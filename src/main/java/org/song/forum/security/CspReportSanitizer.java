package org.song.forum.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * CSP 上报解析与清洗。
 *
 * <p>支持两种浏览器上报格式：</p>
 * <ul>
 *   <li>legacy（{@code application/csp-report}）：{@code {"csp-report": {...}}} 单条；</li>
 *   <li>Reporting API（{@code application/reports+json}）：数组，只取 {@code type=csp-violation}
 *       条目的 {@code body} 节点，其余 type 忽略。</li>
 * </ul>
 *
 * <p><b>只读白名单字段，不落原始 body</b>：URL 字段去掉 query 与 fragment 并截断到 300，
 * {@code script-sample} 去掉控制字符并截断到 120。这样日志里不会出现页面查询串、token
 * 或用户输入的大段内容，单条日志的体积也可控。</p>
 *
 * <p>坏 JSON、空 body、缺少 {@code csp-report} 节点一律返回空列表，异常在方法内消化，
 * 调用方（上报端点）永远不需要为解析失败准备错误响应。</p>
 */
@Component
public class CspReportSanitizer {

    /** Reporting API 的批量上报类型。 */
    private static final String REPORTS_CONTENT_TYPE = "application/reports+json";

    /** URL 字段最大长度。 */
    private static final int MAX_URL_LENGTH = 300;

    /** 触发样本最大长度。 */
    private static final int MAX_SAMPLE_LENGTH = 120;

    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

    private final ObjectMapper objectMapper;

    public CspReportSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析并清洗上报内容。
     *
     * @param contentType 请求的 Content-Type；包含 {@code application/reports+json} 时按批量格式解析
     * @param body        请求体原始字节（调用方已做体积上限校验）
     * @return 清洗后的违规列表；输入不可解析时返回空列表，从不抛异常
     */
    public List<CspViolation> sanitize(String contentType, byte[] body) {
        if (body == null || body.length == 0) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || root.isMissingNode() || root.isNull()) {
                return List.of();
            }
            if (contentType != null && contentType.contains(REPORTS_CONTENT_TYPE)) {
                return parseBatch(root);
            }
            JsonNode report = root.path("csp-report");
            return report.isObject() ? List.of(toViolation(report)) : List.of();
        } catch (IOException e) {
            // 坏 JSON 是上报端点的正常输入：只丢弃，不记原始内容，也不向外抛
            return List.of();
        }
    }

    /** 批量格式：数组元素为对象且 {@code type=csp-violation} 时取 {@code body} 节点。 */
    private List<CspViolation> parseBatch(JsonNode root) {
        if (!root.isArray()) {
            return List.of();
        }
        List<CspViolation> violations = new ArrayList<>();
        for (JsonNode element : root) {
            if (!element.isObject()
                    || !"csp-violation".equals(element.path("type").asText())) {
                continue;
            }
            JsonNode report = element.path("body");
            if (report.isObject()) {
                violations.add(toViolation(report));
            }
        }
        return violations;
    }

    /** 按白名单抽取字段；Reporting API 的 body 使用 camelCase，legacy 使用 kebab-case。 */
    private CspViolation toViolation(JsonNode report) {
        String directive = text(report, "effective-directive", "effectiveDirective");
        if (directive.isEmpty()) {
            directive = text(report, "violated-directive", "violatedDirective");
        }
        if (directive.isEmpty()) {
            directive = "unknown";
        }
        return new CspViolation(
                cleanUrl(text(report, "document-uri", "documentURL")),
                directive,
                cleanUrl(text(report, "blocked-uri", "blockedURL")),
                cleanUrl(text(report, "source-file", "sourceFile")),
                cleanSample(text(report, "script-sample", "sample")),
                text(report, "disposition", null));
    }

    /** 取文本字段：缺失、JSON null 或非值节点都视为空串。 */
    private static String text(JsonNode node, String kebabCaseName, String camelCaseName) {
        JsonNode value = node.get(kebabCaseName);
        if (value == null && camelCaseName != null) {
            value = node.get(camelCaseName);
        }
        if (value == null || value.isNull() || !value.isValueNode()) {
            return "";
        }
        return value.asText();
    }

    /** URL 清洗：去掉 query/fragment，trim 后截断到 300；{@code inline}/{@code eval} 等字面量原样保留。 */
    static String cleanUrl(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        int end = raw.length();
        int queryIndex = raw.indexOf('?');
        if (queryIndex >= 0) {
            end = Math.min(end, queryIndex);
        }
        int fragmentIndex = raw.indexOf('#');
        if (fragmentIndex >= 0) {
            end = Math.min(end, fragmentIndex);
        }
        return truncate(raw.substring(0, end).trim(), MAX_URL_LENGTH);
    }

    /** 样本清洗：去掉控制字符（含换行），trim 后截断到 120。 */
    static String cleanSample(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return truncate(CONTROL_CHARS.matcher(raw).replaceAll("").trim(), MAX_SAMPLE_LENGTH);
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
