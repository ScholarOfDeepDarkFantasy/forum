# JavaForum 部署手册（raspi：Jenkins + podman + nginx）

> 目标主机：SSH 别名 `raspi`（Debian 12 / aarch64，Jenkins 2.580.1，rootless podman 4.3.1）
> 设计背景与取舍见 [raspi-jenkins-podman-analysis.md](raspi-jenkins-podman-analysis.md)。

## 1. 部署形态

```
浏览器 ── http://raspi/forum/            （内网）
浏览器 ── https://treenewbee.men/forum/  （公网，Cloudflare Tunnel）
  cloudflared（系统服务，远程托管 tunnel；ingress: hostname=treenewbee.men → http://localhost:80）
    └─ 保留原始 Host 头 → nginx
  nginx :80（系统服务）
    ├─ /forum/       → /pd/Program/forum/frontend/      Vue dist（nginx 直出，不进容器）
    ├─ /forum/uploads/ → /pd/Program/forum/uploads/     头像文件（bind mount 进后端容器，nginx 直出）
    └─ /forum/api/   → 127.0.0.1:8086/api/              反向代理
                       forum-backend 容器（eclipse-temurin:17-jre-jammy）
                         │ jdbc:postgresql://host.containers.internal:5432/javaforum
                       主机已有 postgresql 容器（rootless，库 javaforum 数据保留）
Jenkins :8080 → job `javaforum`（Jenkinsfile from SCM，master，轮询 H/5）
```

要点：后端只绑 `127.0.0.1:8086`；数据库不新增容器；镜像/工作区/前端都在 `/pd`（根盘只剩 5G）。SSE 端点 `/forum/api/notifications/stream` 由 snippet 单独配置（`proxy_buffering off` + `proxy_read_timeout 3600s`），漏配的表现与排障见第 6 节。

**两个 server 块，两个入口**（2026-10-26 起）：

| 入口 | nginx 配置文件 | `server_name` | 暴露范围 |
|---|---|---|---|
| 内网 `http://raspi/forum/` | `/etc/nginx/conf.d/app.conf`（include snippet） | `raspi` | app.conf 里所有 location（`/imageweb/`、`/manager/`、`/files/`、`/api/` 等）+ `/forum/` |
| 公网 `https://treenewbee.men/forum/` | `/etc/nginx/conf.d/forum-public.conf`（只 include snippet） | `treenewbee.men` | 仅 `/forum/`；其它路径一律 404 |

公网块刻意不复用 `app.conf` 的 `server_name`：tunnel 会把原始 Host（`treenewbee.men`）带到 nginx，
若把该域名加进 `app.conf`，`/imageweb/`、`/manager/`、`/api/`（mini-mall 网关）等内网应用会一并被带到公网。
该文件由 `deploy.sh` 每次部署幂等覆盖（`deploy/nginx/public.conf`），并带 `real_ip_header CF-Connecting-IP`
（`set_real_ip_from` 限定回环），否则后端看到的客户端 IP 恒为 `::1`，登录限流会把全站当成同一个 IP。
它还用 `X-Forwarded-Proto` 在源站强制 HTTPS（明文请求 308 到 `https://treenewbee.men/...`），并下发
`HSTS`、`X-Content-Type-Options`、`X-Frame-Options`、`Referrer-Policy` 四个安全响应头；
`absolute_redirect off` 仍然保留，让 `/forum` → `/forum/` 这类内部跳转保持相对路径。
HTML 与静态资源的安全头实际写在 snippet 的两个 location 里（nginx 1.22 的 `add_header` 不继承，见第 7 节）。

## 2. 一次性引导（换机器时照做；当前主机已完成）

```bash
# 1) 目录（pi 属主；jenkins 在 pi 组，pipeline 用 sudo -u pi 写入）
sudo mkdir -p /pd/Program/forum/deploy /pd/Program/forum/frontend
sudo chown -R pi:pi /pd/Program/forum
sudo chmod 2775 /pd/Program/forum

# 2) 生成 deploy/.env，并重置已有 forum 角色密码（只做一次，值是随机的）
cd /pd/Program/forum/deploy
DB_PASSWORD="$(openssl rand -hex 24)"
JWT_SECRET="$(openssl rand -hex 32)"
cat > .env <<EOF
DB_URL=jdbc:postgresql://host.containers.internal:5432/javaforum
DB_USERNAME=forum
DB_PASSWORD='$DB_PASSWORD'
JWT_SECRET='$JWT_SECRET'
HOST_PORT=8086
TZ=Asia/Shanghai
IMAGE_TAG=latest
UPLOAD_HOST_DIR=/pd/Program/forum/uploads
JAVA_OPTS='-Xms128m -Xmx512m -XX:+ExitOnOutOfMemoryError'   # 不要用 MaxRAMPercentage，见第 7 节
EOF
chmod 600 .env
sudo -u pi podman exec postgresql psql -U postgres -c "ALTER ROLE forum WITH PASSWORD '$DB_PASSWORD';"

# 3) 头像上传目录：容器内 UID 1001 映射到宿主 subuid（如 101000），属主不是 pi、也不是 1001
sudo -u pi mkdir -p /pd/Program/forum/uploads/avatars
sudo -u pi podman unshare chown -R 1001:1001 /pd/Program/forum/uploads
sudo -u pi podman unshare chmod -R a+rX /pd/Program/forum/uploads

# 4) nginx：snippet + 在 app.conf 的 server 块内加 include（只加一次）
sudo cp /pd/Program/forum/deploy/nginx/forum.conf /etc/nginx/snippets/forum.conf 2>/dev/null || true
sudo sed -i '/server_name raspi;/a\    include /etc/nginx/snippets/forum.conf;' /etc/nginx/conf.d/app.conf
sudo nginx -t && sudo systemctl reload nginx
```

> 第 4 步的 snippet 内容来自仓库 `deploy/nginx/forum.conf`，部署时流水线会自动 `cp` 覆盖它；
> 如果 `app.conf` 里已经有 `include /etc/nginx/snippets/forum.conf;`，不要重复执行 `sed`。
> 第 3 步必须在第一次 `podman-compose up` 之前完成：目录不存在时 rootless podman 会以映射 uid 创建挂载点，nginx 会先看到 403。
> `.env` 里的 `UPLOAD_HOST_DIR` 由 `deploy.sh` 每次部署幂等写入（缺失则追加、已有则更正），一般不要手工改；`ls -ln /pd/Program/forum` 看到 `uploads/` 属主是 `101000` 这类 subuid 属正常现象，不要改回 `pi`。

```bash
# 5) Jenkins job（主机无公网入口，没有 Jenkins 管理员密码时用文件方式创建；有密码也可在 UI 里建）
sudo mkdir -p /var/lib/jenkins/jobs/javaforum
# config.xml 见附录 A，写入后：
sudo chown -R jenkins:jenkins /var/lib/jenkins/jobs/javaforum
sudo systemctl restart jenkins
```

Jenkins 重启后 30–60s 可用；job 配置里已带 `SCMTrigger H/5`，**首次轮询会直接触发第一次构建**。

## 3. 日常发布

1. 本地改代码，`git push` 到 `master`；
2. Jenkins 5 分钟内轮询到变更 → 构建 jar → 构建前端 → 构建镜像 → 部署 → 冒烟；
3. 看结果：

```bash
# 构建历史
sudo ls /var/lib/jenkins/jobs/javaforum/builds/
# 跟踪最新一次构建
sudo tail -f /var/lib/jenkins/jobs/javaforum/builds/*/log
# 容器状态与日志
sudo -u pi podman ps --filter name=forum-backend
sudo -u pi podman logs --tail 100 forum-backend
```

也可在 Jenkins UI 里 `Build with Parameters`：`RUN_TESTS`（跑测试）、`DEPLOY`（是否部署）、`IMAGE_TAG`（默认 BUILD_NUMBER）。

## 4. 回滚

- 方式一（推荐，可追溯）：Jenkins `Build with Parameters`，`IMAGE_TAG=<上一个 BUILD_NUMBER>`，`DEPLOY=true`。流水线会重新构建同版本 jar/前端并用该 tag 部署（历史镜像仍保留时 build 很快）。
- 方式二（只回滚容器）：`cd /pd/Program/forum/deploy && sudo -u pi sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=<旧tag>/' .env && sudo -u pi podman-compose -p forum up -d`。
- 数据库无版本回滚概念（Flyway 只前进）；回滚代码前确认迁移兼容。
- V3 迁移只创建扩展与索引，**回滚代码不需要回滚数据库**：旧版本后端不依赖它们，扩展保留无害。确实要清理索引时（例如重建索引期间临时释放磁盘）执行：

```sql
DROP INDEX IF EXISTS idx_posts_content_trgm;
DROP INDEX IF EXISTS idx_posts_title_trgm;
```

（`pg_trgm` 扩展本身建议保留：删除后重建需要再扫一遍 `posts` 建索引。）

## 5. 冒烟检查（部署后）

```bash
curl -fsS http://127.0.0.1:8086/api/health                                   # {"success":true,...}
curl -fsS -H 'Host: raspi' http://127.0.0.1/forum/ | grep '/forum/assets/'    # 前端资源前缀
curl -fsS -H 'Host: raspi' http://127.0.0.1/forum/posts/1 | grep 'id="app"'   # SPA 回退
curl -fsS -H 'Host: raspi' 'http://127.0.0.1/forum/api/posts?page=0&size=1'   # 经 nginx 的 API
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: raspi' \
  http://127.0.0.1/forum/uploads/__probe__/missing.jpg                        # 期望 404（不是 200 HTML）
```

```bash
# 头像上传冒烟：登录拿 token → 上传本地 avatar.jpg（JPEG，≤2MB）→ 经 nginx 取回
TOKEN=$(curl -fsS -H 'Content-Type: application/json' \
  -d "{\"username\":\"${SMOKE_USER:-admin}\",\"password\":\"${SMOKE_PASS:-admin123456}\"}" \
  http://127.0.0.1:8086/api/auth/login | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
AVATAR=$(curl -fsS -H "Authorization: Bearer $TOKEN" -F 'file=@avatar.jpg;type=image/jpeg' \
  http://127.0.0.1:8086/api/users/me/avatar | sed -n 's/.*"avatarUrl":"\([^"]*\)".*/\1/p')
curl -fsSI -H 'Host: raspi' "http://127.0.0.1/forum$AVATAR" | grep -Ei '^(HTTP/|content-type)'
# 期望 HTTP/1.1 200 OK 与 Content-Type: image/jpeg
```

> 必须带 `Host: raspi`：`app.conf` 是 `server_name raspi`，用 IP 访问会落到 `sites-enabled/default`。
> 公网入口同理，用 `-H 'Host: treenewbee.men'` 验证 `forum-public.conf` 那条路由（`deploy.sh` 已内置这些断言）：
>
> ```bash
> # 明文必须 308 到 https（cloudflared 会把客户端 scheme 放进 X-Forwarded-Proto）
> curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' \
>   -H 'Host: treenewbee.men' -H 'X-Forwarded-Proto: http' http://127.0.0.1/forum/
> # 期望：308 https://treenewbee.men/forum/
>
> # HTML 必须带 4 个安全响应头
> curl -sI -H 'Host: treenewbee.men' http://127.0.0.1/forum/ | grep -iE \
>   '^(strict-transport-security|x-content-type-options|x-frame-options|referrer-policy):'
>
> # 公网静态资源同样单条 immutable；公网 SSE 与内网一样 401
> curl -sI -H 'Host: treenewbee.men' http://127.0.0.1/forum/assets/<hash>.js | grep -ci '^cache-control:'
> curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: treenewbee.men' \
>   http://127.0.0.1/forum/api/notifications/stream   # 期望 401
> ```

### 全文搜索（V3 pg_trgm）验证

V3 在 `forum-backend` 启动时由 Flyway 自动执行（`forum` 角色有 `CREATE` 权限；PostgreSQL 15 的 pg_trgm 是 trusted 扩展，不需要超级用户）。部署后确认扩展、索引与接口：

```bash
# 1) 扩展已安装
sudo -u pi podman exec postgresql psql -U postgres -d javaforum -c '\dx pg_trgm'
# 期望：Name = pg_trgm，Version = 1.6

# 2) 两个 GIN 部分索引存在
sudo -u pi podman exec postgresql psql -U postgres -d javaforum -c '\d posts'
# 期望：idx_posts_title_trgm、idx_posts_content_trgm，类型 gin，条件 (deleted = false)

# 3) 搜索接口经 nginx 可用
curl -fsS -H 'Host: raspi' 'http://127.0.0.1/forum/api/posts/search?keyword=Jenkins' | head -c 300
# 期望：{"success":true,...,"data":{"content":[...],...}}（无匹配时 content 为 []，仍是 200）

# 4) 空白关键词与缺参数都是 400（不是 500）
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: raspi' 'http://127.0.0.1/forum/api/posts/search?keyword=%20'
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: raspi' 'http://127.0.0.1/forum/api/posts/search'

# 5) 搜索页 SPA 回退正常
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: raspi' 'http://127.0.0.1/forum/search?q=Jenkins'
# 期望：200
```

数据量小时（十几篇帖子）`EXPLAIN` 显示顺序扫描属正常：规划器认为全表读比索引检索更便宜。

```sql
EXPLAIN SELECT id FROM posts WHERE deleted = false
  AND LOWER(title) LIKE CONCAT('%', 'jenkins', '%');
```

### 实时通知（SSE）验证

通知走 `POST /api/notifications/stream-ticket` 换一次性 ticket，再用 `GET /api/notifications/stream?ticket=` 建 SSE 长连接；nginx 必须命中精确 location（`proxy_buffering off`、`proxy_read_timeout 3600s`、透传 `$is_args$args`），否则表现为「通知延迟十几秒」或「每分钟断一次」。

```bash
# 1) 未带 ticket：必须 401（`deploy.sh` 也会断言这一条；200 说明 nginx 把请求兜到了 SPA）
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: raspi' \
  http://127.0.0.1/forum/api/notifications/stream

# 2) 登录 → 取票 → 用 ticket 建流并观察心跳（15s 一条）
TOKEN=$(curl -fsS -H 'Content-Type: application/json' \
  -d "{\"username\":\"${SMOKE_USER:-admin}\",\"password\":\"${SMOKE_PASS:-admin123456}\"}" \
  http://127.0.0.1:8086/api/auth/login | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
TICKET=$(curl -fsS -X POST -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8086/api/notifications/stream-ticket | sed -n 's/.*"ticket":"\([^"]*\)".*/\1/p')
echo "ticket=$TICKET"
timeout 40 curl -sN -H 'Host: raspi' "http://127.0.0.1/forum/api/notifications/stream?ticket=$TICKET" | head -20
# 期望：先收到 `: connected`，随后每 15s 一行 `event:heartbeat`

# 3) 同一 ticket 只能消费一次：再请求必须 401
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: raspi' \
  "http://127.0.0.1/forum/api/notifications/stream?ticket=$TICKET"   # 期望 401

# 4) 另开一个账号 B 回复 A 的帖子，A 的流上应出现 event:notification（data 里 type=REPLY）
```

> `stream` 端点的鉴权在 ticket 内部完成，`SecurityConfig` 对它单独 `permitAll`；`POST /stream-ticket` 仍需 JWT。

### 登录限流与真实客户端 IP（V4）

V4 在 `forum-backend` 启动时由 Flyway 自动执行，给 `users` 加 `token_version`；后端过滤器每次带 token 的请求都会回查该列，因此封禁/登出立即生效（响应仍是既有的 401 `{"success":false,"message":"未登录或登录已过期"}`）。

```bash
# 1) 迁移与列类型
sudo -u pi podman exec postgresql psql -U postgres -d javaforum -tAc \
  "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1"
# 期望：4|add user token version|t
sudo -u pi podman exec postgresql psql -U postgres -d javaforum -tAc \
  "SELECT data_type, is_nullable, column_default FROM information_schema.columns WHERE table_name='users' AND column_name='token_version'"
# 期望：bigint|NO|0

# 2) 登出立即失效：拿 token → 登出 → 旧 token 立刻 401
TOKEN=$(curl -fsS -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123456"}' \
  http://127.0.0.1:8086/api/auth/login | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8086/api/auth/me                              # 期望 200
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8086/api/auth/logout                          # 期望 200
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8086/api/auth/me                              # 期望 401

# 3) 限流：换一个从未失败过的 IP，连续错密码到 429，并确认 Retry-After
for i in 1 2 3 4 5; do
  curl -s -o /dev/null -w '%{http_code} ' -H 'Content-Type: application/json' \
    -H 'X-Real-IP: 203.0.113.9' \
    -d '{"username":"admin","password":"wrong-password"}' \
    http://127.0.0.1:8086/api/auth/login
done; echo
# 期望：401 401 401 401 401（第 5 次错密码把窗口打满）
curl -s -D - -o /dev/null -H 'Content-Type: application/json' -H 'X-Real-IP: 203.0.113.9' \
  -d '{"username":"admin","password":"admin123456"}' \
  http://127.0.0.1:8086/api/auth/login | grep -Ei '^(HTTP/|retry-after)'
# 期望：HTTP/1.1 429 与 Retry-After: 89x~900（锁定后立即执行为 900，稍等几秒为 899/89x，属正常；锁定内正确密码同样被拒）

# 4) 其它 IP 不受影响（经 nginx 时必须带 Host，nginx 会用自己的 $remote_addr 覆盖 X-Real-IP）
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: raspi' -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123456"}' \
  http://127.0.0.1/forum/api/auth/login                      # 期望 200
```

> 后端只在 `remoteAddr` 是**环回或 RFC1918 私网地址**时才读 `X-Real-IP`。本机用 rootless podman 发布 `127.0.0.1:${HOST_PORT:-8086}:8080`，端口映射会让后端看到的 `remoteAddr` 变成容器网关地址（2026-10-23 实测 `10.89.3.15`，不是 `127.0.0.1`）；该地址属于私网，因此上面的 `-H 'X-Real-IP: ...'` 在本机直连 8086 时**会被采信**，第 4 步（其它 IP 不受影响）应返回 **200**。安全前提：8086 只发布在宿主机回环、且由本机 nginx 覆盖写该头，所以本机以外的客户端无法伪造；将来若把端口发布到其它网卡或公网，这个信任模型必须重新评估（本机任意进程即可伪造该头）。经 nginx 时该头会被覆盖为真实 `$remote_addr`。若要把锁定影响降到最低，冒烟可改用一次性用户（见计划 Task 6）。

### 静态资源缓存头（第九轮）验证

第九轮把 `/forum/assets/` 改成一年 + `immutable`（只写一条 `Cache-Control`），并给入口页加了 `no-cache`。`deploy.sh` 会自动断言这些点（HTTP 200、只有一条 `Cache-Control`、`immutable`、一年、入口页 `no-cache`），这里给手工复核命令：

```bash
# 1) 资源：必须带 immutable（「只有一条」的计数校验在第 3 步）
ASSET=$(curl -fsS -H 'Host: raspi' http://127.0.0.1/forum/ | grep -o '/forum/assets/[^"]*\.js' | head -1)
[ -n "$ASSET" ] || { echo '未从首页解析到 JS 资源'; exit 1; }
echo "asset=$ASSET"
curl -s -o /dev/null -D - -H 'Host: raspi' -H 'Accept-Encoding: gzip' "http://127.0.0.1$ASSET" \
  | grep -iE '^(HTTP/|cache-control:|content-encoding:)'
# 期望：HTTP/1.1 200 OK
#       cache-control: public, max-age=31536000, immutable
#       content-encoding: gzip
# 注意：必须用 GET 取响应头，不能用 curl -I（HEAD）——nginx 的 gzip filter 对
# header_only 请求直接跳过，HEAD 无论带不带 Accept-Encoding 都不会有 content-encoding。
# 若 .js 没出现 content-encoding，还取决于宿主机 nginx 的 gzip_types（不在本仓库），以 raspi 实测为准。

# 2) 入口页与 SPA 回退：必须 no-cache
curl -sI -H 'Host: raspi' http://127.0.0.1/forum/ | grep -i '^cache-control:'
# 期望：cache-control: no-cache
curl -sI -H 'Host: raspi' http://127.0.0.1/forum/posts/1 | grep -i '^cache-control:'
# 期望：cache-control: no-cache

# 3) 不能出现两条 Cache-Control（同时写了 expires 与 add_header 就会）
curl -sI -H 'Host: raspi' "http://127.0.0.1$ASSET" | grep -ci '^cache-control:'
# 期望：1
```

> 必须带 `-H 'Host: raspi'`：raspi 上 nginx 的 server 块写了 `server_name raspi`，不带 Host 时请求落到默认 server，测不到 `/forum/` 的配置。

> **2026-10-25 验收实测（Build #20，镜像 `forum-backend:20`）**：`/forum/assets/index-Dm2pwhnr.js` 返回 `HTTP/1.1 200`、1 条 `Cache-Control: public, max-age=31536000, immutable`、`Content-Encoding: gzip`；`/forum/` 与 `/forum/posts/1` 均为 `Cache-Control: no-cache`；首页入口 JS 84,431 B、入口 CSS 39,019 B（与本地构建一致）；`deploy.sh` 的缓存头断言全部通过。随后的回填提交 `8e6d71b` 触发 **Build #21** 亦 SUCCESS（镜像 `:21` healthy，产物字节数相同），属 docs-only 重建。上线后请复核一次 `sudo nginx -T | grep -n add_header`，确认两个 location 没有丢 server 级安全头。
>
> 发布后如果用户反馈「还是旧界面」，让用户强制刷新一次即可：旧 `index.html` 里引用的旧 hash 资源已被 `rsync --delete` 删除，`no-cache` 保证下一次不会再用旧 html。

> **2026-10-28 第十轮公网验收（Build #24，镜像 `forum-backend:24`）**：`https://treenewbee.men/forum/` 200
> 且带 `Cache-Control: no-cache` + 4 个安全响应头；`http://` 308 到 https；公网 asset 200 + 单条 immutable + gzip；
> 公网 `/imageweb/`、`/manager/` 404；公网 SSE 401；公网登录 → me → logout → 旧 token 401；
> nginx access log 首列为真实公网 IP（realip 生效）；连续 5 次错密码后第 6 次 429 + `Retry-After: 899`，
> 后端日志 `WARN ... IP <真实公网 IP> 登录失败 5 次，锁定 900000 ms`。`deploy.sh` 新增的 4 组公网断言在本次构建日志中通过。

## 6. 排障

| 现象 | 排查 |
|---|---|
| Jenkins 构建红在 Checkout | 确认 `git ls-remote https://gh-proxy.org/https://github.com/ScholarOfDeepDarkFantasy/forum.git` 可用（jenkins 用户） |
| `pom.xml` 依赖下载慢/失败 | 复用 `/var/lib/jenkins/.m2`；Maven Central 实测 0.57s，一般无需镜像 |
| 前端 install 失败 | 强制 `--registry=https://registry.npmmirror.com`（Jenkinsfile 已带）；pnpm 绝对路径 `/home/pi/.nvm/versions/node/v24.16.0/bin/pnpm` |
| Build Image 失败 | 用 `sudo -u pi podman build --format docker ...`（`--format docker` 才会保留 HEALTHCHECK）；上下文必须含 `target/forum-0.0.1-SNAPSHOT.jar` |
| 部署阶段 `缺少 .env` | 执行第 2 步引导；`chmod 600` 且属主 pi |
| `podman-compose: command not found` | 脚本在 pi 环境执行；jenkins 的 sudoers 已放行 `/usr/bin/podman-compose` |
| 后端启动即退出 | `sudo -u pi podman logs forum-backend`：常见是 DB 连不上（`host.containers.internal` 解析/密码） |
| 容器 `Created` 但起不来，日志有 `crun: opening file 'memory.max' ...` | compose 里写了 `mem_limit`，而本机 `/proc/cmdline` 带 `cgroup_disable=memory`（没有内存 cgroup 控制器）；删掉 `mem_limit`，用 `JAVA_OPTS` 的 `-Xmx` 限制堆 |
| 数据库密码错 | `sudo -u pi podman exec postgresql psql -U postgres -c "ALTER ROLE forum WITH PASSWORD '<.env 里的值>';"` |
| `/forum/` 404 | `app.conf` 是否 include snippet；`/pd/Program/forum/frontend/index.html` 是否存在 |
| **公网域名**访问 `/forum/` 404，内网 `http://raspi/forum/` 正常 | 典型的 `server_name` 不匹配：cloudflared 保留原始 Host（`treenewbee.men`）到 nginx，而 `app.conf` 是 `server_name raspi`，请求落到 `sites-enabled/default`（`server_name _`，root=`/var/www/html`）→ 404。确认 `/etc/nginx/conf.d/forum-public.conf` 存在且 `nginx -t` + reload 过；本机验证：`curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: treenewbee.men' http://127.0.0.1/forum/` 应为 **200** |
| 公网访问时所有用户被当成同一个 IP（一人打满限流，全站 429） | `forum-public.conf` 的 realip 没生效：cloudflared 从回环连入，缺 `real_ip_header CF-Connecting-IP` 时 `$remote_addr` 恒为 `::1`，后端 `ClientIpResolver` 只能拿到环回地址。确认该文件含 `set_real_ip_from 127.0.0.1; set_real_ip_from ::1; real_ip_header CF-Connecting-IP;` 并已 reload；`sudo tail /var/log/nginx/access.log` 里公网请求的第一列应是真实公网 IP 而非 `::1` |
| `/forum/api/` 502 | `forum-backend` 未起或未监听 8086：`sudo -u pi curl -v http://127.0.0.1:8086/api/health` |
| `/forum/uploads/xxx` 返回 200 HTML | nginx 的 `^~ /forum/uploads/` 必须排在 `location /forum/`（SPA 回退）之前，否则 `try_files` 把请求兜到 `index.html`；确认 snippet 顺序后 `sudo nginx -t && sudo systemctl reload nginx` |
| 上传返回 `413` 或 `400 图片不能超过 2MB` | 413 来自 nginx：snippet 的 `/forum/api/` 已设 `client_max_body_size 3m`，若仍出现说明服务器上是旧版 snippet（重新发布或手工 `cp` 后 reload）；400 来自后端（超过 2MB 或 multipart 上限），响应体里带 `图片不能超过 2MB` |
| 上传返回 `400 保存头像失败` | 容器内写 `/app/uploads` 失败：多为宿主目录属主/权限不对，重跑 `deploy.sh`（`podman unshare chown -R 1001:1001` + `chmod -R a+rX`），并确认 `uploads` 不是符号链接 |
| 取头像 `403` | 文件对 nginx worker 不可读（历史 0600 或属主错）：重跑 `deploy.sh` 由 `chmod -R a+rX` 治愈；仍失败则 `ls -ln /pd/Program/forum/uploads/avatars` 检查权限 |
| 通知要等十几秒甚至更久才到 | snippet 的 SSE 精确 location 未生效（`proxy_buffering off` 缺失会攒事件）：确认服务器上 snippet 是新版、`nginx -t` 后已 reload；`curl -N` 直连 8086 对比，直连实时、经 nginx 不实时即是此因 |
| 通知在浏览器里每分钟断一次并频繁重连 | 通用 `/forum/api/` 的 `proxy_read_timeout`（60s）覆盖了 SSE：必须命中 `location = /forum/api/notifications/stream` 且 `proxy_read_timeout 3600s`；确认 `$is_args$args` 透传了 ticket |
| `/forum/api/notifications/stream` 返回 200 HTML | 精确 location 缺失，请求被 `try_files` 兜到 `/forum/index.html`；`deploy.sh` 冒烟会因此失败 |
| 取票 401「通知连接凭证无效或已过期」 | ticket 60 秒过期或已被消费（刷新页面会重新取票）；确认前端与后端版本配套、`app.sse.ticket-ttl-ms` 未被改小 |
| Jenkins 起不来 | `sudo journalctl -u jenkins -n 100 --no-pager`；job config.xml 写坏时先移走 `/var/lib/jenkins/jobs/javaforum` 再重启 |
| 登录返回 429 | 该 IP 的失败窗口已打满：等 `Retry-After` 秒（默认 900）或重启容器（内存计数清零）；确认不是 NAT 后整栋楼共用一个出口 IP 导致互相牵连 |
| 所有用户看到同一个 IP / 限流失效 | 反代链路上 `X-Real-IP` 被覆盖或缺失：确认 snippet 的两个 `location` 都有 `proxy_set_header X-Real-IP $remote_addr;`；只有环回或 RFC1918 私网源地址才读 `X-Real-IP`——直连 `127.0.0.1:8086` 时后端看到的是容器网关私网地址，带该头即可测；公网源直连 8086 则只认 `remoteAddr`，该头被忽略。 |

## 7. 已知限制

- **没有公网入口**：不用 GitHub webhook，靠 `pollSCM`（最长延迟约 5 分钟）；想即时构建需手动 Build。
- **重启后容器不会自动拉起**：pi（和 jenkins）没有开启 linger，rootless 容器依赖登录/手动启动（与主机上现有 mini-mall 等服务同一现状）。重启后执行：`sudo -u pi podman-compose -p forum -f /pd/Program/forum/deploy/docker-compose.yml up -d`。
- **`/` 根盘只剩约 5G**：Jenkins 工作区固定在 `/pd/jenkins-workspace/forum`，pnpm store 在 `/pd/pnpm-store`，镜像在 `/pd/container`。
- **不能用 `mem_limit` 限制容器内存**：内核 cmdline 带 `cgroup_disable=memory`，rootless crun 写 `memory.max` 会失败；堆大小由 `.env` 里的 `JAVA_OPTS=-Xmx512m` 控制。
- **不要执行全局 `podman image prune`**：会把 mini-mall 等其它项目的镜像一起删掉；流水线只清理 `localhost/forum-backend` 的旧数字 tag。
- **头像文件不在数据库备份范围**：`/pd/Program/forum/uploads/` 是独立目录，数据库备份/迁移不覆盖它。恢复时用 `sudo tar -C /pd/Program/forum -p -xf uploads.tar uploads` 保留属主，再跑一次 `deploy.sh` 修正权限。
- **孤儿头像文件不会自动清理**：换头像、删号后旧文件只在「没有其他用户引用」时由后端删除；异常中断或历史数据可能留下无引用文件，目前没有清理任务，需要时按 `users.avatar_url` 人工对账。
- **1–2 个字的关键词用不上全文搜索索引**：pg_trgm 的三元组索引在关键词短于 3 个字符时无法过滤，规划器会走 `posts` 顺序扫描。当前数据量（十几到几十篇）下无感；数据量增长后短词查询会变慢，属预期行为。
- **中文按子串匹配，无分词与相关度排序**：搜索是「标题/正文包含关键词」，不区分词边界，也不做相关度打分；排序规则固定为「标题命中优先、同档按时间倒序」。
- **实时通知是单实例的**：SSE 连接注册表在应用内存中，多实例部署时事件只从产生通知的实例推给连在它上面的客户端；升级/重启会让在线连接断开，客户端自动换票重连并校准未读数。单容器部署下无影响。
- **SSE 依赖反向代理配置**：必须命中 `/forum/api/notifications/stream` 的精确 location（`proxy_buffering off`、`proxy_read_timeout 3600s`、`$is_args$args`）。漏配时通知会延迟或每分钟断连；`deploy.sh` 只断言「无 ticket 返回 401」，「是否真正实时」需按第 5 节手工验证。
- **登录限流的计数在进程内**：容器重启即清零，多实例部署时各实例独立计数；如需跨实例一致，需按 `docs/DEVELOPMENT.md` §5.2 换成共享存储。
- **限流键是单个 IP 字面量，双栈下存在绕过面**：若 nginx 同时监听 IPv6，公网 IPv6 客户端可在一个 /64 内轮换源地址，使每次失败落到不同计数桶从而绕过锁定；只监听 IPv4 的现有部署不受影响，双栈部署建议按 /64 归并限流键或只监听 IPv4。
- **成功登录会清空该 IP 的整个失败窗口**：`POST /api/auth/register` 公开，攻击者可「4 次错密码 + 1 次自己账号成功登录」循环让窗口永不锁定；单 IP 触发全站锁定已在 Build #15 修复，但此绕过面仍在，见 `docs/DEVELOPMENT.md` §4.12 已知取舍第 5 条。
- **第八轮（2026-10-24 质量加固）没有新增部署要求**：无数据库迁移、无新配置项、无 nginx/location 改动；发布即镜像替换（Jenkins 自动构建 + `deploy.sh`）。回滚只需把镜像 tag 切到上一版并重跑 `deploy.sh`。
- **静态资源缓存一年 + 入口页 no-cache（第九轮起）**：`/forum/assets/*` 带内容 hash，发布后文件名变化、旧文件被 `rsync --delete` 删除，因此长缓存安全；`index.html` 必须每次回源，否则用户会一直加载旧入口、请求已删除的旧 hash 资源（表现为白屏）。若出现白屏，让用户强制刷新一次即可，不要改成给 `index.html` 加长缓存。
- **首次发布第九轮后可能白屏一次**：改动前 `index.html` 没有任何 `Cache-Control`，浏览器按启发式规则缓存；上线瞬间可能仍用旧 html 去请求已被删除的旧 hash 资源，强刷一次即可恢复。`no-cache` 只对「已经拿到新 html」之后的加载生效。
- **nginx `add_header` 不再从 server 级继承**：`/forum/assets/` 与 `location = /forum/index.html` 各自定义 `add_header`，若宿主机 `conf.d/app.conf` 在 server 级设置过安全头（`X-Frame-Options`、`Referrer-Policy`、CSP 等），这两个 location 会丢掉它们。第十轮起把 4 个安全头直接写进这两个 location（`deploy/nginx/forum.conf`），公网 API 响应由 `forum-public.conf` 的 server 级负责；将来 nginx 升到 1.29+ 可用 `add_header_inherit merge` 合并。
- **Cloudflare 免费版会给 404 加边缘缓存头**：请求不存在的 `/forum/assets/*.js` 会返回 404 + `Cache-Control: max-age=14400` + `cf-cache-status: MISS`，这是 Cloudflare 的边缘行为，不是源站配置错误；源站的 404 本身不带 Cache-Control。
- **公网强制 HTTPS 在源站兜底**：`forum-public.conf` 用 `X-Forwarded-Proto` 把明文请求 308 到 https；Cloudflare 面板的 Always Use HTTPS 未纳入仓库，属手工设置，开启后是多一层保险。HSTS 不带 `includeSubDomains`，避免误伤该域名下的其它内网子域。

## 附录 A：Jenkins job `config.xml`

```xml
<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job">
  <description>JavaForum 自动构建部署（raspi：podman + nginx）</description>
  <keepDependencies>false</keepDependencies>
  <properties>
    <jenkins.model.BuildDiscarderProperty>
      <strategy class="hudson.tasks.LogRotator">
        <daysToKeep>-1</daysToKeep>
        <numToKeep>15</numToKeep>
        <artifactDaysToKeep>-1</artifactDaysToKeep>
        <artifactNumToKeep>-1</artifactNumToKeep>
      </strategy>
    </jenkins.model.BuildDiscarderProperty>
  </properties>
  <definition class="org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition" plugin="workflow-cps">
    <scm class="hudson.plugins.git.GitSCM" plugin="git">
      <configVersion>2</configVersion>
      <userRemoteConfigs>
        <hudson.plugins.git.UserRemoteConfig>
          <url>https://gh-proxy.org/https://github.com/ScholarOfDeepDarkFantasy/forum.git</url>
        </hudson.plugins.git.UserRemoteConfig>
      </userRemoteConfigs>
      <branches>
        <hudson.plugins.git.BranchSpec>
          <name>*/master</name>
        </hudson.plugins.git.BranchSpec>
      </branches>
      <doGenerateSubmoduleConfigurations>false</doGenerateSubmoduleConfigurations>
      <submoduleCfg class="empty-list"/>
      <extensions/>
    </scm>
    <scriptPath>Jenkinsfile</scriptPath>
    <lightweight>true</lightweight>
  </definition>
  <triggers>
    <hudson.triggers.SCMTrigger>
      <spec>H/5 * * * *</spec>
      <ignorePostCommitHooks>false</ignorePostCommitHooks>
    </hudson.triggers.SCMTrigger>
  </triggers>
  <disabled>false</disabled>
</flow-definition>
```

> 工作区不在 job 里配，而由 `Jenkinsfile` 的 `agent { node { customWorkspace '/pd/jenkins-workspace/forum' } }` 指定。
> 无 Jenkins 密码时也可以登录后 «新建 Item → Pipeline → Pipeline script from SCM» 手工建，参数同上。
