# 第十轮设计：公网上线验证与运维加固

> 状态：已定稿（2026-10-28）
> 上游：`600a8aa fix: 公网 Cloudflare Tunnel 入口的 nginx server 块`（本地未推送）
> 目标：把「公网入口能打开」推进到「公网入口可验证、可回归、可加固」

## 1. 背景

第九轮上线后，仓库外追加了一次未推送的改动 `600a8aa`：为 Cloudflare Tunnel 带来的 `Host: treenewbee.men` 新增独立 server 块 `deploy/nginx/public.conf`，只 include `forum.conf` 片段，避免把 `app.conf` 里的 `/imageweb/`、`/manager/`、`/files/`、`/api/` 等内网应用带到公网；`deploy.sh` 增加公网 Host 200 与 `/imageweb/` 404 两条断言。

该 commit 已经存在于本地 master，但：

1. 尚未推送，Jenkins 从未构建过它；
2. 公网现场没有做过端到端验收（登录、限流真实 IP、缓存头、SSE、上传）；
3. `docs/DEVELOPMENT.md` 没有第十轮记录，部署手册只有三段说明；
4. 公网响应缺少 HTTPS 强制跳转与基础安全响应头（实测无 HSTS / nosniff / X-Frame-Options / Referrer-Policy）；
5. `deploy.sh` 只断言「公网首页 200」，公网专属的缓存头、SSE、跳转都没有进入回归。

本轮只做运维与验证，不改业务功能。

## 2. 范围

### 2.1 范围内

- 推送 `600a8aa`，跑一次 Jenkins 部署，确认公网入口在真实流水线里可用。
- 公网入口加固：HTTP→HTTPS 强制跳转、HSTS、`X-Content-Type-Options`、`X-Frame-Options`、`Referrer-Policy`。
- 把公网专属行为固化进 `deploy/deploy.sh` 断言（本机 `Host` + `X-Forwarded-Proto` 模拟，不依赖外网）。
- 公网端到端验收：首页/静态资源/API/SSE/上传、HTTP 跳转、缓存头与 gzip、登录与登出、限流按真实 IP 计数与隔离。
- 文档：`docs/DEVELOPMENT.md` 新增 §4.15、部署手册补验收命令、README 补公网地址、计划 §12 记录实施偏差。

### 2.2 非目标

- 不改 Java / Vue 业务代码，测试基线保持后端 177、前端 323。
- 不配置 Cloudflare 账号内的设置（Always Use HTTPS、WAF、HSTS 开关）；本轮只在源站 nginx 兜底，并把 CF 侧建议写进文档。
- 不引入新的限流存储、不加 CSP（论坛有内联主题脚本与 Markdown 内联样式，CSP 需单独一轮设计）。
- 不处理 §5.1 既有技术债（T1 历史密码、T3 E2E 等）。

## 3. 现状审计（实测，2026-10-28）

| 项 | 实测结果 |
| --- | --- |
| 本地/远端 | HEAD `600a8aa`；远端 master 仍是 `9199d91`（未推送） |
| 仓库状态 | 工作区干净；`deploy/nginx/public.conf` 41 行 |
| 公网 HTTPS | `https://treenewbee.men/forum/` → 200（约 2.7 s） |
| 公网 HTTP | `http://treenewbee.men/forum/` → **200，不跳 https** |
| 内网应用暴露 | `https://treenewbee.men/imageweb/` → 404 ✓ |
| 公网响应头 | 有 `Cache-Control: no-cache`；**无** HSTS / nosniff / X-Frame-Options / Referrer-Policy |
| 不存在的 asset | 404，带 CF 边缘加的 `Cache-Control: max-age=14400`、`cf-cache-status: MISS` |
| cloudflared | 系统服务 active，版本 2026.10.5，ingress `treenewbee.men → http://localhost:80` |
| nginx | 1.22.1；`/etc/nginx/conf.d/forum-public.conf` 已由 `600a8aa` 部署，`server_name treenewbee.men` 在第 319 行 |
| realip | 公网请求在 nginx access log 里显示真实 IP `27.17.82.164`；`$realip_remote_addr` = `::1`（仅回环可伪造） |
| 转发头（临时探针实测） | cloudflared 会传 `X-Forwarded-Proto: https/http` 与 `CF-Visitor: {"scheme":...}`；探针已撤回，配置已恢复 |

关键结论：

1. `600a8aa` 的路由修复是有效的，公网页面已经能打开，问题只剩「未验证 + 加固 + 回归」。
2. 源站 nginx 能拿到 `X-Forwarded-Proto`，因此 HTTP→HTTPS 跳转可以在源站实现，不依赖 Cloudflare 面板设置。
3. nginx 1.22 没有 `add_header_inherit merge`（1.29+ 才有）：location 里一旦有 `add_header`（`/forum/assets/`、`/forum/index.html` 都有），就不再继承 server 级 `add_header`。因此安全头只能稳定覆盖 HTML/API 响应，静态资源的响应不会带安全头——记录为已知限制，不为它拆分 location。

## 4. 方案

### R1 公网强制 HTTPS（源站兜底）

在 `deploy/nginx/public.conf` 的 server 块内：

```nginx
# cloudflared 会传 X-Forwarded-Proto（实测 http/https 均有），公网明文请求在源站 308 到 https。
# 用 308 而不是 301：POST 登录/发帖重定向后仍保持方法与请求体。
if ($http_x_forwarded_proto = "http") {
    return 308 https://treenewbee.men$request_uri;
}
```

- 只看 `X-Forwarded-Proto`，不看 `$scheme`：nginx 与 cloudflared 之间永远是 http，看 `$scheme` 会导致无限跳转。
- 本机带 `Host: treenewbee.men` 的部署断言没有该头，不受影响。
- 内网入口 `Host: raspi` 不走该 server 块，行为不变。

### R2 基础安全响应头（server 级）

```nginx
add_header X-Content-Type-Options "nosniff" always;
add_header X-Frame-Options "SAMEORIGIN" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
add_header Strict-Transport-Security "max-age=31536000" always;
```

- HSTS 不带 `includeSubDomains`：该域名下可能还有其它内网应用子域，避免误伤。
- 不设 CSP、不加 `Permissions-Policy`：留待单独一轮。
- 已知限制：`/forum/assets/`、`/forum/index.html` 自带 `add_header`，nginx 1.22 下不继承 server 级安全头；HSTS 在首次 HTML 响应即下发，浏览器后续补全为 https。

### R3 realip 保持不变

`600a8aa` 的 `set_real_ip_from 127.0.0.1/::1` + `real_ip_header CF-Connecting-IP` 已验证正确（access log 真实 IP、`$realip_remote_addr` 回环）。本轮只在 `deploy.sh` 里补一条「公网路径按真实 IP 限流」的手工验收步骤，不改配置。

### R4 `deploy.sh` 增加公网断言（全部本机模拟）

`deploy.sh` 跑在 raspi 上，新增以下断言（不访问外网）：

| 断言 | 命令要点 | 期望 |
| --- | --- | --- |
| 公网强制 HTTPS | `-H 'Host: treenewbee.men' -H 'X-Forwarded-Proto: http'` GET `/forum/` | `308`，`Location: https://treenewbee.men/...` |
| 公网安全头 | `-H 'Host: treenewbee.men'` HEAD `/forum/` | 四个头齐全 |
| 公网 asset 缓存 | 从公网 Host 的首页取 asset 路径，HEAD 它 | `200` + 单条 `Cache-Control: ... immutable` |
| 公网 SSE | `-H 'Host: treenewbee.men'` GET `/forum/api/notifications/stream` | `401` |

断言风格与第九轮一致：命令替换末尾 `|| true`、状态码先断言、失败信息带实际值。

### R5 Cloudflare 侧建议只写文档

- 建议在 Cloudflare 面板打开 Always Use HTTPS（源站已有兜底，双保险）。
- 记录 CF 免费版对 404 的默认边缘缓存头、以及不缓存 HTML 的行为，避免误判为源站配置问题。

### R6 不改业务代码与测试基线

本轮 0 个 Java/Vue 改动，后端仍 177 用例 / 21 类、前端仍 323 用例 / 32 spec。

## 5. 公网端到端验证矩阵

| # | 项 | 方法 | 期望 |
| --- | --- | --- | --- |
| V1 | 公网 HTTPS 首页 | `curl -I https://treenewbee.men/forum/` | 200，`no-cache` |
| V2 | 公网 HTTP 跳转 | `curl -I http://treenewbee.men/forum/` | 308 → https |
| V3 | 内网应用不暴露 | `https://treenewbee.men/imageweb/`、`/manager/` | 404 |
| V4 | 静态资源 | 公网首页 asset：状态、单条 immutable、`Content-Encoding: gzip` | 200 / 1 条 / gzip |
| V5 | API | `https://treenewbee.men/forum/api/health` | 200 `status=UP` |
| V6 | SSE | 公网 `/forum/api/notifications/stream` 无 ticket | 401 |
| V7 | 上传 | 公网 `/forum/uploads/__probe__/missing.jpg` | 404（不落到 SPA） |
| V8 | 登录/登出 | 公网 `POST /forum/api/auth/login` → me → logout → 旧 token me | 200 / 200 / 200 / 401 |
| V9 | realip | 公网请求后看 nginx access log 的 `$remote_addr` | 真实公网 IP |
| V10 | 按真实 IP 限流 | 公网连续 5 次错密码 → 第 6 次 | 429 + `Retry-After`，后端日志 `WARN ... 登录失败 5 次` 且 IP 为真实 IP |
| V11 | 限流隔离 | 同时用内网 `X-Real-IP: 203.0.113.99`（环回可信）正确密码 | 200，说明未按环回地址共桶 |

V10 会把验收机自己的公网 IP 锁 15 分钟。验收顺序：先完成 V8 等需要登录的步骤，再做 V10；完成后用 `podman restart forum-backend` 清空内存计数（重启即清，属既有语义），再复核 V1/V8。

## 6. 文档改动

- `docs/DEVELOPMENT.md`：新增 §4.15「第十轮：公网入口验证与加固（2026-10-28）」（背景、变更、验证矩阵结果、部署形态补充、已知限制、上线验收占位）；§5.2 增补候选（CSP、CF 侧设置纳入 IaC）；§8 变更日志新增本轮 3 行。
- `docs/deployment/README.md`：补公网验收命令（HTTP 跳转、安全头、asset、SSE）；§7 已知限制补「assets/index.html 不继承安全头」「CF 404 边缘缓存头」。
- `README.md`：在线地址补公网 URL（`https://treenewbee.men/forum/`），与内网地址并列。
- 计划文件：§12 追加第十轮实施记录。

## 7. 风险与回滚

| 风险 | 影响 | 处理 |
| --- | --- | --- |
| `X-Forwarded-Proto` 条件误判 | 公网循环跳转或无法跳转 | 只认该头；上线后立即 V2 验证；失败则回退 `public.conf` 到 `600a8aa` 版本并 reload |
| HSTS 误伤子域 | 内网 https 子域被强制 | 不带 `includeSubDomains`，max-age 一年 |
| 部署断言过脆 | 构建误红 | 断言全部本机、带 `--max-time`、失败信息含实际值 |
| 限流验收锁住验收 IP | 15 分钟内验收机无法登录 | 验收顺序前置 V8；结束 `podman restart forum-backend` 清计数 |
| CF 侧缓存干扰判断 | 误判源站配置 | 文档记录 `cf-cache-status` 读法 |

回滚：`git revert` 本轮代码提交 → 推送 → Jenkins 构建 → `deploy.sh` 幂等覆盖 nginx 与静态资源。

## 8. 上线验收

1. Jenkins 构建成功，镜像为构建号，容器 healthy。
2. V1–V11 全部通过，记录构建号、镜像 tag、`Retry-After` 实际值、日志原文。
3. `deploy.sh` 新增的 4 条公网断言在本次部署日志里出现且通过。
4. 文档 §4.15 回填上述结果，计划 checkbox 全勾。
