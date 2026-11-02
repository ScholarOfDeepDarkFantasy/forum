# 第十四轮：错误契约收尾设计（2026-11-03）

## 1. 背景

第十三轮引入公开的 CSP 上报端点后，终审发现两个与「异常到响应」有关的缺口：

1. **同族客户端错误仍会变成 500**：`GlobalExceptionHandler` 只为业务异常、参数校验、认证、限流、multipart、405 等单独配置了处理器，其余全部落到 `@ExceptionHandler(Exception.class)` → **500 + `log.error("未处理异常", e)` 堆栈**。其中 `HttpMessageNotReadableException`（请求体不是合法 JSON）与 `HttpMediaTypeNotSupportedException`（不支持的 `Content-Type`）属于典型的**客户端错误**，返回 500 既误导调用方，也会被任意匿名请求用来刷 ERROR 日志。
2. **405 的测试守护偏薄**：`CspReportIntegrationTest.nonPostMethodsReturn405InsteadOfServerError` 只覆盖 `PUT`，未断言响应体是 `Result` 结构、`Allow` 是否恰好为 `POST`、也未覆盖 `PATCH`/`DELETE` 与「不产生 ERROR 日志」。

此外第十三轮终审留下 6 条 Minor（部署手册 curl 示例被换行写坏、文档把「非 POST」写成一律 405、`public.conf` 陈旧注释、计划残留文字等），本轮顺手收尾。

## 2. 目标与非目标

**目标**

- `POST` 请求体不是合法 JSON → **400** + `Result.fail("请求体格式错误")`，不再 500、不再 `log.error`。
- 不支持的 `Content-Type` → **415** + `Result.fail("不支持的 Content-Type")`。
- 非 POST 方法（带认证）保持 **405 + `Allow: POST`**，并把测试扩成 `PUT`/`PATCH`/`DELETE` × `Result` 体 × `Allow` 精确 × 无 ERROR 日志。
- 用一章文档（`docs/DEVELOPMENT.md` §4.19）把全部状态码契约固定下来，部署手册补 400/415 验证命令。
- 修正第十三轮终审 6 条 Minor 中属于文档/注释的部分。

**非目标**

- 不重构异常体系、不引入 `ProblemDetail`/RFC 7807、不做文案国际化。
- 不改任何业务状态码语义（400/401/403/404/405/413/415/429/500 的既有含义全部保留）。
- 不改前端代码、不改 CSP 上报端点行为、不改 nginx 路由。
- 不新增依赖。

## 3. 现状审计

`src/main/java/org/song/forum/exception/GlobalExceptionHandler.java`（单文件、唯一 `@RestControllerAdvice`）现有处理器：

| 异常 | 状态 | 说明 |
|---|---|---|
| `BusinessException` | 400 | 业务错误 |
| `MethodArgumentNotValidException` | 400 | `@Valid` 字段校验，返回字段错误表 |
| `BadCredentialsException` | 401 | 登录失败 |
| `AuthenticationException` | 401 | 其它认证失败 |
| `IllegalStateException` | 401 / 400 | 消息含「未登录」→401，否则 400（状态冲突不是 409） |
| `MissingServletRequestParameterException` | 400 | 缺 query 参数（已有） |
| `MethodArgumentTypeMismatchException` | 400 | query 参数类型不符（已有） |
| `MaxUploadSizeExceededException` / `MissingServletRequestPartException` / `MultipartException` | 400 / 400 / 400 | 上传相关（超限文案 `图片不能超过 2MB`；**413 只由 nginx `client_max_body_size` 产生**） |
| `AccessDeniedException` | 403 | 权限不足 |
| `NoSuchElementException` | 404 | 资源不存在 |
| `InvalidStreamTicketException` | 401 | SSE 票据 |
| `TooManyLoginAttemptsException` | 429 + `Retry-After` | 登录限流 |
| `HttpRequestMethodNotSupportedException` | 405 + `Allow` | 第十三轮新增 |
| `IOException` | 返回 null/不写响应 | 客户端断开 |
| `Exception` | **500 + ERROR 堆栈** | 兜底 |

缺口：`HttpMessageNotReadableException`、`HttpMediaTypeNotSupportedException` 没有专属处理器 → 落兜底。

**实测（计划 Task 2 中作为 RED 记录）**：对 `/api/auth/login` 发坏 JSON 或 `text/plain`，当前分别得到 `500`（并打 `ERROR 未处理异常`）。

## 4. 方案

### R1 新增两个客户端错误处理器（放在 multipart 之后、`AccessDeniedException` 之前）

```java
/** 请求体无法解析（坏 JSON / 类型不匹配）→ 400 */
@ExceptionHandler(HttpMessageNotReadableException.class)
public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException e) {
    log.debug("请求体无法解析: {}", e.getMessage());
    return ResponseEntity.badRequest().body(Result.fail("请求体格式错误"));
}

/** Content-Type 不受支持 → 415 */
@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
public ResponseEntity<Result<Void>> handleUnsupportedMediaType(
        HttpMediaTypeNotSupportedException e) {
    log.debug("不支持的 Content-Type: {}", e.getContentType());
    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .body(Result.fail("不支持的 Content-Type"));
}
```

- 用 `debug` 而不是 `warn/error`：坏请求可由匿名方任意构造，不应贡献可刷的日志；排障时把 `org.song.forum` 调到 DEBUG 即可看到。
- 保留 405 现有处理器不动（`Allow` 由 `e.getSupportedHttpMethods()` 生成）。

### R2 405 测试加固（`CspReportIntegrationTest`）

把 `nonPostMethodsReturn405InsteadOfServerError` 扩展为：

- `for (HttpMethod method : List.of(PUT, PATCH, DELETE))`，带合法 JWT；
- 断言状态 405、`Allow` **恰好等于** `POST`、响应体是 `Result`（`"success":false`、`"message":"请求方法不被支持"`、含 `timestamp`）；
- 断言 `GlobalExceptionHandler` logger 在整段请求期间**没有 ERROR 事件**（复用现有 `ListAppender` 模式，attach 到 `GlobalExceptionHandler.class`）；
- 保留匿名 `PUT` → 401 的前置断言。

### R3 新增 `ErrorContractIntegrationTest`（5 例）

| 用例 | 请求 | 期望 |
|---|---|---|
| `malformedJsonToLoginReturns400` | `POST /api/auth/login`，`application/json`，body `{"username":` | 400、`Result`、message `请求体格式错误` |
| `malformedJsonToRegisterReturns400` | `POST /api/auth/register`，坏 JSON | 400 |
| `jsonArrayToLoginReturns400` | body `[]` | 400（Jackson 反序列化失败，不是 500） |
| `plainTextContentTypeReturns415` | `text/plain` + `{"username":"a","password":"b"}` | 415、message `不支持的 Content-Type` |
| `emptyJsonObjectStillReturns400ByValidation` | body `{}` | 400（锁定 `@Valid` 契约，非 415/500） |

每个用例都断言响应体是 `Result` 结构（`success=false` 且含 `message`/`timestamp`）。

### R4 文档：错误响应契约表（`docs/DEVELOPMENT.md` §4.19）

固定下表（状态码 / 触发条件 / 文案 / 额外头）：

| 状态 | 触发 | 文案（`Result.message`） | 额外头 |
|---|---|---|---|
| 400 | 业务规则、`@Valid` 失败、缺/错 query 参数、**坏 JSON**、上传超过 2MB | 业务文案 / 字段错误 / `请求体格式错误` / `图片不能超过 2MB` | — |
| 401 | 未登录、token 失效/过期、登录密码错、SSE 票据无效 | `未登录或登录已过期` / `用户名或密码错误`（`请先登录` 是前端 401 拦截器文案，非后端 body） | — |
| 403 | 角色不足 | `无权限执行此操作` | — |
| 404 | 资源不存在 | `...不存在` | — |
| 405 | **认证后** 方法不允许（`PUT/PATCH/DELETE/HEAD`；匿名先被 Security 拦成 401） | `请求方法不被支持` | `Allow: POST`（OPTIONS 由 Spring 返回 200） |
| 415 | `Content-Type` 不受支持 | `不支持的 Content-Type` | — |
| 429 | 登录失败次数超限 | `登录失败次数过多，请 N 分钟后再试` | `Retry-After` |
| 500 | 未预期异常（唯一兜底） | `服务器内部错误，请稍后重试` | — |

补充说明：经 `@RestControllerAdvice`/业务异常返回的响应体统一为 `{"success":false,"message":"…","timestamp":…}`；Security 过滤器直接写出的 401/403 无 `timestamp`；nginx 的 413 是 HTML，不属本契约。400/401 之外不泄露堆栈。

### R5 第十三轮 Minor 修正

- `docs/deployment/README.md` 合成上报 `curl` 示例合并为单行（`-w '%{http_code}\n'`、`\` 续行或整行）。
- `docs/DEVELOPMENT.md` §4.18 与十三轮计划中「非 POST 统一 405」改为「`PUT/PATCH/DELETE` → 405 + `Allow: POST`；`OPTIONS` 由 Spring 返回 200（`Allow: POST,OPTIONS`）；`HEAD` → 405」。
- `deploy/nginx/public.conf:39-40` 陈旧注释改为「uploads 的 4 个安全头下发在 `forum.conf` 的 location 里（本文件只 include snippet）」。
- 十三轮计划残余「（待回填）」与 `cleanSample` 描述改为完成态措辞。

## 5. 影响面与回归

- 后端：只新增两个 `@ExceptionHandler`，且只对「原本返回 500」的异常生效；已有处理器优先级更高，行为不变。
- 前端：完全没有代码改动；前端错误处理已按 `message` 展示，400/415 与现有 400 同构。
- 部署/nginx：不改路由；仅改一条注释。
- CSP 上报：端点仍恒 204（坏 JSON 在服务内部被容忍），本轮不影响其语义。
- 测试基线：后端 197 → **202**（+5，新类 1 个 → 25 类）、前端 334/33 不变、E2E 8/8 不变。

## 6. 验证矩阵

| ID | 验证点 | 命令/方式 | 期望 |
|---|---|---|---|
| V1 | 后端全量 | `mvn -B clean test` | `Tests run: 202, Failures: 0, Errors: 0, Skipped: 1`，25 类 |
| V2 | 前端全量 | `cd frontend && pnpm test` | 33 spec / 334 tests |
| V3 | 构建与预算 | `MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build && pnpm check:bundle` | 构建成功、五项预算内 |
| V4 | 本地错误契约 | `curl` 坏 JSON / text/plain / 认证 PUT | 400 / 415 / 405 + `Allow: POST` |
| V5 | 部署后同 V4（经 nginx `Host: raspi`） | 同上 | 一致，且容器 `ERROR` 日志为 0 |
| V6 | 回归 | 登录 → me → logout、E2E 8/8 | 全过 |
| V7 | 405 守护有效性 | 变异：把 `HttpRequestMethodNotSupportedException` 处理器注释掉 | `CspReportIntegrationTest` 405 用例变红 |

## 7. 风险与回滚

- 风险 1：`HttpMessageNotReadableException` 也覆盖「JSON 字段类型不匹配」（如 `"boardId": "abc"`），现在 400 而非 500 —— 这是**期望**修正，但可能掩盖真实序列化 bug；缓解：handler 用 `debug` 打原始消息，排障可开。
- 风险 2：`ErrorContractIntegrationTest` 依赖登录端点匿名可访问；若未来收紧会红，属预期守护。
- 回滚：`git revert` 本轮两个提交即可；无数据/配置变更，不涉及迁移与 nginx。

## 8. 文档改动

- `README.md`：新增「### 错误响应契约」小节（上表精简版 + `Result` 结构示例），测试基线改 202/25。
- `docs/DEVELOPMENT.md`：新增 §4.19（背景/改动表/错误契约表/测试矩阵/已知限制/上线验收），§3.1/§3.2 数字更新，§4.18 的 OPTIONS 口径修正，§8 变更日志 4 行。
- `docs/deployment/README.md`：curl 示例修正 + 「错误码速查」两行（400/415 何时出现）。
- 计划 `docs/superpowers/plans/2026-11-03-round14-error-contract.md`：§12 实施记录与偏差。

## 9. 上线验收

- Build 号与镜像号、V1–V7 实测结果、经公网的 400/415/405 冒烟、`ERROR` 日志计数。
- 已知限制：错误文案未国际化；415 文案含英文 `Content-Type`；`IOException`（客户端断开）仍不写响应。
