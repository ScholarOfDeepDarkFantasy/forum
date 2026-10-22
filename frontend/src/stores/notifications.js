import { defineStore } from 'pinia'
import { notificationApi } from '@/api'

/** 通知列表每页条数；视图分页组件应引用同一常量，避免两处各自维护 10 */
export const PAGE_SIZE = 10

/** 首次加载前的哨兵占位（非真实 PageResponse：无 page/size/last 字段） */
function emptyPage() {
  return { content: [], totalElements: 0, totalPages: 0 }
}

/**
 * 通知状态：顶栏角标与通知页共用，避免两处各自轮询。
 */
export const useNotificationStore = defineStore('notifications', {
  state: () => ({
    unreadCount: 0,
    list: emptyPage(),
    listPage: 0,
    /** 有新通知但当前列表不适合插入时累计的条数；通知页据此显示「有 N 条新通知」 */
    pendingNewCount: 0,
    /** 通知页是否打开：只有打开且停在第 1 页才即时插入，否则会打乱分页 */
    viewOpen: false,
    /** 列表是否已成功加载过：首次加载前插入会被随后的响应覆盖 */
    listLoaded: false
  }),

  actions: {
    /**
     * 清空会话级状态（角标/列表/分页/横幅）。
     *
     * <p>登出或切换账号时必须调用：A 的未读数、列表与分页位置都不能带进 B 的会话，
     * 否则新账号会短暂看到上一个账号的通知内容。</p>
     */
    resetSession() {
      this.unreadCount = 0
      this.pendingNewCount = 0
      this.list = emptyPage()
      this.listPage = 0
      this.listLoaded = false
      this.viewOpen = false
    },

    async refreshUnread() {
      try {
        const data = await notificationApi.unreadCount()
        this.unreadCount = data?.count ?? 0
      } catch (error) {
        // 失败保留上次已知计数：归零会让角标静默消失，用户在有未读时看不到提示。
        // 仅在会话失效（401）时清零——此时拦截器已清 token 并跳转登录页。
        if (error?.response?.status === 401) {
          this.unreadCount = 0
        }
      }
    },

    async loadList(page = 0) {
      this.list = await notificationApi.mine({ page, size: PAGE_SIZE })
      this.listPage = page
      this.listLoaded = true
      // 第 1 页就是服务端的最新状态，横幅随之归零
      if (page === 0) {
        this.pendingNewCount = 0
      }
    },

    /**
     * 合并一条 SSE 推送进来的通知。
     *
     * <p>按 id 去重：重连补发、开两个标签页都会让同一条通知到达多次。
     *
     * <p>只有「通知页打开 + 已加载过 + 停在第 1 页」才插到头部并挤掉最后一条：
     * 翻页时插入会让用户正在看的第 2 页混进新数据，未打开页面时插入则会在
     * 下次进入时被首次加载覆盖。其余情况只累计 pending，由横幅提示刷新。</p>
     */
    handleIncoming(notification) {
      if (!notification || notification.id == null) return
      if (this.list.content.some((item) => item.id === notification.id)) return

      // 服务端已经把通知写进库，未读数直接 +1；重连后 onopen 还会以接口结果校准
      this.unreadCount += 1

      if (this.viewOpen && this.listLoaded && this.listPage === 0) {
        this.list.content.unshift(notification)
        if (this.list.content.length > PAGE_SIZE) {
          this.list.content.pop()
        }
        this.list.totalElements = (this.list.totalElements ?? 0) + 1
      } else {
        this.pendingNewCount += 1
      }
    },

    async markAllRead() {
      await notificationApi.markAllRead()
      this.unreadCount = 0
      this.pendingNewCount = 0
      await this.loadList(this.listPage)
    },

    /**
     * 标记单条已读。就地更新列表项而不是重新拉列表——用户点进去的那一刻那条
     * 通知就该变灰，多一次请求只会让界面闪一下。
     *
     * 顺序是先请求后改本地状态：请求失败时不能把界面改灰，否则重进页面又变回
     * 未读，用户会以为是 bug。失败直接抛出，由调用方决定是否吞掉。
     */
    async markRead(id) {
      const item = this.list.content.find((n) => n.id === id)
      // 已读的通知不必再请求；未读数也无需再减
      if (item && item.isRead) return
      await notificationApi.markRead(id)
      if (item) item.isRead = true
      // 角标由实时事件累加，这里只保证不会因为「已读一条」减成 -1
      this.unreadCount = Math.max(0, this.unreadCount - 1)
    }
  }
})
