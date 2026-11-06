# 第十五轮：错误码与框架文案国际化设计（2026-11-06）

> 错误契约二期：只加机器可读 `code` 与框架级文案中英双语，不加业务功能。
> 第十四轮已把坏 JSON / 415 / 405 收敛成 4xx 并固定状态码语义；本轮**不改任何状态码与中文文案**。

## 1. 背景

第十四轮后错误契约仍有四个已知限制（`docs/DEVELOPMENT.md` §4.19「已知限制」）：

1. **失败响应无机器可读错误码**：400 同时承载业务规则、字段校验、坏 JSON、缺参数、上传超限等语义，
   前端只能匹配中文 `message`，文案一改就断。
2. **错误文案硬编码中文**：`GlobalExceptionHandler` 里 17 处字符串字面量，英文调用方没有可用提示。
3. **Security 直写的 401/403 无 `code` 无 `timestamp`**，与 MVC 统一 `Result` 结构不一致。
4. **前端只能吃后端中文**，没有「后端只给 code」时的兜底。

## 2. 目标与非目标

**目标**

- 失败响应增加 `code`（枚举常量名，如 `BAD_JSON`）；成功响应不含 `code`（`NON_NULL` 省略）。
- 新建 `ErrorCode`（17 常量，带 HTTP 状态与文案键）；`Result` 增加三个 `fail(ErrorCode…)` 工厂。
- `messages.properties`（默认中文，**逐字保留现状**）+ `messages_en.properties`（英文）；
  `spring.messages.basename=messages`、`encoding=UTF-8`、`fallback-to-system-locale=false`；
  新增 `ErrorMessages` 统一解析。
- 语言判定：只有**显式** `Accept-Language: en` 才英文，缺省/`zh`/`fr`/JVM 默认一律中文
  （容器 JVM locale 常为 `en`，不归一就会让无头 `curl` 拿到英文）。
- Security 401/403 用 `ObjectMapper` 写出含 `code`/`timestamp` 的 `Result`。
- 前端 `src/api/index.js` 加 `CODE_MESSAGES` 兜底：`message` → `code` → `请求失败`/`网络错误`；
  401「请先登录」分支不变；不引 i18n 库。
- 测试：后端 202 → **214**（25 → 26 类），前端 334 → **336**（33 spec 不变）。

**非目标**：不翻译业务异常与 `@Valid` 字段文案；不改状态码；不引 `ProblemDetail`/`Content-Language`；
不做前端 UI 多语言切换；不动 DB/Flyway/nginx/部署逻辑（仅 `deploy.sh` 加断言）；不新增依赖。

## 3. 现状审计（行号为本轮实测）

| 位置 | 现状 | 问题 |
|---|---|---|
| `util/Result.java:18-21` | 字段 `success/message/data/timestamp` | 失败响应无 `code` |
| `util/Result.java:35-41` | `fail(String)`/`fail(String,T)` 手拼 4 参构造 | 无 `ErrorCode` 工厂 |
| `exception/GlobalExceptionHandler.java:36-40` | 业务异常 400 `e.getMessage()` | 无 code，文案不翻译 |
| 同上 `:43-50` | 校验失败 400 `参数校验失败` + 字段 Map | 硬编码 |
| 同上 `:57-66` | 坏凭据 401 `e.getMessage()`；`AuthenticationException` 401 `未登录或登录已过期` | 硬编码 |
| 同上 `:69-75` | `IllegalStateException` 含「未登录」→401，否则 400 `e.getMessage()` | 硬编码 |
| 同上 `:81-95` | 缺参数 400 `缺少必需参数: X`；类型不符 400 `参数格式不正确: X` | 字符串拼接 |
| 同上 `:98-119` | 上传超限/缺 part/非 multipart →400 三条文案 | 硬编码 |
| 同上 `:122-135` | 坏 JSON 400 `请求体格式错误`；415 `不支持的 Content-Type` | 硬编码 |
| 同上 `:138-147` | 403 `无权限执行此操作`；404 `资源不存在` | 硬编码 |
| 同上 `:154-173` | SSE 票据 401 `e.getMessage()`；429 `e.getMessage()` + `Retry-After` | 文案在异常类里拼 |
| 同上 `:181-208` | `IOException`→null；405 `请求方法不被支持`+`Allow`；兜底 500 | 硬编码 |
| `config/SecurityConfig.java:77-92` | 手拼 401/403 JSON，仅 `success`+`message` | 无 code/timestamp |
| `resources/application.yml:1-38` | `spring:` 无 `messages` | 无 i18n 配置 |
| 仓库全局 | 无 `messages*.properties` / `MessageSource` / `LocaleContextHolder` | 无 i18n 基础设施 |
| `frontend/src/api/index.js:40-76` | 成功分支 `res.message \|\| '请求失败'`；错误分支 `data.message \|\| error.message \|\| '网络错误'` | 无 code 兜底 |
| `controller/ErrorContractIntegrationTest.java` | 5 例，断言 `success`/`message`/`timestamp`、无 ERROR | 未断言 code |
| `controller/CspReportIntegrationTest.java:196-232` | 405 用例断言 `Allow: POST`、文案、无 ERROR | 本轮不改，靠新类覆盖 code |

## 4. 方案

### R1 `ErrorCode` 枚举（`org.song.forum.exception.ErrorCode`）

常量名即响应 `code`；`httpStatus` 决定状态，`messageKey` 指向资源。状态统一由枚举给出，
避免「枚举写 400、handler 写 401」。

| 常量 | HTTP | `messageKey` | 中文（逐字保留） | 英文（新增） |
|---|---|---|---|---|
| `BAD_JSON` | 400 | `error.bad-json` | 请求体格式错误 | Malformed request body |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | `error.unsupported-media-type` | 不支持的 Content-Type | Unsupported content type |
| `METHOD_NOT_ALLOWED` | 405 | `error.method-not-allowed` | 请求方法不被支持 | HTTP method not allowed |
| `VALIDATION_FAILED` | 400 | `error.validation-failed` | 参数校验失败 | Validation failed |
| `UNAUTHORIZED` | 401 | `error.unauthorized` | 未登录或登录已过期 | Authentication required or session expired |
| `BAD_CREDENTIALS` | 401 | `error.bad-credentials` | 用户名或密码错误 | Invalid username or password |
| `FORBIDDEN` | 403 | `error.forbidden` | 无权限执行此操作 | You do not have permission to perform this action |
| `NOT_FOUND` | 404 | `error.not-found` | 资源不存在 | Resource not found |
| `UPLOAD_TOO_LARGE` | 400 | `error.upload-too-large` | 图片不能超过 2MB | Image exceeds the 2MB limit |
| `MISSING_PART` | 400 | `error.missing-part` | 缺少图片文件 | Image file is required |
| `MULTIPART_REQUIRED` | 400 | `error.multipart-required` | 请使用 multipart/form-data 上传图片 | Upload must use multipart/form-data |
| `MISSING_PARAMETER` | 400 | `error.missing-parameter` | 缺少必需参数: {0} | Missing required parameter: {0} |
| `TYPE_MISMATCH` | 400 | `error.type-mismatch` | 参数格式不正确: {0} | Invalid parameter format: {0} |
| `STREAM_TICKET_INVALID` | 401 | `error.stream-ticket-invalid` | 通知连接凭证无效或已过期 | Notification stream ticket is invalid or expired |
| `TOO_MANY_ATTEMPTS` | 429 | `error.too-many-attempts` | 登录失败次数过多，请 {0} 分钟后再试 | Too many login attempts. Try again in {0} minute(s) |
| `INTERNAL_ERROR` | 500 | `error.internal` | 服务器内部错误，请稍后重试 | Internal server error. Please try again later |
| `BUSINESS_ERROR` | 400 | `error.business` | 业务处理失败（仅缺省兜底） | Business rule violation |

> 后三个带参键用 `MessageFormat` `{0}`：参数名、字段名、分钟数由 handler 传入，不在代码里拼中文。

### R2 `Result` 增加可空 `code`

字段顺序 `success; code; message; data; timestamp`（全参构造改 5 参数）。`NON_NULL` 保证：

```json
{"success":true,"message":"ok","data":{…},"timestamp":1730000000000}
{"success":false,"code":"BAD_JSON","message":"请求体格式错误","timestamp":1730000000000}
```

保留全部旧工厂（`code=null`），新增：

```java
public static <T> Result<T> fail(ErrorCode code);                          // message=code.name()，非 Web 兜底
public static <T> Result<T> fail(ErrorCode code, String message);           // handler 使用
public static <T> Result<T> fail(ErrorCode code, String message, T data);   // 校验失败（字段 Map）
```

实测全仓库 `new Result<>(` 只出现在 `Result.java:24/28/36/40`，改构造无外部扩散；
`util.Result` 只依赖枚举名，无运行期循环。

### R3 文案资源与 `ErrorMessages`

`messages.properties`（中文）与 `messages_en.properties`（英文）键值即 R1；`application.yml` 增加：

```yaml
  messages:
    basename: messages
    encoding: UTF-8
    fallback-to-system-locale: false
```

`org.song.forum.exception.ErrorMessages`（`@Component`，构造注入 `MessageSource`）：

- `get(ErrorCode, Object... args)` → locale 取 `LocaleContextHolder.getLocale()`（MVC 路径）。
- `getForLocale(ErrorCode, Locale, Object... args)` → 显式 locale（Security 过滤器路径）。
- `normalize`：**只有 `en` 返回 `Locale.ENGLISH`，其余一律 `Locale.SIMPLIFIED_CHINESE`**。原因：Security
  没有 MVC locale 上下文，只能 `request.getLocale()`；无 `Accept-Language` 时它返回 JVM 默认 locale
  （容器常为 `en`），不归一「默认中文」会在生产静默变英文。`zh`/`fr`/null 同样落中文。

### R4 handler 映射（保持现有日志级别与 `Allow` 头）

- 业务/状态：`BusinessException`→`BUSINESS_ERROR`（`e.getMessage()` 不翻译）；`IllegalStateException`
  含「未登录」→`UNAUTHORIZED`，否则→`BUSINESS_ERROR`；`AuthenticationException`→`UNAUTHORIZED`；
  `BadCredentialsException`→`BAD_CREDENTIALS`；`AccessDeniedException`→`FORBIDDEN`；
  `NoSuchElementException`→`NOT_FOUND`。
- 请求：`HttpMessageNotReadableException`→`BAD_JSON`；`HttpMediaTypeNotSupportedException`→
  `UNSUPPORTED_MEDIA_TYPE`；`HttpRequestMethodNotSupportedException`→`METHOD_NOT_ALLOWED`（保留 `Allow`）；
  `MissingServletRequestParameterException`→`MISSING_PARAMETER`（传参数名）；
  `MethodArgumentTypeMismatchException`→`TYPE_MISMATCH`（传参数名）；
  `MethodArgumentNotValidException`→`VALIDATION_FAILED`（字段 Map 作为 `data`）。
- 上传：`MaxUploadSizeExceededException`→`UPLOAD_TOO_LARGE`；`MissingServletRequestPartException`→
  `MISSING_PART`；`MultipartException`→`MULTIPART_REQUIRED`。
- 凭证/限流/兜底：`InvalidStreamTicketException`→`STREAM_TICKET_INVALID`；
  `TooManyLoginAttemptsException`→`TOO_MANY_ATTEMPTS`（分钟数 `Math.max(1,(retryAfter+59)/60)` 由 handler 算）；
  `Exception`→`INTERNAL_ERROR`（`log.error`）；`IOException` 仍返回 null。

### R5 Security 401/403

`SecurityConfig` 注入 `ObjectMapper` 与 `ErrorMessages`，新增私有方法：

```java
private void writeError(HttpServletResponse response, ErrorCode code, Locale locale) throws IOException {
    response.setStatus(code.getHttpStatus().value());
    response.setContentType("application/json;charset=UTF-8");
    objectMapper.writeValue(response.getWriter(), Result.fail(code, errorMessages.getForLocale(code, locale)));
}
```

401→`UNAUTHORIZED`、403→`FORBIDDEN`，locale 传 `request.getLocale()`；body 与 MVC 同构，不再手拼字符串。

### R6 前端兜底（`frontend/src/api/index.js`）

```js
const CODE_MESSAGES = {
  BAD_JSON: '请求体格式错误', BAD_CREDENTIALS: '用户名或密码错误',
  UNAUTHORIZED: '未登录或登录已过期', FORBIDDEN: '无权限执行此操作',
  NOT_FOUND: '资源不存在', UNSUPPORTED_MEDIA_TYPE: '不支持的 Content-Type',
  METHOD_NOT_ALLOWED: '请求方法不被支持', TOO_MANY_ATTEMPTS: '操作过于频繁，请稍后再试',
  INTERNAL_ERROR: '服务器内部错误，请稍后重试'
}
function errorMessageOf(data) {
  if (data && typeof data === 'object') {
    if (data.message) return data.message
    if (data.code && CODE_MESSAGES[data.code]) return CODE_MESSAGES[data.code]
  }
  return ''
}
```

- 成功分支 `success:false`：`errorMessageOf(res) || '请求失败'`。
- 错误分支：`errorMessageOf(error.response?.data) || (error.response?.data ? '请求失败' : error.message || '网络错误')`。
- 401 非凭据接口仍清 `jf_token`/`jf_user`、提示「请先登录」、跳 `LOGIN_PATH`；凭据接口透传后端文案。

## 5. 影响面与回归

- 失败响应多 `code`；成功响应不变；Security 401/403 多 `code` 与 `timestamp`。
- `code` 是新增字段，旧前端忽略；`message` 中文逐字不变，现有提示与 E2E 文案不回归。
- 测试：新增 1 类 `ErrorCodeIntegrationTest`（10 例）+ `ErrorContractIntegrationTest` +2 例；
  后端 214/26、前端 336/33、E2E 8/8。
- 日志：`ERROR` 仍只来自兜底；`deploy.sh` 新断言只发坏 JSON（400，无 ERROR）。
- 部署：`deploy.sh` 加 3 条契约断言；nginx/DB/镜像构建方式不变。

## 6. 验证矩阵（V1–V8）

| ID | 验证点 | 方式 | 期望 |
|---|---|---|---|
| V1 | 后端全量 | `mvn -B clean test` | 214 tests / 26 类 / 0 失败 / 1 skip |
| V2 | 前端全量 | `cd frontend && pnpm test` | 33 spec / 336 tests |
| V3 | 构建预算 | `MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build && pnpm check:bundle` | 成功且五项预算内 |
| V4 | 本地错误码 | 坏 JSON / `text/plain` / 认证 `PUT /api/csp-report` | 400 `BAD_JSON`、415 `UNSUPPORTED_MEDIA_TYPE`、405 `METHOD_NOT_ALLOWED`+`Allow: POST` |
| V5 | 语言协商 | 同 V4 带与不带 `Accept-Language: en` | `Malformed request body` / `请求体格式错误` |
| V6 | 匿名 401 | `GET /api/auth/me` | 401 body 含 `UNAUTHORIZED` 与 `timestamp` |
| V7 | 回归 | 登录→`/me`→登出、E2E 8/8 | 全过 |
| V8 | 部署后 | nginx（`Host: raspi`）与公网 HTTPS 复核 V4–V6；容器 ERROR 计数 | 一致，ERROR 0 |

## 7. 风险与回滚

- **Locale 归一写错**会把中文环境判成英文 → 归一只认 `en`；集成测试覆盖 `en`/`fr`/无头默认中文；
  `deploy.sh` 再断一次默认中文。
- **资源编码乱码** → `spring.messages.encoding=UTF-8` + 文件 UTF-8，测试断言中文原文。
- **`Result` 构造改参** → 实测无外部 `new Result<>(` 调用，且保留全部旧工厂。
- **Security 注入 `ObjectMapper` 失败** → Spring Boot 自动配置保证存在；V6 走匿名 401 验证。
- **回滚**：`git revert` 本轮 6 个提交；无 DB 迁移、无必需配置项（`spring.messages` 有默认值）。

## 8. 文档改动

- `README.md`：错误契约增加 `code` 与双语说明/curl；基线改 214/26、336/33。
- `docs/DEVELOPMENT.md`：§3.1/§3.2 数字与类分布；新增 §4.20；§5.2 候选；§8 变更日志 6 行。
- `docs/deployment/README.md`：冒烟「错误码与双语速查」三条 curl。
- `deploy/deploy.sh`：冒烟段 3 条断言。
- 本轮 plan：§12 实施记录与偏差（T6 回填）。

## 9. 上线验收与已知限制

- Build/镜像号、V1–V8 实测、公网与内网（`Host: raspi`）的 400/415/405、`code`、中英文案、
  匿名 401 的 `timestamp`；容器近 5 分钟 ERROR 0；公网 E2E 8/8。
- 已知限制：业务异常与字段校验文案仍中文；`IOException` 仍不写响应；不设 `Content-Language`；
  语言只由请求头决定（只认 `en`，其余中文）。
