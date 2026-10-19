import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

// 同 HomeView.spec.js：useRoute/useRouter 是 <script setup> 里的注入式依赖，
// `mocks` 选项替换不了，直接 mock 模块本身最轻。
const push = vi.fn()
const route = { params: { id: '1' }, hash: '', fullPath: '/posts/1', name: 'post-detail' }
vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ push })
}))

// 组件会调用 ElMessage.success/info，jsdom 下让真实实现去挂 DOM 没必要；
// 这里只需保证调用不抛，并可在需要时断言。
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), info: vi.fn(), warning: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm: vi.fn(() => Promise.resolve()) }
}))

vi.mock('@/api', () => ({
  postApi: {
    detail: vi.fn(),
    like: vi.fn(),
    favorite: vi.fn(),
    pin: vi.fn(),
    exquisite: vi.fn(),
    remove: vi.fn()
  },
  commentApi: { list: vi.fn(), create: vi.fn(), remove: vi.fn() },
  notificationApi: { unreadCount: vi.fn() }
}))

import PostDetailView from './PostDetailView.vue'
import { postApi, commentApi } from '@/api'
import { usePostActions } from '@/composables/usePostActions'
import { useAuthStore } from '@/stores/auth'

/** Element Plus 全部打桩；el-button 把 type 落到 data-type 上，便于断言点赞/收藏的选中态 */
const stubs = {
  'el-card': { template: '<div><slot name="header" /><slot /></div>' },
  'el-tag': { template: '<span><slot /></span>' },
  'el-divider': { template: '<hr />' },
  'el-button': { props: ['type'], template: '<button :data-type="type"><slot /></button>' },
  'el-input': { template: '<textarea />' },
  'el-avatar': { template: '<span><slot /></span>' },
  'el-alert': { template: '<div />' },
  'el-icon': { template: '<i><slot /></i>' },
  'el-empty': { template: '<div />' },
  'el-pagination': { template: '<div />' }
}

let pinia

function mountDetail() {
  return mount(PostDetailView, { global: { plugins: [pinia], stubs } })
}

/** 清空微任务队列，等待 onMounted / 事件处理器里的 await 链结束 */
function flush() {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

function detailPayload(over = {}) {
  return {
    id: 1,
    title: '帖子',
    content: '正文',
    authorId: 9,
    authorUsername: 'u',
    boardName: 'b',
    createTime: '2026-10-16T10:00:00',
    viewCount: 1,
    commentCount: 0,
    tags: [],
    isPinned: false,
    isExquisite: false,
    liked: false,
    favorited: false,
    likeCount: 0,
    favoriteCount: 0,
    ...over
  }
}

/**
 * 每个用例使用独立 postId：usePostActions 的 stateMap 与 locallyToggled 是模块级
 * 共享状态，复用同一 id 会让断言依赖执行顺序（同 HomeView.spec.js 的约定）。
 */
describe('PostDetailView 互动状态与共享状态同源', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    commentApi.list.mockResolvedValue({ content: [], totalElements: 0 })
  })

  it('首页点赞请求未返回时打开详情，两处状态不打架；请求返回后详情同步点亮', async () => {
    const actions = usePostActions()
    // 首页列表已把该帖播种进共享状态：未点赞、3 个赞
    actions.syncFromPost({ id: 201, likeCount: 3, favoriteCount: 0, liked: false })

    let resolveLike
    postApi.like.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveLike = resolve
        })
    )
    // 首页点下赞：locallyToggled 立即置位（syncFromPost 之后会跳过标志），请求还在飞
    const pending = actions.toggleLike(201)

    postApi.detail.mockResolvedValue(detailPayload({ id: 201, likeCount: 3, liked: false }))
    const wrapper = mountDetail()
    await flush()

    // 请求未返回：详情页与首页列表一致，都是「未点赞 + 3」（旧实现会在此渲染快照，
    // 随后与共享状态永久分裂）
    expect(wrapper.find('.post-actions').text()).toContain('点赞 3')
    expect(wrapper.findAll('.post-actions button')[0].attributes('data-type')).toBe('default')

    resolveLike({ active: true, count: 4 })
    await pending
    await flush()

    // 请求返回：共享状态更新，详情页无需重新请求也跟着点亮
    expect(actions.stateFor(201)).toMatchObject({ liked: true, likeCount: 4 })
    expect(wrapper.find('.post-actions').text()).toContain('点赞 4')
    expect(wrapper.findAll('.post-actions button')[0].attributes('data-type')).toBe('primary')
  })

  it('互动字段实时读共享状态：外部改动立即反映，而不是加载时快照', async () => {
    postApi.detail.mockResolvedValue(
      detailPayload({ id: 202, likeCount: 3, liked: false, favoriteCount: 2, favorited: false })
    )
    const wrapper = mountDetail()
    await flush()

    expect(wrapper.find('.post-actions').text()).toContain('点赞 3')
    expect(wrapper.find('.post-actions').text()).toContain('收藏 2')

    // 模拟首页列表随后把共享状态切成「已赞 4 / 已藏 5」
    const s = usePostActions().stateFor(202)
    s.liked = true
    s.likeCount = 4
    s.favorited = true
    s.favoriteCount = 5
    await flush()

    expect(wrapper.find('.post-actions').text()).toContain('点赞 4')
    expect(wrapper.find('.post-actions').text()).toContain('收藏 5')
    const btns = wrapper.findAll('.post-actions button')
    expect(btns[0].attributes('data-type')).toBe('primary')
    // 收藏按钮的 warning 选中态是任务要求保留的行为
    expect(btns[1].attributes('data-type')).toBe('warning')
  })

  it('版主置顶整体替换 post.value 后，互动字段仍取自共享状态、不被返回体带偏', async () => {
    const auth = useAuthStore()
    auth.token = 'fake-token'
    auth.user = { id: 9, username: 'u', roles: ['MODERATOR'] }

    const actions = usePostActions()
    // 用户未在本会话操作过该帖 → 详情接口的 liked/likeCount 被采信进共享状态
    actions.syncFromPost({ id: 203, likeCount: 0, favoriteCount: 0 })
    postApi.detail.mockResolvedValue(detailPayload({ id: 203, likeCount: 3, liked: true }))

    const wrapper = mountDetail()
    await flush()
    expect(wrapper.find('.post-actions').text()).toContain('点赞 3')
    expect(wrapper.findAll('.post-actions button')[0].attributes('data-type')).toBe('primary')

    // 置顶接口返回的响应整体替换 post.value（其中 likeCount 为 0、liked 为 false）
    postApi.pin.mockResolvedValue(
      detailPayload({ id: 203, isPinned: true, likeCount: 0, liked: false })
    )
    const moderatorButtons = wrapper.findAll('.mod-actions')[1].findAll('button')
    await moderatorButtons[0].trigger('click')
    await flush()

    expect(postApi.pin).toHaveBeenCalledWith(203, true)
    expect(wrapper.find('.post-actions').text()).toContain('点赞 3')
    expect(wrapper.findAll('.post-actions button')[0].attributes('data-type')).toBe('primary')
  })

})

/** Markdown 渲染与标签导航：与互动状态无关，单独成组 */
describe('PostDetailView Markdown 渲染与标签导航', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    commentApi.list.mockResolvedValue({ content: [], totalElements: 0 })
  })

  it('正文按 Markdown 渲染而不是原样输出', async () => {
    postApi.detail.mockResolvedValue(detailPayload({ id: 301, content: '## 小标题\n\n**加粗**' }))
    const wrapper = mountDetail()
    await flush()

    const content = wrapper.find('.post-content')
    expect(content.classes()).toContain('markdown-body')
    expect(content.html()).toContain('<h2>小标题</h2>')
    expect(content.html()).toContain('<strong>加粗</strong>')
  })

  it('点击标签 chip 跳转标签帖子页，标签名经过编码', async () => {
    postApi.detail.mockResolvedValue(detailPayload({ id: 302, tags: ['spring-boot', 'C#'] }))
    const wrapper = mountDetail()
    await flush()

    const chips = wrapper.findAll('.post-tags .tag-link')
    expect(chips).toHaveLength(2)
    await chips[0].trigger('click')
    expect(push).toHaveBeenCalledWith('/tags/spring-boot')
    await chips[1].trigger('click')
    expect(push).toHaveBeenCalledWith('/tags/C%23')
  })
})
