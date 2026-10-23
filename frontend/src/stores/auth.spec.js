import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { authApi } from '@/api'
import { useAuthStore } from './auth'

vi.mock('@/api', () => ({
  authApi: {
    logout: vi.fn()
  }
}))

/**
 * 前端登出：本地状态必须清干净，且后端调用失败不能阻断登出。
 */
describe('auth store 登出', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('调用后端登出接口并清空本地状态', async () => {
    localStorage.setItem('jf_token', 'token-1')
    localStorage.setItem('jf_user', JSON.stringify({ id: 1, username: 'alice' }))
    const store = useAuthStore()
    authApi.logout.mockResolvedValue(undefined)

    await store.logout()

    expect(authApi.logout).toHaveBeenCalledTimes(1)
    expect(store.token).toBe('')
    expect(store.user).toBeNull()
    expect(localStorage.getItem('jf_token')).toBeNull()
    expect(localStorage.getItem('jf_user')).toBeNull()
  })

  it('后端登出失败也清空本地状态', async () => {
    const store = useAuthStore()
    store.token = 'token-2'
    store.user = { id: 2, username: 'bob' }
    authApi.logout.mockRejectedValue(new Error('network down'))

    await expect(store.logout()).resolves.toBeUndefined()

    expect(store.token).toBe('')
    expect(store.user).toBeNull()
    expect(localStorage.getItem('jf_token')).toBeNull()
  })

  it('未登录时调用登出也不抛异常', async () => {
    const store = useAuthStore()
    authApi.logout.mockRejectedValue(new Error('401'))

    await expect(store.logout()).resolves.toBeUndefined()

    expect(store.token).toBe('')
    expect(store.user).toBeNull()
  })
})
