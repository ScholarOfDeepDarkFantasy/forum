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

  it('先调用后端登出、后清本地态（顺序反转会让服务端收到已清掉的 token）', async () => {
    localStorage.setItem('jf_token', 'token-order')
    const store = useAuthStore()
    let tokenAtRequestTime = null
    // 在「请求发出」的那一刻读 localStorage：此时 token 必须还在
    authApi.logout.mockImplementationOnce(async () => {
      tokenAtRequestTime = localStorage.getItem('jf_token')
    })

    await store.logout()

    expect(tokenAtRequestTime).toBe('token-order')
    expect(localStorage.getItem('jf_token')).toBeNull()
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
