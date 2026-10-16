import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

vi.mock('@/api', () => ({
  notificationApi: {
    unreadCount: vi.fn(),
    mine: vi.fn(),
    markAllRead: vi.fn()
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
})
