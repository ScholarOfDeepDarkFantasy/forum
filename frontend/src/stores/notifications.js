import { defineStore } from 'pinia'
import { notificationApi } from '@/api'

const EMPTY_PAGE = { content: [], totalElements: 0, totalPages: 0 }

/**
 * 通知状态：顶栏角标与通知页共用，避免两处各自轮询。
 */
export const useNotificationStore = defineStore('notifications', {
  state: () => ({
    unreadCount: 0,
    list: { ...EMPTY_PAGE },
    listPage: 0
  }),

  actions: {
    async refreshUnread() {
      try {
        const data = await notificationApi.unreadCount()
        this.unreadCount = data?.count ?? 0
      } catch {
        this.unreadCount = 0
      }
    },

    async loadList(page = 0) {
      this.list = await notificationApi.mine({ page, size: 10 })
      this.listPage = page
    },

    async markAllRead() {
      await notificationApi.markAllRead()
      this.unreadCount = 0
      await this.loadList(this.listPage)
    }
  }
})
