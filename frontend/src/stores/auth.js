import { defineStore } from 'pinia'
import { authApi } from '@/api'

/**
 * 登录态 store：token + 当前用户信息持久化到 localStorage
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('jf_token') || '',
    user: JSON.parse(localStorage.getItem('jf_user') || 'null')
  }),

  getters: {
    isLoggedIn: (s) => !!s.token,
    isAdmin: (s) => s.user?.roles?.includes('ADMIN') ?? false,
    isModerator: (s) =>
      s.user?.roles?.includes('ADMIN') || s.user?.roles?.includes('MODERATOR') || false
  },

  actions: {
    async login(credentials) {
      const data = await authApi.login(credentials) // 拦截器已解包 data
      this.token = data.token
      this.user = data
      localStorage.setItem('jf_token', data.token)
      localStorage.setItem('jf_user', JSON.stringify(data))
      return data
    },

    async register(form) {
      await authApi.register(form)
    },

    /** 用 token 拉取最新用户信息（刷新/资料修改后调用） */
    async fetchMe() {
      if (!this.token) return
      const me = await authApi.me()
      this.user = { ...this.user, ...me }
      localStorage.setItem('jf_user', JSON.stringify(this.user))
    },

    async updateProfile(form) {
      await authApi.updateProfile(form)
      await this.fetchMe()
    },

    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem('jf_token')
      localStorage.removeItem('jf_user')
    }
  }
})
