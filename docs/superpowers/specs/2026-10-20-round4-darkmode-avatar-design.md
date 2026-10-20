# 第四轮功能设计：深色模式 + 头像上传

- 日期：2026-10-20
- 状态：已与用户逐节确认（深色模式、头像上传、测试/上线三节均确认）
- 对应需求：用户 m01481「进行下一步开发」
- 关联文档：`docs/DEVELOPMENT.md`、`docs/deployment/README.md`

## 1. 背景与范围

第三轮（Markdown 渲染 + 标签检索）已于 2026-10-19 上线。本轮做两个相对独立、可独立验收的功能：

1. **深色模式**：全站可切换浅色/深色，默认跟随操作系统，用户手动选择后持久化。
2. **头像上传**：用户可在个人资料页上传头像图片，服务端统一裁剪为 256×256 JPEG 存盘，由 nginx 直接托管；支持移除头像。

用户已确认的关键决策：

| 决策点 | 结论 |
|---|---|
| 深色模式控制方式 | 跟随系统 + 可手动覆盖并存盘（localStorage `jf_theme`） |
| 深色实现机制 | `html.dark` 类 + CSS 变量双套 + Element Plus 官方暗色 |
| 头像存储 | 主机目录 `/pd/Program/forum/uploads/avatars/`，nginx 以 `/forum/uploads/` 直出；不进数据库、不用对象存储 |
| 头像处理 | 服务端居中裁剪 + 缩放为 256×256 JPEG；接受 JPEG/PNG，按魔数校验，≤2MB |
| 头像移除 | 支持，移除后回退首字母头像 |
| 资料页录入 | 只保留上传控件；历史外链 `avatarUrl` 仍可渲染，但不再提供新外链输入 |

## 2. 目标与非目标

### 目标

- 未手动选择过主题时，跟随系统 `prefers-color-scheme`；手动选择浅色/深色后持久化，并可在菜单中恢复「跟随系统」。
- 深色模式下，自研页面、Element Plus 组件、Markdown 正文与代码高亮全部为暗色，无亮色残留。
- 头像上传：校验类型/大小 → EXIF 方向校正 → 居中裁剪 → 256×256 JPEG → 存主机目录 → 全站头像（顶栏、评论、回复、资料页）显示。
- 头像可移除，移除后回到首字母头像；头像文件在容器重建/重新部署后仍存在。
- 不引入 Flyway 迁移，不改数据库表结构。

### 非目标

- 不做主题色自定义（只做浅色/深色两档）。
- 不把主题偏好存后端/跨设备同步（本设备 localStorage 即可）。
- 不做头像审核、多尺寸/缩略图、CDN 或对象存储。
- 不做评论 Markdown 渲染（第三轮已明确评论保持纯文本）。
- 不新增 E2E 测试框架；手工浏览器验收。

## 3. 现状与约束

### 前端

- Element Plus 2.14.7 自带官方暗色主题：`element-plus/theme-chalk/dark/css-vars.css`，选择器为 `html.dark`，已确认本地 `node_modules` 中存在。
- 自研样式绝大多数使用 `frontend/src/styles/tokens.css` 的颜色令牌；仍有 4 处一次性字面量：
  - `frontend/src/styles/markdown.css:57` 代码块底色 `#f6f8fa`；
  - `frontend/src/views/NotificationsView.vue:142` 未读行底色 `#f0f7ff`；
  - `frontend/src/views/NotificationsView.vue:158` 与 `frontend/src/views/PostDetailView.vue:391,401` 弱化文字 `#c0c4cc`；
  - `frontend/src/views/PostDetailView.vue:418` 锚点高亮底色 `#fff8e6`。
- `frontend/src/main.js` 目前只引入 `highlight.js/styles/github.css`（亮色主题）。
- `frontend/index.html` 没有主题引导脚本，需新增内联脚本防首屏闪烁。
- 主题组件不应引入新的 `@media` 字面量：`frontend/src/styles/breakpoint.spec.js` 精确要求全 `src` 恰有 6 处 `@media (max-width: 768px)`，并禁止 `min-width`。若确实需要第 7 处，必须同步 `EXPECTED_MEDIA_BREAKPOINTS`、`tokens.css` 说明与 README 清单——本轮设计不需要新增断点。

### 头像

- 后端只有 `PUT /api/auth/profile` 接受字符串 `avatarUrl`（`UpdateProfileRequest` 长度 255），没有文件上传与静态资源托管。
- `users.avatar_url` 列已存在（`User.java:33-34`，`length = 255`），无需迁移。
- 前端 `frontend/src/views/ProfileView.vue:87-89` 是「头像 URL」输入框；顶栏（`App.vue:60`，size 28）、评论（`PostDetailView.vue:279`，size 26）、回复（`PostDetailView.vue:293`，size 22）均为首字母 `<el-avatar>`。
- 部署侧：`docker-compose.yml` 没有卷挂载；`deploy/nginx/forum.conf` 没有 `/forum/uploads/`；`Dockerfile` 运行用户为 1001，未创建上传目录；`deploy/deploy.sh` 没有目录属主处理。

### 测试基线

- 后端：9 个测试类 / 39 个用例（`mvn -B clean test`）。
- 前端：20 个测试文件 / 143 个用例（`pnpm test`）。
- 接口：33 个（31 + 标签 2）。

### 部署形态（不可忽略）

- 推送 `origin/master` 后，raspi 上 Jenkins 每 5 分钟轮询并自动构建+部署（http://raspi/forum/）。
- rootless podman，容器内 uid 1001；主机 `pi` 的 subuid 范围为 `100000:65536`，容器 uid 1001 对应主机 subuid，挂载目录属主需用 `podman unshare chown` 处理。
- 主机 `/pd` 有约 1TB 空闲，头像文件放 `/pd` 不影响根盘 5G 余量。

## 4. 深色模式设计

### 4.1 状态模型

新增 `frontend/src/composables/useTheme.js`（模块级单例，供组件与入口共用）：

- 状态：`mode: 'system' | 'light' | 'dark'`。
- 持久化：localStorage 键 `jf_theme`，只写显式选择（`'light' | 'dark'`）；`mode = 'system'` 时删除该键。读取失败/值非法一律回退 `'system'`。
- 计算：`isDark = mode === 'dark' || (mode === 'system' && systemPrefersDark)`，其中 `systemPrefersDark` 来自 `window.matchMedia('(prefers-color-scheme: dark)')`，仅当 `mode === 'system'` 时监听其 `change`。
- 应用：`watchEffect` 中执行 `document.documentElement.classList.toggle('dark', isDark)`，并同步 `document.documentElement.style.colorScheme = isDark ? 'dark' : 'light'`（原生滚动条/表单控件跟随）。
- `setMode(m)`；`toggle()` 在 `light`/`dark` 之间切换（若当前 `system`，则按当前实际效果取反）。
- 导出 `initTheme()`：在 `app.mount` 前调用，确保挂载前 class 已就位；`jsdom` 下 `matchMedia` 缺失时按 `false` 处理（不抛错）。

### 4.2 颜色令牌

`frontend/src/styles/tokens.css` 保持 `:root` 亮色定义，并新增 `html.dark { ... }` 覆盖块。颜色令牌全集（新增的 4 个语义令牌均有消费方，不预留空令牌）：

| 令牌 | 亮色 | 暗色 | 消费方 |
|---|---|---|---|
| `--jf-color-primary` | `#409eff` | `#409eff` | 全局主色、链接、激活态 |
| `--jf-color-text-1` | `#303133` | `#e5eaf3` | 正文 |
| `--jf-color-text-3` | `#909399` | `#a3a6ad` | 次要文字 |
| `--jf-color-border` | `#e4e7ed` | `#4c4d4f` | 边框/分隔线 |
| `--jf-color-bg-page` | `#f5f6f8` | `#0a0a0a` | `body` 页面底 |
| `--jf-color-bg-card` | `#ffffff` | `#1d1e1f` | 顶栏、`el-card` 场景 |
| `--jf-color-bg-hover` | `#f8f9fb` | `#262727` | hover 行、表头、行内代码底 |
| `--jf-color-code-bg`（新） | `#f6f8fa` | `#161b22` | Markdown `pre` 代码块 |
| `--jf-color-text-weak`（新） | `#c0c4cc` | `#8d9095` | 评论时间/失效评论/通知时间 |
| `--jf-color-unread-bg`（新） | `#f0f7ff` | `#1b2b3d` | 未读通知行 |
| `--jf-color-anchor-bg`（新） | `#fff8e6` | `#2f2a1a` | 评论锚点高亮 |

暗色取值对齐 Element Plus 暗色调色板（`--el-bg-color-page:#0a0a0a`、`--el-bg-color-overlay:#1d1e1f`、`--el-text-color-primary:#e5eaf3` 等），避免自研区域与 EP 组件出现两套灰阶。

替换 4 处字面量：`markdown.css`、`PostDetailView.vue`（2 处）、`NotificationsView.vue`（2 处）改为 `var(...)`，并删除这些位置原有的「一次性语义色」说明注释（改为令牌说明）。

新增守卫 spec `frontend/src/styles/tokens.spec.js`：

- `:root` 与 `html.dark` 中 `--jf-color-*` 令牌集合必须完全一致（漏配暗色即红）；
- 每个 `--jf-color-*` 在 `frontend/src` 下至少有一处 `var(--jf-color-xxx)` 消费；
- 色值格式必须为 6 位十六进制（防 `rgb()`/`hsl()` 与大小写漂移）。

### 4.3 Element Plus 暗色集成

`frontend/src/main.js` 导入顺序（顺序本身有语义，后续 spec 会校验）：

```
element-plus/dist/index.css
element-plus/theme-chalk/dark/css-vars.css
./styles/tokens.css
./styles/common.css
./styles/markdown.css
highlight.js/styles/github.css
./styles/highlight-dark.css
./style.css
```

EP 的暗色文件只定义 `--el-*`；页面底色、卡片色、边框来自自研令牌，`html.dark` 覆盖后即联动。`tokens.css` 中现有的 `--el-color-primary: var(--jf-color-primary)` 保留（EP 暗色文件会把 `--el-color-primary` 设为同值 `#409eff`，无冲突）。

### 4.4 代码高亮

- 保留 `github.css`（亮色）。
- 新增 `frontend/src/styles/highlight-dark.css`：逐条复制 highlight.js 11.12 的 `github-dark` 主题规则，所有选择器加 `html.dark` 前缀（如 `html.dark .hljs-keyword`），文件头注明「来自 highlight.js 11.12 github-dark 主题（MIT），由构建期手工同步」。选择器前缀天然只在暗色生效，无需 JS 动态换 `<link>`，无切换闪烁。
- 背景统一交给容器：`markdown.css` 中 `.markdown-body pre { background: var(--jf-color-code-bg) }`，并追加 `.markdown-body pre code.hljs { background: transparent }`，避免 `github*.css` 的 `.hljs` 背景与容器底色叠加出「双层深色」。

### 4.5 开关 UI

新增 `frontend/src/components/ThemeToggle.vue`：

- 触发按钮：圆形文字按钮，图标随当前模式为 `Sunny`（浅色）/ `Moon`（深色）/ `MoonNight`（跟随系统）；`aria-label="切换主题"`，`title` 同步。
- 点击打开 `el-dropdown`，三个菜单项：跟随系统 / 浅色 / 深色；当前 `mode` 项使用 `el-icon` + `Check` 打勾。
- 放在 `App.vue` 顶栏 `.nav-actions` 最左，登录与未登录都显示；不新增任何 `@media`。
- 组件测试（`ThemeToggle.spec.js`）覆盖：默认 system、选择浅色/深色后 html class 与 localStorage、选择跟随系统后删除键、图标与 aria 属性。

### 4.6 防首屏闪烁

`frontend/index.html` 的 `<head>` 内新增内联脚本（在样式与模块脚本之前）：

```html
<script>
  (function () {
    try {
      var saved = localStorage.getItem('jf_theme');
      var dark = saved === 'dark' || (saved !== 'light' && window.matchMedia('(prefers-color-scheme: dark)').matches);
      if (dark) document.documentElement.classList.add('dark');
      document.documentElement.style.colorScheme = dark ? 'dark' : 'light';
    } catch (e) {}
  })();
</script>
```

与 `useTheme` 的重复是刻意的（CSS 到达浏览器前必须已定色）；用 `tokens.spec.js` 或独立断言守住该脚本存在且键名一致。

### 4.7 边界与异常

- `localStorage` 抛错/坏值：忽略并按 `system`。
- `matchMedia` 不存在（jsdom/旧浏览器）：`systemPrefersDark = false`，不崩。
- 多标签页：本轮不做 `storage` 事件同步（记录为技术债）。
- 系统主题在 `system` 模式下来回切换：class 实时跟随，无需刷新。

## 5. 头像上传设计

### 5.1 接口

| 方法 | 路径 | 请求 | 响应 | 权限 |
|---|---|---|---|---|
| POST | `/api/users/me/avatar` | `multipart/form-data`，字段 `file` | `Result<UserResponse>`（含新 `avatarUrl`） | 登录 |
| DELETE | `/api/users/me/avatar` | 无 | `Result<UserResponse>`（`avatarUrl` 为 `null`） | 登录；幂等 |
| GET | `/uploads/**` | 无 | 静态图片 | 公开（后端仅本地开发用，生产由 nginx 直出） |

- 新增 `controller/UserController.java`，`@RequestMapping("/api/users")`，两个方法分别 `@PostMapping("/me/avatar")`、`@DeleteMapping("/me/avatar")`；`@RequestParam("file") MultipartFile file`。
- `PUT /api/auth/profile` 从 `UpdateProfileRequest` 中**删除** `avatarUrl` 字段；`AuthService.updateProfile` 不再 set `avatarUrl`。头像只能通过上传/删除接口变更。
- 历史 `avatarUrl` 为外链的数据继续原样返回、前端原样渲染；不做数据迁移。

### 5.2 存储与部署

- 主机目录：`/pd/Program/forum/uploads/avatars/`，属主由 `podman unshare chown -R 1001:1001` 调整为容器 uid 可写。
- 容器内：`UPLOAD_DIR=/app/uploads`；`docker-compose.yml` 增加：

```yaml
    volumes:
      - "${UPLOAD_HOST_DIR:-./uploads}:/app/uploads"
    environment:
      UPLOAD_DIR: /app/uploads
```

- `Dockerfile`：`RUN mkdir -p /app/logs /app/uploads && chown -R 1001:1001 /app`，并 `ENV UPLOAD_DIR=/app/uploads`。
- `deploy/deploy.sh` 新增：
  1. `mkdir -p "$ROOT/uploads/avatars"`；
  2. `podman unshare chown -R 1001:1001 "$ROOT/uploads"`；
  3. `.env` 中写入/更新 `UPLOAD_HOST_DIR=/pd/Program/forum/uploads`（与 `IMAGE_TAG` 同样的做法）；
  4. 冒烟：`http://127.0.0.1/forum/uploads/nonexistent.jpg` 必须返回 404（alias 丢失时会落到 SPA 回退返回 200）。
- `deploy/nginx/forum.conf` 在 `/forum/` 之前新增：

```nginx
location ^~ /forum/uploads/ {
    alias /pd/Program/forum/uploads/;
    expires 7d;
    access_log off;
    add_header X-Content-Type-Options nosniff;
}
```

- 本地开发：`app.upload-dir: ${UPLOAD_DIR:./uploads}`；后端 `config/WebConfig.java` 用 `addResourceHandlers("/uploads/**")` 映射到 `file:${app.upload-dir}/`（生产被 nginx 覆盖，不影响）；`.gitignore` 增加 `/uploads/`。

### 5.3 图片处理

新增 `util/AvatarImageProcessor.java`（纯函数，可单测）与 `service/AvatarService.java`（编排：读文件、校验、转码、落盘、清旧文件、更新用户）：

1. 校验：文件非空；长度 ≤ 2MB；魔数 JPEG（`FF D8 FF`）或 PNG（`89 50 4E 47 0D 0A 1A 0A`）；不符返回 400「仅支持 JPEG/PNG 图片」。
2. EXIF：用 `metadata-extractor:2.19.0` 读取 Orientation（1–8），按标准映射对 `BufferedImage` 做旋转/镜像（`AvatarImageProcessor.orientationTransform` 纯函数单测 8 种取值）。
3. 解码：`ImageIO.read`；返回 null（损坏/不支持）→ 400「无法解析图片」。
4. 居中裁剪：取 `min(width, height)` 为边长，居中裁正方形；再缩放到 256×256（小图允许放大）。
5. 重编码 JPEG：`ImageIO.getImageWritersByFormatName("jpeg")`，质量 0.9；输出字节。
6. 落盘：文件名 `<UUID>.jpg`，写到 `${app.upload-dir}/avatars/`；启动时/首次上传时 `mkdirs`。
7. 更新用户：`user.setAvatarUrl("/uploads/avatars/<uuid>.jpg")`；如旧值匹配 `/uploads/avatars/` 前缀，删除旧文件（`Path.toRealPath` 校验仍在 `avatars` 目录内）。
8. 删除接口：清空 `avatarUrl` 并删除旧文件；无头像时直接返回当前用户（幂等）。

配置与异常：

- `application.yml` 新增 `spring.servlet.multipart.max-file-size: 2MB`、`max-request-size: 3MB`，以及 `app.upload-dir: ${UPLOAD_DIR:./uploads}`。
- `GlobalExceptionHandler` 新增 `MaxUploadSizeExceededException` → 400「图片不能超过 2MB」。
- 不新增 Flyway 迁移。

### 5.4 前端

- `frontend/src/api/index.js` 新增：

```js
export const userApi = {
  uploadAvatar: (file) => {
    const fd = new FormData()
    fd.append('file', file)
    return api.post('/users/me/avatar', fd)
  },
  removeAvatar: () => api.delete('/users/me/avatar')
}
```

- `frontend/src/utils/avatar.js`：

```js
export function resolveAvatarUrl(url) {
  if (!url) return ''
  if (url.startsWith('/uploads/')) return `${import.meta.env.BASE_URL}${url.slice(1)}`
  return url
}
```

- `frontend/src/components/UserAvatar.vue`：props `url`/`name`/`size`（默认 28）；`el-avatar :size :src="resolveAvatarUrl(url)"`；图片加载失败（`@error`）时回退槽位首字母；无 url 时同样显示首字母。
- 替换使用处：
  - `App.vue` 顶栏头像（28）；
  - `PostDetailView.vue` 评论头像（26）与回复头像（22）；
  - `ProfileView.vue` 资料页预览（64）。
- `ProfileView.vue` 改造：
  - 删除「头像 URL」`el-input`；
  - 新增头像区块：`UserAvatar :size="64"` + 隐藏 `<input type="file" accept="image/jpeg,image/png">` + 「选择图片」/「移除头像」按钮；
  - 客户端预校验：类型必须 `image/jpeg`/`image/png`，大小 ≤2MB，否则 `ElMessage.warning` 且不发请求；
  - 上传中按钮 loading；成功后 `auth.fetchMe()` 刷新全局用户并提示「头像已更新」；
  - 移除成功后 `auth.fetchMe()` 并提示「头像已移除」；
  - 保存资料时不再发送 `avatarUrl`。

### 5.5 安全与限制

- 上传/删除接口仅 `POST`/`DELETE`，落入 `SecurityConfig` 的 `anyRequest().authenticated()`；`GET /uploads/**` 公开。
- 只按魔数接受 JPEG/PNG，统一重编码为 JPEG：不落原始字节，杜绝 SVG 脚本、EXIF 元数据与伪造 Content-Type。
- 文件名纯 UUID，不接受客户端文件名，根除路径穿越。
- 2MB 双保险：Spring multipart 限制 + 服务层长度校验。
- 已知限制（记入技术债）：上传/删除无频率限制；无头像历史版本与审核；单图规格固定 256×256。

## 6. 测试计划

### 6.1 后端（预计 9 类 39 例 → 11 类约 56 例）

- 新增 `src/test/java/org/song/forum/util/AvatarImageProcessorTest.java`：方向映射 1–8；居中裁剪（宽图/高图/方图）；缩放输出恰为 256×256；JPEG/PNG 魔数判定；坏数据返回空。
- 新增 `src/test/java/org/song/forum/controller/AvatarFlowIntegrationTest.java`（H2 + RANDOM_PORT，与既有集成测试一致；`@DynamicPropertySource` 指向 JUnit `@TempDir`，避免污染工作区）：
  1. 未登录上传 → 401；
  2. 上传 JPEG → 200、`avatarUrl` 形如 `/uploads/avatars/*.jpg`、磁盘文件为 256×256 JPEG；
  3. 上传 PNG → 200；
  4. 再次上传 → 旧文件被删除、新文件存在；
  5. 伪造类型（文本文件改名 .jpg）→ 400；
  6. 超过 2MB → 400；
  7. `GET /uploads/<file>` → 200；
  8. 删除 → 200、`avatarUrl` 为 null、文件不存在；
  9. 重复删除 → 200 幂等；
  10. `PUT /api/auth/profile` 不再接受/不再改变头像（`avatarUrl` 字段删除后的编译期保证 + 接口用例）。
- 既有 9 类 39 例保持绿（受影响用例只允许因 `UpdateProfileRequest` 字段删除而调整）。

### 6.2 前端（预计 20 文件 143 例 → 26 文件约 165 例）

- 新增 `frontend/src/composables/useTheme.spec.js`：默认 system；system 下 matchMedia 变化同步 class；选择 dark/light 持久化；坏 localStorage 回退 system；再次选 system 删除键。
- 新增 `frontend/src/components/ThemeToggle.spec.js`：三个菜单项、图标/aria、当前项打勾、点击后 class 与存储变化。
- 新增 `frontend/src/styles/tokens.spec.js`：双套令牌集合一致；每个颜色令牌有消费方；色值格式。
- 新增 `frontend/src/utils/avatar.spec.js`：空值、`/uploads/` 前缀拼 BASE_URL、外链/http/data 原样。
- 新增 `frontend/src/components/UserAvatar.spec.js`：有 url 渲染 `img`；无 url 显示首字母；`error` 后回退首字母。
- 更新 `frontend/src/views/ProfileView.spec.js`：上传成功调用 `userApi.uploadAvatar` 并 `fetchMe`；非法类型/超限不发请求；移除头像调用 `userApi.removeAvatar`；既有收藏用例不变。
- 更新 `frontend/src/api/index.spec.js`：`userApi` 两个端点的方法/路径/FormData 字段。
- 既有 143 例（含 `breakpoint.spec.js` 6 处断点、`common.spec.js` 分页守护、Markdown 14 例）保持绿；不新增 `@media` 字面量。

### 6.3 手工验收（raspi）

1. 未手动选择时切系统主题 → 页面实时跟随；
2. 顶栏切浅色/深色 → 刷新后保持；选「跟随系统」后恢复跟随；
3. 深色下检查首页/详情/编辑预览/标签页/通知/后台/资料页，无亮色残留；代码块、表格、引用、图片均正常；
4. 登录 → 资料页上传手机竖拍照片 → 顶栏/评论头像方向正确、居中正方形；
5. `curl -H 'Host: raspi' http://127.0.0.1/forum/uploads/<file>` → 200；
6. `podman-compose down/up`（或重新部署）后头像仍在；
7. 移除头像 → 顶栏恢复字母头像；
8. 上传 3MB 图片 → 前端拦截（直接打接口则 400）。

## 7. 文档与上线

### 提交拆分（全部直接提交到 master，遵循项目既有约定）

1. `feat: 新增深色模式与主题切换`（tokens.css、useTheme、ThemeToggle、App.vue、index.html、main.js、highlight-dark.css、4 处字面量替换、相关 spec）
2. `feat: 新增头像上传后端接口与图片处理`（pom.xml、UserController、AvatarService、AvatarImageProcessor、WebConfig、SecurityConfig、GlobalExceptionHandler、application.yml、AvatarImageProcessorTest、AvatarFlowIntegrationTest、UpdateProfileRequest/AuthService）
3. `feat: 新增头像上传前端交互与全站头像展示`（api/index.js、utils/avatar.js、UserAvatar、App/PostDetail/Profile 改造、相关 spec）
4. `chore: 部署支持头像上传目录`（Dockerfile、docker-compose.yml、deploy/deploy.sh、deploy/nginx/forum.conf、.gitignore）
5. `docs: 记录第四轮深色模式与头像上传功能`（README、docs/DEVELOPMENT.md、docs/deployment/README.md、本 spec 索引与 changelog 哈希回填）

### 上线

- 推送 `origin/master` → Jenkins 轮询（≤5 分钟）自动构建并部署到 http://raspi/forum/。
- 部署脚本自行创建 `/pd/Program/forum/uploads/avatars` 并校正属主，幂等；首次部署后按 6.3 验收。
- 回滚：`.env` 的 `IMAGE_TAG` 退回上一数字 tag，或 `git revert` 后推送；头像目录独立于镜像，回滚不丢文件。

## 8. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| rootless 挂载目录属主不对（容器 uid 1001 写不进去） | 上传 500 | `deploy.sh` 用 `podman unshare chown -R 1001:1001`；集成测试覆盖写入；上线后立即手工上传验收 |
| nginx location 顺序错误，`/forum/uploads/` 被 SPA 回退吞掉 | 头像 404 或返回 index.html | 用 `^~` 前缀并在 `/forum/` 之前；部署脚本以「不存在的文件必须 404」作守卫 |
| EXIF 读取/旋转在边角图片上出错 | 头像方向错误/异常 | 方向映射纯函数 8 值单测；解码失败统一 400；集成测试用普通 JPEG |
| ImageIO 对 CMYK/渐进式 JPEG 支持有限 | 少数图片上传失败 | 明确返回 400 提示重试/换图；记入技术债，不静默转存 |
| 深色下仍有硬编码色残留 | 视觉割裂 | 令牌化 4 处字面量 + `tokens.spec.js` 双套一致性守卫 + 手工全页面验收 |
| EP 暗色与自研令牌冲突 | 局部亮色/对比度低 | 暗色取值对齐 EP 官方调色板；导入顺序固定并由 spec 校验 |
| 首屏闪烁（先亮后暗） | 观感差 | `index.html` 内联引导脚本；手工验收刷新观察 |
| localStorage 坏值/隐私模式 | 主题异常或脚本报错 | `try/catch` 兜底 system；composable 单测覆盖 |
| 上传接口被滥用（无频率限制） | 磁盘/带宽压力 | 2MB 上限 + 单用户覆盖式存储（不累积）；限流记入技术债 |
| 头像目录未备份 | 换机会丢失头像 | 文档明确路径与备份建议；头像可重新上传，非核心数据 |

## 9. 验收标准

- 前端 `pnpm test` 全绿（预计 ≥26 文件、约 165 例），`VITE_BASE_PATH=/forum/ pnpm build` 成功。
- 后端 `mvn -B clean test` 全绿（预计 11 类、约 56 例）。
- deep-mode：默认跟随系统；手动切换持久化；菜单可恢复跟随系统；所有页面无亮色残留。
- 头像：上传/更换/移除全链路可用；方向正确、居中 256×256；文件经 nginx 直出 200；容器重建后仍在；非法类型/超限返回 400。
- 部署：Jenkins 构建绿灯，`deploy.sh` 幂等可重跑；`/forum/`、`/forum/api/` 既有功能无回归（Markdown、标签、通知、后台）。
- 文档同步：README、DEVELOPMENT §4.9、deployment README、changelog 哈希回填。

## 10. 技术债与后续

- 上传/删除接口缺少用户级频率限制。
- 头像无历史版本/审核，仅覆盖式存储。
- 主题偏好不跨设备同步；多标签页不监听 `storage` 事件。
- 未做头像裁剪 UI（固定居中裁剪，用户无法自选构图）。
- 未引入 E2E（Playwright）覆盖主题与上传链路。
- 管理员用户列表仍无头像列（本轮不扩）。
