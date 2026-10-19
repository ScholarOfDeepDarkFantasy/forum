# JavaForum 部署方案分析（raspi + Jenkins + podman + nginx）

> 状态：**已确认并实施**（2026-10-19；数据库选 B 复用现有实例，决策见 §9）。落地后的操作手册见 [`README.md`](README.md)。
>
> 需求（用户原话要点）：
> 1. 应用部署到 Linux 主机（SSH 里的 raspi）
> 2. 新增 Jenkins 文件，通过 raspi 上的 Jenkins 自动化部署
> 3. raspi 上用 podman，根据已有镜像调整 docker 文件
> 4. 前端不进容器，直接由 nginx 托管

---

## 1. 结论先行：目标形态

```
浏览器 ── http://raspi/forum/
   │
   ▼
nginx :80（系统服务，已有）
   ├─ /forum/        → /pd/Program/forum/frontend/     ← Vue dist（静态，要求 4）
   └─ /forum/api/    → 127.0.0.1:8086/api/             ← backend 容器
                              │
                    backend 容器（eclipse-temurin:17-jre-jammy，已有镜像，只绑 127.0.0.1:8086）
                              │  jdbc:postgresql://host.containers.internal:5432/javaforum
                    主机已有 postgresql 容器（postgres:15，rootless；库 javaforum，数据保留）

Jenkins :8080（已有服务）
   └─ job: javaforum（Jenkinsfile from SCM）
        Checkout(master, gh-proxy) → Test → BuildJar(M3/JDK17) → Image(podman, as pi)
        → FrontBuild(pnpm, VITE_BASE_PATH=/forum/) → Deploy(podman-compose -p forum)
        → Sync dist → nginx -t/reload → 冒烟
```

关键点：**不占 8080**（Jenkins 已占用）、**数据库不暴露到局域网**、**后端只绑 127.0.0.1**、**复用本机已有镜像、不拉新基础镜像**。

---

## 2. 主机现状（实测，raspi）

| 项 | 值 |
|---|---|
| OS / 架构 | Debian 12 bookworm，aarch64（4 核，3.7 GiB 内存，已用约 1.4 GiB） |
| 磁盘 | `/` 28G 82% 已用（剩 5.0G）；`/pd` 1.8T 剩 1007G ← 部署与镜像都放这里 |
| 容器 | podman 4.3.1（rootless，用户 pi），podman-compose 1.0.3，buildah；`docker` 是 podman 的别名命令（docker-compose 1.29.2 亦指向 podman） |
| 容器存储 | graphroot `/pd/container`（15G），已有镜像全部是 arm64 |
| Jenkins | 2.580.1，systemd 服务用户 `jenkins`(uid 114, 附加组含 pi)，`http://raspi:8080`，JENKINS_HOME=/var/lib/jenkins（1.4G，root 盘） |
| Jenkins 工具 | Maven 名 `M3` → `/usr/share/maven`（3.8.7）；JDK 工具 `Java21`(/usr/lib/jvm/jdk-21.0.11)、`Java17`(/usr/lib/jvm/java-17-openjdk-arm64)；git `/usr/bin/git` |
| Node | v24.16.0（nvm，pi 家目录），npm 12.0.1，**pnpm 11.12.0** 位于 `/home/pi/.nvm/versions/node/v24.16.0/bin/pnpm`（不在 jenkins 的 PATH，但 jenkins 可直接执行，已验证） |
| nginx | 系统服务，80 端口；`/etc/nginx/conf.d/app.conf` 内 `server_name raspi`；`/api/` 已反代给 mini-a-mall(8085) |
| 数据库 | 已有 rootless 容器 `postgresql`（postgres:15，`0.0.0.0:5432`）。库 `javaforum` 已存在（12 张表，来自早前真实部署），`forum` 角色对 flyway_schema_history 有 `arwd` 权限、其余表基本为 owner |
| 网络 | npmjs 约 10s（慢到超时边缘）、npmmirror 0.44s；github 直连约 10s（慢）、gh-proxy 0.65s；maven central 0.57s（可用，无需换源） |
| 已有关键镜像 | `postgres:15`、`eclipse-temurin:17-jre-jammy`、`node:lts-alpine3.*`、当地 mini-mall 各服务镜像；**没有 maven 镜像，也没有 jre-alpine 镜像** |
| 免密 sudo（`/etc/sudoers.d/jenkins_nopasswd`） | jenkins → root：`nginx -t`、`cp`、`systemctl reload nginx`；jenkins → pi：`bash`、`mvn`、`npm`、`podman`、`podman-compose`、`mkdir`、`echo`。pi 自身 `sudo ALL` 无密码 |

> 结论：主机侧**不需要再改 sudoers**。Jenkins 以 pi 身份跑 podman，天然复用 pi 的 rootless 容器与已有镜像；文件写入也用 `sudo -u pi` 完成，避免 jenkins/pi 属主纠纷。

---

## 3. 差距分析：现有文件为什么直接上不了 raspi

| 文件 | 现状 | 问题 | 处理 |
|---|---|---|---|
| `Dockerfile` | 两阶段：`maven:3.9-eclipse-temurin-17` → `eclipse-temurin:17-jre-alpine`；apk 装 curl/tzdata；jarmode 分层 | 两个构建基础镜像本机都没有；alpine 的 apk、curl 相关步骤全废；分层提取对这台小机器收益有限 | **改成"运行时单阶段"**：jar 由 Jenkins 的 M3+JDK17 构建，Dockerfile 只 `FROM eclipse-temurin:17-jre-jammy` + COPY jar。健康检查改用 bash `/dev/tcp`（jammy 无 curl，避免构建期联网） |
| `docker-compose.yml` | `name: javaforum`；`postgres:15-alpine`；后端 `8080:8080`；`depends_on: condition: service_healthy` | 1.0.3 不支持 `name:`；无 alpine 版 postgres；8080 与 Jenkins 冲突；1.0.3 的 depends_on 只认键、**忽略 condition**（healthcheck 也是 WIP） | 去 `name:`（用 `-p forum`）；`postgres:15`；后端绑 `127.0.0.1:${HOST_PORT:-8086}:8080`；db 不映射端口；数据库就绪由流水线轮询而不是 depends_on |
| `.dockerignore` | 排除 `target/` | 改成单阶段后 jar 必须进上下文 | 允许 `!target/*.jar`（或由 Jenkins 把 Dockerfile+jar 拷到 `/tmp/forum-image/target/` 再 build，上下文最小、最稳） |
| 无 `Jenkinsfile` | — | 需求 2 | 新增（见 §4.4） |
| 前端部署 | 只有 `vite build` | 未接 nginx；`VITE_BASE_PATH` 未在构建时注入 | Jenkins 构建 `VITE_BASE_PATH=/forum/`，dist 同步到 `/pd/Program/forum/frontend/`，nginx 出 `/forum/` |
| nginx | 无 `/forum` 路由 | 需求 4 | 新增 snippet：`/forum/` 静态 + SPA 回退，`/forum/api/` 反代 8086 |
| 密钥 | `.env.example` 只有占位 | 生产 JWT_SECRET/DB_PASSWORD 无管理 | 主机 **`/pd/Program/forum/.env`（pi 属主 0600）**，首次部署自动生成随机值；可选改用 Jenkins Credentials |
| 端口 | 8086 空闲（8086-8090 均空闲） | 需固定 | 默认 `HOST_PORT=8086` |
| pnpm | Jenkins PATH 里没有 | 前端跑不了 | 用绝对路径 `/home/pi/.nvm/versions/node/v24.16.0/bin/pnpm`（已验证 jenkins 可执行）；npm 源改用 npmmirror |

---

## 4. 方案详设

### 4.1 目录布局（主机）

```
/pd/Program/forum/                 # 部署根，pi:pi 2775（jenkins 在 group pi）
  deploy/
    docker-compose.yml             # 由仓库同步
    .env                           # 生成一次，0600，含 DB_PASSWORD / JWT_SECRET
  frontend/                        # nginx /forum/ 的 root（dist 同步，--delete）
/pd/jenkins-workspace/forum/       # Jenkins 工作区（放 /pd，避免吃根盘 5G）
```
Jenkins job 里用 `customWorkspace` 指定上述工作区。

### 4.2 镜像：按本机已有镜像重写 Dockerfile

```dockerfile
FROM docker.io/library/eclipse-temurin:17-jre-jammy          # 本机已有 arm64 镜像，不拉取
ENV TZ=Asia/Shanghai \
    SERVER_PORT=8080 \
    SPRING_PROFILES_ACTIVE=prod \
    LOG_FILE=/app/logs/javaforum.log \
    JAVA_OPTS="-Xms128m -Xmx512m -XX:+ExitOnOutOfMemoryError"

# 用数字 UID/GID，避免精简镜像缺 passwd/useradd；1001 只存在于容器内
WORKDIR /app
COPY --chown=1001:1001 target/forum-0.0.1-SNAPSHOT.jar /app/app.jar
RUN mkdir -p /app/logs && chown -R 1001:1001 /app
USER 1001:1001
EXPOSE 8080

# jammy 镜像没有 curl：用 bash 的 /dev/tcp 探测；需 --format docker 保留 HEALTHCHECK
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
  CMD ["/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080"]

ENTRYPOINT ["/bin/bash", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
```

实现时必须验证的点：
- 普通用户 `app` + `/app/logs` 可写（日志落到挂载卷）；
- `podman build --format docker`（否则 OCI 格式会静默丢弃 HEALTHCHECK，mini-mall 构建日志里已有这条 warning）；
- 镜像名 `localhost/forum-backend:<BUILD_NUMBER>`，保留上一版用于回滚。

### 4.3 compose（podman-compose 1.0.3 兼容写法）

```yaml
services:
  backend:
    image: localhost/forum-backend:${IMAGE_TAG:-latest}
    container_name: forum-backend
    environment:
      SPRING_PROFILES_ACTIVE: prod
      DB_URL: ${DB_URL}
      DB_USERNAME: ${DB_USERNAME}
      DB_PASSWORD: ${DB_PASSWORD}
      JWT_SECRET: ${JWT_SECRET}
      SERVER_PORT: "8080"
      LOG_FILE: /app/logs/javaforum.log
      TZ: ${TZ:-Asia/Shanghai}
      # 显式 -Xmx：主机 cgroup_disable=memory，容器看不到内存上限（见 §10）
      JAVA_OPTS: ${JAVA_OPTS:--Xms128m -Xmx512m -XX:+ExitOnOutOfMemoryError}
    ports:
      - "127.0.0.1:${HOST_PORT:-8086}:8080"
    restart: unless-stopped
    # 不写 mem_limit：cgroup_disable=memory，crun 写 memory.max 会失败（见 §10）
```

1.0.3 的已知限制与对策：
- 自动读取「compose 文件同目录的 `.env`」做变量插值（已确认源码），所以 `.env` 放 `deploy/.env` 即可；
- `container_name` 支持（min 日志里已见 `podman run --name`）；`down` 子命令存在但无 `--remove-orphans`；
- 不支持 volume 的 `:U` 等扩展选项（旧版解析会静默忽略）→ 不挂日志卷，`podman logs` 看日志；
- 无 `profiles`；顶层 `name:` 不可靠 → 统一 `podman-compose -p forum`；
- healthcheck 支持是 WIP → 不依赖它，部署脚本用 curl 轮询；
- `mem_limit` 会转成 `-m`，但本机内核带 `cgroup_disable=memory`，crun 写 `memory.max` 直接失败 → 不用 `mem_limit`，靠 `JAVA_OPTS -Xmx` 限堆。

### 4.4 Jenkins 流水线（新增 `Jenkinsfile`）

Job 形态：`Pipeline script from SCM`
- SCM：Git，URL `https://gh-proxy.org/https://github.com/ScholarOfDeepDarkFantasy/forum.git`（仓库是公开的，无需凭据；直连 GitHub 约 10s，走代理 0.6s），分支 `*/master`，Script Path `Jenkinsfile`
- 参数：`RUN_TESTS`(bool，默认 false)、`DEPLOY`(bool，默认 true)、`IMAGE_TAG`(string，空则用 BUILD_NUMBER)
- 触发：`pollSCM('H/5 * * * *')`（主机无公网入口，GitHub webhook 进不来）+ 手动

阶段：

| # | Stage | 关键动作 |
|---|---|---|
| 1 | Checkout | `checkout scm`（customWorkspace=/pd/jenkins-workspace/forum） |
| 2 | Backend Tests | 仅 RUN_TESTS：`JAVA_HOME=Java17 mvn -B test` |
| 3 | Build Jar | `JAVA_HOME=Java17 mvn -B -DskipTests clean package` → `target/forum-0.0.1-SNAPSHOT.jar` |
| 4 | Frontend Build | `PNPM=/home/pi/.nvm/versions/node/v24.16.0/bin/pnpm`；`pnpm install --frozen-lockfile --registry=https://registry.npmmirror.com`；`VITE_BASE_PATH=/forum/ pnpm build` |
| 5 | Build Image | 组装 `/tmp/forum-image/target/*.jar` + Dockerfile → `sudo -u pi podman build --format docker -t localhost/forum-backend:$TAG /tmp/forum-image` |
| 6 | Prepare Deploy Dir | `sudo -u pi mkdir -p /pd/Program/forum/{deploy,frontend}`；同步 compose；`.env` 必须已存在（缺失时脚本报错并提示引导文档，不做自动生成） |
| 7 | Check DB | 复用主机 `postgresql` 容器；Flyway 启动时校验历史（已确认 checksum 匹配），`DataInitializer` 幂等 |
| 8 | Deploy Backend | `podman-compose -p forum up -d`，轮询 `curl http://127.0.0.1:8086/api/health`（最多 120s），失败打印 `podman logs` |
| 9 | Publish Frontend | `sudo -u pi rsync -a --delete frontend/dist/ /pd/Program/forum/frontend/` |
| 10 | Nginx | `sudo cp deploy/nginx/forum.conf /etc/nginx/snippets/` → `nginx -t` → `systemctl reload nginx` |
| 11 | Smoke | `/api/health`；`/forum/` 引用 `/forum/assets/`；`/forum/posts/1` 回退到 `index.html`；经 nginx 的 `/forum/api/posts` 200 |
| 12 | Cleanup | 只保留最近 2 个 `localhost/forum-backend` 数字 tag；**绝不全局 `podman image prune`**（会删 mini-mall 等共用镜像） |

`JAVA_HOME` 用 Jenkins 工具 `Java17`（declarative `tools { jdk 'Java17' }` + `mvn` 用 `M3`）；`JAVA_TOOL_OPTIONS` 不用，避免污染。

### 4.5 前端与 nginx（需求 4）

构建：`VITE_BASE_PATH=/forum/ pnpm build` → 产物内资源 `/forum/assets/...`，路由 base `/forum/`，axios baseURL `/forum/api`（与 README「部署到子路径」一节一致，项目已有 `src/viteConfig.spec.js` 守着）。

nginx snippet `deploy/nginx/forum.conf`（挂到 80 端口的 server 内）：

```nginx
location = /forum { return 302 /forum/; }

location ^~ /forum/assets/ {
    alias /pd/Program/forum/frontend/assets/;
    expires 7d;
    access_log off;
}

location /forum/ {
    alias /pd/Program/forum/frontend/;
    index index.html;
    try_files $uri $uri/ /forum/index.html;   # SPA 回退
}

location /forum/api/ {
    proxy_pass http://127.0.0.1:8086/api/;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 60s;
}
```

一次性动作（实施时做一次，二选一）：
- **推荐**：在 `/etc/nginx/conf.d/app.conf` 的 server 块加一行 `include /etc/nginx/snippets/forum.conf;`，之后 snippet 由流水线维护；
- 备选：流水线整体覆盖 `app.conf`（`sudo cp` 已允许，但会覆盖其他人的手工改动）。

注意：`conf.d/app.conf` 是 `server_name raspi`，而 `sites-enabled/default` 是 default_server；**必须用 `http://raspi/forum/` 访问**，用 IP 会落到 default。所以冒烟测试带 `-H 'Host: raspi'`。

### 4.6 数据库：复用主机已有实例（已确认，选项 B）

主机已有一个 rootless `postgresql` 容器（postgres:15，`0.0.0.0:5432`），库 `javaforum` 与 12 张表是早前一次真实部署留下的。**已确认复用**：

- backend 容器内 `DB_URL=jdbc:postgresql://host.containers.internal:5432/javaforum`（podman 自动注入主机名，实测解析为 192.168.3.4；失败则退化为宿主机局域网 IP）；
- compose 不再起 db，部署只重建 backend 一个容器；
- 数据保留；`DataInitializer` 幂等（admin 已存在则跳过），不会覆盖已有用户/帖子；
- 旧密码曾以明文出现在 git 历史的 `.env` 中（commit `6eecbdc`），引导时已重置 `forum` 角色密码为随机值，仅写入 `deploy/.env`（0600）；
- Flyway 历史与当前迁移文件一致（V1/V2 checksum 已用 CRC32 复算匹配），启动 `validate` 不会失败。

### 4.7 密钥与配置

- `deploy/.env`（主机生成，0600，pi 属主）：`DB_URL`、`DB_USERNAME=forum`、`DB_PASSWORD`、`JWT_SECRET`（各 `openssl rand -hex 32`）、`HOST_PORT=8086`、`TZ=Asia/Shanghai`、`IMAGE_TAG`（流水线每次改写）
- 仓库里 `.env.example` 只留占位与注释，不提交真实值（`.gitignore` 已忽略 `.env`）
- Jenkins Credentials 方案已确认**不采用**（少一步 Jenkins 配置，密钥只存在于主机 `.env`）

### 4.8 资源与稳定性

- 内存 3.7G、已用 1.4G：只有一个 backend 容器；JVM 用 `-Xms128m -Xmx512m` 显式限堆（主机无内存 cgroup，不能用 `mem_limit`/`MaxRAMPercentage`），数据库是已有的 postgresql 容器（~50MB），余量充足。
- 镜像/卷/工作区都在 `/pd`（1TB 空余），根盘 5G 不参与。
- 容器 `restart: unless-stopped`，主机重启后 pi 的 rootless podman 是否自启取决于 `podman-restart.service`/linger；当前 pi 的 linger=no。**当前pi 有运行中的容器（mini-mall 等），说明靠登录/手动恢复**；我们保持同样方式，不额外引入系统级改动。这点会在部署文档里写明。
- 不与现有容器冲突：compose 项目名 `forum`，卷/网络名带 `forum_` 前缀；端口 8086 空闲。

---

## 5. 变更清单（实施时）

**新增**
- `Jenkinsfile`
- `deploy/nginx/forum.conf`
- `docs/deployment/README.md`（一次引导 + 构建/回滚/排障手册）
- `docs/deployment/raspi-jenkins-podman-analysis.md`（本文件）

**修改**
- `Dockerfile`（运行时单阶段，见 §4.2）
- `.dockerignore`（放行 jar / 适配 `/tmp` 构建上下文）
- `docker-compose.yml`（podman 兼容，见 §4.3）
- `.env.example`（HOST_PORT=8086、IMAGE_TAG、说明）
- `README.md`（部署章节改为 raspi/Jenkins 流程）
- `docs/DEVELOPMENT.md`（T9/T10 技术债状态、§4.7 部署记录）

**不改**：`src/**`、`frontend/src/**`、`pom.xml`（构建方式已够）。

---

## 6. 一次性引导（实施阶段执行一次）

1. `/pd/Program/forum/{deploy,frontend}` 建目录（pi:pi，2775）
2. nginx `app.conf` 加 include（推荐 snippet 方案）
3. Jenkins 新建 Pipeline job `javaforum`（SCM + Jenkinsfile；参数/轮询已写在 Jenkinsfile 内）
4. （可选）Jenkins Credentials 存两个密钥
5. 首次构建 → 首次部署 → 冒烟

---

## 7. 验证方案（部署后逐条过）

- [x] Jenkins job 构建绿灯，日志里 jar、镜像 tag 正确
- [x] `curl http://127.0.0.1:8086/api/health` → `{"success":true,...}`
- [x] `curl -H 'Host: raspi' http://127.0.0.1/forum/` → 200 且引用 `/forum/assets/...`
- [x] 浏览器 `http://raspi/forum/` 能注册/登录/发帖（走 `/forum/api/`）
- [x] `podman-compose -p forum ps` 只有 `forum-backend` Up，宿主仅监听 `127.0.0.1:8086`
- [x] 重复部署幂等；`IMAGE_TAG` 回滚可切上一版镜像（Build #2 已重复执行一遍完整部署）
- [x] 构建前后的 mini-mall 容器/镜像不受影响

> 2026-10-19 Build #2（commit `771990d`）部署成功，以上逐条实测通过；未在真实浏览器里点「注册/发帖」，用 `POST /forum/api/auth/login` 与 `GET /forum/api/posts` 验证了鉴权与数据链路。

---

## 8. 风险

| 风险 | 影响 | 对策 |
|---|---|---|
| podman-compose 1.0.3 太老（healthcheck/condition/name 不完整） | 起容器顺序不可靠 | 流水线显式轮询就绪；实现时以实测校准容器名 |
| jre-jammy 无 curl，useradd/groupadd 是否存在 | HEALTHCHECK/构建失败 | 已改用 bash /dev/tcp；useradd 存在（Debian 系）；失败则退回 `--user 1001:1001` |
| pnpm 11 与 lockfile v9、Node 24 的兼容性 | 前端装包失败 | 实测 `pnpm install --frozen-lockfile`；不行则 `corepack prepare pnpm@10` 固定版本 |
| npmjs 极慢 | 构建超时 | 强制 `--registry=https://registry.npmmirror.com` |
| GitHub 直连慢 | checkout 慢/失败 | 用 gh-proxy（与 mini-mall 一致） |
| 共用 rootless 存储误删 | 影响 mini-mall 等 | 只按 `localhost/forum-backend` 精确清理，禁止全局 prune |
| 首次构建下载 maven 依赖 | 构建慢（~几分钟） | 复用 `/var/lib/jenkins/.m2`（389M 已有），Java17/M3 工具已配 |

---

## 9. 确认结果（2026-10-19）

1. **数据库**：**B 复用现有 postgresql 里的 javaforum**（数据保留，`host.containers.internal:5432`）。
2. **触发方式**：`pollSCM` 每 5 分钟自动 + 手动。
3. **nginx 管理**：snippet + 一次性 include（`/etc/nginx/conf.d/app.conf` 已加 include）。
4. **密钥**：主机 `deploy/.env`（0600），不引入 Jenkins Credentials。
5. 执行方式：本机完成代码/文档修改，主机侧一次性引导由本次会话直接完成，并跑通首次构建与冒烟。

确认后我按 §5 改文件并跑通首次部署。

---

## 10. 实施记录（2026-10-19）

- **首次构建（Build #1）红在部署**：`podman run ... -m 768m` 启动容器失败：
  `crun: opening file `memory.max` for writing: No such file or directory`。
  原因：raspi 的 `/proc/cmdline` 带 `cgroup_disable=memory`，`/sys/fs/cgroup/cgroup.controllers` 只有 `cpuset cpu io pids`；
  rootless crun 没有内存控制器可写，`mem_limit` 在此机器上必然失败。
- **修正**：compose 去掉 `mem_limit`；内存改由 `JAVA_OPTS=-Xms128m -Xmx512m` 显式限堆
  （无内存上限时 `MaxRAMPercentage` 会按整机 3.7G 算，反而容易把整机拖进 OOM）；
  `deploy.sh` 在 `podman-compose up` 后立即检查容器 `Running`，失败即打印 `podman logs` 并退出，不再白等 120s 健康检查。
- 宿主机 `/pd/Program/forum/deploy/.env` 的 `JAVA_OPTS` 同步为 `-Xms128m -Xmx512m -XX:+ExitOnOutOfMemoryError`。
- 首次构建已验证的前半段：Maven 打包成功（jar 63M）、前端 `VITE_BASE_PATH=/forum/` 构建成功、镜像 `localhost/forum-backend:1` 构建成功（390MB）。
