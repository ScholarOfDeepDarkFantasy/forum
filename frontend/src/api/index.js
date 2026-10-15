import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一 axios 实例：
 * - 请求拦截器自动附带 Bearer token
 * - 响应拦截器解包 Result<T>，业务失败时弹出错误并 reject
 * - 401 时清除登录态并跳转登录页
 */
const api = axios.create({
  baseURL: '/api',
  timeout: 15000
})

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('jf_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

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
    if (status === 401) {
      localStorage.removeItem('jf_token')
      localStorage.removeItem('jf_user')
      ElMessage.error('请先登录')
      if (!location.pathname.startsWith('/login')) {
        location.href = '/login'
      }
    } else {
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
  myFavorites: () => api.get('/posts/favorites/mine')
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
  markAllRead: () => api.post('/notifications/read-all')
}

// ========== 管理 ==========
export const adminApi = {
  users: (params) => api.get('/admin/users', { params }),
  changeStatus: (id, data) => api.put(`/admin/users/${id}/status`, data)
}

export default api
