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
    listPage: 0
  }),

  actions: {
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
    },

    async markAllRead() {
      await notificationApi.markAllRead()
      this.unreadCount = 0
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
      // 角标靠轮询刷新，可能落后于列表；钳在 0 避免出现 -1
      this.unreadCount = Math.max(0, this.unreadCount - 1)
    }
  }
})
