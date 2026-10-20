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

# 上传目录必须由 nginx 的 ^~ /forum/uploads/ 直出：
# 命中 alias 时，不存在的文件是 404；若配置没生效，请求会落到 SPA 的
# try_files 而返回 200 + index.html —— 这正是要抓的静默错误
uploads_code="$(curl --max-time 5 -s -o /dev/null -w '%{http_code}' -H 'Host: raspi' \
    "http://127.0.0.1/forum/uploads/__probe__/missing.jpg")"
[ "$uploads_code" = "404" ] \
    || die "/forum/uploads/ 未直出（返回 $uploads_code，应为 404；检查 nginx snippet 是否已 reload 且 /pd/Program/forum/uploads 可读）"

log "清理旧镜像（保留最近 2 个数字 tag，绝不做全局 prune）"
podman images --format '{{.Tag}}' localhost/forum-backend 2>/dev/null \
    | grep -E '^[0-9]+$' | sort -n | head -n -2 \
    | while read -r old; do
        podman rmi -f "localhost/forum-backend:$old" >/dev/null 2>&1 || true
    done || true

log "完成：http://raspi/forum/"
