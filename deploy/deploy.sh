#!/usr/bin/env bash
# JavaForum 部署脚本（由 Jenkins 以 pi 身份调用，使用 rootless podman）
#
# 用法: bash deploy/deploy.sh <IMAGE_TAG>
#
# 前提（一次性初始化，见 docs/deployment/README.md）：
#   1. /pd/Program/forum/deploy/.env 已存在（含 DB_URL/DB_USERNAME/DB_PASSWORD/JWT_SECRET/HOST_PORT）
#   2. /etc/nginx/conf.d/app.conf 已 include /etc/nginx/snippets/forum.conf
#   3. 镜像 localhost/forum-backend:<IMAGE_TAG> 已由流水线构建
#
# 本脚本还会写 /etc/nginx/conf.d/forum-public.conf（公网 Cloudflare Tunnel 入口的
# server 块，源文件 deploy/nginx/public.conf）。首次执行前该文件可以不存在——
# cp 会创建它，随后的 nginx -t 与 reload 让它生效。
#
# 脚本可重复执行：先 down 再 up，保证换镜像后容器一定重建。

set -euo pipefail

TAG="${1:?用法: bash deploy/deploy.sh <IMAGE_TAG>}"
ROOT=/pd/Program/forum
DEPLOY_DIR="$ROOT/deploy"
FRONTEND_DIR="$ROOT/frontend"
ENV_FILE="$DEPLOY_DIR/.env"
WORKSPACE="$(cd "$(dirname "$0")/.." && pwd)"

log() { echo "[deploy] $*"; }
die() { echo "[deploy][ERROR] $*" >&2; exit 1; }

command -v podman >/dev/null || die "podman 不存在"
command -v podman-compose >/dev/null || die "podman-compose 不存在"
[ -f "$ENV_FILE" ] || die "缺少 $ENV_FILE（一次性初始化见 docs/deployment/README.md）"
[ -f "$WORKSPACE/docker-compose.yml" ] || die "缺少 $WORKSPACE/docker-compose.yml"
[ -d "$WORKSPACE/frontend/dist" ] || die "缺少 frontend/dist，前端未构建"
podman image exists "localhost/forum-backend:$TAG" || die "镜像 localhost/forum-backend:$TAG 不存在"

grep -q 'snippets/forum.conf' /etc/nginx/conf.d/app.conf \
    || die "/etc/nginx/conf.d/app.conf 未 include snippets/forum.conf（一次性初始化见文档）"

log "准备目录 $DEPLOY_DIR"
mkdir -p "$DEPLOY_DIR" "$FRONTEND_DIR"
cp -f "$WORKSPACE/docker-compose.yml" "$DEPLOY_DIR/docker-compose.yml"

# 镜像 tag 是流水线维护项：改写 .env 里的 IMAGE_TAG 一行（写在文件里，compose 与重启都能读到）
if grep -q '^IMAGE_TAG=' "$ENV_FILE"; then
    sed -i "s|^IMAGE_TAG=.*|IMAGE_TAG=$TAG|" "$ENV_FILE"
else
    printf 'IMAGE_TAG=%s\n' "$TAG" >> "$ENV_FILE"
fi
chmod 600 "$ENV_FILE"

# 头像目录在宿主机上，容器内 UID 1001 需要卷写权限：
# rootless podman 把容器 1001 映射到宿主的 subuid（100000+1000），
# 因此必须在 podman 的 user namespace 里 chown；宿主上直接 chown 1001:1001 是错的用户。
log "准备头像目录 $ROOT/uploads"
# 符号链接会让下面的 chown -R 跟到链接目标（可能是系统目录），因此拒绝
[ ! -L "$ROOT/uploads" ] || die "$ROOT/uploads 是符号链接，拒绝 chown -R（请改成真实目录）"
mkdir -p "$ROOT/uploads/avatars"
podman unshare chown -R 1001:1001 "$ROOT/uploads" \
    || die "调整 $ROOT/uploads 属主失败（需要 pi 的 rootless podman user namespace；见 docs/deployment/README.md）"
# 目录权限固定为 0755、文件至少对 nginx worker 可读：既挡住 umask 差异，
# 也顺手治愈历史上以 0600 写入（nginx 403）的头像文件
podman unshare chmod -R a+rX "$ROOT/uploads" \
    || die "调整 $ROOT/uploads 权限失败（nginx worker 需要可读权限）"

# 卷的宿主目录路径写进 .env，compose 每次 up 都从这里读
if grep -q '^UPLOAD_HOST_DIR=' "$ENV_FILE"; then
    sed -i "s|^UPLOAD_HOST_DIR=.*|UPLOAD_HOST_DIR=$ROOT/uploads|" "$ENV_FILE"
else
    printf 'UPLOAD_HOST_DIR=%s\n' "$ROOT/uploads" >> "$ENV_FILE"
fi
# GNU sed 会保留原权限，但 .env 含数据库口令，这里把 600 显式重申一次
chmod 600 "$ENV_FILE"

HOST_PORT="$(grep -E '^HOST_PORT=' "$ENV_FILE" | tail -1 | cut -d= -f2 | tr -d "'\"")"
HOST_PORT="${HOST_PORT:-8086}"

log "重建后端容器（镜像 localhost/forum-backend:$TAG）"
(cd "$DEPLOY_DIR" && podman-compose -p forum down) >/dev/null 2>&1 || true
podman rm -f forum-backend >/dev/null 2>&1 || true
(cd "$DEPLOY_DIR" && podman-compose -p forum up -d)

# podman-compose 1.0.3 不会把子命令失败透传成非零退出码：必须自己确认容器真的起来了，
# 否则要白等 120s 才报健康检查超时，真正的错误（如不兼容的运行时参数）会被埋掉。
if [ "$(podman inspect -f '{{.State.Running}}' forum-backend 2>/dev/null || true)" != "true" ]; then
    podman logs --tail 100 forum-backend 2>&1 || true
    die "forum-backend 未运行（见上方 podman 输出；常见原因：crun 不支持的运行时参数，如 mem_limit）"
fi

log "等待后端就绪 http://127.0.0.1:$HOST_PORT/api/health"
ready=0
for _ in $(seq 1 60); do
    if curl -fsS "http://127.0.0.1:$HOST_PORT/api/health" >/dev/null 2>&1; then
        ready=1
        break
    fi
    sleep 2
done
if [ "$ready" != 1 ]; then
    podman logs --tail 100 forum-backend 2>&1 || true
    die "后端 120s 内未就绪（看上面的 podman logs）"
fi

log "发布前端静态文件到 $FRONTEND_DIR"
rsync -a --delete "$WORKSPACE/frontend/dist/" "$FRONTEND_DIR/"
chmod -R a+rX "$FRONTEND_DIR"

log "同步 nginx snippet 并 reload"
sudo -n cp "$WORKSPACE/deploy/nginx/forum.conf" /etc/nginx/snippets/forum.conf
# 公网入口（Cloudflare Tunnel）的独立 server 块：app.conf 是 `server_name raspi`，
# 匹配不上 tunnel 带来的 Host: treenewbee.men，请求会落到 sites-enabled/default 的 404。
# 该文件只 include forum snippet，因此公网仅暴露 /forum/。
sudo -n cp "$WORKSPACE/deploy/nginx/public.conf" /etc/nginx/conf.d/forum-public.conf
sudo -n /usr/sbin/nginx -t
sudo -n /usr/bin/systemctl reload nginx

log "冒烟测试"
curl -fsS "http://127.0.0.1:$HOST_PORT/api/health" | head -c 300
echo
curl -fsS -H 'Host: raspi' "http://127.0.0.1/forum/" | grep -q '/forum/assets/' \
    || die "前端首页没有引用 /forum/assets（VITE_BASE_PATH 没生效？）"
curl -fsS -H 'Host: raspi' "http://127.0.0.1/forum/posts/1" | grep -q 'id="app"' \
    || die "SPA 路由回退失败（try_files / index.html）"
curl -fsS -H 'Host: raspi' "http://127.0.0.1/forum/api/posts?page=0&size=1" >/dev/null \
    || die "经 nginx 访问 /forum/api/posts 失败（检查反代与数据库连通性）"

# 静态资源必须带 immutable 长缓存、入口页必须 no-cache（第九轮）：
# 抓真实 asset 路径而不是硬编码文件名——每次构建的 hash 都会变。
#
# 命令替换内部末尾的 `|| true` 是必需的：`set -o pipefail` 下 grep 无匹配会让
# 整条管道返回 1，赋值语句本身就会触发 errexit —— 下面带诊断信息的 die 永远不可达。
# 这里不用 curl -f：要把 HTTP 状态码带进 die 文案。
home_raw="$(curl -sS --max-time 5 -H 'Host: raspi' -w '\n%{http_code}' "http://127.0.0.1/forum/" || true)"
home_status="${home_raw##*$'\n'}"
home_html="${home_raw%$'\n'*}"
asset_hits="$(printf '%s\n' "$home_html" | grep -c '/forum/assets/[^"]*\.js' || true)"
asset_path="$(printf '%s\n' "$home_html" | grep -o '/forum/assets/[^"]*\.js' | head -1 || true)"
[ -n "$asset_path" ] \
    || die "/forum/ 首页没有引用任何 /forum/assets/*.js（首页 HTTP ${home_status:-000}，命中 ${asset_hits:-0} 条）"

# HEAD 探针同样带超时；先断言 200，否则「资源 404」会被误报成「Cache-Control 不含 immutable」
asset_raw="$(curl -sS --max-time 5 -I -H 'Host: raspi' -w '\n%{http_code}' "http://127.0.0.1$asset_path" || true)"
asset_status="${asset_raw##*$'\n'}"
asset_cache="$(printf '%s\n' "$asset_raw" | tr -d '\r' | grep -i '^cache-control:' || true)"
asset_cache_n="$(printf '%s\n' "$asset_raw" | tr -d '\r' | grep -ci '^cache-control:' || true)"
[ "$asset_status" = "200" ] \
    || die "$asset_path 的请求返回 HTTP ${asset_status:-000}（应为 200；资源不存在或 nginx alias 未指向 frontend/assets）"
# 条数必须为 1：expires 与 add_header 同写会各生成一条 Cache-Control，两条头能分别
# 满足下面的 immutable / max-age 断言，只查内容会漏检（计划风险 R6）
[ "$asset_cache_n" = "1" ] \
    || die "$asset_path 返回了 $asset_cache_n 条 Cache-Control（应为 1；assets 块里是否同时写了 expires 与 add_header？实际：$(echo "$asset_cache" | tr '\n' ';')）"
echo "$asset_cache" | grep -q 'immutable' \
    || die "$asset_path 的 Cache-Control 不含 immutable（实际：${asset_cache:-无}；检查 nginx snippet 是否已 reload）"
echo "$asset_cache" | grep -q 'max-age=31536000' \
    || die "$asset_path 的 Cache-Control 不是一年（实际：${asset_cache:-无}）"

index_headers="$(curl -sS --max-time 5 -I -H 'Host: raspi' "http://127.0.0.1/forum/index.html" | tr -d '\r')"
echo "$index_headers" | grep -qi '^cache-control: no-cache' \
    || die "/forum/index.html 的 Cache-Control 不含 no-cache（实际：$(echo "$index_headers" | grep -i '^cache-control:' || echo 无)）"
[ "$(echo "$index_headers" | grep -ci '^cache-control:')" = "1" ] \
    || die "/forum/index.html 返回了多条 Cache-Control 头（是否同时写了 expires 与 add_header？）"

# 上传目录必须由 nginx 的 ^~ /forum/uploads/ 直出：
# 命中 alias 时，不存在的文件是 404；若配置没生效，请求会落到 SPA 的
# try_files 而返回 200 + index.html —— 这正是要抓的静默错误
uploads_code="$(curl --max-time 5 -s -o /dev/null -w '%{http_code}' -H 'Host: raspi' \
    "http://127.0.0.1/forum/uploads/__probe__/missing.jpg")"
[ "$uploads_code" = "404" ] \
    || die "/forum/uploads/ 未直出（返回 $uploads_code，应为 404；检查 nginx snippet 是否已 reload 且 /pd/Program/forum/uploads 可读）"

# 公网入口（Cloudflare Tunnel）必须命中 conf.d/forum-public.conf 的 server 块。
# cloudflared 把原始 Host 原样带给 nginx；匹配不上 app.conf 的 `server_name raspi`
# 就会落到 default_server 的 404 —— 那正是「tunnel 通了但页面 404」的成因。
# 本机带 Host 头即可覆盖同一条 nginx 路由，不需要真的走公网。
public_code="$(curl --max-time 5 -s -o /dev/null -w '%{http_code}' -H 'Host: treenewbee.men' \
    "http://127.0.0.1/forum/")"
[ "$public_code" = "200" ] \
    || die "Host: treenewbee.men 的 /forum/ 返回 $public_code（应为 200；检查 /etc/nginx/conf.d/forum-public.conf 是否存在并已 reload）"
# 公网 server 块只 include forum snippet：其它内网应用（/imageweb/、/manager/ 等）不能被带出去
public_leak="$(curl --max-time 5 -s -o /dev/null -w '%{http_code}' -H 'Host: treenewbee.men' \
    "http://127.0.0.1/imageweb/")"
[ "$public_leak" = "404" ] \
    || die "Host: treenewbee.men 访问 /imageweb/ 返回 $public_leak（应为 404；公网 server 块不应暴露内网应用）"

# 公网必须强制 HTTPS：cloudflared 会把客户端 scheme 放进 X-Forwarded-Proto（实测
# http/https 均有），本机带上它即可复现公网明文请求，不需要真的走外网。
public_redirect="$(curl --max-time 5 -s -o /dev/null -w '%{http_code} %{redirect_url}' \
    -H 'Host: treenewbee.men' -H 'X-Forwarded-Proto: http' "http://127.0.0.1/forum/" || true)"
public_redirect_code="${public_redirect%% *}"
public_redirect_url="${public_redirect#* }"
[ "$public_redirect_code" = "308" ] \
    || die "公网明文 /forum/ 未返回 308（返回 ${public_redirect_code:-000}；检查 forum-public.conf 的 X-Forwarded-Proto 跳转）"
case "$public_redirect_url" in
    https://treenewbee.men/forum/*) ;;
    *) die "公网 308 的 Location 不是 https://treenewbee.men/forum/（实际：${public_redirect_url:-空}）" ;;
esac

# 公网 HTML 必须带基础安全响应头。index.html 与 assets 两个 location 已各自补了同样
# 4 个头（nginx 1.22 无 add_header_inherit，见 deploy/nginx/forum.conf），这里断言源站兜底。
public_headers="$(curl -sS --max-time 5 -I -H 'Host: treenewbee.men' "http://127.0.0.1/forum/" | tr -d '\r' || true)"
for expected_header in \
    '^x-content-type-options: nosniff' \
    '^x-frame-options: SAMEORIGIN' \
    '^referrer-policy: strict-origin-when-cross-origin' \
    '^strict-transport-security: max-age=31536000'
do
    echo "$public_headers" | grep -qi "$expected_header" \
        || die "公网 /forum/ 缺少安全响应头 $expected_header（实际头：$(printf '%s\n' "$public_headers" | grep -i '^\(x-\|referrer\|strict\)' | tr '\n' ';')）"
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
    "http://127.0.0.1/forum/api/notifications/stream" || true)"
[ "$public_stream_code" = "401" ] \
    || die "经公网 Host 访问 SSE 返回 $public_stream_code（应为 401）"

# 公网 HTML 必须带强制 CSP：script-src 只允许 self + 内联主题脚本的 SHA-256。
# 这里不仅查结构，还会现算首页内联脚本的 SHA-256 与响应头比对 —— 改了 index.html
# 却忘同步哈希时，部署会在这里失败。默认构建 RUN_TESTS=false 不跑 csp.spec.js，
# 所以这条部署期校验是哈希失配的最后一道闸（csp.spec.js 只在显式开启测试时生效）。
public_csp="$(curl -sS --max-time 5 -I -H 'Host: treenewbee.men' "http://127.0.0.1/forum/" | tr -d '\r' || true)"
echo "$public_csp" | grep -qi '^content-security-policy:.*default-src' \
    || die "公网 /forum/ 缺少 Content-Security-Policy（实际：$(printf '%s\n' "$public_csp" | grep -i '^content-security-policy' || echo '无该头')）"
csp_hash="$(printf '%s\n' "$public_csp" | grep -io 'sha256-[A-Za-z0-9+/=]*' | head -1 || true)"
[ -n "$csp_hash" ] \
    || die "公网 CSP 的 script-src 没有 sha256- 哈希（检查 forum.conf / public.conf 的 CSP）"

# 现算首页内联主题脚本的哈希（<script> … </script> 之间的原始字节，含换行与缩进），
# 与响应头里的哈希逐字比对；这一条能抓住「改脚本忘改哈希」而前缀检查抓不到的情况。
public_html="$(curl -sS --max-time 5 -H 'Host: treenewbee.men' "http://127.0.0.1/forum/" || true)"
inline_js="$(printf '%s' "$public_html" | perl -0777 -ne 'print $1 if /<script>(.*?)<\/script>/s' || true)"
[ -n "$inline_js" ] \
    || die "公网 /forum/ 首页没有内联主题脚本（frontend/index.html 是否被改成外链？CSP 哈希将失效）"
actual_hash="sha256-$(printf '%s' "$inline_js" | openssl dgst -sha256 -binary | openssl base64 -A)"
[ "$actual_hash" = "$csp_hash" ] \
    || die "公网 CSP 哈希与页面内联脚本不一致（CSP: $csp_hash，实际: $actual_hash；改 index.html 后要同步 forum.conf/public.conf）"

# script-src / connect-src 必须是预期值：精确匹配可同时挡住「偷塞 'unsafe-inline'」「把
# CF 信标换成 cloudflareinsights.com.evil.com」这类只在尾部追加的绕过写法。
script_src="$(printf '%s\n' "$public_csp" | sed -n 's/.*\(script-src [^;]*\).*/\1/p' | head -1)"
connect_src="$(printf '%s\n' "$public_csp" | sed -n 's/.*\(connect-src [^;]*\).*/\1/p' | head -1)"
style_src="$(printf '%s\n' "$public_csp" | sed -n 's/.*\(style-src [^;]*\).*/\1/p' | head -1)"
expected_script_src="script-src 'self' '$csp_hash' https://static.cloudflareinsights.com"
[ "$script_src" = "$expected_script_src" ] \
    || die "公网 CSP 的 script-src 不是预期值（实际：${script_src:-空}；预期：$expected_script_src）"
expected_connect_src="connect-src 'self' https://cloudflareinsights.com"
[ "$connect_src" = "$expected_connect_src" ] \
    || die "公网 CSP 的 connect-src 不是预期值（实际：${connect_src:-空}；预期：$expected_connect_src）"
expected_style_src="style-src 'self' 'unsafe-inline'"
[ "$style_src" = "$expected_style_src" ] \
    || die "公网 CSP 的 style-src 不是预期值（实际：${style_src:-空}；预期：$expected_style_src）"
log "CSP 校验通过：哈希与首页内联脚本一致（$csp_hash），script/connect/style-src 结构符合预期"

# 上传目录（图片）也必须带全套安全头；用一个不存在的文件名，验证 add_header 的
# always 生效（404 也下发）。这里只看头，不看状态码。
uploads_probe="$(curl -sS --max-time 5 -I -H 'Host: raspi' "http://127.0.0.1/forum/uploads/__probe_missing__.png" | tr -d '\r' || true)"
for expected_header in \
    '^x-content-type-options: nosniff' \
    '^x-frame-options: SAMEORIGIN' \
    '^referrer-policy: strict-origin-when-cross-origin' \
    '^strict-transport-security: max-age=31536000'
do
    printf '%s\n' "$uploads_probe" | grep -qi "$expected_header" \
        || die "uploads location 缺少安全响应头 $expected_header（实际：$(printf '%s\n' "$uploads_probe" | grep -i '^\(x-\|referrer\|strict\)' | tr '\n' ';')）"
done

# SSE 订阅端点必须由后端回答 401（ticket 缺失），而不是落到 SPA 的 index.html（200）：
# 若 nginx 精确匹配缺失，请求会被 try_files 兜到 /forum/index.html，这里会拿到 200；
# 后端未更新或未起则可能是 404/502 —— 三者都说明「实时通知不可用」，部署应立刻失败
stream_code="$(curl --max-time 5 -s -o /dev/null -w '%{http_code}' -H 'Host: raspi' \
    "http://127.0.0.1/forum/api/notifications/stream")"
[ "$stream_code" = "401" ] \
    || die "/forum/api/notifications/stream 未返回 401（返回 $stream_code，应为 401：缺 ticket 时后端必须拒绝；检查 nginx 精确匹配、后端版本与 reload）"

log "清理旧镜像（保留最近 2 个数字 tag，绝不做全局 prune）"
podman images --format '{{.Tag}}' localhost/forum-backend 2>/dev/null \
    | grep -E '^[0-9]+$' | sort -n | head -n -2 \
    | while read -r old; do
        podman rmi -f "localhost/forum-backend:$old" >/dev/null 2>&1 || true
    done || true

log "完成：http://raspi/forum/"
