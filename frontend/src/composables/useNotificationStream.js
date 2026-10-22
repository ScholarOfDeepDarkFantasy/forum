import { onUnmounted, ref, watch } from 'vue'
import { ElNotification } from 'element-plus'
import { notificationApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'

/**
 * 退避序列（毫秒）。连上后归零，所以每次断线都从 1s 重新开始；
 * 30s 封顶是经验值：再长用户会觉得「通知坏了」，再短则长期断网时白刷请求。
 */
export const BACKOFF_MS = [1000, 2000, 4000, 8000, 16000, 30000]

/**
 * 断线期间的兜底轮询间隔。沿用改造前的 120s：
 * SSE 正常时一次都不跑，断线时保证角标最多落后两分钟。
 */
export const FALLBACK_POLL_MS = 120_000

const TITLES = {
  REPLY: '新回复',
  LIKE: '新点赞',
  FAVORITE: '新收藏',
  SYSTEM: '系统通知'
}

/** 顶部轻提示：点通知页才能看到详情，这里只给一眼可见的提示 */
function defaultNotify(notification) {
  ElNotification({
    title: TITLES[notification.type] || '新通知',
    message: notification.content || '',
    type: notification.type === 'LIKE' ? 'success' : 'info',
    duration: 4000
  })
}

/**
 * 创建 SSE 连接管理器。
 *
 * <p>所有外部依赖都可注入（store/api/EventSource/timers/document），
 * 这样 jsdom 测试可以完全控制连接建立与时间推进，不必真的等 30 秒。</p>
 *
 * <p>状态机：closed → connecting → open → retrying → connecting …；
 * 取票 401 时进入 disabled，永久停止（拦截器已清 token 并跳登录页）。</p>
 */
export function createNotificationStream({
  store,
  api = notificationApi,
  notify = defaultNotify,
  EventSourceImpl = globalThis.EventSource,
  documentRef = typeof document === 'undefined' ? null : document,
  timers = globalThis
}) {
  const state = ref('closed')
  let source = null
  let retryTimer = null
  let pollTimer = null
  let retryIndex = 0
  let started = false
  let stopped = true
  let pendingReconnect = false
  /**
   * 连接世代。每次 start()/stop() 开启新一代，取票返回后与世代比对：
   * 换号或手动停止时在途的旧 ticket 即使晚到也直接丢弃，不能拿上一会话的
   * ticket 建连（否则旧用户的通知会继续进新用户的 store）。
   */
  let epoch = 0

  function closeSource() {
    if (!source) return
    source.removeEventListener?.('notification', onNotification)
    source.onopen = null
    source.onerror = null
    source.close()
    source = null
  }

  function stopFallbackPolling() {
    if (!pollTimer) return
    timers.clearInterval(pollTimer)
    pollTimer = null
  }

  function startFallbackPolling() {
    if (pollTimer || stopped) return
    pollTimer = timers.setInterval(() => {
      if (started && !stopped) store.refreshUnread()
    }, FALLBACK_POLL_MS)
  }

  function onNotification(event) {
    let payload
    try {
      payload = JSON.parse(event.data)
    } catch {
      return
    }
    // id 是列表去重与跳转的键；没有 id 的帧（或未来的心跳事件）一律忽略
    if (!payload || payload.id == null) return
    store.handleIncoming(payload)
    notify(payload)
  }

  function open(ticket) {
    closeSource()
    const url = `${import.meta.env.BASE_URL}api/notifications/stream?ticket=${encodeURIComponent(ticket)}`
    let es
    try {
      es = new EventSourceImpl(url)
    } catch {
      // 构造失败（浏览器禁用 EventSource、URL 非法等）不能让 async connect 变成
      // 未处理的 rejection，也不能把状态卡在 connecting：按断线处理，退避重试 + 兜底轮询。
      // 页面不可见时不启动轮询（省电），回前台由 visibilitychange 立即重连。
      scheduleRetry()
      if (!documentRef?.hidden) startFallbackPolling()
      return
    }
    source = es
    es.addEventListener?.('notification', onNotification)

    es.onopen = () => {
      if (source !== es) return
      retryIndex = 0
      state.value = 'open'
      stopFallbackPolling()
      // 断线期间可能漏过事件：用接口结果校准一次未读数
      store.refreshUnread()
    }

    es.onerror = () => {
      if (source !== es) return
      // 连接已断（服务端重启/网络切换/代理超时）。EventSource 自带重连会忽略鉴权，
      // 必须自己换新 ticket 重来。
      closeSource()
      scheduleRetry()
      // 页面隐藏时省电，不跑兜底轮询；回前台重连成功后由 onopen 校准
      if (!documentRef?.hidden) startFallbackPolling()
    }
  }

  function scheduleRetry() {
    if (stopped || state.value === 'disabled') return
    state.value = 'retrying'
    if (documentRef?.hidden) {
      // 页面不可见时不重连：移动端后台标签页保持长连接会耗电，回前台再立即重连
      pendingReconnect = true
      return
    }
    const delay = BACKOFF_MS[Math.min(retryIndex, BACKOFF_MS.length - 1)]
    retryIndex += 1
    retryTimer = timers.setTimeout(connect, delay)
  }

  async function connect() {
    if (!started || stopped || state.value === 'disabled') return
    retryTimer = null
    state.value = 'connecting'
    // 记住发起取票时的世代：await 期间 stop()/start() 会推进世代，旧 ticket 必须作废
    const myEpoch = epoch

    let ticket
    try {
      const data = await api.streamTicket()
      ticket = data?.ticket
      if (!ticket) throw new Error('empty ticket')
    } catch (error) {
      // 世代已变：这是上一会话的失败，既不能触发重连，也不能因旧 401 把新会话置为 disabled
      if (myEpoch !== epoch || !started || stopped) return
      if (error?.response?.status === 401) {
        // ticket 端点都 401 了，说明会话已失效；再重连只会每 30s 刷一次 401
        state.value = 'disabled'
        stop()
        return
      }
      scheduleRetry()
      return
    }

    // 取票是异步的：期间用户可能已登出、换号或组件已卸载
    if (myEpoch !== epoch || !started || stopped) return
    if (documentRef?.hidden) {
      // 页面不可见时不建连（省电，也不浪费一次性 ticket）；回前台由 visibilitychange 立即重连
      pendingReconnect = true
      state.value = 'retrying'
      return
    }
    open(ticket)
  }

  function onVisibilityChange() {
    if (!pendingReconnect || documentRef?.hidden) return
    pendingReconnect = false
    retryIndex = 0
    // 回前台立即重连，不背之前的退避
    connect()
  }

  function start() {
    if (started) return
    // 只在真正开启新会话时推进世代：幂等 start 若也推进，会把当前在途 ticket 作废
    // 却没有新的 connect 接手，流会永久停在 connecting。
    epoch += 1
    started = true
    stopped = false
    pendingReconnect = false
    retryIndex = 0
    state.value = 'connecting'
    documentRef?.addEventListener?.('visibilitychange', onVisibilityChange)
    connect()
  }

  function stop() {
    // 推进世代使在途取票失效：旧 ticket 晚到时不能再建连
    epoch += 1
    started = false
    stopped = true
    pendingReconnect = false
    retryIndex = 0
    if (retryTimer) {
      timers.clearTimeout(retryTimer)
      retryTimer = null
    }
    stopFallbackPolling()
    closeSource()
    documentRef?.removeEventListener?.('visibilitychange', onVisibilityChange)
    if (state.value !== 'disabled') state.value = 'closed'
  }

  return { state, start, stop }
}

/**
 * 组件侧入口：登录后建立 SSE，登出/换号/组件卸载时断开。
 * 只在 App.vue 调用一次（整个应用一条连接）。
 *
 * <p>监听 token 而不是 isLoggedIn：A→B 直接登录时登录态是 true→true，
 * 只 watch 布尔会继续用 A 的连接把 A 的通知推进 B 的 store；A 的流若已
 * 401 disabled，B 登录后也永远不会重新 start。</p>
 *
 * <p>options 原样转发给 createNotificationStream，测试可注入 api/EventSource/timers。</p>
 */
export function useNotificationStream(options = {}) {
  const store = useNotificationStore()
  const auth = useAuthStore()
  const stream = createNotificationStream({ ...options, store })

  watch(
    () => auth.token,
    (token) => {
      // 先停旧连接并清空上一会话的通知状态，再按新 token 连接；
      // 登出（token 为空）只清理，不重连。
      stream.stop()
      store.resetSession()
      if (token) stream.start()
    },
    { immediate: true }
  )

  onUnmounted(() => stream.stop())

  return stream
}
