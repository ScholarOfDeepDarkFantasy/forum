import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { useNotificationStore } from '@/stores/notifications'

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

describe('NotificationsView 新通知横幅', () => {
  it('打开页面时把 viewOpen 置为 true', async () => {
    seed([])
    mountView()
    await flush()

    expect(useNotificationStore().viewOpen).toBe(true)
  })

  it('有 pending 时显示横幅，点击后真的重拉第 1 页并清零', async () => {
    seed([unreadReply])
    const wrapper = mountView()
    await flush()

    const store = useNotificationStore()
    // 先真的加载第 2 页。只手动改 listPage 的话，挂载时 load() 那次调用就能满足
    // 「最后一次以 page:0 调用」的断言——把 @click 改成只清计数仍然全绿（空断言）。
    await store.loadList(1)
    expect(notificationApi.mine).toHaveBeenLastCalledWith({ page: 1, size: 10 })
    expect(store.listPage).toBe(1)

    // 模拟「用户停在第 2 页时来了新通知」：store 只累计 pending，不插入
    store.pendingNewCount = 2
    await nextTick()

    const banner = wrapper.find('.new-banner')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain('2 条新通知')

    const callsBeforeClick = notificationApi.mine.mock.calls.length

    await banner.trigger('click')
    await flush()

    // 点击必须真的重拉（调用次数 +1）并且落回第 1 页，而不只是把 pending 清零
    expect(notificationApi.mine).toHaveBeenCalledTimes(callsBeforeClick + 1)
    expect(notificationApi.mine).toHaveBeenLastCalledWith({ page: 0, size: 10 })
    expect(store.listPage).toBe(0)
    expect(store.pendingNewCount).toBe(0)
    expect(wrapper.find('.new-banner').exists()).toBe(false)
  })

  it('列表为空时横幅仍然显示', async () => {
    // 横幅渲染在列表的 v-if/v-else 之外：首条通知到达时列表还是空的，若被
    // el-empty 分支挡掉，用户就看不到任何变化。这条锁定既有行为，防止把横幅
    // 挪回列表条件内造成回归。
    seed([])
    const wrapper = mountView()
    await flush()
    expect(wrapper.find('.empty').exists()).toBe(true)

    useNotificationStore().pendingNewCount = 1
    await nextTick()

    expect(wrapper.find('.empty').exists()).toBe(true)
    expect(wrapper.find('.new-banner').exists()).toBe(true)
  })

  it('卸载时把 viewOpen 复位，避免离开后继续往列表插入', async () => {
    seed([])
    const wrapper = mountView()
    await flush()
    // 先确认挂载期间确实置位，否则「卸载后为 false」在初始值就是 false 时是空断言
    expect(useNotificationStore().viewOpen).toBe(true)

    wrapper.unmount()

    expect(useNotificationStore().viewOpen).toBe(false)
  })
})
