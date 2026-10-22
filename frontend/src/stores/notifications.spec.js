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

  /** 构造一页列表，避免每个用例重复写 content/total 结构 */
  function pageOf(items = [], totalElements = items.length) {
    return { content: items, totalElements, totalPages: 1 }
  }

  function notification(id, isRead = false) {
    return { id, type: 'LIKE', content: `通知 ${id}`, isRead, createTime: '2026-10-22T10:00:00' }
  }

  it('handleIncoming：通知页未打开时只累计未读与 pending，不动列表', async () => {
    notificationApi.mine.mockResolvedValue(pageOf([notification(1)]))
    const store = useNotificationStore()
    await store.loadList(0)

    store.viewOpen = false
    store.handleIncoming(notification(2))

    expect(store.unreadCount).toBe(1)
    expect(store.pendingNewCount).toBe(1)
    expect(store.list.content.map((n) => n.id)).toEqual([1])
  })

  it('handleIncoming：通知页打开且第 1 页时头部插入并清空 pending', async () => {
    const store = useNotificationStore()
    store.list = pageOf([notification(1)])
    store.listLoaded = true
    store.viewOpen = true
    store.listPage = 0

    store.handleIncoming(notification(2))

    expect(store.list.content.map((n) => n.id)).toEqual([2, 1])
    expect(store.list.totalElements).toBe(2)
    expect(store.pendingNewCount).toBe(0)
    expect(store.unreadCount).toBe(1)
  })

  it('handleIncoming：列表已满 10 条时插入并挤掉最后一条，长度不变', () => {
    const store = useNotificationStore()
    const items = Array.from({ length: 10 }, (_, i) => notification(i + 1))
    store.list = pageOf(items, 30)
    store.listLoaded = true
    store.viewOpen = true
    store.listPage = 0

    store.handleIncoming(notification(99))

    expect(store.list.content).toHaveLength(10)
    expect(store.list.content[0].id).toBe(99)
    // 原来第 10 条被挤出去，第 9 条成为新的末尾
    expect(store.list.content[9].id).toBe(9)
    expect(store.list.totalElements).toBe(31)
  })

  it('handleIncoming：翻到第 2 页时不插入，只累计 pending（不能打乱当前分页）', () => {
    const store = useNotificationStore()
    store.list = pageOf([notification(11)], 20)
    store.listLoaded = true
    store.viewOpen = true
    store.listPage = 1

    store.handleIncoming(notification(2))

    expect(store.list.content.map((n) => n.id)).toEqual([11])
    expect(store.pendingNewCount).toBe(1)
  })

  it('handleIncoming：同一个 id 重复送达时去重，未读与 pending 都不变', () => {
    const store = useNotificationStore()
    store.list = pageOf([notification(5)])
    store.listLoaded = true
    store.viewOpen = true
    store.listPage = 0

    store.handleIncoming(notification(5))
    store.handleIncoming(notification(5))

    expect(store.list.content).toHaveLength(1)
    expect(store.unreadCount).toBe(0)
    expect(store.pendingNewCount).toBe(0)
  })

  it('loadList(0) 与 markAllRead 都会清空 pending', async () => {
    notificationApi.mine.mockResolvedValue(pageOf([notification(1)]))
    const store = useNotificationStore()
    store.pendingNewCount = 3

    await store.loadList(0)
    expect(store.pendingNewCount).toBe(0)

    store.pendingNewCount = 2
    await store.markAllRead()
    expect(store.pendingNewCount).toBe(0)
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
