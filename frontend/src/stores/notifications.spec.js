import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

vi.mock('@/api', () => ({
  notificationApi: {
    unreadCount: vi.fn(),
    mine: vi.fn(),
    markAllRead: vi.fn(),
    markRead: vi.fn()
  }
}))

import { notificationApi } from '@/api'
import { useNotificationStore } from './notifications'

describe('notifications store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('refreshUnread 写入后端返回的计数', async () => {
    notificationApi.unreadCount.mockResolvedValue({ count: 4 })
    const store = useNotificationStore()
    await store.refreshUnread()
    expect(store.unreadCount).toBe(4)
  })

  it('refreshUnread 遇到 401 时计数归零且不抛出', async () => {
    notificationApi.unreadCount.mockResolvedValueOnce({ count: 4 })
    const store = useNotificationStore()
    await store.refreshUnread()
    expect(store.unreadCount).toBe(4)

    notificationApi.unreadCount.mockRejectedValueOnce({ response: { status: 401 } })
    await expect(store.refreshUnread()).resolves.toBeUndefined()
    expect(store.unreadCount).toBe(0)
  })

  it('refreshUnread 失败时保留上次成功计数', async () => {
    notificationApi.unreadCount.mockResolvedValueOnce({ count: 5 })
    const store = useNotificationStore()
    await store.refreshUnread()
    expect(store.unreadCount).toBe(5)

    notificationApi.unreadCount.mockRejectedValueOnce(new Error('offline'))
    await store.refreshUnread()
    expect(store.unreadCount).toBe(5)
  })

  it('markAllRead 之后未读归零', async () => {
    notificationApi.unreadCount.mockResolvedValue({ count: 3 })
    notificationApi.markAllRead.mockResolvedValue(null)
    const store = useNotificationStore()
    await store.refreshUnread()
    await store.markAllRead()
    expect(store.unreadCount).toBe(0)
  })

  it('loadList 保存分页数据', async () => {
    notificationApi.mine.mockResolvedValue({
      content: [{ id: 1, type: 'LIKE' }],
      totalElements: 1,
      totalPages: 1
    })
    const store = useNotificationStore()
    await store.loadList(0)
    expect(store.list.content).toHaveLength(1)
    expect(store.listPage).toBe(0)
  })

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

  it('markRead 后端失败时不改本地状态', async () => {
    // 请求失败却先把界面改灰，会让人以为已经读过；重进页面又变回未读。
    // 宁可这次点击没反应（拦截器已弹错误），也不要制造出假状态。
    notificationApi.unreadCount.mockResolvedValue({ count: 1 })
    notificationApi.markRead.mockRejectedValue(new Error('network'))
    notificationApi.mine.mockResolvedValue({
      content: [{ id: 5, type: 'REPLY', isRead: false }],
      totalElements: 1,
      totalPages: 1
    })
    const store = useNotificationStore()
    await store.refreshUnread()
    await store.loadList(0)

    await expect(store.markRead(5)).rejects.toThrow('network')

    expect(store.list.content[0].isRead).toBe(false)
    expect(store.unreadCount).toBe(1)
  })

  it('markRead 的未读数不会减成负数', async () => {
    // 未读数可能落后于列表（角标靠轮询刷新），极端情况下本地计数已是 0。
    notificationApi.markRead.mockResolvedValue(null)
    notificationApi.mine.mockResolvedValue({
      content: [{ id: 3, type: 'LIKE', isRead: false }],
      totalElements: 1,
      totalPages: 1
    })
    const store = useNotificationStore()
    await store.loadList(0)
    expect(store.unreadCount).toBe(0)

    await store.markRead(3)

    expect(store.unreadCount).toBe(0)
  })
})
