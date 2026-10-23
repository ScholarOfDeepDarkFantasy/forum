# JavaForum

基于 Spring Boot 3 + Vue3 的论坛网站 MVP。

> **开发记录：** 开发进度、已完成工作、待办与技术债、后续计划、关键决策记录见 [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md)。该文档在每次开发后更新。

## 技术栈

- 后端：Java 17 / Maven / Spring Boot 3.5 / Spring Security 6（JWT 无状态认证）/ Spring Data JPA / PostgreSQL / Flyway / springdoc-openapi
- 前端：Vue3 + Vite + Pinia + Vue Router + Axios + Element Plus（`frontend/` 子目录）
- 测试：JUnit 5 + Spring Boot Test + H2（后端）；Vitest + @vue/test-utils + jsdom（前端）

## 功能

- 用户：注册 / 登录 / 个人资料修改（昵称、简介、邮箱展示），JWT 无状态会话；资料页支持上传与移除头像
- 帖子：板块内发帖、编辑、删除（软删除），分页列表与关键词搜索，浏览量统计
- 互动：点赞、收藏与「我的收藏」；评论支持楼中楼，删除后保留占位
- 权限：管理员 / 版主 / 普通用户三级角色，支持置顶、加精、禁言、封禁
- **会话安全**：登出与封禁会让旧 JWT 立即失效（`users.token_version` 版本号校验，过滤器每请求比对一次）；登录失败按客户端 IP 计数，`LOGIN_MAX_FAILURES`（默认 5）次密码错误后锁定 `LOGIN_LOCK_MS`（默认 15 分钟），锁定期间返回 429 并带 `Retry-After` 秒数，即使密码正确也拒绝
- 通知：点赞、收藏、回复三类通知（另有 `SYSTEM` 预留类型），未读角标与单条已读，点击跳转到帖子或具体评论；新通知经 SSE 实时到达（顶栏角标即时 +1、右上角轻提示），断线按 1s→30s 退避自动重连，重连后校准未读数
- **Markdown 渲染**：帖子正文按 CommonMark + 表格/删除线（`markdown-it`）渲染（标题、列表、表格、代码块高亮、链接、图片），编辑页提供实时预览；渲染结果经 DOMPurify 白名单过滤、外链补 `target`/`rel`、图片懒加载后才插入 DOM，服务端仍只存纯文本
- **标签检索**：帖子可带多个标签，`/tags` 展示标签云（按帖子数降序，支持关键词检索与分页），点击标签进入 `/tags/:name` 查看该标签下的帖子
- **深色模式**：默认跟随系统，也可手动选择浅色/深色（顶栏右侧、通知铃铛之前的主题菜单），选择存于 `localStorage` 的 `jf_theme`；`index.html` 的内联脚本在首屏挂上 `html.dark`，避免刷新时先白后黑；配色来自 `styles/tokens.css` 的双套 CSS 变量与 Element Plus 官方暗色变量
- **头像上传**：资料页选择 JPEG/PNG（不超过 2MB），服务端只按文件魔数判定类型、按 EXIF 方向校正后居中裁剪为 256×256 JPEG，落盘到宿主机的 `/pd/Program/forum/uploads/avatars/` 并由 nginx 直出；支持移除，图片缺失或加载失败时回退为昵称首字母
- **全文搜索**：顶栏搜索框或 `/search?q=` 独立结果页，标题与正文子串匹配、标题命中优先、命中词高亮；正文命中时显示片段。中文按子串匹配，无分词与相关度排序
- 部署：前端由 nginx 托管、后端容器化运行，Jenkins 自动构建发布（见 `docs/deployment/README.md`）

## 环境要求

- JDK 17+
- Maven 3.8+
- PostgreSQL 14+
- Node.js 18+ 与 pnpm（前端）

## 数据库准备

```sql
CREATE DATABASE javaforum WITH ENCODING 'UTF8';
```

建表与升级由 Flyway 负责，脚本位于 `src/main/resources/db/migration/`：

- `V1__init_schema.sql`：初始表结构与角色种子数据
- `V2__notification_comment_id.sql`：通知表新增可空的 `comment_id` 列，用于把 REPLY 通知定位到具体评论
- `V3__post_search_trgm_index.sql`：安装 `pg_trgm` 扩展，并为 `LOWER(title)`/`LOWER(content)` 建 GIN 三元组部分索引（`WHERE deleted = false`），支撑全文搜索的「包含」语义
- `V4__add_user_token_version.sql`：`users` 加 `token_version BIGINT NOT NULL DEFAULT 0`，登出/封禁时 +1，用于让已签发的 JWT 立即失效（旧 token 没有 `ver` 声明时按 0 处理，升级不强制重登）

后端 `spring.jpa.hibernate.ddl-auto` 设为 `validate`：Hibernate 只校验实体与表结构是否一致，不建表也不改表，因此任何实体变更都必须配套一条迁移脚本。

## 运行（后端）

数据库密码与 JWT 密钥**不写进仓库**，从环境变量读取。`application.yml` 对 `DB_PASSWORD` 与 `JWT_SECRET` 刻意不设默认值：缺失时应用启动即失败并报 `Could not resolve placeholder`，而不是用弱密钥悄悄跑起来。

### 1. 准备环境变量

复制 `.env.example` 为 `.env`（已被 `.gitignore` 忽略）并填入真实值，然后在启动前加载进当前 shell。

PowerShell：

```powershell
Get-Content .env | ForEach-Object {
  if ($_ -match '^\s*([A-Z_]+)\s*=\s*(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1], $matches[2])
  }
}
```

也可以临时逐个设置：

```powershell
$env:DB_PASSWORD = "你的数据库密码"
$env:JWT_SECRET = "至少32字节的随机密钥"
```

`JWT_SECRET` 用 HS256，长度不足 32 字节会在构造密钥时抛异常。生成一条：`openssl rand -hex 32`。

### 2. 启动

```bash
mvn spring-boot:run
```

`pom.xml` 里 `<spring-boot.run.profiles>` 默认为 `dev`，所以本地启动会自动进入开发配置，无需额外参数。

### 3. Profile 说明

| 配置项 | `dev`（本地默认） | `prod` |
|---|---|---|
| DevTools 热重启 | 开 | 关 |
| SQL 明细（`org.hibernate.SQL`） | `debug` | `warn` |
| SQL 绑定参数（`jdbc.bind`） | `trace` | 不打印 |
| 连接池上限 | 10 | 20 |
| Actuator 暴露 | 仅 `health` | 仅 `health`，`show-details: never` |
| 文件日志 | 无（只输出到控制台） | `logs/javaforum.log` |

指定 profile：

```bash
# 生产
mvn spring-boot:run -Dspring-boot.run.profiles=prod
# 或打包运行
java -jar target/forum-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

`prod` 下 `org.hibernate.orm.query` 保持 `warn`——`HHH90003004`（集合关联与分页同用导致分页退化为全表加载）就是在这个级别输出，保留它才能在真实流量下发现性能退化。

### 4. 显式配置项

除 `DB_PASSWORD`、`JWT_SECRET` 两个必填项外，其余都有默认值，可按需覆盖（生产环境由 `docker-compose.yml` 的 `environment` 透传，改 `.env` 后重启容器即生效；第七轮的 4 个 `LOGIN_*` 已列入透传，见部署手册）：

| 变量 | 默认值 |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/javaforum` |
| `DB_USERNAME` | `forum` |
| `DB_POOL_MAX` | `dev` 为 10，`prod` 为 20 |
| `SERVER_PORT` | `8080` |
| `JWT_EXPIRATION_MS` | `86400000`（24 小时） |
| `LOG_FILE` | `logs/javaforum.log`（仅 `prod`） |
| `SSE_TIMEOUT_MS` | `1800000`（单条 SSE 连接最长 30 分钟，到点客户端自动重连） |
| `SSE_HEARTBEAT_MS` | `15000`（心跳事件间隔，用于穿透代理的空闲超时） |
| `SSE_TICKET_TTL_MS` | `60000`（一次性连接凭证有效期） |
| `SSE_MAX_CONNECTIONS_PER_USER` | `5`（每用户并发连接上限，超出时挤掉最早的一条） |
| `LOGIN_MAX_FAILURES` | `5`（同一 IP 在失败窗口内允许的错误密码次数，达到即锁定） |
| `LOGIN_WINDOW_MS` | `900000`（失败计数窗口，15 分钟；窗口外的失败自动作废） |
| `LOGIN_LOCK_MS` | `900000`（锁定时长，15 分钟；锁定内即使密码正确也返回 429） |
| `LOGIN_CLEANUP_INTERVAL_MS` | `60000`（清理过期窗口的间隔；只为回收内存，不影响判定） |

以上 4 个 `LOGIN_*` 已由 `docker-compose.yml` 透传（默认值同上表），生产改 `.env` 后 `podman-compose up -d` 即生效。

### 5. 验证启动

- 健康检查：http://localhost:8080/api/health
- Swagger 文档：http://localhost:8080/swagger-ui.html
- Actuator 健康（需 ADMIN 身份，匿名会得到 401）：http://localhost:8080/actuator/health

首次启动会由 `src/main/java/org/song/forum/config/DataInitializer.java` 幂等创建默认管理员账号（已存在则跳过）：

- 用户名 `admin`
- 密码 `admin123456`（生产环境请立即修改）

该账号用于访问后台的用户管理与板块管理页面。

## 部署（Linux + podman + nginx）

正式环境是 SSH 别名 `raspi` 的 Debian 12 主机：Jenkins 构建后端 jar 与前端 dist，podman 跑后端容器，nginx 直接托管前端并反代 API。完整操作手册（一次性引导、回滚、排障）见 [`docs/deployment/README.md`](docs/deployment/README.md)，方案取舍见 [`docs/deployment/raspi-jenkins-podman-analysis.md`](docs/deployment/raspi-jenkins-podman-analysis.md)。

```
http://raspi/forum/            nginx :80
  ├─ /forum/                   /pd/Program/forum/frontend/（Vue dist，不进容器）
  └─ /forum/api/               127.0.0.1:8086（forum-backend 容器）
                                 └─ host.containers.internal:5432 → 主机已有 postgresql 容器（库 javaforum）
```

相关文件：

| 文件 | 作用 |
|---|---|
| `Dockerfile` | 运行时单阶段镜像：`eclipse-temurin:17-jre-jammy`（raspi 已有镜像）+ 预构建 jar；非 root（UID 1001），bash `/dev/tcp` 健康检查 |
| `.dockerignore` | 只放行 `target/forum-0.0.1-SNAPSHOT.jar`，排除前端、文档、`.env`、日志 |
| `docker-compose.yml` | 只定义 `backend`（podman-compose 1.0.3 兼容）；数据库复用主机已有实例，不新建 db 容器 |
| `Jenkinsfile` | Checkout → (Tests) → Build Jar → Build Frontend → Build Image → Deploy |
| `deploy/deploy.sh` | 部署脚本：compose 同步、镜像切换、健康检查、前端 rsync、nginx reload、冒烟、旧镜像清理 |
| `deploy/nginx/forum.conf` | nginx snippet：`/forum/` 静态 + SPA 回退、`/forum/api/` 反代 |

头像文件既不在镜像里，也不进数据库：宿主机目录 `/pd/Program/forum/uploads/` 通过 bind mount 挂进容器（部署机 `.env` 的 `UPLOAD_HOST_DIR`，容器内固定 `/app/uploads`），再由 nginx 的 `location ^~ /forum/uploads/` 直出静态文件，并带 `X-Content-Type-Options: nosniff`。`deploy/deploy.sh` 每次部署都会 `mkdir -p .../uploads/avatars`、在 rootless podman 的 user namespace 里 `podman unshare chown -R 1001:1001`、再 `podman unshare chmod -R a+rX`（既抹平 umask 差异，也顺带治愈历史上 0600 导致 nginx 403 的文件），并把 `UPLOAD_HOST_DIR` 幂等写入 `.env`。注意容器内 UID 1001 在宿主上映射为 subuid（如 101000），直接在宿主上 `chown 1001:1001` 会落到错误的用户。

### 1. 本地构建镜像（可选）

部署主机上没有 maven 镜像，jar 由 Jenkins 的 M3 + JDK17 构建。手工验证镜像：

```bash
mvn -B -DskipTests clean package
podman build --format docker -t localhost/forum-backend:local .
```

> 必须带 `--format docker`，否则 OCI 格式会丢弃 `HEALTHCHECK`。

### 2. 部署到 raspi

1. 一次性引导见 [`docs/deployment/README.md`](docs/deployment/README.md) §2；
2. `git push` 到 `master`，Jenkins 每 5 分钟轮询并自动构建部署（也可在 Jenkins UI 手动 Build）；
3. 验证：`curl http://raspi/forum/`、`curl http://127.0.0.1:8086/api/health`。

### 3. 与本地运行的差异

| 项 | 本地 `mvn spring-boot:run` | raspi 容器 |
|---|---|---|
| Profile | `dev`（打印 SQL） | `prod`（SQL 只留告警） |
| 时区 | 宿主机时区 | `TZ`，默认 `Asia/Shanghai` |
| 端口 | `SERVER_PORT` 或 8080 | 容器内 8080，宿主只绑 `127.0.0.1:${HOST_PORT:-8086}` |
| 日志 | 控制台 | 容器内 `/app/logs/javaforum.log`（`podman logs` 同时可看） |
| 数据库 | 本机 PostgreSQL | 主机已有 `postgresql` 容器；容器内经 `host.containers.internal:5432` 访问 |

`.env` 里的 `DB_URL` 在部署机是 `jdbc:postgresql://host.containers.internal:5432/javaforum`，本地开发仍是 `localhost`，`.env.example` 已分两段写。密钥只存在于部署机 `deploy/.env`（0600），仓库不含真实值。

## 运行（前端）

```bash
cd frontend
pnpm install
pnpm dev
```

开发服务器默认 http://localhost:5173，并把 `/api` 代理到 `http://localhost:8080`（见 `frontend/vite.config.js`），前端统一使用相对路径 `/api`，本地开发无需处理 CORS。

### 部署到子路径（如 `/forum/`）

默认部署在根路径。若需部署到 `http://example.com/forum/`，构建时指定 `VITE_BASE_PATH`：

```bash
VITE_BASE_PATH=/forum/ pnpm build
```

构建产物里资源路径会变成 `/forum/assets/...`，同时：

- 路由 `createWebHistory(import.meta.env.BASE_URL)` 自动带上 `/forum/`
- axios 的 `baseURL` 变成 `/forum/api`，登录失败跳转变成 `/forum/login`

服务器需要把 `/forum/` 指向 `dist/`，并把 `/forum/api/` 反代到后端 `/api/`。nginx 示例：

```nginx
location /forum/ {
    alias /usr/share/nginx/html/forum/;
    try_files $uri $uri/ /forum/index.html;
}
location /forum/api/ {
    proxy_pass http://backend:8080/api/;
    proxy_set_header Host $host;
}
```

> **注意**：`vite.config.js` 的 `base` 只接受绝对 URL、`'./'` 或空串。写成 `'./forum'` 这类值**不会让构建失败**，只在日志里留一条 `(!) invalid "base" option` 警告然后按 `/` 处理，产物仍是 `/assets/...`，上线后整站 404。`src/viteConfig.spec.js` 会守住这条以及 base / 路由 / 接口前缀三处联动。


## 测试

### 后端测试

```bash
mvn test
```

测试使用 H2 内存库（PostgreSQL 兼容模式 `MODE=PostgreSQL`），Flyway 关闭、`ddl-auto=create-drop`，无需本地 PostgreSQL、也无需设置任何环境变量（`src/test/resources/application-test.yml` 由 `@ActiveProfiles("test")` 提供测试专用凭据）。当前为 20 个测试类、163 个用例（其中 1 个 POSIX 文件权限用例在 Windows 上跳过）。

### 前端测试

```bash
cd frontend
pnpm test        # Vitest 单元与组件测试
pnpm build       # 生产构建
```

当前为 31 个 spec、314 个用例，覆盖：

- 通知跳转映射（`src/utils/notificationLink.js`）：REPLY 带评论锚点、缺 `commentId` 时退化为只跳帖子、LIKE/FAVORITE 跳帖子、SYSTEM 与非法输入不可跳转
- 通知 store（`src/stores/notifications.js`）：未读数刷新、请求失败保留上次计数、全部已读、单条已读（就地更新列表项、未读角标钳在 0、已读不重复请求）、列表分页
- 点赞/收藏共享状态（`src/composables/usePostActions.js`）：失败回滚、同步服务端 `liked`/`favorited` 标志且不覆盖本地已操作状态
- 响应式判定与断点一致性（`src/composables/useResponsive.js`、`src/styles/breakpoint.spec.js`）：媒体查询断点数量必须恰为 6、字面量必须逐字写成 `768px`、数值须与 `MOBILE_BREAKPOINT` 一致，且禁止出现 `min-width` 查询
- 分页样式收敛（`src/styles/common.spec.js`）：`.jf-pager` 规则完整、`common.css` 在 `tokens.css` 之后引入、没有视图重新定义 `.pager`、每个 `el-pagination` 都带 `jf-pager` 且数量精确相等
- 时间格式化（`src/utils/format.spec.js`）：ISO 串截到分钟、空值返回空串、相对时间的刚刚/分钟/小时/回退日期四档，以及未来时间不产生负数
- 后台卡片组件（`src/components/cards.spec.js`）：字段与空值占位渲染、`formatTime`/`statusTag` 透传、ADMIN 账号的操作按钮禁用、事件 payload
- 接口层（`src/api/index.spec.js`）：401 时凭据类接口（登录/注册）原样展示后端消息且不清除登录态，其余接口清 token 并跳登录页（版块与列表加载、点赞交互与游客跳转、行点击跳详情）、`tagApi` 的请求路径与参数（`/tags` 携带 `keyword` 与分页参数、`/tags/posts` 携带 `name` 与分页参数）、`PostDetailView`（互动字段实时读共享状态：列表点的赞在详情页同步生效、置顶整体替换 `post` 后互动字段不被返回体带偏；正文按 Markdown 渲染后不得直接输出原始内容、无正文时显示占位）、`AdminView`（桌面表格 / 窄屏卡片、空状态、视口切换与监听清理、卡片事件接线）与 `ProfileView`（收藏分页渲染、空状态）
- Markdown 渲染（`src/utils/markdown.js`）：CommonMark + 表格（标题/列表/表格）与代码块高亮、单换行转 `<br>`、外链补 `target`/`rel`、图片补 `loading=lazy`、原始 HTML 与事件属性被过滤、`javascript:` 链接不可点击、未注册语言降级为纯文本、裸链接自动识别、站内/锚点/邮件链接不加 `target`、表格包在横向滚动容器内、空输入与渲染异常兜底
- 标签页（`src/views/TagsView.spec.js`、`src/views/TagPostsView.spec.js`、`src/components/PostListItem.spec.js`）：标签云渲染与点击跳转、关键词检索重置到第一页、分页切换、空态与失败重试、路由标签名变化时重置分页并重新加载、公共帖子行渲染置顶/精华标记与互动计数、点击标签按 URL 规则编码且不触发行跳转、点赞走共享计数
- 编辑页 Markdown 预览（`src/views/PostEditView.spec.js`）：桌面左右分栏且输入正文时预览实时渲染、窄屏显示编辑/预览 tab 而不是分栏、编辑模式回填旧正文并渲染预览
- 深色模式（`src/composables/useTheme.spec.js`、`src/components/ThemeToggle.spec.js`、`src/styles/tokens.spec.js`）：默认跟随系统并实时响应系统主题变化、手动选择写入 `localStorage`、存储值损坏时回退 `system`、`toggle` 按当前实际明暗取反、切到显式模式后解除 `matchMedia` 监听、`matchMedia` 缺失或 Storage 抛错不崩溃；`tokens.spec.js` 另守两套令牌集合一致、每个颜色令牌都有 `var()` 消费方、`main.js` 的 8 条样式导入顺序、首屏脚本先于模块脚本读取 `jf_theme`
- 头像（`src/utils/avatar.spec.js`、`src/components/UserAvatar.spec.js`、`src/views/ProfileView.spec.js`）：站内 `/uploads/` 前缀拼 `BASE_URL`（含 `/forum/` 子路径）、外链与 data URL 原样透传、空值与仅前缀返回空串、加载失败回退昵称首字母并在 url 变化后重试、代理对昵称只取一个码点、上传成功刷新用户、非法类型与超过 2MB 时客户端拦截、移除头像
- 搜索页与顶栏入口（`src/views/SearchView.spec.js`、`src/components/HeaderSearch.spec.js`、`src/components/PostListItem.spec.js`、`src/api/index.spec.js`）：读 `?q=` 拉取结果并展示计数、三种空态、分页与换词重置、过期响应丢弃、顶栏桌面输入框/窄屏图标两种形态、命中词 `<mark>` 高亮与正文片段、`postApi.search` 的请求路径
- 搜索文本工具（`src/utils/search.spec.js`）：大小写不敏感分段高亮、正则元字符按字面匹配、Markdown 轻量清洗（代码围栏/行内代码/标题/引用/列表/链接/图片）、空白折叠、命中窗口与省略号
- 实时通知连接（`src/composables/useNotificationStream.spec.js`）：取票后建立 SSE 连接、事件回调合并到 store、断线按 1s→30s 指数退避重连、重连成功回调、401 取票失败进入 `disabled` 且不再重连、坏 JSON 忽略不中断、页面隐藏时暂停与恢复、网络离线时 120s 轮询兜底且重连后停止、旧票晚到失败不破坏新会话、`stop` 清理定时器与连接
- SSE 生命周期与开关（`src/App.spec.js`）：登录后取票并建立连接、未登录不取票、登出后 `stop` 且角标清零、SSE 关闭时退回 120s 轮询、账号切换时断开旧连接并以新 token 重新建立

## 优化记录（第二轮）

| 主题 | 改动 |
| --- | --- |
| 性能 | 帖子与收藏列表改两步查询，消除 `HHH90003004` 集合分页全表加载；收藏列表补齐 tags 批量抓取 |
| 安全 | 数据库密码与 JWT 密钥改为环境变量；`application.yml` 不再含凭据 |
| 运维 | 拆分 `dev` / `prod` profile，生产默认关闭 SQL 日志 |
| 功能 | 通知支持单条标记已读，点击通知即消除未读 |
| 可维护性 | 时间格式化收敛为 `utils/format.js`；后台卡片抽为 `components/UserCard.vue`、`BoardCard.vue`；分页样式收敛为 `.jf-pager` |

详细的任务拆分与验收步骤见 `docs/superpowers/plans/2026-10-17-optimization-round2.md`。

## 样式与设计令牌

`frontend/src/styles/tokens.css` 是设计令牌的唯一来源，包含颜色、间距阶梯、圆角、字号阶梯，以及 `--el-color-primary` 对 Element Plus 主色的覆盖。页面应引用令牌（如 `var(--jf-space-4)`）而不是写死数值。令牌只保留有消费方的条目：无消费方的 `--jf-color-text-2`、`--jf-radius-lg`、`--jf-shadow-card`、`--jf-shadow-hover` 已移除，新增令牌时应连同首个消费方一起加。

第四轮起颜色令牌分成两套：`:root` 为亮色、`html.dark` 为暗色，两套的 `--jf-color-*` 名称必须完全一致。样式导入顺序固定为 Element Plus 基础样式 → 官方暗色变量 → `tokens.css` → `common.css` → `markdown.css` → `github.css` → `highlight-dark.css` → `style.css`（共 8 条，`tokens.spec.js` 逐条校验）。`styles/tokens.spec.js` 还守住：两套令牌集合相等、11 个颜色令牌与设计一致、每个颜色都是 6 位小写十六进制、每个颜色令牌在 `src` 下至少有一处 `var()` 消费、页面底与卡片底在两套中取值不同（否则切换主题看不出变化）。暗色语法高亮在 `styles/highlight-dark.css` 里以 `html.dark .hljs-*` 逐条叠加（手工同步 highlight.js 11.12.0 的 github-dark.css），代码块底色统一走 `--jf-color-code-bg`。`html.dark` 类由 `composables/useTheme.js` 与 `index.html` 的首屏内联脚本共同维护，存储键与类名以 `useTheme.js` 为唯一来源。

注意 768px 断点目前在多处重复出现：`frontend/src/composables/useResponsive.js` 的 `MOBILE_BREAKPOINT = 768`、`tokens.css` 的 `--jf-breakpoint-mobile`（仅作说明，`@media` 无法使用 `var()`），以及 `HomeView` / `PostDetailView` / `LoginView` / `RegisterView` / `NotificationsView` 五个视图与 `components/PostListItem`（公共帖子行）中的六处 `@media (max-width: 768px)`。改断点必须在同一次提交内同步以上所有位置，`breakpoint.spec.js` 会扫描 `src/**/*.{vue,css}` 的媒体查询，并在断点数量不为 6、字面量不是逐字的 `768px`、数值与 `MOBILE_BREAKPOINT` 不一致或出现 `min-width` 查询时报错。

## 已知限制

- **历史纯文本帖子中的 Markdown 语法会被解释**：Markdown 渲染上线后，正文里恰好长得像 Markdown 的写法（`# 标题`、`*斜体*`、`[文字](链接)`、`| 表格 |` 等）会按语法渲染，而不是原样显示。本轮不按帖子版本区分渲染：单换行会转成 `<br>` 以尽量保留纯文本帖子的换行观感，需要原样展示的符号可用反斜杠转义或放进代码块。
- **REPLY 通知的评论锚点只覆盖第一页评论**：帖子详情页每页加载 10 条根评论（后端按 `createTime` 升序只返回根评论，子回复随父评论平铺渲染）。若通知指向的根评论排在第 11 条之后，页面里不存在对应元素。此时不再静默失败：当该帖评论数大于 10 时会提示「目标评论可能在后面的评论分页里，可翻页查看」，需手动翻页定位。评论已删除也会命中同一条提示（该提示无法区分两种情况），这是本轮接受的误报。彻底修法是按 `commentId` 逐页回溯后再选中目标页——其代价（每次通知跳转最多多出若干次请求，且仍受页数上限约束）见 `docs/superpowers/specs/2026-10-16-experience-iteration-design.md` §5。
- **全文搜索按子串匹配**：中文没有分词，「关键词 + 相关度排序」的能力有限；1–2 个字的关键词用不上 pg_trgm 索引，会退化为顺序扫描（数据量小时无感）。
- **实时通知是单实例的**：SSE 连接注册表在应用内存里，多实例部署时事件只从产生通知的那个实例推给连在它上面的客户端（JavaForum 目前单容器部署，不构成问题）；重启/发版会让在线连接断开，客户端自动换票重连，断线期间发出的事件不补推，只校准未读数，刷新或重新进入通知页即可看到。
- **登录限流是单实例的**：失败计数存在应用内存（`LoginRateLimiter` 的 `ConcurrentHashMap`），多实例部署时每个实例各算各的，重启即清零。当前单容器部署不构成问题；要跨实例一致需换成 Redis 之类的共享窗口（见 `docs/DEVELOPMENT.md` §5.2）。
- **SSE 需要反向代理配合**：nginx 必须给 `location = /forum/api/notifications/stream` 单独关掉 `proxy_buffering`、把 `proxy_read_timeout` 放大到 3600s 并透传 `$is_args$args`；漏配时表现为「通知要等十几秒/一分钟才到」或「每分钟断一次」。`deploy/deploy.sh` 的冒烟会检查该端点返回 401，但「是否真的实时」仍需手工验证（部署手册有 curl 与双账号验收步骤）。

## 目录结构

```
# 部署相关
Dockerfile           # 运行时镜像：预构建 jar + eclipse-temurin:17-jre-jammy
.dockerignore        # 只放行预构建 jar，排除前端/文档/.env
docker-compose.yml   # 部署用：仅 backend（复用主机已有 PostgreSQL）
Jenkinsfile          # CI/CD：构建 jar、前端、镜像并部署
deploy/              # deploy.sh + nginx snippet + 部署手册
.env.example         # 环境变量模板（复制为 .env，后者不入库）
uploads/             # 本地开发的头像落盘目录（运行时生成、已 gitignore；生产在 /pd/Program/forum/uploads）

frontend/src/
├── api/           # Axios 实例与接口封装
├── components/    # 可复用组件（UserCard、BoardCard、PostListItem、UserAvatar、ThemeToggle、HeaderSearch）
├── composables/   # 组合式函数（useResponsive、usePostActions、useTheme）
├── router/        # 路由表与登录/管理员守卫
├── stores/        # Pinia store（auth、notifications）
├── styles/        # tokens.css 双套设计令牌、common.css 跨视图共用类、markdown.css、highlight-dark.css
├── utils/         # 纯函数（通知跳转映射、时间格式化、Markdown 渲染、头像地址解析、搜索高亮与片段）
└── views/         # 页面组件

src/main/java/org/song/forum/
├── config/        # Security、OpenAPI、JPA 审计、管理员种子等配置
├── controller/    # REST 控制器（无业务逻辑）
├── service/       # 业务逻辑与事务
├── repository/    # Spring Data JPA 数据访问
├── entity/        # JPA 实体
├── dto/           # 请求/响应 DTO
├── security/      # JWT 过滤器、UserDetails
├── exception/     # 全局异常处理
└── util/          # Result<T> 等工具
```
