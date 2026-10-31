# 第十一轮：安全响应头收敛与 CSP 设计

- 日期：2026-10-31
- 仓库：`E:\JavaSpace\forum`
- 上游：第十轮《公网入口验证与加固》（`docs/superpowers/specs/2026-10-28-round10-public-entry-hardening-design.md`）
- 状态：待实现

## 1. 背景

第十轮给公网入口补了 4 个安全响应头（`X-Content-Type-Options`、`X-Frame-Options`、`Referrer-Policy`、`Strict-Transport-Security`），并把 HTTP 308 到 HTTPS。仍然缺少的是一层**内容维度**的防线：

1. 没有 `Content-Security-Policy`（CSP）。一旦出现 XSS（Markdown 已用 DOMPurify + `html:false` 消毒，但这是唯一一道屏障），浏览器会无条件执行注入脚本。
2. `/forum/uploads/` 只带 `X-Content-Type-Options`，没有 HSTS / XFO / Referrer-Policy（该 location 自带 `add_header`，不继承 server 级；nginx 1.22 没有 `add_header_inherit`）。
3. HSTS 是否加 `includeSubDomains` 从未评估记录。
4. Cloudflare 面板侧的 Always Use HTTPS / HSTS / TLS 模式等设置只存在于人的记忆里，没有可核对的清单。

首页现有的「防闪烁」内联脚本是 CSP 的直接障碍：`script-src 'self'` 会拒绝它。因此本轮的核心设计取舍是**内联脚本怎么办**。

## 2. 目标与非目标

### 目标

- 给 `/forum/` 的 HTML 响应启用**强制** CSP（不是 Report-Only），`script-src` 收窄到 `'self'` + 该内联脚本的 SHA-256。
- 内联脚本保持「先于首帧执行」，不引入 FOUC，不改前端运行时行为。
- 用**自动化测试**把「内联脚本内容 ↔ CSP 哈希」和「公网 conf ↔ 内网 conf 的 CSP 一致」钉死，避免以后改脚本导致线上脚本被 CSP 静默拒绝。
- `/forum/uploads/` 补齐 4 个安全头。
- `deploy.sh` 增加 CSP 与 uploads 头的部署断言。
- 文档固化 HSTS `includeSubDomains` 决策过程与 Cloudflare 设置清单。

### 非目标

- 不改业务代码、不改 Vue 组件、不动 Element Plus 引入方式（后端 177 / 前端 323 基线中的用例不因功能改动而变化）。
- 不引入 CSP nonce 机制（需要服务端渲染或 HTML 改写，收益低于哈希方案）。
- 不追求 `style-src` 去掉 `'unsafe-inline'`：Vue 的 `:style` 绑定、Element Plus 的 popper 定位、Markdown 内联样式都依赖行内样式，去掉会让整站样式失效。
- 不改 `connect-src` 白名单之外的服务；站点没有第三方分析/字体/CDN。
- 不做 Report-Only 观察期（本站无 CSP 上报收集端，Report-Only 只在控制台产生噪音；改为部署后用 Playwright 实测 + 部署断言 + 快速回滚方案兜底）。

## 3. 现状审计

### 3.1 首页内联脚本

`frontend/index.html` 第 13–24 行：

```html
<script>
  ;(function () {
    try {
      var saved = localStorage.getItem('jf_theme')
      var dark =
        saved === 'dark' ||
        (saved !== 'light' && window.matchMedia('(prefers-color-scheme: dark)').matches)
      if (dark) document.documentElement.classList.add('dark')
      document.documentElement.style.colorScheme = dark ? 'dark' : 'light'
    } catch (e) {}
  })()
</script>
```

- 唯一的内联脚本；`dist/index.html` 里仍是这一个（其余是 `<script type="module" crossorigin src="/forum/assets/...">`）。
- 实测 SHA-256（源码与 `dist/index.html` 完全相同，Vite 不改写它）：
  `sha256-Sbv+SnhhPDOg1J2CVOMR4HoLBVSxnmSJ54wG/DCJq94=`
- `frontend/src/styles/tokens.spec.js` 已断言它读取 `STORAGE_KEY`、添加 `dark` 类、注释指向 `useTheme.js`；本轮只新增哈希一致性断言，不改这些既有断言。

### 3.2 现有安全头落点（nginx 1.22，无 `add_header_inherit`）

| 响应 | 现行头 | 落点 |
| --- | --- | --- |
| `/forum/` HTML、SPA 路由 | 4 头 + `no-cache` | `deploy/nginx/forum.conf` 的 `location = /forum/index.html` |
| `/forum/assets/*` | 4 头 + immutable | `location ^~ /forum/assets/` |
| `/forum/uploads/*` | 仅 `X-Content-Type-Options` | `location ^~ /forum/uploads/` |
| `/forum/api/*`、SSE | 4 头（server 级继承） | `deploy/nginx/public.conf` server 块；内网 `app.conf` 无 server 级头（API 不是文档，缺头可接受） |

没有 CSP，任何位置都没有。

### 3.3 前端能力盘点（决定 CSP 指令）

| 能力 | 结论 | 对应指令 |
| --- | --- | --- |
| 内联 `<script>` | 仅 `index.html` 主题引导 | `script-src 'self' 'sha256-…'` |
| `eval` / `new Function` | 全仓 0 处 | 不必加 `'unsafe-eval'` |
| 内联 `style=` / Vue `:style` | 多处（EP popper、宽度、Markdown） | `style-src 'self' 'unsafe-inline'` |
| 外部图片（Markdown 里 `![](https://…)`） | 允许，DOMPurify 保留 `img` | `img-src 'self' data: https:` |
| `blob:` / `createObjectURL` | 0 处 | 不需要 `blob:` |
| 字体 | 无 `@font-face`，系统字体 + EP 的 SVG 图标 | `font-src 'self' data:` |
| XHR / SSE | 同源 `/forum/api/…` | `connect-src 'self'` |
| 新窗口链接 | Markdown 外链 `target=_blank rel=noopener` | 不需要指令 |
| `v-html` | 只有 `renderMarkdown` 消毒后的输出 | 说明 CSP 是第二道防线 |

## 4. 方案

### R1：CSP 策略值（单一定义，两处引用）

```
default-src 'self'; base-uri 'self'; object-src 'none'; script-src 'self' 'sha256-Sbv+SnhhPDOg1J2CVOMR4HoLBVSxnmSJ54wG/DCJq94='; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self' data:; connect-src 'self'; form-action 'self'; frame-ancestors 'self'
```

- 不加 `upgrade-insecure-requests`：站点全站 https，公网入口已强制 308；内网仍是 http，加了会打断内网调试。`img-src` 已限制 https。
- `frame-ancestors 'self'` 是 XFO 的现代等价物；`X-Frame-Options` 保留给旧浏览器，两者并存不冲突。
- 不加 `report-uri`/`report-to`：没有收集端。
- 不加 `'unsafe-inline'` 到 `script-src`：唯一的豁免用哈希精确放行。

### R2：CSP 下发点

- `deploy/nginx/public.conf`：server 块，`add_header Content-Security-Policy "<R1>" always;`（覆盖该 server 下所有响应，含 404/308；HTML 的实际生效值来自 index.html location 的同值头）。
- `deploy/nginx/forum.conf`：`location = /forum/index.html` 内，与其余 4 头并列再加一条 CSP（内网 `raspi` 入口没有 server 级头，必须在这里补）。
- `uploads`/`assets` 不加 CSP（非文档响应；assets 是 JS/CSS，CSP 对它们无意义）。
- 两份 conf 的 CSP 字符串必须**逐字相同**，由 R4 的测试守护。

### R3：内联脚本保持内联，用哈希放行

不搬运脚本、不引入 nonce，理由：

1. 外置成 `<script src="%BASE_URL%theme-init.js">` 会让首屏多一次阻塞请求（公网经 CF，多 1 个 RTT），主题闪烁风险上升；且 `public/` 文件没有 content hash，还得单独配缓存策略。
2. nonce 需要每次响应生成并注入 HTML，而本站是纯静态 nginx，需要 `sub_filter` 或后端改写，复杂度和回归面都更大。
3. 哈希方案的唯一成本是「脚本改了哈希要同步」，这正好可以用一个 node 环境的 vitest 用例覆盖（`frontend/src/plugins/csp.spec.js`），比人工纪律可靠。

### R4：测试守护（`frontend/src/plugins/csp.spec.js`，新增）

node 环境（`// @vitest-environment node`），只读文件、不启动浏览器：

1. 从 `frontend/index.html` 提取唯一内联脚本，计算 `sha256-<base64>`，断言 `deploy/nginx/forum.conf` 的 CSP 含该哈希。
2. 断言 CSP 含必备指令（`default-src 'self'`、`base-uri 'self'`、`object-src 'none'`、`connect-src 'self'`、`form-action 'self'`、`frame-ancestors 'self'`），且 `script-src` 不含 `'unsafe-inline'`/`'unsafe-eval'`/`*`。
3. 断言 `deploy/nginx/forum.conf` 与 `deploy/nginx/public.conf` 的 CSP 字符串完全一致。
4. 断言 `deploy/nginx/forum.conf` 的 `uploads` location 含 4 个安全头（`always`）。
5. 断言 `deploy/deploy.sh` 含 CSP 断言与 uploads 头断言（防以后被人删掉）。

预期 RED：实现前 5 条全红（conf 里没有 CSP、uploads 缺 3 头）。

### R5：`/forum/uploads/` 补齐 4 头

```nginx
location ^~ /forum/uploads/ {
    alias /pd/Program/forum/uploads/;
    expires 7d;
    access_log off;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;
    add_header Strict-Transport-Security "max-age=31536000" always;
}
```

- 注意 `expires 7d` 仍生成一条 `Cache-Control`，这是唯一一条，没有第二条 add_header Cache-Control，所以不会出现双头。
- `nosniff` 从「无 always」改为 `always`，让 404 也带（deploy.sh 用不存在的文件名断言时依赖这一点）。

### R6：HSTS `includeSubDomains` 决策

结论：**本轮不开**。理由与复核方法写进文档：

- `treenewbee.men` 下是否还有其它子域、是否依赖 HTTP，无法从仓库推断；一旦开 `includeSubDomains` 且某子域只有 HTTP，浏览器会强制 https 打不开，回滚需要等 max-age 过期（或依赖 `Strict-Transport-Security: max-age=0` 清除，但要求用户先访问到响应）。
- 复核方法：`dig +short treenewbee.men ANY`、检查 Cloudflare DNS 记录列表、对每个子域跑 `curl -sI http://<sub>` 看是否有 200 明文；确认无明文依赖后，再改 `max-age` 并加 `includeSubDomains`。
- 文档同时写明：若将来开启，先把 `max-age` 降回 300 秒观察一天，再升到一年。

### R7：Cloudflare 面板设置清单（仅文档）

`docs/deployment/README.md` 增「Cloudflare 侧设置核对表」：SSL/TLS 模式 Full (strict)、Always Use HTTPS 开启、Automatic HTTPS Rewrites 视情况、HSTS 若在 CF 侧开启需与源站一致、Minimum TLS 1.2、Bot Fight Mode 可选、WAF 规则建议。每条给出「在哪里看 / 期望值 / 与源站的关系」。

## 5. 验证矩阵

| # | 场景 | 期望 |
| --- | --- | --- |
| V1 | `https://treenewbee.men/forum/` | 200，含 CSP 且 `script-src` 是无 `unsafe-inline` 的白名单 |
| V2 | 公网 SPA 路由 `/forum/posts/1` | 与 V1 相同的 CSP |
| V3 | 内网 `http://127.0.0.1/forum/`（`Host: raspi`） | 含相同 CSP（来自 snippet 的 index location） |
| V4 | `/forum/assets/*.js` | 单条 immutable + gzip，无 CSP 也不影响 |
| V5 | `/forum/uploads/<不存在>.png` | 404 + 4 个安全头（含 `always` 生效） |
| V6 | 浏览器首屏（Playwright，公网部署后） | 无 CSP 违规（console 无 `Refused to …`）、主题按 `jf_theme` 生效、暗色可切、头像加载、Markdown 页与删除确认框正常 |
| V7 | SSE | `/forum/api/notifications/stream` 仍 401（不因 CSP 变化） |
| V8 | `deploy.sh` | 新增断言全过（CSP、uploads 4 头） |
| V9 | 回滚演练（文档） | 删除 CSP 两行 + `deploy.sh` 两段断言 + 重跑部署即恢复；CSP 是纯响应头，无数据影响 |

## 6. 测试与基线

- 后端：**177 / 0 / 0 / 1**（21 类，不变——无 Java 改动）。
- 前端：**323 → 328**（+5，新增 `frontend/src/plugins/csp.spec.js` 5 条）；spec 文件 **32 → 33**。
  - 5 条：1) 内联脚本哈希与 forum.conf 的 CSP 一致 2) 必备指令齐全且 `script-src` 无 `unsafe-inline`/`unsafe-eval`/`*` 3) forum.conf 与 public.conf 的 CSP 逐字相同 4) uploads location 含 4 个安全头且都是 `always` 5) deploy.sh 含 CSP 与 uploads 头断言。
  - README / DEVELOPMENT 同步写 **328 / 33**。
- 构建：`VITE_BASE_PATH=/forum/ pnpm build` + `pnpm check:bundle` 不回归（本轮不动 JS/CSS 体积）。
- Jenkins 默认不跑前端测试（`RUN_TESTS` 参数控制），所以测试提交在 CI 里不会红；本地必须按 RED→GREEN 执行。

## 7. 风险与回滚

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| CSP 把某个合法来源挡掉（如 Markdown 里 http 图片） | 图片不显示 | `img-src` 允许 `https:`；http 图片本就受混合内容限制，属预期；V6 实测 |
| 哈希不同步（脚本改了忘了改 CSP） | 首屏主题脚本被拒，暗色闪一下 | csp.spec 用例 1 守护；部署断言只验头存在，测试才是哈希的守门人 |
| 内联样式被拦 | 样式错乱 | `style-src 'unsafe-inline'` 已放行 |
| 旧浏览器不支持 CSP | 无影响 | CSP 是渐进增强，未知指令忽略 |
| Cloudflare 改写了响应头 | CSP 失效或被改 | CF 默认不改源站头；V1/V2 实测校验 |
| 上线后才发现被拦 | 页面功能受损 | 回滚 = 删两行 add_header + 重跑部署（分钟级） |

## 8. 文档改动

- `docs/DEVELOPMENT.md`：新增 §4.16「第十一轮：安全头收敛与 CSP（2026-10-31）」；§3.2 前端基线 323→328、spec 32→33；§5.2 候选更新；§8 变更日志。
- `docs/deployment/README.md`：公网验证小节加 CSP 命令；§7 已知限制更新（uploads 缺头那条改写、CSP `style-src 'unsafe-inline'` 理由、HSTS 决策、CF 清单）；新增「Cloudflare 侧设置核对表」。
- `README.md`：安全小节加 CSP 一句与测试基线。
- 计划文件：§12 实施记录与偏差。

## 9. 上线验收

- 构建：Build #28 起（本轮首个部署提交）；记录构建号与镜像 tag。
- V1–V9 全过；`deploy.sh` 断言数从 13 组增至 15 组。
- 回填：§4.16 上线验收段、§8 哈希、部署手册构建号、计划 checkbox。
