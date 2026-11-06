# 第十五轮实施计划：错误码与框架文案国际化（2026-11-06）

> 设计：`docs/superpowers/specs/2026-11-06-round15-error-code-i18n-design.md`（已提交，实施时以它为准）。
> 本轮目标：失败响应加机器可读 `code`，框架文案中/英双语（默认中文、中文逐字不变），
> Security 401/403 补齐 `code` 与 `timestamp`，前端按 `code` 兜底。不加业务功能、不改状态码语义。
> 本文档必须能被一次会话内的 fresh 子代理**逐字节照做**：所有代码/配置/测试/文档片段均可直接粘贴，
> 所有验证命令都给出可判断通过的预期输出。

## 0 执行约定

1. **分支**：直接在 `master` 上提交，不开分支、不 rebase、不 amend 已推送提交。
2. **提交粒度**：每个 Task 一次提交，计划共 **6 次**；执行中因质量审查追加 `ad38754`（默认 locale 修复），实际 **7 次**，见 §12。提交信息逐字使用附录 A，不得改写。
   Task 内所有 Step 的改动先全部完成并验证，再一次性 `git add` + `git commit`。
3. **Shell**：Windows Git Bash；仓库根 `E:/JavaSpace/forum`（Bash 路径 `/e/JavaSpace/forum`）。
   所有命令都在仓库根执行，除非明确 `cd frontend`。
4. **前端命令**：构建必须带子路径与 MSYS 变量转义：
   `MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build`；
   测试为 `pnpm test`（即 `vitest run`）。不要用 PowerShell。
5. **不新增依赖**：不加 Maven/npm 依赖；`MessageSource` 由 Spring Boot 自动配置提供。
6. **不动**：数据库、Flyway、nginx 配置、Jenkinsfile、镜像构建方式。只允许在 `deploy/deploy.sh`
   的冒烟段追加断言。
7. **审查复跑**（需要从干净副本重跑时）：
   ```bash
   rm -rf /c/Temp/r15-review && mkdir -p /c/Temp/r15-review
   git -c core.autocrlf=false archive HEAD | tar -x -C /c/Temp/r15-review
   cd /c/Temp/r15-review && mvn -B clean test
   ```
8. **每个 Task 结束前**：`git status --short` 只应出现该 Task 预期修改的文件；多余文件必须查清后
   删除（新增的 `target/`、`node_modules/` 已被忽略）。
9. **行号会漂移**：Step 中给出的「旧文本」用于定位替换，若行号对不上以文本为准，不得凭行号盲改。
10. **失败即停**：任何验证输出与预期不符，停下来记录实际输出，禁止「跳过继续」。

## 1 现状与基线（实测，2026-11-06，HEAD `1702c76`）

| 项 | 值 |
| --- | --- |
| 后端测试 | 202 用例 / 25 个测试类 / 1 skipped（`AvatarFlowIntegrationTest` 的 POSIX 用例） |
| 前端测试 | 334 用例 / 33 spec |
| E2E | 8 用例 / 5 spec（手动执行，不计入基线） |
| `utils/Result.java` | 42 行，字段 `success/message/data/timestamp`，失败响应无 `code` |
| `exception/GlobalExceptionHandler.java` | 209 行，17 处硬编码中文 |
| `config/SecurityConfig.java` | 101 行，:77-92 手拼 401/403 JSON（无 code/timestamp） |
| `application.yml` | 91 行，`spring:` 下无 `messages` |
| `frontend/src/api/index.js` | 156 行，错误提示只读 `message` |
| 仓库全局 | 无 `messages*.properties`、无 `MessageSource`、无 `LocaleContextHolder` |

本轮结束基线：后端 **214 / 26 类 / 1 skipped**（审查修复后为 **215 / 26**，见 §12）；前端 **336 / 33 spec**；E2E 8/8 不变。

## Task 1 提交设计与计划（docs，第 1/6 次提交）

**涉及文件**（均新增）：`docs/superpowers/specs/2026-11-06-round15-error-code-i18n-design.md`（224 行）、
`docs/superpowers/plans/2026-11-06-round15-error-code-i18n.md`（本文件）。

### Step 1-1 前置检查

```bash
cd /e/JavaSpace/forum
git rev-parse --abbrev-ref HEAD          # 预期：master
git status --short                        # 预期：只有两个 ?? 文档
wc -l docs/superpowers/specs/2026-11-06-round15-error-code-i18n-design.md \
      docs/superpowers/plans/2026-11-06-round15-error-code-i18n.md
```

预期：分支 `master`；状态两行 `??`；spec 行数在 180–240 之间；计划行数 > 500。

### Step 1-2 内容完整性检查

```bash
cd /e/JavaSpace/forum
grep -c '^| `' docs/superpowers/specs/2026-11-06-round15-error-code-i18n-design.md   # 预期 >= 17（ErrorCode 全表）
grep -c 'V1–V8\|V1-V8' docs/superpowers/specs/2026-11-06-round15-error-code-i18n-design.md  # 预期 >= 1
grep -c 'BAD_JSON\|UNSUPPORTED_MEDIA_TYPE\|METHOD_NOT_ALLOWED' docs/superpowers/plans/2026-11-06-round15-error-code-i18n.md  # 预期 >= 3
```

若文件缺失或行数明显不符：停止，向上游报告，禁止自行编造文档内容。

### Step 1-3 提交

```bash
cd /e/JavaSpace/forum
git add docs/superpowers/specs/2026-11-06-round15-error-code-i18n-design.md \
        docs/superpowers/plans/2026-11-06-round15-error-code-i18n.md
git commit -m "docs: 新增第十五轮错误码与国际化设计与计划"
git log --oneline -1
git status --short
```

预期：`git log` 输出一条 `docs: 新增第十五轮错误码与国际化设计与计划`；`git status` 为空。

---

## Task 2 后端契约：ErrorCode + messages + Result + 两个写出点（第 2/6 次提交）

**涉及文件**：新增 `src/main/java/org/song/forum/exception/ErrorCode.java`、
`src/main/java/org/song/forum/exception/ErrorMessages.java`、
`src/main/resources/messages.properties`、`src/main/resources/messages_en.properties`；
修改 `src/main/resources/application.yml`、`src/main/java/org/song/forum/util/Result.java`、
`src/main/java/org/song/forum/exception/GlobalExceptionHandler.java`、
`src/main/java/org/song/forum/config/SecurityConfig.java`、`deploy/deploy.sh`。

### Step 2-1 新建 `ErrorCode.java`

```bash
cat > src/main/java/org/song/forum/exception/ErrorCode.java <<'EOF'
package org.song.forum.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 机器可读错误码：常量名即响应体 {@code code}，同时携带 HTTP 状态与文案键。
 *
 * <p>新增错误码时必须同步三处：①`messages.properties`；②`messages_en.properties`；
 * ③前端 `src/api/index.js` 的 `CODE_MESSAGES` 兜底表。中文文案必须与既有响应逐字一致，
 * 否则前端提示与 E2E 断言会回归。</p>
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    BAD_JSON(HttpStatus.BAD_REQUEST, "error.bad-json"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "error.unsupported-media-type"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "error.method-not-allowed"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "error.validation-failed"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "error.unauthorized"),
    BAD_CREDENTIALS(HttpStatus.UNAUTHORIZED, "error.bad-credentials"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "error.forbidden"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "error.not-found"),
    UPLOAD_TOO_LARGE(HttpStatus.BAD_REQUEST, "error.upload-too-large"),
    MISSING_PART(HttpStatus.BAD_REQUEST, "error.missing-part"),
    MULTIPART_REQUIRED(HttpStatus.BAD_REQUEST, "error.multipart-required"),
    MISSING_PARAMETER(HttpStatus.BAD_REQUEST, "error.missing-parameter"),
    TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "error.type-mismatch"),
    STREAM_TICKET_INVALID(HttpStatus.UNAUTHORIZED, "error.stream-ticket-invalid"),
    TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "error.too-many-attempts"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "error.internal"),
    BUSINESS_ERROR(HttpStatus.BAD_REQUEST, "error.business");

    private final HttpStatus httpStatus;
    private final String messageKey;
}
EOF
```

### Step 2-2 新建 `ErrorMessages.java`

```bash
cat > src/main/java/org/song/forum/exception/ErrorMessages.java <<'EOF'
package org.song.forum.exception;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 错误文案解析：全站唯一入口，负责把请求 locale 归一成「中文（默认）」或「英文」。
 *
 * <p>为什么不在 MessageSource 层配 supportedLocales：Security 过滤器在 MVC 之外，
 * 拿不到 LocaleContext，只能 {@code request.getLocale()}；而容器 JVM 默认 locale 常是 en，
 * 不带 Accept-Language 的 curl 会拿到英文，破坏「默认中文」。因此这里只认显式 en，
 * 其余（zh、fr、null、系统默认）一律回退中文。</p>
 */
@Component
@RequiredArgsConstructor
public class ErrorMessages {

    private final MessageSource messageSource;

    /** MVC 内使用：locale 来自 LocaleContextHolder（Accept-Language 解析结果）。 */
    public String get(ErrorCode code, Object... args) {
        return getForLocale(code, LocaleContextHolder.getLocale(), args);
    }

    /** Security 过滤器内使用：locale 由调用方显式传入（request.getLocale()）。 */
    public String getForLocale(ErrorCode code, Locale locale, Object... args) {
        Object[] effective = (args == null || args.length == 0) ? null : args;
        return messageSource.getMessage(code.getMessageKey(), effective, normalize(locale));
    }

    /** 只有显式英文才走英文资源；其他一律用默认（中文）资源。 */
    static Locale normalize(Locale locale) {
        return locale != null && "en".equalsIgnoreCase(locale.getLanguage())
                ? Locale.ENGLISH
                : Locale.SIMPLIFIED_CHINESE;
    }
}
EOF
```

### Step 2-3 新建 `messages.properties`（默认中文，逐字保留现状）

```bash
cat > src/main/resources/messages.properties <<'EOF'
# 第十五轮：错误文案默认资源（中文）。键与 org.song.forum.exception.ErrorCode 一一对应。
# 中文文案必须与第十四轮的响应逐字一致，避免前端提示与 E2E 断言回归。
error.bad-json=请求体格式错误
error.unsupported-media-type=不支持的 Content-Type
error.method-not-allowed=请求方法不被支持
error.validation-failed=参数校验失败
error.unauthorized=未登录或登录已过期
error.bad-credentials=用户名或密码错误
error.forbidden=无权限执行此操作
error.not-found=资源不存在
error.upload-too-large=图片不能超过 2MB
error.missing-part=缺少图片文件
error.multipart-required=请使用 multipart/form-data 上传图片
error.missing-parameter=缺少必需参数: {0}
error.type-mismatch=参数格式不正确: {0}
error.stream-ticket-invalid=通知连接凭证无效或已过期
error.too-many-attempts=登录失败次数过多，请 {0} 分钟后再试
error.internal=服务器内部错误，请稍后重试
error.business=业务处理失败
EOF
```

### Step 2-4 新建 `messages_en.properties`

```bash
cat > src/main/resources/messages_en.properties <<'EOF'
# Round 15: English error messages. Only an explicit "Accept-Language: en" reaches this bundle.
error.bad-json=Malformed request body
error.unsupported-media-type=Unsupported content type
error.method-not-allowed=HTTP method not allowed
error.validation-failed=Validation failed
error.unauthorized=Authentication required or session expired
error.bad-credentials=Invalid username or password
error.forbidden=You do not have permission to perform this action
error.not-found=Resource not found
error.upload-too-large=Image exceeds the 2MB limit
error.missing-part=Image file is required
error.multipart-required=Upload must use multipart/form-data
error.missing-parameter=Missing required parameter: {0}
error.type-mismatch=Invalid parameter format: {0}
error.stream-ticket-invalid=Notification stream ticket is invalid or expired
error.too-many-attempts=Too many login attempts. Try again in {0} minute(s)
error.internal=Internal server error. Please try again later
error.business=Business rule violation
EOF
```

### Step 2-5 修改 `application.yml`

在 `spring:` 的 `application.name` 之后插入 `messages` 段。Git Bash 自带 `awk`（不依赖 python），
按整行匹配、原样写出缩进；只运行一次（第二次会被前置判断跳过）：

```bash
if grep -q '^  messages:$' src/main/resources/application.yml; then
  echo "application.yml 已包含 messages 段，跳过"
else
  awk '
    { print }
    /^    name: JavaForum$/ {
      print ""
      print "  # ---- 错误文案国际化（第十五轮） ----"
      print "  # 默认资源 messages.properties 为中文，messages_en.properties 为英文。"
      print "  # fallback-to-system-locale=false：请求无 locale 时不再看 JVM 默认 locale，"
      print "  # 直接落到中文默认资源（容器 locale 常为 en，否则「默认中文」会失效）。"
      print "  messages:"
      print "    basename: messages"
      print "    encoding: UTF-8"
      print "    fallback-to-system-locale: false"
    }
  ' src/main/resources/application.yml > /tmp/application.yml.r15
  mv /tmp/application.yml.r15 src/main/resources/application.yml
fi
grep -c 'basename: messages' src/main/resources/application.yml   # 预期：1
sed -n '1,22p' src/main/resources/application.yml
```

预期：`messages:` 缩进 2 空格、其下三项缩进 4 空格，紧跟在 `name: JavaForum` 之后（中间空一行）；
`grep -c` 输出 `1`。若输出 `2`，说明重复执行过，先 `git checkout -- src/main/resources/application.yml` 再重来。

### Step 2-6 替换 `Result.java`（整文件）

```bash
cat > src/main/java/org/song/forum/util/Result.java <<'EOF'
package org.song.forum.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.song.forum.exception.ErrorCode;

/**
 * 统一响应结构 Result&lt;T&gt;
 * <p>success=true 时 data 为业务数据；success=false 时 code 为机器可读错误码、
 * message 为可展示文案（按请求语言本地化），data 为 null。</p>
 * <p>code 在成功响应里为 null，由 {@code NON_NULL} 省略，成功响应结构与第十四轮一致。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> {

    private boolean success;
    private String code;
    private String message;
    private T data;
    private Long timestamp;

    public static <T> Result<T> ok(T data) {
        return new Result<>(true, null, "ok", data, System.currentTimeMillis());
    }

    public static <T> Result<T> ok(T data, String message) {
        return new Result<>(true, null, message, data, System.currentTimeMillis());
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(String message) {
        return new Result<>(false, null, message, null, System.currentTimeMillis());
    }

    public static <T> Result<T> fail(String message, T data) {
        return new Result<>(false, null, message, data, System.currentTimeMillis());
    }

    /** 失败响应（带机器可读错误码）：message 缺省为 code 名，仅作非 Web 场景兜底。 */
    public static <T> Result<T> fail(ErrorCode code) {
        return fail(code, code.name());
    }

    public static <T> Result<T> fail(ErrorCode code, String message) {
        return new Result<>(false, code.name(), message, null, System.currentTimeMillis());
    }

    public static <T> Result<T> fail(ErrorCode code, String message, T data) {
        return new Result<>(false, code.name(), message, data, System.currentTimeMillis());
    }
}
EOF
```

### Step 2-7 替换 `GlobalExceptionHandler.java`（整文件）

```bash
cat > src/main/java/org/song/forum/exception/GlobalExceptionHandler.java <<'EOF'
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
EOF
```

### Step 2-8 替换 `SecurityConfig.java`（整文件）

```bash
cat > src/main/java/org/song/forum/config/SecurityConfig.java <<'EOF'
package org.song.forum.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.song.forum.exception.ErrorCode;
import org.song.forum.exception.ErrorMessages;
import org.song.forum.security.JwtAuthenticationFilter;
import org.song.forum.util.Result;

import java.io.IOException;
import java.util.Locale;

/**
 * Spring Security 6 配置（无状态 JWT 方案）
 * <p>说明：按确认结论采用 JWT 无状态认证，因此禁用 Session、CSRF 与表单登录。
 * {@code @EnableMethodSecurity} 启用方法级 @PreAuthorize 注解（管理员/版主权限）。</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;
    private final ErrorMessages errorMessages;

    /** 公开接口：认证文档健康检查、注册登录、板块与内容浏览 */
    private static final String[] PUBLIC_GET = {
            "/api/health",
            "/api/boards/**",
            "/api/posts/**",
            "/api/tags/**",
            "/api/auth/users/*",
            "/uploads/avatars/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    /**
     * 管理员专属路径：显式写在访问规则最前面。
     * <p>为什么必须单列：下面的 {@code requestMatchers(HttpMethod.GET).permitAll()} 会放行
     * 任意 GET 请求，actuator 端点（{@code /actuator/health}、{@code /actuator/info} ...）
     * 正好都是 GET，不加这条规则就会被匿名访问。</p>
     */
    private static final String[] ADMIN_ONLY = {
            "/actuator/**"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())                       // JWT 无状态方案，无 Cookie 会话
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(auth -> auth
                        // 必须最先声明：否则下面 GET 全放行会把 /actuator 一并放开
                        .requestMatchers(ADMIN_ONLY).hasRole("ADMIN")
                        .requestMatchers(PUBLIC_GET).permitAll()
                        // SSE 订阅：EventSource 不能带 Authorization 头，鉴权由一次性 ticket 承担。
                        .requestMatchers(HttpMethod.GET, "/api/notifications/stream").permitAll()
                        // CSP 上报由浏览器发起，不带认证头；端点自身用限速/体积上限/永不回显兜底
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
                                "/api/csp-report").permitAll()
                        // 必须先于下面的 GET 放行规则声明，否则匿名请求会直达 Controller 抛 500
                        .requestMatchers("/api/auth/me", "/api/auth/profile", "/api/auth/logout").authenticated()
                        .requestMatchers(HttpMethod.GET).permitAll()  // MVP：其余 GET 浏览公开
                        .anyRequest().authenticated()                 // 其余（POST/PUT/DELETE）需登录
                )
                .exceptionHandling(ex -> ex
                        // 未认证访问受保护接口 → 401 JSON（而不是重定向登录页）
                        .authenticationEntryPoint((request, response, e) ->
                                writeError(response, ErrorCode.UNAUTHORIZED, request.getLocale()))
                        // 已登录但权限不足 → 403 JSON
                        .accessDeniedHandler((request, response, e) ->
                                writeError(response, ErrorCode.FORBIDDEN, request.getLocale()))
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Security 过滤器链在 MVC 之外，拿不到 LocaleContextHolder，只能显式传 request 的 locale；
     * JSON 交给 ObjectMapper 生成（不手拼字符串），字段与 GlobalExceptionHandler 保持一致。
     */
    private void writeError(HttpServletResponse response, ErrorCode code, Locale locale) throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(),
                Result.fail(code, errorMessages.getForLocale(code, locale)));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
EOF
```

### Step 2-9 在 `deploy/deploy.sh` 冒烟段追加 3 条断言

定位（必须唯一，旧文本 3 行）：

```bash
curl -fsS -H 'Host: raspi' "http://127.0.0.1/forum/api/posts?page=0&size=1" >/dev/null \
    || die "经 nginx 访问 /forum/api/posts 失败（检查反代与数据库连通性）"
```

在其后插入：

```bash
# 第十五轮：失败响应必须带机器可读 code；不带 Accept-Language 时必须是中文
# （容器 JVM 默认 locale 常为 en，靠 ErrorMessages 归一保持「默认中文」）
bad_json_raw="$(curl -sS --max-time 5 -H 'Content-Type: application/json' \
    --data '{bad' "http://127.0.0.1:$HOST_PORT/api/auth/login" || true)"
printf '%s' "$bad_json_raw" | grep -q '"code":"BAD_JSON"' \
    || die "坏 JSON 未返回 code=BAD_JSON（实际：${bad_json_raw:-空}）"
printf '%s' "$bad_json_raw" | grep -q '"message":"请求体格式错误"' \
    || die "默认语言不是中文（实际：${bad_json_raw:-空}）"
bad_json_en="$(curl -sS --max-time 5 -H 'Accept-Language: en' -H 'Content-Type: application/json' \
    --data '{bad' "http://127.0.0.1:$HOST_PORT/api/auth/login" || true)"
printf '%s' "$bad_json_en" | grep -q '"message":"Malformed request body"' \
    || die "Accept-Language: en 未返回英文文案（实际：${bad_json_en:-空}）"
```

插入后自检：

```bash
bash -n deploy/deploy.sh && echo "deploy.sh 语法 OK"
grep -c 'BAD_JSON' deploy/deploy.sh          # 预期 2（一条断言 + 一条 die 文案）
```

### Step 2-10 编译 + 跑既有 202 用例（本轮行为不变）

```bash
cd /e/JavaSpace/forum
mvn -B -q -DskipTests compile && echo "compile OK"
mvn -B clean test 2>&1 | tail -25
```

预期：编译通过；最后汇总 `Tests run: 202, Failures: 0, Errors: 0, Skipped: 1`；
`surefire-reports` 仍为 25 个 txt。若出现失败，按报错修到全绿再继续。

### Step 2-11 提交

```bash
cd /e/JavaSpace/forum
git status --short
git add src/main/java/org/song/forum/exception/ErrorCode.java \
        src/main/java/org/song/forum/exception/ErrorMessages.java \
        src/main/java/org/song/forum/exception/GlobalExceptionHandler.java \
        src/main/java/org/song/forum/util/Result.java \
        src/main/java/org/song/forum/config/SecurityConfig.java \
        src/main/resources/messages.properties \
        src/main/resources/messages_en.properties \
        src/main/resources/application.yml \
        deploy/deploy.sh
git commit -m "feat: 为失败响应增加机器可读错误码并支持中英文案"
git log --oneline -1
```

预期提交信息逐字如附录 A 第 2 条；`git status --short` 为空。

---

## Task 3 测试守护（第 3/6 次提交）

**涉及文件**：新增 `src/test/java/org/song/forum/controller/ErrorCodeIntegrationTest.java`（10 例）；
修改 `src/test/java/org/song/forum/controller/ErrorContractIntegrationTest.java`（5 → 7 例）。
合计 +12 例、+1 个测试类（25 → 26）。

### Step 3-1 新建 `ErrorCodeIntegrationTest.java`

说明：6 例走真实 HTTP（`@SpringBootTest(RANDOM_PORT)` + 内联 H2），2 例校验 `Result` JSON 序列化，
2 例校验 `ErrorMessages` 的 locale 归一。放在集成测试类里是为了满足「测试类只 +1」。

```bash
cat > src/test/java/org/song/forum/controller/ErrorCodeIntegrationTest.java <<'EOF'
package org.song.forum.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.song.forum.dto.auth.LoginRequest;
import org.song.forum.dto.auth.RegisterRequest;
import org.song.forum.exception.ErrorCode;
import org.song.forum.exception.ErrorMessages;
import org.song.forum.util.Result;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第十五轮错误码契约：失败响应带机器可读 code；框架文案按 Accept-Language 中/英切换，
 * 且「只有显式 en 才英文，其余一律中文」。另含 Result 序列化与 ErrorMessages 归一用例。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:error-code-test;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ErrorCodeIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ErrorMessages errorMessages;

    @Test
    void malformedJsonReturnsBadJsonCode() {
        ResponseEntity<String> response = postBadJsonWithLocale(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .contains("\"success\":false")
                .contains("\"code\":\"BAD_JSON\"")
                .contains("\"message\":\"请求体格式错误\"")
                .contains("\"timestamp\":");
    }

    @Test
    void plainTextContentTypeReturnsUnsupportedMediaTypeCode() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        ResponseEntity<String> response = rest.exchange(url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"a\",\"password\":\"b\"}", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody())
                .contains("\"code\":\"UNSUPPORTED_MEDIA_TYPE\"")
                .contains("\"message\":\"不支持的 Content-Type\"");
    }

    @Test
    void nonPostToCspReportReturnsMethodNotAllowedCode() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(loginAsFreshUser());
        ResponseEntity<String> response = rest.exchange(url("/api/csp-report"), HttpMethod.PUT,
                new HttpEntity<>("{}", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().get(HttpHeaders.ALLOW)).contains("POST");
        assertThat(response.getBody())
                .contains("\"code\":\"METHOD_NOT_ALLOWED\"")
                .contains("\"message\":\"请求方法不被支持\"")
                .contains("\"timestamp\":");
    }

    @Test
    void anonymousRequestReturnsUnauthorizedCodeFromSecurity() {
        ResponseEntity<String> response = rest.getForEntity(url("/api/auth/me"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody())
                .contains("\"success\":false")
                .contains("\"code\":\"UNAUTHORIZED\"")
                .contains("\"message\":\"未登录或登录已过期\"")
                .contains("\"timestamp\":");
    }

    @Test
    void englishAcceptLanguageReturnsEnglishMessage() {
        ResponseEntity<String> response = postBadJsonWithLocale("en");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .contains("\"code\":\"BAD_JSON\"")
                .contains("\"message\":\"Malformed request body\"");
    }

    @Test
    void unknownLocaleFallsBackToChinese() {
        ResponseEntity<String> response = postBadJsonWithLocale("fr");

        assertThat(response.getBody()).contains("\"message\":\"请求体格式错误\"");
    }

    @Test
    void failureResultSerializesCode() throws Exception {
        String json = objectMapper.writeValueAsString(Result.fail(ErrorCode.BAD_JSON, "x"));

        assertThat(json).contains("\"success\":false").contains("\"code\":\"BAD_JSON\"");
    }

    @Test
    void successResultOmitsCode() throws Exception {
        String json = objectMapper.writeValueAsString(Result.ok("hello"));

        assertThat(json).contains("\"success\":true").doesNotContain("\"code\"");
    }

    @Test
    void errorMessagesResolvesEnglish() {
        assertThat(errorMessages.getForLocale(ErrorCode.INTERNAL_ERROR, Locale.ENGLISH))
                .isEqualTo("Internal server error. Please try again later");
    }

    @Test
    void errorMessagesNormalizesUnknownLocaleToChinese() {
        assertThat(errorMessages.getForLocale(ErrorCode.INTERNAL_ERROR, Locale.FRENCH))
                .isEqualTo("服务器内部错误，请稍后重试");
    }

    private ResponseEntity<String> postBadJsonWithLocale(String language) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (language != null) {
            headers.set(HttpHeaders.ACCEPT_LANGUAGE, language);
        }
        return rest.exchange(url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>("{\"username\":", headers), String.class);
    }

    private String loginAsFreshUser() throws Exception {
        String username = "r15_" + System.nanoTime();
        RegisterRequest register = new RegisterRequest();
        register.setUsername(username);
        register.setPassword("password123");
        register.setEmail(username + "@test.com");
        ResponseEntity<String> registered = rest.postForEntity(
                url("/api/auth/register"), register, String.class);
        assertThat(registered.getStatusCode().value()).isEqualTo(201);

        LoginRequest login = new LoginRequest();
        login.setUsername(username);
        login.setPassword("password123");
        ResponseEntity<String> response = rest.postForEntity(url("/api/auth/login"), login, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data").path("token").asText();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
EOF
```

### Step 3-2 替换 `ErrorContractIntegrationTest.java`（7 例）

```bash
cat > src/test/java/org/song/forum/controller/ErrorContractIntegrationTest.java <<'EOF'
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
EOF
```

### Step 3-3 变异验证（证明新用例真的在守护，而不是恒绿）

```bash
cd /e/JavaSpace/forum
sed -i 's/^error.bad-json=.*/error.bad-json=变异/' src/main/resources/messages.properties
mvn -B -Dtest=ErrorCodeIntegrationTest test 2>&1 | tail -20   # 预期：BUILD FAILURE，至少 2 例失败
git checkout -- src/main/resources/messages.properties
mvn -B -Dtest=ErrorCodeIntegrationTest test 2>&1 | tail -8     # 预期：Tests run: 10, Failures: 0
```

记录两次输出的关键行（T6 回填 §12 用）。若第一次没有失败，说明断言没覆盖到，必须修正后再跑。

### Step 3-4 全量回归 214 / 26 类

```bash
cd /e/JavaSpace/forum
mvn -B clean test 2>&1 | tail -30
ls target/surefire-reports/*.txt | wc -l                 # 预期：26
grep -h "Tests run" target/surefire-reports/*.txt | awk -F'[:,]' '{s+=$2} END {print s}'   # 预期：214
```

预期：`Tests run: 214, Failures: 0, Errors: 0, Skipped: 1`。

### Step 3-5 提交

```bash
cd /e/JavaSpace/forum
git add src/test/java/org/song/forum/controller/ErrorCodeIntegrationTest.java \
        src/test/java/org/song/forum/controller/ErrorContractIntegrationTest.java
git commit -m "test: 守护错误码与双语错误文案"
git log --oneline -1
```

---

## Task 4 前端错误码兜底（第 4/6 次提交）

**涉及文件**：`frontend/src/api/index.js`、`frontend/src/api/index.spec.js`。

### Step 4-1 改 `index.js`

在 `isCredentialEndpoint` 函数之后插入兜底表与取值函数：

```js
/**
 * 错误码兜底文案：后端正常都会带 message（已按 Accept-Language 本地化），
 * 只有「有 code 没 message」的场景（旧后端、网关错误页）才用这张表。
 * 这里只兜中文，不引入 i18n 库。
 */
const CODE_MESSAGES = {
  BAD_JSON: '请求体格式错误',
  BAD_CREDENTIALS: '用户名或密码错误',
  UNAUTHORIZED: '未登录或登录已过期',
  FORBIDDEN: '无权限执行此操作',
  NOT_FOUND: '资源不存在',
  UNSUPPORTED_MEDIA_TYPE: '不支持的 Content-Type',
  METHOD_NOT_ALLOWED: '请求方法不被支持',
  TOO_MANY_ATTEMPTS: '操作过于频繁，请稍后再试',
  INTERNAL_ERROR: '服务器内部错误，请稍后重试'
}

/** 优先后端 message；为空时按机器可读 code 兜底；都认不出时返回空串，由调用方决定默认文案。 */
function errorMessageOf(data) {
  if (data && typeof data === 'object') {
    if (data.message) return data.message
    if (data.code && CODE_MESSAGES[data.code]) return CODE_MESSAGES[data.code]
  }
  return ''
}
```

把成功分支的这两行（旧文本）替换：

```js
      ElMessage.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message || '请求失败'))
```

替换为：

```js
      const message = errorMessageOf(res) || '请求失败'
      ElMessage.error(message)
      return Promise.reject(new Error(message))
```

把错误分支的（旧文本，2 行）：

```js
    const status = error.response?.status
    const message = error.response?.data?.message || error.message || '网络错误'
```

替换为：

```js
    const status = error.response?.status
    const data = error.response?.data
    const message = errorMessageOf(data) || (data ? '请求失败' : error.message || '网络错误')
```

自检：

```bash
cd /e/JavaSpace/forum/frontend
grep -n "CODE_MESSAGES\|errorMessageOf" src/api/index.js
sed -n '78,100p' src/api/index.js
```

### Step 4-2 `index.spec.js` 加 2 例

定位 describe 结尾（旧文本唯一）：

```js
  it('成功响应解包出 data 字段', () => {
    expect(handlers.response({ data: { success: true, data: { id: 7 } } })).toEqual({ id: 7 })
  })
})
```

替换为：

```js
  it('成功响应解包出 data 字段', () => {
    expect(handlers.response({ data: { success: true, data: { id: 7 } } })).toEqual({ id: 7 })
  })

  it('业务失败只有 code 时按错误码兜底提示', async () => {
    const rejected = handlers.response({ data: { success: false, code: 'INTERNAL_ERROR' } })

    await expect(rejected).rejects.toThrow('服务器内部错误，请稍后重试')
    expect(elMessageError).toHaveBeenCalledWith('服务器内部错误，请稍后重试')
  })

  it('未知错误码且无 message 时回退到「请求失败」', async () => {
    const err = {
      config: { url: '/posts' },
      response: { status: 400, data: { success: false, code: 'SOMETHING_NEW' } },
      message: 'Request failed'
    }

    await expect(handlers.responseError(err)).rejects.toBe(err)

    expect(elMessageError).toHaveBeenCalledWith('请求失败')
  })
})
```

### Step 4-3 前端全量 336 / 33

```bash
cd /e/JavaSpace/forum/frontend
pnpm test 2>&1 | tail -15
```

预期：`Test Files 33 passed (33)`、`Tests 336 passed (336)`。

### Step 4-4 构建与预算（V3）

```bash
cd /e/JavaSpace/forum/frontend
MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build 2>&1 | tail -8
pnpm check:bundle 2>&1 | tail -20
```

预期：构建成功；`check:bundle` 五项全部在预算内（入口 JS 150 kB、入口 CSS 70 kB、
单 chunk 300 kB、CSS 总量 260 kB、JS 总量 950 kB），退出码 0。

### Step 4-5 提交

```bash
cd /e/JavaSpace/forum
git add frontend/src/api/index.js frontend/src/api/index.spec.js
git commit -m "fix: 前端按错误码兜底展示并保留后端文案"
git log --oneline -1
```

---

## Task 5 文档记录（第 5/6 次提交）

**涉及文件**：`README.md`、`docs/DEVELOPMENT.md`、`docs/deployment/README.md`。

### Step 5-1 `README.md` 错误响应契约

把 `### 错误响应契约` 标题（README :29）到 `## 环境要求` 标题（README :46）之间的**正文**（含状态表与「补充：上传超过 2MB…」那段）整体替换为下面这段；保留 `### 错误响应契约` 标题本身，且不要动 `## 环境要求` 及之后内容。替换后自检：

```bash
grep -c '^### 错误响应契约$' README.md    # 预期：1
grep -c '^## 环境要求$' README.md         # 预期：1
```

```markdown
失败响应体统一为 `{"success":false,"code":"…","message":"…","timestamp":…}`（成功响应不含 `code`），
不泄露堆栈；`code` 是机器可读错误码，`message` 按请求的 `Accept-Language` 返回中文或英文
（默认中文，只有显式 `en` 才返回英文；业务异常与字段校验文案仍为中文）。Security 过滤器写出的
401/403 与 MVC 同构。nginx 层的 413 仍是 HTML 页面：

| 状态 | `code` | 何时出现 |
|---|---|---|
| 400 | `BAD_JSON` / `VALIDATION_FAILED` / `BUSINESS_ERROR` / `MISSING_PARAMETER` / `TYPE_MISMATCH` / `UPLOAD_TOO_LARGE` / `MISSING_PART` / `MULTIPART_REQUIRED` | 业务规则不满足、`@Valid` 字段错误、缺/错 query 参数、**请求体不是合法 JSON**、上传超过 2MB |
| 401 | `UNAUTHORIZED` / `BAD_CREDENTIALS` / `STREAM_TICKET_INVALID` | 未登录、token 失效/过期、密码错误、SSE 票据无效 |
| 403 | `FORBIDDEN` | 角色不足 |
| 404 | `NOT_FOUND` | 资源不存在 |
| 405 | `METHOD_NOT_ALLOWED` | 方法不允许（认证后 `PUT/PATCH/DELETE/HEAD`；匿名请求先被 Security 返回 401），带 `Allow: POST`；`OPTIONS` 由 Spring 返回 200 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | `Content-Type` 不受支持（如对 JSON 接口发 `text/plain`） |
| 429 | `TOO_MANY_ATTEMPTS` | 登录失败次数超限，带 `Retry-After` 秒数 |
| 500 | `INTERNAL_ERROR` | 未预期异常（唯一兜底；出现即代表需要修） |

**语言协商**：只有显式 `Accept-Language: en` 返回英文，其余（缺省/`zh`/其他）一律中文——容器的
JVM 默认 locale 常是 `en`，后端做了归一。调用方应优先用 `code` 分支，`message` 只用于展示：

```bash
curl -s -H 'Content-Type: application/json' --data '{bad' http://127.0.0.1:8080/api/auth/login
# {"success":false,"code":"BAD_JSON","message":"请求体格式错误","timestamp":...}
curl -s -H 'Accept-Language: en' -H 'Content-Type: application/json' --data '{bad' http://127.0.0.1:8080/api/auth/login
# {"success":false,"code":"BAD_JSON","message":"Malformed request body","timestamp":...}
```

补充：上传超过 2MB 时后端返回 **400** `图片不能超过 2MB`（不是 413）；413 只出现在 nginx 的
`client_max_body_size 3m`（HTML 响应，非本契约）。
```

### Step 5-2 `README.md` 测试基线

- 把「当前为 25 个测试类、202 个用例（其中 1 个 POSIX 文件权限用例在 Windows 上跳过）」
  改为「当前为 26 个测试类、214 个用例（其中 1 个 POSIX 文件权限用例在 Windows 上跳过）」。
- 把「当前为 33 个 spec、334 个用例」改为「当前为 33 个 spec、336 个用例」。
- 在「接口层（`src/api/index.spec.js`）」条目末尾补一句：
  `；错误码兜底（有 code 无 message 时按码映射，未知 code 回退「请求失败」）`（插入在
  `401 时凭据类接口…` 那句之前或之后均可，保持句子通顺）。

### Step 5-3 `DEVELOPMENT.md` §3.1 代码规模

先实测：

```bash
cd /e/JavaSpace/forum
echo "main files: $(find src/main/java -name '*.java' | wc -l)"
echo "main lines: $(find src/main/java -name '*.java' -exec cat {} + | wc -l)"
echo "test files: $(find src/test/java -name '*.java' | wc -l)"
echo "test lines: $(find src/test/java -name '*.java' -exec cat {} + | wc -l)"
```

把 §3.1 表两行更新为实测值（文件数预期 89 / 27；行数照抄命令输出，禁止估算）：

```markdown
| 后端主代码（`src/main/java`） | 89 | <实测> |
| 后端测试（`src/test/java`） | 27 | <实测> |
```

把后端分层那句的 `exception/` 4 改为 `exception/` 6，并在括号补「+`ErrorCode`、`ErrorMessages`」：
即 `…、`util/` 4、`exception/` 6（`ErrorCode`、`ErrorMessages` 为第十五轮新增）`。

### Step 5-4 `DEVELOPMENT.md` §3.2 测试基线

- 后端行：`**202** 个用例 / 25 个测试类` → `**214** 个用例 / 26 个测试类`。
- 前端行：`**334** 个用例 / 33 个 spec` → `**336** 个用例 / 33 个 spec`。
- 分布段：`合计 202` → `合计 214`；`ErrorContractIntegrationTest` 5 → 7；新增
  `ErrorCodeIntegrationTest` 10（放在该列表末尾即可）；`合计 334` → `合计 336`；
  `index`（api）17 → 19。

### Step 5-5 `DEVELOPMENT.md` 新增 §4.20、更新 §5.2 与 §8

在 §4.19 结束处（`## 5. 待办与后续计划` 之前）插入：

```markdown
### 4.20 第十五轮：错误码与框架文案国际化（2026-11-06）

**背景**：第十四轮收尾后错误契约仍缺两件事：失败响应没有机器可读 `code`（前端只能匹配中文
`message`），以及 `GlobalExceptionHandler` 的 17 处文案硬编码中文、英文调用方没有可用提示。
本轮只加 `code` 与中英双语，不改状态码语义、不改业务文案、不加业务功能。

**改动**

| 文件 | 改动 |
| --- | --- |
| `exception/ErrorCode.java`（新） | 17 个错误码枚举，常量名即响应 `code`，携带 HTTP 状态与 `messageKey` |
| `exception/ErrorMessages.java`（新） | `MessageSource` 封装：`get` 走 `LocaleContextHolder`，`getForLocale` 供 Security 显式传 locale；只认 `en` 为英文，其余归一为中文 |
| `resources/messages.properties` / `messages_en.properties`（新） | 中/英文案；中文与第十四轮逐字一致 |
| `util/Result.java` | 新增可空 `code` 与三个 `fail(ErrorCode…)` 工厂；成功响应仍不含 `code` |
| `exception/GlobalExceptionHandler.java` | 全部处理器改用 `ErrorCode` + `ErrorMessages`；业务异常与字段校验文案保持原样；`IOException` 仍返回 null |
| `config/SecurityConfig.java` | 注入 `ObjectMapper`/`ErrorMessages`，401/403 用 `Result` 序列化（补 `code`、`timestamp`），不再手拼 JSON |
| `application.yml` | `spring.messages`：`basename=messages`、`encoding=UTF-8`、`fallback-to-system-locale=false` |
| `deploy/deploy.sh` | 冒烟新增 3 条断言：坏 JSON 带 `code=BAD_JSON`、默认中文、`Accept-Language: en` 英文 |
| `frontend/src/api/index.js` | 新增 `CODE_MESSAGES` 兜底与 `errorMessageOf`：`message` → `code` → `请求失败`/`网络错误` |

**错误码与文案**（完整表见本轮 spec；`message` 默认中文，`en` 时取英文）：

| 状态 | `code` | 中文 |
| --- | --- | --- |
| 400 | `BAD_JSON` | 请求体格式错误 |
| 400 | `VALIDATION_FAILED` | 参数校验失败（`data` 为字段 Map） |
| 400 | `BUSINESS_ERROR` | 业务异常原文（不翻译） |
| 400 | `MISSING_PARAMETER` / `TYPE_MISMATCH` | 缺少必需参数: X / 参数格式不正确: X |
| 400 | `UPLOAD_TOO_LARGE` / `MISSING_PART` / `MULTIPART_REQUIRED` | 图片不能超过 2MB / 缺少图片文件 / 请使用 multipart/form-data 上传图片 |
| 401 | `UNAUTHORIZED` / `BAD_CREDENTIALS` / `STREAM_TICKET_INVALID` | 未登录或登录已过期 / 用户名或密码错误 / 通知连接凭证无效或已过期 |
| 403 | `FORBIDDEN` | 无权限执行此操作 |
| 404 | `NOT_FOUND` | 资源不存在 |
| 405 | `METHOD_NOT_ALLOWED` | 请求方法不被支持（带 `Allow`） |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 不支持的 Content-Type |
| 429 | `TOO_MANY_ATTEMPTS` | 登录失败次数过多，请 N 分钟后再试（带 `Retry-After`） |
| 500 | `INTERNAL_ERROR` | 服务器内部错误，请稍后重试 |

**语言协商**：只有显式 `Accept-Language: en` 返回英文；缺省、`zh`、`fr` 与 JVM 默认一律中文。
原因：Security 过滤器在 MVC 之外只能 `request.getLocale()`，容器 JVM 默认 locale 常为 `en`，
不归一「默认中文」会在生产静默失效。`ErrorMessages.normalize` 只认 `en`。

**验证矩阵**

| # | 验证点 | 结果 |
| --- | --- | --- |
| V1 | 后端全量 `mvn -B clean test` | ✅ `Tests run: 214, Failures: 0, Errors: 0, Skipped: 1`，26 个测试类 |
| V2 | 前端 Vitest 336/33 | ✅ `Tests 336 passed (336)` / `Test Files 33 passed (33)` |
| V3 | 构建 / bundle 预算 | ✅ `VITE_BASE_PATH=/forum/ pnpm build` 通过；`check:bundle` 五项在预算内 |
| V4 | 坏 JSON→`BAD_JSON`、`text/plain`→`UNSUPPORTED_MEDIA_TYPE`、认证 `PUT`→`METHOD_NOT_ALLOWED`+`Allow: POST` | ✅ `ErrorCodeIntegrationTest` 真实 HTTP 覆盖 |
| V5 | `Accept-Language: en` 英文、默认/`fr` 中文 | ✅ `ErrorCodeIntegrationTest` 覆盖 |
| V6 | 匿名 `GET /api/auth/me` → 401 `UNAUTHORIZED` + `timestamp` | ✅ `ErrorCodeIntegrationTest.anonymousRequestReturnsUnauthorizedCodeFromSecurity` |
| V7 | 变异：改坏 `messages.properties` 的中文后测试必须红 | ✅ `ErrorCodeIntegrationTest` 至少 2 例失败；恢复后 10/10 绿 |
| V8 | 部署后公网复核 + 容器 ERROR 0（见本轮回填） | ⏳ 由 Task 6 回填 |

**构建记录**：`<T1>` 设计与计划 → `<T2>` 后端错误码与双语 → `<T3>` 测试守护（214/26）→
`<T4>` 前端兜底（336/33）→ 本提交记录 → 回填提交见下。

**已知限制**

1. 业务异常与 `@Valid` 字段文案仍为中文（本轮只框架级文案双语）。
2. `IOException`（客户端提前断开）仍不写响应。
3. 不设 `Content-Language`；语言只由请求头决定，且只认 `en`。
4. 前端 `CODE_MESSAGES` 是中文兜底表，不与后端文案资源联动（后端正常都会带 `message`）。
```

把 §5.2 最后一段（第十四轮候选）之后追加：

```markdown
> 第十五轮完成框架级错误码与双语文案（见 §4.20）：失败响应带 `code`，`message` 按
> `Accept-Language` 返回中文/英文（默认中文），前端按 `code` 兜底。新增候选：①业务异常与字段
> 校验文案翻译（需翻译词表与审核，属内容工作）；②按语言返回 `Content-Language` 或迁移 RFC 9457
> `ProblemDetail`（需前端同步解析）；③前端 UI 文案整体 i18n（当前只覆盖错误提示）。
```

§8 变更日志表最上方插入 5 行：T1–T4 的「提交」列填 `git log --oneline` 里的 7 位短哈希，
T5（本次提交）一行写「本次提交」（T6 会把它换成实际哈希）：

```markdown
| 2026-11-06 | docs | 新增第十五轮错误码与国际化设计与计划 | `<T1 哈希>` |
| 2026-11-06 | feat | 为失败响应增加机器可读错误码并支持中英文案 | `<T2 哈希>` |
| 2026-11-06 | test | 守护错误码与双语错误文案（214/26） | `<T3 哈希>` |
| 2026-11-06 | fix | 前端按错误码兜底展示并保留后端文案（336/33） | `<T4 哈希>` |
| 2026-11-06 | docs | 记录第十五轮错误码与国际化（§4.20、基线 214/26） | 本次提交 |
```

### Step 5-6 `docs/deployment/README.md` 冒烟速查

把旧的「错误契约速查（第十四轮）」三行（注释 + 两条 curl）替换为：

```bash
# 错误码与双语速查（第十五轮）：失败响应带 code；message 随 Accept-Language 变（默认中文）
curl -sS -H 'Content-Type: application/json' --data '{bad' http://127.0.0.1:8086/api/auth/login
# 期望 body 含 "code":"BAD_JSON" 与 "message":"请求体格式错误"
curl -sS -H 'Accept-Language: en' -H 'Content-Type: application/json' --data '{bad' http://127.0.0.1:8086/api/auth/login
# 期望 body 含 "message":"Malformed request body"
curl -sS -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: text/plain' --data 'x' http://127.0.0.1:8086/api/auth/login
# 期望 415
```

### Step 5-7 自检并提交

```bash
cd /e/JavaSpace/forum
grep -n 'code.*BAD_JSON' README.md | head -3
grep -n '4.20 第十五轮' docs/DEVELOPMENT.md
grep -n '当前为 26 个测试类、214 个用例' README.md
grep -n '336 个用例' README.md docs/DEVELOPMENT.md
grep -n 'Malformed request body' docs/deployment/README.md
git status --short
git add README.md docs/DEVELOPMENT.md docs/deployment/README.md
git commit -m "docs: 记录第十五轮错误码与国际化"
git log --oneline -1
```

预期：所有 grep 命中；提交后工作区干净。

---

## Task 6 构建、上线验收与回填（第 6/6 次提交）

### Step 6-1 本地终验（V1–V3、V7）

```bash
cd /e/JavaSpace/forum
mvn -B clean test 2>&1 | tail -20                       # V1：214 / 26 / 1 skip
cd frontend
pnpm test 2>&1 | tail -8                                 # V2：336 / 33
MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build 2>&1 | tail -5
pnpm check:bundle 2>&1 | tail -20                        # V3
cd ..
git status --short                                       # 预期为空
```

### Step 6-2 推送触发 Jenkins

```bash
cd /e/JavaSpace/forum
git log --oneline -6
git push origin master
```

预期：6 条本轮提交（含本次回填前的 5 条）出现在 `origin/master`；Jenkins 自动触发构建。
记录 Build 号与镜像 `forum-backend:<N>`（页面或 `podman images` 可见）。若 5 分钟未触发，
按 `docs/deployment/README.md` 的手工步骤触发，并在 §12 记录原因。

### Step 6-3 部署后内网冒烟（V4–V6、V8）

```bash
# 坏 JSON：默认中文
curl -sS -H 'Host: raspi' -H 'Content-Type: application/json' \
  --data '{bad' http://127.0.0.1/forum/api/auth/login
# 期望 {"success":false,"code":"BAD_JSON","message":"请求体格式错误","timestamp":...}

# Accept-Language: en
curl -sS -H 'Host: raspi' -H 'Accept-Language: en' -H 'Content-Type: application/json' \
  --data '{bad' http://127.0.0.1/forum/api/auth/login
# 期望 "message":"Malformed request body"

# text/plain → 415
curl -sS -o /dev/null -w '%{http_code}\n' -H 'Host: raspi' -X POST \
  -H 'Content-Type: text/plain' --data 'x' http://127.0.0.1/forum/api/auth/login
# 期望 415

# 匿名 401（Security 写出）：code + timestamp
curl -sS -H 'Host: raspi' http://127.0.0.1/forum/api/auth/me
# 期望 {"success":false,"code":"UNAUTHORIZED","message":"未登录或登录已过期","timestamp":...}

# 认证后 405（需要管理员 token）
TOKEN=$(curl -fsS -H 'Host: raspi' -H 'Content-Type: application/json' \
  -d "{\"username\":\"${SMOKE_USER:-admin}\",\"password\":\"${SMOKE_PASS:-admin123456}\"}" \
  http://127.0.0.1/forum/api/auth/login | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
curl -sS -o /dev/null -w '%{http_code} %header{allow}\n' -H 'Host: raspi' \
  -H "Authorization: Bearer $TOKEN" -X PUT http://127.0.0.1/forum/api/csp-report
# 期望 405 POST

# 容器 ERROR 计数（近 5 分钟）
podman ps --format '{{.Names}}' | grep -i forum
podman logs --since 5m "$(podman ps --format '{{.Names}}' | grep -i forum | head -1)" 2>&1 \
  | grep -c ' ERROR ' || true
# 期望 0
```

### Step 6-4 公网复核与 E2E（V7、V8）

```bash
BASE=https://treenewbee.men/forum
curl -sS -H 'Content-Type: application/json' --data '{bad' "$BASE/api/auth/login"
# 期望 code=BAD_JSON、message=请求体格式错误
curl -sS -H 'Accept-Language: en' -H 'Content-Type: application/json' --data '{bad' "$BASE/api/auth/login"
# 期望 message=Malformed request body
curl -sS -o /dev/null -w '%{http_code}\n' -H 'Content-Type: text/plain' --data 'x' "$BASE/api/auth/login"
# 期望 415
curl -sS "$BASE/api/auth/me"
# 期望 401 + code=UNAUTHORIZED + timestamp

cd /e/JavaSpace/forum/frontend
E2E_BASE_URL=https://treenewbee.men/forum/ E2E_ADMIN_USERNAME="${SMOKE_USER:-admin}" \
  E2E_ADMIN_PASSWORD="$SMOKE_PASS" E2E_CHANNEL=msedge pnpm test:e2e 2>&1 | tail -12
# 期望 8 passed
```

密码从本地运维记录/`.env` 取，不写进仓库。任一项不符即停，先排查再回填。

### Step 6-5 回填 `DEVELOPMENT.md` §4.20

把 §4.20 的「构建记录」段替换为实测（哈希与 Build 号照抄实际值）：

```markdown
**构建记录**：`<T1>` 设计与计划 → `<T2>` 后端错误码与双语 → `<T3>` 测试守护（214/26）→
`<T4>` 前端兜底（336/33）→ `<T5>` 文档记录 → 推送后 **Build #<N> SUCCESS**（checkout `<T5>`，
镜像 `forum-backend:<N>`）→ 本回填提交。

**上线验收（2026-11-06，Build #<N> / 镜像 `forum-backend:<N>`）**：内网（`Host: raspi`）坏 JSON →
400 `BAD_JSON` + `请求体格式错误`；`Accept-Language: en` → `Malformed request body`；`text/plain` →
415 `UNSUPPORTED_MEDIA_TYPE`；匿名 `GET /forum/api/auth/me` → 401 `UNAUTHORIZED` + `timestamp`；
认证 `PUT /api/csp-report` → 405 `METHOD_NOT_ALLOWED` + `Allow: POST`；容器近 5 分钟 ERROR 计数 0；
公网 HTTPS 同一套结果；公网 E2E **8 passed**。
```

把 V8 行的「⏳ 由 Task 6 回填」改为 `✅` 并写明实测结果。

再更新 `DEVELOPMENT.md` §8 变更日志（两处，缺一不可）：
① 把 T5 那行「提交」列的「本次提交」替换为 T5 的实际 7 位短哈希；
② 在表头分隔行下插入回填行（本轮最终提交仍写「本次提交」）：

```markdown
| 2026-11-06 | docs | 回填第十五轮构建与上线验收（Build #<N> / 镜像 <N>、V1–V8） | 本次提交 |
```

### Step 6-6 回填计划 §12

在本文档末尾追加（哈希/构建号/实测结果照抄）：

```markdown
## 12 实施记录与偏差（Task 6 回填）

| Task | 提交 | 与计划的偏差 |
| --- | --- | --- |
| T1 | `<hash>` | 无 |
| T2 | `<hash>` | 无 |
| T3 | `<hash>` | 无 |
| T4 | `<hash>` | 无 |
| T5 | `<hash>` | 无 |
| T6 | `<hash>` | 无 |

**验证实测**：V1 214/26/1 skip；V2 336/33；V3 构建 + 五项预算通过；V4–V6 内网/公网均符合；
V7 E2E 8/8；V8 Build #<N>、镜像 `<N>`、ERROR 0。

**审查/意外**：<如实记录；没有就写「无」>。
```

### Step 6-7 提交并推送

```bash
cd /e/JavaSpace/forum
git add docs/DEVELOPMENT.md docs/superpowers/plans/2026-11-06-round15-error-code-i18n.md
git commit -m "docs: 回填第十五轮构建与上线验收"
git push origin master
git log --oneline -3
git status --short
```

预期：`git status` 为空；`origin/master` 与本地一致。

---

## 7 文件清单（按 Task）

| Task | 新增 | 修改 |
| --- | --- | --- |
| T1 | spec、plan | — |
| T2 | `ErrorCode.java`、`ErrorMessages.java`、`messages.properties`、`messages_en.properties` | `Result.java`、`GlobalExceptionHandler.java`、`SecurityConfig.java`、`application.yml`、`deploy/deploy.sh` |
| T3 | `ErrorCodeIntegrationTest.java` | `ErrorContractIntegrationTest.java` |
| T4 | — | `frontend/src/api/index.js`、`frontend/src/api/index.spec.js` |
| T5 | — | `README.md`、`docs/DEVELOPMENT.md`、`docs/deployment/README.md` |
| T6 | — | `docs/DEVELOPMENT.md`、plan（§12） |

## 8 测试数账

| 项 | 基线 | 本轮 | 增量 |
| --- | --- | --- | --- |
| 后端用例 | 202 | 214 | +12（`ErrorCodeIntegrationTest` 10 + `ErrorContractIntegrationTest` 2） |
| 后端测试类 | 25 | 26 | +1 |
| 前端用例 | 334 | 336 | +2（`index.spec.js`） |
| 前端 spec | 33 | 33 | 0 |
| E2E | 8/5 | 8/5 | 0（手动，不计入基线） |

## 9 风险与应对

| # | 风险 | 应对 |
| --- | --- | --- |
| R1 | `Locale` 归一写错，中文请求拿到英文 | `normalize` 只认 `en`；单测覆盖 `en`/`fr`；deploy.sh 断默认中文；集成测试覆盖无头默认 |
| R2 | `messages*.properties` 编码/格式错误导致中文乱码或 `MessageFormat` 解析失败 | 文件 UTF-8；`spring.messages.encoding=UTF-8`；测试逐字断言中文；`mvn test` 覆盖全部 17 键的常用路径 |
| R3 | `Result` 全参构造改 5 参漏改调用点 | 实测 `new Result<>(` 只在 `Result.java` 内；`mvn compile` 兜底 |
| R4 | 旧前端把新增 `code` 当未知字段 | `code` 仅新增；`index.js` 只在 `message` 为空时读它 |
| R5 | Security 序列化异常导致 401 变 500 | `ObjectMapper` 由 Boot 自动配置；V6 直接覆盖匿名 401 |
| R6 | 405 的 `Allow` 丢失 | 保留原 `builder.allow(...)`；`ErrorCodeIntegrationTest` 断言 `contains("POST")` |
| R7 | 测试类超过 26 导致基线不符 | 新用例全部并入 1 个新类；`ls surefire-reports/*.txt | wc -l` 必须为 26 |
| R8 | 部署断言因 `set -o pipefail` 误杀 | 断言均为 `printf | grep -q`，不涉及命令替换返回码；改完 `bash -n` 自检 |

## 10 回滚

按逆序 `git revert` 本轮 6 个提交即可恢复第十四轮行为；无数据库迁移、无必需配置项
（`spring.messages` 缺失时 Boot 用默认值，旧镜像可继续运行）。回滚后重跑 V1/V2 确认回到 202/334。

## 11 Handoff（给执行者/审查者）

1. 从 Task 1 顺序执行；每个 Task 的验证不通过不得进入下一个 Task。
2. 所有「旧文本」替换必须确认唯一命中（`grep -c` 为 1），否则先修正定位再改。
3. 提交信息逐字对照附录 A；哈希写进 §8 变更日志与 §12。
4. T6 之前不要改 `docs/DEVELOPMENT.md` 的构建记录与 V8；T6 只回填实测值。
5. 若发现设计与实际代码不符（例如某 handler 已被删除），先按「实现现状 + 设计意图」做最小修正，
   并在 §12 记录偏差；不要静默改设计。

## 附录 A 提交信息（逐字，计划 6 条；另加计划外 `ad38754`，见 §12）

```text
docs: 新增第十五轮错误码与国际化设计与计划
feat: 为失败响应增加机器可读错误码并支持中英文案
test: 守护错误码与双语错误文案
fix: 前端按错误码兜底展示并保留后端文案
docs: 记录第十五轮错误码与国际化
docs: 回填第十五轮构建与上线验收
```

## 附录 B 发版前冒烟清单（B.1–B.7）

- **B.1 后端全量**：`cd /e/JavaSpace/forum && mvn -B clean test` → `Tests run: 215, Failures: 0,
  Errors: 0, Skipped: 1`；`ls target/surefire-reports/*.txt | wc -l` = 26。
- **B.2 前端全量**：`cd frontend && pnpm test` → `Test Files 33 passed`、`Tests 336 passed`。
- **B.3 构建预算**：`MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build` 成功；
  `pnpm check:bundle` 五项在预算内、退出码 0。
- **B.4 本地错误码**：坏 JSON → 400 `BAD_JSON`；`text/plain` → 415 `UNSUPPORTED_MEDIA_TYPE`；
  认证 `PUT /api/csp-report` → 405 `METHOD_NOT_ALLOWED` + `Allow: POST`；三者 body 均含 `timestamp`。
- **B.5 语言协商**：不带 `Accept-Language`（或 `fr`）→ 中文 `请求体格式错误`；
  `-H 'Accept-Language: en'` → `Malformed request body`。
- **B.6 匿名 401**：`GET /forum/api/auth/me` → 401，body 含 `"code":"UNAUTHORIZED"` 与 `"timestamp":`。
- **B.7 部署后**：内网（`Host: raspi`）与 `https://treenewbee.men/forum` 复核 B.4–B.6；
  `deploy.sh` 的 3 条新断言通过；容器近 5 分钟 `ERROR` 计数 0；公网 E2E 8 passed。

## 12 实施记录与偏差（Task 6 回填）

| Task | 提交 | 与计划的偏差 |
| --- | --- | --- |
| T1 | `344b664` | 无 |
| T2 | `ff2966e` | 无（代码逐字节一致）。执行手段偏差：`application.yml` / `deploy.sh` 用 heredoc 插入（awk 内嵌中文被本机 gawk 按 GBK 转码）；冒烟因无本地 PostgreSQL 用 H2 测试装配。 |
| T3 | `3ce8687` | 计划 Step 3-2 写「ErrorContractIntegrationTest 5→7 例」，实际替换后为 6 例，补第 7 例 `plainTextContentTypeReturnsEnglishMessageWhenRequested`（text/plain + en → 415 英文）；全量 214 / 26 类。 |
| T4 | `0306082` | 无（两例兜底测试 + 构建/预算符合预期，前端 336/33）。 |
| T5 | `0774de9` | 无（README 错误契约整段替换并补语言协商示例）。 |
| T6 | 本次回填提交 | 计划外追加 **`ad38754`** 修复「无 Accept-Language 时回退 JVM 默认 locale」缺陷；测试基线 214→**215**、`ErrorCodeIntegrationTest` 10→11。 |

**验证实测**：V1 后端 **215 / 26 类 / 1 skip**；V2 前端 **336 / 33 spec**；V3 构建 + `check:bundle`
五项预算通过（入口 84.43/150、CSS 39.02/70、单 chunk 234.28/300、JS 总量 836.41/950、CSS 总量 181.57/260）；
V4–V6 内网与公网 HTTPS 均符合（坏 JSON 400 `BAD_JSON`、`en` 英文、text/plain 415、匿名 401
`UNAUTHORIZED`+`timestamp`、认证 PUT 405 `METHOD_NOT_ALLOWED`+`Allow: POST`）；V7 公网 E2E **8 passed (1.1 m)**；
V8 Build **#49**（镜像 `forum-backend:49`）部署成功、容器 `ERROR` 计数 0、health 200。

**审查/意外**：

1. Task 2 质量审查判 **CHANGES_REQUESTED**：C1 —— 无 `Accept-Language` 时 `LocaleContextHolder` /
   `request.getLocale()` 回退 JVM 默认 locale，生产容器（temurin，`LANG=en_US.UTF-8`）默认返回英文，
   与「默认中文」目标相反；审查实测 en 默认 JVM 下 13 个既有用例变红，且 `deploy.sh` 的中文断言会在
   部署时失败。修复 `ad38754`：`spring.web.locale: zh_CN`（Boot 3 读 `spring.web.locale`，
   `spring.mvc.locale` 已不生效）、`SecurityConfig.explicitLocale()`（无头传 null）、
   `ErrorMessages.getMessage` 补 `code.name()` 缺键兜底，并新增守护用例
   `noAcceptLanguageStaysChineseEvenWhenJvmDefaultIsEnglish`（变异验证：去掉 `spring.web.locale` 该例红）。
   生产复现：**Build #48 FAILURE**，失败断言正是 `deploy.sh` 的「默认语言不是中文」。
2. 计划把 Task 3 的 ErrorContract 用例数写多 1（见 T3 偏差）。
3. Task 2 由 Lead 接手完成（实现队友 `impl-r15-t2` 中途空闲，工作区已有其未提交改动；Lead 按计划补完
   Step 2-5…2-9 并统一提交，`git status` 干净）。
