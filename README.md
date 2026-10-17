# JavaForum

基于 Spring Boot 3 + Vue3 的论坛网站 MVP。

## 技术栈

- 后端：Java 17 / Maven / Spring Boot 3.5 / Spring Security 6（JWT 无状态认证）/ Spring Data JPA / PostgreSQL / Flyway / springdoc-openapi
- 前端：Vue3 + Vite + Pinia + Vue Router + Axios + Element Plus（`frontend/` 子目录）
- 测试：JUnit 5 + Spring Boot Test + H2（后端）；Vitest + @vue/test-utils + jsdom（前端）

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

后端 `spring.jpa.hibernate.ddl-auto` 设为 `validate`：Hibernate 只校验实体与表结构是否一致，不建表也不改表，因此任何实体变更都必须配套一条迁移脚本。

## 运行（后端）

```bash
# 方式一：修改 application.yml 中的数据库密码后直接运行
mvn spring-boot:run

# 方式二：通过环境变量覆盖
# Windows (PowerShell)
$env:DB_PASSWORD="你的密码"; mvn spring-boot:run
```

启动后：

- 健康检查：http://localhost:8080/api/health
- Swagger 文档：http://localhost:8080/swagger-ui.html

首次启动会由 `src/main/java/org/song/forum/config/DataInitializer.java` 幂等创建默认管理员账号（已存在则跳过）：

- 用户名 `admin`
- 密码 `admin123456`（生产环境请立即修改）

该账号用于访问后台的用户管理与板块管理页面。

## 运行（前端）

```bash
cd frontend
pnpm install
pnpm dev
```

开发服务器默认 http://localhost:5173，并把 `/api` 代理到 `http://localhost:8080`（见 `frontend/vite.config.js`），前端统一使用相对路径 `/api`，本地开发无需处理 CORS。

## 测试

### 后端测试

```bash
mvn test
```

测试使用 H2 内存库（PostgreSQL 兼容模式 `MODE=PostgreSQL`），Flyway 关闭、`ddl-auto=create-drop`，无需本地 PostgreSQL。当前为 7 个测试类、20 个用例。

### 前端测试

```bash
cd frontend
pnpm test        # Vitest 单元与组件测试
pnpm build       # 生产构建
```

当前为 8 个 spec、54 个用例，覆盖：

- 通知跳转映射（`src/utils/notificationLink.js`）：REPLY 带评论锚点、缺 `commentId` 时退化为只跳帖子、LIKE/FAVORITE 跳帖子、SYSTEM 与非法输入不可跳转
- 通知 store（`src/stores/notifications.js`）：未读数刷新、请求失败保留上次计数、全部已读、列表分页
- 点赞/收藏共享状态（`src/composables/usePostActions.js`）：失败回滚、同步服务端 `liked`/`favorited` 标志且不覆盖本地已操作状态
- 响应式判定与断点一致性（`src/composables/useResponsive.js`、`src/styles/breakpoint.spec.js`）：媒体查询断点数量必须恰为 5、字面量必须逐字写成 `768px`、数值须与 `MOBILE_BREAKPOINT` 一致，且禁止出现 `min-width` 查询
- 组件测试：`HomeView`（版块与列表加载、点赞交互与游客跳转、行点击跳详情）、`PostDetailView`（互动字段实时读共享状态：列表点的赞在详情页同步生效、置顶整体替换 `post` 后互动字段不被返回体带偏）与 `AdminView`（桌面表格 / 窄屏卡片、空状态、视口切换与监听清理）

## 样式与设计令牌

`frontend/src/styles/tokens.css` 是设计令牌的唯一来源，包含颜色、间距阶梯、圆角、字号阶梯，以及 `--el-color-primary` 对 Element Plus 主色的覆盖。页面应引用令牌（如 `var(--jf-space-4)`）而不是写死数值。令牌只保留有消费方的条目：无消费方的 `--jf-color-text-2`、`--jf-radius-lg`、`--jf-shadow-card`、`--jf-shadow-hover` 已移除，新增令牌时应连同首个消费方一起加。

注意 768px 断点目前在多处重复出现：`frontend/src/composables/useResponsive.js` 的 `MOBILE_BREAKPOINT = 768`、`tokens.css` 的 `--jf-breakpoint-mobile`（仅作说明，`@media` 无法使用 `var()`），以及 `HomeView` / `PostDetailView` / `LoginView` / `RegisterView` / `NotificationsView` 五个视图中的 `@media (max-width: 768px)`。改断点必须在同一次提交内同步以上所有位置，`breakpoint.spec.js` 会扫描 `src/**/*.{vue,css}` 的媒体查询，并在断点数量不为 5、字面量不是逐字的 `768px`、数值与 `MOBILE_BREAKPOINT` 不一致或出现 `min-width` 查询时报错。

## 已知限制

- **REPLY 通知的评论锚点只覆盖第一页评论**：帖子详情页每页加载 10 条根评论（后端按 `createTime` 升序只返回根评论，子回复随父评论平铺渲染）。若通知指向的根评论排在第 11 条之后，页面里不存在对应元素。此时不再静默失败：当该帖评论数大于 10 时会提示「目标评论可能在后面的评论分页里，可翻页查看」，需手动翻页定位。评论已删除也会命中同一条提示（该提示无法区分两种情况），这是本轮接受的误报。彻底修法是按 `commentId` 逐页回溯后再选中目标页——其代价（每次通知跳转最多多出若干次请求，且仍受页数上限约束）见 `docs/superpowers/specs/2026-10-16-experience-iteration-design.md` §5。

## 目录结构

```
frontend/src/
├── api/           # Axios 实例与接口封装
├── composables/   # 组合式函数（useResponsive、usePostActions）
├── router/        # 路由表与登录/管理员守卫
├── stores/        # Pinia store（auth、notifications）
├── styles/        # tokens.css 设计令牌
├── utils/         # 纯函数（通知跳转映射）
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
