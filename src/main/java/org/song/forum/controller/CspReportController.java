package org.song.forum.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.song.forum.security.CspReportService;

/**
 * CSP 违规上报端点。
 *
 * <p>浏览器匿名 POST，不带认证头，因此安全性完全由「永远 204、不回显、限速、体积上限、
 * 只记白名单字段」承担：任何 POST 输入（坏 JSON、超长 body、错误 Content-Type）都返回空 204，
 * 不返回 {@code Result}，也不抛业务异常。非 POST 方法由 {@code GlobalExceptionHandler}
 * 统一转成 405（Allow: POST），匿名请求则先被 Security 拦成 401。</p>
 */
@RestController
@RequiredArgsConstructor
public class CspReportController {

    private final CspReportService cspReportService;

    /** 浏览器 CSP 上报：永远 204，不解析失败、不返回错误体。 */
    @PostMapping("/api/csp-report")
    public ResponseEntity<Void> report(HttpServletRequest request) {
        cspReportService.accept(request);
        return ResponseEntity.noContent()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }
}
