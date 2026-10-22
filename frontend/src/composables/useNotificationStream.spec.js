import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  BACKOFF_MS,
  FALLBACK_POLL_MS,
  createNotificationStream
} from '@/composables/useNotificationStream'

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
})
