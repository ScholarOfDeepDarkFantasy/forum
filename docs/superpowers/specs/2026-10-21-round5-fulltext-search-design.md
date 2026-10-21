# 第五轮迭代设计：全文搜索（帖子）

> 状态：已确认设计，待写实现计划
> 日期：2026-10-21
> 关联需求：用户「进行下一步开发」；本轮从 DEVELOPMENT §5.2 候选中选择「全文搜索」（另一候选 WebSocket 实时通知留待后续轮次）
> 关联文档：`docs/DEVELOPMENT.md`、`docs/deployment/README.md`

## 1. 背景与目标

### 1.1 背景

- 首页现有的关键字输入框只做**板块内过滤**（`GET /api/posts?boardId=&keyword=`），结果按「置顶优先 + 时间倒序」排列，既不能跨板块搜索，也不区分「标题命中」与「正文命中」。
- 帖子正文是 Markdown 源文本，正文里的关键字命中常常出现在用户看不到的位置（代码块、链接 URL 等），仅按时间排序会把最相关的结果排到后面。
- 数据库当前没有任何检索索引，`LIKE '%kw%'` 走全表顺序扫描；当前数据量（16 帖）无感，但「全文搜索」作为功能名需要落到索引与排序上。

### 1.2 本轮目标

1. 新增独立搜索结果页 `/search?q=<keyword>`，支持分页、结果计数、空态与命中高亮。
2. 顶栏新增搜索框（桌面为输入框，窄屏为搜索图标按钮），回车/点击进入搜索页；首页原有的板块内关键字过滤保持不动。
3. 新增公开接口 `GET /api/posts/search`：标题或正文命中即返回，**标题命中优先**，同档按发帖时间倒序。
4. 新增 Flyway `V3` 迁移：启用 `pg_trgm` 扩展，为 `posts.title` / `posts.content` 建 GIN 三元组索引（部分索引，排除软删除行），让 ≥3 字的查询走索引。
5. 补齐测试、文档与上线验收记录。

### 1.3 非目标

- 不做分词/中文词法分析（不引入 zhparser、pg_jieba 等）：中文按子串匹配，不产生「词」级排序。
- 不做相关度评分（`similarity()` / `ts_rank`）：排序规则固定为「标题命中优先 + 时间倒序」。
- 不做搜索建议、历史记录、热门搜索词。
- 不搜索标签名、作者昵称、评论内容。
- 不改首页板块内过滤的行为与排序（包括其 `LIKE` 不做通配符转义这一点，见 §8 技术债）。

## 2. 总体方案

```
浏览器
  ├─ App.vue 顶栏搜索框（桌面输入框 / 窄屏图标按钮）
  │     └─ 回车 → /search?q=xxx
  └─ SearchView.vue (/search)
        ├─ 读 route.query.q，输入框就地修改并同步 URL
        ├─ postApi.search({ keyword, page, size })
        └─ 结果：PostListItem（标题高亮）+ 片段（正文命中上下文高亮）+ 分页

后端
  GET /api/posts/search?keyword=&page=&size=
        └─ PostController.search → PostService.search
              ├─ 关键词规范化（trim / 空串 400 / 超过 50 字符 400）
              ├─ LIKE 通配符转义（! % _ → !x，ESCAPE '!'）
              ├─ PostRepository.searchByKeyword(pattern, pageable)
              │     WHERE deleted=false AND (title LIKE OR content LIKE)
              │     ORDER BY CASE 标题命中 THEN 0 ELSE 1 END, createTime DESC
              └─ 复用 hydrate + mapSummaries → PageResponse<PostSummaryResponse>

数据库
  V3__post_search_trgm_index.sql（与 V1/V2 同目录；H2 测试关闭 Flyway）
        ├─ CREATE EXTENSION IF NOT EXISTS pg_trgm
        ├─ GIN (LOWER(title) gin_trgm_ops) WHERE deleted = false
        └─ GIN (LOWER(content) gin_trgm_ops) WHERE deleted = false
```

## 3. 现状与约束

### 3.1 现有代码

- `src/main/java/org/song/forum/repository/PostRepository.java:searchIds`：板块内分页查询，`@EntityGraph(board, author)`，`keyword` 传空串表示无关键字（不能传 null，规避 pgJDBC 把 null 推断成 bytea 的问题）。
- `src/main/java/org/song/forum/service/PostService.java`：`list/myPosts/listByTag` 都遵循「两步分页」——先只取骨架（不 join `tags` 集合，避免 `HHH90003004`），再用 `hydrate(Page<Post>)` 批量抓关联，最后 `mapSummaries` 批量补 `liked/favorited`。
- `src/main/java/org/song/forum/controller/PostController.java`：`@GetMapping`（列表）、`@GetMapping("/{id}")`（详情）、`/mine`、`/favorites/mine`；`/search` 作为字面量路径优先级高于 `/{id}`，与 `/mine` 同模式。
- `src/main/java/org/song/forum/config/SecurityConfig.java`：已有 `requestMatchers(HttpMethod.GET).permitAll()`，新增 GET 接口无需改安全配置。
- `src/main/java/org/song/forum/exception/GlobalExceptionHandler.java`：`BusinessException` → 400；`MissingServletRequestParameterException` → 400；`MethodArgumentTypeMismatchException` → 400。
- `src/main/java/org/song/forum/util/PageResponse.java`：`record(content,page,size,totalElements,totalPages,last)` + `of(Page)`。
- 前端 `frontend/src/views/HomeView.vue`：工具栏内联关键字过滤（`postApi.list`），`PostListItem` 列表，`el-pagination` + `.jf-pager`。
- 前端 `frontend/src/components/PostListItem.vue`：标题/作者/标签/计数/点赞按钮；本轮新增可选高亮。
- 前端 `frontend/src/App.vue`：header 内 `brand`、`标签` nav-link、`nav-actions`（ThemeToggle、通知、用户菜单、登录/注册）；`<router-view :key="$route.fullPath" />`。
- 前端 `frontend/src/router/index.js`：路由表（懒加载组件），`/tags`、`/tags/:name` 已存在。
- 迁移：`src/main/resources/db/migration/V1__init_schema.sql`、`V2__notification_comment_id.sql`；`application.yml` 的 `spring.flyway.locations: classpath:db/migration`。
- 测试基线：后端 12 个测试类 / 90 例（Windows 上 1 例 POSIX 权限测试 skip）；前端 25 个 spec / 211 例；接口 35 个。

### 3.2 数据库环境（已实测，2026-10-21）

- raspi 上的 PostgreSQL 15.18；`pg_trgm` 可用版本 1.6，`trusted = true`。
- 应用连接用户 `forum` 拥有该库的 `CREATE` 权限与 `public` schema 的 `CREATE` 权限，因此 `CREATE EXTENSION IF NOT EXISTS pg_trgm` 可以**以应用迁移身份**执行，无需超级用户手工预装。
- 该库中当前只装了 `plpgsql`。
- 本地开发库（若使用 PostgreSQL）会执行 V3；测试库（H2）不支持 `CREATE EXTENSION`，因此 `RepositoryTests` 关闭 Flyway（`spring.flyway.enabled=false`），不执行任何迁移。

## 4. 后端设计

### 4.1 接口契约

`GET /api/posts/search`

| 参数 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `keyword` | 是（缺失 → 400 缺少必需参数） | — | 去首尾空白后 1–50 个字符（按 code point 计） |
| `page` | 否 | `0` | 负数按 0 处理 |
| `size` | 否 | `10` | 夹在 1–50 |

成功响应：`Result<PageResponse<PostSummaryResponse>>`，`PostSummaryResponse` 字段与现有列表完全一致（`id/boardId/boardName/authorId/authorUsername/authorNickname/title/likeCount/commentCount/viewCount/isPinned/isExquisite/tags/createTime/liked/favorited`）。

错误：`Result.fail(message)` + HTTP 400

- 缺失 `keyword`：`缺少必需参数: keyword`（全局处理器既有文案）
- 空白：`搜索关键词不能为空`
- 超过 50 字符：`搜索关键词不能超过 50 个字符`
- `page/size` 非数字：`参数格式不正确: page`（全局处理器既有文案）

### 4.2 仓库层

`PostRepository` 新增：

```java
/**
 * 全文搜索分页第一步：只取骨架，不 join tags 集合（原因同 {@link #searchIds}）。
 * pattern 由 service 层做过 LIKE 通配符转义（! % _ → !x），SQL 用 ESCAPE '!'。
 * 排序：标题命中优先（CASE 0/1），同档按 createTime 倒序；置顶不加权。
 */
@Query("""
        SELECT p FROM Post p
        WHERE p.deleted = false
          AND (LOWER(p.title) LIKE CONCAT('%', :pattern, '%') ESCAPE '!'
               OR LOWER(p.content) LIKE CONCAT('%', :pattern, '%') ESCAPE '!')
        ORDER BY CASE WHEN LOWER(p.title) LIKE CONCAT('%', :pattern, '%') ESCAPE '!' THEN 0 ELSE 1 END,
                 p.createTime DESC
        """)
@EntityGraph(attributePaths = {"board", "author"})
Page<Post> searchByKeyword(@Param("pattern") String pattern, Pageable pageable);
```

要点：

- `LOWER(...)` 两侧都小写化：`pattern` 由 service 层 `toLowerCase(Locale.ROOT)`；H2（PostgreSQL 兼容模式）同样支持 `ESCAPE '!'`。
- `pageable` 必须是**无排序**的 `PageRequest.of(page, size)`：排序已写在 JPQL 里；若再带 `Sort`，Spring Data 会拼接第二个 `ORDER BY`。
- 不 join `tags`，保持两步分页模式；`hydrate` 仍负责抓 tags。
- `ESCAPE '!'` 让用户输入里的 `%`/`_` 变成字面量，避免「输入 % 命中全部」与索引失效。

### 4.3 服务层

`PostService` 新增：

```java
public PageResponse<PostSummaryResponse> search(String rawKeyword, int page, int size) {
    String keyword = normalizeSearchKeyword(rawKeyword);
    int safePage = Math.max(page, 0);
    int safeSize = Math.min(Math.max(size, 1), 50);
    String pattern = escapeLike(keyword.toLowerCase(Locale.ROOT));
    Page<Post> skeletons = postRepository.searchByKeyword(pattern, PageRequest.of(safePage, safeSize));
    return PageResponse.of(mapSummaries(hydrate(skeletons)));
}
```

私有工具（`static`，便于单测）：

- `normalizeSearchKeyword(String raw)`：`null`/`trim().isEmpty()` → `throw new BusinessException("搜索关键词不能为空")`；`codePointCount > 50` → `throw new BusinessException("搜索关键词不能超过 50 个字符")`；返回 trim 后原串（保留大小写，只用于转义）。
- `escapeLike(String kw)`：逐字符，遇到 `!` `%` `_` 先补一个 `!`。

### 4.4 控制器层

`PostController` 新增（放在 `list` 之后、`detail` 之前，字面量路径先于 `/{id}`）：

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

`page/size` 的夹取在 controller 与 service 各做一次（与现有 `list` 的 `Math.min(size, 50)` 风格一致，service 层兜底）。

### 4.5 数据库迁移

新增 `src/main/resources/db/migration/V3__post_search_trgm_index.sql`（与 V1/V2 同目录，保持 `application.yml` 的 `spring.flyway.locations` 不变）：

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

**测试隔离（实现时必做）**：`@DataJpaTest` 会加载 `FlywayAutoConfiguration`（已核对 Boot 3.5.6 的 `AutoConfigureDataJpa.imports`），H2 上执行 `CREATE EXTENSION` 会失败。因此给 `src/test/java/org/song/forum/repository/RepositoryTests.java` 的 `@DataJpaTest(properties = {...})` 增加一项 `"spring.flyway.enabled=false"`，与其它集成测试（`TagFlowIntegrationTest` 等）保持一致——该测试的 schema 本来就由 `spring.jpa.hibernate.ddl-auto=create-drop` 创建，不依赖迁移。

验证策略：

- 实现时跑 `mvn -B test` 全绿即证明 H2 侧已隔离（RepositoryTests 不执行 Flyway）。
- 上线后在 raspi 上确认：`\dx pg_trgm` 存在；`\d posts` 能看到两个 GIN 索引；用 `EXPLAIN` 确认 ≥3 字查询在数据量足够时可走索引（16 帖时规划器选择顺序扫描属正常，不视为失败）。

## 5. 前端设计

### 5.1 API 客户端

`frontend/src/api/index.js` 的 `postApi` 增加：

```js
// 全文搜索：标题命中优先、时间倒序；keyword 必填（后端 400 校验）
search: (params) => api.get('/posts/search', { params })
```

### 5.2 搜索工具

新增 `frontend/src/utils/search.js`：

- `highlightSegments(text, keyword)` → `Array<{ text, hit }>`：大小写不敏感地找出全部出现位置，返回交替片段；用 `indexOf` 循环实现（不构造正则，避免用户输入 `(` `.` `*` 等正则元字符导致的异常与转义问题）；`keyword` 为空/无命中时返回 `[{ text, hit: false }]`。
- `buildSnippet(content, keyword, radius = 40)` → `string`：先做 Markdown 轻量清洗（去掉代码围栏标记、行内反引号、标题/引用/列表标记、链接只留文字、图片只留 alt），再把换行与连续空白折叠为单个空格；有命中时取第一处命中前后的窗口并加 `…`，无命中取开头窗口。
- 片段与高亮都在**文本层**完成，模板用 `v-for` 渲染 `<mark>`，**不引入 `v-html`**，避免搜索关键词反射型 XSS。

### 5.3 顶部搜索组件

新增 `frontend/src/components/HeaderSearch.vue`：

- 桌面（`useResponsive().isMobile === false`）：`el-input` 宽约 220px，placeholder「搜索帖子」，`maxlength="50"`，回车/点击图标 → `router.push({ name: 'search', query: { q } })`；已在 `/search` 时，若 `q` 与输入一致则不重复跳转。
- 窄屏（`isMobile === true`）：`el-button` 搜索图标 → 进入 `/search`（页面内自带输入框）。
- 用 `v-if` + `useResponsive()` 切换两种形态，**不新增 `@media` 字面量**（`frontend/src/styles/breakpoint.spec.js` 要求全仓恰好 6 处）。
- 当路由变化到 `/search` 时，用 `watch(() => route.query.q)` 把输入框同步为当前关键词。
- `App.vue` 在 `标签` nav-link 之后挂载 `<HeaderSearch />`。

### 5.4 搜索页

新增 `frontend/src/views/SearchView.vue`，路由 `/search`（`name: 'search'`，懒加载，公开访问）：

- 顶部标题「搜索」+ 输入框（`maxlength=50`，回车/按钮触发），提交时 `router.replace({ name: 'search', query: { q } })`（`replace` 避免每次搜索都塞历史记录）。
- 数据：`q = route.query.q`；`postApi.search({ keyword: q, page: page.value, size: 10 })`；`page` 为内部状态（与首页一致，不写进 URL），切换关键词时重置为 0。
- 展示：`共 N 条结果`；`PostListItem` 列表（传 `:keyword="q"` 高亮标题、传 `:snippet="buildSnippet(...)"` 显示正文命中上下文）；`el-pagination` + `.jf-pager`（复用首页样式类）。
- 空态分三种：未输入关键词（提示「输入关键词开始搜索」）、无结果（「没有找到与 “xxx” 相关的帖子」）、请求失败（`ElMessage` 已由拦截器提示，页面显示「搜索失败，请稍后重试」）。
- 请求竞态：沿用 `requestSeq` 模式（自增序号丢弃过期响应），但注意 `App.vue` 的 `<router-view :key="$route.fullPath" />` 已保证关键词变化时组件重建，序号仅作为防御。
- 结果里的点赞沿用 `usePostActions`（与首页一致）；`syncFromPost` 播种共享状态。

### 5.5 PostListItem 扩展

`frontend/src/components/PostListItem.vue` 新增两个可选 props：

- `keyword?: string`（默认 `''`）：非空时标题用 `highlightSegments` 渲染 `<mark class="hit">`。
- `snippet?: string`（默认 `''`）：非空时在标题下显示一行片段（同样高亮）。

首页/标签页不传这两个 props，行为不变。

## 6. 测试计划

### 6.1 后端（新增约 1 个测试类 + 1 个仓库测试方法）

`src/test/java/org/song/forum/controller/SearchFlowIntegrationTest.java`（`@ActiveProfiles("test")`、`@SpringBootTest(RANDOM_PORT)`、`TestRestTemplate`，模式参考 `TagFlowIntegrationTest`）：

1. 游客可搜索：`GET /api/posts/search?keyword=...` → 200。
2. 缺失 `keyword` → 400，message 含「keyword」。
3. 空白关键词 → 400，message「搜索关键词不能为空」。
4. 51 字符关键词 → 400，message「不能超过 50 个字符」。
5. 标题命中排在正文命中之前（构造两条：title 含词、content 含词；断言第一条 id 是 title 命中）。
6. 正文命中可被搜索到；软删除后不再命中（作者删除后重搜为空）。
7. 分页：3 条命中、`size=2` → `totalElements=3`、`content.length=2`、`last=false`。
8. `size=999` → 响应 `size=50`；`page=-1` → 响应 `page=0`。
9. `%`/`_` 字面量：标题「100%完成」可被 `keyword=100%` 命中，而「1000完成」不被命中（证明转义生效）。
10. 标题命中优先 + 同档时间倒序（两条标题命中，新的在前）。

`RepositoryTests` 增加一个 `searchByKeyword` 的 H2 用例（覆盖 JPQL 的 `CASE ... ORDER BY` 与 `ESCAPE '!'` 语法，避免只能靠集成测试兜底）。

### 6.2 前端（新增 3 个 spec，更新 2 个）

- `frontend/src/utils/search.spec.js`：大小写不敏感、多次命中、正则元字符（`.`/`(`）按字面量、空关键词、无命中；`buildSnippet` 的窗口/省略号/Markdown 清洗/空白折叠。
- `frontend/src/components/HeaderSearch.spec.js`：回车跳转 query、空输入不跳转、窄屏渲染图标按钮、已在该路由不重复跳转、路由变化同步输入框。
- `frontend/src/views/SearchView.spec.js`：读 `route.query.q` 调 `postApi.search`、结果数文案、三种空态、分页触发第二次请求（page=1）、高亮片段传入 `PostListItem`、过期响应被序号丢弃。
- `frontend/src/components/PostListItem.spec.js`：新增 `keyword` 高亮标题、`snippet` 渲染与高亮；不传时行为不变。
- `frontend/src/api/index.spec.js`：`postApi.search` 的 URL 与 params。

### 6.3 基线

- 前端：25 个 spec / 211 例 → 预计 28 个 spec / 约 235 例（以实测为准）。
- 后端：12 个测试类 / 90 例（Windows 1 skip）→ 预计 14 个测试类 / 约 102 例（以实测为准）。
- 接口：35 → 36。

## 7. 文档与上线

### 7.1 文档

- `README.md`：功能清单加「全文搜索（标题/正文，标题命中优先）」；目录结构加 `SearchView.vue`/`HeaderSearch.vue`/`utils/search.js`；测试基线更新；已知限制加「中文按子串匹配，无分词与相关度排序；1–2 字查询不走索引」。
- `docs/DEVELOPMENT.md`：§2.2 第五轮标记；§3.2 基线；§3.4 接口数 36；新增 §4.10「全文搜索」；§5.2 把全文搜索改为已完成；§7 记录本轮决策；§8 changelog 行（哈希待回填）。
- `docs/deployment/README.md`：说明应用启动时 Flyway 自动执行 V3（以 `forum` 身份 `CREATE EXTENSION pg_trgm` 并建 GIN 索引；已确认具备权限），首次部署后用 `\dx`/`\d posts` 验证；1–2 字查询退化顺序扫描属预期；回滚只需 drop 索引（扩展保留无害）。

### 7.2 上线流程

1. 全量验证：`mvn -B clean test`、`cd frontend && pnpm test`、`VITE_BASE_PATH=/forum/ pnpm build`。
2. push 到 `origin/master` → raspi Jenkins `javaforum` 轮询（≤5 分钟）自动构建、部署（镜像 `localhost/forum-backend:<build>`）。
3. raspi 冒烟：`/forum/api/health` UP；`/forum/api/posts/search?keyword=...` 返回结果且标题命中在前；`/forum/search?q=...` 页面 200；`/forum/api/posts/search?keyword=%20` → 400；`/forum/api/posts/search` 缺失参数 → 400；`EXPLAIN`/`\d posts` 确认索引存在。
4. 文档回填：§4.10 验收、§8 哈希（单独 docs 提交并 push，Jenkins 会再构建一次，属预期）。

## 8. 风险、取舍与技术债

| 风险 | 说明 | 对策 |
| --- | --- | --- |
| 中文短词不走索引 | `pg_trgm` 三元组要求查询串 ≥3 字符，1–2 字中文（如「测试」）退化为顺序扫描 | 当前 16 帖数据量无感；README 与部署文档写明；后续可加 `tsvector`+中文分词或独立搜索引擎 |
| 无相关度排序 | 只有「标题命中/正文命中」两档 | 本轮明确取舍；后续可接 `similarity()` 或全文检索引擎 |
| H2 与 PostgreSQL 方言差异 | H2 兼容模式支持 `ESCAPE '!'`，但不支持 `CREATE EXTENSION` | V3 与 V1/V2 同目录仅 PostgreSQL 可执行；RepositoryTests 关闭 Flyway；H2 用例覆盖 JPQL 语法 |
| 扩展安装权限 | 生产/本地库若 `forum` 无 `CREATE` 权限则迁移失败 | raspi 已实测通过；README/部署文档给出 `ALTER DATABASE ... OWNER` 或超级用户 `CREATE EXTENSION` 的手工兜底 |
| 查询性能下降 | `OR` 跨列 + `LIKE` 可能放弃索引 | 已建两列 GIN；部分索引排除软删除行；后续按需改为 UNION 两段查询 |
| 搜索页公开 | 游客可搜索 | 与现有 GET 公开策略一致，不新增敏感数据面 |

### 技术债（本轮不做，记录在案）

- T13：首页板块内过滤的 `LIKE` 未做通配符转义，与新搜索接口行为不一致（用户输入 `%` 会命中全部）。
- T14：搜索无相关度/无分词；中文按子串匹配。
- T15：`content` 无长度上限（既有债），超大正文的 `LIKE` 与索引维护成本需在内容治理时一并处理。

## 9. 决策记录

| 决策 | 结论 | 理由 |
| --- | --- | --- |
| 入口形态 | 独立 `/search` 页 + 顶栏搜索框；首页内联过滤保留 | 搜索成为可收藏、可分享的页面；首页过滤是「板块内筛选」，语义不同 |
| 排序 | 标题命中优先，同档时间倒序 | 稳定、可预期、可通过 SQL `CASE` 表达，不依赖中文分词质量 |
| 匹配范围 | 标题 + 正文 | 与现有 `LIKE` 语义一致，本轮只加索引与排序，不扩大数据面 |
| 索引方案 | `pg_trgm` 1.6 + GIN 三元组索引（部分索引） | 已实测扩展 `trusted` 且 `forum` 有 `CREATE` 权限，可随迁移自动安装；H2 测试有清晰隔离路径 |

## 10. 验收标准

- [ ] `GET /api/posts/search` 满足 §4.1 契约（含 4 类 400 与分页夹取）。
- [ ] 标题命中结果整体排在正文命中之前；同档按 `createTime` 倒序。
- [ ] `%`/`_`/`!` 按字面量匹配，不会命中全部。
- [ ] 软删除帖子不出现在结果中。
- [ ] `/search?q=` 页面可用：结果计数、分页、标题/片段高亮、三种空态、窄屏可用。
- [ ] 顶栏搜索框可用，移动端显示图标按钮，不新增 `@media` 字面量（断点门禁 6 处不变）。
- [ ] V3 迁移在 raspi 上自动执行成功（`pg_trgm` + 两个 GIN 索引），H2 测试全绿。
- [ ] 后端/前端测试与构建全绿，基线写入文档；push 后 Jenkins 构建成功并完成冒烟。
