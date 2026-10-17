import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

// 同其它视图测试：useRoute/useRouter 是 <script setup> 的注入式依赖，
// mocks 选项替换不了，mock 模块本身最省事。
const push = vi.fn()
vi.mock('vue-router', () => ({
  useRouter: () => ({ push })
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), info: vi.fn(), warning: vi.fn(), error: vi.fn() }
}))

vi.mock('@/api', () => ({
  notificationApi: {
    mine: vi.fn(),
    unreadCount: vi.fn(),
    markAllRead: vi.fn(),
    markRead: vi.fn()
  }
}))

import NotificationsView from './NotificationsView.vue'
import { notificationApi } from '@/api'

const stubs = {
  'el-card': { template: '<div><slot name="header" /><slot /></div>' },
  'el-button': { template: '<button><slot /></button>' },
  'el-tag': { template: '<span><slot /></span>' },
  'el-empty': { template: '<div class="empty" />' },
  'el-pagination': { template: '<div />' }
}

let pinia

function mountView() {
  return mount(NotificationsView, { global: { plugins: [pinia], stubs } })
}

/** 清空微任务队列，等待 onMounted 里的 await 链结束 */
async function flush() {
  for (let i = 0; i < 5; i++) await Promise.resolve()
}

function seed(rows) {
  notificationApi.mine.mockResolvedValue({
    content: rows,
    totalElements: rows.length,
    totalPages: 1
  })
  notificationApi.unreadCount.mockResolvedValue({ count: rows.filter((r) => !r.isRead).length })
}

const unreadReply = {
  id: 11,
  type: 'REPLY',
  postId: 3,
  postTitle: '帖子A',
  commentId: 77,
  content: 'someone 回复了你的评论',
  isRead: false,
  createTime: '2026-10-17T10:00:00'
}

beforeEach(() => {
  pinia = createPinia()
  setActivePinia(pinia)
  vi.clearAllMocks()
})

describe('NotificationsView 点击通知', () => {
  it('点击未读通知时标记已读并跳转到评论锚点', async () => {
    seed([unreadReply])
    notificationApi.markRead.mockResolvedValue(null)
    const wrapper = mountView()
    await flush()

    await wrapper.find('.notif').trigger('click')
    await flush()

    expect(notificationApi.markRead).toHaveBeenCalledWith(11)
    expect(push).toHaveBeenCalledWith({ path: '/posts/3', hash: '#comment-77' })
  })

  it('点击已读通知时不再发已读请求', async () => {
    // 注意这一条是端到端契约，不是单层守卫的证据：组件层的 `if (!n.isRead)`
    // 与 store.markRead 里的 `if (item.isRead) return` 是双重保险，删掉任意
    // 一层这条断言仍然通过（变异测试已确认）。契约本身仍成立且值得守住。
    seed([{ ...unreadReply, isRead: true }])
    const wrapper = mountView()
    await flush()

    await wrapper.find('.notif').trigger('click')
    await flush()

    expect(notificationApi.markRead).not.toHaveBeenCalled()
    expect(push).toHaveBeenCalled()
  })

  it('标记已读失败时仍然跳转', async () => {
    // 已读只是副作用，失败不该把导航一起带走——否则用户点了没反应。
    seed([unreadReply])
    notificationApi.markRead.mockRejectedValue(new Error('network'))
    const wrapper = mountView()
    await flush()

    await wrapper.find('.notif').trigger('click')
    await flush()

    expect(push).toHaveBeenCalledWith({ path: '/posts/3', hash: '#comment-77' })
  })

  it('不可跳转的类型（SYSTEM）点击后既不标记已读也不跳转', async () => {
    seed([{ ...unreadReply, id: 12, type: 'SYSTEM', commentId: null }])
    const wrapper = mountView()
    await flush()

    await wrapper.find('.notif').trigger('click')
    await flush()

    expect(notificationApi.markRead).not.toHaveBeenCalled()
    expect(push).not.toHaveBeenCalled()
  })
})
