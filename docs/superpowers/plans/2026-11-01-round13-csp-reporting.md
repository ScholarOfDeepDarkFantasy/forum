# 第十三轮实现计划：CSP 违规上报与可见性

- 设计：`docs/superpowers/specs/2026-11-01-round13-csp-reporting-design.md`
- 起点：`c4c41c0`（第十二轮终态）
- 仓库：`E:\JavaSpace\forum`（Git Bash `/e/JavaSpace/forum`），master 直接提交，不建分支、不 amend、不 rebase

## 0. 执行约定

1. 每个 Task 一次提交（计划 5 次；审查修复可产生计划外提交，需在 §12 记录）。
2. 提交信息逐字使用附录 A。
3. 后端验证：`cd /e/JavaSpace/forum && mvn -B clean test`（基线 177 → **190**，测试类 21 → 24）。
4. 前端验证：`cd /e/JavaSpace/forum/frontend && pnpm test`（基线 330 → **332**，spec 33）。
5. 构建验证：`MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build && pnpm check:bundle`。
6. 部署/公网验收：推送 → Jenkins 轮询 `sudo grep -o "Finished: [A-Z]*" /var/lib/jenkins/jobs/javaforum/builds/<N>/log`；
   本机后端口令走 `python /c/Temp/forum-ssh.py '<cmd>'`；后端日志用
   `sudo -u pi XDG_RUNTIME_DIR=/run/user/$(id -u pi) podman logs --tail 200 forum-backend`。
7. 前端命令注意 `cd frontend`，构建必须带 `MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH`。

## Task 1：设计与计划入库

**Files**

- 新增 `docs/superpowers/specs/2026-11-01-round13-csp-reporting-design.md`
- 新增 `docs/superpowers/plans/2026-11-01-round13-csp-reporting.md`

**Steps**

- [ ] 1-1 两份文档落盘（本文件即计划；设计见 spec）。
- [ ] 1-2 `git add` 两份文档 → 提交 `docs: 新增第十三轮 CSP 违规上报设计与计划`。
- [ ] 1-3 `git status` 空。

## Task 2：后端接收 CSP 上报

**Files**

- 新增 `src/main/java/org/song/forum/security/CspViolation.java`
- 新增 `src/main/java/org/song/forum/security/CspReportSanitizer.java`
- 新增 `src/main/java/org/song/forum/security/CspReportThrottle.java`
- 新增 `src/main/java/org/song/forum/security/CspReportService.java`
- 新增 `src/main/java/org/song/forum/controller/CspReportController.java`
- 修改 `src/main/java/org/song/forum/config/SecurityConfig.java`
- 修改 `src/main/resources/application.yml`
- 新增 `src/test/java/org/song/forum/security/CspReportSanitizerTest.java`
- 新增 `src/test/java/org/song/forum/security/CspReportThrottleTest.java`
- 新增 `src/test/java/org/song/forum/controller/CspReportIntegrationTest.java`

**RED**

- [ ] 2-1 先写三个测试类，`mvn -B test -Dtest='CspReport*Test'` 应因缺类编译失败（RED 证据贴原文）。

**实现**

- [ ] 2-2 `CspViolation`（record）：

```java
public record CspViolation(String documentUri, String directive, String blockedUri,
                           String sourceFile, String sample, String disposition) {
}
```

- [ ] 2-3 `CspReportSanitizer`（`@Component`，构造注入 `ObjectMapper`）：

  - `List<CspViolation> sanitize(String contentType, byte[] body)`：`contentType` 含
    `application/reports+json` → 批量（数组元素 `type` 为 `csp-violation` 时取 `body` 节点）；
    否则按 legacy 解析 `root.path("csp-report")`。空 body / 解析异常 → `List.of()`（方法内 catch
    `IOException`，不向外抛）。
  - 字段白名单：`document-uri`、`effective-directive`（缺省 `violated-directive`，再缺省
    `"unknown"`）、`blocked-uri`、`source-file`、`script-sample`、`disposition`。
  - `cleanUrl`：空 → `""`；`?`/`#` 之前的部分；trim；截断 300。
  - `cleanSample`：去 `\\p{Cntrl}`，trim，截断 120。
  - 批量里忽略非 csp-violation 条目；legacy 节点缺失或非对象 → 空列表。
  - javadoc 写明「只读白名单字段，不落原始 body」。

- [ ] 2-4 `CspReportThrottle`（`@Component`）：

```java
public CspReportThrottle(Clock clock,
        @Value("${app.security.csp-report.max-per-minute:20}") int maxPerMinute,
        @Value("${app.security.csp-report.window-ms:60000}") long windowMs) {
    if (maxPerMinute < 1) throw new IllegalArgumentException(
            "app.security.csp-report.max-per-minute 必须 >= 1，当前 " + maxPerMinute);
    if (windowMs <= 0) throw new IllegalArgumentException(
            "app.security.csp-report.window-ms 必须 > 0，当前 " + windowMs);
    ...
}
```
  - `boolean tryAcquire(String ip)`：`windows.compute(ip, (k, w) -> ...)`，无窗口或已过期则
    新建（count=1，返回 true）；未过期且 count < max → count+1；否则原样返回 false。
  - `@Scheduled(fixedDelayString = "${app.security.csp-report.cleanup-interval-ms:60000}") void cleanupScheduled()`
    调 `cleanupExpired()`；`int cleanupExpired()` 遍历 `entrySet()`，`now - windowStart >= windowMs`
    时用 `windows.remove(key, entry.getValue())`（按身份删除，避免删掉刚建的新窗口）。
  - `@Slf4j`，日志风格与 `LoginRateLimiter` 对齐。

- [ ] 2-5 `CspReportService`（`@Component`，构造注入 `ClientIpResolver`、`CspReportThrottle`、
  `CspReportSanitizer`，`@Value("${app.security.csp-report.max-body-bytes:8192}") int maxBodyBytes`）：

```java
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
```
  - javadoc 写明「永远不抛异常；日志每条 WARN，单 IP 由 throttle 限速」。

- [ ] 2-6 `CspReportController`：

```java
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
```

- [ ] 2-7 `SecurityConfig`：`requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/csp-report").permitAll()`，
  注释说明「CSP 上报由浏览器发起，不带认证头」。

- [ ] 2-8 `application.yml` 在 `app.security` 下追加：

```yaml
    # ---- CSP 违规上报（浏览器自动发送，匿名） ----
    csp-report:
      max-per-minute: ${CSP_REPORT_MAX_PER_MINUTE:20}
      window-ms: ${CSP_REPORT_WINDOW_MS:60000}
      cleanup-interval-ms: ${CSP_REPORT_CLEANUP_INTERVAL_MS:60000}
      max-body-bytes: ${CSP_REPORT_MAX_BODY_BYTES:8192}
```

**测试**

- [ ] 2-9 `CspReportSanitizerTest`（5，`new ObjectMapper()`，不启动 Spring）：
  1. legacy 报告解析字段齐全，`document-uri` 去掉 `?query#frag`；
  2. 批量格式只取 `type=csp-violation`，忽略其它 type；
  3. `blocked-uri` 为 `inline`/`eval` 时保留字面量；
  4. 超长 `script-sample`/URI 截断（120/300）且去换行；
  5. 坏 JSON / 空 body / 缺 `csp-report` 节点 → 空列表。
- [ ] 2-10 `CspReportThrottleTest`（3，`MutableClock` 与 `LoginRateLimiterTest` 同款）：
  1. 上限内放行、超限拒绝；2. 窗口滚动后重新放行；3. 不同 IP 各自计数。
- [ ] 2-11 `CspReportIntegrationTest`（5，`@ActiveProfiles("test")` +
  `@SpringBootTest(webEnvironment = RANDOM_PORT, properties = { H2 内存库四件套, "app.security.csp-report.max-per-minute=3" })`）：
  1. legacy 上报 → 204、空 body、日志 1 条 `CSP 违规上报`（用 Logback `ListAppender` 挂在
     `CspReportService` logger 上）；
  2. 批量上报 → 204、日志 `directive=script-src`；
  3. 同 IP 连发 4 条但上限 3 → 全 204、只有 3 条 WARN；
  4. 坏 JSON / 20KB body / `text/plain` → 全部 204、无 WARN；
  5. `GET /api/csp-report` → 405。
- [ ] 2-12 单类通过后全量：`mvn -B clean test` → `Tests run: 190, Failures: 0, Errors: 0, Skipped: 1`，24 类。
- [ ] 2-13 `git add` 10 文件 → 提交 `feat: 接收 CSP 违规上报并输出结构化日志`。

## Task 3：nginx 下发上报配置与通道断言

**Files**

- 修改 `deploy/nginx/forum.conf`
- 修改 `deploy/nginx/public.conf`
- 修改 `deploy/deploy.sh`
- 修改 `frontend/src/plugins/csp.spec.js`

**RED**

- [ ] 3-1 先加 csp.spec 两条断言（见 3-4）→ `pnpm vitest run src/plugins/csp.spec.js` 应 2 failed（RED 原文）。

**实现**

- [ ] 3-2 `forum.conf`：
  - `location = /forum/index.html` 里 CSP 字符串末尾（`frame-ancestors 'self'` 之后）追加
    `; report-uri /forum/api/csp-report; report-to csp-endpoint`；
  - 同一 location 追加 `add_header Reporting-Endpoints '\''csp-endpoint="/forum/api/csp-report"'\'' always;`
    （nginx 支持单引号字符串；写完后在 raspi `nginx -t` 验证，若解析失败改用双引号转义写法）；
  - 在 `location /forum/api/` 之前插入精确 location：

```nginx
# CSP 上报端点：浏览器匿名 POST，体积很小；单独 location 收紧体积上限并关掉 access log
location = /forum/api/csp-report {
    proxy_pass http://127.0.0.1:8086/api/csp-report;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 10s;
    client_max_body_size 16k;
    access_log off;
}
```

- [ ] 3-3 `public.conf`：server 级 CSP 字符串追加同样两段（保持与 forum.conf 逐字相同）。

- [ ] 3-4 `csp.spec.js` 追加 2 条：

```js
it('CSP 声明 report-uri 与 report-to，并由 index location 下发 Reporting-Endpoints', () => {
  const conf = readFileSync(FORUM_CONF, 'utf8')
  const [csp] = cspValues(FORUM_CONF)
  expect(csp).toContain('report-uri /forum/api/csp-report')
  expect(csp).toContain('report-to csp-endpoint')
  const block = locationBlock(conf, 'location = /forum/index.html')
  expect(block).toContain('Reporting-Endpoints')
  expect(block).toContain('csp-endpoint="/forum/api/csp-report"')
})

it('deploy.sh 含 CSP 上报通道断言（响应头 + 真实 POST 204）', () => {
  const script = readFileSync(DEPLOY_SH, 'utf8')
  expect(script).toContain('reporting-endpoints')
  expect(script).toContain('csp-endpoint="/forum/api/csp-report"')
  expect(script).toContain('/forum/api/csp-report')
  expect(script).toContain('CSP 上报端点')
})
```

  - 注意：第 1 条必须用 `locationBlock` 截块断言，不能用全文件 `toContain('Reporting-Endpoints')`（防恒真）。

- [ ] 3-5 `deploy.sh` 在 CSP 校验 `log "CSP 校验通过..."` 之后追加 4 条断言（全部带
  `--max-time 5`、`tr -d '\r'`，并沿用现有 `die`/`log` 风格）：

```bash
# ---- 第十三轮：CSP 上报通道 ----
public_html_headers="$(curl -sS --max-time 5 -I -H 'Host: treenewbee.men' "http://127.0.0.1/forum/" | tr -d '\r' || true)"
printf '%s\n' "$public_html_headers" | grep -qi '^reporting-endpoints:.*csp-endpoint="/forum/api/csp-report"' \
    || die "公网 /forum/ 缺少 Reporting-Endpoints: csp-endpoint（实际：$(printf '%s\n' "$public_html_headers" | grep -i '^reporting-endpoints' || echo '无该头')）"
printf '%s\n' "$public_csp" | grep -qi 'report-uri /forum/api/csp-report' \
    || die "公网 CSP 缺少 report-uri /forum/api/csp-report"
printf '%s\n' "$public_csp" | grep -qi 'report-to csp-endpoint' \
    || die "公网 CSP 缺少 report-to csp-endpoint"
csp_report_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 \
    -X POST -H 'Content-Type: application/csp-report' \
    --data '{"csp-report":{"document-uri":"http://127.0.0.1/forum/","violated-directive":"script-src","blocked-uri":"inline"}}' \
    http://127.0.0.1/forum/api/csp-report || true)"
[ "$csp_report_code" = "204" ] \
    || die "CSP 上报端点未返回 204（实际：${csp_report_code:-空}）"
csp_report_get="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 \
    http://127.0.0.1/forum/api/csp-report || true)"
[ "$csp_report_get" = "405" ] \
    || die "CSP 上报端点不应接受 GET（实际：${csp_report_get:-空}）"
log "CSP 上报通道校验通过：Reporting-Endpoints 已下发，POST 204，GET 405"
```

  - 注意：上面第一段复用了 `public_csp` 变量（Task 3 执行时它已在同一函数作用域里）；
    若位置不在同一作用域，需在该段重新抓一次 `/forum/` 的头。

- [ ] 3-6 `bash -n deploy/deploy.sh` 通过；`pnpm vitest run src/plugins/csp.spec.js` → **9 passed**；
  `pnpm test` → **332 / 33**。
- [ ] 3-7 `git add` 4 文件 → 提交 `feat: nginx 下发 CSP 上报配置并断言通道`。

## Task 4：文档记录

**Files**

- 修改 `README.md`
- 修改 `docs/DEVELOPMENT.md`
- 修改 `docs/deployment/README.md`
- 修改 `docs/superpowers/plans/2026-11-01-round13-csp-reporting.md`（§12 实施记录）

**Steps**

- [ ] 4-1 `README.md`：
  - 功能列表「会话安全」附近加一条「CSP 违规上报：report-uri + report-to → /api/csp-report，结构化日志」；
  - 配置表加 `CSP_REPORT_MAX_PER_MINUTE`（默认 20）、`CSP_REPORT_WINDOW_MS`（默认 60000）、
    `CSP_REPORT_MAX_BODY_BYTES`（默认 8192）、`CSP_REPORT_CLEANUP_INTERVAL_MS`（默认 60000）；
  - 已知限制加「CSP 上报只进日志，无限速之外的聚合」。
- [ ] 4-2 `docs/DEVELOPMENT.md`：
  - §2.2 加第十三轮行（5 任务，2026-11-01），「已完成 12 轮」→ 13；
  - §3.1/§3.2 更新规模与基线（后端 190/24、前端 332/33；新增测试类 `CspReportSanitizerTest` 5、
    `CspReportThrottleTest` 3、`CspReportIntegrationTest` 5；前端分布 `csp` 7→9）；
  - 新增 **§4.18 第十三轮：CSP 违规上报与可见性（2026-11-01）**：背景、改动表（nginx 2 + deploy.sh +
    后端 5 + 测试 3）、上报格式与端点行为（永远 204、限速、清洗规则）、V1–V7 验证矩阵、
    构建记录（待回填）、已知限制（spec §7）；
  - §5.1 关闭 **T18**（删除线 + 「2026-11-01 第十三轮：`/api/csp-report` + `report-uri`/`report-to`
    + deploy 断言 + 结构化 WARN，见 §4.18」）；
  - §5.2 加第十三轮说明 + 2 条候选（违规日志聚合/看板；`report-to` 的 `max_age` 协商）；
  - §8 顶部加 4 行（docs 本次提交、Task 3 提交、Task 2 提交、Task 1 提交）。
- [ ] 4-3 `docs/deployment/README.md`：
  - 「安全响应头与 CSP（第十一轮）验证」之后加「### CSP 违规上报（第十三轮）验证」：头检查命令、
    合成上报 POST、日志 grep（`podman logs --tail 200 forum-backend | grep 'CSP 违规上报'`）、
    真实浏览器违规（Playwright 注入内联脚本）步骤；
  - §6 排障加一行「CSP 上报量大/被刷」：`CSP_REPORT_MAX_PER_MINUTE=1` 或 nginx `return 204`；
  - §7 已知限制加 2 条（只进日志；`report-to` 需 HTTPS，内网走 report-uri）。
- [ ] 4-4 计划 §12「实施记录与偏差（2026-11-01）」：提交链、构建表（待回填）、偏差、实测表。
- [ ] 4-5 `git add` 4 文件 → 提交 `docs: 记录第十三轮 CSP 违规上报`。

## Task 5：全量验证、部署与回填

- [ ] 5-1 全量回归（本地）：`mvn -B clean test` → 190/24/1 skip；前端 `pnpm test` → 332/33；
  `VITE_BASE_PATH=/forum/ pnpm build` + `pnpm check:bundle` 0；`pnpm exec playwright test --list` → 8/5。
- [ ] 5-2 推送 `git push git@github.com:ScholarOfDeepDarkFantasy/forum.git master:master`。
- [ ] 5-3 Jenkins 构建（预计 #40），确认 `deploy.sh` 新断言通过（日志含 `CSP 上报通道校验通过`）。
- [ ] 5-4 公网验收 V1–V6（含 Playwright 注入内联脚本触发真实违规，`podman logs | grep 'CSP 违规上报'`
  出现 `blocked=inline`）；V5 坏输入全 204/405、无 ERROR。
- [ ] 5-5 回填：§4.18 上线验收（Build #N/镜像 N、V1–V7 结果）、§8 回填行、§12 构建表、部署手册验收段；
  提交 `docs: 回填第十三轮构建与上线验收` → 推送 → 构建 #41 → 确认远端==HEAD。
- [ ] 5-6 最终只读审查（提交形态、端点安全性、日志清洗、nginx 配置、文档一致性）→ 收尾报告。

## 附录 A：提交信息（逐字）

1. `docs: 新增第十三轮 CSP 违规上报设计与计划`
2. `feat: 接收 CSP 违规上报并输出结构化日志`
3. `feat: nginx 下发 CSP 上报配置并断言通道`
4. `docs: 记录第十三轮 CSP 违规上报`
5. `docs: 回填第十三轮构建与上线验收`

## 附录 B：上线冒烟（B.1–B.7）

- B.1 公网 `/forum/` 200 且带 `Reporting-Endpoints`、6 条安全头；
- B.2 CSP 含 `report-uri`/`report-to`，哈希与内联脚本一致；
- B.3 合成 legacy 上报 → 204；后端日志 `CSP 违规上报 ... directive=script-src`；
- B.4 合成批量上报 → 204；
- B.5 坏 JSON / 20KB / `text/plain` → 204/405，容器日志无 ERROR；
- B.6 Playwright 公网注入内联脚本触发真实违规 → 日志 `blocked=inline`；
- B.7 页面功能回归（首页/详情/登录/管理页各 1 次快速点数）+ E2E 8/8。
