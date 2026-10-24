package org.song.forum.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.song.forum.util.Result;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理：所有异常统一转换为 Result&lt;T&gt;，Controller 不再自行 try-catch
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常 → 400 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Result.fail(e.getMessage()));
    }

    /** 参数校验失败 → 400，字段错误逐条返回 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Map<String, String>>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            errors.put(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(Result.fail("参数校验失败", errors));
    }

    /**
     * 登录凭据错误 → 401，但必须保留具体原因。
     * <p>BadCredentialsException 是 AuthenticationException 的子类，若只保留父类处理器，
     * 会把「用户名或密码错误」覆盖成「未登录或登录已过期」，前端只能提示得含糊其辞。</p>
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Result<Void>> handleBadCredentials(BadCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.fail(e.getMessage()));
    }

    /** 未认证 / 会话过期 → 401 */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result<Void>> handleAuth(AuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.fail("未登录或登录已过期"));
    }

    /** 纵深防御：匿名请求穿透到 Service 层时兜底转 401（正常应被 Security 过滤器拦截为 401） */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Result<Void>> handleIllegalState(IllegalStateException e) {
        if (e.getMessage() != null && e.getMessage().contains("未登录")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.fail("未登录或登录已过期"));
        }
        return ResponseEntity.badRequest().body(Result.fail(e.getMessage()));
    }

    /**
     * 缺少必需请求参数 → 400（如 GET /api/tags/posts 缺 name）。
     * <p>原先会落到 Exception 兜底返回 500：这是客户端请求错误，必须明确 400。</p>
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParameter(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数: {}", e.getParameterName());
        return ResponseEntity.badRequest().body(Result.fail("缺少必需参数: " + e.getParameterName()));
    }

    /**
     * 请求参数类型不匹配 → 400（如 ?size=abc 或路径变量 id 非数字）。
     * <p>原先会落到 Exception 兜底返回 500，同样属于客户端错误。</p>
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("请求参数格式不正确: {}={}", e.getName(), e.getValue());
        return ResponseEntity.badRequest().body(Result.fail("参数格式不正确: " + e.getName()));
    }

    /** 上传文件超过上限 → 400（Spring 在进入 Controller 前由 multipart 解析器抛出） */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过上限: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Result.fail("图片不能超过 2MB"));
    }

    /**
     * 缺少文件 part（字段名写错、只传普通字段、空 multipart）→ 400。
     * <p>原先落到 Exception 兜底返回 500，把客户端错误报成了服务端错误。</p>
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Result<Void>> handleMissingPart(MissingServletRequestPartException e) {
        log.warn("缺少上传文件 part: {}", e.getRequestPartName());
        return ResponseEntity.badRequest().body(Result.fail("缺少图片文件"));
    }

    /** 请求不是 multipart/form-data → 400，直接告诉客户端正确的提交方式 */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Result<Void>> handleMultipart(MultipartException e) {
        log.warn("非 multipart 上传请求: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Result.fail("请使用 multipart/form-data 上传图片"));
    }

    /** 已登录但无权限 → 403 */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.fail("无权限执行此操作"));
    }

    /** 资源不存在 → 404 */
    @ExceptionHandler(java.util.NoSuchElementException.class)
    public ResponseEntity<Result<Void>> handleNotFound(java.util.NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.fail("资源不存在"));
    }

    /**
     * SSE 订阅 ticket 无效 → 401。
     * <p>不能落到 Exception 兜底：那会返回 500，nginx 冒烟与前端都无法区分
     * 「没有权限」和「服务端坏了」。ticket 一次性，过期/重放都必须拒绝。</p>
     */
    @ExceptionHandler(InvalidStreamTicketException.class)
    public ResponseEntity<Result<Void>> handleInvalidStreamTicket(InvalidStreamTicketException e) {
        log.warn("SSE 订阅凭证校验失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.fail(e.getMessage()));
    }

    /**
     * 登录失败限流 → 429 + Retry-After。
     * <p>不能返回 200 或 401：429 才能让客户端与网关区分「凭据错了」与「先别试了」；
     * Retry-After 给的是秒数，前端可据此提示，运维也能在压测脚本里据此退避。</p>
     */
    @ExceptionHandler(TooManyLoginAttemptsException.class)
    public ResponseEntity<Result<Void>> handleTooManyLoginAttempts(TooManyLoginAttemptsException e) {
        // 事实日志在 LoginRateLimiter.recordFailure（带 IP、阈值与锁定时长）；
        // 这里每条 429 都打 WARN 不带 IP，锁定期间等于给流量刷日志的放大器。
        log.debug("登录失败次数超限: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(Result.fail(e.getMessage()));
    }

    /**
     * 异步流（SSE）在连接断开后的写失败 → 安静收尾，不转 500。
     * <p>浏览器关页/切网时容器会抛 IOException（含 AsyncRequestNotUsableException），
     * 这是正常断连而不是服务端故障：只记 debug，返回 null 不再写 body——
     * 响应已作为异步流提交，再写 500 JSON 只会二次失败、并让日志被 ERROR 刷屏。</p>
     */
    @ExceptionHandler(java.io.IOException.class)
    public ResponseEntity<Result<Void>> handleIo(java.io.IOException e) {
        log.debug("IO 异常（连接已断开，按正常断连处理）: {}", e.toString());
        return null;
    }

    /** 兜底 → 500 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.internalServerError().body(Result.fail("服务器内部错误，请稍后重试"));
    }
}
