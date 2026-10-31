# 第十一轮实现计划：安全响应头收敛与 CSP

- 日期：2026-10-31
- 设计：`docs/superpowers/specs/2026-10-31-round11-security-headers-csp-design.md`
- 仓库根：`E:\JavaSpace\forum`（Git Bash：`/e/JavaSpace/forum`）
- 目标基线：后端 177（21 类）、前端 330（33 spec）、端点 39

## 0. 执行约定

- 直接在 `master` 提交，不建分支、不 amend、不 rebase、不 force push。
- 本轮计划 5 次提交（设计计划 → 测试守护 → 实现 → 文档记录 → 回填验收）；实际 8 次：计划外的 3 个修复提交见 §12（CSP 落错位置、CF 信标放行、部署断言放宽）。
- 提交信息逐字使用附录 A；每个 Task 提交后工作区必须干净。
- 前端命令一律在 `frontend/` 下执行；构建必须 `MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build`。
- 推送走 SSH：`git push git@github.com:ScholarOfDeepDarkFantasy/forum.git master:master`。
- Jenkins 状态用 `python /c/Temp/forum-ssh.py 'sudo grep -o "Finished: [A-Z]*" /var/lib/jenkins/jobs/javaforum/builds/<N>/log'`；容器用 `sudo -u pi XDG_RUNTIME_DIR=/run/user/$(id -u pi) podman ps`。
- nginx 配置下发用 base64 经 SSH（仓库无 scp 权限）：
  `B64=$(base64 -w0 deploy/nginx/forum.conf)`，`python /c/Temp/forum-ssh.py "echo '$B64' | base64 -d | sudo tee /etc/nginx/snippets/forum.conf >/dev/null && sudo /usr/sbin/nginx -t && sudo /usr/bin/systemctl reload nginx"`。
- 验收顺序：先本机 `nginx -t`（部署时自动跑），再做 V1–V9；限流相关的旧断言不受本轮影响。
- 本轮不改任何 Java / Vue / JS 业务代码；前端只新增一个 node 环境的 spec。

## 1. 现状与文件清单

### 1.1 已确认事实

- `frontend/index.html` 第 13–24 行是唯一内联脚本；`dist/index.html` 里仍是它。
- 其 SHA-256 实测（源码与 dist 相同）：`sha256-Sbv+SnhhPDOg1J2CVOMR4HoLBVSxnmSJ54wG/DCJq94=`。
- nginx 版本 **1.22.1**：`add_header` 在 location 出现后不继承 server 级（无 `add_header_inherit`）。
- 现有 CSP：无；`/forum/uploads/` 只有 `X-Content-Type-Options`（无 `always`）。
- 部署断言：`deploy/deploy.sh` 现有 13 组；`deploy/nginx/public.conf` server 级 4 头；`deploy/nginx/forum.conf` 的 assets/index 两 location 各 4 头。
- 测试基线：后端 177 / 21 类 / 1 skipped；前端 323 / 32 spec；`check:bundle` 五项。

### 1.2 本轮文件

| 文件 | 动作 |
| --- | --- |
| `docs/superpowers/specs/2026-10-31-round11-security-headers-csp-design.md` | 新增（Task 1 前） |
| `docs/superpowers/plans/2026-10-31-round11-security-headers-csp.md` | 新增（本文件） |
| `frontend/src/plugins/csp.spec.js` | 新增（Task 2） |
| `deploy/nginx/forum.conf` | 修改（Task 3：index location 加 CSP、uploads 补 4 头） |
| `deploy/nginx/public.conf` | 修改（Task 3：server 级加 CSP） |
| `deploy/deploy.sh` | 修改（Task 3：CSP 与 uploads 头断言） |
| `README.md`、`docs/DEVELOPMENT.md`、`docs/deployment/README.md` | 修改（Task 4/5） |

### 1.3 不改的文件

- 所有 `src/main/java/**`、`src/test/java/**`。
- `frontend/index.html`（内联脚本原样保留）、`frontend/src/**`（除新增 spec）。
- `frontend/vite.config.js`、`frontend/package.json`、`pnpm-lock.yaml`。
- `deploy/nginx/app.conf`（宿主初始化文件，不在仓库）。

## 2. Task 1：提交设计与计划（无代码）

**Step 1-1** 确认工作区干净、HEAD = 远端 master。
**Step 1-2** 两份文档已写好（spec 与 plan）。
**Step 1-3** 提交：

```
git add docs/superpowers/specs/2026-10-31-round11-security-headers-csp-design.md docs/superpowers/plans/2026-10-31-round11-security-headers-csp.md
git commit -m "docs: 新增第十一轮安全头收敛与 CSP 设计与计划"
```

预期：`2 files changed`，工作区干净。

## 3. Task 2：测试守护（RED）

**Step 2-1** 新建 `frontend/src/plugins/csp.spec.js`：

```js
// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { resolve } from 'node:path'

const ROOT = resolve(process.cwd(), '..')
const FORUM_CONF = resolve(ROOT, 'deploy/nginx/forum.conf')
const PUBLIC_CONF = resolve(ROOT, 'deploy/nginx/public.conf')
const DEPLOY_SH = resolve(ROOT, 'deploy/deploy.sh')
const INDEX_HTML = resolve(process.cwd(), 'index.html')

const CSP_RE = /add_header\s+Content-Security-Policy\s+"([^"]+)"\s+always;/g

function cspValues(file) {
  const source = readFileSync(file, 'utf8')
  return [...source.matchAll(CSP_RE)].map((match) => match[1])
}

function inlineScriptHash() {
  const html = readFileSync(INDEX_HTML, 'utf8')
  const scripts = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)]
  expect(scripts).toHaveLength(1)
  return `sha256-${createHash('sha256').update(scripts[0][1]).digest('base64')}`
}

describe('CSP 与内联脚本哈希', () => {
  it('index.html 内联脚本的 sha256 出现在 forum.conf 的 CSP 中', () => {
    const hash = inlineScriptHash()
    const [csp] = cspValues(FORUM_CONF)
    expect(csp, 'forum.conf 缺少 CSP').toBeTruthy()
    expect(csp).toContain(`script-src 'self' '${hash}'`)
  })

  it('CSP 必备指令齐全且 script-src 没有 unsafe-*/*', () => {
    const [csp] = cspValues(FORUM_CONF)
    for (const directive of [
      "default-src 'self'",
      "base-uri 'self'",
      "object-src 'none'",
      "style-src 'self' 'unsafe-inline'",
      "img-src 'self' data: https:",
      "font-src 'self' data:",
      "connect-src 'self'",
      "form-action 'self'",
      "frame-ancestors 'self'"
    ]) {
      expect(csp).toContain(directive)
    }
    const scriptSrc = csp.split(';').map((part) => part.trim()).find((part) => part.startsWith('script-src'))
    expect(scriptSrc).toBeTruthy()
    expect(scriptSrc).not.toContain('unsafe-inline')
    expect(scriptSrc).not.toContain('unsafe-eval')
    expect(scriptSrc).not.toContain(' *')
  })

  it('forum.conf 与 public.conf 的 CSP 逐字相同', () => {
    const [forumCsp] = cspValues(FORUM_CONF)
    const [publicCsp] = cspValues(PUBLIC_CONF)
    expect(forumCsp).toBeTruthy()
    expect(publicCsp).toBe(forumCsp)
  })

  it('uploads location 带 4 个安全头且都是 always', () => {
    const conf = readFileSync(FORUM_CONF, 'utf8')
    const block = conf.slice(conf.indexOf('location ^~ /forum/uploads/'))
    const uploadsBlock = block.slice(0, block.indexOf('}'))
    for (const header of [
      'add_header X-Content-Type-Options "nosniff" always;',
      'add_header X-Frame-Options "SAMEORIGIN" always;',
      'add_header Referrer-Policy "strict-origin-when-cross-origin" always;',
      'add_header Strict-Transport-Security "max-age=31536000" always;'
    ]) {
      expect(uploadsBlock).toContain(header)
    }
  })

  it('deploy.sh 含 CSP 与 uploads 头断言', () => {
    const script = readFileSync(DEPLOY_SH, 'utf8')
    expect(script).toContain('content-security-policy')
    expect(script).toContain('/forum/uploads/')
  })
})
```

**Step 2-2** 跑 RED：`pnpm vitest run src/plugins/csp.spec.js`
预期：`Tests 5 failed (5)`（forum.conf 无 CSP、uploads 缺 3 头、deploy.sh 无断言）。

**Step 2-3** 提交：

```
git add frontend/src/plugins/csp.spec.js
git commit -m "test: 守护 CSP 脚本哈希与安全头一致性"
```

预期：`1 file changed`。Jenkins 默认不跑前端测试，因此该提交在 CI 仍是绿的。

## 4. Task 3：实现 CSP 与 uploads 头（GREEN）

**Step 3-1** `deploy/nginx/forum.conf`：

1）`location = /forum/index.html` 在现有 4 个头后面追加（注意 CSP 字符串与 public.conf 完全一致）：

```nginx
    # 第十一轮：强制 CSP。script-src 用 SHA-256 精确放行首页唯一的内联主题引导脚本，
    # 不放开 'unsafe-inline'；改 index.html 内联脚本后必须同步这里的哈希，
    # frontend/src/plugins/csp.spec.js 会断言两者一致。
    add_header Content-Security-Policy "default-src 'self'; base-uri 'self'; object-src 'none'; script-src 'self' 'sha256-Sbv+SnhhPDOg1J2CVOMR4HoLBVSxnmSJ54wG/DCJq94='; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self' data:; connect-src 'self'; form-action 'self'; frame-ancestors 'self'" always;
```

2）`location ^~ /forum/uploads/` 改为：

```nginx
location ^~ /forum/uploads/ {
    alias /pd/Program/forum/uploads/;
    expires 7d;
    access_log off;
    # 第十一轮：补齐全套安全头（该 location 自带 add_header，不继承 server 级）；
    # 全部带 always，404 也下发，deploy.sh 用不存在的文件名做断言。
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;
    add_header Strict-Transport-Security "max-age=31536000" always;
}
```

**Step 3-2** `deploy/nginx/public.conf`：在 server 级 4 头之后追加同一条 CSP（同字符串），并更新注释：说明 CSP 覆盖该 server 全部响应，HTML 的实际生效头来自 snippets 里的 index location。

**Step 3-3** `deploy/deploy.sh`：在 `public_sse` 断言之后、内网 SSE 断言之前插入两组：

```bash
# 公网 HTML 必须带强制 CSP，且 script-src 只允许 self + 内联主题脚本的哈希
public_csp="$(curl -sS --max-time 5 -I -H 'Host: treenewbee.men' "http://127.0.0.1/forum/" | tr -d '\r' || true)"
echo "$public_csp" | grep -qi '^content-security-policy:.*default-src .self.' \
    || die "公网 /forum/ 缺少 Content-Security-Policy（实际头：$(echo "$public_csp" | grep -i '^content-security-policy' || echo '无')）"
echo "$public_csp" | grep -qi "^content-security-policy:.*script-src .self. 'sha256-" \
    || die "公网 CSP 的 script-src 未使用内联脚本哈希（检查 forum.conf 与 frontend/index.html 是否同步）"
echo "$public_csp" | grep -qi "content-security-policy:.*script-src .self. 'sha256-[^']*';.*style-src 'self' 'unsafe-inline'" \
    || die "公网 CSP 结构异常（应为 script-src 'self' 'sha256-…'; style-src 'self' 'unsafe-inline'）"

# 上传目录（图片）也要带 4 个安全头：不存在的文件名也应有（add_header always）
uploads_probe="$(curl -sS --max-time 5 -I -H 'Host: raspi' "http://127.0.0.1/forum/uploads/__probe_missing__.png" | tr -d '\r' || true)"
for expected_header in \
    '^x-content-type-options: nosniff' \
    '^x-frame-options: SAMEORIGIN' \
    '^referrer-policy: strict-origin-when-cross-origin' \
    '^strict-transport-security: max-age=31536000'
do
    echo "$uploads_probe" | grep -qi "$expected_header" \
        || die "uploads location 缺少安全响应头 $expected_header（实际头：$(printf '%s\n' "$uploads_probe" | grep -i '^\(x-\|referrer\|strict\)' | tr '\n' ';')）"
done
```

注意：`-I`（HEAD）对静态文件与 HTML 都返回 add_header 头，不依赖 body，符合第十轮 B.2 的教训（那条只针对 `Content-Encoding: gzip`）。

**Step 3-4** GREEN 校验（本机）：

```
cd /e/JavaSpace/forum/frontend
pnpm vitest run src/plugins/csp.spec.js    # 5 passed
pnpm test                                  # 33 files / 328 tests
```

`bash -n deploy/deploy.sh` 必须通过；`grep -c 'Content-Security-Policy' deploy/nginx/*.conf` 各 1。

**Step 3-5** 提交：

```
git add deploy/nginx/forum.conf deploy/nginx/public.conf deploy/deploy.sh
git commit -m "fix: 补齐上传目录安全头并启用 CSP"
```

预期：`3 files changed`。

## 5. Task 4：本机验证与部署

**Step 4-1** 全量回归（不依赖 nginx）：

```
cd /e/JavaSpace/forum && mvn -B clean test          # 177 / 0 / 0 / 1
cd frontend && pnpm test                            # 33 / 328
MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build   # 产物与第九轮同量级
pnpm check:bundle                                   # 五项绿
```

**Step 4-2** 推送并部署：

```
git push git@github.com:ScholarOfDeepDarkFantasy/forum.git master:master
```

Jenkins 触发构建（预计 #28）；轮询 `Finished: SUCCESS`，记录构建号与镜像 tag。

**Step 4-3** 部署后立即验证（raspi 本机 + 公网）：

- `curl -sI -H 'Host: raspi' http://127.0.0.1/forum/` → 含 `Content-Security-Policy`。
- `curl -sI -H 'Host: treenewbee.men' http://127.0.0.1/forum/` → 含 CSP。
- `curl -sI -H 'Host: raspi' http://127.0.0.1/forum/uploads/__probe_missing__.png` → 404 + 4 头。
- 公网 `curl -sI https://treenewbee.men/forum/` → CSP + 原 4 头。
- `deploy.sh` 在 Jenkins 日志里 15 组断言全过（若失败，先看部署日志里的 `[deploy][ERROR]`）。

**Step 4-4** Playwright 无违规验证（本机 Chromium/Edge，经隧道或直接公网）：

- 打开 `/forum/`、`/forum/posts/1`、`/forum/admin`（管理员登录）、`/forum/search?keyword=…`。
- 断言 console 中没有 `Refused to` / `Content Security Policy` 违规；`page.on('console')` 收集。
- 截图：亮色首页、暗色首页、帖子详情（含 Markdown 代码块）、头像、删除确认框。
- 断言 `document.documentElement.classList.contains('dark')` 在切换主题后成立；SSE 不报错。

**Step 4-5** 若 V1–V9 失败：优先回滚 CSP 两行（base64 下发旧 conf + reload），保留 uploads 头；再定位。

## 6. Task 5：文档与回填

**Step 5-1** `docs/DEVELOPMENT.md`：

- 新增 §4.16「第十一轮：安全头收敛与 CSP（2026-10-31）」，含：背景、改动表（3 文件）、CSP 策略值与各指令理由、内联脚本哈希方案与守护测试、uploads 补齐、HSTS 决策、验证矩阵 V1–V9、构建记录、已知限制（style-src 'unsafe-inline'、无 CSP 上报、哈希需手工同步但有测试、CF 设置手工）、上线验收段（先写 `⟨待回填⟩`，Task 5-4 填）。
- §3.2 前端基线 323→328、32→33 spec。
- §5.2 候选更新（去掉「CSP」条，补「CSP Report 收集」与「CF 设置 IaC」）。
- §8 变更日志插 5 行（本轮 5 提交）。

**Step 5-2** `docs/deployment/README.md`：

- 公网验证小节补 CSP 检查命令与期望。
- §7 已知限制：删除「uploads 只带 nosniff」、补「CSP 用 SHA-256 放行内联脚本，改脚本需同步」「style-src 含 unsafe-inline 的理由」「HSTS 不含 includeSubDomains 的评估方法」「CF 面板设置仍需手工」。
- 新增「Cloudflare 侧设置核对表」（SSL/TLS Full strict、Always Use HTTPS、Min TLS 1.2、HSTS 一致性、WAF 建议、如何核对）。
- §5 验收引用块加第十一轮（待 Task 5-4 回填）。

**Step 5-3** `README.md`：安全说明段加 CSP 一句；测试基线 177 / 328。

**Step 5-4** 提交并推送、等 Jenkins，然后回填：

```
git add README.md docs/DEVELOPMENT.md docs/deployment/README.md docs/superpowers/plans/2026-10-31-round11-security-headers-csp.md
git commit -m "docs: 记录第十一轮安全头收敛与 CSP"
```

**Step 5-5** 附录 B 冒烟（V1–V9）全过后回填：

- `docs/DEVELOPMENT.md` §4.16 上线验收写实际构建号/镜像/验证结果；§8 顶部加回填行并把记录行哈希回填。
- `docs/deployment/README.md` 验收引用块写构建号。
- 计划文件 §12 实施记录 + checkbox 全勾；附录 B 全 `- [x]`。
- 提交 `docs: 回填第十一轮构建与上线验收`，推送，等最后一个构建 SUCCESS，确认远端 == HEAD。

## 附录 A：本轮提交信息（逐字）

1. `docs: 新增第十一轮安全头收敛与 CSP 设计与计划`
2. `test: 守护 CSP 脚本哈希与安全头一致性`
3. `fix: 补齐上传目录安全头并启用 CSP`
4. `docs: 记录第十一轮安全头收敛与 CSP`
5. `docs: 回填第十一轮构建与上线验收`

## 附录 B：上线冒烟清单

- [x] B.1 公网 `/forum/` 200 + CSP + 原 4 头
- [x] B.2 公网 SPA 路由与 B.1 同 CSP
- [x] B.3 内网 `Host: raspi` `/forum/` 同 CSP
- [x] B.4 asset 单条 immutable + gzip 不回归
- [x] B.5 uploads 404 带 4 头
- [x] B.6 详情页/管理页/搜索页 Playwright 无 CSP 违规，暗色与头像正常
- [x] B.7 SSE 401 不回归
- [x] B.8 `deploy.sh` 15/15 组断言通过
- [x] B.9 回滚演练：删 CSP 两行 + 重跑部署可恢复（只记录方法，不实际执行全网回滚）
- [x] B.10 远端 == HEAD、工作区干净


## 12. 实施记录与偏差（2026-10-31）

### 12.1 提交与构建链

| # | 提交 | 内容 | Jenkins |
| --- | --- | --- | --- |
| 0 | `e3a3ffa` | 设计与计划 | Build #27 为起点 `086c329` SUCCESS |
| 1 | `d50341e` | `csp.spec.js` 首版 5 条守护（RED：`Tests 5 failed`） | 随 `f8675ba` 一起构建 |
| 2 | `f8675ba` | uploads 补 4 头、public.conf server 级 CSP、`deploy.sh` 两组断言 | **Build #28 FAILURE**：`公网 /forum/ 缺少 Content-Security-Policy（实际：无该头）` |
| 3 | `0c897c9` | 把 CSP 移到 `location = /forum/index.html`；spec 扩到 6 条 | **Build #29 SUCCESS**（镜像 29） |
| 4 | `db88a9f` | 放行 CF Web Analytics 信标（`static.cloudflareinsights.com` + 上报域） | **Build #30 FAILURE**：`公网 CSP 结构异常`（`deploy.sh` 的 `script-src` 结构断言过严） |
| 5 | `bd24453` | 放宽结构断言 + 2 条信标放行断言 | **Build #31 SUCCESS**（镜像 31） |
| 6 | 本次 docs 提交 | `docs: 记录第十一轮安全头收敛与 CSP` | Build #32 |
| 7 | 回填提交 | `docs: 回填第十一轮构建与上线验收` | Build #33 |

### 12.2 偏差与决定

1. **CSP 落错位置**（Build #28）：首版实现对 nginx conf 用「第一次出现的 HSTS 行 + `}`」做文本替换，CSP 被插进了 uploads 块；`index.html` location 自己写 `add_header` 会屏蔽 server 级，公网 HTML 实际没有 CSP。修复：改为先定位 `location = /forum/index.html` 再替换，并新增 2 条 spec（CSP 必须在 index location、uploads 不得有 CSP）。教训记入 `docs/DEVELOPMENT.md` §4.16。
2. **Cloudflare Web Analytics 信标**：公网实测发现 CF 免费版注入 `static.cloudflareinsights.com/beacon.min.js`，严格 CSP 下每页控制台报违规。决定显式放行该主机与上报域名 `cloudflareinsights.com`（并在 `deploy.sh` 里断言放行，防误删），而非关闭 CF 的 Web Analytics（面板项不在仓库内）。
3. **部署断言收紧**（Build #30）：`script-src` 结构断言原本要求哈希后直接跟 `;`，放行额外主机后失效。改为允许哈希后出现白名单主机，并新增信标放行断言；`deploy.sh` 组数从 13 组升到 15 组。
4. **前端测试数**：计划写 328/33（新增 5 条），实际 `csp.spec.js` 最终 7 条 → **330 / 33 spec**；`docs/DEVELOPMENT.md`、`README.md` 按实际回填。
5. **HSTS `includeSubDomains`**：评估结论是不加（该域名下还有内网应用 `/imageweb/` 等），记入 §4.16 与部署手册。
6. **验收顺序**：限流相关断言本轮不涉及；V10（锁 IP）本轮未重跑，沿用第十轮结果，避免再次锁验收机 15 分钟。
7. **Playwright 脚本不入库**：仅作一次性公网渲染复核（截图在 `C:\Temp\r11-csp`），与 T3/T18 一致。
