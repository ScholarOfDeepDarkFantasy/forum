# 第五轮：全文搜索 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 JavaForum 增加「独立搜索结果页 + 顶栏搜索入口」的全文搜索：公开接口 `GET /api/posts/search`（标题命中优先、同档按发帖时间倒序、`LIKE` 通配符转义）、Flyway `V3` 的 pg_trgm GIN 部分索引、前端 `/search?q=` 结果页（计数、分页、标题/片段高亮、三种空态、窄屏可用），并随 Jenkins 自动部署到 raspi。

**Architecture:** 后端沿用「两步分页」：`PostController.search` → `PostService.search`（归一化 1~50 code point、`escapeLike` 转义 `! % _`、clamp page/size、构造**无排序**的 `PageRequest`）→ `PostRepository.searchByKeyword`（JPQL 里用 `CASE WHEN 标题命中 THEN 0 ELSE 1` 排序）→ `hydrate` 按 id 批取 `board/author/tags` → `mapSummaries`。因为列表 DTO 不带正文，搜索结果额外填一个可空 `snippet`（正文命中处左右各 80 字符的窗口），前端 `buildSnippet` 负责 Markdown 清洗、省略号与高亮。前端搜索页复用 `PostListItem` + `usePostActions`，命中词在**文本层**用 `<mark>` 渲染（不引入 `v-html`）；顶栏用 `useResponsive()` 在输入框/图标按钮之间切换 DOM，不新增任何 `@media`。V3 由容器启动时 Flyway 自动执行，不改 Dockerfile / docker-compose / nginx / deploy.sh。

**Tech Stack:** Java 17 / Spring Boot 3.5.6 / Spring Data JPA / Flyway / PostgreSQL 15（pg_trgm 1.6，trusted）；Vue 3.5 / Vite 8 / Element Plus 2.14.7 / Vitest 5；Jenkins + rootless podman + nginx。

**约定：**
- 每个 Step 只做一件事；测试先红后绿，红了才写实现。
- 后端命令在仓库根 `cd /e/JavaSpace/forum`；前端命令先 `cd frontend`（`vitest` 的 `process.cwd()` 必须是 `frontend`，`breakpoint.spec.js` 扫描 `src/**` 依赖这一点）。
- 提交信息逐字使用 spec §7.1 给出的文案；Task 1~4 只 `git commit`，**Task 5 才 `git push origin master`**。
- 不新增后端/前端依赖；不动 `Dockerfile`、`docker-compose.yml`、`deploy/nginx/`、`deploy/deploy.sh`（V3 在容器启动时由 Flyway 自动执行）。
- 新增/修改的 `.vue`、`.css` **不得出现任何 `@media`**：`frontend/src/styles/breakpoint.spec.js` 要求全 `src` 恰好 6 处 `@media (max-width: 768px)`，且禁止 `min-width`。
- 判空、命名、注释风格对齐既有代码（中文注释解释「为什么」，不解释「做了什么」）；所有文件 LF + UTF-8。
- 数量为估算：后端预期 **13 个测试类 / 105 个用例**（spec §6.3 写 14 类/约 102 例，差异见 Self-Review；含 Task 1 质量修复新增的 4 个搜索用例），前端预期 **28 个 spec / 约 273 例**（含 Task 2/3 两轮质量修复）；**以实测为准**，实测与估算不一致时按 Task 5 Step 9 的规则先改文档。

**设计文档：** `docs/superpowers/specs/2026-10-21-round5-fulltext-search-design.md`

---

## 文件结构总览

### 后端（新增）

| 文件 | 职责 |
|---|---|
| `src/main/resources/db/migration/V3__post_search_trgm_index.sql` | `CREATE EXTENSION IF NOT EXISTS pg_trgm` + 标题/正文两个 GIN 部分索引（`WHERE deleted = false`） |
| `src/test/java/org/song/forum/controller/SearchFlowIntegrationTest.java` | `GET /api/posts/search` 的 14 个集成用例（契约 400、排序、分页、转义、软删除、片段 code point 边界、大小写、既有响应无 `snippet` 键） |

### 后端（修改）

| 文件 | 改动 |
|---|---|
| `src/main/java/org/song/forum/repository/PostRepository.java` | 新增 `searchByKeyword(pattern, pageable)`：标题/正文 `LIKE ... ESCAPE '!'` + CASE 标题优先 + `createTime DESC, id DESC` |
| `src/main/java/org/song/forum/service/PostService.java` | 新增 `search(...)`、`normalizeSearchKeyword`、`escapeLike`、`snippetOf`、`SNIPPET_RADIUS` |
| `src/main/java/org/song/forum/controller/PostController.java` | 新增 `GET /search`（插在 `list` 之后、`/{id}` 之前） |
| `src/main/java/org/song/forum/dto/post/PostSummaryResponse.java` | 新增可空 `snippet`：仅搜索接口填充；`@JsonInclude(NON_NULL)` 保证其它接口 JSON 不出现该键 |
| `src/test/java/org/song/forum/repository/RepositoryTests.java` | `@DataJpaTest` 增加 `spring.flyway.enabled=false`；新增 1 个 `searchByKeyword` 用例 |

### 前端（新增）

| 文件 | 职责 |
|---|---|
| `frontend/src/utils/search.js` | 纯函数：`highlightSegments(text, keyword)` 分段高亮、`buildSnippet(content, keyword, radius)` Markdown 轻量清洗 + 命中窗口 + 省略号 |
| `frontend/src/utils/search.spec.js` | 上述两个纯函数的 30 个用例 |
| `frontend/src/components/HeaderSearch.vue` | 顶栏搜索入口：桌面 `el-input`（220px）/ 窄屏图标按钮，`useResponsive` + `v-if` 切换 |
| `frontend/src/components/HeaderSearch.spec.js` | 9 个用例：回车跳转、空白不跳、同页同词不重复、窄屏图标（含 aria-label）、URL→输入框同步、搜索页清空回空态、非搜索页清空不跳转、码点限长（51 字符拒绝 / 50 emoji 允许） |
| `frontend/src/views/SearchView.vue` | `/search?q=` 搜索结果页：计数、分页、三种空态、`requestSeq` 防竞态、点赞复用共享状态 |
| `frontend/src/views/SearchView.spec.js` | 18 个用例：空态、加载与 props、无结果、失败重试、分页、换词、过期响应、页码重置、清空后错误/在途请求复位、分页与重试保草稿、加载中不渲染计数、单页不分页、空白 q、同词不重复、码点限长、快速翻页过期响应 |

### 前端（修改）

| 文件 | 改动 |
|---|---|
| `frontend/src/api/index.js` | `postApi.search: (params) => api.get('/posts/search', { params })` |
| `frontend/src/api/index.spec.js` | 新增「postApi 搜索请求路径」1 例（12 → 13） |
| `frontend/src/router/index.js` | 新增 `/search` 懒加载路由，`name: 'search'` |
| `frontend/src/App.vue` | 顶栏「标签」之后挂 `<HeaderSearch />`；`.header-inner` 改 `flex-start` + `gap`，`.nav-actions` 自动左推 |
| `frontend/src/components/PostListItem.vue` | 可选 props `keyword`/`snippet`；标题与片段文本层 `<mark class="hit">`；新增 `.hit` / `.post-snippet` 样式（零 `@media`） |
| `frontend/src/components/PostListItem.spec.js` | 新增 4 例（4 → 8）：标题高亮、片段渲染、HTML 纯文本（XSS）防护、关键词未命中不高亮 |

### 部署与文档

| 文件 | 改动 |
|---|---|
| `README.md` | 功能清单加全文搜索、测试基线 13/105 与 28/273、已知限制加中文子串匹配、目录结构加 `HeaderSearch`/搜索工具 |
| `docs/DEVELOPMENT.md` | §2.2 第五轮、§3.1/§3.2 基线、§3.3 加 V3、§3.4 36 个端点 + 搜索契约、§3.5 加 `/search`、新增 §4.10、§5.1 加 T13/T14/T15、§5.2 标记完成、§7 决策、§8 变更日志 |
| `docs/deployment/README.md` | §4 回滚加 drop 索引、§5 加 V3 索引验证、§7 已知限制加「1–2 字查询顺序扫描」 |

---

## Task 1: 后端搜索接口 + V3 迁移 + 测试隔离

**Files:**
- Create: `src/main/resources/db/migration/V3__post_search_trgm_index.sql`
- Create: `src/test/java/org/song/forum/controller/SearchFlowIntegrationTest.java`
- Modify: `src/test/java/org/song/forum/repository/RepositoryTests.java:22-30,281`
- Modify: `src/main/java/org/song/forum/repository/PostRepository.java:33-35`
- Modify: `src/main/java/org/song/forum/dto/post/PostSummaryResponse.java:37`
- Modify: `src/main/java/org/song/forum/service/PostService.java:158,234-237`
- Modify: `src/main/java/org/song/forum/controller/PostController.java:39`

- [ ] **Step 1-1: 关闭 RepositoryTests 的 Flyway，并写失败的仓库搜索测试**

Modify `src/test/java/org/song/forum/repository/RepositoryTests.java:22-30`，把 `@DataJpaTest` 的 properties 换成：

```java
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:repo-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // V3 迁移在 PostgreSQL 上执行 CREATE EXTENSION pg_trgm，H2 不支持；
        // 显式关闭 Flyway，schema 完全由 ddl-auto=create-drop 生成，与其它测试类一致。
        "spring.flyway.enabled=false"
})
```

再把下面的用例追加到类末尾（第 281 行 `}` 之前）：

```java
    /**
     * 全文搜索第一步的 JPQL：标题命中优先（CASE 0/1），正文命中排后，软删除不出现。
     * pattern 由 service 转义，SQL 用 ESCAPE '!'，因此 "%" 必须按字面量匹配。
     */
    @Test
    void searchByKeywordRanksTitleHitsFirstAndEscapesWildcards() {
        User author = user("searcher");
        Board board = boardRepository.save(Board.builder().name("搜索板块").description("验证全文搜索 JPQL").build());

        Post titleHit = postRepository.save(Post.builder()
                .board(board).author(author)
                .title("标题里有关键词").content("正文无关")
                .build());
        // 先建标题命中、后建正文命中：若只按 createTime 倒序，正文命中的会排第一，
        // 因此这个顺序能区分「CASE 标题优先」是否真的生效。
        Post contentHit = postRepository.save(Post.builder()
                .board(board).author(author)
                .title("正文命中示例").content("正文包含关键词，标题没有")
                .build());
        postRepository.save(Post.builder()
                .board(board).author(author)
                .title("完全无关").content("完全无关")
                .build());
        postRepository.save(Post.builder()
                .board(board).author(author)
                .title("关键词但已删除").content("正文无关").deleted(true)
                .build());
        em.flush();
        em.clear();

        Page<Post> page = postRepository.searchByKeyword("关键词", PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(Post::getId)
                .containsExactly(titleHit.getId(), contentHit.getId());

        // ESCAPE '!'：转义后 pattern 里的 "%" 是字面量，不能当通配符用
        Post percent = postRepository.save(Post.builder()
                .board(board).author(author)
                .title("100%完成").content("进度")
                .build());
        postRepository.save(Post.builder()
                .board(board).author(author)
                .title("1000完成").content("进度")
                .build());
        em.flush();
        em.clear();

        Page<Post> escaped = postRepository.searchByKeyword("100!%", PageRequest.of(0, 10));

        assertThat(escaped.getTotalElements()).isEqualTo(1);
        assertThat(escaped.getContent().get(0).getId()).isEqualTo(percent.getId());
    }
```

Run:

```bash
cd /e/JavaSpace/forum && mvn -B test -Dtest=RepositoryTests
```

Expected: **FAIL** —— 编译失败：`cannot find symbol: method searchByKeyword(String,PageRequest)`（`PostRepository` 还没有该方法）。

- [ ] **Step 1-2: 在 PostRepository 增加 searchByKeyword**

Modify `src/main/java/org/song/forum/repository/PostRepository.java:33-35`（`searchIds` 方法之后、`findWithAssociations` 注释之前）插入：

```java
    /**
     * 全文搜索分页第一步：只取骨架，不 join tags 集合（原因同 {@link #searchIds}）。
     * pattern 由 service 层做过 LIKE 通配符转义（! % _ → !x），SQL 用 ESCAPE '!'。
     * 排序：标题命中优先（CASE 0/1），同档按 createTime 倒序、id 倒序；置顶不加权。
     * 追加 id 是兜底：createTime 相同（批量导入/同微秒）时，仅按时间排序在 PostgreSQL
     * 下顺序不稳定，翻页会出现重复或漏行。
     */
    @Query("""
            SELECT p FROM Post p
            WHERE p.deleted = false
              AND (LOWER(p.title) LIKE CONCAT('%', :pattern, '%') ESCAPE '!'
                   OR LOWER(p.content) LIKE CONCAT('%', :pattern, '%') ESCAPE '!')
            ORDER BY CASE WHEN LOWER(p.title) LIKE CONCAT('%', :pattern, '%') ESCAPE '!' THEN 0 ELSE 1 END,
                     p.createTime DESC, p.id DESC
            """)
    @EntityGraph(attributePaths = {"board", "author"})
    Page<Post> searchByKeyword(@Param("pattern") String pattern, Pageable pageable);
```

Run:

```bash
cd /e/JavaSpace/forum && mvn -B test -Dtest=RepositoryTests
```

Expected: **PASS** —— `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`。

- [ ] **Step 1-3: 新增 V3 迁移（pg_trgm 扩展 + 两个 GIN 部分索引）**

Create `src/main/resources/db/migration/V3__post_search_trgm_index.sql`：

```sql
-- 全文搜索：pg_trgm 三元组索引（PostgreSQL）
-- 说明：V3 只能跑在 PostgreSQL 上；H2 测试统一关闭 Flyway（见下），不会执行本迁移。
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_posts_title_trgm
    ON posts USING gin (LOWER(title) gin_trgm_ops)
    WHERE deleted = false;

CREATE INDEX IF NOT EXISTS idx_posts_content_trgm
    ON posts USING gin (LOWER(content) gin_trgm_ops)
    WHERE deleted = false;
```

Run:

```bash
cd /e/JavaSpace/forum && ls src/main/resources/db/migration && grep -c 'CREATE INDEX' src/main/resources/db/migration/V3__post_search_trgm_index.sql
```

Expected: 迁移目录出现 `V1__init_schema.sql`、`V2__notification_comment_id.sql`、`V3__post_search_trgm_index.sql`；`grep -c` 输出 `2`。本步无 JUnit 用例可跑（H2 无法执行 `CREATE EXTENSION`，真实执行由 Task 5 的 raspi 冒烟验证）。

- [ ] **Step 1-4: 给 PostSummaryResponse 增加可空 snippet 字段**

Modify `src/main/java/org/song/forum/dto/post/PostSummaryResponse.java:37`（`favorited` 字段之后、类结束 `}` 之前）追加：

```java
    /**
     * 正文命中处的纯文本窗口（左右各 80 字符，未清洗 Markdown、不含省略号）。
     * <p>只有全文搜索接口会填：搜索页需要在标题下方展示「正文命中上下文」，
     * 而列表 DTO 刻意不带完整正文（避免搜索页响应膨胀）。标题命中且正文不含
     * 关键词时为 {@code ""}（前端据此不渲染片段行），其它列表接口保持 {@code null}。</p>
     * <p>NON_NULL 保证列表/我的帖子/收藏/详情等既有接口的 JSON 里不出现 {@code "snippet": null}，
     * 这些接口的响应与加字段前保持逐字节一致。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String snippet;
```

同时新增 import：`import com.fasterxml.jackson.annotation.JsonInclude;`（放在 `lombok` import 之前）。

Run:

```bash
cd /e/JavaSpace/forum && mvn -B test-compile
```

Expected: **PASS** —— 编译通过（`PostDetailResponse.of` 显式逐字段复制，不受新字段影响）。先加字段再写集成测试：测试里要断言 `getSnippet()`，字段不存在时测试根本编译不过，红的原因就说不清了。

- [ ] **Step 1-5: 写失败的搜索接口集成测试**

Create `src/test/java/org/song/forum/controller/SearchFlowIntegrationTest.java`：

```java
package org.song.forum.controller;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.song.forum.dto.auth.AuthResponse;
import org.song.forum.dto.auth.LoginRequest;
import org.song.forum.dto.auth.RegisterRequest;
import org.song.forum.dto.board.BoardRequest;
import org.song.forum.dto.board.BoardResponse;
import org.song.forum.dto.post.PostDetailResponse;
import org.song.forum.dto.post.PostRequest;
import org.song.forum.dto.post.PostSummaryResponse;
import org.song.forum.util.PageResponse;
import org.song.forum.util.Result;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第五轮全文搜索接口集成测试（H2 + 随机端口）：
 * 游客可搜 → 4 类 400 → 标题优先 → 正文命中与软删除 → 分页 → 参数夹取 → 通配符转义 → 时间倒序
 * → 片段不劈开代理对 → 长度按 code point → 大小写不敏感 → 非搜索响应不含 snippet 键
 * <p>每个用例用自己的关键词与独立帖子，避免用例间数据互相命中。</p>
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:search-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SearchFlowIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private static String adminToken;
    private static String userToken;
    private static Long boardId;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private String loginAdmin() {
        LoginRequest l = new LoginRequest();
        l.setUsername("admin");
        l.setPassword("admin123456");
        ResponseEntity<Result<AuthResponse>> resp = rest.exchange(url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>(l), new ParameterizedTypeReference<>() {});
        return resp.getBody().getData().getToken();
    }

    private String registerAndLogin(String name) {
        RegisterRequest r = new RegisterRequest();
        r.setUsername(name);
        r.setPassword("password123");
        r.setEmail(name + "@test.com");
        rest.postForEntity(url("/api/auth/register"), r, Result.class);
        LoginRequest l = new LoginRequest();
        l.setUsername(name);
        l.setPassword("password123");
        ResponseEntity<Result<AuthResponse>> resp = rest.exchange(url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>(l), new ParameterizedTypeReference<>() {});
        return resp.getBody().getData().getToken();
    }

    private Long createPost(String token, String title, String content) {
        PostRequest req = new PostRequest();
        req.setBoardId(boardId);
        req.setTitle(title);
        req.setContent(content);
        req.setTags(List.of());
        ResponseEntity<Result<PostDetailResponse>> resp = rest.exchange(url("/api/posts"), HttpMethod.POST,
                new HttpEntity<>(req, bearer(token)), new ParameterizedTypeReference<>() {});
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        return resp.getBody().getData().getId();
    }

    /**
     * 调搜索接口：rawQuery 直接拼在 '?' 之后，百分号编码由调用方负责。
     * 用 URI 重载而不是 String 重载——String 走 URI 模板展开，会把已经编码好的 %E5 再转义一次。
     */
    private ResponseEntity<Result<PageResponse<PostSummaryResponse>>> search(String rawQuery) {
        URI uri = URI.create(url("/api/posts/search?" + rawQuery));
        return rest.exchange(uri, HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
    }

    /** 关键词百分号编码：'100%' → '100%25'，中文与 '+&' 也能安全进 query */
    private String encode(String keyword) {
        return URLEncoder.encode(keyword, StandardCharsets.UTF_8);
    }

    /**
     * createTime 是微秒精度的 LocalDateTime，两次连续 HTTP 请求几乎不会撞在同一微秒；
     * 排序用例之间显式 sleep 一下，避免「时间倒序」的断言偶发不稳定。
     */
    private void pauseBetweenCreates() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试线程被中断", e);
        }
    }

    @Test
    @Order(1)
    void guestCanSearch() {
        adminToken = loginAdmin();
        BoardRequest board = new BoardRequest();
        board.setName("搜索测试板块");
        board.setDescription("用于全文搜索接口测试");
        board.setSortOrder(1);
        ResponseEntity<Result<BoardResponse>> created = rest.exchange(url("/api/boards"), HttpMethod.POST,
                new HttpEntity<>(board, bearer(adminToken)), new ParameterizedTypeReference<>() {});
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        boardId = created.getBody().getData().getId();
        userToken = registerAndLogin("searcher");
        createPost(userToken, "搜索测试帖", "全文搜索接口的准备数据");

        // 不带 Authorization：搜索是公开 GET
        ResponseEntity<Result<PageResponse<PostSummaryResponse>>> resp = search("keyword=" + encode("全文搜索"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().isSuccess()).isTrue();
        assertThat(resp.getBody().getData()).isNotNull();
    }

    @Test
    @Order(2)
    void missingKeywordReturns400() {
        ResponseEntity<Result<PageResponse<PostSummaryResponse>>> resp = search("page=0&size=10");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getMessage()).contains("keyword");
    }

    @Test
    @Order(3)
    void blankKeywordReturns400() {
        ResponseEntity<Result<PageResponse<PostSummaryResponse>>> resp = search("keyword=%20%20");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getMessage()).isEqualTo("搜索关键词不能为空");
    }

    @Test
    @Order(4)
    void keywordLongerThan50CodePointsReturns400() {
        ResponseEntity<Result<PageResponse<PostSummaryResponse>>> resp = search("keyword=" + "a".repeat(51));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getMessage()).contains("不能超过 50 个字符");
    }

    @Test
    @Order(5)
    void titleHitRanksBeforeContentHit() {
        // 标题命中先建、正文命中后建：只按时间倒序时正文会排第一，只有 CASE 生效才能通过
        Long titleHit = createPost(userToken, "标题优先词 出现在标题里", "正文与关键词无关");
        pauseBetweenCreates();
        Long contentHit = createPost(userToken, "正文命中排第二", "这段正文包含 标题优先词 但标题不含");

        PageResponse<PostSummaryResponse> page = search("keyword=" + encode("标题优先词") + "&page=0&size=10")
                .getBody().getData();

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content()).extracting(PostSummaryResponse::getId)
                .containsExactly(titleHit, contentHit);
        // 标题命中且正文不含关键词：不给片段，避免在标题下面重复显示一遍标题
        assertThat(page.content().get(0).getSnippet()).isEmpty();
        // 正文命中：服务端给的是命中处窗口，交给前端 buildSnippet 清洗与高亮
        assertThat(page.content().get(1).getSnippet()).contains("标题优先词");
    }

    @Test
    @Order(6)
    void contentHitIsSearchableUntilSoftDeleted() {
        Long postId = createPost(userToken, "软删除验证", "这里正文包含 正文命中词 用于搜索");

        PageResponse<PostSummaryResponse> before = search("keyword=" + encode("正文命中词")).getBody().getData();
        assertThat(before.content()).extracting(PostSummaryResponse::getId).contains(postId);

        ResponseEntity<Result<Void>> deleted = rest.exchange(url("/api/posts/" + postId), HttpMethod.DELETE,
                new HttpEntity<>(bearer(userToken)), new ParameterizedTypeReference<>() {});
        assertThat(deleted.getStatusCode().value()).isEqualTo(200);

        PageResponse<PostSummaryResponse> after = search("keyword=" + encode("正文命中词")).getBody().getData();
        assertThat(after.totalElements()).isZero();
        assertThat(after.content()).isEmpty();
    }

    @Test
    @Order(7)
    void paginationReportsTotalAndLastFlag() {
        createPost(userToken, "分页检索词 一", "分页检索词 正文一");
        createPost(userToken, "分页检索词 二", "分页检索词 正文二");
        createPost(userToken, "分页检索词 三", "分页检索词 正文三");

        PageResponse<PostSummaryResponse> first = search("keyword=" + encode("分页检索词") + "&page=0&size=2")
                .getBody().getData();
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(first.content()).hasSize(2);
        assertThat(first.size()).isEqualTo(2);
        assertThat(first.last()).isFalse();

        PageResponse<PostSummaryResponse> second = search("keyword=" + encode("分页检索词") + "&page=1&size=2")
                .getBody().getData();
        assertThat(second.content()).hasSize(1);
        assertThat(second.last()).isTrue();
    }

    @Test
    @Order(8)
    void pageAndSizeAreClamped() {
        createPost(userToken, "钳制词标题", "钳制词正文");

        PageResponse<PostSummaryResponse> bigSize = search("keyword=" + encode("钳制词") + "&page=0&size=999")
                .getBody().getData();
        assertThat(bigSize.size()).isEqualTo(50);

        PageResponse<PostSummaryResponse> negativePage = search("keyword=" + encode("钳制词") + "&page=-1&size=10")
                .getBody().getData();
        assertThat(negativePage.page()).isZero();
        assertThat(negativePage.size()).isEqualTo(10);
    }

    @Test
    @Order(9)
    void likeWildcardsAreEscaped() {
        createPost(userToken, "100%完成", "进度说明");
        createPost(userToken, "1000完成", "进度说明");
        createPost(userToken, "100_完成", "进度说明");
        createPost(userToken, "100!完成", "进度说明");

        // '%' 不转义时会当通配符，搜 100% 会误命中 1000完成
        PageResponse<PostSummaryResponse> percent = search("keyword=" + encode("100%")).getBody().getData();
        assertThat(percent.totalElements()).isEqualTo(1);
        assertThat(percent.content().get(0).getTitle()).isEqualTo("100%完成");

        // '_' 同理（单个字符通配）
        PageResponse<PostSummaryResponse> underscore = search("keyword=" + encode("100_")).getBody().getData();
        assertThat(underscore.totalElements()).isEqualTo(1);
        assertThat(underscore.content().get(0).getTitle()).isEqualTo("100_完成");

        // '!' 是 ESCAPE 指定的转义符，它自己也要能被搜到
        PageResponse<PostSummaryResponse> bang = search("keyword=" + encode("100!")).getBody().getData();
        assertThat(bang.totalElements()).isEqualTo(1);
        assertThat(bang.content().get(0).getTitle()).isEqualTo("100!完成");
    }

    @Test
    @Order(10)
    void titleHitsAreOrderedByCreateTimeDesc() {
        Long older = createPost(userToken, "时间排序词 旧帖", "正文一");
        pauseBetweenCreates();
        Long newer = createPost(userToken, "时间排序词 新帖", "正文二");

        PageResponse<PostSummaryResponse> page = search("keyword=" + encode("时间排序词")).getBody().getData();

        assertThat(page.content()).extracting(PostSummaryResponse::getId).containsExactly(newer, older);
    }

    /**
     * 片段窗口必须落在 code point 边界上：UTF-16 下标切割会把 emoji/生僻字劈成两半，
     * 留下落单的 high/low surrogate（序列化成 JSON 会变成替换字符或直接报错）。
     * <p>正文构造：窗口 start 正好落在 emoji 的 low surrogate 上——不吸附时片段以
     * 半个 emoji 开头。关键词放在后面，保证 index 的计算不因 emoji 而偏移。</p>
     */
    @Test
    @Order(11)
    void snippetWindowDoesNotSplitSurrogatePairs() {
        String content = "x".repeat(80) + "😀" + "z".repeat(79) + "KEYWORD" + "y".repeat(100);
        Long postId = createPost(userToken, "片段边界验证", content);

        PageResponse<PostSummaryResponse> page = search("keyword=KEYWORD&page=0&size=10").getBody().getData();
        assertThat(page.content()).extracting(PostSummaryResponse::getId).contains(postId);

        PostSummaryResponse hit = page.content().stream()
                .filter(p -> postId.equals(p.getId()))
                .findFirst()
                .orElseThrow();
        String snippet = hit.getSnippet();

        assertThat(Character.isLowSurrogate(snippet.charAt(0)))
                .as("片段不应以落单的 low surrogate 开头")
                .isFalse();
        assertThat(Character.isHighSurrogate(snippet.charAt(snippet.length() - 1)))
                .as("片段不应以落单的 high surrogate 结尾")
                .isFalse();
        // 窗口 start 吸附回 emoji 起点后，片段从 emoji 开始：emoji + 79z + KEYWORD + 80y（UTF-16 长度 168）；
        // 不吸附时片段以半个 emoji（落单 low surrogate）开头。
        assertThat(snippet).startsWith("😀" + "z".repeat(79) + "KEYWORD");
        assertThat(snippet).hasSize(168);
        assertNoLoneSurrogate(snippet);
    }

    /**
     * 长度上限按 code point 计：30 个 emoji 的 UTF-16 length 是 60，但只有 30 个字符，必须放行；
     * 60 个 emoji 才是真的超过 50 个字符。
     */
    @Test
    @Order(12)
    void keywordLengthLimitCountsCodePointsNotUtf16Units() {
        ResponseEntity<Result<PageResponse<PostSummaryResponse>>> thirty = search("keyword=" + encode("😀".repeat(30)));
        assertThat(thirty.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Result<PageResponse<PostSummaryResponse>>> sixty = search("keyword=" + encode("😀".repeat(60)));
        assertThat(sixty.getStatusCode().value()).isEqualTo(400);
        assertThat(sixty.getBody().getMessage()).contains("不能超过 50 个字符");
    }

    @Test
    @Order(13)
    void searchIsCaseInsensitiveAcrossTitleAndContent() {
        Long postId = createPost(userToken, "java 入门", "从零开始学 Java 语法");

        assertThat(search("keyword=" + encode("JAVA")).getBody().getData().content())
                .extracting(PostSummaryResponse::getId).contains(postId);
        assertThat(search("keyword=" + encode("Java")).getBody().getData().content())
                .extracting(PostSummaryResponse::getId).contains(postId);
    }

    /**
     * snippet 只属于搜索接口：既有列表/详情接口的 JSON 不能多出 "snippet": null，
     * 否则前端契约与快照都会变。搜索接口仍显式返回空串（标题命中、正文不含关键词）。
     */
    @Test
    @Order(14)
    void snippetFieldIsAbsentFromNonSearchResponses() {
        Long postId = createPost(userToken, "片段字段验证", "正文不含关键词");

        ResponseEntity<String> list = rest.getForEntity(url("/api/posts?size=50"), String.class);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getBody()).doesNotContain("\"snippet\"");

        ResponseEntity<String> detail = rest.getForEntity(url("/api/posts/" + postId), String.class);
        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        assertThat(detail.getBody()).doesNotContain("\"snippet\"");

        PageResponse<PostSummaryResponse> page = search("keyword=" + encode("片段字段验证")).getBody().getData();
        assertThat(page.content().get(0).getSnippet()).isEmpty();
        ResponseEntity<String> searchBody = rest.getForEntity(
                URI.create(url("/api/posts/search?keyword=" + encode("片段字段验证"))), String.class);
        assertThat(searchBody.getBody()).contains("\"snippet\":\"\"");
    }

    /** 逐 code point 检查：合法代理对会被 codePoints() 合并成 >0xFFFF 的值，只有落单代理才会落在 0xD800~0xDFFF。 */
    private static void assertNoLoneSurrogate(String snippet) {
        snippet.codePoints().forEach(cp -> assertThat(cp >= 0xD800 && cp <= 0xDFFF)
                .as("片段含未配对的代理字符: U+%04X", cp)
                .isFalse());
    }
}
```

Run:

```bash
cd /e/JavaSpace/forum && mvn -B test -Dtest=SearchFlowIntegrationTest
```

Expected: **FAIL** —— `Tests run: 14`，用例普遍失败：第 1 例因 `GET /api/posts/search` 返回 404 而断言失败（端点尚未实现），第 2~4 例同样 404 而非 400，其余依赖搜索响应的用例一并失败。

- [ ] **Step 1-6: 实现 PostService.search 与三个私有工具**

Modify `src/main/java/org/song/forum/service/PostService.java:158`（`listByTag` 方法之后、`getDetail` 之前）插入：

```java
    /**
     * 全文搜索：标题命中优先，同档按创建时间倒序、时间相同再按 id 倒序。
     * <p>关键词去首尾空白后必须是 1~50 个 code point；page 负数按 0，size 夹到 1~50。
     * 这里再夹取一次 size 是防御性的：controller 也夹了一次，service 作为公开方法
     * 不依赖调用方已经处理过。</p>
     */
    @Transactional(readOnly = true)
    public PageResponse<PostSummaryResponse> search(String rawKeyword, int page, int size) {
        String keyword = normalizeSearchKeyword(rawKeyword);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);
        // 小写 + 转义都在 Java 侧做，JPQL 只拼 %...%；大小写不敏感由 SQL 的 LOWER 保证。
        String pattern = escapeLike(keyword.toLowerCase(Locale.ROOT));
        // pageable 必须不带 Sort：排序写在 JPQL 的 ORDER BY 里，再带 Sort 会被拼成第二个 ORDER BY。
        Page<Post> skeletons = postRepository.searchByKeyword(pattern, PageRequest.of(safePage, safeSize));
        Page<Post> hydrated = hydrate(skeletons);
        Page<PostSummaryResponse> summaries = mapSummaries(hydrated);
        // 搜索页要显示正文命中处的一行上下文，而列表 DTO 不带正文：
        // 只为当前页附一段窗口。snippetOf 返回空串而非 null（Collectors.toMap 不接受 null 值）。
        Map<Long, String> snippets = hydrated.getContent().stream()
                .collect(Collectors.toMap(Post::getId, p -> snippetOf(p.getContent(), keyword)));
        summaries.forEach(s -> s.setSnippet(snippets.get(s.getId())));
        return PageResponse.of(summaries);
    }
```

再把三个工具方法追加到 `src/main/java/org/song/forum/service/PostService.java:234-237`（`blankToEmpty` 之后）：

```java
    /**
     * 搜索关键词归一化：去首尾空白、限长 1~50 个 code point，保留原始大小写。
     * 用 codePointCount 而不是 length：emoji/生僻字的 length 是 2，按 length 判长会误伤。
     */
    private static String normalizeSearchKeyword(String rawKeyword) {
        String keyword = rawKeyword == null ? "" : rawKeyword.trim();
        if (keyword.isEmpty()) {
            throw new BusinessException("搜索关键词不能为空");
        }
        if (keyword.codePointCount(0, keyword.length()) > 50) {
            throw new BusinessException("搜索关键词不能超过 50 个字符");
        }
        return keyword;
    }

    /**
     * 转义 LIKE 通配符：'!' 是 JPQL 里 ESCAPE 指定的转义符，所以它自己也要先转义。
     * 不转义时 '%'（如「100%完成」）会被当成任意串，搜「100%」会误命中「1000完成」。
     */
    private static String escapeLike(String keyword) {
        StringBuilder sb = new StringBuilder(keyword.length() + 8);
        for (int i = 0; i < keyword.length(); i++) {
            char c = keyword.charAt(i);
            if (c == '!' || c == '%' || c == '_') {
                sb.append('!');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 搜索片段窗口半径：以正文命中位置为中心，左右各保留的字符数 */
    private static final int SNIPPET_RADIUS = 80;

    /**
     * 取正文命中处的一段纯文本窗口。
     * <p>这里只截窗口：不清洗 Markdown、不加省略号——清洗、省略号与高亮统一由前端
     * {@code utils/search.js} 的 {@code buildSnippet} 完成，避免同一套规则在两种语言里各写一遍。
     * 正文不含关键词（标题命中）时返回空串。</p>
     * <p>窗口端点会吸附到 code point 边界：UTF-16 下标可能切在代理对中间，
     * 留下落单的 surrogate（序列化时变替换字符）。吸附后最多多保留一个字符。</p>
     */
    private static String snippetOf(String content, String keyword) {
        if (content == null || content.isEmpty() || keyword.isEmpty()) {
            return "";
        }
        int index = content.toLowerCase(Locale.ROOT).indexOf(keyword.toLowerCase(Locale.ROOT));
        if (index < 0) {
            return "";
        }
        int start = Math.max(0, index - SNIPPET_RADIUS);
        int end = Math.min(content.length(), index + keyword.length() + SNIPPET_RADIUS);
        // start 落在 low surrogate 上说明前一个 code point 被切开了，回退一格包含完整的代理对
        if (start > 0 && Character.isLowSurrogate(content.charAt(start))) {
            start--;
        }
        // end 的前一个字符是 high surrogate 说明最后一个 code point 被切开了，前进一格补齐
        if (end < content.length() && Character.isHighSurrogate(content.charAt(end - 1))) {
            end++;
        }
        return content.substring(start, end);
    }
```

Run:

```bash
cd /e/JavaSpace/forum && mvn -B test -Dtest=SearchFlowIntegrationTest
```

Expected: **FAIL** —— 编译通过、`PostService.search` 就绪，但 `GET /api/posts/search` 仍是 404，第 1 例继续红。

- [ ] **Step 1-7: 在 PostController 加 GET /search**

Modify `src/main/java/org/song/forum/controller/PostController.java:39`（`list` 方法结束 `}` 之后、`detail` 注释之前）插入：

```java
    /** 全文搜索：标题命中优先，其次按发帖时间倒序（游客可访问） */
    @GetMapping("/search")
    public Result<PageResponse<PostSummaryResponse>> search(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(postService.search(keyword, page, Math.min(Math.max(size, 1), 50)));
    }
```

> 位置很关键：Spring MVC 的字面量路径优先于 `/{id}`，但为了可读性与既有 `/mine` 一致，仍放在 `/{id}` 之前；不要放到 `detail` 之后。

Run:

```bash
cd /e/JavaSpace/forum && mvn -B test -Dtest=SearchFlowIntegrationTest
```

Expected: **PASS** —— `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0`。

- [ ] **Step 1-8: 后端全量回归**

Run:

```bash
cd /e/JavaSpace/forum && mvn -B clean test
```

Expected: **PASS** —— `BUILD SUCCESS`；`grep -h 'Tests run' target/surefire-reports/*.txt` 汇总为 **13 个测试类、105 个用例**，其中 1 个 POSIX 文件权限用例在 Windows 上 `Skipped`。

- [ ] **Step 1-9: 提交后端搜索实现**

Run:

```bash
cd /e/JavaSpace/forum
grep -c 'spring.flyway.enabled=false' src/test/java/org/song/forum/repository/RepositoryTests.java
git add src/main/java/org/song/forum/repository/PostRepository.java \
        src/main/java/org/song/forum/service/PostService.java \
        src/main/java/org/song/forum/controller/PostController.java \
        src/main/java/org/song/forum/dto/post/PostSummaryResponse.java \
        src/main/resources/db/migration/V3__post_search_trgm_index.sql \
        src/test/java/org/song/forum/repository/RepositoryTests.java \
        src/test/java/org/song/forum/controller/SearchFlowIntegrationTest.java
git commit -m "feat: 新增帖子全文搜索接口与 pg_trgm 索引"
```

Expected: `grep -c` 输出 `1`；提交成功；`git status` 干净；`git show --stat HEAD` 显示 7 个文件。

### Task 1 质量修复记录（实施偏差）

Task 1 提交后评审提出 5 条质量修复，已单独一个提交落地（`fix: 加固全文搜索片段边界与排序稳定性`），本计划对应片段已同步为修复后的代码：

1. **片段窗口按 code point 吸附**（`PostService.snippetOf`，`PostService.java:314-325`）：UTF-16 下标可能切在代理对中间，`start` 落在 low surrogate 时回退一格、`end` 前一个是 high surrogate 时前进一格；窗口半径 80、`Locale.ROOT` 小写搜索、正文未命中返回 `""` 均不变。新增 `@Order(11)` 用例（80 个 `x` + 😀 + 79 个 `z` + `KEYWORD` + 100 个 `y`，窗口 start 正好落在 emoji 的 low surrogate 上）。
2. **关键词长度用例补 code point 维度**（`@Order(12)`）：`"😀".repeat(30)` 的 UTF-16 长度为 60，但只有 30 个 code point，必须 200；`"😀".repeat(60)` 才是 60 个字符，返回 400「搜索关键词不能超过 50 个字符」。原有 `"a".repeat(51)`（ASCII）用例保留。
3. **大小写不敏感集成用例**（`@Order(13)`）：关键词 `JAVA`/`Java` 都能命中标题为 `java 入门` 的帖子（SQL `LOWER` + service 侧 `Locale.ROOT`）。
4. **既有接口不新增 `"snippet": null` 字段**（`PostSummaryResponse.java:3,48`）：`snippet` 加 `@JsonInclude(JsonInclude.Include.NON_NULL)`，列表/我的帖子/收藏/详情 JSON 与加字段前逐字节一致；`@Order(14)` 断言 `GET /api/posts` 与 `GET /api/posts/{id}` 的原始 JSON 不含 `"snippet"`，搜索标题命中仍显式返回 `"snippet":""`。
5. **排序稳定性**（`PostRepository.searchByKeyword`，`PostRepository.java:50`）：`ORDER BY ... p.createTime DESC, p.id DESC`；`createTime` 相同（批量导入/同微秒）时翻页不再重复或漏行。

已知偏差（沿用现有技术债，不阻塞 Task 1）：

- `page` 仍未设上限：`page × size` 很大时 OFFSET 扫描会变慢。与既有的 `GET /api/posts` 列表端点行为一致，属已记录的技术债，本轮不单独修。
- V3 的 `CREATE INDEX` 没有 `CONCURRENTLY`（Flyway 在事务里执行）：建索引期间会阻塞 `posts` 写入，当前数据量与停机窗口可接受；Task 5 的部署冒烟会用 `EXPLAIN`/`\d posts` 验证两个 GIN 索引确实生效。

验证：`mvn -B clean test` → **13 个测试类 / 105 个用例**，BUILD SUCCESS（1 例 POSIX 权限用例在 Windows 上 `Skipped`）；`@Order(11)` 用例在去掉吸附代码时红、加回后绿。

---

## Task 2: 前端搜索文本工具（高亮 + 片段）

**Files:**
- Create: `frontend/src/utils/search.spec.js`
- Create: `frontend/src/utils/search.js`

- [ ] **Step 2-1: 写失败的工具 spec**

Create `frontend/src/utils/search.spec.js`：

```js
import { describe, it, expect } from 'vitest'
import { highlightSegments, buildSnippet } from './search'

function isHighSurrogate(code) {
  return code >= 0xd800 && code <= 0xdbff
}

function isLowSurrogate(code) {
  return code >= 0xdc00 && code <= 0xdfff
}

/** 检测字符串里是否有落单的代理项（切成两半的 emoji/生僻字） */
function hasLoneSurrogate(value) {
  for (let i = 0; i < value.length; i += 1) {
    const code = value.charCodeAt(i)
    if (isHighSurrogate(code)) {
      if (!isLowSurrogate(value.charCodeAt(i + 1))) return true
      i += 1
    } else if (isLowSurrogate(code)) {
      return true
    }
  }
  return false
}

describe('highlightSegments', () => {
  it('空文本或空关键词返回单段，hit 为 false', () => {
    expect(highlightSegments('', 'java')).toEqual([{ text: '', hit: false }])
    expect(highlightSegments('Java 帖子', '')).toEqual([{ text: 'Java 帖子', hit: false }])
    expect(highlightSegments(null, 'java')).toEqual([{ text: '', hit: false }])
  })

  it('命中在中间时切成三段，保留原文大小写', () => {
    expect(highlightSegments('前面 Java 后面', 'java')).toEqual([
      { text: '前面 ', hit: false },
      { text: 'Java', hit: true },
      { text: ' 后面', hit: false }
    ])
  })

  it('大小写不敏感，命中段用原文切片而不是关键词', () => {
    const segments = highlightSegments('JAVA 与 java', 'Java')
    expect(segments.filter((s) => s.hit).map((s) => s.text)).toEqual(['JAVA', 'java'])
    // 大小写混排的多处命中同样按原文输出
    expect(
      highlightSegments('Java JAVA java', 'jAvA')
        .filter((s) => s.hit)
        .map((s) => s.text)
    ).toEqual(['Java', 'JAVA', 'java'])
  })

  it('多处命中时逐段输出，相邻命中之间不产生空段', () => {
    expect(highlightSegments('aa-aa', 'aa')).toEqual([
      { text: 'aa', hit: true },
      { text: '-', hit: false },
      { text: 'aa', hit: true }
    ])
    // 相邻命中（无间隔）也要逐段输出，不能合并或漏掉第二处
    expect(highlightSegments('aaaa', 'aa')).toEqual([
      { text: 'aa', hit: true },
      { text: 'aa', hit: true }
    ])
  })

  it('无命中返回单段', () => {
    expect(highlightSegments('完全无关', 'java')).toEqual([{ text: '完全无关', hit: false }])
  })

  it('关键词里的正则元字符按字面匹配，不抛异常', () => {
    expect(highlightSegments('计算 a+b 与 (x)', 'a+b')).toEqual([
      { text: '计算 ', hit: false },
      { text: 'a+b', hit: true },
      { text: ' 与 (x)', hit: false }
    ])
    const segments = highlightSegments('数组 [0] 越界', '[0]')
    expect(() => highlightSegments('数组 [0] 越界', '[0]')).not.toThrow()
    expect(segments).toEqual([
      { text: '数组 ', hit: false },
      { text: '[0]', hit: true },
      { text: ' 越界', hit: false }
    ])
  })

  it('关键词等于整段时只有一段 hit', () => {
    expect(highlightSegments('Java', 'java')).toEqual([{ text: 'Java', hit: true }])
  })

  it('大小写折叠改变长度时仍按原文下标切片，不丢字符', () => {
    // 'İ'.toLowerCase() === 'i̇'（两个 UTF-16 码元），用折叠串下标切原文会错位
    expect(highlightSegments('İx', 'İ')).toEqual([
      { text: 'İ', hit: true },
      { text: 'x', hit: false }
    ])
    expect(highlightSegments('İİİİİx', 'x')).toEqual([
      { text: 'İİİİİ', hit: false },
      { text: 'x', hit: true }
    ])
    // 分段首尾相接必须还原原文，且命中段是原文而不是折叠结果
    const segments = highlightSegments('İİİİİx', 'x')
    expect(segments.map((s) => s.text).join('')).toBe('İİİİİx')
    expect(segments[1].text).toBe('x')
  })
})

describe('buildSnippet', () => {
  it('清洗 Markdown 标记：标题、引用、列表、行内代码、链接、图片只留文字', () => {
    const snippet = buildSnippet(
      '# 标题\n> 引用\n- 列表项\n`code`\n[链接文字](https://example.com)\n![图片说明](/a.png)',
      ''
    )

    expect(snippet).toBe('标题 引用 列表项 code 链接文字 图片说明')
    expect(snippet).not.toContain('#')
    expect(snippet).not.toContain('](')
  })

  it('代码围栏整段丢弃，避免代码里的符号混进片段', () => {
    const snippet = buildSnippet('说明文字\n```java\nint a = 1;\n```\n结尾', '')

    expect(snippet).toBe('说明文字 结尾')
    expect(snippet).not.toContain('int a')
  })

  it('换行与连续空白折叠为单个空格', () => {
    expect(buildSnippet('第一行\n\n第二行\t\t第三段', '')).toBe('第一行 第二行 第三段')
  })

  it('CRLF 换行同样折叠为单个空格', () => {
    expect(buildSnippet('# 标题\r\n> 引用\r\n- 列表项', '')).toBe('标题 引用 列表项')
    expect(buildSnippet('第一行\r\n\r\n第二行', '')).toBe('第一行 第二行')
  })

  it('空正文返回空串', () => {
    expect(buildSnippet('', 'java')).toBe('')
    expect(buildSnippet(null, 'java')).toBe('')
  })

  it('有命中时以命中为中心截窗口，两端补省略号', () => {
    const content = 'A'.repeat(100) + '关键词' + 'B'.repeat(100)

    const snippet = buildSnippet(content, '关键词', 10)

    expect(snippet.startsWith('…')).toBe(true)
    expect(snippet.endsWith('…')).toBe(true)
    expect(snippet).toContain('关键词')
    // radius=10：单侧最多 10 个字符，窗口远小于清洗前的 203 字符
    expect(snippet.length).toBeLessThan(40)
  })

  it('无命中时取开头窗口，超出长度才补尾部省略号', () => {
    const content = 'C'.repeat(200)

    const snippet = buildSnippet(content, '无关词', 10)

    expect(snippet.startsWith('C')).toBe(true)
    expect(snippet.endsWith('…')).toBe(true)
    expect(buildSnippet('短正文', '无关词', 10)).toBe('短正文')
  })

  it('统计口径不区分大小写：大写关键词也能定位到小写正文', () => {
    const snippet = buildSnippet('前面 java 后面', 'JAVA', 20)

    expect(snippet).toBe('前面 java 后面')
  })

  it('大小写折叠改变长度时片段不出现乱码，窗口下标映射回原文', () => {
    expect(buildSnippet('İİİİİx', 'x', 2)).toBe('…İİx')
    expect(buildSnippet('İx', 'İ', 10)).toBe('İx')
    expect(buildSnippet('İİİİİx', 'x', 2)).not.toMatch(/\uFFFD/)
  })

  it('进一步清洗强调、分割线、表格、原始 HTML 与自动链接', () => {
    // 分割线只按整行识别：行尾的 --- 不是分割线，按普通文本保留（与 markdown-it 一致）
    expect(buildSnippet('**粗体** ---', '')).toBe('粗体 ---')
    expect(buildSnippet('~~删除~~ __强调__ *斜体* _倾斜_', '')).toBe('删除 强调 斜体 倾斜')
    expect(buildSnippet('前\n***\n中\n___\n后', '')).toBe('前 中 后')
    expect(buildSnippet('| 名称 | 数量 |\n| --- | --- |\n| 苹果 | 3 |', '')).toBe(
      '名称 数量 苹果 3'
    )
    expect(buildSnippet('<div class="x">红色</div>文本', '')).toBe('红色 文本')
    expect(buildSnippet('<https://a.b>', '')).toBe('https://a.b')
  })

  it('行内代码优先保护，代码里的强调与管道按字面保留，下划线遵循词内规则', () => {
    // 代码外的 _ 在词内不构成强调（CommonMark 的 intraword 规则），关键词仍可命中
    expect(buildSnippet('示例 a_b_c 说明', 'a_b_c', 5)).toBe('示例 a_b_c 说明')
    // 行内代码内容必须在强调/链接/HTML/管道清洗之前保护起来
    expect(buildSnippet('示例 `a_b_c` 说明', 'a_b_c', 5)).toBe('示例 a_b_c 说明')
    expect(buildSnippet('`a**b**c`', '')).toBe('a**b**c')
    expect(buildSnippet('`a || b`', '')).toBe('a || b')
    // 占位符不能泄漏到输出
    expect(buildSnippet('`a || b`', '')).not.toContain('\u0000')
    // 代码里的关键词在清洗后的片段里仍能被高亮
    const snippet = buildSnippet('示例 `a_b_c` 说明', 'a_b_c', 5)
    expect(highlightSegments(snippet, 'a_b_c').filter((s) => s.hit).map((s) => s.text)).toEqual([
      'a_b_c'
    ])
  })

  it('带标题的链接与图片只保留文字', () => {
    expect(buildSnippet('[文字](https://e.com "标题")', '')).toBe('文字')
    expect(buildSnippet("[文字](https://e.com '标题')", '')).toBe('文字')
    expect(buildSnippet('[文字](https://e.com (标题))', '')).toBe('文字')
    expect(buildSnippet('![图片](https://e.com/a.png "标题")', '')).toBe('图片')
  })

  it('闭合围栏不短于起始围栏，闭合后继续保留后续正文', () => {
    expect(buildSnippet('说明\n```js\ncode\n````\n后文', '')).toBe('说明 后文')
  })

  it('反引号围栏的 info string 含反引号时按单行行内代码处理', () => {
    // markdown-it 把 4 个反引号包住 `code` 视为行内代码，内容是字面量的 `code`
    expect(buildSnippet('```` `code` ````', '')).toBe('`code`')
  })

  it('分割线与表格只按整行/整块识别，避免误伤普通文本', () => {
    // 只有整行都是 - - - / *** / ___ 才是分割线
    expect(buildSnippet('5 - - - 6', '')).toBe('5 - - - 6')
    expect(buildSnippet('前\n- - -\n后', '')).toBe('前 后')
    // 没有分隔行的普通文本不按表格折叠管道
    expect(buildSnippet('前 | 中 | 后', '')).toBe('前 | 中 | 后')
    // 有分隔行才是表格块，块内（含单列表格）才折叠管道
    expect(buildSnippet('| 名称 | 数量 |\n| --- | --- |\n| 苹果 | 3 |', '')).toBe(
      '名称 数量 苹果 3'
    )
    expect(buildSnippet('| 表头 |\n| --- |\n| 值 |', '')).toBe('表头 值')
  })

  it('邮件自动链接保留地址，不被当作原始 HTML 标签删除', () => {
    expect(buildSnippet('<foo@bar.example.com>', '')).toBe('foo@bar.example.com')
  })

  it('4 个及以上反引号的围栏与未闭合围栏都整段丢弃', () => {
    expect(buildSnippet('说明\n````md\n```js\ncode\n```\n````\n结尾', '')).toBe('说明 结尾')
    expect(buildSnippet('开头\n```js\nint a = 1;', '')).toBe('开头')
    expect(buildSnippet('开头\n````\nint a = 1;', '')).toBe('开头')
  })

  it('链接 URL 里的一层成对括号不会留下多余的右括号', () => {
    expect(buildSnippet('[text](https://e.com/a_(b))', '')).toBe('text')
    expect(buildSnippet('![图](https://e.com/a_(b))', '')).toBe('图')
  })

  it('radius 非有限值回退默认值，负数按 0 处理', () => {
    const long = 'A'.repeat(100) + '关键词' + 'B'.repeat(100)

    expect(buildSnippet(long, '关键词', NaN)).toBe(buildSnippet(long, '关键词'))
    expect(buildSnippet(long, '关键词', Infinity)).toBe(buildSnippet(long, '关键词'))
    expect(buildSnippet(long, '关键词', undefined)).toBe(buildSnippet(long, '关键词'))
    expect(buildSnippet('前面 keyword 后面', 'keyword', -5)).toBe('…keyword…')
  })

  it('radius 为 0 时只保留关键词本身，无命中返回空串', () => {
    expect(buildSnippet('前面 keyword 后面', 'keyword', 0)).toBe('…keyword…')
    expect(buildSnippet('keyword 后面', 'keyword', 0)).toBe('keyword…')
    expect(buildSnippet('x'.repeat(10), '无命中', 0)).toBe('')
  })

  it('命中在开头或结尾时窗口收敛到文本边界', () => {
    expect(buildSnippet('关键词' + 'B'.repeat(100), '关键词', 5)).toBe('关键词BBBBB…')
    expect(buildSnippet('A'.repeat(100) + '关键词', '关键词', 5)).toBe('…AAAAA关键词')
  })

  it('窗口边界不劈开代理对', () => {
    const snippet = buildSnippet('a' + '😀'.repeat(5), '', 2)

    expect(snippet).toBe('a😀😀…')
    expect(hasLoneSurrogate(snippet)).toBe(false)
  })
})
```

Run:

```bash
cd /e/JavaSpace/forum/frontend && pnpm exec vitest run src/utils/search.spec.js
```

Expected: **FAIL** —— `Failed to resolve import "./search"`（模块还不存在）。

- [ ] **Step 2-2: 实现 utils/search.js**

Create `frontend/src/utils/search.js`：

```js
/**
 * 搜索关键词的高亮与片段工具。
 *
 * 为什么不用正则：关键词来自用户输入，`(`、`[`、`*`、`?` 等字符会改变正则语义，
 * 轻则高亮错位，重则 new RegExp 抛异常；拼正则前还得再转义一遍——那等于把
 * 「怎么切字符串」同时写成两套实现。indexOf 循环只依赖大小写折叠，没有元字符问题。
 *
 * 为什么返回 { text, hit } 数组而不是 HTML 字符串：模板里用文本节点 + <mark> 渲染，
 * 天然转义，不需要 v-html，也就没有反射型 XSS 的口子（搜索词会原样出现在 URL 里，
 * 是典型的可控输入）。
 */

/**
 * 大小写折叠并记录下标映射。
 *
 * 不能直接 `source.toLowerCase()` 后拿折叠串下标切原文：Unicode 折叠可能改变 UTF-16
 * 长度（例如 `'İ'.toLowerCase()` 是 'i' + 组合点，两个码元），长度一变下标就错位，
 * 切片会切出乱码。这里逐码点折叠，记录每个折叠码元对应的原文起止下标：`map[k]` 是
 * 该折叠码元在原文里的起点，`endMap[k]` 是它所在原文码点的终点；命中区间的右端用
 * `endMap[末位]` 回推。`map` 末尾额外压一个原文长度哨兵，当前切片不读它。
 */
function foldWithMap(text) {
  const source = String(text ?? '')
  const parts = []
  const map = []
  const endMap = []
  for (let i = 0; i < source.length; ) {
    const width = source.codePointAt(i) > 0xffff ? 2 : 1
    const lower = source.slice(i, i + width).toLowerCase()
    for (let k = 0; k < lower.length; k += 1) {
      map.push(i)
      endMap.push(i + width)
    }
    parts.push(lower)
    i += width
  }
  map.push(source.length)
  return { folded: parts.join(''), map, endMap }
}

/** 大小写折叠（关键词只需要折叠结果；与 foldWithMap 逐码点口径一致） */
function fold(text) {
  return foldWithMap(text).folded
}

function isHighSurrogate(code) {
  return code >= 0xd800 && code <= 0xdbff
}

function isLowSurrogate(code) {
  return code >= 0xdc00 && code <= 0xdfff
}

/**
 * 把文本按关键词切成若干段，命中段 hit=true。
 * 空文本、空关键词、无命中都返回单段（hit=false），调用方不必判空。
 * 命中区间通过 foldWithMap 的映射回到原文下标，因此 `İ` 这类折叠后变长的字符也能正确高亮。
 */
export function highlightSegments(text, keyword) {
  const source = String(text ?? '')
  const kw = String(keyword ?? '')
  if (!source || !kw) return [{ text: source, hit: false }]

  const { folded, map, endMap } = foldWithMap(source)
  const needle = fold(kw)
  if (!needle) return [{ text: source, hit: false }]

  const segments = []
  let from = 0
  let index = folded.indexOf(needle)
  while (index !== -1) {
    const start = map[index]
    const end = endMap[index + needle.length - 1]
    if (start > from) segments.push({ text: source.slice(from, start), hit: false })
    segments.push({ text: source.slice(start, end), hit: true })
    from = end
    index = folded.indexOf(needle, index + needle.length)
  }
  // 无命中时 from 仍是 0，这里会把整段原文作为未命中段返回，与显式判空等价
  if (from < source.length) segments.push({ text: source.slice(from), hit: false })
  return segments
}

/** 片段窗口半径：命中位置左右各保留的字符数 */
const DEFAULT_RADIUS = 40
const ELLIPSIS = '…'

/** 行内代码占位符：正文入口先剥掉 NUL，因此 `\u0000<编号>\u0000` 不会与内容冲突 */
const PLACEHOLDER = '\u0000'

/** 围栏：3 个以上同字符、缩进不超过 3 空格；info string 只影响能否识别（见 fenceOpening） */
const FENCE_OPEN = /^ {0,3}(`{3,}|~{3,})(.*)$/
const FENCE_CLOSE = /^ {0,3}(`{3,}|~{3,})[ \t]*$/
/** 分割线：整行只有空格与 3 个以上 - / * / _（允许中间有空格） */
const THEMATIC_BREAK = /^ {0,3}(?:(?:\*[ \t]*){3,}|(?:-[ \t]*){3,}|(?:_[ \t]*){3,})$/
/** 表格分隔行：[可选 |] 单元格（冒号 + 连字符）[| 单元格…] [可选 |] */
const TABLE_SEPARATOR = /^ {0,3}\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$/
/** 图片先于链接：alt 文本保留，可选的 "标题"/'标题'/(标题) 一并丢弃 */
const IMAGE = /!\[([^\]]*)\]\(\s*(?:[^()\s]|\([^()]*\))*(?:\s+(?:"[^"]*"|'[^']*'|\([^()]*\)))?\s*\)/g
/** 链接：只留文字（URL 允许一层成对括号，可选标题同样丢弃） */
const LINK = /\[([^\]]*)\]\(\s*(?:[^()\s]|\([^()]*\))*(?:\s+(?:"[^"]*"|'[^']*'|\([^()]*\)))?\s*\)/g
/** 自动链接：<https://...> / <mailto:...> 与 <a@b.c>，保留链接文本 */
const AUTOLINK_URL = /<((?:https?:\/\/|mailto:)[^>\s]+)>/gi
const AUTOLINK_EMAIL = /<([^<>\s@]+@[^<>\s@]+\.[^<>\s@]+)>/g
/** 原始 HTML 标签：标签名后必须是空白、/ 或 >，避免把 <foo@bar.example.com> 当成标签删掉 */
const HTML_TAG = /<\/?[a-zA-Z][a-zA-Z0-9-]*(?:\s[^>]*)?\/?>/g

/**
 * 识别围栏起始行。反引号围栏的 info string 里不允许再出现反引号（CommonMark），
 * 否则这一行不是围栏而是单行行内代码，例如 ```` `code` ````。
 */
function fenceOpening(line) {
  const match = FENCE_OPEN.exec(line)
  if (!match) return null
  if (match[1][0] === '`' && match[2].includes('`')) return null
  return { char: match[1][0], length: match[1].length }
}

/** 闭合围栏必须与起始围栏同字符，长度不短于起始围栏，且除缩进/尾随空白外没有内容 */
function fenceClosing(line, opening) {
  const match = FENCE_CLOSE.exec(line)
  return Boolean(match) && match[1][0] === opening.char && match[1].length >= opening.length
}

/** 先按行扫围栏代码块：闭合段整段丢弃；未闭合段丢弃到文末（近似处理） */
function stripFencedCode(lines) {
  const kept = []
  for (let i = 0; i < lines.length; ) {
    const opening = fenceOpening(lines[i])
    if (!opening) {
      kept.push(lines[i])
      i += 1
      continue
    }
    let close = i + 1
    while (close < lines.length && !fenceClosing(lines[close], opening)) close += 1
    if (close >= lines.length) break
    i = close + 1
  }
  return kept
}

function isTableSeparator(line) {
  return line.includes('|') && TABLE_SEPARATOR.test(line)
}

/**
 * 按行去掉整行分割线与表格块。
 * 只有整行都是 - / * / _ 才是分割线，`5 - - - 6` 这种行内连字符要原样保留；
 * 只有「表头行 + 分隔行」才进入表格块，块内管道符折叠为空格，块外管道符原样保留。
 */
function stripBlockMarkers(text) {
  const lines = text.split('\n')
  const kept = []
  for (let i = 0; i < lines.length; ) {
    if (THEMATIC_BREAK.test(lines[i])) {
      i += 1
      continue
    }
    if (i + 1 < lines.length && lines[i].includes('|') && isTableSeparator(lines[i + 1])) {
      kept.push(lines[i].replace(/\|/g, ' '))
      i += 2
      while (i < lines.length && lines[i].includes('|')) {
        kept.push(lines[i].replace(/\|/g, ' '))
        i += 1
      }
      continue
    }
    kept.push(lines[i])
    i += 1
  }
  return kept.join('\n')
}

/**
 * 行内代码用占位符保护起来，避免后续的链接/HTML/强调/管道清洗误伤代码里的字面量。
 * 返回 `{ text, codes }`，`codes[n]` 是第 n 段代码的内容（已按 CommonMark 去掉两端各
 * 一个空格）。未闭合的反引号串原样保留；空行是段落边界，行内代码不跨段配对（近似处理）。
 */
function protectInlineCode(text) {
  const codes = []
  let out = ''
  let i = 0
  while (i < text.length) {
    if (text[i] !== '`') {
      out += text[i]
      i += 1
      continue
    }
    let width = 0
    while (text[i + width] === '`') width += 1
    let cursor = i + width
    let close = -1
    while (cursor < text.length) {
      if (text[cursor] === '\n' && text[cursor + 1] === '\n') break
      if (text[cursor] !== '`') {
        cursor += 1
        continue
      }
      let run = 0
      while (text[cursor + run] === '`') run += 1
      if (run === width) {
        close = cursor
        break
      }
      cursor += run
    }
    if (close === -1) {
      out += text.slice(i, i + width)
      i += width
      continue
    }
    let inner = text.slice(i + width, close)
    if (inner.length >= 2 && inner.startsWith(' ') && inner.endsWith(' ') && inner.trim() !== '') {
      inner = inner.slice(1, -1)
    }
    out += PLACEHOLDER + codes.length + PLACEHOLDER
    codes.push(inner)
    i = close + width
  }
  return { text: out, codes }
}

/** 还原行内代码占位符；兜底剥掉任何残留的 NUL，保证占位符不泄漏到片段里 */
function restoreInlineCode(text, codes) {
  return text
    .replace(/\u0000(\d+)\u0000/g, (_, index) => codes[Number(index)] ?? '')
    .replace(/\u0000/g, '')
}

/**
 * 轻量清洗 Markdown，只保留适合「一行片段」展示的纯文本。
 *
 * 这是面向搜索片段的近似清洗，不是完整的 Markdown 解析器：setext 标题、嵌套列表、
 * 四空格缩进的代码块、嵌套引用（只去一层 `>`）、转义写法等边角情况不保证与渲染结果
 * 一致。这里刻意不走 markdown-it 的渲染流程——它已经是帖子详情页的渲染依赖，但本工具
 * 只需要纯文本；先渲染成 <p>/<strong> 再剥标签更慢，也容易漏标签。
 *
 * 步骤顺序有依赖：先按行扫掉围栏代码块、整行分割线与表格块；行内代码用占位符先保护
 * 起来（避免代码里的强调/管道被误伤），图片先于链接，自动链接先于通用 HTML 标签；最后
 * 还原代码内容并折叠空白。
 */
function plainText(markdown) {
  // NUL 只用于内部占位：入口先剥掉正文里的 NUL，保证占位符不会与内容冲突
  const source = String(markdown ?? '')
    .replace(/\u0000/g, '')
    .replace(/\r\n?/g, '\n')
  const { text, codes } = protectInlineCode(stripFencedCode(source.split('\n')).join('\n'))
  const cleaned = stripBlockMarkers(text)
    // 图片先于链接：只留 alt 文本（URL 允许一层成对括号，可选标题一并丢弃）
    .replace(IMAGE, '$1')
    // 链接：只留文字（URL 里的一层括号不会被截断，不留下多余的 ')'）
    .replace(LINK, '$1')
    // 自动链接：保留链接文本，必须先于通用 HTML 标签剥离
    .replace(AUTOLINK_URL, '$1')
    .replace(AUTOLINK_EMAIL, '$1')
    // 原始 HTML 标签：替换为空格，保留标签之间的可见文字
    .replace(HTML_TAG, ' ')
    // 强调符号：先成对双标记，再成对单标记（粗体/斜体/删除线）。
    // 下划线遵循 CommonMark 的 intraword 规则（`a_b_c` 不是强调）；星号允许词内强调。
    .replace(/~~(?=\S)([^~]+?)(?<=\S)~~/g, '$1')
    .replace(/\*\*(?=\S)([^*]+?)(?<=\S)\*\*/g, '$1')
    .replace(/(?<![\p{L}\p{N}])__(?=\S)([^_]+?)(?<=\S)__(?![\p{L}\p{N}])/gu, '$1')
    .replace(/\*(?=\S)([^*\n]+?)(?<=\S)\*/g, '$1')
    .replace(/(?<![\p{L}\p{N}])_(?=\S)([^_\n]+?)(?<=\S)_(?![\p{L}\p{N}])/gu, '$1')
    // 标题 / 引用 / 无序列表 / 有序列表前缀（逐行；只去一层引用，嵌套引用是近似局限）
    .replace(/^\s{0,3}#{1,6}\s+/gm, '')
    .replace(/^\s{0,3}>\s?/gm, '')
    .replace(/^\s{0,3}(?:[-*+]|\d+\.)\s+/gm, '')
  // 行内代码最后还原（强调/链接/HTML 清洗已完成，代码里的字面量不会再被误伤），
  // 再折叠空白，保证片段始终是单行
  return restoreInlineCode(cleaned, codes).replace(/\s+/g, ' ').trim()
}

/**
 * 生成一行搜索片段：先清洗 Markdown，再按关键词定位窗口。
 * 有命中时以第一处命中为中心；无命中时取开头窗口（标题命中也能看到一点正文上下文）。
 * 截断处补省略号；窗口越界时自动收敛到文本边界，并吸附到 code point 边界，避免劈开
 * emoji/生僻字。radius 非有限值（undefined/NaN/Infinity）时回退 DEFAULT_RADIUS，负数
 * 按 0 处理；radius = 0 时无命中返回空串，有命中只保留关键词本身（两侧仍按是否截断补
 * 省略号）。
 */
export function buildSnippet(content, keyword, radius = DEFAULT_RADIUS) {
  const text = plainText(content)
  if (!text) return ''

  const limit = Number.isFinite(radius) ? Math.max(0, Math.floor(radius)) : DEFAULT_RADIUS
  const needle = fold(keyword)
  let pos = -1
  let hitEnd = -1
  if (needle) {
    const { folded, map, endMap } = foldWithMap(text)
    const index = folded.indexOf(needle)
    if (index !== -1) {
      pos = map[index]
      hitEnd = endMap[index + needle.length - 1]
    }
  }

  let start = 0
  let end = 0
  if (pos === -1) {
    end = Math.min(text.length, limit * 2)
  } else {
    start = Math.max(0, pos - limit)
    end = Math.min(text.length, hitEnd + limit)
  }
  // 命中窗口端点吸附到 code point 边界：start 落在低位代理上时回退到配对高位，
  // end 前一个码元是高位代理（说明切开了代理对）时把低位代理一并纳入。
  if (start > 0 && isLowSurrogate(text.charCodeAt(start))) start -= 1
  if (end > 0 && end < text.length && isHighSurrogate(text.charCodeAt(end - 1))) end += 1

  if (start === 0 && end === 0) return ''
  const head = start > 0 ? ELLIPSIS : ''
  const tail = end < text.length ? ELLIPSIS : ''
  return head + text.slice(start, end) + tail
}
```

Run:

```bash
cd /e/JavaSpace/forum/frontend && pnpm exec vitest run src/utils/search.spec.js
```

Expected: **PASS** —— `30 passed`。

- [ ] **Step 2-3: 提交搜索文本工具**

Run:

```bash
cd /e/JavaSpace/forum
git add frontend/src/utils/search.js frontend/src/utils/search.spec.js
git commit -m "feat: 新增搜索关键词高亮与片段工具"
```

Expected: 提交成功；`git status` 干净。

### Task 2 质量修复记录（实施偏差）

质量评审后在同一分支追加一次修复提交（`fix: 修正搜索工具的大小写折叠与片段清洗边界`），只改动 `frontend/src/utils/search.js`、`frontend/src/utils/search.spec.js` 与本计划文档：

1. **折叠索引对齐**：`String.prototype.toLowerCase()` 可能改变 UTF-16 长度（`'İ'.toLowerCase()` 得到 'i' + 组合点两个码元），原实现用折叠串下标切原文会错位、切出乱码。改为 `foldWithMap(source) -> { folded, map, endMap }`：逐码点折叠并记录每个折叠码元对应的原文起止下标，`indexOf` 命中后把区间映射回原文再切片；`buildSnippet` 的 `pos` 同样映射。
2. **片段清洗补全**：`plainText` 增加强调符号（`**`/`__`/`~~` 与成对 `*`/`_`）、水平分割线、表格管道与分隔行、原始 HTML 标签、自动链接；围栏支持 4 个以上反引号，未闭合围栏丢弃到文末。顺序为围栏 → 行内代码 → 分割线/表格分隔行 → 图片 → 链接 → 自动链接 → HTML → 强调 → 行前缀 → 管道 → 空白折叠。**局限：这是面向「一行片段」的近似清洗，不是完整 Markdown 解析器**，嵌套、转义、setext 标题等边角写法不保证与渲染结果一致。
3. **radius 校验与代理对安全**：非有限值（`undefined`/`NaN`/`Infinity`）回退 `DEFAULT_RADIUS = 40`，负数按 0；窗口端点吸附到 code point 边界，不劈开 emoji/生僻字。`radius = 0` 时无命中返回空串，有命中只保留关键词本身（两侧仍按是否截断补省略号）。
4. **链接 URL 平衡括号**：链接/图片正则允许 URL 内一层成对括号，`[text](https://e.com/a_(b))` 不再留下多余的 `)`。
5. **测试与注释**：相邻命中用 `'aaaa'` + `'aa'` 覆盖；补 CRLF、大小写、代理对、radius 边界用例；删除可证明等价的多余分支（无命中时 `from` 仍为 0，末尾分支会整段返回原文）；更正文中关于 markdown-it 的说明（它已是渲染依赖，本工具只是不需要渲染流程）。

结果：`frontend/src/utils/search.spec.js` 共 **24 例**；前端全量 **26 文件 / 235 例** 通过。修复测试先在旧实现上失败（`İx` 被整段高亮、`buildSnippet('İİİİİx','x',2)` 只剩 `…`），改为映射下标后转绿。

### Task 2 二次质量修复记录（实施偏差）

delta 复审确认上一轮三项问题已修复，但发现 `plainText` 的近似清洗仍有误伤；按「先写失败测试、再修实现」在同一分支追加一次修复提交（`fix: 修正搜索片段清洗的代码块与表格误伤`），只改动 `frontend/src/utils/search.js`、`frontend/src/utils/search.spec.js` 与本计划文档：

1. **行内代码优先保护（R1）**：`plainText` 先按行扫掉围栏，随后立即用 `\u0000<编号>\u0000` 占位符把行内代码内容整体保护起来，等链接/HTML/强调清洗完再还原，因此 `` `a**b**c` ``、`` `a || b` ``、`` `a_b_c` `` 中的字面量不再被误伤；`_` 强调补上 CommonMark 的 intraword 限制（开标记前、闭标记后不能是字母/数字），`示例 a_b_c 说明` 不再变成 `示例 abc 说明`；`*` 仍允许词内强调（与 markdown-it 一致）。「只去掉标记，保留内容」的注释现在与实现一致。入口先剥掉正文里的 NUL、还原后再兜底清一次，占位符不会泄漏到片段。
2. **带标题链接（R2）**：链接/图片正则容许目的地址后跟可选空白 + `"标题"` / `'标题'` / `(标题)`，四种写法（含图片）都只保留可见文字，不再泄漏原始 Markdown。
3. **围栏闭合（R3/R4）**：围栏改为按行扫描，闭合行必须与起始行同字符且长度不短于起始行；输入首行 `说明`、三反引号围栏、`code`、四反引号的闭合行与 `后文` 时，只丢代码、保留 `后文`。反引号围栏的 info string 含反引号时不算围栏，4 个反引号包住 `code` 的单行写法按行内代码得到 `` `code` ``。
4. **减少过度清洗（R5）**：分割线改为整行锚定（`5 - - - 6` 原样保留）；管道符只在「表头行 + 分隔行」的表格块内折叠（`前 | 中 | 后` 原样保留）；原始 HTML 标签名后要求空白、`/` 或 `>`，并新增邮件自动链接，`<foo@bar.example.com>` 输出 `foo@bar.example.com`。
5. **注释（R6）**：更正 `foldWithMap` 文档中「`map[folded.length]` 用于切片」的说法（右端实际用 `endMap`，末尾哨兵不参与切片）；`plainText` 头部注释继续列出 setext 标题、嵌套列表、四空格缩进代码块、嵌套引用、转义等近似局限。
6. **既有断言调整**：`buildSnippet('**粗体** ---', '')` 的期望由 `粗体` 改为 `粗体 ---`。整行锚定后行尾的 `---` 不是分割线，与 markdown-it 渲染一致；这是「减少过度清洗」带来的预期行为变化，已随用例固化。

结果：`frontend/src/utils/search.spec.js` 共 **30 例**；前端全量 **26 文件 / 241 例** 通过。新增与调整的用例先在旧实现上失败 **7 例**（R1 行内代码与词内下划线、R2 标题链接、R3 长闭合围栏、R4 单行行内代码、R5 分割线/表格/邮件链接、调整期望的 `**粗体** ---`），修复实现后转绿。

---

## Task 3: 顶栏搜索入口与搜索结果页

**Files:**
- Modify: `frontend/src/api/index.js:105`
- Modify: `frontend/src/api/index.spec.js:64,164`
- Create: `frontend/src/components/HeaderSearch.spec.js`
- Create: `frontend/src/components/HeaderSearch.vue`
- Create: `frontend/src/views/SearchView.spec.js`
- Create: `frontend/src/views/SearchView.vue`
- Modify: `frontend/src/router/index.js:10`
- Modify: `frontend/src/components/PostListItem.spec.js:80`
- Modify: `frontend/src/components/PostListItem.vue:6,8-11,15,36,38,99-102`
- Modify: `frontend/src/App.vue:9,53,108-115,132-136`

- [ ] **Step 3-1: 写失败的 postApi.search 请求路径用例**

Modify `frontend/src/api/index.spec.js:64`，把动态 import 改成同时取出 `postApi`：

```js
// 动态 import：确保上面的 mock 在模块求值前已生效
const { tagApi, userApi, postApi } = await import('./index.js')
```

再在 `src/api/index.spec.js:164`（`describe('userApi 请求路径')` 之前）插入：

```js
describe('postApi 搜索请求路径', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('search 请求 GET /posts/search 并透传 keyword/page/size', () => {
    postApi.search({ keyword: 'java', page: 0, size: 10 })

    expect(apiGet).toHaveBeenCalledWith('/posts/search', {
      params: { keyword: 'java', page: 0, size: 10 }
    })
  })
})
```

Run:

```bash
cd /e/JavaSpace/forum/frontend && pnpm exec vitest run src/api/index.spec.js
```

Expected: **FAIL** —— `postApi.search is not a function`（其余 12 例仍绿）。

- [ ] **Step 3-2: 给 postApi 加 search**

Modify `frontend/src/api/index.js:105`（`list` 之后）：

```js
export const postApi = {
  list: (params) => api.get('/posts', { params }),
  // 全文搜索：标题命中优先、时间倒序；keyword 必填（后端 400 校验）
  search: (params) => api.get('/posts/search', { params }),
  detail: (id) => api.get(`/posts/${id}`),
```

Run:

```bash
cd /e/JavaSpace/forum/frontend && pnpm exec vitest run src/api/index.spec.js
```

Expected: **PASS** —— `13 passed`。

- [ ] **Step 3-3: 写失败的 HeaderSearch spec**

Create `frontend/src/components/HeaderSearch.spec.js`：

```js
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { reactive, nextTick } from 'vue'

const push = vi.fn()
const route = reactive({ name: 'home', query: {}, fullPath: '/', path: '/' })
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push }) }))

import HeaderSearch from './HeaderSearch.vue'

/**
 * 可手动驱动的 matchMedia 桩。
 *
 * jsdom 不实现 window.matchMedia，useResponsive 会走「缺失」分支返回 false ——
 * 窄屏分支永远测不到。这里返回受控的 mql，并记录 add/remove 的调用，便于断言。
 */
function installMatchMedia(matches) {
  const mql = {
    matches,
    media: '(max-width: 768px)',
    addEventListener: vi.fn(),
    removeEventListener: vi.fn()
  }
  window.matchMedia = vi.fn(() => mql)
  return mql
}

const stubs = {
  'el-input': {
    props: ['modelValue', 'placeholder', 'maxlength'],
    emits: ['update:modelValue'],
    template:
      '<div class="stub-input"><input class="search-input" :value="modelValue" :placeholder="placeholder" :maxlength="maxlength" @input="$emit(\'update:modelValue\', $event.target.value)" /><slot name="append" /></div>'
  },
  'el-button': { template: '<button class="stub-btn"><slot /></button>' },
  'el-icon': { template: '<i><slot /></i>' }
}

const wrappers = []

function mountSearch() {
  const wrapper = mount(HeaderSearch, { global: { stubs } })
  wrappers.push(wrapper)
  return wrapper
}

describe('HeaderSearch', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    route.name = 'home'
    route.query = {}
    route.fullPath = '/'
    installMatchMedia(false)
  })

  afterEach(() => {
    wrappers.forEach((w) => w.unmount())
    wrappers.length = 0
  })

  it('桌面渲染搜索输入框，回车跳到搜索页', async () => {
    const wrapper = mountSearch()

    const input = wrapper.find('input.search-input')
    expect(input.attributes('placeholder')).toBe('搜索帖子')
    expect(input.attributes('maxlength')).toBe('50')

    await input.setValue('java')
    await input.trigger('keyup.enter')

    expect(push).toHaveBeenCalledWith({ name: 'search', query: { q: 'java' } })
  })

  it('空白关键词不跳转', async () => {
    const wrapper = mountSearch()
    const input = wrapper.find('input.search-input')

    await input.setValue('   ')
    await input.trigger('keyup.enter')

    expect(push).not.toHaveBeenCalled()
  })

  it('已在搜索页且关键词相同时不重复跳转', async () => {
    route.name = 'search'
    route.query = { q: 'java' }
    route.fullPath = '/search?q=java'
    const wrapper = mountSearch()

    const input = wrapper.find('input.search-input')
    expect(input.element.value).toBe('java')
    await input.trigger('keyup.enter')

    expect(push).not.toHaveBeenCalled()
  })

  it('窄屏只渲染图标按钮，点击进入搜索页', async () => {
    installMatchMedia(true)
    const wrapper = mountSearch()

    expect(wrapper.find('input.search-input').exists()).toBe(false)
    const button = wrapper.find('.stub-btn')
    expect(button.exists()).toBe(true)

    await button.trigger('click')

    expect(push).toHaveBeenCalledWith({ name: 'search' })
  })

  it('地址栏 ?q= 变化时同步输入框', async () => {
    route.name = 'search'
    route.query = { q: 'spring' }
    route.fullPath = '/search?q=spring'
    const wrapper = mountSearch()

    expect(wrapper.find('input.search-input').element.value).toBe('spring')

    route.query = { q: 'vue' }
    route.fullPath = '/search?q=vue'
    await nextTick()

    expect(wrapper.find('input.search-input').element.value).toBe('vue')
  })
})
```

Run:

```bash
cd /e/JavaSpace/forum/frontend && pnpm exec vitest run src/components/HeaderSearch.spec.js
```

Expected: **FAIL** —— `Failed to resolve import "./HeaderSearch.vue"`。

- [ ] **Step 3-4: 实现 HeaderSearch.vue**

Create `frontend/src/components/HeaderSearch.vue`：

```vue
<script setup>
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { useResponsive } from '@/composables/useResponsive'

const route = useRoute()
const router = useRouter()
const { isMobile } = useResponsive()

const keyword = ref('')

function submit() {
  const q = keyword.value.trim()
  // 与服务端一致：按 Unicode 码点计 1~50（maxlength 只按 UTF-16 码元截断，
  // emoji 等代理对字符会被少算，所以这里再按码点挡一次）
  if (!q || Array.from(q).length > 50) return
  // 已在搜索页且关键词不变：不重复 push，避免历史记录里堆同样地址
  if (route.name === 'search' && route.query.q === q) return
  router.push({ name: 'search', query: { q } })
}

/**
 * 清空输入框：ElInput 会先清空 v-model 再 emit('clear')，此时 submit() 只会看到空串。
 * 设计意图是「清空 = 回到搜索页的空态」：只在搜索页把地址栏的 q 去掉；其它页面不跳转。
 */
function onClear() {
  if (route.name !== 'search') return
  router.push({ name: 'search' })
}

/** 窄屏没有输入框：直接把用户送到搜索页，在页面里输入 */
function openSearchPage() {
  if (route.name === 'search') return
  router.push({ name: 'search' })
}

// 顶栏输入框与地址栏保持一致：页面内搜索、浏览器前进后退、离开搜索页都会同步
watch(
  () => route.query.q,
  (q) => {
    keyword.value = typeof q === 'string' ? q : ''
  },
  { immediate: true }
)
</script>

<template>
  <div class="header-search">
    <el-input
      v-if="!isMobile"
      v-model="keyword"
      placeholder="搜索帖子"
      maxlength="50"
      clearable
      class="search-box"
      @keyup.enter="submit"
      @clear="onClear"
    >
      <template #append>
        <el-button :icon="Search" aria-label="搜索" @click="submit" />
      </template>
    </el-input>
    <el-button
      v-else
      :icon="Search"
      circle
      text
      aria-label="搜索"
      @click="openSearchPage"
    />
  </div>
</template>

<style scoped>
/* 宽度固定在 220px：顶栏用 flex-start + .nav-actions 的 margin-left:auto 分成左右两组，
   输入框自适应会把导航挤到换行，固定宽度则不会随内容跳动 */
.search-box {
  width: 220px;
}
</style>
```

Run:

```bash
cd /e/JavaSpace/forum/frontend && pnpm exec vitest run src/components/HeaderSearch.spec.js
```

Expected: **PASS** —— `5 passed`。

- [ ] **Step 3-5: 写失败的 SearchView spec**

Create `frontend/src/views/SearchView.spec.js`：

```js
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { reactive } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

const replace = vi.fn()
const push = vi.fn()
// 真实路由对象是响应式的：computed(keyword) 与 watch 都依赖它，故用 reactive 模拟
const route = reactive({ name: 'search', query: {}, fullPath: '/search', path: '/search' })
vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ replace, push })
}))
vi.mock('@/api', () => ({ postApi: { search: vi.fn(), like: vi.fn() } }))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn() } }))

import SearchView from './SearchView.vue'
import { postApi } from '@/api'

const stubs = {
  'el-card': { template: '<div><slot name="header" /><slot /></div>' },
  'el-input': {
    props: ['modelValue', 'placeholder', 'maxlength'],
    emits: ['update:modelValue'],
    template:
      '<div class="stub-input"><input class="search-input" :value="modelValue" :placeholder="placeholder" :maxlength="maxlength" @input="$emit(\'update:modelValue\', $event.target.value)" /><slot name="append" /></div>'
  },
  'el-button': { template: '<button class="stub-btn"><slot /></button>' },
  'el-empty': { props: ['description'], template: '<div class="empty">{{ description }}</div>' },
  'el-pagination': {
    props: ['total', 'pageSize', 'currentPage', 'layout'],
    emits: ['current-change'],
    template: '<button class="pager-next" @click="$emit(\'current-change\', currentPage + 1)" />'
  },
  // 把 keyword/snippet 透到 DOM，才能断言「搜索页真的把高亮参数传给了帖子行」
  PostListItem: {
    props: ['post', 'keyword', 'snippet'],
    template:
      '<div class="post-item" :data-keyword="keyword" :data-snippet="snippet">{{ post.title }}</div>'
  }
}

const directives = { loading: () => {} }
const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

let pinia
const wrappers = []

function mountSearch() {
  const wrapper = mount(SearchView, {
    global: { plugins: [pinia], stubs, directives }
  })
  wrappers.push(wrapper)
  return wrapper
}

function postPayload(over = {}) {
  return {
    id: 11,
    title: '帖子A',
    tags: [],
    authorUsername: 'u',
    boardName: 'b',
    viewCount: 0,
    commentCount: 0,
    likeCount: 0,
    liked: false,
    snippet: '…正文里提到全文检索…',
    ...over
  }
}

describe('SearchView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    route.name = 'search'
    route.query = {}
    route.fullPath = '/search'
    pinia = createPinia()
    setActivePinia(pinia)
  })

  // 已挂载的组件会一直 watch 共享的 route，测试之间必须卸载，避免串扰
  afterEach(() => {
    wrappers.forEach((w) => w.unmount())
    wrappers.length = 0
  })

  it('未带 q 时显示「输入关键词开始搜索」且不发请求', async () => {
    const wrapper = mountSearch()
    await flush()

    expect(postApi.search).not.toHaveBeenCalled()
    expect(wrapper.find('.empty').text()).toBe('输入关键词开始搜索')
  })

  it('按 ?q= 加载结果并渲染计数、把 keyword/snippet 传给帖子行', async () => {
    route.query = { q: '全文' }
    route.fullPath = '/search?q=全文'
    postApi.search.mockResolvedValue({ content: [postPayload()], totalElements: 12, totalPages: 2 })

    const wrapper = mountSearch()
    await flush()

    expect(postApi.search).toHaveBeenCalledWith({ keyword: '全文', page: 0, size: 10 })
    expect(wrapper.text()).toContain('共 12 条结果')
    const rows = wrapper.findAll('.post-item')
    expect(rows).toHaveLength(1)
    expect(rows[0].attributes('data-keyword')).toBe('全文')
    expect(rows[0].attributes('data-snippet')).toContain('全文')
  })

  it('无结果时显示带关键词的空态文案', async () => {
    route.query = { q: '没命中' }
    route.fullPath = '/search?q=没命中'
    postApi.search.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 })

    const wrapper = mountSearch()
    await flush()

    expect(wrapper.find('.empty').text()).toBe('没有找到与 “没命中” 相关的帖子')
  })

  it('加载失败显示错误态，重试成功后恢复', async () => {
    route.query = { q: '全文' }
    route.fullPath = '/search?q=全文'
    postApi.search
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce({ content: [], totalElements: 0, totalPages: 0 })

    const wrapper = mountSearch()
    await flush()

    expect(wrapper.find('.error-state').text()).toContain('搜索失败，请稍后重试')

    await wrapper.find('.retry-btn').trigger('click')
    await flush()

    expect(postApi.search).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.error-state').exists()).toBe(false)
    expect(wrapper.find('.empty').text()).toBe('没有找到与 “全文” 相关的帖子')
  })

  it('分页切换请求下一页', async () => {
    route.query = { q: '全文' }
    route.fullPath = '/search?q=全文'
    postApi.search.mockResolvedValue({ content: [postPayload()], totalElements: 21, totalPages: 3 })

    const wrapper = mountSearch()
    await flush()

    await wrapper.find('.pager-next').trigger('click')
    await flush()

    expect(postApi.search).toHaveBeenLastCalledWith({ keyword: '全文', page: 1, size: 10 })
  })

  it('提交新关键词走 router.replace，并在 URL 变化后重新加载', async () => {
    route.query = { q: '旧词' }
    route.fullPath = '/search?q=旧词'
    postApi.search.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 })

    const wrapper = mountSearch()
    await flush()

    const input = wrapper.find('input.search-input')
    await input.setValue('spring')
    await input.trigger('keyup.enter')

    expect(replace).toHaveBeenCalledWith({ name: 'search', query: { q: 'spring' } })

    route.query = { q: 'spring' }
    route.fullPath = '/search?q=spring'
    await flush()

    expect(postApi.search).toHaveBeenLastCalledWith({ keyword: 'spring', page: 0, size: 10 })
  })

  it('过期响应被序号丢弃，不覆盖新关键词的结果', async () => {
    let resolveOld
    postApi.search
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveOld = resolve
          })
      )
      .mockResolvedValueOnce({ content: [postPayload({ id: 2, title: '新帖' })], totalElements: 1, totalPages: 1 })
    route.query = { q: '旧词' }
    route.fullPath = '/search?q=旧词'

    const wrapper = mountSearch()
    await flush()

    route.query = { q: '新词' }
    route.fullPath = '/search?q=新词'
    await flush()
    expect(wrapper.text()).toContain('新帖')

    resolveOld({ content: [postPayload({ id: 1, title: '旧帖' })], totalElements: 1, totalPages: 1 })
    await flush()

    expect(wrapper.text()).toContain('新帖')
    expect(wrapper.text()).not.toContain('旧帖')
  })

  it('换关键词时页码重置为 0', async () => {
    route.query = { q: '旧词' }
    route.fullPath = '/search?q=旧词'
    postApi.search.mockResolvedValue({ content: [postPayload()], totalElements: 21, totalPages: 3 })

    const wrapper = mountSearch()
    await flush()
    await wrapper.find('.pager-next').trigger('click')
    await flush()
    expect(postApi.search).toHaveBeenLastCalledWith({ keyword: '旧词', page: 1, size: 10 })

    route.query = { q: '新词' }
    route.fullPath = '/search?q=新词'
    await flush()

    expect(postApi.search).toHaveBeenLastCalledWith({ keyword: '新词', page: 0, size: 10 })
  })
})
```

Run:

```bash
cd /e/JavaSpace/forum/frontend && pnpm exec vitest run src/views/SearchView.spec.js
```

Expected: **FAIL** —— `Failed to resolve import "./SearchView.vue"`。

- [ ] **Step 3-6: 实现 SearchView.vue**

Create `frontend/src/views/SearchView.vue`：

```vue
<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { postApi } from '@/api'
import { usePostActions } from '@/composables/usePostActions'
import { useAuthStore } from '@/stores/auth'
import { buildSnippet } from '@/utils/search'
import PostListItem from '@/components/PostListItem.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const actions = usePostActions()

const pageData = ref({ content: [], totalElements: 0, totalPages: 0 })
const page = ref(0)
const size = 10
const loading = ref(false)
const error = ref('')
const searched = ref(false)

/** 当前关键词：以地址栏为准，刷新、前进后退、顶栏跳转都走同一条路径 */
const keyword = computed(() => (typeof route.query.q === 'string' ? route.query.q.trim() : ''))

/**
 * 输入框初值取地址栏（刷新 / 直达链接）。
 * 之后只在关键词变化时同步（见文件末尾的 watch）；分页与重试只重载当前关键词，
 * 不能覆盖用户正在编辑、尚未提交的草稿。
 */
const input = ref(keyword.value)

/**
 * 同一路由记录内换词时组件可能被复用（App.vue 的 :key 目前会重建，这里仍防一手）：
 * 两次请求乱序返回时用自增序号丢弃过期响应，避免慢请求的旧结果覆盖新关键词。
 */
let requestSeq = 0

async function load() {
  const q = keyword.value
  if (!q) {
    // 清空关键词要完整复位：作废在途请求（慢响应不能写进空页面）、
    // 关掉 loading、清掉上一次失败的 error，否则空态会被错误态/遮罩永久挡住
    requestSeq += 1
    searched.value = false
    loading.value = false
    error.value = ''
    pageData.value = { content: [], totalElements: 0, totalPages: 0 }
    return
  }
  const seq = ++requestSeq
  searched.value = true
  loading.value = true
  error.value = ''
  try {
    const data = await postApi.search({ keyword: q, page: page.value, size })
    if (seq !== requestSeq) return
    // 与首页一致：把互动字段播种进共享状态，点进详情后仍是同一份计数
    data.content.forEach((p) => actions.syncFromPost(p))
    pageData.value = data
  } catch {
    if (seq !== requestSeq) return
    error.value = '搜索失败，请稍后重试'
    pageData.value = { content: [], totalElements: 0, totalPages: 0 }
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

function submit() {
  const q = input.value.trim()
  // 与服务端一致：按 Unicode 码点计 1~50（maxlength 只按 UTF-16 码元截断，
  // emoji 等代理对字符会被少算，所以这里再按码点挡一次）
  if (!q || Array.from(q).length > 50) return
  // 交给 watch(keyword) 统一触发加载：replace 不新增历史记录，连续搜索不会塞满后退按钮
  router.replace({ name: 'search', query: { q } })
}

function clear() {
  router.replace({ name: 'search' })
}

function changePage(p) {
  page.value = p - 1
  load()
}

async function toggleLike(p) {
  if (!auth.isLoggedIn) {
    router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  try {
    await actions.toggleLike(p.id)
  } catch {
    // 失败回滚由 usePostActions 完成，提示由 axios 拦截器统一给出
  }
}

// 关键词变化（页面内搜索、顶栏搜索、浏览器前进后退）时回到第一页重新加载；
// 只有这条路径把输入框拉回 URL，分页/重试不会碰用户未提交的草稿
watch(keyword, () => {
  input.value = keyword.value
  page.value = 0
  load()
})

load()
</script>

<template>
  <el-card shadow="never" class="search-card">
    <template #header>
      <div class="head">
        <span class="title">搜索</span>
        <span v-if="searched && !error && !loading" class="total">共 {{ pageData.totalElements }} 条结果</span>
      </div>
      <div class="search-bar">
        <el-input
          v-model="input"
          placeholder="搜索帖子标题/内容…"
          maxlength="50"
          clearable
          class="search-input"
          @keyup.enter="submit"
          @clear="clear"
        >
          <template #append>
            <el-button :icon="Search" aria-label="搜索" @click="submit" />
          </template>
        </el-input>
      </div>
    </template>

    <div v-loading="loading">
      <div v-if="error" class="error-state">
        <span>{{ error }}</span>
        <el-button size="small" class="retry-btn" @click="load">重试</el-button>
      </div>
      <el-empty v-else-if="!searched" description="输入关键词开始搜索" />
      <template v-else>
        <PostListItem
          v-for="p in pageData.content"
          :key="p.id"
          :post="p"
          :keyword="keyword"
          :snippet="buildSnippet(p.snippet || '', keyword)"
          @like="toggleLike"
        />
        <el-empty
          v-if="!pageData.content.length"
          :description="`没有找到与 “${keyword}” 相关的帖子`"
        />
      </template>
    </div>

    <el-pagination
      v-if="!error && pageData.totalPages > 1"
      layout="prev, pager, next"
      :total="pageData.totalElements"
      :page-size="size"
      :current-page="page + 1"
      class="jf-pager"
      @current-change="changePage"
    />
  </el-card>
</template>

<style scoped>
.search-card {
  margin: var(--jf-space-4) auto;
  max-width: 860px;
}
.head {
  display: flex;
  align-items: center;
  gap: var(--jf-space-3);
}
.title {
  font-weight: 600;
}
.total {
  margin-left: auto;
  color: var(--jf-color-text-3);
  font-size: var(--jf-font-meta);
}
.search-bar {
  margin-top: var(--jf-space-3);
}
.error-state {
  display: flex;
  align-items: center;
  gap: var(--jf-space-3);
  padding: var(--jf-space-4) 0;
  color: var(--jf-color-text-3);
}
</style>
```

Run:

```bash
cd /e/JavaSpace/forum/frontend && pnpm exec vitest run src/views/SearchView.spec.js
```

Expected: **PASS** —— `8 passed`。

- [ ] **Step 3-7: 注册 /search 路由**

Modify `frontend/src/router/index.js:10`（`/tags/:name` 之后）插入：

```js
  { path: '/search', name: 'search', component: () => import('@/views/SearchView.vue') },
```

Run:

```bash
cd /e/JavaSpace/forum/frontend && pnpm exec vitest run src/views/SearchView.spec.js src/components/HeaderSearch.spec.js
```

Expected: **PASS** —— `13 passed`（路由表本身没有 spec，`viteConfig.spec.js` 只校验 base 与接口前缀）。

- [ ] **Step 3-8: 给 PostListItem 写失败的高亮/片段用例**

Modify `frontend/src/components/PostListItem.spec.js:80`（类末尾 `})` 之前）追加两个用例：

```js
  it('传入 keyword 时只给标题里的命中片段套 <mark>', () => {
    const post = postPayload({ id: 11, title: '全文检索与 Java', tags: [] })
    const wrapper = mount(PostListItem, { props: { post, keyword: 'java' }, global: { stubs } })

    const marks = wrapper.findAll('mark.hit')
    expect(marks).toHaveLength(1)
    expect(marks[0].text()).toBe('Java')
    expect(wrapper.find('.post-title').text()).toBe('全文检索与 Java')
    expect(wrapper.find('.post-snippet').exists()).toBe(false)
  })

  it('传入 snippet 时在标题下渲染片段并高亮命中词', () => {
    const post = postPayload({ id: 12, title: '标题不含关键词', tags: [] })
    const wrapper = mount(PostListItem, {
      props: { post, keyword: '检索', snippet: '…这里提到检索…' },
      global: { stubs }
    })

    expect(wrapper.find('.post-snippet').text()).toContain('检索')
    expect(wrapper.find('.post-snippet mark.hit').text()).toBe('检索')
    // 标题没命中：标题里不应出现 mark
    expect(wrapper.find('.post-title mark').exists()).toBe(false)
  })
```

Run:

```bash
cd /e/JavaSpace/forum/frontend && pnpm exec vitest run src/components/PostListItem.spec.js
```

Expected: **FAIL** —— 两个新用例失败（`mark.hit` 不存在、`.post-snippet` 不存在），原有 4 例仍绿。

- [ ] **Step 3-9: 给 PostListItem 加 keyword/snippet 与高亮渲染**

Modify `frontend/src/components/PostListItem.vue:8-11`，把 props 换成：

```js
const props = defineProps({
  post: { type: Object, required: true },
  // 搜索页传入：标题与片段里命中的关键词会被 <mark> 高亮；其它页面不传，渲染与原来一致
  keyword: { type: String, default: '' },
  // 搜索页传入：正文命中处的纯文本窗口（服务端截取 ±80 字符），前端 buildSnippet 再清洗
  snippet: { type: String, default: '' }
})
```

Modify `frontend/src/components/PostListItem.vue:6`（`formatRelative` import 之后）新增 import：

```js
import { highlightSegments } from '@/utils/search'
```

再在 `const state = computed(() => actions.stateFor(props.post.id))`（第 15 行）之后新增两个 computed（必须放在 props 声明之后：`computed` 的取值函数在渲染时才执行，但可读性上不应把 `props` 的使用写在 `defineProps` 之前）：

```js
/** 标题分段：没有 keyword 时退化为单段，模板结构不需要分支 */
const titleSegments = computed(() =>
  props.keyword
    ? highlightSegments(props.post.title, props.keyword)
    : [{ text: props.post.title, hit: false }]
)

/** 片段分段：snippet 为空时数组为空，模板用 v-if 直接不渲染整行 */
const snippetSegments = computed(() =>
  props.snippet ? highlightSegments(props.snippet, props.keyword) : []
)
```

Modify `frontend/src/components/PostListItem.vue:36`，把标题文本换成文本层渲染：

```html
        <span class="post-title">
          <template v-for="(seg, i) in titleSegments" :key="i">
            <mark v-if="seg.hit" class="hit">{{ seg.text }}</mark>
            <template v-else>{{ seg.text }}</template>
          </template>
        </span>
```

Modify `frontend/src/components/PostListItem.vue:38`（`<div class="post-meta">` 之前）插入片段行：

```html
      <p v-if="snippet" class="post-snippet">
        <template v-for="(seg, i) in snippetSegments" :key="i">
          <mark v-if="seg.hit" class="hit">{{ seg.text }}</mark>
          <template v-else>{{ seg.text }}</template>
        </template>
      </p>
```

Modify `frontend/src/components/PostListItem.vue:99-102`（`.post-title` 规则之后）追加样式：

```css
/* 命中词高亮：用浅色底 + 继承文字色，暗色令牌下也不会变成白底黑字 */
.hit {
  background: var(--jf-color-anchor-bg);
  color: inherit;
  padding: 0 1px;
  border-radius: var(--jf-radius-sm);
}
/* 片段只占一行：超出用省略号，避免搜索结果行高被长正文撑开 */
.post-snippet {
  margin: 4px 0 0;
  color: var(--jf-color-text-3);
  font-size: var(--jf-font-meta);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
```

> 这里刻意不写 `.hit` 的 `@media` 变体：`breakpoint.spec.js` 要求全 `src` 恰好 6 处媒体查询，新增一处就会红。

Run:

```bash
cd /e/JavaSpace/forum/frontend && pnpm exec vitest run src/components/PostListItem.spec.js
```

Expected: **PASS** —— `6 passed`。

- [ ] **Step 3-10: 把 HeaderSearch 挂到顶栏并调整布局**

Modify `frontend/src/App.vue:9`（`ThemeToggle` import 之后）新增：

```js
import HeaderSearch from '@/components/HeaderSearch.vue'
```

Modify `frontend/src/App.vue:53`（`标签` nav-link 之后）插入：

```html
        <HeaderSearch />
```

Modify `frontend/src/App.vue:108-115` 的 `.header-inner`：

```css
.header-inner {
  max-width: 1100px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  /* 原来是 space-between（brand / 标签 / actions 三段）；插入搜索框后改成左对齐分组，
     由 .nav-actions 的 margin-left:auto 把右侧操作区推到行尾 */
  justify-content: flex-start;
  gap: var(--jf-space-2);
  height: 100%;
}
```

Modify `frontend/src/App.vue:132-136` 的 `.nav-actions`，增加一行：

```css
.nav-actions {
  display: flex;
  align-items: center;
  gap: var(--jf-space-3);
  margin-left: auto;
}
```

Run:

```bash
cd /e/JavaSpace/forum/frontend && pnpm test
```

Expected: **PASS** —— **28 个 spec、273 个用例**（含 Task 3 质量修复新增的 16 例；`api` 12→13、`PostListItem` 4→8、`HeaderSearch` 5→9、`SearchView` 8→18）。`breakpoint` 仍断言 6 处 `@media`，未新增。

- [ ] **Step 3-11: 前端构建自检（可选但建议）**

Run:

```bash
cd /e/JavaSpace/forum/frontend && MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build
```

Expected: 构建成功；`dist/assets/` 里出现新的 `SearchView-*.js` chunk（懒加载路由生效）；仅有既有的 >500kB chunk 警告。

- [ ] **Step 3-12: 提交前端入口与搜索页**

Run:

```bash
cd /e/JavaSpace/forum
git add frontend/src/api/index.js frontend/src/api/index.spec.js \
        frontend/src/router/index.js frontend/src/App.vue \
        frontend/src/components/HeaderSearch.vue frontend/src/components/HeaderSearch.spec.js \
        frontend/src/components/PostListItem.vue frontend/src/components/PostListItem.spec.js \
        frontend/src/views/SearchView.vue frontend/src/views/SearchView.spec.js
git commit -m "feat: 新增顶栏搜索入口与搜索结果页"
```

Expected: 提交成功；`git status` 干净；`git show --stat HEAD` 显示 10 个文件。

### Task 3 质量修复记录（实施偏差）

质量评审后在同一分支追加一次修复提交（`fix: 修正搜索页状态清理与顶栏搜索交互细节`），只改动 `frontend/src/views/SearchView.vue`、`frontend/src/views/SearchView.spec.js`、`frontend/src/components/HeaderSearch.vue`、`frontend/src/components/HeaderSearch.spec.js`、`frontend/src/components/PostListItem.spec.js` 与本计划文档：

1. **空 q 完整复位**：`load()` 的空白关键词分支原来只清 `searched`/`pageData`，失败后的 `.error-state` 会一直留着且重试无法恢复。改为同时 `requestSeq += 1`（作废在途响应）、`loading.value = false`、`error.value = ''`。
2. **分页/重试保草稿**：`input` 的初值改为 `keyword.value`，只在 `watch(keyword)`（页面内提交、顶栏跳转、前进后退）里同步；`load()`、`changePage()`、`retry()` 不再覆盖用户尚未回车的新草稿。
3. **计数加载门禁**：`共 N 条结果` 的渲染条件加 `!loading`，请求中不再短暂显示「共 0 条结果」。
4. **`@clear` 语义**：原 `@clear="submit"` 是 no-op（ElInput 先清空 model，submit 读到空白直接 return）。按「清空即回到空搜索页」的意图改为 `onClear()`：仅在 `route.name === 'search'` 时 `router.push({ name: 'search' })` 回空态，其他页面不动；Enter/图标仍走 `submit`。**偏差决策**：任务描述给的是 push 回空态，故保留 push（不 replace），并注册 HeaderSearch 的 clear 用例固化。
5. **卫生**：更正 `HeaderSearch.vue` 中「space-between 之外」的过期注释（现为 `flex-start` + `.nav-actions` 的 `margin-left:auto`）；删除无样式的 `search-btn` class。
6. **码点限长**：客户端按 `Array.from(q).length` 计 1–50 个 Unicode 码点，与服务端 `PostService` 的 `codePointCount(0, length) > 50` 对齐；HeaderSearch 与 SearchView 的提交路径统一，超限静默忽略（不新增提示组件，与计划「失败提示保持极简」一致）。复核补充：`maxlength="50"` 按 UTF-16 码元截断会让 26–50 个码点的 emoji 查询在输入阶段就被截断（真实用户只能输入 25 个 emoji），故两处 `maxlength` 放宽为 `100`，1–50 码点的真正上限仍由 `submit` 的码点校验兜底。
7. **测试补齐**：SearchView 8→18、HeaderSearch 5→9、PostListItem 6→8，共新增 16 例（空 q 清错误态/作废在途、分页与重试保草稿、加载中不渲染计数、单页不分页、空白 q、同词不重复、码点限长（51 ASCII 拒绝 / 50 emoji 通过）、快速翻页过期响应、清空跳转与非搜索页不跳转、XSS 纯文本防护、关键词未命中不高亮、窄屏 aria-label）。先写测试：隐藏实现改动后 8 例失败（push 未调用、错误态残留、loading 残留、草稿被覆盖、计数在加载中渲染），恢复实现后转绿。复核后补强：同词用例显式断言 `replace` 未被调用——由此暴露出 SearchView 的 `submit()` 原本缺少「关键词未变则短路」判断（同词回车会再 `replace` 一次），已补上与 HeaderSearch 一致的 `route.query.q === q` 短路；并补 SearchView 自身清空按钮用例。

结果：`cd frontend && pnpm test` → **28 个 spec / 274 个用例**全绿；`MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build` 成功（仅既有 >500kB chunk 警告）；`breakpoint` 仍为 6 处 `@media (max-width: 768px)`，未新增依赖、未使用 `v-html`。计划上文 Step 3-1/3-3/3-5/3-8 的 spec 片段按上述行为同步，测试计数基线统一为 273。

---

## Task 4: 文档（README / 开发记录 / 部署手册）

**Files:**
- Modify: `README.md`
- Modify: `docs/DEVELOPMENT.md`
- Modify: `docs/deployment/README.md`

> 本任务开始前先取本轮已产生的提交哈希，§8 变更日志要用真实哈希（最后一行是文档提交本身，按仓库约定写「本次提交」）：
>
> ```bash
> cd /e/JavaSpace/forum && git log --format='%h %s' -4
> ```

- [ ] **Step 4-1: README 功能清单与目录结构**

Modify `README.md` 的「## 功能」列表，在「头像上传」条目之后插入：

```markdown
- **全文搜索**：顶栏搜索框或 `/search?q=` 独立结果页，标题与正文子串匹配、标题命中优先、命中词高亮并显示正文片段；中文按子串匹配，无分词与相关度排序
```

Modify `README.md`「## 目录结构」的前端树两行：

```markdown
├── components/    # 可复用组件（UserCard、BoardCard、PostListItem、UserAvatar、ThemeToggle、HeaderSearch）
```

```markdown
├── utils/         # 纯函数（通知跳转映射、时间格式化、Markdown 渲染、头像地址解析、搜索高亮与片段）
```

Run:

```bash
cd /e/JavaSpace/forum && grep -n '全文搜索\|HeaderSearch\|搜索高亮' README.md
```

Expected: 3 行命中（功能条目、components 行、utils 行）。

- [ ] **Step 4-2: README 测试基线与已知限制**

Modify `README.md` 后端测试说明，把：

```markdown
当前为 12 个测试类、90 个用例（其中 1 个 POSIX 文件权限用例在 Windows 上跳过）。
```

改为：

```markdown
当前为 13 个测试类、105 个用例（其中 1 个 POSIX 文件权限用例在 Windows 上跳过）。
```

Modify 前端测试说明：

```markdown
当前为 25 个 spec、211 个用例，覆盖：
```

改为：

```markdown
当前为 28 个 spec、273 个用例，覆盖：
```

并在前端覆盖清单末尾（「头像（…）」条目之后）追加两条：

```markdown
- 搜索页与顶栏入口（`src/views/SearchView.spec.js`、`src/components/HeaderSearch.spec.js`、`src/components/PostListItem.spec.js`、`src/api/index.spec.js`）：读 `?q=` 拉取结果并展示计数、三种空态、分页与换词重置、过期响应丢弃、顶栏桌面输入框/窄屏图标两种形态、命中词 `<mark>` 高亮与正文片段、`postApi.search` 的请求路径
- 搜索文本工具（`src/utils/search.spec.js`）：大小写不敏感分段高亮、正则元字符按字面匹配、Markdown 轻量清洗（代码围栏/行内代码/标题/引用/列表/链接/图片）、空白折叠、命中窗口与省略号
```

Modify `README.md`「## 已知限制」列表末尾追加：

```markdown
- **全文搜索按子串匹配**：中文没有分词，「关键词 + 相关度排序」的能力有限；1–2 个字的关键词用不上 pg_trgm 索引，会退化为顺序扫描（数据量小时无感）。
```

Run:

```bash
cd /e/JavaSpace/forum && grep -n '105 个用例\|273 个用例\|按子串匹配' README.md
```

Expected: 3 行命中。

- [ ] **Step 4-3: DEVELOPMENT 迭代轮次与状态快照**

Modify `docs/DEVELOPMENT.md` §2.2 表格，在「第四轮」行之后追加：

```markdown
| 第五轮 | 全文搜索：搜索接口 + pg_trgm 索引 + 顶栏入口与搜索结果页 | 5 | ✅ 已完成 | 2026-10-21 |
```

Modify 紧随其后的进度句：

```markdown
**当前进度：MVP 100% ｜ 已完成 4 轮迭代。**
```

改为：

```markdown
**当前进度：MVP 100% ｜ 已完成 5 轮迭代。**
```

Modify §3 标题日期：

```markdown
## 3. 当前状态快照（2026-10-21）
```

然后按实测更新 §3.1 表格。先取数：

```bash
cd /e/JavaSpace/forum
git ls-files 'src/main/java/**/*.java' | wc -l
git ls-files 'src/test/java/**/*.java' | wc -l
git ls-files 'frontend/src/**' ':(exclude)frontend/src/**/*.spec.js' ':(exclude)frontend/src/assets/**' | wc -l
git ls-files 'frontend/src/**/*.spec.js' | wc -l
```

把四行数字分别填入「后端主代码」「后端测试」「前端产品代码」「前端测试」四行的文件数列；行数用 `xargs cat | wc -l` 同法取。§3.1 下方的分层文件数（`dto/` 18、`controller/` 9、`service/` 9、`repository/` 9 不变；`views/` 20（11 页面 + 9 spec）、`components/` 11（6 组件 + 5 spec）、`utils/` 10（5 纯函数 + 5 spec））按实测修改。

> 这一格是数据依赖而非占位：唯一来源就是上面四条命令的输出；不要凭估算填。

Run:

```bash
cd /e/JavaSpace/forum && grep -n '第五轮\|已完成 5 轮迭代\|2026-10-21' docs/DEVELOPMENT.md | head -5
```

Expected: 至少命中「第五轮」行、「已完成 5 轮迭代」与快照标题。

- [ ] **Step 4-4: DEVELOPMENT 测试基线与接口清单**

Modify `docs/DEVELOPMENT.md` §3.2 表格两行：

```markdown
| 后端 | **105** 个用例 / 13 个测试类 | 全绿（其中 1 个 POSIX 文件权限用例在 Windows 上跳过） |
| 前端 | **273** 个用例 / 28 个 spec | 全绿 |
```

后端测试类分布：把 `RepositoryTests` 的 `7` 改成 `8`，并在 `TagFlowIntegrationTest` 后追加 `SearchFlowIntegrationTest` 14（合计 105）。

前端 spec 分布：`index`（api）由 12 改 13、`PostListItem` 由 4 改 8，并追加 `search` 30、`HeaderSearch` 9、`SearchView` 18（Task 2/3 两轮质量修复后的实测值，合计 273）。

Modify §3.3 迁移脚本行：

```markdown
| 迁移脚本 | `V1__init_schema.sql`（建表 + 角色种子）、`V2__notification_comment_id.sql`（通知表加 `comment_id`）、`V3__post_search_trgm_index.sql`（pg_trgm 扩展 + 标题/正文 GIN 部分索引） |
```

Modify §3.4 标题与 PostController 行：

```markdown
### 3.4 接口清单（36 个端点）
```

```markdown
| `PostController` | `GET /api/posts`、`GET /api/posts/search`、`GET /api/posts/{id}`、`GET /api/posts/mine`、`POST /api/posts`、`PUT /api/posts/{id}`、`DELETE /api/posts/{id}`、`GET /api/posts/favorites/mine`、`POST /api/posts/{id}/like`、`POST /api/posts/{id}/favorite`、`PUT /api/posts/{id}/pin`、`PUT /api/posts/{id}/exquisite` |
```

在标签端点契约表之后、头像端点小节之前插入全文搜索契约：

```markdown
全文搜索端点为公开 GET（`SecurityConfig` 的 `PUBLIC_GET` 已包含 `/api/posts/**`），响应统一包在 `Result<T>` 里：

| 端点 | 参数 | 返回 |
| --- | --- | --- |
| `GET /api/posts/search` | `keyword`（必填，去首尾空白后 1~50 个 code point）、`page`（默认 0，负数按 0）、`size`（默认 10，钳制到 1~50） | `PageResponse<PostSummaryResponse>`；标题命中优先、同档按 `createTime` 倒序（同时间再按 `id` 倒序），软删除不出现；错误统一 400：`缺少必需参数: keyword`、`搜索关键词不能为空`、`搜索关键词不能超过 50 个字符`、`参数格式不正确: page`（`size` 同理） |

`PostSummaryResponse` 在搜索接口里额外带一个可空字段 `snippet`：正文命中处左右各 80 字符的纯文本窗口（**不清洗 Markdown、不含省略号**，由前端 `utils/search.js` 的 `buildSnippet` 完成清洗与省略号）；窗口端点吸附到 code point 边界，不会劈开 emoji/生僻字；标题命中且正文不含关键词时是空串，其它列表接口通过 `@JsonInclude(NON_NULL)` 省略该键（而非 `null`）。关键词里的 `%`、`_`、`!` 按字面量匹配（服务端统一转义后 SQL 用 `ESCAPE '!'`）。
```

Modify §3.5 页面表，在 `/tags/:name` 行之后追加：

```markdown
| `/search` | `SearchView` | 全文搜索结果页（`?q=`、结果计数、分页、标题/片段高亮、三种空态） |
```

Run:

```bash
cd /e/JavaSpace/forum && grep -n '36 个端点\|api/posts/search\|/search` | `SearchView' docs/DEVELOPMENT.md
```

Expected: 3 行命中。

- [ ] **Step 4-5: DEVELOPMENT 新增 §4.10**

在 `docs/DEVELOPMENT.md` §4.9 结束分隔线 `---` 之后、「## 5. 待办与后续计划」之前插入：

```markdown
### 4.10 第五轮：全文搜索（2026-10-21）

计划：`docs/superpowers/plans/2026-10-21-round5-fulltext-search.md`（5 个任务：后端接口 + V3 迁移 → 搜索文本工具 → 顶栏入口与搜索结果页 → 文档 → 全量验证与上线）

目标：把首页已有的关键词 `LIKE` 过滤升级为独立的搜索结果页与公开搜索接口——标题命中优先、同档按发帖时间倒序，配合 pg_trgm GIN 索引支撑「包含」语义的子串检索；顶栏提供搜索入口，窄屏收成图标。

新增/修改的文件：

| 位置 | 文件 | 要点 |
| --- | --- | --- |
| 后端 | `repository/PostRepository.java` | `searchByKeyword`：`LOWER(title/content) LIKE CONCAT('%', :pattern, '%') ESCAPE '!'`，`CASE WHEN 标题命中 THEN 0 ELSE 1` + `createTime DESC, id DESC`，EntityGraph 只 join `board`/`author` |
| 后端 | `service/PostService.java` | `search(rawKeyword, page, size)`：归一化 1~50 code point、`escapeLike`（`! % _` 转义）、clamp、**无 Sort** 的 `PageRequest`、两步分页 + `snippetOf` 截正文窗口 |
| 后端 | `controller/PostController.java` | `GET /api/posts/search`，放在 `/{id}` 之前；controller 与 service 各夹取一次 `size` |
| 后端 | `dto/post/PostSummaryResponse.java` | 新增可空 `snippet`：仅搜索接口填充，`@JsonInclude(NON_NULL)` 让其它列表的 JSON 省略该键 |
| 后端 | `db/migration/V3__post_search_trgm_index.sql` | `CREATE EXTENSION IF NOT EXISTS pg_trgm` + 标题/正文两个 `USING gin (LOWER(...) gin_trgm_ops) WHERE deleted = false` |
| 前端 | `utils/search.js` | `highlightSegments`（indexOf 循环、不构造正则）、`buildSnippet`（Markdown 轻量清洗 + 命中窗口 + 省略号） |
| 前端 | `components/HeaderSearch.vue` | 桌面 220px 输入框、窄屏图标按钮，`useResponsive` + `v-if`，零新增 `@media` |
| 前端 | `views/SearchView.vue` | `/search?q=`：结果计数、分页、三种空态、`requestSeq` 防竞态，点赞复用 `usePostActions` |
| 前端 | `components/PostListItem.vue` | 可选 `keyword`/`snippet` props，文本层 `<mark class="hit">` 高亮（不引入 `v-html`） |
| 前端 | `api/index.js`、`router/index.js`、`App.vue` | `postApi.search`、`/search` 懒加载路由、顶栏挂载（`.header-inner` 改 `flex-start` + `.nav-actions` 自动左推） |

关键取舍：

- **pg_trgm 而不是 tsvector**：中文没有空格分词，`to_tsvector('simple', ...)` 只能整段匹配；pg_trgm 的三元组索引对「包含」语义的子串检索有效，也不需要额外的分词插件。
- **标题优先用 CASE，不做相关度评分**：`similarity()`/`ts_rank` 的排序用户无法预期；「标题命中的排前面、同档按时间」既好解释又稳定可测。
- **两步分页**：`@EntityGraph` 带 `tags` 集合再分页会触发 `HHH90003004`（全表加载后内存分页），与首页/标签页保持同一套 `searchByKeyword` → `hydrate` → `mapSummaries`。
- **`ESCAPE '!'`**：`%`/`_` 是 LIKE 通配符，不转义时搜「100%」会命中「1000」；service 统一转义，repository 只拼 `%pattern%`。
- **片段由服务端截窗口、前端清洗**：列表 DTO 不带正文（避免搜索页响应随正文膨胀），服务端只给命中位置左右各 80 字符的窗口，并把窗口端点吸附到 code point 边界（避免劈开 emoji/生僻字）；Markdown 清洗、省略号与高亮统一在 `buildSnippet` 一处实现。
- **H2 测试关闭 Flyway**：V3 的 `CREATE EXTENSION` 在 H2 上无法执行，测试库统一 `spring.flyway.enabled=false` + `ddl-auto=create-drop`。

测试与验证：后端新增 `SearchFlowIntegrationTest` 14 例（契约 400、标题优先、时间倒序、分页与夹取、通配符转义、软删除、片段不劈开代理对、长度按 code point、大小写不敏感、既有响应无 `snippet` 键），`RepositoryTests` 新增 1 例覆盖 JPQL 的 CASE 排序与 `ESCAPE '!'`；前端新增 `search`/`HeaderSearch`/`SearchView` 三个 spec 并扩充 `PostListItem`/`api`。基线数字与上线验收结果见 §3.2 与下方「上线验收」段落。

已知限制：

- 中文按子串匹配，没有分词与相关度排序；1–2 个字的关键词无法有效利用 trigram 索引，退化为顺序扫描（T14）。
- `posts.content` 没有长度上限，超长正文的片段截取与传输仍有成本（T15）。
- 跨列 OR（标题或正文）可能让查询规划器放弃 GIN 索引；数据量继续增长时可拆成两条查询再 `UNION`（未立项）。
```

Run:

```bash
cd /e/JavaSpace/forum && grep -n '### 4.10 第五轮' docs/DEVELOPMENT.md
```

Expected: 命中 1 行。

- [ ] **Step 4-6: DEVELOPMENT 技术债、候选功能、决策与变更日志**

Modify §5.1 技术债表，在 T12 行之后追加：

```markdown
| T13 | **首页关键词过滤不转义 LIKE 通配符** | 低 | 首页 `GET /api/posts?keyword=` 走 `searchIds` 的 `LOWER(...) LIKE LOWER(CONCAT('%', :keyword, '%'))`，未转义 `%`/`_`，搜「100%」会命中「1000」。本轮只给新搜索接口做了转义；修首页需同步改 JPQL 与前端提示，属独立小改动 |
| T14 | **搜索无分词、无相关度排序** | 中 | 采用 pg_trgm 子串匹配：中文不分词，1–2 个字的关键词的 trigram 覆盖不足，规划器会放弃索引改走顺序扫描；排序只有「标题命中优先 + 时间倒序」，没有 `ts_rank`/`similarity` 之类相关度。数据量增长后的升级路径是 `tsvector`（需分词扩展）或两阶段检索 |
| T15 | **`posts.content` 无长度上限** | 低 | 正文列是 `TEXT` 且无应用层长度校验；超长正文会放大 `snippet` 截取与索引维护成本，也让 `pg_trgm` 的 GIN 索引更大。可加与标题一致的长度提示或截断策略 |
```

Modify §5.2 候选功能表：把「全文搜索」行改为已完成，并更新小节标题与结尾说明：

```markdown
### 5.2 候选功能（第五轮已取全文搜索，其余未开始）
```

```markdown
| ~~全文搜索~~ ✅ 已完成 | 中 | 中 | 第五轮已交付：公开 `GET /api/posts/search` + pg_trgm GIN 部分索引、独立 `/search?q=` 结果页与顶栏入口（§4.10） |
```

```markdown
第三轮已取前两项（见 §4.8），第四轮已取头像上传与深色模式（见 §4.9），第五轮已取全文搜索（见 §4.10）；其余候选（WebSocket 实时通知等）留待后续轮次，按同样的「审计 → 计划 → 逐任务执行 → 验证 → 提交」流程推进，避免一次铺开。
```

Modify §7 关键决策表，追加三行：

```markdown
| 搜索实现 | **pg_trgm 子串匹配**，不用 `tsvector` | 中文没有空格分词，`to_tsvector('simple', ...)` 只能整段命中；pg_trgm 的三元组索引对「包含」语义有效，且 PostgreSQL 15 的 pg_trgm 是 trusted 扩展，`forum` 角色可直接 `CREATE EXTENSION` |
| 搜索排序 | **标题命中优先 + `createTime` 倒序**，不做相关度评分 | 相关度分数用户无法预期；「标题命中的排前面」规则可解释、可测试，也避免引入 `similarity()` 的排序开销 |
| 搜索片段 | **服务端截窗口、前端清洗与高亮** | 列表 DTO 保持轻量（不带正文）；Markdown 清洗、省略号与 `<mark>` 只在 `utils/search.js` 实现一次，避免 Java/JS 两套规则漂移 |
```

Modify §8 变更日志，在表头下第一行插入四行（前三行用 Step 4 开头 `git log` 拿到的真实哈希）：

```markdown
| 2026-10-21 | docs | 记录第五轮全文搜索功能（README、开发记录、部署手册） | 本次提交 |
| 2026-10-21 | feat | 第五轮搜索前端：`postApi.search`、顶栏 `HeaderSearch`、`/search` 结果页、命中词高亮与正文片段 | `<Task 3 的哈希>` |
| 2026-10-21 | feat | 第五轮搜索文本工具：`highlightSegments` 与 `buildSnippet` | `<Task 2 的哈希>` |
| 2026-10-21 | feat | 第五轮全文搜索后端：`GET /api/posts/search`、标题优先排序、pg_trgm 索引与测试隔离 | `<Task 1 的哈希>` |
```

> 三个 `<Task N 的哈希>` 必须替换成 `git log --format='%h %s' -4` 输出里的真实短哈希（数据依赖，唯一来源就是该命令）。

Run:

```bash
cd /e/JavaSpace/forum && grep -n 'T13\|T14\|T15\|~~全文搜索~~' docs/DEVELOPMENT.md
```

Expected: 4 行命中。

- [ ] **Step 4-7: 部署手册（回滚、索引验证、已知限制）**

Modify `docs/deployment/README.md` §4 回滚，在「数据库无版本回滚概念…」之后追加：

````markdown
- V3 迁移只创建扩展与索引，**回滚代码不需要回滚数据库**：旧版本后端不依赖它们，扩展保留无害。确实要清理索引时（例如重建索引期间临时释放磁盘）执行：

```sql
DROP INDEX IF EXISTS idx_posts_content_trgm;
DROP INDEX IF EXISTS idx_posts_title_trgm;
```

（`pg_trgm` 扩展本身建议保留：删除后重建需要再扫一遍 `posts` 建索引。）
````

Modify `docs/deployment/README.md` §5，在 `> 必须带 Host: raspi` 说明之后新增小节：

````markdown
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
# 期望：{"success":true,...,"content":[...]}

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
````

Modify `docs/deployment/README.md` §7 已知限制，在「孤儿头像文件不会自动清理」之后追加：

```markdown
- **1–2 个字的关键词用不上全文搜索索引**：pg_trgm 的三元组索引在关键词短于 3 个字符时无法过滤，规划器会走 `posts` 顺序扫描。当前数据量（十几到几十篇）下无感；数据量增长后短词查询会变慢，属预期行为。
- **中文按子串匹配，无分词与相关度排序**：搜索是「标题/正文包含关键词」，不区分词边界，也不做相关度打分；排序规则固定为「标题命中优先、同档按时间倒序」。
```

Run:

```bash
cd /e/JavaSpace/forum && grep -n 'pg_trgm\|顺序扫描\|DROP INDEX' docs/deployment/README.md
```

Expected: 至少 5 行命中。

- [ ] **Step 4-8: 提交文档**

Run:

```bash
cd /e/JavaSpace/forum && git status --short
```

Expected: 仅 `README.md`、`docs/DEVELOPMENT.md`、`docs/deployment/README.md` 三个文件被修改。

```bash
cd /e/JavaSpace/forum
git add README.md docs/DEVELOPMENT.md docs/deployment/README.md
git commit -m "docs: 记录第五轮全文搜索功能"
```

Expected: 提交成功；`git status` 干净。

---

## Task 5: 全量验证、上线与验收回填

**Files:**
- Modify: `docs/DEVELOPMENT.md`（§4.10 追加「上线验收」、§8 新增回填行）

- [ ] **Step 5-1: 后端全量测试**

Run:

```bash
cd /e/JavaSpace/forum && mvn -B clean test
```

Expected: `BUILD SUCCESS`；汇总：

```bash
cd /e/JavaSpace/forum && grep -h 'Tests run' target/surefire-reports/*.txt
```

Expected: 合计 **105 个用例 / 13 个测试类**，1 个 `Skipped`（POSIX 文件权限用例，Windows 上跳过），无 `Failures`、无 `Errors`。

- [ ] **Step 5-2: 前端全量测试**

Run:

```bash
cd /e/JavaSpace/forum/frontend && pnpm test
```

Expected: **28 个 spec、273 个用例全绿**（含 `breakpoint` 的 6 处 `@media` 门禁）。

- [ ] **Step 5-3: 生产构建（子路径 /forum/）**

Run:

```bash
cd /e/JavaSpace/forum/frontend && MSYS2_ENV_CONV_EXCL=VITE_BASE_PATH VITE_BASE_PATH=/forum/ pnpm build
```

Expected: 构建成功；`dist/index.html` 里的资源前缀是 `/forum/assets/`；产物含懒加载的 `SearchView-*.js`。`MSYS2_ENV_CONV_EXCL` 必须保留——不加时 Git Bash 会把 `/forum/` 当路径改写成 `C:/Program Files/Git/forum/`。

- [ ] **Step 5-4: 推送 master**

Run:

```bash
cd /e/JavaSpace/forum && git status --short && git log --oneline -6
git push origin master
```

Expected: `git status` 干净；`git log` 顶部是四个本轮提交（后端 / 工具 / 前端 / 文档）；push 成功，远端 `master` 前进到文档提交。

- [ ] **Step 5-5: 等待 Jenkins 构建（SCM 轮询，最长约 5 分钟）**

Run:

```bash
ssh raspi 'sudo ls -t /var/lib/jenkins/jobs/javaforum/builds | head -3'
```

Expected: 出现新的构建号（比上次大 1）。若 5 分钟内没有新构建，触发一次手动构建后继续：

```bash
ssh raspi 'sudo curl -fsS -X POST http://127.0.0.1:8080/job/javaforum/build'
```

- [ ] **Step 5-6: 确认构建成功并记录构建号与镜像 tag**

Run（把 `<N>` 换成上一步的新构建号）：

```bash
ssh raspi 'sudo grep -o "<result>[^<]*</result>" /var/lib/jenkins/jobs/javaforum/builds/<N>/build.xml'
ssh raspi 'sudo tail -n 40 /var/lib/jenkins/jobs/javaforum/builds/<N>/log'
```

Expected: `<result>SUCCESS</result>`；日志末尾出现镜像 `localhost/forum-backend:<N>`、`forum-backend` 容器重建并 `Up (healthy)`；`Deploy frontend` 阶段 `rsync` 成功。若为 `FAILURE`，先读日志定位（常见：`mvn` 测试红、pnpm install 超时、`缺少 .env`），修完重新提交并重跑 Step 5-4。

- [ ] **Step 5-7: raspi 冒烟（接口、页面、四类 400）**

Run:

```bash
ssh raspi "curl -fsS -H 'Host: raspi' http://127.0.0.1/forum/api/health"
ssh raspi "curl -s -o /dev/null -w 'health=%{http_code}\n' -H 'Host: raspi' http://127.0.0.1/forum/api/health"
ssh raspi "curl -s -o /dev/null -w 'search=%{http_code}\n' -H 'Host: raspi' 'http://127.0.0.1/forum/api/posts/search?keyword=Jenkins'"
ssh raspi "curl -s -o /dev/null -w 'searchPage=%{http_code}\n' -H 'Host: raspi' 'http://127.0.0.1/forum/search?q=Jenkins'"
ssh raspi "curl -s -o /dev/null -w 'blank=%{http_code}\n' -H 'Host: raspi' 'http://127.0.0.1/forum/api/posts/search?keyword=%20'"
ssh raspi "curl -s -o /dev/null -w 'missing=%{http_code}\n' -H 'Host: raspi' 'http://127.0.0.1/forum/api/posts/search'"
ssh raspi "curl -s -o /dev/null -w 'tooLong=%{http_code}\n' -H 'Host: raspi' \"http://127.0.0.1/forum/api/posts/search?keyword=$(printf 'a%.0s' $(seq 1 51))\""
ssh raspi "curl -fsS -H 'Host: raspi' 'http://127.0.0.1/forum/api/posts/search?keyword=Jenkins' | head -c 400"
```

Expected:

- `health=200`、`search=200`、`searchPage=200`（SPA 回退到 `index.html`）
- `blank=400`、`missing=400`、`tooLong=400`
- 最后一条返回 `{"success":true,...}`，`content` 里的标题/`snippet` 是搜索关键词相关的帖子（库里没有匹配时 `content:[]` 也是 200，属正常）

- [ ] **Step 5-8: 验证 V3 迁移已执行、索引已建**

Run:

```bash
ssh raspi 'sudo -u pi podman exec postgresql psql -U postgres -d javaforum -c "\\dx pg_trgm"'
ssh raspi 'sudo -u pi podman exec postgresql psql -U postgres -d javaforum -c "\\d posts"'
```

Expected: 第一条列出 `pg_trgm`（Version 1.6）；第二条的 Indexes 区块含 `idx_posts_title_trgm` 与 `idx_posts_content_trgm`，均为 `gin` 且带 `WHERE (deleted = false)`。

再确认迁移记录：

```bash
ssh raspi 'sudo -u pi podman exec postgresql psql -U postgres -d javaforum -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"'
```

Expected: 最新一行 `version = 3`、`description = post search trgm index`、`success = t`。

- [ ] **Step 5-9: 验收记录 + 哈希回填并提交**

Modify `docs/DEVELOPMENT.md` §4.10 的末尾（「已知限制」三条之后）追加真实验收结果，把尖括号内容换成 Step 5-4/5-6/5-8 实测值：

```markdown
上线验收（2026-10-21）：

- 推送后 Jenkins Build **#<N>** **SUCCESS**（镜像 `localhost/forum-backend:<N>`），`forum-backend` 重建后 `Up (healthy)`，仅监听 `127.0.0.1:8086`。
- V3 由容器启动时的 Flyway 自动执行：`flyway_schema_history` 最新记录 `version=3 / success=t`；`\dx pg_trgm` 显示扩展已安装，`\d posts` 显示 `idx_posts_title_trgm`、`idx_posts_content_trgm` 两个 `gin` 部分索引（`WHERE deleted = false`）。`forum` 角色以非超级用户身份完成 `CREATE EXTENSION`（pg_trgm 为 trusted 扩展），未额外授权。
- 冒烟：`/forum/api/health`、`/forum/api/posts/search?keyword=Jenkins`、`/forum/search?q=Jenkins` 均 200；空白关键词、缺参数、51 字符关键词均 400；`EXPLAIN` 在十几篇帖子的数据量下走顺序扫描，属预期（短词与低数据量用不上 trigram 索引）。
- 全量测试：后端 13 个测试类 / 105 个用例（1 例 POSIX 权限用例在 Windows 上跳过）；前端 28 个 spec / 273 个用例；`VITE_BASE_PATH=/forum/ pnpm build` 通过。
```

Modify `docs/DEVELOPMENT.md` §8 变更日志，把上一轮文档提交行的「本次提交」替换成 `git log` 里该提交的真实短哈希，并在表格最上方新增一行：

```markdown
| 2026-10-21 | docs | 第五轮全文搜索上线验收（Build #<N> 成功、pg_trgm 索引与冒烟实测）与变更日志回填 | 本次提交 |
```

Run:

```bash
cd /e/JavaSpace/forum && git diff --stat
```

Expected: 仅 `docs/DEVELOPMENT.md` 变化。

```bash
cd /e/JavaSpace/forum
git add docs/DEVELOPMENT.md
git commit -m "docs: 回填第五轮提交哈希并记录上线验收"
git push origin master
```

Expected: 提交与推送成功；`git status` 干净。

- [ ] **Step 5-10: 确认回填提交触发的重建（预期行为）**

Run（等 5 分钟后）：

```bash
ssh raspi 'sudo ls -t /var/lib/jenkins/jobs/javaforum/builds | head -2'
ssh raspi 'sudo grep -o "<result>[^<]*</result>" /var/lib/jenkins/jobs/javaforum/builds/<N+1>/build.xml'
```

Expected: 出现比 Step 5-6 更大一号的构建（本次只改文档，但流水线仍会重建 jar/前端并部署），`<result>SUCCESS</result>`；这是「每次 push 都会构建部署」的既有约定，不是失败。

- [ ] **Step 5-11: 收尾核对**

Run:

```bash
cd /e/JavaSpace/forum && git status --short && git log --oneline -6 && git rev-list --count HEAD
```

Expected: 工作区干净；顶部 6 条提交依次为「回填」「文档」「前端」「工具」「后端」与本轮之前的提交；`git rev-list --count HEAD` 比本轮开始前多 5 个提交（4 个功能/文档提交 + 1 个验收回填提交）。

---

## Self-Review 记录

### 1. Spec 覆盖对照

| Spec 条目 | 落点 |
| --- | --- |
| §1 目标：独立搜索页 + 顶栏入口 + 公开接口 + V3 索引 + 测试/文档/验收 | Task 1（接口与索引）、Task 2（高亮/片段工具）、Task 3（入口与页面）、Task 4（文档）、Task 5（验证与上线） |
| §1 非目标：中文分词、相关度评分、搜索建议、标签/作者/评论搜索、首页板块内过滤 | 明确不做：Task 5 Step 9 不涉及；§5.1 记 T13（首页不转义）、T14（无分词无相关度）；§3.4 只加一个 `/search` 端点 |
| §4.1 契约（keyword 必填 1~50 code point、page/size 默认与夹取、4 类 400、`Result<PageResponse<PostSummaryResponse>>`） | Task 1 Step 1-5（用例 1~4、8）与 Step 1-6/1-7；Task 4 Step 4-4（文档契约表） |
| §4.2 `searchByKeyword`（JPQL 逐字、EntityGraph、无排序 Pageable、不 join tags） | Task 1 Step 1-2（JPQL 逐字）与 Step 1-6（`PageRequest.of(safePage, safeSize)`） |
| §4.3 `search(...)` 与私有 static 工具（normalize/escapeLike） | Task 1 Step 1-6 |
| §4.4 控制器（`GET /search` 放 list 之后、detail 之前；两侧夹取） | Task 1 Step 1-7（含位置说明） |
| §4.5 V3 迁移（逐字 SQL）+ H2 测试隔离（`spring.flyway.enabled=false`） | Task 1 Step 1-3（SQL 逐字）、Step 1-1（RepositoryTests properties）；Step 1-5 的集成测试同样带该属性；Task 5 Step 5-8 验证真实执行 |
| §5.1 `postApi.search` | Task 3 Step 3-1/3-2 |
| §5.2 `highlightSegments` / `buildSnippet`（indexOf、无正则、Markdown 轻量清洗、窗口 + 省略号） | Task 2 Step 2-1/2-2（30 例覆盖每一条规则） |
| §5.3 `HeaderSearch.vue`（220px、maxlength、回车/图标、同页同词不重复、窄屏图标、watch URL、App.vue 标签之后、零 @media） | Task 3 Step 3-3/3-4/3-10；样式只写固定宽度，无媒体查询 |
| §5.4 `SearchView.vue`（`/search?q=`、replace、内部 page、计数、PostListItem、三空态、requestSeq、usePostActions） | Task 3 Step 3-5/3-6（8 例逐一对应） |
| §5.5 `PostListItem` 可选 `keyword`/`snippet`、`<mark class="hit">`、首页/标签页行为不变 | Task 3 Step 3-8/3-9；props 默认空串，首页与标签页不传 |
| §6.1 后端 14 例 + RepositoryTests 1 例 | Task 1 Step 1-5（14 个 `@Order` 方法）与 Step 1-1（仓库用例） |
| §6.2 前端 3 个新 spec + 更新 `PostListItem`/`api` spec | Task 2 Step 2-1、Task 3 Step 3-1/3-3/3-5/3-8；期望值 28 spec / 273 例 |
| §6.3 基线（后端 14 类/102 例、前端 28 spec/约 235 例、端点 36） | Task 4 Step 4-2/4-4（写实测 13 类/105 例、28 spec/273 例、36 端点）；差异见本记录第 4 节第 2 条 |
| §7.1 三份文档的每一处改动 | Task 4 Step 4-1~4-8 |
| §7.2 上线流程（验证 → push → Jenkins → 冒烟 → 验收/哈希回填） | Task 5 Step 5-1~5-11 |
| §8 风险表（短词不走索引、无相关度、H2/PG 差异、扩展权限、OR 弃索引、公开页） | Task 1 Step 1-1/1-3 的注释；Task 4 Step 4-5（§4.10 已知限制）与 Step 4-7（部署手册 §7）；§5.1 的 T13/T14/T15 |
| §9 决策（独立页 + 顶栏、标题优先 + 时间倒序、标题 + 正文、pg_trgm） | Task 4 Step 4-6 的 §7 三行决策；实现分别落在 Task 3（页面）、Task 1（排序与索引） |
| §10 验收 8 条 | Task 5 Step 5-1~5-9（契约 400 由 Step 5-7 覆盖，标题优先/时间倒序由 Step 5-1 的集成测试覆盖，`%`/`_`/`!` 由用例 9 覆盖，软删除由用例 6，页面/空态/窄屏由 Task 3 的 13 个前端用例，零 @media 由 `breakpoint.spec.js`，V3 由 Step 5-8） |

结论：spec 各小节均能指向至少一个 Step，无遗漏条目。

### 2. 占位符扫描

对全篇执行了以下模式的检查：`TBD|TODO|待补|类似 Task|同上|见 Task [0-9]|补充错误处理|酌情`。

- 无 `TBD` / `TODO` / 「类似 Task N」/ 「同上」；所有代码块都是可直接粘贴的完整文件或精确 before/after。
- 两处「数据依赖」内容不是占位符，且各只有一个数据来源：
  - `docs/DEVELOPMENT.md` §3.1 的文件数/行数与 §3.2 的分布 → Task 4 Step 4-3/4-4 给出 `git ls-files | wc -l` 与实测命令。
  - §8 的三条提交哈希 → Task 4 开头给出 `git log --format='%h %s' -4`，并注明必须替换为真实短哈希。
- 一处「运行时取值」：Task 5 的 `<N>`（Jenkins 构建号）由 Step 5-5 的 `ls -t` 输出确定，Step 5-6/5-9/5-10 都要求换成实际值后才写入文档，仓库里不会出现尖括号。
- 修复记录：初稿把「标题优先」用例写成「标题命中后建」，等于同时满足时间倒序，测不出 CASE 排序；已改为「标题命中先建、正文命中后建」，只有 CASE 生效才能通过。

### 3. 类型与命名一致性

| 名字 | 定义处 | 使用处 | 一致 |
| --- | --- | --- | --- |
| `GET /api/posts/search` | Task 1 `PostController` | Task 3 `postApi.search`、Task 3 `SearchView`、Task 4 文档 §3.4、Task 5 冒烟 | ✅ |
| `keyword` / `page` / `size` 参数名 | Task 1 Step 1-7 | Task 3 `postApi.search` 调用、Task 5 冒烟 URL | ✅ |
| `PostSummaryResponse.snippet` | Task 1 Step 1-4 | Task 1 Step 1-6（`setSnippet`）、Task 1 Step 1-5（断言）、Task 3 `SearchView`（`p.snippet`）、Task 4 §3.4 | ✅ |
| `PageResponse` 访问器 `content()/page()/size()/totalElements()/totalPages()/last()` | 既有 record | Task 1 集成测试断言 | ✅ |
| `normalizeSearchKeyword` / `escapeLike` / `snippetOf` / `SNIPPET_RADIUS` | Task 1 Step 1-6 | 仅 `PostService.search` 内部调用 | ✅ |
| `searchByKeyword(pattern, pageable)` | Task 1 Step 1-2 | Task 1 Step 1-6 服务调用、Step 1-1 仓库测试 | ✅ |
| `highlightSegments(text, keyword)` → `Array<{text,hit}>` | Task 2 `utils/search.js` | Task 3 `PostListItem`（标题、片段）、Task 3 spec 的 `mark.hit` 断言 | ✅ |
| `buildSnippet(content, keyword, radius = 40)` | Task 2 `utils/search.js` | Task 3 `SearchView` 的 `:snippet`、Task 3 spec 的 `data-snippet` 断言 | ✅ |
| `postApi.search(params)` | Task 3 Step 3-2 | Task 3 `SearchView`、`api/index.spec.js` 新用例 | ✅ |
| 路由 `name: 'search'`、路径 `/search`、query 键 `q` | Task 3 Step 3-7 | `HeaderSearch`（push/replace）、`SearchView`（`route.query.q`）、Task 5 冒烟 | ✅ |
| `.hit` / `.post-snippet` 类名 | Task 3 Step 3-9 样式 | 同 Step 模板与 spec 断言 | ✅ |
| 错误文案「搜索关键词不能为空」「搜索关键词不能超过 50 个字符」 | Task 1 Step 1-6 | Task 1 Step 1-5 集成测试、Task 4 §3.4 契约表 | ✅ |
| `SearchView` 三种空态文案 | Task 3 Step 3-6 模板 | Task 3 `SearchView.spec.js` 断言、Task 4 §3.5 页面说明 | ✅ |

### 4. 歧义与解决方式

1. **spec §4.1 说列表字段「与现有列表完全一致」，但 §5.2/§5.4/§10 要求展示「正文命中片段」——`PostSummaryResponse` 里没有 `content`。** 解决：给 `PostSummaryResponse` 增加一个**可空** `snippet` 字段，只由 `PostService.search` 填充（正文命中位置左右各 80 字符的窗口），其它列表接口保持 `null`。理由是三条替代都更差：(a) 列表 DTO 返回完整正文——搜索页一页 10 条，论坛帖正文可达数十 KB，且 T15 记录正文无长度上限，响应会随正文膨胀；(b) 前端逐条调 `/api/posts/{id}` 取正文——N+1 请求，且详情接口会把浏览量 +1；(c) 只用标题造片段——正文命中的行永远没有片段，§10 的「片段高亮」验收过不去。为把改动压到最小：服务端只截窗口，**不清洗 Markdown、不加省略号**，清洗/省略号/高亮统一在 `buildSnippet` 一处实现，避免中英两套规则漂移。
2. **spec §6.3 估计「后端 14 类 / 约 102 例」，按本计划实际是 13 类 / 105 例。** 解决：验收以「新增 1 个测试类 + 15 个用例」为准（12→13 类，90→105 例；其中 4 例是 Task 1 质量修复补的片段边界/长度/大小写/JSON 契约用例）；spec 的 102 例估算与实测不同，且 14 类需要再新增一个测试类（本 spec 的用例都落在 `SearchFlowIntegrationTest` 与 `RepositoryTests`）。文档统一写实测值，若后续实测与估算再有出入，按 Task 5 Step 5-9 的规则「先改文档数字再提交」。
3. **spec 只写 `escapeLike` / `normalizeSearchKeyword` 是「私有 static」，没说放哪个类。** 解决：放 `PostService`——`escapeLike` 的入参是 service 归一化后的关键词、`normalizeSearchKeyword` 抛 `BusinessException`，两者都依赖 service 的错误语义；放 `util` 反而要让 util 依赖 `exception`。
4. **spec 没说 `page`/`size` 非数字的 400 文案从哪来。** 解决：复用 `GlobalExceptionHandler.handleTypeMismatch` 的既有文案「参数格式不正确: page」——它已由 tag 接口的回归用例固定，不新增分支；计划不重复测这条（既有 `TagFlowIntegrationTest` 已覆盖同一条处理链）。
5. **spec §5.4 写 `:snippet="buildSnippet(...)"`，但没说 snippet 的来源。** 解决：`buildSnippet(p.snippet || '', q)`——先用服务端窗口（有则用以定位上下文），再交给同一个函数清洗/加省略号/供 `PostListItem` 高亮；`p.snippet` 为空串时返回 `''`，`PostListItem` 不渲染片段行。
6. **搜索页的 `page` 是否进 URL。** 解决：不进。与首页一致——`page` 是组件内部状态，只有 `q` 进地址栏；这样「后退」回到上一次关键词而不是上一页，且避免翻页时 `:key="$route.fullPath"` 重建组件导致重复请求（本轮不修改 `App.vue` 的 key 策略）。
7. **`escapeLike` 是否要处理反斜杠。** 解决：不需要——JPQL 用 `ESCAPE '!'` 显式指定了转义符，`\` 在 `LIKE` 里没有特殊含义（PostgreSQL 的 `standard_conforming_strings=on` 下它只是普通字符），只转义 `!`、`%`、`_` 即可。
8. **「标题命中优先」与「置顶优先」的冲突。** 解决：搜索结果**不加权置顶**（spec §4.2 注释明确「置顶不加权」）；置顶排序仍只用于首页/标签页的 `pinnedFirstSort()`。因此 `search` 不调用 `pinnedFirstSort()`，`PageRequest` 不带 `Sort`。

### 5. 风险提示（写给执行者）

- **`PageRequest` 一定不能带 Sort**：JPQL 已含 `ORDER BY`，再传 `Sort` 会被 Spring Data 追加成第二个 `ORDER BY`，H2 与 PostgreSQL 都会报 SQL 语法错。这是 Task 1 最容易写错的一行。
- **`ESCAPE '!'` 与 `escapeLike` 必须成对**：只改一边会让 `%`/`_`/`!` 的用例（Order 9）红；改 JPQL 里的转义符时务必同步改 `escapeLike` 的字符集与用例里的 `100!%` / `100!!`。
- **`Collectors.toMap` 不接受 null 值**：`snippetOf` 必须返回空串。若改成返回 `null`，`PostService.search` 会在有标题命中行时抛 NPE。
- **`breakpoint.spec.js` 要求全 `src` 恰好 6 处 `@media (max-width: 768px)` 且禁止 `min-width`**：本轮新增的 `HeaderSearch.vue`、`SearchView.vue` 与 `PostListItem.vue` 的 `.hit`/`.post-snippet` 都没有媒体查询，实施时不要「顺手」加窄屏样式；窄屏差异走 `useResponsive()` + `v-if`。
- **`v-html` 是红线**：高亮必须走文本分段 + `<mark>`；关键词来自 URL，用 `v-html` 等于把反射型 XSS 的入口交给用户。
- **`SearchView` 的请求竞态**：`requestSeq` 的 `finally` 里也要判断 `seq === requestSeq` 才清 `loading`，否则慢的旧请求会把新请求的 loading 关掉；`SearchView.spec.js` 的「过期响应」用例专门钉这条。
- **`HeaderSearch` 的输入框同步方向**：`watch(route.query.q, ..., { immediate: true })` 只负责 URL → 输入框；输入框 → URL 只由 `submit()` 负责。不要加双向 `watch` 写 URL，否则每敲一个字符都会 `push` 一条历史记录。
- **H2 与 PostgreSQL 的差异**：`ESCAPE '!'`、`CASE ... ORDER BY`、`LOWER()` 在两边行为一致；但 H2 不会执行 V3（Flyway 关闭），所以「迁移能跑」只能由 Task 5 的 raspi 冒烟证明，别把 Step 5-1 的全绿当成迁移验证。
- **上线顺序**：V3 由容器启动时 Flyway 执行，唯一前置条件是 `forum` 角色在目标库有 `CREATE` 权限（spec §3.2 已实测：pg_trgm 1.6 trusted=true、`forum` 有 CREATE）。若某天换库后 `CREATE EXTENSION` 报权限错误，兜底是 `ALTER DATABASE javaforum OWNER TO forum;` 或由超级用户先执行一次 `CREATE EXTENSION pg_trgm;`——扩展已存在时 V3 的 `IF NOT EXISTS` 会跳过。
- **回滚口径**：本轮无需回滚数据库。旧版本后端不认识 V3 创建的索引也不受影响；真要清理时按部署手册 §4 的 `DROP INDEX` 执行（保留扩展）。
