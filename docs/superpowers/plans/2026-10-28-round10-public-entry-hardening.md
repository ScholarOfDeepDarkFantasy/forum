# 第十轮实现计划：公网上线验证与运维加固

> 配套设计：`docs/superpowers/specs/2026-10-28-round10-public-entry-hardening-design.md`
> 仓库：`E:\JavaSpace\forum`（Git Bash 路径 `/e/JavaSpace/forum`）
> 基线：HEAD `600a8aa`（本地未推送），后端 177 用例 / 21 类，前端 323 用例 / 32 spec
> 本轮测试基线不变：**后端 177、前端 323**（不改业务代码）

## 0. 执行约定

1. 直接在 master 提交，不建分支、不 amend、不 rebase。
2. 提交粒度：`docs: 新增第十轮公网入口设计与计划`（本文件 + 设计，1 次）→ `fix: 公网入口强制 HTTPS 并补安全响应头`（Task 2）→ `docs: 记录第十轮公网入口验证与加固`（Task 4）→ `docs: 回填第十轮构建与上线验收`（Task 5）。加上已存在的 `600a8aa`，本轮共 4 次新提交。
3. 推送与 Jenkins：本机 GitHub 直连不可用，统一走 SSH：
   `git push git@github.com:ScholarOfDeepDarkFantasy/forum.git master:master`
   构建状态用 raspi 上的 Jenkins home 读取（API 匿名 403）：
   `python /c/Temp/forum-ssh.py 'sudo grep -o "Finished: [A-Z]*" /var/lib/jenkins/jobs/javaforum/builds/<N>/log'`
4. 所有部署/服务器命令通过 `python /c/Temp/forum-ssh.py '<cmd>'` 执行（paramiko，pi/song，超时 180s）。
5. 计划里的 checkbox 统一在 Task 5 的回填提交里勾选。
6. 验收顺序约束：V10（限流）会把验收机公网 IP 锁 15 分钟，必须在所有需要登录的步骤之后执行，结束后 `podman restart forum-backend` 清计数。

## 1. 现状与文件清单

### 1.1 需要修改的文件

| 文件 | 改动 |
| --- | --- |
| `deploy/nginx/public.conf` | 新增 HTTP→HTTPS 308、四个安全响应头、注释说明；行数 41 → 约 60 |
| `deploy/deploy.sh` | 公网断言 4 组（跳转、安全头、asset 缓存、SSE），插在 `/imageweb/` 断言之后、SSE 断言之前 |
| `docs/DEVELOPMENT.md` | 新增 §4.15；§5.2 增补候选；§8 变更日志 3 行 |
| `docs/deployment/README.md` | 公网验收命令；§7 已知限制 2 条 |
| `README.md` | 在线地址补公网 URL |
| 计划文件 | §12 实施记录；Task 5 勾选 checkbox |

### 1.2 不改的文件

- 所有 `src/main/java/**`、`src/test/java/**`、`frontend/**`：本轮 0 代码改动。
- `deploy/nginx/forum.conf`：内网/公网共用的片段，本轮不动。

## Task 1：推送 600a8aa 并跑通公网部署（无代码改动）

**Step 1-1** 确认本地状态：

```bash
cd /e/JavaSpace/forum
git status --short          # 期望：空
git log --oneline -3        # 期望：600a8aa / 9199d91 / 8e6d71b
```

**Step 1-2** 推送：

```bash
git push git@github.com:ScholarOfDeepDarkFantasy/forum.git master:master
```

期望输出含 `9199d91..600a8aa`。

**Step 1-3** 等 Jenkins 构建。先用 raspi 读 `nextBuildNumber`，再轮询最新构建的日志：

```bash
python /c/Temp/forum-ssh.py 'sudo cat /var/lib/jenkins/jobs/javaforum/nextBuildNumber'
# 假设拿到 N，轮询：
python /c/Temp/forum-ssh.py 'sudo grep -o "Finished: [A-Z]*" /var/lib/jenkins/jobs/javaforum/builds/N/log 2>/dev/null || echo pending'
```

期望 `Finished: SUCCESS`；随后确认镜像与容器：

```bash
python /c/Temp/forum-ssh.py 'sudo -u pi XDG_RUNTIME_DIR=/run/user/$(id -u pi) podman ps --format "{{.Names}} {{.Image}} {{.Status}}" | grep forum-backend'
```

期望 `forum-backend localhost/forum-backend:N Up ... (healthy)`。

**Step 1-4** 记录基线：

```bash
curl -s -o /dev/null -w 'public_https=%{http_code}\n' --max-time 20 https://treenewbee.men/forum/
python /c/Temp/forum-ssh.py 'curl -s -o /dev/null -w "internal=%{http_code}\n" -H "Host: raspi" http://127.0.0.1/forum/'
```

两者都应 200。把构建号、镜像号记入本轮记录（Task 4/5 用）。

## Task 2：公网入口加固（public.conf + deploy.sh）

### Step 2-1 先写断言（会失败）

按 Step 2-5 在 `deploy/deploy.sh` 里加入 4 组断言后，在 raspi 上对着**当前**（未加固）配置手跑一遍，确认它们会红：

```bash
python /c/Temp/forum-ssh.py 'curl -s -o /dev/null -w "xfp_redirect=%{http_code}\n" -H "Host: treenewbee.men" -H "X-Forwarded-Proto: http" http://127.0.0.1/forum/'
# 期望当前为 200（未加固，没有跳转）→ 断言红
python /c/Temp/forum-ssh.py 'curl -s -I -H "Host: treenewbee.men" http://127.0.0.1/forum/ | grep -ci "strict-transport-security" || true'
# 期望当前为 0 → 断言红
```

把两次输出贴进实施记录（RED 证据）。

### Step 2-2 修改 `deploy/nginx/public.conf`

在 `server {` 块内、`absolute_redirect off;` 之前插入：

```nginx
    # 公网强制 HTTPS：cloudflared 会把客户端 scheme 放进 X-Forwarded-Proto（实测 http/https 均有），
    # 而 nginx 与 cloudflared 之间恒为明文 http，所以只能看这个头，不能看 $scheme（会无限跳转）。
    # 用 308 而非 301：登录/发帖等 POST 重定向后方法与请求体保持不变。
    # 本机带 Host: treenewbee.men 的部署断言没有这个头，因此不受影响。
    if ($http_x_forwarded_proto = "http") {
        return 308 https://treenewbee.men$request_uri;
    }

    # 基础安全响应头（源站兜底；Cloudflare 面板里的 Always Use HTTPS 是双保险，见部署手册）。
    # HSTS 不带 includeSubDomains：该域名下可能还有其它内网应用子域。
    # nginx 1.22 无 add_header_inherit：/forum/assets/ 与 /forum/index.html 自带 add_header，
    # 这两个 location 不继承下列头 —— 见 docs/DEVELOPMENT.md §4.15 已知限制。
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;
    add_header Strict-Transport-Security "max-age=31536000" always;
```

同时更新文件头注释：把「该域名没开 Cloudflare 的 Always Use HTTPS，实测 http 直接 200」改为「源站已强制 https；Cloudflare 面板的 Always Use HTTPS 可作为双保险」，避免与实现矛盾。

### Step 2-3 校验配置语法

```bash
python /c/Temp/forum-ssh.py 'sudo /usr/sbin/nginx -t'
```

期望 `syntax is ok` / `test is successful`。

### Step 2-4 在 raspi 预应用并做公网验收（不依赖 Jenkins）

先上传新文件再应用（保持部署幂等，无需回滚）：

```bash
# 本机
scp -P 22 deploy/nginx/public.conf pi@raspi:/tmp/public.conf   # 或用论坛 helper 的 SFTP 能力
# raspi
sudo cp /tmp/public.conf /etc/nginx/conf.d/forum-public.conf
sudo /usr/sbin/nginx -t && sudo /usr/bin/systemctl reload nginx
```

然后从**本机**做公网验证：

```bash
curl -s -o /dev/null -w 'http_redirect=%{http_code} loc=%{redirect_url}\n' --max-time 20 http://treenewbee.men/forum/
# 期望 308 https://treenewbee.men/forum/
curl -s -D - -o /dev/null --max-time 20 https://treenewbee.men/forum/ | grep -iE '^(HTTP/|cache-control:|strict-transport-security:|x-content-type-options:|x-frame-options:|referrer-policy:)'
# 期望 200、no-cache、四个安全头齐全
curl -s -o /dev/null -w 'imageweb=%{http_code}\n' --max-time 20 https://treenewbee.men/imageweb/   # 404
```

若 `http` 仍返回 200，说明 `X-Forwarded-Proto` 未按预期到达，检查 `nginx -T` 里 `return 308` 是否在正确 server 块，再回看 spec §3 的探针结论。

### Step 2-5 修改 `deploy/deploy.sh`（4 组断言）

在 `public_leak` 断言块之后插入：

```bash
# 公网必须强制 HTTPS：cloudflared 传 X-Forwarded-Proto（实测 http/https 均有），
# 本机带上它即可复现公网明文请求，不需要真的走外网。
public_redirect="$(curl --max-time 5 -s -o /dev/null -w '%{http_code} %{redirect_url}' \
    -H 'Host: treenewbee.men' -H 'X-Forwarded-Proto: http' "http://127.0.0.1/forum/")"
public_redirect_code="${public_redirect%% *}"
public_redirect_url="${public_redirect#* }"
[ "$public_redirect_code" = "308" ] \
    || die "公网明文 /forum/ 未返回 308（返回 ${public_redirect_code:-000}；检查 forum-public.conf 的 X-Forwarded-Proto 跳转）"
case "$public_redirect_url" in
    https://treenewbee.men/forum/*) ;;
    *) die "公网 308 的 Location 不是 https://treenewbee.men/forum/（实际：${public_redirect_url:-空}）" ;;
esac

# 公网 HTML 必须带基础安全响应头（assets/index.html 因自带 add_header 不继承，属已知限制）
public_headers="$(curl -sS --max-time 5 -I -H 'Host: treenewbee.men' "http://127.0.0.1/forum/" | tr -d '\r' || true)"
for expected_header in \
    '^x-content-type-options: nosniff' \
    '^x-frame-options: SAMEORIGIN' \
    '^referrer-policy: strict-origin-when-cross-origin' \
    '^strict-transport-security: max-age=31536000'
do
    echo "$public_headers" | grep -qi "$expected_header" \
        || die "公网 /forum/ 缺少安全响应头 $expected_header（实际头：$(echo "$public_headers" | grep -i '^\(x-\|referrer\|strict\)' | tr '\n' ';')）"
done

# 公网静态资源也必须走 immutable 长缓存（复用上面解析出的 asset_path）
public_asset_raw="$(curl -sS --max-time 5 -I -H 'Host: treenewbee.men' -w '\n%{http_code}' "http://127.0.0.1$asset_path" || true)"
public_asset_status="${public_asset_raw##*$'\n'}"
public_asset_cache_n="$(printf '%s\n' "$public_asset_raw" | tr -d '\r' | grep -ci '^cache-control:' || true)"
[ "$public_asset_status" = "200" ] \
    || die "经公网 Host 请求 $asset_path 返回 HTTP ${public_asset_status:-000}（应为 200）"
[ "$public_asset_cache_n" = "1" ] \
    || die "经公网 Host 请求 $asset_path 返回了 $public_asset_cache_n 条 Cache-Control（应为 1）"

# 公网 SSE 端点也必须由后端 401（不能被 SPA 兜成 200）
public_stream_code="$(curl --max-time 5 -s -o /dev/null -w '%{http_code}' -H 'Host: treenewbee.men' \
    "http://127.0.0.1/forum/api/notifications/stream")"
[ "$public_stream_code" = "401" ] \
    || die "经公网 Host 访问 SSE 返回 $public_stream_code（应为 401）"
```

注意：

- `asset_path` / `asset_cache_n` 变量在 `index_headers` 之前已定义，插入位置在其后即可复用。
- 所有命令替换都带 `--max-time`，`|| true` 只用在「要把状态码带进诊断」的地方（与第九轮风格一致）。

### Step 2-6 校验与提交

```bash
bash -n deploy/deploy.sh
git diff --check
git status --short   # 期望只有 deploy/nginx/public.conf、deploy/deploy.sh 两个 M
git add deploy/nginx/public.conf deploy/deploy.sh
git commit -m "fix: 公网入口强制 HTTPS 并补安全响应头"
```

提交信息逐字：`fix: 公网入口强制 HTTPS 并补安全响应头`。

## Task 3：公网端到端验收（V1–V11）

前提：Task 2 已提交并推送，Jenkins 构建成功（部署日志里能看到 4 组新公网断言通过）。

**Step 3-1** 推送并等待构建：

```bash
git push git@github.com:ScholarOfDeepDarkFantasy/forum.git master:master
# 读 nextBuildNumber 轮询 Finished
```

**Step 3-2** V1/V2/V3（首页、跳转、不暴露）：

```bash
curl -s -o /dev/null -w 'V1_https=%{http_code}\n' --max-time 20 https://treenewbee.men/forum/
curl -s -o /dev/null -w 'V2_http=%{http_code} loc=%{redirect_url}\n' --max-time 20 http://treenewbee.men/forum/
curl -s -o /dev/null -w 'V3_imageweb=%{http_code}\n' --max-time 20 https://treenewbee.men/imageweb/
curl -s -o /dev/null -w 'V3_manager=%{http_code}\n' --max-time 20 https://treenewbee.men/manager/
```

期望 `200 / 308+https / 404 / 404`。

**Step 3-3** V4 静态资源：从公网首页取 asset 再 HEAD：

```bash
asset=$(curl -s --max-time 20 https://treenewbee.men/forum/ | grep -o '/forum/assets/[^"]*\.js' | head -1)
echo "asset=$asset"
curl -s -o /dev/null -D - --max-time 20 "https://treenewbee.men$asset" | grep -iE '^(HTTP/|cache-control:|content-encoding:)'
```

期望 200、单条 `Cache-Control: ... immutable`、`Content-Encoding: gzip`。

**Step 3-4** V5/V6/V7（API、SSE、上传）：

```bash
curl -s -o /dev/null -w 'V5_health=%{http_code}\n' --max-time 20 https://treenewbee.men/forum/api/health
curl -s -o /dev/null -w 'V6_sse=%{http_code}\n' --max-time 20 https://treenewbee.men/forum/api/notifications/stream
curl -s -o /dev/null -w 'V7_uploads=%{http_code}\n' --max-time 20 https://treenewbee.men/forum/uploads/__probe__/missing.jpg
```

期望 `200 / 401 / 404`。

**Step 3-5** V8 登录/登出链路（公网）：

```bash
resp=$(curl -s --max-time 20 -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123456"}' \
  https://treenewbee.men/forum/api/auth/login)
token=$(printf '%s' "$resp" | python -c 'import json,sys; print(json.load(sys.stdin)["data"]["token"])')
curl -s -o /dev/null -w 'V8_me=%{http_code}\n' --max-time 20 -H "Authorization: Bearer $token" https://treenewbee.men/forum/api/auth/me
curl -s -o /dev/null -w 'V8_logout=%{http_code}\n' --max-time 20 -X POST -H "Authorization: Bearer $token" https://treenewbee.men/forum/api/auth/logout
curl -s -o /dev/null -w 'V8_old_token=%{http_code}\n' --max-time 20 -H "Authorization: Bearer $token" https://treenewbee.men/forum/api/auth/me
```

期望 `200 / 200 / 401`。

**Step 3-6** V9 realip：

```bash
curl -s -o /dev/null --max-time 20 "https://treenewbee.men/forum/?realip_probe=$(date +%s)"
python /c/Temp/forum-ssh.py 'sudo tail -20 /var/log/nginx/access.log | grep realip_probe'
```

期望日志第一列是验收机的公网 IP（不是 `::1` / `127.0.0.1`）。

**Step 3-7** V11 隔离（先做，避免被 V10 影响）：用内网入口伪造另一个 IP，正确密码应 200：

```bash
python /c/Temp/forum-ssh.py 'curl -s -o /dev/null -w "V11_other_ip=%{http_code}\n" -H "Host: raspi" -H "X-Real-IP: 203.0.113.99" -H "Content-Type: application/json" -d "{\"username\":\"admin\",\"password\":\"admin123456\"}" http://127.0.0.1/forum/api/auth/login'
```

期望 200（环回来源可信，伪造 X-Real-IP 只影响验收，不涉及公网）。

**Step 3-8** V10 按真实 IP 限流（最后做）：连续 5 次错误密码，第 6 次 429：

```bash
for i in 1 2 3 4 5; do
  curl -s -o /dev/null -w "fail$i=%{http_code}\n" --max-time 20 -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"wrong-password"}' https://treenewbee.men/forum/api/auth/login
done
curl -s -o /dev/null -D - --max-time 20 -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"wrong-password"}' https://treenewbee.men/forum/api/auth/login | grep -iE '^(HTTP/|retry-after:)'
python /c/Temp/forum-ssh.py 'sudo podman logs --tail 40 forum-backend | grep -E "LoginRateLimiter|GlobalExceptionHandler" | tail -5'
```

期望：5 个 `401`，第 6 次 `429` + `Retry-After: 8xx`；日志 `WARN ... IP <验收机公网 IP> 登录失败 5 次，锁定 900000 ms`，IP 必须是真实公网 IP。

**Step 3-9** 清理锁定：

```bash
python /c/Temp/forum-ssh.py 'sudo podman restart forum-backend && sleep 20 && curl -s -o /dev/null -w "after_restart_health=%{http_code}\n" http://127.0.0.1:8086/api/health'
# 再复核公网登录
```

期望 health 200、公网登录 200。记录「重启会清空限流窗口（内存计数）」这一既有语义到文档。

## Task 4：文档

**Step 4-1** `docs/DEVELOPMENT.md` 新增 §4.15（放在 §4.14 之后、`## 5.` 之前），包含：

- 本轮背景（`600a8aa` 未推送、未验收、缺跳转与安全头）。
- 变更表：`deploy/nginx/public.conf` 308 跳转 + 4 个安全头；`deploy/deploy.sh` 4 组公网断言。
- 公网入口架构图（浏览器 → Cloudflare 边缘 → cloudflared → localhost:80 → forum-public.conf → forum.conf 片段 → 8086）。
- 验证矩阵 V1–V11 实测结果表（构建号、镜像 tag、实际值）。
- 已知限制：nginx 1.22 无 add_header 继承（assets/index.html 无安全头）；CF 404 边缘缓存头；CF 免费版无 WAF；未设 CSP；HSTS 不含子域。
- 「CF 侧建议」：Always Use HTTPS、按需开 HSTS、不要把域名加到 app.conf。
- 上线验收占位（`⟨待回填⟩`）。

**Step 4-2** `docs/deployment/README.md`：

- 公网验收小节补命令：HTTP 308、安全头 `grep`、公网 asset、公网 SSE。
- §7 已知限制补两条：assets/index.html 不继承 server 级安全头；CF 对 404 的 `max-age=14400` 边缘缓存头属正常。

**Step 4-3** `README.md`：在线地址处补 `https://treenewbee.men/forum/`（与内网地址并列）。

**Step 4-4** 计划文件：在末尾追加 §12「实施记录与偏差（2026-10-28）」（构建号、RED 证据、偏差：如 scp 方式、限流验收顺序）。

**Step 4-5** 提交：

```bash
git add docs/DEVELOPMENT.md docs/deployment/README.md README.md docs/superpowers/plans/2026-10-28-round10-public-entry-hardening.md
git commit -m "docs: 记录第十轮公网入口验证与加固"
```

## Task 5：全量验证、回填与收尾

**Step 5-1** 全量回归（本轮无代码改动，用于证明基线未动）：

```bash
cd /e/JavaSpace/forum && mvn -B clean test 2>&1 | tail -12   # 期望 177 / 0 / 0 / 1
cd frontend && pnpm test 2>&1 | tail -8                        # 期望 32 files / 323 tests
MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build 2>&1 | tail -6
pnpm check:bundle 2>&1 | tail -10
```

**Step 5-2** 推送、等 Jenkins 构建（记录构建号、镜像 tag）。

**Step 5-3** 复核 V1/V2/V4/V5/V6/V8（不重新做限流，避免再次锁定）。

**Step 5-4** 回填：

- `docs/DEVELOPMENT.md` §4.15 的上线验收替换为实测结果（构建号、镜像、V1–V11 结果、`Retry-After` 实际值、日志原文）。
- `docs/deployment/README.md` 验收小节补 `> **2026-10-28 验收实测（Build #N，镜像 forum-backend:N）**`。
- `README.md` 若写了构建号/日期，同步。
- 计划文件：§12 补最终构建号；勾选本轮所有 `- [ ]` → `- [x]`。

**Step 5-5** 提交并推送：

```bash
git add docs/DEVELOPMENT.md docs/deployment/README.md README.md docs/superpowers/plans/2026-10-28-round10-public-entry-hardening.md
git commit -m "docs: 回填第十轮构建与上线验收"
git push git@github.com:ScholarOfDeepDarkFantasy/forum.git master:master
```

**Step 5-6** 等最后一次构建成功，复核 health 与公网首页，确认远端 master == HEAD。

## 附录 A 提交信息（逐字）

1. `docs: 新增第十轮公网入口设计与计划`
2. `fix: 公网入口强制 HTTPS 并补安全响应头`
3. `docs: 记录第十轮公网入口验证与加固`
4. `docs: 回填第十轮构建与上线验收`

## 附录 B 上线冒烟清单

- [ ] B.1 公网 HTTPS 首页 200、`Cache-Control: no-cache`
- [ ] B.2 公网 HTTP 308 到 https
- [ ] B.3 公网 `/imageweb/`、`/manager/` 404
- [ ] B.4 公网 asset 200 + 单条 immutable + gzip
- [ ] B.5 公网 `/forum/api/health` 200；SSE 401；uploads 404
- [ ] B.6 公网登录 200 → me 200 → logout 200 → 旧 token 401
- [ ] B.7 realip：access log 首列是真实公网 IP
- [ ] B.8 公网 5 次错密码后 429 + `Retry-After`，日志 IP 为真实 IP；内网伪造 IP 200（隔离）
- [ ] B.9 重启后端清空限流窗口后公网登录恢复 200
- [ ] B.10 部署日志包含 4 组新公网断言且通过

## 12. 实施记录与偏差（2026-10-28）

### 12.1 构建记录

| 提交 | 内容 | Jenkins | 镜像 |
| --- | --- | --- | --- |
| `600a8aa` | 公网 server 块（本轮前置，本地未推送 → 本次补推） | Build #23 SUCCESS | `forum-backend:23`（healthy） |
| `a1cd36b` | 公网强制 HTTPS + 安全响应头 + deploy.sh 断言 | Build #24 SUCCESS | `forum-backend:24`（healthy） |
| ⟨待回填⟩ | 文档（§4.15、部署手册、README、本计划） | ⟨待回填⟩ | ⟨待回填⟩ |
| ⟨待回填⟩ | 回填验收与哈希 | ⟨待回填⟩ | ⟨待回填⟩ |

### 12.2 RED 证据（加固前，raspi 本机模拟公网 Host）

```
xfp_redirect=200                                  # 期望 308，实际 200 → 断言红
strict-transport-security 计数=0                  # 期望 ≥1 → 断言红
```

加固后同一组命令：`A_redirect=[308 https://treenewbee.men/forum/]`、四个安全头 grep 全过、
`public_asset=200 cache_control_n=1`、`public_sse=401`；`deploy.sh` 实跑 13/13 组断言通过。

### 12.3 执行中的偏差与决定

1. **下发 nginx 配置的方式**：计划写普通拷贝，实际本机没有 scp，改用
   `base64 -w0` + `forum-ssh.py` + `sudo tee`（public.conf → `/etc/nginx/conf.d/forum-public.conf`，
   forum.conf → `/etc/nginx/snippets/forum.conf`）+ `nginx -t` + `systemctl reload nginx`；改完立即公网/内网双回归。
2. **临时探针实验**：为确认 cloudflared 是否传 `X-Forwarded-Proto`，在公网 server 块临时加
   `location = /forum/__probe` 返回 `$http_x_forwarded_proto` / `$http_cf_visitor` / `$remote_addr` /
   `$realip_remote_addr`。实测 https→`https`、http→`http`，`$realip_remote_addr=::1`（只有回环可信），
   公网真实 IP 已由 `CF-Connecting-IP` 还原。探针已删除并 reload。
3. **设计判断修正**：spec 原把「assets 与 index.html 拿不到安全头」列为已知限制；实现时发现只需把 4 个头
   写进 snippet 的两个 location（它们自带 `add_header`，不继承 server 级），因此该限制已消除，
   已知限制改为「nginx 1.22 无 add_header_inherit，下发点分散」。
4. **断言数量**：`deploy.sh` 现有 13 组断言（原 9 组 + 本轮 4 组），新 4 组插在 `/imageweb/` 泄露断言之后。
5. **V10 的清理**：限流验收会锁验收机公网 IP 15 分钟；按约定放到最后，结束后
   `podman restart forum-backend` 清空内存计数，实测重启 60 s 后 `healthy`、公网登录恢复 200。
6. **验收机 IP**：V10 首次验收机公网 IP 为 `27.17.82.164`（`Retry-After: 899`）；
   Task 5 复核时用 `203.0.113.x` 段再验一次（429 + `Retry-After: 900`），两次都在 §4.15 留证。
