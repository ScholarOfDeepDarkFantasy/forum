// E2E 数据引导：只走公开的 HTTP 契约，不依赖数据库、不假设库里有板块或帖子。
//
// 已知限制（见 docs/DEVELOPMENT.md §4.17）：
// - 板块软删除：帖子删除是软删除（行仍在），posts.board_id 有外键，
//   所以「自动创建的板块」无法在清理时删掉 —— 首次在空库运行时创建的
//   「E2E 板块」会作为专用板块保留下来，之后每轮复用它，不再增长。
// - 清理只删除本轮创建的帖子；删除失败必须让测试失败（配合
//   playwright.config.js 的 failOnFlakyTests，避免被重试吞掉）。
const BASE_URL = (process.env.E2E_BASE_URL || 'http://127.0.0.1:5173').replace(/\/+$/, '')
const isLocal = /^https?:\/\/(127\.0\.0\.1|localhost|\[::1\])(:\d+)?(\/|$)/i.test(BASE_URL)
const ADMIN_USERNAME = process.env.E2E_ADMIN_USERNAME || 'admin'
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD || 'admin123456'
const REQUEST_TIMEOUT_MS = 15_000

// 公网环境绝不能默默用默认密码：缺凭据直接在加载阶段失败。
if (!isLocal && (!process.env.E2E_ADMIN_USERNAME || !process.env.E2E_ADMIN_PASSWORD)) {
  throw new Error(
    '[E2E] 远程环境必须显式提供 E2E_ADMIN_USERNAME / E2E_ADMIN_PASSWORD，' +
      '拒绝使用默认 admin 凭据',
  )
}

async function request(path, { method = 'GET', token, body } = {}) {
  let res
  try {
    res = await fetch(`${BASE_URL}/api${path}`, {
      method,
      headers: {
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    })
  } catch (err) {
    const hint = err?.name === 'TimeoutError' ? `（超时 ${REQUEST_TIMEOUT_MS}ms）` : ''
    throw new Error(
      `[E2E] ${method} ${path} 请求失败${hint}，BASE_URL=${BASE_URL}，请确认后端已启动: ${err.message}`,
    )
  }
  const text = await res.text()
  let payload = null
  try { payload = text ? JSON.parse(text) : null } catch { /* 非 JSON 保留原文 */ }
  if (!res.ok || payload?.success !== true) {
    throw new Error(`[E2E] ${method} ${path} -> HTTP ${res.status}: ${payload?.message || text.slice(0, 200)}`)
  }
  return payload.data
}

const markdownContent = () =>
  ['# E2E 标题', '', '**加粗** 的正文，用于验证 Markdown 渲染。', '',
   '```js', "const e2e = 'ok'", '```', '',
   '![E2E 图片](https://picsum.photos/seed/javaforum-e2e/320/180)'].join('\n')

export async function bootstrapData() {
  const stamp = Date.now()
  const title = `E2E 冒烟 ${stamp}`
  const credentials = { username: ADMIN_USERNAME, password: ADMIN_PASSWORD }
  const auth = await request('/auth/login', { method: 'POST', body: credentials })
  if (!auth?.token) throw new Error('[E2E] 登录成功但响应里没有 token')
  const token = auth.token

  let boardId
  let boardName
  let boardCreated = false
  const boards = await request('/boards', { token })
  if (Array.isArray(boards) && boards.length > 0) {
    if (!boards[0]?.id) throw new Error('[E2E] GET /boards 返回了没有 id 的板块')
    if (!boards[0]?.name) throw new Error('[E2E] GET /boards 返回了没有 name 的板块（首页板块筛选需要它）')
    boardId = boards[0].id
    boardName = boards[0].name
  } else {
    const createdName = `E2E 板块 ${stamp}`
    const board = await request('/boards', {
      method: 'POST',
      token,
      body: { name: createdName, description: 'Playwright 空库时自动创建，作为 E2E 专用板块保留', sortOrder: 999 },
    })
    if (!board?.id) throw new Error('[E2E] 创建板块成功但响应里没有 id')
    boardId = board.id
    boardName = board.name || createdName
    boardCreated = true
    console.log(`[E2E] 空库，已创建并保留 E2E 专用板块 boardId=${boardId}（软删除外键限制，无法在清理时删除）`)
  }

  let post
  try {
    post = await request('/posts', {
      method: 'POST',
      token,
      body: { boardId, title, content: markdownContent() },
    })
  } catch (err) {
    const keep = boardCreated ? `；自动创建的 E2E 板块 boardId=${boardId} 已保留（无需手工删除）` : ''
    throw new Error(`${err.message}\n[E2E] 发帖失败，boardId=${boardId}${keep}`)
  }
  if (!post?.id) throw new Error('[E2E] 发帖成功但响应里没有 id')

  const cleanup = async () => {
    const failures = []
    // 登出用例会让服务端递增 tokenVersion，bootstrap 时拿到的 token 随即失效（401）。
    // 清理前重新登录换一枚与当前版本匹配的 token；失败时仍用旧 token 尽力清理并记录。
    let cleanupToken = token
    try {
      const fresh = await request('/auth/login', { method: 'POST', body: credentials })
      if (fresh?.token) cleanupToken = fresh.token
    } catch (err) {
      failures.push(`[E2E] 清理前重新登录失败: ${err.message}`)
    }
    // 不把 404 当作清理成功：本轮的帖子是刚创建的，DELETE 返回 404 通常意味着
    // base path / 路由 / postId 错了，帖子其实还在库里；吞掉就会假绿。
    const remove = async (path) => {
      try {
        await request(path, { method: 'DELETE', token: cleanupToken })
      } catch (err) {
        failures.push(err.message)
      }
    }
    await remove(`/posts/${post.id}`)
    if (failures.length) {
      const boardHint = boardCreated
        ? '（自动创建的 E2E 板块按设计保留，无需手工删除）'
        : '（使用的是库中已有板块，请勿删除该板块）'
      throw new Error(
        `[E2E] 清理失败，boardId=${boardId} postId=${post.id}${boardHint}:\n${failures.join('\n')}`,
      )
    }
  }

  return { boardId, boardName, postId: post.id, title, boardCreated, adminToken: token, credentials, cleanup }
}
