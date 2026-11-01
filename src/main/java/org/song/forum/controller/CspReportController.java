package org.song.forum.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.song.forum.security.CspReportService;

/**
 * CSP 违规上报端点。
 *
 * <p>浏览器匿名 POST，不带认证头，因此安全性完全由「永远 204、不回显、限速、体积上限、
 * 只记白名单字段」承担：任何输入（坏 JSON、超长 body、错误 Content-Type）都返回空 204，
 * 不返回 {@code Result}，也不抛业务异常。</p>
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

    /**
     * 显式拒绝 GET：上报端点只接受 POST，避免被当成查询接口探测。
     *
     * <p>为什么不依赖 Spring MVC 默认的 405：项目的 {@code GlobalExceptionHandler} 用
     * {@code @ExceptionHandler(Exception.class)} 兜底，会抢先捕获
     * {@code HttpRequestMethodNotSupportedException} 并转成 500。这里直接回 405，
     * 既能保持「只开 POST」的语义，也不需要为一个端点改动全局异常处理。</p>
     */
    @GetMapping("/api/csp-report")
    public ResponseEntity<Void> rejectGet() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header(HttpHeaders.ALLOW, "POST")
                .build();
    }
}
