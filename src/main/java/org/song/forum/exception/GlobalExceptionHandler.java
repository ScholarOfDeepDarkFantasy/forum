package org.song.forum.exception;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.song.forum.util.Result;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 全局异常处理：所有异常统一转换为 Result&lt;T&gt;，Controller 不再自行 try-catch。
 * <p>文案从 {@link ErrorMessages} 取（中/英），失败响应携带机器可读 {@link ErrorCode}；
 * 业务异常与字段校验文案仍原样透传（本轮不翻译）。</p>
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ErrorMessages errorMessages;

    /** 业务异常 → 400；业务文案不翻译，保留异常自带中文 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return build(ErrorCode.BUSINESS_ERROR, e.getMessage());
    }

    /** 参数校验失败 → 400，字段错误逐条返回 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Map<String, String>>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            errors.put(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.getHttpStatus())
                .body(Result.fail(ErrorCode.VALIDATION_FAILED,
                        errorMessages.get(ErrorCode.VALIDATION_FAILED), errors));
    }

    /**
     * 登录凭据错误 → 401，但必须保留具体原因。
     * <p>BadCredentialsException 是 AuthenticationException 的子类，若只保留父类处理器，
     * 会把「用户名或密码错误」覆盖成「未登录或登录已过期」。</p>
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Result<Void>> handleBadCredentials(BadCredentialsException e) {
        return localized(ErrorCode.BAD_CREDENTIALS);
    }

    /** 未认证 / 会话过期 → 401 */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result<Void>> handleAuth(AuthenticationException e) {
        return localized(ErrorCode.UNAUTHORIZED);
    }

    /** 纵深防御：匿名请求穿透到 Service 层时兜底转 401（正常应被 Security 过滤器拦截为 401） */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Result<Void>> handleIllegalState(IllegalStateException e) {
        if (e.getMessage() != null && e.getMessage().contains("未登录")) {
            return localized(ErrorCode.UNAUTHORIZED);
        }
        return build(ErrorCode.BUSINESS_ERROR, e.getMessage());
    }

    /** 缺少必需请求参数 → 400（如 GET /api/tags/posts 缺 name） */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParameter(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数: {}", e.getParameterName());
        return localized(ErrorCode.MISSING_PARAMETER, e.getParameterName());
    }

    /** 请求参数类型不匹配 → 400（如 ?size=abc 或路径变量 id 非数字） */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("请求参数格式不正确: {}={}", e.getName(), e.getValue());
        return localized(ErrorCode.TYPE_MISMATCH, e.getName());
    }

    /** 上传文件超过上限 → 400（Spring 在进入 Controller 前由 multipart 解析器抛出） */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过上限: {}", e.getMessage());
        return localized(ErrorCode.UPLOAD_TOO_LARGE);
    }

    /** 缺少文件 part（字段名写错、只传普通字段、空 multipart）→ 400 */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Result<Void>> handleMissingPart(MissingServletRequestPartException e) {
        log.warn("缺少上传文件 part: {}", e.getRequestPartName());
        return localized(ErrorCode.MISSING_PART);
    }

    /** 请求不是 multipart/form-data → 400，直接告诉客户端正确的提交方式 */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Result<Void>> handleMultipart(MultipartException e) {
        log.warn("非 multipart 上传请求: {}", e.getMessage());
        return localized(ErrorCode.MULTIPART_REQUIRED);
    }

    /** 请求体无法解析（坏 JSON / 类型不匹配）→ 400；用 debug 避免匿名请求刷日志 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.debug("请求体无法解析: {}", e.getMessage());
        return localized(ErrorCode.BAD_JSON);
    }

    /** Content-Type 不受支持 → 415 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        log.debug("不支持的 Content-Type: {}", e.getContentType());
        return localized(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    /** 已登录但无权限 → 403 */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(AccessDeniedException e) {
        return localized(ErrorCode.FORBIDDEN);
    }

    /** 资源不存在 → 404 */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Result<Void>> handleNotFound(NoSuchElementException e) {
        return localized(ErrorCode.NOT_FOUND);
    }

    /**
     * SSE 订阅 ticket 无效 → 401。
     * <p>不能落到 Exception 兜底：那会返回 500，nginx 冒烟与前端都无法区分
     * 「没有权限」和「服务端坏了」。ticket 一次性，过期/重放都必须拒绝。</p>
     */
    @ExceptionHandler(InvalidStreamTicketException.class)
    public ResponseEntity<Result<Void>> handleInvalidStreamTicket(InvalidStreamTicketException e) {
        log.warn("SSE 订阅凭证校验失败: {}", e.getMessage());
        return localized(ErrorCode.STREAM_TICKET_INVALID);
    }

    /**
     * 登录失败限流 → 429 + Retry-After。
     * <p>不能返回 200 或 401：429 才能让客户端与网关区分「凭据错了」与「先别试了」。</p>
     */
    @ExceptionHandler(TooManyLoginAttemptsException.class)
    public ResponseEntity<Result<Void>> handleTooManyLoginAttempts(TooManyLoginAttemptsException e) {
        // 事实日志在 LoginRateLimiter.recordFailure；这里每条 429 都打 WARN 等于给流量刷日志。
        log.debug("登录失败次数超限: {}", e.getMessage());
        long minutes = Math.max(1L, (e.getRetryAfterSeconds() + 59) / 60);
        return ResponseEntity.status(ErrorCode.TOO_MANY_ATTEMPTS.getHttpStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(Result.fail(ErrorCode.TOO_MANY_ATTEMPTS,
                        errorMessages.get(ErrorCode.TOO_MANY_ATTEMPTS, minutes)));
    }

    /**
     * 异步流（SSE）在连接断开后的写失败 → 安静收尾，不转 500。
     * <p>响应已作为异步流提交，再写 500 JSON 只会二次失败、并让日志被 ERROR 刷屏。</p>
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Result<Void>> handleIo(IOException e) {
        log.debug("IO 异常（连接已断开，按正常断连处理）: {}", e.toString());
        return null;
    }

    /**
     * 请求方法不支持 → 405 + Allow。
     * <p>「方法不对」是客户端错误，必须与真正的服务端故障区分开。</p>
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getHttpStatus());
        Set<HttpMethod> supported = e.getSupportedHttpMethods();
        if (supported != null && !supported.isEmpty()) {
            builder.allow(supported.toArray(HttpMethod[]::new));
        }
        return builder.body(Result.fail(ErrorCode.METHOD_NOT_ALLOWED,
                errorMessages.get(ErrorCode.METHOD_NOT_ALLOWED)));
    }

    /** 兜底 → 500 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        log.error("未处理异常", e);
        return localized(ErrorCode.INTERNAL_ERROR);
    }

    /** 本地化文案 + 枚举 HTTP 状态 */
    private ResponseEntity<Result<Void>> localized(ErrorCode code, Object... args) {
        return ResponseEntity.status(code.getHttpStatus())
                .body(Result.fail(code, errorMessages.get(code, args)));
    }

    /** 业务原文 + 枚举 HTTP 状态 */
    private ResponseEntity<Result<Void>> build(ErrorCode code, String message) {
        return ResponseEntity.status(code.getHttpStatus())
                .body(Result.fail(code, message));
    }
}
