const BASE_URL = (process.env.E2E_BASE_URL || 'http://127.0.0.1:5173').replace(/\/+$/, '')
const ADMIN_USERNAME = process.env.E2E_ADMIN_USERNAME || 'admin'
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD || 'admin123456'

async function request(path, { method = 'GET', token, body } = {}) {
  const res = await fetch(`${BASE_URL}/api${path}`, {
    method,
    headers: {
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
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
  let boardCreated = false
  const boards = await request('/boards', { token })
  if (Array.isArray(boards) && boards.length > 0) {
    boardId = boards[0].id
  } else {
    const board = await request('/boards', {
      method: 'POST',
      token,
      body: { name: `E2E 板块 ${stamp}`, description: 'Playwright 自动创建，测试后删除', sortOrder: 999 },
    })
    boardId = board.id
    boardCreated = true
  }

  let post
  try {
    post = await request('/posts', {
      method: 'POST',
      token,
      body: { boardId, title, content: markdownContent() },
    })
  } catch (err) {
    if (boardCreated) await request(`/boards/${boardId}`, { method: 'DELETE', token }).catch(() => {})
    throw err
  }
  if (!post?.id) throw new Error('[E2E] 发帖成功但响应里没有 id')

  const cleanup = async () => {
    const failures = []
    const remove = async (path) => {
      try {
        await request(path, { method: 'DELETE', token })
      } catch (err) {
        if (!/HTTP 404/.test(err.message)) failures.push(err.message) // 已手工删除视为清理成功
      }
    }
    await remove(`/posts/${post.id}`)
    if (boardCreated) await remove(`/boards/${boardId}`)
    if (failures.length) throw new Error(`[E2E] 清理失败，请手工删除 board=${boardId} post=${post.id}:\n${failures.join('\n')}`)
  }

  return { boardId, postId: post.id, title, boardCreated, adminToken: token, credentials, cleanup }
}
