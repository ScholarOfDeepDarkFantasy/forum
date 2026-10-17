# JavaForum 第二轮优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复第一轮迭代审计出的安全隐患与性能瓶颈（凭据明文入库、日志暴露查询、分页全表加载、单条通知无法标记已读），并把重复代码收敛到共用模块，使项目达到可部署状态。

**Architecture:** 后端按"仓储层分页策略 → 配置外置 → 运维暴露面"三条线改造，前端按"共用格式化工具 → 组件抽取"两条线收敛。所有改动保持既有分层（controller/service/repository/entity/dto/config/security/exception/util），不新增架构概念，不引入新依赖。

**Tech Stack:** Java 17 / Spring Boot 3.5.6 / Spring Data JPA / Hibernate 6.6 / PostgreSQL 15 / Flyway / Vue 3.5 / Vite 8 / Pinia 4 / Element Plus 2.14 / Vitest 5。

**基线：** 分支 `master`，最新提交 `53ac4b9`。后端 `mvn clean test` 20/20 通过，前端 `pnpm test` 67/67 通过（10 个 spec 文件），`pnpm build` 通过。

---

## 审计依据

本计划的任务全部来自对当前代码库的实测，不是凭空设想。关键证据：

| 发现 | 证据 |
|---|---|
| 数据库密码明文入库 | `src/main/resources/application.yml:9` 为 `password: 4*2j+#bVkNDP7:3EWcF9`，且 `git ls-files` 确认该文件已被跟踪 |
| 生产日志默认打印全部 SQL | `application.yml:59-60` `org.song.forum: debug` + `org.hibernate.SQL: debug`；实际启动日志单条查询超 38000 字节 |
| 帖子列表分页退化为全表加载 | 运行日志出现 `HHH90003004: firstResult/maxResults specified with collection fetch; applying in memory`；根因见 `src/main/java/org/song/forum/repository/PostRepository.java:18` 的 `@EntityGraph(attributePaths = {"board", "author", "tags"})` 与分页同用 |
| 收藏列表存在 N+1 | 同类问题：`FavoriteRepository.java:20` 的 `@EntityGraph` 未覆盖 `post.tags`，每行触发一次 `select ... from post_tags where post_id=?` |
| 通知无法单条标记已读 | 后端仅有 `GET /notifications`、`GET /unread-count`、`POST /read-all` 三个端点，无单条接口 |
| 时间格式化在 4 个视图重复 | `HomeView.vue`、`PostDetailView.vue`、`NotificationsView.vue`、`AdminView.vue` 各有一份 `fmt`/`fmtTime` |
| 分页样式在 4 个视图重复 | `.pager` 规则重复出现在 `HomeView.vue`、`PostDetailView.vue`、`NotificationsView.vue`、`AdminView.vue` |
| 后台视图模板重复 | `AdminView.vue` 296 行，用户与板块各有一套"表格 + 卡片"渲染，字段需 4 处同步修改 |

**明确不做（本轮边界）：** Markdown 渲染、标签检索、头像上传、深色模式、WebSocket 实时通知、全文搜索。这些是产品功能而非优化，各自需要独立设计。

---

## 文件结构

**后端改动**
- Modify: `src/main/java/org/song/forum/repository/PostRepository.java` — 拆分分页查询与集合抓取
- Modify: `src/main/java/org/song/forum/repository/FavoriteRepository.java` — 同上，覆盖 `post.tags`
- Modify: `src/main/java/org/song/forum/service/PostService.java` — 两步查询编排
- Modify: `src/main/java/org/song/forum/service/LikeService.java` — 收藏列表两步查询
- Modify: `src/main/java/org/song/forum/repository/NotificationRepository.java` — 单条已读更新
- Modify: `src/main/java/org/song/forum/service/NotificationService.java` — `markRead(Long id, Long recipientId)`
- Modify: `src/main/java/org/song/forum/controller/NotificationController.java` — `POST /api/notifications/{id}/read`
- Create: `src/main/resources/application-dev.yml` — 开发环境配置（含彩色 SQL 日志）
- Create: `src/main/resources/application-prod.yml` — 生产环境配置（关闭 SQL 日志、收紧 actuator）
- Modify: `src/main/resources/application.yml` — 只保留公共配置，凭据改为环境变量
- Modify: `src/main/java/org/song/forum/config/SecurityConfig.java` — actuator 端点鉴权
- Create: `src/test/java/org/song/forum/service/NotificationServiceTest.java` — 单条已读的归属校验

**前端改动**
- Create: `frontend/src/utils/format.js` — 时间格式化
- Create: `frontend/src/utils/format.spec.js`
- Create: `frontend/src/styles/common.css` — 共用 `.pager` 等样式类
- Modify: `frontend/src/main.js` — 引入 common.css
- Modify: `frontend/src/views/HomeView.vue`、`PostDetailView.vue`、`NotificationsView.vue`、`AdminView.vue` — 改用共用工具，删除重复定义
- Modify: `frontend/src/api/index.js` — 新增 `notificationApi.markRead(id)`
- Modify: `frontend/src/stores/notifications.js` — `markRead(id)` action
- Modify: `frontend/src/views/NotificationsView.vue` — 点击通知时标记已读
- Create: `frontend/src/components/UserCard.vue` — 后台用户卡片
- Create: `frontend/src/components/BoardCard.vue` — 后台板块卡片
- Modify: `frontend/src/views/AdminView.vue` — 使用两个卡片组件

**文档**
- Modify: `README.md` — 环境变量、profile、actuator 说明
- Create: `.env.example` — 环境变量样板
- Modify: `docs/superpowers/specs/2026-10-16-experience-iteration-design.md` — 记录第二轮调整

---

## Task 1: 帖子和收藏列表改两步查询，消除全表加载

**Files:**
- Modify: `src/main/java/org/song/forum/repository/PostRepository.java:14-38`
- Modify: `src/main/java/org/song/forum/repository/FavoriteRepository.java:14-28`
- Modify: `src/main/java/org/song/forum/service/PostService.java:120-145`
- Modify: `src/main/java/org/song/forum/service/LikeService.java:100-118`
- Test: `src/test/java/org/song/forum/repository/RepositoryTests.java`

**背景：** `PostRepository.search` 同时带 `@EntityGraph(attributePaths = {"board", "author", "tags"})` 和 `Pageable`。`tags` 是集合关联，Hibernate 无法在 SQL 层同时做 `first ? rows only` 和集合 join，于是把**全部匹配行**读进内存再截取。数据量一大就会变成性能事故。正确做法是两步：先用不带集合的查询分页拿到 id 列表，再用一条 `IN` 查询把 tags 批量抓回。

- [ ] **Step 1: 写失败的测试**

在 `src/test/java/org/song/forum/repository/RepositoryTests.java` 末尾（`uniqueConstraintsWork` 方法之后、类结束之前）添加：

```java
    /**
     * 分页 + 集合关联（tags）必须走两步查询：先分页取 id，再批量抓 tags。
     * Hibernate 若在集合抓取上做分页会打印 HHH90003004 并退化为全表加载，
     * 这里用"第二页也能拿到正确 tags"来固定该行为——全表加载实现下第二页元素会缺失。
     */
    @Test
    void pagedSearchFetchesTagsWithoutFullTableLoad() {
        User author = user("pager");
        Board board = boardRepository.save(Board.builder().name("分页板块").description("验证分页").build());
        Tag t1 = tagRepository.save(Tag.builder().name("page-tag-a").build());
        Tag t2 = tagRepository.save(Tag.builder().name("page-tag-b").build());

        for (int i = 0; i < 15; i++) {
            Post p = postRepository.save(Post.builder()
                    .board(board).author(author)
                    .title("分页帖 " + i).content("内容 " + i)
                    .build());
            p.getTags().add(i % 2 == 0 ? t1 : t2);
            postRepository.save(p);
        }
        em.flush();
        em.clear();

        Page<Post> firstPage = postRepository.searchIds(null, "", PageRequest.of(0, 10));
        Page<Post> secondPage = postRepository.searchIds(null, "", PageRequest.of(1, 10));

        assertThat(firstPage.getTotalElements()).isEqualTo(15);
        assertThat(firstPage.getContent()).hasSize(10);
        assertThat(secondPage.getContent()).hasSize(5);

        // 第二步：按 id 批量抓详情，tags 必须已初始化
        List<Long> secondPageIds = secondPage.getContent().stream().map(Post::getId).toList();
        List<Post> hydrated = postRepository.findWithAssociations(new HashSet<>(secondPageIds));
        assertThat(hydrated).hasSize(5);
        assertThat(hydrated).allSatisfy(p -> {
            assertThat(p.getTags()).isNotEmpty();
            assertThat(p.getBoard().getName()).isEqualTo("分页板块");
            assertThat(p.getAuthor().getUsername()).isEqualTo("pager");
        });
    }

    /**
     * 收藏列表同样不得在集合抓取上分页（FavoriteRepository 的 @EntityGraph 未覆盖 post.tags）。
     */
    @Test
    void pagedFavoritesHydrateTags() {
        User fan = user("fan");
        Board board = boardRepository.save(Board.builder().name("收藏板块").description("验证收藏分页").build());
        Tag tag = tagRepository.save(Tag.builder().name("fav-tag").build());
        Post post = postRepository.save(Post.builder()
                .board(board).author(fan).title("被收藏").content("内容").build());
        post.getTags().add(tag);
        postRepository.save(post);
        favoriteRepository.save(Favorite.builder().user(fan).post(post).build());
        em.flush();
        em.clear();

        Page<Favorite> page = favoriteRepository
                .findByUserIdOrderByCreateTimeDesc(fan.getId(), PageRequest.of(0, 10));
        assertThat(page.getContent()).hasSize(1);

        Set<Long> postIds = page.getContent().stream()
                .map(f -> f.getPost().getId()).collect(Collectors.toSet());
        List<Post> hydrated = postRepository.findWithAssociations(postIds);
        assertThat(hydrated).hasSize(1);
        assertThat(hydrated.get(0).getTags()).extracting(Tag::getName).containsExactly("fav-tag");
    }
```

在文件顶部 import 区补充：

```java
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -B test -Dtest=RepositoryTests`
Expected: 编译失败，提示 `cannot find symbol: method searchIds(...)` 与 `findWithAssociations(...)`

- [ ] **Step 3: 拆分 PostRepository 的查询**

修改 `src/main/java/org/song/forum/repository/PostRepository.java`。把现有的 `search` 方法（带 `@EntityGraph` 的那个）保留不变，供不需要分页的场景使用；新增两个方法：

```java
    /**
     * 分页取帖子的"骨架"（不 join tags 集合）。集合关联若与分页同用，
     * Hibernate 会打印 HHH90003004 并把全部匹配行读进内存再截取，
     * 因此分页必须只查主表与 to-one 关联。
     */
    @Query("""
            SELECT p FROM Post p
            WHERE p.deleted = false
              AND (:boardId IS NULL OR p.board.id = :boardId)
              AND (:keyword = '' OR LOWER(p.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                                OR LOWER(p.content) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """)
    @EntityGraph(attributePaths = {"board", "author"})
    Page<Post> searchIds(@Param("boardId") Long boardId,
                         @Param("keyword") String keyword,
                         Pageable pageable);

    /**
     * 第二步：按 id 批量抓取帖子及全部所需关联（含 tags 集合）。
     * 调用方负责用第一步的结果排序——IN 查询不保证顺序。
     */
    @Query("SELECT p FROM Post p WHERE p.id IN :ids")
    @EntityGraph(attributePaths = {"board", "author", "tags"})
    List<Post> findWithAssociations(@Param("ids") Collection<Long> ids);
```

在文件顶部 import 区补充：

```java
import java.util.Collection;
import java.util.List;
```

- [ ] **Step 4: 在 PostService 中编排两步查询**

修改 `src/main/java/org/song/forum/service/PostService.java` 的 `list` 方法。当前实现形如：

```java
    @Transactional(readOnly = true)
    public PageResponse<PostSummaryResponse> list(Long boardId, String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, pinnedFirstSort());
        Page<Post> posts = postRepository.search(boardId, blankToEmpty(keyword), pageable);
        return PageResponse.of(mapSummaries(posts));
    }
```

改为：

```java
    @Transactional(readOnly = true)
    public PageResponse<PostSummaryResponse> list(Long boardId, String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, pinnedFirstSort());
        // 第一步：只分页取骨架，避免在 tags 集合抓取上分页导致全表加载
        Page<Post> ids = postRepository.searchIds(boardId, blankToEmpty(keyword), pageable);
        if (ids.isEmpty()) {
            return PageResponse.of(ids.map(p -> toSummary(p, false, false)));
        }
        // 第二步：按 id 批量抓关联，再按第一步的顺序还原
        Map<Long, Post> byId = postRepository
                .findWithAssociations(ids.getContent().stream().map(Post::getId).toList())
                .stream()
                .collect(Collectors.toMap(Post::getId, p -> p));
        Page<Post> ordered = ids.map(byId::get);
        return PageResponse.of(mapSummaries(ordered));
    }
```

在 `PostService.java` 顶部 import 区补充（若尚未存在）：

```java
import java.util.Map;
import java.util.stream.Collectors;
```

- [ ] **Step 5: 在 LikeService 中同样编排**

修改 `src/main/java/org/song/forum/service/LikeService.java` 的 `myFavorites`。当前实现形如：

```java
    @Transactional(readOnly = true)
    public PageResponse<PostSummaryResponse> myFavorites(Long userId, int page, int size) {
        Page<Favorite> favorites = favoriteRepository
                .findByUserIdOrderByCreateTimeDesc(userId, PageRequest.of(page, size));
        Set<Long> postIds = favorites.getContent().stream()
                .map(f -> f.getPost().getId()).collect(Collectors.toSet());
        Set<Long> likedIds = postIds.isEmpty()
                ? Set.of()
                : new HashSet<>(likeRepository.findLikedPostIds(userId, postIds));
        return PageResponse.of(favorites.map(f -> toSummary(f.getPost(), likedIds.contains(f.getPost().getId()))));
    }
```

改为：

```java
    @Transactional(readOnly = true)
    public PageResponse<PostSummaryResponse> myFavorites(Long userId, int page, int size) {
        Page<Favorite> favorites = favoriteRepository
                .findByUserIdOrderByCreateTimeDesc(userId, PageRequest.of(page, size));
        Set<Long> postIds = favorites.getContent().stream()
                .map(f -> f.getPost().getId()).collect(Collectors.toSet());
        if (postIds.isEmpty()) {
            return PageResponse.of(favorites.map(f -> toSummary(f.getPost(), false)));
        }
        // tags 不在 FavoriteRepository 的 @EntityGraph 里，按 id 批量补齐，
        // 否则每行触发一次 select ... from post_tags where post_id=?
        Map<Long, Post> byId = postRepository
                .findWithAssociations(postIds)
                .stream()
                .collect(Collectors.toMap(Post::getId, p -> p));
        Set<Long> likedIds = new HashSet<>(likeRepository.findLikedPostIds(userId, postIds));
        return PageResponse.of(favorites.map(f -> {
            Post hydrated = byId.getOrDefault(f.getPost().getId(), f.getPost());
            return toSummary(hydrated, likedIds.contains(hydrated.getId()));
        }));
    }
```

确认 `LikeService` 已注入 `PostRepository`；若没有则补字段：

```java
    private final PostRepository postRepository;
```

并在 import 区补：

```java
import org.song.forum.entity.Post;
import org.song.forum.repository.PostRepository;

import java.util.Map;
```

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn -B test -Dtest=RepositoryTests`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 7: 确认 HHH90003004 警告消失**

Run: `mvn -B test -Dtest=PostFlowIntegrationTest 2>&1 | Select-String 'HHH90003004'`
Expected: 无输出（该警告是本次要消除的症状）

- [ ] **Step 8: 后端全量回归**

Run: `mvn -B clean test`
Expected: `Tests run: 22, Failures: 0, Errors: 0, Skipped: 0` 与 `BUILD SUCCESS`

- [ ] **Step 9: 提交**

```bash
git add src/main/java/org/song/forum/repository/PostRepository.java \
        src/main/java/org/song/forum/repository/FavoriteRepository.java \
        src/main/java/org/song/forum/service/PostService.java \
        src/main/java/org/song/forum/service/LikeService.java \
        src/test/java/org/song/forum/repository/RepositoryTests.java
git commit -m "perf: 帖子与收藏列表改两步查询消除集合分页全表加载"
```

---

## Task 2: 凭据外置与日志分级

**Files:**
- Modify: `src/main/resources/application.yml`
- Create: `src/main/resources/application-dev.yml`
- Create: `src/main/resources/application-prod.yml`
- Create: `.env.example`
- Modify: `.gitignore`
- Modify: `README.md`

**背景：** `application.yml:9` 的数据库密码是明文，且该文件已被 git 跟踪——任何拿到仓库的人都能连上数据库。同时 `application.yml:59-60` 在生产也打印全部 SQL，既泄露数据又拖慢性能。解决方式是：配置文件只引用环境变量，dev/prod 差异用 Spring profile 分开。

- [ ] **Step 1: 改造 application.yml，只保留公共配置**

把 `src/main/resources/application.yml` 全文替换为：

```yaml
spring:
  application:
    name: JavaForum

  # ---- 数据源：PostgreSQL ----
  # 凭据一律从环境变量读取，不写入仓库。本地开发可用 .env.example 复制为 .env 后 source，
  # 或在 IDE 里配置运行配置的环境变量。
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/javaforum}
    username: ${DB_USERNAME:forum}
    password: ${DB_PASSWORD}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2

  # ---- JPA / Hibernate ----
  jpa:
    hibernate:
      ddl-auto: validate      # 建表交给 Flyway，Hibernate 只做校验
    open-in-view: false
    properties:
      hibernate:
        format_sql: true
    show-sql: false

  # ---- Flyway ----
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: false

server:
  port: ${SERVER_PORT:8080}
  servlet:
    encoding:
      charset: UTF-8
      force: true

# ---- JWT 配置 ----
jwt:
  # 生产环境务必通过环境变量 JWT_SECRET 覆盖；至少 32 字节（HS384 需要更长，见实现）
  secret: ${JWT_SECRET}
  # Token 有效期（毫秒）：24 小时
  expiration-ms: ${JWT_EXPIRATION_MS:86400000}
  # 响应头名称
  header: Authorization
  # Token 前缀
  prefix: "Bearer "
```

说明：`DB_PASSWORD` 与 `JWT_SECRET` 故意不给默认值。缺少时应用启动即失败，这是想要的——宁可启动报错，也不要带着弱默认值跑起来。

- [ ] **Step 2: 新增开发环境配置**

创建 `src/main/resources/application-dev.yml`：

```yaml
# 开发环境：本地调试用。启动方式：mvn spring-boot:run -Dspring-boot.run.profiles=dev
spring:
  devtools:
    restart:
      enabled: true
    livereload:
      enabled: true

logging:
  level:
    org.song.forum: debug
    org.hibernate.SQL: debug
    org.hibernate.orm.jdbc.bind: trace
    org.springframework.security: info
```

- [ ] **Step 3: 新增生产环境配置**

创建 `src/main/resources/application-prod.yml`：

```yaml
# 生产环境：日志只记必要的，关闭 SQL 打印与 devtools。
# 启动方式：java -jar forum.jar --spring.profiles.active=prod
spring:
  devtools:
    restart:
      enabled: false
    livereload:
      enabled: false

  datasource:
    hikari:
      maximum-pool-size: 20

management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: never

logging:
  level:
    root: info
    org.song.forum: info
    org.hibernate.SQL: warn
    org.springframework.security: warn
  file:
    name: ${LOG_FILE:logs/javaforum.log}
```

- [ ] **Step 4: 新增环境变量样板**

创建 `.env.example`：

```bash
# 复制为 .env 并填入真实值。.env 已被 .gitignore 忽略，不会进入版本库。
# PostgreSQL 连接
DB_URL=jdbc:postgresql://localhost:5432/javaforum
DB_USERNAME=forum
DB_PASSWORD=replace-with-a-strong-password

# JWT 签名密钥：至少 48 字符的随机串（JwtTokenProvider 使用 HS384）
# 生成方式：openssl rand -base64 48
JWT_SECRET=replace-with-a-random-48-plus-characters-string

# 可选
SERVER_PORT=8080
JWT_EXPIRATION_MS=86400000
```

- [ ] **Step 5: 确认 .gitignore 已覆盖 .env**

`.gitignore` 现有内容已包含 `.env` 与 `.env.local`，无需改动。运行验证：

Run: `git check-ignore -v .env`
Expected: 输出包含 `.gitignore:31:.env` 字样（行号可能不同），表明 `.env` 被忽略

- [ ] **Step 6: 更新 README 的配置说明**

在 `README.md` 的「运行（后端）」章节开头插入：

```markdown
### 环境变量

凭据不再写入 `application.yml`，必须通过环境变量提供。参考 `.env.example`：

```bash
# PowerShell
$env:DB_URL = "jdbc:postgresql://localhost:5432/javaforum"
$env:DB_USERNAME = "forum"
$env:DB_PASSWORD = "你的密码"
$env:JWT_SECRET = "至少48字符的随机串"
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

`DB_PASSWORD` 与 `JWT_SECRET` 没有默认值，缺失时应用会直接启动失败——这是有意为之。

### 运行 profile

| profile | 用途 | SQL 日志 | devtools |
| --- | --- | --- | --- |
| `dev` | 本地开发 | 打印（含绑定参数） | 开启 |
| `prod` | 生产部署 | 关闭 | 关闭 |
```

- [ ] **Step 7: 验证 dev profile 能启动**

先在当前终端设置环境变量（沿用你现有的数据库密码），再运行：

```powershell
$env:DB_PASSWORD = "你的数据库密码"
$env:JWT_SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef"
mvn -B spring-boot:run -Dspring-boot.run.profiles=dev
```

Expected: 日志出现 `The following 1 profile is active: "dev"` 与 `Started ForumApplication`

- [ ] **Step 8: 验证缺少必填环境变量时启动失败**

```powershell
Remove-Item Env:\DB_PASSWORD
mvn -B spring-boot:run -Dspring-boot.run.profiles=dev
```

Expected: 启动失败，错误信息包含 `Could not resolve placeholder 'DB_PASSWORD'`

- [ ] **Step 9: 提交**

```bash
git add src/main/resources/application.yml \
        src/main/resources/application-dev.yml \
        src/main/resources/application-prod.yml \
        .env.example README.md
git commit -m "chore: 凭据改为环境变量并从配置分离 dev/prod profile"
```

---

## Task 3: 通知单条已读

**Files:**
- Modify: `src/main/java/org/song/forum/repository/NotificationRepository.java:22-24`
- Modify: `src/main/java/org/song/forum/service/NotificationService.java`
- Modify: `src/main/java/org/song/forum/controller/NotificationController.java`
- Create: `src/test/java/org/song/forum/service/NotificationServiceTest.java`
- Test: `src/test/java/org/song/forum/controller/CommentFlowIntegrationTest.java`

**背景：** 现在用户点击一条通知跳转到帖子后，那条通知仍是未读状态，角标也不减。要么整页"全部已读"（把没看过的也清了），要么就只能忍着。需要补一个单条已读接口，并且必须校验该通知属于当前用户——否则任何人都能把别人的通知标记为已读。

- [ ] **Step 1: 写失败的集成测试**

在 `src/test/java/org/song/forum/controller/CommentFlowIntegrationTest.java` 末尾（类结束之前）添加一个方法。先读该文件确认 helper 名称（`url(...)`、`bearer(...)`）与 token 变量名，然后用实际名字替换下面示例里的占位：

```java
    @Test
    @Order(5)
    void stage5_markSingleNotificationRead() {
        // 复用 stage3 中 ap_user 收到的 REPLY 通知
        ResponseEntity<Result<PageResponse<Map<String, Object>>>> list =
                rest.exchange(url("/api/notifications?page=0&size=10"), HttpMethod.GET,
                        new HttpEntity<>(bearer(apToken)),
                        new ParameterizedTypeReference<>() {});
        Map<String, Object> target = list.getBody().getData().content().get(0);
        long notifId = ((Number) target.get("id")).longValue();
        assertThat(target.get("isRead")).isEqualTo(false);

        // 单条已读
        ResponseEntity<Result<Void>> markResp = rest.exchange(
                url("/api/notifications/" + notifId + "/read"), HttpMethod.POST,
                new HttpEntity<>(bearer(apToken)), new ParameterizedTypeReference<Result<Void>>() {});
        assertThat(markResp.getStatusCode().is2xxSuccessful()).isTrue();

        // 未读数减少，列表里该条变为已读
        ResponseEntity<Result<Map<String, Object>>> count =
                rest.exchange(url("/api/notifications/unread-count"), HttpMethod.GET,
                        new HttpEntity<>(bearer(apToken)),
                        new ParameterizedTypeReference<Result<Map<String, Object>>>() {});
        assertThat(((Number) count.getBody().getData().get("count")).intValue()).isZero();

        ResponseEntity<Result<PageResponse<Map<String, Object>>>> after =
                rest.exchange(url("/api/notifications?page=0&size=10"), HttpMethod.GET,
                        new HttpEntity<>(bearer(apToken)),
                        new ParameterizedTypeReference<>() {});
        assertThat(after.getBody().getData().content().get(0).get("isRead")).isEqualTo(true);
    }

    @Test
    @Order(6)
    void stage6_cannotMarkOthersNotificationRead() {
        // 用另一个用户去标记 ap_user 的通知：必须 404/400，且不影响原状态
        ResponseEntity<Result<PageResponse<Map<String, Object>>>> mine =
                rest.exchange(url("/api/notifications?page=0&size=10"), HttpMethod.GET,
                        new HttpEntity<>(bearer(apToken)),
                        new ParameterizedTypeReference<>() {});
        long someoneElses = ((Number) mine.getBody().getData().content().get(0).get("id")).longValue();

        ResponseEntity<Result> stolen = rest.exchange(
                url("/api/notifications/" + someoneElses + "/read"), HttpMethod.POST,
                new HttpEntity<>(bearer(opToken)), new ParameterizedTypeReference<Result>() {});
        assertThat(stolen.getStatusCode().value()).isEqualTo(404);
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -B test -Dtest=CommentFlowIntegrationTest`
Expected: 6 个测试中后两个失败，断言为 `expected: 200 but was: 404`（接口不存在）

- [ ] **Step 3: 仓储层加单条已读更新**

在 `src/main/java/org/song/forum/repository/NotificationRepository.java` 的 `markAllRead` 之后添加：

```java
    /** 单条已读：带 recipientId 条件，防止越权改他人通知 */
    @Modifying
    @Query("""
            UPDATE Notification n SET n.isRead = true
            WHERE n.id = :id AND n.recipient.id = :recipientId AND n.isRead = false
            """)
    int markRead(@Param("id") Long id, @Param("recipientId") Long recipientId);
```

- [ ] **Step 4: Service 加方法与归属校验**

在 `src/main/java/org/song/forum/service/NotificationService.java` 的 `markAllRead` 之后添加：

```java
    /**
     * 把单条通知标记为已读。
     * 先按 id + recipientId 查一次做归属判断：更新语句影响 0 行时无法区分
     * "不存在"、"不属于你"和"本来就已读"，直接返回会让越权请求看起来像成功。
     */
    @Transactional
    public void markRead(Long id, Long recipientId) {
        Notification n = notificationRepository.findById(id)
                .orElseThrow(() -> new BusinessException("通知不存在"));
        if (!n.getRecipient().getId().equals(recipientId)) {
            throw new NoSuchElementException("通知不存在");
        }
        if (Boolean.TRUE.equals(n.getIsRead())) {
            return;
        }
        notificationRepository.markRead(id, recipientId);
    }
```

确认 import 区已有 `java.util.NoSuchElementException` 与 `org.song.forum.exception.BusinessException`；没有则补上。注意：`GlobalExceptionHandler` 已把 `NoSuchElementException` 映射为 404，所以越权与不存在对外表现一致，不泄露"该 id 存在但不是你的"。

- [ ] **Step 5: Controller 加端点**

在 `src/main/java/org/song/forum/controller/NotificationController.java` 的 `readAll` 方法之后添加：

```java
    @PostMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        notificationService.markRead(id, SecurityUtils.currentUserId());
        return Result.ok(null, "已标记为已读");
    }
```

确认 import 区有 `org.springframework.web.bind.annotation.PathVariable`、`org.song.forum.security.SecurityUtils`。

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn -B test -Dtest=CommentFlowIntegrationTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 7: 后端全量回归**

Run: `mvn -B clean test`
Expected: `Tests run: 24, Failures: 0, Errors: 0, Skipped: 0` 与 `BUILD SUCCESS`

- [ ] **Step 8: 提交**

```bash
git add src/main/java/org/song/forum/repository/NotificationRepository.java \
        src/main/java/org/song/forum/service/NotificationService.java \
        src/main/java/org/song/forum/controller/NotificationController.java \
        src/test/java/org/song/forum/controller/CommentFlowIntegrationTest.java
git commit -m "feat: 通知支持单条标记已读并校验归属"
```

---

## Task 4: 前端接入单条已读

**Files:**
- Modify: `frontend/src/api/index.js`
- Modify: `frontend/src/stores/notifications.js`
- Modify: `frontend/src/stores/notifications.spec.js`
- Modify: `frontend/src/views/NotificationsView.vue`

- [ ] **Step 1: 写失败的 store 测试**

在 `frontend/src/stores/notifications.spec.js` 的 `describe` 块内追加：

```js
  it('markRead 只减掉一条未读并就地更新该行', async () => {
    notificationApi.unreadCount.mockResolvedValue({ count: 3 })
    notificationApi.markRead.mockResolvedValue(null)
    notificationApi.mine.mockResolvedValue({
      content: [
        { id: 1, type: 'LIKE', isRead: false },
        { id: 2, type: 'REPLY', isRead: false }
      ],
      totalElements: 2,
      totalPages: 1
    })
    const store = useNotificationStore()
    await store.refreshUnread()
    await store.loadList(0)

    await store.markRead(2)

    expect(notificationApi.markRead).toHaveBeenCalledWith(2)
    expect(store.unreadCount).toBe(2)
    // 就地更新，不重新拉列表——用户刚点的那条要立即变灰
    expect(store.list.content.find((n) => n.id === 2).isRead).toBe(true)
    expect(store.list.content.find((n) => n.id === 1).isRead).toBe(false)
  })

  it('markRead 时该条已读则不重复扣减', async () => {
    notificationApi.unreadCount.mockResolvedValue({ count: 1 })
    notificationApi.markRead.mockResolvedValue(null)
    notificationApi.mine.mockResolvedValue({
      content: [{ id: 9, type: 'REPLY', isRead: true }],
      totalElements: 1,
      totalPages: 1
    })
    const store = useNotificationStore()
    await store.refreshUnread()
    await store.loadList(0)

    await store.markRead(9)

    expect(store.unreadCount).toBe(1)
  })
```

同时在文件顶部的 `vi.mock('@/api', ...)` 工厂里，给 `notificationApi` 补上 `markRead: vi.fn()`。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd frontend && pnpm test -- src/stores/notifications.spec.js`
Expected: FAIL，提示 `store.markRead is not a function`

- [ ] **Step 3: API 层加方法**

在 `frontend/src/api/index.js` 的 `notificationApi` 对象里，`markAllRead` 之后添加：

```js
  markRead: (id) => api.post(`/notifications/${id}/read`),
```

- [ ] **Step 4: Store 加 action**

在 `frontend/src/stores/notifications.js` 的 `actions` 里，`markAllRead` 之后添加：

```js
    /**
     * 标记单条已读。就地更新列表项而不是重新拉取——用户点进去的那一刻
     * 那条通知就应该变灰，多一次请求只会让界面闪一下。
     */
    async markRead(id) {
      const item = this.list.content.find((n) => n.id === id)
      if (item && item.isRead) return
      await notificationApi.markRead(id)
      if (item) item.isRead = true
      this.unreadCount = Math.max(0, this.unreadCount - 1)
    },
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd frontend && pnpm test -- src/stores/notifications.spec.js`
Expected: `Tests 7 passed`

- [ ] **Step 6: 通知页点击时调用**

修改 `frontend/src/views/NotificationsView.vue` 的 `openNotification`：

```js
async function openNotification(n) {
  const target = targetOf(n)
  if (!target) return
  // 先标记已读再跳转：不等它完成，失败也不该挡住导航
  if (!n.isRead) {
    notifications.markRead(n.id).catch(() => {})
  }
  router.push(target)
}
```

- [ ] **Step 7: 前端全量测试与构建**

Run: `cd frontend && pnpm test && pnpm build`
Expected: `Test Files 10 passed`，`Tests 69 passed`；构建 `✓ built in`

- [ ] **Step 8: 提交**

```bash
git add frontend/src/api/index.js frontend/src/stores/notifications.js \
        frontend/src/stores/notifications.spec.js frontend/src/views/NotificationsView.vue
git commit -m "feat: 点击通知时标记单条已读"
```

---

## Task 5: 抽取共用时间格式化工具

**Files:**
- Create: `frontend/src/utils/format.js`
- Create: `frontend/src/utils/format.spec.js`
- Modify: `frontend/src/views/HomeView.vue`
- Modify: `frontend/src/views/PostDetailView.vue`
- Modify: `frontend/src/views/NotificationsView.vue`
- Modify: `frontend/src/views/AdminView.vue`
- Modify: `frontend/src/views/ProfileView.vue`（计划漏列，实际也存在一份 `fmt`）

**状态：已完成。** 实际共 5 个视图各删掉一份本地 `fmt`/`fmtTime`：HomeView 用 `formatRelative`（列表里"28 分钟前"比绝对时间更有用），其余四处用 `formatDateTime`。用例数最终为 `Test Files 12 passed / Tests 84 passed`（计划写 77 时 Task 4 的用例尚未计入）。

**背景：** 同一个"后端 ISO 时间 → 显示文本"的转换在 4 个视图各写了一遍，实现略有差异（有的截到分钟，有的原样返回）。抽成工具函数后行为一致，也好统一补上"刚刚"/"3 分钟前"这类相对时间。

- [x] **Step 1: 写失败的测试**

创建 `frontend/src/utils/format.spec.js`：

```js
import { describe, it, expect } from 'vitest'
import { formatDateTime, formatRelative } from './format'

describe('formatDateTime', () => {
  it('把后端 ISO 字符串格式化为到分钟的本地文本', () => {
    expect(formatDateTime('2026-10-17T10:23:34.827384')).toBe('2026-10-17 10:23')
  })

  it('空值返回空串', () => {
    expect(formatDateTime(null)).toBe('')
    expect(formatDateTime(undefined)).toBe('')
    expect(formatDateTime('')).toBe('')
  })
})

describe('formatRelative', () => {
  const now = new Date('2026-10-17T12:00:00').getTime()

  it('一分钟内显示刚刚', () => {
    expect(formatRelative('2026-10-17T11:59:30', now)).toBe('刚刚')
  })

  it('一小时内显示分钟数', () => {
    expect(formatRelative('2026-10-17T11:30:00', now)).toBe('30 分钟前')
  })

  it('一天内显示小时数', () => {
    expect(formatRelative('2026-10-17T09:00:00', now)).toBe('3 小时前')
  })

  it('超过一天回退到具体日期', () => {
    expect(formatRelative('2026-10-14T09:00:00', now)).toBe('2026-10-14 09:00')
  })

  it('空值返回空串', () => {
    expect(formatRelative(null, now)).toBe('')
  })
})
```

- [x] **Step 2: 运行测试确认失败**

Run: `cd frontend && pnpm test -- src/utils/format.spec.js`
Expected: FAIL，`Failed to resolve import "./format"`

- [x] **Step 3: 写实现**

创建 `frontend/src/utils/format.js`：

```js
/**
 * 后端时间字段是 LocalDateTime 序列化后的 "2026-10-17T10:23:34.827384"（无时区）。
 * 直接 new Date() 解析会按浏览器本地时区补偏移，这里统一按字符串截取，避免时区漂移。
 */
export function formatDateTime(value) {
  if (!value) return ''
  return String(value).replace('T', ' ').slice(0, 16)
}

const MINUTE = 60 * 1000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

/**
 * 相对时间：1 分钟内「刚刚」，1 小时内「N 分钟前」，1 天内「N 小时前」，再久回退到具体日期。
 * now 参数只为测试可注入，生产调用省略。
 */
export function formatRelative(value, now = Date.now()) {
  if (!value) return ''
  const then = new Date(value).getTime()
  if (Number.isNaN(then)) return formatDateTime(value)
  const diff = now - then
  if (diff < MINUTE) return '刚刚'
  if (diff < HOUR) return `${Math.floor(diff / MINUTE)} 分钟前`
  if (diff < DAY) return `${Math.floor(diff / HOUR)} 小时前`
  return formatDateTime(value)
}
```

- [x] **Step 4: 运行测试确认通过**

Run: `cd frontend && pnpm test -- src/utils/format.spec.js`
Expected: `Tests 8 passed`

- [x] **Step 5: 替换 HomeView 的本地实现**

在 `frontend/src/views/HomeView.vue` 中，删除本地的 `fmtTime` 函数，并在 import 区加入：

```js
import { formatRelative } from '@/utils/format'
```

模板里所有 `fmtTime(` 改为 `formatRelative(`。

- [x] **Step 6: 替换 PostDetailView 的本地实现**

在 `frontend/src/views/PostDetailView.vue` 中，删除本地的 `fmt` 函数，import 区加入：

```js
import { formatDateTime } from '@/utils/format'
```

模板里所有 `fmt(` 改为 `formatDateTime(`。

- [x] **Step 7: 替换 NotificationsView 的本地实现**

在 `frontend/src/views/NotificationsView.vue` 中，删除本地的 `fmt` 函数，import 区加入：

```js
import { formatRelative } from '@/utils/format'
```

模板里所有 `fmt(` 改为 `formatRelative(`。

- [x] **Step 8: 替换 AdminView 的本地实现**

在 `frontend/src/views/AdminView.vue` 中，删除本地的 `fmt` 函数，import 区加入：

```js
import { formatDateTime } from '@/utils/format'
```

模板里所有 `fmt(` 改为 `formatDateTime(`。

- [x] **Step 9: 确认没有残留的本地定义**

Run: `Select-String -Path frontend/src/views/*.vue -Pattern 'function fmt|function fmtTime'`
Expected: 无输出

- [x] **Step 10: 全量测试与构建**

Run: `cd frontend && pnpm test && pnpm build`
Expected: `Test Files 11 passed`，`Tests 77 passed`；构建成功

- [x] **Step 11: 提交**

```bash
git add frontend/src/utils/format.js frontend/src/utils/format.spec.js \
        frontend/src/views/HomeView.vue frontend/src/views/PostDetailView.vue \
        frontend/src/views/NotificationsView.vue frontend/src/views/AdminView.vue
git commit -m "refactor: 抽取共用时间格式化工具替换四处重复实现"
```

---

## Task 6: 抽取后台用户与板块卡片组件

**Files:**
- Create: `frontend/src/components/UserCard.vue`
- Create: `frontend/src/components/BoardCard.vue`
- Create: `frontend/src/components/cards.spec.js`
- Modify: `frontend/src/views/AdminView.vue`
- Modify: `frontend/src/views/AdminView.spec.js`

**状态：已完成。** 实际 AdminView.vue 297 → 235 行，新增 UserCard.vue（85 行）与 BoardCard.vue（54 行）；cards.spec.js 10 个用例，AdminView.spec.js 由 8 增至 11 个（新增 3 个跨组件事件接线用例，已用变异测试验证有效）。

**背景：** `AdminView.vue` 已到 296 行，用户与板块各有一套"桌面表格 + 窄屏卡片"。加一个字段要在 4 处同步改，漏一处就是数据不一致。把卡片抽成两个只负责展示的小组件，AdminView 只保留编排。

- [x] **Step 1: 写失败的组件测试**

创建 `frontend/src/components/cards.spec.js`：

```js
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import UserCard from './UserCard.vue'
import BoardCard from './BoardCard.vue'

const stubs = {
  'el-tag': { template: '<span class="tag"><slot /></span>' },
  'el-button': {
    props: ['disabled'],
    emits: ['click'],
    template: '<button class="btn" :disabled="disabled" @click="$emit(\'click\')"><slot /></button>'
  }
}

describe('UserCard', () => {
  const user = {
    id: 7,
    username: 'alice',
    nickname: '爱丽丝',
    email: 'alice@test.com',
    status: 'ACTIVE',
    mutedUntil: null,
    roles: ['USER']
  }

  it('渲染用户的关键字段', () => {
    const wrapper = mount(UserCard, {
      props: { user, statusTag: () => 'success' },
      global: { stubs }
    })
    const text = wrapper.text()
    expect(text).toContain('alice')
    expect(text).toContain('爱丽丝')
    expect(text).toContain('alice@test.com')
    expect(text).toContain('ACTIVE')
  })

  it('管理员的状态管理按钮被禁用', () => {
    const wrapper = mount(UserCard, {
      props: { user: { ...user, roles: ['ADMIN'] }, statusTag: () => 'danger' },
      global: { stubs }
    })
    expect(wrapper.find('.btn').attributes('disabled')).toBeDefined()
  })

  it('普通用户的状态管理按钮可用且点击发出事件', async () => {
    const wrapper = mount(UserCard, {
      props: { user, statusTag: () => 'success' },
      global: { stubs }
    })
    const btn = wrapper.find('.btn')
    expect(btn.attributes('disabled')).toBeUndefined()
    await btn.trigger('click')
    expect(wrapper.emitted('status')).toHaveLength(1)
    expect(wrapper.emitted('status')[0][0]).toEqual(user)
  })
})

describe('BoardCard', () => {
  const board = { id: 3, name: '后端', description: 'Java 讨论', postCount: 12 }

  it('渲染板块字段', () => {
    const wrapper = mount(BoardCard, { props: { board }, global: { stubs } })
    expect(wrapper.text()).toContain('后端')
    expect(wrapper.text()).toContain('Java 讨论')
    expect(wrapper.text()).toContain('12')
  })

  it('描述为空时显示占位符', () => {
    const wrapper = mount(BoardCard, {
      props: { board: { ...board, description: null } },
      global: { stubs }
    })
    expect(wrapper.text()).toContain('-')
  })

  it('编辑与删除按钮分别发出事件', async () => {
    const wrapper = mount(BoardCard, { props: { board }, global: { stubs } })
    const btns = wrapper.findAll('.btn')
    await btns[0].trigger('click')
    await btns[1].trigger('click')
    expect(wrapper.emitted('edit')[0][0]).toEqual(board)
    expect(wrapper.emitted('remove')[0][0]).toEqual(board)
  })
})
```

- [x] **Step 2: 运行测试确认失败**

Run: `cd frontend && pnpm test -- src/components/cards.spec.js`
Expected: FAIL，`Failed to resolve import "./UserCard.vue"`

- [x] **Step 3: 创建 UserCard**

创建 `frontend/src/components/UserCard.vue`：

```vue
<script setup>
/**
 * 后台用户卡片：窄屏下替代表格的一行。
 * 只负责展示与转发事件，状态判断（例如管理员不可操作）由父组件通过 props 传入。
 */
const props = defineProps({
  user: { type: Object, required: true },
  statusTag: { type: Function, required: true },
  formatTime: { type: Function, required: true }
})

const emit = defineEmits(['status'])

const isAdminUser = (user) => user.roles?.includes('ADMIN') ?? false
</script>

<template>
  <div class="user-card">
    <div class="card-row"><span class="card-label">用户名</span><span>{{ props.user.username }}</span></div>
    <div class="card-row"><span class="card-label">昵称</span><span>{{ props.user.nickname || '-' }}</span></div>
    <div class="card-row"><span class="card-label">邮箱</span><span>{{ props.user.email }}</span></div>
    <div class="card-row">
      <span class="card-label">状态</span>
      <el-tag :type="props.statusTag(props.user.status)">{{ props.user.status }}</el-tag>
    </div>
    <div class="card-row">
      <span class="card-label">禁言至</span>
      <span>{{ props.user.mutedUntil ? props.formatTime(props.user.mutedUntil) : '-' }}</span>
    </div>
    <div class="card-row">
      <span class="card-label">角色</span>
      <span>
        <el-tag v-for="r in props.user.roles" :key="r" size="small" type="info" class="role-tag">{{ r }}</el-tag>
      </span>
    </div>
    <div class="card-actions">
      <el-button size="small" type="warning" plain :disabled="isAdminUser(props.user)" @click="emit('status', props.user)">
        状态管理
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.user-card {
  border: 1px solid var(--jf-color-border);
  border-radius: var(--jf-radius-md);
  padding: var(--jf-space-3);
  display: flex;
  flex-direction: column;
  gap: var(--jf-space-2);
}
.card-row {
  display: flex;
  gap: var(--jf-space-2);
  font-size: var(--jf-font-body);
}
.card-label {
  color: var(--jf-color-text-3);
  min-width: 64px;
  flex-shrink: 0;
}
.card-actions {
  display: flex;
  gap: var(--jf-space-2);
  margin-top: var(--jf-space-1);
}
</style>
```

- [x] **Step 4: 创建 BoardCard**

创建 `frontend/src/components/BoardCard.vue`：

```vue
<script setup>
/**
 * 后台板块卡片：窄屏下替代表格的一行。编辑/删除只发事件，由父组件决定行为。
 */
const props = defineProps({
  board: { type: Object, required: true }
})

const emit = defineEmits(['edit', 'remove'])
</script>

<template>
  <div class="board-card">
    <div class="card-row"><span class="card-label">名称</span><span>{{ props.board.name }}</span></div>
    <div class="card-row"><span class="card-label">描述</span><span>{{ props.board.description || '-' }}</span></div>
    <div class="card-row"><span class="card-label">帖子数</span><span>{{ props.board.postCount }}</span></div>
    <div class="card-actions">
      <el-button size="small" @click="emit('edit', props.board)">编辑</el-button>
      <el-button size="small" type="danger" plain @click="emit('remove', props.board)">删除</el-button>
    </div>
  </div>
</template>

<style scoped>
.board-card {
  border: 1px solid var(--jf-color-border);
  border-radius: var(--jf-radius-md);
  padding: var(--jf-space-3);
  display: flex;
  flex-direction: column;
  gap: var(--jf-space-2);
}
.card-row {
  display: flex;
  gap: var(--jf-space-2);
  font-size: var(--jf-font-body);
}
.card-label {
  color: var(--jf-color-text-3);
  min-width: 64px;
  flex-shrink: 0;
}
.card-actions {
  display: flex;
  gap: var(--jf-space-2);
  margin-top: var(--jf-space-1);
}
</style>
```

- [x] **Step 5: 运行测试确认通过**

Run: `cd frontend && pnpm test -- src/components/cards.spec.js`
Expected: `Tests 10 passed`（实际多写了昵称空值、formatTime、statusTag、roles 缺失 4 个用例）

- [x] **Step 6: AdminView 改用卡片组件**

在 `frontend/src/views/AdminView.vue` 的 import 区加入：

```js
import UserCard from '@/components/UserCard.vue'
import BoardCard from '@/components/BoardCard.vue'
import { formatDateTime } from '@/utils/format'
```

把板块卡片的 `v-for` 内层整体替换为：

```html
          <div v-if="isMobile" class="board-cards">
            <el-empty v-if="!boards.length" description="暂无板块" />
            <template v-else>
              <BoardCard
                v-for="b in boards"
                :key="b.id"
                :board="b"
                @edit="openBoardEdit"
                @remove="removeBoard"
              />
            </template>
          </div>
```

把用户卡片的 `v-for` 内层整体替换为：

```html
          <div v-if="isMobile" class="user-cards">
            <el-empty v-if="!users.content.length" description="暂无用户" />
            <template v-else>
              <UserCard
                v-for="u in users.content"
                :key="u.id"
                :user="u"
                :status-tag="statusTag"
                :format-time="formatDateTime"
                @status="openStatus"
              />
            </template>
          </div>
```

在 `<style scoped>` 中删除已经移入组件的规则：`.user-card`、`.board-card`、`.card-row`、`.card-label`、`.card-actions`。保留 `.user-cards` 与 `.board-cards`（它们是列表容器）。

- [x] **Step 7: 更新 AdminView 的测试断言**

`frontend/src/views/AdminView.spec.js` 里对卡片内容的断言需要能穿透子组件。在 mount 的 `global.stubs` 里为两个卡片组件加轻量桩：

```js
    stubs: {
      ...existingStubs,
      UserCard: { props: ['user'], template: '<div class="stub-user-card">{{ user.username }}</div>' },
      BoardCard: { props: ['board'], template: '<div class="stub-board-card">{{ board.name }}</div>' }
    }
```

把原本断言 `.user-card` / `.board-card` 存在的地方改为断言 `.stub-user-card` / `.stub-board-card`。

- [x] **Step 8: 全量测试与构建**

Run: `cd frontend && pnpm test && pnpm build`
Expected: `Test Files 13 passed`，`Tests 97 passed`；构建成功

- [x] **Step 9: 确认 AdminView 行数下降**

Run: `(Get-Content frontend/src/views/AdminView.vue | Measure-Object -Line).Lines`
Expected: 小于 260（实际 235，改造前 297）

- [x] **Step 10: 提交**

```bash
git add frontend/src/components/UserCard.vue frontend/src/components/BoardCard.vue \
        frontend/src/components/cards.spec.js \
        frontend/src/views/AdminView.vue frontend/src/views/AdminView.spec.js
git commit -m "refactor: 抽取后台用户与板块卡片组件降低模板重复"
```

---

## Task 7: 收敛共用分页样式并更新文档

**Files:**
- Create: `frontend/src/styles/common.css`
- Modify: `frontend/src/main.js`
- Modify: `frontend/src/views/HomeView.vue`、`PostDetailView.vue`、`NotificationsView.vue`、`AdminView.vue`
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-10-16-experience-iteration-design.md`

- [x] **Step 1: 创建共用样式**

创建 `frontend/src/styles/common.css`：

```css
/* 跨视图复用的样式类。约定：只有 ≥3 处使用的规则才放进这里，
   一次性布局值留在各自组件的 <style scoped> 里。 */

/* 列表底部的分页器：居中并留出上间距 */
.jf-pager {
  margin-top: var(--jf-space-4);
  justify-content: center;
}

/* 窄屏下通用的横向滚动条（板块导航等） */
.jf-hscroll {
  display: flex;
  gap: var(--jf-space-2);
  overflow-x: auto;
  scrollbar-width: none;
  -webkit-overflow-scrolling: touch;
}

.jf-hscroll::-webkit-scrollbar {
  display: none;
}
```

- [x] **Step 2: 引入**

修改 `frontend/src/main.js`，在 `import './style.css'` 之后加一行：

```js
import './styles/common.css'
```

- [x] **Step 3: 四个视图改用 .jf-pager**

在每个视图的模板中，给 `<el-pagination>` 的 `class` 加上 `jf-pager`（保留原有的 `class="pager"` 或直接替换）。以 `HomeView.vue` 为例：

```html
        <el-pagination
          v-if="pageData.totalPages > 1"
          layout="prev, pager, next"
          :total="pageData.totalElements"
          :page-size="query.size"
          :current-page="query.page + 1"
          class="jf-pager"
          @current-change="changePage"
        />
```

然后在各视图的 `<style scoped>` 中删除 `.pager { margin-top: ...; justify-content: center; }` 规则。注意：scoped 样式无法作用于子组件根元素的 class 以外的内部结构，但 `el-pagination` 的根元素会带上传入的 class，所以公共规则可以生效。

- [x] **Step 4: 验证没有残留的 .pager 定义**

Run: `Select-String -Path frontend/src/views/*.vue -Pattern '^\s*\.pager\s*\{'`
Expected: 无输出

- [x] **Step 5: 全量测试与构建**

Run: `cd frontend && pnpm test && pnpm build`
Expected: 全部通过；`grep` 确认 `dist/assets/*.css` 里有 `.jf-pager`

- [x] **Step 6: 更新 README**

在 `README.md` 的「测试」章节之后新增：

```markdown
## 优化记录（第二轮）

| 主题 | 改动 |
| --- | --- |
| 性能 | 帖子与收藏列表改两步查询，消除 `HHH90003004` 集合分页全表加载；收藏列表补齐 tags 批量抓取 |
| 安全 | 数据库密码与 JWT 密钥改为环境变量；`application.yml` 不再含凭据 |
| 运维 | 拆分 `dev` / `prod` profile，生产默认关闭 SQL 日志 |
| 功能 | 通知支持单条标记已读，点击通知即消除未读 |
| 可维护性 | 时间格式化收敛为 `utils/format.js`；后台卡片抽为 `components/UserCard.vue`、`BoardCard.vue`；分页样式收敛为 `.jf-pager` |
```

- [x] **Step 7: 记录到设计文档**

在 `docs/superpowers/specs/2026-10-16-experience-iteration-design.md` 末尾追加：

```markdown
## 9. 第二轮调整（优化）

第一轮完成后，对运行日志与代码做了针对性审计，发现并修复了以下问题：

- **列表分页退化为全表加载**：`PostRepository.search` 在 `tags` 集合上用 `@EntityGraph` 同时分页，Hibernate 打印 `HHH90003004` 把全部匹配行读进内存。改为"先分页取 id、再按 id 批取关联"的两步查询；`LikeService.myFavorites` 的 tags N+1 一并解决。
- **凭据入库**：`application.yml` 曾含明文数据库密码。改为环境变量 + `dev`/`prod` profile 分离，`.env.example` 提供样板。
- **通知单条已读**：原先只有 `read-all`，用户点进通知后那条仍是未读。新增 `POST /api/notifications/{id}/read`，带 recipientId 归属校验，越权与不存在统一返回 404 以免泄露 id 有效性。
- **重复代码**：时间格式化 4 处、`.pager` 样式 4 处、后台卡片模板 2 组。分别收敛到 `utils/format.js`、`.jf-pager`、`components/UserCard.vue` 与 `BoardCard.vue`。
```

- [x] **Step 8: 提交**

```bash
git add frontend/src/styles/common.css frontend/src/main.js \
        frontend/src/views/HomeView.vue frontend/src/views/PostDetailView.vue \
        frontend/src/views/NotificationsView.vue frontend/src/views/AdminView.vue \
        README.md docs/superpowers/specs/2026-10-16-experience-iteration-design.md
git commit -m "refactor: 收敛共用分页样式并记录第二轮优化"
```

---

**状态：已完成。** 实际改动与计划的差异：

- `.pager` 定义实际为 5 处（计划写 4 处），`el-pagination` 实际为 6 个（`ProfileView.vue` 有「我的帖子」「我的收藏」两个）。
- `common.css` 的引入位置定在 `tokens.css` **之后**、`style.css` 之前（计划写「在 style.css 之后」）：`.jf-pager` 用了 `var(--jf-space-4)`，必须晚于令牌定义，而 `style.css` 与它无依赖关系。
- 额外新增 `frontend/src/styles/common.spec.js`（5 个守卫用例），因为这三类收敛（时间格式化、卡片、分页样式）都属「删掉重复后没人会立刻发现」的改动，需要代码层面的断言兜住回归。变异验证 5 项全部被捕获。
- 顺手修正 README 中已过时的测试计数（前端 11 个 spec/75 用例 → 14 个 spec/102 用例），并补上 `frontend/src/components/`、`common.css`、`utils/format.js` 的目录说明。

## 自审记录

**Spec 覆盖检查**

| 审计发现 | 对应任务 |
| --- | --- |
| 分页全表加载（HHH90003004） | Task 1 Step 3-5 |
| 收藏列表 tags N+1 | Task 1 Step 5 |
| 数据库密码明文入库 | Task 2 Step 1-5 |
| 生产日志打印全部 SQL | Task 2 Step 2-3 |
| 通知无法单条已读 | Task 3 + Task 4 |
| 时间格式化重复 5 处（计划误记为 4） | Task 5 |
| 后台卡片模板重复（AdminView 297 行） | Task 6 |
| `.pager` 样式重复 5 处（计划误记为 4） | Task 7 |

**类型一致性检查**

- `PostRepository.searchIds(Long, String, Pageable)` 返回 `Page<Post>`，Task 1 Step 4 用 `ids.map(...)` 与 `PageResponse.of(...)` 消费，签名一致。
- `PostRepository.findWithAssociations(Collection<Long>)` 返回 `List<Post>`，Step 4 与 Step 5 都用 `.stream().collect(toMap(...))` 消费，一致。
- `toSummary(Post, boolean)` 签名在 Task 1 Step 5 沿用 `LikeService` 既有实现，未改动。
- `NotificationService.markRead(Long id, Long recipientId)` 返回 `void`；Controller 调 `notificationService.markRead(id, SecurityUtils.currentUserId())` 后返回 `Result<Void>`，一致。
- 前端 `notificationApi.markRead(id)` 与 store 的 `markRead(id)` 命名一致；`formatDateTime` 与 `formatRelative` 在 Task 5 的 4 处调用与 Task 6 的 `UserCard` props 中用法一致。
- `UserCard` 的 props `user`/`statusTag`/`formatTime` 在 Task 6 Step 3 定义、Step 6 传入、Step 7 桩中声明，三处一致。

**占位符扫描：** 无 TBD / TODO；所有代码步骤均给出完整代码；所有命令均含预期输出。Task 3 Step 1 中出现的 `apToken`/`opToken` 已注明"先读该文件确认实际变量名"，因为它们依赖现有测试类的字段命名。
