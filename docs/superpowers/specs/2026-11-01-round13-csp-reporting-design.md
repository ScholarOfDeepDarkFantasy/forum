# 第十三轮设计：CSP 违规上报与可见性

- 日期：2026-11-01
- 起点：`c4c41c0`（第十二轮终态，工作区干净，远端同步）
- 关联：第十一轮 CSP（`docs/DEVELOPMENT.md` §4.16）、技术债 T18（CSP 无违规上报）

## 1. 背景与目标

第十一轮把 CSP 收紧到「只有首页内联主题脚本的 SHA-256 与 CF 分析信标被放行」。目前唯一发现
违规的途径是发布后人工打开浏览器看控制台（或临时 Playwright 脚本），属于**事后、抽样、不可
持续**的观测手段：一旦某次发版引入被策略拦截的资源，线上用户会看到坏样式/坏交互，而我们只能
等反馈。

**目标**：让浏览器把 CSP 违规自动上报到源站，由后端写入结构化日志，并在部署脚本里验证「上报
通道真的通」。本轮不做违规聚合/看板（见 §7 非目标）。

**验收标准**

1. 公网 `/forum/` 的 HTML 响应带 `Reporting-Endpoints: csp-endpoint="/forum/api/csp-report"`，
   CSP 同时含 `report-uri /forum/api/csp-report` 与 `report-to csp-endpoint`（两份 nginx conf 同步）。
2. `POST /api/csp-report` 对浏览器两种格式（legacy `application/csp-report` 与 Reporting API
   `application/reports+json`）都返回 **204**，并在后端日志输出一行可 grep 的结构化 WARN。
3. 端点公开可匿名访问（浏览器不会带 JWT）；对超大 body、坏 JSON、错误 Content-Type、按 IP
   刷量都**永不 5xx / 永不 4xx**（一律 204），避免上报本身成为攻击面。
4. 部署脚本 `deploy.sh` 断言上报通道（响应头 + CSP 指令 + 真实 POST 204）。
5. 基线不回退：后端 177 → **189**（+12）/ 21 → 24 类；前端 330 → **332** / 33 spec；E2E 8/8；
   产物预算不变。

## 2. 非目标

- 不做违规聚合、看板、告警、入库（日志进 journald，按需 grep）。
- 不做 `Report-Only` 灰度：现有策略已稳定运行一轮，直接启用上报。
- 不新增前端依赖、不改业务 API、不改数据库（本轮无 Flyway 迁移）。
- 不做 `report-to` 的 `max_age`/多端点协商（只声明一个 group）。
- 不替换现有 `consoleGuard`（E2E 仍失败即红）；上报是线上补充，不是测试替代。

## 3. 现状审计（写方案前提）

| 事实 | 位置 |
| --- | --- |
| CSP 在 `location = /forum/index.html`（两个 location 各一条：forum.conf、public.conf） | `deploy/nginx/forum.conf:68`、`deploy/nginx/public.conf:48` |
| nginx 1.22 无 `add_header_inherit`，location 有 add_header 即屏蔽 server 级 | 第十轮实测（§4.15） |
| 现有 CSP 无任何 `report-*` 指令 | 同上 |
| `/forum/api/` 反代 8086，`client_max_body_size 3m`；SSE 有独立精确 location | `deploy/nginx/forum.conf:90-106` |
| `deploy.sh` 现有 CSP 校验：哈希现算比对 + `script/connect/style-src` 逐字断言（加新指令不影响） | `deploy/deploy.sh:225-260` |
| `csp.spec.js` 7 条：哈希、下发点、必备指令、信标、两份 conf 逐字相同、uploads 四头、deploy.sh 含断言 | `frontend/src/plugins/csp.spec.js` |
| 后端无 CSP 相关代码；`Result` 包装统一，异常由 `GlobalExceptionHandler` 兜底 | `src/main/java/org/song/forum/...` |
| 公开 POST 只有 register/login；`requestMatchers(HttpMethod.GET).permitAll()` 让任意 GET 公开 | `SecurityConfig.java:64-73` |
| 已有 `ClockConfig`（`Clock` bean）与 `ClientIpResolver`（信任环回/RFC1918 代理头）可复用 | `config/ClockConfig.java`、`security/ClientIpResolver.java` |
| 浏览器上报格式：legacy `{"csp-report":{...}}`；Reporting API `[{type:"csp-violation", body:{...}, url, user_agent}]` | W3C CSP3 / Reporting API |

## 4. 方案

### 4.1 nginx：下发上报配置

`forum.conf` 的 `location = /forum/index.html`：

```nginx
add_header Reporting-Endpoints "csp-endpoint=\"/forum/api/csp-report\"" always;
```

CSP 尾部追加两条（保留现有指令顺序与逐字性，只在 `frame-ancestors 'self'` 之后追加）：

```text
; report-uri /forum/api/csp-report; report-to csp-endpoint
```

`public.conf` server 级的 CSP 字符串必须与 `forum.conf` **逐字相同**（csp.spec 已断言），因此同样
追加这两条；HTML 的实际生效头来自 snippet 的 index location，`Reporting-Endpoints` 只写在
index location（server 级会被它屏蔽，写了也是误导）。

**为什么两条都留**：`report-to` 是现代语法（Chrome/Edge 96+），`report-uri` 是兼容语法
（Safari/Firefox 仍需要）。同时存在会让现代浏览器发两份（`report-uri` 与 Reporting API 各一份），
由后端按 IP 限速 + 日志去重字段消化；不做复杂协商。

**专用反代 location**（放在 `/forum/api/` 之前，exact match 优先级更高）：

```nginx
location = /forum/api/csp-report {
    proxy_pass http://127.0.0.1:8086/api/csp-report;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 10s;
    # 单个 CSP 报告远小于 16k；给小上限防止把上报端点当大文件上传通道
    client_max_body_size 16k;
    access_log off;
}
```

`access_log off`：正常页面每页可能有多条违规，逐条写 nginx access log 会把日志冲掉；结构化信息
由后端 WARN 承担。错误仍进 error_log。

### 4.2 后端：公开上报端点

新增 `org.song.forum.controller.CspReportController`：

- `POST /api/csp-report`，**无条件 204**，响应头 `Cache-Control: no-store`。
- 不返回 `Result`，不抛业务异常：上报失败对浏览器无意义，抛错只会刷 500 日志。
- 读取逻辑在 `CspReportService`，controller 只做 204。

`CspReportService`（`org.song.forum.security` 包，与限流/解析类同包）：

1. `String ip = clientIpResolver.resolve(request)`；
2. `if (!throttle.tryAcquire(ip)) { log.debug("CSP 上报被限速丢弃 ip={}", ip); return; }`
   —— 先限速再读 body，避免刷量时把 CPU 花在解析上；
3. 有界读取：`request.getInputStream().readNBytes(MAX_BODY_BYTES + 1)`（8192），超限丢弃
   （`log.debug` 记录长度），不解析；
4. 按 Content-Type 分流：含 `application/reports+json` → Reporting API 批量；否则按 legacy
   `application/csp-report` / `application/json` 解析；其它类型只 debug 丢弃；
5. `CspReportSanitizer` 解析并清洗成 `List<CspViolation>`；
6. 每条 `log.warn("CSP 违规上报 ip={} directive={} blocked={} doc={} sample={}", ...)`；
7. 全部包在 `try/catch (Exception)` 里：任何解析失败只 debug（`log.debug("CSP 上报解析失败 ip={}", ip, e)`），永不向外抛。

**清洗规则（隐私/体积）**

- URL 字段（document-uri、blocked-uri、source-file）：去掉 query 与 fragment（`?`/`#` 之后），
  长度截断到 300；`inline`/`eval`/`data`/`blob:` 等非 URL 字面量原样保留并截断。
- `script-sample`：去掉控制字符（含换行），截断到 120。
- `directive` 取 `effective-directive`，缺省取 `violated-directive`，未知则 `unknown`。
- 只读取白名单字段，**不落整个原始 body**（避免把页面查询串、用户内容写进日志）。

`CspReportThrottle`（固定窗口，单实例内存）：

- `Map<String, Window>` + `Clock`；每 IP 每分钟最多 `app.security.csp-report.max-per-minute`（默认 20）。
- 构造器校验 `maxPerMinute >= 1`（写错时启动失败，避免「限流悄悄失效」）。
- `@Scheduled(fixedDelayString = "${app.security.csp-report.cleanup-interval-ms:60000}")` 清理过期窗口
  （`cleanupScheduled()` + 包级可见 `int cleanupExpired()`，与 `LoginRateLimiter` 同构）。
- 用 `ConcurrentHashMap.compute` 保证并发下计数正确；窗口切换时重置 count。

配置（`application.yml` 的 `app.security` 下）：

```yaml
    csp-report:
      max-per-minute: ${CSP_REPORT_MAX_PER_MINUTE:20}
      cleanup-interval-ms: ${CSP_REPORT_CLEANUP_INTERVAL_MS:60000}
```

`SecurityConfig`：在 register/login 那行追加 POST 放行：

```java
.requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/csp-report").permitAll()
```

**为什么公开**：CSP 上报由浏览器发起，不带 Authorization；且不能在 nginx 层 secret 化（浏览器无
法带自定义头）。防护靠体积上限、限速、永不回显、不落原文。

### 4.3 部署断言（deploy.sh）

在 CSP 校验段落之后追加 4 条（全走本机 nginx，带公网 Host）：

1. `reporting-endpoints` 响应头存在且含 `csp-endpoint="/forum/api/csp-report"`；
2. CSP 含 `report-uri /forum/api/csp-report` 与 `report-to csp-endpoint`；
3. 真实 POST 一份 legacy 报告 → **204**；
4. `GET /forum/api/csp-report` → **405**（只开 POST，防止被当查询接口）。

### 4.4 前端守护（csp.spec.js）

新增 2 条（node 环境，只读仓库文件）：

1. `forum.conf` 的 index location 含 `Reporting-Endpoints` 且 CSP 含 `report-uri`/`report-to`；
   `public.conf` 的 CSP 与 `forum.conf` 逐字相同的断言自然覆盖新指令。
2. `deploy.sh` 含上报通道断言唯一串（`Reporting-Endpoints`、`csp-report`、`report-to`），防止断言被悄悄删。

### 4.5 兼容与回滚

- 老浏览器忽略未知 CSP 指令；多发的 `report-uri` 与 `report-to` 重复上报由限速消化。
- 回滚：`git revert` 本轮提交 + 重跑 `deploy.sh`；无数据库/无迁移，回滚无数据风险。
- 上报端点被滥用时的止血：`CSP_REPORT_MAX_PER_MINUTE=1` 重启容器即把日志压到每 IP 每分钟 1 条；
  或在 nginx 把该 location `return 204;`（上报直接吞掉，不影响页面）。

## 5. 验证矩阵

| # | 验证点 | 方法 | 期望 |
| --- | --- | --- | --- |
| V1 | HTML 带 `Reporting-Endpoints` | `curl -sI -H 'Host: treenewbee.men' http://127.0.0.1/forum/` | 含 `csp-endpoint="/forum/api/csp-report"` |
| V2 | CSP 含两条上报指令 | 同上 grep CSP | `report-uri /forum/api/csp-report`、`report-to csp-endpoint` |
| V3 | legacy 上报 | POST `application/csp-report` 合成 body | 204，后端日志 `CSP 违规上报` 一行 |
| V4 | Reporting API 上报 | POST `application/reports+json` 数组 | 204，日志含 `directive=script-src` |
| V5 | 坏输入不炸 | 坏 JSON / 20KB body / `text/plain` / GET | 全部 204（GET 405），无 5xx |
| V6 | 真实浏览器违规 | Playwright `page.addScriptTag` 注入内联脚本（被 CSP 拦截） | 后端日志出现 `blocked=inline`（经公网） |
| V7 | 基线不回退 | `mvn -B clean test`、`pnpm test`、`check:bundle`、E2E 8/8 | 189/24、332/33、五项预算内、8 passed |

## 6. 测试清单（后端 +12）

`CspReportSanitizerTest`（5）：legacy 解析并去 query/fragment；批量只取 `csp-violation`；`inline`/
`eval` 字面量保留；超长字段截断；坏 JSON/空 body → 空列表。

`CspReportThrottleTest`（3）：窗口内放行到上限；窗口滚动后重置；不同 IP 各自计数。

`CspReportIntegrationTest`（4，独立 H2）：legacy → 204；批量 → 204；坏 JSON + 超大 body → 204；
GET → 405（`properties = {"app.security.csp-report.max-per-minute=3"}`）。

前端 `csp.spec.js` +2 → 332 / 33。

## 7. 已知限制（写入 §4.18）

- 上报只进日志：没有聚合/告警/看板，需要 `journalctl`/`podman logs` 里 grep；日志轮转由系统负责。
- 限速与去重都在进程内：多实例各算各的、重启清零；攻击者换 IP 仍可放大日志量（有 20/min/IP 上限）。
- 现代浏览器同时发 `report-uri` 与 `report-to` 两份，日志会出现重复条目（字段相同）；grep 时按
  `directive` 汇总即可。
- `report-to` 需安全上下文（HTTPS）；公网有 CF 提供 HTTPS，内网 `http://raspi/forum/`（`Host: raspi`）
  只有 `report-uri` 生效。
- 不记录 `script-sample` 之外的内容，无法还原完整页面上下文；需要时按 `doc` 字段人工复现。
- `Reporting-Endpoints` 只写在 index location，assets/uploads 响应不带（它们不执行脚本，无上报需求）。

## 8. 上线验收

1. 部署后 V1/V2 头检查通过；V3/V4 合成上报 204 且日志可 grep。
2. 公网 Playwright 注入内联脚本触发真实违规，后端日志出现 `CSP 违规上报 ... blocked=inline`。
3. V5 坏输入全 204/405，容器日志无 ERROR、无 5xx 访问。
4. 基线 189/24、332/33、E2E 8/8、`check:bundle` 0，公网页面控制台无新增违规。
