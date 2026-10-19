# JavaForum 第三轮功能设计：Markdown 渲染 + 标签检索

- 日期：2026-10-19
- 状态：待评审（设计已逐节与用户确认）
- 范围：帖子正文 GFM Markdown 渲染（编辑页实时分栏预览、详情页渲染） + 标签总览页与标签帖子页
- 明确不做：评论 Markdown 渲染；图片上传；标签编辑入口扩展；全文搜索；WebSocket
- 用户决策：
  - Markdown 仅覆盖帖子正文，GFM 全量（含表格/代码块/链接/图片），评论保持纯文本
  - 编辑页用实时分栏预览（桌面左右分栏，窄屏切「编辑/预览」tab，沿用 768px 断点）
  - 标签做成「总览页 + 标签帖子页」，帖子卡片/详情里的标签可点击跳转
  - 方案组合：Markdown 客户端渲染（markdown-it + DOMPurify + highlight.js）；标签复用现有帖子分页管线

## 1. 背景与目标

JavaForum 已完成 MVP、体验迭代轮与部署环境化（raspi + Jenkins + rootless podman + nginx，`http://raspi/forum/`）。
`docs/DEVELOPMENT.md` §5.2 列出的第三轮候选中，Markdown 渲染与标签检索是成本最低、对写作与内容组织收益最直接的两项。
本轮目标：

1. 帖子正文支持 GFM Markdown，编辑时实时预览，历史纯文本帖子仍能正常阅读。
2. 提供标签总览（按热度排序）与标签帖子列表，让 `post_tags` 里已存在的数据真正可用。

不引入新的存储结构：帖子正文继续按 Markdown 源文本存 `TEXT`，读时渲染；标签数据沿用 `tags` / `post_tags` 表。

## 2. 非目标

- 不做评论 Markdown 渲染（评论保持纯文本，避免评论列表变重与二次 XSS 面）。
- 不做图片上传/粘贴上传；Markdown 图片仅支持外链 URL。
- 不新增标签维护后台（标签仍由发帖时的 `resolveTags` 自动创建）。
- 不改权限模型：新接口均为公开 GET。
- 不做数据库迁移；不动部署工程（Dockerfile / compose / nginx / Jenkinsfile 均无变更）。

## 3. 现状与约束

- 后端：Spring Boot 3.5，`PostService` 已有「两步分页」管线——`PostRepository.searchIds` 先按条件分页取骨架，
  `findWithAssociations` 再按 id 批量抓 board/author/tags，最后 `hydrate + pinnedFirstSort + toSummary` 组装列表项
  （含当前用户的 `liked/favorited`）。标签帖子列表必须复用这条管线，避免集合关联与分页同用导致
  `HHHH90003004` 与内存截取。
- `TagRepository` 目前只有 `findByName`；`Tag.name` 唯一、长度 30，发帖时统一 `trim + Locale.ROOT 小写`。
- 安全配置：`SecurityConfig.PUBLIC_GET` 已含 `/api/posts/**` 等，`HttpMethod.GET` 全放行；新接口仍显式列入 `PUBLIC_GET`，避免未来收紧时被遗漏。
- 前端：Vue 3 + Vite + Pinia + Element Plus；`useResponsive()` 提供 `isMobile`（768px）；
  `usePostActions` 是点赞/收藏的唯一状态源；首页帖子行目前内联在 `HomeView.vue`。
- 前端无任何 Markdown/XSS 相关依赖，需新增：`markdown-it`、`dompurify`、`highlight.js`。

## 4. Markdown 渲染设计

### 4.1 渲染管线（前端，读时渲染）

新增 `frontend/src/utils/markdown.js`，导出 `renderMarkdown(src)`：

- `markdown-it` 实例配置：`html: false`（不解析原始 HTML）、`linkify: true`、`breaks: true`（老帖子的单换行不被合并）。
- 代码块：`highlight.js/lib/core`，仅注册 `java、javascript、typescript、bash、json、xml、css、sql、python、yaml、markdown`；
  未注册语言按纯文本输出，不抛错。
- 自定义 renderer：
  - 链接：外链补 `target="_blank" rel="noopener noreferrer"`（锚点/相对链接保持默认行为）。
  - 图片：补 `loading="lazy"`。
- 输出前统一 `DOMPurify.sanitize(html, { USE_PROFILES: { html: true }, ADD_ATTR: ['target'] })`；
  渲染异常时降级为 HTML 转义后的纯文本（`escapeHtml`）。

新增 `frontend/src/styles/markdown.css`，定义 `.markdown-body` 作用域内的标题/段落/列表/引用/行内与块代码/表格/图片/链接样式，
全部使用既有设计令牌（`--jf-*`）；highlight.js 使用自带 github 主题（仅代码块着色）。在 `main.js` 中于 `common.css` 之后引入。

### 4.2 详情页

`PostDetailView.vue`：

- 新增 computed `renderedContent = renderMarkdown(post.content)`。
- 正文容器由 `{{ post.content }}` 改为 `v-html="renderedContent"`，class 改为 `post-content markdown-body`，
  移除正文的 `white-space: pre-wrap`（评论内容样式保持不变）。
- 标签 chips 改为可点击（见 §5.5）。

### 4.3 编辑页（实时分栏预览）

`PostEditView.vue`：

- 桌面（>768px）：`.editor-grid` 两栏 grid，左侧原 `el-input` textarea（rows 18），右侧预览面板 `v-html="previewHtml"`；
  预览为空时显示占位提示「预览将实时显示在这里」。
- 窄屏：用 `useResponsive().isMobile` 切换为「编辑 / 预览」两个 tab（`el-tabs`），共用同一份 `form.content` 与同一个渲染函数。
- 正文上方/下方增加一行 Markdown 语法提示：标题、列表、代码块、表格、链接、图片（GFM）。
- 表单校验与提交逻辑不变；`tags` 选择器不变。

### 4.4 兼容与安全

- 历史帖子为纯文本：`breaks: true` 保证原有换行观感；但以 `#`、`1.`、`-`、`>` 等开头的行会被解释为 Markdown，属预期行为（写入 README 已知取舍）。
- 双层 XSS 防线：`html: false` + DOMPurify；`javascript:` 链接由 markdown-it 的 `validateLink` 拒绝。
- 图片仅外链，`loading="lazy"`，CSS 限制 `max-width: 100%`，避免撑破布局。

## 5. 标签检索设计

### 5.1 API 契约（公开 GET）

| 接口 | 参数 | 返回 |
|---|---|---|
| `GET /api/tags` | `keyword`（可选，标签名模糊）、`page`（默认 0）、`size`（默认 30，上限 100） | `Result<PageResponse<TagResponse>>`，项 `{ id, name, postCount }`，按 `postCount` 降序、`name` 升序 |
| `GET /api/tags/posts` | `name`（必填，标签名）、`page`（默认 0）、`size`（默认 10，上限 50） | `Result<PageResponse<PostSummaryResponse>>`，置顶优先 + `createTime` 倒序，结构与首页列表一致 |

标签名用 query 参数而非 `/{name}` 路径：标签名来自用户输入，可能含 `/` 等字符，Tomcat 默认拒绝编码后的 `%2F`。

### 5.2 后端实现

新增：

- `dto/tag/TagResponse.java`：`Long id`、`String name`、`long postCount`。
- `service/TagService.java`：
  - `PageResponse<TagResponse> list(String keyword, int page, int size)`：`blankToEmpty`，执行聚合查询后映射、按（postCount desc, name asc）排序、在服务层分页（标签量级小，规避 group by 与 Pageable 同用时的 count 查询问题）。
  - 名称归一化：`trim + lowercase(Locale.ROOT)`（与 `PostService.resolveTags` 一致）。
- `controller/TagController.java`：`@RequestMapping("/api/tags")`，两个 GET；`size` 钳制（`Math.min(size, 100)` / `Math.min(size, 50)`）。

修改：

- `repository/TagRepository`：新增聚合查询
  `SELECT t.id, t.name, COUNT(p.id) FROM Post p JOIN p.tags t WHERE p.deleted = false AND (:keyword = '' OR LOWER(t.name) LIKE LOWER(CONCAT('%', :keyword, '%'))) GROUP BY t.id, t.name ORDER BY COUNT(p.id) DESC, t.name ASC`
  （只统计未删除帖子；无帖子的标签不出现）。
- `repository/PostRepository`：新增 `searchByTagName(name, pageable)`——
  `SELECT p FROM Post p JOIN p.tags t WHERE p.deleted = false AND t.name = :name`，`@EntityGraph(attributePaths = {"board", "author"})`（不 join tags 集合）。
- `service/PostService`：新增 `listByTag(String name, int page, int size)`——先 `tagRepository.findByName`，不存在直接返回空页（200，totalElements=0）；
  存在则 `searchByTagName` → `findWithAssociations` → `hydrate + pinnedFirstSort + toSummary`（复用现有私有管线）。
- `config/SecurityConfig`：`PUBLIC_GET` 显式加入 `/api/tags/**`。

### 5.3 前端路由与入口

- `router/index.js` 新增：`/tags`（name `tags`）、`/tags/:name`（name `tag-posts`），均公开（无 meta）。
- `App.vue` 头部品牌旁新增「标签」导航入口，游客与登录用户均可见。
- `api/index.js` 新增 `tagApi = { list: (params) => api.get('/tags', { params }), posts: (name, params) => api.get('/tags/posts', { params: { name, ...params } }) }`。

### 5.4 页面

- `views/TagsView.vue`：标题「标签」+ 关键字过滤框（回车/清空触发，走 API `keyword`）+ 标签云（`el-tag`，显示名称与帖子数，按数量降序）
  + 分页（size 30）+ 空状态；点击标签进入 `/tags/<encodeURIComponent(name)>`。
- `views/TagPostsView.vue`：顶部「标签：`name`」+ 返回标签总览链接 + 帖子列表 + 分页（size 10）+ 空状态「该标签下暂无帖子」。
- 抽公共组件 `components/PostListItem.vue`：承载首页帖子行（置顶/精华标记、标题、作者/板块/相对时间、标签 chips、浏览/评论数、点赞按钮）。
  - Props：`post`；事件：`like`（父级处理登录跳转/请求）。行点击进详情；标签 chip `@click.stop` 跳标签页。
  - `HomeView.vue` 与 `TagPostsView.vue` 共用。

### 5.5 标签可点击

- `HomeView.vue`（经 `PostListItem`）与 `PostDetailView.vue` 的标签 chips 变为可点击：`@click.stop` → `/tags/<encodeURIComponent(name)>`。
- 标签名在服务端统一小写存储，前端跳转时仅做 URL 编码。

### 5.6 边界

- 未知标签（含被删除帖子导致已无帖子的标签）：帖子页返回空页，前端显示空状态，不报错。
- `keyword` 只过滤标签名，不搜帖子内容。
- 标签总览只展示至少有 1 篇未删除帖子的标签。

## 6. 测试计划

后端新增 `TagFlowIntegrationTest`（沿用现有 `@SpringBootTest + MockMvc` 模式）：

- 造数：以管理员身份创建带标签的帖子。
- `GET /api/tags`：返回项含 `postCount`，按数量降序；`keyword` 过滤生效；分页字段正确。
- `GET /api/tags/posts?name=`：返回帖子 `tags` 字段完整、置顶优先；未知标签返回空页；`size` 超上限被钳制。
- `RepositoryTests`：补一条标签聚合查询断言。

前端新增/更新：

- 新增 `utils/markdown.spec.js`：标题/表格/代码块/链接渲染；`<script>`、`onerror`、原始 HTML、`javascript:` 链接被清除；外链 `target/rel`；图片 `loading=lazy`。
- 新增 `TagsView.spec.js`、`TagPostsView.spec.js`、`PostListItem.spec.js`、`PostEditView.spec.js`（预览同步、窄屏 tab）。
- 更新 `PostDetailView.spec.js`（正文按 Markdown 渲染）、`HomeView.spec.js`（改用组件后结构变化）、`api/index.spec.js`（tagApi 路径）。

基线（以实际执行结果为准，完成后回填 `docs/DEVELOPMENT.md` §3.2）：后端 8 类/30 例 → 9 类/约 36 例；前端 15 文件/108 例 → 约 21 文件/130+ 例。

## 7. 文档与上线

- `README.md`：功能列表补 Markdown 渲染与标签页；注明「帖子正文按 Markdown 渲染、评论为纯文本」。
- `docs/DEVELOPMENT.md`：版本 1.3；§4 增加本轮记录（新增接口与文件）；§5.1 技术债复查；§5.2 勾掉这两项；§3 基线更新；§8 变更日志（hash 回填按约定单独 docs 提交）。
- 部署：无 Dockerfile/compose/nginx/Jenkinsfile 变更；push 后 Jenkins `pollSCM` 5 分钟内自动重建并部署到 `http://raspi/forum/`。
- 依赖：`frontend/package.json` 新增 `markdown-it`、`dompurify`、`highlight.js`，`pnpm-lock.yaml` 同步更新。

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| 抽 `PostListItem` 改变首页行为/测试 | 组件只做展示，点赞与跳转由父级传参/事件驱动；`HomeView.spec.js` 同步更新并跑全量前端测试 |
| highlight.js 包体偏大 | 只注册常用语言（core + 语言子包），不引全量入口 |
| 老帖子被 Markdown 语法改写观感 | `breaks: true` + 文档记录已知取舍 |
| 标签聚合 group by 与分页冲突 | 聚合查询不分页，服务层排序分页；标签量级小，可后续换 native 分页 |
| 新接口漏放进 `PUBLIC_GET` | 显式加入 `/api/tags/**` 并加集成测试断言匿名可访问 |

## 9. 验收标准

1. 后端 `mvn -B test` 全绿，新增标签集成测试通过。
2. 前端 `pnpm test` 全绿；`pnpm build`（`VITE_BASE_PATH=/forum/`）成功。
3. 详情页：标题/列表/表格/代码块/链接/图片按 GFM 渲染；含 `<script>`/`onerror` 的内容不产生可执行节点。
4. 编辑页：桌面左右分栏实时预览；窄屏可在「编辑/预览」间切换；保存后详情页渲染一致。
5. 标签总览按帖子数降序展示并可点击进入；标签帖子页分页、置顶优先、空标签显示空状态。
6. 部署到 raspi 后 `http://raspi/forum/` 上述功能可用（Jenkins 自动构建成功）。
