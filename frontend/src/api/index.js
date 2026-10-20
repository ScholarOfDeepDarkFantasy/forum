import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一 axios 实例：
 * - 请求拦截器自动附带 Bearer token
 * - 响应拦截器解包 Result<T>，业务失败时弹出错误并 reject
 * - 401 时清除登录态并跳转登录页
 *
 * 接口前缀与登录页路径都从 BASE_URL 派生（它由 vite.config.js 的 base 决定，且总以 '/' 结尾）：
 * 根路径下是 /api 与 /login，部署到 /forum/ 子路径时自动变成 /forum/api 与 /forum/login。
 */
const BASE_URL = import.meta.env.BASE_URL
const LOGIN_PATH = `${BASE_URL}login`

const api = axios.create({
  baseURL: `${BASE_URL}api`,
  timeout: 15000
})

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('jf_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/**
 * 登录/注册这类「凭据接口」返回 401 时，含义是「这次输入的用户名或密码不对」，
 * 不是「当前会话失效」。必须原样展示后端消息，且不能清登录态、不能跳转登录页，
 * 否则用户会看到「请先登录」这种与操作无关的提示，还被莫名甩回登录页。
 */
const CREDENTIAL_ENDPOINTS = ['/auth/login', '/auth/register']

function isCredentialEndpoint(url = '') {
  return CREDENTIAL_ENDPOINTS.some((path) => url.startsWith(path))
}

api.interceptors.response.use(
  (response) => {
    const res = response.data
    // 后端统一 Result 结构：{ success, message, data }
    if (res && typeof res === 'object' && 'success' in res) {
      if (res.success) {
        return res.data
      }
      ElMessage.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return res
  },
  (error) => {
    const status = error.response?.status
    const message = error.response?.data?.message || error.message || '网络错误'
    const url = error.config?.url || ''
    if (status === 401 && !isCredentialEndpoint(url)) {
      localStorage.removeItem('jf_token')
      localStorage.removeItem('jf_user')
      ElMessage.error('请先登录')
      if (!location.pathname.startsWith(LOGIN_PATH)) {
        location.href = LOGIN_PATH
      }
    } else {
      // 凭据接口的 401 也走这里：显示后端原文（如「用户名或密码错误」），不动登录态
      ElMessage.error(message)
    }
    return Promise.reject(error)
  }
)

// ========== 认证 ==========
export const authApi = {
  register: (data) => api.post('/auth/register', data),
  login: (data) => api.post('/auth/login', data),
  me: () => api.get('/auth/me'),
  updateProfile: (data) => api.put('/auth/profile', data),
  publicProfile: (id) => api.get(`/auth/users/${id}`)
}

// ========== 用户（头像） ==========
export const userApi = {
  uploadAvatar: (file) => {
    const fd = new FormData()
    fd.append('file', file)
    return api.post('/users/me/avatar', fd)
  },
  removeAvatar: () => api.delete('/users/me/avatar')
}

// ========== 板块 ==========
export const boardApi = {
  list: () => api.get('/boards'),
  get: (id) => api.get(`/boards/${id}`),
  create: (data) => api.post('/boards', data),
  update: (id, data) => api.put(`/boards/${id}`, data),
  remove: (id) => api.delete(`/boards/${id}`)
}

// ========== 帖子 ==========
export const postApi = {
  list: (params) => api.get('/posts', { params }),
  detail: (id) => api.get(`/posts/${id}`),
  mine: (params) => api.get('/posts/mine', { params }),
  create: (data) => api.post('/posts', data),
  update: (id, data) => api.put(`/posts/${id}`, data),
  remove: (id) => api.delete(`/posts/${id}`),
  pin: (id, pinned) => api.put(`/posts/${id}/pin`, null, { params: { pinned } }),
  exquisite: (id, exquisite) => api.put(`/posts/${id}/exquisite`, null, { params: { exquisite } }),
  like: (id) => api.post(`/posts/${id}/like`),
  favorite: (id) => api.post(`/posts/${id}/favorite`),
  myFavorites: (params) => api.get('/posts/favorites/mine', { params })
}

// ========== 标签 ==========
export const tagApi = {
  list: (params) => api.get('/tags', { params }),
  posts: (name, params) => api.get('/tags/posts', { params: { name, ...params } })
}

// ========== 评论 ==========
export const commentApi = {
  list: (postId, params) => api.get(`/posts/${postId}/comments`, { params }),
  create: (postId, data) => api.post(`/posts/${postId}/comments`, data),
  remove: (commentId) => api.delete(`/comments/${commentId}`)
}

// ========== 通知 ==========
export const notificationApi = {
  mine: (params) => api.get('/notifications', { params }),
  unreadCount: () => api.get('/notifications/unread-count'),
  markAllRead: () => api.post('/notifications/read-all'),
  markRead: (id) => api.post(`/notifications/${id}/read`)
}

// ========== 管理 ==========
export const adminApi = {
  users: (params) => api.get('/admin/users', { params }),
  changeStatus: (id, data) => api.put(`/admin/users/${id}/status`, data)
}

export default api
