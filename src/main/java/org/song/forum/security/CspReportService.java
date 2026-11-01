package org.song.forum.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 接收浏览器 CSP 上报并输出结构化日志。
 *
 * <p>处理顺序：解析客户端 IP → 限速 → 有界读取 body → 清洗 → 每条 WARN。
 * 限速先于读 body，是为了在被刷量时尽早丢弃，不把 CPU 花在读取与解析上。</p>
 *
 * <p><b>永远不抛异常、永远不返回错误体</b>：上报失败对浏览器没有意义，向外抛只会把
 * 异常栈刷进日志、把 5xx 暴露给匿名调用方。任何异常都只 debug 记录（不含原始 body）。</p>
 */
@Slf4j
@Component
public class CspReportService {

    private final ClientIpResolver clientIpResolver;
    private final CspReportThrottle throttle;
    private final CspReportSanitizer sanitizer;
    private final int maxBodyBytes;

    public CspReportService(ClientIpResolver clientIpResolver,
                            CspReportThrottle throttle,
                            CspReportSanitizer sanitizer,
                            @Value("${app.security.csp-report.max-body-bytes:8192}") int maxBodyBytes) {
        this.clientIpResolver = clientIpResolver;
        this.throttle = throttle;
        this.sanitizer = sanitizer;
        this.maxBodyBytes = maxBodyBytes;
    }

    /**
     * 接收一次上报。日志每条违规一行 WARN，单 IP 的条数由 {@link CspReportThrottle} 限制。
     *
     * @param request 原始请求；方法不会写入响应，也不会抛出异常
     */
    public void accept(HttpServletRequest request) {
        String ip = clientIpResolver.resolve(request);
        try {
            if (!throttle.tryAcquire(ip)) {
                log.debug("CSP 上报被限速丢弃 ip={}", ip);
                return;
            }
            byte[] body = request.getInputStream().readNBytes(maxBodyBytes + 1);
            if (body.length == 0) {
                log.debug("CSP 上报为空 ip={}", ip);
                return;
            }
            if (body.length > maxBodyBytes) {
                log.debug("CSP 上报超过 {} 字节，已丢弃 ip={} 实际={}", maxBodyBytes, ip, body.length);
                return;
            }
            String contentType = request.getContentType() == null ? "" : request.getContentType();
            for (CspViolation v : sanitizer.sanitize(contentType, body)) {
                log.warn("CSP 违规上报 ip={} directive={} blocked={} doc={} sample={} disposition={}",
                        ip, v.directive(), v.blockedUri(), v.documentUri(), v.sample(), v.disposition());
            }
        } catch (Exception e) {
            // 上报端点不允许因为坏输入产生 5xx，也不允许把异常栈刷进日志
            log.debug("CSP 上报处理失败 ip={}", ip, e);
        }
    }
}
