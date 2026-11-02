# 第十四轮实现计划：错误契约收尾（2026-11-03）

> 权威规格：`docs/superpowers/specs/2026-11-03-round14-error-contract-design.md`。
> 本文件只描述怎么落地；冲突时以 spec 为准，并在提交说明与 §12 记录偏差。

## 0. 执行约定

- 仓库：`E:\JavaSpace\forum`（Git Bash `/e/JavaSpace/forum`），master 直接提交，不建分支、不 amend、不 rebase。
- 本轮共 **4 次提交**（顺序如下），每次提交后 `git status` 必须干净：
  1. `docs: 新增第十四轮错误契约收尾设计与计划`（Task 1）
  2. `fix: 收敛客户端错误映射并加固 405 守护`（Task 2）
  3. `docs: 记录第十四轮错误契约收尾`（Task 3）
  4. `docs: 回填第十四轮构建与上线验收`（Task 4）
- 提交信息逐字使用附录 A。
- 每次跑后端测试前确认没有并发 `mvn`；审查/复跑一律在 `/c/Temp` 一次性副本里做（`git -c core.autocrlf=false archive <commit> | tar -x -C ...`）。
- 前端命令：`cd /e/JavaSpace/forum/frontend`；构建必须带 `MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH`。
- 不改前端代码、不改 nginx 路由、不改 CSP 端点语义。

## 1. 现状与基线

- HEAD = `d52751a`（第十三轮终态），工作区干净，远端同步。
- 后端基线：`Tests run: 197, Failures: 0, Errors: 0, Skipped: 1`，24 个测试类。
- 前端基线：33 spec / 334 tests；E2E 8/8；`check:bundle` 五项预算内。
- 缺口服确认：`src/main/java/org/song/forum/exception/GlobalExceptionHandler.java`
  - 现有 405 处理器：`handleMethodNotSupported(HttpRequestMethodNotSupportedException)`（约 :170-183）
  - 兜底：`@ExceptionHandler(Exception.class)` → `log.error("未处理异常", e)` + 500（约 :185-189）
  - `HttpMessageNotReadableException` / `HttpMediaTypeNotSupportedException` **没有**专属处理器。

---

## Task 1：设计与计划文档

**Files**
- Create `docs/superpowers/specs/2026-11-03-round14-error-contract-design.md`（已写好）
- Create `docs/superpowers/plans/2026-11-03-round14-error-contract.md`（本文件）

- [ ] **Step 1-1** 确认两份文件存在且 `git status` 只有这两个 `??`。
- [ ] **Step 1-2** `git add` 两份文件，提交 `docs: 新增第十四轮错误契约收尾设计与计划`。
- [ ] **Step 1-3** 验证：`git log --oneline -1` 信息逐字一致；`git status --short` 为空。

---

## Task 2：错误映射与 405 守护（TDD）

**Files**
- Modify `src/main/java/org/song/forum/exception/GlobalExceptionHandler.java`
- Modify `src/test/java/org/song/forum/controller/CspReportIntegrationTest.java`
- Create `src/test/java/org/song/forum/controller/ErrorContractIntegrationTest.java`

### Step 2-1 先写测试（RED）

**新增类**（可整段粘贴，包名/导入按此）：

```java
package org.song.forum.controller;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误响应契约：坏请求必须是 4xx + Result，而不是 500 + ERROR 堆栈。
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

    @Test
    void malformedJsonToLoginReturns400() {
        ResponseEntity<String> response = postJson("/api/auth/login", "{\"username\":");

        assertResultError(response, HttpStatus.BAD_REQUEST, "请求体格式错误");
    }

    @Test
    void malformedJsonToRegisterReturns400() {
        ResponseEntity<String> response = postJson("/api/auth/register", "not-a-json");

        assertResultError(response, HttpStatus.BAD_REQUEST, "请求体格式错误");
    }

    @Test
    void jsonArrayToLoginReturns400() {
        ResponseEntity<String> response = postJson("/api/auth/login", "[]");

        assertResultError(response, HttpStatus.BAD_REQUEST, "请求体格式错误");
    }

    @Test
    void plainTextContentTypeReturns415() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        ResponseEntity<String> response = rest.exchange(
                url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"a\",\"password\":\"b\"}", headers), String.class);

        assertResultError(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "不支持的 Content-Type");
    }

    @Test
    void emptyJsonObjectReturns400ByValidation() {
        ResponseEntity<String> response = postJson("/api/auth/login", "{}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"success\":false");
        assertThat(response.getBody()).contains("\"timestamp\":");
    }

    private ResponseEntity<String> postJson(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private void assertResultError(ResponseEntity<String> response, HttpStatus status, String message) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody())
                .contains("\"success\":false")
                .contains("\"message\":\"" + message + "\"")
                .contains("\"timestamp\":");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
```

**扩展 405 用例**：把 `CspReportIntegrationTest.nonPostMethodsReturn405InsteadOfServerError` 的「认证后 PUT」段替换为循环 + 精确断言 + 无 ERROR 日志。改动要点：

- 类顶部新增 `private Logger exceptionLogger;`，并在 `attachLogAppender()`/`detachLogAppender()` 中同时挂/摘 `GlobalExceptionHandler.class`（新 `ListAppender`，命名 `exceptionAppender`）。
- 认证 PUT 段替换为：

```java
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        for (HttpMethod method : List.of(HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            ResponseEntity<String> response = rest.exchange(
                    url("/api/csp-report"), method, new HttpEntity<>("{}", headers), String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(response.getHeaders().getFirst(HttpHeaders.ALLOW)).isEqualTo("POST");
            assertThat(response.getBody())
                    .contains("\"success\":false")
                    .contains("\"message\":\"请求方法不被支持\"")
                    .contains("\"timestamp\":");
        }
        assertThat(exceptionAppender.list)
                .noneMatch(event -> event.getLevel() == Level.ERROR);
```

- [ ] **Step 2-2** 跑 RED 并保存原文：
  ```bash
  cd /e/JavaSpace/forum
  mvn -B test -Dtest=ErrorContractIntegrationTest -q 2>&1 | tail -40
  ```
  期望（修复前）：`malformedJsonToLoginReturns400` / `malformedJsonToRegisterReturns400` / `jsonArrayToLoginReturns400` / `plainTextContentTypeReturns415` **失败**（实际 500 而非 400/415）；`emptyJsonObjectReturns400ByValidation` 通过。
  再跑 405：`mvn -B test -Dtest=CspReportIntegrationTest -q 2>&1 | tail -40`，期望失败点至少包含 `Allow` 精确断言或 ERROR 日志断言（若 `Allow` 本就是 `POST` 则表现为 ERROR 断言失败——旧实现会打出 `ERROR 未处理异常`）。

### Step 2-3 实现（GREEN）

在 `GlobalExceptionHandler` 的 multipart 处理器之后、`AccessDeniedException` 处理器之前插入（导入 `org.springframework.http.converter.HttpMessageNotReadableException` 与 `org.springframework.web.HttpMediaTypeNotSupportedException`）：

```java
    /** 请求体无法解析（坏 JSON / 类型不匹配）→ 400；用 debug 避免匿名请求刷日志 */
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

- [ ] **Step 2-4** 复跑：
  ```bash
  mvn -B test -Dtest=ErrorContractIntegrationTest   # 5/5
  mvn -B test -Dtest=CspReportIntegrationTest       # 9/9（原 8 + 扩展现有 1 用例，不新增 @Test）
  mvn -B clean test                                 # 202 / 0 / 0 / 1，25 类
  ```
  若 405 用例的 `Allow` 实际不是恰好 `POST`（例如 `POST,OPTIONS`），以真实值修订断言并在 §12.2 记录（不要放宽到 `contains`，先确认真实头）。
- [ ] **Step 2-5** 变异验证（可选但推荐，报告里写结论）：把 `handleMethodNotSupported` 注释掉重跑 `CspReportIntegrationTest` → 405 用例应红（409/500）。
- [ ] **Step 2-6** 提交 `fix: 收敛客户端错误映射并加固 405 守护`（3 文件），`git status` 干净。

---

## Task 3：文档

**Files**
- Modify `README.md`
- Modify `docs/DEVELOPMENT.md`
- Modify `docs/deployment/README.md`
- Modify `deploy/nginx/public.conf`
- Modify `docs/superpowers/plans/2026-11-01-round13-csp-reporting.md`
- Modify 本计划 `docs/superpowers/plans/2026-11-03-round14-error-contract.md`（§12）

- [ ] **Step 3-1 README**
  - 测试基线：`24 个测试类、197 个用例` → `25 个测试类、202 个用例`；`33 个 spec、334 个用例` 不变（确认原文写法后精确替换）。
  - 在「API / 接口」相关小节末尾新增：

```markdown
### 错误响应契约

所有失败响应体统一为 `{"success":false,"message":"…","timestamp":…}`，不泄露堆栈：

| 状态 | 何时出现 |
|---|---|
| 400 | 业务规则不满足、`@Valid` 字段错误、缺/错 query 参数、**请求体不是合法 JSON** |
| 401 | 未登录、token 失效/过期、密码错误、SSE 票据无效 |
| 403 | 角色不足 |
| 404 | 资源不存在 |
| 405 | 方法不允许（`PUT/PATCH/DELETE/HEAD`），带 `Allow: POST`；`OPTIONS` 由 Spring 返回 200 |
| 413 | 上传超限 |
| 415 | `Content-Type` 不受支持（如对 JSON 接口发 `text/plain`） |
| 429 | 登录失败次数超限，带 `Retry-After` 秒数 |
| 500 | 未预期异常（唯一兜底；出现即代表需要修） |
```

- [ ] **Step 3-2 DEVELOPMENT**
  - §3.1：按 `find|wc` 重测四行（后端主代码、后端测试、前端产品、前端 spec、E2E）。
  - §3.2：后端 `202 个用例 / 25 个测试类`，类分布把 `ErrorContractIntegrationTest` 5 加进去（放 `CspReportIntegrationTest` 之后），分布合计改为 202；前端 334/33 不变。
  - §4.18：把「非 POST 方法统一 405」改为「`PUT/PATCH/DELETE` → 405 + `Allow: POST`；`OPTIONS` 由 Spring 返回 200（`Allow: POST,OPTIONS`）；`HEAD` → 405」。
  - 在 §4.18 之后、`## 5.` 之前新增 **§4.19 第十四轮：错误契约收尾（2026-11-03）**：背景（坏 JSON/不支持 Content-Type 曾 500 + ERROR）、改动表（`GlobalExceptionHandler` +2 handler；测试 1 新类 + 1 扩展）、错误契约表（spec §4.R4 全表）、验证矩阵 V1–V7 结果、构建记录（待回填）、已知限制（文案未国际化、415 含英文、IOException 不写响应、handler 用 debug 排障需调日志级别）、上线验收（待回填）。
  - §5.2 加第十四轮说明 + 候选（错误文案国际化、`ProblemDetail` 迁移、CSP 上报聚合）。
  - §8 顶部加 4 行（docs 本次提交「本次提交」、`fix` 提交、Task 1 docs、第十三轮回填行 `d52751a` 保持不动——先核对现有行）。
- [ ] **Step 3-3 部署手册**
  - 修正第十三轮新增的合成上报 `curl` 示例：合并为单行 `curl -sS -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/csp-report' --data '{"csp-report":{…}}' http://127.0.0.1/forum/api/csp-report`。
  - 在冒烟检查小节加两行「错误码速查」：坏 JSON→400（`请求体格式错误`）、`text/plain`→415（`不支持的 Content-Type`）。
- [ ] **Step 3-4 nginx 注释**：`deploy/nginx/public.conf:39-40` 旧注释（uploads 仅 nosniff）改为「uploads 的 4 个安全头下发在 `forum.conf` 的 location 里；本文件只 include snippet」。
- [ ] **Step 3-5 十三轮计划残留**：`docs/superpowers/plans/2026-11-01-round13-csp-reporting.md` 中 `cleanSample` 的 `\p{Cntrl}` 描述改为实际使用的 `[\p{Cc}\p{Cf}\u2028\u2029]`；两处「（待回填）」改为「（已于 `165c469` / 回填提交完成）」。
- [ ] **Step 3-6** 验证：
  ```bash
  grep -n "202 个用例" README.md docs/DEVELOPMENT.md
  grep -n "4.19 第十四轮" docs/DEVELOPMENT.md
  grep -n "不支持的 Content-Type" README.md docs/DEVELOPMENT.md
  grep -c "待回填" docs/superpowers/plans/2026-11-01-round13-csp-reporting.md   # 期望 0
  bash -n deploy/deploy.sh
  ```
- [ ] **Step 3-7** 提交 `docs: 记录第十四轮错误契约收尾`（约 6 文件），`git status` 干净。

---

## Task 4：全量验证、上线与回填

- [ ] **Step 4-1** 后端：`mvn -B clean test` → `202 / 0 / 0 / 1`，25 类，BUILD SUCCESS。
- [ ] **Step 4-2** 前端：`cd frontend && pnpm test` → 33/334；`MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build` 成功；`pnpm check:bundle` exit 0（五项预算内）。
- [ ] **Step 4-3** 本地错误契约冒烟（`pnpm dev` 或已部署）：

  ```bash
  # 坏 JSON → 400
  curl -s -o /dev/null -w '%{http_code}\n' -H 'Content-Type: application/json' --data '{"username":' http://127.0.0.1:8080/api/auth/login
  # text/plain → 415
  curl -s -o /dev/null -w '%{http_code}\n' -H 'Content-Type: text/plain' --data '{"username":"a"}' http://127.0.0.1:8080/api/auth/login
  # 认证 PUT → 405 + Allow: POST（token 先登录拿）
  curl -s -D - -o /dev/null -H "Authorization: Bearer $TOKEN" -X PUT http://127.0.0.1:8080/api/csp-report | grep -iE '^(HTTP/|allow:)'
  ```

- [ ] **Step 4-4** 推送（SSH 显式 URL）：
  ```bash
  git push git@github.com:ScholarOfDeepDarkFantasy/forum.git master:master
  git ls-remote git@github.com:ScholarOfDeepDarkFantasy/forum.git refs/heads/master
  ```
- [ ] **Step 4-5** Jenkins：记录 `nextBuildNumber` → 轮询 `Finished: SUCCESS` 与 `Checking out Revision`（构建号与镜像号）。
- [ ] **Step 4-6** 公网验收（经 nginx `Host: raspi`，以及 `https://treenewbee.men/forum`）：
  - 坏 JSON → 400（`请求体格式错误`）；
  - `text/plain` → 415（`不支持的 Content-Type`）；
  - 认证 PUT/PATCH/DELETE → 405 + `Allow: POST`；
  - `OPTIONS` → 200 + `Allow: POST,OPTIONS`；
  - 容器 `ERROR` 日志计数 0；`/forum/api/health` 200。
- [ ] **Step 4-7** 回填并提交 `docs: 回填第十四轮构建与上线验收`：§4.19 上线验收段写构建号/镜像号/V1–V7/公网冒烟；§8 回填行 + `fix` 行哈希；部署手册验收引用块；计划 §12.1 构建表补本轮回填行。
- [ ] **Step 4-8** 推送 → 期望再一个 docs-only 构建 SUCCESS；确认远端 == HEAD。

---

## 附录 A：提交信息（逐字）

1. `docs: 新增第十四轮错误契约收尾设计与计划`
2. `fix: 收敛客户端错误映射并加固 405 守护`
3. `docs: 记录第十四轮错误契约收尾`
4. `docs: 回填第十四轮构建与上线验收`

## 附录 B：冒烟清单

- B.1 `GET /forum/api/health` → 200。
- B.2 坏 JSON → 400 且 message=`请求体格式错误`、`timestamp` 存在。
- B.3 `text/plain` → 415 且 message=`不支持的 Content-Type`。
- B.4 登录 → me → logout → 旧 token 401（不回归）。
- B.5 认证 `PUT /forum/api/csp-report` → 405 + `Allow: POST`。
- B.6 `OPTIONS /forum/api/csp-report` → 200 + `Allow` 含 `OPTIONS`。
- B.7 容器 `ERROR` 日志计数 0；E2E 8/8（可选，本地或公网）。

## 12. 实施记录与偏差（2026-11-03）

（执行时填写：提交哈希与构建表、偏差、实测数字、审查结论、上线验收）
