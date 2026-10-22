import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import {
  BACKOFF_MS,
  FALLBACK_POLL_MS,
  createNotificationStream,
  useNotificationStream
} from '@/composables/useNotificationStream'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'

/**
 * EventSource 的最小替身：记录 URL、手动触发 open/error/notification。
 * jsdom 没有 EventSource，只能注入替身；这同时让「连接何时建立」变得可控。
 */
class MockEventSource {
  static instances = []

  constructor(url) {
    this.url = url
    this.closed = false
    this.listeners = {}
    this.onopen = null
    this.onerror = null
    MockEventSource.instances.push(this)
  }

  addEventListener(name, handler) {
    this.listeners[name] = [...(this.listeners[name] || []), handler]
  }

  removeEventListener(name, handler) {
    this.listeners[name] = (this.listeners[name] || []).filter((fn) => fn !== handler)
  }

  close() {
    this.closed = true
  }

  emit(name, event) {
    ;(this.listeners[name] || []).forEach((fn) => fn(event))
  }

  static latest() {
    return MockEventSource.instances[MockEventSource.instances.length - 1]
  }

  static reset() {
    MockEventSource.instances = []
  }
}

const flush = () => vi.advanceTimersByTimeAsync(0)

describe('createNotificationStream', () => {
  let store
  let api
  let notify
  let documentRef
  let setHidden
  let stream

  beforeEach(() => {
    vi.useFakeTimers()
    MockEventSource.reset()

    const handlers = {}
    documentRef = {
      hidden: false,
      addEventListener: vi.fn((name, fn) => {
        handlers[name] = fn
      }),
      removeEventListener: vi.fn((name) => {
        delete handlers[name]
      })
    }
    setHidden = (hidden) => {
      documentRef.hidden = hidden
      if (handlers.visibilitychange) handlers.visibilitychange()
    }

    store = { handleIncoming: vi.fn(), refreshUnread: vi.fn() }
    api = { streamTicket: vi.fn() }
    notify = vi.fn()
    stream = createNotificationStream({
      store,
      api,
      notify,
      EventSourceImpl: MockEventSource,
      documentRef
    })
  })

  afterEach(() => {
    stream.stop()
    vi.useRealTimers()
  })

  it('取到 ticket 后建立连接，open 后状态为 open 并校准未读数', async () => {
    api.streamTicket.mockResolvedValue({ ticket: 'tk-1', expiresIn: 60 })

    stream.start()
    await flush()

    expect(api.streamTicket).toHaveBeenCalledTimes(1)
    expect(MockEventSource.instances).toHaveLength(1)
    expect(MockEventSource.latest().url).toBe('/api/notifications/stream?ticket=tk-1')

    MockEventSource.latest().onopen()
    expect(stream.state.value).toBe('open')
    expect(store.refreshUnread).toHaveBeenCalledTimes(1)
  })

  it('取票失败按 1s/2s/4s 指数退避重试', async () => {
    api.streamTicket.mockRejectedValue(new Error('network'))

    stream.start()
    await flush()
    expect(stream.state.value).toBe('retrying')
    expect(api.streamTicket).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(999)
    expect(api.streamTicket).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(api.streamTicket).toHaveBeenCalledTimes(2)

    await vi.advanceTimersByTimeAsync(1999)
    expect(api.streamTicket).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(1)
    expect(api.streamTicket).toHaveBeenCalledTimes(3)

    await vi.advanceTimersByTimeAsync(4000)
    expect(api.streamTicket).toHaveBeenCalledTimes(4)
  })

  it('退避封顶 30s，不会无限增长', async () => {
    api.streamTicket.mockRejectedValue(new Error('network'))
    stream.start()
    await flush()

    // 1+2+4+8+16+30 → 第 7 次尝试
    for (const delay of BACKOFF_MS) {
      await vi.advanceTimersByTimeAsync(delay)
    }
    expect(api.streamTicket).toHaveBeenCalledTimes(7)

    // 第 7 次失败仍等 30s（封顶），而不是 60s
    await vi.advanceTimersByTimeAsync(29_999)
    expect(api.streamTicket).toHaveBeenCalledTimes(7)
    await vi.advanceTimersByTimeAsync(1)
    expect(api.streamTicket).toHaveBeenCalledTimes(8)
  })

  it('取票返回 401 时进入 disabled 并永久停止（交给拦截器跳登录页）', async () => {
    api.streamTicket.mockRejectedValue({ response: { status: 401 } })

    stream.start()
    await flush()

    expect(stream.state.value).toBe('disabled')
    await vi.advanceTimersByTimeAsync(120_000)
    expect(api.streamTicket).toHaveBeenCalledTimes(1)
  })

  it('连接断开后进入 retrying，并在断线期间按 120s 兜底轮询，重连成功停止', async () => {
    api.streamTicket.mockResolvedValue({ ticket: 'tk-1' })
    stream.start()
    await flush()
    MockEventSource.latest().onopen()
    expect(store.refreshUnread).toHaveBeenCalledTimes(1)

    MockEventSource.latest().onerror()
    expect(stream.state.value).toBe('retrying')

    await vi.advanceTimersByTimeAsync(FALLBACK_POLL_MS)
    expect(store.refreshUnread).toHaveBeenCalledTimes(2)

    // 1s 后重连；重连成功会再校准一次未读数，并停掉兜底轮询
    api.streamTicket.mockResolvedValue({ ticket: 'tk-2' })
    await vi.advanceTimersByTimeAsync(1000)
    expect(MockEventSource.instances).toHaveLength(2)
    MockEventSource.latest().onopen()
    expect(store.refreshUnread).toHaveBeenCalledTimes(3)
    expect(stream.state.value).toBe('open')

    await vi.advanceTimersByTimeAsync(FALLBACK_POLL_MS * 2)
    expect(store.refreshUnread).toHaveBeenCalledTimes(3)
  })

  it('notification 事件解析后交给 store 与轻提示；坏 JSON / 缺 id 直接忽略', async () => {
    api.streamTicket.mockResolvedValue({ ticket: 'tk-1' })
    stream.start()
    await flush()
    const es = MockEventSource.latest()
    es.onopen()

    const payload = { id: 7, type: 'REPLY', content: 'alice 回复了你的评论' }
    es.emit('notification', { data: JSON.stringify(payload) })
    expect(store.handleIncoming).toHaveBeenCalledWith(payload)
    expect(notify).toHaveBeenCalledWith(payload)

    es.emit('notification', { data: '{not-json' })
    es.emit('notification', { data: JSON.stringify({ type: 'LIKE' }) })
    expect(store.handleIncoming).toHaveBeenCalledTimes(1)
    expect(notify).toHaveBeenCalledTimes(1)
  })

  it('stop 关闭连接、清掉重连定时器，之后不再取票', async () => {
    api.streamTicket.mockResolvedValue({ ticket: 'tk-1' })
    stream.start()
    await flush()
    const es = MockEventSource.latest()

    stream.stop()

    expect(es.closed).toBe(true)
    expect(stream.state.value).toBe('closed')
    await vi.advanceTimersByTimeAsync(120_000)
    expect(api.streamTicket).toHaveBeenCalledTimes(1)
  })

  it('页面隐藏时不重连，回到前台立即重连（不背退避）', async () => {
    api.streamTicket.mockResolvedValue({ ticket: 'tk-1' })
    stream.start()
    await flush()

    setHidden(true)
    MockEventSource.latest().onerror()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(api.streamTicket).toHaveBeenCalledTimes(1)

    setHidden(false)
    expect(api.streamTicket).toHaveBeenCalledTimes(2)
  })

  it('成功连接后退避计数归零：再次断线仍从 1s 开始', async () => {
    api.streamTicket.mockRejectedValueOnce(new Error('network'))
    api.streamTicket.mockRejectedValueOnce(new Error('network'))
    api.streamTicket.mockResolvedValue({ ticket: 'tk-3' })

    stream.start()
    await flush()
    await vi.advanceTimersByTimeAsync(1000)
    await vi.advanceTimersByTimeAsync(2000)
    expect(api.streamTicket).toHaveBeenCalledTimes(3)

    MockEventSource.latest().onopen()
    api.streamTicket.mockRejectedValue(new Error('network'))
    MockEventSource.latest().onerror()

    await vi.advanceTimersByTimeAsync(999)
    expect(api.streamTicket).toHaveBeenCalledTimes(3)
    await vi.advanceTimersByTimeAsync(1)
    expect(api.streamTicket).toHaveBeenCalledTimes(4)
  })

  it('响应里没有 ticket 视为失败并重试，不建立连接', async () => {
    api.streamTicket.mockResolvedValue({})

    stream.start()
    await flush()

    expect(stream.state.value).toBe('retrying')
    expect(MockEventSource.instances).toHaveLength(0)
    await vi.advanceTimersByTimeAsync(1000)
    expect(api.streamTicket).toHaveBeenCalledTimes(2)
  })

  it('stop 后 30s 内不再重连：EventSource 构造数不增加', async () => {
    api.streamTicket.mockResolvedValue({ ticket: 'tk-1' })
    stream.start()
    await flush()
    MockEventSource.latest().onerror()
    expect(stream.state.value).toBe('retrying')
    const constructed = MockEventSource.instances.length

    stream.stop()
    await vi.advanceTimersByTimeAsync(30_000)

    expect(MockEventSource.instances).toHaveLength(constructed)
    expect(api.streamTicket).toHaveBeenCalledTimes(1)
  })

  it('stop 后 120s 不再 refreshUnread（兜底轮询已清）', async () => {
    api.streamTicket.mockResolvedValue({ ticket: 'tk-1' })
    stream.start()
    await flush()
    MockEventSource.latest().onopen()
    MockEventSource.latest().onerror()
    expect(stream.state.value).toBe('retrying')

    stream.stop()
    await vi.advanceTimersByTimeAsync(FALLBACK_POLL_MS)

    // 只有 onopen 那次校准；兜底轮询若未清掉，120s 时会再多一次
    expect(store.refreshUnread).toHaveBeenCalledTimes(1)
  })

  it('重连使用新 ticket：第二次连接的 URL 用 tickets[1] 且不含 tickets[0]', async () => {
    const tickets = ['ticket-first', 'ticket-second']
    api.streamTicket
      .mockResolvedValueOnce({ ticket: tickets[0] })
      .mockResolvedValueOnce({ ticket: tickets[1] })

    stream.start()
    await flush()
    expect(MockEventSource.latest().url).toContain(`ticket=${tickets[0]}`)

    MockEventSource.latest().onopen()
    MockEventSource.latest().onerror()
    await vi.advanceTimersByTimeAsync(1000)

    const second = MockEventSource.latest()
    expect(second.url).toContain(`ticket=${tickets[1]}`)
    expect(second.url).not.toContain(`ticket=${tickets[0]}`)
  })

  it('退避 4s 边界：3999ms 不重连，4000ms 才重连', async () => {
    api.streamTicket.mockRejectedValue(new Error('network'))
    stream.start()
    await flush()

    await vi.advanceTimersByTimeAsync(1000)
    await vi.advanceTimersByTimeAsync(2000)
    expect(api.streamTicket).toHaveBeenCalledTimes(3)

    await vi.advanceTimersByTimeAsync(3999)
    expect(api.streamTicket).toHaveBeenCalledTimes(3)
    await vi.advanceTimersByTimeAsync(1)
    expect(api.streamTicket).toHaveBeenCalledTimes(4)
  })

  it('在途 ticket 时 stop→start：旧 ticket 晚到不建立连接（世代失效）', async () => {
    let resolveOld
    api.streamTicket.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve
        })
    )

    stream.start()
    await flush()
    expect(MockEventSource.instances).toHaveLength(0)

    stream.stop()
    api.streamTicket.mockResolvedValueOnce({ ticket: 'tk-new' })
    stream.start()
    await flush()
    expect(MockEventSource.instances).toHaveLength(1)
    const fresh = MockEventSource.latest()
    expect(fresh.url).toContain('ticket=tk-new')

    resolveOld({ ticket: 'tk-old' })
    await flush()

    // 旧 ticket 属于上一个世代：既不能新建连接，也不能把刚建好的新连接顶掉
    expect(MockEventSource.instances).toHaveLength(1)
    expect(MockEventSource.latest()).toBe(fresh)
    expect(fresh.url).toContain('ticket=tk-new')
    expect(fresh.closed).toBe(false)
  })

  it('旧世代 ticket 晚到失败（401）不把新会话置为 disabled，也不断开新连接', async () => {
    let rejectOld
    api.streamTicket.mockImplementationOnce(
      () =>
        new Promise((_, reject) => {
          rejectOld = reject
        })
    )

    stream.start()
    await flush()
    expect(MockEventSource.instances).toHaveLength(0)

    stream.stop()
    api.streamTicket.mockResolvedValueOnce({ ticket: 'tk-new' })
    stream.start()
    await flush()
    const fresh = MockEventSource.latest()
    expect(fresh.url).toContain('ticket=tk-new')

    // 旧世代的取票在最晚时刻以 401 失败：epoch 守卫必须先于 401 判断生效，
    // 否则旧会话的失效会把刚建立的新会话永久置为 disabled。
    rejectOld({ response: { status: 401 } })
    await flush()

    expect(stream.state.value).not.toBe('disabled')
    expect(stream.state.value).toBe('connecting')
    expect(fresh.closed).toBe(false)
    expect(api.streamTicket).toHaveBeenCalledTimes(2)
  })

  it('EventSource 构造失败时进入退避并启动兜底轮询，不会卡在 connecting', async () => {
    const constructed = []
    class ThrowingEventSource {
      constructor() {
        constructed.push(1)
        throw new Error('EventSource unavailable')
      }
    }
    const failing = createNotificationStream({
      store,
      api,
      notify,
      EventSourceImpl: ThrowingEventSource,
      documentRef
    })
    try {
      api.streamTicket.mockResolvedValue({ ticket: 'tk-1' })
      failing.start()
      await flush()

      expect(failing.state.value).toBe('retrying')
      expect(constructed).toHaveLength(1)

      // 构造失败也走退避：1s 后重新取票并再次尝试建连
      await vi.advanceTimersByTimeAsync(1000)
      expect(constructed).toHaveLength(2)

      // 兜底轮询同时生效，保证角标不会一直停在旧值
      await vi.advanceTimersByTimeAsync(FALLBACK_POLL_MS)
      expect(store.refreshUnread).toHaveBeenCalledTimes(1)
    } finally {
      failing.stop()
    }
  })

  it('页面隐藏时 onerror 不启动兜底轮询；回到前台才重连', async () => {
    api.streamTicket.mockResolvedValue({ ticket: 'tk-1' })
    stream.start()
    await flush()
    setHidden(true)
    MockEventSource.latest().onerror()
    expect(stream.state.value).toBe('retrying')

    await vi.advanceTimersByTimeAsync(FALLBACK_POLL_MS)
    expect(store.refreshUnread).not.toHaveBeenCalled()
    expect(api.streamTicket).toHaveBeenCalledTimes(1)

    setHidden(false)
    expect(api.streamTicket).toHaveBeenCalledTimes(2)
  })

  it('页面隐藏时在途 ticket 不 open，回到前台后再建立连接', async () => {
    documentRef.hidden = true
    api.streamTicket.mockResolvedValue({ ticket: 'tk-1' })
    stream.start()
    await flush()

    expect(MockEventSource.instances).toHaveLength(0)
    expect(stream.state.value).toBe('retrying')

    setHidden(false)
    await flush()
    expect(MockEventSource.instances).toHaveLength(1)
    expect(MockEventSource.latest().url).toContain('ticket=tk-1')
  })
})

describe('useNotificationStream（账号切换与卸载）', () => {
  let pinia
  let auth
  let store
  let api
  let documentRef
  let wrapper
  let stream

  beforeEach(() => {
    vi.useFakeTimers()
    MockEventSource.reset()
    localStorage.clear()

    pinia = createPinia()
    setActivePinia(pinia)
    auth = useAuthStore()
    store = useNotificationStore()

    api = { streamTicket: vi.fn().mockResolvedValue({ ticket: 'tk-a' }) }
    const handlers = {}
    documentRef = {
      hidden: false,
      addEventListener: vi.fn((name, fn) => {
        handlers[name] = fn
      }),
      removeEventListener: vi.fn((name) => {
        delete handlers[name]
      })
    }
    stream = null
    wrapper = null
  })

  afterEach(() => {
    wrapper?.unmount()
    vi.useRealTimers()
  })

  function mountHost() {
    const Host = defineComponent({
      setup() {
        stream = useNotificationStream({
          api,
          notify: vi.fn(),
          EventSourceImpl: MockEventSource,
          documentRef
        })
        return () => null
      }
    })
    wrapper = mount(Host, { global: { plugins: [pinia] } })
    return wrapper
  }

  it('token 从 A 换成 B：关闭旧连接、重置通知状态、用新 ticket 重连', async () => {
    auth.token = 'token-a'
    mountHost()
    await flush()
    expect(MockEventSource.instances).toHaveLength(1)
    const old = MockEventSource.instances[0]
    expect(old.url).toContain('ticket=tk-a')

    // A 的会话里已有角标/列表/分页状态，切号时不能带进 B
    store.unreadCount = 5
    store.pendingNewCount = 2
    store.list = { content: [{ id: 1 }], totalElements: 1, totalPages: 1 }
    store.listPage = 1
    store.listLoaded = true
    store.viewOpen = true

    api.streamTicket.mockResolvedValue({ ticket: 'tk-b' })
    auth.token = 'token-b'
    await nextTick()
    await flush()

    expect(old.closed).toBe(true)
    expect(store.unreadCount).toBe(0)
    expect(store.pendingNewCount).toBe(0)
    expect(store.list).toEqual({ content: [], totalElements: 0, totalPages: 0 })
    expect(store.listPage).toBe(0)
    expect(store.listLoaded).toBe(false)
    expect(store.viewOpen).toBe(false)
    expect(MockEventSource.instances).toHaveLength(2)
    expect(MockEventSource.latest().url).toContain('ticket=tk-b')
  })

  it('登出时关闭连接、重置通知状态，且之后不再重连', async () => {
    auth.token = 'token-a'
    mountHost()
    await flush()
    const es = MockEventSource.instances[0]
    store.unreadCount = 3
    store.listLoaded = true

    auth.token = ''
    await nextTick()
    await flush()

    expect(es.closed).toBe(true)
    expect(store.unreadCount).toBe(0)
    expect(store.listLoaded).toBe(false)
    expect(stream.state.value).toBe('closed')

    await vi.advanceTimersByTimeAsync(30_000)
    expect(MockEventSource.instances).toHaveLength(1)
    expect(api.streamTicket).toHaveBeenCalledTimes(1)
  })

  it('组件卸载时断开连接', async () => {
    auth.token = 'token-a'
    mountHost()
    await flush()
    const es = MockEventSource.instances[0]

    wrapper.unmount()
    wrapper = null

    expect(es.closed).toBe(true)
    expect(stream.state.value).toBe('closed')
  })
})
